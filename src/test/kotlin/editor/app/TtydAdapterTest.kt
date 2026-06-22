package editor.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TtydAdapterTest {
    @Test
    fun mapsOperatingSystemsToBundledTtydAssets() {
        assertEquals("ttyd.linux.so", TtydAdapter.assetNameFor("Linux"))
        assertEquals("ttyd.macos.dylib", TtydAdapter.assetNameFor("Mac OS X"))
        assertEquals("ttyd.macos.dylib", TtydAdapter.assetNameFor("Darwin"))
        assertEquals("ttyd.msvc.dll", TtydAdapter.assetNameFor("Windows 11"))
    }

    @Test
    fun returnsNullForUnsupportedPlatform() {
        assertNull(TtydAdapter.assetNameFor("Solaris"))
    }

    @Test
    fun bundledNativeLibrariesAreAvailableOnClasspath() {
        listOf("ttyd.linux.so", "ttyd.macos.dylib", "ttyd.msvc.dll").forEach { asset ->
            val resource = TtydAdapter::class.java.getResourceAsStream("/native/ttyd/$asset")
            assertNotNull(resource, "Missing bundled ttyd native library: $asset").close()
        }
    }
}
