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
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable

/**
 * Various utilities shared amongst the Launcher's classes.
 */
internal object Utilities {
    private var sIconWidth = -1
    private var sIconHeight = -1
    private val sPaint = Paint()
    private val sBounds = Rect()
    private val sOldBounds = Rect()
    private val sCanvas = Canvas()

    /**
     * Returns a Drawable representing the thumbnail of the specified Drawable.
     * The size of the thumbnail is defined by the dimension
     * android.R.dimen.launcher_application_icon_size.
     *
     * This method is not thread-safe and should be invoked on the UI thread only.
     *
     * @param icon The icon to get a thumbnail of.
     * @param context The application's context.
     *
     * @return A thumbnail for the specified icon or the icon itself if the
     * thumbnail could not be created.
     */
    fun createIconThumbnail(icon: Drawable?, context: Context?): Drawable {
        var icon = icon
        if (sIconWidth == -1) {
            val resources = context!!.resources
            sIconHeight = resources.getDimension(android.R.dimen.app_icon_size).toInt()
            sIconWidth = sIconHeight
        }
        var width = sIconWidth
        var height = sIconHeight
        val scale = 1.0f
        if (icon is PaintDrawable) {
            val painter = icon
            painter.intrinsicWidth = width
            painter.intrinsicHeight = height
        } else if (icon is BitmapDrawable) {
            // Ensure the bitmap has a density.
            val bitmapDrawable = icon
            val bitmap = bitmapDrawable.bitmap
            if (bitmap.density == Bitmap.DENSITY_NONE) {
                bitmapDrawable.setTargetDensity(context!!.resources.displayMetrics)
            }
        }
        val iconWidth = icon!!.intrinsicWidth
        val iconHeight = icon.intrinsicHeight
        if (width > 0 && height > 0) {
            if (width < iconWidth || height < iconHeight || scale != 1.0f) {
                val ratio = iconWidth.toFloat() / iconHeight
                if (iconWidth > iconHeight) {
                    height = (width / ratio).toInt()
                } else if (iconHeight > iconWidth) {
                    width = (height * ratio).toInt()
                }
                val c = if (icon.opacity != PixelFormat.OPAQUE) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                val thumb = Bitmap.createBitmap(sIconWidth, sIconHeight, c)
                val canvas = sCanvas
                canvas.setBitmap(thumb)
                // Copy the old bounds to restore them later
                // If we were to do oldBounds = icon.getBounds(),
                // the call to setBounds() that follows would
                // change the same instance and we would lose the
                // old bounds
                sOldBounds.set(icon.bounds)
                val x = (sIconWidth - width) / 2
                val y = (sIconHeight - height) / 2
                icon.setBounds(x, y, x + width, y + height)
                icon.draw(canvas)
                icon.bounds = sOldBounds
                icon = FastBitmapDrawable(thumb)
            } else if (iconWidth < width && iconHeight < height) {
                val c = Bitmap.Config.ARGB_8888
                val thumb = Bitmap.createBitmap(sIconWidth, sIconHeight, c)
                val canvas = sCanvas
                canvas.setBitmap(thumb)
                sOldBounds.set(icon.bounds)
                val x = (width - iconWidth) / 2
                val y = (height - iconHeight) / 2
                icon.setBounds(x, y, x + iconWidth, y + iconHeight)
                icon.draw(canvas)
                icon.bounds = sOldBounds
                icon = FastBitmapDrawable(thumb)
            }
        }
        return icon
    }

    /**
     * Returns a Bitmap representing the thumbnail of the specified Bitmap.
     * The size of the thumbnail is defined by the dimension
     * android.R.dimen.launcher_application_icon_size.
     *
     * This method is not thread-safe and should be invoked on the UI thread only.
     *
     * @param bitmap The bitmap to get a thumbnail of.
     * @param context The application's context.
     *
     * @return A thumbnail for the specified bitmap or the bitmap itself if the
     * thumbnail could not be created.
     */
    fun createBitmapThumbnail(bitmap: Bitmap, context: Context?): Bitmap {
        if (sIconWidth == -1) {
            val resources = context!!.resources
            sIconHeight = resources.getDimension(
                android.R.dimen.app_icon_size).toInt()
            sIconWidth = sIconHeight
        }
        var width = sIconWidth
        var height = sIconHeight
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        if (width > 0 && height > 0) {
            if (width < bitmapWidth || height < bitmapHeight) {
                val ratio = bitmapWidth.toFloat() / bitmapHeight
                if (bitmapWidth > bitmapHeight) {
                    height = (width / ratio).toInt()
                } else if (bitmapHeight > bitmapWidth) {
                    width = (height * ratio).toInt()
                }
                val c = if (width == sIconWidth && height == sIconHeight) bitmap.config else Bitmap.Config.ARGB_8888
                val thumb = Bitmap.createBitmap(sIconWidth, sIconHeight, c)
                val canvas = sCanvas
                val paint = sPaint
                canvas.setBitmap(thumb)
                paint.isDither = false
                paint.isFilterBitmap = true
                sBounds[(sIconWidth - width) / 2, (sIconHeight - height) / 2, width] = height
                sOldBounds[0, 0, bitmapWidth] = bitmapHeight
                canvas.drawBitmap(bitmap, sOldBounds, sBounds, paint)
                return thumb
            } else if (bitmapWidth < width || bitmapHeight < height) {
                val c = Bitmap.Config.ARGB_8888
                val thumb = Bitmap.createBitmap(sIconWidth, sIconHeight, c)
                val canvas = sCanvas
                val paint = sPaint
                canvas.setBitmap(thumb)
                paint.isDither = false
                paint.isFilterBitmap = true
                canvas.drawBitmap(bitmap, ((sIconWidth - bitmapWidth) / 2).toFloat(), (
                        (sIconHeight - bitmapHeight) / 2).toFloat(), paint)
                return thumb
            }
        }
        return bitmap
    }

    init {
        sCanvas.drawFilter = PaintFlagsDrawFilter(Paint.DITHER_FLAG,
            Paint.FILTER_BITMAP_FLAG)
    }
}