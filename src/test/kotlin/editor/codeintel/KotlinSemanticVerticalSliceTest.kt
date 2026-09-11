package editor.codeintel

import editor.codeintel.model.RelationKind
import editor.codeintel.model.ScopeKind
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinSemanticVerticalSliceTest {
    @Test
    fun infersCallResultAndCompletesReceiverMembers() {
        val source = """
            class Customer {
                val id: Long = 0
                val name: String = ""

                fun save() {}
            }

            fun getCustomer(): Customer = Customer()

            fun test() {
                val customer = getCustomer()

                customer./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        val snapshot = harness.index("Sample.kt", source)

        val customerType = harness.symbol("Customer")
        val customerVariable = harness.symbol("customer")
        val inferred = assertIs<TypeRef.Named>(harness.typeOf(customerVariable))
        assertEquals(customerType.id, inferred.symbolId)
        assertEquals(setOf("id", "name", "save"), snapshot.members(inferred).map { it.name }.toSet())
        assertEquals(setOf("id", "name", "save"), harness.completions("Sample.kt", source).map { it.label }.toSet())
        assertTrue(snapshot.scopes(customerVariable.fileId).any { it.kind == ScopeKind.FUNCTION })
        assertTrue(snapshot.scopes(customerVariable.fileId).any { it.kind == ScopeKind.BLOCK })
    }

    @Test
    fun resolvesTypesAndMembersAcrossFilesIndependentOfIndexOrder() {
        val serviceSource = """
            fun getCustomer(): Customer = Customer()

            fun test() {
                val customer = getCustomer()
                customer./*caret*/
            }
        """.trimIndent()
        val customerSource = """
            class Customer {
                val id: Long = 0
                val name: String = ""
                fun save() {}
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        harness.index("Service.kt", serviceSource)
        harness.index("Customer.kt", customerSource)

        val inferred = assertIs<TypeRef.Named>(harness.typeOf(harness.symbol("customer", "Service.kt")))
        assertEquals(harness.symbol("Customer", "Customer.kt").id, inferred.symbolId)
        assertEquals(setOf("id", "name", "save"), harness.completions("Service.kt", serviceSource).map { it.label }.toSet())
    }

    @Test
    fun includesInheritedMembersInReceiverCompletion() {
        val source = """
            open class Entity {
                val id: Long = 0
            }

            class Customer : Entity() {
                val name: String = ""
            }

            fun getCustomer(): Customer = Customer()

            fun test() {
                val customer = getCustomer()
                customer./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        val snapshot = harness.index("Inheritance.kt", source)

        val customer = harness.symbol("Customer")
        val entity = harness.symbol("Entity")
        assertTrue(snapshot.relations(customer.id, RelationKind.EXTENDS).any { it.to == entity.id })
        assertEquals(setOf("id", "name"), harness.completions("Inheritance.kt", source).map { it.label }.toSet())
    }

    @Test
    fun definitionAndReferencesUseResolvedSymbolIdentity() {
        val source = """
            class Customer
            fun make(): Customer = Customer()
        """.trimIndent()
        val harness = SemanticTestHarness()
        val snapshot = harness.index("References.kt", source)
        val customer = harness.symbol("Customer")
        val occurrences = snapshot.occurrences(customer.id).toList()

        assertEquals(3, occurrences.size)
        assertTrue(occurrences.all { it.resolvedSymbolId == customer.id })
        assertEquals(SymbolKind.CLASS, snapshot.symbolAt(customer.fileId, occurrences.last().range.startOffset)?.kind)
    }

    @Test
    fun oldSnapshotRemainsStableAfterPublishingNewFileVersion() {
        val harness = SemanticTestHarness()
        val old = harness.index("Versioned.kt", "class Before", version = 1L)
        val current = harness.index("Versioned.kt", "class After", version = 2L)

        assertTrue(old.workspaceSymbols().any { it.name == "Before" })
        assertTrue(old.workspaceSymbols().none { it.name == "After" })
        assertTrue(current.workspaceSymbols().any { it.name == "After" })
        assertTrue(current.version > old.version)
    }

    @Test
    fun publicSurfaceHashIgnoresLocalImplementationChanges() {
        val adapter = editor.codeintel.frontend.KotlinSemanticAdapter()
        fun extract(bodyValue: Int, returnType: String) = adapter.extract(
            editor.codeintel.frontend.SourceFile(
                "Surface.kt",
                "kotlin",
                "class Customer\nfun load(): $returnType { val local = $bodyValue; return Customer() }",
                bodyValue.toLong()
            )
        )

        val first = extract(1, "Customer")
        val implementationOnly = extract(2, "Customer")
        val signatureChange = extract(2, "String")

        assertEquals(first.exportedSurfaceHash, implementationOnly.exportedSurfaceHash)
        assertTrue(first.exportedSurfaceHash != signatureChange.exportedSurfaceHash)
    }

    @Test
    fun visibleSymbolsRespectNestedScopeAndDeclarationOrder() {
        val source = """
            fun test() {
                val before = 1
                if (true) {
                    val inner = 2
                    /*caret*/
                }
                val after = 3
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        val snapshot = harness.index("Scopes.kt", source)
        val file = snapshot.file("Scopes.kt") ?: error("file was not indexed")
        val visible = snapshot.visibleSymbols(file.id, source.indexOf(SemanticTestHarness.CARET)).map { it.name }.toSet()

        assertTrue("before" in visible)
        assertTrue("inner" in visible)
        assertTrue("after" !in visible)
    }
}
