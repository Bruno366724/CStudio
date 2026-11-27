# Tarea para CStudio: sistema de capas (para GitHub Copilot)

Lee primero `CONTEXTO_PROYECTO.md` y `TAREAS_COPILOT.md`. Las **reglas obligatorias de `TAREAS_COPILOT.md` siguen vigentes** (español, sin dependencias nuevas, cambios localizados, archivos Kotlin completos y compilables, disco fuera del hilo principal, no tragar `CancellationException`, mantener el manejo de `OutOfMemoryError`, no crear objetos en `onDraw` ni en `onTouchEvent`, no modificar el bitmap de la capa en cada evento del lápiz).

Este archivo es la **aprobación explícita del cambio de arquitectura de capas** que `CONTEXTO_PROYECTO.md` dejaba pendiente. Las restricciones de "Cosas que NO debes tocar" de `TAREAS_COPILOT.md` quedan levantadas **solo** para lo que esta tarea necesita (ver "Alcance permitido").

Fecha límite del proyecto: **2 de diciembre de 2026**. Si una fase resulta riesgosa para la fecha, di que conviene recortarla en vez de complicarla (en cada fase se indica qué se puede recortar).

---

## Objetivo

Capas como en una app de dibujo real:

- **Agregar** una capa (se inserta justo encima de la activa y queda seleccionada).
- **Eliminar** una capa (con confirmación; nunca se puede eliminar la última).
- **Mover** una capa hacia arriba o hacia abajo en el orden.
- **Seleccionar** la capa activa (necesario para poder usarlas).
- **Ocultar/mostrar** y **cambiar la opacidad** de cada capa (ya existen en `project.json` y están en el Bloque 1 del contexto).

Todo lo que existe hoy (pincel, borrador, deshacer/rehacer, limpiar, cuentagotas, zoom/pan, autoguardado, miniatura, exportación) debe funcionar con varias capas. **Un proyecto con una sola capa debe comportarse y verse exactamente igual que hoy.**

---

## Decisiones de diseño ya tomadas (no cambiarlas sin preguntar)

1. **Orden:** el índice 0 de la lista es la capa de abajo; el último es la de arriba. Es el mismo orden en que ya se guarda `layers` en `project.json`.
2. **Se dibuja solo en la capa activa.** Pincel y borrador actúan sobre esa capa. La opacidad del trazo (`BrushSettings.opacity` y alfa del color) sigue aplicándose al trazo; la opacidad de la capa se aplica **al componer**, nunca al pintar.
3. **Capa oculta = no editable.** Si la capa activa está oculta, no se inicia ningún trazo y se avisa con un Toast ("La capa está oculta."). Dibujar en algo que no ves es confuso.
4. **Limpiar** borra solo la capa activa.
5. **Cuentagotas** toma el color de lo que se ve (composición de capas visibles con su opacidad sobre papel blanco), no de la capa activa.
6. **Exportar y miniatura** usan la composición de las capas **visibles** con su opacidad, sobre transparente (la exportación) y sobre blanco (la miniatura, como hoy).
7. **Historial global** (un solo `History`, un solo límite de 20 entradas y 64 MB): cada entrada recuerda sobre qué capa actúa. **Agregar y mover capas no son deshacibles y no tocan el historial.** **Eliminar** una capa descarta del historial las entradas de esa capa (recicla sus bitmaps y ajusta `historyBytes`). Deshacer/rehacer actúan sobre la capa de la entrada, sea o no la activa.
8. **Nombres:** no hay renombrado. La interfaz muestra "Capa N", con N contado desde abajo (Capa 1 = la de abajo). **No se agrega un campo `nombre` a `project.json`.**
9. **Límites:** máximo `MAX_LAYERS = 20` capas y un presupuesto de memoria de `LAYER_MEMORY_BUDGET = 256 MB` para las capas vivas (ancho × alto × 4 bytes × cantidad). A 4096 × 4096 caben 4 capas; a 1920 × 1080 caben las 20. Al superarlo, Toast "No hay memoria para más capas en este lienzo." y no se agrega. Ambas constantes viven en un solo lugar (por ejemplo `CanvasDimensions.kt` o `LayerStack`) y son `internal`/sobrescribibles para pruebas.
10. **Formato de `project.json`: sin cambios.** Se sigue usando versión 2 con `id`, `archivo`, `visible` y `opacidad` por capa. El orden de la lista es el orden de apilado.
11. **Interfaz:** panel superpuesto (no un diálogo que tape el lienzo) con botones de texto en español, igual que el resto de la app. **No agregar librerías de íconos.** Mover con botones "Subir"/"Bajar"; arrastrar para reordenar queda fuera de alcance.

