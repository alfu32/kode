# Semantic Code Database

Kode's code-intelligence source of truth is a persistent, incremental semantic database. Tree-sitter CSTs remain transient syntax inputs; LSP, compiler, SCIP, and ML integrations are optional enrichers.

```text
source -> Tree-sitter CST -> language adapter -> FileSemanticDelta
                                                   |
                                                   v
                                      persistent semantic database
                                                   |
                                                   v
                                         immutable snapshot
                                                   |
                     +-----------------------------+------------------------+
                     |                             |                        |
               symbol resolver              type resolver          member resolver
                     |                             |                        |
                     +-----------------------------+------------------------+
                                                   |
                                         completion providers
                                                   |
                                      deterministic/optional ML ranker
```

## Package and file map

- `editor.codeintel.model/SemanticModel.kt`: stable IDs, ranges, file metadata, symbols, persisted scopes, occurrences, common types, imports, relations, confidence, unresolved types, inference hints, and the per-file delta contract.
- `editor.codeintel.frontend/LanguageSemanticAdapter.kt`: syntax-only adapter boundary. Adapters emit deltas and cannot mutate the index.
- `editor.codeintel.frontend/KotlinSemanticAdapter.kt`: first adapter vertical slice. It extracts Kotlin declarations, lexical scopes, declared/return/super types, imports, calls, member references, ownership, and initializer hints from Tree-sitter.
- `editor.codeintel.frontend/LegacySemanticDeltaFactory.kt`: explicitly low-confidence bridge that pre-caches non-migrated language output in the normalized schema without making it authoritative for semantic queries.
- `editor.codeintel.index/SemanticIndex.kt`: per-file replacement, progressive project resolution, atomic snapshot publication, and snapshot queries.
- `editor.codeintel.index/H2SemanticStore.kt`: normalized transactional persistence using Kode's existing embedded project database lifecycle. The schema is independent from the temporary legacy tables.
- `editor.codeintel.resolver/SemanticResolver.kt`: scope-aware symbol resolution, declared and inferred types, calls, ownership, and inheritance.
- `editor.codeintel.resolver/ExpressionTypeResolver.kt`: conservative best-effort expression typing used by member completion.
- `editor.codeintel.completion/CompletionPipeline.kt`: local, member, type, import, keyword, workspace, and snippet providers plus the candidate engine.
- `editor.codeintel.ml/CompletionRanker.kt`: ranker seam and deterministic first implementation. An ONNX implementation may score candidates but may not generate facts or members.
- `editor.codeintel.semantic/SemanticTokens.kt`: semantic token classification based on the same resolved occurrences.
- `editor.codeintel/CodeIntelService.kt`: compatibility facade used by the existing editor and pre-index scan. Kotlin is served from semantic snapshots; legacy extraction remains a low-confidence fallback for languages not migrated yet.

## Persisted model

The semantic schema stores project facts separately:

```text
semantic_files
semantic_scopes
semantic_symbols
semantic_occurrences
semantic_relations
semantic_types
semantic_unresolved_types
semantic_imports
semantic_type_hints
```

Indexes cover symbol names and qualified names, symbol file/scope ownership, occurrence file offsets and resolved symbols, relation directions, and scope ranges. Tree-sitter trees and editor buffer text are not stored in these tables.

Every update replaces one file's records in one transaction. Full-project pre-indexing batches deltas, resolves the workspace once, and publishes one snapshot rather than exposing partially scanned state. Interactive edits replace one file immediately. Resolution completes before an immutable snapshot is atomically published, so readers continue using the preceding snapshot during extraction and persistence. Stable IDs are derived from file identity and declaration/source locations; occurrences point to resolved `SymbolId` values instead of relying on text equality.

## Current Kotlin vertical slice

The first implemented slice supports:

- class, interface, object, enum, function, method, property, local, and parameter symbols;
- file, package, type, function, and block scopes;
- package/import records and ownership/member relations;
- declared property/parameter/return types;
- local inference from constructor calls and resolved function return types;
- cross-file type resolution independent of indexing order;
- class inheritance and inherited-member traversal;
- definition/reference lookup by resolved symbol identity;
- `receiver.` completion for identifiers and simple calls;
- deterministic completion scoring;
- semantic token refinement from resolved occurrences;
- persistent reload of extracted and resolved facts.

Unknown types and unresolved occurrences are retained as unknown. Resolution does not invent a confident answer.

## Incremental migration plan

1. **Kotlin foundation (implemented):** emit symbols, scopes, occurrences, imports, unresolved types, and relations; persist per-file deltas; publish snapshots; route Kotlin compatibility queries through the semantic index.
2. **Dependency invalidation:** persist file dependencies and compare `exportedSurfaceHash`; only re-resolve dependent files when the public semantic surface changes.
3. **Kotlin expression coverage:** add chained member access, `this`, `super`, casts, parenthesized expressions, assignment propagation, generic and nullable type decoding, and visibility rules.
4. **Semantic highlighting:** make the editor consume `SemanticTokenService` directly while retaining Tree-sitter lexical tokens for strings/comments/literals and unresolved identifiers.
5. **Statistics and ranking:** persist completion selection history, same-function/same-file recency, and project frequency; keep deterministic candidate generation.
6. **Additional adapters:** add Java, TypeScript/JavaScript, C/C++, Rust, and Python as grammar plus `LanguageSemanticAdapter` plus shared semantic scenarios. No adapter receives its own index, resolver, completion UI, or ranker.
7. **Optional enrichment:** merge LSP/compiler/SCIP facts by confidence without making any provider mandatory or allowing lower-confidence facts to overwrite deterministic facts.
8. **Optional ONNX scoring:** implement a small CPU ranker behind `CompletionRanker`; generative completion, if added, remains a separate provider.
9. **Legacy removal:** delete flat declaration/usage persistence and regex extraction only after migrated adapters reach equivalent coverage.

## Test contract

`SemanticTestHarness` accepts annotated source containing `/*caret*/` and asserts language-neutral outcomes: symbols, types, definitions, references, and completion candidates. Adapter tests should describe semantic scenarios rather than Tree-sitter node names.

The milestone fixtures cover:

- `val customer = getCustomer()` inferring `Customer` and completing `id`, `name`, and `save`;
- `Customer.kt` plus `Service.kt`, including reverse indexing order;
- `Customer : Entity()` exposing both declared and inherited members;
- stable old snapshots while a new file version is published;
- semantic facts surviving a database reload.

## Design constraints

- Pre-indexing remains fundamental; disk is the project pre-cache and memory is the hot query layer.
- CSTs are transient and never persisted; retaining edited trees in a bounded open-file incremental-parse cache is the next parser milestone.
- Language-specific syntax stays in adapters.
- Candidate generation and member discovery are deterministic.
- ML never owns symbol identity, type truth, definitions, or accessible-member discovery.
- LSP is an optional precision overlay, not the editor architecture.
- A file text change does not imply a workspace reindex.
