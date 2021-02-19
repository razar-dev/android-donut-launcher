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
import android.util.AttributeSet
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.view.ViewDebug.ExportedProperty
import ussr.razar.android.dount.launcher.CellLayout.CellInfo.VacantCell
import ussr.razar.android.widget.WidgetLayout
import java.util.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class CellLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    WidgetLayout(context, attrs, defStyle) {
    private var mPortrait = false
    private val mCellWidth: Int
    private val mCellHeight: Int
    private val mLongAxisStartPadding: Int
    private val mLongAxisEndPadding: Int
    private val mShortAxisStartPadding: Int
    private val mShortAxisEndPadding: Int
    private val mShortAxisCells: Int
    private val mLongAxisCells: Int
    private var mWidthGap = 0
    private var mHeightGap = 0
    private val mRect = Rect()
    private val mCellInfo = CellInfo()
    private var mCellXY = IntArray(2)
    private var mOccupied: Array<BooleanArray>? = null
    private val mDragRect = RectF()
    private var mDirtyTag = false
    override fun cancelLongPress() {
        super.cancelLongPress()

        // Cancel long press for all children
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            child.cancelLongPress()
        }
    }

    val countX: Int
        get() = if (mPortrait) mShortAxisCells else mLongAxisCells
    val countY: Int
        get() = if (mPortrait) mLongAxisCells else mShortAxisCells

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        // Generate an id for each view, this assumes we have at most 256x256 cells
        // per workspace screen
        val cellParams = params as LayoutParams
        cellParams.regenerateId = true
        super.addView(child, index, params)
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            val r = Rect()
            child.getDrawingRect(r)
            requestRectangleOnScreen(r)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mCellInfo.screen = (parent as ViewGroup).indexOfChild(this)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.action
        val cellInfo = mCellInfo
        if (action == MotionEvent.ACTION_DOWN) {
            val frame = mRect
            val x = ev.x.toInt() + scrollX
            val y = ev.y.toInt() + scrollY
            val count = childCount
            var found = false
            for (i in count - 1 downTo 0) {
                val child = getChildAt(i)
                if (child.visibility == VISIBLE || child.animation != null) {
                    child.getHitRect(frame)
                    if (frame.contains(x, y)) {
                        val lp = child.layoutParams as LayoutParams
                        cellInfo.cell = child
                        cellInfo.cellX = lp.cellX
                        cellInfo.cellY = lp.cellY
                        cellInfo.spanX = lp.cellHSpan
                        cellInfo.spanY = lp.cellVSpan
                        cellInfo.valid = true
                        found = true
                        mDirtyTag = false
                        break
                    }
                }
            }
            if (!found) {
                val cellXY = mCellXY
                pointToCellExact(x, y, cellXY)
                val portrait = mPortrait
                val xCount = if (portrait) mShortAxisCells else mLongAxisCells
                val yCount = if (portrait) mLongAxisCells else mShortAxisCells
                val occupied = mOccupied
                findOccupiedCells(xCount, yCount, occupied, null)
                cellInfo.cell = null
                cellInfo.cellX = cellXY[0]
                cellInfo.cellY = cellXY[1]
                cellInfo.spanX = 1
                cellInfo.spanY = 1
                cellInfo.valid =
                    cellXY[0] >= 0 && cellXY[1] >= 0 && cellXY[0] < xCount && cellXY[1] < yCount && !occupied!![cellXY[0]][cellXY[1]]

                // Instead of finding the interesting vacant cells here, wait until a
                // caller invokes getTag() to retrieve the result. Finding the vacant
                // cells is a bit expensive and can generate many new objects, it's
                // therefore better to defer it until we know we actually need it.
                mDirtyTag = true
            }
            tag = cellInfo
        } else if (action == MotionEvent.ACTION_UP) {
            cellInfo.cell = null
            cellInfo.cellX = -1
            cellInfo.cellY = -1
            cellInfo.spanX = 0
            cellInfo.spanY = 0
            cellInfo.valid = false
            mDirtyTag = false
            tag = cellInfo
        }
        return false
    }

    override fun getTag(): CellInfo {
        val info = super.getTag() as CellInfo
        if (mDirtyTag && info.valid) {
            val portrait = mPortrait
            val xCount = if (portrait) mShortAxisCells else mLongAxisCells
            val yCount = if (portrait) mLongAxisCells else mShortAxisCells
            val occupied = mOccupied
            findOccupiedCells(xCount, yCount, occupied, null)
            findIntersectingVacantCells(info, info.cellX, info.cellY, xCount, yCount, occupied)
            mDirtyTag = false
        }
        return info
    }

    fun findAllVacantCells(occupiedCells: BooleanArray?, ignoreView: View?): CellInfo {
        val portrait = mPortrait
        val xCount = if (portrait) mShortAxisCells else mLongAxisCells
        val yCount = if (portrait) mLongAxisCells else mShortAxisCells
        val occupied = mOccupied
        if (occupiedCells != null) {
            for (y in 0 until yCount) {
                for (x in 0 until xCount) {
                    occupied!![x][y] = occupiedCells[y * xCount + x]
                }
            }
        } else {
            findOccupiedCells(xCount, yCount, occupied, ignoreView)
        }
        return findAllVacantCellsFromOccupied(occupied, xCount, yCount)
    }

    /**
     * Variant of findAllVacantCells that uses LauncerModel as its source rather than the
     * views.
     */
    fun findAllVacantCellsFromOccupied(
        occupied: Array<BooleanArray>?,
        xCount: Int, yCount: Int
    ): CellInfo {
        val cellInfo = CellInfo()
        cellInfo.cellX = -1
        cellInfo.cellY = -1
        cellInfo.spanY = 0
        cellInfo.spanX = 0
        cellInfo.maxVacantSpanX = Int.MIN_VALUE
        cellInfo.maxVacantSpanXSpanY = Int.MIN_VALUE
        cellInfo.maxVacantSpanY = Int.MIN_VALUE
        cellInfo.maxVacantSpanYSpanX = Int.MIN_VALUE
        cellInfo.screen = mCellInfo.screen
        val current = cellInfo.current
        for (x in 0 until xCount) {
            for (y in 0 until yCount) {
                if (!occupied!![x][y]) {
                    current[x, y, x] = y
                    findVacantCell(current, xCount, yCount, occupied, cellInfo)
                    occupied[x][y] = true
                }
            }
        }
        cellInfo.valid = cellInfo.vacantCells.size > 0

        // Assume the caller will perform their own cell searching, otherwise we
        // risk causing an unnecessary rebuild after findCellForSpan()
        return cellInfo
    }

    /**
     * Given a point, return the cell that strictly encloses that point
     * @param x X coordinate of the point
     * @param y Y coordinate of the point
     * @param result Array of 2 ints to hold the x and y coordinate of the cell
     */
    private fun pointToCellExact(x: Int, y: Int, result: IntArray) {
        val portrait = mPortrait
        val hStartPadding = if (portrait) mShortAxisStartPadding else mLongAxisStartPadding
        val vStartPadding = if (portrait) mLongAxisStartPadding else mShortAxisStartPadding
        result[0] = (x - hStartPadding) / (mCellWidth + mWidthGap)
        result[1] = (y - vStartPadding) / (mCellHeight + mHeightGap)
        val xAxis = if (portrait) mShortAxisCells else mLongAxisCells
        val yAxis = if (portrait) mLongAxisCells else mShortAxisCells
        if (result[0] < 0) result[0] = 0
        if (result[0] >= xAxis) result[0] = xAxis - 1
        if (result[1] < 0) result[1] = 0
        if (result[1] >= yAxis) result[1] = yAxis - 1
    }

    /**
     * Given a cell coordinate, return the point that represents the upper left corner of that cell
     *
     * @param cellX X coordinate of the cell
     * @param cellY Y coordinate of the cell
     *
     * @param result Array of 2 ints to hold the x and y coordinate of the point
     */
    fun cellToPoint(cellX: Int, cellY: Int, result: IntArray) {
        val portrait = mPortrait
        val hStartPadding = if (portrait) mShortAxisStartPadding else mLongAxisStartPadding
        val vStartPadding = if (portrait) mLongAxisStartPadding else mShortAxisStartPadding
        result[0] = hStartPadding + cellX * (mCellWidth + mWidthGap)
        result[1] = vStartPadding + cellY * (mCellHeight + mHeightGap)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // TODO: currently ignoring padding
        val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)
        if (widthSpecMode == MeasureSpec.UNSPECIFIED || heightSpecMode == MeasureSpec.UNSPECIFIED) {
            throw RuntimeException("CellLayout cannot have UNSPECIFIED dimensions")
        }
        val shortAxisCells = mShortAxisCells
        val longAxisCells = mLongAxisCells
        val longAxisStartPadding = mLongAxisStartPadding
        val longAxisEndPadding = mLongAxisEndPadding
        val shortAxisStartPadding = mShortAxisStartPadding
        val shortAxisEndPadding = mShortAxisEndPadding
        val cellWidth = mCellWidth
        val cellHeight = mCellHeight
        mPortrait = heightSpecSize > widthSpecSize
        val numShortGaps = shortAxisCells - 1
        val numLongGaps = longAxisCells - 1
        if (mPortrait) {
            val vSpaceLeft = (heightSpecSize - longAxisStartPadding - longAxisEndPadding
                    - cellHeight * longAxisCells)
            mHeightGap = vSpaceLeft / numLongGaps
            val hSpaceLeft = (widthSpecSize - shortAxisStartPadding - shortAxisEndPadding
                    - cellWidth * shortAxisCells)
            mWidthGap = if (numShortGaps > 0) {
                hSpaceLeft / numShortGaps
            } else {
                0
            }
        } else {
            val hSpaceLeft = (widthSpecSize - longAxisStartPadding - longAxisEndPadding
                    - cellWidth * longAxisCells)
            mWidthGap = hSpaceLeft / numLongGaps
            val vSpaceLeft = (heightSpecSize - shortAxisStartPadding - shortAxisEndPadding
                    - cellHeight * shortAxisCells)
            mHeightGap = if (numShortGaps > 0) {
                vSpaceLeft / numShortGaps
            } else {
                0
            }
        }
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            if (mPortrait) {
                lp.setup(cellWidth, cellHeight, mWidthGap, mHeightGap, shortAxisStartPadding,
                    longAxisStartPadding)
            } else {
                lp.setup(cellWidth, cellHeight, mWidthGap, mHeightGap, longAxisStartPadding,
                    shortAxisStartPadding)
            }
            if (lp.regenerateId) {
                child.id = id and 0xFF shl 16 or (lp.cellX and 0xFF shl 8) or (lp.cellY and 0xFF)
                lp.regenerateId = false
            }
            val childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
            val childheightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            child.measure(childWidthMeasureSpec, childheightMeasureSpec)
        }
        setMeasuredDimension(widthSpecSize, heightSpecSize)
        if (mThumb == null) initThumb(widthSpecSize shr 2, heightSpecSize shr 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                val lp = child.layoutParams as LayoutParams
                val childLeft = lp.x
                val childTop = lp.y
                child.layout(childLeft, childTop, childLeft + lp.width, childTop + lp.height)
            }
        }
        layoutDrawed = false
    }

    private var mThumb: Bitmap? = null
    private var mThumbCanvas: Canvas? = null
    private var mThumbPaint: Paint? = null
    private var layoutDrawed = false
    private fun initThumb(width: Int, height: Int) {
        if (mThumb == null || mThumb!!.isRecycled) mThumb = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_4444)
        val matrix = Matrix()
        matrix.setScale(0.25f, 0.25f)
        mThumbCanvas = Canvas(mThumb!!)
        mThumbCanvas!!.concat(matrix)
        mThumbPaint = Paint()
        mThumbPaint!!.isDither = true
        mThumbPaint!!.isAntiAlias = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (mThumb != null) mThumb!!.recycle()
        mThumb = null
        mThumbCanvas = null
    }

    public override fun setChildrenDrawingCacheEnabled(enabled: Boolean) {
        val count = childCount
        for (i in 0 until count) {
            val view = getChildAt(i)
            view.isDrawingCacheEnabled = enabled
            // reduce cache quality to reduce memory usage
            view.drawingCacheQuality = DRAWING_CACHE_QUALITY_HIGH
            // Update the drawing caches
            view.buildDrawingCache(true)
        }
    }

    public override fun setChildrenDrawnWithCacheEnabled(enabled: Boolean) {
        super.setChildrenDrawnWithCacheEnabled(enabled)
    }

    /**
     * Find a vacant area that will fit the given bounds nearest the requested
     * cell location. Uses Euclidean distance to score multiple vacant areas.
     *
     * @param pixelX The X location at which you want to search for a vacant area.
     * @param pixelY The Y location at which you want to search for a vacant area.
     * @param spanX Horizontal span of the object.
     * @param spanY Vertical span of the object.
     * @param vacantCells Pre-computed set of vacant cells to search.
     * @param recycle Previously returned value to possibly recycle.
     * @return The X, Y cell of a vacant area that can contain this object,
     * nearest the requested location.
     */
    fun findNearestVacantArea(
        pixelX: Int, pixelY: Int, spanX: Int, spanY: Int,
        vacantCells: CellInfo?, recycle: IntArray?
    ): IntArray? {

        // Keep track of best-scoring drop area
        val bestXY = recycle ?: IntArray(2)
        val cellXY = mCellXY
        var bestDistance = Double.MAX_VALUE

        // Bail early if vacant cells aren't valid
        if (!vacantCells!!.valid) {
            return null
        }

        // Look across all vacant cells for best fit
        val size = vacantCells.vacantCells.size
        for (i in 0 until size) {
            val cell = vacantCells.vacantCells[i]

            // Reject if vacant cell isn't our exact size
            if (cell!!.spanX != spanX || cell.spanY != spanY) {
                continue
            }

            // Score is center distance from requested pixel
            cellToPoint(cell.cellX, cell.cellY, cellXY)
            val distance = sqrt((cellXY[0] - pixelX).toDouble().pow(2.0) +
                    (cellXY[1] - pixelY).toDouble().pow(2.0))
            if (distance <= bestDistance) {
                bestDistance = distance
                bestXY[0] = cell.cellX
                bestXY[1] = cell.cellY
            }
        }

        // Return null if no suitable location found 
        return if (bestDistance < Double.MAX_VALUE) {
            bestXY
        } else {
            null
        }
    }

    /**
     * Drop a child at the specified position
     *
     * @param child The child that is being dropped
     * @param targetXY Destination area to move to
     */
    fun onDropChild(child: View?, targetXY: IntArray?) {
        if (child != null) {
            val lp = child.layoutParams as LayoutParams
            lp.cellX = targetXY!![0]
            lp.cellY = targetXY[1]
            lp.isDragging = false
            mDragRect.setEmpty()
            child.requestLayout()
            invalidate()
        }
    }

    fun onDropAborted(child: View?) {
        if (child != null) {
            (child.layoutParams as LayoutParams).isDragging = false
            invalidate()
        }
        mDragRect.setEmpty()
    }

    /**
     * Start dragging the specified child
     *
     * @param child The child that is being dragged
     */
    fun onDragChild(child: View?) {
        val lp = child!!.layoutParams as LayoutParams
        lp.isDragging = true
        mDragRect.setEmpty()
    }

    /**
     * Computes the required horizontal and vertical cell spans to always
     * fit the given rectangle.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     */
    fun rectToCell(width: Int, height: Int): IntArray {
        // Always assume we're working with the smallest span to make sure we
        // reserve enough space in both orientations.
        val resources = resources
        val actualWidth = resources.getDimensionPixelSize(R.dimen.cell_width)
        val actualHeight = resources.getDimensionPixelSize(R.dimen.cell_height)
        val smallerSize = min(actualWidth, actualHeight)

        // Always round up to next largest cell
        val spanX = (width + smallerSize) / smallerSize
        val spanY = (height + smallerSize) / smallerSize
        return intArrayOf(spanX, spanY)
    }

    val occupiedCells: BooleanArray
        get() {
            val portrait = mPortrait
            val xCount = if (portrait) mShortAxisCells else mLongAxisCells
            val yCount = if (portrait) mLongAxisCells else mShortAxisCells
            val occupied = mOccupied
            findOccupiedCells(xCount, yCount, occupied, null)
            val flat = BooleanArray(xCount * yCount)
            for (y in 0 until yCount) {
                for (x in 0 until xCount) {
                    flat[y * xCount + x] = occupied!![x][y]
                }
            }
            return flat
        }

    private fun findOccupiedCells(xCount: Int, yCount: Int, occupied: Array<BooleanArray>?, ignoreView: View?) {
        for (x in 0 until xCount) {
            for (y in 0 until yCount) {
                occupied!![x][y] = false
            }
        }
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child is Folder || child == ignoreView) {
                continue
            }
            val lp = child.layoutParams as LayoutParams
            var x = lp.cellX
            while (x < lp.cellX + lp.cellHSpan && x < xCount) {
                var y = lp.cellY
                while (y < lp.cellY + lp.cellVSpan && y < yCount) {
                    occupied!![x][y] = true
                    y++
                }
                x++
            }
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): ViewGroup.LayoutParams {
        return LayoutParams(context, attrs)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean {
        return p is LayoutParams
    }

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        return LayoutParams(p)
    }

    class LayoutParams : MarginLayoutParams {
        /**
         * Horizontal location of the item in the grid.
         */
        @ExportedProperty
        var cellX = 0

        /**
         * Vertical location of the item in the grid.
         */
        @ExportedProperty
        var cellY = 0

        /**
         * Number of cells spanned horizontally by the item.
         */
        @ExportedProperty
        var cellHSpan: Int

        /**
         * Number of cells spanned vertically by the item.
         */
        @ExportedProperty
        var cellVSpan: Int

        /**
         * Is this item currently being dragged
         */
        var isDragging = false

        // X coordinate of the view in the layout.
        @ExportedProperty
        var x = 0

        // Y coordinate of the view in the layout.
        @ExportedProperty
        var y = 0
        var regenerateId = false

        constructor(c: Context?, attrs: AttributeSet?) : super(c, attrs) {
            cellHSpan = 1
            cellVSpan = 1
        }

        constructor(source: ViewGroup.LayoutParams?) : super(source) {
            cellHSpan = 1
            cellVSpan = 1
        }

        constructor(cellX: Int, cellY: Int, cellHSpan: Int, cellVSpan: Int) : super(FILL_PARENT, FILL_PARENT) {
            this.cellX = cellX
            this.cellY = cellY
            this.cellHSpan = cellHSpan
            this.cellVSpan = cellVSpan
        }

        fun setup(
            cellWidth: Int, cellHeight: Int, widthGap: Int, heightGap: Int,
            hStartPadding: Int, vStartPadding: Int
        ) {
            val myCellHSpan = cellHSpan
            val myCellVSpan = cellVSpan
            val myCellX = cellX
            val myCellY = cellY
            width = myCellHSpan * cellWidth + (myCellHSpan - 1) * widthGap -
                    leftMargin - rightMargin
            height = myCellVSpan * cellHeight + (myCellVSpan - 1) * heightGap -
                    topMargin - bottomMargin
            x = hStartPadding + myCellX * (cellWidth + widthGap) + leftMargin
            y = vStartPadding + myCellY * (cellHeight + heightGap) + topMargin
        }
    }

    class CellInfo : ContextMenuInfo {
        /**
         * See View.AttachInfo.InvalidateInfo for futher explanations about
         * the recycling mechanism. In this case, we recycle the vacant cells
         * instances because up to several hundreds can be instanciated when
         * the user long presses an empty cell.
         */
        class VacantCell {
            var cellX = 0
            var cellY = 0
            var spanX = 0
            var spanY = 0
            private var next: VacantCell? = null
            fun release() {
                synchronized(sLock) {
                    if (sAcquiredCount < POOL_LIMIT) {
                        sAcquiredCount++
                        next = sRoot
                        sRoot = this
                    }
                }
            }

            override fun toString(): String {
                return "VacantCell[x=" + cellX + ", y=" + cellY + ", spanX=" + spanX +
                        ", spanY=" + spanY + "]"
            }

            companion object {
                // We can create up to 523 vacant cells on a 4x4 grid, 100 seems
                // like a reasonable compromise given the size of a VacantCell and
                // the fact that the user is not likely to touch an empty 4x4 grid
                // very often 
                const val POOL_LIMIT = 100
                private val sLock = Any()
                private var sAcquiredCount = 0
                private var sRoot: VacantCell? = null
                fun acquire(): VacantCell {
                    synchronized(sLock) {
                        if (sRoot == null) {
                            return VacantCell()
                        }
                        val info = sRoot
                        sRoot = info!!.next
                        sAcquiredCount--
                        return info
                    }
                }
            }
        }

        var cell: View? = null
        var cellX = 0
        var cellY = 0
        var spanX = 0
        var spanY = 0
        var screen = 0
        var valid = false
        val vacantCells = ArrayList<VacantCell?>(VacantCell.POOL_LIMIT)
        var maxVacantSpanX = 0
        var maxVacantSpanXSpanY = 0
        var maxVacantSpanY = 0
        var maxVacantSpanYSpanX = 0
        val current = Rect()
        fun clearVacantCells() {
            val list = vacantCells
            val count = list.size
            for (i in 0 until count) list[i]!!.release()
            list.clear()
        }

        fun findVacantCellsFromOccupied(occupied: BooleanArray, xCount: Int, yCount: Int) {
            if (cellX < 0 || cellY < 0) {
                maxVacantSpanXSpanY = Int.MIN_VALUE
                maxVacantSpanX = maxVacantSpanXSpanY
                maxVacantSpanYSpanX = Int.MIN_VALUE
                maxVacantSpanY = maxVacantSpanYSpanX
                clearVacantCells()
                return
            }
            val unflattened = Array(xCount) { BooleanArray(yCount) }
            for (y in 0 until yCount) {
                for (x in 0 until xCount) {
                    unflattened[x][y] = occupied[y * xCount + x]
                }
            }
            findIntersectingVacantCells(this, cellX, cellY, xCount, yCount, unflattened)
        }

        /**
         * This method can be called only once! Calling #findVacantCellsFromOccupied will
         * restore the ability to call this method.
         *
         * Finds the upper-left coordinate of the first rectangle in the grid that can
         * hold a cell of the specified dimensions.
         *
         * @param cellXY The array that will contain the position of a vacant cell if such a cell
         * can be found.
         * @param spanX The horizontal span of the cell we want to find.
         * @param spanY The vertical span of the cell we want to find.
         *
         * @return True if a vacant cell of the specified dimension was found, false otherwise.
         */
        @JvmOverloads
        fun findCellForSpan(cellXY: IntArray, spanX: Int, spanY: Int, clear: Boolean = true): Boolean {
            val list = vacantCells
            val count = list.size
            var found = false
            if (this.spanX >= spanX && this.spanY >= spanY) {
                cellXY[0] = cellX
                cellXY[1] = cellY
                found = true
            }

            // Look for an exact match first
            for (i in 0 until count) {
                val cell = list[i]
                if (cell!!.spanX == spanX && cell.spanY == spanY) {
                    cellXY[0] = cell.cellX
                    cellXY[1] = cell.cellY
                    found = true
                    break
                }
            }

            // Look for the first cell large enough
            for (i in 0 until count) {
                val cell = list[i]
                if (cell!!.spanX >= spanX && cell.spanY >= spanY) {
                    cellXY[0] = cell.cellX
                    cellXY[1] = cell.cellY
                    found = true
                    break
                }
            }
            if (clear) clearVacantCells()
            return found
        }

        override fun toString(): String {
            return "Cell[view=" + (if (cell == null) "null" else cell!!.javaClass) + ", x=" + cellX +
                    ", y=" + cellY + "]"
        }
    }

    companion object {
        private fun findIntersectingVacantCells(
            cellInfo: CellInfo, x: Int, y: Int,
            xCount: Int, yCount: Int, occupied: Array<BooleanArray>?
        ) {
            cellInfo.maxVacantSpanX = Int.MIN_VALUE
            cellInfo.maxVacantSpanXSpanY = Int.MIN_VALUE
            cellInfo.maxVacantSpanY = Int.MIN_VALUE
            cellInfo.maxVacantSpanYSpanX = Int.MIN_VALUE
            cellInfo.clearVacantCells()
            if (occupied!![x][y]) {
                return
            }
            cellInfo.current[x, y, x] = y
            findVacantCell(cellInfo.current, xCount, yCount, occupied, cellInfo)
        }

        private fun findVacantCell(
            current: Rect, xCount: Int, yCount: Int, occupied: Array<BooleanArray>?,
            cellInfo: CellInfo
        ) {
            addVacantCell(current, cellInfo)
            if (current.left > 0) {
                if (isColumnEmpty(current.left - 1, current.top, current.bottom, occupied)) {
                    current.left--
                    findVacantCell(current, xCount, yCount, occupied, cellInfo)
                    current.left++
                }
            }
            if (current.right < xCount - 1) {
                if (isColumnEmpty(current.right + 1, current.top, current.bottom, occupied)) {
                    current.right++
                    findVacantCell(current, xCount, yCount, occupied, cellInfo)
                    current.right--
                }
            }
            if (current.top > 0) {
                if (isRowEmpty(current.top - 1, current.left, current.right, occupied)) {
                    current.top--
                    findVacantCell(current, xCount, yCount, occupied, cellInfo)
                    current.top++
                }
            }
            if (current.bottom < yCount - 1) {
                if (isRowEmpty(current.bottom + 1, current.left, current.right, occupied)) {
                    current.bottom++
                    findVacantCell(current, xCount, yCount, occupied, cellInfo)
                    current.bottom--
                }
            }
        }

        private fun addVacantCell(current: Rect, cellInfo: CellInfo) {
            val cell = VacantCell.acquire()
            cell.cellX = current.left
            cell.cellY = current.top
            cell.spanX = current.right - current.left + 1
            cell.spanY = current.bottom - current.top + 1
            if (cell.spanX > cellInfo.maxVacantSpanX) {
                cellInfo.maxVacantSpanX = cell.spanX
                cellInfo.maxVacantSpanXSpanY = cell.spanY
            }
            if (cell.spanY > cellInfo.maxVacantSpanY) {
                cellInfo.maxVacantSpanY = cell.spanY
                cellInfo.maxVacantSpanYSpanX = cell.spanX
            }
            cellInfo.vacantCells.add(cell)
        }

        private fun isColumnEmpty(x: Int, top: Int, bottom: Int, occupied: Array<BooleanArray>?): Boolean {
            for (y in top..bottom) {
                if (occupied!![x][y]) {
                    return false
                }
            }
            return true
        }

        private fun isRowEmpty(y: Int, left: Int, right: Int, occupied: Array<BooleanArray>?): Boolean {
            for (x in left..right) {
                if (occupied!![x][y]) {
                    return false
                }
            }
            return true
        }

        fun findVacantCell(
            vacant: IntArray, spanX: Int, spanY: Int,
            xCount: Int, yCount: Int, occupied: Array<BooleanArray>?
        ): Boolean {
            for (x in 0 until xCount) {
                for (y in 0 until yCount) {
                    var available = !occupied!![x][y]
                    var i = x
                    out@ while (i < x + spanX - 1 && x < xCount) {
                        var j = y
                        while (j < y + spanY - 1 && y < yCount) {
                            available = available && !occupied[i][j]
                            if (!available) break@out
                            j++
                        }
                        i++
                    }
                    if (available) {
                        vacant[0] = x
                        vacant[1] = y
                        return true
                    }
                }
            }
            return false
        }
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.CellLayout, defStyle, 0)
        mCellWidth = a.getDimensionPixelSize(R.styleable.CellLayout_cellWidth, 10)
        mCellHeight = a.getDimensionPixelSize(R.styleable.CellLayout_cellHeight, 10)
        mLongAxisStartPadding = a.getDimensionPixelSize(R.styleable.CellLayout_longAxisStartPadding, 10)
        mLongAxisEndPadding = a.getDimensionPixelSize(R.styleable.CellLayout_longAxisEndPadding, 10)
        mShortAxisStartPadding = a.getDimensionPixelSize(R.styleable.CellLayout_shortAxisStartPadding, 10)
        mShortAxisEndPadding = a.getDimensionPixelSize(R.styleable.CellLayout_shortAxisEndPadding, 10)
        mShortAxisCells = a.getInt(R.styleable.CellLayout_shortAxisCells, 4)
        mLongAxisCells = a.getInt(R.styleable.CellLayout_longAxisCells, 4)
        a.recycle()
        isAlwaysDrawnWithCacheEnabled = false
        if (mOccupied == null) {
            mOccupied = if (mPortrait) {
                Array(mShortAxisCells) { BooleanArray(mLongAxisCells) }
            } else {
                Array(mLongAxisCells) { BooleanArray(mShortAxisCells) }
            }
        }
    }
}