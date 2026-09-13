package react.renderer

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnsiCanvasRendererTest {

    @Test
    fun decodesPageDownCsiWithoutWaitingForAnotherInputByte() {
        val renderer = AnsiCanvasRenderer(
            input = ByteArrayInputStream("\u001b[6~".toByteArray()),
            output = StringBuilder(),
            initialCols = 80,
            initialRows = 24
        )

        val event = renderer.tryPollEvent()

        assertNotNull(event)
        assertEquals("key_down", event.kind)
        assertEquals("PageDown", event.key)
    }
}
