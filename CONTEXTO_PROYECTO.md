# Contexto del proyecto: app de dibujo para Android (Kotlin)

Este archivo está pensado para entregarle a Claude u otra IA junto con el repositorio. Describe el objetivo, las decisiones que ya están implementadas y el trabajo que todavía falta.

## Cómo usar este contexto

- Responde en español.
- Explica conceptos de Android comparándolos con C# cuando sea útil.
- Antes de proponer cambios, distingue entre hechos comprobados, pendientes de validar y decisiones que todavía requieren la aprobación del usuario.
- No agregues librerías ni dependencias sin preguntar.
- Implementa cambios pequeños y localizados. No refactorices áreas no relacionadas.
- En archivos Kotlin, preferí código completo y compilable.
- Ejecutá primero el flujo de validación del proyecto:
  1. `.\gradlew assembleDebug`
  2. `.\gradlew connectedDebugAndroidTest`
- En Android, todo el acceso a disco pesado debe ejecutarse fuera del hilo principal.
- No cierres corrutinas con capturas parciales: conserva `CancellationException`, captura `Exception` y `OutOfMemoryError` donde corresponda.

## Qué estoy construyendo
App de dibujo simple para Android, al estilo de Ibis Paint pero mucho más básica. Es un regalo de mesiversario para mi novia (artista y animadora) y tiene que poder usarla en su tableta Samsung con S Pen. **Fecha límite: 2 de diciembre de 2026.**

Más adelante quiero agregarle animación cuadro a cuadro (línea de tiempo, onion skin), así que la arquitectura no debe cerrar esa puerta.

Requisitos de fondo: gratis, rápida, sin anuncios y sin necesidad de internet.

## Quién soy
Estudiante de sistemas. Sé C#, POO y SQL. Soy nuevo en Kotlin y en Android. Cuando me ayudes:
- Explicá los conceptos de Android comparándolos con C# cuando sirva.
- Dame código completo que compile, no fragmentos con "..." (salvo que sea muy corto).

## Stack y entorno
- Lenguaje: Kotlin (no Java).
- IDE: Android Studio en Windows.
- UI: Jetpack Compose para barras de herramientas, selector de color y controles.
- Lienzo: `View` personalizada clásica (`DrawingView`) incrustada con `AndroidView`. NO usar el Canvas de Compose para el lienzo.
- minSdk 26, compileSdk 37, targetSdk 37.
- Sin librerías pesadas de terceros. Si hace falta alguna, preguntame antes.
- Dispositivos: celular Motorola G85 (Android, sin lápiz) conectado por USB para pruebas; destino final: tableta Samsung Android con S Pen.

## Estado técnico resumido

- Proyecto Gradle Android con Kotlin y Compose.
- `namespace`/`applicationId`: `com.example.cstudio`.
- `compileSdk`/`targetSdk`: 37.
- `minSdk`: 26.
- `versionCode`: 1.
- `versionName`: 1.0.
- El APK debug se genera en `CStudio/app/build/outputs/apk/debug/app-debug.apk`.
- Las pruebas instrumentadas usan `AndroidJUnit4`, `androidx.test` y Compose test.
- El respaldo automático está deshabilitado para no copiar los proyectos privados de `filesDir/projects`.
- `MainActivity` detecta cambios de orientación/redimensionado con `configChanges` para no recrearla innecesariamente.

## Arquitectura de ejecución

La app tiene una única `MainActivity` y dos pantallas principales controladas por estado Compose:

1. `HOME`: galería de proyectos.
2. `EDITOR`: barra de herramientas, `AndroidView` con `DrawingView` y diálogo de exportación.

`MainActivity` funciona como coordinador:

- Guarda `currentProject`, `projectReady`, `dirty`, `isBusy` y la pantalla actual.
- Inicia/pausa el autosave.
- Guarda, carga, crea, abre, renombra y elimina proyectos bajo un `Mutex` común.
- Usa `Dispatchers.IO` para operaciones de disco.
- Usa `NonCancellable + Dispatchers.IO` para guardados y exportaciones que deben terminar aunque la Activity pierda el ciclo de vida.
- Rehabilita o bloquea la entrada del lienzo durante carga, guardado y navegación.

## Flujo del lienzo

El contenido dibujado vive en un `Bitmap` ARGB_8888 mutable administrado por `DrawingView`.

