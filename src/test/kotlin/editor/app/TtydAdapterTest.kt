package editor.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtydAdapterTest {
    @Test
    fun mapsOperatingSystemsToSharedLibraryAssets() {
        assertEquals("ttyd.linux.so", TtydAdapter.assetNameFor("Linux"))
        assertEquals("ttyd.macos.dylib", TtydAdapter.assetNameFor("Mac OS X"))
        assertEquals("ttyd.macos.dylib", TtydAdapter.assetNameFor("Darwin"))
        assertEquals("ttyd.msvc.dll", TtydAdapter.assetNameFor("Windows 11"))
    }

    @Test
    fun returnsNullForUnsupportedPlatform() {
        assertNull(TtydAdapter.assetNameFor("Solaris"))
    }
}