---

## Dónde está hoy el supuesto de "una sola capa" (hechos del código)

- `DrawingView.kt`: `layerBitmap`, `layerCanvas`, `history = History(layerBitmap)`, `onDraw`, `finishStroke`, `clear`, `replaceLayer`, `snapshotLayer`, `sampleColorAtViewPoint`.
- `History.kt`: `History(private val bitmap: Bitmap)` y un único `Canvas(bitmap)`; `capture(bounds)`, `commit(bounds, before)`, `clear()`.
- `MainActivity.kt`: `displayProject` lanza `IOException` si `project.layers.size != 1`; `saveCurrentProject` y `onPause` llaman `drawingView.snapshotLayer()` y `projectStorage.saveProject(metadata, listOf(snapshot))`; `exportDrawing` exporta `snapshotLayer()`; `navigateHome` reemplaza por una capa vacía de 64 × 64.
- `ProjectStorage.kt`: `createThumbnail(layers.first())`; `cleanupTemporaryFilesAndIncompleteProjects` y `readBackupOrRecover` asumen que existe `layer_0.png`; nunca se borran archivos de capas que ya no están en los metadatos.

---

## Alcance permitido

Puedes modificar: `DrawingView.kt`, `History.kt`, `StrokeRenderer.kt` (solo si es imprescindible), `MainActivity.kt`, `ProjectStorage.kt`, `ProjectMetadata.kt` (solo si hace falta, sin cambiar el formato), `ActionButtons.kt` y `ToolBar.kt` (solo para agregar el botón "Capas" y pasar un callback), y archivos nuevos (`drawing/LayerStack.kt`, `ui/LayersPanel.kt`, pruebas nuevas).

**No tocar:** el formato de `project.json` y su migración, `ImageExporter` (se le entrega un bitmap compuesto, él no cambia), la paleta de colores, el selector de color, `HomeScreen`, `NewProjectDialog`, `ExportDialog`, el cálculo del ancho del pincel (`widthFor`) ni nada de zoom/pan.

**Fuera de alcance (no implementar):** duplicar, renombrar o fusionar capas, modos de fusión, bloqueo de transparencia, miniaturas por capa, arrastrar para reordenar, deshacer agregar/mover/eliminar, animación. Mantén el diseño de manera que más adelante cada cuadro de animación pueda ser un conjunto de capas, pero **no** construyas nada de eso ahora.

---

## Cómo trabajar

1. Una **fase por vez**, en orden. Un commit por fase.
2. Después de cada fase ejecuta desde la raíz de `CStudio/`:
   1. `.\gradlew assembleDebug`
   2. `.\gradlew connectedDebugAndroidTest`
3. **Las pruebas existentes deben seguir pasando sin modificarlas** (usan `History(bitmap)`, `saveProject(metadata, listOf(bitmap))`, `replaceLayer(...)`, `snapshotLayer()`, `drawView(...)` con píxeles exactos). Si para mantenerlas hace falta conservar una firma antigua como sobrecarga o con parámetro por defecto, hazlo.
4. Si algo falla, pega el log completo y propone primero la corrección mínima.
5. Al terminar la última fase corre además `.\gradlew assembleRelease` (R8 nunca debe romper el arranque; recuerda que `packageScope` se limita a `com.example.cstudio.**`).
6. El dispositivo de prueba es un Moto G85 sin lápiz. No afirmes que algo quedó validado con el S Pen real.

