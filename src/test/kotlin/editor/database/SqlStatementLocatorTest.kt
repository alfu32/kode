package editor.database

import editor.database.query.SqlStatementLocator
import editor.lib.Position
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlStatementLocatorTest {
    private val locator = SqlStatementLocator()

    @Test
    fun ignoresSemicolonsInsideStringsAndComments() {
        val sql = """
            select ';';
            
            select 1; -- comment ;
            
            select 'abc;def';
            
            /*
             ;
            */
            select 2;
        """.trimIndent()

        val statements = locator.statements(sql).map { it.sql }

        assertEquals(
            listOf(
                "select ';'",
                "select 1",
                "-- comment ;\n\nselect 'abc;def'",
                "/*\n ;\n*/\nselect 2"
            ),
            statements
        )
    }

    @Test
    fun locatesStatementContainingCaret() {
        val sql = "select 1;\nselect 'a;b';\nselect 3;"

        val located = locator.locate(sql, Position(line = 1, column = 8), selection = null)

        assertEquals("select 'a;b'", located.sql)
    }

    @Test
    fun selectionOverridesCaret() {
        val located = locator.locate("select 1;\nselect 2;", Position(0, 0), "select 2")

        assertEquals("select 2", located.sql)
    }
}
