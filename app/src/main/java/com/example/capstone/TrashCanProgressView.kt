package com.example.capstone  // Ensure the package matches the one in your XML

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TrashCanProgressView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var progress = 0
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 35f
        isAntiAlias = true
    }
    private val lidPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()


        val trashCanRect = RectF(width * 0.2f, height * 0.2f, width * 0.8f, height * 0.9f)
        val roundedRectPath = Path()
        roundedRectPath.addRoundRect(trashCanRect, 25f, 25f, Path.Direction.CW)
        canvas.drawPath(roundedRectPath, outlinePaint)


        val lidPath = Path()
        lidPath.moveTo(width * 0.15f, height * 0.2f)
        lidPath.lineTo(width * 0.85f, height * 0.2f)
        lidPath.quadTo(width * 0.5f, height * 0.05f, width * 0.15f, height * 0.2f)
        canvas.drawPath(lidPath, lidPaint)


        val fillHeight = trashCanRect.bottom - ((progress / 100f) * trashCanRect.height())
        val filledRect = RectF(trashCanRect.left, fillHeight, trashCanRect.right, trashCanRect.bottom)
        canvas.drawRect(filledRect, fillPaint)


        canvas.drawText("$progress%", width * 0.35f, height * 0.65f, textPaint)


        if (progress > 70) {
            fillPaint.color = Color.RED
            canvas.drawRect(filledRect, fillPaint)
        }
    }


    fun setProgress(value: Int) {
        progress = value.coerceIn(0, 100)
        invalidate()
    }
}
