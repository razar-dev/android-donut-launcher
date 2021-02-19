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

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.Intent.ShortcutIconResource
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns

/**
 * Represents a launchable application. An application is made of a name (or title),
 * an intent and an icon.
 */
class ApplicationInfo : ItemInfo {
    /**
     * The application name.
     */
    var title: CharSequence? = null

    /**
     * The intent used to start the application.
     */
    var intent: Intent? = null

    /**
     * The application icon.
     */
    var icon: Drawable? = null

    /**
     * When set to true, indicates that the icon has been resized.
     */
    var filtered: Boolean = false

    /**
     * Indicates whether the icon comes from an application's resource (if false)
     * or from a custom Bitmap (if true.)
     */
    var customIcon: Boolean = false

    /**
     * If isShortcut=true and customIcon=false, this contains a reference to the
     * shortcut icon as an application's resource.
     */
    var iconResource: ShortcutIconResource? = null

    constructor() {
        itemType = BaseLauncherColumns.ITEM_TYPE_SHORTCUT
    }

    constructor(info: ApplicationInfo) : super(info) {
        title = info.title.toString()
        intent = Intent(info.intent)
        if (info.iconResource != null) {
            iconResource = ShortcutIconResource()
            iconResource!!.packageName = info.iconResource!!.packageName
            iconResource!!.resourceName = info.iconResource!!.resourceName
        }
        icon = info.icon
        filtered = info.filtered
        customIcon = info.customIcon
    }

    /**
     * Creates the application intent based on a component name and various launch flags.
     * Sets [.itemType] to [LauncherSettings.BaseLauncherColumns.ITEM_TYPE_APPLICATION].
     *
     * @param className the class name of the component representing the intent
     * @param launchFlags the launch flags
     */
    fun setActivity(className: ComponentName?, launchFlags: Int) {
        intent = Intent(Intent.ACTION_MAIN)
        intent!!.addCategory(Intent.CATEGORY_LAUNCHER)
        intent!!.component = className
        intent!!.flags = launchFlags
        itemType = BaseLauncherColumns.ITEM_TYPE_APPLICATION
    }

    override fun onAddToDatabase(values: ContentValues) {
        super.onAddToDatabase(values)
        val titleStr: String? = if (title != null) title.toString() else null
        values.put(BaseLauncherColumns.TITLE, titleStr)
        val uri: String? = if (intent != null) intent!!.toUri(0) else null
        values.put(BaseLauncherColumns.INTENT, uri)
        if (customIcon) {
            values.put(BaseLauncherColumns.ICON_TYPE,
                BaseLauncherColumns.ICON_TYPE_BITMAP)
            val bitmap: Bitmap? = (icon as FastBitmapDrawable?)?.bitmap
            writeBitmap(values, bitmap)
        } else {
            values.put(BaseLauncherColumns.ICON_TYPE,
                BaseLauncherColumns.ICON_TYPE_RESOURCE)
            if (iconResource != null) {
                values.put(BaseLauncherColumns.ICON_PACKAGE,
                    iconResource!!.packageName)
                values.put(BaseLauncherColumns.ICON_RESOURCE,
                    iconResource!!.resourceName)
            }
        }
    }

    override fun toString(): String {
        return title.toString()
    }
}