# Tareas de corrección para CStudio (para GitHub Copilot)

Lee primero `CONTEXTO_PROYECTO.md` (está fuera del repositorio; si no lo tienes, pídeselo al usuario). Estas tareas salen de una revisión de código **sin ejecutar nada**, así que cada una debe verificarse con pruebas antes de darla por cerrada.

## Reglas obligatorias (resumen del contexto)

- Responde en español. El usuario viene de C#, explica los conceptos de Android comparándolos cuando ayude.
- **No agregues librerías ni dependencias.**
- Cambios pequeños y localizados. **No refactorices** código que no tenga que ver con la tarea.
- Entrega archivos Kotlin completos y compilables, sin `...`.
- Todo acceso pesado a disco va fuera del hilo principal.
- No tragues `CancellationException`: relánzala antes de cualquier `catch (e: Exception)`. Conserva también el manejo de `OutOfMemoryError`.
- No crees objetos dentro de `onDraw` ni de `onTouchEvent`.
- Fecha límite del proyecto: **2 de diciembre de 2026**. Si una tarea es riesgosa para la fecha, di que conviene recortarla en vez de complicarla.

## Cómo trabajar

1. Haz **una tarea por vez**, en el orden de abajo. Un commit por tarea.
2. Después de cada tarea ejecuta, desde la raíz de `CStudio/`:
   1. `.\gradlew assembleDebug`
   2. `.\gradlew connectedDebugAndroidTest`
3. Si algo falla, pega el log completo y propone primero la corrección mínima.
4. Marca la tarea como hecha al final de este archivo solo cuando ambos comandos pasen.
5. El dispositivo de prueba es un Moto G85 **sin lápiz**: la parte de S Pen se valida con pruebas simuladas y luego a mano en la tableta Samsung. No afirmes que quedó validada con el lápiz real.

---

## Tarea 1: Seguir el puntero correcto del lápiz (prioridad alta)

**Archivo:** `drawing/DrawingView.kt`, método `onTouchEvent`.

**Problema.** El trazo en curso no recuerda de qué puntero viene. En `ACTION_MOVE` se usan `event.x`, `event.y`, `getHistoricalX(i)`, `getHistoricalY(i)` y `getHistoricalPressure(i)`, que son siempre del puntero 0. Escenario real en la tableta: la palma toca primero (puntero 0, `TOOL_TYPE_FINGER`) y el S Pen entra después como `ACTION_POINTER_DOWN` (puntero 1). El trazo se inicia con el lápiz, pero los siguientes `MOVE` leen las coordenadas de la palma.

Otros síntomas relacionados:
- `isStylus` se calcula con `event.actionIndex`, que en `ACTION_MOVE` vale 0. Por eso, durante el movimiento, la presión se interpreta según el tipo del puntero 0 y no según el del lápiz.
- `ACTION_POINTER_UP` no confirma el trazo cuando el puntero que se levanta es el del lápiz: el trazo queda "colgado" hasta que se levante la palma.
- En `ACTION_DOWN`, la línea `if (!isStylus && activeStylus) return true` nunca se cumple, porque justo antes se asigna `activeStylus = isStylus`. Es código muerto; elimínalo o corrígelo con cuidado de no cambiar el comportamiento de los tests actuales.

**Qué hacer.**

1. Agregar `private var activePointerId = MotionEvent.INVALID_POINTER_ID`.
2. Asignarlo al empezar un trazo:
   - en `ACTION_DOWN`: `event.getPointerId(0)` (y `INVALID_POINTER_ID` si el toque cae fuera del lienzo);
   - en el `startStroke` de `ACTION_POINTER_DOWN` con lápiz: `event.getPointerId(actionIndex)`.
