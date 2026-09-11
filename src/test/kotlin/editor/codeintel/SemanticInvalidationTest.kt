package editor.codeintel

import editor.codeintel.frontend.KotlinSemanticAdapter
import editor.codeintel.frontend.SourceFile
import editor.codeintel.index.DependencyGraph
import editor.codeintel.index.SemanticIndex
import editor.codeintel.model.FileDependencyKind
import editor.codeintel.model.FileDependencyRecord
import editor.codeintel.model.FileId
import editor.codeintel.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SemanticInvalidationTest {
    private val adapter = KotlinSemanticAdapter()

    @Test
    fun implementationOnlyEditDoesNotReResolveDependentFile() {
        val index = SemanticIndex()
        val initial = index.applyAll(
            listOf(
                extract("Customer.kt", "class Customer { val id: Long = 0 }", 1L),
                extract(
                    "Service.kt",
                    "fun getCustomer(): Customer = Customer()\nfun test() { val customer = getCustomer() }",
                    1L
                )
            )
        )
        val customerFile = requireNotNull(initial.file("Customer.kt"))
        val serviceFile = requireNotNull(initial.file("Service.kt"))

        assertTrue(
            initial.dependencies(serviceFile.id).any {
                it.toFileId == customerFile.id && it.kind == FileDependencyKind.TYPE_REFERENCE
            }
        )

        val updated = index.apply(extract("Customer.kt", "class Customer { val id: Long = 1 }", 2L))

        assertEquals(2L, updated.resolutionGeneration(customerFile.id))
        assertEquals(1L, updated.resolutionGeneration(serviceFile.id))
    }

    @Test
    fun exportedSurfaceEditReResolvesTransitiveDependents() {
        val index = SemanticIndex()
        val initial = index.applyAll(
            listOf(
                extract("Base.kt", "open class Base { val id: Long = 0 }", 1L),
                extract("Middle.kt", "open class Middle : Base()", 1L),
                extract("Leaf.kt", "class Leaf : Middle()", 1L)
            )
        )
        val base = requireNotNull(initial.file("Base.kt"))
        val middle = requireNotNull(initial.file("Middle.kt"))
        val leaf = requireNotNull(initial.file("Leaf.kt"))

        assertTrue(initial.dependencies(middle.id).any { it.toFileId == base.id })
        assertTrue(initial.dependencies(leaf.id).any { it.toFileId == middle.id })

        val updated = index.apply(extract("Base.kt", "open class Base { val id: String = \"\" }", 2L))

        assertEquals(2L, updated.resolutionGeneration(base.id))
        assertEquals(2L, updated.resolutionGeneration(middle.id))
        assertEquals(2L, updated.resolutionGeneration(leaf.id))
    }

    @Test
    fun newDefinitionWakesAPreviouslyUnresolvedFile() {
        val index = SemanticIndex()
        val unresolved = index.apply(
            extract(
                "Service.kt",
                "fun getCustomer(): Customer = Customer()\nfun test() { val customer = getCustomer() }",
                1L
            )
        )
        val serviceFile = requireNotNull(unresolved.file("Service.kt"))
        val unresolvedVariable = unresolved.workspaceSymbols().first { it.name == "customer" }
        assertEquals(null, unresolvedVariable.inferredTypeId)

        val resolved = index.apply(extract("Customer.kt", "class Customer { val id: Long = 0 }", 1L))
        val customer = resolved.workspaceSymbols().first { it.name == "Customer" }
        val variable = resolved.workspaceSymbols().first { it.name == "customer" }
        val inferred = assertIs<TypeRef.Named>(resolved.type(variable.inferredTypeId)?.ref)

        assertEquals(customer.id, inferred.symbolId)
        assertEquals(2L, resolved.resolutionGeneration(serviceFile.id))
    }

    @Test
    fun removedDefinitionClearsDependentResolution() {
        val index = SemanticIndex()
        val initial = index.applyAll(
            listOf(
                extract("Customer.kt", "class Customer", 1L),
                extract(
                    "Service.kt",
                    "fun getCustomer(): Customer = Customer()\nfun test() { val customer = getCustomer() }",
                    1L
                )
            )
        )
        val serviceFile = requireNotNull(initial.file("Service.kt"))
        assertTrue(initial.workspaceSymbols().first { it.name == "customer" }.inferredTypeId != null)

        val updated = index.apply(extract("Customer.kt", "class Client", 2L))
        val function = updated.symbols(serviceFile.id).first { it.name == "getCustomer" }
        val variable = updated.symbols(serviceFile.id).first { it.name == "customer" }

        assertEquals(null, function.declaredTypeId)
        assertEquals(null, variable.inferredTypeId)
        assertTrue(
            updated.occurrences(serviceFile.id)
                .filter { it.text == "Customer" }
                .all { it.resolvedSymbolId == null }
        )
        assertEquals(2L, updated.resolutionGeneration(serviceFile.id))
    }

    @Test
    fun dependentClosureTerminatesForCycles() {
        val first = FileId(1L)
        val second = FileId(2L)
        val third = FileId(3L)
        val graph = DependencyGraph(
            listOf(
                FileDependencyRecord(first, second, FileDependencyKind.TYPE_REFERENCE, 1L),
                FileDependencyRecord(second, first, FileDependencyKind.TYPE_REFERENCE, 1L),
                FileDependencyRecord(third, second, FileDependencyKind.TYPE_REFERENCE, 1L)
            )
        )

        assertEquals(setOf(first, second, third), graph.dependentClosure(setOf(first)))
    }

    private fun extract(path: String, text: String, version: Long) =
        adapter.extract(SourceFile(path, "kotlin", text, version))
}
