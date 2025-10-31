package com.example.capstone
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import kotlin.math.sin

class WavyTextSpan(private val amplitude: Float, private val frequency: Float) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, context: Paint.FontMetricsInt?): Int {
        return paint.measureText(text, start, end).toInt()  // Return the width of the text to ensure proper spacing
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val baselineY = y.toFloat()  // Vertical position of the text
        val letterX = x  // Horizontal position of the text
        // Apply the sine function to create a wavy effect
        val letterY = baselineY + amplitude * sin(frequency * start)
        canvas.drawText(text, start, end, letterX, letterY, paint)
    }
}
