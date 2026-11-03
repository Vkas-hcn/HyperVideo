package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** 仅用于确认页品牌词的横向渐变文字（Hyper 白色 + Video 红粉渐变）。 */
class GradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var gradientStart = 0xFFFDA1B6.toInt()
    private var gradientEnd = 0xFFE21220.toInt()

    fun setGradientColors(start: Int, end: Int) {
        gradientStart = start
        gradientEnd = end
        applyShader()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyShader()
    }

    private fun applyShader() {
        if (width <= 0) return
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            gradientStart,
            gradientEnd,
            Shader.TileMode.CLAMP,
        )
    }
}
