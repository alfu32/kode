## Requirements for code intelligence

### Product constraints

* Must remain responsive on constrained/headless machines.
* Small core distribution; avoid bundling heavy parsers/grammars for ~200+ languages.
* Existing editor: rope/piece-table buffer, undo/redo, git diff, project search.
* Syntax highlighting already works via keyword/regex lists.
* False positives in **search** (including comments/strings) are acceptable and sometimes desirable.
* Editor must not freeze due to highlighting/intelligence work (no multi-second stalls).

### Code intelligence scope (initial)

* Provide a fast, useful “IDE feel” without full semantic correctness:

    * Document outline (symbols in file)
    * Workspace symbol search (jump to definition candidates)
    * Basic navigation to likely definitions
* Optional later (true semantics):

    * go-to-definition / references / rename via LSP when available

### Runtime behavior constraints

* Any expensive work must be done via cancellable background jobs + debouncing.
* Results must be versioned; UI only applies results matching current doc version.
* Avoid per-keystroke full-file rescans and avoid per-undo snapshot copies.

---

## Architectural specification

### Core principle

Separate **render-time tokenization** from **index-time definition extraction**.

#### Pipeline A — Rendering (always fast)

Purpose: paint viewport quickly.

* Input: buffer slices for visible lines.
* Output: styled spans/cells for visible lines.
* Implementation:

    * Tokenize **only visible lines** (+ margin).
    * Cache per-line results.
    * Incremental invalidation on edits.
    * Hard time budget; degrade gracefully (fallback to simple keyword highlighting if needed).
* Does **not** depend on symbol definitions.

#### Pipeline B — Definition indexing (eventually consistent)

Purpose: maintain per-file + workspace symbol definitions database.

* Input: document snapshots (or line iterator) + language-specific definition extractor.
* Output: `SymbolDef[]` shard per file, aggregated into workspace index.
* Runs async, chunked, cancellable, debounced.
* May rescan from file start, but never blocks UI.

#### Pipeline C — Optional semantic provider (LSP)

Purpose: correct semantics when available (defs/refs/rename/diagnostics).

* Input: LSP didOpen/didChange/didSave with coalesced deltas.
* Output: semantic locations/tokens/diagnostics.
* Merged as an overlay; absence never breaks core indexing.

---

## Technical specification

### Data structures

#### Document versioning

* `doc_version: long` increments per edit transaction (not per character if coalesced).

#### Edit delta (canonical)

```text
EditDelta {
  start_offset: int
  end_offset: int          // exclusive
  inserted_text: string    // empty => delete
}
```

#### Coalescing rules (typing/backspace)

Merge deltas into a single “transaction delta” for background/LSP:

* Adjacent offsets, same op type, within time window (e.g. 500ms)
* No cursor jump/selection change
* Produces one `EditDelta` per burst (or a small list)

#### Undo/redo storage (must change from snapshots)

Store operations, not full buffer copies:

```text
UndoOp = Insert(offset, text) | Delete(offset, deleted_text)
```

Push inverse ops; optional checkpoints every N ops.

#### Symbol definition schema (language-agnostic)

```text
SymbolDef {
  name: string
  kind: CLASS | FUNCTION | VARIABLE | ENUM | INTERFACE | TYPEALIAS | MODULE | ...
  file_id: int
  range: [start_offset, end_offset]
  container?: string        // optional
}
```

#### Indexes

* Per-file shard: `file_id -> SymbolDef[]`
* Workspace symbol map: `name(lower) -> [SymbolRef(file_id, symbol_idx)]`
* Optional textual occurrence index (for “find in project” acceleration):

    * include comments/strings; not used as “semantic references”.

---

## Definition extraction strategy (fits size/perf constraints)

### Language extractor types

Each language can provide one of:

1. **Regex/scanner-based definition extractor** (recommended for breadth)
2. **Optional pack-provided tree-sitter parser** (for a small set of languages only)
3. **LSP-only** (when user supplies a server)

### Scanner-based extractor (recommended baseline)

* Operates as a streaming scan over the file (line iterator).
* Recognizes definition patterns using token scanning (not giant regex):

    * e.g., Kotlin: `class|interface|object|enum class IDENT`, `fun IDENT`, `val|var IDENT`
* Minimal state:

    * brace depth and container stack (optional)
* Output only definitions; ignore “definitions” found inside comments/strings (even if search includes them).

---

## Incremental indexing algorithm (no heavy parser required)

### Checkpointed rescan

* Maintain checkpoints every `K` lines (e.g. 200):

    * `CheckpointState { brace_depth, container_stack_summary, in_block_comment?, ... }`
* On edit at line `L`:

    * find nearest checkpoint `C <= L`
    * rescan from line `C` forward in chunks
    * replace symbols in that region; stop early if state stabilizes and produced symbol list matches prior run for a suffix window

### Chunking + budgets

* Background indexing runs in slices:

    * e.g. process 1k–10k lines or up to 20–50ms per chunk
* If budget exceeded:

    * yield; schedule continuation
* Always cancellable; discard stale results by version check.

---

## Job scheduling and concurrency

### Jobs per document

* `render_token_job` (optional background; render can also run directly with strict budget)
* `definition_index_job` (debounced + cancellable)
* `lsp_sync_job` (debounced + cancellable, optional)

### Debounce targets (suggested)

* Definition indexing: 150–300ms after last keystroke
* LSP didChange batching: 75–150ms
* Workspace scan on open/save: 300–800ms chunked

### Version-gating rule

All job results include `computed_version`.
UI applies only if `computed_version == current_doc_version`; otherwise drop.

---

## Distribution/size requirement: language packs without full plugin system

### Minimal “language pack” loader

* Install packs to `~/.kode/packs/<language>/<version>/`
* Pack may contain:

    * TextMate grammar resources (optional)
    * Definition extractor config (scanner patterns)
    * Optional native tree-sitter grammar (per-platform)
    * Optional LSP descriptor
* Download on demand from a simple `index.json` + artifact URLs.
* Verify SHA-256 before loading.

No general plugin host required; this is a narrow extension loader.

---

## Acceptance criteria (performance + UX)

* Typing latency stays under interactive threshold; no multi-second stalls.
* Highlighting may degrade temporarily under load, but input never blocks.
* Outline and workspace symbol search populate progressively (“indexing…” allowed).
* Index updates on save and eventually during editing (debounced).
* Undo/redo does not store full document snapshots per step.