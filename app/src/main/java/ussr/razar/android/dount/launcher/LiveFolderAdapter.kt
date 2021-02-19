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
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.provider.LiveFolders
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.SoftReference
import java.net.URISyntaxException
import java.util.*

internal class LiveFolderAdapter constructor(launcher: Launcher?, info: LiveFolderInfo?, cursor: Cursor?) :
    CursorAdapter(launcher, cursor, true) {
    private val mIsList: Boolean = info!!.displayMode == LiveFolders.DISPLAY_MODE_LIST
    private val mInflater: LayoutInflater = LayoutInflater.from(launcher)
    private val mIcons: HashMap<String, Drawable?> = HashMap()
    private val mCustomIcons: HashMap<Long, SoftReference<Drawable>> = HashMap()
    private val mLauncher = launcher
    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        val view: View
        val holder = ViewHolder()
        if (!mIsList) {
            view = mInflater.inflate(R.layout.application_boxed, parent, false)
        } else {
            view = mInflater.inflate(R.layout.application_list, parent, false)
            holder.description = view.findViewById<View>(R.id.description) as TextView?
            holder.icon = view.findViewById<View>(R.id.icon) as ImageView?
        }
        holder.name = view.findViewById<View>(R.id.name) as TextView?
        holder.idIndex = cursor.getColumnIndexOrThrow(LiveFolders._ID)
        holder.nameIndex = cursor.getColumnIndexOrThrow(LiveFolders.NAME)
        holder.descriptionIndex = cursor.getColumnIndex(LiveFolders.DESCRIPTION)
        holder.intentIndex = cursor.getColumnIndex(LiveFolders.INTENT)
        holder.iconBitmapIndex = cursor.getColumnIndex(LiveFolders.ICON_BITMAP)
        holder.iconResourceIndex = cursor.getColumnIndex(LiveFolders.ICON_RESOURCE)
        holder.iconPackageIndex = cursor.getColumnIndex(LiveFolders.ICON_PACKAGE)
        view.tag = holder
        return view
    }

    override fun bindView(view: View, context: Context, cursor: Cursor) {
        val holder: ViewHolder = view.tag as ViewHolder
        holder.id = cursor.getLong(holder.idIndex)
        val icon: Drawable? = loadIcon(context, cursor, holder)
        holder.name!!.text = cursor.getString(holder.nameIndex)
        if (!mIsList) {
            holder.name!!.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        } else {
            val hasIcon: Boolean = icon != null
            holder.icon!!.visibility = if (hasIcon) View.VISIBLE else View.GONE
            if (hasIcon) holder.icon!!.setImageDrawable(icon)
            if (holder.descriptionIndex != -1) {
                val description: String? = cursor.getString(holder.descriptionIndex)
                if (description != null) {
                    holder.description!!.text = description
                    holder.description!!.visibility = View.VISIBLE
                } else {
                    holder.description!!.visibility = View.GONE
                }
            } else {
                holder.description!!.visibility = View.GONE
            }
        }
        if (holder.intentIndex != -1) {
            try {
                holder.intent = Intent.parseUri(cursor.getString(holder.intentIndex), 0)
            } catch (e: URISyntaxException) {
                // Ignore
            }
        } else {
            holder.useBaseIntent = true
        }
    }

    private fun loadIcon(context: Context, cursor: Cursor, holder: ViewHolder): Drawable? {
        var icon: Drawable? = null
        var data: ByteArray? = null
        if (holder.iconBitmapIndex != -1) {
            data = cursor.getBlob(holder.iconBitmapIndex)
        }
        if (data != null) {
            val reference: SoftReference<Drawable>? = mCustomIcons[holder.id]
            if (reference != null) {
                icon = reference.get()
            }
            if (icon == null) {
                val bitmap: Bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                icon = FastBitmapDrawable(Utilities.createBitmapThumbnail(bitmap, context))
                mCustomIcons[holder.id] = SoftReference(icon)
            }
        } else if (holder.iconResourceIndex != -1 && holder.iconPackageIndex != -1) {
            val resource: String = cursor.getString(holder.iconResourceIndex)
            icon = mIcons[resource]
            if (icon == null) {
                try {
                    val packageManager: PackageManager = context.packageManager
                    val resources: Resources = packageManager.getResourcesForApplication(
                        cursor.getString(holder.iconPackageIndex))
                    val id: Int = resources.getIdentifier(resource,
                        null, null)
                    icon = Utilities.createIconThumbnail(resources.getDrawable(id), context)
                    mIcons[resource] = icon
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        return icon
    }

    fun cleanup() {
        for (icon: Drawable? in mIcons.values) {
            icon!!.callback = null
        }
        mIcons.clear()
        for (icon: SoftReference<Drawable> in mCustomIcons.values) {
            val drawable: Drawable? = icon.get()
            if (drawable != null) {
                drawable.callback = null
            }
        }
        mCustomIcons.clear()
        val cursor: Cursor? = cursor
        if (cursor != null) {
            try {
                cursor.close()
            } finally {
                mLauncher!!.stopManagingCursor(cursor)
            }
        }
    }

    internal class ViewHolder {
        var name: TextView? = null
        var description: TextView? = null
        var icon: ImageView? = null
        var intent: Intent? = null
        var id: Long = 0
        var useBaseIntent: Boolean = false
        var idIndex: Int = 0
        var nameIndex: Int = 0
        var descriptionIndex: Int = -1
        var intentIndex: Int = -1
        var iconBitmapIndex: Int = -1
        var iconResourceIndex: Int = -1
        var iconPackageIndex: Int = -1
    }

    companion object {
        fun query(context: Context?, info: LiveFolderInfo?): Cursor {
            return context!!.contentResolver.query(info!!.uri!!, null, null,
                null, LiveFolders.NAME + " ASC")!!
        }
    }

    init {
        mLauncher!!.startManagingCursor(getCursor())
    }
}