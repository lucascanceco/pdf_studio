# PDF Studio — Protocolo de prueba en Android

Usar **copias de archivos de prueba**, nunca originales únicos ni documentos sensibles. La compilación de GitHub comprueba el código, pero no sustituye las pruebas manuales en un teléfono.

## Pruebas funcionales

- [ ] Abrir un PDF de 1 página y otro de 50+ páginas; pasar páginas y ampliar con dos dedos.
- [ ] Buscar una palabra en un PDF con texto seleccionable.
- [ ] Convertir dos JPG a PDF y comprobar orden, márgenes y nitidez.
- [ ] Convertir TXT y DOCX sencillo; revisar saltos de línea y acentos (el diseño de Word no se conserva necesariamente).
- [ ] Unir dos PDF de diferentes tamaños y verificar todas las páginas.
- [ ] Extraer páginas 2 a 4, rotar y reorganizar (orden `3,1,2,2`); comprobar que el original no cambie.
- [ ] Agregar texto a una página; comprobar posición y si se puede seleccionar en otro lector.
- [ ] Agregar imagen PNG transparente; comprobar color, tamaño y transparencia.
- [ ] Dibujar firma; comprobar trazos, ubicación y nitidez.
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
- [ ] Probar documentos grandes sin agotar memoria (50–100 páginas).
- [ ] Abrir de nuevo documentos recientes.
- [ ] Confirmar que ningún resultado sobrescriba el PDF original.

## Limitaciones conocidas

- La edición directa de texto y objetos *preexistentes* en cualquier PDF aún no está disponible. Las herramientas actuales agregan contenido.
- Las marcas visuales no son una censura segura de información sensible.
- El OCR puede cometer errores, sobre todo con escaneos borrosos o columnas.
- La conversión DOCX actual extrae el texto, no conserva tablas y diseños complejos.
- La compresión rasterizada puede aumentar el tamaño de ciertos PDF y pierde contenido interactivo/texto seleccionable.
- Falta una interfaz de edición precisa mediante arrastre sobre la página, además de deshacer/rehacer.

Anotar modelo de celular, versión de Android, función, resultado esperado, resultado real y captura si hay un problema.
