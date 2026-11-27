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
- Al cambiar el tamaño de la vista, conserva el punto del lienzo situado en el centro y recalcula `fitScale`.

## Selector de color y cuentagotas

Implementados y probados el 26/11/2026 en Kotlin/Compose, sin dependencias externas:

- `color/ColorUtils.kt` ofrece parseo y formato HEX (incluyendo alfa) y conversiones RGB, HSL y HSV/HSB.
- El selector ofrece modos rueda HSV, cuadro HSV y valores; entrada HEX/RGB/HSB, alfa, vista previa, copia y colores recientes. La rueda y los controles exponen semántica accesible y admiten interacción táctil y teclado.
- `ui/ColorValueInputs.kt` conserva los prefijos HEX válidos mientras se escribe; el color se actualiza al completar la entrada sin reemplazar el texto parcial.
- `color/RecentColorsRepository.kt` persiste el último color y un historial reciente deduplicado y limitado con `SharedPreferences`; maneja datos JSON corruptos.
- El alfa ARGB seleccionado se combina con la opacidad del pincel tanto en la vista previa como en el trazo confirmado.
- El cuentagotas toma muestras del bitmap del lienzo respetando el viewport, zoom y desplazamiento; los píxeles transparentes se componen sobre blanco. Se puede cancelar con el botón o Atrás. Si se toca fuera del lienzo, avisa y mantiene el modo activo.
- El aviso y botón de cancelar del cuentagotas se dibujan como superposición; no reducen el área del `AndroidView` ni deben mover el lienzo.
- El `AndroidView` del lienzo está recortado a sus límites para que un lienzo ampliado no cubra el HUD.

Alcance pendiente de estas funciones: no se pueden guardar paletas personalizadas ni tomar muestras desde imágenes o desde otras apps. El selector HSV y el cuentagotas del lienzo sí están implementados; la interacción con S Pen/tableta todavía no se ha validado manualmente.

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
- `History.commit()` copia los límites del trazo. `DrawingView` reutiliza un `Rect`, por lo que guardar la referencia original hacía que trazos anteriores usaran los límites del último trazo al deshacer/rehacer.

Una revisión futura debería volver a verificar los cálculos de `historyBytes` en `undo()` y el tratamiento de OOM, aunque las pruebas existentes pasan.

## Persistencia

Los proyectos viven en:

`filesDir/projects/<uuid>/`

Contenido típico:

- `project.json`: metadatos versionados.
- `project.json.bak`: copia de respaldo del JSON.
- `layer_0.png` y `layer_<uuid>.png`: imágenes de las capas.
- `thumbnail.png`: miniatura cuadrada de 256 × 256.
- Archivos temporales `*.tmp` durante escrituras.

Formato JSON:

- Versión 1: un único `canvasSize`.
- Versión 2: `canvasWidth` y `canvasHeight` separados.
- El formato JSON v2 mantiene una lista ordenada de capas. El editor permite agregar, seleccionar, borrar, reordenar, ocultar y cambiar la opacidad de hasta 20 capas, sujeto a un presupuesto conjunto de 256 MB para los bitmaps vivos.

`ProjectStorage`:

- Escribe capas y metadatos de forma atómica.
- Decodifica las capas como bitmaps mutables ARGB_8888.
- Crea una miniatura con proporción contenida y barras blancas.
- Conserva una copia `.bak` antes de reemplazar `project.json`.
- Si el JSON falla, intenta usar el respaldo.
- Si no hay respaldo usable, reconstruye metadatos con las PNG de capas compatibles, ordenadas por nombre de archivo. El orden original no se puede recuperar sin metadatos.
- Respeta el máximo de 20 capas y `LayerStack.canFit`; las PNG omitidas por dimensiones incompatibles o presupuesto se registran en `recovery_preserved_layers.json` para que guardados posteriores no las eliminen.
- Conserva proyectos con versión mayor que 2 sin modificarlos.
- Limpia temporales y carpetas incompletas al iniciar.
- Guarda solo las capas con píxeles modificados, escribe primero las imágenes y metadatos atómicos y elimina archivos de capas huérfanos después.
- Una medición instrumentada del inicio/limpieza con 30 proyectos de prueba de 64 × 64 dio 27,27 ms en el emulador API 37 (por debajo del umbral de 50 ms); el constructor no se modificó.

La bandera `dirty` evita reescribir el proyecto si no hubo cambios. Se activa al confirmar trazos, deshacer, rehacer, limpiar o cambiar metadatos de capas. Solo se desactiva después de un guardado exitoso.

## Funciones planificadas (por prioridad)
Para la entrega del 2 de diciembre el objetivo es completar el Bloque 1; el Bloque 2 solo si sobra tiempo. Todo lo demás queda para versiones posteriores. Marcá con [x] lo que ya esté hecho.

