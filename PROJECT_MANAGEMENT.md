# Project Management
Purpose: track project tasks by status with dates and commit provenance.
Fields: each task line includes opened:, done:, closed:, then a commits list on the next line.
Formats: dates are YYYY-MM-DD (SQL date); commits list is `commits:` followed by bullet lines.
Commit bullets: `- <shortsha> <YYYY-MM-DD> <subject>`; leave empty if no commits.
Dates: CLOSED tasks use the first/last commit dates; TODO/DONE use the task's tracked dates.

## TODO
00062 - Bundle a Braille-capable font with the app and document terminal font setup for Windows/Linux. opened: 2025-12-20 done: 9999-12-31 closed: 9999-12-31
commits:

00061 - Audit third-party licenses for Microsoft Store distribution and generate required notices. opened: 2025-12-20 done: 9999-12-31 closed: 9999-12-31
commits:

00060 - Relegate scanning to a background task and allow the splash screen to be dismissed immediately. opened: 2025-12-20 done: 9999-12-31 closed: 9999-12-31
commits:

00059 - Bug: after pressing enter and switching applications we should force a rerender. opened: 2025-12-20 done: 9999-12-31 closed: 9999-12-31
commits:

## INPROGRESS

00058 - Bug: changing workspace does not trigger a full scan, caused by missing DB on new projects ( whcih is created when the app starts). opened: 2025-12-20 done: 9999-12-31 closed: 9999-12-31
commits:

00057 - No items. opened: 2025-12-20 done: 2025-12-20 closed: 2025-12-20
commits:

## CLOSED
00056 - Feature: windows. opened: 2025-12-18 done: 2025-12-19 closed: 2025-12-19
commits:
 - 3ab68f3 2025-12-18 feat(windows): adapt for windows terminals (conhost)
 - 9ef8ba5 2025-12-18 feat(windows): adapt for windows terminals (conhost)
 - e044762 2025-12-18 feat(windows): adapt for windows terminals (conhost)
 - fd5ae8f 2025-12-18 feat(windows): adapt for windows terminals (conhost)
 - 2761ff3 2025-12-19 fix(windows): vt setup on windows
 - 3ab2526 2025-12-19 fix(windows): vt setup on windows
 - 741f58d 2025-12-19 fix(windows): vt setup on windows
 - 984210b 2025-12-19 fix(windows): vt setup on windows
 - b170ad3 2025-12-19 fix(windows): vt setup on windows
 - ebe2b31 2025-12-19 fix(windows): vt setup on windows

00055 - Feature: code intelligence. opened: 2025-12-14 done: 2025-12-19 closed: 2025-12-19
commits:
 - 2d49242 2025-12-14 feat(code-intelligence): WIP
 - 7100ac1 2025-12-14 feat(code-intelligence): WIP
 - 7ac0447 2025-12-14 feat(code-intelligence): WIP
 - f014dce 2025-12-14 feat(code-intelligence): WIP
 - 4d4a49a 2025-12-15 fix(code-intelligence): indexing of tokens
 - 79aebb8 2025-12-15 feat(code-intelligence): WIP
 - 8e5f9fb 2025-12-15 fix(code-intelligence): navigation by click./ctrl-click or arrows
 - ee128fa 2025-12-15 feat(code-intelligence): WIP
 - f1634c9 2025-12-15 feat(code-intelligence): database of tokens,
 - 16b1861 2025-12-16 feat(code-intellignece): methods and fields qualifiers
 - 2e092a9 2025-12-16 feat(code-intellignece): methods and fields qualifiers
 - 36c6fd5 2025-12-16 feat(code-intelligence): packages, item parent detection ( container )
 - 62e8fa4 2025-12-16 feat(code-intelligence): colors
 - 9c85670 2025-12-16 feat(code-intellignece): indexed db
 - 1be8925 2025-12-17 feat(code-intelligence): more packages
 - 3dcc0ba 2025-12-17 feat(code-intel): refine ctrl-click navigation
 - cbe7f46 2025-12-17 feat(code-intelligence): more packages
 - 3af4705 2025-12-18 feat(code-intelligence): tree-sitter
 - 87e1c0e 2025-12-18 feat(code-intelligence): tree-sitter
 - 8bb6fcf 2025-12-18 feat(code-intelligence): tree-sitter grammars and hover metadata
 - 5a5ed9b 2025-12-19 feat(code-intelligence): tree-sitter integration
 - 6e55c48 2025-12-19 feat(code-intelligence): tree-sitter integration

