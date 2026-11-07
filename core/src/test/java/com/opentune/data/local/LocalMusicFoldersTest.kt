package com.opentune.data.local

import com.opentune.data.settings.LibrarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalMusicFoldersTest {
    private fun settings(folders: Set<String> = emptySet(), single: String? = null) =
        LibrarySettings(localFolder = single, localFolders = folders)

    @Test fun withNothingChosenEveryFolderIsWanted() {
        val s = settings()
        assertTrue(LocalMusic.wanted(s, "Music/Pop/"))
        assertTrue(LocalMusic.wanted(s, "Download/recordings/"))
    }

    @Test fun theOldSingleFolderChoiceStillHolds() {
        val s = settings(single = "Music/")
        assertTrue(LocalMusic.wanted(s, "Music/Pop/"))
        assertFalse(LocalMusic.wanted(s, "Podcasts/Shows/"))
        assertFalse(LocalMusic.wanted(s, "Music"))
    }

    @Test fun aPickedTreeUriBecomesARelativePathPrefix() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FPop"
        assertEquals("Music/Pop/", LocalMusic.folderPrefix(uri))
        assertEquals("Music/Pop", LocalMusic.folderName(uri))
        val s = settings(folders = setOf(uri))
        assertTrue(LocalMusic.wanted(s, "Music/Pop/"))
        assertTrue(LocalMusic.wanted(s, "Music/Pop/Lofi/")) // folders beneath it come along
        assertFalse(LocalMusic.wanted(s, "Music/Popscopy/")) // a folder sharing a prefix, but not the name
        assertFalse(LocalMusic.wanted(s, "Download/recordings/"))
    }

    @Test fun severalFoldersAreAllKept() {
        val s = settings(folders = setOf(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FPop",
            "content://com.android.externalstorage.documents/tree/secondary%3ARecordings",
        ))
        assertTrue(LocalMusic.wanted(s, "Music/Pop/"))
        assertTrue(LocalMusic.wanted(s, "Recordings/fm/"))
        assertFalse(LocalMusic.wanted(s, "Music/Jazz/"))
    }

    @Test fun aNonPrimaryFolderIsKeptUnderRelativePaths() {
        val uri = "content://com.android.externalstorage.documents/tree/secondary%3AMusic%2FEdits"
        assertEquals("Music/Edits/", LocalMusic.folderPrefix(uri))
        assertTrue(LocalMusic.wanted(settings(folders = setOf(uri)), "Music/Edits/cut1/"))
    }

    @Test fun aPrefixIsNeverLooseOnItsOwnSlash() {
        assertEquals("Music/Pop/", LocalMusic.folderPrefix("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FPop"))
        assertEquals("Downloads/", LocalMusic.folderPrefix("Downloads"))
        assertEquals("", LocalMusic.folderPrefix("content://com.android.externalstorage.documents/tree/primary%3A"))
    }

    @Test fun anUnreadableEntryWritesNothingOff() {
        // An entry that can't be parsed yields no prefix, so it can't silently widen the choice.
        val s = settings(folders = setOf("content://com.android.externalstorage.documents/tree/primary%3A"))
        assertTrue(LocalMusic.chosenPrefixes(s).isEmpty())
        // Nothing chosen now: back to the single-folder fallback, then every folder.
        assertFalse(LocalMusic.wanted(s.copy(localFolder = "Music/"), "Podcasts/Shows/"))
    }
}
