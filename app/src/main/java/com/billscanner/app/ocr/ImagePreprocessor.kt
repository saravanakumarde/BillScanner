package com.billscanner.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Cheap, on-device pre-processing to help ML Kit read faded thermal-paper
 * receipts: grayscale + contrast boost. Not OCR itself, just image cleanup.
 */
object ImagePreprocessor {

    fun forOcr(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Grayscale
        val saturation = ColorMatrix().apply { setSaturation(0f) }

        // Contrast boost (scale around mid-gray, then re-add mid-gray offset)
        val contrast = 1.35f
        val translate = (-.5f * contrast + .5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        saturation.postConcat(contrastMatrix)

        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(saturation) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }
}
