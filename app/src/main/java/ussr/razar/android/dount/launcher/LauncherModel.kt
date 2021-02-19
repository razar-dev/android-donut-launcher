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
import android.content.Intent.ShortcutIconResource
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.BaseColumns
import android.util.Log
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites
import ussr.razar.android.internal.utils.ApplicationInfoComparator
import java.lang.ref.WeakReference
import java.net.URISyntaxException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maintains in-memory state of the Launcher. It is expected that there should be only one
 * LauncherModel object held in a static. Also provide APIs for updating the database state
 * for the Launcher.
 */
class LauncherModel {
    private var mApplicationsLoaded = false
    private var mDesktopItemsLoaded = false
    private val mDesktopItems: ArrayList<ItemInfo> = ArrayList()
    private val mDesktopAppWidgets: ArrayList<LauncherAppWidgetInfo> = ArrayList()

    private val mFolders: HashMap<Long, FolderInfo> = HashMap()
    private val mApplications: ArrayList<ApplicationInfo> = ArrayList(DEFAULT_APPLICATIONS_NUMBER)

    /**
     * @return The current list of applications
     */
    var applicationsAdapter: ApplicationsAdapter? = null
        private set
    private var mApplicationsLoader: ApplicationsLoader? = null
    private var mDesktopItemsLoader: DesktopItemsLoader? = null
    private var mApplicationsLoaderThread: Thread? = null
    private var mDesktopLoaderThread: Thread? = null
    private val mAppInfoCache = HashMap<ComponentName, ApplicationInfo>(INITIAL_ICON_CACHE_CAPACITY)
    @Synchronized
    fun abortLoaders() {
        if (DEBUG_LOADERS) Log.d(LOG_TAG, "aborting loaders")
        if (mApplicationsLoader != null && mApplicationsLoader!!.isRunning) {
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> aborting applications loader")
            mApplicationsLoader!!.stop()
            mApplicationsLoaded = false
        }
        if (mDesktopItemsLoader != null && mDesktopItemsLoader!!.isRunning) {
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> aborting workspace loader")
            mDesktopItemsLoader!!.stop()
            mDesktopItemsLoaded = false
        }
    }

    /**
     * Drop our cache of components to their lables & icons.  We do
     * this from Launcher when applications are added/removed.  It's a
     * bit overkill, but it's a rare operation anyway.
     */
    @Synchronized
    fun dropApplicationCache() {
        mAppInfoCache.clear()
    }

    /**
     * Loads the list of installed applications in mApplications.
     *
     * @return true if the applications loader must be started
     * (see startApplicationsLoader()), false otherwise.
     */
    @Synchronized
    fun loadApplications(
        isLaunching: Boolean, launcher: Launcher,
        localeChanged: Boolean
    ): Boolean {
        if (DEBUG_LOADERS) Log.d(LOG_TAG, "load applications")
        if (isLaunching && mApplicationsLoaded && !localeChanged) {
            applicationsAdapter = ApplicationsAdapter(launcher, mApplications)
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> applications loaded, return")
            return false
        }
        stopAndWaitForApplicationsLoader()
        if (localeChanged) {
            dropApplicationCache()
        }
        if (applicationsAdapter == null || isLaunching || localeChanged) {
            applicationsAdapter = ApplicationsAdapter(launcher, mApplications)
        }
        mApplicationsLoaded = false
        if (!isLaunching) {
            startApplicationsLoaderLocked(launcher, false)
            return false
        }
        return true
    }

    @Synchronized
    private fun stopAndWaitForApplicationsLoader() {
        if (mApplicationsLoader != null && mApplicationsLoader!!.isRunning) {
            if (DEBUG_LOADERS) {
                Log.d(LOG_TAG, "  --> wait for applications loader (" + mApplicationsLoader!!.mId + ")")
            }
            mApplicationsLoader!!.stop()
            // Wait for the currently running thread to finish, this can take a little
            // time but it should be well below the timeout limit
            try {
                mApplicationsLoaderThread!!.join(APPLICATION_NOT_RESPONDING_TIMEOUT)
            } catch (e: InterruptedException) {
                // Empty
            }
        }
    }

    @Synchronized
    private fun startApplicationsLoader(launcher: Launcher, isLaunching: Boolean) {
        if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> starting applications loader unlocked")
        startApplicationsLoaderLocked(launcher, isLaunching)
    }

    private fun startApplicationsLoaderLocked(launcher: Launcher, isLaunching: Boolean) {
        if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> starting applications loader")
        stopAndWaitForApplicationsLoader()
        mApplicationsLoader = ApplicationsLoader(launcher, isLaunching)
        mApplicationsLoaderThread = Thread(mApplicationsLoader, "Applications Loader")
        mApplicationsLoaderThread!!.start()
    }

    @Synchronized
    fun addPackage(launcher: Launcher, packageName: String?) {
        if (mApplicationsLoader != null && mApplicationsLoader!!.isRunning) {
            startApplicationsLoaderLocked(launcher, false)
            return
        }
        if (packageName != null && packageName.isNotEmpty()) {
            val packageManager = launcher.packageManager
            val matches = findActivitiesForPackage(packageManager, packageName)
            if (matches.isNotEmpty()) {
                val adapter = applicationsAdapter
                val cache = mAppInfoCache
                for (info in matches) {
                    adapter!!.setNotifyOnChange(false)
                    adapter.add(makeAndCacheApplicationInfo(packageManager, cache, info, launcher))
                }
                adapter!!.sort(ApplicationInfoComparator())
                adapter.notifyDataSetChanged()
            }
        }
    }

