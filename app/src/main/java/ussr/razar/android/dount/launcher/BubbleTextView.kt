/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ussr.razar.android.dount.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.util.AttributeSet
import android.widget.TextView
import kotlin.math.min

/**
 * TextView that draws a bubble behind the text. We cannot use a LineBackgroundSpan
 * because we want to make the bubble taller than the text and TextView's clip is
 * too aggressive.
 */
open class BubbleTextView : TextView {
    private val mRect: RectF = RectF()
    private var mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mBackgroundSizeChanged: Boolean = false
    private var mBackground: Drawable? = null
    private var mCornerRadius: Float = 0f
    private var mPaddingH: Float = 0f
    private var mPaddingV: Float = 0f

    constructor(context: Context?) : super(context) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    private fun init() {
        isFocusable = true
        mBackground = background
        setBackgroundDrawable(null)
        mBackground?.callback = this
        mPaint.color = context.resources.getColor(R.color.bubble_dark_background)
        val scale: Float = context.resources.displayMetrics.density
        mCornerRadius = CORNER_RADIUS * scale
        mPaddingH = PADDING_H * scale
        mPaddingV = PADDING_V * scale
    }

    override fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if ((getLeft() != left) || (getRight() != right) || (getTop() != top) || (getBottom() != bottom)) {
            mBackgroundSizeChanged = true
        }
        return super.setFrame(left, top, right, bottom)
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return who === mBackground || super.verifyDrawable(who)
    }

    override fun drawableStateChanged() {
        val d: Drawable? = mBackground
        if (d != null && d.isStateful) {
            d.state = drawableState
        }
        super.drawableStateChanged()
    }

    override fun draw(canvas: Canvas) {
        val background: Drawable? = mBackground
        if (background != null) {
            val scrollX: Int = scrollX
            val scrollY: Int = scrollY
            if (mBackgroundSizeChanged) {
                background.setBounds(0, 0, right - left, bottom - top)
                mBackgroundSizeChanged = false
            }
            if ((scrollX or scrollY) == 0) {
                background.draw(canvas)
            } else {
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                background.draw(canvas)
                canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            }
        }
        val layout: Layout = layout
        val rect: RectF = mRect
        val left: Int = compoundPaddingLeft
        val top: Int = extendedPaddingTop
        rect.set(left + layout.getLineLeft(0) - mPaddingH,
            top + layout.getLineTop(0) - mPaddingV,
            min(left + layout.getLineRight(0) + mPaddingH, (scrollX + right - getLeft()).toFloat()),
            top + layout.getLineBottom(0) + mPaddingV)
        canvas.drawRoundRect(rect, mCornerRadius, mCornerRadius, mPaint)
        super.draw(canvas)
    }

    companion object {
        private const val CORNER_RADIUS: Float = 8.0f
        private const val PADDING_H: Float = 5.0f
        private const val PADDING_V: Float = 1.0f
    }
}