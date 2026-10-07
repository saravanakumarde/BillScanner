package com.billscanner.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper around ML Kit's on-device Latin-script text recognizer.
 * Latin script covers both German (incl. umlauts ä/ö/ü and ß) and English,
 * so one recognizer instance handles both without any network call or API key.
 */
class ReceiptOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Returns the raw recognized text, newline-separated per visual line. */
    suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()

        val lines = mutableListOf<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                lines.add(line.text)
            }
        }
        return OcrResult(
            rawText = result.text,
            lines = lines
        )
    }
}

data class OcrResult(
    val rawText: String,
    val lines: List<String>
)
