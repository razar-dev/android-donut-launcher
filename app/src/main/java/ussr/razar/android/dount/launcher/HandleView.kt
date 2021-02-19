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
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView

class HandleView : ImageView {
    private var mLauncher: Launcher? = null
    private var mOrientation = ORIENTATION_HORIZONTAL

    constructor(context: Context?) : super(context)

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int = 0) : super(context, attrs, defStyle) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.HandleView, defStyle, 0)
        mOrientation = a.getInt(R.styleable.HandleView_direction, ORIENTATION_HORIZONTAL)
        a.recycle()
    }

    override fun focusSearch(direction: Int): View {
        val newFocus = super.focusSearch(direction)
        if (newFocus == null && mLauncher!!.isDrawerDown) {
            val workspace = mLauncher?.workspace
            workspace!!.dispatchUnhandledMove(null, direction)
            return if (mOrientation == ORIENTATION_HORIZONTAL && direction == FOCUS_DOWN) this else workspace
        }
        return newFocus
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyDown(keyCode, event)
        return if (!handled && !mLauncher!!.isDrawerDown && !isDirectionKey(keyCode)) {
            mLauncher!!.applicationsGrid!!.onKeyDown(keyCode, event)
        } else handled
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyUp(keyCode, event)
        return if (!handled && !mLauncher!!.isDrawerDown && !isDirectionKey(keyCode)) {
            mLauncher!!.applicationsGrid!!.onKeyUp(keyCode, event)
        } else handled
    }

    fun setLauncher(launcher: Launcher?) {
        mLauncher = launcher
    }

    companion object {
        private const val ORIENTATION_HORIZONTAL = 1
        private fun isDirectionKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP
        }
    }
}