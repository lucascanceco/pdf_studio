# PDF Studio (Android)

Aplicación Android nativa para visualizar y manipular PDF sin conexión. Inspiración visual: tarjetas pastel, superficies blancas y degradado violeta/durazno.

## Estado real

**0.4.0-beta (versionCode 4), rama `release/integrated-0_4_0`.** Incluye visor con búsqueda y zoom, salto a página, Inicio con lectura/borrador reciente, creación desde fotos/TXT/DOCX, unión con vista previa y orden, extracción, rotación, organización con miniaturas y movimiento a posición, editor multipágina de texto/imágenes/marcas con vista previa y múltiples elementos, firmas ajustables, OCR offline, PDF buscable, protección, marca de agua y preparación para envío con peso objetivo.

Hay un **reemplazo de texto original muy acotado** para cadenas `Tj` simples, únicas y verificables. No sirve para cualquier PDF con fuentes incrustadas, glifos codificados o texto fragmentado; el motor rechaza casos incompatibles. DOCX admite párrafos, listas, tablas sencillas con filas enteras y saltos; no reproduce el diseño complejo de Word. La app ofrece salida solo-texto. OCR es aproximado y la marca visual **no es censura segura**. Consultar [TESTING.md](TESTING.md) para pruebas y límites exactos. **La compilación CI no sustituye la validación física ni la revisión de copias con otro lector.**

## Requisitos

- Android Studio con JDK 17 y SDK 35.
- Gradle 8.9 (el proyecto no incluye wrapper todavía).
- Android 8.0 (API 26) o superior.

## Compilar

1. Abrir la carpeta `PDFStudio` en Android Studio.
2. Permitir que Android Studio configure Gradle 8.9 y sincronice dependencias.
3. Compilar con **Build > Build APK(s)**.
4. Instalar en un dispositivo Android 8+.

La app elimina `INTERNET` y `ACCESS_NETWORK_STATE` del manifiesto fusionado mediante `tools:node="remove"` porque dependencias pueden declararlos. No hay servicios de red deliberados. Los archivos se eligen con el selector del sistema y se guardan con `ACTION_CREATE_DOCUMENT`. No se modifican los originales; una cancelación o fallo de destino permite retomar la sesión del editor.

## Próximas etapas

1. Pruebas reales en varios dispositivos, especialmente Android 8, 13 y 16, y apertura de las copias en un segundo lector.
2. Escáner con cámara y corrección de perspectiva.
3. Motor de composición de texto/fuentes PDF complejas y fidelidad DOCX superior, si se incorpora un backend compatible offline.
4. Historial completo de deshacer/rehacer y pruebas automáticas instrumentadas.

## Diseño de logo

Hoja blanca con esquina doblada y tres trazos sobre un fondo violeta. Vectorial, escalable y sin imágenes rasterizadas.
