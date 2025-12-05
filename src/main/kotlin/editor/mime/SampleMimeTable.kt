package editor.mime

import java.util.Locale

/**
 * Extension-to-MIME hints derived from the repository's samples folder.
 * This is intentionally simple: it maps filename extensions to a best-effort
 * MIME and records the associated language name from the sample.
 */
object SampleMimeTable {

    data class Entry(
        val language: String,
        val extension: String, // with leading dot
        val mime: String,
    )

    private val languageToExtension: List<Pair<String, String>> = listOf(
        "powerquery" to "pq",
        "apl" to "apl",
        "erb" to "erb",
        "elixir" to "ex",
        "java" to "java",
        "asm" to "asm",
        "c" to "c",
        "diff" to "diff",
        "codeql" to "ql",
        "crystal" to "cr",
        "desktop" to "desktop",
        "cairo" to "cairo",
        "mdc" to "mdc",
        "handlebars" to "hbs",
        "vyper" to "vy",
        "vue" to "vue",
        "toml" to "toml",
        "glimmer-ts" to "gts",
        "clojure" to "clj",
        "hjson" to "hjson",
        "coq" to "v",
        "shellsession" to "shellsession",
        "hack" to "hack",
        "latex" to "tex",
        "go" to "go",
        "cobol" to "cob",
        "cypher" to "cypher",
        "bibtex" to "bib",
        "fortran-fixed-form" to "f",
        "applescript" to "applescript",
        "prisma" to "prisma",
        "hy" to "hy",
        "riscv" to "s",
        "wasm" to "wasm",
        "jison" to "jison",
        "tsv" to "tsv",
        "marko" to "marko",
        "glsl" to "glsl",
        "awk" to "awk",
        "mermaid" to "mmd",
        "vhdl" to "vhdl",
        "json5" to "json5",
        "fish" to "fish",
        "turtle" to "ttl",
        "less" to "less",
        "dotenv" to "env",
        "postcss" to "pcss",
        "luau" to "luau",
        "d" to "d",
        "julia" to "jl",
        "jsonnet" to "jsonnet",
        "solidity" to "sol",
        "asciidoc" to "adoc",
        "tcl" to "tcl",
        "lua" to "lua",
        "viml" to "vim",
        "qmldir" to "qmldir",
        "gleam" to "gleam",
        "reg" to "reg",
        "yaml" to "yaml",
        "blade" to "blade.php",
        "regexp" to "regexp",
        "bicep" to "bicep",
        "vb" to "vb",
        "matlab" to "m",
        "angular-ts" to "ts",
        "swift" to "swift",
        "stata" to "do",
        "cmake" to "cmake",
        "ini" to "ini",
        "razor" to "cshtml",
        "sdbl" to "sdbl",
        "actionscript-3" to "as",
        "typescript" to "ts",
        "soy" to "soy",
        "r" to "r",
        "po" to "po",
        "python" to "py",
        "coffee" to "coffee",
        "plsql" to "pls",
        "cue" to "cue",
        "abap" to "abap",
        "csv" to "csv",
        "dart" to "dart",
        "cadence" to "cdc",
        "kdl" to "kdl",
        "nim" to "nim",
        "raku" to "raku",
        "berry" to "be",
        "clarity" to "clar",
        "jinja" to "jinja",
        "ruby" to "rb",
        "pkl" to "pkl",
        "wikitext" to "wiki",
        "prolog" to "prolog",
        "verilog" to "v",
        "elm" to "elm",
        "sas" to "sas",
        "jssm" to "jssm",
        "logo" to "logo",
        "gdshader" to "gdshader",
        "javascript" to "js",
        "systemd" to "service",
        "v" to "v",
        "css" to "css",
        "nginx" to "conf",
        "qml" to "qml",
        "perl" to "pl",
        "ssh-config" to "sshconfig",
        "wolfram" to "wl",
        "fsharp" to "fs",
        "puppet" to "pp",
        "nextflow" to "nf",
        "jsonc" to "jsonc",
        "beancount" to "beancount",
        "cpp" to "cpp",
        "emacs-lisp" to "el",
        "racket" to "rkt",
        "svelte" to "svelte",
        "typst" to "typ",
        "fortran-free-form" to "f90",
        "graphql" to "graphql",
        "ada" to "adb",
        "glimmer-js" to "gjs",
        "docker" to "dockerfile",
        "codeowners" to "codeowners",
        "jsx" to "jsx",
        "edge" to "edge",
        "stylus" to "styl",
        "rel" to "rel",
        "gherkin" to "feature",
        "objective-c" to "m",
        "scala" to "scala",
        "move" to "move",
        "gnuplot" to "gp",
        "hlsl" to "hlsl",
        "scss" to "scss",
        "proto" to "proto",
        "kotlin" to "kt",
        "gdresource" to "tres",
        "sql" to "sql",
        "xml" to "xml",
        "rust" to "rs",
        "nushell" to "nu",
        "php" to "php",
        "ara" to "ara",
        "tasl" to "tasl",
        "rst" to "rst",
        "gdscript" to "gd",
        "pascal" to "pas",
        "haskell" to "hs",
        "shaderlab" to "shader",
        "zenscript" to "zs",
        "haml" to "haml",
        "dream-maker" to "dm",
        "system-verilog" to "sv",
        "lean" to "lean",
        "nix" to "nix",
        "erlang" to "erl",
        "vue-vine" to "vine",
        "ts-tags" to "tags",
        "apex" to "cls",
        "vala" to "vala",
        "kusto" to "kql",
        "tex" to "tex",
        "bsl" to "bsl",
        "haxe" to "hx",
        "talonscript" to "talon",
        "zig" to "zig",
        "apache" to "conf",
        "sass" to "sass",
        "groovy" to "groovy",
        "templ" to "templ",
        "hxml" to "hxml",
        "astro" to "astro",
        "ballerina" to "bal",
        "log" to "log",
        "openscad" to "scad",
        "bat" to "bat",
        "shellscript" to "sh",
        "rosmsg" to "msg",
        "twig" to "twig",
        "polar" to "polar",
        "narrat" to "narrat",
        "pug" to "pug",
        "genie" to "gs",
        "wenyan" to "wy",
        "markdown" to "md",
        "jsonl" to "jsonl",
        "llvm" to "ll",
        "hurl" to "hurl",
        "fennel" to "fnl",
        "make" to "mk",
        "hcl" to "hcl",
        "html" to "html",
        "dax" to "dax",
        "imba" to "imba",
        "purescript" to "purs",
        "angular-html" to "html",
        "sparql" to "rq",
        "objective-cpp" to "mm",
        "qss" to "qss",
        "typespec" to "tsp",
        "ocaml" to "ml",
        "csharp" to "cs",
        "tsx" to "tsx",
        "terraform" to "tf",
        "json" to "json",
        "wit" to "wit",
        "splunk" to "spl",
        "scheme" to "scm",
        "xsl" to "xsl",
        "smalltalk" to "st",
        "liquid" to "liquid",
        "vue-html" to "html",
        "common-lisp" to "lisp",
        "wgsl" to "wgsl",
        "mdx" to "mdx",
        "fluent" to "ftl",
        "powershell" to "ps1",
        "mipsasm" to "s",
        "http" to "http",
        "mojo" to "mojo",
    )

