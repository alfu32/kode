# TUI Code Editor

![img_2.png](img_2.png)
![img_1.png](img_1.png)

## Objectives
- Build a terminal-first code editor with modern ergonomics: split panes, tabs, guttered editing, syntax highlighting, and basic code intelligence.
- Maintain broad terminal compatibility (SSH-friendly) while supporting mouse, resize, and keyboard navigation.
- Keep architecture modular so rendering, input, buffer, and integrations (Git, settings) evolve independently.

## Layout & UX
- Always-visible top bar for workspace/file info; bottom status bar for mode, cursor, and Git hints.
- Middle is split: left panel with tabs (Project/FileTree for browsing/editing files and folders; Git for status/commit authoring/history; Settings for theme selection), right panel with the layered code editor.
- Splitter should be resizable via mouse/keyboard; persist user preference when possible.
- Right editor layers: base text lines trimmed to viewport, token colors from per-visible-line TextMate/tmGrammar lexing, and a code-intel layer that maps definitions/usages and supports Ctrl-click navigation.
- Editor interactions: gutter, arrow/home/end/pgup/pgdn navigation, selection with Shift/Ctrl+Shift, and operations backed by the generic text buffer.

## Architecture Overview
- **Terminal**: raw-mode handler, input parser (keys/mouse/resize), and event loop throttling. Favor a normalized event model so renderers and widgets remain decoupled from terminal quirks.
- **Renderer**: ANSI canvas with back buffer diffing; draws rects/text, supports RGB, and exposes a simple API (`drawText`, `drawRect`, `setColor`, `flush`). Keep layout constants centralized.
- **Buffer**: generic text buffer (gap buffer or rope) with undo/redo, cursors, selections, and efficient per-line access.
- **Editor**: viewport management, gutters, token overlays, and code-intel navigation hooks. Tokenization limited to visible lines for performance.
- **Panels**: 
  - Project/FileTree uses the file system API for expand/collapse, create/delete, open, and workspace switch.
  - Git panel shows status (upper), commit message editor (middle), and commit list (lower); wire to Git CLI or a thin wrapper.
  - Settings panel exposes theme selection for UI and code editor palettes.
- **Config/Themes**: central palette definitions (for UI + syntax) to keep rendering consistent across panels.

## Development Notes
- Use `./gradlew build` / `./gradlew test` as primary checks; target Java 21 per `build.gradle.kts`.
- Add regression tests for buffer operations, renderer diffing, event parsing, and panel behaviors. Snapshot tests can cover render output.
- Keep `README.md` updated when layout, objectives, or architecture change so contributors stay aligned.
- Build artifact: `./gradlew fatJar` produces a self-contained `*-all.jar` with the manifest entrypoint.
- Dependencies: JLine for terminal I/O, JGit for Git panel operations; add further libs deliberately to keep the footprint lean.
- Current scaffolding: JLine-backed terminal input (`JLineTerminalInput`), ANSI renderer (`AnsiCanvasRenderer`), basic `EditorState` with viewport/visible lines, and a `UiRenderer` that draws the top bar, split panes, and status bar.


![img_2.png](img_2.png)
![img_1.png](img_1.png)
![img.png](img.png)