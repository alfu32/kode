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

# response guidelines

- always respond in the sum up in the commitizen format

All commits must follow the Commitizen / Conventional Commits standard using the structural layout below:

## Commitizen / Conventional Commits standard
```text
<type>(<scope>): <subject>

<body>
```

### Field Definitions

* **`<type>`**: Must be one of the following lowercase tokens:
    * `feat`: A new feature or capability.
    * `fix`: A bug fix.
    * `docs`: Documentation changes only.
    * `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc).
    * `refactor`: A code change that neither fixes a bug nor adds a feature.
    * `perf`: A code change that improves performance.
    * `test`: Adding missing tests or correcting existing tests.
    * `chore`: Changes to the build process, auxiliary tools, or libraries/dependencies.
* **`<scope>`**: Optional. A noun naming the specific codebase component or module affected, wrapped in parentheses (e.g., `(parser)`, `(auth)`, `(runtime)`).
* **`<subject>`**: A brief, imperative-mood summary of the change. Do not capitalize the first letter. Do not end with a period.
* **`<body>`**: Optional. Separate from the subject with exactly one blank line. Provides the motivation for the change and contrasts it with previous behavior.

additionally the body should be structured as follows:

(REQUEST:)
- summary of what was asked/requested

(IMPLEMENTATION:)
- summary of the solution or answer
implementation details:
- bulleted list of technical/functional modifications or planning steps ( what you print out by default in the summary )

(NOT IMPLEMENTED:)
 - summary of not implemented features/parts of the request
 - features/requests remaining to be implemented/researched
 - eventual steps/tests to be taken by the user before proceeding

### Examples

```text
fix(editor): persist and reveal mapped compiler diagnostics

REQUEST:
the user has to be able to see error points given by diagnostics by expandable markers in the gutter

IMPLEMENTATION:
  - Diagnostics are persisted on each node and restored with the project.
  - New validation/compilation clears previous diagnostics.
  - Gutter markers now reveal the mapped editor, section, and source line automatically.
  - Nodes with diagnostics show a red warning badge in the diagram.
  - Runtime/override errors without source-map entries are retained and shown as unmapped instead of being discarded.
  - The status bar now shows:
    generated-file:line:column -> node section source-line:column

NOT IMPLEMENTED:
  - colorisation and retrieval of code artifacts
  - research solution through local / embedded small LM.
    - we need CUDA working on this machine otherwise we'll not be able to test
```

```text
fix(compiler): resolve memory leaks on dynamic execution evaluation loops
```
