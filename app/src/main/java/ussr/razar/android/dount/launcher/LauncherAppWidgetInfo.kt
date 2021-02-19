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
import android.appwidget.AppWidgetManager
import android.content.ContentValues
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites

/**
 * Represents a widget, which just contains an identifier.
 */
class LauncherAppWidgetInfo constructor(appWidgetId: Int) : ItemInfo() {
    /**
     * Identifier for this widget when talking with [AppWidgetManager] for updates.
     */
    var appWidgetId: Int

    /**
     * View that holds this widget after it's been created.  This view isn't created
     * until Launcher knows it's needed.
     */
    var hostView: AppWidgetHostView? = null
    override fun onAddToDatabase(values: ContentValues) {
        super.onAddToDatabase(values)
        values.put(Favorites.APPWIDGET_ID, appWidgetId)
    }

    override fun toString(): String {
        return appWidgetId.toString()
    }

    init {
        itemType = Favorites.ITEM_TYPE_APPWIDGET
        this.appWidgetId = appWidgetId
    }
}