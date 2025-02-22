package com.fsck.k9.storage.messages

import android.content.ContentValues
import com.fsck.k9.mail.FolderClass
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mailstore.FolderDetails
import com.fsck.k9.mailstore.LockableDatabase
import com.fsck.k9.mailstore.toDatabaseFolderType

internal class UpdateFolderOperations(private val lockableDatabase: LockableDatabase) {
    fun changeFolder(folderServerId: String, name: String, type: FolderType) {
        lockableDatabase.execute(false) { db ->
            val values = ContentValues().apply {
                put("name", name)
                put("type", type.toDatabaseFolderType())
            }

            db.update("folders", values, "server_id = ?", arrayOf(folderServerId))
        }
    }

    fun updateFolderSettings(folderDetails: FolderDetails) {
        lockableDatabase.execute(false) { db ->
            val contentValues = ContentValues().apply {
                put("top_group", folderDetails.isInTopGroup)
                put("integrate", folderDetails.isIntegrate)
                put("poll_class", folderDetails.syncClass.name)
                put("display_class", folderDetails.displayClass.name)
                put("notify_class", folderDetails.notifyClass.name)
                put("push_class", folderDetails.pushClass.name)
            }

            db.update("folders", contentValues, "id = ?", arrayOf(folderDetails.folder.id.toString()))
        }
    }

    fun setIncludeInUnifiedInbox(folderId: Long, includeInUnifiedInbox: Boolean) {
        lockableDatabase.execute(false) { db ->
            val contentValues = ContentValues().apply {
                put("integrate", includeInUnifiedInbox)
            }

            db.update("folders", contentValues, "id = ?", arrayOf(folderId.toString()))
        }
    }

    fun setDisplayClass(folderId: Long, folderClass: FolderClass) {
        setFolderClass(folderId, "display_class", folderClass)
    }

    fun setSyncClass(folderId: Long, folderClass: FolderClass) {
        setFolderClass(folderId, "poll_class", folderClass)
    }

    fun setNotificationClass(folderId: Long, folderClass: FolderClass) {
        setFolderClass(folderId, "notify_class", folderClass)
    }

    private fun setFolderClass(folderId: Long, columnName: String, folderClass: FolderClass) {
        lockableDatabase.execute(false) { db ->
            val contentValues = ContentValues().apply {
                put(columnName, folderClass.name)
            }

            db.update("folders", contentValues, "id = ?", arrayOf(folderId.toString()))
        }
    }
}