    @Synchronized
    fun removePackage(launcher: Launcher, packageName: String?) {
        if (mApplicationsLoader != null && mApplicationsLoader!!.isRunning) {
            dropApplicationCache() // TODO: this could be optimized
            startApplicationsLoaderLocked(launcher, false)
            return
        }
        if (packageName != null && packageName.isNotEmpty()) {
            val adapter = applicationsAdapter
            val toRemove: MutableList<ApplicationInfo> = ArrayList()
            val count = adapter!!.count
            for (i in 0 until count) {
                val applicationInfo = adapter.getItem(i)
                val intent = applicationInfo!!.intent
                val component = intent!!.component
                if (packageName == component?.packageName) {
                    toRemove.add(applicationInfo)
                }
            }
            val cache = mAppInfoCache
            for (info in toRemove) {
                adapter.setNotifyOnChange(false)
                adapter.remove(info)
                cache.remove(info.intent!!.component)
            }
            if (toRemove.size > 0) {
                adapter.sort(ApplicationInfoComparator())
                adapter.notifyDataSetChanged()
            }
        }
    }

    @Synchronized
    fun updatePackage(launcher: Launcher, packageName: String?) {
        if (mApplicationsLoader != null && mApplicationsLoader!!.isRunning) {
            startApplicationsLoaderLocked(launcher, false)
            return
        }
        if (packageName != null && packageName.isNotEmpty()) {
            val packageManager = launcher.packageManager
            val adapter = applicationsAdapter
            val matches = findActivitiesForPackage(packageManager, packageName)
            val count = matches.size
            var changed = false
            for (i in 0 until count) {
                val info = matches[i]
                val applicationInfo = findIntent(adapter,
                    info.activityInfo.applicationInfo.packageName, info.activityInfo.name)
                if (applicationInfo != null) {
                    updateAndCacheApplicationInfo(packageManager, info, applicationInfo, launcher)
                    changed = true
                }
            }
            if (syncLocked(launcher, packageName)) changed = true
            if (changed) {
                adapter!!.sort(ApplicationInfoComparator())
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateAndCacheApplicationInfo(
        packageManager: PackageManager, info: ResolveInfo,
        applicationInfo: ApplicationInfo, context: Context
    ) {
        updateApplicationInfoTitleAndIcon(packageManager, info, applicationInfo, context)
        val componentName = ComponentName(
            info.activityInfo.applicationInfo.packageName, info.activityInfo.name)
        mAppInfoCache[componentName] = applicationInfo
    }

    @Synchronized
    fun syncPackage(launcher: Launcher, packageName: String?) {
        if (mApplicationsLoader != null && mApplicationsLoader!!.isRunning) {
            startApplicationsLoaderLocked(launcher, false)
            return
        }
        if (packageName != null && packageName.isNotEmpty()) {
            if (syncLocked(launcher, packageName)) {
                val adapter = applicationsAdapter
                adapter!!.sort(ApplicationInfoComparator())
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun syncLocked(launcher: Launcher, packageName: String): Boolean {
        val packageManager = launcher.packageManager
        val matches = findActivitiesForPackage(packageManager, packageName)
        if (matches.isNotEmpty()) {
            val adapter = applicationsAdapter

            // Find disabled activities and remove them from the adapter
            val removed = removeDisabledActivities(packageName, matches, adapter)
            // Find enable activities and add them to the adapter
            // Also updates existing activities with new labels/icons
            val added = addEnabledAndUpdateActivities(matches, adapter, launcher)
            return added || removed
        }
        return false
    }

    private fun addEnabledAndUpdateActivities(
        matches: List<ResolveInfo>,
        adapter: ApplicationsAdapter?, launcher: Launcher
    ): Boolean {
        val toAdd: MutableList<ApplicationInfo> = ArrayList()
        val count = matches.size
        var changed = false
        for (i in 0 until count) {
            val info = matches[i]
            val applicationInfo = findIntent(adapter,
                info.activityInfo.applicationInfo.packageName, info.activityInfo.name)
            changed = if (applicationInfo == null) {
                toAdd.add(makeAndCacheApplicationInfo(launcher.packageManager,
                    mAppInfoCache, info, launcher))
                true
            } else {
                updateAndCacheApplicationInfo(
                    launcher.packageManager, info, applicationInfo, launcher)
                true
            }
        }
        for (info in toAdd) {
            adapter!!.setNotifyOnChange(false)
            adapter.add(info)
        }
        return changed
    }

    private fun removeDisabledActivities(
        packageName: String, matches: List<ResolveInfo>,
        adapter: ApplicationsAdapter?
    ): Boolean {
        val toRemove: MutableList<ApplicationInfo?> = ArrayList()
        val count = adapter!!.count
        var changed = false
        for (i in 0 until count) {
            val applicationInfo = adapter.getItem(i)
            val intent = applicationInfo!!.intent
            val component = intent!!.component
            if (packageName == component?.packageName) {
                if (!findIntent(matches, component)) {
                    toRemove.add(applicationInfo)
                    changed = true
                }
            }
        }
        val cache = mAppInfoCache
        for (info in toRemove) {
            adapter.setNotifyOnChange(false)
            adapter.remove(info)
            cache.remove(info!!.intent!!.component)
        }
        return changed
    }

    fun getApplicationInfoIcon(manager: PackageManager, info: ApplicationInfo): Drawable? {
        val resolveInfo = manager.resolveActivity(info.intent!!, 0) ?: return null
        val componentName = ComponentName(
            resolveInfo.activityInfo.applicationInfo.packageName,
            resolveInfo.activityInfo.name)
        val application = mAppInfoCache[componentName] ?: return resolveInfo.activityInfo.loadIcon(manager)
        return application.icon
    }

    private inner class ApplicationsLoader(launcher: Launcher, private val mIsLaunching: Boolean) : Runnable {
        private val mLauncher: WeakReference<Launcher> = WeakReference(launcher)

        @Volatile
        private var mStopped = false

        @Volatile
        var isRunning: Boolean
            private set
        val mId: Int
        fun stop() {
            mStopped = true
        }

        override fun run() {
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  ----> running applications loader ($mId)")

            // Elevate priority when Home launches for the first time to avoid
            // starving at boot time. Staring at a blank home is not cool.
            Process.setThreadPriority(if (mIsLaunching) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND)
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val launcher = mLauncher.get()
            val manager = launcher!!.packageManager
            val apps = manager.queryIntentActivities(mainIntent, 0)
            if (!mStopped) {
                val count = apps.size
                // Can be set to null on the UI thread by the unbind() method
                // Do not access without checking for null first
                val applicationList: ApplicationsAdapter? = applicationsAdapter
                var action = ChangeNotifier(applicationList, true)
                val appInfoCache = mAppInfoCache
                var i = 0
                while (i < count && !mStopped) {
                    val info = apps[i]
                    val application = makeAndCacheApplicationInfo(manager, appInfoCache, info, launcher)
                    if (action.add(application) && !mStopped) {
                        launcher.runOnUiThread(action)
                        action = ChangeNotifier(applicationList, false)
                    }
                    i++
                }
                launcher.runOnUiThread(action)
            }
            synchronized(this@LauncherModel) {
                if (!mStopped) {
                    mApplicationsLoaded = true
                } else {
                    if (DEBUG_LOADERS) Log.d(LOG_TAG, "  ----> applications loader stopped ($mId)")
                }
            }
            isRunning = false
        }

        init {
            isRunning = true
            mId = sAppsLoaderCount.getAndIncrement()
        }
    }

    private class ChangeNotifier(private val mApplicationList: ApplicationsAdapter?, first: Boolean) : Runnable {
        private val mBuffer: ArrayList<ApplicationInfo>
        private var mFirst = true
        override fun run() {
            val applicationList = mApplicationList ?: return
            // Can be set to null on the UI thread by the unbind() method
            if (mFirst) {
                applicationList.setNotifyOnChange(false)
                applicationList.clear()
                if (DEBUG_LOADERS) Log.d(LOG_TAG, "  ----> cleared application list")
                mFirst = false
            }
            val buffer = mBuffer
            val count = buffer.size
            for (i in 0 until count) {
                applicationList.setNotifyOnChange(false)
                applicationList.add(buffer[i])
            }
            buffer.clear()
            applicationList.sort(ApplicationInfoComparator())
            applicationList.notifyDataSetChanged()
        }

        fun add(application: ApplicationInfo): Boolean {
            val buffer = mBuffer
            buffer.add(application)
            return buffer.size >= UI_NOTIFICATION_RATE
        }

        init {
            mFirst = first
            mBuffer = ArrayList(UI_NOTIFICATION_RATE)
        }
    }



    val isDesktopLoaded: Boolean
        get() = mDesktopItemsLoaded

    /**
     * Loads all of the items on the desktop, in folders, or in the dock.
     * These can be apps, shortcuts or widgets
     */
    fun loadUserItems(
        isLaunching: Boolean, launcher: Launcher, localeChanged: Boolean,
        loadApplications: Boolean
    ) {
        var loadApplications = loadApplications
        if (DEBUG_LOADERS) Log.d(LOG_TAG, "loading user items")
        if (isLaunching && isDesktopLoaded) {
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> items loaded, return")
            if (loadApplications) startApplicationsLoader(launcher, true)
            // We have already loaded our data from the DB
            launcher.onDesktopItemsLoaded(mDesktopItems, mDesktopAppWidgets)
            return
        }
        if (mDesktopItemsLoader != null && mDesktopItemsLoader!!.isRunning) {
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> stopping workspace loader")
            mDesktopItemsLoader!!.stop()
            // Wait for the currently running thread to finish, this can take a little
            // time but it should be well below the timeout limit
            try {
                mDesktopLoaderThread!!.join(APPLICATION_NOT_RESPONDING_TIMEOUT)
            } catch (e: InterruptedException) {
                // Empty
            }

            // If the thread we are interrupting was tasked to load the list of
            // applications make sure we keep that information in the new loader
            // spawned below
            // note: we don't apply this to localeChanged because the thread can
            // only be stopped *after* the localeChanged handling has occured
            loadApplications = mDesktopItemsLoader!!.mLoadApplications
        }
        if (DEBUG_LOADERS) Log.d(LOG_TAG, "  --> starting workspace loader")
        mDesktopItemsLoaded = false
        mDesktopItemsLoader = DesktopItemsLoader(launcher, localeChanged, loadApplications,
            isLaunching)
        mDesktopLoaderThread = Thread(mDesktopItemsLoader, "Desktop Items Loader")
        mDesktopLoaderThread!!.start()
    }

    private inner class DesktopItemsLoader(
        launcher: Launcher, localeChanged: Boolean, val mLoadApplications: Boolean,
        private val mIsLaunching: Boolean
    ) : Runnable {
        @Volatile
        private var mStopped = false

        @Volatile
        var isRunning = false
            private set
        private val mLauncher: WeakReference<Launcher> = WeakReference(launcher)
        private val mLocaleChanged: Boolean = localeChanged
        private val mId: Int = sWorkspaceLoaderCount.getAndIncrement()
        fun stop() {
            mStopped = true
        }

        override fun run() {
            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  ----> running workspace loader ($mId)")
            isRunning = true
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            val launcher = mLauncher.get()
            val contentResolver = launcher!!.contentResolver
            val manager = launcher.packageManager
            if (mLocaleChanged) {
                updateShortcutLabels(contentResolver, manager)
            }
            val desktopItems = mDesktopItems
            val desktopAppWidgets = mDesktopAppWidgets
            val c = contentResolver.query(
                Favorites.CONTENT_URI, null, null, null, null)
            c?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val intentIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.INTENT)
                val titleIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.TITLE)
                val iconTypeIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.ICON_TYPE)
                val iconIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.ICON)
                val iconPackageIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.ICON_PACKAGE)
                val iconResourceIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.ICON_RESOURCE)
                val containerIndex = cursor.getColumnIndexOrThrow(Favorites.CONTAINER)
                val itemTypeIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE)
                val appWidgetIdIndex = cursor.getColumnIndexOrThrow(Favorites.APPWIDGET_ID)
                val screenIndex = cursor.getColumnIndexOrThrow(Favorites.SCREEN)
                val cellXIndex = cursor.getColumnIndexOrThrow(Favorites.CELLX)
                val cellYIndex = cursor.getColumnIndexOrThrow(Favorites.CELLY)
                val spanXIndex = cursor.getColumnIndexOrThrow(Favorites.SPANX)
                val spanYIndex = cursor.getColumnIndexOrThrow(Favorites.SPANY)
                val uriIndex = cursor.getColumnIndexOrThrow(Favorites.URI)
                val displayModeIndex = cursor.getColumnIndexOrThrow(Favorites.DISPLAY_MODE)
                var info: ApplicationInfo?
                var intentDescription: String?
                var widgetInfo: Widget
                var appWidgetInfo: LauncherAppWidgetInfo
                var container: Int
                var id: Long
                var intent: Intent?
                val folders = mFolders
                while (!mStopped && cursor.moveToNext()) {
                    try {
                        when (val itemType = cursor.getInt(itemTypeIndex)) {
                            BaseLauncherColumns.ITEM_TYPE_APPLICATION, BaseLauncherColumns.ITEM_TYPE_SHORTCUT -> {
                                intentDescription = cursor.getString(intentIndex)
                                intent = try {
                                    Intent.parseUri(intentDescription, 0)
                                } catch (e: URISyntaxException) {
                                    continue
                                }
                                info = if (itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION) {
                                    getApplicationInfo(manager, intent!!, launcher)
                                } else {
                                    getApplicationInfoShortcut(cursor, launcher, iconTypeIndex,
                                        iconPackageIndex, iconResourceIndex, iconIndex)
                                }
                                if (info == null) {
                                    info = ApplicationInfo()
                                    info.icon = manager.defaultActivityIcon
                                }
                                info.title = cursor.getString(titleIndex)
                                info.intent = intent
                                info.id = cursor.getLong(idIndex)
                                container = cursor.getInt(containerIndex)
                                info.container = container.toLong()
                                info.screen = cursor.getInt(screenIndex)
                                info.cellX = cursor.getInt(cellXIndex)
                                info.cellY = cursor.getInt(cellYIndex)
                                when (container) {
                                    Favorites.CONTAINER_DESKTOP -> desktopItems.add(info)
                                    else -> {
                                        // Item is in a user folder
                                        val folderInfo = findOrMakeUserFolder(folders, container.toLong())
                                        folderInfo.add(info)
                                    }
                                }
                            }
                            Favorites.ITEM_TYPE_USER_FOLDER -> {
                                id = cursor.getLong(idIndex)
                                val folderInfo = findOrMakeUserFolder(folders, id)
                                folderInfo.title = cursor.getString(titleIndex)
                                folderInfo.id = id
                                container = cursor.getInt(containerIndex)
                                folderInfo.container = container.toLong()
                                folderInfo.screen = cursor.getInt(screenIndex)
                                folderInfo.cellX = cursor.getInt(cellXIndex)
                                folderInfo.cellY = cursor.getInt(cellYIndex)
                                when (container) {
                                    Favorites.CONTAINER_DESKTOP -> desktopItems.add(folderInfo)
                                }
                            }
                            Favorites.ITEM_TYPE_LIVE_FOLDER -> {
                                id = cursor.getLong(idIndex)
                                val liveFolderInfo = findOrMakeLiveFolder(folders, id)
                                intentDescription = cursor.getString(intentIndex)
                                intent = null
                                if (intentDescription != null) {
                                    try {
                                        intent = Intent.parseUri(intentDescription, 0)
                                    } catch (e: URISyntaxException) {
                                        // Ignore, a live folder might not have a base intent
                                    }
                                }
                                liveFolderInfo.title = cursor.getString(titleIndex)
                                liveFolderInfo.id = id
                                container = cursor.getInt(containerIndex)
                                liveFolderInfo.container = container.toLong()
                                liveFolderInfo.screen = cursor.getInt(screenIndex)
                                liveFolderInfo.cellX = cursor.getInt(cellXIndex)
                                liveFolderInfo.cellY = cursor.getInt(cellYIndex)
                                liveFolderInfo.uri = Uri.parse(cursor.getString(uriIndex))
                                liveFolderInfo.baseIntent = intent
                                liveFolderInfo.displayMode = cursor.getInt(displayModeIndex)
                                loadLiveFolderIcon(launcher, cursor, iconTypeIndex, iconPackageIndex,
                                    iconResourceIndex, liveFolderInfo)
                                when (container) {
                                    Favorites.CONTAINER_DESKTOP -> desktopItems.add(liveFolderInfo)
                                }
                            }
                            Favorites.ITEM_TYPE_WIDGET_SEARCH -> {
                                widgetInfo = Widget.makeSearch()
                                container = cursor.getInt(containerIndex)
                                if (container != Favorites.CONTAINER_DESKTOP) {
                                    Log.e(Launcher.LOG_TAG, "Widget found where container "
                                            + "!= CONTAINER_DESKTOP  ignoring!")
                                    continue
                                }
                                widgetInfo.id = cursor.getLong(idIndex)
                                widgetInfo.screen = cursor.getInt(screenIndex)
                                widgetInfo.container = container.toLong()
                                widgetInfo.cellX = cursor.getInt(cellXIndex)
                                widgetInfo.cellY = cursor.getInt(cellYIndex)
                                desktopItems.add(widgetInfo)
                            }
                            Favorites.ITEM_TYPE_APPWIDGET -> {
                                // Read all Launcher-specific widget details
                                val appWidgetId = cursor.getInt(appWidgetIdIndex)
                                appWidgetInfo = LauncherAppWidgetInfo(appWidgetId)
                                appWidgetInfo.id = cursor.getLong(idIndex)
                                appWidgetInfo.screen = cursor.getInt(screenIndex)
                                appWidgetInfo.cellX = cursor.getInt(cellXIndex)
                                appWidgetInfo.cellY = cursor.getInt(cellYIndex)
                                appWidgetInfo.spanX = cursor.getInt(spanXIndex)
                                appWidgetInfo.spanY = cursor.getInt(spanYIndex)
                                container = cursor.getInt(containerIndex)
                                if (container != Favorites.CONTAINER_DESKTOP) {
                                    Log.e(Launcher.LOG_TAG, "Widget found where container "
                                            + "!= CONTAINER_DESKTOP -- ignoring!")
                                    continue
                                }
                                appWidgetInfo.container = cursor.getInt(containerIndex).toLong()
                                desktopAppWidgets.add(appWidgetInfo)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(Launcher.LOG_TAG, "Desktop items loading interrupted:", e)
                    }
                }
            }
            synchronized(this@LauncherModel) {
                if (!mStopped) {
                    if (DEBUG_LOADERS) {
                        Log.d(LOG_TAG, "  --> done loading workspace")
                        Log.d(LOG_TAG, "  ----> worskpace items=" + desktopItems.size)
                        Log.d(LOG_TAG, "  ----> worskpace widgets=" + desktopAppWidgets.size)
                    }

                    // Create a copy of the lists in case the workspace loader is restarted
                    // and the list are cleared before the UI can go through them
                    val uiDesktopItems = ArrayList(desktopItems)
                    val uiDesktopWidgets = ArrayList(desktopAppWidgets)
                    if (!mStopped) {
                        Log.d(LOG_TAG, "  ----> items cloned, ready to refresh UI")
                        launcher.runOnUiThread(Runnable {
                            if (DEBUG_LOADERS) Log.d(LOG_TAG, "  ----> onDesktopItemsLoaded()")
                            launcher.onDesktopItemsLoaded(uiDesktopItems, uiDesktopWidgets)
                        })
                    }
                    if (mLoadApplications) {
                        if (DEBUG_LOADERS) {
                            Log.d(LOG_TAG, "  ----> loading applications from workspace loader")
                        }
                        startApplicationsLoader(launcher, mIsLaunching)
                    }
                    mDesktopItemsLoaded = true
                } else {
                    if (DEBUG_LOADERS) Log.d(LOG_TAG, "  ----> worskpace loader was stopped")
                }
            }
            isRunning = false
        }

    }

