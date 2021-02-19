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

import android.appwidget.AppWidgetHost
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites
import java.io.IOException
import java.net.URISyntaxException
import java.util.*

class LauncherProvider : ContentProvider() {
    private var mOpenHelper: SQLiteOpenHelper? = null
    override fun onCreate(): Boolean {
        mOpenHelper = DatabaseHelper(context!!)
        return true
    }

    override fun getType(uri: Uri): String {
        val args = SqlArguments(uri, null, null)
        return if (TextUtils.isEmpty(args.where)) {
            "vnd.android.cursor.dir/" + args.table
        } else {
            "vnd.android.cursor.item/" + args.table
        }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val args = SqlArguments(uri, selection, selectionArgs)
        val qb = SQLiteQueryBuilder()
        qb.tables = args.table
        val db: SQLiteDatabase = mOpenHelper!!.writableDatabase
        val result: Cursor = qb.query(db, projection, args.where, args.args, null, null, sortOrder)
        result.setNotificationUri(context?.contentResolver, uri)
        return result
    }

    override fun insert(uri: Uri, initialValues: ContentValues?): Uri? {
        var uri: Uri = uri
        val args = SqlArguments(uri)
        val db: SQLiteDatabase = mOpenHelper!!.writableDatabase
        val rowId: Long = db.insert(args.table, null, initialValues)
        if (rowId <= 0) return null
        uri = ContentUris.withAppendedId(uri, rowId)
        sendNotify(uri)
        return uri
    }

