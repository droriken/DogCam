package com.kendrori.dogcam

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.objects.DetectedObject

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }

    private val targetCornerPaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Professional "Go" Green
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }

    private val boxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 100
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        setShadowLayer(6f, 3f, 3f, Color.BLACK)
    }

    private var results: List<DetectedObject> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var rotation: Int = 0
    private var cropRect: Rect = Rect()
    private var customLabel: String = ""
    private var isMirrored: Boolean = false
    private var isVisible: Boolean = true
    private var deviceRotation: Float = 0f
    private var targetKeywords: List<String> = emptyList()
    private var isTargetActive: Boolean = false
    
    private val transformMatrix = Matrix()
    private val tempRect = RectF()

    fun setResults(
        results: List<DetectedObject>,
        imageWidth: Int,
        imageHeight: Int,
        rotation: Int,
        cropRect: Rect,
        label: String = "",
        isMirrored: Boolean = false,
        deviceRotation: Float = 0f,
        targetKeywords: List<String> = emptyList(),
        isTargetActive: Boolean = false
    ) {
        this.results = results
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.rotation = rotation
        this.cropRect = Rect(cropRect)
        this.customLabel = label
        this.isMirrored = isMirrored
        this.deviceRotation = deviceRotation
        this.targetKeywords = targetKeywords
        this.isTargetActive = isTargetActive
        
        updateTransformMatrix()
        invalidate()
    }

    fun setOverlayVisible(visible: Boolean) {
        this.isVisible = visible
        invalidate()
    }

    private fun updateTransformMatrix() {
        transformMatrix.reset()
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0) return

        // 1. Map ML Kit buffer coordinates to the display rotation
        val bufferToUpright = Matrix()
        when (rotation) {
            90 -> {
                bufferToUpright.setRotate(90f)
                bufferToUpright.postTranslate(imageHeight.toFloat(), 0f)
            }
            180 -> {
                bufferToUpright.setRotate(180f)
                bufferToUpright.postTranslate(imageWidth.toFloat(), imageHeight.toFloat())
            }
            270 -> {
                bufferToUpright.setRotate(270f)
                bufferToUpright.postTranslate(0f, imageWidth.toFloat())
            }
        }

        // 2. IMPORTANT: Use the cropRect provided by CameraX.
        val effectiveCrop = if (cropRect.isEmpty) Rect(0, 0, imageWidth, imageHeight) else cropRect
        val cropF = RectF(effectiveCrop)
        bufferToUpright.mapRect(cropF)
        
        transformMatrix.postConcat(bufferToUpright)
        transformMatrix.postTranslate(-cropF.left, -cropF.top)

        // 3. Map the visible viewport to the View's dimensions using FILL_CENTER
        val viewportW = cropF.width()
        val viewportH = cropF.height()
        
        val scale = Math.max(width.toFloat() / viewportW, height.toFloat() / viewportH)
        transformMatrix.postScale(scale, scale)
        transformMatrix.postTranslate(
            (width - viewportW * scale) / 2f,
            (height - viewportH * scale) / 2f
        )

        // 4. Mirroring for front camera
        if (isMirrored) {
            transformMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateTransformMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isVisible || results.isEmpty()) return

        for (obj in results) {
            tempRect.set(obj.boundingBox)
            transformMatrix.mapRect(tempRect)

            val margin = 10f
            tempRect.left = tempRect.left.coerceAtLeast(margin)
            tempRect.top = tempRect.top.coerceAtLeast(margin)
            tempRect.right = tempRect.right.coerceAtMost(width.toFloat() - margin)
            tempRect.bottom = tempRect.bottom.coerceAtMost(height.toFloat() - margin)

            if (tempRect.width() < 20f || tempRect.height() < 20f) continue

            canvas.drawRect(tempRect, boxPaint)
            
            // Check for explicit species match
            val hasSpeciesLabel = obj.labels.any { label ->
                targetKeywords.any { kw -> label.text.contains(kw, ignoreCase = true) }
            }

            // Check for generic "Pet/Animal" match
            val hasGenericLabel = obj.labels.any { label ->
                label.text.contains("Pet", ignoreCase = true) ||
                label.text.contains("Animal", ignoreCase = true)
            }

            // Determine if this specific object is a target
            // Logic:
            // 1. Explicit Species Match: The detector labeled it "Dog" or "Cat".
            // 2. Verified Generic Match: The detector says "Pet/Animal" AND the global AI confirms a target.
            // 3. Probabilistic Match: The object is unlabeled AND the global AI confirms a target.
            //    (Unlabeled objects in STREAM_MODE are often the very targets the global AI is seeing)
            val isTarget = hasSpeciesLabel || (isTargetActive && (hasGenericLabel || obj.labels.isEmpty()))
            
            drawCorners(canvas, tempRect, if (isTarget) targetCornerPaint else cornerPaint)

            val labelText = if (isTarget && customLabel.isNotEmpty()) {
                customLabel 
            } else {
                obj.labels.firstOrNull()?.text ?: "Pet"
            }
            drawLabel(canvas, tempRect, labelText)
        }
    }

    private fun drawCorners(canvas: Canvas, rect: RectF, paint: Paint) {
        val handleLength = Math.min(Math.min(rect.width(), rect.height()) / 4f, 60f)
        
        canvas.drawLine(rect.left, rect.top, rect.left + handleLength, rect.top, paint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + handleLength, paint)
        
        canvas.drawLine(rect.right, rect.top, rect.right - handleLength, rect.top, paint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + handleLength, paint)
        
        canvas.drawLine(rect.left, rect.bottom, rect.left + handleLength, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - handleLength, paint)
        
        canvas.drawLine(rect.right, rect.bottom, rect.right - handleLength, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - handleLength, paint)
    }

    private fun drawLabel(canvas: Canvas, rect: RectF, text: String) {
        canvas.save()
        canvas.translate(rect.left, rect.top)
        canvas.rotate(deviceRotation)
        
        val textHeight = textPaint.textSize
        val textWidth = textPaint.measureText(text)
        
        var x = 12f
        var y = -20f

        if (rect.top < (textHeight + 30f)) {
            y = textHeight + 15f
        }

        if (rect.left + textWidth > width - 20f) {
            x = (width - rect.left) - textWidth - 12f
        }

        canvas.drawText(text, x, y, textPaint)
        canvas.restore()
    }
}
