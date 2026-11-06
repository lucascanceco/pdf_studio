package com.lucascanceco.pdfstudio

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Uses the bundled Latin OCR model: no model download or network access required. */
class OcrOperations(context: Context) {
    private val pdf = PdfOperations(context)

    suspend fun recognize(uri: Uri, page: Int): String {
        val bitmap = pdf.renderPage(uri, page, 1700)
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try { return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text }
            finally { recognizer.close() }
        } finally { bitmap.recycle() }
    }

    suspend fun createSearchableCopy(uri: Uri): java.io.File {
        val count = pdf.pageCount(uri)
        val linesByPage = mutableMapOf<Int, List<PdfOperations.OcrLine>>()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            for (index in 0 until count) {
                val bitmap = pdf.renderPage(uri, index, 1700)
                try {
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                    linesByPage[index] = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val rect = line.boundingBox ?: return@mapNotNull null
                        PdfOperations.OcrLine(
                            text = line.text,
                            x = rect.left.toFloat() / bitmap.width,
                            baseline = rect.bottom.toFloat() / bitmap.height,
                            height = rect.height().toFloat() / bitmap.height
                        )
                    }
                } finally { bitmap.recycle() }
            }
        } finally { recognizer.close() }
        return pdf.makeSearchable(uri, linesByPage)
    }
}