- Un trazo activo no se modifica directamente en el bitmap de cada evento.
- Los puntos se guardan en arrays reutilizados y se convierten en curvas cuadráticas al representar la vista.
- Al soltar el lápiz o el dedo, `finishStroke()` captura el rectángulo afectado, dibuja el trazo en el bitmap una sola vez y crea una entrada de historial.
- El borrador usa `PorterDuff.Mode.DST_OUT`.
- La opacidad se aplica al pincel; el borrador no debe heredarla sin una decisión explícita de diseño.
- `onDraw()` dibuja el fondo, el papel blanco, el bitmap confirmado y la vista previa del trazo activo.
- `invalidate()` solicita el redibujado completo; no se usa `invalidateRect`, porque Android moderno no garantiza esa optimización.

## Gestos y entrada

`DrawingView.onTouchEvent()` distingue la entrada por `MotionEvent`:

- `TOOL_TYPE_STYLUS`: dibuja.
- `TOOL_TYPE_FINGER`: inicia gestos de dos dedos.
- Se usan puntos históricos y presión donde están disponibles.
- Mientras existe un lápiz activo se intenta ignorar los punteros de dedo adicionales.
- `ACTION_CANCEL` cancela el trazo activo.
- `undo()`, `redo()` y `clear()` cancelan primero un trazo en curso.
- `inputEnabled = false` descarta entradas táctiles mientras se carga o se guarda.

`Viewport` administra una `Matrix` de escala y desplazamiento:

- Convierte coordenadas de pantalla a coordenadas del lienzo.
- Ajusta la vista con `fitScale`.
- Permite zoom mínimo de 0.5 × el ajuste y un máximo de al menos 800 %.
- Limita el pan para mantener una parte visible del lienzo.
- Conserva o adapta relativamente zoom/pan al cambiar el tamaño de la vista.

## Pinceles

- `Brush.kt`: datos mínimos del pincel.
- `Brushes.kt`: pincel normal y borrador.
- `BrushSettings.kt`: color, tamaño, opacidad y pincel seleccionado.
- `StrokeRenderer.kt`: presión, curvas Bézier, opacidad y borrado.
- El ancho se expresa actualmente como `dp × density / fitScale`; esto hace que el tamaño en píxeles de lienzo dependa de la pantalla y del zoom.

## Historial

`History` guarda como unidad acotada el rectángulo afectado por cada trazo.

- Máximo configurado: 20 entradas.
- Límite de memoria: 64 MB.
- Al confirmar un trazo se conserva `before`.
- Al deshacer se captura `after` justo antes de restaurar `before`.
- Al rehacer se restaura `after`.
- Si falta memoria al capturar el estado posterior, se informa `onMemoryError` y se conserva la entrada sin perder el dibujo.
- `clear()` crea una entrada especial que borra el lienzo completo.

Una revisión futura debería volver a verificar los cálculos de `historyBytes` en `undo()` y el tratamiento de OOM, aunque las pruebas existentes pasan.

## Persistencia

Los proyectos viven en:

`filesDir/projects/<uuid>/`

Contenido típico:

- `project.json`: metadatos versionados.
- `project.json.bak`: copia de respaldo del JSON.
- `layer_0.png`: capa actual.
- `thumbnail.png`: miniatura cuadrada de 256 × 256.
- Archivos temporales `*.tmp` durante escrituras.

Formato JSON:

- Versión 1: un único `canvasSize`.
- Versión 2: `canvasWidth` y `canvasHeight` separados.
- La lista `layers` ya existe y es extensible, aunque la UI/editor actual solo soporta una capa.

`ProjectStorage`:

- Escribe capas y metadatos de forma atómica.
- Decodifica las capas como bitmaps mutables ARGB_8888.
- Crea una miniatura con proporción contenida y barras blancas.
- Conserva una copia `.bak` antes de reemplazar `project.json`.
- Si el JSON falla, intenta usar el respaldo.
- Si no hay respaldo usable pero existe `layer_0.png`, reconstruye metadatos mínimos con nombre "Recuperado".
- Conserva proyectos con versión mayor que 2 sin modificarlos.
- Limpia temporales y carpetas incompletas al iniciar.

La bandera `dirty` evita reescribir el proyecto si no hubo cambios. Se activa al confirmar trazos, deshacer, rehacer o limpiar. Solo se desactiva después de un guardado exitoso.

## Funciones planificadas (por prioridad)
Para la entrega del 2 de diciembre el objetivo es completar el Bloque 1; el Bloque 2 solo si sobra tiempo. Todo lo demás queda para versiones posteriores. Marcá con [x] lo que ya esté hecho.

Antes de implementar una función que afecte la arquitectura (capas, zoom, animación), avisame para no tener que rehacer código después.