    override fun bulkInsert(uri: Uri, values: Array<ContentValues>): Int {
        val args = SqlArguments(uri)
        val db: SQLiteDatabase = mOpenHelper!!.writableDatabase
        db.beginTransaction()
        try {
            val numValues: Int = values.size
            for (i in 0 until numValues) {
                if (db.insert(args.table, null, values[i]) < 0) return 0
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        sendNotify(uri)
        return values.size
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val args = SqlArguments(uri, selection, selectionArgs)
        val db: SQLiteDatabase = mOpenHelper!!.writableDatabase
        val count: Int = db.delete(args.table, args.where, args.args)
        if (count > 0) sendNotify(uri)
        return count
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        val args = SqlArguments(uri, selection, selectionArgs)
        val db: SQLiteDatabase = mOpenHelper!!.writableDatabase
        val count: Int = db.update(args.table, values, args.where, args.args)
        if (count > 0) sendNotify(uri)
        return count
    }

    private fun sendNotify(uri: Uri) {
        val notify: String? = uri.getQueryParameter(PARAMETER_NOTIFY)
        if (notify == null || ("true" == notify)) {
            context?.contentResolver?.notifyChange(uri, null)
        }
    }

    private class DatabaseHelper(private val mContext: Context) :
        SQLiteOpenHelper(mContext, DATABASE_NAME, null, DATABASE_VERSION) {
        private val mAppWidgetHost: AppWidgetHost?

        /**
         * Send notification that we've deleted the [AppWidgetHost],
         * probably as part of the initial database creation. The receiver may
         * want to re-call [AppWidgetHost.startListening] to ensure
         * callbacks are correctly set.
         */
        private fun sendAppWidgetResetNotify() {
            val resolver: ContentResolver = mContext.contentResolver
            resolver.notifyChange(CONTENT_APPWIDGET_RESET_URI, null)
        }

        override fun onCreate(db: SQLiteDatabase) {
            if (LOGD) Log.d(LOG_TAG, "creating new launcher database")
            db.execSQL(("CREATE TABLE favorites (" +
                    "_id INTEGER PRIMARY KEY," +
                    "title TEXT," +
                    "intent TEXT," +
                    "container INTEGER," +
                    "screen INTEGER," +
                    "cellX INTEGER," +
                    "cellY INTEGER," +
                    "spanX INTEGER," +
                    "spanY INTEGER," +
                    "itemType INTEGER," +
                    "appWidgetId INTEGER NOT NULL DEFAULT -1," +
                    "isShortcut INTEGER," +
                    "iconType INTEGER," +
                    "iconPackage TEXT," +
                    "iconResource TEXT," +
                    "icon BLOB," +
                    "uri TEXT," +
                    "displayMode INTEGER" +
                    ");"))
            db.execSQL(("CREATE TABLE gestures (" +
                    "_id INTEGER PRIMARY KEY," +
                    "title TEXT," +
                    "intent TEXT," +
                    "itemType INTEGER," +
                    "iconType INTEGER," +
                    "iconPackage TEXT," +
                    "iconResource TEXT," +
                    "icon BLOB" +
                    ");"))

            // Database was just created, so wipe any previous widgets
            if (mAppWidgetHost != null) {
                mAppWidgetHost.deleteHost()
                sendAppWidgetResetNotify()
            }
            if (!convertDatabase(db)) {
                // Populate favorites table with initial favorites
                loadFavorites(db)
            }
        }

        private fun convertDatabase(db: SQLiteDatabase): Boolean {
            if (LOGD) Log.d(LOG_TAG, "converting database from an older format, but not onUpgrade")
            var converted = false
            val uri: Uri = Uri.parse(("content://" + Settings.AUTHORITY +
                    "/old_favorites?notify=true"))
            val resolver: ContentResolver = mContext.contentResolver
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(uri, null, null, null, null)
            } catch (e: Exception) {
                // Ignore
            }

            // We already have a favorites database in the old provider
            if (cursor != null && cursor.count > 0) {
                try {
                    converted = copyFromCursor(db, cursor) > 0
                } finally {
                    cursor.close()
                }
                if (converted) {
                    resolver.delete(uri, null, null)
                }
            }
            if (converted) {
                // Convert widgets from this import into widgets
                if (LOGD) Log.d(LOG_TAG, "converted and now triggering widget upgrade")
                convertWidgets(db)
            }
            return converted
        }

        private fun copyFromCursor(db: SQLiteDatabase, c: Cursor): Int {
            val idIndex: Int = c.getColumnIndexOrThrow(BaseColumns._ID)
            val intentIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.INTENT)
            val titleIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.TITLE)
            val iconTypeIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.ICON_TYPE)
            val iconIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.ICON)
            val iconPackageIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.ICON_PACKAGE)
            val iconResourceIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.ICON_RESOURCE)
            val containerIndex: Int = c.getColumnIndexOrThrow(Favorites.CONTAINER)
            val itemTypeIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE)
            val screenIndex: Int = c.getColumnIndexOrThrow(Favorites.SCREEN)
            val cellXIndex: Int = c.getColumnIndexOrThrow(Favorites.CELLX)
            val cellYIndex: Int = c.getColumnIndexOrThrow(Favorites.CELLY)
            val uriIndex: Int = c.getColumnIndexOrThrow(Favorites.URI)
            val displayModeIndex: Int = c.getColumnIndexOrThrow(Favorites.DISPLAY_MODE)
            val rows: Array<ContentValues?> = arrayOfNulls(c.count)
            var i = 0
            while (c.moveToNext()) {
                val values = ContentValues(c.columnCount)
                values.put(BaseColumns._ID, c.getLong(idIndex))
                values.put(BaseLauncherColumns.INTENT, c.getString(intentIndex))
                values.put(BaseLauncherColumns.TITLE, c.getString(titleIndex))
                values.put(BaseLauncherColumns.ICON_TYPE, c.getInt(iconTypeIndex))
                values.put(BaseLauncherColumns.ICON, c.getBlob(iconIndex))
                values.put(BaseLauncherColumns.ICON_PACKAGE, c.getString(iconPackageIndex))
                values.put(BaseLauncherColumns.ICON_RESOURCE, c.getString(iconResourceIndex))
                values.put(Favorites.CONTAINER, c.getInt(containerIndex))
                values.put(BaseLauncherColumns.ITEM_TYPE, c.getInt(itemTypeIndex))
                values.put(Favorites.APPWIDGET_ID, -1)
                values.put(Favorites.SCREEN, c.getInt(screenIndex))
                values.put(Favorites.CELLX, c.getInt(cellXIndex))
                values.put(Favorites.CELLY, c.getInt(cellYIndex))
                values.put(Favorites.URI, c.getString(uriIndex))
                values.put(Favorites.DISPLAY_MODE, c.getInt(displayModeIndex))
                rows[i++] = values
            }
            db.beginTransaction()
            var total = 0
            try {
                val numValues: Int = rows.size
                i = 0
                while (i < numValues) {
                    if (db.insert(TABLE_FAVORITES, null, rows[i]) < 0) {
                        return 0
                    } else {
                        total++
                    }
                    i++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return total
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (LOGD) Log.d(LOG_TAG, "onUpgrade triggered")
            var version: Int = oldVersion
            if (version < 3) {
                // upgrade 1,2 -> 3 added appWidgetId column
                db.beginTransaction()
                try {
                    // Insert new column for holding appWidgetIds
                    db.execSQL("ALTER TABLE favorites " +
                            "ADD COLUMN appWidgetId INTEGER NOT NULL DEFAULT -1;")
                    db.setTransactionSuccessful()
                    version = 3
                } catch (ex: SQLException) {
                    // Old version remains, which means we wipe old data
                    Log.e(LOG_TAG, ex.message, ex)
                } finally {
                    db.endTransaction()
                }

                // Convert existing widgets only if table upgrade was successful
                if (version == 3) {
                    convertWidgets(db)
                }
            }
            if (version < 4) {
                db.beginTransaction()
                try {
                    db.execSQL(("CREATE TABLE gestures (" +
                            "_id INTEGER PRIMARY KEY," +
                            "title TEXT," +
                            "intent TEXT," +
                            "itemType INTEGER," +
                            "iconType INTEGER," +
                            "iconPackage TEXT," +
                            "iconResource TEXT," +
                            "icon BLOB" +
                            ");"))
                    db.setTransactionSuccessful()
                    version = 4
                } catch (ex: SQLException) {
                    // Old version remains, which means we wipe old data
                    Log.e(LOG_TAG, ex.message, ex)
                } finally {
                    db.endTransaction()
                }
            }
            if (version != DATABASE_VERSION) {
                Log.w(LOG_TAG, "Destroying all old data.")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_FAVORITES")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_GESTURES")
                onCreate(db)
            }
        }

        /**
         * Upgrade existing clock and photo frame widgets into their new widget
         * equivalents. This method allocates appWidgetIds, and then hands off to
         * LauncherAppWidgetBinder to finish the actual binding.
         */
        private fun convertWidgets(db: SQLiteDatabase) {
            val bindSources: IntArray = intArrayOf(
                Favorites.ITEM_TYPE_WIDGET_CLOCK,
                Favorites.ITEM_TYPE_WIDGET_PHOTO_FRAME)
            val bindTargets: ArrayList<ComponentName> = ArrayList()
            bindTargets.add(ComponentName("com.android.alarmclock",
                "com.android.alarmclock.AnalogAppWidgetProvider"))
            bindTargets.add(ComponentName("com.android.camera",
                "com.android.camera.PhotoAppWidgetProvider"))
            val selectWhere: String = buildOrWhereString(BaseLauncherColumns.ITEM_TYPE, bindSources)
            var c: Cursor? = null
            var allocatedAppWidgets = false
            db.beginTransaction()
            try {
                // Select and iterate through each matching widget
                c = db.query(TABLE_FAVORITES, arrayOf(BaseColumns._ID),
                    selectWhere, null, null, null, null)
                if (LOGD) Log.d(LOG_TAG, "found upgrade cursor count=" + c.count)
                val values = ContentValues()
                while (c != null && c.moveToNext()) {
                    val favoriteId: Long = c.getLong(0)

                    // Allocate and update database with new appWidgetId
                    try {
                        val appWidgetId: Int = mAppWidgetHost!!.allocateAppWidgetId()
                        if (LOGD) Log.d(LOG_TAG, "allocated appWidgetId=$appWidgetId for favoriteId=$favoriteId")
                        values.clear()
                        values.put(Favorites.APPWIDGET_ID, appWidgetId)

                        // Original widgets might not have valid spans when upgrading
                        values.put(Favorites.SPANX, 2)
                        values.put(Favorites.SPANY, 2)
                        val updateWhere: String = BaseColumns._ID + "=" + favoriteId
                        db.update(TABLE_FAVORITES, values, updateWhere, null)
                        allocatedAppWidgets = true
                    } catch (ex: RuntimeException) {
                        Log.e(LOG_TAG, "Problem allocating appWidgetId", ex)
                    }
                }
                db.setTransactionSuccessful()
            } catch (ex: SQLException) {
                Log.w(LOG_TAG, "Problem while allocating appWidgetIds for existing widgets", ex)
            } finally {
                db.endTransaction()
                c?.close()
            }

            // If any appWidgetIds allocated, then launch over to binder
            if (allocatedAppWidgets) {
                launchAppWidgetBinder(bindSources, bindTargets)
            }
        }

        /**
         * Launch the widget binder that walks through the Launcher database,
         * binding any matching widgets to the corresponding targets. We can't
         * bind ourselves because our parent process can't obtain the
         * BIND_APPWIDGET permission.
         */
        private fun launchAppWidgetBinder(bindSources: IntArray, bindTargets: ArrayList<ComponentName>) {
            val intent = Intent()
            intent.component = ComponentName("com.android.settings",
                "com.android.settings.LauncherAppWidgetBinder")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val extras = Bundle()
            extras.putIntArray(EXTRA_BIND_SOURCES, bindSources)
            extras.putParcelableArrayList(EXTRA_BIND_TARGETS, bindTargets)
            intent.putExtras(extras)
            mContext.startActivity(intent)
        }

        /**
         * Loads the default set of favorite packages from an xml file.
         *
         * @param db The database to write the values into
         */
        private fun loadFavorites(db: SQLiteDatabase): Int {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val values = ContentValues()
            val packageManager: PackageManager = mContext.packageManager
            var i = 0
            try {
                val parser: XmlResourceParser = mContext.resources.getXml(R.xml.default_workspace)
                val attrs: AttributeSet = Xml.asAttributeSet(parser)
                val depth: Int = parser.depth
                var type: Int
                while (((parser.next().also { type = it }) != XmlPullParser.END_TAG ||
                            parser.depth > depth) && type != XmlPullParser.END_DOCUMENT
                ) {
                    if (type != XmlPullParser.START_TAG) {
                        continue
                    }
                    var added = false
                    val name: String = parser.name
                    val a: TypedArray = mContext.obtainStyledAttributes(attrs, R.styleable.Favorite)
                    values.clear()
                    values.put(Favorites.CONTAINER,
                        Favorites.CONTAINER_DESKTOP)
                    values.put(Favorites.SCREEN,
                        a.getString(R.styleable.Favorite_screen))
                    values.put(Favorites.CELLX,
                        a.getString(R.styleable.Favorite_x))
                    values.put(Favorites.CELLY,
                        a.getString(R.styleable.Favorite_y))
                    when (name) {
                        TAG_FAVORITE -> {
                            added = addAppShortcut(db, values, a, packageManager, intent)
                        }
                        TAG_SEARCH -> {
                            added = addSearchWidget(db, values)
                        }
                        TAG_CLOCK -> {
                            added = addClockWidget(db, values)
                        }
                        TAG_SHORTCUT -> {
                            added = addShortcut(db, values, a)
                        }
                    }
                    if (added) i++
                    a.recycle()
                }
            } catch (e: XmlPullParserException) {
                Log.w(LOG_TAG, "Got exception parsing favorites.", e)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Got exception parsing favorites.", e)
            }
            return i
        }

        private fun addAppShortcut(
            db: SQLiteDatabase, values: ContentValues, a: TypedArray,
            packageManager: PackageManager, intent: Intent
        ): Boolean {
            val info: ActivityInfo
            val packageName: String = a.getString(R.styleable.Favorite_packageName).toString()
            val className: String = a.getString(R.styleable.Favorite_className).toString()
            try {
                val cn = ComponentName(packageName, className)
                info = packageManager.getActivityInfo(cn, 0)
                intent.component = cn
                intent.flags = (Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                values.put(BaseLauncherColumns.INTENT, intent.toUri(0))
                values.put(BaseLauncherColumns.TITLE, info.loadLabel(packageManager).toString())
                values.put(BaseLauncherColumns.ITEM_TYPE, BaseLauncherColumns.ITEM_TYPE_APPLICATION)
                values.put(Favorites.SPANX, 1)
                values.put(Favorites.SPANY, 1)
                db.insert(TABLE_FAVORITES, null, values)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(LOG_TAG, ("Unable to add favorite: " + packageName +
                        "/" + className), e)
                return false
            }
            return true
        }

        private fun addShortcut(db: SQLiteDatabase, values: ContentValues, a: TypedArray): Boolean {
            val r: Resources = mContext.resources
            val iconResId: Int = a.getResourceId(R.styleable.Favorite_icon, 0)
            val titleResId: Int = a.getResourceId(R.styleable.Favorite_title, 0)
            val intent: Intent?
            var uri: String? = null
            try {
                uri = a.getString(R.styleable.Favorite_uri)
                intent = Intent.parseUri(uri, 0)
            } catch (e: URISyntaxException) {
                Log.w(LauncherModel.LOG_TAG, "Shortcut has malformed uri: $uri")
                return false // Oh well
            }
            if (iconResId == 0 || titleResId == 0) {
                Log.w(LauncherModel.LOG_TAG, "Shortcut is missing title or icon resource ID")
                return false
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            values.put(BaseLauncherColumns.INTENT, intent.toUri(0))
            values.put(BaseLauncherColumns.TITLE, r.getString(titleResId))
            values.put(BaseLauncherColumns.ITEM_TYPE, BaseLauncherColumns.ITEM_TYPE_SHORTCUT)
            values.put(Favorites.SPANX, 1)
            values.put(Favorites.SPANY, 1)
            values.put(BaseLauncherColumns.ICON_TYPE, BaseLauncherColumns.ICON_TYPE_RESOURCE)
            values.put(BaseLauncherColumns.ICON_PACKAGE, mContext.packageName)
            values.put(BaseLauncherColumns.ICON_RESOURCE, mContext.resources.getResourceName(iconResId))
            db.insert(TABLE_FAVORITES, null, values)
            return true
        }

        private fun addSearchWidget(db: SQLiteDatabase, values: ContentValues): Boolean {
            // Add a search box
            values.put(BaseLauncherColumns.ITEM_TYPE, Favorites.ITEM_TYPE_WIDGET_SEARCH)
            values.put(Favorites.SPANX, 4)
            values.put(Favorites.SPANY, 1)
            db.insert(TABLE_FAVORITES, null, values)
            return true
        }

        private fun addClockWidget(db: SQLiteDatabase, values: ContentValues): Boolean {
            val bindSources: IntArray = intArrayOf(
                Favorites.ITEM_TYPE_WIDGET_CLOCK)
            val bindTargets: ArrayList<ComponentName> = ArrayList()
            bindTargets.add(ComponentName("com.android.alarmclock",
                "com.android.alarmclock.AnalogAppWidgetProvider"))
            var allocatedAppWidgets = false

            // Try binding to an analog clock widget
            try {
                val appWidgetId: Int = mAppWidgetHost!!.allocateAppWidgetId()
                values.put(BaseLauncherColumns.ITEM_TYPE, Favorites.ITEM_TYPE_WIDGET_CLOCK)
                values.put(Favorites.SPANX, 2)
                values.put(Favorites.SPANY, 2)
                values.put(Favorites.APPWIDGET_ID, appWidgetId)
                db.insert(TABLE_FAVORITES, null, values)
                allocatedAppWidgets = true
            } catch (ex: RuntimeException) {
                Log.e(LOG_TAG, "Problem allocating appWidgetId", ex)
            }

            // If any appWidgetIds allocated, then launch over to binder
            if (allocatedAppWidgets) {
                launchAppWidgetBinder(bindSources, bindTargets)
            }
            return allocatedAppWidgets
        }

        companion object {
            private const val TAG_FAVORITE: String = "favorite"
            private const val TAG_SHORTCUT: String = "shortcut"
            private const val TAG_CLOCK: String = "clock"
            private const val TAG_SEARCH: String = "search"
        }

        init {
            mAppWidgetHost = AppWidgetHost(mContext, Launcher.APPWIDGET_HOST_ID)
        }
    }

    internal class SqlArguments {
        var table: String? = null
        var where: String? = null
        val args: Array<String>?

        constructor(url: Uri, where: String?, args: Array<String>?) {
            if (url.pathSegments.size == 1) {
                table = url.pathSegments[0]
                this.where = where
                this.args = args
            } else if (url.pathSegments.size != 2) {
                throw IllegalArgumentException("Invalid URI: $url")
            } else if (!TextUtils.isEmpty(where)) {
                throw UnsupportedOperationException("WHERE clause not supported: $url")
            } else {
                table = url.pathSegments[0]
                this.where = "_id=" + ContentUris.parseId(url)
                this.args = null
            }
        }

        constructor(url: Uri) {
            if (url.pathSegments.size == 1) {
                table = url.pathSegments[0]
                where = null
                args = null
            } else {
                throw IllegalArgumentException("Invalid URI: $url")
            }
        }
    }

    companion object {
        private const val LOG_TAG: String = "LauncherProvider"
        private const val LOGD: Boolean = true
        private const val DATABASE_NAME: String = "launcher.db"
        private const val DATABASE_VERSION: Int = 4
        const val AUTHORITY: String = "ussr.razar.android.dount.launcher.settings"
        const val EXTRA_BIND_SOURCES: String = "ussr.razar.android.dount.launcher.settings.bindsources"
        const val EXTRA_BIND_TARGETS: String = "ussr.razar.android.dount.launcher.settings.bindtargets"
        const val TABLE_FAVORITES: String = "favorites"
        const val TABLE_GESTURES: String = "gestures"
        const val PARAMETER_NOTIFY: String = "notify"

        /**
         * [Uri] triggered at any registered [android.database.ContentObserver] when
         * [AppWidgetHost.deleteHost] is called during database creation.
         * Use this to recall [AppWidgetHost.startListening] if needed.
         */
        val CONTENT_APPWIDGET_RESET_URI: Uri = Uri.parse("content://$AUTHORITY/appWidgetReset")

        /**
         * Build a query string that will match any row where the column matches
         * anything in the values list.
         */
        fun buildOrWhereString(column: String?, values: IntArray): String {
            val selectWhere: StringBuilder = StringBuilder()
            for (i in values.indices.reversed()) {
                selectWhere.append(column).append("=").append(values[i])
                if (i > 0) {
                    selectWhere.append(" OR ")
                }
            }
            return selectWhere.toString()
        }
    }
}