    /**
     * Finds the user folder defined by the specified id.
     *
     * @param id The id of the folder to look for.
     *
     * @return A UserFolderInfo if the folder exists or null otherwise.
     */
    fun findFolderById(id: Long): FolderInfo? {
        return mFolders[id]
    }

    fun addFolder(info: FolderInfo) {
        mFolders[info.id] = info
    }

    /**
     * Return an existing UserFolderInfo object if we have encountered this ID previously, or make a
     * new one.
     */
    private fun findOrMakeUserFolder(folders: HashMap<Long, FolderInfo>?, id: Long): UserFolderInfo {
        // See if a placeholder was created for us already
        var folderInfo = folders!![id]
        if (folderInfo == null || folderInfo !is UserFolderInfo) {
            // No placeholder -- create a new instance
            folderInfo = UserFolderInfo()
            folders[id] = folderInfo
        }
        return folderInfo
    }

    /**
     * Return an existing UserFolderInfo object if we have encountered this ID previously, or make a
     * new one.
     */
    private fun findOrMakeLiveFolder(folders: HashMap<Long, FolderInfo>?, id: Long): LiveFolderInfo {
        // See if a placeholder was created for us already
        var folderInfo = folders!![id]
        if (folderInfo == null || folderInfo !is LiveFolderInfo) {
            // No placeholder -- create a new instance
            folderInfo = LiveFolderInfo()
            folders[id] = folderInfo
        }
        return folderInfo
    }

