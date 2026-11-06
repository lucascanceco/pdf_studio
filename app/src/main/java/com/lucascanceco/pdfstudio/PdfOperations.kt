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
import java.io.File
import java.io.FileOutputStream
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

    suspend fun renderPage(uri: Uri, page: Int, width: Int = 1200): Bitmap = withContext(Dispatchers.IO) {
        val temp = copyToTemp(uri, ".pdf")
        try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    require(page in 0 until renderer.pageCount)
                    renderer.openPage(page).use { pdfPage ->
                        val h = (width.toFloat() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
                        val image = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
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

    suspend fun imagesToPdf(uris: List<Uri>): File = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        val output = File.createTempFile("pdfstudio_images_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                uris.forEach { uri ->
                    val bitmap = context.contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "Imagen no accesible" }
                        BitmapFactory.decodeStream(stream)
                    } ?: error("Imagen no válida")
                    try {
                        val page = PDPage(PDRectangle.A4)
                        document.addPage(page)
                        val image = LosslessFactory.createFromImage(document, bitmap)
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

    suspend fun export(temp: File, destination: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "wt").use { output ->
            requireNotNull(output) { "No se puede guardar" }
            temp.inputStream().use { it.copyTo(output) }
        }
    }
}
