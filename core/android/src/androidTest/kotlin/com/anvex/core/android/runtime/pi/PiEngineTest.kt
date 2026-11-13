package com.anvex.core.android.runtime.pi

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anvex.core.ai.ProviderRuntimeConfig
import com.anvex.core.android.db.AnvexDatabase
import com.anvex.core.android.reliability.CommandApprovalService
import com.anvex.core.android.reliability.DurableApprovalRepository
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-3 gate: the PRODUCTION PiEngine path end to end on device —
 * runtime asset extraction, driver boot over the control socket, the
 * scripted provider (deterministic, no network/key), and the approval
 * gateway relay into a real CommandApprovalService backed by a real
 * (in-memory) durable-grant store.
 *
 * Scripted scenario: (A) write hello.txt — safe FileAction, auto-allowed
 * by policy, no prompt; (B) compound `&&` bash — policy requires
 * approval, the test prompt DENIES, side-effect files must not exist;
 * (C) plain echo-redirect bash — safe, runs, approved.txt appears.
 * Success = run settles, exactly one prompt (for B), file outcomes as
 * above, and a persisted Pi session file for checkpoint/resume.
 */
@RunWith(AndroidJUnit4::class)
class PiEngineTest {

    @Test(timeout = 300_000)
    fun piEngineRunsThroughApprovalGateway() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AnvexDatabase::class.java).build()
        try {
            val approvals = CommandApprovalService(DurableApprovalRepository(db.approvalGrantDao()))
            val engine = PiEngine(context, approvals)

            val base = File(context.filesDir, "pi-engine-test").apply { deleteRecursively(); mkdirs() }
            val project = File(base, "project").apply { mkdirs() }
            val extFile = copyAsset(context, "pi-test/ext/scripted-provider.ts", File(base, "ext/scripted-provider.ts"))
            copyAsset(context, "pi-test/vendor/event-stream.js", File(base, "vendor/event-stream.js"))

            val prompts = mutableListOf<PiToolMapping.ApprovalAsk>()
            val result = engine.run(
                projectRoot = project,
                config = PiEngine.RunConfig(
                    providerName = "poc",
                    modelId = "poc-scripted",
                    extraExtensions = listOf(extFile.absolutePath),
                    agentDir = File(base, "agent").absolutePath,
                    sessionId = "pi-engine-test",
                    settleTimeoutMs = 120_000,
                ),
                prompt = "Run the scripted task.",
                approvalPrompt = PiEngine.ApprovalPrompt { ask ->
                    prompts += ask
                    false // the test user denies every prompted action
                },
            )

            assertTrue("Pi run failed: ${result.error} (events=${result.eventCounts})", result.success)
            val out = File(project, "poc-out")
            assertEquals("hello from pi poc\n", File(out, "hello.txt").readText())
            assertFalse("denied compound bash created compound.txt", File(out, "compound.txt").exists())
            assertFalse("denied compound bash created compound2.txt", File(out, "compound2.txt").exists())
            assertTrue("safe echo-redirect bash did not run", File(out, "approved.txt").exists())
            assertEquals("exactly one action should reach the user prompt", 1, prompts.size)
            assertEquals("bash", prompts.single().toolName)
            val session = result.sessionFile?.let { File(it) }
            assertTrue("session file missing for checkpoint/resume: ${result.sessionFile}", session?.isFile == true)
            Unit
        } finally {
            db.close()
        }
    }

    /**
     * M-1 instrumented proof: boot Pi with a fake provider whose key is
     * embedded literally in models.json (the production PiProviderMapping
     * output), let a bash child dump its environment, and assert the
     * dump — which is the Pi process environment verbatim, because Pi
     * spawns bash with `env = {...process.env}` — contains the
     * driver-set PI_CODING_AGENT_DIR marker but neither the env var
     * name ANVEX_PI_API_KEY nor the key value. Before M-1 the driver
     * placed the key in that environment and this test fails.
     */
    @Test(timeout = 300_000)
    fun piProcessEnvironmentDoesNotContainTheApiKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AnvexDatabase::class.java).build()
        try {
            val approvals = CommandApprovalService(DurableApprovalRepository(db.approvalGrantDao()))
            val engine = PiEngine(context, approvals)

            val base = File(context.filesDir, "pi-env-test").apply { deleteRecursively(); mkdirs() }
            val project = File(base, "project").apply { mkdirs() }
            val extFile = copyAsset(context, "pi-test/ext/scripted-provider-env.ts", File(base, "ext/scripted-provider-env.ts"))
            copyAsset(context, "pi-test/vendor/event-stream.js", File(base, "vendor/event-stream.js"))

            val fakeKey = "sk-m1-env-proof"
            val mapped = PiProviderMapping.forRuntime(
                ProviderRuntimeConfig(
                    config = ProviderConfig(
                        id = "p1", name = "DeepSeek", kind = ProviderKind.DEEPSEEK,
                        baseUrl = "https://api.deepseek.com", model = "deepseek-chat",
                    ),
                    apiKey = fakeKey,
                ),
            )!!

            val result = engine.run(
                projectRoot = project,
                config = PiEngine.RunConfig(
                    providerName = "poc",
                    modelId = "poc-scripted",
                    apiKey = fakeKey,
                    modelsJson = mapped.modelsJson,
                    extraExtensions = listOf(extFile.absolutePath),
                    agentDir = File(base, "agent").absolutePath,
                    sessionId = "pi-env-test",
                    settleTimeoutMs = 120_000,
                ),
                prompt = "Run the scripted env task.",
                // The env dump is policy-gated (compound redirect); the
                // test user approves it — that is the point of the proof.
                approvalPrompt = PiEngine.ApprovalPrompt { true },
            )

            assertTrue("Pi run failed: ${result.error} (events=${result.eventCounts})", result.success)
            val dump = File(project, "poc-out/env-dump.txt")
            assertTrue("env dump missing — the bash child did not run", dump.isFile)
            val envText = dump.readText()
            assertTrue(
                "dump is not the Pi process environment (driver marker PI_CODING_AGENT_DIR missing)",
                envText.contains("PI_CODING_AGENT_DIR="),
            )
            assertFalse("ANVEX_PI_API_KEY is present in the Pi process environment", envText.contains("ANVEX_PI_API_KEY"))
            assertFalse("the provider key value is present in the Pi process environment", envText.contains(fakeKey))
            Unit
        } finally {
            db.close()
        }
    }

    private fun copyAsset(context: android.content.Context, name: String, dest: File): File {
        dest.parentFile?.mkdirs()
        context.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }
}
