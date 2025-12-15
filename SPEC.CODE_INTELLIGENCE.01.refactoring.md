Proposed alignment steps:

- Align API names with the spec (Symbol/SymbolKind/TextRange/NavigationTarget etc.) and make the editor talk to a single EditorIntelligenceService façade that can be backed by LSP or our regex fallback.
- Treat LSP as the primary semantic provider per language; keep the regex scanner as an alternate backend. Select backend per language at runtime.
- Add project-wide storage for definitions/usages (even if in-memory at first) to satisfy cross-file queries and to avoid keyword over-highlighting.
- Extend requests to include diagnostics and refactoring hooks (even stubbed) so the editor surface matches the capabilities list.
- Harden navigation: prefer definition targets by symbol kind/name, not nearest variable (fixes mis-jumps like StyleSheet).
- Add incremental update wiring (file change → invalidate → refresh) so answers stay fresh without reindexing everything.