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

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.widget.Toast
import ussr.razar.android.dount.launcher.LauncherSettings.Favorites

class InstallShortcutReceiver : BroadcastReceiver() {
    private val mCoordinates: IntArray = IntArray(2)
    override fun onReceive(context: Context, data: Intent) {
        if (ACTION_INSTALL_SHORTCUT != data.action) {
            return
        }
        val screen: Int = Launcher.screen
        if (!installShortcut(context, data, screen)) {
            // The target screen is full, let's try the other screens
            for (i in 0 until Launcher.SCREEN_COUNT) {
                if (i != screen && installShortcut(context, data, i)) break
            }
        }
    }

    private fun installShortcut(context: Context, data: Intent, screen: Int): Boolean {
        val name: String? = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
        if (findEmptyCell(context, mCoordinates, screen)) {
            val cell: CellLayout.CellInfo = CellLayout.CellInfo()
            cell.cellX = mCoordinates[0]
            cell.cellY = mCoordinates[1]
            cell.screen = screen
            val intent: Intent? = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            if (intent?.action == null) {
                intent?.action = Intent.ACTION_VIEW
            }

            // By default, we allow for duplicate entries (located in
            // different places)
            val duplicate: Boolean = data.getBooleanExtra(Launcher.EXTRA_SHORTCUT_DUPLICATE, true)
            if (duplicate || !LauncherModel.shortcutExists(context, name!!, intent!!)) {
                Launcher.addShortcut(context, data, cell, true)
                Toast.makeText(context, context.getString(R.string.shortcut_installed, name),
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.shortcut_duplicate, name),
                    Toast.LENGTH_SHORT).show()
            }
            return true
        } else {
            Toast.makeText(context, context.getString(R.string.out_of_space),
                Toast.LENGTH_SHORT).show()
        }
        return false
    }

    companion object {
        private const val ACTION_INSTALL_SHORTCUT: String = "com.android.launcher.action.INSTALL_SHORTCUT"
        private fun findEmptyCell(context: Context, xy: IntArray, screen: Int): Boolean {
            val xCount: Int = Launcher.NUMBER_CELLS_X
            val yCount: Int = Launcher.NUMBER_CELLS_Y
            val occupied: Array<BooleanArray> = Array(xCount) { BooleanArray(yCount) }
            val cr: ContentResolver = context.contentResolver
            val c: Cursor = cr.query(Favorites.CONTENT_URI, arrayOf<String?>(Favorites.CELLX, Favorites.CELLY,
                Favorites.SPANX, Favorites.SPANY),
                Favorites.SCREEN + "=?", arrayOf(screen.toString()), null)!!
            val cellXIndex: Int = c.getColumnIndexOrThrow(Favorites.CELLX)
            val cellYIndex: Int = c.getColumnIndexOrThrow(Favorites.CELLY)
            val spanXIndex: Int = c.getColumnIndexOrThrow(Favorites.SPANX)
            val spanYIndex: Int = c.getColumnIndexOrThrow(Favorites.SPANY)
            try {
                while (c.moveToNext()) {
                    val cellX: Int = c.getInt(cellXIndex)
                    val cellY: Int = c.getInt(cellYIndex)
                    val spanX: Int = c.getInt(spanXIndex)
                    val spanY: Int = c.getInt(spanYIndex)
                    var x: Int = cellX
                    while (x < cellX + spanX && x < xCount) {
                        var y: Int = cellY
                        while (y < cellY + spanY && y < yCount) {
                            occupied[x][y] = true
                            y++
                        }
                        x++
                    }
                }
            } catch (e: Exception) {
                return false
            } finally {
                c.close()
            }
            return CellLayout.findVacantCell(xy, 1, 1, xCount, yCount, occupied)
        }
    }
}