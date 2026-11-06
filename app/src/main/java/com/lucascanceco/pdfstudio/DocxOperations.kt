package com.lucascanceco.pdfstudio

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

/** Deliberately bounded DOCX converter: block order, lists, tables and explicit page breaks. */
class DocxOperations(private val context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    sealed interface Block {
        data class Paragraph(val text: String, val listItem: Boolean = false, val pageBreak: Boolean = false) : Block
        data class Table(val rows: List<List<String>>) : Block
    }

    data class Conversion(val file: File, val pages: Int, val tables: Int, val warning: String)

    private fun parse(uri: Uri): List<Block> {
        val blocks = mutableListOf<Block>()
        val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el DOCX")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var inText = false
                    var paragraph: StringBuilder? = null
                    var list = false
                    var breakBefore = false
                    var table: MutableList<List<String>>? = null
                    var row: MutableList<String>? = null
                    var cell = StringBuilder()
                    var inCell = false
                    while (parser.eventType != XmlPullParser.END_DOCUMENT && blocks.size < 10_000) {
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "tbl" -> table = mutableListOf()
                                "tr" -> row = mutableListOf()
                                "tc" -> { inCell = true; cell = StringBuilder() }
                                "p" -> { paragraph = StringBuilder(); list = false; breakBefore = false }
                                "numPr" -> list = true
                                "t" -> inText = true
                                "tab" -> paragraph?.append("    ")
                                "br" -> {
                                    val kind = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "type" }
                                        ?.let { parser.getAttributeValue(it) }
                                    if (kind == "page") breakBefore = true else paragraph?.append(" ")
                                }
                                "lastRenderedPageBreak" -> breakBefore = true
                            }
                            XmlPullParser.TEXT -> if (inText) paragraph?.append(parser.text)
                            XmlPullParser.END_TAG -> when (parser.name) {
                                "t" -> inText = false
                                "p" -> {
                                    val value = paragraph?.toString().orEmpty()
                                    if (inCell) { if (cell.isNotEmpty()) cell.append(' '); cell.append(value) }
                                    else blocks.add(Block.Paragraph(value, list, breakBefore))
                                    paragraph = null
                                }
                                "tc" -> { row?.add(cell.toString()); inCell = false }
                                "tr" -> { table?.add(row?.toList().orEmpty()); row = null }
                                "tbl" -> { blocks.add(Block.Table(table?.toList().orEmpty())); table = null }
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
        require(blocks.isNotEmpty()) { "No se encontró contenido de Word" }
        return blocks
    }

    suspend fun convert(uri: Uri): Conversion = withContext(Dispatchers.IO) {
        val blocks = parse(uri)
        val output = File.createTempFile("pdfstudio_word_layout_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                val font = PDType1Font.HELVETICA
                val fontSize = 10.5f
                val leading = 15f
                val margin = 43f
                val pageWidth = PDRectangle.A4.width
                val pageHeight = PDRectangle.A4.height
                var stream: PDPageContentStream? = null
                var y = 0f
                fun newPage() {
                    stream?.close()
                    val page = PDPage(PDRectangle.A4)
                    document.addPage(page)
                    stream = PDPageContentStream(document, page)
                    y = pageHeight - margin
                }
                fun safe(value: String) = value.map {
                    if (it.code in 32..126 || it.code in 160..255) it else ' '
                }.joinToString("")
                fun lines(value: String, maxWidth: Float): List<String> {
                    val result = mutableListOf<String>()
                    var current = ""
                    safe(value).split(Regex("\\s+")).forEach { word ->
                        val candidate = if (current.isEmpty()) word else "$current $word"
                        if (font.getStringWidth(candidate) / 1000f * fontSize > maxWidth && current.isNotEmpty()) {
                            result.add(current); current = word
                        } else current = candidate
                    }
                    result.add(current)
                    return result
                }
                fun write(text: String, x: Float, baseline: Float) {
                    if (text.isBlank()) return
                    stream!!.beginText()
                    stream!!.setFont(font, fontSize)
                    stream!!.newLineAtOffset(x, baseline)
                    stream!!.showText(text)
                    stream!!.endText()
                }
                newPage()
                blocks.forEach { block ->
                    when (block) {
                        is Block.Paragraph -> {
                            if (block.pageBreak) newPage()
                            val content = if (block.listItem) "-  ${block.text}" else block.text
                            val wrapped = lines(content, pageWidth - 2f * margin)
                            wrapped.forEach { segment ->
                                if (y < margin + leading) newPage()
                                write(segment, margin, y)
                                y -= leading
                            }
                            y -= 5f
                        }
                        is Block.Table -> {
                            val colCount = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                            val tableWidth = pageWidth - 2f * margin
                            val cellWidth = tableWidth / colCount
                            block.rows.forEach { row ->
                                val cellLines = (0 until colCount).map { index ->
                                    lines(row.getOrNull(index).orEmpty(), cellWidth - 12f)
                                }
                                val rowHeight = cellLines.maxOf { it.size } * leading + 10f
                                require(rowHeight < pageHeight - 2f * margin) {
                                    "Una fila de tabla es demasiado alta para una página A4. Elegí PDF con el texto de Word."
                                }
                                if (y - rowHeight < margin) newPage()
                                cellLines.forEachIndexed { index, content ->
                                    val x = margin + index * cellWidth
                                    stream!!.addRect(x, y - rowHeight, cellWidth, rowHeight)
                                    stream!!.stroke()
                                    content.forEachIndexed { line, segment ->
                                        write(segment, x + 6f, y - 5f - fontSize - line * leading)
                                    }
                                }
                                y -= rowHeight
                            }
                            y -= 8f
                        }
                    }
                }
                stream?.close()
                document.save(output)
                Conversion(output, document.numberOfPages, blocks.count { it is Block.Table },
                    "Se conservan bloques, filas y saltos; no se garantizan tipografías, imágenes flotantes ni diseño complejo.")
            }
        } catch (e: Exception) { output.delete(); throw e }
    }
}