### Bloque 1: imprescindibles
- [x] Deshacer/rehacer (cada trazo ya es una unidad; límite de ~20 pasos)
- [x] Zoom y pan con dos dedos (`Matrix`)
- [x] Autoguardado y reabrir el proyecto
- [x] Exportar PNG/JPG/WEBP/PDF con MediaStore
- [ ] Capas: crear, ocultar, ordenar y cambiar opacidad (es lo más grande del bloque; si falta tiempo, pasa al Bloque 2)
- [x] Pantalla de inicio/galería interna de proyectos
- [x] Tamaños de lienzo predefinidos y personalizados hasta 4096 × 4096

### Bloque 2: muy valiosas (estilo Ibis Paint)
- [ ] Selector de color completo (rueda o cuadro HSV, colores recientes y paleta guardada)
- [ ] Cuentagotas (tocar el lienzo para tomar un color)
- [ ] Estabilizador de trazo (suaviza el pulso)
- [ ] Balde de relleno (con tolerancia; cuidar el rendimiento)
- [ ] Formas y líneas rectas (línea, círculo, rectángulo)
- [ ] Selección y transformar (mover, escalar, rotar una parte del dibujo)
- [ ] Modos de fusión de capas y bloqueo de transparencia
- [ ] Distintos pinceles (lápiz, tinta, acuarela, aerógrafo) y guardar ajustes de pincel
- [ ] Simetría (espejo vertical)

### Bloque 3: específicas del S Pen
- [ ] Botón del lápiz como borrador o cuentagotas
- [ ] Ajuste de la curva de presión
- [ ] Rechazo de palma más fino
- [ ] Predicción de trazo (`androidx.input:input-motionprediction` y `androidx.graphics`) si en la tableta se nota latencia

### Bloque 4: animación (segunda etapa)
- [ ] Línea de tiempo con cuadros, onion skin y reproducción con FPS configurable
- [ ] Duplicar, mover y borrar cuadros
- [ ] Exportar GIF, secuencia PNG y video

### Bloque 5: detalles personales (suman mucho al regalo)
- [ ] Pantalla de bienvenida con una dedicatoria o fecha especial
- [ ] Ícono y nombre propios de la app
- [ ] Mensaje escondido que aparezca al cumplir un hito (por ejemplo, al exportar el primer dibujo)
- [ ] Lienzos de tamaños predefinidos (cuadrado, vertical, 16:9) y papel de colores o cuadriculado

## Decisiones de diseño
- Cada capa es un `Bitmap` ARGB_8888. El trazo en curso se guarda como lista de puntos (x, y, ancho) y se redibuja en `onDraw` (acelerado por GPU, con `saveLayerAlpha` para aplicar la opacidad al trazo completo); al soltar el lápiz se "confirma" pintándolo una sola vez en el bitmap de la capa activa. El borrador usa `PorterDuff.Mode.DST_OUT`.
- Tamaño inicial predeterminado del lienzo: 1920 × 1080; cada proyecto admite dimensiones de 64 a 4096 px por lado.
- Entrada del lápiz: `MotionEvent.getPressure()`, `getHistoricalX/Y` (puntos intermedios) y `getToolType()`.
  - `TOOL_TYPE_STYLUS` dibuja.
  - Dos dedos (`TOOL_TYPE_FINGER`) hacen pan y zoom.
  - Cuidar `ACTION_CANCEL` por apoyo de la palma.
- Suavizado de trazos con curvas Bézier cuadráticas entre puntos.
- Deshacer/rehacer: máximo 20 pasos y 64 MB. Cada entrada guarda el estado anterior y captura el estado posterior al deshacer.
- Zoom y pan con `Matrix`.
- Guardado: almacenamiento interno de la app. Exportar PNG, JPG, WEBP y PDF con `MediaStore`.
- Los proyectos viven en `filesDir/projects/<uuid>` con metadatos JSON versionados, capas PNG ARGB_8888 y miniaturas de 256×256. El autosave usa debounce de 2 segundos y también guarda al pausar la Activity.
- El JSON ya contiene una lista de capas extensible; por ahora la interfaz solo abre y edita proyectos de una capa.
- Rendimiento: no crear objetos dentro de `onDraw` ni de `onTouchEvent` (reutilizar `Path` y `Paint`). El `Bitmap` de la capa solo se modifica al terminar cada trazo, porque cada cambio obliga a subirlo completo a la GPU (no modificarlo en cada evento del lápiz). Usar `invalidate()` simple: el rectángulo sucio (`invalidate(l, t, r, b)`) está deprecado y Android moderno lo ignora.

