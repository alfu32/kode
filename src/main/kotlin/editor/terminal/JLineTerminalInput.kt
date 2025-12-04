package editor.terminal

import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import java.io.InputStream

/**
 * JLine-backed terminal input producing normalized events.
 *
 * Note: Mouse tracking must be enabled by the caller. This class keeps things minimal:
 *  - Resize events are emitted from JLine's size change hook.
 *  - Key events normalize common navigation keys.
 */
class JLineTerminalInput(
    input: InputStream = System.`in`,
    private val enableMouse: Boolean = true
) : TerminalInput {

    private val terminal: Terminal = TerminalBuilder.builder()
        .dumb(false)
        .jna(false)
        .streams(input, System.out)
        .system(true)
        .build()

    private val bindingReader = BindingReader(terminal.reader())
    private val keyMap = KeyMap<String>().apply {
        // Basic navigation
        bind("Up", KeyMap.key(InfoCmp.Capability.key_up))
        bind("Down", KeyMap.key(InfoCmp.Capability.key_down))
        bind("Left", KeyMap.key(InfoCmp.Capability.key_left))
        bind("Right", KeyMap.key(InfoCmp.Capability.key_right))
        bind("Home", KeyMap.key(InfoCmp.Capability.key_home))
        bind("End", KeyMap.key(InfoCmp.Capability.key_end))
        bind("PageUp", KeyMap.key(InfoCmp.Capability.key_ppage))
        bind("PageDown", KeyMap.key(InfoCmp.Capability.key_npage))
        bind("Enter", "\r")
        bind("Backspace", "\u007f")
        bind("Tab", "\t")
        bind("Ctrl-C", KeyMap.ctrl('c'))
    }

    @Volatile
    private var pendingResize: TerminalEvent.Resize? = null

    init {
        terminal.handle(Terminal.Signal.WINCH) {
            val size = terminal.size
            pendingResize = TerminalEvent.Resize(size.columns, size.rows)
        }
        if (enableMouse) {
            // Basic mouse tracking; caller can extend if needed.
            terminal.puts(InfoCmp.Capability.keypad_xmit)
            terminal.puts(InfoCmp.Capability.mouse_on)
            terminal.flush()
        }
    }

    override fun poll(): TerminalEvent {
        while (true) {
            tryPoll()?.let { return it }
            Thread.sleep(5)
        }
    }

    override fun tryPoll(): TerminalEvent? {
        pendingResize?.let {
            pendingResize = null
            return it
        }
        val binding = bindingReader.readBinding(keyMap, null, false)
        if (binding != null) {
            return TerminalEvent.Key(key = binding)
        }
        return null
    }
}
