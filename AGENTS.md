# Repository Guidelines

## Project Structure & Module Organization
- Kotlin/JVM project targeting Java 21 (see `build.gradle.kts`); Gradle wrapper drives builds.
- Core renderer/input code lives in `src/main/kotlin/react` (ANSI canvas, styles, UI events). Add new editor/panel modules alongside this package or in sibling packages if concerns grow.
- Tests belong in `src/test/kotlin`, mirroring package names. Build outputs land in `build/`.
- `README.md` describes the intended terminal UX and architecture; keep it aligned when you change layouts or subsystems.

## Build, Test, and Development Commands
- `./gradlew build` — compile sources and run the full test suite.
- `./gradlew test` — run tests only (JUnit Platform via `kotlin("test")`).
- `./gradlew fatJar` — produce `build/libs/kt-tui-edit-all.jar` with dependencies; run via `java -jar build/libs/kt-tui-edit-all.jar`.
- `./gradlew clean` — remove build outputs when you need a fresh build.

## Coding Style & Naming Conventions
- Follow Kotlin official style: 4-space indentation, trailing commas where helpful, prefer expression bodies for simple functions, and explicit visibility for non-public APIs.
- Keep packages lowercase (e.g., `react.ui`), classes in `PascalCase`, functions/properties in `camelCase`, and constants in `UPPER_SNAKE_CASE`.
- Limit new dependencies; current footprint is JLine for terminal I/O and JGit for Git panel work.
- Prefer small, testable components (renderer, buffer, input parsing) over monoliths; keep rendering/layout constants centralized for reuse.

## Testing Guidelines
- Testing uses Kotlin test on JUnit Platform. Name suites `*Test` (e.g., `AnsiCanvasRendererTest`) and mirror the source package.
- Cover buffer mutations, renderer diffing, input parsing, and panel state changes; favor deterministic data over real terminal I/O.
- Run `./gradlew test` before sending a PR; add regression cases when fixing bugs.

## Commit & Pull Request Guidelines
- Match the existing short format `type(scope)` (e.g., `refactor(vdom)`, `feat(buffer)`), imperative and lowercase.
- Commit often with focused changes; avoid mixing refactors with feature work when possible.
- Pull requests should include: a brief summary of user-facing/editor-visible changes, linked issue (if any), notes on testing performed, and screenshots/asciinema snippets when altering layout or rendering.
- Keep diffs small and comment tricky logic (buffer edge cases, render diff rules) to aid review.
