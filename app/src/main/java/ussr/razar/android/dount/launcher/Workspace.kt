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

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.os.Parcelable
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.Scroller
import android.widget.TextView
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites
import ussr.razar.android.widget.WidgetLayout
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The workspace is a wide area with a wallpaper and a finite number of screens. Each screen
 * contains a number of icons, folders or widgets the user can interact with. A workspace is meant
 * to be used with a fixed width only.
 */
class Workspace @JvmOverloads constructor(context: Context, attrs: AttributeSet?, defStyle: Int = 0) :
    ViewGroup(context, attrs, defStyle), DropTarget, DragSource, DragScroller {
    private var mDefaultScreen = 0
    private val mWallpaperManager: WallpaperManager = WallpaperManager.getInstance(context)
    private var mFirstLayout = true
    private var mNextScreen = INVALID_SCREEN
    private var mScroller: Scroller? = null
    private var mAllowLongPress = false
    private var mVelocityTracker: VelocityTracker? = null

    /**
     * CellInfo for the cell that is currently being dragged
     */
    private var mDragInfo: CellLayout.CellInfo? = null

    /**
     * Target drop area calculated during last acceptDrop call.
     */
    private var mTargetCell: IntArray? = null
    private var mLastMotionX = 0f
    private var mLastMotionY = 0f
    private var mTouchState = TOUCH_STATE_REST
    private var mLongClickListener: OnLongClickListener? = null
    private lateinit var mLauncher: Launcher
    private var mDragger: DragController? = null

    /**
     * Cache of vacant cells, used during drag events and invalidated as needed.
     */
    private var mVacantCache: CellLayout.CellInfo? = null
    private val mTempEstimate = IntArray(2)
    private var mLocked = false
    private var mTouchSlop = 0
    private var mMaximumVelocity = 0
    val mDrawerBounds = Rect()
    private val mClipBounds = Rect()
    var mDrawerContentHeight = 0
    var mDrawerContentWidth = 0

    /**
     * Initializes various states for this workspace.
     */
    private fun initWorkspace() {
        mScroller = Scroller(context)
        currentScreen = mDefaultScreen
        Launcher.screen= (currentScreen)
        val configuration = ViewConfiguration.get(context)
        mTouchSlop = configuration.scaledTouchSlop
        mMaximumVelocity = configuration.scaledMaximumFlingVelocity
    }

    override fun addView(child: View, index: Int, params: LayoutParams) {
        require(child is CellLayout) { "A Workspace can only have CellLayout children." }
        super.addView(child, index, params)
    }

    override fun addView(child: View) {
        require(child is CellLayout) { "A Workspace can only have CellLayout children." }
        super.addView(child)
    }

    override fun addView(child: View, index: Int) {
        require(child is CellLayout) { "A Workspace can only have CellLayout children." }
        super.addView(child, index)
    }

    override fun addView(child: View, width: Int, height: Int) {
        require(child is CellLayout) { "A Workspace can only have CellLayout children." }
        super.addView(child, width, height)
    }

    override fun addView(child: View, params: LayoutParams) {
        require(child is CellLayout) { "A Workspace can only have CellLayout children." }
        super.addView(child, params)
    }

    /**
     * @return The open folder on the current screen, or null if there is none
     */
    val openFolder: Folder?
        get() {
            val currentScreen = getChildAt(currentScreen) as CellLayout
            val count = currentScreen.childCount
            for (i in 0 until count) {
                val child = currentScreen.getChildAt(i)
                val lp = child.layoutParams as CellLayout.LayoutParams
                if (lp.cellHSpan == 4 && lp.cellVSpan == 4 && child is Folder) {
                    return child
                }
            }
            return null
        }
    val openFolders: ArrayList<Folder>
        get() {
            val screens = childCount
            val folders = ArrayList<Folder>(screens)
            for (screen in 0 until screens) {
                val currentScreen = getChildAt(screen) as CellLayout
                val count = currentScreen.childCount
                for (i in 0 until count) {
                    val child = currentScreen.getChildAt(i)
                    val lp = child.layoutParams as CellLayout.LayoutParams
                    if (lp.cellHSpan == 4 && lp.cellVSpan == 4 && child is Folder) {
                        folders.add(child)
                        break
                    }
                }
            }
            return folders
        }

    fun resetDefaultScreen(screen: Int) {
        if (screen >= childCount || screen < 0) {
            Log.e("H++ Workspace", "Cannot reset default screen to $screen")
            return
        }
        mDefaultScreen = screen
    }

    /**
     * Returns the index of the currently displayed screen.
     *
     * @return The index of the currently displayed screen.
     */

    var currentScreen: Int = 0
        set(currentScreen) {
            clearVacantCache()
            field = max(0, min(currentScreen, childCount - 1))
            scrollTo(currentScreen * width, 0)
            invalidate()
        }

    /**
     * Adds the specified child in the current screen. The position and dimension of the child are
     * defined by x, y, spanX and spanY.
     *
     * @param child
     * The child to add in one of the workspace's screens.
     * @param x
     * The X position of the child in the screen's grid.
     * @param y
     * The Y position of the child in the screen's grid.
     * @param spanX
     * The number of cells spanned horizontally by the child.
     * @param spanY
     * The number of cells spanned vertically by the child.
     */
    fun addInCurrentScreen(child: View?, x: Int, y: Int, spanX: Int, spanY: Int) {
        addInScreen(child, currentScreen, x, y, spanX, spanY, false)
    }

    /**
     * Adds the specified child in the current screen. The position and dimension of the child are
     * defined by x, y, spanX and spanY.
     *
     * @param child
     * The child to add in one of the workspace's screens.
     * @param x
     * The X position of the child in the screen's grid.
     * @param y
     * The Y position of the child in the screen's grid.
     * @param spanX
     * The number of cells spanned horizontally by the child.
     * @param spanY
     * The number of cells spanned vertically by the child.
     * @param insert
     * When true, the child is inserted at the beginning of the children list.
     */
    fun addInCurrentScreen(child: View?, x: Int, y: Int, spanX: Int, spanY: Int, insert: Boolean) {
        addInScreen(child, currentScreen, x, y, spanX, spanY, insert)
    }

    /**
     * Adds the specified child in the specified screen. The position and dimension of the child are
     * defined by x, y, spanX and spanY.
     *
     * @param child
     * The child to add in one of the workspace's screens.
     * @param screen
     * The screen in which to add the child.
     * @param x
     * The X position of the child in the screen's grid.
     * @param y
     * The Y position of the child in the screen's grid.
     * @param spanX
     * The number of cells spanned horizontally by the child.
     * @param spanY
     * The number of cells spanned vertically by the child.
     */
    @JvmOverloads
    fun addInScreen(child: View?, screen: Int, x: Int, y: Int, spanX: Int, spanY: Int, insert: Boolean = false) {
        if (screen < 0 || screen >= childCount) {
            Log.e(Launcher.LOG_TAG, "The screen must be >= 0 and < " + childCount
                    + ". Now you are querying " + screen)
            return
            // throw new IllegalStateException("The screen must be >= 0 and < " + getChildCount());
        }
        clearVacantCache()
        val group = getChildAt(screen) as CellLayout
        var lp = child?.layoutParams as CellLayout.LayoutParams?
        if (lp == null) {
            lp = CellLayout.LayoutParams(x, y, spanX, spanY)
        } else {
            lp.cellX = x
            lp.cellY = y
            lp.cellHSpan = spanX
            lp.cellVSpan = spanY
        }
        child?.let { group.addView(it, if (insert) 0 else -1, lp) }
        if (child !is Folder) {
            child?.setOnLongClickListener(mLongClickListener)
        }
    }

    fun addWidget(view: View?, widget: Widget?, insert: Boolean) {
        addInScreen(view, widget!!.screen, widget.cellX, widget.cellY, widget.spanX, widget.spanY,
            insert)
    }

    fun findAllVacantCells(occupied: BooleanArray?): CellLayout.CellInfo {
        val group = getChildAt(currentScreen) as CellLayout
        return group.findAllVacantCells(occupied, null)
    }

    fun findAllVacantCellsFromModel(): CellLayout.CellInfo {
        val group = getChildAt(currentScreen) as CellLayout
        val countX = group.countX
        val countY = group.countY
        val occupied = Array(countX) { BooleanArray(countY) }
        Launcher.model.findAllOccupiedCells(occupied, currentScreen)
        return group.findAllVacantCellsFromOccupied(occupied, countX, countY)
    }

    private fun clearVacantCache() {
        if (mVacantCache != null) {
            mVacantCache!!.clearVacantCells()
            mVacantCache = null
        }
    }

    /**
     * Registers the specified listener on each screen contained in this workspace.
     *
     * @param l
     * The listener used to respond to long clicks.
     */
    override fun setOnLongClickListener(l: OnLongClickListener?) {
        mLongClickListener = l
        val count = childCount
        for (i in 0 until count) {
            getChildAt(i).setOnLongClickListener(l)
        }
    }

    private fun updateWallpaperOffset(scrollRange: Int = getChildAt(childCount - 1).right - (right - left)) {
        // TODO mWallpaperManager.setWallpaperOffsetSteps(1.0f / (getChildCount() - 1), 0 );
        try {
            mWallpaperManager.setWallpaperOffsets(windowToken, scrollX
                    / scrollRange.toFloat(), 0f)
            val setWallpaperOffsetSteps = mWallpaperManager.javaClass.getMethod(
                "setWallpaperOffsetSteps", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            setWallpaperOffsetSteps.invoke(mWallpaperManager, 1.0f / (childCount - 1), 0)
        } catch (e: Exception) {
        }
    }

    override fun computeScroll() {
        if (mScroller!!.computeScrollOffset()) {
            scrollTo(mScroller!!.currX, mScroller!!.currY)
            updateWallpaperOffset()
            postInvalidate()
        } else if (mNextScreen != INVALID_SCREEN) {
            val lastScreen = currentScreen
            currentScreen = max(0, min(mNextScreen, childCount - 1))

            // set screen and indicator
            Launcher.screen=(currentScreen)

            // notify widget about screen changed
            var changedView: View?
            if (lastScreen != currentScreen) {
                changedView = getChildAt(lastScreen) // A screen get out
                if (changedView is WidgetLayout) changedView.onViewportOut()
            }
            changedView = getChildAt(currentScreen) // A screen get in
            if (changedView is WidgetLayout) changedView.onViewportIn()
            mNextScreen = INVALID_SCREEN
            clearChildrenCache()
        }
    }

    override fun isOpaque(): Boolean {
        return false
    }

    override fun dispatchDraw(canvas: Canvas) {
        var restore = false

        // If the all apps drawer is open and the drawing region for the workspace
        // is contained within the drawer's bounds, we skip the drawing. This requires
        // the drawer to be fully opaque.
        if (mLauncher.isDrawerUp) {
            val clipBounds = mClipBounds
            canvas.getClipBounds(clipBounds)
            clipBounds.offset(-scrollX, -scrollY)
            if (mDrawerBounds.contains(clipBounds)) {
                return
            }
        } else if (mLauncher.isDrawerMoving) {
            restore = true
            canvas.save()
            val view = mLauncher.drawerHandle
            val top = view!!.top + view.height
            canvas.clipRect(scrollX.toFloat(), top.toFloat(), (scrollX + mDrawerContentWidth).toFloat(), (top
                    + mDrawerContentHeight).toFloat(), Region.Op.DIFFERENCE)
        }

        // ViewGroup.dispatchDraw() supports many features we don't need:
        // clip to padding, layout animation, animation listener, disappearing
        // children, etc. The following implementation attempts to fast-track
        // the drawing dispatch by drawing only what we know needs to be drawn.
        val fastDraw = mTouchState != TOUCH_STATE_SCROLLING && mNextScreen == INVALID_SCREEN
        // If we are not scrolling or flinging, draw only the current screen
        if (fastDraw) {
            drawChild(canvas, getChildAt(currentScreen), drawingTime)
        } else {
            val drawingTime = drawingTime
            // If we are flinging, draw only the current screen and the target screen
            if (mNextScreen in 0 until childCount && abs(currentScreen - mNextScreen) == 1) {
                drawChild(canvas, getChildAt(currentScreen), drawingTime)
                drawChild(canvas, getChildAt(mNextScreen), drawingTime)
            } else {
                // If we are scrolling, draw all of our children
                val count = childCount
                for (i in 0 until count) {
                    drawChild(canvas, getChildAt(i), drawingTime)
                }
            }
        }
        if (restore) {
            canvas.restore()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        check(widthMode == MeasureSpec.EXACTLY) { "Workspace can only be used in EXACTLY mode." }
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        check(heightMode == MeasureSpec.EXACTLY) { "Workspace can only be used in EXACTLY mode." }

        // The children are given the same width and height as the workspace
        val count = childCount
        for (i in 0 until count) {
            getChildAt(i).measure(widthMeasureSpec, heightMeasureSpec)
        }
        if (mFirstLayout) {
            scrollTo(currentScreen * width, 0)
            updateWallpaperOffset(width * (childCount - 1))
            mFirstLayout = false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var childLeft = 0
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                val childWidth = child.measuredWidth
                child.layout(childLeft, 0, childLeft + childWidth, child.measuredHeight)
                childLeft += childWidth
            }
        }
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        val screen = indexOfChild(child)
        if (screen != currentScreen || !mScroller!!.isFinished) {
            if (!mLauncher.isWorkspaceLocked) {
                snapToScreen(screen)
            }
            return true
        }
        return false
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (mLauncher.isDrawerDown) {
            val openFolder = openFolder
            if (openFolder != null) {
                return openFolder.requestFocus(direction, previouslyFocusedRect)
            } else {
                val focusableScreen: Int = if (mNextScreen != INVALID_SCREEN) {
                    mNextScreen
                } else {
                    currentScreen
                }
                getChildAt(focusableScreen).requestFocus(direction, previouslyFocusedRect)
            }
        }
        return false
    }

    override fun dispatchUnhandledMove(focused: View?, direction: Int): Boolean {
        if (direction == FOCUS_LEFT) {
            if (currentScreen > 0) {
                snapToScreen(currentScreen - 1)
                return true
            }
        } else if (direction == FOCUS_RIGHT) {
            if (currentScreen < childCount - 1) {
                snapToScreen(currentScreen + 1)
                return true
            }
        }
        return super.dispatchUnhandledMove(focused, direction)
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (mLauncher.isDrawerDown) {
            val openFolder = openFolder
            if (openFolder == null) {
                getChildAt(currentScreen).addFocusables(views, direction)
                if (direction == FOCUS_LEFT) {
                    if (currentScreen > 0) {
                        getChildAt(currentScreen - 1).addFocusables(views, direction)
                    }
                } else if (direction == FOCUS_RIGHT) {
                    if (currentScreen < childCount - 1) {
                        getChildAt(currentScreen + 1).addFocusables(views, direction)
                    }
                }
            } else {
                openFolder.addFocusables(views, direction)
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (mLocked || !mLauncher.isDrawerDown) {
            return true
        }

        /*
         * This method JUST determines whether we want to intercept the motion. If we return true,
         * onTouchEvent will be called and we do the actual scrolling there.
         */

        /*
         * Shortcut the most recurring case: the user is in the dragging state and he is moving his
         * finger. We want to intercept this motion.
         */
        val action = ev.action
        if (action == MotionEvent.ACTION_MOVE && mTouchState != TOUCH_STATE_REST) {
            return true
        }
        val x = ev.x
        val y = ev.y
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                /*
             * mIsBeingDragged == false, otherwise the shortcut would have caught it. Check whether
             * the user has moved far enough from his original down touch.
             */

                /*
             * Locally do absolute value. mLastMotionX is set to the y value of the down event.
             */
                val xDiff = abs(x - mLastMotionX).toInt()
                val yDiff = abs(y - mLastMotionY).toInt()
                val touchSlop = mTouchSlop
                val xMoved = xDiff > touchSlop
                val yMoved = yDiff > touchSlop
                if (xMoved || yMoved) {
                    if (xMoved) {
                        // Scroll if the user moved far enough along the X axis
                        mTouchState = TOUCH_STATE_SCROLLING
                        enableChildrenCache()
                    }
                    // Either way, cancel any pending longpress
                    if (allowLongPress()) {
                        setAllowLongPress(true)
                        // Try canceling the long press. It could also have been scheduled
                        // by a distant descendant, so use the mAllowLongPress flag to block
                        // everything
                        val currentScreen = getChildAt(currentScreen)
                        currentScreen.cancelLongPress()
                    }
                }
            }
            MotionEvent.ACTION_DOWN -> {
                // Remember location of down touch
                mLastMotionX = x
                mLastMotionY = y
                setAllowLongPress(true)

                /*
             * If being flinged and user touches the screen, initiate drag; otherwise don't.
             * mScroller.isFinished should be false when being flinged.
             */mTouchState = if (mScroller!!.isFinished) TOUCH_STATE_REST else TOUCH_STATE_SCROLLING
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                // Release the drag
                clearChildrenCache()
                mTouchState = TOUCH_STATE_REST
                setAllowLongPress(false)
            }
        }

        /*
         * The only time we want to intercept motion events is if we are in the drag mode.
         */return mTouchState != TOUCH_STATE_REST
    }

    private fun enableChildrenCache() {
        val count = childCount
        for (i in 0 until count) {
            val layout = getChildAt(i) as CellLayout
            layout.drawingCacheQuality = DRAWING_CACHE_QUALITY_LOW
            layout.isChildrenDrawnWithCacheEnabled = true
            layout.setChildrenDrawingCacheEnabled(true)
        }
    }

    private fun clearChildrenCache() {
        val count = childCount
        for (i in 0 until count) {
            val layout = getChildAt(i) as CellLayout
            layout.isChildrenDrawnWithCacheEnabled = false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mLocked || !mLauncher.isDrawerDown) {
            return true
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain()
        }
        mVelocityTracker!!.addMovement(ev)
        val action = ev.action
        val x = ev.x
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                /*
             * If being flinged and user touches, stop the fling. isFinished will be false if being
             * flinged.
             */if (!mScroller!!.isFinished) {
                    mScroller!!.abortAnimation()
                }

                // Remember where the motion event started
                mLastMotionX = x
            }
            MotionEvent.ACTION_MOVE -> if (mTouchState == TOUCH_STATE_SCROLLING) {
                // Scroll to follow the motion event
                val deltaX = (mLastMotionX - x).toInt()
                mLastMotionX = x
                if (deltaX < 0) {
                    if (scrollX > 0) {
                        scrollBy(max(-scrollX, deltaX), 0)
                        updateWallpaperOffset()
                    }
                } else if (deltaX > 0) {
                    val availableToScroll = (getChildAt(childCount - 1).right
                            - scrollX - width)
                    if (availableToScroll > 0) {
                        scrollBy(min(availableToScroll, deltaX), 0)
                        updateWallpaperOffset()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mTouchState == TOUCH_STATE_SCROLLING) {
                    val velocityTracker = mVelocityTracker
                    velocityTracker!!.computeCurrentVelocity(1000, mMaximumVelocity.toFloat())
                    val velocityX = velocityTracker.xVelocity.toInt()
                    if (velocityX > SNAP_VELOCITY && currentScreen > 0) {
                        // Fling hard enough to move left
                        snapToScreen(currentScreen - 1)
                    } else if (velocityX < -SNAP_VELOCITY && currentScreen < childCount - 1) {
                        // Fling hard enough to move right
                        snapToScreen(currentScreen + 1)
                    } else {
                        snapToDestination()
                    }
                    if (mVelocityTracker != null) {
                        mVelocityTracker!!.recycle()
                        mVelocityTracker = null
                    }
                }
                mTouchState = TOUCH_STATE_REST
            }
            MotionEvent.ACTION_CANCEL -> mTouchState = TOUCH_STATE_REST
        }
        return true
    }

    private fun snapToDestination() {
        val screenWidth = width
        val whichScreen = (scrollX + screenWidth / 2) / screenWidth
        snapToScreen(whichScreen)
    }

    private fun snapToScreen(whichScreen: Int) {
        var whichScreen = whichScreen
        if (!mScroller!!.isFinished) return
        clearVacantCache()
        enableChildrenCache()
        whichScreen = max(0, min(whichScreen, childCount - 1))
        val changingScreens = whichScreen != currentScreen
        mNextScreen = whichScreen
        val focusedChild = focusedChild
        if (focusedChild != null && changingScreens && focusedChild === getChildAt(currentScreen)) {
            focusedChild.clearFocus()
        }
        val newX = whichScreen * width
        val delta = newX - scrollX
        mScroller!!.startScroll(scrollX, 0, delta, 0, abs(delta) * 2)
        invalidate()
    }

    fun startDrag(cellInfo: CellLayout.CellInfo) {
        val child = cellInfo.cell

        // Make sure the drag was started by a long press as opposed to a long click.
        // Note that Search takes focus when clicked rather than entering touch mode
        if (!child!!.isInTouchMode && child !is Search) {
            return
        }
        mDragInfo = cellInfo
        mDragInfo!!.screen = currentScreen
        val current = getChildAt(currentScreen) as CellLayout
        current.onDragChild(child)
        mDragger!!.startDrag(child, this, child.tag, DragController.DRAG_ACTION_MOVE)
        invalidate()
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = SavedState(super.onSaveInstanceState())
        state.currentScreen = currentScreen
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val savedState = state as SavedState
        super.onRestoreInstanceState(savedState.superState)
        if (savedState.currentScreen != -1) {
            currentScreen = savedState.currentScreen
            Launcher.screen=(currentScreen)
        }
    }

    fun addApplicationShortcut(
        info: ApplicationInfo?, cellInfo: CellLayout.CellInfo,
        insertAtFirst: Boolean,
    ) {
        val layout = getChildAt(cellInfo.screen) as CellLayout
        val result = IntArray(2)
        layout.cellToPoint(cellInfo.cellX, cellInfo.cellY, result)
        onDropExternal(result[0], result[1], info, layout, insertAtFirst)
    }

    override fun onDrop(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {
        val cellLayout = currentDropLayout
        if (source !== this) {
            onDropExternal(x - xOffset, y - yOffset, dragInfo, cellLayout)
        } else {
            // Move internally
            if (mDragInfo != null) {
                val cell = mDragInfo!!.cell
                val index = if (mScroller!!.isFinished) currentScreen else mNextScreen
                if (index != mDragInfo!!.screen) {
                    val originalCellLayout = getChildAt(mDragInfo!!.screen) as CellLayout
                    originalCellLayout.removeView(cell)
                    cellLayout.addView(cell)
                }
                mTargetCell = estimateDropCell(x - xOffset, y - yOffset, mDragInfo!!.spanX,
                    mDragInfo!!.spanY, cell, cellLayout, mTargetCell)
                cellLayout.onDropChild(cell, mTargetCell)
                val info = cell!!.tag as ItemInfo
                val lp = cell.layoutParams as CellLayout.LayoutParams
                LauncherModel.moveItemInDatabase(mLauncher, info,
                    Favorites.CONTAINER_DESKTOP.toLong(), index, lp.cellX, lp.cellY)
            }
        }
    }

    override fun onDragEnter(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?,
    ) {
        clearVacantCache()
    }

    override fun onDragOver(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?,
    ) {
    }

    override fun onDragExit(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?,
    ) {
        clearVacantCache()
    }

    private fun onDropExternal(
        x: Int, y: Int, dragInfo: Any?, cellLayout: CellLayout,
        insertAtFirst: Boolean = false,
    ) {
        // Drag from somewhere else
        var info = dragInfo as ItemInfo?
        val view: View?
        when (info!!.itemType) {
            BaseLauncherColumns.ITEM_TYPE_APPLICATION, BaseLauncherColumns.ITEM_TYPE_SHORTCUT -> {
                if (info.container == NO_ID.toLong()) {
                    // Came from all apps -- make a copy
                    info = ApplicationInfo(info as ApplicationInfo)
                }
                view = mLauncher.createShortcut(R.layout.application, cellLayout,
                    info as ApplicationInfo?)
            }
            Favorites.ITEM_TYPE_USER_FOLDER -> view = FolderIcon.fromXml(R.layout.folder_icon, mLauncher,
                getChildAt(currentScreen) as ViewGroup, info as UserFolderInfo?)
            else -> throw IllegalStateException("Unknown item type: " + info.itemType)
        }
        cellLayout.addView(view, if (insertAtFirst) 0 else -1)
        view.setOnLongClickListener(mLongClickListener)
        mTargetCell = estimateDropCell(x, y, 1, 1, view, cellLayout, mTargetCell)
        cellLayout.onDropChild(view, mTargetCell)
        val lp = view.layoutParams as CellLayout.LayoutParams
        val model: LauncherModel = Launcher.model
        model.addDesktopItem(info)
        LauncherModel.addOrMoveItemInDatabase(mLauncher, info,
            Favorites.CONTAINER_DESKTOP.toLong(), currentScreen, lp.cellX, lp.cellY)
    }

    /**
     * Return the current [CellLayout], correctly picking the destination screen while a
     * scroll is in progress.
     */
    private val currentDropLayout: CellLayout
        private get() {
            val index = if (mScroller!!.isFinished) currentScreen else mNextScreen
            return getChildAt(index) as CellLayout
        }

    /**
     * {@inheritDoc}
     */
    override fun acceptDrop(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?,
    ): Boolean {
        val layout = currentDropLayout
        val cellInfo = mDragInfo
        val spanX = cellInfo?.spanX ?: 1
        val spanY = cellInfo?.spanY ?: 1
        if (mVacantCache == null) {
            val ignoreView = cellInfo?.cell
            mVacantCache = layout.findAllVacantCells(null, ignoreView)
        }
        return mVacantCache!!.findCellForSpan(mTempEstimate, spanX, spanY, false)
    }

    /**
     * Calculate the nearest cell where the given object would be dropped.
     */
    private fun estimateDropCell(
        pixelX: Int, pixelY: Int, spanX: Int, spanY: Int, ignoreView: View?,
        layout: CellLayout, recycle: IntArray?,
    ): IntArray? {
        // Create vacant cell cache if none exists
        if (mVacantCache == null) {
            mVacantCache = layout.findAllVacantCells(null, ignoreView)
        }

        // Find the best target drop location
        return layout.findNearestVacantArea(pixelX, pixelY, spanX, spanY, mVacantCache, recycle)
    }

    fun setLauncher(launcher: Launcher) {
        mLauncher = launcher
    }

    fun setDragger(dragger: DragController?) {
        mDragger = dragger
    }

    override fun onDropCompleted(target: View, success: Boolean) {
        // This is a bit expensive but safe
        clearVacantCache()
        if (success) {
            if (target !== this && mDragInfo != null) {
                val cellLayout = getChildAt(mDragInfo!!.screen) as CellLayout
                cellLayout.removeView(mDragInfo!!.cell)
                val tag = mDragInfo!!.cell!!.tag
                Launcher.model.removeDesktopItem(tag as ItemInfo)
            }
        } else {
            if (mDragInfo != null) {
                val cellLayout = getChildAt(mDragInfo!!.screen) as CellLayout
                cellLayout.onDropAborted(mDragInfo!!.cell)
            }
        }
        mDragInfo = null
    }

    override fun scrollLeft() {
        clearVacantCache()
        if (mNextScreen == INVALID_SCREEN && currentScreen > 0 && mScroller!!.isFinished) {
            snapToScreen(currentScreen - 1)
        }
    }

    override fun scrollRight() {
        clearVacantCache()
        if (mNextScreen == INVALID_SCREEN && currentScreen < childCount - 1 && mScroller!!.isFinished) {
            snapToScreen(currentScreen + 1)
        }
    }

    fun getScreenForView(v: View?): Int {
        val result = -1
        if (v != null) {
            val vp = v.parent
            val count = childCount
            for (i in 0 until count) {
                if (vp === getChildAt(i)) {
                    return i
                }
            }
        }
        return result
    }

    /**
     * Find a search widget on the given screen
     */
    private fun findSearchWidget(screen: CellLayout): Search? {
        val count = screen.childCount
        for (i in 0 until count) {
            val v = screen.getChildAt(i)
            if (v is Search) {
                return v
            }
        }
        return null
    }

    /**
     * Gets the first search widget on the current screen, if there is one. Returns
     * `null` otherwise.
     */
    fun findSearchWidgetOnCurrentScreen(): Search? {
        val currentScreen = getChildAt(currentScreen) as CellLayout
        return findSearchWidget(currentScreen)
    }

    fun getFolderForTag(tag: Any): Folder? {
        val screenCount = childCount
        for (screen in 0 until screenCount) {
            val currentScreen = getChildAt(screen) as CellLayout
            val count = currentScreen.childCount
            for (i in 0 until count) {
                val child = currentScreen.getChildAt(i)
                val lp = child.layoutParams as CellLayout.LayoutParams
                if (lp.cellHSpan == 4 && lp.cellVSpan == 4 && child is Folder) {
                    if (child.info === tag) {
                        return child
                    }
                }
            }
        }
        return null
    }

    fun getViewForTag(tag: Any?): View? {
        val screenCount = childCount
        for (screen in 0 until screenCount) {
            val currentScreen = getChildAt(screen) as CellLayout
            val count = currentScreen.childCount
            for (i in 0 until count) {
                val child = currentScreen.getChildAt(i)
                if (child.tag === tag) {
                    return child
                }
            }
        }
        return null
    }

    /**
     * Unlocks the SlidingDrawer so that touch events are processed.
     *
     * @see .lock
     */
    fun unlock() {
        mLocked = false
    }

    /**
     * @return True is long presses are still allowed for the current touch
     */
    fun allowLongPress(): Boolean {
        return mAllowLongPress
    }

    /**
     * Set true to allow long-press events to be triggered, usually checked by [Launcher] to
     * accept or block dpad-initiated long-presses.
     */
    fun setAllowLongPress(allowLongPress: Boolean) {
        mAllowLongPress = allowLongPress
    }

    fun removeShortcutsForPackage(packageName: String) {
        val childrenToRemove = ArrayList<View>()
        val model: LauncherModel = Launcher.model
        val count = childCount
        for (i in 0 until count) {
            val layout = getChildAt(i) as CellLayout
            var childCount = layout.childCount
            childrenToRemove.clear()
            for (j in 0 until childCount) {
                val view = layout.getChildAt(j)
                val tag = view.tag
                if (tag is ApplicationInfo) {
                    // We need to check for ACTION_MAIN otherwise getComponent() might
                    // return null for some shortcuts (for instance, for shortcuts to
                    // web pages.)
                    val intent = tag.intent
                    val name = intent!!.component
                    if (Intent.ACTION_MAIN == intent.action && name != null && packageName == name.packageName) {
                        model.removeDesktopItem(tag)
                        LauncherModel.deleteItemFromDatabase(mLauncher, tag)
                        childrenToRemove.add(view)
                    }
                } else if (tag is UserFolderInfo) {
                    val contents = tag.contents
                    val toRemove = ArrayList<ApplicationInfo?>(1)
                    val contentsCount = contents.size
                    var removedFromFolder = false
                    for (k in 0 until contentsCount) {
                        val appInfo = contents[k]
                        val intent = appInfo.intent
                        val name = intent!!.component
                        if (Intent.ACTION_MAIN == intent.action && name != null && packageName == name.packageName) {
                            toRemove.add(appInfo)
                            LauncherModel.deleteItemFromDatabase(mLauncher, appInfo)
                            removedFromFolder = true
                        }
                    }
                    contents.removeAll(toRemove)
                    if (removedFromFolder) {
                        val folder = openFolder
                        folder?.notifyDataSetChanged()
                    }
                }
            }
            childCount = childrenToRemove.size
            for (j in 0 until childCount) {
                layout.removeViewInLayout(childrenToRemove[j])
            }
            if (childCount > 0) {
                layout.requestLayout()
                layout.invalidate()
            }
        }
    }

    fun updateShortcutsForPackage(packageName: String) {
        val count = childCount
        for (i in 0 until count) {
            val layout = getChildAt(i) as CellLayout
            val childCount = layout.childCount
            for (j in 0 until childCount) {
                val view = layout.getChildAt(j)
                val tag = view.tag
                if (tag is ApplicationInfo) {
                    // We need to check for ACTION_MAIN otherwise getComponent() might
                    // return null for some shortcuts (for instance, for shortcuts to
                    // web pages.)
                    val intent = tag.intent
                    val name = intent!!.component
                    if (tag.itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION && Intent.ACTION_MAIN == intent.action && name != null && packageName == name.packageName) {
                        val icon: Drawable = Launcher.model.getApplicationInfoIcon(
                            mLauncher.packageManager, tag)!!
                        if (icon != null && icon !== tag.icon) {
                            tag.icon!!.callback = null
                            tag.icon = Utilities.createIconThumbnail(icon, context)
                            tag.filtered = true
                            (view as TextView).setCompoundDrawablesWithIntrinsicBounds(null,
                                tag.icon, null, null)
                        }
                    }
                }
            }
        }
    }

    class SavedState internal constructor(superState: Parcelable?) : BaseSavedState(superState) {
        var currentScreen = -1

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(currentScreen)
        }


    }



    companion object {
        private const val INVALID_SCREEN = -1

        /**
         * The velocity at which a fling gesture will cause us to snap to the next screen
         */
        private const val SNAP_VELOCITY = 1000
        private const val TOUCH_STATE_REST = 0
        private const val TOUCH_STATE_SCROLLING = 1
    }

    init {

        // Try to set default screen from preferences
        try {
            val d = PreferenceManager.getDefaultSharedPreferences(context).getString(
                context.getString(R.string.key_default_screen), "2")
            mDefaultScreen = (d?.toInt()?:0) - 1
        } catch (e: Exception) {
            val a = context
                .obtainStyledAttributes(attrs, R.styleable.Workspace, defStyle, 0)
            mDefaultScreen = a.getInt(R.styleable.Workspace_defaultScreen, 1)
            a.recycle()
        }
        initWorkspace()
    }
}