package os.deckbridge.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.logic.loadRekordbox

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtworkCacheTest {
    @Test
    fun thumbIsCachedInTheAppAndTheUsbIsUnchanged() {
        val volume = SyntheticRekordbox.volume()
        val before = volume.files.keys.toSet()
        val catalog = loadRekordbox(volume)
        val night = catalog.tracks.first { it.title == "Night Bus" }
        val bare = catalog.tracks.first { it.title == "Click 100" }
        val dir = RuntimeEnvironment.getApplication().cacheDir
        val thumb = ArtworkStore.thumb(dir, volume, night)
        assertNotNull(thumb)
        assertTrue(thumb!!.isNotEmpty())
        val folder = File(dir, "artwork")
        assertTrue(folder.isDirectory && folder.list()?.isNotEmpty() == true)
        assertEquals(before, volume.files.keys.toSet())
        val again = ArtworkStore.thumb(dir, volume, night)
        assertTrue(again!!.contentEquals(thumb))
        assertNull(ArtworkStore.thumb(dir, volume, bare))
    }
}
