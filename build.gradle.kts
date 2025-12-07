import java.io.File

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "org.github.alfu32.kte"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.eclipse.org/content/repositories/tm4e-snapshots/") {
        name = "tm4e-snapshots"
        mavenContent { snapshotsOnly() }
    }


}

dependencies {
    testImplementation(kotlin("test"))
    // implementation("org.jline:jline:3.27.1")
    // https://mvnrepository.com/artifact/org.eclipse.jgit/org.eclipse.jgit
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    // https://mvnrepository.com/artifact/com.soywiz.korlibs.korim/korim
    implementation("com.soywiz.korlibs.korim:korim:4.0.10")
    // https://mvnrepository.com/artifact/com.soywiz.korlibs.korio/korio
    implementation("com.soywiz.korlibs.korio:korio:4.0.10")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    //implementation("org.eclipse.tm4e:tm4e-core:0.17.2-SNAPSHOT")
    // implementation("org.eclipse.tm4e:tm4e:0.17.2-SNAPSHOT")
    // codex resume 019aef55-2d9e-77d0-9785-c3c47e6226c7
    implementation("org.eclipse:org.eclipse.tm4e.core:0.17.2-SNAPSHOT")
    implementation("org.eclipse:org.eclipse.tm4e:0.17.2-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")


}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

val regexGrammarOutput = layout.buildDirectory.dir("generated/regex-grammars")
val generatedGrammarSources = layout.buildDirectory.dir("generated/sources/regexGrammars")
val regexGrammarCssOutput = layout.buildDirectory.dir("generated/regex-grammars/css")

val generateRegexGrammarMaps = tasks.register("generateRegexGrammarMaps") {
    group = "tools"
    description = "Extract token-name -> regex maps from TextMate grammars into regex-grammars/*.json"
    val outputDir = regexGrammarOutput.map { it.dir("regex-grammars").asFile }
    inputs.files(fileTree("grammars") { include("*.json") })
    outputs.dir(regexGrammarOutput)
    doLast {
        val grammarsDir = project.layout.projectDirectory.dir("grammars").asFile
        val outDir = outputDir.get()
        outDir.mkdirs()
        val languages = mutableListOf<String>()
        grammarsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
            if (file.name == "package.json") return@forEach
            val langId = file.name.removeSuffix(".json")
            val text = file.readText()
            try {
                val parsed = groovy.json.JsonSlurper().parseText(text) as? Map<*, *> ?: return@forEach
                val matches = linkedMapOf<String, MutableList<String>>()
                val extensions = (parsed["fileTypes"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                fun addMatch(name: String?, match: String?) {
                    if (name.isNullOrBlank() || match.isNullOrBlank()) return
                    matches.getOrPut(name) { mutableListOf() }.add(match)
                }
                fun walk(node: Any?) {
                    val map = node as? Map<*, *> ?: return
                    val name = map["name"] as? String
                    val match = map["match"] as? String
                    addMatch(name, match)
                    (map["patterns"] as? List<*>)?.forEach(::walk)
                    (map["repository"] as? Map<*, *>)?.values?.forEach(::walk)
                    listOf("captures", "beginCaptures", "endCaptures").forEach { key ->
                        (map[key] as? Map<*, *>)?.values?.forEach(::walk)
                    }
                }
                walk(parsed)
                val combined = matches.mapValues { (_, list) ->
                    list.distinct().joinToString("|") { "(?:$it)" }
                }
                val output = mapOf(
                    "language" to langId,
                    "extensions" to extensions,
                    "tokens" to combined
                )
                val outFile = File(outDir, "$langId.json")
                outFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(output)))
                languages += langId
                logger.lifecycle("Generated regex grammar for $langId with ${combined.size} patterns")
            } catch (e: Exception) {
                logger.warn("Skipping ${file.name}: ${e.message}")
            }
        }
        val indexFile = File(outDir, "index.json")
        indexFile.writeText(
            groovy.json.JsonOutput.prettyPrint(
                groovy.json.JsonOutput.toJson(mapOf("languages" to languages.sorted()))
            )
        )
        println("Regex grammar maps written to ${outDir.absolutePath}")
    }
}

