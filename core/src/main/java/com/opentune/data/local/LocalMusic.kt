package com.opentune.data.local

import android.content.ContentUris
import android.content.Context
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import com.opentune.data.model.Song
import com.opentune.data.settings.LibrarySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Audio files on the device, read from MediaStore. */
object LocalMusic {
    const val PREFIX = "local:"
    private val ALBUM_ART = "content://media/external/audio/albumart".toUri()

    fun isLocal(videoId: String) = videoId.startsWith(PREFIX)

    fun contentUri(videoId: String): Uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        videoId.removePrefix(PREFIX).toLong(),
    )

    data class Track(val song: Song, val folder: String, val mimeType: String?, val durationMs: Long, val isMusic: Boolean)

    /**
     * The folder prefixes the library covers, as MediaStore relative paths ("Music/Pop/").
     * Entries from the SAF picker are decoded from their tree URIs; a plain entry is a
     * relative path as saved by the old single-folder choice. Empty means every folder on
     * the phone, or just the old single one when that is set.
     */
    fun chosenPrefixes(settings: LibrarySettings): List<String> {
        if (settings.localFolders.isEmpty()) return settings.localFolder?.let { listOf(it) } ?: emptyList()
        return settings.localFolders.map(::folderPrefix).filter { it.isNotEmpty() }
    }

    /** A human name for a saved folder entry: the tree URI's path, or the path as saved. */
    fun folderName(raw: String): String = folderPrefix(raw).removeSuffix("/")

    /** A saved folder entry as a MediaStore relative-path prefix ("Music/Pop/"). */
    fun folderPrefix(raw: String): String {
        if (!raw.startsWith("content://")) return raw.takeIf { it.endsWith("/") } ?: "$raw/"
        val decoded = Uri.decode(raw)
        // "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FPop"
        // → "primary:Music/Pop" → the volume name is dropped: MediaStore's RELATIVE_PATH has none.
        val path = decoded.substringAfter("/tree/", "")
            .substringBefore('/', "")
            .substringAfter(':', "")
            .trim('/')
        return if (path.isEmpty()) "" else "$path/"
    }

    /** Whether a track's folder is wanted. No chosen folders means every folder on the phone, or just the old single one when that is set. */
    fun wanted(settings: LibrarySettings, folder: String): Boolean {
        if (folder == "") return false
        val prefixes = chosenPrefixes(settings)
        if (prefixes.isEmpty()) return settings.localFolder == null || folder == settings.localFolder
        // Every prefix ends in "/", so "Music/Pop/" never matches "Music/Popsicle/".
        return prefixes.any { folder.startsWith(it) }
    }

    suspend fun tracks(context: Context, settings: LibrarySettings): List<Song> = withContext(Dispatchers.IO) {
        query(context)
            .filter { wanted(settings, it.folder) }
            .filter { !settings.filterNonMusic || looksLikeMusic(it) }
            .map { it.song }
    }

    suspend fun folders(context: Context): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        query(context).groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    /**
     * Short clips, WAV recordings, voice notes and system sounds aren't music,
     * even when they sit in a music folder.
     */
    fun looksLikeMusic(t: Track): Boolean {
        val folder = t.folder.lowercase()
        return t.isMusic &&
            t.durationMs >= 30_000 &&
            t.mimeType?.contains("wav") != true &&
            listOf("recording", "voice", "whatsapp audio", "notifications", "ringtones", "alarms", "call").none { it in folder }
    }

    // Lint's Recycle check doesn't see Kotlin's use {}, which closes the cursor.
    @SuppressLint("Recycle")
    private fun query(context: Context): List<Track> {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC,
            folderColumn,
        )
        val out = mutableListOf<Track>()
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        ) ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val duration = c.getLong(5)
                val rawFolder = c.getString(8).orEmpty()
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawFolder else rawFolder.substringBeforeLast('/') + "/"
                val artist = c.getString(2)?.takeUnless { it == "<unknown>" } ?: "Unknown artist"
                out += Track(
                    song = Song(
                        videoId = "$PREFIX$id",
                        title = c.getString(1).orEmpty(),
                        artist = artist,
                        thumbnailUrl = ContentUris.withAppendedId(ALBUM_ART, c.getLong(4)).toString(),
                        durationText = formatDuration(duration),
                        albumName = c.getString(3),
                    ),
                    folder = folder,
                    mimeType = c.getString(6),
                    durationMs = duration,
                    isMusic = c.getInt(7) != 0,
                )
            }
        }
        return out
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