## Cómo quiero que me ayudes (modo emergencia)
1. Prioridad: que funcione antes de que sea perfecto. Hay fecha límite.
2. Cuando te pegue un error, pedime el log completo (Build output o Logcat) si no lo viste, y dame primero la corrección mínima.
3. Cambios chicos y localizados. No refactorices código que no tenga que ver con el problema.
4. Si algo es riesgoso para la fecha, proponé recortar alcance en vez de complicar.
5. Respondé en español, claro y directo.

## Comandos útiles
Desde la terminal de Android Studio, en la raíz del proyecto:
- `adb devices` — ver si el celular está detectado.
- `.\gradlew assembleDebug` — generar el APK de prueba.
- APK generado en: `app\build\outputs\apk\debug\app-debug.apk`
- `adb install -r app\build\outputs\apk\debug\app-debug.apk` — instalar o actualizar en el dispositivo.
- `adb logcat` — ver logs en vivo.

## Problemas comunes
- **Gradle sync falla:** revisar la conexión, el JDK de Gradle (Settings > Build, Execution, Deployment > Build Tools > Gradle) y probar "Invalidate Caches / Restart".
- **`adb devices` no lista el celular:** usar cable de datos, activar Depuración por USB, aceptar la huella RSA en el celular y poner el modo "Transferencia de archivos".
- **La presión siempre da el mismo valor en el celular:** es normal con los dedos; la presión real se valida con el S Pen en la tableta.
- **`OutOfMemoryError`:** bitmaps demasiado grandes o historial de deshacer sin límite.
- **Trazos entrecortados:** falta usar los puntos históricos o se redibuja todo el lienzo en cada evento.
- **La barra de herramientas no se ve pero responde al toque:** no usar `canvas.drawColor()` en `DrawingView.onDraw`; pinta todo el canvas y tapa la UI de Compose. Usar `drawRect` con el tamaño de la vista.

## Estructura actual del código

Los archivos de la app están en `CStudio/app/src/main/java/com/example/cstudio/`:

- `MainActivity.kt`: crea la pantalla, conecta Compose con el lienzo, gestiona proyectos, autosave, exportación y navegación.
- `brush/Brush.kt`: describe el comportamiento de un pincel.
- `brush/Brushes.kt`: lista los pinceles disponibles (Pincel y Borrador).
- `brush/BrushSettings.kt`: agrupa color, tamaño, opacidad y pincel seleccionado.
- `drawing/DrawingView.kt`: vista Android del lienzo, entrada táctil y coordinación de componentes.
- `drawing/Viewport.kt`: matriz, zoom, desplazamiento, ajuste y conversión de coordenadas.
- `drawing/StrokeRenderer.kt`: presión, borrador, opacidad y curvas Bézier.
- `drawing/History.kt`: historial de deshacer/rehacer, limitado por pasos y memoria.
- `storage/ProjectMetadata.kt`: modelo y serialización JSON versionada.
- `storage/ProjectStorage.kt`: creación, guardado atómico, carga, listado, miniaturas y borrado de proyectos.
- `storage/AutoSaveDebouncer.kt`: espera dos segundos desde el último cambio antes de guardar.
- `storage/ImageExporter.kt`: exporta PNG, JPG, WEBP y PDF mediante MediaStore; PDF usa `PdfDocument`.
- `ui/ToolBar.kt`: organiza las filas de controles.
- `ui/ColorPalette.kt`: paleta rápida de colores.
- `ui/BrushOptions.kt`: controles de tamaño y opacidad.
- `ui/ActionButtons.kt`: selección de pincel y acciones del lienzo.
- `ui/HomeScreen.kt`: pantalla inicial y galería con abrir, renombrar y borrar con confirmación.
- `ui/NewProjectDialog.kt`: nombre, presets, dimensiones personalizadas y validación del nuevo lienzo.
- `ui/ExportDialog.kt`: selección de formato, calidad y fondo de exportación.
- `ui/theme/`: colores, tipografía y tema de Compose.

`CanvasDimensions.kt` centraliza los límites y el tamaño predeterminado. `project.json` usa formato v2 con ancho y alto separados, y los proyectos v1 se migran al abrirse.

## Exportación

`ImageExporter` usa `MediaStore`:

- PNG/JPG/WEBP: `Pictures/CStudio`.
- PDF: `Documents/CStudio`.
- Android 8/9 usa escritura legacy y solicita permiso de almacenamiento.
- Android 10+ crea una fila con `IS_PENDING` y la publica al terminar.
- Ante un fallo o `OutOfMemoryError`, intenta eliminar la URI creada para evitar filas huérfanas.
- JPG y fondo blanco se componen sobre blanco.
- PNG/WEBP pueden usar transparencia.
- PDF usa `PdfDocument` y limita la página a 842 × 1191 puntos manteniendo la proporción.
- Los nombres usan nombre limpio, timestamp con segundos y extensión.

