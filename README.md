# PDF Studio (Android)

Aplicación Android nativa para visualizar y manipular PDF sin conexión. Inspiración visual: tarjetas pastel, superficies blancas y degradado violeta/durazno.

## Estado real

**0.1.0 — código fuente inicial, aún no compilado en un Android SDK.** Incluye lector PDF por páginas con zoom, recientes, creación desde imágenes, unión, extracción de páginas y rotación. No están implementadas todavía la edición del texto original, OCR, compresión ni Word a PDF. No se deben presentar como terminadas.

## Requisitos

- Android Studio con JDK 17 y SDK 35.
- Gradle 8.9 (el proyecto no incluye wrapper todavía).
- Android 8.0 (API 26) o superior.

## Compilar

1. Abrir la carpeta `PDFStudio` en Android Studio.
2. Permitir que Android Studio configure Gradle 8.9 y sincronice dependencias.
3. Compilar con **Build > Build APK(s)**.
4. Instalar en un dispositivo Android 8+.

La app no solicita permiso `INTERNET`. Los archivos se eligen con el selector del sistema y se guardan con `ACTION_CREATE_DOCUMENT`. No se modifican los originales.

## Próximas etapas

1. Editor de anotaciones, firmas y marcas de agua.
2. Escáner y OCR local con modelo incluido.
3. Edición estructural avanzada, prototipo y matriz de compatibilidad.
4. Procesamiento por lotes, protección y compresión verificable.

## Diseño de logo

Hoja blanca con esquina doblada y tres trazos sobre un fondo violeta. Vectorial, escalable y sin imágenes rasterizadas.