---

## Fase 1: modelo de capas y renderizado

**Archivos:** nuevo `drawing/LayerStack.kt` (o equivalente), `drawing/DrawingView.kt`.

**Qué hacer.**

1. Crear una clase de capa con: `id: String`, `archivo: String`, `bitmap: Bitmap` (ARGB_8888 mutable), `visible: Boolean`, `opacity: Float`, y un contador de versión de píxeles (`pixelVersion: Long`) que sube cada vez que cambian los píxeles (trazo confirmado, deshacer, rehacer, limpiar). Se usará en la Fase 3 para el guardado incremental.
2. Crear `LayerStack` (o mantener la lista en `DrawingView`, pero preferible una clase aparte para que `DrawingView` no siga creciendo) con: lista de capas, índice activo, `add`, `remove`, `move(index, delta)`, `select`, `setVisible`, `setOpacity`, y las comprobaciones de límites (mínimo 1 capa, `MAX_LAYERS`, presupuesto de memoria).
3. En `DrawingView`:
   - Reemplazar `layerBitmap`/`layerCanvas` por la capa activa (mantener un `Canvas` por capa o crearlo al confirmar el trazo; **nunca** dentro de `onDraw`/`onTouchEvent`).
   - Nueva API pública (sugerida; ajusta nombres si hace falta):
     ```kotlin
     val layerCount: Int
     val activeLayerIndex: Int
     fun layerInfos(): List<LayerInfo>          // id, visible, opacity (sin bitmaps)
     fun addLayer(): Boolean                     // false si no cabe (límite o memoria)
     fun removeActiveLayer(): Boolean            // false si es la única
     fun moveLayer(index: Int, delta: Int)       // delta = +1 sube, -1 baja
     fun selectLayer(index: Int)
     fun setLayerVisible(index: Int, visible: Boolean)
     fun setLayerOpacity(index: Int, opacity: Float)
     var onLayersChanged: (() -> Unit)?          // estructura/visibilidad/opacidad/activa cambió
     var onBlockedDrawing: (() -> Unit)?         // se intentó dibujar en capa oculta
     ```
   - `replaceLayer(bitmap, takeOwnership)` se **conserva** (queda con una sola capa; las pruebas actuales la usan) y se agrega `replaceLayers(...)` para cargar varias capas con sus metadatos (`id`, `archivo`, `visible`, `opacidad`). `snapshotLayer()` se conserva y devuelve una copia de la **capa activa**.
   - Nuevas copias de bitmaps para otras fases: `snapshotComposite(): Bitmap` (capas visibles con su opacidad, sobre transparente, tamaño del lienzo) y un `internal fun snapshotLayerAt(index)` para pruebas.
4. `onDraw`:
   - Orden: fondo gris → papel blanco → capas visibles de abajo hacia arriba, cada una con `bitmapPaint.alpha = (opacity * 255)` (reutiliza el `Paint`, no crees uno nuevo por frame).
   - El **trazo en curso** se dibuja en la posición de la capa activa dentro de la pila: capas de abajo normales, capa activa con su trazo, capas de arriba normales.
   - Pincel con capa activa de opacidad < 1: dibuja capa activa + trazo dentro de un mismo `saveLayerAlpha` con la opacidad de la capa, para que la vista previa sea igual que el resultado confirmado.
   - Borrador: el `DST_OUT` debe afectar **solo a los píxeles de la capa activa**, no a las capas de abajo ni al papel. Hoy se hace un truco con el papel blanco dentro de `bounds`; con varias capas hay que rehacerlo (por ejemplo, `saveLayer` solo en `bounds` con la capa activa + trazo en `DST_OUT`, y la capa activa normal fuera de esos límites). Mantén el resultado idéntico al actual cuando hay una sola capa.