3. Resetearlo a `INVALID_POINTER_ID` en `finishStroke()` y `cancelStroke()`.
4. En `ACTION_MOVE`, dentro del trazo, resolver el índice con `event.findPointerIndex(activePointerId)`; si da `< 0`, ignorar el evento. Usar ese índice en `getHistoricalX(index, i)`, `getHistoricalY(index, i)`, `getHistoricalPressure(index, i)`, `getX(index)`, `getY(index)`, `getPressure(index)`. Calcular `isStylus` con `event.getToolType(index)`.
5. En `ACTION_UP`, usar el mismo índice (con respaldo al 0 si no se encuentra).
6. En `ACTION_POINTER_UP`: si `event.getPointerId(actionIndex) == activePointerId` y `isDrawing`, agregar el punto final de ese puntero y llamar a `finishStroke()`. Mantener el resto del comportamiento actual (`activeStylus`, `isTransforming`).
7. Mantener lo que ya funciona: un trazo iniciado con un dedo se cancela cuando entra el lápiz (`strokeStartedWithFinger`), y mientras hay un lápiz activo se ignoran los dedos adicionales.

**Pruebas a agregar** en `ExampleInstrumentedTest.kt` (el helper `dispatchTouch` actual solo simula `TOOL_TYPE_FINGER` con ids 0..n; crea un **overload** que reciba `toolTypes: IntArray` e `ids: IntArray` sin romper las llamadas existentes):

- Palma + lápiz: puntero 0 finger en una zona, `ACTION_POINTER_DOWN` con puntero 1 stylus en otra, varios `ACTION_MOVE` moviendo ambos. Comprobar que los píxeles aparecen en la ruta del lápiz y **no** en la de la palma.
- Levantar el lápiz con la palma aún apoyada (`ACTION_POINTER_UP` del puntero del lápiz): el trazo debe quedar confirmado en el bitmap y existir una entrada de historial (comprobable con `undo()`).
- Las pruebas existentes (`pinchAndPan...`, `strokesStayInsideTheCanvas...`, `undoRedoAndClear...`) deben seguir pasando sin cambios.

---

## Tarea 2: "Memoria insuficiente" falso al deshacer sin historial

**Archivos:** `drawing/DrawingView.kt` (`undo()`), `drawing/History.kt`.

**Problema.** `History.undo()` devuelve `false` tanto cuando no hay nada que deshacer como cuando falla por `OutOfMemoryError`. `DrawingView.undo()` trata todo `false` como falta de memoria y llama a `onMemoryError`, así que pulsar "Deshacer" con el historial vacío muestra el aviso "Memoria insuficiente para este tamaño."

**Qué hacer.**

1. En `History` agregar `val canUndo: Boolean get() = undoStack.isNotEmpty()` (y, por simetría, `canRedo`).
2. En `DrawingView.undo()`: después de `cancelStroke()`, si `!history.canUndo`, hacer `return` sin invocar nada. Mantener `onMemoryError` solo para el caso real de OOM.

**Prueba a agregar:** con un `DrawingView` recién creado, asignar `onMemoryError` a un contador, llamar `undo()` y comprobar que el contador sigue en 0 y que el bitmap no cambió.

---

## Tarea 3: Falso error al autoguardar (`CancellationException`)

**Archivos:** `storage/AutoSaveDebouncer.kt`, `MainActivity.kt`.

**Problema.** `AutoSaveDebouncer.schedule()` hace `pendingJob?.cancel()`. Pero `pendingJob` es el mismo job que, tras el `delay`, ejecuta `action()` (el guardado). Si el usuario termina un trazo mientras se está guardando, `schedule()` cancela el guardado en curso. La `CancellationException` sube por `saveCurrentProject()` y la captura el `catch (exception: Exception)`, que marca `dirty = true` y muestra el aviso "No se pudo guardar el proyecto." sin que haya ningún error real. Esto es más probable en lienzos grandes, donde guardar tarda más. Además contradice la regla del proyecto de conservar `CancellationException`.

**Qué hacer.**

