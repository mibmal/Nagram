package xyz.nextalone.nagram.helper

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import xyz.nextalone.nagram.NaConfig
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Personal build: a local, on-device record of messages other people deleted and of
 * earlier versions of edited messages. Nothing here is ever sent anywhere.
 *
 * Deleted messages are keyed by "scope": the channel's dialog id for channels and
 * supergroups, or 0 for private chats and basic groups (their ids are unique per account).
 */
object MessageHistoryStore {

    data class EditVersion(val date: Int, val text: String)

    private class Db(account: Int) : SQLiteOpenHelper(
        ApplicationLoader.applicationContext, "nagram_history_$account.db", null, 1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE deleted (scope INTEGER, mid INTEGER, date INTEGER, PRIMARY KEY (scope, mid))")
            db.execSQL("CREATE TABLE edits (dialog INTEGER, mid INTEGER, date INTEGER, text TEXT)")
            db.execSQL("CREATE INDEX edits_idx ON edits (dialog, mid)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val dbs = ConcurrentHashMap<Int, Db>()
    private val deletedCache = ConcurrentHashMap<Int, MutableSet<String>>()
    private val editedCache = ConcurrentHashMap<Int, MutableSet<String>>()

    private fun db(account: Int) = dbs.getOrPut(account) { Db(account) }

    private fun key(a: Long, b: Int) = "$a:$b"

    private fun loadSet(account: Int, deleted: Boolean): MutableSet<String> {
        val cache = if (deleted) deletedCache else editedCache
        cache[account]?.let { return it }
        val set = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        try {
            val sql = if (deleted) "SELECT scope, mid FROM deleted" else "SELECT DISTINCT dialog, mid FROM edits"
            db(account).readableDatabase.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) set.add(key(c.getLong(0), c.getInt(1)))
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        return cache.putIfAbsent(account, set) ?: set
    }

    @JvmStatic
    fun keepDeletedEnabled() = NaConfig.keepDeletedMessages.Bool()

    @JvmStatic
    fun editHistoryEnabled() = NaConfig.keepEditHistory.Bool()

    /** Remembers that these messages were deleted by someone else. */
    @JvmStatic
    fun markDeleted(account: Int, scope: Long, ids: List<Int>) {
        if (ids.isEmpty()) return
        val set = loadSet(account, true)
        try {
            val db = db(account).writableDatabase
            val now = (System.currentTimeMillis() / 1000).toInt()
            db.beginTransaction()
            try {
                for (id in ids) {
                    db.insertWithOnConflict("deleted", null, ContentValues().apply {
                        put("scope", scope); put("mid", id); put("date", now)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    set.add(key(scope, id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun scopeOf(owner: TLRPC.Message): Long =
        owner.peer_id?.channel_id?.takeIf { it != 0L }?.let { -it } ?: 0L

    @JvmStatic
    fun isDeleted(account: Int, messageObject: MessageObject?): Boolean {
        val owner = messageObject?.messageOwner ?: return false
        if (owner.id <= 0) return false
        val set = deletedCache[account] ?: run { warmUp(account); return false }
        return set.contains(key(scopeOf(owner), owner.id))
    }

    /** Loads the deleted-ids cache in the background so isDeleted() never touches disk on the UI thread. */
    private val warming = ConcurrentHashMap<Int, Boolean>()

    @JvmStatic
    fun warmUp(account: Int) {
        if (warming.putIfAbsent(account, true) != null) return
        Thread { loadSet(account, true); loadSet(account, false) }.start()
    }

    /** Saves the previous text of an edited message when it actually changed. */
    @JvmStatic
    fun recordEdit(account: Int, old: TLRPC.Message?, new: TLRPC.Message) {
        if (old == null) return
        val oldText = old.message ?: return
        if (oldText == (new.message ?: "")) return
        val dialog = MessageObject.getDialogId(new)
        try {
            db(account).writableDatabase.insert("edits", null, ContentValues().apply {
                put("dialog", dialog); put("mid", new.id)
                put("date", if (old.edit_date != 0) old.edit_date else old.date)
                put("text", oldText)
            })
            loadSet(account, false).add(key(dialog, new.id))
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun hasEdits(account: Int, messageObject: MessageObject?): Boolean {
        val owner = messageObject?.messageOwner ?: return false
        val set = editedCache[account] ?: run { warmUp(account); return false }
        return set.contains(key(messageObject.dialogId, owner.id))
    }

    /** Earlier versions, oldest first. */
    @JvmStatic
    fun getEdits(account: Int, messageObject: MessageObject): List<EditVersion> {
        val result = ArrayList<EditVersion>()
        try {
            db(account).readableDatabase.rawQuery(
                "SELECT date, text FROM edits WHERE dialog = ? AND mid = ? ORDER BY date, rowid",
                arrayOf(messageObject.dialogId.toString(), messageObject.messageOwner.id.toString())
            ).use { c ->
                while (c.moveToNext()) result.add(EditVersion(c.getInt(0), c.getString(1) ?: ""))
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        return result
    }

    @JvmStatic
    fun clearAll(account: Int) {
        try {
            val db = db(account).writableDatabase
            db.delete("deleted", null, null)
            db.delete("edits", null, null)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        deletedCache[account]?.clear()
        editedCache[account]?.clear()
    }
}
