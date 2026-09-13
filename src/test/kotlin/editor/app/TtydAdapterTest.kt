package editor.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TtydAdapterTest {
    @Test
    fun mapsOperatingSystemsToBundledTtydAssets() {
        assertEquals("linux/x86_64/ttyd.so", TtydAdapter.assetNameFor("Linux"))
        assertEquals("linux/x86_64/ttyd.so", TtydAdapter.assetNameFor("Linux", "x86_64"))
        assertEquals("linux/aarch64/ttyd.so", TtydAdapter.assetNameFor("Linux", "aarch64"))
        assertEquals("macos/aarch64/ttyd.dylib", TtydAdapter.assetNameFor("Mac OS X", "aarch64"))
        assertEquals("macos/aarch64/ttyd.dylib", TtydAdapter.assetNameFor("Darwin", "arm64"))
        assertEquals("macos/x86_64/ttyd.dylib", TtydAdapter.assetNameFor("Mac OS X", "x86_64"))
        assertEquals("windows/x86_64/ttyd.dll", TtydAdapter.assetNameFor("Windows 11", "x86_64"))
        assertEquals("windows/aarch64/ttyd.dll", TtydAdapter.assetNameFor("Windows 11", "aarch64"))
    }

    @Test
    fun returnsNullForUnsupportedPlatform() {
        assertNull(TtydAdapter.assetNameFor("Solaris"))
        assertNull(TtydAdapter.assetNameFor("Linux", "riscv64"))
        assertNull(TtydAdapter.assetNameFor("Mac OS X", "sparc"))
        assertNull(TtydAdapter.assetNameFor("Windows 11", "sparc"))
    }

    @Test
    fun bundledNativeLibrariesAreAvailableOnClasspath() {
        listOf(
            "native/linux/x86_64/ttyd.so",
            "native/linux/aarch64/ttyd.so",
            "native/macos/aarch64/ttyd.dylib",
            "native/macos/x86_64/ttyd.dylib",
            "native/windows/x86_64/ttyd.dll",
            "native/windows/aarch64/ttyd.dll"
        ).forEach { asset ->
            val resource = TtydAdapter::class.java.getResourceAsStream("/$asset")
            assertNotNull(resource, "Missing bundled ttyd native library: $asset").close()
        }
    }
}