val generateRegexGrammarSources = tasks.register("generateRegexGrammarSources") {
    group = "tools"
    description = "Generate Kotlin sources for regex-based grammars under editor.grammars.generated"
    val srcOut = generatedGrammarSources.map { it.dir("kotlin") }
    inputs.dir(regexGrammarOutput)
    outputs.dir(generatedGrammarSources)
    dependsOn(generateRegexGrammarMaps)
    doLast {
        val inputDir = regexGrammarOutput.get().dir("regex-grammars").asFile
        val outDir = srcOut.get().asFile
        outDir.mkdirs()
        val indexFile = File(inputDir, "index.json")
        if (!indexFile.exists()) {
            logger.warn("No regex grammar index found at ${indexFile.absolutePath}")
            return@doLast
        }
        val index = groovy.json.JsonSlurper().parseText(indexFile.readText()) as? Map<*, *>
        val languages = (index?.get("languages") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val packageDir = File(outDir, "editor/grammars/generated")
        packageDir.mkdirs()
        val providerFile = File(packageDir, "GeneratedRegexProvider.kt")
        val entries = languages.mapNotNull { lang ->
            val file = File(inputDir, "$lang.json")
            if (!file.exists()) return@mapNotNull null
            val obj = groovy.json.JsonSlurper().parseText(file.readText()) as? Map<*, *> ?: return@mapNotNull null
            val tokens = obj["tokens"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val extensions = (obj["extensions"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val tokenMap = mutableMapOf<String, String>()
            tokens.forEach { (k, v) ->
                val key = k?.toString() ?: return@forEach
                val value = v?.toString() ?: return@forEach
                tokenMap[key] = value
            }
            GeneratedEntry(lang, extensions, tokenMap)
        }
        entries.forEach { entry ->
            writeLanguageFile(packageDir, entry)
        }
        providerFile.writeText(renderProvider(entries))
        logger.lifecycle("Generated ${entries.size} regex grammars into ${providerFile.absolutePath}")
    }
}

val generateRegexGrammarCss = tasks.register("generateRegexGrammarCss") {
    group = "tools"
    description = "Generate Darcula-like CSS styles for each regex grammar"
    val outputDir = regexGrammarCssOutput.map { it.asFile }
    inputs.dir(regexGrammarOutput)
    outputs.dir(regexGrammarCssOutput)
    dependsOn(generateRegexGrammarMaps)
    doLast {
        val inputDir = regexGrammarOutput.get().dir("regex-grammars").asFile
        val outDir = outputDir.get()
        outDir.mkdirs()
        val indexFile = File(inputDir, "index.json")
        if (!indexFile.exists()) {
            logger.warn("No regex grammar index found at ${indexFile.absolutePath}")
            return@doLast
        }
        val index = groovy.json.JsonSlurper().parseText(indexFile.readText()) as? Map<*, *>
        val languages = (index?.get("languages") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        languages.forEach { lang ->
            val jsonFile = File(inputDir, "$lang.json")
            if (!jsonFile.exists()) return@forEach
            val obj = groovy.json.JsonSlurper().parseText(jsonFile.readText()) as? Map<*, *> ?: return@forEach
            val tokens = (obj["tokens"] as? Map<*, *>)?.keys?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
            val css = buildLanguageCss(lang, tokens)
            File(outDir, "$lang.css").writeText(css)
        }
        logger.lifecycle("Generated CSS styles for ${languages.size} grammars into ${outDir.absolutePath}")
    }
}
generateRegexGrammarSources.configure {
    mustRunAfter(generateRegexGrammarCss)
}

data class GeneratedEntry(val language: String, val extensions: List<String>, val tokens: Map<String, String>)

fun safeConstName(language: String): String =
    language.uppercase()
        .replace(Regex("[^A-Z0-9]+"), "_")
        .trim('_')
        .let { if (it.isBlank()) "LANG" else it }

fun writeLanguageFile(packageDir: File, entry: GeneratedEntry) {
    fun esc(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
    val constName = safeConstName(entry.language)
    val extList = entry.extensions.joinToString(", ") { "\"${esc(it)}\"" }
    val tokenMap = entry.tokens.entries.joinToString(",\n        ") { "\"${esc(it.key)}\" to \"${esc(it.value)}\"" }
    val file = File(packageDir, "${constName}.kt")
    file.writeText(
        """
        package editor.grammars.generated

        import editor.grammars.RegexGrammarDefinition

        internal val ${constName}: RegexGrammarDefinition = RegexGrammarDefinition(
            language = "${esc(entry.language)}",
            extensions = listOf($extList),
            tokens = mapOf(
        $tokenMap
            )
        )
        """.trimIndent()
    )
}

fun renderProvider(entries: List<GeneratedEntry>): String {
    fun esc(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
    val registrations = entries.joinToString(",\n                ") { safeConstName(it.language) }
    return """
        package editor.grammars.generated

        import editor.grammars.RegexGrammarDefinition
        import editor.grammars.RegexSyntaxProvider

        object GeneratedRegexProvider : RegexSyntaxProvider(
            listOf(
                $registrations
            )
        )
    """.trimIndent()
}

fun buildLanguageCss(language: String, qualifiers: Set<String>): String {
    val palette = mapOf(
        "comment" to "#808080",
        "string" to "#6A8759",
        "regex" to "#C6794C",
        "number" to "#6897BB",
        "constant" to "#9876AA",
        "keyword" to "#CC7832",
        "operator" to "#A9B7C6",
        "punctuation" to "#A9B7C6",
        "tag" to "#E8BF6A",
        "attribute" to "#A5C261",
        "property" to "#A5C261",
        "type" to "#A9B7C6",
        "class" to "#A9B7C6",
        "interface" to "#A9B7C6",
        "function" to "#FFC66D",
        "method" to "#FFC66D",
        "variable" to "#A9B7C6",
        "parameter" to "#A9B7C6",
        "namespace" to "#A9B7C6",
        "module" to "#A9B7C6",
        "annotation" to "#BBB529",
        "decorator" to "#BBB529",
        "boolean" to "#CC7832"
    )
    fun chooseColor(q: String): String {
        val lower = q.lowercase()
        fun has(term: String) = lower.contains(term)
        return when {
            has("comment") -> palette["comment"]!!
            has("string") -> palette["string"]!!
            has("regex") -> palette["regex"]!!
            has("number") || has("numeric") -> palette["number"]!!
            has("keyword") -> palette["keyword"]!!
            has("boolean") -> palette["boolean"]!!
            has("constant") -> palette["constant"]!!
            has("annotation") || has("decorator") -> palette["annotation"]!!
            has("operator") -> palette["operator"]!!
            has("punctuation") || has("delimiter") || has("brace") || has("bracket") -> palette["punctuation"]!!
            has("function") || has("method") -> palette["function"]!!
            has("parameter") -> palette["parameter"]!!
            has("variable") || has("identifier") -> palette["variable"]!!
            has("attribute") || has("property") -> palette["attribute"]!!
            has("tag") || has("element") -> palette["tag"]!!
            has("type") || has("class") || has("interface") || has("enum") -> palette["type"]!!
            has("namespace") || has("module") || has("package") -> palette["namespace"]!!
            else -> "#b6b9c5"
        }
    }
    fun toSelector(q: String): String =
        q.replace(' ', '_').replace(":", "-").replace(",", "-").replace(".", "-")
    val body = qualifiers.sorted().joinToString("\n\n") { q ->
        val selector = toSelector(q)
        val fg = chooseColor(q)
        ".$selector {\n  fg: $fg;\n}\n"
    }
    return "/* Auto-generated Darcula-like colors for $language */\n$body\n"
}

sourceSets.main {
    java.srcDir(generatedGrammarSources.map { it.dir("kotlin") })
    resources.srcDir(regexGrammarOutput)
    resources.srcDir(regexGrammarCssOutput)
}

tasks.named("processResources") {
    dependsOn(generateRegexGrammarMaps)
    dependsOn(generateRegexGrammarCss)
}

tasks.named("compileKotlin") {
    dependsOn(generateRegexGrammarSources)
}

tasks.register("generateTmScopes") {
    group = "tools"
    description = "Extract TextMate scopes from grammars/*.json into grammars/tm-scopes.css"
    val outputFile = project.layout.projectDirectory.file("grammars/tm-scopes.css")
    inputs.files(fileTree("grammars") { include("*.json") })
    outputs.file(outputFile)
    doLast {
        val grammarsDir = project.layout.projectDirectory.dir("grammars")
        val scopes = linkedSetOf<String>()
        grammarsDir.asFile.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
            if (file.name == "package.json") return@forEach
            val text = file.readText()
            try {
                val json = groovy.json.JsonSlurper().parseText(text)
                fun walk(node: Any?) {
                    when (node) {
                        is Map<*, *> -> {
                            node["name"]?.let { if (it is String) scopes += it }
                            (node["patterns"] as? List<*>)?.forEach(::walk)
                            (node["repository"] as? Map<*, *>)?.values?.forEach(::walk)
                            listOf("captures", "beginCaptures", "endCaptures").forEach { key ->
                                (node[key] as? Map<*, *>)?.values?.forEach(::walk)
                            }
                        }
                        is List<*> -> node.forEach(::walk)
                    }
                }
                walk(json)
            } catch (e: Exception) {
                logger.warn("Skipping ${file.name}: ${e.message}")
            }
        }
        val css = buildString {
            scopes.toSortedSet().forEach { scope ->
                val selector = "." + scope.replace(' ', '_').replace(":", "-").replace(",", "-")
                append(selector).append(" {\n")
                append("  fg: #2125f8;\n")
                append("}\n\n")
            }
        }
        outputFile.asFile.writeText(css)
        println("Wrote ${outputFile.asFile} with ${scopes.size} scopes")
    }
}
/**
 * Build a self-contained executable JAR (fat / uber JAR).
 */
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat JAR with all dependencies"

    archiveBaseName.set("kt-tui-edit")
    archiveClassifier.set("all")        // so name ends with -all.jar

    // optional: if you do not want the version in filename:
    // archiveVersion.set("")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "editor.app.MainKt"
    }

    // include compiled classes/resources of this project
    from(sourceSets.main.get().output)

    // include all runtime dependencies
    val runtimeClasspath = configurations.runtimeClasspath.get()
    from({
        runtimeClasspath
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        // CRITICAL: exclude all signature-related metadata
        exclude("META-INF/*.SF")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.EC")

        // also safe to exclude unused Maven metadata
        exclude("META-INF/*.kotlin_module")
    }
}

tasks.register<Copy>("distBundle") {
    group = "distribution"
    description = "Bundle fat jar and generated grammar CSS into dist/"
    dependsOn("fatJar", generateRegexGrammarCss)
    val distDir = layout.projectDirectory.dir("dist")
    from(layout.buildDirectory.file("libs/kt-tui-edit-all.jar")) {
        into("")
    }
    from(regexGrammarCssOutput) {
        into("grammars-css")
    }
    into(distDir)
    doFirst { distDir.asFile.mkdirs() }
}
