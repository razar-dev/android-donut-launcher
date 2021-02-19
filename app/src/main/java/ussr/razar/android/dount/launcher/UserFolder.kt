package ussr.razar.android.dount.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import ussr.razar.android.dount.launcher.LauncherSettings.BaseLauncherColumns

/**
 * Folder which contains applications or shortcuts chosen by the user.
 *
 */
class UserFolder(context: Context?, attrs: AttributeSet?) : Folder(context, attrs), DropTarget {
    override fun acceptDrop(
        source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int,
        dragInfo: Any?
    ): Boolean {
        val item = dragInfo as ItemInfo?
        val itemType = item!!.itemType
        return (itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION ||
                itemType == BaseLauncherColumns.ITEM_TYPE_SHORTCUT) && item.container != info?.id
    }

    override fun onDrop(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {
        val item = dragInfo as ApplicationInfo?
        (mContent!!.adapter as ArrayAdapter<ApplicationInfo?>).add(dragInfo)
        LauncherModel.addOrMoveItemInDatabase(mLauncher, item, info!!.id, 0, 0, 0)
    }

    override fun onDragEnter(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}
    override fun onDragOver(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}
    override fun onDragExit(source: DragSource?, x: Int, y: Int, xOffset: Int, yOffset: Int, dragInfo: Any?) {}
    override fun onDropCompleted(target: View, success: Boolean) {
        if (success) {
            val adapter = mContent!!.adapter as ArrayAdapter<ApplicationInfo>
            adapter.remove(mDragItem)
        }
    }

    override fun bind(info: FolderInfo) {
        super.bind(info)
        setContentAdapter(ApplicationsAdapter(context, (info as UserFolderInfo).contents))
    }

    override fun onOpen() {
        super.onOpen()
        requestFocus()
    }

    companion object {
        /**
         * Creates a new UserFolder, inflated from R.layout.user_folder.
         *
         * @param context The application's context.
         *
         * @return A new UserFolder.
         */
        fun fromXml(context: Context?): UserFolder {
            return LayoutInflater.from(context).inflate(R.layout.user_folder, null) as UserFolder
        }
    }
}