package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2a-0 (§4 extraction): the shared `ToolCall → BoltAction` primitive
 * and the shared protected-path test. These pin the semantics that
 * used to live as two hand-mirrored copies in ActionDispatcher and
 * PiToolMapping; the Pi form (blank guard + backslash normalization)
 * is the normative one.
 */
class CoreToolActionsTest {

    // ------------------------------------------------------------ mapping

    @Test
    fun `write maps to file action with content and path`() {
        val action = CoreToolActions.toBoltAction("write", path = "src/App.kt", content = "fun main() {}")
        assertEquals(BoltAction.FileAction(content = "fun main() {}", filePath = "src/App.kt"), action)
    }

    @Test
    fun `edit maps to file action whose content is the mutation fingerprint`() {
        val fingerprint = """{"path":"src/App.kt","edits":[{"oldText":"a","newText":"b"}]}"""
        val action = CoreToolActions.toBoltAction("edit", path = "src/App.kt", content = fingerprint)
        assertEquals(BoltAction.FileAction(content = fingerprint, filePath = "src/App.kt"), action)
    }

    @Test
    fun `bash maps to shell action with the command`() {
        val action = CoreToolActions.toBoltAction("bash", command = "ls src")
        assertEquals(BoltAction.ShellAction(content = "ls src"), action)
    }

    @Test
    fun `read maps to a read tool action`() {
        val action = CoreToolActions.toBoltAction("read", path = "src/App.kt")
        assertEquals(BoltAction.ToolAction("read", "src/App.kt"), action)
    }

    @Test
    fun `null fields default to empty strings`() {
        assertEquals(
            BoltAction.FileAction(content = "", filePath = ""),
            CoreToolActions.toBoltAction("write"),
        )
        assertEquals(
            BoltAction.FileAction(content = "", filePath = ""),
            CoreToolActions.toBoltAction("edit"),
        )
        assertEquals(
            BoltAction.ShellAction(content = ""),
            CoreToolActions.toBoltAction("bash"),
        )
        assertEquals(
            BoltAction.ToolAction("read", ""),
            CoreToolActions.toBoltAction("read"),
        )
    }

    @Test
    fun `unknown tools fail closed as unknown actions`() {
        val action = CoreToolActions.toBoltAction("teleport", content = """{"to":"mars"}""")
        assertEquals(BoltAction.UnknownAction("teleport", """{"to":"mars"}"""), action)
        assertTrue(ActionSafetyPolicy.assess(action).requiresApproval)
        // Pi-only tools are NOT part of the shared mapping: they reach
        // the primitive only from a non-Pi caller, where unknown is the
        // fail-closed answer.
        assertTrue(CoreToolActions.toBoltAction("grep", path = "src") is BoltAction.UnknownAction)
        assertTrue(CoreToolActions.toBoltAction("mcp", content = "{}") is BoltAction.UnknownAction)
    }

    // ------------------------------------------------------- protected set

    @Test
    fun `protected path covers the full metadata set at any depth`() {
        for (path in listOf(
            ".git/config",
            "sub/.git/config",
            ".anvex/settings.json",
            "sub/.anvex/state.db",
            ".anvex-pi/agent/models.json",
            "sub/.anvex-pi/agent/sessions/x.jsonl",
            ".pi/extensions/marker.ts",
            "sub/.pi/settings.json",
            ".github/workflows/ci.yml",
            "sub/.github/workflows/release.yml",
        )) {
            assertTrue("$path must be protected", SensitivePaths.isProtectedPath(path))
        }
    }

    @Test
    fun `protected path treats backslashes as separators (Pi form) `() {
        // Pi hands the check RAW tool paths: a backslash-separated
        // metadata segment must still match. This is the divergence
        // the A2a-0 errata records — the primitive adopts the Pi form.
        for (path in listOf(
            """sub\.git\config""",
            """.anvex\settings.json""",
            """.anvex-pi\agent\models.json""",
            """sub\.pi\settings.json""",
            """sub\.github\workflows\ci.yml""",
        )) {
            assertTrue("$path must be protected", SensitivePaths.isProtectedPath(path))
        }
    }

    @Test
    fun `protected path rejects blanks and ordinary project paths`() {
        for (path in listOf(
            "",
            "   ",
            "src/main.kt",
            ".github/ISSUE_TEMPLATE/bug.md",
            ".gitignore",
            "git/config",
            "docs/.github/file.md",
            ".env",
        )) {
            assertFalse("$path must not be protected", SensitivePaths.isProtectedPath(path))
        }
    }
}
