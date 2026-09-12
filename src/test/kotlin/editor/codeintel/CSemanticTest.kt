package editor.codeintel

import editor.codeintel.frontend.CSemanticAdapter
import editor.codeintel.frontend.SourceFile
import editor.codeintel.index.H2SemanticStore
import editor.codeintel.index.SemanticIndex
import editor.codeintel.model.*
import editor.codeintel.model.SymbolKind
import editor.codeintel.resolver.BestEffortExpressionTypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CSemanticTest {
    @Test
    fun reResolvesConsumersAfterMovingAndChangingStructFields() {
        val adapter = CSemanticAdapter()
        val index = SemanticIndex()
        val header = SourceFile("model.h", "c", "typedef struct aaa_s { int count; } aaa_t;", 1L)
        val consumer = SourceFile("use.c", "c", "aaa_t value; void run() { value.count = 1; }", 1L)
        index.applyAll(listOf(adapter.extract(header), adapter.extract(consumer)))
        val old = index.snapshot()
        val file = assertNotNull(old.file(consumer.path))
        val snapshot = index.apply(adapter.extract(header.copy(text = "// moved\n" + header.text.replace("int count;", "int count; int extra;"), version = 2L)))
        val value = snapshot.symbols(file.id).single { it.name == "value" }
        val type = assertNotNull(snapshot.type(value.declaredTypeId)).ref
        assertEquals(setOf("count", "extra"), snapshot.members(type).map { it.name }.toSet())
        val count = snapshot.workspaceSymbols().single { it.name == "count" }
        assertTrue(snapshot.occurrences(count.id).any { it.fileId == file.id })
        assertTrue(snapshot.resolutionGeneration(file.id) > old.resolutionGeneration(file.id))
    }

    @Test
    fun resolvesTagAliasAndPointerMembersAcrossFilesAndReload() {
        val adapter = CSemanticAdapter()
        val store = H2SemanticStore { "jdbc:h2:mem:c-tags;DB_CLOSE_DELAY=-1" }
        val index = SemanticIndex(store)
        val header = SourceFile("types.h", "c", "typedef struct aaa_s { int count; } aaa_t;", 1)
        val source = SourceFile("main.c", "c", """
            #include "types.h"
            void run(aaa_t *ptr) {
                struct aaa_s tagged;
                aaa_t aliased;
                tagged.count = aliased.count;
                ptr->count = 1;
            }
        """.trimIndent(), 1)
        index.applyAll(listOf(adapter.extract(header), adapter.extract(source)))
        for (snapshot in listOf(index.snapshot(), SemanticIndex(store).load())) {
            val symbols = snapshot.workspaceSymbols().toList()
            val tag = symbols.single { it.name == "aaa_s" }
            val alias = symbols.single { it.name == "aaa_t" }
            assertEquals(SymbolKind.STRUCT, tag.kind)
            assertEquals(SymbolKind.TYPE_ALIAS, alias.kind)
            assertTrue(snapshot.relations(alias.id, RelationKind.ALIAS_OF).any { it.to == tag.id })
            for (name in listOf("ptr", "tagged", "aliased")) {
                val variable = symbols.single { it.name == name }
                val type = assertNotNull(snapshot.type(variable.declaredTypeId)).ref
                assertEquals(listOf("count"), snapshot.members(type).map { it.name }.toList(), name)
            }
            val member = symbols.single { it.name == "count" }
            assertEquals(3, snapshot.occurrences(member.id).count { it.kind == OccurrenceKind.MEMBER_REFERENCE })
            val file = assertNotNull(snapshot.file(source.path))
            val offset = source.text.indexOf("ptr->") + 5
            val context = adapter.completionContext(source, offset)
            assertTrue(context.memberAccess)
            val type = BestEffortExpressionTypeResolver().typeOf(file.id, assertNotNull(context.receiverRange), snapshot)
            assertEquals(listOf("count"), snapshot.members(type).map { it.name }.toList())
        }
    }

    @Test
    fun supportsAnonymousStructsAndSameSpellingTagAliases() {
        val adapter = CSemanticAdapter()
        val snapshot = SemanticIndex().apply(adapter.extract(SourceFile("types.c", "c", """
            typedef struct Foo { int id; } Foo;
            typedef struct { int value; } Bar;
            Foo foo;
            Bar bar;
        """.trimIndent(), 1)))
        for ((name, field) in listOf("foo" to "id", "bar" to "value")) {
            val variable = snapshot.workspaceSymbols().single { it.name == name }
            val type = assertNotNull(snapshot.type(variable.declaredTypeId)).ref
            assertEquals(listOf(field), snapshot.members(type).map { it.name }.toList())
        }
    }
}
