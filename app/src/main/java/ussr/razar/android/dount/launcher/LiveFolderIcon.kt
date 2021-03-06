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
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup

class LiveFolderIcon : FolderIcon {
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?) : super(context)

    override fun acceptDrop(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?): Boolean {
        return false
    }

    override fun onDrop(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}
    override fun onDragEnter(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}
    override fun onDragOver(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}
    override fun onDragExit(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}

    companion object {
        fun fromXml(
            resId: Int, launcher: Launcher, group: ViewGroup?,
            folderInfo: LiveFolderInfo?
        ): LiveFolderIcon {
            val icon: LiveFolderIcon = LayoutInflater.from(launcher).inflate(resId, group, false) as LiveFolderIcon
            val resources: Resources = launcher.resources
            var d: Drawable? = folderInfo!!.icon
            if (d == null) {
                d = Utilities.createIconThumbnail(
                    resources.getDrawable(R.drawable.ic_launcher_folder), launcher)
                folderInfo.filtered = true
            }
            icon.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null)
            icon.text = folderInfo.title
            icon.tag = folderInfo
            icon.setOnClickListener(launcher)
            return icon
        }
    }
}