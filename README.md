# PDF Studio (Android)

Aplicación Android nativa para visualizar y manipular PDF sin conexión. Inspiración visual: tarjetas pastel, superficies blancas y degradado violeta/durazno.

## Estado real

**0.3.0-beta — compila con GitHub Actions; quedan pendientes las pruebas funcionales de regresión en distintos teléfonos.** Incluye visor con zoom, búsqueda, recientes, creación desde imágenes/TXT/DOCX textual, unión, extracción, rotación, organización visual con miniaturas, editor multipágina de texto/imágenes/marcas en una sola sesión, firma posicionable, OCR offline, PDF buscable, protección por contraseña, marcas de agua y compresión rasterizada opcional.

La edición de texto/objetos originales de PDF arbitrarios, la conservación de maquetación Word compleja, el reconocimiento perfecto de escaneos y el sistema completo de deshacer/rehacer **no están resueltos**. La función de marca visual **no es censura segura**. Consultar [TESTING.md](TESTING.md) para pruebas y limitaciones.

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

1. Pruebas reales en varios dispositivos, especialmente Android 8, 13 y 16; selección directa de anotaciones ya aplicadas.
2. Escáner con cámara y corrección de perspectiva.
3. Edición estructural avanzada: validar alcance y motor para modificar objetos originales.
4. Deshacer/rehacer y conversión de formatos con mayor fidelidad.

## Diseño de logo

Hoja blanca con esquina doblada y tres trazos sobre un fondo violeta. Vectorial, escalable y sin imágenes rasterizadas.