## Pruebas reales

La suite está en:

- `app/src/androidTest/java/com/example/cstudio/ExampleInstrumentedTest.kt`
- `app/src/androidTest/java/com/example/cstudio/HomeScreenInstrumentedTest.kt`

`ExampleInstrumentedTest.kt` cubre:

- Contexto de la app.
- Pinch y pan.
- Límites de trazos fuera del lienzo.
- Undo, redo y limpiar.
- Guardado/carga de capa y píxeles.
- Rechazo de versiones desconocidas.
- Migración v1 a v2.
- Dimensiones de lienzo.
- Historial en lienzo 4096 × 4096.
- Cambio de capa.
- Proyecto dañado y recuperación.
- Ordenamiento, borrado y renombrado.
- Debounce del autosave.
- Composición sobre blanco.
- Entrada bloqueada y borrador.
- Carga mutable y metadatos dañados.
- Exportación PNG, JPG, WEBP y PDF.

`HomeScreenInstrumentedTest.kt` cubre:

- Inicio en la galería y acción de nuevo proyecto.
- Valor HD como preset predeterminado.

Resultado validado el 26/11/2026:

- `.\gradlew assembleDebug`: exitoso.
- `.\gradlew connectedDebugAndroidTest`: exitoso en Moto G85.
- Suite conectada: 21 pruebas, todas aprobadas.

Este dispositivo no tiene lápiz. La presión real y el rechazo de palma todavía no pueden considerarse validados.

## Estado actual (actualizar a mano)

Hecho y probado en el celular:
- [x] Proyecto creado y corriendo en el celular
- [x] Trazo suavizado (Bézier con puntos históricos)
- [x] Pincel con color, tamaño y opacidad
- [x] Borrador
- [x] Barra de herramientas (colores, tamaño, opacidad, pincel/borrador, limpiar)
- [x] Deshacer/rehacer
- [x] Zoom y desplazamiento con dos dedos; botón Ajustar
- [x] Autoguardado, carga del último proyecto y recuperación con proyecto nuevo ante archivos dañados
- [x] Lienzos rectangulares y tamaños configurables hasta 4096 × 4096; default 1920 × 1080
- [x] Migración de proyectos v1 a v2
- [x] Inicio/galería con miniaturas, nuevo, abrir, renombrar y borrar
- [x] Diálogo de nuevo proyecto con presets y tamaño personalizado
- [x] Exportación PNG/JPG/WEBP a Pictures/CStudio y PDF a Documents/CStudio; Android 8–9 solicita permiso de almacenamiento
- [x] Pruebas instrumentadas de persistencia/recuperación, debounce, tamaños, migración, exportación, historial, Home y diálogo de creación
- [x] Carga mutable de capas desde disco y conversión segura a ARGB_8888
- [x] Vista previa del borrador sin ocultar el resto del dibujo
- [x] Guardado/exportación no cancelables con reciclado controlado de snapshots
- [x] Entrada bloqueada durante carga, guardado y navegación
- [x] Guardado condicionado a cambios reales y recuperación de project.json
- [x] Historial de memoria reducida con undo/redo exacto
- [x] Límites básicos de zoom/pan y conservación relativa al redimensionar

Pendiente de validar:
- [ ] Presión real del S Pen y latencia en la tableta (el celular no tiene lápiz)
- [ ] Rechazo de palma con la tableta Samsung real
- [ ] Compilación release firmada y actualización instalada encima

Las demás funciones están en la lista de arriba.

**Problema actual / último error:**
Sin errores conocidos en las pruebas conectadas ejecutadas en el Moto G85.

## Limitaciones conocidas y trabajo que puede parecer hecho pero no está terminado

- El sistema de capas existe solo en el modelo JSON; no hay UI ni edición multicapa.
- No hay selector HSV completo, cuentagotas, estabilizador, relleno, formas ni transformaciones.
- No hay botón S Pen, curva de presión ni rechazo avanzado de palma probado.
- No hay línea de tiempo ni animación.
- No hay copia de seguridad/exportación masiva de proyectos.
- El APK release firmado todavía no está preparado.
- Falta prueba real de 4096 × 4096 y canvas.maximumBitmapWidth en Samsung.
- Faltan pruebas específicas de exportación fallida y filas MediaStore huérfanas.
- Faltan pruebas específicas de doble navegación, recorrido completo de Activity recreada y undo/redo con trazos grandes.
- El nombre original de exportación usa segundos, pero la regla aún puede colisionar si el mismo nombre se exporta varias veces exactamente en el mismo segundo.