5. `finishStroke`: captura `before` de la capa activa, confirma el trazo en esa capa, sube su `pixelVersion`. Si la capa activa está oculta, `ACTION_DOWN` no inicia trazo (ver decisión 3) y llama `onBlockedDrawing`.
6. `clear()`, `undo()`, `redo()`: actúan sobre la capa correspondiente y suben su `pixelVersion`.
7. `sampleColorAtViewPoint`: componer en el píxel, de abajo hacia arriba, las capas visibles (cada una con su opacidad) sobre blanco. No debe asignar un bitmap completo.
8. Eliminar una capa: primero `cancelStroke()`, sacarla de la lista, descartar sus entradas del historial (Fase 2) y **solo después** `recycle()` de su bitmap. Todo en el hilo principal para que `onDraw` nunca vea un bitmap reciclado.

**Qué se puede recortar si falta tiempo:** nada de esta fase; es la base.

**Pruebas a agregar** (archivo nuevo `LayersInstrumentedTest.kt`, mismo estilo y helpers que `ExampleInstrumentedTest.kt`; copia el `dispatchTouch`/`drawView` privados o muévelos a un archivo de utilidades de test sin cambiar su comportamiento):

- Agregar capa: `layerCount == 2`, la activa es la nueva (la de arriba); un trazo cae **solo** en la nueva capa (comprobar con `snapshotLayerAt`).
- Mover capa: dibujar rojo en una capa y azul solapado en otra; el píxel compuesto por `drawView` cambia al mover una sobre la otra.
- Ocultar capa: desaparece de `drawView`; intentar dibujar en la capa activa oculta no crea píxeles y dispara `onBlockedDrawing`.
- Opacidad de capa 0.5: el píxel compuesto coincide con el esperado sobre papel blanco.
- Borrador sobre la capa activa no borra píxeles de la capa de abajo (vista previa y confirmado).
- Cuentagotas toma el color compuesto visible (con opacidad) y no el de la capa activa.
- No se puede eliminar la última capa; `addLayer()` devuelve `false` al superar `MAX_LAYERS` o el presupuesto de memoria.

---

## Fase 2: historial multicapa

**Archivos:** `drawing/History.kt`, `drawing/DrawingView.kt`.

**Problema.** `History` solo conoce un bitmap. Debe poder deshacer/rehacer en la capa de cada entrada y mantener el invariante de `TAREAS_COPILOT.md` (Tarea 4): `historyBytes` = suma de `byteSize` de todas las entradas de `undoStack` y `redoStack`.

**Qué hacer.**

1. Cada `HistoryEntry` guarda además el bitmap objetivo (la capa). `capture`, `commit` y `clear` reciben el bitmap objetivo; `undo`/`redo` usan el de la entrada. Conserva el constructor `History(bitmap)` y las firmas actuales como atajo para el bitmap "por defecto", para que las pruebas existentes pasen sin cambios.
2. Agregar `fun removeEntriesFor(target: Bitmap)`: recorre ambas pilas, resta `byteSize`, recicla `before`/`after` y quita las entradas de ese bitmap, manteniendo el invariante. Se llama al eliminar una capa, **antes** de reciclar su bitmap.
3. `undo()`/`redo()` deben informar a `DrawingView` sobre qué capa actuaron (valor de retorno o callback) para subir su `pixelVersion` y disparar `onStrokeCommitted`.
4. Revisar `trimHistory`, `clearRedoHistory` y `reset` con varios objetivos; un recorte no debe dejar bitmaps sin reciclar ni `historyBytes` inconsistente.
5. Mantener el comportamiento ante OOM: si falla la captura de `after`, devolver `false`, conservar la entrada y no perder el dibujo. `DrawingView.undo()` ya distingue "sin historial" (`canUndo`) de OOM; no lo rompas.

**Pruebas a agregar:**

- Dos capas con trazos en cada una: undo/redo restauran la capa correcta en el orden correcto, y `trackedBytes == liveEntryBytesForTesting` tras cada paso.
- Eliminar una capa descarta sus entradas: tras eliminar, `undo()` solo toca la otra capa, no hay excepciones y el invariante se mantiene.
- Mover una capa no invalida el historial: deshacer después de mover sigue restaurando la capa que corresponde.
- Las pruebas existentes de historial (`historyByteAccountingStaysCorrectAcrossUndoRedoCyclesAndReset`, `historyCopiesBoundsWhenCallerReusesRectAcrossStrokes`, `clearOnMaximumCanvasRemainsUndoableOverHistoryLimit`) pasan sin cambios.

