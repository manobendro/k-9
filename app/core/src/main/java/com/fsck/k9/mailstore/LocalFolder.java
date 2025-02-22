package com.fsck.k9.mailstore;


import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;
import com.fsck.k9.Account;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.crypto.EncryptionExtractor;
import com.fsck.k9.crypto.EncryptionResult;
import com.fsck.k9.helper.FileHelper;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.BoundaryGenerator;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.FolderClass;
import com.fsck.k9.mail.FolderType;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Multipart;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.filter.CountingOutputStream;
import com.fsck.k9.mail.internet.BinaryTempFileBody;
import com.fsck.k9.mail.internet.MimeHeader;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mail.internet.SizeAware;
import com.fsck.k9.mail.message.MessageHeaderParser;
import com.fsck.k9.mailstore.LockableDatabase.DbCallback;
import com.fsck.k9.mailstore.LockableDatabase.WrappedException;
import com.fsck.k9.message.extractors.AttachmentCounter;
import com.fsck.k9.message.extractors.AttachmentInfoExtractor;
import com.fsck.k9.message.extractors.MessageFulltextCreator;
import com.fsck.k9.message.extractors.MessagePreviewCreator;
import com.fsck.k9.message.extractors.PreviewResult;
import com.fsck.k9.message.extractors.PreviewResult.PreviewType;

import org.apache.commons.io.IOUtils;
import org.apache.james.mime4j.util.MimeUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import timber.log.Timber;


public class LocalFolder {
    private static final int MAX_BODY_SIZE_FOR_DATABASE = 16 * 1024;
    private static final long INVALID_MESSAGE_PART_ID = -1;


    private final LocalStore localStore;
    private final AttachmentInfoExtractor attachmentInfoExtractor;
    private final EncryptionExtractor encryptionExtractor = DI.get(EncryptionExtractor.class);


    private String status = null;
    private long lastChecked = 0;
    private FolderType type = FolderType.REGULAR;
    private String serverId = null;
    private String name;
    private long databaseId = -1L;
    private int visibleLimit = -1;
    private String prefId = null;

    private FolderClass displayClass = FolderClass.NO_CLASS;
    private FolderClass syncClass = FolderClass.INHERITED;
    private FolderClass pushClass = FolderClass.SECOND_CLASS;
    private FolderClass notifyClass = FolderClass.INHERITED;

    private boolean isInTopGroup = false;
    private boolean isIntegrate = false;

    private MoreMessages moreMessages = MoreMessages.UNKNOWN;
    private boolean localOnly = false;


    public LocalFolder(LocalStore localStore, String serverId) {
        this(localStore, serverId, null);
    }

    public LocalFolder(LocalStore localStore, String serverId, String name) {
        this(localStore, serverId, name, FolderType.REGULAR);
    }

    public LocalFolder(LocalStore localStore, String serverId, String name, FolderType type) {
        this.localStore = localStore;
        this.serverId = serverId;
        this.name = name;
        this.type = type;
        attachmentInfoExtractor = localStore.getAttachmentInfoExtractor();
    }

    public LocalFolder(LocalStore localStore, long databaseId) {
        super();
        this.localStore = localStore;
        this.databaseId = databaseId;
        attachmentInfoExtractor = localStore.getAttachmentInfoExtractor();
    }

    public FolderType getType() {
        return type;
    }

    public long getLastChecked() {
        return lastChecked;
    }

    public String getStatus() {
        return status;
    }

    public long getDatabaseId() {
        return databaseId;
    }

    public String getAccountUuid()
    {
        return getAccount().getUuid();
    }

    public boolean getSignatureUse() {
        return getAccount().getSignatureUse();
    }

