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

import android.content.*
import android.graphics.*
import android.os.*
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.min

/**
 * A ViewGroup that coordinated dragging across its dscendants
 */
class DragLayer(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs), DragController {
    private var mDragging = false
    private var mShouldDrop = false
    private var mLastMotionX = 0f
    private var mLastMotionY = 0f

    /**
     * The bitmap that is currently being dragged
     */
    private var mDragBitmap: Bitmap? = null
    private var mOriginator: View? = null
    private var mBitmapOffsetX = 0
    private var mBitmapOffsetY = 0

    /**
     * X offset from where we touched on the cell to its upper-left corner
     */
    private var mTouchOffsetX = 0f

    /**
     * Y offset from where we touched on the cell to its upper-left corner
     */
    private var mTouchOffsetY = 0f

    /**
     * Utility rectangle
     */
    private val mDragRect = Rect()

    /**
     * Where the drag originated
     */
    private var mDragSource: DragSource? = null

    /**
     * The data associated with the object being dragged
     */
    private var mDragInfo: Any? = null
    private val mRect = Rect()
    private val mDropCoordinates = IntArray(2)
    private var mListener: DragController.DragListener? = null
    private var mDragScroller: DragScroller? = null
    private var mScrollState = SCROLL_OUTSIDE_ZONE
    private val mScrollRunnable = ScrollRunnable()
    private var mIgnoredDropTarget: View? = null
    private var mDragRegion: RectF? = null
    private var mEnteredRegion = false
    private var mLastDropTarget: DropTarget? = null
    private val mTrashPaint = Paint()
    private var mDragPaint: Paint? = null
    private var mAnimationFrom = 0f
    private var mAnimationTo = 0f
    private var mAnimationDuration = 0
    private var mAnimationStartTime: Long = 0
    private var mAnimationType = 0
    private var mAnimationState = ANIMATION_STATE_DONE
    private var mInputMethodManager: InputMethodManager? = null
    override fun startDrag(v: View?, source: DragSource?, dragInfo: Any?, dragAction: Int) {
        if (PROFILE_DRAWING_DURING_DRAG) {
            Debug.startMethodTracing("Launcher")
        }

        // Hide soft keyboard, if visible
        if (mInputMethodManager == null) {
            mInputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        }
        mInputMethodManager!!.hideSoftInputFromWindow(windowToken, 0)
        if (mListener != null) {
            mListener!!.onDragStart(v, source, dragInfo, dragAction)
        }
        val r = mDragRect
        r[v!!.scrollX, v.scrollY, 0] = 0
        offsetDescendantRectToMyCoords(v, r)
        mTouchOffsetX = mLastMotionX - r.left
        mTouchOffsetY = mLastMotionY - r.top
        v.clearFocus()
        v.isPressed = false
        val willNotCache = v.willNotCacheDrawing()
        v.setWillNotCacheDrawing(false)

        // Reset the drawing cache background color to fully transparent
        // for the duration of this operation
        val color = v.drawingCacheBackgroundColor
        v.drawingCacheBackgroundColor = 0
        if (color != 0) {
            v.destroyDrawingCache()
        }
        v.buildDrawingCache()
        val viewBitmap = v.drawingCache
        val width = viewBitmap.width
        val height = viewBitmap.height
        val scale = Matrix()
        var scaleFactor = v.width.toFloat()
        scaleFactor = (scaleFactor + DRAG_SCALE) / scaleFactor
        scale.setScale(scaleFactor, scaleFactor)
        mAnimationTo = 1.0f
        mAnimationFrom = 1.0f / scaleFactor
        mAnimationDuration = ANIMATION_SCALE_UP_DURATION
        mAnimationState = ANIMATION_STATE_STARTING
        mAnimationType = ANIMATION_TYPE_SCALE
        mDragBitmap = Bitmap.createBitmap(viewBitmap, 0, 0, width, height, scale, true)
        v.destroyDrawingCache()
        v.setWillNotCacheDrawing(willNotCache)
        v.drawingCacheBackgroundColor = color
        val dragBitmap = mDragBitmap
        mBitmapOffsetX = (dragBitmap!!.width - width) / 2
        mBitmapOffsetY = (dragBitmap.height - height) / 2
        if (dragAction == DragController.DRAG_ACTION_MOVE) {
            v.visibility = GONE
        }
        mDragPaint = null
        mDragging = true
        mShouldDrop = true
        mOriginator = v
        mDragSource = source
        mDragInfo = dragInfo
        (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VIBRATE_DURATION.toLong())
        mEnteredRegion = false
        invalidate()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return mDragging || super.dispatchKeyEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (mDragging && mDragBitmap != null) {
            if (mAnimationState == ANIMATION_STATE_STARTING) {
                mAnimationStartTime = SystemClock.uptimeMillis()
                mAnimationState = ANIMATION_STATE_RUNNING
            }
            if (mAnimationState == ANIMATION_STATE_RUNNING) {
                var normalized = (SystemClock.uptimeMillis() - mAnimationStartTime).toFloat() /
                        mAnimationDuration
                if (normalized >= 1.0f) {
                    mAnimationState = ANIMATION_STATE_DONE
                }
                normalized = min(normalized, 1.0f)
                val value = mAnimationFrom + (mAnimationTo - mAnimationFrom) * normalized
                when (mAnimationType) {
                    ANIMATION_TYPE_SCALE -> {
                        val dragBitmap: Bitmap = mDragBitmap!!
                        canvas.save()
                        canvas.translate(scrollX + mLastMotionX - mTouchOffsetX - mBitmapOffsetX,
                            scrollY + mLastMotionY - mTouchOffsetY - mBitmapOffsetY)
                        canvas.translate(dragBitmap.width * (1.0f - value) / 2,
                            dragBitmap.height * (1.0f - value) / 2)
                        canvas.scale(value, value)
                        canvas.drawBitmap(dragBitmap, 0.0f, 0.0f, mDragPaint)
                        canvas.restore()
                    }
                }
            } else {
                // Draw actual icon being dragged
                canvas.drawBitmap(mDragBitmap!!,
                    scrollX + mLastMotionX - mTouchOffsetX - mBitmapOffsetX,
                    scrollY + mLastMotionY - mTouchOffsetY - mBitmapOffsetY, mDragPaint)
            }
        }
    }