---

## Fase 3: persistencia multicapa

**Archivos:** `storage/ProjectStorage.kt` (y `ProjectMetadata.kt` solo si es imprescindible, sin cambiar el formato).

**Qué hacer.**

1. **Nombres de archivo:** las capas nuevas usan `id = "layer-<uuid>"` y `archivo = "layer_<uuid>.png"` (únicos, no se reutilizan tras borrar). La capa inicial de un proyecto nuevo sigue siendo `layer-0` / `layer_0.png`.
2. **Orden de escritura (seguro ante cierres):** primero se escriben los PNG nuevos o modificados (atómico, como hoy), después `project.json` (atómico, con su `.bak`), y **al final** se borran los archivos `layer_*.png` del directorio que ya no están referenciados en `metadata.layers`. Si la app se cierra a la mitad, solo quedan archivos huérfanos que se limpian en el siguiente guardado.
3. **Guardado incremental:** nueva sobrecarga que reciba solo las capas cuyos píxeles cambiaron (por ejemplo `Map<String, Bitmap>` de id → copia) más la miniatura ya compuesta. Valida que toda capa de los metadatos o venga en el mapa o ya exista en disco. Conserva `saveProject(metadata, List<Bitmap>)` que escribe todas (lo usan las pruebas).
4. **Miniatura:** la compone `DrawingView` en el hilo principal a partir de las capas vivas visibles (un bitmap pequeño de 256 × 256, escalando cada capa al dibujarla). **No** se copian todas las capas solo para la miniatura. Mide el tiempo con un lienzo de 4096 × 4096 y varias capas y repórtalo; si pasa de ~30 ms, propón mitigación y espera la decisión antes de implementarla.
5. **Recuperación y limpieza:** `cleanupTemporaryFilesAndIncompleteProjects` debe considerar "tiene capa" si existe **cualquier** `layer_*.png` (hoy solo mira `layer_0.png` y borraría un proyecto cuya capa 0 fue eliminada). `readBackupOrRecover` debe reconstruir los metadatos mínimos con la primera capa `layer_*.png` disponible si no existe `layer_0.png`.
6. `loadProject` ya decodifica todas las capas de los metadatos; verifica que respeta `visible`, `opacidad` y el orden.

**Si se complica:** como recorte aceptable, el guardado puede escribir todas las capas cada vez (sin sobrecarga incremental), **avisándolo explícitamente**, siempre que se respete el presupuesto de memoria de la decisión 9. El resto de la fase (orden de escritura, huérfanos, recuperación) no se recorta.

**Pruebas a agregar:**

- Round-trip de un proyecto con 3 capas: orden, `visible`, `opacidad` y píxeles idénticos.
- Tras eliminar una capa y guardar, su PNG ya no existe en disco y los demás sí.
- Guardado incremental: una capa sin cambios no se reescribe (contenido y `lastModified` iguales) y una modificada sí.
- Recuperación: proyecto sin `project.json` ni `.bak` pero con `layer_<uuid>.png` (sin `layer_0.png`) se recupera y no es borrado por la limpieza inicial.
- Un proyecto v2 de una sola capa existente se carga y guarda igual que antes (pruebas actuales sin cambios).

---

## Fase 4: integración en `MainActivity`

**Archivo:** `MainActivity.kt`.

**Qué hacer.**