    /**
     * Remove the callback for the cached drawables or we leak the previous
     * Home screen on orientation change.
     */
    fun unbind() {
        // Interrupt the applications loader before setting the adapter to null
        stopAndWaitForApplicationsLoader()
        applicationsAdapter = null
        unbindAppDrawables(mApplications)
        unbindDrawables(mDesktopItems)
        unbindAppWidgetHostViews(mDesktopAppWidgets)
        unbindCachedIconDrawables()
    }

    /**
     * Remove the callback for the cached drawables or we leak the previous
     * Home screen on orientation change.
     */
    private fun unbindDrawables(desktopItems: ArrayList<ItemInfo>) {
        for (i in 0 until desktopItems.size) {
            val item = desktopItems[i]
            when (item.itemType) {
                BaseLauncherColumns.ITEM_TYPE_APPLICATION, BaseLauncherColumns.ITEM_TYPE_SHORTCUT -> (item as ApplicationInfo?)!!.icon!!.callback =
                    null
            }
        }
    }

    /**
     * Remove the callback for the cached drawables or we leak the previous
     * Home screen on orientation change.
     */
    private fun unbindAppDrawables(applications: ArrayList<ApplicationInfo>?) {
        if (applications != null) {
            val count = applications.size
            for (i in 0 until count) {
                applications[i].icon!!.callback = null
            }
        }
    }

