# Contexto del proyecto: app de dibujo para Android (Kotlin)

> Pegá este archivo al inicio del chat de Copilot, o guardalo como `.github/copilot-instructions.md` en la raíz del proyecto.

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
- Deshacer/rehacer: guardar copia del rectángulo afectado antes de cada trazo, máximo ~20 pasos.
- Zoom y pan con `Matrix`.
- Guardado: almacenamiento interno de la app. Exportar PNG con `MediaStore`.
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

Pendiente de validar:
- [ ] Presión real del S Pen y latencia en la tableta (el celular no tiene lápiz)

Las demás funciones están en la lista de arriba.

**Problema actual / último error:**
(escribirlo acá)
