# Repository Guidelines

## Project Structure & Module Organization
- Kotlin/JVM app (Kotlin 2.2, Java 21). Core TUI framework sits in `src/main/kotlin/react` (rendering, input, styles).
- Editor domain lives in `src/main/kotlin/editor`: `app` (entry, layout), `ui` (panels: file tree, code editor, hex/image viewers), `lib` (text buffer, file tree contract), `mime` (detector). Shared styles are under `styles/`. Sample files for MIME tests are under `samples/`.
- Tests go in `src/test/kotlin`, mirroring packages. Build artifacts emit to `build/`.
- Keep `README.md` in sync when changing layout, focus handling, or viewer behavior.

## Build, Test, and Development Commands
- `GRADLE_USER_HOME=./.gradle-user ./gradlew build` — compile and run tests.
- `GRADLE_USER_HOME=./.gradle-user ./gradlew test` — execute the test suite only.
- `GRADLE_USER_HOME=./.gradle-user ./gradlew fatJar` — create `build/libs/kode-1.0-SNAPSHOT-all.jar`; run with `java -jar build/libs/kode-1.0-SNAPSHOT-all.jar`.
- `GRADLE_USER_HOME=./.gradle-user ./gradlew clean` — clear build outputs. Avoid editing the wrapper scripts.

## Coding Style & Naming Conventions
- Follow Kotlin official style: 4-space indents, expression bodies for one-liners, explicit visibility for non-public APIs. Keep packages lowercase; classes in `PascalCase`, functions/properties in `camelCase`, constants in `UPPER_SNAKE_CASE`.
- Favor composable components: rendering logic in `react`, domain logic in `editor/lib`, and view wiring in `editor/ui`. Keep state changes deterministic to avoid flicker in the renderer.
- Extend the MIME table with explicit entries (category + mime + language) rather than inference; place updates in `editor/mime/SampleMimeTable.kt`.

## Testing Guidelines
- Tests use Kotlin test on JUnit Platform. Name suites `*Test` (e.g., `TextBufferTest`, `DefaultMimeTypeDetectorTest`) and mirror source packages.
- Cover buffer mutations, focus/dispatch flows, diff rendering, MIME detection, and viewer routing (code/image/hex). Use fixtures from `samples/` where possible.
- Run `GRADLE_USER_HOME=./.gradle-user ./gradlew test` before submitting changes; add regression cases for input/focus bugs.

## Commit & Pull Request Guidelines
- Prefer concise, imperative messages; existing history leans toward `type(scope): subject` (e.g., `feat(editor): add hex viewer`).
- Keep commits focused (no mixed refactor + feature). Include tests or rationale when skipping them.
- PRs should state user-visible changes, linked issues, test results, and terminal screenshots/gifs when altering layout, focus, or rendering behavior.
- Highlight tricky logic (focus arbitration, canvas diffing, MIME lookup) with short comments to ease review.