    /**
     * Remove any [LauncherAppWidgetHostView] references in our widgets.
     */
    private fun unbindAppWidgetHostViews(appWidgets: ArrayList<LauncherAppWidgetInfo>?) {
        if (appWidgets != null) {
            val count = appWidgets.size
            for (i in 0 until count) {
                val launcherInfo = appWidgets[i]
                launcherInfo.hostView = null
            }
        }
    }

    /**
     * Remove the callback for the cached drawables or we leak the previous
     * Home screen on orientation change.
     */
    private fun unbindCachedIconDrawables() {
        for (appInfo in mAppInfoCache.values) {
            appInfo.icon!!.callback = null
        }
    }

    /**
     * Fills in the occupied structure with all of the shortcuts, apps, folders and widgets in
     * the model.
     */
    fun findAllOccupiedCells(occupied: Array<BooleanArray>, screen: Int) {
        for (i in 0 until mDesktopItems.size) {
            addOccupiedCells(occupied, screen, mDesktopItems[i])
        }
        for (i in 0 until mDesktopAppWidgets.size) {
            addOccupiedCells(occupied, screen, mDesktopAppWidgets[i])
        }
    }

    /**
     * Add the footprint of the specified item to the occupied array
     */
    private fun addOccupiedCells(
        occupied: Array<BooleanArray>, screen: Int,
        item: ItemInfo?
    ) {
        if (item!!.screen == screen) {
            for (xx in item.cellX until item.cellX + item.spanX) {
                for (yy in item.cellY until item.cellY + item.spanY) {
                    occupied[xx][yy] = true
                }
            }
        }
    }

