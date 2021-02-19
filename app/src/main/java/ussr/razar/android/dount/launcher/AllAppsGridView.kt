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
import android.content.res.TypedArray
import android.graphics.*
import android.util.AttributeSet
import android.view.*
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView.OnItemLongClickListener
import android.widget.GridView

class AllAppsGridView : GridView, OnItemClickListener, OnItemLongClickListener, DragSource {
    private var mDragger: DragController? = null
    private var mLauncher: Launcher? = null
    private var mTexture: Bitmap? = null
    private var mPaint: Paint? = null
    private var mTextureWidth: Int = 0
    private var mTextureHeight: Int = 0

    constructor(context: Context?) : super(context)

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int = android.R.attr.gridViewStyle) : super(context, attrs, defStyle) {
        val a: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.AllAppsGridView, defStyle, 0)
        val textureId: Int = a.getResourceId(R.styleable.AllAppsGridView_texture, 0)
        if (textureId != 0) {
            mTexture = BitmapFactory.decodeResource(resources, textureId)
            mTextureWidth = mTexture!!.width
            mTextureHeight = mTexture!!.height
            mPaint = Paint()
            mPaint!!.isDither = false
        }
        a.recycle()
    }

    override fun isOpaque(): Boolean {
        return !((mTexture?.hasAlpha())?:false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        onItemClickListener = this
        onItemLongClickListener = this
    }

    override fun draw(canvas: Canvas) {
        val texture: Bitmap? = mTexture
        val paint: Paint? = mPaint
        val width: Int = width
        val height: Int = height
        val textureWidth: Int = mTextureWidth
        val textureHeight: Int = mTextureHeight
        var x = 0
        var y: Int
        while (x < width) {
            y = 0
            while (y < height) {
                canvas.drawBitmap(texture!!, x.toFloat(), y.toFloat(), paint)
                y += textureHeight
            }
            x += textureWidth
        }
        super.draw(canvas)
    }

    override fun onItemClick(parent: AdapterView<*>, v: View, position: Int, id: Long) {
        val app: ApplicationInfo = parent.getItemAtPosition(position) as ApplicationInfo
        mLauncher?.startActivitySafely(app.intent)
    }

    override fun onItemLongClick(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean {
        if (!view.isInTouchMode) {
            return false
        }
        var app: ApplicationInfo = parent.getItemAtPosition(position) as ApplicationInfo
        app = ApplicationInfo(app)
        mDragger!!.startDrag(view, this, app, DragController.DRAG_ACTION_COPY)
        mLauncher!!.closeAllApplications()
        return true
    }

    fun setDragController(drag: DragController?) {
        mDragger = drag
    }

    override fun onDropCompleted(target: View, success: Boolean) {}
    fun setLauncher(launcher: Launcher?) {
        mLauncher = launcher
    }
}