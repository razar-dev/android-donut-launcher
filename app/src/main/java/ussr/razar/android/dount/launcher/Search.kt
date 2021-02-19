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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.Animation.AnimationListener
import android.view.animation.Interpolator
import android.view.animation.Transformation
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

class Search(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs), View.OnClickListener, View.OnKeyListener,
    OnLongClickListener {
    private var mLauncher: Launcher? = null
    private var mSearchText: TextView? = null
    private var mVoiceButton: ImageButton? = null

    /** The animation that morphs the search widget to the search dialog.  */
    private val mMorphAnimation: Animation

    /** The animation that morphs the search widget back to its normal position.  */
    private val mUnmorphAnimation: Animation

    // These four are passed to Launcher.startSearch() when the search widget
    // has finished morphing. They are instance variables to make it possible to update
    // them while the widget is morphing.
    private var mInitialQuery: String? = null
    private var mSelectInitialQuery = false
    private var mAppSearchData: Bundle? = null
    private var mGlobalSearch = false

    // For voice searching
    private val mVoiceSearchIntent: Intent
    private val mWidgetTopOffset: Int

    /**
     * Implements OnClickListener.
     */
    override fun onClick(v: View) {
        if (v === mVoiceButton) {
            startVoiceSearch()
        } else {
            mLauncher!!.onSearchRequested()
        }
    }

    private fun startVoiceSearch() {
        try {
            context.startActivity(mVoiceSearchIntent)
        } catch (ex: ActivityNotFoundException) {
            // Should not happen, since we check the availability of
            // voice search before showing the button. But just in case...
            Log.w("SearchWidget", "Could not find voice search activity")
        }
    }

    /**
     * Sets the query text. The query field is not editable, instead we forward
     * the key events to the launcher, which keeps track of the text,
     * calls setQuery() to show it, and gives it to the search dialog.
     */
    fun setQuery(query: String?) {
        mSearchText!!.setText(query, TextView.BufferType.NORMAL)
    }

    /**
     * Morph the search gadget to the search dialog.
     * See [Activity.startSearch] for the arguments.
     */
    fun startSearch(
        initialQuery: String?, selectInitialQuery: Boolean,
        appSearchData: Bundle?, globalSearch: Boolean
    ) {
        mInitialQuery = initialQuery
        mSelectInitialQuery = selectInitialQuery
        mAppSearchData = appSearchData
        mGlobalSearch = globalSearch
        showSearchDialog()
    }

    /**
     * Shows the system search dialog immediately, without any animation.
     */
    private fun showSearchDialog() {
        mLauncher!!.showSearchDialog(
            mInitialQuery, mSelectInitialQuery, mAppSearchData, mGlobalSearch)
    }

    /**
     * Restore the search gadget to its normal position.
     *
     * @param animate Whether to animate the movement of the gadget.
     */
    fun stopSearch(animate: Boolean) {
        setQuery("")

        // Only restore if we are not already restored.
        if (animation === mMorphAnimation) {
            if (animate && !isAtTop) {
                mUnmorphAnimation.duration = animationDuration.toLong()
                startAnimation(mUnmorphAnimation)
            } else {
                clearAnimation()
            }
        }
    }

    private val isAtTop: Boolean
        get() = widgetTop == 0
    private val animationDuration: Int
        get() = (widgetTop / ANIMATION_VELOCITY).toInt()

    /**
     * Modify clearAnimation() to invalidate the parent. This works around
     * an issue where the region where the end of the animation placed the view
     * was not redrawn after clearing the animation.
     */
    override fun clearAnimation() {
        val animation = animation
        if (animation != null) {
            super.clearAnimation()
            if (animation.hasEnded()
                && animation.fillAfter
                && animation.willChangeBounds()
            ) {
                (parent as View).invalidate()
            } else {
                invalidate()
            }
        }
    }

    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (!event.isSystem &&
            keyCode != KeyEvent.KEYCODE_DPAD_UP &&
            keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
            keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            keyCode != KeyEvent.KEYCODE_DPAD_RIGHT &&
            keyCode != KeyEvent.KEYCODE_DPAD_CENTER
        ) {
            // Forward key events to Launcher, which will forward text 
            // to search dialog
            when (event.action) {
                KeyEvent.ACTION_DOWN -> return mLauncher!!.onKeyDown(keyCode, event)
                KeyEvent.ACTION_MULTIPLE -> return mLauncher!!.onKeyMultiple(keyCode, event.repeatCount, event)
                KeyEvent.ACTION_UP -> return mLauncher!!.onKeyUp(keyCode, event)
            }
        }
        return false
    }

    /**
     * Implements OnLongClickListener to pass long clicks on child views
     * to the widget. This makes it possible to pick up the widget by long
     * clicking on the text field or a button.
     */
    override fun onLongClick(v: View): Boolean {
        return performLongClick()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mSearchText = findViewById<View>(R.id.search_src_text) as TextView
        mVoiceButton = findViewById<View>(R.id.search_voice_btn) as ImageButton
        mSearchText!!.setOnKeyListener(this)
        mSearchText!!.setOnClickListener(this)
        mVoiceButton!!.setOnClickListener(this)
        setOnClickListener(this)
        mSearchText!!.setOnLongClickListener(this)
        mVoiceButton!!.setOnLongClickListener(this)

        // Set the placeholder text to be the Google logo within the search widget.
        val googlePlaceholder = context.resources.getDrawable(R.drawable.placeholder_google)
        mSearchText!!.setCompoundDrawablesWithIntrinsicBounds(googlePlaceholder, null, null, null)
        configureVoiceSearchButton()
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    /**
     * If appropriate & available, configure voice search
     *
     * Note:  Because the home screen search widget is always web search, we only check for
     * getVoiceSearchLaunchWebSearch() modes.  We don't support the alternate form of app-specific
     * voice search.
     */
    private fun configureVoiceSearchButton() {
        // Enable the voice search button if there is an activity that can handle it
        val pm = context.packageManager
        val ri = pm.resolveActivity(mVoiceSearchIntent,
            PackageManager.MATCH_DEFAULT_ONLY)
        val voiceSearchVisible = ri != null

        // finally, set visible state of voice search button, as appropriate
        mVoiceButton!!.visibility = if (voiceSearchVisible) VISIBLE else GONE
    }

    /**
     * Sets the [Launcher] that this gadget will call on to display the search dialog.
     */
    fun setLauncher(launcher: Launcher?) {
        mLauncher = launcher
    }

    /**
     * Moves the view to the top left corner of its parent.
     */
    private inner class ToParentOriginAnimation : Animation() {
        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            val dx = -left * interpolatedTime
            val dy = -widgetTop * interpolatedTime
            t.matrix.setTranslate(dx, dy)
        }
    }

    /**
     * Moves the view from the top left corner of its parent.
     */
    private inner class FromParentOriginAnimation : Animation() {
        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            val dx = -left * (1.0f - interpolatedTime)
            val dy = -widgetTop * (1.0f - interpolatedTime)
            t.matrix.setTranslate(dx, dy)
        }
    }

    /**
     * The widget is centered vertically within it's 4x1 slot. This is
     * accomplished by nesting the actual widget inside another view. For
     * animation purposes, we care about the top of the actual widget rather
     * than it's container. This method return the top of the actual widget.
     */
    private val widgetTop: Int
        get() = top + getChildAt(0).top + mWidgetTopOffset

    companion object {
        // Speed at which the widget slides up/down, in pixels/ms.
        private const val ANIMATION_VELOCITY = 1.0f

        /** The distance in dips between the optical top of the widget and the top if its bounds  */
        private const val WIDGET_TOP_OFFSET = 9f
    }


    init {
        val scale = context.resources.displayMetrics.density
        mWidgetTopOffset = (WIDGET_TOP_OFFSET * scale).roundToInt()
        val interpolator: Interpolator = AccelerateDecelerateInterpolator()
        mMorphAnimation = ToParentOriginAnimation()
        // no need to apply transformation before the animation starts,
        // since the gadget is already in its normal place.
        mMorphAnimation.fillBefore = false
        // stay in the top position after the animation finishes
        mMorphAnimation.fillAfter = true
        mMorphAnimation.interpolator = interpolator
        mMorphAnimation.setAnimationListener(object : AnimationListener {

            // The runnable which we'll pass to our handler to show the search dialog.
            private val mShowSearchDialogRunnable = Runnable { showSearchDialog() }
            override fun onAnimationEnd(animation: Animation) {}
            override fun onAnimationRepeat(animation: Animation) {}
            override fun onAnimationStart(animation: Animation) {
                // Make the search dialog show up ideally *just* as the animation reaches
                // the top, to aid the illusion that the widget becomes the search dialog.
                // Otherwise, there is a short delay when the widget reaches the top before
                // the search dialog shows. We do this roughly 80ms before the animation ends.
                handler.postDelayed(
                    mShowSearchDialogRunnable,
                    max(mMorphAnimation.duration - 90, 0))
            }
        })
        mUnmorphAnimation = FromParentOriginAnimation()
        // stay in the top position until the animation starts
        mUnmorphAnimation.fillBefore = true
        // no need to apply transformation after the animation finishes,
        // since the gadget is now back in its normal place.
        mUnmorphAnimation.fillAfter = false
        mUnmorphAnimation.interpolator = interpolator
        mUnmorphAnimation.setAnimationListener(object : AnimationListener {
            override fun onAnimationEnd(animation: Animation) {
                clearAnimation()
            }

            override fun onAnimationRepeat(animation: Animation) {}
            override fun onAnimationStart(animation: Animation) {}
        })
        mVoiceSearchIntent = Intent(RecognizerIntent.ACTION_WEB_SEARCH)
        mVoiceSearchIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
    }
}