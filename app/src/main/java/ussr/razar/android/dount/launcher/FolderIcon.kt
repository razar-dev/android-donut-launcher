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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns

/**
 * An icon that can appear on in the workspace representing an [UserFolder].
 */
open class FolderIcon : BubbleTextView, DropTarget {
    private var mInfo: UserFolderInfo? = null
    private var mLauncher: Launcher? = null
    private var mCloseIcon: Drawable? = null
    private var mOpenIcon: Drawable? = null

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?) : super(context)

    override fun acceptDrop(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ): Boolean {
        val item = dragInfo as ItemInfo?
        val itemType = item!!.itemType
        return ((itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION ||
                itemType == BaseLauncherColumns.ITEM_TYPE_SHORTCUT)
                && item.container != mInfo!!.id)
    }

    override fun onDrop(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {
        val item = dragInfo as ApplicationInfo
        // TODO: update open folder that is looking at this data
        mInfo!!.add(item)
        LauncherModel.addOrMoveItemInDatabase(mLauncher, item, mInfo!!.id, 0, 0, 0)
    }

    override fun onDragEnter(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ) {
        setCompoundDrawablesWithIntrinsicBounds(null, mOpenIcon, null, null)
    }

    override fun onDragOver(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ) {
    }

    override fun onDragExit(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ) {
        setCompoundDrawablesWithIntrinsicBounds(null, mCloseIcon, null, null)
    }

    companion object {
        fun fromXml(
            resId: Int, launcher: Launcher?, group: ViewGroup?,
            folderInfo: UserFolderInfo?
        ): FolderIcon {
            val icon = LayoutInflater.from(launcher).inflate(resId, group, false) as FolderIcon
            val resources = launcher!!.resources
            var d = resources.getDrawable(R.drawable.ic_launcher_folder)
            d = Utilities.createIconThumbnail(d, launcher)
            icon.mCloseIcon = d
            icon.mOpenIcon = resources.getDrawable(R.drawable.ic_launcher_folder_open)
            icon.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null)
            icon.text = folderInfo!!.title
            icon.tag = folderInfo
            icon.setOnClickListener(launcher)
            icon.mInfo = folderInfo
            icon.mLauncher = launcher
            return icon
        }
    }
}