00054 - Feature: tickable. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - fbae1af 2025-12-18 feat(tickable): WIP

00053 - Feature: startup. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - b7ab217 2025-12-18 fix(startup): rendering of the current editor code at start

00052 - Feature: segfault. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - 6f047ce 2025-12-18 fix(segfault): nim ts doesn't work properly,

00051 - Feature: render loop. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - d946ea7 2025-12-18 feat(render-loop): timings

00050 - Feature: image render. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - 0b5dea6 2025-12-18 feat(image-render): quart glyph

00049 - Feature: splash screen. opened: 2025-12-20 done: 2025-12-20 closed: 2025-12-20
commits:

00048 - Feature: code squash. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - 46db908 2025-12-18 feat(code-squash): view code as image rendered with braille glyphs

00047 - Feature: code shape. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - 10e43c8 2025-12-18 feat(code-shape): code shape image
 - cc20f68 2025-12-18 feat(code-shape): code shape image
 - d178d47 2025-12-18 feat(code-shape): code shape image

00046 - Feature: animation frame. opened: 2025-12-18 done: 2025-12-18 closed: 2025-12-18
commits:
 - 1de1954 2025-12-18 feat(animation-frame):

00045 - Feature: shell. opened: 2025-12-17 done: 2025-12-17 closed: 2025-12-17
commits:
 - e787801 2025-12-17 feat(shell): ctrl-t to drop to shell

00044 - Feature: release. opened: 2025-12-08 done: 2025-12-17 closed: 2025-12-17
commits:
 - 37dbb31 2025-12-08 feat(release): KODI rel 1.0.2
 - 541b83c 2025-12-08 feat(release): KODI rel 1.0.0
 - 88ebcbb 2025-12-08 feat(release): KODI rel 1.1.0
 - c8acf40 2025-12-08 feat(release): KODI rel 1.0.0
 - d613f5d 2025-12-17 feat(release): readme

00043 - Feature: info. opened: 2025-12-17 done: 2025-12-17 closed: 2025-12-17
commits:
 - 663f4f7 2025-12-17 feat(info): supported languages list

00042 - Feature: file rename. opened: 2025-12-17 done: 2025-12-17 closed: 2025-12-17
commits:
 - 557e374 2025-12-17 fix(file-rename): fix regression
 - 7767b72 2025-12-17 fix(file-rename): fix regression
 - ca00628 2025-12-17 fix(file-rename): fix regression

00041 - Feature: db. opened: 2025-12-16 done: 2025-12-16 closed: 2025-12-16
commits:
 - 528abef 2025-12-16 feat(db): indexing, auto cleanup

00040 - Feature: lsp manager. opened: 2025-12-15 done: 2025-12-15 closed: 2025-12-15
commits:
 - 30b8638 2025-12-15 ix(lsp-manager): view

00039 - Feature: lsp. opened: 2025-12-15 done: 2025-12-15 closed: 2025-12-15
commits:
 - 3034bda 2025-12-15 feat(LSP): management, WIP
 - 6cd816c 2025-12-15 feat(lsp): integration
 - 8388ad5 2025-12-15 feat(lsp): integration, WIP
 - 97c447b 2025-12-15 feat(lsp): integration
 - b9f5b85 2025-12-15 feat(lsp): LSP integration planning
 - d52f7a2 2025-12-15 feat(lsp): integration
 - fb129dc 2025-12-15 feat(lsp): integration, WIP

00038 - Feature: editor. opened: 2025-12-05 done: 2025-12-15 closed: 2025-12-15
commits:
 - 1c1e9fe 2025-12-05 feat(editor)
 - 41ec8c1 2025-12-12 feat(editor): overflowing text wrapping
 - 13c5f87 2025-12-15 ix(editor): recent files restore viewport and cursor positions
 - fc00016 2025-12-15 fix(editor): add the same padding of the currrent line to the next line when we hit NEWLINE

