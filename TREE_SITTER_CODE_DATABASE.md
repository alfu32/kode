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
- `editor.codeintel.frontend/KotlinSemanticAdapter.kt`: Kotlin adapter vertical slice. It extracts declarations, lexical scopes, structured declared/return/super types, imports, calls, chained member references, assignments, casts, literals, `this`/`super`, ownership, and visibility from Tree-sitter.
- `editor.codeintel.frontend/LegacySemanticDeltaFactory.kt`: explicitly low-confidence bridge that pre-caches non-migrated language output in the normalized schema without making it authoritative for semantic queries.
- `editor.codeintel.index/SemanticIndex.kt`: per-file replacement, progressive project resolution, atomic snapshot publication, and snapshot queries.
- `editor.codeintel.index/DependencyGraph.kt`: indexed forward/reverse file edges and cycle-safe transitive dependent traversal.
- `editor.codeintel.index/SemanticInvalidationPlanner.kt`: exported-surface comparison, unresolved-name wake-up, and minimal affected-file planning.
- `editor.codeintel.index/H2SemanticStore.kt`: normalized transactional persistence using Kode's existing embedded project database lifecycle. The schema is independent from the temporary legacy tables.
- `editor.codeintel.resolver/SemanticResolver.kt`: scope-aware symbol resolution, fixed-point local inference, structured types, calls, ownership, inheritance, receiver chains, and access checks.
- `editor.codeintel.resolver/ExpressionTypeResolver.kt`: conservative best-effort expression typing for identifiers, literals, calls, casts, parenthesized expressions, chained members, `this`, and `super`.
- `editor.codeintel.completion/CompletionPipeline.kt`: local, member, type, import, keyword, workspace, and snippet providers plus the candidate engine.
- `editor.codeintel.ml/CompletionRanker.kt`: ranker seam and deterministic first implementation. An ONNX implementation may score candidates but may not generate facts or members.
- `editor.codeintel.semantic/SemanticTokens.kt`: merges persisted Tree-sitter lexical facts with classifications refined from resolved occurrences.
- `editor.codeintel/CodeIntelService.kt`: compatibility facade used by the existing editor and pre-index scan. Kotlin is served from semantic snapshots; legacy extraction remains a low-confidence fallback for languages not migrated yet.

## Persisted model

The semantic schema stores project facts separately:

```text
semantic_files
semantic_scopes
semantic_symbols
semantic_occurrences
semantic_expression_types
semantic_lexical_tokens
semantic_relations
semantic_types
semantic_unresolved_types
semantic_imports
semantic_type_hints
semantic_file_dependencies
```

Indexes cover symbol names and qualified names, symbol file/scope ownership, occurrence file offsets and resolved symbols, relation directions, and scope ranges. Tree-sitter trees and editor buffer text are not stored in these tables.

Every update replaces the affected files' records in one transaction. Full-project pre-indexing batches deltas, resolves the workspace once, and publishes one snapshot rather than exposing partially scanned state. Interactive edits extract one file, compare its exported semantic surface, and re-resolve only that file unless its public surface changed. Resolution and persistence complete before an immutable snapshot is atomically published, so readers continue using the preceding snapshot during the update. Stable IDs are derived from file identity and declaration/source locations; occurrences point to resolved `SymbolId` values instead of relying on text equality.

## Incremental dependency invalidation

The resolved project derives typed file edges for imports, type references, calls, inheritance, member references, and general references. The same graph is available through `SemanticSnapshot` and is persisted as the project pre-cache. Each file also carries a resolution generation.

On an edit:

```text
extract changed file
        |
compare exportedSurfaceHash
        |
        +-- unchanged -> resolve and replace changed file only
        |
        +-- changed ----> follow reverse dependency closure
                              |
                         resolve affected files
                              |
                    one persistence transaction
                              |
                       publish snapshot N+1
```

The exported fingerprint includes exported declarations, their symbol identities, declared/inferred type hints, and imports. Including symbol identity is necessary because IDs currently include source offsets; moving a declaration must wake dependents even if its signature text is unchanged. A newly introduced definition has no prior graph edge, so the planner also wakes files with matching unresolved type, call, occurrence, or import facts. Cycles are handled by a visited-set traversal. Removing or renaming an exported symbol re-resolves dependents and clears stale type/occurrence bindings.

## Current Kotlin vertical slice

The first implemented slice supports:

- class, interface, object, enum, function, method, property, local, and parameter symbols;
- file, package, type, function, and block scopes;
- package/import records and ownership/member relations;
- declared property/parameter/return types;
- generic, nullable, function, union, and primitive type representations;
- fixed-point local inference from literals, constructor/function calls, references, casts, initializers, and later assignments;
- cross-file type resolution independent of indexing order;
- class inheritance and inherited-member traversal;
- definition/reference lookup by resolved symbol identity;
- `receiver.` and `receiver?.` completion for identifiers, calls, parenthesized/cast expressions, chained members, `this`, and `super`;
- private/protected/internal/public flags and scope/inheritance-aware access filtering for resolution and completion;
- conservative union inference for conflicting assignments, with only members shared by every alternative offered;
- deterministic completion scoring;
- semantic token refinement from resolved occurrences;
- Tree-sitter keyword, string, number, and comment tokens merged with semantic identifier classifications;
- persistent reload of extracted and resolved facts.

Unknown types and unresolved occurrences are retained as unknown. Resolution does not invent a confident answer.

## Incremental migration plan

1. **Kotlin foundation (implemented):** emit symbols, scopes, occurrences, imports, unresolved types, and relations; persist per-file deltas; publish snapshots; route Kotlin compatibility queries through the semantic index.
2. **Dependency invalidation (implemented):** persist typed file dependencies and resolution generations, compare `exportedSurfaceHash`, wake matching unresolved files for new definitions, and only re-resolve the transitive dependent closure when the public semantic surface changes.
3. **Kotlin expression coverage (implemented):** support chained and safe member access, `this`, `super`, casts, parentheses, literals, assignment propagation, generic/nullable/function/union type decoding, fixed-point inference, and visibility-aware resolution/completion.
4. **Semantic highlighting (implemented):** persist Tree-sitter keyword/string/number/comment facts, merge them with resolved occurrence kinds in `SemanticTokenService`, and render the merged stream while retaining unknown identifiers.
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
- semantic facts surviving a database reload;
- implementation-only edits leaving dependent resolution generations unchanged;
- exported-surface edits invalidating transitive dependents, including cyclic graphs;
- new definitions waking unresolved files and removed definitions clearing stale bindings;
- dependency edges and generations surviving persistent reload;
- chained property, method-call, and top-level function-call receiver completion;
- cast, parenthesized, `this`, `super`, and safe-call receiver typing;
- direct-reference and later-assignment propagation, including conservative conflicting-type unions;
- generic/nullable type shapes surviving persistence reload;
- private and protected members being accepted in valid owner/subclass contexts and rejected elsewhere.

## Design constraints

- Pre-indexing remains fundamental; disk is the project pre-cache and memory is the hot query layer.
- CSTs are transient and never persisted; retaining edited trees in a bounded open-file incremental-parse cache is the next parser milestone.
- Language-specific syntax stays in adapters.
- Candidate generation and member discovery are deterministic.
- ML never owns symbol identity, type truth, definitions, or accessible-member discovery.
- LSP is an optional precision overlay, not the editor architecture.
- A file text change does not imply a workspace reindex.