1. En `AutoSaveDebouncer`, separar la fase de espera de la fase de guardado. Comportamiento deseado:
   - `schedule()` mientras **espera**: reinicia el temporizador (como ahora).
   - `schedule()` mientras **se está guardando**: no cancela el guardado; marca que hace falta otro guardado y lo programa cuando termine el actual.
   - `cancel()` no interrumpe un guardado en curso; solo anula la espera pendiente y cualquier guardado reprogramado.
   - Mantener `cancelAndJoin()` y la firma pública usada por el test `autosaveDebounceWaitsUntilChangesStop` (que debe seguir pasando sin cambios).

   Esquema orientativo (todo corre en el hilo principal con `lifecycleScope`, no hace falta sincronización extra):

   ```kotlin
   private var pendingJob: Job? = null
   private var running = false
   private var rerun = false

   fun schedule() {
       if (running) { rerun = true; return }
       pendingJob?.cancel()
       pendingJob = scope.launch {
           delay(delayMillis)
           running = true
           try { action() } finally { running = false }
           if (rerun) { rerun = false; schedule() }
       }
   }

   fun cancel() {
       rerun = false
       if (!running) { pendingJob?.cancel(); pendingJob = null }
   }
   ```

   Revisa que `cancelAndJoin()` siga siendo coherente con este esquema.

2. En `MainActivity.kt`, en **todas** las funciones que tienen `catch (exception: Exception)` (`saveCurrentProject`, `createNewProject`, `openProject`, `renameProject`, `deleteProject`, `exportDrawing`), agregar antes de ese catch:

   ```kotlin
   } catch (exception: CancellationException) {
       throw exception
   }
   ```

   En `saveCurrentProject()`, si se cancela, no debe mostrar error ni dejar `dirty` en un estado incorrecto: si el guardado en disco sí terminó, `dirty` debe quedar en `false`; si no, en `true`.

3. En `saveCurrentProject()`, ejecutar la escritura con `withContext(NonCancellable + Dispatchers.IO)` igual que ya hace `onPause`, para que un guardado iniciado termine aunque se cancele el scope.

**Pruebas a agregar** (junto a `autosaveDebounceWaitsUntilChangesStop`):

- Una `schedule()` llamada mientras `action` está en ejecución **no** cancela esa ejecución (la acción termina con éxito) y provoca exactamente un guardado adicional después.
- `cancel()` durante la espera impide que el guardado ocurra.

---

## Tarea 4: Contabilidad incorrecta de `historyBytes`

**Archivo:** `drawing/History.kt`.

**Problema.** En `undo()` se hace `entry.after = null` y **después** `historyBytes -= entry.byteSize` (que ya no cuenta el `after`), y luego se suma solo el tamaño del nuevo `after`. Con ciclos undo → redo → undo el contador deja de reflejar la memoria real y el límite de 64 MB se alcanza antes de lo debido. No hay crash, pero el historial se recorta de más. El propio `CONTEXTO_PROYECTO.md` ya marca esto como pendiente de revisar.

**Invariante que debe cumplirse siempre:** `historyBytes` es igual a la suma de `byteSize` de todas las entradas en `undoStack` y `redoStack`.

**Qué hacer.**

1. Reescribir `undo()` para que mantenga el invariante: restar `entry.byteSize` **antes** de reciclar/anular `after`, y sumar el `byteSize` real al terminar (con `after` nuevo, o solo con `before` si falla por OOM).
2. Revisar `redo()`, `clearRedoHistory()`, `trimHistory()` y `reset()` bajo el mismo invariante.
3. Mantener el comportamiento ante OOM: si no se puede capturar el estado posterior, devolver `false`, conservar la entrada en `undoStack` y no perder el dibujo.
4. Agregar una propiedad `internal` solo para pruebas (por ejemplo `internal val trackedBytes: Long`) o equivalente, **sin** cambiar la API pública.

**Pruebas a agregar:**

- Secuencia undo → redo → undo → redo: tras cada paso, `trackedBytes` debe coincidir con la suma real de `before.byteCount + (after?.byteCount ?: 0)` de las entradas vivas (puedes calcularla con un helper `internal` de prueba).
- Tras `reset()`, `trackedBytes == 0`.
- Mantener `clearOnMaximumCanvasRemainsUndoableOverHistoryLimit` pasando.

---

## Tarea 5: Compilar y probar el release (R8) ahora, no el último día

**Archivos:** `app/build.gradle.kts`, `app/src/main/keepRules/rules.keep`.

