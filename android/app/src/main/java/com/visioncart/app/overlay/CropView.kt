package com.visioncart.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

class CropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private enum class TouchMode { NONE, HANDLE_TL, HANDLE_TR, HANDLE_BL, HANDLE_BR, HANDLE_T, HANDLE_B, HANDLE_L, HANDLE_R, MOVE_CROP, MOVE_IMAGE }

    private val maskPaint = Paint().apply { color = 0xB3000000.toInt(); style = Paint.Style.FILL }
    private val borderPaint = Paint().apply { color = 0xFF0A7C66.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val handleFillPaint = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val handleStrokePaint = Paint().apply { color = 0xFF0A7C66.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val guidePaint = Paint().apply { color = 0x550A7C66.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true }

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val cropRect = RectF()
    private var touchMode = TouchMode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private val handleRadius = 20f
    private val touchRadius = 48f
    private val minCropSize = 100f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            imageMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            constrainMatrix()
            updateInverse()
            invalidate()
            return true
        }
    })

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return

        val scale = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val scaledW = bitmap.width * scale
        val scaledH = bitmap.height * scale
        val left = (w - scaledW) / 2f
        val top = (h - scaledH) / 2f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(left, top)

        val cw = scaledW * 0.8f
        val ch = scaledH * 0.6f
        cropRect.set(
            left + (scaledW - cw) / 2f,
            top + (scaledH - ch) / 2f,
            left + (scaledW + cw) / 2f,
            top + (scaledH + ch) / 2f
        )

        updateInverse()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(bitmap, imageMatrix, null)
        drawMask(canvas)
        drawGrid(canvas)
        drawBorder(canvas)
        drawHandles(canvas)
    }

    private fun drawMask(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, cropRect.top, maskPaint)
        canvas.drawRect(0f, cropRect.bottom, w, h, maskPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, maskPaint)
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, maskPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(cropRect.left + thirdW * i, cropRect.top, cropRect.left + thirdW * i, cropRect.bottom, guidePaint)
            canvas.drawLine(cropRect.left, cropRect.top + thirdH * i, cropRect.right, cropRect.top + thirdH * i, guidePaint)
        }
    }

    private fun drawBorder(canvas: Canvas) {
        canvas.drawRect(cropRect, borderPaint)
    }

    private fun drawHandles(canvas: Canvas) {
        val handles = arrayOf(
            cropRect.left to cropRect.top,
            cropRect.centerX() to cropRect.top,
            cropRect.right to cropRect.top,
            cropRect.left to cropRect.centerY(),
            cropRect.right to cropRect.centerY(),
            cropRect.left to cropRect.bottom,
            cropRect.centerX() to cropRect.bottom,
            cropRect.right to cropRect.bottom
        )
        for ((hx, hy) in handles) {
            canvas.drawCircle(hx, hy, handleRadius, handleFillPaint)
            canvas.drawCircle(hx, hy, handleRadius, handleStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress && touchMode == TouchMode.MOVE_IMAGE) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                touchMode = detectTouchMode(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (touchMode) {
                    TouchMode.HANDLE_TL -> {
                        cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minCropSize)
                        cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minCropSize)
                    }
                    TouchMode.HANDLE_TR -> {
                        cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, width.toFloat())
                        cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minCropSize)
                    }
                    TouchMode.HANDLE_BL -> {
                        cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minCropSize)
                        cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, height.toFloat())
                    }
                    TouchMode.HANDLE_BR -> {
                        cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, width.toFloat())
                        cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, height.toFloat())
                    }
                    TouchMode.HANDLE_T -> {
                        cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minCropSize)
                    }
                    TouchMode.HANDLE_B -> {
                        cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, height.toFloat())
                    }
                    TouchMode.HANDLE_L -> {
                        cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minCropSize)
                    }
                    TouchMode.HANDLE_R -> {
                        cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, width.toFloat())
                    }
                    TouchMode.MOVE_CROP -> {
                        val cw = cropRect.width()
                        val ch = cropRect.height()
                        val newLeft = (cropRect.left + dx).coerceIn(0f, width.toFloat() - cw)
                        val newTop = (cropRect.top + dy).coerceIn(0f, height.toFloat() - ch)
                        cropRect.set(newLeft, newTop, newLeft + cw, newTop + ch)
                    }
                    TouchMode.MOVE_IMAGE -> {
                        imageMatrix.postTranslate(dx, dy)
                        constrainMatrix()
                        updateInverse()
                    }
                    TouchMode.NONE -> {}
                }
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
            }
        }
        return true
    }

    private fun detectTouchMode(x: Float, y: Float): TouchMode {
        val handles = arrayOf(
            TouchMode.HANDLE_TL to (cropRect.left to cropRect.top),
            TouchMode.HANDLE_TR to (cropRect.right to cropRect.top),
            TouchMode.HANDLE_BL to (cropRect.left to cropRect.bottom),
            TouchMode.HANDLE_BR to (cropRect.right to cropRect.bottom),
            TouchMode.HANDLE_T to (cropRect.centerX() to cropRect.top),
            TouchMode.HANDLE_B to (cropRect.centerX() to cropRect.bottom),
            TouchMode.HANDLE_L to (cropRect.left to cropRect.centerY()),
            TouchMode.HANDLE_R to (cropRect.right to cropRect.centerY())
        )
        for ((mode, pos) in handles) {
            if (isInCircle(x, y, pos.first, pos.second, touchRadius)) return mode
        }
        if (cropRect.contains(x, y)) return TouchMode.MOVE_CROP
        return TouchMode.MOVE_IMAGE
    }

    private fun isInCircle(px: Float, py: Float, cx: Float, cy: Float, r: Float): Boolean {
        return abs(px - cx) <= r && abs(py - cy) <= r
    }

    private fun constrainMatrix() {
        val mapped = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(mapped)
        var dx = 0f
        var dy = 0f
        if (mapped.width() <= width) {
            dx = (width - mapped.width()) / 2f - mapped.left
        } else {
            if (mapped.left > 0) dx = -mapped.left
            if (mapped.right < width) dx = width - mapped.right
        }
        if (mapped.height() <= height) {
            dy = (height - mapped.height()) / 2f - mapped.top
        } else {
            if (mapped.top > 0) dy = -mapped.top
            if (mapped.bottom < height) dy = height - mapped.bottom
        }
        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    private fun updateInverse() {
        imageMatrix.invert(inverseMatrix)
    }

    fun cropBitmap(): Bitmap {
        val pts = floatArrayOf(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        inverseMatrix.mapPoints(pts)
        val rawLeft = pts[0].roundToInt().coerceIn(0, bitmap.width - 1)
        val rawTop = pts[1].roundToInt().coerceIn(0, bitmap.height - 1)
        val rawRight = pts[2].roundToInt().coerceIn(rawLeft + 1, bitmap.width)
        val rawBottom = pts[3].roundToInt().coerceIn(rawTop + 1, bitmap.height)
        val padX = maxOf(8, ((rawRight - rawLeft) * 0.08f).roundToInt())
        val padY = maxOf(8, ((rawBottom - rawTop) * 0.08f).roundToInt())
        val srcLeft = (rawLeft - padX).coerceIn(0, bitmap.width - 1)
        val srcTop = (rawTop - padY).coerceIn(0, bitmap.height - 1)
        val srcRight = (rawRight + padX).coerceIn(srcLeft + 1, bitmap.width)
        val srcBottom = (rawBottom + padY).coerceIn(srcTop + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, srcLeft, srcTop, srcRight - srcLeft, srcBottom - srcTop)
    }
}
