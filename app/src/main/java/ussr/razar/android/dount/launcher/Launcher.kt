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

import android.annotation.SuppressLint
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.*
import android.content.Intent.ShortcutIconResource
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.LabeledIntent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.*
import android.os.MessageQueue.IdleHandler
import android.preference.PreferenceManager
import android.provider.LiveFolders
import android.provider.Settings
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.TextKeyListener
import android.util.Log
import android.util.SparseArray
import android.view.*
import android.view.View.OnLongClickListener
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.SlidingDrawer.*
import ussr.razar.android.content.LauncherIntent
import ussr.razar.android.content.LauncherIntent.Extra
import ussr.razar.android.content.LauncherMetadata
import ussr.razar.android.content.LauncherMetadata.Requirements
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.*
import kotlin.math.min

/**
 * Default launcher application.
 */
class Launcher : Activity(), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {
    private val mApplicationsReceiver: BroadcastReceiver = ApplicationsIntentReceiver()
    private val mCloseSystemDialogsReceiver: BroadcastReceiver = CloseSystemDialogsIntentReceiver()
    private val mObserver: ContentObserver = FavoritesChangeObserver()
    private val mWidgetObserver: ContentObserver = AppWidgetResetObserver()
    private var mInflater: LayoutInflater? = null
    private var mDragLayer: DragLayer? = null
    lateinit var workspace: Workspace
        private set
    private var mAppWidgetManager: AppWidgetManager? = null
    var appWidgetHost: LauncherAppWidgetHost? = null
        private set
    private var mAddItemCellInfo: CellLayout.CellInfo? = null
    private var mMenuAddInfo: CellLayout.CellInfo? = null
    private val mCellCoordinates = IntArray(2)
    private var mFolderInfo: FolderInfo? = null
    private var mDrawer: SlidingDrawer? = null
    private var mHandleIcon: TransitionDrawable? = null
    private var mHandleView: HandleView? = null
    private var mAllAppsGrid: AllAppsGridView? = null

    /**
     * Returns true if the workspace is being loaded. When the workspace is loading, no user
     * interaction should be allowed to avoid any conflict.
     *
     * @return True if the workspace is locked, false otherwise.
     */
    var isWorkspaceLocked = true
        private set
    private var mSavedState: Bundle? = null
    private var mDefaultKeySsb: SpannableStringBuilder? = null
    private var mDestroyed = false
    private var mIsNewIntent = false
    private var mRestoring = false
    private var mWaitingForResult = false
    private var mLocaleChanged = false
    private var mSavedInstanceState: Bundle? = null
    private var mBinder: DesktopBinder? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mInflater = layoutInflater
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val w = window // in Activity's onCreate() for instance
            w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        mAppWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = LauncherAppWidgetHost(this, APPWIDGET_HOST_ID)
        appWidgetHost!!.startListening()
        checkForLocaleChange()
        setWallpaperDimension()
        setContentView(R.layout.launcher)
        setupViews()
        registerIntentReceivers()
        registerContentObservers()
        mSavedState = savedInstanceState
        mSavedState?.let { restoreState(it) }
        if (PROFILE_STARTUP) {
            Debug.stopMethodTracing()
        }
        if (!mRestoring) {
            startLoaders()
        }

