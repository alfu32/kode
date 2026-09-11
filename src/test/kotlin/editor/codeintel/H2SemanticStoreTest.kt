package editor.codeintel

import editor.codeintel.frontend.KotlinSemanticAdapter
import editor.codeintel.frontend.SourceFile
import editor.codeintel.index.H2SemanticStore
import editor.codeintel.index.SemanticIndex
import editor.codeintel.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class H2SemanticStoreTest {
    @Test
    fun reloadsPreindexedSemanticFacts() {
        val url = "jdbc:h2:mem:semantic-store-${System.nanoTime()};DB_CLOSE_DELAY=-1"
        val store = H2SemanticStore { url }
        val adapter = KotlinSemanticAdapter()
        val source = SourceFile(
            "Stored.kt",
            "kotlin",
            "class Stored { val value: Long = 0 }",
            7L
        )
        SemanticIndex(store).apply(adapter.extract(source))

        val snapshot = SemanticIndex(store).load()
        val stored = snapshot.workspaceSymbols().first { it.name == "Stored" }
        val value = snapshot.workspaceSymbols().first { it.name == "value" }

        assertEquals(7L, snapshot.file("Stored.kt")?.semanticVersion)
        assertEquals("Long", assertIs<TypeRef.Primitive>(snapshot.type(value.declaredTypeId)?.ref).name)
        assertTrue(snapshot.members(TypeRef.Named(stored.id)).any { it.name == "value" })
    }

    @Test
    fun reResolvesCrossFileInferenceAfterPersistentReload() {
        val url = "jdbc:h2:mem:semantic-cross-file-${System.nanoTime()};DB_CLOSE_DELAY=-1"
        val store = H2SemanticStore { url }
        val adapter = KotlinSemanticAdapter()
        val index = SemanticIndex(store)
        index.apply(
            adapter.extract(
                SourceFile(
                    "Service.kt",
                    "kotlin",
                    "fun getCustomer(): Customer = Customer()\nfun test() { val customer = getCustomer() }",
                    1L
                )
            )
        )
        index.apply(
            adapter.extract(
                SourceFile("Customer.kt", "kotlin", "class Customer { val id: Long = 0 }", 1L)
            )
        )

        val snapshot = SemanticIndex(store).load()
        val customerType = snapshot.workspaceSymbols().first { it.name == "Customer" }
        val variable = snapshot.workspaceSymbols().first { it.name == "customer" }
        val inferred = assertIs<TypeRef.Named>(snapshot.type(variable.inferredTypeId)?.ref)

        assertEquals(customerType.id, inferred.symbolId)
        assertTrue(snapshot.members(inferred).any { it.name == "id" })
    }
}