00037 - Feature: syntax. opened: 2025-12-07 done: 2025-12-13 closed: 2025-12-13
commits:
 - 07f4126 2025-12-07 fix(syntax): detection and coloring
 - d10d96a 2025-12-07 fix(syntax): detection and coloring
 - f43faa2 2025-12-12 feat(syntax): h hpp hh headers added
 - ecbc764 2025-12-13 feat(syntax): h hpp hh headers added

00036 - Feature: lang. opened: 2025-12-13 done: 2025-12-13 closed: 2025-12-13
commits:
 - b3e410f 2025-12-13 feat(lang): regex errors are logged instead of breaking the app.

00035 - Feature: image. opened: 2025-12-13 done: 2025-12-13 closed: 2025-12-13
commits:
 - bc0bf06 2025-12-13 feat(image): bixel image renderer

00034 - Feature: terminal. opened: 2025-12-08 done: 2025-12-12 closed: 2025-12-12
commits:
 - 2e8e721 2025-12-08 revert(terminal): postponed integration, it doesnt work properly
 - fd491c9 2025-12-08 feat(terminal)
 - 45d975f 2025-12-12 refactor(terminal): removed the built-in terminal

00033 - Feature: mime. opened: 2025-12-05 done: 2025-12-12 closed: 2025-12-12
commits:
 - c0dad47 2025-12-05 feat(mime)
 - 9d9400f 2025-12-12 fix(mime): h files are c sources

00032 - Feature: main. opened: 2025-12-12 done: 2025-12-12 closed: 2025-12-12
commits:
 - aa5e3e6 2025-12-12 fix(main): quit on ctrl-q only

00031 - Feature: git diff. opened: 2025-12-12 done: 2025-12-12 closed: 2025-12-12
commits:
 - 7d771dd 2025-12-12 feat(git-diff): display git diff per file

00030 - Feature: git. opened: 2025-12-12 done: 2025-12-12 closed: 2025-12-12
commits:
 - 57c3218 2025-12-12 fix(git): supports workspaces that have no git
 - 80fc997 2025-12-12 fix(git): supports workspaces that have no git
 - 89cc3e0 2025-12-12 fix(git): supports workspaces that have no git
 - cd8568d 2025-12-12 fix(git): supports workspaces that have no git
 - ed389fe 2025-12-12 fix(git): supports workspaces that have no git

00029 - Feature: diff editor. opened: 2025-12-12 done: 2025-12-12 closed: 2025-12-12
commits:
 - 6a36e80 2025-12-12 fix(diff-editor): rendering logic
 - 962ca6f 2025-12-12 fix(diff-editor): rendering logic
 - ab03c09 2025-12-12 feat(diff-editor): highlighted diff chunks
 - b307576 2025-12-12 feat(diff-editor):
 - da751f5 2025-12-12 feat(diff-editor): wrap lines
 - fb5f13d 2025-12-12 feat(diff-editor): squashed/unsquashed view

00028 - Feature: commits. opened: 2025-12-12 done: 2025-12-12 closed: 2025-12-12
commits:
 - 8c5abf7 2025-12-12 feat(commits): affected files
 - 8e8a335 2025-12-12 feat(commits): affected files

00027 - Feature: workspace. opened: 2025-12-11 done: 2025-12-11 closed: 2025-12-11
commits:
 - 9c5a66d 2025-12-11 feat(workspace): switch workspace
 - aaedc5e 2025-12-11 feat(workspace): first argument designates the workspace folder
 - b61ffb6 2025-12-11 feat(workspace): switch workspace

00026 - Feature: documentation. opened: 2025-12-05 done: 2025-12-10 closed: 2025-12-10
commits:
 - 56d8d05 2025-12-05 feat(synchro): README.md
 - 9e3df9c 2025-12-09 feat(readme): latest features
 - c932019 2025-12-09 feat(readme): latest features
 - ccd0d00 2025-12-09 feat(doc): documented latest features
 - f861dc2 2025-12-10 feat(readme)