    val entries: List<Entry> = buildEntries()
    val extensionToEntry: Map<String, Entry> = buildExtensionMap(entries)
    val extensionToMime: Map<String, String> = extensionToEntry.mapValues { it.value.mime }

    private fun buildEntries(): List<Entry> =
        languageToExtension.map { (language, extRaw) ->
            val normalizedExt = extRaw.removePrefix(".")
            Entry(
                language = language,
                extension = ".$normalizedExt",
                mime = guessMime(language, normalizedExt),
            )
        }

    private fun buildExtensionMap(entries: List<Entry>): Map<String, Entry> {
        val map = mutableMapOf<String, Entry>()
        for (entry in entries) {
            val key = entry.extension.removePrefix(".").lowercase(Locale.ROOT)
            val existing = map[key]
            if (existing == null) {
                map[key] = entry
            } else if (existing.mime == entry.mime) {
                // Same MIME, keep existing entry.
                continue
            } else {
                // Multiple languages share this extension; prefer a generic MIME.
                map[key] = Entry(
                    language = "generic-$key",
                    extension = entry.extension,
                    mime = guessGenericMime(key),
                )
            }
        }
        return map
    }

    private fun guessMime(language: String, ext: String): String {
        val lower = ext.lowercase(Locale.ROOT)
        return when (lower) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "jsx" -> "text/javascript"
            "ts" -> "text/typescript"
            "tsx" -> "text/x-typescript"
            "json", "jsonc", "jsonl", "json5", "jsonnet" -> "application/json"
            "md", "mdx", "mdc" -> "text/markdown"
            "adoc", "asciidoc" -> "text/asciidoc"
            "xml", "xsl" -> "application/xml"
            "yaml", "yml" -> "application/x-yaml"
            "toml" -> "application/toml"
            "env" -> "text/x-env"
            "ini" -> "text/x-ini"
            "sql" -> "application/sql"
            "graphql" -> "application/graphql"
            "proto" -> "text/x-protobuf"
            "tf", "hcl" -> "application/hcl"
            "cmake" -> "text/x-cmake"
            "dockerfile" -> "text/x-dockerfile"
            "cs" -> "text/x-csharp"
            "kt" -> "text/x-kotlin"
            "kts" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "go" -> "text/x-go"
            "rs" -> "text/x-rust"
            "py" -> "text/x-python"
            "rb" -> "text/x-ruby"
            "php" -> "application/x-php"
            "pl", "pm" -> "text/x-perl"
            "sh", "bash", "zsh", "fish" -> "text/x-shellscript"
            "ps1" -> "text/powershell"
            "tsv" -> "text/tab-separated-values"
            "csv" -> "text/csv"
            "ts" -> "text/typescript"
            "sass" -> "text/x-sass"
            "scss" -> "text/x-scss"
            "less" -> "text/x-less"
            "styl" -> "text/x-stylus"
            "vue" -> "text/x-vue"
            "svelte" -> "text/x-svelte"
            "vue-html" -> "text/html"
            "vue-vine" -> "text/x-vue"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "conf" -> "text/plain"
            "log" -> "text/plain"
            "http" -> "message/http"
            else -> "text/x-$language"
        }
    }

    private fun guessGenericMime(ext: String): String =
        when (ext.lowercase(Locale.ROOT)) {
            "m" -> "text/plain"
            "s" -> "text/x-asm"
            "v" -> "text/plain"
            else -> "text/plain"
        }
}