        // For handling default keys
        mDefaultKeySsb = SpannableStringBuilder()
        Selection.setSelection(mDefaultKeySsb, 0)
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)
    }

    private fun checkForLocaleChange() {
        val localeConfiguration = LocaleConfiguration()
        readConfiguration(this, localeConfiguration)
        val configuration = resources.configuration
        val previousLocale = localeConfiguration.locale
        val locale = configuration.locale.toString()
        val previousMcc = localeConfiguration.mcc
        val mcc = configuration.mcc
        val previousMnc = localeConfiguration.mnc
        val mnc = configuration.mnc
        mLocaleChanged = locale != previousLocale || mcc != previousMcc || mnc != previousMnc
        if (mLocaleChanged) {
            localeConfiguration.locale = locale
            localeConfiguration.mcc = mcc
            localeConfiguration.mnc = mnc
            writeConfiguration(this, localeConfiguration)
        }
    }

    private class LocaleConfiguration {
        var locale: String? = null
        var mcc = -1
        var mnc = -1
    }

    private fun startLoaders() {
        val loadApplications = model.loadApplications(true, this, mLocaleChanged)
        model.loadUserItems(!mLocaleChanged, this, mLocaleChanged, loadApplications)
        mRestoring = false
    }

    private fun setWallpaperDimension() {
        val wpm = getSystemService(WALLPAPER_SERVICE) as WallpaperManager
        val display = windowManager.defaultDisplay
        val isPortrait = display.width < display.height
        val width = if (isPortrait) display.width else display.height
        val height = if (isPortrait) display.height else display.width
        wpm.suggestDesiredDimensions(width * WALLPAPER_SCREENS_SPAN, height)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        mWaitingForResult = false

        // The pattern used here is that a user PICKs a specific application,
        // which, depending on the target, might need to CREATE the actual target.

        // For example, the user would PICK_SHORTCUT for "Music playlist", and we
        // launch over to the Music app to actually CREATE_SHORTCUT.
        if (resultCode == RESULT_OK && mAddItemCellInfo != null) {
            when (requestCode) {
                REQUEST_PICK_APPLICATION -> completeAddApplication(this, data, mAddItemCellInfo!!, !isWorkspaceLocked)
                REQUEST_PICK_SHORTCUT -> processShortcut(data, REQUEST_PICK_APPLICATION, REQUEST_CREATE_SHORTCUT)
                REQUEST_CREATE_SHORTCUT -> completeAddShortcut(data, mAddItemCellInfo!!, !isWorkspaceLocked)
                REQUEST_PICK_LIVE_FOLDER -> addLiveFolder(data)
                REQUEST_CREATE_LIVE_FOLDER -> completeAddLiveFolder(data, mAddItemCellInfo!!, !isWorkspaceLocked)
                REQUEST_PICK_APPWIDGET -> addAppWidget(data)
                REQUEST_CREATE_APPWIDGET -> completeAddAppWidget(data, mAddItemCellInfo!!, !isWorkspaceLocked)
            }
        } else if ((requestCode == REQUEST_PICK_APPWIDGET || requestCode == REQUEST_CREATE_APPWIDGET)
            && resultCode == RESULT_CANCELED
        ) {
            // Clean up the appWidgetId if we canceled
            val appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (appWidgetId != -1) {
                appWidgetHost!!.deleteAppWidgetId(appWidgetId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (restartOnScreenNumberChange()) return
        if (mRestoring) {
            startLoaders()
        }

        // If this was a new intent (i.e., the mIsNewIntent flag got set to true by
        // onNewIntent), then close the search dialog if needed, because it probably
        // came from the user pressing 'home' (rather than, for example, pressing 'back').
        if (mIsNewIntent) {
            // Post to a handler so that this happens after the search dialog tries to open
            // itself again.
            workspace.post {
                val searchmanager = this@Launcher
                    .getSystemService(SEARCH_SERVICE) as SearchManager
                try {
                    searchmanager.stopSearch()
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "error stopping search", e)
                }
            }
        }
        mIsNewIntent = false
    }

    override fun onPause() {
        super.onPause()
        closeDrawer(false)
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        // Flag any binder to stop early before switching
        if (mBinder != null) {
            mBinder!!.mTerminate = true
        }
        return null
    }

    private fun acceptFilter(): Boolean {
        val inputManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return !inputManager.isFullscreenMode
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // this tells the framework to start tracking for
            // a long press and eventual key up. it will only
            // do so if this is the first down (not a repeat).
            event.startTracking()
            return true
        }
        val handled = super.onKeyDown(keyCode, event)
        if (!handled && acceptFilter() && keyCode != KeyEvent.KEYCODE_ENTER) {
            val gotKey = TextKeyListener.getInstance().onKeyDown(workspace, mDefaultKeySsb,
                keyCode, event)
            if (gotKey && mDefaultKeySsb != null && mDefaultKeySsb!!.isNotEmpty()) {
                // something usable has been typed - start a search
                // the typed text will be retrieved and cleared by
                // showSearchDialog()
                // If there are multiple keystrokes before the search dialog takes focus,
                // onSearchRequested() will be called for every keystroke,
                // but it is idempotent, so it's fine.
                return onSearchRequested()
            }
        }
        return handled
    }


    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.isTracking && !event.isCanceled) {
            // if the call key is being released, AND we are tracking
            // it from an initial key down, AND it is not canceled,
            // then handle it.
            openOptionsMenu()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private val typedText: String
        get() = mDefaultKeySsb.toString()

    private fun clearTypedText() {
        mDefaultKeySsb!!.clear()
        mDefaultKeySsb!!.clearSpans()
        Selection.setSelection(mDefaultKeySsb, 0)
    }

    /**
     * Restores the previous state, if it exists.
     *
     * @param savedState
     * The previous state.
     */
    private fun restoreState(savedState: Bundle) {
        val currentScreen = savedState.getInt(RUNTIME_STATE_CURRENT_SCREEN, -1)
        if (currentScreen > -1) {
            workspace.currentScreen = (currentScreen)
        }
        val addScreen = savedState.getInt(RUNTIME_STATE_PENDING_ADD_SCREEN, -1)
        if (addScreen > -1) {
            mAddItemCellInfo = CellLayout.CellInfo()
            val addItemCellInfo = mAddItemCellInfo!!
            addItemCellInfo.valid = true
            addItemCellInfo.screen = addScreen
            addItemCellInfo.cellX = savedState.getInt(RUNTIME_STATE_PENDING_ADD_CELL_X)
            addItemCellInfo.cellY = savedState.getInt(RUNTIME_STATE_PENDING_ADD_CELL_Y)
            addItemCellInfo.spanX = savedState.getInt(RUNTIME_STATE_PENDING_ADD_SPAN_X)
            addItemCellInfo.spanY = savedState.getInt(RUNTIME_STATE_PENDING_ADD_SPAN_Y)
            addItemCellInfo.findVacantCellsFromOccupied(savedState
                .getBooleanArray(RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS)!!, savedState
                .getInt(RUNTIME_STATE_PENDING_ADD_COUNT_X), savedState
                .getInt(RUNTIME_STATE_PENDING_ADD_COUNT_Y))
            mRestoring = true
        }
        val renameFolder = savedState.getBoolean(RUNTIME_STATE_PENDING_FOLDER_RENAME, false)
        if (renameFolder) {
            val id = savedState.getLong(RUNTIME_STATE_PENDING_FOLDER_RENAME_ID)
            mFolderInfo = model.getFolderById(this, id)
            mRestoring = true
        }
    }

    private var mDeleteZone: DeleteZone? = null

    /**
     * Finds all the views we need and configure them properly.
     */
    private fun setupViews() {
        mDragLayer = findViewById<View>(R.id.drag_layer) as DragLayer
        val dragLayer = mDragLayer
        workspace = dragLayer!!.findViewById<View>(R.id.workspace) as Workspace
        val workspace = workspace
        syncScreenNumber()
        mDrawer = dragLayer.findViewById<View>(R.id.drawer) as SlidingDrawer
        val drawer = mDrawer
        mAllAppsGrid = drawer!!.content as AllAppsGridView
        val grid = mAllAppsGrid
        mDeleteZone = dragLayer.findViewById<View>(R.id.delete_zone) as DeleteZone
        mHandleView = drawer.findViewById<View>(R.id.all_apps) as HandleView
        mHandleView!!.setLauncher(this)
        mHandleIcon = mHandleView!!.drawable as TransitionDrawable
        mHandleIcon!!.isCrossFadeEnabled = true
        drawer.lock()
        val drawerManager = DrawerManager()
        drawer.setOnDrawerOpenListener(drawerManager)
        drawer.setOnDrawerCloseListener(drawerManager)
        drawer.setOnDrawerScrollListener(drawerManager)
        grid!!.isTextFilterEnabled = false
        grid.setDragController(dragLayer)
        grid.setLauncher(this)
        workspace.setOnLongClickListener(this)
        workspace.setDragger(dragLayer)
        workspace.setLauncher(this)
        mDeleteZone!!.setLauncher(this)
        mDeleteZone!!.setDragController(dragLayer)
        mDeleteZone!!.setHandle(mHandleView)
        dragLayer.setIgnoredDropTarget(grid)
        dragLayer.setDragScoller(workspace)
        dragLayer.setDragListener(mDeleteZone)
    }

    /**
     * Creates a view representing a shortcut.
     *
     * @param info
     * The data structure describing the shortcut.
     *
     * @return A View inflated from R.layout.application.
     */
    private fun createShortcut(info: ApplicationInfo?): View {
        return createShortcut(R.layout.application, workspace.getChildAt(workspace
            .currentScreen) as ViewGroup, info)
    }

    /**
     * Creates a view representing a shortcut inflated from the specified resource.
     *
     * @param layoutResId
     * The id of the XML layout used to create the shortcut.
     * @param parent
     * The group the shortcut belongs to.
     * @param info
     * The data structure describing the shortcut.
     *
     * @return A View inflated from layoutResId.
     */
    fun createShortcut(layoutResId: Int, parent: ViewGroup?, info: ApplicationInfo?): View {
        val favorite = mInflater!!.inflate(layoutResId, parent, false) as TextView
        if (!info!!.filtered) {
            info.icon = Utilities.createIconThumbnail(info.icon, this)
            info.filtered = true
        }
        favorite.setCompoundDrawablesWithIntrinsicBounds(null, info.icon, null, null)
        favorite.text = info.title
        favorite.tag = info
        favorite.setOnClickListener(this)
        return favorite
    }

    /**
     * Add an application shortcut to the workspace.
     *
     * @param data
     * The intent describing the application.
     * @param cellInfo
     * The position on screen where to create the shortcut.
     */
    private fun completeAddApplication(
        context: Context, data: Intent, cellInfo: CellLayout.CellInfo,
        insertAtFirst: Boolean,
    ) {
        cellInfo.screen = workspace.currentScreen
        if (!findSingleSlot(cellInfo)) return
        val info = infoFromApplicationIntent(context, data)
        if (info != null) {
            workspace.addApplicationShortcut(info, cellInfo, insertAtFirst)
        }
    }

    /**
     * Add a shortcut to the workspace.
     *
     * @param data
     * The intent describing the shortcut.
     * @param cellInfo
     * The position on screen where to create the shortcut.
     * @param insertAtFirst
     */
    private fun completeAddShortcut(
        data: Intent, cellInfo: CellLayout.CellInfo,
        insertAtFirst: Boolean,
    ) {
        cellInfo.screen = workspace.currentScreen
        if (!findSingleSlot(cellInfo)) return
        val info = addShortcut(this, data, cellInfo, false)
        if (!mRestoring) {
            model.addDesktopItem(info)
            val view = createShortcut(info)
            workspace.addInCurrentScreen(view, cellInfo.cellX, cellInfo.cellY, 1, 1, insertAtFirst)
        } else if (model.isDesktopLoaded) {
            model.addDesktopItem(info)
        }
    }

    /**
     * Add a widget to the workspace.
     *
     * @param data
     * The intent describing the appWidgetId.
     * @param cellInfo
     * The position on screen where to create the widget.
     */
    private fun completeAddAppWidget(
        data: Intent, cellInfo: CellLayout.CellInfo,
        insertAtFirst: Boolean,
    ) {
        val extras = data.extras
        val appWidgetId = extras!!.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (LOGD) Log.d(LOG_TAG, "dumping extras content=$extras")
        val appWidgetInfo = mAppWidgetManager!!.getAppWidgetInfo(appWidgetId)

        // Calculate the grid spans needed to fit this widget
        val layout = workspace.getChildAt(cellInfo.screen) as CellLayout
        val spans = layout.rectToCell(appWidgetInfo!!.minWidth, appWidgetInfo.minHeight)

        // Try finding open space on Launcher screen
        val xy = mCellCoordinates
        if (!findSlot(cellInfo, xy, spans[0], spans[1])) {
            if (appWidgetId != -1) appWidgetHost!!.deleteAppWidgetId(appWidgetId)
            return
        }

        // Build Launcher-specific widget info and save to database
        val launcherInfo = LauncherAppWidgetInfo(appWidgetId)
        launcherInfo.spanX = spans[0]
        launcherInfo.spanY = spans[1]
        LauncherModel.addItemToDatabase(this, launcherInfo,
            Favorites.CONTAINER_DESKTOP.toLong(), workspace.currentScreen, xy[0],
            xy[1], false)
        if (!mRestoring) {
            model.addDesktopAppWidget(launcherInfo)

            // Perform actual inflation because we're live
            launcherInfo.hostView = appWidgetHost!!.createView(this, appWidgetId, appWidgetInfo)
            launcherInfo.hostView?.setAppWidget(appWidgetId, appWidgetInfo)
            launcherInfo.hostView?.tag = launcherInfo
            workspace.addInCurrentScreen(launcherInfo.hostView, xy[0], xy[1], launcherInfo.spanX,
                launcherInfo.spanY, insertAtFirst)
        } else if (model.isDesktopLoaded) {
            model.addDesktopAppWidget(launcherInfo)
        }
        // finish load a widget, send it an intent
        appwidgetReadyBroadcast(appWidgetId, appWidgetInfo.provider)
    }

    fun closeSystemDialogs() {
        window.closeAllPanels()
        try {
            dismissDialog(DIALOG_CREATE_SHORTCUT)
            // Unlock the workspace if the dialog was showing
            workspace.unlock()
        } catch (e: Exception) {
            // An exception is thrown if the dialog is not visible, which is fine
        }
        try {
            dismissDialog(DIALOG_RENAME_FOLDER)
            // Unlock the workspace if the dialog was showing
            workspace.unlock()
        } catch (e: Exception) {
            // An exception is thrown if the dialog is not visible, which is fine
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Close the menu
        if (Intent.ACTION_MAIN == intent.action) {
            closeSystemDialogs()

            // Set this flag so that onResume knows to close the search dialog if it's open,
            // because this was a new intent (thus a press of 'home' or some such) rather than
            // for example onResume being called when the user pressed the 'back' button.
            mIsNewIntent = true
            if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) {
                closeDrawer()
                val v = window.peekDecorView()
                if (v != null && v.windowToken != null) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            } else {
                closeDrawer(false)
            }
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // NOTE: Do NOT do this. Ever. This is a terrible and horrifying hack.
        //
        // Home loads the content of the workspace on a background thread. This means that
        // a previously focused view will be, after orientation change, added to the view
        // hierarchy at an undeterminate time in the future. If we were to invoke
        // super.onRestoreInstanceState() here, the focus restoration would fail because the
        // view to focus does not exist yet.
        //
        // However, not invoking super.onRestoreInstanceState() is equally bad. In such a case,
        // panels would not be restored properly. For instance, if the menu is open then the
        // user changes the orientation, the menu would not be opened in the new orientation.
        //
        // To solve both issues Home messes up with the internal state of the bundle to remove
        // the properties it does not want to see restored at this moment. After invoking
        // super.onRestoreInstanceState(), it removes the panels state.
        //
        // Later, when the workspace is done loading, Home calls super.onRestoreInstanceState()
        // again to restore focus and other view properties. It will not, however, restore
        // the panels since at this point the panels' state has been removed from the bundle.
        //
        // This is a bad example, do not do this.
        //
        // If you are curious on how this code was put together, take a look at the following
        // in Android's source code:
        // - Activity.onRestoreInstanceState()
        // - PhoneWindow.restoreHierarchyState()
        // - PhoneWindow.DecorView.onAttachedToWindow()
        //
        // The source code of these various methods shows what states should be kept to
        // achieve what we want here.
        val windowState = savedInstanceState.getBundle("android:viewHierarchyState")
        var savedStates: SparseArray<Parcelable>? = null
        var focusedViewId = View.NO_ID
        if (windowState != null) {
            savedStates = windowState.getSparseParcelableArray("android:views")
            windowState.remove("android:views")
            focusedViewId = windowState.getInt("android:focusedViewId", View.NO_ID)
            windowState.remove("android:focusedViewId")
        }
        super.onRestoreInstanceState(savedInstanceState)
        if (windowState != null) {
            windowState.putSparseParcelableArray("android:views", savedStates)
            windowState.putInt("android:focusedViewId", focusedViewId)
            windowState.remove("android:Panels")
        }
        mSavedInstanceState = savedInstanceState
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(RUNTIME_STATE_CURRENT_SCREEN, workspace.currentScreen)
        val folders = workspace.openFolders
        if (folders.size > 0) {
            val count = folders.size
            val ids = LongArray(count)
            for (i in 0 until count) {
                val info = folders[i].info
                ids[i] = info!!.id
            }
            outState.putLongArray(RUNTIME_STATE_USER_FOLDERS, ids)
        }
        val isConfigurationChange = changingConfigurations != 0

        // When the drawer is opened and we are saving the state because of a
        // configuration change
        if (mDrawer!!.isOpened && isConfigurationChange) {
            outState.putBoolean(RUNTIME_STATE_ALL_APPS_FOLDER, true)
        }
        if (mAddItemCellInfo != null && mAddItemCellInfo!!.valid && mWaitingForResult) {
            val addItemCellInfo: CellLayout.CellInfo = mAddItemCellInfo!!
            val layout = workspace.getChildAt(addItemCellInfo.screen) as CellLayout
            outState.putInt(RUNTIME_STATE_PENDING_ADD_SCREEN, addItemCellInfo.screen)
            outState.putInt(RUNTIME_STATE_PENDING_ADD_CELL_X, addItemCellInfo.cellX)
            outState.putInt(RUNTIME_STATE_PENDING_ADD_CELL_Y, addItemCellInfo.cellY)
            outState.putInt(RUNTIME_STATE_PENDING_ADD_SPAN_X, addItemCellInfo.spanX)
            outState.putInt(RUNTIME_STATE_PENDING_ADD_SPAN_Y, addItemCellInfo.spanY)
            outState.putInt(RUNTIME_STATE_PENDING_ADD_COUNT_X, layout.countX)
            outState.putInt(RUNTIME_STATE_PENDING_ADD_COUNT_Y, layout.countY)
            outState.putBooleanArray(RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS, layout
                .occupiedCells)
        }
        if (mFolderInfo != null && mWaitingForResult) {
            outState.putBoolean(RUNTIME_STATE_PENDING_FOLDER_RENAME, true)
            outState.putLong(RUNTIME_STATE_PENDING_FOLDER_RENAME_ID, mFolderInfo!!.id)
        }
    }

    public override fun onDestroy() {
        mDestroyed = true
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
        try {
            appWidgetHost!!.stopListening()
        } catch (ex: NullPointerException) {
            Log.w(LOG_TAG, "problem while stopping AppWidgetHost during Launcher destruction", ex)
        }
        TextKeyListener.getInstance().release()
        mAllAppsGrid!!.clearTextFilter()
        mAllAppsGrid!!.adapter = null
        model.unbind()
        model.abortLoaders()
        contentResolver.unregisterContentObserver(mObserver)
        contentResolver.unregisterContentObserver(mWidgetObserver)
        unregisterReceiver(mApplicationsReceiver)
        unregisterReceiver(mCloseSystemDialogsReceiver)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        if (requestCode >= 0) mWaitingForResult = true
        super.startActivityForResult(intent, requestCode)
    }

    override fun startSearch(
        initialQuery: String?, selectInitialQuery: Boolean, appSearchData: Bundle?,
        globalSearch: Boolean,
    ) {
        closeDrawer(false)

        // Slide the search widget to the top, if it's on the current screen,
        // otherwise show the search dialog immediately.
        val searchWidget = workspace.findSearchWidgetOnCurrentScreen()
        if (searchWidget == null) {
            showSearchDialog(initialQuery, selectInitialQuery, appSearchData, globalSearch)
        } else {
            searchWidget.startSearch(initialQuery, selectInitialQuery, appSearchData, globalSearch)
            // show the currently typed text in the search widget while sliding
            searchWidget.setQuery(typedText)
        }
    }

    /**
     * Show the search dialog immediately, without changing the search widget.
     *
     * @see Activity.startSearch
     */
    fun showSearchDialog(
        initialQuery: String?, selectInitialQuery: Boolean, appSearchData: Bundle?,
        globalSearch: Boolean,
    ) {
        var initialQuery = initialQuery
        var appSearchData = appSearchData
        if (initialQuery == null) {
            // Use any text typed in the launcher as the initial query
            initialQuery = typedText
            clearTypedText()
        }
        if (appSearchData == null) {
            appSearchData = Bundle()
            appSearchData.putString("source", "launcher-search")
        }
        val searchManager = getSystemService(SEARCH_SERVICE) as SearchManager
        val searchWidget = workspace.findSearchWidgetOnCurrentScreen()
        if (searchWidget != null) {
            // This gets called when the user leaves the search dialog to go back to
            // the Launcher.
            searchManager.setOnCancelListener {
                searchManager.setOnCancelListener(null)
                stopSearch()
            }
        }
        searchManager.startSearch(initialQuery, selectInitialQuery, componentName,
            appSearchData, globalSearch)
    }

    /**
     * Cancel search dialog if it is open.
     */
    private fun stopSearch() {
        // Close search dialog
        val searchManager = getSystemService(SEARCH_SERVICE) as SearchManager
        searchManager.stopSearch()
        // Restore search widget to its normal position
        val searchWidget = workspace.findSearchWidgetOnCurrentScreen()
        searchWidget?.stopSearch(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (isWorkspaceLocked && mSavedInstanceState == null) return false
        super.onCreateOptionsMenu(menu)
        menu.add(MENU_GROUP_ADD,
            MENU_ADD,
            0,
            R.string.menu_add).setIcon(
            android.R.drawable.ic_menu_add).alphabeticShortcut = 'A'
        menu.add(0, MENU_WALLPAPER_SETTINGS, 0, R.string.menu_wallpaper).setIcon(
            android.R.drawable.ic_menu_gallery).alphabeticShortcut = 'W'
        menu.add(0, MENU_SEARCH, 0, R.string.menu_search).setIcon(
            android.R.drawable.ic_search_category_default).alphabeticShortcut = SearchManager.MENU_KEY
        menu.add(0, MENU_NOTIFICATIONS, 0, R.string.menu_notifications).setIcon(
            R.drawable.ic_menu_notifications).alphabeticShortcut = 'N'
        val settings = Intent(Settings.ACTION_SETTINGS)
        settings.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        menu.add(0, MENU_SETTINGS, 0, R.string.menu_settings).setIcon(
            android.R.drawable.ic_menu_preferences).setAlphabeticShortcut('P').intent = settings
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        // We can't trust the view state here since views we may not be done binding.
        // Get the vacancy state from the model instead.
        mMenuAddInfo = workspace.findAllVacantCellsFromModel()
        menu.setGroupEnabled(MENU_GROUP_ADD, mMenuAddInfo != null && mMenuAddInfo!!.valid)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD -> {
                addItems()
                return true
            }
            MENU_WALLPAPER_SETTINGS -> {
                startWallpaper()
                return true
            }
            MENU_SEARCH -> {
                onSearchRequested()
                return true
            }
            MENU_NOTIFICATIONS -> {
                showNotifications()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Indicates that we want global search for this activity by setting the globalSearch argument
     * for [.startSearch] to true.
     */
    override fun onSearchRequested(): Boolean {
        startSearch(null, false, null, true)
        return true
    }

    private fun addItems() {
        showAddDialog(mMenuAddInfo)
    }

    private fun removeShortcutsForPackage(packageName: String?) {
        if (packageName != null && packageName.isNotEmpty()) {
            workspace.removeShortcutsForPackage(packageName)
        }
    }

    private fun updateShortcutsForPackage(packageName: String?) {
        if (packageName != null && packageName.isNotEmpty()) {
            workspace.updateShortcutsForPackage(packageName)
        }
    }

    private fun addAppWidget(data: Intent) {
        val appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val customWidget = data.getStringExtra(EXTRA_CUSTOM_WIDGET)
        if (SEARCH_WIDGET == customWidget) {
            // We don't need this any more, since this isn't a real app widget.
            appWidgetHost!!.deleteAppWidgetId(appWidgetId)
            // add the search widget
            addSearch()
        } else {
            val appWidget = mAppWidgetManager!!.getAppWidgetInfo(appWidgetId)
            try {
                val metadata = packageManager.getReceiverInfo(appWidget.provider,
                    PackageManager.GET_META_DATA).metaData
                if (metadata != null) {
                    if (metadata.containsKey(Requirements.APIVersion)) {
                        val requiredApiVersion = metadata.getInt(Requirements.APIVersion)
                        if (requiredApiVersion > LauncherMetadata.CurrentAPIVersion) {
                            onActivityResult(REQUEST_CREATE_APPWIDGET, RESULT_CANCELED, data)
                            // Show a nice toast here to tell the user why the widget is rejected.
                            return
                        }
                        // If there are Settings for scrollable or animations test them here too!
                    }
                }
            } catch (expt: PackageManager.NameNotFoundException) {
                // No Metadata available... then it is all OK... 
            }
            if (appWidget.configure != null) {
                // Launch over to configure widget, if needed
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                intent.component = appWidget.configure
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                startActivityForResult(intent, REQUEST_CREATE_APPWIDGET)
            } else {
                // Otherwise just add it
                onActivityResult(REQUEST_CREATE_APPWIDGET, RESULT_OK, data)
            }
        }
    }

    private fun addSearch() {
        val info: Widget = Widget.makeSearch()
        val cellInfo = mAddItemCellInfo
        val xy = mCellCoordinates
        val spanX = info.spanX
        val spanY = info.spanY
        if (!findSlot(cellInfo!!, xy, spanX, spanY)) return
        model.addDesktopItem(info)
        LauncherModel.addItemToDatabase(this, info, Favorites.CONTAINER_DESKTOP.toLong(),
            workspace.currentScreen, xy[0], xy[1], false)
        val view = mInflater!!.inflate(info.layoutResource, null)
        view.tag = info
        val search = view.findViewById<View>(R.id.widget_search) as Search
        search.setLauncher(this)
        workspace.addInCurrentScreen(view, xy[0], xy[1], info.spanX, spanY)
    }

    private fun processShortcut(intent: Intent, requestCodeApplication: Int, requestCodeShortcut: Int) {
        // Handle case where user selected "Applications"
        val applicationName = resources.getString(R.string.group_applications)
        val shortcutName = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
        if (applicationName == shortcutName) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val pickIntent = Intent(Intent.ACTION_PICK_ACTIVITY)
            pickIntent.putExtra(Intent.EXTRA_INTENT, mainIntent)
            startActivityForResult(pickIntent, requestCodeApplication)
        } else {
            startActivityForResult(intent, requestCodeShortcut)
        }
    }

    private fun addLiveFolder(intent: Intent) {
        // Handle case where user selected "Folder"
        val folderName = resources.getString(R.string.group_folder)
        val shortcutName = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
        if (folderName == shortcutName) {
            addFolder(!isWorkspaceLocked)
        } else {
            startActivityForResult(intent, REQUEST_CREATE_LIVE_FOLDER)
        }
    }

    private fun addFolder(insertAtFirst: Boolean) {
        val folderInfo = UserFolderInfo()
        folderInfo.title = getText(R.string.folder_name)
        val cellInfo = mAddItemCellInfo
        cellInfo!!.screen = workspace.currentScreen
        if (!findSingleSlot(cellInfo)) return

        // Update the model
        LauncherModel.addItemToDatabase(this, folderInfo,
            Favorites.CONTAINER_DESKTOP.toLong(), workspace.currentScreen,
            cellInfo.cellX, cellInfo.cellY, false)
        model.addDesktopItem(folderInfo)
        model.addFolder(folderInfo)

        // Create the view
        val newFolder: FolderIcon = FolderIcon.fromXml(R.layout.folder_icon, this,
            workspace.getChildAt(workspace.currentScreen) as ViewGroup, folderInfo)
        workspace.addInCurrentScreen(newFolder, cellInfo.cellX, cellInfo.cellY, 1, 1,
            insertAtFirst)
    }

    private fun completeAddLiveFolder(
        data: Intent, cellInfo: CellLayout.CellInfo,
        insertAtFirst: Boolean,
    ) {
        cellInfo.screen = workspace.currentScreen
        if (!findSingleSlot(cellInfo)) return
        val info = addLiveFolder(this, data, cellInfo, false)
        if (!mRestoring) {
            model.addDesktopItem(info)
            val view: View = LiveFolderIcon.fromXml(R.layout.live_folder_icon, this,
                workspace.getChildAt(workspace.currentScreen) as ViewGroup, info)
            workspace
                .addInCurrentScreen(view, cellInfo.cellX, cellInfo.cellY, 1, 1, insertAtFirst)
        } else if (model.isDesktopLoaded) {
            model.addDesktopItem(info)
        }
    }

    private fun findSingleSlot(cellInfo: CellLayout.CellInfo): Boolean {
        val xy = IntArray(2)
        if (findSlot(cellInfo, xy, 1, 1)) {
            cellInfo.cellX = xy[0]
            cellInfo.cellY = xy[1]
            return true
        }
        return false
    }

    private fun findSlot(cellInfo: CellLayout.CellInfo, xy: IntArray, spanX: Int, spanY: Int): Boolean {
        var cellInfo = cellInfo
        if (!cellInfo.findCellForSpan(xy, spanX, spanY)) {
            val occupied = if (mSavedState != null) mSavedState!!
                .getBooleanArray(RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS) else null
            cellInfo = workspace.findAllVacantCells(occupied)
            if (!cellInfo.findCellForSpan(xy, spanX, spanY)) {
                Toast.makeText(this, getString(R.string.out_of_space), Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    @SuppressLint("WrongConstant")
    private fun showNotifications() {
        try {
            val statusBarService = getSystemService("statusbar")
            val methodName = if (Build.VERSION.SDK_INT >= 17) "expandNotificationsPanel" else "expand"
            val statusBarManager: Class<*> = Class.forName("android.app.StatusBarManager")
            val method: Method = statusBarManager.getMethod(methodName)
            method.invoke(statusBarService)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startWallpaper() {
        val pickWallpaper = Intent(Intent.ACTION_SET_WALLPAPER)
        val chooser = Intent.createChooser(pickWallpaper, getText(R.string.chooser_wallpaper))
        val wm = getSystemService(WALLPAPER_SERVICE) as WallpaperManager

        // TODO WallpaperInfo wi = wm.getWallpaperInfo();
        try {
            val getWallpaperInfo = wm.javaClass.getMethod("getWallpaperInfo")
            val wi = getWallpaperInfo.invoke(wm)
            val getPackageName = wi!!.javaClass.getMethod("getPackageName")
            val pname = getPackageName.invoke(wi) as String
            val getSettingsActivity = wi.javaClass.getMethod("getSettingsActivity")
            val activity = getSettingsActivity.invoke(wi) as String
            val li = LabeledIntent(packageName,
                R.string.configure_wallpaper, 0)
            li.setClassName(pname, activity)
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Intent>(li))
        } catch (e: Exception) {
        }
        startActivity(chooser)
    }

    /**
     * Registers various intent receivers. The current implementation registers only a wallpaper
     * intent receiver to let other applications change the wallpaper.
     */
    private fun registerIntentReceivers() {
        var filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        filter.addDataScheme("package")
        registerReceiver(mApplicationsReceiver, filter)
        filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        registerReceiver(mCloseSystemDialogsReceiver, filter)
    }

    /**
     * Registers various content observers. The current implementation registers only a favorites
     * observer to keep track of the favorites applications.
     */
    private fun registerContentObservers() {
        val resolver = contentResolver
        resolver.registerContentObserver(Favorites.CONTENT_URI, true, mObserver)
        resolver.registerContentObserver(LauncherProvider.CONTENT_APPWIDGET_RESET_URI, true,
            mWidgetObserver)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> return true
                KeyEvent.KEYCODE_HOME -> return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (!event.isCanceled) {
                        workspace.dispatchKeyEvent(event)
                        if (mDrawer!!.isOpened) {
                            closeDrawer()
                        } else closeFolder()
                    }
                    return true
                }
                KeyEvent.KEYCODE_HOME -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun closeDrawer(animated: Boolean = true) {
        if (mDrawer!!.isOpened) {
            if (animated) {
                mDrawer!!.animateClose()
            } else {
                mDrawer!!.close()
            }
            if (mDrawer!!.hasFocus()) {
                workspace.getChildAt(workspace.currentScreen).requestFocus()
            }
        }
    }

    private fun closeFolder(): Boolean {
        val folder = workspace.openFolder
        if (folder != null) {
            closeFolder(folder)
            return true
        }
        return false
    }

    fun closeFolder(folder: Folder) {
        folder.info?.opened = false
        val parent = folder.parent as ViewGroup
        parent.removeView(folder)
        folder.onClose()
    }

    /**
     * When the notification that favorites have changed is received, requests a favorites list
     * refresh.
     */
    private fun onFavoritesChanged() {
        isWorkspaceLocked = true
        mDrawer!!.lock()
        model.loadUserItems(false, this, localeChanged = false, loadApplications = false)
    }

    /**
     * Re-listen when widgets are reset.
     */
    private fun onAppWidgetReset() {
        appWidgetHost!!.startListening()
    }

    fun onDesktopItemsLoaded(
        shortcuts: ArrayList<ItemInfo>?,
        appWidgets: ArrayList<LauncherAppWidgetInfo>?,
    ) {
        if (mDestroyed) {
            if (LauncherModel.DEBUG_LOADERS) {
                Log.d(LauncherModel.LOG_TAG, "  ------> destroyed, ignoring desktop items")
            }
            return
        }
        bindDesktopItems(shortcuts, appWidgets)
    }

    /**
     * Refreshes the shortcuts shown on the workspace.
     */
    private fun bindDesktopItems(
        shortcuts: ArrayList<ItemInfo>?,
        appWidgets: ArrayList<LauncherAppWidgetInfo>?,
    ) {
        val drawerAdapter = model.applicationsAdapter
        if (shortcuts == null || appWidgets == null || drawerAdapter == null) {
            if (LauncherModel.DEBUG_LOADERS) Log.d(LauncherModel.LOG_TAG, "  ------> a source is null")
            return
        }
        val workspace = workspace
        val count = workspace.childCount
        for (i in 0 until count) {
            (workspace.getChildAt(i) as ViewGroup).removeAllViewsInLayout()
        }
        if (DEBUG_USER_INTERFACE) {
            val finishButton = Button(this)
            finishButton.text = "Finish"
            workspace.addInScreen(finishButton, 1, 0, 0, 1, 1)
            finishButton.setOnClickListener { finish() }
        }

        // Flag any old binder to terminate early
        if (mBinder != null) {
            mBinder!!.mTerminate = true
        }
        mBinder = DesktopBinder(this, shortcuts, appWidgets, drawerAdapter)
        mBinder!!.startBindingItems()
    }

    private fun bindItems(
        binder: DesktopBinder, shortcuts: ArrayList<ItemInfo>, start: Int,
        count: Int,
    ) {
        val workspace = workspace
        val desktopLocked = isWorkspaceLocked
        val end = min(start + DesktopBinder.ITEMS_COUNT, count)
        var i = start
        val screenNumber = workspace.childCount
        while (i < end) {
            val item = shortcuts[i]
            if (item.screen >= screenNumber) {
                i++
                continue
            }
            when (item.itemType) {
                BaseLauncherColumns.ITEM_TYPE_APPLICATION, BaseLauncherColumns.ITEM_TYPE_SHORTCUT -> {
                    val shortcut = createShortcut(item as ApplicationInfo?)
                    workspace.addInScreen(shortcut, item.screen, item.cellX, item.cellY, 1, 1,
                        !desktopLocked)
                }
                Favorites.ITEM_TYPE_USER_FOLDER -> {
                    val newFolder: FolderIcon = FolderIcon.fromXml(R.layout.folder_icon, this,
                        workspace.getChildAt(workspace.currentScreen) as ViewGroup,
                        item as UserFolderInfo?)
                    workspace.addInScreen(newFolder, item.screen, item.cellX, item.cellY, 1, 1,
                        !desktopLocked)
                }
                Favorites.ITEM_TYPE_LIVE_FOLDER -> {
                    val newLiveFolder: FolderIcon = LiveFolderIcon.fromXml(R.layout.live_folder_icon,
                        this, workspace.getChildAt(workspace.currentScreen) as ViewGroup,
                        item as LiveFolderInfo?)
                    workspace.addInScreen(newLiveFolder, item.screen, item.cellX, item.cellY, 1, 1,
                        !desktopLocked)
                }
                Favorites.ITEM_TYPE_WIDGET_SEARCH -> {
                    val screen = workspace.currentScreen
                    val view = mInflater!!.inflate(R.layout.widget_search, workspace
                        .getChildAt(screen) as ViewGroup, false)
                    val search = view.findViewById<View>(R.id.widget_search) as Search
                    search.setLauncher(this)
                    val widget = item as Widget?
                    view.tag = widget
                    workspace.addWidget(view, widget, !desktopLocked)
                }
            }
            i++
        }
        workspace.requestLayout()
        if (end >= count) {
            finishBindDesktopItems()
            binder.startBindingDrawer()
        } else {
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_ITEMS, i, count).sendToTarget()
        }
    }

    private fun finishBindDesktopItems() {
        if (mSavedState != null) {
            if (!workspace.hasFocus()) {
                workspace.getChildAt(workspace.currentScreen).requestFocus()
            }
            val userFolders = mSavedState!!.getLongArray(RUNTIME_STATE_USER_FOLDERS)
            if (userFolders != null) {
                for (folderId in userFolders) {
                    val info = model.findFolderById(folderId)
                    info?.let { openFolder(it) }
                }
                val openFolder = workspace.openFolder
                openFolder?.requestFocus()
            }
            val allApps = mSavedState!!.getBoolean(RUNTIME_STATE_ALL_APPS_FOLDER, false)
            if (allApps) {
                mDrawer!!.open()
            }
            mSavedState = null
        }
        if (mSavedInstanceState != null) {
            super.onRestoreInstanceState(mSavedInstanceState!!)
            mSavedInstanceState = null
        }
        if (mDrawer!!.isOpened && !mDrawer!!.hasFocus()) {
            mDrawer!!.requestFocus()
        }
        isWorkspaceLocked = false
        mDrawer!!.unlock()
    }

    private fun bindDrawer(binder: DesktopBinder, drawerAdapter: ApplicationsAdapter) {
        mAllAppsGrid!!.adapter = drawerAdapter
        binder.startBindingAppWidgetsWhenIdle()
    }

    private fun bindAppWidgets(
        binder: DesktopBinder,
        appWidgets: LinkedList<LauncherAppWidgetInfo>,
    ) {
        val workspace = workspace
        val desktopLocked = isWorkspaceLocked
        if (!appWidgets.isEmpty()) {
            val item = appWidgets.removeFirst()
            val appWidgetId = item.appWidgetId
            val appWidgetInfo = mAppWidgetManager?.getAppWidgetInfo(appWidgetId)
            item.hostView = appWidgetHost!!.createView(this, appWidgetId, appWidgetInfo)
            if (LOGD) {
                Log.d(LOG_TAG, String.format("about to setAppWidget for id=%d, info=%s", appWidgetId,
                    appWidgetInfo))
            }
            item.hostView?.setAppWidget(appWidgetId, appWidgetInfo)
            item.hostView?.tag = item
            workspace.addInScreen(item.hostView, item.screen, item.cellX, item.cellY, item.spanX,
                item.spanY, !desktopLocked)
            workspace.requestLayout()

            // finish load a widget, send it an intent
            if (appWidgetInfo != null) appwidgetReadyBroadcast(appWidgetId, appWidgetInfo.provider)
        }
        if (appWidgets.isEmpty()) {
            if (PROFILE_ROTATE) {
                Debug.stopMethodTracing()
            }
        } else {
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_APPWIDGETS).sendToTarget()
        }
    }

    private fun appwidgetReadyBroadcast(appWidgetId: Int, cname: ComponentName) {
        val ready = Intent(LauncherIntent.Action.ACTION_READY).putExtra(
            Extra.EXTRA_APPWIDGET_ID, appWidgetId).putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId).putExtra(
            Extra.EXTRA_API_VERSION, LauncherMetadata.CurrentAPIVersion).setComponent(cname)
        sendBroadcast(ready)
    }

    /**
     * Launches the intent referred by the clicked shortcut.
     *
     * @param v
     * The view representing the clicked shortcut.
     */
    override fun onClick(v: View) {
        val tag = v.tag
        if (tag is ApplicationInfo) {
            // Open shortcut
            val intent = tag.intent
            // set bound
            if (v != null) {
                val targetRect = Rect()
                v.getGlobalVisibleRect(targetRect)
                intent!!.sourceBounds = targetRect
            }
            startActivitySafely(intent)
        } else if (tag is FolderInfo) {
            handleFolderClick(tag)
        }
    }

    fun startActivitySafely(intent: Intent?) {
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
            Log.e(LOG_TAG, "Launcher does not have the permission to launch " + intent
                    + ". Make sure to create a MAIN intent-filter for the corresponding activity "
                    + "or use the exported attribute for this activity.", e)
        }
    }

    private fun handleFolderClick(folderInfo: FolderInfo) {
        if (!folderInfo.opened) {
            // Close any open folder
            closeFolder()
            // Open the requested folder
            openFolder(folderInfo)
        } else {
            // Find the open folder...
            val openFolder = workspace.getFolderForTag(folderInfo)
            val folderScreen: Int
            if (openFolder != null) {
                folderScreen = workspace.getScreenForView(openFolder)
                // .. and close it
                closeFolder(openFolder)
                if (folderScreen != workspace.currentScreen) {
                    // Close any folder open on the current screen
                    closeFolder()
                    // Pull the folder onto this screen
                    openFolder(folderInfo)
                }
            }
        }
    }

    /**
     * Opens the user folder described by the specified tag. The opening of the folder is animated
     * relative to the specified View. If the View is null, no animation is played.
     *
     * @param folderInfo
     * The FolderInfo describing the folder to open.
     */
    private fun openFolder(folderInfo: FolderInfo) {
        val openFolder: Folder = when (folderInfo) {
            is UserFolderInfo -> {
                UserFolder.fromXml(this)
            }
            is LiveFolderInfo -> {
                LiveFolder.fromXml(this, folderInfo)
            }
            else -> {
                return
            }
        }
        openFolder.setDragger(mDragLayer)
        openFolder.setLauncher(this)
        openFolder.bind(folderInfo)
        folderInfo.opened = true
        workspace.addInScreen(openFolder, folderInfo.screen, 0, 0, 4, 4)
        openFolder.onOpen()
    }

    override fun onLongClick(v: View): Boolean {
        var v = v
        if (isWorkspaceLocked) {
            return false
        }
        if (v !is CellLayout) {
            v = v.parent as View
        }
        val cellInfo = v.tag as CellLayout.CellInfo

        // This happens when long clicking an item with the dpad/trackball
        if (workspace.allowLongPress()) {
            if (cellInfo.cell == null) {
                if (cellInfo.valid) {
                    // User long pressed on empty space
                    workspace.setAllowLongPress(false)
                    showAddDialog(cellInfo)
                }
            } else {
                if (cellInfo.cell !is Folder) {
                    // User long pressed on an item
                    workspace.startDrag(cellInfo)
                }
            }
        }
        return true
    }

    fun closeAllApplications() {
        mDrawer!!.close()
    }

    val drawerHandle: View?
        get() = mHandleView
    val isDrawerDown: Boolean
        get() = !mDrawer!!.isMoving && !mDrawer!!.isOpened
    val isDrawerUp: Boolean
        get() = mDrawer!!.isOpened && !mDrawer!!.isMoving
    val isDrawerMoving: Boolean
        get() = mDrawer!!.isMoving
    val applicationsGrid: GridView?
        get() = mAllAppsGrid

    override fun onCreateDialog(id: Int): Dialog {
        when (id) {
            DIALOG_CREATE_SHORTCUT -> return CreateShortcut().createDialog()
            DIALOG_RENAME_FOLDER -> return RenameFolder().createDialog()
        }
        return super.onCreateDialog(id)
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog) {
        when (id) {
            DIALOG_CREATE_SHORTCUT -> {
            }
            DIALOG_RENAME_FOLDER -> if (mFolderInfo != null) {
                val input = dialog.findViewById<View>(R.id.folder_name) as EditText
                val text = mFolderInfo!!.title
                input.setText(text)
                input.setSelection(0, text!!.length)
            }
        }
    }

    fun showRenameDialog(info: FolderInfo?) {
        mFolderInfo = info
        mWaitingForResult = true
        showDialog(DIALOG_RENAME_FOLDER)
    }

    private fun showAddDialog(cellInfo: CellLayout.CellInfo?) {
        mAddItemCellInfo = cellInfo
        mWaitingForResult = true
        showDialog(DIALOG_CREATE_SHORTCUT)
    }

    private fun pickShortcut(requestCode: Int, title: Int) {
        val bundle = Bundle()
        val shortcutNames = ArrayList<String>()
        shortcutNames.add(getString(R.string.group_applications))
        bundle.putStringArrayList(Intent.EXTRA_SHORTCUT_NAME, shortcutNames)
        val shortcutIcons = ArrayList<ShortcutIconResource>()
        shortcutIcons.add(ShortcutIconResource.fromContext(this@Launcher,
            R.drawable.ic_launcher_application))
        bundle.putParcelableArrayList(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, shortcutIcons)
        val pickIntent = Intent(Intent.ACTION_PICK_ACTIVITY)
        pickIntent.putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_CREATE_SHORTCUT))
        pickIntent.putExtra(Intent.EXTRA_TITLE, getText(title))
        pickIntent.putExtras(bundle)
        startActivityForResult(pickIntent, requestCode)
    }

    private inner class RenameFolder {
        private var mInput: EditText? = null
        fun createDialog(): Dialog {
            mWaitingForResult = true
            val layout = View.inflate(this@Launcher, R.layout.rename_folder, null)
            mInput = layout.findViewById<View>(R.id.folder_name) as EditText
            val builder = AlertDialog.Builder(this@Launcher)
            builder.setIcon(0)
            builder.setTitle(getString(R.string.rename_folder_title))
            builder.setCancelable(true)
            builder.setOnCancelListener { cleanup() }
            builder.setNegativeButton(getString(R.string.cancel_action)
            ) { _, _ -> cleanup() }
            builder.setPositiveButton(getString(R.string.rename_action)
            ) { _, _ -> changeFolderName() }
            builder.setView(layout)
            return builder.create()
        }

        private fun changeFolderName() {
            val name = mInput!!.text.toString()
            if (!TextUtils.isEmpty(name)) {
                // Make sure we have the right folder info
                mFolderInfo = model.findFolderById(mFolderInfo!!.id)
                mFolderInfo!!.title = name
                LauncherModel.updateItemInDatabase(this@Launcher, mFolderInfo)
                if (isWorkspaceLocked) {
                    mDrawer!!.lock()
                    model.loadUserItems(false, this@Launcher, localeChanged = false, loadApplications = false)
                } else {
                    val folderIcon = workspace
                        .getViewForTag(mFolderInfo) as FolderIcon
                    folderIcon.text = name
                    workspace.requestLayout()
                }
            }
            cleanup()
        }

        private fun cleanup() {
            workspace.unlock()
            dismissDialog(DIALOG_RENAME_FOLDER)
            mWaitingForResult = false
            mFolderInfo = null
        }
    }

    /**
     * Displays the shortcut creation dialog and launches, if necessary, the appropriate activity.
     */
    private inner class CreateShortcut : DialogInterface.OnClickListener, DialogInterface.OnCancelListener,
        DialogInterface.OnDismissListener {
        private var mAdapter: AddAdapter? = null
        fun createDialog(): Dialog {
            mWaitingForResult = true
            mAdapter = AddAdapter(this@Launcher)
            val builder = AlertDialog.Builder(this@Launcher)
            builder.setTitle(getString(R.string.menu_item_add_item))
            builder.setAdapter(mAdapter, this)
            builder.setInverseBackgroundForced(true)
            val dialog = builder.create()
            dialog.setOnCancelListener(this)
            dialog.setOnDismissListener(this)
            return dialog
        }

        override fun onCancel(dialog: DialogInterface) {
            mWaitingForResult = false
            cleanup()
        }

        override fun onDismiss(dialog: DialogInterface) {
            workspace.unlock()
        }

        private fun cleanup() {
            workspace.unlock()
            dismissDialog(DIALOG_CREATE_SHORTCUT)
        }

        /**
         * Handle the action clicked in the "Add to home" dialog.
         */
        override fun onClick(dialog: DialogInterface, which: Int) {
            val res = resources
            cleanup()
            when (which) {
                AddAdapter.ITEM_SHORTCUT -> {

                    // Insert extra item to handle picking application
                    pickShortcut(REQUEST_PICK_SHORTCUT, R.string.title_select_shortcut)
                }
                AddAdapter.ITEM_APPWIDGET -> {
                    val appWidgetId = appWidgetHost!!.allocateAppWidgetId()
                    val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                    pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    // add the search widget
                    val customInfo = ArrayList<AppWidgetProviderInfo>()
                    val info = AppWidgetProviderInfo()
                    info.provider = ComponentName(packageName, "XXX.YYY")
                    info.label = getString(R.string.group_search)
                    info.icon = R.drawable.ic_search_widget
                    customInfo.add(info)
                    pickIntent.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO,
                        customInfo)
                    val customExtras = ArrayList<Bundle>()
                    val b = Bundle()
                    b.putString(EXTRA_CUSTOM_WIDGET, SEARCH_WIDGET)
                    customExtras.add(b)
                    pickIntent.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS,
                        customExtras)
                    // start the pick activity
                    startActivityForResult(pickIntent, REQUEST_PICK_APPWIDGET)
                }
                AddAdapter.ITEM_LIVE_FOLDER -> {

                    // Insert extra item to handle inserting folder
                    val bundle = Bundle()
                    val shortcutNames = ArrayList<String>()
                    shortcutNames.add(res.getString(R.string.group_folder))
                    bundle.putStringArrayList(Intent.EXTRA_SHORTCUT_NAME, shortcutNames)
                    val shortcutIcons = ArrayList<ShortcutIconResource>()
                    shortcutIcons.add(ShortcutIconResource.fromContext(this@Launcher,
                        R.drawable.ic_launcher_folder))
                    bundle.putParcelableArrayList(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, shortcutIcons)
                    val pickIntent = Intent(Intent.ACTION_PICK_ACTIVITY)
                    pickIntent.putExtra(Intent.EXTRA_INTENT, Intent(
                        LiveFolders.ACTION_CREATE_LIVE_FOLDER))
                    pickIntent.putExtra(Intent.EXTRA_TITLE, getText(R.string.title_select_live_folder))
                    pickIntent.putExtras(bundle)
                    startActivityForResult(pickIntent, REQUEST_PICK_LIVE_FOLDER)
                }
                AddAdapter.ITEM_WALLPAPER -> {
                    startWallpaper()
                }
                AddAdapter.ITEM_SCREENS -> {
                    val intent = Intent(this@Launcher, ScreenPrefActivity::class.java)
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * Receives notifications when applications are added/removed.
     */
    private inner class ApplicationsIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val packageName = intent.data!!.schemeSpecificPart
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (LauncherModel.DEBUG_LOADERS) {
                Log.d(LauncherModel.LOG_TAG, "application intent received: " + action + ", replacing="
                        + replacing)
                Log.d(LauncherModel.LOG_TAG, "  --> " + intent.data)
            }
            if (Intent.ACTION_PACKAGE_CHANGED != action) {
                if (Intent.ACTION_PACKAGE_REMOVED == action) {
                    if (!replacing) {
                        removeShortcutsForPackage(packageName)
                        if (LauncherModel.DEBUG_LOADERS) {
                            Log.d(LauncherModel.LOG_TAG, "  --> remove package")
                        }
                        model.removePackage(this@Launcher, packageName)
                    }
                    // else, we are replacing the package, so a PACKAGE_ADDED will be sent
                    // later, we will update the package at this time
                } else {
                    if (!replacing) {
                        if (LauncherModel.DEBUG_LOADERS) {
                            Log.d(LauncherModel.LOG_TAG, "  --> add package")
                        }
                        model.addPackage(this@Launcher, packageName)
                    } else {
                        if (LauncherModel.DEBUG_LOADERS) {
                            Log.d(LauncherModel.LOG_TAG, "  --> update package $packageName")
                        }
                        model.updatePackage(this@Launcher, packageName)
                        updateShortcutsForPackage(packageName)
                    }
                }
                removeDialog(DIALOG_CREATE_SHORTCUT)
            } else {
                if (LauncherModel.DEBUG_LOADERS) {
                    Log.d(LauncherModel.LOG_TAG, "  --> sync package $packageName")
                }
                model.syncPackage(this@Launcher, packageName)
            }
        }
    }

    /**
     * Receives notifications when applications are added/removed.
     */
    private inner class CloseSystemDialogsIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            closeSystemDialogs()
        }
    }

    /**
     * Receives notifications whenever the user favorites have changed.
     */
    private inner class FavoritesChangeObserver : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            onFavoritesChanged()
        }
    }

    /**
     * Receives notifications whenever the appwidgets are reset.
     */
    private inner class AppWidgetResetObserver : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            onAppWidgetReset()
        }
    }

    private inner class DrawerManager : OnDrawerOpenListener, OnDrawerCloseListener, OnDrawerScrollListener {
        private var mOpen = false
        override fun onDrawerOpened() {
            if (!mOpen) {
                mHandleIcon!!.reverseTransition(150)
                val bounds = workspace.mDrawerBounds
                offsetBoundsToDragLayer(bounds, mAllAppsGrid)
                mOpen = true
            }
        }

        private fun offsetBoundsToDragLayer(bounds: Rect?, view: View?) {
            var view = view
            view!!.getDrawingRect(bounds)
            while (view !== mDragLayer) {
                bounds!!.offset(view!!.left, view.top)
                view = view.parent as View
            }
        }

        override fun onDrawerClosed() {
            if (mOpen) {
                mHandleIcon!!.reverseTransition(150)
                workspace.mDrawerBounds.setEmpty()
                mOpen = false
            }
            mAllAppsGrid!!.setSelection(0)
            mAllAppsGrid!!.clearTextFilter()
        }

        override fun onScrollStarted() {
            workspace.mDrawerContentWidth = mAllAppsGrid!!.width
            workspace.mDrawerContentHeight = mAllAppsGrid!!.height
        }

        override fun onScrollEnded() {
            if (PROFILE_DRAWER) {
                Debug.stopMethodTracing()
            }
        }
    }

    private class DesktopBinder(
        launcher: Launcher, shortcuts: ArrayList<ItemInfo>,
        appWidgets: ArrayList<LauncherAppWidgetInfo>, drawerAdapter: ApplicationsAdapter,
    ) : Handler(), IdleHandler {
        private val mShortcuts = shortcuts
        private val mAppWidgets: LinkedList<LauncherAppWidgetInfo>
        private val mDrawerAdapter = drawerAdapter
        private val mLauncher: WeakReference<Launcher> = WeakReference(launcher)
        var mTerminate = false
        fun startBindingItems() {
            if (LauncherModel.DEBUG_LOADERS) Log.d(LOG_TAG, "------> start binding items")
            obtainMessage(MESSAGE_BIND_ITEMS, 0, mShortcuts.size).sendToTarget()
        }

        fun startBindingDrawer() {
            obtainMessage(MESSAGE_BIND_DRAWER).sendToTarget()
        }

        fun startBindingAppWidgetsWhenIdle() {
            // Ask for notification when message queue becomes idle
            val messageQueue = Looper.myQueue()
            messageQueue.addIdleHandler(this)
        }

        override fun queueIdle(): Boolean {
            // Queue is idle, so start binding items
            startBindingAppWidgets()
            return false
        }

        fun startBindingAppWidgets() {
            obtainMessage(MESSAGE_BIND_APPWIDGETS).sendToTarget()
        }

        override fun handleMessage(msg: Message) {
            val launcher = mLauncher.get()
            if (launcher == null || mTerminate) {
                return
            }
            when (msg.what) {
                MESSAGE_BIND_ITEMS -> {
                    launcher.bindItems(this, mShortcuts, msg.arg1, msg.arg2)
                }
                MESSAGE_BIND_DRAWER -> {
                    launcher.bindDrawer(this, mDrawerAdapter)
                }
                MESSAGE_BIND_APPWIDGETS -> {
                    launcher.bindAppWidgets(this, mAppWidgets)
                }
            }
        }

        companion object {
            const val MESSAGE_BIND_ITEMS = 0x1
            const val MESSAGE_BIND_APPWIDGETS = 0x2
            const val MESSAGE_BIND_DRAWER = 0x3

            // Number of items to bind in every pass
            const val ITEMS_COUNT = 6
        }

        init {

            // Sort widgets so active workspace is bound first
            val currentScreen = launcher.workspace.currentScreen
            val screenNumber = launcher.workspace.childCount
            val size = appWidgets.size
            mAppWidgets = LinkedList()
            for (i in 0 until size) {
                val appWidgetInfo = appWidgets[i]
                if (appWidgetInfo.screen >= screenNumber) continue
                if (appWidgetInfo.screen == currentScreen) {
                    mAppWidgets.addFirst(appWidgetInfo)
                } else {
                    mAppWidgets.addLast(appWidgetInfo)
                }
            }
            if (LauncherModel.DEBUG_LOADERS) {
                Log.d(LOG_TAG, "------> binding " + shortcuts.size + " items")
                Log.d(LOG_TAG, "------> binding " + appWidgets.size + " widgets")
            }
        }
    }

    /**
     *
     * @return
     */
    private fun syncScreenNumber(): Boolean {
        try {
            val number = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(getString(R.string.key_screen_number), "3")?.toInt() ?: 3
            val count = workspace.childCount
            Log.i(LOG_TAG, "Screen number $count, to be $number")

            // Don't need to change
            if (count == number) return false
            return if (number < count) {
                // User wants to remove some
                for (i in number until count) {
                    workspace.removeViewAt(i)
                    Log.i("LOG_TAG", "Screen added")
                }
                true
            } else {
                for (i in number - count downTo 1) workspace.addView(mInflater!!.inflate(R.layout.workspace_screen, null))
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     *
     * @return
     */
    private fun restartOnScreenNumberChange(): Boolean {
        try {
            val number = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(getString(R.string.key_screen_number), "3")?.toInt() ?: 3
            if (workspace.childCount != number) {
                finish()
                startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }


    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (TextUtils.equals(getString(R.string.key_default_screen), key)) {
            // Reset default screen
            val screen = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(key, "2")!!.toInt()
            workspace.resetDefaultScreen(screen - 1)
        }
    }

    companion object {
        const val LOG_TAG = "Launcher"
        const val LOGD = false
        private const val PROFILE_STARTUP = false
        private const val PROFILE_DRAWER = false
        private const val PROFILE_ROTATE = false
        private const val DEBUG_USER_INTERFACE = false
        private const val MENU_GROUP_ADD = 1
        private const val MENU_ADD = Menu.FIRST + 1
        private const val MENU_WALLPAPER_SETTINGS = MENU_ADD + 1
        private const val MENU_SEARCH = MENU_WALLPAPER_SETTINGS + 1
        private const val MENU_NOTIFICATIONS = MENU_SEARCH + 1
        private const val MENU_SETTINGS = MENU_NOTIFICATIONS + 1
        private const val REQUEST_CREATE_SHORTCUT = 1
        private const val REQUEST_CREATE_LIVE_FOLDER = 4
        private const val REQUEST_CREATE_APPWIDGET = 5
        private const val REQUEST_PICK_APPLICATION = 6
        private const val REQUEST_PICK_SHORTCUT = 7
        private const val REQUEST_PICK_LIVE_FOLDER = 8
        private const val REQUEST_PICK_APPWIDGET = 9
        const val EXTRA_SHORTCUT_DUPLICATE = "duplicate"
        const val EXTRA_CUSTOM_WIDGET = "custom_widget"
        const val SEARCH_WIDGET = "search_widget"
        const val WALLPAPER_SCREENS_SPAN = 2
        const val SCREEN_COUNT = 3
        private const val DEFAULT_SCREN = 1
        const val NUMBER_CELLS_X = 4
        const val NUMBER_CELLS_Y = 4
        private const val DIALOG_CREATE_SHORTCUT = 1
        const val DIALOG_RENAME_FOLDER = 2
        private const val PREFERENCES = "launcher.preferences"

        // Type: int
        private const val RUNTIME_STATE_CURRENT_SCREEN = "launcher.current_screen"

        // Type: boolean
        private const val RUNTIME_STATE_ALL_APPS_FOLDER = "launcher.all_apps_folder"

        // Type: long
        private const val RUNTIME_STATE_USER_FOLDERS = "launcher.user_folder"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_SCREEN = "launcher.add_screen"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_CELL_X = "launcher.add_cellX"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_CELL_Y = "launcher.add_cellY"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_SPAN_X = "launcher.add_spanX"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_SPAN_Y = "launcher.add_spanY"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_COUNT_X = "launcher.add_countX"

        // Type: int
        private const val RUNTIME_STATE_PENDING_ADD_COUNT_Y = "launcher.add_countY"

        // Type: int[]
        private const val RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS = "launcher.add_occupied_cells"

        // Type: boolean
        private const val RUNTIME_STATE_PENDING_FOLDER_RENAME = "launcher.rename_folder"

        // Type: long
        private const val RUNTIME_STATE_PENDING_FOLDER_RENAME_ID = "launcher.rename_folder_id"
        val model = LauncherModel()
        private val sLock = Any()
        private var sScreen = DEFAULT_SCREN
        const val APPWIDGET_HOST_ID = 1024
        private fun readConfiguration(context: Context, configuration: LocaleConfiguration) {
            var `in`: DataInputStream? = null
            try {
                `in` = DataInputStream(context.openFileInput(PREFERENCES))
                configuration.locale = `in`.readUTF()
                configuration.mcc = `in`.readInt()
                configuration.mnc = `in`.readInt()
            } catch (e: FileNotFoundException) {
                // Ignore
            } catch (e: IOException) {
                // Ignore
            } finally {
                if (`in` != null) {
                    try {
                        `in`.close()
                    } catch (e: IOException) {
                        // Ignore
                    }
                }
            }
        }

        private fun writeConfiguration(context: Context, configuration: LocaleConfiguration) {
            var out: DataOutputStream? = null
            try {
                out = DataOutputStream(context.openFileOutput(PREFERENCES, MODE_PRIVATE))
                out.writeUTF(configuration.locale)
                out.writeInt(configuration.mcc)
                out.writeInt(configuration.mnc)
                out.flush()
            } catch (e: FileNotFoundException) {
                // Ignore
            } catch (e: IOException) {
                // noinspection ResultOfMethodCallIgnored
                context.getFileStreamPath(PREFERENCES).delete()
            } finally {
                if (out != null) {
                    try {
                        out.close()
                    } catch (e: IOException) {
                        // Ignore
                    }
                }
            }
        }

        var screen: Int
            get() {
                synchronized(sLock) { return sScreen }
            }
            set(screen) {
                synchronized(sLock) { sScreen = screen }
            }

        private fun infoFromApplicationIntent(context: Context, data: Intent): ApplicationInfo? {
            val component = data.component
            val packageManager = context.packageManager
            var activityInfo: ActivityInfo? = null
            try {
                activityInfo = component?.let { packageManager.getActivityInfo(it, 0 /* no flags */) }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG, "Couldn't find ActivityInfo for selected application", e)
            }
            if (activityInfo != null) {
                val itemInfo = ApplicationInfo()
                itemInfo.title = activityInfo.loadLabel(packageManager)
                if (itemInfo.title == null) {
                    itemInfo.title = activityInfo.name
                }
                itemInfo.setActivity(component, Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                itemInfo.icon = activityInfo.loadIcon(packageManager)
                itemInfo.container = ItemInfo.NO_ID.toLong()
                return itemInfo
            }
            return null
        }

        fun addShortcut(
            context: Context, data: Intent, cellInfo: CellLayout.CellInfo,
            notify: Boolean,
        ): ApplicationInfo {
            val info = infoFromShortcutIntent(context, data)
            LauncherModel.addItemToDatabase(context, info,
                Favorites.CONTAINER_DESKTOP.toLong(), cellInfo.screen, cellInfo.cellX,
                cellInfo.cellY, notify)
            return info
        }

        private fun infoFromShortcutIntent(context: Context, data: Intent): ApplicationInfo {
            val intent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
            val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
            val bitmap = data.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON)
            var icon: Drawable? = null
            var filtered = false
            var customIcon = false
            var iconResource: ShortcutIconResource? = null
            if (bitmap != null) {
                icon = FastBitmapDrawable(Utilities.createBitmapThumbnail(bitmap, context))
                filtered = true
                customIcon = true
            } else {
                val extra = data.getParcelableExtra<Parcelable>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
                if (extra != null && extra is ShortcutIconResource) {
                    try {
                        iconResource = extra
                        val packageManager = context.packageManager
                        val resources = packageManager
                            .getResourcesForApplication(iconResource.packageName)
                        val id = resources.getIdentifier(iconResource.resourceName, null, null)
                        icon = resources.getDrawable(id)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Could not load shortcut icon: $extra")
                    }
                }
            }
            if (icon == null) {
                icon = context.packageManager.defaultActivityIcon
            }
            val info = ApplicationInfo()
            info.icon = icon
            info.filtered = filtered
            info.title = name
            info.intent = intent
            info.customIcon = customIcon
            info.iconResource = iconResource
            return info
        }

        fun addLiveFolder(
            context: Context, data: Intent, cellInfo: CellLayout.CellInfo,
            notify: Boolean,
        ): LiveFolderInfo {
            val baseIntent = data.getParcelableExtra<Intent>(LiveFolders.EXTRA_LIVE_FOLDER_BASE_INTENT)
            val name = data.getStringExtra(LiveFolders.EXTRA_LIVE_FOLDER_NAME)
            var icon: Drawable? = null
            val filtered = false
            var iconResource: ShortcutIconResource? = null
            val extra = data.getParcelableExtra<Parcelable>(LiveFolders.EXTRA_LIVE_FOLDER_ICON)
            if (extra != null && extra is ShortcutIconResource) {
                try {
                    iconResource = extra
                    val packageManager = context.packageManager
                    val resources = packageManager
                        .getResourcesForApplication(iconResource.packageName)
                    val id = resources.getIdentifier(iconResource.resourceName, null, null)
                    icon = resources.getDrawable(id)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not load live folder icon: $extra")
                }
            }
            if (icon == null) {
                icon = context.resources.getDrawable(R.drawable.ic_launcher_folder)
            }
            val info = LiveFolderInfo()
            info.icon = icon
            info.filtered = filtered
            info.title = name
            if (iconResource != null) {
                info.iconResource = iconResource
            }
            info.uri = data.data
            info.baseIntent = baseIntent
            info.displayMode = data.getIntExtra(LiveFolders.EXTRA_LIVE_FOLDER_DISPLAY_MODE,
                LiveFolders.DISPLAY_MODE_GRID)
            LauncherModel.addItemToDatabase(context, info,
                Favorites.CONTAINER_DESKTOP.toLong(), cellInfo.screen, cellInfo.cellX,
                cellInfo.cellY, notify)
            model.addFolder(info)
            return info
        }

    }
}