    /**
     * Add an item to the desktop
     * @param info
     */
    fun addDesktopItem(info: ItemInfo) {
        // TODO: write to DB; also check that folder has been added to folders list
        mDesktopItems.add(info)
    }

    /**
     * Remove an item from the desktop
     * @param info
     */
    fun removeDesktopItem(info: ItemInfo?) {
        // TODO: write to DB; figure out if we should remove folder from folders list
        mDesktopItems.remove(info)
    }

    /**
     * Add a widget to the desktop
     */
    fun addDesktopAppWidget(info: LauncherAppWidgetInfo) {
        mDesktopAppWidgets.add(info)
    }

    /**
     * Remove a widget from the desktop
     */
    fun removeDesktopAppWidget(info: LauncherAppWidgetInfo) {
        mDesktopAppWidgets.remove(info)
    }

    /**
     * Make an ApplicationInfo object for a sortcut
     */
    private fun getApplicationInfoShortcut(
        c: Cursor, context: Context,
        iconTypeIndex: Int, iconPackageIndex: Int, iconResourceIndex: Int, iconIndex: Int
    ): ApplicationInfo {
        val info = ApplicationInfo()
        info.itemType = BaseLauncherColumns.ITEM_TYPE_SHORTCUT
        when (c.getInt(iconTypeIndex)) {
            BaseLauncherColumns.ICON_TYPE_RESOURCE -> {
                val packageName = c.getString(iconPackageIndex)
                val resourceName = c.getString(iconResourceIndex)
                val packageManager = context.packageManager
                try {
                    val resources = packageManager.getResourcesForApplication(packageName)
                    val id = resources.getIdentifier(resourceName, null, null)
                    info.icon = Utilities.createIconThumbnail(resources.getDrawable(id), context)
                } catch (e: Exception) {
                    info.icon = packageManager.defaultActivityIcon
                }
                info.iconResource = ShortcutIconResource()
                info.iconResource!!.packageName = packageName
                info.iconResource!!.resourceName = resourceName
                info.customIcon = false
            }
            BaseLauncherColumns.ICON_TYPE_BITMAP -> {
                val data = c.getBlob(iconIndex)
                try {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    info.icon = FastBitmapDrawable(
                        Utilities.createBitmapThumbnail(bitmap, context))
                } catch (e: Exception) {
                    info.icon = context.packageManager.defaultActivityIcon
                }
                info.filtered = true
                info.customIcon = true
            }
            else -> {
                info.icon = context.packageManager.defaultActivityIcon
                info.customIcon = false
            }
        }
        return info
    }

    /**
     * Remove an item from the in-memory represention of a user folder. Does not change the DB.
     */
    fun removeUserFolderItem(folder: UserFolderInfo?, info: ItemInfo?) {
        folder!!.contents.remove(info)
    }

    /**
     * Removes a UserFolder from the in-memory list of folders. Does not change the DB.
     * @param userFolderInfo
     */
    fun removeUserFolder(userFolderInfo: UserFolderInfo) {
        mFolders.remove(userFolderInfo.id)
    }