    public void open() throws MessagingException {
        if (isOpen()) {
            return;
        }

        try {
            this.localStore.getDatabase().execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    Cursor cursor = null;
                    try {
                        String baseQuery = "SELECT " + LocalStore.GET_FOLDER_COLS + " FROM folders ";

                        if (serverId != null) {
                            cursor = db.rawQuery(baseQuery + "where folders.server_id = ?", new String[] { serverId });
                        } else {
                            cursor = db.rawQuery(baseQuery + "where folders.id = ?", new String[] { Long.toString(
                                    databaseId) });
                        }

                        if (cursor.moveToFirst() && !cursor.isNull(LocalStore.FOLDER_ID_INDEX)) {
                            open(cursor);
                        } else {
                            throw new MessagingException("LocalFolder.open(): Folder not found: " +
                                    serverId + " (" + databaseId + ")", true);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    } finally {
                        Utility.closeQuietly(cursor);
                    }
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    void open(Cursor cursor) throws MessagingException {
        databaseId = cursor.getLong(LocalStore.FOLDER_ID_INDEX);
        serverId = cursor.getString(LocalStore.FOLDER_SERVER_ID_INDEX);
        visibleLimit = cursor.getInt(LocalStore.FOLDER_VISIBLE_LIMIT_INDEX);
        status = cursor.getString(LocalStore.FOLDER_STATUS_INDEX);
        // Only want to set the local variable stored in the super class.  This class
        // does a DB update on setLastChecked
        lastChecked = cursor.getLong(LocalStore.FOLDER_LAST_CHECKED_INDEX);
        isInTopGroup = cursor.getInt(LocalStore.FOLDER_TOP_GROUP_INDEX) == 1;
        isIntegrate = cursor.getInt(LocalStore.FOLDER_INTEGRATE_INDEX) == 1;
        String noClass = FolderClass.NO_CLASS.toString();
        String displayClass = cursor.getString(LocalStore.FOLDER_DISPLAY_CLASS_INDEX);
        this.displayClass = FolderClass.valueOf((displayClass == null) ? noClass : displayClass);
        String notifyClass = cursor.getString(LocalStore.FOLDER_NOTIFY_CLASS_INDEX);
        this.notifyClass = FolderClass.valueOf((notifyClass == null) ? noClass : notifyClass);
        String pushClass = cursor.getString(LocalStore.FOLDER_PUSH_CLASS_INDEX);
        this.pushClass = FolderClass.valueOf((pushClass == null) ? noClass : pushClass);
        String syncClass = cursor.getString(LocalStore.FOLDER_SYNC_CLASS_INDEX);
        this.syncClass = FolderClass.valueOf((syncClass == null) ? noClass : syncClass);
        String moreMessagesValue = cursor.getString(LocalStore.MORE_MESSAGES_INDEX);
        moreMessages = MoreMessages.fromDatabaseName(moreMessagesValue);
        name = cursor.getString(LocalStore.FOLDER_NAME_INDEX);
        localOnly = cursor.getInt(LocalStore.LOCAL_ONLY_INDEX) == 1;
        String typeString = cursor.getString(LocalStore.TYPE_INDEX);
        type = FolderTypeConverter.fromDatabaseFolderType(typeString);
    }

    public boolean isOpen() {
        return (databaseId != -1L && name != null);
    }

    public String getServerId() {
        return serverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) throws MessagingException {
        try {
            open();

            if (name.equals(this.name)) {
                return;
            }

            this.name = name;
        } catch (MessagingException e) {
            throw new WrappedException(e);
        }
        updateFolderColumn("name", name);
    }

    public void setType(FolderType type) {
        this.type = type;
        try {
            updateFolderColumn("type", FolderTypeConverter.toDatabaseFolderType(type));
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean exists() throws MessagingException {
        return this.localStore.getDatabase().execute(false, new DbCallback<Boolean>() {
            @Override
            public Boolean doDbWork(final SQLiteDatabase db) throws WrappedException {
                Cursor cursor = null;
                try {
                    cursor = db.rawQuery("SELECT id FROM folders where id = ?",
                            new String[] { Long.toString(getDatabaseId()) });
                    if (cursor.moveToFirst()) {
                        int folderId = cursor.getInt(0);
                        return (folderId > 0);
                    }

                    return false;
                } finally {
                    Utility.closeQuietly(cursor);
                }
            }
        });
    }

    public int getMessageCount() throws MessagingException {
        try {
            return this.localStore.getDatabase().execute(false, new DbCallback<Integer>() {
                @Override
                public Integer doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open();
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    Cursor cursor = null;
                    try {
                        cursor = db.rawQuery(
                                "SELECT COUNT(id) FROM messages " +
                                "WHERE empty = 0 AND deleted = 0 and folder_id = ?",
                                new String[] { Long.toString(databaseId) });
                        cursor.moveToFirst();
                        return cursor.getInt(0);   //messagecount
                    } finally {
                        Utility.closeQuietly(cursor);
                    }
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }
    }

    public int getUnreadMessageCount() throws MessagingException {
        if (databaseId == -1L) {
            open();
        }

        try {
            return this.localStore.getDatabase().execute(false, new DbCallback<Integer>() {
                @Override
                public Integer doDbWork(final SQLiteDatabase db) throws WrappedException {
                    int unreadMessageCount = 0;
                    Cursor cursor = db.query("messages", new String[] { "COUNT(id)" },
                            "folder_id = ? AND empty = 0 AND deleted = 0 AND read=0",
                            new String[] { Long.toString(databaseId) }, null, null, null);

                    try {
                        if (cursor.moveToFirst()) {
                            unreadMessageCount = cursor.getInt(0);
                        }
                    } finally {
                        cursor.close();
                    }

                    return unreadMessageCount;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public void setLastChecked(final long lastChecked) throws MessagingException {
        try {
            open();
            this.lastChecked = lastChecked;
        } catch (MessagingException e) {
            throw new WrappedException(e);
        }
        updateFolderColumn("last_updated", lastChecked);
    }

    public int getVisibleLimit() throws MessagingException {
        open();
        return visibleLimit;
    }

    public void setVisibleLimit(final int visibleLimit) throws MessagingException {
        updateMoreMessagesOnVisibleLimitChange(visibleLimit, this.visibleLimit);

        this.visibleLimit = visibleLimit;
        updateFolderColumn("visible_limit", this.visibleLimit);
    }

    private void updateMoreMessagesOnVisibleLimitChange(int newVisibleLimit, int oldVisibleLimit)
            throws MessagingException {

        boolean growVisibleLimit = newVisibleLimit > oldVisibleLimit;
        boolean shrinkVisibleLimit = newVisibleLimit < oldVisibleLimit;
        boolean moreMessagesWereAvailable = getMoreMessages() == MoreMessages.TRUE;

        if (growVisibleLimit || (shrinkVisibleLimit && !moreMessagesWereAvailable)) {
            setMoreMessages(MoreMessages.UNKNOWN);
        }
    }

    public void setStatus(final String status) throws MessagingException {
        this.status = status;
        updateFolderColumn("status", status);
    }

    private void updateFolderColumn(final String column, final Object value) throws MessagingException {
        try {
            this.localStore.getDatabase().execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open();
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    db.execSQL("UPDATE folders SET " + column + " = ? WHERE id = ?", new Object[] { value, databaseId });
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public FolderClass getDisplayClass() {
        return displayClass;
    }

    public FolderClass getSyncClass() {
        return (FolderClass.INHERITED == syncClass) ? getDisplayClass() : syncClass;
    }

    public FolderClass getRawSyncClass() {
        return syncClass;
    }

    public FolderClass getNotifyClass() {
        return (FolderClass.INHERITED == notifyClass) ? getPushClass() : notifyClass;
    }

    public FolderClass getRawNotifyClass() {
        return notifyClass;
    }

    public FolderClass getPushClass() {
        return (FolderClass.INHERITED == pushClass) ? getSyncClass() : pushClass;
    }

    public FolderClass getRawPushClass() {
        return pushClass;
    }

    public void setDisplayClass(FolderClass displayClass) throws MessagingException {
        this.displayClass = displayClass;
        updateFolderColumn("display_class", this.displayClass.name());
    }

    public void setSyncClass(FolderClass syncClass) throws MessagingException {
        this.syncClass = syncClass;
        updateFolderColumn("poll_class", this.syncClass.name());
    }

    public void setPushClass(FolderClass pushClass) throws MessagingException {
        this.pushClass = pushClass;
        updateFolderColumn("push_class", this.pushClass.name());
    }

    public void setNotifyClass(FolderClass notifyClass) throws MessagingException {
        this.notifyClass = notifyClass;
        updateFolderColumn("notify_class", this.notifyClass.name());
    }

    public boolean isIntegrate() {
        return isIntegrate;
    }

    public void setIntegrate(boolean integrate) throws MessagingException {
        isIntegrate = integrate;
        updateFolderColumn("integrate", isIntegrate ? 1 : 0);
    }

    public boolean hasMoreMessages() {
        return moreMessages != MoreMessages.FALSE;
    }

    public MoreMessages getMoreMessages() {
        return moreMessages;
    }

    public void setMoreMessages(MoreMessages moreMessages) throws MessagingException {
        this.moreMessages = moreMessages;
        updateFolderColumn("more_messages", moreMessages.getDatabaseName());
    }

    public boolean isLocalOnly() {
        return localOnly;
    }

    public void fetch(final List<LocalMessage> messages, final FetchProfile fp, final MessageRetrievalListener<LocalMessage> listener)
    throws MessagingException {
        try {
            this.localStore.getDatabase().execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open();
                        if (fp.contains(FetchProfile.Item.BODY)) {
                            for (LocalMessage message : messages) {
                                loadMessageParts(db, message);
                            }
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }
    }

    private void loadMessageParts(SQLiteDatabase db, LocalMessage message) throws MessagingException {
        Map<Long, Part> partById = new HashMap<>();

        String[] columns = {
                "id",                   // 0
                "type",                 // 1
                "parent",               // 2
                "mime_type",            // 3
                "decoded_body_size",    // 4
                "display_name",         // 5
                "header",               // 6
                "encoding",             // 7
                "charset",              // 8
                "data_location",        // 9
                "data",                 // 10
                "preamble",             // 11
                "epilogue",             // 12
                "boundary",             // 13
                "content_id",           // 14
                "server_extra",         // 15
        };
        Cursor cursor = db.query("message_parts", columns, "root = ?",
                new String[] { String.valueOf(message.getMessagePartId()) }, null, null, "seq");
        try {
            while (cursor.moveToNext()) {
                loadMessagePart(message, partById, cursor);
            }
        } finally {
            cursor.close();
        }
    }

    private void loadMessagePart(LocalMessage message, Map<Long, Part> partById, Cursor cursor)
            throws MessagingException {

        long id = cursor.getLong(0);
        long parentId = cursor.getLong(2);
        String mimeType = cursor.getString(3);
        long size = cursor.getLong(4);
        byte[] header = cursor.getBlob(6);
        int dataLocation = cursor.getInt(9);
        String serverExtra = cursor.getString(15);
        // TODO we don't currently cache much of the part data which is computed with AttachmentInfoExtractor,
        // TODO might want to do that at a later point?
        // String displayName = cursor.getString(5);
        // int type = cursor.getInt(1);
        // boolean inlineAttachment = (type == MessagePartType.HIDDEN_ATTACHMENT);

        final Part part;
        if (id == message.getMessagePartId()) {
            part = message;
        } else {
            Part parentPart = partById.get(parentId);
            if (parentPart == null) {
                throw new IllegalStateException("Parent part not found");
            }

            String parentMimeType = parentPart.getMimeType();
            if (MimeUtility.isMultipart(parentMimeType)) {
                BodyPart bodyPart = new LocalBodyPart(getAccountUuid(), message, id, size);
                ((Multipart) parentPart.getBody()).addBodyPart(bodyPart);
                part = bodyPart;
            } else if (MimeUtility.isMessage(parentMimeType)) {
                Message innerMessage = new LocalMimeMessage(getAccountUuid(), message, id);
                parentPart.setBody(innerMessage);
                part = innerMessage;
            } else {
                throw new IllegalStateException("Parent is neither a multipart nor a message");
            }

            parseHeaderBytes(part, header);
        }
        partById.put(id, part);
        part.setServerExtra(serverExtra);

        if (MimeUtility.isMultipart(mimeType)) {
            byte[] preamble = cursor.getBlob(11);
            byte[] epilogue = cursor.getBlob(12);
            String boundary = cursor.getString(13);

            MimeMultipart multipart = new MimeMultipart(mimeType, boundary);
            part.setBody(multipart);
            multipart.setPreamble(preamble);
            multipart.setEpilogue(epilogue);
        } else if (dataLocation == DataLocation.IN_DATABASE) {
            String encoding = cursor.getString(7);
            byte[] data = cursor.getBlob(10);

            Body body = new BinaryMemoryBody(data, encoding);
            part.setBody(body);
        } else if (dataLocation == DataLocation.ON_DISK) {
            String encoding = cursor.getString(7);

            File file = localStore.getAttachmentFile(Long.toString(id));
            if (file.exists()) {
                Body body = new FileBackedBody(file, encoding);
                part.setBody(body);
            }
        }
    }

    private void parseHeaderBytes(Part part, byte[] header) throws MessagingException {
        MessageHeaderParser.parse(new ByteArrayInputStream(header), part::addRawHeader);
    }

    public String getMessageUidById(final long id) throws MessagingException {
        try {
            return this.localStore.getDatabase().execute(false, new DbCallback<String>() {
                @Override
                public String doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open();
                        Cursor cursor = null;

                        try {
                            cursor = db.rawQuery(
                                    "SELECT uid FROM messages WHERE id = ? AND folder_id = ?",
                                    new String[] { Long.toString(id), Long.toString(LocalFolder.this.databaseId) });
                            if (!cursor.moveToNext()) {
                                return null;
                            }
                            return cursor.getString(0);
                        } finally {
                            Utility.closeQuietly(cursor);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public LocalMessage getMessage(final String uid) throws MessagingException {
        try {
            return this.localStore.getDatabase().execute(false, new DbCallback<LocalMessage>() {
                @Override
                public LocalMessage doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open();
                        LocalMessage message = new LocalMessage(LocalFolder.this.localStore, uid, LocalFolder.this);
                        Cursor cursor = null;

                        try {
                            cursor = db.rawQuery(
                                    "SELECT " +
                                    LocalStore.GET_MESSAGES_COLS +
                                    "FROM messages " +
                                    "LEFT JOIN message_parts ON (message_parts.id = messages.message_part_id) " +
                                    "LEFT JOIN threads ON (threads.message_id = messages.id) " +
                                    "WHERE uid = ? AND folder_id = ?",
                                    new String[] { message.getUid(), Long.toString(databaseId) });

                            if (!cursor.moveToNext()) {
                                return null;
                            }
                            message.populateFromGetMessageCursor(cursor);
                        } finally {
                            Utility.closeQuietly(cursor);
                        }
                        return message;
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Nullable
    public LocalMessage getMessage(long messageId) throws MessagingException {
        return localStore.getDatabase().execute(false, db -> {
            open();
            LocalMessage message = new LocalMessage(localStore, messageId, LocalFolder.this);

            Cursor cursor = db.rawQuery(
                    "SELECT " +
                            LocalStore.GET_MESSAGES_COLS +
                            "FROM messages " +
                            "LEFT JOIN message_parts ON (message_parts.id = messages.message_part_id) " +
                            "LEFT JOIN threads ON (threads.message_id = messages.id) " +
                            "WHERE messages.id = ? AND folder_id = ?",
                    new String[] { Long.toString(messageId), Long.toString(databaseId) });
            try {
                if (cursor.moveToNext()) {
                    message.populateFromGetMessageCursor(cursor);
                } else {
                    return null;
                }
            } finally {
                Utility.closeQuietly(cursor);
            }

            return message;
        });
    }

    public List<LocalMessage> getMessages(MessageRetrievalListener<LocalMessage> listener) throws MessagingException {
        return getMessages(listener, true);
    }

    public List<LocalMessage> getMessages(final MessageRetrievalListener<LocalMessage> listener,
            final boolean includeDeleted) throws MessagingException {
        try {
            return  localStore.getDatabase().execute(false, new DbCallback<List<LocalMessage>>() {
                @Override
                public List<LocalMessage> doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open();
                        return LocalFolder.this.localStore.getMessages(listener, LocalFolder.this,
                                "SELECT " + LocalStore.GET_MESSAGES_COLS +
                                "FROM messages " +
                                "LEFT JOIN message_parts ON (message_parts.id = messages.message_part_id) " +
                                "LEFT JOIN threads ON (threads.message_id = messages.id) " +
                                "WHERE empty = 0 AND " +
                                (includeDeleted ? "" : "deleted = 0 AND ") +
                                "folder_id = ? ORDER BY date DESC",
                                new String[] { Long.toString(databaseId) });
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public List<String> getAllMessageUids() throws MessagingException {
        try {
            return  localStore.getDatabase().execute(false, new DbCallback<List<String>>() {
                @Override
                public List<String> doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    Cursor cursor = null;
                    ArrayList<String> result = new ArrayList<>();

                    try {
                        open();

                        cursor = db.rawQuery(
                                "SELECT uid " +
                                    "FROM messages " +
                                        "WHERE empty = 0 AND deleted = 0 AND " +
                                        "folder_id = ? ORDER BY date DESC",
                                new String[] { Long.toString(databaseId) });

                        while (cursor.moveToNext()) {
                            String uid = cursor.getString(0);
                            result.add(uid);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    } finally {
                        Utility.closeQuietly(cursor);
                    }

                    return result;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public List<LocalMessage> getMessagesByUids(@NonNull List<String> uids) throws MessagingException {
        open();
        List<LocalMessage> messages = new ArrayList<>();
        for (String uid : uids) {
            LocalMessage message = getMessage(uid);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    public List<LocalMessage> getMessagesByReference(@NonNull List<MessageReference> messageReferences)
            throws MessagingException {
        open();

        String accountUuid = getAccountUuid();
        long folderId = getDatabaseId();

        List<LocalMessage> messages = new ArrayList<>();
        for (MessageReference messageReference : messageReferences) {
            if (!accountUuid.equals(messageReference.getAccountUuid())) {
                throw new IllegalArgumentException("all message references must belong to this Account!");
            }
            if (folderId != messageReference.getFolderId()) {
                throw new IllegalArgumentException("all message references must belong to this LocalFolder!");
            }

            LocalMessage message = getMessage(messageReference.getUid());
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    public Map<String, String> copyMessages(List<LocalMessage> msgs, LocalFolder folder) throws MessagingException {
        return folder.appendMessages(msgs, true);
    }

    /**
     * The method differs slightly from the contract; If an incoming message already has a uid
     * assigned and it matches the uid of an existing message then this message will replace the
     * old message. It is implemented as a delete/insert. This functionality is used in saving
     * of drafts and re-synchronization of updated server messages.
     *
     * NOTE that although this method is located in the LocalStore class, it is not guaranteed
     * that the messages supplied as parameters are actually {@link LocalMessage} instances (in
     * fact, in most cases, they are not). Therefore, if you want to make local changes only to a
     * message, retrieve the appropriate local message instance first (if it already exists).
     */
    public Map<String, String> appendMessages(List<Message> messages) throws MessagingException {
        return appendMessages(messages, false);
    }

    public void destroyMessages(final List<LocalMessage> messages) {
        try {
            this.localStore.getDatabase().execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    for (LocalMessage message : messages) {
                        try {
                            message.destroy();
                        } catch (MessagingException e) {
                            throw new WrappedException(e);
                        }
                    }
                    return null;
                }
            });
        } catch (MessagingException e) {
            throw new WrappedException(e);
        }
    }

    private ThreadInfo getThreadInfo(SQLiteDatabase db, String messageId, boolean onlyEmpty) {
        if (messageId == null) {
            return null;
        }

        String sql = "SELECT t.id, t.message_id, t.root, t.parent " +
                "FROM messages m " +
                "LEFT JOIN threads t ON (t.message_id = m.id) " +
                "WHERE m.folder_id = ? AND m.message_id = ? " +
                ((onlyEmpty) ? "AND m.empty = 1 " : "") +
                "ORDER BY m.id LIMIT 1";
        String[] selectionArgs = { Long.toString(databaseId), messageId };
        Cursor cursor = db.rawQuery(sql, selectionArgs);

        if (cursor != null) {
            try {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    long threadId = cursor.getLong(0);
                    long msgId = cursor.getLong(1);
                    long rootId = (cursor.isNull(2)) ? -1 : cursor.getLong(2);
                    long parentId = (cursor.isNull(3)) ? -1 : cursor.getLong(3);

                    return new ThreadInfo(threadId, msgId, messageId, rootId, parentId);
                }
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * The method differs slightly from the contract; If an incoming message already has a uid
     * assigned and it matches the uid of an existing message then this message will replace
     * the old message. This functionality is used in saving of drafts and re-synchronization
     * of updated server messages.
     *
     * NOTE that although this method is located in the LocalStore class, it is not guaranteed
     * that the messages supplied as parameters are actually {@link LocalMessage} instances (in
     * fact, in most cases, they are not). Therefore, if you want to make local changes only to a
     * message, retrieve the appropriate local message instance first (if it already exists).
     * @return uidMap of srcUids -> destUids
     */
    private Map<String, String> appendMessages(final List<? extends Message> messages, final boolean copy)
            throws MessagingException {
        open();
        try {
            final Map<String, String> uidMap = new HashMap<>();
            this.localStore.getDatabase().execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        for (Message message : messages) {
                            saveMessage(db, message, copy, uidMap);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });

            this.localStore.notifyChange();

            return uidMap;
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }
    }

    private void saveMessage(SQLiteDatabase db, Message message, boolean copy, Map<String, String> uidMap)
            throws MessagingException {
        if (!(message instanceof MimeMessage)) {
            throw new Error("LocalStore can only store Messages that extend MimeMessage");
        }

        long oldMessageId = -1;
        String uid = message.getUid();
        boolean shouldCreateNewMessage = uid == null || copy;
        if (shouldCreateNewMessage) {
            String randomLocalUid = K9.LOCAL_UID_PREFIX + UUID.randomUUID().toString();

            if (copy) {
                // Save mapping: source UID -> target UID
                uidMap.put(uid, randomLocalUid);
            } else {
                // Modify the Message instance to reference the new UID
                message.setUid(randomLocalUid);
            }

            // The message will be saved with the newly generated UID
            uid = randomLocalUid;
        } else {
            LocalMessage oldMessage = getMessage(uid);

            if (oldMessage != null) {
                oldMessageId = oldMessage.getDatabaseId();

                long oldRootMessagePartId = oldMessage.getMessagePartId();
                deleteMessagePartsAndDataFromDisk(oldRootMessagePartId);
            }
        }

        long rootId = -1;
        long parentId = -1;
        long msgId;

        if (oldMessageId == -1) {
            // This is a new message. Do the message threading.
            ThreadInfo threadInfo = doMessageThreading(db, message);
            oldMessageId = threadInfo.msgId;
            rootId = threadInfo.rootId;
            parentId = threadInfo.parentId;
        }

        try {
            String encryptionType;
            PreviewResult previewResult;
            int attachmentCount;
            String fulltext;
            ContentValues extraContentValues;

            EncryptionResult encryptionResult = encryptionExtractor.extractEncryption(message);
            if (encryptionResult != null) {
                encryptionType = encryptionResult.getEncryptionType();
                previewResult = encryptionResult.getPreviewResult();
                attachmentCount = encryptionResult.getAttachmentCount();
                fulltext = encryptionResult.getTextForSearchIndex();
                extraContentValues = encryptionResult.getExtraContentValues();
            } else {
                MessagePreviewCreator previewCreator = localStore.getMessagePreviewCreator();
                MessageFulltextCreator fulltextCreator = localStore.getMessageFulltextCreator();
                AttachmentCounter attachmentCounter = localStore.getAttachmentCounter();

                encryptionType = null;
                previewResult = previewCreator.createPreview(message);
                attachmentCount = attachmentCounter.getAttachmentCount(message);
                fulltext = fulltextCreator.createFulltext(message);
                extraContentValues = null;
            }

            PreviewType previewType = previewResult.getPreviewType();
            DatabasePreviewType databasePreviewType = DatabasePreviewType.fromPreviewType(previewType);

            long rootMessagePartId = saveMessageParts(db, message);

            ContentValues cv = new ContentValues();
            cv.put("message_part_id", rootMessagePartId);
            cv.put("uid", uid);
            cv.put("subject", message.getSubject());
            cv.put("sender_list", Address.pack(message.getFrom()));
            cv.put("date", message.getSentDate() == null
                    ? System.currentTimeMillis() : message.getSentDate().getTime());
            cv.put("flags", LocalStore.serializeFlags(message.getFlags()));
            cv.put("deleted", message.isSet(Flag.DELETED) ? 1 : 0);
            cv.put("read", message.isSet(Flag.SEEN) ? 1 : 0);
            cv.put("flagged", message.isSet(Flag.FLAGGED) ? 1 : 0);
            cv.put("answered", message.isSet(Flag.ANSWERED) ? 1 : 0);
            cv.put("forwarded", message.isSet(Flag.FORWARDED) ? 1 : 0);
            cv.put("folder_id", databaseId);
            cv.put("to_list", Address.pack(message.getRecipients(RecipientType.TO)));
            cv.put("cc_list", Address.pack(message.getRecipients(RecipientType.CC)));
            cv.put("bcc_list", Address.pack(message.getRecipients(RecipientType.BCC)));
            cv.put("reply_to_list", Address.pack(message.getReplyTo()));
            cv.put("attachment_count", attachmentCount);
            cv.put("internal_date", message.getInternalDate() == null
                    ? System.currentTimeMillis() : message.getInternalDate().getTime());
            cv.put("mime_type", message.getMimeType());
            cv.put("empty", 0);
            cv.put("encryption_type", encryptionType);

            cv.put("preview_type", databasePreviewType.getDatabaseValue());
            if (previewResult.isPreviewTextAvailable()) {
                cv.put("preview", previewResult.getPreviewText());
            } else {
                cv.putNull("preview");
            }

            String messageId = message.getMessageId();
            if (messageId != null) {
                cv.put("message_id", messageId);
            }

            if (extraContentValues != null) {
                cv.putAll(extraContentValues);
            }

            if (oldMessageId == -1) {
                msgId = db.insert("messages", "uid", cv);

                // Create entry in 'threads' table
                cv.clear();
                cv.put("message_id", msgId);

                if (rootId != -1) {
                    cv.put("root", rootId);
                }
                if (parentId != -1) {
                    cv.put("parent", parentId);
                }

                db.insert("threads", null, cv);
            } else {
                msgId = oldMessageId;
                db.update("messages", cv, "id = ?", new String[] { Long.toString(oldMessageId) });
            }

            if (fulltext != null) {
                cv.clear();
                cv.put("docid", msgId);
                cv.put("fulltext", fulltext);
                db.replace("messages_fulltext", null, cv);
            }
        } catch (Exception e) {
            throw new MessagingException("Error appending message: " + message.getSubject(), e);
        }
    }

    private long saveMessageParts(SQLiteDatabase db, Message message) throws IOException, MessagingException {
        long rootMessagePartId = saveMessagePart(db, new PartContainer(-1, message), -1, 0);

        Stack<PartContainer> partsToSave = new Stack<>();
        addChildrenToStack(partsToSave, message, rootMessagePartId);

        int order = 1;
        while (!partsToSave.isEmpty()) {
            PartContainer partContainer = partsToSave.pop();
            long messagePartId = saveMessagePart(db, partContainer, rootMessagePartId, order);
            order++;

            addChildrenToStack(partsToSave, partContainer.part, messagePartId);
        }

        return rootMessagePartId;
    }

    private long saveMessagePart(SQLiteDatabase db, PartContainer partContainer, long rootMessagePartId, int order)
            throws IOException, MessagingException {

        Part part = partContainer.part;

        ContentValues cv = new ContentValues();
        if (rootMessagePartId != -1) {
            cv.put("root", rootMessagePartId);
        }
        cv.put("parent", partContainer.parent);
        cv.put("seq", order);
        cv.put("server_extra", part.getServerExtra());

        return updateOrInsertMessagePart(db, cv, part, INVALID_MESSAGE_PART_ID);
    }

    private void moveTemporaryFile(File tempFile, String messagePartId) throws IOException {
        File destinationFile = localStore.getAttachmentFile(messagePartId);
        FileHelper.renameOrMoveByCopying(tempFile, destinationFile);
    }

    private long updateOrInsertMessagePart(SQLiteDatabase db, ContentValues cv, Part part, long existingMessagePartId)
            throws IOException, MessagingException {
        byte[] headerBytes = getHeaderBytes(part);

        cv.put("mime_type", part.getMimeType());
        cv.put("header", headerBytes);
        cv.put("type", MessagePartType.UNKNOWN);

        File file = null;
        Body body = part.getBody();
        if (body instanceof Multipart) {
            multipartToContentValues(cv, (Multipart) body);
        } else if (body == null) {
            missingPartToContentValues(cv, part);
        } else if (body instanceof Message) {
            messageMarkerToContentValues(cv);
        } else {
            file = leafPartToContentValues(cv, part, body);
        }

        long messagePartId;
        if (existingMessagePartId != INVALID_MESSAGE_PART_ID) {
            messagePartId = existingMessagePartId;
            db.update("message_parts", cv, "id = ?", new String[] { Long.toString(messagePartId) });
        } else {
            messagePartId = db.insertOrThrow("message_parts", null, cv);
        }

        if (file != null) {
            moveTemporaryFile(file, Long.toString(messagePartId));
        }

        return messagePartId;
    }

    private void multipartToContentValues(ContentValues cv, Multipart multipart) {
        cv.put("data_location", DataLocation.CHILD_PART_CONTAINS_DATA);
        cv.put("preamble", multipart.getPreamble());
        cv.put("epilogue", multipart.getEpilogue());
        cv.put("boundary", multipart.getBoundary());
    }

    private void missingPartToContentValues(ContentValues cv, Part part) throws MessagingException {
        AttachmentViewInfo attachment = attachmentInfoExtractor.extractAttachmentInfoForDatabase(part);
        cv.put("display_name", attachment.displayName);
        cv.put("data_location", DataLocation.MISSING);
        cv.put("decoded_body_size", attachment.size);

        if (MimeUtility.isMultipart(part.getMimeType())) {
            cv.put("boundary", BoundaryGenerator.getInstance().generateBoundary());
        }
    }

    private void messageMarkerToContentValues(ContentValues cv) {
        cv.put("data_location", DataLocation.CHILD_PART_CONTAINS_DATA);
    }

    private File leafPartToContentValues(ContentValues cv, Part part, Body body)
            throws MessagingException, IOException {
        AttachmentViewInfo attachment = attachmentInfoExtractor.extractAttachmentInfoForDatabase(part);
        cv.put("display_name", attachment.displayName);

        String encoding = getTransferEncoding(part);

        if (!(body instanceof SizeAware)) {
            throw new IllegalStateException("Body needs to implement SizeAware");
        }

        SizeAware sizeAwareBody = (SizeAware) body;
        long fileSize = sizeAwareBody.getSize();

        File file = null;
        int dataLocation;
        if (fileSize > MAX_BODY_SIZE_FOR_DATABASE) {
            dataLocation = DataLocation.ON_DISK;

            file = writeBodyToDiskIfNecessary(part);

            long size = decodeAndCountBytes(file, encoding, fileSize);
            cv.put("decoded_body_size", size);
        } else {
            dataLocation = DataLocation.IN_DATABASE;

            byte[] bodyData = getBodyBytes(body);
            cv.put("data", bodyData);

            long size = decodeAndCountBytes(bodyData, encoding, bodyData.length);
            cv.put("decoded_body_size", size);
        }
        cv.put("data_location", dataLocation);
        cv.put("encoding", encoding);
        cv.put("content_id", part.getContentId());

        return file;
    }

    private File writeBodyToDiskIfNecessary(Part part) throws MessagingException, IOException {
        Body body = part.getBody();
        if (body instanceof BinaryTempFileBody) {
            return ((BinaryTempFileBody) body).getFile();
        } else {
            return writeBodyToDisk(body);
        }
    }

    private File writeBodyToDisk(Body body) throws IOException, MessagingException {
        File file = File.createTempFile("body", null, BinaryTempFileBody.getTempDirectory());
        OutputStream out = new FileOutputStream(file);
        try {
            body.writeTo(out);
        } finally {
            out.close();
        }

        return file;
    }

    private long decodeAndCountBytes(byte[] bodyData, String encoding, long fallbackValue) {
        ByteArrayInputStream rawInputStream = new ByteArrayInputStream(bodyData);
        return decodeAndCountBytes(rawInputStream, encoding, fallbackValue);
    }

    private long decodeAndCountBytes(File file, String encoding, long fallbackValue)
            throws IOException {
        InputStream inputStream = new FileInputStream(file);
        try {
            return decodeAndCountBytes(inputStream, encoding, fallbackValue);
        } finally {
            inputStream.close();
        }
    }

    private long decodeAndCountBytes(InputStream rawInputStream, String encoding, long fallbackValue) {
        InputStream decodingInputStream = localStore.getDecodingInputStream(rawInputStream, encoding);
        try {
            CountingOutputStream countingOutputStream = new CountingOutputStream();
            try {
                IOUtils.copy(decodingInputStream, countingOutputStream);

                return countingOutputStream.getCount();
            } catch (IOException e) {
                return fallbackValue;
            }
        } finally {
            try {
                decodingInputStream.close();
            } catch (IOException e) { /* ignore */ }
        }
    }

    private byte[] getHeaderBytes(Part part) throws IOException, MessagingException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        part.writeHeaderTo(output);
        return output.toByteArray();
    }

    private byte[] getBodyBytes(Body body) throws IOException, MessagingException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.writeTo(output);
        return output.toByteArray();
    }

    private String getTransferEncoding(Part part) {
        String[] contentTransferEncoding = part.getHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING);
        if (contentTransferEncoding.length > 0) {
            return contentTransferEncoding[0].toLowerCase(Locale.US);
        }

        return MimeUtil.ENC_7BIT;
    }

    private void addChildrenToStack(Stack<PartContainer> stack, Part part, long parentMessageId) {
        Body body = part.getBody();
        if (body instanceof Multipart) {
            Multipart multipart = (Multipart) body;
            for (int i = multipart.getCount() - 1; i >= 0; i--) {
                BodyPart childPart = multipart.getBodyPart(i);
                stack.push(new PartContainer(parentMessageId, childPart));
            }
        } else if (body instanceof Message) {
            Message innerMessage = (Message) body;
            stack.push(new PartContainer(parentMessageId, innerMessage));
        }
    }

    private static class PartContainer {
        public final long parent;
        public final Part part;

        PartContainer(long parent, Part part) {
            this.parent = parent;
            this.part = part;
        }
    }

    public void addPartToMessage(final LocalMessage message, final Part part) throws MessagingException {
        open();

        localStore.getDatabase().execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                long messagePartId;

                Cursor cursor = db.query("message_parts", new String[] { "id" }, "root = ? AND server_extra = ?",
                        new String[] { Long.toString(message.getMessagePartId()), part.getServerExtra() },
                        null, null, null);
                try {
                    if (!cursor.moveToFirst()) {
                        throw new IllegalStateException("Message part not found");
                    }

                    messagePartId = cursor.getLong(0);
                } finally {
                    cursor.close();
                }

                try {
                    updateOrInsertMessagePart(db, new ContentValues(), part, messagePartId);
                } catch (Exception e) {
                    Timber.e(e, "Error writing message part");
                }

                return null;
            }
        });

        localStore.notifyChange();
    }

    /**
     * Changes the stored uid of the given message (using it's internal id as a key) to
     * the uid in the message.
     */
    public void changeUid(final LocalMessage message) throws MessagingException {
        open();
        final ContentValues cv = new ContentValues();
        cv.put("uid", message.getUid());
        this.localStore.getDatabase().execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                db.update("messages", cv, "id = ?", new String[]
                        { Long.toString(message.getDatabaseId()) });
                return null;
            }
        });

        //TODO: remove this once the UI code exclusively uses the database id
        this.localStore.notifyChange();
    }

    public void setFlags(final List<LocalMessage> messages, final Set<Flag> flags, final boolean value)
    throws MessagingException {
        open();

        // Use one transaction to set all flags
        try {
            this.localStore.getDatabase().execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException,
                        UnavailableStorageException {

                    for (LocalMessage message : messages) {
                        try {
                            message.setFlags(flags, value);
                        } catch (MessagingException e) {
                            Timber.e(e, "Something went wrong while setting flag");
                        }
                    }

                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public void setFlags(final Set<Flag> flags, boolean value)
    throws MessagingException {
        open();
        for (LocalMessage message : getMessages(null)) {
            message.setFlags(flags, value);
        }
    }

    public void clearAllMessages() throws MessagingException {
        final String[] folderIdArg = new String[] { Long.toString(databaseId) };

        open();

        try {
            this.localStore.getDatabase().execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        Cursor cursor = db.query("messages", new String[] { "message_part_id" },
                                "folder_id = ? AND empty = 0",
                                folderIdArg, null, null, null);
                        try {
                            while (cursor.moveToNext()) {
                                long messagePartId = cursor.getLong(0);
                                deleteMessageDataFromDisk(messagePartId);
                            }
                        } finally {
                            cursor.close();
                        }

                        db.execSQL("DELETE FROM threads WHERE message_id IN " +
                                "(SELECT id FROM messages WHERE folder_id = ?)", folderIdArg);
                        db.execSQL("DELETE FROM messages WHERE folder_id = ?", folderIdArg);

                        setMoreMessages(MoreMessages.UNKNOWN);

                        return null;
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }

        this.localStore.notifyChange();

        setLastChecked(0);
        setVisibleLimit(getAccount().getDisplayCount());
    }

    public void destroyLocalOnlyMessages() throws MessagingException {
        destroyMessages("uid LIKE '" + K9.LOCAL_UID_PREFIX + "%'");
    }

    public void destroyDeletedMessages() throws MessagingException {
        destroyMessages("empty = 0 AND deleted = 1");
    }

    private void destroyMessages(String messageSelection) throws MessagingException {
        localStore.getDatabase().execute(false, (DbCallback<Void>) db -> {
            try (Cursor cursor = db.query(
                    "messages",
                    new String[] { "id", "message_part_id", "message_id" },
                    "folder_id = ? AND " + messageSelection,
                    new String[] { Long.toString(databaseId) },
                    null,
                    null,
                    null)
            ) {
                while (cursor.moveToNext()) {
                    long messageId = cursor.getLong(0);
                    long messagePartId = cursor.getLong(1);
                    String messageIdHeader = cursor.getString(2);
                    destroyMessage(messageId, messagePartId, messageIdHeader);
                }
            }

            compactFulltextEntries(db);

            return null;
        });
    }

    public void delete() throws MessagingException {
        try {
            this.localStore.getDatabase().execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        // We need to open the folder first to make sure we've got its id
                        open();
                        List<LocalMessage> messages = getMessages(null);
                        for (LocalMessage message : messages) {
                            deleteMessageDataFromDisk(message.getMessagePartId());
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    db.execSQL("DELETE FROM folders WHERE id = ?", new Object[]
                               { Long.toString(databaseId), });
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof LocalFolder) {
            return ((LocalFolder)o).databaseId == databaseId;
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        long value = databaseId;
        return (int) (value ^ (value >>> 32));
    }

    void destroyMessage(LocalMessage localMessage) throws MessagingException {
        destroyMessage(localMessage.getDatabaseId(), localMessage.getMessagePartId(), localMessage.getMessageId());
    }

    private void destroyMessage(final long messageId, final long messagePartId, final String messageIdHeader)
            throws MessagingException {
        try {
            localStore.getDatabase().execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException,
                        UnavailableStorageException {
                    try {
                        deleteMessagePartsAndDataFromDisk(messagePartId);

                        deleteFulltextIndexEntry(db, messageId);

                        if (hasThreadChildren(db, messageId)) {
                            // This message has children in the thread structure so we need to
                            // make it an empty message.
                            ContentValues cv = new ContentValues();
                            cv.put("id", messageId);
                            cv.put("folder_id", getDatabaseId());
                            cv.put("deleted", 0);
                            cv.put("message_id", messageIdHeader);
                            cv.put("empty", 1);

                            db.replace("messages", null, cv);

                            // Nothing else to do
                            return null;
                        }

                        // Get the message ID of the parent message if it's empty
                        long currentId = getEmptyThreadParent(db, messageId);

                        // Delete the placeholder message
                        deleteMessageRow(db, messageId);

                        /*
                         * Walk the thread tree to delete all empty parents without children
                         */

                        while (currentId != -1) {
                            if (hasThreadChildren(db, currentId)) {
                                // We made sure there are no empty leaf nodes and can stop now.
                                break;
                            }

                            // Get ID of the (empty) parent for the next iteration
                            long newId = getEmptyThreadParent(db, currentId);

                            // Delete the empty message
                            deleteMessageRow(db, currentId);

                            currentId = newId;
                        }

                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }

        localStore.notifyChange();
    }

    /**
     * Check whether or not a message has child messages in the thread structure.
     *
     * @param db
     *         {@link SQLiteDatabase} instance to access the database.
     * @param messageId
     *         The database ID of the message to get the children for.
     *
     * @return {@code true} if the message has children. {@code false} otherwise.
     */
    private boolean hasThreadChildren(SQLiteDatabase db, long messageId) {
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(t2.id) " +
                        "FROM threads t1 " +
                        "JOIN threads t2 ON (t2.parent = t1.id) " +
                        "WHERE t1.message_id = ?",
                new String[] { Long.toString(messageId) });

        try {
            return (cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > 0L);
        } finally {
            cursor.close();
        }
    }

    /**
     * Get ID of the the given message's parent if the parent is an empty message.
     *
     * @param db
     *         {@link SQLiteDatabase} instance to access the database.
     * @param messageId
     *         The database ID of the message to get the parent for.
     *
     * @return Message ID of the parent message if there exists a parent and it is empty.
     *         Otherwise {@code -1}.
     */
    private long getEmptyThreadParent(SQLiteDatabase db, long messageId) {
        Cursor cursor = db.rawQuery(
                "SELECT m.id " +
                        "FROM threads t1 " +
                        "JOIN threads t2 ON (t1.parent = t2.id) " +
                        "LEFT JOIN messages m ON (t2.message_id = m.id) " +
                        "WHERE t1.message_id = ? AND m.empty = 1",
                new String[] { Long.toString(messageId) });

        try {
            return (cursor.moveToFirst() && !cursor.isNull(0)) ? cursor.getLong(0) : -1;
        } finally {
            cursor.close();
        }
    }

    /**
     * Delete a message from the 'messages' and 'threads' tables.
     *
     * @param db
     *         {@link SQLiteDatabase} instance to access the database.
     * @param messageId
     *         The database ID of the message to delete.
     */
    private void deleteMessageRow(SQLiteDatabase db, long messageId) {
        String[] idArg = { Long.toString(messageId) };

        // Delete the message
        db.delete("messages", "id = ?", idArg);

        // Delete row in 'threads' table
        // TODO: create trigger for 'messages' table to get rid of the row in 'threads' table
        db.delete("threads", "message_id = ?", idArg);
    }

    void deleteFulltextIndexEntry(SQLiteDatabase db, long messageId) {
        String[] idArg = { Long.toString(messageId) };
        db.delete("messages_fulltext", "docid = ?", idArg);
    }

    void compactFulltextEntries(SQLiteDatabase db) {
        db.execSQL("INSERT INTO messages_fulltext(messages_fulltext) VALUES('optimize')");
    }

    void deleteMessagePartsAndDataFromDisk(final long rootMessagePartId) throws MessagingException {
        deleteMessageDataFromDisk(rootMessagePartId);
        deleteMessageParts(rootMessagePartId);
    }

    private void deleteMessageParts(final long rootMessagePartId) throws MessagingException {
        localStore.getDatabase().execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                db.delete("message_parts", "root = ?", new String[] { Long.toString(rootMessagePartId) });
                return null;
            }
        });
    }

    private void deleteMessageDataFromDisk(final long rootMessagePartId) throws MessagingException {
        localStore.getDatabase().execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                deleteMessagePartsFromDisk(db, rootMessagePartId);
                return null;
            }
        });
    }

    private void deleteMessagePartsFromDisk(SQLiteDatabase db, long rootMessagePartId) {
        Cursor cursor = db.query("message_parts", new String[] { "id" },
                "root = ? AND data_location = " + DataLocation.ON_DISK,
                new String[] { Long.toString(rootMessagePartId) }, null, null, null);
        try {
            while (cursor.moveToNext()) {
                String messagePartId = cursor.getString(0);
                File file = localStore.getAttachmentFile(messagePartId);
                if (file.exists()) {
                    if (!file.delete() && K9.isDebugLoggingEnabled()) {
                        Timber.d("Couldn't delete message part file: %s", file.getAbsolutePath());
                    }
                }
            }
        } finally {
            cursor.close();
        }
    }

    public boolean isInTopGroup() {
        return isInTopGroup;
    }

    public void setInTopGroup(boolean inTopGroup) throws MessagingException {
        isInTopGroup = inTopGroup;
        updateFolderColumn("top_group", isInTopGroup ? 1 : 0);
    }

    public ThreadInfo doMessageThreading(SQLiteDatabase db, Message message) {
        long rootId = -1;
        long parentId = -1;

        String messageId = message.getMessageId();

        // If there's already an empty message in the database, update that
        ThreadInfo msgThreadInfo = getThreadInfo(db, messageId, true);

        // Get the message IDs from the "References" header line
        String[] referencesArray = message.getHeader("References");
        List<String> messageIds = null;
        if (referencesArray.length > 0) {
            messageIds = Utility.extractMessageIds(referencesArray[0]);
        }

        // Append the first message ID from the "In-Reply-To" header line
        String[] inReplyToArray = message.getHeader("In-Reply-To");
        String inReplyTo;
        if (inReplyToArray.length > 0) {
            inReplyTo = Utility.extractMessageId(inReplyToArray[0]);
            if (inReplyTo != null) {
                if (messageIds == null) {
                    messageIds = new ArrayList<>(1);
                    messageIds.add(inReplyTo);
                } else if (!messageIds.contains(inReplyTo)) {
                    messageIds.add(inReplyTo);
                }
            }
        }

        if (messageIds == null) {
            // This is not a reply, nothing to do for us.
            return (msgThreadInfo != null) ?
                    msgThreadInfo : new ThreadInfo(-1, -1, messageId, -1, -1);
        }

        for (String reference : messageIds) {
            ThreadInfo threadInfo = getThreadInfo(db, reference, false);

            if (threadInfo == null) {
                // Create placeholder message in 'messages' table
                ContentValues cv = new ContentValues();
                cv.put("message_id", reference);
                cv.put("folder_id", databaseId);
                cv.put("empty", 1);

                long newMsgId = db.insert("messages", null, cv);

                // Create entry in 'threads' table
                cv.clear();
                cv.put("message_id", newMsgId);
                if (rootId != -1) {
                    cv.put("root", rootId);
                }
                if (parentId != -1) {
                    cv.put("parent", parentId);
                }

                parentId = db.insert("threads", null, cv);
                if (rootId == -1) {
                    rootId = parentId;
                }
            } else {
                if (rootId != -1 && threadInfo.rootId == -1 && rootId != threadInfo.threadId) {
                    // We found an existing root container that is not
                    // the root of our current path (References).
                    // Connect it to the current parent.

                    // Let all children know who's the new root
                    ContentValues cv = new ContentValues();
                    cv.put("root", rootId);
                    db.update("threads", cv, "root = ?",
                            new String[] { Long.toString(threadInfo.threadId) });

                    // Connect the message to the current parent
                    cv.put("parent", parentId);
                    db.update("threads", cv, "id = ?",
                            new String[] { Long.toString(threadInfo.threadId) });
                } else {
                    rootId = (threadInfo.rootId == -1) ?
                            threadInfo.threadId : threadInfo.rootId;
                }
                parentId = threadInfo.threadId;
            }
        }

        //TODO: set in-reply-to "link" even if one already exists

        long threadId;
        long msgId;
        if (msgThreadInfo != null) {
            threadId = msgThreadInfo.threadId;
            msgId = msgThreadInfo.msgId;
        } else {
            threadId = -1;
            msgId = -1;
        }

        return new ThreadInfo(threadId, msgId, messageId, rootId, parentId);
    }

    public List<String> extractNewMessages(final List<String> messageServerIds)
            throws MessagingException {

        try {
            return this.localStore.getDatabase().execute(false, new DbCallback<List<String>>() {
                @Override
                public List<String> doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open();
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }

                    List<String> result = new ArrayList<>();

                    List<String> selectionArgs = new ArrayList<>();
                    Set<String> existingMessages = new HashSet<>();
                    int start = 0;

                    while (start < messageServerIds.size()) {
                        StringBuilder selection = new StringBuilder();

                        selection.append("folder_id = ? AND UID IN (");
                        selectionArgs.add(Long.toString(databaseId));

                        int count = Math.min(messageServerIds.size() - start, LocalStore.UID_CHECK_BATCH_SIZE);

                        for (int i = start, end = start + count; i < end; i++) {
                            if (i > start) {
                                selection.append(",?");
                            } else {
                                selection.append("?");
                            }

                            selectionArgs.add(messageServerIds.get(i));
                        }

                        selection.append(")");

                        Cursor cursor = db.query("messages", LocalStore.UID_CHECK_PROJECTION,
                                selection.toString(), selectionArgs.toArray(LocalStore.EMPTY_STRING_ARRAY),
                                null, null, null);

                        try {
                            while (cursor.moveToNext()) {
                                String uid = cursor.getString(0);
                                existingMessages.add(uid);
                            }
                        } finally {
                            Utility.closeQuietly(cursor);
                        }

                        for (int i = start, end = start + count; i < end; i++) {
                            String messageServerId = messageServerIds.get(i);
                            if (!existingMessages.contains(messageServerId)) {
                                result.add(messageServerId);
                            }
                        }

                        existingMessages.clear();
                        selectionArgs.clear();
                        start += count;
                    }

                    return result;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    private Account getAccount() {
        return localStore.getAccount();
    }

    // Note: The contents of the 'message_parts' table depend on these values.
    // TODO currently unused, might be for caching at a later point
    private static class MessagePartType {
        static final int UNKNOWN = 0;
        static final int ALTERNATIVE_PLAIN = 1;
        static final int ALTERNATIVE_HTML = 2;
        static final int TEXT = 3;
        static final int RELATED = 4;
        static final int ATTACHMENT = 5;
        static final int HIDDEN_ATTACHMENT = 6;
    }

    // Note: The contents of the 'message_parts' table depend on these values.
    static class DataLocation {
        static final int MISSING = 0;
        static final int IN_DATABASE = 1;
        static final int ON_DISK = 2;
        static final int CHILD_PART_CONTAINS_DATA = 3;
    }

    public enum MoreMessages {
        UNKNOWN("unknown"),
        FALSE("false"),
        TRUE("true");

        private final String databaseName;

        MoreMessages(String databaseName) {
            this.databaseName = databaseName;
        }

        public static MoreMessages fromDatabaseName(String databaseName) {
            for (MoreMessages value : MoreMessages.values()) {
                if (value.databaseName.equals(databaseName)) {
                    return value;
                }
            }

            throw new IllegalArgumentException("Unknown value: " + databaseName);
        }

        public String getDatabaseName() {
            return databaseName;
        }
    }

    public static boolean isModeMismatch(Account.FolderMode aMode, FolderClass fMode) {
        return aMode == Account.FolderMode.NONE
                || (aMode == Account.FolderMode.FIRST_CLASS &&
                fMode != FolderClass.FIRST_CLASS)
                || (aMode == Account.FolderMode.FIRST_AND_SECOND_CLASS &&
                fMode != FolderClass.FIRST_CLASS &&
                fMode != FolderClass.SECOND_CLASS)
                || (aMode == Account.FolderMode.NOT_SECOND_CLASS &&
                fMode == FolderClass.SECOND_CLASS);
    }
}