Antes de implementar una función que afecte la arquitectura (capas, zoom, animación), avisame para no tener que rehacer código después.

### Bloque 1: imprescindibles
- [x] Deshacer/rehacer (cada trazo ya es una unidad; límite de ~20 pasos)
- [x] Zoom y pan con dos dedos (`Matrix`)
- [x] Autoguardado y reabrir el proyecto
- [x] Exportar PNG/JPG/WEBP/PDF con MediaStore
- [x] Capas: crear, ocultar, ordenar, seleccionar, eliminar con confirmación y cambiar opacidad (máximo 20; presupuesto de 256 MB)
- [x] Pantalla de inicio/galería interna de proyectos
- [x] Tamaños de lienzo predefinidos y personalizados hasta 4096 × 4096

### Bloque 2: muy valiosas (estilo Ibis Paint)
- [x] Selector HSV con rueda y cuadro, entradas HEX/RGB/HSB, alfa y colores recientes persistentes
- [x] Cuentagotas del lienzo
- [ ] Paletas personalizadas guardadas
- [ ] Cuentagotas desde imágenes
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
- `LayerStack` mantiene bitmaps ARGB_8888 en orden de abajo hacia arriba; se dibuja únicamente en la capa activa. Cada trazo en curso se guarda como puntos (x, y, ancho) y se previsualiza en su posición de la pila; al soltar se confirma una sola vez. La opacidad de capa se aplica al componer y el borrador (`PorterDuff.Mode.DST_OUT`) solo modifica la capa activa.
- Tamaño inicial predeterminado del lienzo: 1920 × 1080; cada proyecto admite dimensiones de 64 a 4096 px por lado.
- Entrada del lápiz: `MotionEvent.getPressure()`, `getHistoricalX/Y` (puntos intermedios) y `getToolType()`.
  - `TOOL_TYPE_STYLUS` dibuja.
  - Dos dedos (`TOOL_TYPE_FINGER`) hacen pan y zoom.
  - Cuidar `ACTION_CANCEL` por apoyo de la palma.
- Suavizado de trazos con curvas Bézier cuadráticas entre puntos.
- Deshacer/rehacer: máximo 20 pasos y 64 MB. Cada entrada guarda el estado anterior y captura el estado posterior al deshacer.
- Zoom y pan con `Matrix`; al redimensionar, se conserva anclado el punto del lienzo que estaba bajo el centro de la vista.
- El aviso del cuentagotas es una superposición sobre el lienzo para no cambiar su tamaño ni desplazar el viewport.
- En `app/build.gradle.kts`, el alcance de optimización R8 de release se limita a `com.example.cstudio.**`. No incluir Kotlin/AndroidX en `packageScope`: hacerlo provocó un `IllegalAccessError` durante el arranque en release.
- Guardado: almacenamiento interno de la app. Exportar PNG, JPG, WEBP y PDF con `MediaStore`.
- Los proyectos viven en `filesDir/projects/<uuid>` con metadatos JSON versionados, capas PNG ARGB_8888 y miniaturas de 256×256. El autosave usa debounce de 2 segundos y también guarda al pausar la Activity.
- El formato v2 no cambió. La pila limita a 20 capas y 256 MB de memoria viva; con lienzos de 4096 × 4096 el presupuesto permite hasta 4 capas. El historial global (20 entradas/64 MB) identifica el bitmap de cada entrada y descarta las entradas de una capa al eliminarla.
- Posible optimización futura, no implementada: capas dispersas por mosaicos (tiles) en resolución completa y ARGB_8888. Reservar memoria solo para tiles con contenido ahorraría espacio en dibujos con áreas transparentes sin reducir calidad; los lienzos densamente pintados seguirían usando casi lo mismo. Comprimir tiles sin pérdida o descargarlos temporalmente a disco podría reducir más la RAM, a costa de complejidad y posible latencia. Requiere adaptar dibujo, historial, persistencia y exportación.
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
- `drawing/LayerStack.kt`: capas, orden, selección y límites de memoria.
- `drawing/Viewport.kt`: matriz, zoom, desplazamiento, ajuste, conversión de coordenadas y anclaje al redimensionar.
- `drawing/StrokeRenderer.kt`: presión, borrador, opacidad y curvas Bézier.
- `drawing/History.kt`: historial de deshacer/rehacer, limitado por pasos y memoria.
- `color/ColorUtils.kt`: parseo y conversiones de colores RGB, HSL, HSV y alfa.
- `color/RecentColorsRepository.kt`: último color e historial reciente persistidos.
- `storage/ProjectMetadata.kt`: modelo y serialización JSON versionada.
- `storage/ProjectStorage.kt`: creación, guardado atómico, carga, listado, miniaturas y borrado de proyectos.
- `storage/AutoSaveDebouncer.kt`: espera dos segundos desde el último cambio antes de guardar.
- `storage/ImageExporter.kt`: exporta PNG, JPG, WEBP y PDF mediante MediaStore; PDF usa `PdfDocument`.
- `ui/ToolBar.kt`: organiza las filas de controles.
- `ui/ColorPalette.kt`: paleta rápida de colores.
- `ui/ColorPickerDialog.kt`: diálogo principal del selector de color.
- `ui/ColorValueInputs.kt`: campos HEX, RGB y HSB.
- `ui/ColorWheel.kt` y `ui/ColorSquare.kt`: selección interactiva HSV.
- `ui/ColorSliders.kt` y `ui/RecentColorsRow.kt`: controles de tono/brillo/alfa e historial reciente.
- `ui/BrushOptions.kt`: controles de tamaño y opacidad.
- `ui/ActionButtons.kt`: selección de pincel y acciones del lienzo.
- `ui/LayersPanel.kt`: panel superpuesto para seleccionar, ordenar, crear, borrar, ocultar y ajustar opacidad de capas.
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
- `app/src/androidTest/java/com/example/cstudio/ColorPickerInputTest.kt`
- `app/src/test/java/com/example/cstudio/color/ColorUtilsTest.kt`
- `app/src/androidTest/java/com/example/cstudio/HomeScreenInstrumentedTest.kt`
- `app/src/androidTest/java/com/example/cstudio/LayersInstrumentedTest.kt`
- `app/src/androidTest/java/com/example/cstudio/LayersPanelAccessibilityTest.kt`

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

