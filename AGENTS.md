# Repository Guidelines

## Project Structure & Module Organization
- Single Kotlin/JVM Gradle project; configuration lives in `build.gradle.kts` with Kotlin DSL.
- Place production sources under `src/main/kotlin` and resources (if any) under `src/main/resources`.
- Put tests in `src/test/kotlin`, mirroring the main package structure; test fixtures go in `src/test/resources`.
- Keep root clean: Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) is the supported entrypoint; avoid adding IDE-specific files beyond `.gitignore`.
- UI layout: top bar, split middle (left tabs: Project/FileTree, Git, Settings; right layered code editor), bottom status bar. Keep layout constants centralized to avoid drift.

## Build, Test, and Development Commands
- `./gradlew build` — compile, run tests, and assemble outputs to `build/`.
- `./gradlew test` — execute the JUnit Platform suite (uses `kotlin("test")`).
- `./gradlew clean` — remove build artifacts for a fresh compile.
- `./gradlew fatJar` — build a self-contained executable JAR (classifier `-all`) with the manifest entrypoint.
- Use the wrapper (`./gradlew`) to ensure Kotlin 2.2.x and Java 21 toolchain alignment as configured in `build.gradle.kts`.

## Coding Style & Naming Conventions
- Kotlin style with 4-space indentation; keep lines readable (~120 chars).
- Package paths should match directories; use `CamelCase` for types, `lowerCamelCase` for functions/properties, and `UPPER_SNAKE_CASE` for constants.
- Favor immutable `val`, small composable functions, and expression bodies where clear. Add KDoc for public APIs and non-obvious logic.
- No formatter is enforced; follow the official Kotlin style guide. If adding a formatter or linter later, document it here.

## Testing Guidelines
- Framework: `kotlin("test")` on JUnit Platform.
- Name tests after behavior (e.g., `shouldHandleMouseDragEvents`) and mirror source packages in `src/test/kotlin`.
- Keep tests fast and deterministic; prefer unit tests over end-to-end when possible. Add regression tests for every bug fix.
- Run `./gradlew test` before opening a PR; include any required fixtures under `src/test/resources`.

## Commit & Pull Request Guidelines
- Use imperative, concise commit subjects (e.g., `Add cursor rendering loop`); conventional prefixes (`feat:`, `fix:`) are fine if they aid readability—history is currently minimal, so establish a clear standard now.
- PRs should state what changed, why, and how to verify (list commands like `./gradlew test`); link issues when applicable and note follow-up tasks.
- Include screenshots or terminal recordings for user-visible TUI changes; keep PRs focused and avoid bundling unrelated refactors.
- With each functional change, update `README.md` to reflect objectives, architecture, layout, and build tasks so docs stay in sync with the code.
