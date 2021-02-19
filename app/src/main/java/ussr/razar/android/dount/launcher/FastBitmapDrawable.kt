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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal class FastBitmapDrawable constructor(val bitmap: Bitmap) : Drawable() {
    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, 0.0f, 0.0f, null)
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    override fun getIntrinsicWidth(): Int {
        return bitmap.width
    }

    override fun getIntrinsicHeight(): Int {
        return bitmap.height
    }

    override fun getMinimumWidth(): Int {
        return bitmap.width
    }

    override fun getMinimumHeight(): Int {
        return bitmap.height
    }
}