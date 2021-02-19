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

import android.content.ContentValues
import android.content.Intent
import android.content.Intent.ShortcutIconResource
import android.graphics.drawable.Drawable
import android.net.Uri
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites

class LiveFolderInfo : FolderInfo() {
    /**
     * The base intent, if it exists.
     */
    var baseIntent: Intent? = null

    /**
     * The live folder's content uri.
     */
    var uri: Uri? = null

    /**
     * The live folder's display type.
     */
    var displayMode: Int = 0

    /**
     * The live folder icon.
     */
    var icon: Drawable? = null

    /**
     * When set to true, indicates that the icon has been resized.
     */
    var filtered: Boolean = false

    /**
     * Reference to the live folder icon as an application's resource.
     */
    var iconResource: ShortcutIconResource = ShortcutIconResource()
    override fun onAddToDatabase(values: ContentValues) {
        super.onAddToDatabase(values)
        values.put(BaseLauncherColumns.TITLE, title.toString())
        values.put(Favorites.URI, uri.toString())
        if (baseIntent != null) {
            values.put(BaseLauncherColumns.INTENT, baseIntent!!.toUri(0))
        }
        values.put(BaseLauncherColumns.ICON_TYPE, BaseLauncherColumns.ICON_TYPE_RESOURCE)
        values.put(Favorites.DISPLAY_MODE, displayMode)
        values.put(BaseLauncherColumns.ICON_PACKAGE, iconResource.packageName)
        values.put(BaseLauncherColumns.ICON_RESOURCE, iconResource.resourceName)
    }

    init {
        itemType = Favorites.ITEM_TYPE_LIVE_FOLDER
    }
}