El build release tiene `optimization { enable = true }` (R8) y **nunca se ha compilado**.

**Qué hacer.**

1. Ejecutar `.\gradlew assembleRelease` y reportar el resultado completo (errores y warnings de R8).
2. Revisar si R8 puede romper algo: la app usa `org.json`, `MediaStore`, `PdfDocument` y Compose, todos del framework o de AndroidX con sus propias reglas, así que lo esperable es que no haga falta nada. Si aparece algún warning de clase faltante, propone la regla mínima en `rules.keep` y explica por qué.
3. **No inventes ni escribas contraseñas, keystores ni rutas de firma en archivos versionados.** El `.gitignore` ya excluye `*.jks` y `*.keystore`. La firma la configura el usuario; limítate a explicar los pasos en Android Studio (Build > Generate Signed App Bundle / APK) o a proponer un bloque `signingConfigs` que lea los datos desde `local.properties` (que también está ignorado).
4. Recordar al usuario que un APK firmado en release **no se instala encima** de uno debug (la firma es distinta): hay que desinstalar el debug primero, y eso borra los proyectos guardados en esa instalación.

---

## Tareas opcionales: medir antes de tocar

Estas dos **no se cambian sin medir primero** y no son prioritarias para el 2 de diciembre.

### Opcional A: trazos muy largos se vuelven pesados en `onDraw`

En cada frame de un trazo en curso, `StrokeRenderer.replayStroke` reconstruye y dibuja **todos** los segmentos. Para trazos normales es aceptable; un trazo larguísimo cuesta cada vez más por frame.

- Primero medir: una prueba o log que registre el tiempo de `onDraw` con 500, 2 000 y 5 000 puntos.
- Solo si el tiempo se acerca a ~12 ms en el Moto G85, proponer una mitigación que **respete la regla de no modificar el bitmap de la capa en cada evento**. Describe las opciones y sus riesgos y espera la decisión del usuario antes de implementarla.

### Opcional B: copia del bitmap en el hilo principal al pausar

`onPause` llama a `snapshotLayer()` en el hilo principal; en 4096 × 4096 son 64 MB de copia. Es difícil de evitar sin riesgo de inconsistencia, y el PNG ya se escribe fuera del hilo principal. Solo medir el tiempo de `snapshotLayer()` en lienzo 4096 × 4096 y reportarlo. No cambiar nada sin aprobación.

---

## Fuera de alcance: requiere decisión del usuario

**Tamaño del pincel y zoom.** `widthFor()` calcula el ancho como `sizeDp * density / viewport.fitScale`. Eso fija el tamaño en píxeles de **lienzo**: al acercar el zoom, el trazo se ve más grueso en pantalla. Muchas apps de dibujo funcionan así, pero otras mantienen el grosor constante en pantalla. Es una decisión de diseño de la artista/usuario, **no la cambies**. Si el usuario lo pide, propón primero las dos alternativas y su impacto en `StrokeRenderer`, el historial y el guardado.

---

## Cosas que NO debes tocar en estas tareas

- Formato de `project.json` y su migración.
- Sistema de capas (todavía no existe en la UI; es un cambio de arquitectura que el usuario debe aprobar antes).
- `ImageExporter` y el flujo de exportación.
- La paleta, la barra de herramientas y las pantallas de Compose.

## Checklist final

- [x] Tarea 1: puntero activo del lápiz y pruebas de palma + lápiz
- [x] Tarea 2: deshacer sin historial ya no muestra "Memoria insuficiente"
- [x] Tarea 3: autoguardado sin falsos errores, `CancellationException` relanzada
- [ ] Tarea 4: `historyBytes` consistente con el invariante
- [ ] Tarea 5: `assembleRelease` compila y se reportó el resultado
- [ ] `.\gradlew assembleDebug` exitoso
- [ ] `.\gradlew connectedDebugAndroidTest` exitoso (todas las pruebas, incluidas las nuevas)
- [ ] Validación manual con S Pen real en la tableta Samsung (pendiente, la hace el usuario)
