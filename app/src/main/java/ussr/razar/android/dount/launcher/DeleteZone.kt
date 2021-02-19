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

import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.*
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.*
import android.util.AttributeSet
import android.view.*
import android.view.animation.*
import android.widget.*
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites

class DeleteZone : ImageView, DropTarget, DragController.DragListener {
    private val mLocation = IntArray(2)
    private lateinit var mLauncher: Launcher
    private var mTrashMode = false
    private var mInAnimation: AnimationSet? = null
    private var mOutAnimation: AnimationSet? = null
    private var mHandleInAnimation: Animation? = null
    private var mHandleOutAnimation: Animation? = null
    private var mOrientation = 0
    private var mDragLayer: DragLayer? = null
    private val mRegion = RectF()
    private var mTransition: TransitionDrawable? = null
    private var mHandle: View? = null

    constructor(context: Context?) : super(context)

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int = 0) : super(context, attrs, defStyle) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.DeleteZone, defStyle, 0)
        mOrientation = a.getInt(R.styleable.DeleteZone_direction, ORIENTATION_HORIZONTAL)
        a.recycle()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mTransition = background as TransitionDrawable
    }

    override fun acceptDrop(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ): Boolean {
        return true
    }

    private var mAppInfo: ApplicationInfo? = null
    private var mWidgetInfo: LauncherAppWidgetInfo? = null
    private var mUninstallHandler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_UNINSTALL -> {
                    mFlagUninstall = true
                    Toast.makeText(context, "Drop to uninstall", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Try removing the dropped icon/widget
     */
    private fun removeDropped() {
        if (mFlagUninstall && mUninstallUri != null) {
            mFlagUninstall = false
            try {
                val it = Intent(Intent.ACTION_DELETE, mUninstallUri)
                context.startActivity(it)
            } catch (e: Exception) {
            }
        }
    }

    override fun onDrop(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {
        mUninstallHandler.removeMessages(MSG_UNINSTALL)
        val item = dragInfo as ItemInfo?
        if (item!!.container == -1L) {
            // Remove what dropped in the delete zone
            removeDropped()
            return
        }
        val model: LauncherModel = Launcher.model
        if (item.container == Favorites.CONTAINER_DESKTOP.toLong()) {
            if (item is LauncherAppWidgetInfo) {
                model.removeDesktopAppWidget(item)
            } else {
                model.removeDesktopItem(item)
            }
        } else {
            if (source is UserFolder) {
                val userFolderInfo = source.info as UserFolderInfo
                model.removeUserFolderItem(userFolderInfo, item)
            }
        }
        if (item is UserFolderInfo) {
            LauncherModel.deleteUserFolderContentsFromDatabase(mLauncher, item)
            model.removeUserFolder(item)
        } else if (item is LauncherAppWidgetInfo) {
            val appWidgetHost = mLauncher.appWidgetHost
            appWidgetHost?.deleteAppWidgetId(item.appWidgetId)
        }
        LauncherModel.deleteItemFromDatabase(mLauncher, item)

        // Remove what dropped in the delete zone
        removeDropped()
    }

    var mFlagUninstall = false
    private var mUninstallUri: Uri? = null
    override fun onDragEnter(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ) {
        mTransition!!.reverseTransition(TRANSITION_DURATION)
        mFlagUninstall = false
        mUninstallUri = null
        try {
            if (dragInfo is LauncherAppWidgetInfo) {
                mWidgetInfo = dragInfo

                // Cancel last waiting one
                mUninstallHandler.removeMessages(MSG_UNINSTALL)

                // Compose intent for uninstall
                mUninstallUri = Uri.fromParts("package", AppWidgetManager.getInstance(mLauncher)
                    .getAppWidgetInfo(mWidgetInfo!!.appWidgetId).provider.packageName, null)
                mUninstallHandler.sendEmptyMessageDelayed(MSG_UNINSTALL, UNINSTALL_DURATION.toLong())
            } else if (dragInfo is ApplicationInfo) {
                mAppInfo = dragInfo
                if (mAppInfo == null || mAppInfo!!.intent == null) return
                mUninstallHandler.removeMessages(MSG_UNINSTALL)
                mUninstallUri = Uri.fromParts("package", mAppInfo!!.intent!!.component?.packageName, null)
                mUninstallHandler.sendEmptyMessageDelayed(MSG_UNINSTALL, UNINSTALL_DURATION.toLong())
            }
        } catch (e: Exception) {
        }
    }

    override fun onDragOver(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ) {
    }

    override fun onDragExit(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ) {
        mTransition!!.reverseTransition(TRANSITION_DURATION)
        mUninstallHandler.removeMessages(MSG_UNINSTALL)
    }

    override fun onDragStart(v: View?, source: DragSource?, info: Any?, dragAction: Int) {
        val item = info as ItemInfo?
        if (item != null) {
            mTrashMode = true
            createAnimations()
            val location = mLocation
            getLocationOnScreen(location)
            mRegion[location[0].toFloat(), location[1].toFloat(), (location[0] + right - left).toFloat()] = (
                    location[1] + bottom - top).toFloat()
            mDragLayer!!.setDeleteRegion(mRegion)
            mTransition!!.resetTransition()
            startAnimation(mInAnimation)
            mHandle!!.startAnimation(mHandleOutAnimation)
            visibility = VISIBLE
        }
        sDragging = true
    }

    override fun onDragEnd() {
        if (mTrashMode) {
            mTrashMode = false
            mDragLayer!!.setDeleteRegion(null)
            startAnimation(mOutAnimation)
            mHandle!!.startAnimation(mHandleInAnimation)
            visibility = GONE
        }
        sDragging = false
    }

    private fun createAnimations() {
        if (mInAnimation == null) {
            mInAnimation = FastAnimationSet()
            val animationSet: AnimationSet = mInAnimation!!
            animationSet.interpolator = AccelerateInterpolator()
            animationSet.addAnimation(AlphaAnimation(0.0f, 1.0f))
            if (mOrientation == ORIENTATION_HORIZONTAL) {
                animationSet.addAnimation(TranslateAnimation(Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f, Animation.RELATIVE_TO_SELF, 1.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f))
            } else {
                animationSet.addAnimation(TranslateAnimation(Animation.RELATIVE_TO_SELF,
                    1.0f, Animation.RELATIVE_TO_SELF, 0.0f, Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f))
            }
            animationSet.duration = ANIMATION_DURATION.toLong()
        }
        if (mHandleInAnimation == null) {
            mHandleInAnimation = if (mOrientation == ORIENTATION_HORIZONTAL) {
                TranslateAnimation(Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f, Animation.RELATIVE_TO_SELF, 1.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f)
            } else {
                TranslateAnimation(Animation.RELATIVE_TO_SELF,
                    1.0f, Animation.RELATIVE_TO_SELF, 0.0f, Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f)
            }
            mHandleInAnimation?.duration = ANIMATION_DURATION.toLong()
        }
        if (mOutAnimation == null) {
            mOutAnimation = FastAnimationSet()
            val animationSet: AnimationSet = mOutAnimation!!
            animationSet.interpolator = AccelerateInterpolator()
            animationSet.addAnimation(AlphaAnimation(1.0f, 0.0f))
            if (mOrientation == ORIENTATION_HORIZONTAL) {
                animationSet.addAnimation(FastTranslateAnimation(Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 1.0f))
            } else {
                animationSet.addAnimation(FastTranslateAnimation(Animation.RELATIVE_TO_SELF,
                    0.0f, Animation.RELATIVE_TO_SELF, 1.0f, Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f))
            }
            animationSet.duration = ANIMATION_DURATION.toLong()
        }
        if (mHandleOutAnimation == null) {
            mHandleOutAnimation = if (mOrientation == ORIENTATION_HORIZONTAL) {
                FastTranslateAnimation(Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 1.0f)
            } else {
                FastTranslateAnimation(Animation.RELATIVE_TO_SELF,
                    0.0f, Animation.RELATIVE_TO_SELF, 1.0f, Animation.ABSOLUTE, 0.0f,
                    Animation.ABSOLUTE, 0.0f)
            }
            mHandleOutAnimation?.fillAfter = true
            mHandleOutAnimation?.duration = ANIMATION_DURATION.toLong()
        }
    }

    fun setLauncher(launcher: Launcher) {
        mLauncher = launcher
    }

    fun setDragController(dragLayer: DragLayer?) {
        mDragLayer = dragLayer
    }

    fun setHandle(view: View?) {
        mHandle = view
    }

    private class FastTranslateAnimation(
        fromXType: Int, fromXValue: Float, toXType: Int, toXValue: Float,
        fromYType: Int, fromYValue: Float, toYType: Int, toYValue: Float
    ) : TranslateAnimation(fromXType, fromXValue, toXType, toXValue,
        fromYType, fromYValue, toYType, toYValue) {
        override fun willChangeTransformationMatrix(): Boolean {
            return true
        }

        override fun willChangeBounds(): Boolean {
            return false
        }
    }

    private class FastAnimationSet : AnimationSet(false) {
        override fun willChangeTransformationMatrix(): Boolean {
            return true
        }

        override fun willChangeBounds(): Boolean {
            return false
        }
    }

    companion object {
        private const val ORIENTATION_HORIZONTAL = 1
        private const val TRANSITION_DURATION = 250
        private const val ANIMATION_DURATION = 200
        const val MSG_UNINSTALL = 0
        const val UNINSTALL_DURATION = 1000
        var sDragging = false
    }
}