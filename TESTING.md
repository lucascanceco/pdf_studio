# PDF Studio — registro reproducible de pruebas

## Alcance y trazabilidad

| Compilación | Entorno | Android | Documento y recorrido | Resultado |
| --- | --- | --- | --- | --- |
| Beta anterior a `0491180` | Teléfono físico de Lucas; modelo no informado | No informada | Lucas confirmó abrir, ver, unir, crear, extraer, rotar y varias funciones de edición; reportó problemas de teclado, barra inferior y guardado, luego mejorías | **Prueba de Lucas sobre beta anterior**. No se atribuye al nuevo APK. Faltan modelo y archivos exactos. |
| `0491180` | Emulador Pixel 6 en revisión anterior | No registrada | Instalación y controles de Inicio | **Instalación y navegación de Inicio verificadas en la revisión**. Hallazgos de edición/exportación fueron lectura de código, no pruebas manuales. |
| `0.4.0-beta`, `release/integrated-0_4_0` | GitHub Actions, `ubuntu-latest`, JDK 17, Android SDK del runner | No se ejecutó Android | `assembleDebug` | **Compilación automatizada**. Anotar run/commit del artefacto final. No equivale a instalar ni probar. |
| APK integrado | Teléfono físico de Lucas | Pendiente | Recorridos siguientes | **Sin ejecutar** hasta instalar nueva versión. |
| APK integrado | Emulador y segundo lector PDF | No ejecutado aquí | Exportaciones y apertura cruzada | **Pendiente**. No se afirma validación que no se efectuó. |

En cada ejecución completar: fecha, commit completo, run de Actions, hash del APK, teléfono o emulador, Android, archivo de prueba y características, pasos, resultado y evidencia de **segundo lector**. Usar copias sin datos sensibles.

## Pruebas de aceptación pendientes sobre el APK integrado

### Edición y recuperación

- [ ] PDF de tres páginas: aplicar texto en pág. 1, colocar imagen todavía activa, «Guardar PDF»; abrir en segundo lector y comprobar ambos **una vez**, posición y páginas.
- [ ] Texto vacío/sin punto, imagen sin posición: mensaje concreto y sesión intacta.
- [ ] Agregar tres anotaciones y seleccionar, mover, modificar y eliminar **la primera**; repetir con texto, imagen y marca.
- [ ] Flecha y gesto Atrás: seguir, conservar borrador, descartar; recuperar después de salir.
- [ ] Cancelar destino y reintentar. Simular escritura denegada, cambiar carpeta y reintentar sin duplicados.
- [ ] Girar teléfono, abrir/cerrar teclado, cambiar de app con texto en edición; verificar ancla y contenido.
- [ ] Firmar con trazo fino, reubicar y ajustar escala; abrir en segundo lector.

### Visor y navegación

- [ ] PDF de 50–100 páginas: búsqueda, contador para salto, zoom alrededor del dedo y desplazamiento hasta cuatro márgenes.
- [ ] Página que falla: número identificado y «Reintentar» operativo.
- [ ] Pantalla angosta, fuente grande, teclado y navegación de tres botones o gestos: controles accesibles.
- [ ] Inicio nuevo sin recientes, Inicio con «Continuar» y borrador; miniatura y última página correctas.
- [ ] OCR de escaneo: elegir miniatura y copiar texto de una página; separado de crear PDF buscable; abrir salida en segundo lector.
- [ ] Organizar: arrastrar varias posiciones y al borde para autodesplazamiento; «Mover a posición…»; duplicar y borrar; corroborar orden/páginas en segundo lector.
- [ ] Unir 3 documentos, escoger carátula última y subirla al principio; revisar miniaturas, nombres, conteos, orden y copia en segundo lector.
- [ ] Fotos grandes: convertir, cambiar orden, repetir entrega con fotos nuevas y mismo patrón de nombre.

### Texto original y conversión Word

- [ ] PDF texto nativo simple: corregir una cadena `Tj` entera; en segundo lector verificar línea, búsqueda del nuevo texto y ausencia del anterior.
- [ ] PDF con fuente incrustada, `TJ` fragmentado, texto rotado, columnas y escaneo: incompatibilidades detectadas sin salida engañosa; ofrecer OCR/anotación.
- [ ] Comparar DOCX y PDF exportado desde Word: párrafos con acentos, listas, tablas de varias páginas, saltos, diseño complejo; registrar diferencias por página y filas completas.
- [ ] Diseño Word complejo: mostrar advertencia, vista previa y alternativa «Crear PDF con el texto de Word» antes del guardado.

### Entrega y estabilidad

- [ ] Preparar para enviar con 5 MB: mostrar peso real (incluso si supera), ampliar firma y advertir rasterización y pérdida de enlaces/texto.
- [ ] PDF protegido, corrupto y escaneado: error comprensible y recuperación.
- [ ] Fotos de 12–50 MP, PDF largo, rotación, suspensión/reanudación; comprobar memoria.
- [ ] Modo avión durante todo el flujo.
- [ ] Inspeccionar manifiesto fusionado: ausencia de permisos INTERNET y ACCESS_NETWORK_STATE tras `tools:node="remove"`.
- [ ] «Acerca de»: versión y commit coinciden; diálogo final «Abrir copia» y «Compartir».

## Limitaciones explícitas del motor

| Característica | Alcance y comportamiento |
| --- | --- |
| PDF original complejo: fuentes incrustadas, subgrupos, cadenas `TJ`, glifos codificados, columnas y rotaciones | Reemplazo **acotado** a una cadena `Tj` exacta, única, verificable y de ancho similar; se rechazan otros casos con alternativa de anotación/OCR. No hay reflujo tipográfico universal. |
| Escaneo sin texto digital | No se modifica texto original inexistente; OCR de extracción o búsqueda, o anotación superpuesta. |
| Word complejo: columnas, imágenes flotantes, campos y fuentes especiales | Conversión estructural básica con advertencia y vista previa; salida alternativa solo-texto. No equivale a maquetación nativa de Word. |
| PDF con firma digital previa | La nueva copia puede invalidar la firma criptográfica; conservar original. |
| Compresión rasterizada | Puede aumentar tamaño o no cumplir objetivo; pierde enlaces, formularios, texto seleccionable. Se muestra peso real. |
| Tapar texto con marcas visuales | **No es censura segura** de contenido confidencial. |

Cuando aparezca un fallo, adjuntar archivo de reproducción sin datos sensibles, modelo, Android, commit en «Acerca de», captura o video y pasos exactos.
