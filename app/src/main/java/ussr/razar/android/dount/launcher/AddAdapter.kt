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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.util.*

/**
 * Adapter showing the types of items that can be added to a [Workspace].
 */
class AddAdapter(launcher: Launcher) : BaseAdapter() {
    private val mInflater: LayoutInflater = launcher.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val mItems = ArrayList<ListItem>()

    /**
     * Specific item in our list.
     */
    inner class ListItem(res: Resources, textResourceId: Int, imageResourceId: Int, actionTag: Int) {
        val text: CharSequence
        var image: Drawable? = null
        private val actionTag: Int

        init {
            text = res.getString(textResourceId)
            image = if (imageResourceId != -1) {
                res.getDrawable(imageResourceId)
            } else {
                null
            }
            this.actionTag = actionTag
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        var convertView = convertView
        val item = getItem(position) as ListItem
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.add_list_item, parent, false)
        }
        val textView = convertView as TextView
        textView.tag = item
        textView.text = item.text
        textView.setCompoundDrawablesWithIntrinsicBounds(item.image, null, null, null)
        return convertView
    }

    override fun getCount(): Int {
        return mItems.size
    }

    override fun getItem(position: Int): Any {
        return mItems[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    companion object {
        const val ITEM_SHORTCUT = 0
        const val ITEM_APPWIDGET = 1
        const val ITEM_LIVE_FOLDER = 2
        const val ITEM_WALLPAPER = 3
        const val ITEM_SCREENS = 4
    }

    init {

        // Create default actions
        val res = launcher.resources
        mItems.add(ListItem(res, R.string.group_shortcuts,
            R.drawable.ic_launcher_shortcut, ITEM_SHORTCUT))
        mItems.add(ListItem(res, R.string.group_widgets,
            R.drawable.ic_launcher_appwidget, ITEM_APPWIDGET))
        mItems.add(ListItem(res, R.string.group_live_folders,
            R.drawable.ic_launcher_add_folder, ITEM_LIVE_FOLDER))
        mItems.add(ListItem(res, R.string.group_wallpapers,
            R.drawable.ic_launcher_wallpaper, ITEM_WALLPAPER))
        mItems.add(ListItem(res, R.string.group_screens,
            R.drawable.ic_menu_thumbnail, ITEM_SCREENS))
    }
}