00025 - Feature: compat. opened: 2025-12-10 done: 2025-12-10 closed: 2025-12-10
commits:
 - 6f9c0e6 2025-12-10 feat(compat): compatibility to java 17
 - 7b7f2c6 2025-12-10 feat(compat): compatibility to java 17

00024 - Feature: about. opened: 2025-12-10 done: 2025-12-10 closed: 2025-12-10
commits:
 - 4eb22f3 2025-12-10 feat(about): about : version and credits

00023 - Feature: tests. opened: 2025-12-09 done: 2025-12-09 closed: 2025-12-09
commits:
 - 0807611 2025-12-09 fix(tests): using stock NoopRenderer

00022 - Feature: search and replace global. opened: 2025-12-09 done: 2025-12-09 closed: 2025-12-09
commits:
 - 08ab4cf 2025-12-09 feat(search-and-replace-global):
 - 3b5e0ee 2025-12-09 feat(search-and-replace-global):
 - 4bb9564 2025-12-09 feat(search-and-replace-global):
 - 79a69d6 2025-12-09 feat(search-and-replace-global): WIP
 - e1ae111 2025-12-09 feat(search-and-replace-global): WIP

00021 - Feature: search and replace. opened: 2025-12-08 done: 2025-12-09 closed: 2025-12-09
commits:
 - 0a16aad 2025-12-08 feat(search-and-replace): regex
 - 6fd64b1 2025-12-08 feat(search-and-replace):
 - ff2f0c8 2025-12-09 feat(search-and-replace): ctrl-f selection

00020 - Feature: global search. opened: 2025-12-09 done: 2025-12-09 closed: 2025-12-09
commits:
 - 2b35613 2025-12-09 feat(global-search): file filter

00019 - Feature: filesystem. opened: 2025-12-09 done: 2025-12-09 closed: 2025-12-09
commits:
 - 0e6527c 2025-12-09 feat(filesystem): file/folder management ( create, delete,rename)
 - 12fd1ec 2025-12-09 feat(filesystem): file/folder management ( create, delete,rename)
 - c7805ca 2025-12-09 feat(filesystem): file/folder management ( create, delete,rename)
 - ec29383 2025-12-09 feat(filesystem): autorefresh of file tree and git panel

00018 - Feature: bundling. opened: 2025-12-09 done: 2025-12-09 closed: 2025-12-09
commits:
 - fbe9be8 2025-12-09 fix(bundling): app css in the right folder

00017 - Feature: undo/redo. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - 32e782f 2025-12-08 refactor(undo/redo): smaller footprint
 - 451bb47 2025-12-08 refactor(undo/redo): smaller footprint
 - 81c2622 2025-12-08 feat(undo/redo): undo redo

00016 - Feature: save. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - b9cecb7 2025-12-08 feat(save): ctrl-s saves the buffer

00015 - Feature: recent files. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - 0a7f937 2025-12-08 feat(recent-files): persisted state optimisation - debounced
 - 0f01a5a 2025-12-08 fix(recent-files): fix scrolling
 - 277049f 2025-12-08 feat(recent-files): management :
 - 2cd1f33 2025-12-08 feat(recent-files): management :
 - 38a8305 2025-12-08 feat(recent-files): persisted state
 - 4ba9103 2025-12-08 feat(recent-files): persisted state optimisation - debounced
 - 65791eb 2025-12-08 feat(recent-files): management :
 - 8be1b93 2025-12-08 feat(recent-files): files list
 - aa11cdc 2025-12-08 feat(recent-files): files list
 - d54cf5e 2025-12-08 feat(recent-files): persisted state
 - f6d476e 2025-12-08 feat(recent-files): management :

00014 - Feature: name. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - 529b2ba 2025-12-08 feat(name): KODI

00013 - Feature: grammars. opened: 2025-12-05 done: 2025-12-08 closed: 2025-12-08
commits:
 - 018ee49 2025-12-05 feat(grammars): definitions
 - 3ce86fb 2025-12-05 feat(grammars): binding to editor
 - 93d77be 2025-12-05 feat(grammars): definitions
 - ae47514 2025-12-05 feat(grammars): binding to editor, identifying grammars
 - c36c0bd 2025-12-05 feat(grammars): definitions
 - c9e6fe3 2025-12-05 feat(grammars): binding to editor, identifying grammars
 - 658f2dc 2025-12-08 feat(grammars): added a text file grammar and handling default for text mime types