    fun getFolderById(context: Context, id: Long): FolderInfo? {
        val cr = context.contentResolver
        val c = cr.query(Favorites.CONTENT_URI,
            null,
            "_id=? and (itemType=? or itemType=?)",
            arrayOf(id.toString(), Favorites.ITEM_TYPE_USER_FOLDER.toString(), Favorites.ITEM_TYPE_LIVE_FOLDER.toString()),
            null)
        c?.use { cursor ->
            if (cursor.moveToFirst()) {
                val itemTypeIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE)
                val titleIndex = cursor.getColumnIndexOrThrow(BaseLauncherColumns.TITLE)
                val containerIndex = cursor.getColumnIndexOrThrow(Favorites.CONTAINER)
                val screenIndex = cursor.getColumnIndexOrThrow(Favorites.SCREEN)
                val cellXIndex = cursor.getColumnIndexOrThrow(Favorites.CELLX)
                val cellYIndex = cursor.getColumnIndexOrThrow(Favorites.CELLY)
                var folderInfo: FolderInfo? = null
                when (cursor.getInt(itemTypeIndex)) {
                    Favorites.ITEM_TYPE_USER_FOLDER -> folderInfo = findOrMakeUserFolder(mFolders, id)
                    Favorites.ITEM_TYPE_LIVE_FOLDER -> folderInfo = findOrMakeLiveFolder(mFolders, id)
                }
                folderInfo!!.title = cursor.getString(titleIndex)
                folderInfo.id = id
                folderInfo.container = cursor.getInt(containerIndex).toLong()
                folderInfo.screen = cursor.getInt(screenIndex)
                folderInfo.cellX = cursor.getInt(cellXIndex)
                folderInfo.cellY = cursor.getInt(cellYIndex)
                return folderInfo
            }
        }
        return null
    }

    companion object {
        const val DEBUG_LOADERS = false
        const val LOG_TAG = "HomeLoaders"
        private const val UI_NOTIFICATION_RATE = 4
        private const val DEFAULT_APPLICATIONS_NUMBER = 42
        private const val APPLICATION_NOT_RESPONDING_TIMEOUT: Long = 5000
        private const val INITIAL_ICON_CACHE_CAPACITY = 50
        private fun findActivitiesForPackage(
            packageManager: PackageManager,
            packageName: String
        ): List<ResolveInfo> {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(mainIntent, 0)
            val matches: MutableList<ResolveInfo> = ArrayList()
            // Find all activities that match the packageName
            val count = apps.size
            for (i in 0 until count) {
                val info = apps[i]
                val activityInfo = info.activityInfo
                if (packageName == activityInfo.packageName) {
                    matches.add(info)
                }
            }
            return matches
        }

        private fun findIntent(
            adapter: ApplicationsAdapter?, packageName: String,
            name: String
        ): ApplicationInfo? {
            val count = adapter!!.count
            for (i in 0 until count) {
                val applicationInfo = adapter.getItem(i)
                val intent = applicationInfo!!.intent
                val component = intent!!.component
                if (packageName == component?.packageName && name == component.className) {
                    return applicationInfo
                }
            }
            return null
        }

        private fun findIntent(apps: List<ResolveInfo>, component: ComponentName): Boolean {
            val className = component.className
            for (info in apps) {
                val activityInfo = info.activityInfo
                if (activityInfo.name == className) {
                    return true
                }
            }
            return false
        }

        private fun makeAndCacheApplicationInfo(
            manager: PackageManager,
            appInfoCache: HashMap<ComponentName, ApplicationInfo>, info: ResolveInfo,
            context: Context?
        ): ApplicationInfo {
            val componentName = ComponentName(
                info.activityInfo.applicationInfo.packageName,
                info.activityInfo.name)
            var application = appInfoCache[componentName]
            if (application == null) {
                application = ApplicationInfo()
                application.container = ItemInfo.NO_ID.toLong()
                updateApplicationInfoTitleAndIcon(manager, info, application, context)
                application.setActivity(componentName,
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                appInfoCache[componentName] = application
            }
            return application
        }

        private fun updateApplicationInfoTitleAndIcon(
            manager: PackageManager, info: ResolveInfo,
            application: ApplicationInfo, context: Context?
        ) {
            application.title = info.loadLabel(manager)
            if (application.title == null) {
                application.title = info.activityInfo.name
            }
            application.icon = Utilities.createIconThumbnail(info.activityInfo.loadIcon(manager), context)
            application.filtered = false
        }

        private val sAppsLoaderCount = AtomicInteger(1)
        private val sWorkspaceLoaderCount = AtomicInteger(1)
        private fun updateShortcutLabels(resolver: ContentResolver, manager: PackageManager) {
            val c = resolver.query(Favorites.CONTENT_URI, arrayOf(BaseColumns._ID, BaseLauncherColumns.TITLE,
                BaseLauncherColumns.INTENT, BaseLauncherColumns.ITEM_TYPE),
                null, null, null)!!
            val idIndex = c.getColumnIndexOrThrow(BaseColumns._ID)
            val intentIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.INTENT)
            val itemTypeIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE)
            val titleIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.TITLE)

            // boolean changed = false;
            c.use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        if (cursor.getInt(itemTypeIndex) !=
                            BaseLauncherColumns.ITEM_TYPE_APPLICATION
                        ) {
                            continue
                        }
                        val intentUri = cursor.getString(intentIndex)
                        if (intentUri != null) {
                            val shortcut = Intent.parseUri(intentUri, 0)
                            if (Intent.ACTION_MAIN == shortcut.action) {
                                val name = shortcut.component
                                if (name != null) {
                                    val activityInfo = manager.getActivityInfo(name, 0)
                                    val title = cursor.getString(titleIndex)
                                    val label = getLabel(manager, activityInfo)
                                    if (title == null || title != label) {
                                        val values = ContentValues()
                                        values.put(BaseLauncherColumns.TITLE, label)
                                        resolver.update(
                                            Favorites.CONTENT_URI_NO_NOTIFICATION,
                                            values, "_id=?", arrayOf(cursor.getLong(idIndex).toString()))

                                        // changed = true;
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        private fun getLabel(manager: PackageManager, activityInfo: ActivityInfo): String {
            /*var label: String? = activityInfo.loadLabel(manager).toString()
            if (label == null) {
                label = manager.getApplicationLabel(activityInfo.applicationInfo).toString()
            }*/
            return activityInfo.loadLabel(manager).toString()
        }

        private fun loadLiveFolderIcon(
            launcher: Launcher, c: Cursor, iconTypeIndex: Int,
            iconPackageIndex: Int, iconResourceIndex: Int, liveFolderInfo: LiveFolderInfo
        ) {
            when (c.getInt(iconTypeIndex)) {
                BaseLauncherColumns.ICON_TYPE_RESOURCE -> {
                    val packageName = c.getString(iconPackageIndex)
                    val resourceName = c.getString(iconResourceIndex)
                    val packageManager = launcher.packageManager
                    try {
                        val resources = packageManager.getResourcesForApplication(packageName)
                        val id = resources.getIdentifier(resourceName, null, null)
                        liveFolderInfo.icon = resources.getDrawable(id)
                    } catch (e: Exception) {
                        liveFolderInfo.icon = launcher.resources.getDrawable(R.drawable.ic_launcher_folder)
                    }
                    liveFolderInfo.iconResource.packageName = packageName
                    liveFolderInfo.iconResource.resourceName = resourceName
                }
                else -> liveFolderInfo.icon = launcher.resources.getDrawable(R.drawable.ic_launcher_folder)
            }
        }

        /**
         * Make an ApplicationInfo object for an application
         */
        private fun getApplicationInfo(
            manager: PackageManager, intent: Intent,
            context: Context?
        ): ApplicationInfo? {
            val resolveInfo = manager.resolveActivity(intent, 0) ?: return null
            val info = ApplicationInfo()
            val activityInfo = resolveInfo.activityInfo
            info.icon = Utilities.createIconThumbnail(activityInfo.loadIcon(manager), context)
            if (info.title == null || info.title!!.isEmpty()) {
                info.title = activityInfo.loadLabel(manager)
            }
            if (info.title == null) {
                info.title = ""
            }
            info.itemType = BaseLauncherColumns.ITEM_TYPE_APPLICATION
            return info
        }

        /**
         * Adds an item to the DB if it was not created previously, or move it to a new
         * <container></container>, screen, cellX, cellY>
         */
        fun addOrMoveItemInDatabase(
            context: Context?, item: ItemInfo?, container: Long,
            screen: Int, cellX: Int, cellY: Int
        ) {
            if (item!!.container == ItemInfo.NO_ID.toLong()) {
                // From all apps
                addItemToDatabase(context, item, container, screen, cellX, cellY, false)
            } else {
                // From somewhere else
                moveItemInDatabase(context, item, container, screen, cellX, cellY)
            }
        }

        /**
         * Move an item in the DB to a new <container></container>, screen, cellX, cellY>
         */
        fun moveItemInDatabase(
            context: Context?, item: ItemInfo?, container: Long, screen: Int,
            cellX: Int, cellY: Int
        ) {
            item!!.container = container
            item.screen = screen
            item.cellX = cellX
            item.cellY = cellY
            val values = ContentValues()
            val cr = context!!.contentResolver
            values.put(Favorites.CONTAINER, item.container)
            values.put(Favorites.CELLX, item.cellX)
            values.put(Favorites.CELLY, item.cellY)
            values.put(Favorites.SCREEN, item.screen)
            cr.update(Favorites.getContentUri(item.id, false), values, null, null)
        }

        /**
         * Returns true if the shortcuts already exists in the database.
         * we identify a shortcut by its title and intent.
         */
        fun shortcutExists(context: Context, title: String, intent: Intent): Boolean {
            val cr = context.contentResolver
            val c =
                cr.query(Favorites.CONTENT_URI, arrayOf("title", "intent"), "title=? and intent=?", arrayOf(title, intent.toUri(0)), null)
            return c.use { c ->
                c!!.moveToFirst()
            }
        }

        /**
         * Add an item to the database in a specified container. Sets the container, screen, cellX and
         * cellY fields of the item. Also assigns an ID to the item.
         */
        fun addItemToDatabase(
            context: Context?, item: ItemInfo, container: Long,
            screen: Int, cellX: Int, cellY: Int, notify: Boolean
        ) {
            item.container = container
            item.screen = screen
            item.cellX = cellX
            item.cellY = cellY
            val values = ContentValues()
            val cr = context!!.contentResolver
            item.onAddToDatabase(values)
            val result = cr.insert(if (notify) Favorites.CONTENT_URI else Favorites.CONTENT_URI_NO_NOTIFICATION, values)
            if (result != null) {
                item.id = result.pathSegments[1].toInt().toLong()
            }
        }

        /**
         * Update an item to the database in a specified container.
         */
        fun updateItemInDatabase(context: Context, item: ItemInfo?) {
            val values = ContentValues()
            val cr = context.contentResolver
            item!!.onAddToDatabase(values)
            cr.update(Favorites.getContentUri(item.id, false), values, null, null)
        }

        /**
         * Removes the specified item from the database
         * @param context
         * @param item
         */
        fun deleteItemFromDatabase(context: Context, item: ItemInfo) {
            val cr = context.contentResolver
            cr.delete(Favorites.getContentUri(item.id, false), null, null)
        }

        /**
         * Remove the contents of the specified folder from the database
         */
        fun deleteUserFolderContentsFromDatabase(context: Context, info: UserFolderInfo) {
            val cr = context.contentResolver
            cr.delete(Favorites.getContentUri(info.id, false), null, null)
            cr.delete(Favorites.CONTENT_URI,
                Favorites.CONTAINER + "=" + info.id, null)
        }
    }
}