    private fun endDrag() {
        if (mDragging) {
            mDragging = false
            if (mDragBitmap != null) {
                mDragBitmap!!.recycle()
            }
            if (mOriginator != null) {
                mOriginator!!.visibility = VISIBLE
            }
            if (mListener != null) {
                mListener!!.onDragEnd()
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.action
        val x = ev.x
        val y = ev.y
        when (action) {
            MotionEvent.ACTION_MOVE -> {
            }
            MotionEvent.ACTION_DOWN -> {
                // Remember location of down touch
                mLastMotionX = x
                mLastMotionY = y
                mLastDropTarget = null
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                if (mShouldDrop && drop(x, y)) {
                    mShouldDrop = false
                }
                endDrag()
            }
        }
        return mDragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!mDragging) {
            return false
        }
        val action = ev.action
        val x = ev.x
        val y = ev.y
        when (action) {
            MotionEvent.ACTION_DOWN -> {

                // Remember where the motion event started
                mLastMotionX = x
                mLastMotionY = y
                if (x < SCROLL_ZONE || x > width - SCROLL_ZONE) {
                    mScrollState = SCROLL_WAITING_IN_ZONE
                    postDelayed(mScrollRunnable, SCROLL_DELAY.toLong())
                } else {
                    mScrollState = SCROLL_OUTSIDE_ZONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val scrollX = scrollX
                val scrollY = scrollY
                val touchX = mTouchOffsetX
                val touchY = mTouchOffsetY
                val offsetX = mBitmapOffsetX
                val offsetY = mBitmapOffsetY
                var left = (scrollX + mLastMotionX - touchX - offsetX).toInt()
                var top = (scrollY + mLastMotionY - touchY - offsetY).toInt()
                val dragBitmap = mDragBitmap
                val width = dragBitmap!!.width
                val height = dragBitmap.height
                val rect = mRect
                rect[left - 1, top - 1, left + width + 1] = top + height + 1
                mLastMotionX = x
                mLastMotionY = y
                left = (scrollX + x - touchX - offsetX).toInt()
                top = (scrollY + y - touchY - offsetY).toInt()

                // Invalidate current icon position
                rect.union(left - 1, top - 1, left + width + 1, top + height + 1)
                val coordinates = mDropCoordinates
                val dropTarget = findDropTarget(x.toInt(), y.toInt(), coordinates)
                if (dropTarget != null) {
                    if (mLastDropTarget === dropTarget) {
                        dropTarget.onDragOver(mDragSource, coordinates[0], coordinates[1],
                            mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
                    } else {
                        if (mLastDropTarget != null) {
                            mLastDropTarget!!.onDragExit(mDragSource, coordinates[0], coordinates[1],
                                mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
                        }
                        dropTarget.onDragEnter(mDragSource, coordinates[0], coordinates[1],
                            mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
                    }
                } else {
                    if (mLastDropTarget != null) {
                        mLastDropTarget!!.onDragExit(mDragSource, coordinates[0], coordinates[1],
                            mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
                    }
                }
                invalidate(rect)
                mLastDropTarget = dropTarget
                var inDragRegion = false
                if (mDragRegion != null) {
                    val region: RectF = mDragRegion!!
                    val inRegion = region.contains(ev.rawX, ev.rawY)
                    if (!mEnteredRegion && inRegion) {
                        mDragPaint = mTrashPaint
                        mEnteredRegion = true
                        inDragRegion = true
                    } else if (mEnteredRegion && !inRegion) {
                        mDragPaint = null
                        mEnteredRegion = false
                    }
                }
                if (!inDragRegion && x < SCROLL_ZONE) {
                    if (mScrollState == SCROLL_OUTSIDE_ZONE) {
                        mScrollState = SCROLL_WAITING_IN_ZONE
                        mScrollRunnable.setDirection(SCROLL_LEFT)
                        postDelayed(mScrollRunnable, SCROLL_DELAY.toLong())
                    }
                } else if (!inDragRegion && x > getWidth() - SCROLL_ZONE) {
                    if (mScrollState == SCROLL_OUTSIDE_ZONE) {
                        mScrollState = SCROLL_WAITING_IN_ZONE
                        mScrollRunnable.setDirection(SCROLL_RIGHT)
                        postDelayed(mScrollRunnable, SCROLL_DELAY.toLong())
                    }
                } else {
                    if (mScrollState == SCROLL_WAITING_IN_ZONE) {
                        mScrollState = SCROLL_OUTSIDE_ZONE
                        mScrollRunnable.setDirection(SCROLL_RIGHT)
                        removeCallbacks(mScrollRunnable)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(mScrollRunnable)
                if (mShouldDrop) {
                    drop(x, y)
                    mShouldDrop = false
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun drop(x: Float, y: Float): Boolean {
        invalidate()
        val coordinates = mDropCoordinates
        val dropTarget = findDropTarget(x.toInt(), y.toInt(), coordinates)
        if (dropTarget != null) {
            dropTarget.onDragExit(mDragSource, coordinates[0], coordinates[1],
                mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
            return if (dropTarget.acceptDrop(mDragSource, coordinates[0], coordinates[1],
                    mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
            ) {
                dropTarget.onDrop(mDragSource, coordinates[0], coordinates[1],
                    mTouchOffsetX.toInt(), mTouchOffsetY.toInt(), mDragInfo)
                mDragSource!!.onDropCompleted(dropTarget as View, true)
                true
            } else {
                mDragSource!!.onDropCompleted(dropTarget as View, false)
                true
            }
        }
        return false
    }

    private fun findDropTarget(x: Int, y: Int, dropCoordinates: IntArray): DropTarget? {
        return findDropTarget(this, x, y, dropCoordinates)
    }

    private fun findDropTarget(container: ViewGroup, x: Int, y: Int, dropCoordinates: IntArray): DropTarget? {
        var x = x
        var y = y
        val r = mDragRect
        val count = container.childCount
        val scrolledX = x + container.scrollX
        val scrolledY = y + container.scrollY
        val ignoredDropTarget = mIgnoredDropTarget
        for (i in count - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.visibility == VISIBLE && child !== ignoredDropTarget) {
                child.getHitRect(r)
                if (r.contains(scrolledX, scrolledY)) {
                    var target: DropTarget? = null
                    if (child is ViewGroup) {
                        x = scrolledX - child.getLeft()
                        y = scrolledY - child.getTop()
                        target = findDropTarget(child, x, y, dropCoordinates)
                    }
                    if (target == null) {
                        if (child is DropTarget) {
                            // Only consider this child if they will accept
                            val childTarget = child as DropTarget
                            return if (childTarget.acceptDrop(mDragSource, x, y, 0, 0, mDragInfo)) {
                                dropCoordinates[0] = x
                                dropCoordinates[1] = y
                                child
                            } else {
                                null
                            }
                        }
                    } else {
                        return target
                    }
                }
            }
        }
        return null
    }

    fun setDragScoller(scroller: DragScroller?) {
        mDragScroller = scroller
    }

    fun setDragListener(l: DragController.DragListener?) {
        mListener = l
    }

    /**
     * Specifies the view that must be ignored when looking for a drop target.
     *
     * @param view The view that will not be taken into account while looking
     * for a drop target.
     */
    fun setIgnoredDropTarget(view: View?) {
        mIgnoredDropTarget = view
    }

    /**
     * Specifies the delete region.
     *
     * @param region The rectangle in screen coordinates of the delete region.
     */
    fun setDeleteRegion(region: RectF?) {
        mDragRegion = region
    }

    private inner class ScrollRunnable : Runnable {
        private var mDirection = 0
        override fun run() {
            if (mDragScroller != null) {
                if (mDirection == SCROLL_LEFT) {
                    mDragScroller!!.scrollLeft()
                } else {
                    mDragScroller!!.scrollRight()
                }
                mScrollState = SCROLL_OUTSIDE_ZONE
            }
        }

        fun setDirection(direction: Int) {
            mDirection = direction
        }
    }

    companion object {
        private const val SCROLL_DELAY = 600
        private const val SCROLL_ZONE = 20
        private const val VIBRATE_DURATION = 35
        private const val ANIMATION_SCALE_UP_DURATION = 110
        private const val PROFILE_DRAWING_DURING_DRAG = false

        // Number of pixels to add to the dragged item for scaling
        private const val DRAG_SCALE = 24.0f
        private const val SCROLL_OUTSIDE_ZONE = 0
        private const val SCROLL_WAITING_IN_ZONE = 1
        private const val SCROLL_LEFT = 0
        private const val SCROLL_RIGHT = 1
        private const val ANIMATION_STATE_STARTING = 1
        private const val ANIMATION_STATE_RUNNING = 2
        private const val ANIMATION_STATE_DONE = 3
        private const val ANIMATION_TYPE_SCALE = 1
    }

    init {
        val srcColor = context.resources.getColor(R.color.delete_color_filter)
        mTrashPaint.colorFilter = PorterDuffColorFilter(srcColor, PorterDuff.Mode.SRC_ATOP)
    }
}