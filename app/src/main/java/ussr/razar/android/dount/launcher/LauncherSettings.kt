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

import android.net.Uri
import android.provider.BaseColumns

/**
 * Settings related utilities.
 */
internal class LauncherSettings {
    internal interface BaseLauncherColumns : BaseColumns {
        companion object {
            /**
             * Descriptive name of the gesture that can be displayed to the user.
             * <P>Type: TEXT</P>
             */
            const val TITLE: String = "title"

            /**
             * The Intent URL of the gesture, describing what it points to. This
             * value is given to [android.content.Intent.parseUri] to create
             * an Intent that can be launched.
             * <P>Type: TEXT</P>
             */
            const val INTENT: String = "intent"

            /**
             * The type of the gesture
             *
             * <P>Type: INTEGER</P>
             */
            const val ITEM_TYPE: String = "itemType"

            /**
             * The gesture is an application
             */
            const val ITEM_TYPE_APPLICATION: Int = 0

            /**
             * The gesture is an application created shortcut
             */
            const val ITEM_TYPE_SHORTCUT: Int = 1

            /**
             * The icon type.
             * <P>Type: INTEGER</P>
             */
            const val ICON_TYPE: String = "iconType"

            /**
             * The icon is a resource identified by a package name and an integer id.
             */
            const val ICON_TYPE_RESOURCE: Int = 0

            /**
             * The icon is a bitmap.
             */
            const val ICON_TYPE_BITMAP: Int = 1

            /**
             * The icon package name, if icon type is ICON_TYPE_RESOURCE.
             * <P>Type: TEXT</P>
             */
            const val ICON_PACKAGE: String = "iconPackage"

            /**
             * The icon resource id, if icon type is ICON_TYPE_RESOURCE.
             * <P>Type: TEXT</P>
             */
            const val ICON_RESOURCE: String = "iconResource"

            /**
             * The custom icon bitmap, if icon type is ICON_TYPE_BITMAP.
             * <P>Type: BLOB</P>
             */
            const val ICON: String = "icon"
        }
    }

    internal object Favorites : BaseLauncherColumns {
        /**
         * The content:// style URL for this table
         */
        val CONTENT_URI: Uri = Uri.parse(("content://" +
                LauncherProvider.AUTHORITY + "/" + LauncherProvider.TABLE_FAVORITES +
                "?" + LauncherProvider.PARAMETER_NOTIFY + "=true"))

        /**
         * The content:// style URL for this table. When this Uri is used, no notification is
         * sent if the content changes.
         */
        val CONTENT_URI_NO_NOTIFICATION: Uri = Uri.parse(("content://" +
                LauncherProvider.AUTHORITY + "/" + LauncherProvider.TABLE_FAVORITES +
                "?" + LauncherProvider.PARAMETER_NOTIFY + "=false"))

        /**
         * The content:// style URL for a given row, identified by its id.
         *
         * @param id The row id.
         * @param notify True to send a notification is the content changes.
         *
         * @return The unique content URL for the specified row.
         */
        fun getContentUri(id: Long, notify: Boolean): Uri {
            return Uri.parse(("content://" + LauncherProvider.AUTHORITY +
                    "/" + LauncherProvider.TABLE_FAVORITES + "/" + id + "?" +
                    LauncherProvider.PARAMETER_NOTIFY + "=" + notify))
        }

        /**
         * The container holding the favorite
         * <P>Type: INTEGER</P>
         */
        const val CONTAINER: String = "container"

        /**
         * The icon is a resource identified by a package name and an integer id.
         */
        const val CONTAINER_DESKTOP: Int = -100

        /**
         * The screen holding the favorite (if container is CONTAINER_DESKTOP)
         * <P>Type: INTEGER</P>
         */
        const val SCREEN: String = "screen"

        /**
         * The X coordinate of the cell holding the favorite
         * (if container is CONTAINER_DESKTOP or CONTAINER_DOCK)
         * <P>Type: INTEGER</P>
         */
        const val CELLX: String = "cellX"

        /**
         * The Y coordinate of the cell holding the favorite
         * (if container is CONTAINER_DESKTOP)
         * <P>Type: INTEGER</P>
         */
        const val CELLY: String = "cellY"

        /**
         * The X span of the cell holding the favorite
         * <P>Type: INTEGER</P>
         */
        const val SPANX: String = "spanX"

        /**
         * The Y span of the cell holding the favorite
         * <P>Type: INTEGER</P>
         */
        const val SPANY: String = "spanY"

        /**
         * The favorite is a user created folder
         */
        const val ITEM_TYPE_USER_FOLDER: Int = 2

        /**
         * The favorite is a live folder
         */
        const val ITEM_TYPE_LIVE_FOLDER: Int = 3

        /**
         * The favorite is a widget
         */
        const val ITEM_TYPE_APPWIDGET: Int = 4

        /**
         * The favorite is a clock
         */
        const val ITEM_TYPE_WIDGET_CLOCK: Int = 1000

        /**
         * The favorite is a search widget
         */
        const val ITEM_TYPE_WIDGET_SEARCH: Int = 1001

        /**
         * The favorite is a photo frame
         */
        const val ITEM_TYPE_WIDGET_PHOTO_FRAME: Int = 1002

        /**
         * The appWidgetId of the widget
         *
         * <P>Type: INTEGER</P>
         */
        const val APPWIDGET_ID: String = "appWidgetId"

        /**
         * The URI associated with the favorite. It is used, for instance, by
         * live folders to find the content provider.
         * <P>Type: TEXT</P>
         */
        const val URI: String = "uri"

        /**
         * The display mode if the item is a live folder.
         * <P>Type: INTEGER</P>
         *
         * @see android.provider.LiveFolders.DISPLAY_MODE_GRID
         *
         * @see android.provider.LiveFolders.DISPLAY_MODE_LIST
         */
        const val DISPLAY_MODE: String = "displayMode"
    }
}