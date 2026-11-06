package com.lucascanceco.pdfstudio

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Exact native text runs only. No visual white rectangles or hidden old searchable text. */
class OriginalTextOperations(private val context: Context) {
    private fun input(uri: Uri): File {
        val file = File.createTempFile("pdfstudio_source_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "El PDF no se puede abrir" }
                file.outputStream().use { stream.copyTo(it) }
            }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    /** Only whole, unencoded Tj operands are candidates. Fragmented/TJ/font-subset text is rejected. */
    suspend fun candidates(uri: Uri, pageIndex: Int): List<String> = withContext(Dispatchers.IO) {
        val source = input(uri)
        try {
            PDDocument.load(source).use { document ->
                val page = document.getPage(pageIndex)
                require(page.rotation % 360 == 0) { "La página tiene texto rotado: usá OCR o una anotación." }
                val parser = PDFStreamParser(page)
                parser.parse()
                val tokens = parser.tokens
                tokens.indices.mapNotNull { index ->
                    val operator = tokens[index] as? Operator ?: return@mapNotNull null
                    if (operator.name != "Tj" || index == 0) return@mapNotNull null
                    val word = tokens[index - 1] as? COSString ?: return@mapNotNull null
                    val value = word.string
                    value.takeIf { it.length in 3..180 && it.all { char -> char.code in 32..255 } && it.any(Char::isLetter) }
                }.distinct()
            }
        } finally { source.delete() }
    }

    suspend fun replace(uri: Uri, pageIndex: Int, old: String, replacement: String): File = withContext(Dispatchers.IO) {
        require(old.isNotBlank() && replacement.isNotBlank())
        require(replacement.all { it.code in 32..255 }) { "El texto nuevo contiene caracteres incompatibles con la fuente básica." }
        val source = input(uri)
        val output = File.createTempFile("pdfstudio_native_text_", ".pdf", context.cacheDir)
        try {
            PDDocument.load(source).use { document ->
                val page = document.getPage(pageIndex)
                require(page.rotation % 360 == 0) { "No se modifica texto rotado." }
                val parser = PDFStreamParser(page)
                parser.parse()
                val tokens = parser.tokens.toMutableList()
                val matches = tokens.indices.filter { index ->
                    (tokens[index] as? Operator)?.name == "Tj" && index > 0 &&
                        (tokens[index - 1] as? COSString)?.string == old
                }
                require(matches.size == 1) { "La línea no es un fragmento único editable. Probá OCR o anotaciones." }
                // Enforce a small change to avoid shifting neighboring text in common simple PDFs.
                require(kotlin.math.abs(replacement.length - old.length) <= 4) {
                    "El reemplazo cambia demasiado el ancho de la línea; usá una anotación."
                }
                val index = matches.single()
                tokens[index - 1] = COSString(replacement)
                val stream = PDStream(document)
                stream.createOutputStream().use { ContentStreamWriter(it).writeTokens(tokens) }
                page.contents = stream
                document.save(output)
            }
            // Validate actual text extraction. A graphical overprint would not pass this test.
            PDDocument.load(output).use { verified ->
                val stripper = PDFTextStripper().apply { startPage = pageIndex + 1; endPage = pageIndex + 1 }
                val text = stripper.getText(verified)
                require(text.contains(replacement) && !text.contains(old)) {
                    "No se pudo validar el reemplazo sin texto oculto. No se exportó el cambio."
                }
            }
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { source.delete() }
    }
}
