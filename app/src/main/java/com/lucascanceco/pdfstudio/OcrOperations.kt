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
}
