package com.anvex.core.android.runtime.pi

import com.anvex.core.engine.ActionSafetyPolicy
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.CoreToolActions
import com.anvex.core.engine.SensitivePaths
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2a-0 (§4 extraction) cross-consumer parity fixture: every row of
 * the four SHARED tools (write/edit/bash/read) is mapped through BOTH
 * `PiToolMapping.toBoltAction` (via its JSONObject wire shape) and the
 * shared `CoreToolActions.toBoltAction` primitive, and must produce an
 * EQUAL BoltAction and an EQUAL ActionSafetyPolicy verdict. This is
 * the lock that keeps the two consumers from drifting apart again —
 * the drift this extraction removes had already happened once
 * (isProtectedPath near-mirror divergence; see the A2 design note §4
 * errata).
 *
 * Caller contracts modelled here (design note §4 + addendum (b)):
 *  - write: content = the file content.
 *  - edit: content = the mutation fingerprint — for Pi that is its
 *    edits-array string (or the full input JSON when no edits array
 *    is present); the loop caller passes the FULL argumentsJson.
 *    Either way the string is passed through untouched, never
 *    truncated.
 *  - bash: command = the shell command. read: path only.
 */
class CoreToolActionsParityTest {

    private val root = java.io.File(System.getProperty("java.io.tmpdir"), "anvex-pi-test-root").apply { mkdirs() }

    private data class Row(
        val tool: String,
        val input: JSONObject,
        val corePath: String?,
        val coreContent: String?,
        val coreCommand: String?,
    )

    private fun writeRow(path: String?, content: String?): Row {
        val input = JSONObject()
        if (path != null) input.put("path", path)
        if (content != null) input.put("content", content)
        return Row("write", input, path, content, null)
    }

    private fun editRow(path: String?, editsJson: String?): Row {
        val input = JSONObject()
        if (path != null) input.put("path", path)
        if (editsJson != null) input.put("edits", JSONArray(editsJson))
        // The fingerprint string Pi will use — the core caller passes
        // the same string through (its full argumentsJson when there
        // is no edits array; Pi falls back to the full input JSON too).
        val fingerprint = input.optJSONArray("edits")?.toString() ?: input.toString()
        return Row("edit", input, path, fingerprint, null)
    }

    private fun bashRow(command: String?): Row {
        val input = JSONObject()
        if (command != null) input.put("command", command)
        return Row("bash", input, null, null, command)
    }

    private fun readRow(path: String?): Row {
        val input = JSONObject()
        if (path != null) input.put("path", path)
        return Row("read", input, path, null, null)
    }

    private fun assertParity(row: Row) {
        val piAction = PiToolMapping.toBoltAction(row.tool, row.input)
        val coreAction = CoreToolActions.toBoltAction(
            row.tool,
            path = row.corePath,
            content = row.coreContent,
            command = row.coreCommand,
        )
        assertEquals("${row.tool} must map identically in both consumers", piAction, coreAction)
        assertEquals(
            "${row.tool} must assess identically in both consumers",
            ActionSafetyPolicy.assess(piAction).risk,
            ActionSafetyPolicy.assess(coreAction).risk,
        )
    }

    @Test
    fun `shared tools map identically through both consumers`() {
        val rows = listOf(
            writeRow("src/App.kt", "fun main() {}"),
            writeRow(null, null),
            writeRow(".env", "KEY=x"),
            writeRow(".github/workflows/ci.yml", "name: ci"),
            bashRow("ls src"),
            bashRow("echo x > a.txt && echo y > b.txt"),
            bashRow(null),
            readRow("src/App.kt"),
            readRow(null),
            editRow("src/App.kt", """[{"oldText":"a","newText":"b"}]"""),
            editRow("src/App.kt", null),
        )
        rows.forEach(::assertParity)
    }

    @Test
    fun `backslash protected rows agree across consumers`() {
        // The A2a-0 errata divergence: backslash-separated metadata
        // segments. Both consumers must produce the same action AND
        // the shared primitive must call the path protected (Pi then
        // hard-denies the write before approval is ever consulted).
        val rows = listOf(
            writeRow("""sub\.git\config""", "x"),
            writeRow("""sub\.github\workflows\ci.yml""", "name: ci"),
            editRow(""".anvex-pi\agent\models.json""", """[{"oldText":"a","newText":"b"}]"""),
        )
        for (row in rows) {
            assertParity(row)
            val path = row.corePath!!
            assertTrue("$path must be protected", SensitivePaths.isProtectedPath(path))
            assertTrue(
                "$path must be hard-denied for Pi ${row.tool}",
                PiToolMapping.isHardDenied(row.tool, row.input, root),
            )
        }
    }

    @Test
    fun `different edits produce different fingerprints in both consumers`() {
        val a = editRow("src/App.kt", """[{"oldText":"a","newText":"b"}]""")
        val b = editRow("src/App.kt", """[{"oldText":"a","newText":"c"}]""")
        assertParity(a)
        assertParity(b)
        val piA = PiToolMapping.toBoltAction(a.tool, a.input) as BoltAction.FileAction
        val piB = PiToolMapping.toBoltAction(b.tool, b.input) as BoltAction.FileAction
        val coreA = CoreToolActions.toBoltAction(a.tool, a.corePath, a.coreContent, a.coreCommand) as BoltAction.FileAction
        val coreB = CoreToolActions.toBoltAction(b.tool, b.corePath, b.coreContent, b.coreCommand) as BoltAction.FileAction
        assertNotEquals("Pi fingerprints must differ per edit", piA.content, piB.content)
        assertNotEquals("core fingerprints must differ per edit", coreA.content, coreB.content)
        assertEquals(piA.content, coreA.content)
        assertEquals(piB.content, coreB.content)
    }

    @Test
    fun `edit over the 2 MB budget requires approval in both consumers`() {
        // ActionSafetyPolicy: a FileAction whose content exceeds
        // 2_000_000 UTF-8 bytes assesses REQUIRES_APPROVAL.
        val bigEdit = """[{"oldText":"a","newText":"${"x".repeat(2_100_000)}"}]"""
        val row = editRow("src/Big.kt", bigEdit)
        assertParity(row)
        val piAction = PiToolMapping.toBoltAction(row.tool, row.input)
        val coreAction = CoreToolActions.toBoltAction(row.tool, row.corePath, row.coreContent, row.coreCommand)
        assertTrue(ActionSafetyPolicy.assess(piAction).requiresApproval)
        assertTrue(ActionSafetyPolicy.assess(coreAction).requiresApproval)
        // Same rule through the plain write path.
        val bigWrite = writeRow("src/Big.kt", "x".repeat(2_100_000))
        assertParity(bigWrite)
        assertTrue(
            ActionSafetyPolicy.assess(
                CoreToolActions.toBoltAction(bigWrite.tool, bigWrite.corePath, bigWrite.coreContent, bigWrite.coreCommand),
            ).requiresApproval,
        )
    }
}