1. `displayProject`: quitar la excepción por `layers.size != 1`; cargar todas las capas con `replaceLayers`. Si falla la carga, reciclar lo que corresponda (mantén el patrón `ownershipTransferred`). Al abrir, la capa activa es la de arriba.
2. **Un solo punto de guardado** para `saveCurrentProject()` y `onPause()`: arma los metadatos desde el estado vivo (`currentProject.copy(layers = <metadatos de DrawingView>)`), toma en el hilo principal copias solo de las capas con cambios de píxeles y la miniatura compuesta, y escribe con `withContext(NonCancellable + Dispatchers.IO)` bajo el `Mutex`. Conserva exactamente la lógica actual de `dirty`/`changeVersion`, el manejo de `CancellationException` (relanzar antes de `catch (Exception)`) y de `OutOfMemoryError`. Después de un guardado exitoso se marcan como guardadas las versiones de píxeles que se escribieron (no las que cambiaron durante el guardado).
3. Cambios **solo de metadatos** (agregar/mover/eliminar capa, visibilidad, opacidad) también deben marcar el proyecto como `dirty` y programar el autoguardado, aunque no haya cambiado ningún píxel. Conecta `onLayersChanged` a eso.
4. `exportDrawing`: exportar `drawingView.snapshotComposite()` en lugar de `snapshotLayer()`. `ImageExporter` no se modifica; sigue reciclando el snapshot que recibe.
5. `navigateHome`: sigue reemplazando por una capa vacía de 64 × 64 con `replaceLayer`. Verifica que no queden bitmaps de capas sin reciclar.
6. Estado Compose: `MainActivity` guarda una lista inmutable de `LayerInfo` y el índice activo en `mutableStateOf`, actualizada desde `onLayersChanged`. **Los bitmaps nunca pasan a Compose.**
7. `onBlockedDrawing` → Toast "La capa está oculta.". Agregar y no poder agregar → Toast de la decisión 9.
8. El botón Atrás debe cerrar primero el panel de capas si está abierto (agrégalo al `OnBackPressedCallback` existente, respetando el orden actual de diálogos).

**Pruebas a agregar:**

- Exportación compuesta: `snapshotComposite()` con dos capas (una con opacidad 0.5) coincide píxel a píxel con lo esperado.
- Si hay forma razonable sin refactorizar: abrir un proyecto con varias capas y comprobar que `layerCount` coincide y que el estado `dirty` no se activa solo por abrirlo.

---

## Fase 5: interfaz (panel de capas)

**Archivos:** nuevo `ui/LayersPanel.kt`; cambios mínimos en `ActionButtons.kt`, `ToolBar.kt` y `MainActivity.kt`.

**Qué hacer.**

1. Botón **"Capas"** en `ActionButtons` (agrega un parámetro `onLayers` y pásalo desde `ToolBar`). No cambies nada más de esas filas.
2. `LayersPanel`: tarjeta superpuesta (`Card`/`Surface`) anclada a un costado dentro del mismo `Box` del lienzo, como ya se hace con el aviso del cuentagotas. **No debe cambiar el tamaño del `AndroidView` ni mover el lienzo.** Ancho acotado (~260–300 dp) y con scroll vertical si hay muchas capas.
3. Contenido: lista **de arriba hacia abajo** (la capa de arriba primero), cada fila con "Capa N", indicador de capa activa, y botón de ver/ocultar (texto: "Ver" / "Oculta"). Tocar la fila selecciona la capa. Debajo de la lista: control de **opacidad** (slider) de la capa activa y botones **Agregar**, **Subir**, **Bajar**, **Borrar**. Deshabilita "Subir" en la capa de arriba, "Bajar" en la de abajo y "Borrar" si solo hay una.
4. **Borrar** abre un `AlertDialog` de confirmación ("¿Borrar la capa N? Sus trazos dejarán de estar en el historial de deshacer.") como el de `HomeScreen`.
5. Objetivos táctiles de al menos 48 dp, `contentDescription`/semántica en español como en `ColorPalette`, y ningún texto fijo en inglés.
6. Los cambios del slider de opacidad se aplican en vivo (se ve el lienzo mientras se ajusta) y marcan el proyecto como cambiado; evita escribir a disco en cada movimiento del slider (el debouncer ya agrupa).

**Qué se puede recortar si falta tiempo:** el slider de opacidad y el botón de ver/ocultar (dejando la capa siempre visible con opacidad 1). Agregar, borrar, mover y seleccionar **no** se recortan.

---

## Fase 6: pruebas finales y documentación

