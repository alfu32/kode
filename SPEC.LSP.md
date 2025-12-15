## Proposal: LSP Integration for Kode Code Intelligence

### Goals
- Add precise, semantic code intelligence (definitions/references/hover/rename) via LSP, while keeping the existing regex scanner as a fallback.
- Keep typing latency responsive (background, debounced, cancellable).
- Work with small footprint bundles; servers are user-provided/installed.

### Scope
- Definition/Go-to, References, Hover, Completion; rename later.
- Diagnostics: optional (display as overlay/bottom panel).
- Languages: start with common servers available on PATH (tsserver, gopls, rust-analyzer, pylsp/jedi, clangd, intelephense, etc.).
- Regex scanner stays as baseline for languages without LSP.

### Architecture
1) **Client Manager**
   - Per-language LSP client launched when a file with that language is opened and a server is discoverable.
   - Capability detection from `initialize` (defs/refs/hover/completion/diag).
   - One process per server; shared across files of same language.

2) **Document Sync**
   - Debounced coalesced edits (existing docVersion, buffer deltas) -> `textDocument/didChange`.
   - `didOpen` on first load; `didClose` on file close.
   - `didSave` on save.
   - Version gating: apply results only when `computedVersion == currentVersion`.

3) **Request Flow**
   - Definition/References/Hover: fire on Ctrl-click / F12 / hover after debounce; fall back to regex index if no LSP.
   - Completion: trigger on Ctrl+Space; merge LSP items with regex suggestions, dedup by label.
   - Diagnostics: subscribe to `textDocument/publishDiagnostics`; surface as inline markers/popups if enabled.

4) **Result Merging**
   - Primary: LSP results when available and current; fallback: regex index.
   - Token styling: keep regex tokens; optionally overlay LSP semantic tokens later.
   - Usages popup: prefer LSP references; if missing, use regex index.

5) **Config & Discovery**
   - Config file: `codeintel/lsp.json` (optional) to map language -> command array; supports overrides.
   - Auto-discover common servers on PATH when no config entry exists.
   - Enable/disable per language; allow user to point to custom binaries.
   - Env/property overrides: `kode.lsp.config`, `KODE_LSP_CONFIG`.

6) **Process & Lifecycle**
   - Start on first need; keep alive with idle timeout.
   - Stderr/stdout logging to a rotating file (debug level toggle).
   - Graceful shutdown on app exit.

### UI/UX
 - Ctrl-click: use LSP definition when available; otherwise regex.
 - Usages popup: populate from LSP references; fallback to regex.
 - Suggestions (Ctrl+Space): merge LSP completion items + regex/local identifiers; show detail/kind if provided.
 - Diagnostics: optional gutter markers or status panel (future).
 - Status: show “LSP: on/off <server>” in header or status bar when active.

### Data Structures (additions)
 - `LspProvider` per language with capabilities.
 - `LspJob` for pending requests; cancellable.
 - `LspConfig { language: string, command: [string], args: [string], rootPatterns?: [string] }`.

### Integration Points
 - Reuse existing debounce/versioning in `CodeEditorView` and `CodeIntelService`.
 - Hook completion/defs/refs/hover calls to prefer LSP provider; keep regex index for rendering and fallback.
 - Add a small LSP client module (JSON-RPC over stdio) with minimal dependencies.

### Rollout Plan
1) Implement minimal LSP client (initialize, didOpen/Change/Save, definition, references, completion, hover) with version gating.
2) Config loader (`codeintel/lsp.json`) + PATH discovery for popular servers.
3) Wire CodeEditorView to query LSP provider first; merge results with regex.
4) Add basic diagnostics plumbing (optional display).
5) Tests: mock server harness or golden JSON fixtures; integration smoke with a real server gated behind opt-in.***
