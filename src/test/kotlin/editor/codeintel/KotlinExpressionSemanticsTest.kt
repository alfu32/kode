package editor.codeintel

import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolFlags
import editor.codeintel.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinExpressionSemanticsTest {
    @Test
    fun completesChainedPropertiesAndMethodCalls() {
        val source = """
            class Address {
                val city: String = ""
            }

            class Customer {
                val address: Address = Address()
                fun primaryAddress(): Address = Address()
            }

            fun getCustomer(): Customer = Customer()

            fun test() {
                val customer = getCustomer()
                customer.address./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        harness.index("Chains.kt", source)

        assertEquals(setOf("city"), harness.completions("Chains.kt", source).map { it.label }.toSet())

        val callSource = source.replace("customer.address./*caret*/", "customer.primaryAddress()./*caret*/")
        val callHarness = SemanticTestHarness()
        callHarness.index("Calls.kt", callSource)

        assertEquals(setOf("city"), callHarness.completions("Calls.kt", callSource).map { it.label }.toSet())

        val functionSource = source.replace("customer.address./*caret*/", "getCustomer().address./*caret*/")
        val functionHarness = SemanticTestHarness()
        functionHarness.index("FunctionChain.kt", functionSource)

        assertEquals(
            setOf("city"),
            functionHarness.completions("FunctionChain.kt", functionSource).map { it.label }.toSet()
        )
    }

    @Test
    fun typesParenthesizedAndCastReceivers() {
        val source = """
            class Customer {
                val id: Long = 0
            }

            fun test(value: Any) {
                (value as Customer)./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        harness.index("Casts.kt", source)

        assertEquals(setOf("id"), harness.completions("Casts.kt", source).map { it.label }.toSet())
    }

    @Test
    fun propagatesTypesThroughReferencesAndAssignments() {
        val source = """
            class Customer {
                val id: Long = 0
            }

            fun getCustomer(): Customer = Customer()

            fun test(original: Customer) {
                val copied = original
                var assigned = null
                assigned = getCustomer()
                assigned./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        harness.index("Assignments.kt", source)

        val customer = harness.symbol("Customer")
        val copied = assertIs<TypeRef.Named>(harness.typeOf(harness.symbol("copied")))
        val assigned = assertIs<TypeRef.Named>(harness.typeOf(harness.symbol("assigned")))

        assertEquals(customer.id, copied.symbolId)
        assertEquals(customer.id, assigned.symbolId)
        assertEquals(setOf("id"), harness.completions("Assignments.kt", source).map { it.label }.toSet())
    }

    @Test
    fun mergesConflictingAssignmentsInsteadOfChoosingTheLastType() {
        val source = """
            class First {
                val shared: Long = 0
                val onlyFirst: Long = 0
            }

            class Second {
                val shared: Long = 0
                val onlySecond: Long = 0
            }

            fun test() {
                var value = First()
                value = Second()
                value./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        harness.index("Union.kt", source)

        val inferred = assertIs<TypeRef.Union>(harness.typeOf(harness.symbol("value")))
        assertEquals(2, inferred.alternatives.size)
        assertEquals(setOf("shared"), harness.completions("Union.kt", source).map { it.label }.toSet())
    }

    @Test
    fun infersPrimitiveLiteralTypes() {
        val source = "fun test() { val count = 1; val title = \"Kode\" }"
        val harness = SemanticTestHarness()
        val snapshot = harness.index("Literals.kt", source)
        val file = requireNotNull(snapshot.file("Literals.kt"))
        val literalOffset = source.indexOf('1')

        assertEquals("Int", assertIs<TypeRef.Primitive>(harness.typeOf(harness.symbol("count"))).name)
        assertEquals("String", assertIs<TypeRef.Primitive>(harness.typeOf(harness.symbol("title"))).name)
        assertEquals(
            "Int",
            assertIs<TypeRef.Primitive>(
                snapshot.expressionType(file.id, SourceRange(literalOffset, literalOffset + 1))
            ).name
        )
    }

    @Test
    fun doesNotResolveMembersByWorkspaceTextWhenReceiverTypeIsUnknown() {
        val source = """
            class Known {
                val coincidental: Long = 0
            }

            fun test(unknown: Any) {
                unknown.coincidental
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        val snapshot = harness.index("UnknownReceiver.kt", source)
        val file = requireNotNull(snapshot.file("UnknownReceiver.kt"))
        val member = snapshot.occurrences(file.id)
            .first { it.kind == OccurrenceKind.MEMBER_REFERENCE && it.text == "coincidental" }

        assertEquals(null, member.resolvedSymbolId)
    }

    @Test
    fun preservesGenericAndNullableTypeShapeForMemberLookup() {
        val source = """
            class Box<T> {
                val size: Int = 0
            }

            fun inspect(box: Box<String>?) {
                box?./*caret*/
            }
        """.trimIndent()
        val harness = SemanticTestHarness()
        harness.index("Types.kt", source)

        val nullable = assertIs<TypeRef.Nullable>(harness.typeOf(harness.symbol("box")))
        val generic = assertIs<TypeRef.Generic>(nullable.inner)
        assertIs<TypeRef.Named>(generic.base)
        assertEquals("String", assertIs<TypeRef.Primitive>(generic.arguments.single()).name)
        assertEquals(setOf("size"), harness.completions("Types.kt", source).map { it.label }.toSet())
    }

    @Test
    fun filtersMembersUsingThisSuperAndVisibility() {
        val thisSource = """
            open class Entity {
                val id: Long = 0
                protected val inherited: Long = 0
                private val hiddenBase: Long = 0
            }

            class Customer : Entity() {
                val name: String = ""
                private val secret: String = ""

                fun inspect() {
                    this./*caret*/
                }
            }
        """.trimIndent()
        val thisHarness = SemanticTestHarness()
        val snapshot = thisHarness.index("This.kt", thisSource)
        val secret = thisHarness.symbol("secret")

        assertEquals(SymbolFlags.PRIVATE, secret.flags and SymbolFlags.VISIBILITY_MASK)
        assertEquals(
            setOf("id", "inherited", "name", "secret", "inspect"),
            thisHarness.completions("This.kt", thisSource).map { it.label }.toSet()
        )

        val superSource = thisSource.replace("this./*caret*/", "super./*caret*/")
        val superHarness = SemanticTestHarness()
        superHarness.index("Super.kt", superSource)
        assertEquals(
            setOf("id", "inherited"),
            superHarness.completions("Super.kt", superSource).map { it.label }.toSet()
        )

        val externalSource = """
            open class Entity {
                val id: Long = 0
                protected val inherited: Long = 0
                private val hiddenBase: Long = 0
            }

            class Customer : Entity() {
                val name: String = ""
                private val secret: String = ""
            }

            fun invalidAccess(customer: Customer) {
                customer.secret
                customer.inherited
            }

            fun external(customer: Customer) {
                customer./*caret*/
            }
        """.trimIndent()
        val externalHarness = SemanticTestHarness()
        val externalSnapshot = externalHarness.index("External.kt", externalSource)
        val externalMembers = externalHarness.completions("External.kt", externalSource).map { it.label }.toSet()

        assertTrue("name" in externalMembers)
        assertTrue("id" in externalMembers)
        assertTrue("secret" !in externalMembers)
        assertTrue("inherited" !in externalMembers)
        assertTrue("hiddenBase" !in externalMembers)
        assertTrue(snapshot.workspaceSymbols().any { it.name == "Customer" })
        val externalFile = requireNotNull(externalSnapshot.file("External.kt"))
        assertTrue(
            externalSnapshot.occurrences(externalFile.id)
                .filter { it.kind == OccurrenceKind.MEMBER_REFERENCE && it.text in setOf("secret", "inherited") }
                .all { it.resolvedSymbolId == null }
        )
    }
}