1. Ejecutar `.\gradlew assembleDebug`, `.\gradlew testDebugUnitTest`, `.\gradlew connectedDebugAndroidTest` y `.\gradlew assembleRelease`. Reportar cantidad de pruebas y resultado.
2. Pruebas de rendimiento **solo medidas, sin cambiar nada sin aprobación**: tiempo de `onDraw` con 1, 5, 10 y 20 capas a 1920 × 1080, y con 4 capas a 4096 × 4096, mientras hay un trazo en curso. Si algún caso se acerca a ~12 ms en el Moto G85, reportarlo y proponer opciones (no implementarlas sin decisión).
3. Actualizar `CONTEXTO_PROYECTO.md` **solo en lo que corresponde**: marcar "Capas" como hecho en el Bloque 1, actualizar "Estructura actual del código" (archivos nuevos), las secciones de decisiones de diseño y de limitaciones (quitar los avisos de "solo una capa"; anotar los nuevos: sin renombrar, sin duplicar/fusionar, agregar/mover no deshacibles, eliminar descarta su historial, límites de capas/memoria) y los resultados de pruebas. No tocar otras secciones.
4. Agregar abajo un checklist final como el de `TAREAS_COPILOT.md`.

---

## Cosas a vigilar (riesgos conocidos)

- **Bitmaps reciclados en uso:** reciclar una capa mientras `onDraw` o un guardado la usan produce un crash. Todo cambio de la lista de capas ocurre en el hilo principal y los guardados trabajan sobre **copias** tomadas en el hilo principal.
- **Memoria:** cada capa a 4096 × 4096 pesa 64 MB. Por eso existe el presupuesto de la decisión 9 y el guardado incremental; no copies todas las capas para guardar ni para la miniatura.
- **Carpetas de proyecto "incompletas":** la limpieza inicial borra directorios sin metadatos, respaldo ni capa; si no se actualiza para reconocer `layer_<uuid>.png`, podría borrar un proyecto válido (ver Fase 3, punto 5).
- **Compatibilidad hacia atrás:** los proyectos guardados con una capa (`layer_0.png`) deben abrir y guardar sin cambios visibles.
- **Esta app no valida S Pen:** la presión real y el rechazo de palma con varias capas se prueban a mano en la tableta Samsung; no afirmes lo contrario.

## Checklist final

- [x] Fase 1: modelo de capas y renderizado (agregar, seleccionar, mover, ocultar, opacidad, borrador por capa, cuentagotas compuesto)
- [x] Fase 2: historial multicapa con invariante de `historyBytes` y descarte al eliminar una capa
- [x] Fase 3: persistencia multicapa (orden de escritura, huérfanos, guardado incremental, recuperación y limpieza)
- [x] Fase 4: `MainActivity` (carga de varias capas, guardado único, exportación compuesta, estado Compose)
- [x] Fase 5: panel de capas (agregar, borrar con confirmación, subir/bajar, seleccionar, ver/ocultar, opacidad)
- [ ] Fase 6: pruebas, `assembleRelease`, mediciones y `CONTEXTO_PROYECTO.md` actualizado (parcial: sigue pendiente medir `onDraw` en el Moto G85)
- [x] `.\gradlew assembleDebug` y `.\gradlew assembleRelease` exitosos
- [x] `.\gradlew testDebugUnitTest` exitoso (9 pruebas); `LayersInstrumentedTest` exitoso (13 pruebas en API 37)
- [x] Medición de miniatura a 4096 × 4096 con 2 capas: 9.176 ms en emulador API 37
- [ ] Suite completa `.\gradlew connectedDebugAndroidTest`: 50 pruebas, 43 pasan; 7 fallan por `NoSuchMethodException: InputManager.getInstance` al inicializar Espresso 3.5.1 en API 37
- [ ] Medición de `onDraw` con 1, 5, 10 y 20 capas a 1920 × 1080 y 4 capas a 4096 × 4096 en el Moto G85
- [ ] Validación manual con S Pen real en la tableta Samsung (pendiente, la hace el usuario)
