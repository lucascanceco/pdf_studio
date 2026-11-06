# PDF Studio — Protocolo de prueba en Android

Usar **copias de archivos de prueba**, nunca originales únicos ni documentos sensibles. La compilación de GitHub comprueba el código, pero no sustituye las pruebas manuales en un teléfono.

## Pruebas funcionales

- [ ] Abrir un PDF de 1 página y otro de 50+ páginas; pasar páginas y ampliar con dos dedos.
- [ ] Buscar una palabra en un PDF con texto seleccionable.
- [ ] Convertir dos JPG a PDF y comprobar orden, márgenes y nitidez.
- [ ] Convertir TXT y DOCX sencillo; revisar saltos de línea y acentos (el diseño de Word no se conserva necesariamente).
- [ ] Unir dos PDF de diferentes tamaños y verificar todas las páginas.
- [ ] Extraer páginas 2 a 4, rotar y reorganizar con miniaturas (mover, duplicar y quitar); comprobar que el original no cambie.
- [ ] Agregar texto a una página; cambiar el tamaño en la vista previa y comprobar posición y selección en otro lector.
- [ ] En una sola sesión, añadir texto, dos imágenes y un resaltado en páginas distintas; guardar una sola vez y revisar todos los cambios.
- [ ] Aplicar un cambio y luego pulsar «Reeditar último cambio» para moverlo y ajustar su tamaño antes de volver a aplicarlo.
- [ ] Agregar imagen PNG transparente; comprobar color, tamaño y transparencia.
- [ ] Dibujar firma, elegir posición y tamaño desde el editor visual; comprobar trazos, ubicación y nitidez.
- [ ] Resaltar, subrayar y tachar en copias; comprobar posición y opacidad.
- [ ] Reconocer texto mediante OCR en una página escaneada, copiarlo y compararlo con el original.
- [ ] Generar PDF buscable desde un escaneo; buscar una palabra en otro lector.
- [ ] Agregar marca de agua a un documento multipágina.
- [ ] Proteger con contraseña y verificar que otro lector la solicite.
- [ ] Comprimir un PDF de imágenes y comparar tamaño y nitidez; confirmar que los enlaces/texto interactivo se pierden como se advierte.
- [ ] Guardar cada resultado en una carpeta elegida y volver a abrirlo.
- [ ] Probar sin Wi-Fi ni datos móviles.

## Robustez y regresión

- [ ] Cancelar el selector de archivo y el diálogo Guardar: la app no debe cerrarse.
- [ ] Probar PDF protegido, corrupto y sin páginas válidas; debe mostrar error sin cerrar la app.
- [ ] Girar el teléfono durante la lectura y comprobar que no se pierde el documento.
- [ ] Girar durante una edición con cambios pendientes; comprobar que se conservan el PDF y las anotaciones.
- [ ] Abrir y cerrar el teclado mientras se coloca texto; verificar que el punto de inserción no cambia.
- [ ] Revisar el editor con navegación Android de tres botones y gestos: «Aplicar cambio» y «Guardar PDF» deben permanecer accesibles.
- [ ] Abrir fotos de cámara de alta resolución y convertirlas sin bloqueo por memoria.
- [ ] Probar documentos grandes sin agotar memoria (50–100 páginas).
- [ ] Abrir de nuevo documentos recientes.
- [ ] Confirmar que ningún resultado sobrescriba el PDF original.

## Limitaciones conocidas

- La edición directa de texto y objetos *preexistentes* en cualquier PDF aún no está disponible. Las herramientas actuales agregan contenido.
- Las marcas visuales no son una censura segura de información sensible.
- El OCR puede cometer errores, sobre todo con escaneos borrosos o columnas.
- La conversión DOCX actual extrae el texto, no conserva tablas y diseños complejos.
- La compresión rasterizada puede aumentar el tamaño de ciertos PDF y pierde contenido interactivo/texto seleccionable.
- El editor permite reubicar borradores y reeditar el último cambio de la página, pero todavía no cuenta con selección y transformación libre de todos los objetos existentes.
- El organizador visual mueve miniaturas mediante botones, no mediante arrastrar y soltar.

Anotar modelo de celular, versión de Android, función, resultado esperado, resultado real y captura si hay un problema.
