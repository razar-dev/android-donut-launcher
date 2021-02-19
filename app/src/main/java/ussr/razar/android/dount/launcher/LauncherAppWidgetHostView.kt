/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.os.Parcelable
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * {@inheritDoc}
 */
class LauncherAppWidgetHostView constructor(context: Context) : AppWidgetHostView(context) {
    private var mHasPerformedLongPress: Boolean = false
    private var mPendingCheckForLongPress: CheckForLongPress? = null
    private val mInflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    override fun getErrorView(): View {
        return mInflater.inflate(R.layout.appwidget_error, this, false)
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        try {
            super.dispatchRestoreInstanceState(container)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var startY: Float = 0f
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Consume any touch events for ourselves after longpress is triggered
        if (mHasPerformedLongPress) {
            mHasPerformedLongPress = false
            return true
        }
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                postCheckForLongClick()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.y - startY) < 5) return false
                mHasPerformedLongPress = false
                if (mPendingCheckForLongPress != null) {
                    removeCallbacks(mPendingCheckForLongPress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mHasPerformedLongPress = false
                if (mPendingCheckForLongPress != null) {
                    removeCallbacks(mPendingCheckForLongPress)
                }
            }
        }

        // Otherwise continue letting touch events fall through to children
        return false
    }

    internal inner class CheckForLongPress : Runnable {
        private var mOriginalWindowAttachCount: Int = 0
        override fun run() {
            if (((parent != null) && hasWindowFocus()
                        && (mOriginalWindowAttachCount == windowAttachCount
                        ) && !mHasPerformedLongPress)
            ) {
                if (performLongClick()) {
                    mHasPerformedLongPress = true
                }
            }
        }

        fun rememberWindowAttachCount() {
            mOriginalWindowAttachCount = windowAttachCount
        }
    }

    private fun postCheckForLongClick() {
        mHasPerformedLongPress = false
        if (mPendingCheckForLongPress == null) {
            mPendingCheckForLongPress = CheckForLongPress()
        }
        mPendingCheckForLongPress!!.rememberWindowAttachCount()
        postDelayed(mPendingCheckForLongPress, WIDGET_LONG_CLICK_TIMEOUT)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        mHasPerformedLongPress = false
        if (mPendingCheckForLongPress != null) {
            removeCallbacks(mPendingCheckForLongPress)
        }
    }

    companion object {
        private const val WIDGET_LONG_CLICK_TIMEOUT: Long = 700
    }

}