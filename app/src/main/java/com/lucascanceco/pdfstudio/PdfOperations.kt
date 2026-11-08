package com.lucascanceco.pdfstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.rendering.PDFRenderer as BoxRenderer
import android.graphics.Color as AndroidColor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All work stays on-device; never writes to the selected source URI. */
class PdfOperations(private val context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    private fun copyToTemp(uri: Uri, suffix: String): File {
        val file = File.createTempFile("pdfstudio_", suffix, context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir el archivo" }
                file.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) { file.delete(); throw e }
        return file
    }

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        val temp = copyToTemp(uri, ".pdf")
        try { PDDocument.load(temp).use { it.numberOfPages } } finally { temp.delete() }
    }

    suspend fun search(uri: Uri, query: String): List<Int> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val source = copyToTemp(uri, ".pdf")
        try {
            PDDocument.load(source).use { doc ->
                val stripper = PDFTextStripper()
                (1..doc.numberOfPages).filter { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.getText(doc).contains(query, ignoreCase = true)
                }
            }
        } finally { source.delete() }
    }

    suspend fun renderPage(uri: Uri, page: Int, width: Int = 1200): Bitmap = withContext(Dispatchers.IO) {
        val temp = copyToTemp(uri, ".pdf")
        try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    require(page in 0 until renderer.pageCount)
                    renderer.openPage(page).use { pdfPage ->
                        val requestedH = (width.toFloat() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
                        val bitmapW = if (requestedH > 4096) (width * 4096f / requestedH).toInt().coerceAtLeast(1) else width
                        val bitmapH = requestedH.coerceAtMost(4096)
                        val image = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.WHITE)
                        pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image
                    }
                }
            }
        } finally { temp.delete() }
    }

    suspend fun merge(uris: List<Uri>): File = withContext(Dispatchers.IO) {
        require(uris.size >= 2)
        val temps = uris.map { copyToTemp(it, ".pdf") }
        val output = File.createTempFile("pdfstudio_merge_", ".pdf", context.cacheDir)
        try {
            val merger = PDFMergerUtility()
            temps.forEach { merger.addSource(it) }
            merger.destinationFileName = output.absolutePath
            merger.mergeDocuments(null)
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { temps.forEach { it.delete() } }
    }

    suspend fun extract(uri: Uri, first: Int, last: Int): File = withContext(Dispatchers.IO) {
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_extract_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { input ->
                require(first in 1..input.numberOfPages && last in first..input.numberOfPages)
                PDDocument().use { result ->
                    for (index in first - 1 until last) result.importPage(input.getPage(index))
                    result.save(output)
                }
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    suspend fun rotate(uri: Uri, quarterTurns: Int = 1): File = withContext(Dispatchers.IO) {
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_rotate_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                doc.pages.forEach { it.rotation = (it.rotation + 90 * quarterTurns) % 360 }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    /** Page indices are zero-based, in the desired final order. Duplicates are allowed. */
    suspend fun reorder(uri: Uri, indices: List<Int>): File = withContext(Dispatchers.IO) {
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_pages_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { input ->
                require(indices.isNotEmpty() && indices.all { it in 0 until input.numberOfPages })
                PDDocument().use { doc ->
                    indices.forEach { doc.importPage(input.getPage(it)) }
                    doc.save(output)
                }
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    /** Adds selectable text without rasterizing the original page. Coordinates are relative to visible page, top-left origin. */
    suspend fun addText(uri: Uri, pageIndex: Int, value: String, xFraction: Float, yFraction: Float, size: Float): File = withContext(Dispatchers.IO) {
        require(value.isNotBlank() && value.length <= 1000 && size in 6f..72f)
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_text_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                val page = doc.getPage(pageIndex)
                val box = page.cropBox ?: page.mediaBox
                val x = box.lowerLeftX + box.width * xFraction.coerceIn(0f, 1f)
                val y = box.lowerLeftY + box.height * (1f - yFraction.coerceIn(0f, 1f))
                val safeText = value.replace('\n', ' ').map { if (it.code in 32..255) it else '?' }.joinToString("")
                PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, size)
                    stream.setNonStrokingColor(28, 28, 40)
                    stream.newLineAtOffset(x, y)
                    stream.showText(safeText)
                    stream.endText()
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    enum class Markup { HIGHLIGHT, UNDERLINE, STRIKEOUT }

    enum class EditKind { TEXT, IMAGE, HIGHLIGHT, UNDERLINE, STRIKEOUT, REDACT }

    /** Coordinates are normalized to each PDF page and remain stable if the keyboard resizes the UI. */
    data class EditOperation(
        val pageIndex: Int,
        val kind: EditKind,
        val x: Float,
        val y: Float,
        val width: Float = 0f,
        val height: Float = 0f,
        val text: String = "",
        val fontSize: Float = 16f,
        val image: Bitmap? = null
    )

    /** Transactional multi-page editing: writes all staged changes into one new PDF. */
    suspend fun applyEdits(uri: Uri, edits: List<EditOperation>): File = stageEdits(uri, edits, false)

    private suspend fun stageEdits(uri: Uri, edits: List<EditOperation>, allowRedactions: Boolean): File = withContext(Dispatchers.IO) {
        require(edits.isNotEmpty()) { "No hay modificaciones para guardar" }
        require(allowRedactions || edits.none { it.kind == EditKind.REDACT }) {
            "Una censura debe exportarse como copia aplanada, nunca como una marca visual"
        }
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_edited_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                edits.forEach { edit ->
                    require(edit.pageIndex in 0 until doc.numberOfPages)
                    val page = doc.getPage(edit.pageIndex)
                    val box = page.cropBox ?: page.mediaBox
                    val x = box.lowerLeftX + box.width * edit.x.coerceIn(0f, 1f)
                    val top = box.lowerLeftY + box.height * (1f - edit.y.coerceIn(0f, 1f))
                    PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                        when (edit.kind) {
                            EditKind.TEXT -> {
                                val safe = edit.text.replace('\n', ' ').map { if (it.code in 32..126 || it.code in 160..255) it else '?' }.joinToString("")
                                if (safe.isNotBlank()) {
                                    stream.beginText()
                                    stream.setFont(PDType1Font.HELVETICA, edit.fontSize.coerceIn(6f, 72f))
                                    stream.setNonStrokingColor(28, 28, 40)
                                    stream.newLineAtOffset(x, top)
                                    stream.showText(safe)
                                    stream.endText()
                                }
                            }
                            EditKind.IMAGE -> {
                                val bitmap = edit.image ?: error("Falta la imagen de una edición")
                                val w = box.width * edit.width.coerceIn(.05f, 1f)
                                val h = w * bitmap.height / bitmap.width
                                stream.drawImage(LosslessFactory.createFromImage(doc, bitmap), x, top - h, w, h)
                            }
                            else -> {
                                val w = box.width * edit.width.coerceIn(.005f, 1f)
                                val h = box.height * edit.height.coerceIn(.003f, 1f)
                                stream.saveGraphicsState()
                                if (edit.kind == EditKind.REDACT) {
                                    stream.setNonStrokingColor(0, 0, 0)
                                    stream.addRect(x, top - h, w, h)
                                    stream.fill()
                                } else if (edit.kind == EditKind.HIGHLIGHT) {
                                    val alpha = PDExtendedGraphicsState()
                                    alpha.nonStrokingAlphaConstant = .30f
                                    stream.setGraphicsStateParameters(alpha)
                                    stream.setNonStrokingColor(255, 211, 45)
                                    stream.addRect(x, top - h, w, h)
                                    stream.fill()
                                } else {
                                    stream.setStrokingColor(230, 78, 105)
                                    stream.setLineWidth(1.5f)
                                    val lineY = if (edit.kind == EditKind.UNDERLINE) top - h else top - h / 2f
                                    stream.moveTo(x, lineY)
                                    stream.lineTo(x + w, lineY)
                                    stream.stroke()
                                }
                                stream.restoreGraphicsState()
                            }
                        }
                    }
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    /**
     * Security export: burn edits and opaque redaction areas into pixel-only pages.
     * Original content streams, text, attachments, links and annotations are NOT copied.
     * Never use the visual-only applyEdits result as a safely redacted document.
     */
    suspend fun exportFlattened(uri: Uri, edits: List<EditOperation>, dpi: Float = 144f): File = withContext(Dispatchers.IO) {
        require(dpi in 96f..220f) { "Resolución fuera del rango seguro" }
        require(edits.any { it.kind == EditKind.REDACT }) { "Marcá al menos una región para censurar" }
        require(edits.filter { it.kind == EditKind.REDACT }.all {
            it.width > .002f && it.height > .002f && it.x >= 0f && it.y >= 0f &&
                it.x + it.width <= 1.001f && it.y + it.height <= 1.001f
        }) { "Hay una región de censura inválida" }
        val staged = stageEdits(uri, edits, true)
        val result = File.createTempFile("pdfstudio_flattened_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(staged).use { input ->
                val renderer = BoxRenderer(input)
                PDDocument().use { output ->
                    for (index in 0 until input.numberOfPages) {
                        val original = input.getPage(index).cropBox ?: input.getPage(index).mediaBox
                        val bitmap = renderer.renderImageWithDPI(index, dpi)
                        try {
                            // JPEG is opaque. Converted pages contain pixels only, not editable PDF layers.
                            val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
                            try {
                                val canvas = android.graphics.Canvas(opaque)
                                canvas.drawColor(AndroidColor.WHITE)
                                canvas.drawBitmap(bitmap, 0f, 0f, null)
                                val page = PDPage(PDRectangle(original.width, original.height))
                                output.addPage(page)
                                PDPageContentStream(output, page).use { stream ->
                                    stream.drawImage(JPEGFactory.createFromImage(output, opaque, .94f), 0f, 0f, original.width, original.height)
                                }
                            } finally { opaque.recycle() }
                        } finally { bitmap.recycle() }
                    }
                    output.save(result)
                }
            }
            PDDocument.load(result).use { check ->
                require(check.numberOfPages > 0 && PDFTextStripper().getText(check).isBlank()) {
                    "La copia aplanada conserva texto extraíble; no se exportó"
                }
            }
            result
        } catch (e: Exception) { result.delete(); throw e }
        finally { staged.delete() }
    }

    /** Draws an annotation visually over an area chosen by the user. Does not delete original text. */
    suspend fun markup(uri: Uri, pageIndex: Int, xFraction: Float, yFraction: Float, widthFraction: Float, heightFraction: Float, type: Markup): File = withContext(Dispatchers.IO) {
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_markup_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                val page = doc.getPage(pageIndex)
                val box = page.cropBox ?: page.mediaBox
                val x = box.lowerLeftX + box.width * xFraction.coerceIn(0f, 1f)
                val top = box.lowerLeftY + box.height * (1f - yFraction.coerceIn(0f, 1f))
                val w = box.width * widthFraction.coerceIn(.02f, 1f)
                val h = box.height * heightFraction.coerceIn(.005f, .3f)
                PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                    stream.saveGraphicsState()
                    when (type) {
                        Markup.HIGHLIGHT -> {
                            val gs = PDExtendedGraphicsState()
                            gs.nonStrokingAlphaConstant = .3f
                            stream.setGraphicsStateParameters(gs)
                            stream.setNonStrokingColor(255, 211, 45)
                            stream.addRect(x, top - h, w, h)
                            stream.fill()
                        }
                        Markup.UNDERLINE, Markup.STRIKEOUT -> {
                            stream.setStrokingColor(230, 78, 105)
                            stream.setLineWidth(1.5f)
                            stream.moveTo(x, if (type == Markup.UNDERLINE) top - h else top - h / 2f)
                            stream.lineTo(x + w, if (type == Markup.UNDERLINE) top - h else top - h / 2f)
                            stream.stroke()
                        }
                    }
                    stream.restoreGraphicsState()
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    data class ImageStamp(val pageIndex: Int, val bitmap: Bitmap, val x: Float, val y: Float, val width: Float)

    /** Writes every image in one document pass so multiple placements share a single export. */
    suspend fun stampImages(uri: Uri, stamps: List<ImageStamp>): File = withContext(Dispatchers.IO) {
        require(stamps.isNotEmpty()) { "No hay imágenes para guardar" }
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_stamps_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                stamps.forEach { stamp ->
                    require(stamp.pageIndex in 0 until doc.numberOfPages)
                    val page = doc.getPage(stamp.pageIndex)
                    val box = page.cropBox ?: page.mediaBox
                    val w = box.width * stamp.width.coerceIn(.05f, 1f)
                    val h = w * stamp.bitmap.height / stamp.bitmap.width
                    val x = box.lowerLeftX + box.width * stamp.x.coerceIn(0f, 1f)
                    val y = box.lowerLeftY + box.height * (1f - stamp.y.coerceIn(0f, 1f)) - h
                    val image = LosslessFactory.createFromImage(doc, stamp.bitmap)
                    PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                        stream.drawImage(image, x, y, w, h)
                    }
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    /** Stamps a locally chosen image with its alpha channel preserved. */
    suspend fun stampImage(uri: Uri, pageIndex: Int, bitmap: Bitmap, xFraction: Float, yFraction: Float, widthFraction: Float): File = withContext(Dispatchers.IO) {
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_stamp_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                val page = doc.getPage(pageIndex)
                val box = page.cropBox ?: page.mediaBox
                val w = box.width * widthFraction.coerceIn(.05f, 1f)
                val h = w * bitmap.height / bitmap.width
                val x = box.lowerLeftX + box.width * xFraction.coerceIn(0f, 1f)
                val y = box.lowerLeftY + box.height * (1f - yFraction.coerceIn(0f, 1f)) - h
                val image = LosslessFactory.createFromImage(doc, bitmap)
                PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                    stream.drawImage(image, x, y, w, h)
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    suspend fun passwordProtect(uri: Uri, password: String): File = withContext(Dispatchers.IO) {
        require(password.length in 4..64) { "La contraseña debe tener entre 4 y 64 caracteres" }
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_protected_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                val permissions = AccessPermission()
                val policy = StandardProtectionPolicy(password, password, permissions)
                policy.encryptionKeyLength = 128
                doc.protect(policy)
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    suspend fun addWatermark(uri: Uri, watermark: String): File = withContext(Dispatchers.IO) {
        require(watermark.isNotBlank() && watermark.length <= 80)
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_watermark_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                doc.pages.forEach { page ->
                    val box = page.cropBox ?: page.mediaBox
                    val safe = watermark.map { if (it.code in 32..126) it else '?' }.joinToString("")
                    PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA_BOLD, 28f)
                        stream.setNonStrokingColor(180, 180, 190)
                        stream.newLineAtOffset(box.lowerLeftX + box.width * .12f, box.lowerLeftY + box.height * .5f)
                        stream.showText(safe)
                        stream.endText()
                    }
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    /** Lossy option for image-heavy documents. Rasterizes pages; never silently overwrites the source. */
    suspend fun compressRasterized(uri: Uri, quality: Float = .65f): File = withContext(Dispatchers.IO) {
        val output = File.createTempFile("pdfstudio_compact_", ".pdf", context.cacheDir)
        try {
            val count = pageCount(uri)
            PDDocument().use { doc ->
                for (index in 0 until count) {
                    val bitmap = renderPage(uri, index, 1000)
                    try {
                        val width = 595f
                        val height = width * bitmap.height / bitmap.width
                        val page = PDPage(PDRectangle(width, height))
                        doc.addPage(page)
                        val jpg = JPEGFactory.createFromImage(doc, bitmap, quality)
                        PDPageContentStream(doc, page).use { it.drawImage(jpg, 0f, 0f, width, height) }
                    } finally { bitmap.recycle() }
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
    }

    data class OcrLine(val text: String, val x: Float, val baseline: Float, val height: Float)

    /** Creates a searchable copy while preserving the original graphics. Invisible text can be selected/searched. */
    suspend fun makeSearchable(uri: Uri, recognized: Map<Int, List<OcrLine>>): File = withContext(Dispatchers.IO) {
        require(recognized.isNotEmpty())
        val source = copyToTemp(uri, ".pdf")
        val output = File.createTempFile("pdfstudio_searchable_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { doc ->
                recognized.forEach { (index, lines) ->
                    val page = doc.getPage(index)
                    val box = page.cropBox ?: page.mediaBox
                    PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { stream ->
                        lines.forEach { line ->
                            val safe = line.text.map { if (it.code in 32..126 || it.code in 160..255) it else ' ' }.joinToString("")
                            if (safe.isNotBlank()) {
                                stream.beginText()
                                stream.setRenderingMode(RenderingMode.NEITHER)
                                stream.setFont(PDType1Font.HELVETICA, (box.height * line.height).coerceIn(4f, 60f))
                                stream.newLineAtOffset(box.lowerLeftX + box.width * line.x, box.lowerLeftY + box.height * (1f - line.baseline))
                                stream.showText(safe)
                                stream.endText()
                            }
                        }
                    }
                }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }

    suspend fun imagesToPdf(uris: List<Uri>): File = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        val output = File.createTempFile("pdfstudio_images_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                uris.forEach { uri ->
                    // Downsample large camera images before embedding, avoiding memory exhaustion.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "Imagen no accesible" }
                        BitmapFactory.decodeStream(stream, null, bounds)
                    }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagen no válida" }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2800) sample *= 2
                    val bitmap = context.contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "Imagen no accesible" }
                        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("Imagen no válida")
                    try {
                        val page = PDPage(PDRectangle.A4)
                        document.addPage(page)
                        val image = if (bitmap.hasAlpha()) LosslessFactory.createFromImage(document, bitmap)
                            else JPEGFactory.createFromImage(document, bitmap, .90f)
                        val availableW = page.mediaBox.width - 48f
                        val availableH = page.mediaBox.height - 48f
                        val scale = minOf(availableW / bitmap.width, availableH / bitmap.height)
                        val w = bitmap.width * scale
                        val h = bitmap.height * scale
                        PDPageContentStream(document, page).use { stream ->
                            stream.drawImage(image, (page.mediaBox.width - w) / 2f, (page.mediaBox.height - h) / 2f, w, h)
                        }
                    } finally { bitmap.recycle() }
                }
                document.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
    }

    /** Converts plain UTF-8 text or a DOCX's textual content. Complex Word layout is deliberately not claimed to survive. */
    suspend fun documentToPdf(uri: Uri, docx: Boolean): File = withContext(Dispatchers.IO) {
        val text = if (docx) extractDocxText(uri) else context.contentResolver.openInputStream(uri).use {
            requireNotNull(it) { "Archivo no accesible" }
            it.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText().take(500_000) }
        }
        require(text.isNotBlank()) { "No se encontró texto en el documento" }
        val output = File.createTempFile("pdfstudio_document_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { doc ->
                val font = PDType1Font.HELVETICA
                val fontSize = 11f
                val leading = 16f
                val margin = 46f
                var page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                var stream = PDPageContentStream(doc, page)
                var y = page.mediaBox.height - margin
                try {
                    for (line in text.lines()) {
                        val safe = line.map { if (it.code in 32..126 || it.code in 160..255) it else ' ' }.joinToString("")
                        val words = safe.split(' ')
                        val wrapped = mutableListOf<String>()
                        var currentLine = ""
                        for (word in words) {
                            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                            if (candidate.length > 95 && currentLine.isNotEmpty()) {
                                wrapped.add(currentLine)
                                currentLine = word
                            } else currentLine = candidate
                        }
                        wrapped.add(currentLine)
                        for (segment in wrapped) {
                            if (y < margin + leading) {
                                stream.close()
                                page = PDPage(PDRectangle.A4)
                                doc.addPage(page)
                                stream = PDPageContentStream(doc, page)
                                y = page.mediaBox.height - margin
                            }
                            if (segment.isNotBlank()) {
                                stream.beginText()
                                stream.setFont(font, fontSize)
                                stream.newLineAtOffset(margin, y)
                                stream.showText(segment.take(180))
                                stream.endText()
                            }
                            y -= leading
                        }
                    }
                } finally { stream.close() }
                doc.save(output)
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
    }

    private fun extractDocxText(uri: Uri): String {
        val result = StringBuilder()
        val input = context.contentResolver.openInputStream(uri) ?: error("Archivo no accesible")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var inText = false
                    while (parser.eventType != XmlPullParser.END_DOCUMENT && result.length < 500_000) {
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> if (parser.name == "t") inText = true
                            XmlPullParser.TEXT -> if (inText) result.append(parser.text)
                            XmlPullParser.END_TAG -> when (parser.name) {
                                "t" -> inText = false
                                "p" -> result.append('\n')
                            }
                        }
                        parser.next()
                    }
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result.toString()
    }

    suspend fun export(temp: File, destination: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "wt").use { output ->
            requireNotNull(output) { "No se puede guardar" }
            temp.inputStream().use { it.copyTo(output) }
        }
    }
}
