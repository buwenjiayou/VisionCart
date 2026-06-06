package com.visioncart.app.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Crops an image around a detection bbox with a small safety margin.
 * bbox format: [x1, y1, x2, y2] in source image pixels.
 */
class CropTransformation(
    private val bbox: List<Int>,
    private val paddingFraction: Float = 0.15f
) : Transformation {

    override val cacheKey: String = "crop_${bbox.joinToString("_")}_$paddingFraction"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (bbox.size < 4) return input

        val left = minOf(bbox[0], bbox[2]).coerceIn(0, input.width)
        val top = minOf(bbox[1], bbox[3]).coerceIn(0, input.height)
        val right = maxOf(bbox[0], bbox[2]).coerceIn(0, input.width)
        val bottom = maxOf(bbox[1], bbox[3]).coerceIn(0, input.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val padX = maxOf(12, (width * paddingFraction).toInt())
        val padY = maxOf(12, (height * paddingFraction).toInt())

        val cropLeft = (left - padX).coerceAtLeast(0)
        val cropTop = (top - padY).coerceAtLeast(0)
        val cropRight = (right + padX).coerceAtMost(input.width)
        val cropBottom = (bottom + padY).coerceAtMost(input.height)

        return Bitmap.createBitmap(
            input,
            cropLeft,
            cropTop,
            (cropRight - cropLeft).coerceAtLeast(1),
            (cropBottom - cropTop).coerceAtLeast(1)
        )
    }
}