00012 - Feature: git panel. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - 82f0c66 2025-12-08 feat(git panel): implementation
 - f041834 2025-12-08 feat(git panel): implementation

00011 - Feature: commit. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - 968d63f 2025-12-08 fix(commit): double tap on commit

00010 - Feature: coloring. opened: 2025-12-07 done: 2025-12-08 closed: 2025-12-08
commits:
 - 244cf73 2025-12-07 feat(coloring): detection and coloring
 - b94fad4 2025-12-07 feat(coloring): detection and coloring
 - d1c1fce 2025-12-07 feat(coloring): detection and coloring
 - 1ce7c9b 2025-12-08 feat(coloring): detection and coloring
 - 2865b33 2025-12-08 feat(coloring): detection and coloring
 - 3682493 2025-12-08 feat(coloring): detection and coloring
 - 757a581 2025-12-08 feat(coloring): detection and coloring
 - 837ba38 2025-12-08 feat(coloring): detection and coloring
 - a808c23 2025-12-08 feat(coloring): detection and coloring
 - aeef066 2025-12-08 feat(coloring): detection and coloring

00009 - Feature: cleanup. opened: 2025-12-05 done: 2025-12-08 closed: 2025-12-08
commits:
 - 42ec057 2025-12-05 refactor(cleanup)
 - 81c3fa6 2025-12-05 refactor(cleanup)
 - 93a5710 2025-12-05 refactor(cleanup)
 - 98f3adc 2025-12-05 refactor(cleanup)
 - c633515 2025-12-08 refactor(cleanup): moved image assets

00008 - Feature: applystyle. opened: 2025-12-08 done: 2025-12-08 closed: 2025-12-08
commits:
 - f2e2e64 2025-12-08 refactor(applyStyle): applyStyle and withStyle did the same thing,

00007 - Feature: vdom. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 40f9d1b 2025-12-05 refactor(vdom)
 - 7a070a6 2025-12-05 refactor(vdom)
 - ddb36a2 2025-12-05 refactor(vdom)

00006 - Feature: source code samples. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - b595527 2025-12-05 feat(source-code-samples)
 - d15c6a9 2025-12-05 feat(source-code-samples)

00005 - Feature: slider control. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 3c1a3fb 2025-12-05 feat(slider-control): impl controls, aspect ratio

00004 - Feature: mime types. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 5a6f25e 2025-12-05 feat(mime-types)

00003 - Feature: init. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 03bf7ae 2025-12-05 feat(init)
 - 2b4f850 2025-12-05 feat(init)
 - 7319c61 2025-12-05 feat(init)
 - 8e4e32e 2025-12-05 feat(init)
 - c0b7470 2025-12-05 feat(init)

00002 - Feature: image viewer. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 13f1a87 2025-12-05 feat(image-viewer): impl controls, aspect ratio
 - 1e5b0d6 2025-12-05 feat(image-viewer): impl controls, aspect ratio
 - 672de2a 2025-12-05 feat(image-viewer): impl controls, aspect ratio
 - 725773c 2025-12-05 feat(image-viewer): impl controls, aspect ratio
 - 891a15f 2025-12-05 feat(image-viewer): impl

00001 - Feature: highlight. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 4a9638d 2025-12-05 feat(highlight): integration
 - 77cb2dd 2025-12-05 feat(highlight): integration

00000 - Feature: editors. opened: 2025-12-05 done: 2025-12-05 closed: 2025-12-05
commits:
 - 0c3cb2c 2025-12-05 feat(editors): hex editor
 - 23b7c96 2025-12-05 feat(editors): hex editor
 - 2d48489 2025-12-05 feat(editors)
 - 48ba1be 2025-12-05 feat(editors)
 - 794fd63 2025-12-05 feat(editors)
 - 7f2d686 2025-12-05 feat(editors)
 - 995706b 2025-12-05 feat(editors): hex editor: generic byte buffer impl
 - cf2f43e 2025-12-05 feat(editors): hex editor


