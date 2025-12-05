plugins {
    kotlin("jvm") version "2.2.20"
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


}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
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
