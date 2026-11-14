package com.anvex.core.android.tools

import com.anvex.core.agent.CoreTools
import com.anvex.core.agent.ToolCall
import com.anvex.core.agent.TurnContext
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A2a (§3/§7): the :core:android ToolExecutor implementation —
 * direct-chat fail-closed (Q1), protected-path write refusal, and the
 * sensitive-read refusal (the gate's assessment marks reads SAFE, so
 * execution is where secrets are refused — the AgentToolExecutor.read
 * / ActionDispatcher.readFile pattern).
 */
class AgentLoopToolExecutorTest {

    private class MemoryFs : ProjectFileSystem {
        val files = mutableMapOf<String, String>()
        var writeCount = 0
        override fun mkdirs(relativePath: String) {}
        override fun exists(relativePath: String): Boolean = files.containsKey(relativePath)
        override fun isDirectory(relativePath: String): Boolean = false
        override fun writeFile(relativePath: String, content: String) {
            writeCount++
            files[relativePath] = content
        }
        override fun writeFileBytes(relativePath: String, bytes: ByteArray) {
            writeCount++
            files[relativePath] = bytes.toString(Charsets.UTF_8)
        }
        override fun readFile(relativePath: String): String? = files[relativePath]
        override fun readFileBytes(relativePath: String): ByteArray? =
            files[relativePath]?.toByteArray(Charsets.UTF_8)
        override fun appendFile(relativePath: String, content: String) {
            writeCount++
            files[relativePath] = (files[relativePath] ?: "") + content
        }
        override fun delete(relativePath: String, recursive: Boolean): Boolean =
            files.remove(relativePath) != null
        override fun list(relativePath: String): List<ProjectFileSystem.FileEntry> = emptyList()
        override fun move(from: String, to: String): Boolean = false
        override fun copy(from: String, to: String): Boolean = false
        override fun walk(): List<String> = files.keys.toList()
    }

    private val projectContext = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")
    private val directContext = TurnContext(sessionId = "s1", projectId = null, providerId = "prov", modelId = "m1")

    private fun executor(fs: MemoryFs, root: File? = null): AgentLoopToolExecutor {
        var provider: suspend (String) -> ProjectFileSystem? = { fs }
        return AgentLoopToolExecutor(fsProvider = provider, shellRootProvider = { root })
    }

    @Test fun directChatFailsAllFourToolsClosedWithZeroStorageAccess() = runBlocking {
        val fs = MemoryFs()
        var fsCalls = 0
        val exec = AgentLoopToolExecutor(
            fsProvider = { fsCalls++; fs },
            shellRootProvider = { File("/tmp") },
        )
        val calls = listOf(
            ToolCall("1", CoreTools.READ, """{"path":"a.txt"}"""),
            ToolCall("2", CoreTools.WRITE, """{"path":"a.txt","content":"x"}"""),
            ToolCall("3", CoreTools.EDIT, """{"path":"a.txt","oldText":"a","newText":"b"}"""),
            ToolCall("4", CoreTools.BASH, """{"command":"ls"}"""),
        )
        for (call in calls) {
            val result = exec.execute(directContext, call)
            assertTrue("${call.toolName} must fail closed in direct chat", result.isError)
        }
        assertEquals(0, fsCalls)
        assertEquals(0, fs.writeCount)
    }

    @Test fun writeToProtectedPathsIsRefusedAndCreatesNothing() = runBlocking {
        val fs = MemoryFs()
        val exec = executor(fs)
        for (path in listOf(".git/config", ".github/workflows/ci.yml", ".anvex-pi/agent/x.json")) {
            val result = exec.execute(
                projectContext,
                ToolCall("w", CoreTools.WRITE, """{"path":"$path","content":"pwned"}"""),
            )
            assertTrue("$path must be refused", result.isError)
            assertFalse(fs.exists(path))
        }
        assertEquals(0, fs.writeCount)
    }

    @Test fun sensitiveReadIsRefusedEvenThoughTheGateMarksReadsSafe() = runBlocking {
        val fs = MemoryFs()
        fs.files[".env"] = "SECRET_TOKEN=hunter2"
        val exec = executor(fs)

        val result = exec.execute(projectContext, ToolCall("r", CoreTools.READ, """{"path":".env"}"""))

        assertTrue(result.isError)
        assertFalse(result.output.contains("hunter2"))
    }

    @Test fun writeThenReadRoundTrips() = runBlocking {
        val fs = MemoryFs()
        val exec = executor(fs)

        val write = exec.execute(
            projectContext,
            ToolCall("w", CoreTools.WRITE, """{"path":"src/a.txt","content":"hello"}"""),
        )
        assertFalse(write.isError)

        val read = exec.execute(projectContext, ToolCall("r", CoreTools.READ, """{"path":"src/a.txt"}"""))
        assertFalse(read.isError)
        assertEquals("hello", read.output)
    }

    @Test fun editReplacesTheUniqueSpan() = runBlocking {
        val fs = MemoryFs()
        fs.files["a.txt"] = "one two one"
        val exec = executor(fs)

        val ok = exec.execute(
            projectContext,
            ToolCall("e", CoreTools.EDIT, """{"path":"a.txt","oldText":"two","newText":"2"}"""),
        )
        assertFalse(ok.isError)
        assertEquals("one 2 one", fs.files["a.txt"])

        val ambiguous = exec.execute(
            projectContext,
            ToolCall("e", CoreTools.EDIT, """{"path":"a.txt","oldText":"one","newText":"1"}"""),
        )
        assertTrue(ambiguous.isError)
        assertEquals("one 2 one", fs.files["a.txt"])

        val missing = exec.execute(
            projectContext,
            ToolCall("e", CoreTools.EDIT, """{"path":"a.txt","oldText":"three","newText":"3"}"""),
        )
        assertTrue(missing.isError)
    }

    @Test fun readOfMissingFileIsAnErrorResult() = runBlocking {
        val exec = executor(MemoryFs())
        val result = exec.execute(projectContext, ToolCall("r", CoreTools.READ, """{"path":"nope.txt"}"""))
        assertTrue(result.isError)
    }

    @Test fun bashFailsClosedWhenNoRealWorkspaceRootResolves() = runBlocking {
        val exec = executor(MemoryFs(), root = null)
        val result = exec.execute(projectContext, ToolCall("b", CoreTools.BASH, """{"command":"ls"}"""))
        assertTrue(result.isError)
    }

    @Test fun unknownToolIsAnErrorResult() = runBlocking {
        val exec = executor(MemoryFs())
        val result = exec.execute(projectContext, ToolCall("x", "teleport", "{}"))
        assertTrue(result.isError)
    }

    @Test fun malformedArgumentsAreAnErrorResult() = runBlocking {
        val exec = executor(MemoryFs())
        val result = exec.execute(projectContext, ToolCall("w", CoreTools.WRITE, "not json"))
        assertTrue(result.isError)
    }
}
