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
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.widget.Toast
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites
import java.net.URISyntaxException

class UninstallShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, data: Intent) {
        if (ACTION_UNINSTALL_SHORTCUT != data.action) {
            return
        }
        val intent: Intent? = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        val name: String? = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
        val duplicate: Boolean = data.getBooleanExtra(Launcher.EXTRA_SHORTCUT_DUPLICATE, true)
        if (intent != null && name != null) {
            val cr: ContentResolver = context.contentResolver
            val c: Cursor = cr.query(Favorites.CONTENT_URI, arrayOf(BaseColumns._ID, BaseLauncherColumns.INTENT),
                BaseLauncherColumns.TITLE + "=?", arrayOf(name), null)!!
            val intentIndex: Int = c.getColumnIndexOrThrow(BaseLauncherColumns.INTENT)
            val idIndex: Int = c.getColumnIndexOrThrow(BaseColumns._ID)
            var changed = false
            c.use { c ->
                while (c.moveToNext()) {
                    try {
                        if (intent.filterEquals(Intent.parseUri(c.getString(intentIndex), 0))) {
                            val id: Long = c.getLong(idIndex)
                            val uri: Uri = Favorites.getContentUri(id, false)
                            cr.delete(uri, null, null)
                            changed = true
                            if (!duplicate) {
                                break
                            }
                        }
                    } catch (e: URISyntaxException) {
                        // Ignore
                    }
                }
            }
            if (changed) {
                cr.notifyChange(Favorites.CONTENT_URI, null)
                Toast.makeText(context, context.getString(R.string.shortcut_uninstalled, name),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ACTION_UNINSTALL_SHORTCUT: String = "com.android.launcher.action.UNINSTALL_SHORTCUT"
    }
}