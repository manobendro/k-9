package com.fsck.k9.storage.messages

import android.database.Cursor
import com.fsck.k9.Account.FolderMode
import com.fsck.k9.helper.map
import com.fsck.k9.mail.FolderClass
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mailstore.FolderDetailsAccessor
import com.fsck.k9.mailstore.FolderMapper
import com.fsck.k9.mailstore.LockableDatabase
import com.fsck.k9.mailstore.toFolderType

internal class RetrieveFolderOperations(private val lockableDatabase: LockableDatabase) {
    fun <T> getFolder(folderId: Long, mapper: FolderMapper<T>): T? {
        return lockableDatabase.execute(false) { db ->
            db.query(
                "folders",
                FOLDER_COLUMNS,
                "id = ?",
                arrayOf(folderId.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val cursorFolderAccessor = CursorFolderAccessor(cursor)
                    mapper.map(cursorFolderAccessor)
                } else {
                    null
                }
            }
        }
    }

    fun <T> getFolders(excludeLocalOnly: Boolean = false, mapper: FolderMapper<T>): List<T> {
        val selection = if (excludeLocalOnly) "local_only = 0" else null
        return lockableDatabase.execute(false) { db ->
            db.query("folders", FOLDER_COLUMNS, selection, null, null, null, "id").use { cursor ->
                val cursorFolderAccessor = CursorFolderAccessor(cursor)
                cursor.map {
                    mapper.map(cursorFolderAccessor)
                }
            }
        }
    }

    fun <T> getDisplayFolders(displayMode: FolderMode, outboxFolderId: Long?, mapper: FolderMapper<T>): List<T> {
        return lockableDatabase.execute(false) { db ->
            val displayModeSelection = getDisplayModeSelection(displayMode)
            val outboxFolderIdOrZero = outboxFolderId ?: 0

            val query =
                """
                SELECT ${FOLDER_COLUMNS.joinToString()}, (
                    SELECT COUNT(messages.id) 
                    FROM messages 
                    WHERE messages.folder_id = folders.id 
                      AND messages.empty = 0 AND messages.deleted = 0 
                      AND (messages.read = 0 OR folders.id = ?)
                )
                FROM folders
                $displayModeSelection
                """.trimIndent()

            db.rawQuery(query, arrayOf(outboxFolderIdOrZero.toString())).use { cursor ->
                val cursorFolderAccessor = CursorFolderAccessor(cursor)
                cursor.map {
                    mapper.map(cursorFolderAccessor)
                }
            }
        }
    }

    private fun getDisplayModeSelection(displayMode: FolderMode): String {
        return when (displayMode) {
            FolderMode.ALL -> {
                ""
            }
            FolderMode.FIRST_CLASS -> {
                "WHERE display_class = '${FolderClass.FIRST_CLASS.name}'"
            }
            FolderMode.FIRST_AND_SECOND_CLASS -> {
                "WHERE display_class IN ('${FolderClass.FIRST_CLASS.name}', '${FolderClass.SECOND_CLASS.name}')"
            }
            FolderMode.NOT_SECOND_CLASS -> {
                "WHERE display_class != '${FolderClass.SECOND_CLASS.name}'"
            }
            FolderMode.NONE -> {
                throw AssertionError("Invalid folder display mode: $displayMode")
            }
        }
    }

    fun getFolderId(folderServerId: String): Long? {
        return lockableDatabase.execute(false) { db ->
            db.query(
                "folders",
                arrayOf("id"),
                "server_id = ?",
                arrayOf(folderServerId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }
    }
}

private class CursorFolderAccessor(val cursor: Cursor) : FolderDetailsAccessor {
    override val id: Long
        get() = cursor.getLong(0)

    override val name: String
        get() = cursor.getString(1)

    override val type: FolderType
        get() = cursor.getString(2).toFolderType()

    override val serverId: String
        get() = cursor.getString(3)

    override val isLocalOnly: Boolean
        get() = cursor.getInt(4) == 1

    override val isInTopGroup: Boolean
        get() = cursor.getInt(5) == 1

    override val isIntegrate: Boolean
        get() = cursor.getInt(6) == 1

    override val syncClass: FolderClass
        get() = FolderClass.valueOf(cursor.getString(7))

    override val displayClass: FolderClass
        get() = FolderClass.valueOf(cursor.getString(8))

    override val notifyClass: FolderClass
        get() = FolderClass.valueOf(cursor.getString(9))

    override val pushClass: FolderClass
        get() = FolderClass.valueOf(cursor.getString(10))

    override val messageCount: Int
        get() = cursor.getInt(11)
}

private val FOLDER_COLUMNS = arrayOf(
    "id",
    "name",
    "type",
    "server_id",
    "local_only",
    "top_group",
    "integrate",
    "poll_class",
    "display_class",
    "notify_class",
    "push_class"
)