Resultado validado en la revisión del 26/11/2026:

- AndroidX Test de instrumentación: `espresso-core 3.7.0` y `androidx.test.ext:junit 1.3.0`, actualizados con aprobación del usuario. Espresso 3.7.0 evita el fallo de reflexión `InputManager.getInstance()` en API 37.
- `.\gradlew assembleDebug`, `.\gradlew assembleDebugAndroidTest`, `.\gradlew testDebugUnitTest` y `.\gradlew assembleRelease`: compilaciones correctas; 9 pruebas unitarias aprobadas.
- Suite instrumentada en el emulador API 37: **55/55 pruebas aprobadas**, incluidas 16 de `LayersInstrumentedTest`, 6 de color, 30 de `ExampleInstrumentedTest`, 2 de Home y 1 de accesibilidad.
- La tarea Gradle `connectedDebugAndroidTest` intentó instalar el APK debug también en el Moto G85, donde hay un APK release firmado con otra firma. No se desinstaló ni se borraron datos del teléfono; la suite completa se ejecutó directamente en el emulador mediante `adb`.
- Medición de miniatura en lienzo 4096 × 4096 con 2 capas: 9.176 ms en el emulador API 37 (referencia, no medición en Moto G85).
- Las 35 pruebas instrumentadas anteriores habían pasado en el Moto G85. No reemplazar su APK release firmado ni borrar sus proyectos para ejecutar instrumentación.
- Sonda de memoria en emulador API 37, cuatro capas 4096 × 4096: guardado simulado (snapshot + escritura) pico de 518,19 MiB nativos / 3,37 MiB Java; exportación compuesta 325,97 MiB nativos / 2,26 MiB Java. No incluye el historial real ni todo el ciclo de `onPause`; no es medición de dispositivo físico.
- Se propuso guardar snapshots de una capa por vez para reducir el pico; el usuario no autorizó esa optimización y no se implementó. Repetir la medición en la tableta Samsung antes de decidir cambios de memoria.
- APK release generado sin firmar: `CStudio/app/build/outputs/apk/release/app-release-unsigned.apk`.
- El usuario generó e instaló un APK release firmado con Android Studio y confirmó que abre y funciona correctamente. El archivo de firma y sus contraseñas se guardan fuera del repositorio.
- Los seis commits de esta revisión están locales en `main`, no subidos a GitHub; último commit: `902046d`.

El teléfono probado no tiene lápiz. La presión real y el rechazo de palma todavía no pueden considerarse validados en la Samsung con S Pen.

## Estado actual (actualizar a mano)

