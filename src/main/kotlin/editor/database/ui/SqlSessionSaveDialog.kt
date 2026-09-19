package editor.database.ui

import editor.ui.ModalTextInputDialog
import react.StyleSheet

/** Compatibility wrapper that gives SQL session saves the shared modal text-input behavior. */
class SqlSessionSaveDialog(
    styleSheet: StyleSheet,
    defaultName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) : ModalTextInputDialog(
    styleSheet = styleSheet,
    title = "Save SQL session",
    label = "File name or path",
    initial = defaultName,
    onConfirm = onSave,
    onDismiss = onDismiss
)
