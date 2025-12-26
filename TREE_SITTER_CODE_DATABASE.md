Below is a **complete, minimal, and coherent base package** plus the **authoritative design document** you asked for.

the base entities and contracts are in the file `src/main/kotlin/editor/lang/base.kt`

# Tree-Sitter Code Intelligence Database

## 1. Purpose

This system extracts **project-wide code intelligence** from Tree-sitter parse trees
and produces a **flat, ordered list of identifier occurrences**.

Each occurrence records:
- identifier (fully-qualified)
- kind (what it is)
- type (declaration or usage)
- file name
- 0-based line number
- 0-based character position

The output is precise, deterministic, and free of false hits by construction.

---

## 2. Final Output Contract

The **only persisted artifact** is:

```
IdentifierOccurrence {
    identifier: String
    kind: SymbolKind
    type: SymbolType
    fileName: String
    lineNumber: Int
    charPosition: Int
}

```

No trees, graphs, or symbol tables are stored.
All richer structures are transient.

---

## 3. Core Constraints

- Tree-sitter provides syntax, not semantics
- Node meaning is context-dependent
- Language differences are unavoidable
- False positives are unacceptable
- Identifier resolution must be scope-aware

---

## 4. High-Level Architecture

```

Tree-sitter CST
↓
Linearization (path-aware, ordered)
↓
Language Adapter (node classification)
↓
Scoped Aggregation (temporary)
↓
FLAT LIST<IdentifierOccurrence>

```

---

## 5. Responsibilities Breakdown

### 5.1 Tree-sitter Layer
- Parse source files
- Provide concrete syntax trees
- Supply byte offsets and point locations

### 5.2 Linearization
- DFS traversal
- Record CST path and order
- Preserve full context for later interpretation

### 5.3 Language Adapters
Language-specific, syntax-aware logic only.

They must:
- classify nodes into (Kind, Type)
- decide whether a declaration opens a scope
- extract identifier text

They must NOT:
- resolve symbols globally
- track imports across files
- infer types beyond syntax

---

## 6. Symbol Kinds

```

PACKAGE
CLASS
TYPE
ANONYMOUS_OBJECT
FUNCTION
METHOD
FIELD
CONSTANT
VARIABLE

```

---

## 7. Symbol Types

```

DECLARATION
USAGE

```

---

## 8. Scope Handling

Scopes are tracked **temporarily** using a stack:

- Packages
- Classes
- Functions / methods
- Anonymous objects (language-dependent)

Fully-qualified identifiers are constructed by joining scope names.

---

## 9. Why a Flat List

A flat list:
- avoids semantic over-commitment
- is trivially indexable
- supports incremental rebuilds
- allows multiple interpretations later
- scales across languages uniformly

Advanced queries (find usages, implementations, renames)
are derived **from the list**, not embedded into it.

---

## 10. Incremental Parsing Rule

If a file is reparsed:
- discard all previous occurrences from that file
- regenerate occurrences from scratch

Location stability is guaranteed only per parse.

---

## 11. Adapter Research Checklist (per language)

For each language, determine:
- declaration node types
- usage node types
- identifier node forms
- scope-opening constructs
- anonymous scope semantics

Adapters are expected to evolve incrementally.

---

## 12. Non-Goals

This system does NOT:
- perform full type inference
- build a complete symbol graph
- replace a compiler front-end
- guarantee semantic correctness beyond syntax

---

## 13. Summary

This architecture:
- accepts Tree-sitter’s limitations
- avoids false hits by design
- scales across languages
- keeps storage minimal
- keeps semantics explicit and auditable

It is intentionally conservative, explicit, and correct.
```

---

## State of completion

You now have:

* a **clean base package**
* a **hard output contract**
* a **language-agnostic core**
* a **clear adapter boundary**
* a **precise requirements document**

Next logical steps (when you want them):

* Kotlin adapter deep-spec
* identifier canonicalization rules
* import / using resolution strategy
* query patterns over the flat list