Hecho e implementado:
- [x] Proyecto creado y corriendo en el celular
- [x] Trazo suavizado (Bézier con puntos históricos)
- [x] Pincel con color, tamaño y opacidad
- [x] Borrador
- [x] Barra de herramientas (colores, tamaño, opacidad, pincel/borrador, limpiar)
- [x] Selector HSV con rueda/cuadro, campos HEX/RGB/HSB, alfa e historial reciente
- [x] Cuentagotas del lienzo con zoom/pan, cancelación y aviso al tocar fuera
- [x] El selector y el cuentagotas mantienen el lienzo en su posición al abrirse/activarse
- [x] Deshacer/rehacer
- [x] Zoom y desplazamiento con dos dedos; botón Ajustar
- [x] Autoguardado, carga del último proyecto y recuperación con proyecto nuevo ante archivos dañados
- [x] Lienzos rectangulares y tamaños configurables hasta 4096 × 4096; default 1920 × 1080
- [x] Migración de proyectos v1 a v2
- [x] Inicio/galería con miniaturas, nuevo, abrir, renombrar y borrar
- [x] Diálogo de nuevo proyecto con presets y tamaño personalizado
- [x] Exportación PNG/JPG/WEBP a Pictures/CStudio y PDF a Documents/CStudio; Android 8–9 solicita permiso de almacenamiento
- [x] Pruebas instrumentadas de persistencia/recuperación, debounce, tamaños, migración, exportación, historial, Home y diálogo de creación
- [x] Pruebas de conversiones de color, entradas del selector, alfa, historial y cuentagotas
- [x] Carga mutable de capas desde disco y conversión segura a ARGB_8888
- [x] Vista previa del borrador sin ocultar el resto del dibujo
- [x] Guardado/exportación no cancelables con reciclado controlado de snapshots
- [x] Entrada bloqueada durante carga, guardado y navegación
- [x] Guardado condicionado a cambios reales y recuperación de project.json
- [x] Historial de memoria reducida con undo/redo exacto
- [x] Historial conserva límites de cada trazo al hacer undo/redo
- [x] Límites básicos de zoom/pan y anclaje del lienzo al redimensionar
- [x] Capas multicapa con agregar, borrar, reordenar, visibilidad, opacidad, renderizado, historial y persistencia
- [x] Exportación, cuentagotas y miniaturas usan la composición visible de capas
- [x] Recuperación de todas las capas compatibles desde PNG si se dañan los metadatos, preservando las omitidas
- [x] Panel de capas con semántica accesible y acciones de visibilidad etiquetadas
- [x] Aviso de máximo de 20 capas distinto del aviso de falta de memoria; aviso de capa oculta limitado a uno cada dos segundos
- [x] Entrada HEX parcial estable al teclear
- [x] Pruebas instrumentadas API 37 actualizadas y suite completa aprobada
- [x] APK release firmado e instalado en el teléfono

Pendiente de validar:
- [ ] Presión real del S Pen y latencia en la tableta (el celular no tiene lápiz)
- [ ] Rechazo de palma con la tableta Samsung real

Las demás funciones están en la lista de arriba.

**Estado de la última revisión (26/11/2026):**
La suite de 55 pruebas instrumentadas pasó en el emulador API 37 tras actualizar AndroidX Test. La ejecución Gradle multidispositivo no puede instalar el APK debug encima del APK release firmado del Moto G85 por diferencia de firmas; mantener intactos la instalación y los proyectos del teléfono. La medición de memoria supera 400 MiB al guardar, pero el usuario rechazó implementar el guardado secuencial. Falta repetir la medición y validar S Pen en la Samsung.

## Limitaciones conocidas y trabajo que puede parecer hecho pero no está terminado

- El sistema multicapa pasó 16 pruebas en el emulador API 37; falta validación manual en una Samsung con S Pen y pruebas de estrés en el dispositivo objetivo para 20 capas y lienzos grandes.
- El selector HSV y el cuentagotas del lienzo sí existen. Siguen pendientes las paletas personalizadas guardadas y el muestreo desde imágenes o desde otras apps.
- No hay estabilizador, relleno, formas ni transformaciones.
- No hay acción configurable para el botón del S Pen ni editor de curva de presión; el rechazo de palma aún no se ha validado en la tableta Samsung.
- No hay línea de tiempo ni animación.
- No hay copia de seguridad/exportación masiva de proyectos.
- La suite completa de instrumentación en API 37 pasa con Espresso 3.7.0 / AndroidX JUnit 1.3.0. El Moto G85 conserva el APK release firmado; no instalar el debug ni desinstalar la app para ejecutar pruebas.
- El pico medido al guardar cuatro capas 4096 × 4096 fue de 518,19 MiB nativos en un emulador, usando una sonda de snapshot + escritura; la exportación llegó a 325,97 MiB. La sonda no reproduce todo el ciclo real ni el historial, y falta repetirla en la tableta Samsung.
- La mitigación de snapshots/escritura secuenciales fue propuesta y el usuario no la autorizó; no implementarla sin una nueva aprobación.
- Falta prueba real de 4096 × 4096 y canvas.maximumBitmapWidth en Samsung.
- Faltan pruebas específicas de exportación fallida y filas MediaStore huérfanas.
- Faltan pruebas específicas de doble navegación, recorrido completo de Activity recreada y undo/redo con trazos grandes.
- El nombre original de exportación usa segundos, pero la regla aún puede colisionar si el mismo nombre se exporta varias veces exactamente en el mismo segundo.
