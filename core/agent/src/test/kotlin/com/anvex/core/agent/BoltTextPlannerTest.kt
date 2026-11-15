package com.anvex.core.agent

import com.anvex.core.ai.AiError
import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.ai.ValidationResult
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A2c (design note §5.5): the bolt-text fallback planner — it adapts
 * the REPAIRED BoltStreamParser (never rewrites it), converts closed
 * actions to core ToolCalls served from a per-turn queue, and makes
 * exactly ONE provider call per turn.
 */
class BoltTextPlannerTest {

    private val context = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")

    private class FakeClient(private val scripts: List<List<StreamEvent>>) : ProviderClient {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0
        override val kind = ProviderKind.OPENROUTER
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> {
            requests += request
            val script = scripts[index++]
            return flow { script.forEach { emit(it) } }
        }
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection(): ValidationResult = ValidationResult(true, 0, "ok")
    }

    private fun scriptOf(text: String): List<StreamEvent> =
        listOf(StreamEvent.Token(text), StreamEvent.Completed("stop"))

    private fun argumentsOf(call: ToolCall) =
        Json.parseToJsonElement(call.argumentsJson).jsonObject

    @Test fun scriptedBoltTextBecomesQueuedToolCallsThenRespond() = runBlocking {
        val text = """
            Creating the file and checking the workspace.
            <boltArtifact id="proj" title="Project">
            <boltAction type="file" filePath="a.txt">hello</boltAction>
            <boltAction type="shell">ls -la</boltAction>
            <boltAction type="read" path="b.txt"></boltAction>
            </boltArtifact>
            All done.
        """.trimIndent()
        val client = FakeClient(listOf(scriptOf(text)))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "do it")

        val write = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.CallTool
        assertEquals(CoreTools.WRITE, write.call.toolName)
        assertEquals("a.txt", argumentsOf(write.call)["path"]?.jsonPrimitive?.content)
        assertEquals("hello\n", argumentsOf(write.call)["content"]?.jsonPrimitive?.content)

        val bash = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.CallTool
        assertEquals(CoreTools.BASH, bash.call.toolName)
        assertEquals("ls -la", argumentsOf(bash.call)["command"]?.jsonPrimitive?.content)

        val read = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.CallTool
        assertEquals(CoreTools.READ, read.call.toolName)
        assertEquals("b.txt", argumentsOf(read.call)["path"]?.jsonPrimitive?.content)

        val respond = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.Respond
        assertTrue(respond.text.contains("Creating the file"))
        assertTrue(respond.text.contains("All done."))

        // Batch policy: ONE provider call served the whole turn, and
        // the bolt request carries NO tools (that is the point of the
        // fallback — the endpoint cannot take them).
        assertEquals(1, client.requests.size)
        assertTrue(client.requests.single().tools.isEmpty())
    }

    @Test fun unterminatedFileActionIsAppliedByTheFlushOptInOnSuccess() = runBlocking {
        // The stream ends inside the file action: only its closing
        // tags are missing (a formatting omission), so the successful
        // completion flush (applyIncomplete = true) recovers it.
        val text = """
            <boltArtifact id="p" title="P">
            <boltAction type="file" filePath="a.txt">partial body
        """.trimIndent()
        val client = FakeClient(listOf(scriptOf(text)))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "write it")

        val write = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.CallTool
        assertEquals(CoreTools.WRITE, write.call.toolName)
        assertEquals("a.txt", argumentsOf(write.call)["path"]?.jsonPrimitive?.content)
        assertTrue(
            argumentsOf(write.call)["content"]?.jsonPrimitive?.content?.contains("partial body") == true
        )
    }

    @Test fun unterminatedShellActionIsNeverApplied() = runBlocking {
        // The repaired parser's safety invariant: an unterminated
        // shell action must not execute under ANY flush shape,
        // including the success opt-in — a truncated command is
        // dropped, never served as a bash call.
        val text = """
            <boltArtifact id="p" title="P">
            <boltAction type="shell">rm -rf build
        """.trimIndent()
        val client = FakeClient(listOf(scriptOf(text)))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "clean")

        val action = planner.nextAction(context, emptyList(), CoreTools.ALL)
        assertTrue("expected Respond, got $action", action is PlannedAction.Respond)
        assertEquals(1, client.requests.size)
    }

    @Test fun nonCoreActionSurfacesAsText() = runBlocking {
        val text = """
            Let me look that up.
            <boltArtifact id="p" title="P">
            <boltAction type="fetch" url="https://example.com/docs"></boltAction>
            </boltArtifact>
        """.trimIndent()
        val client = FakeClient(listOf(scriptOf(text)))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "look it up")

        val action = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.Respond
        assertTrue(action.text.contains("Let me look that up."))
        assertTrue(action.text.contains("https://example.com/docs"))
    }

    @Test fun providerFailureThrowsAndNeverAppliesTheOpenAction() = runBlocking {
        val cause = AiError(AiError.Kind.NETWORK, "socket closed")
        val script = listOf(
            StreamEvent.Token("<boltArtifact id=\"p\" title=\"P\">\n<boltAction type=\"file\" filePath=\"a.txt\">partial"),
            StreamEvent.Failed(cause),
        )
        val client = FakeClient(listOf(script))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "write it")

        try {
            planner.nextAction(context, emptyList(), CoreTools.ALL)
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(cause, e.error)
        }
    }

    @Test fun appendChunksMergeIntoOneWrite() = runBlocking {
        val text = """
            <boltArtifact id="p" title="P">
            <boltAction type="file" filePath="a.txt">part1-</boltAction>
            <boltAction type="file" filePath="a.txt" append="true">part2</boltAction>
            </boltArtifact>
        """.trimIndent()
        val client = FakeClient(listOf(scriptOf(text)))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "write in chunks")

        val write = planner.nextAction(context, emptyList(), CoreTools.ALL) as PlannedAction.CallTool
        assertEquals(CoreTools.WRITE, write.call.toolName)
        val content = argumentsOf(write.call)["content"]?.jsonPrimitive?.content ?: ""
        assertTrue(content.contains("part1-"))
        assertTrue(content.indexOf("part1-") < content.indexOf("part2"))
        // Merged: the next action is already the final Respond.
        assertTrue(planner.nextAction(context, emptyList(), CoreTools.ALL) is PlannedAction.Respond)
    }

    @Test fun historyFromAnEarlierNativePhaseIsRenderedIntoTheRequest() = runBlocking {
        // The router's downgrade re-plan hands the bolt planner a
        // history that already contains a native-phase call + result;
        // both must reach the model as context turns.
        val client = FakeClient(listOf(scriptOf("Understood, continuing.")))
        val planner = BoltTextPlanner(client, systemPrompt = "sys", prompt = "continue")
        val history = listOf(
            AgentEvent.TurnStarted("t1"),
            AgentEvent.ToolCallProposed(ToolCall("n1", CoreTools.WRITE, """{"path":"a.txt","content":"hi"}""")),
            AgentEvent.ToolResultReceived(ToolResult("n1", CoreTools.WRITE, "Wrote a.txt.")),
        )

        val action = planner.nextAction(context, history, CoreTools.ALL)

        assertEquals(PlannedAction.Respond("Understood, continuing."), action)
        val turns = client.requests.single().turns
        assertTrue(turns.any { it.role.name == "ASSISTANT" && it.content.contains("a.txt") })
        assertTrue(turns.any { it.content == "Wrote a.txt." })
    }
}
