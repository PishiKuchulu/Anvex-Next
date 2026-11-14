# A2 Design Note — Native Agent Loop

Status: **SIGNED — architect sign-off 2026-11-14**, with the Q1–Q7
resolutions and the three sign-off addenda recorded in §2, §4, §6 and
§7. No code in this stage.
Base: `main` = `71c4bab` (tag `a1-landed`), branch `a2/design-note`.
Inputs: `ARCHITECTURE.md`, `docs/adr/0001`–`0003`, the `:core:agent`
contracts landed in A1.

Evidence discipline: every factual claim about the codebase below cites
`file:line` verified from this checkout's source, found by **type-name
grep** (never by filename, never by comment — the AUD-009 lesson). Where
a claim could not be verified it is marked UNVERIFIED rather than
asserted. Nothing in this note is implemented yet; §2's refinement and
§§3–5's designs land only after sign-off, in the A2a slice (§6).

---

## 1. D1 RESOLVED — `AgentPlanner` KEEPS for the task path

### Evidence (all verified from source at `71c4bab`)

The batch planner is **live in production**, wired into the durable
Agent task path — not into the chat loop:

- `AgentPlanner` is a fun-interface in `:core:engine`:
  `AgentOrchestration.kt:28` — `suspend fun plan(goal, context): AgentPlan`.
  Its orchestrator is `AgentOrchestrator` (`AgentOrchestration.kt:60`),
  whose `run()` (`:65`) executes plan → assess → approve → execute and
  itself calls `ActionSafetyPolicy.assess(step.action)` at
  `AgentOrchestration.kt:81` — **ADR-0003 already holds on this path.**
- A production implementation exists: `ProviderAgentPlanner`
  (`core/android/.../runtime/ProviderAgentPlanner.kt:20`, `plan()` at
  `:21`), which plans via a `ProviderClient` + `BoltStreamParser`.
- Wiring: `AppGraph.agentTaskBridge` is constructed with
  `planner = ProviderAgentPlanner { fallbackAiClient() }`
  (`core/android/.../AppGraph.kt:116`, planner argument at `:120`).
- Execution: inside `AgentTaskBridge`'s durable-task handler
  (`taskEngine.register("agent")`, `AgentTaskBridge.kt:35`), the run
  constructs and executes the orchestrator:
  `AgentOrchestrator(planner, dispatcher, executor).run(...)` at
  `AgentTaskBridge.kt:58` (planner property at `:25`). The bridge
  additionally assesses steps itself
  (`ActionSafetyPolicy.assess` at `AgentTaskBridge.kt:82` and `:109`).
- Reachability from the UI: `AgentCenterScreen` calls
  `graph.enqueueAgentTask(goal, p.id, context)`
  (`app/.../developer/AgentCenterScreen.kt:151`), and
  `AppGraph.enqueueAgentTask` (`AppGraph.kt:257`) routes to
  `agentTaskBridge.enqueue(...)` at `AppGraph.kt:261` for the native
  engine.
- The chat loop does **not** use it: a type-name grep for
  `AgentPlanner|AgentOrchestrator|ActionPlanner` over
  `core/android/.../session/ChatSession.kt` returns **no matches**.
  The chat path's de-facto orchestrator remains `ChatSession` itself,
  as `ARCHITECTURE.md` states.

### PiTaskBridge caller status — LIVE (engine-conditional), not dead

The KDoc on `enqueueAgentTask` says earlier code left
`[PiTaskBridge.enqueue]` "with no production caller"
(`AppGraph.kt:254`, KDoc block `AppGraph.kt:249–256`). Read in full,
that comment is **past tense**: it describes the audit-05 Gap-1 bug
("previously every goal went to the native bridge even when Pi was the
configured engine") that `enqueueAgentTask` was created to fix.
Type-name grep over `app/src/main` + `core/android/src/main` gives the
current truth:

- Declaration: `core/android/.../runtime/pi/PiTaskBridge.kt:24`.
- Only production references: construction as an `AppGraph` property
  (`AppGraph.kt:134–135`) and **one call site** —
  `piTaskBridge.enqueue(prompt = goal, projectId = projectId, context = context)`
  at `AppGraph.kt:259`, inside `enqueueAgentTask`, taken when the
  selected engine is Pi (`agentEngine == AgentEngine.PI`,
  `AppGraph.kt:258`).
- `enqueueAgentTask` is itself called from the UI
  (`AgentCenterScreen.kt:151`).

**Verdict: `PiTaskBridge.enqueue` has a live, engine-conditional
production caller.** Declaring it dead from the comment alone would
repeat the AUD-009 error.

### Decision (architect's D1 rule, applied to the evidence)

- `AgentPlanner`/`AgentOrchestrator` **KEEP** for the durable task
  path. The new loop uses `ActionPlanner` **only**. No subsumption,
  no deletion, no changes to the task path in A2.

### End-state (architect addition) — proposed ADR-0002 amendment

"Keep" is the A2-time decision, not the end-state: two parallel
orchestrators would otherwise violate "ONE orchestrator facade"
forever. The task path is a **later strangler target**. Proposed
amendment text for ADR-0002, to be pasted into the ADR at sign-off:

> **Amendment (A2 design note, 2026-11-14) — the durable task path is
> a strangler target, not a subsumption target.**
> `AgentPlanner`/`AgentOrchestrator` (`:core:engine`) remain the live
> planner/orchestrator of the durable Agent task path
> (`AppGraph.agentTaskBridge` → `AgentTaskBridge`) for the whole of
> A2; the new loop uses `ActionPlanner` only, and neither interface
> subsumes the other. When the new loop reaches parity for the task
> path (checklist in `docs/design/A2-DESIGN-NOTE.md` §1),
> durable tasks will run through the `AgentEngine` facade, and
> `AgentOrchestrator`/`AgentPlanner` will be retired **at that
> point**. ADR-0003 applies to the task path unchanged in the
> meantime — `AgentOrchestrator` already assesses every step through
> `ActionSafetyPolicy`.

Task-path items for the retirement/parity checklist (each must be
true of the new loop before the task path may move):

- [ ] Durable enqueue with the same task identity/checkpoint
      semantics (today: `AgentTaskBridge` resumes from a `step=`
      checkpoint, `AgentTaskBridge.kt:41`).
- [ ] Cooperative cancellation of a running task (today:
      `shouldCancel` in `AgentOrchestrator.run`,
      `AgentOrchestration.kt:65`).
- [ ] Mutation journaling per completed step (today:
      `onStepCompleted` → `MutationRepository`,
      `AgentTaskBridge.kt:58` run arguments).
- [ ] Approvals backed by durable scoped grants (today:
      `CommandApprovalService`, `CommandApprovalService.kt:9`).
- [ ] Engine routing parity: a Pi-selected task still runs on Pi
      (today: the `agentEngine == PI` branch,
      `AppGraph.kt:258–259`) or Pi itself has retired by then.
- [ ] Agent Center's start path (`AgentCenterScreen.kt:151`) works
      unchanged against the facade.

---

## 2. D3 — Typed error taxonomy (spec now; code in A2a commit 1)

### Current state (verified)

- `TurnFailed` is untyped today: `data class TurnFailed(val message: String)`
  at `core/agent/.../AgentEvent.kt:36–37`.
- `AiError` is a **data class** (not sealed) in `:core:ai`:
  `AiTypes.kt:73`, with `val message: String` (`AiTypes.kt:75`),
  a `Kind` enum (`AUTH, ACCESS_RESTRICTED, RATE_LIMIT, NOT_FOUND,
  NETWORK, PROTOCOL, CANCELED, UNKNOWN`, `AiTypes.kt:80–82`) and a
  UI-oriented `userTitle` (`AiTypes.kt:85`). So `cause.message` in the
  architect's sketch exists as-is. **Sign-off decision (Q3, addendum
  a):** `Provider.message` delegates to **`cause.userTitle`**, not the
  raw `message` — the contract field is user-presentable, `userTitle`
  exists precisely for that, and raw provider text is diagnostic (it
  remains available on `cause` for logs). The bridge still passes the
  full `AiError` to `UiState.error`, so today's UI does not change.
- **Every `TurnFailed` call site in the repo** (type-name grep over
  `core` + `app` sources): the definition (`AgentEvent.kt:37`); two
  KDoc mentions (`AgentEngine.kt:24`, `:32`); and exactly **one
  construction site** — the contract test's `FakeEngine` denial path,
  `publish(AgentEvent.TurnFailed("denied"))` at
  `core/agent/.../AgentContractsTest.kt:83`. No production code
  constructs `TurnFailed` yet (no loop exists), so the refinement
  touches one test site plus the definition.
- Bridge targets exist today: `ChatSession.UiState`
  (`ChatSession.kt:114`) carries `val error: AiError?`
  (`ChatSession.kt:119`) and `val engineError: String?`
  (`ChatSession.kt:121`).

### The refinement (lands as A2a commit 1, after sign-off)

New file `core/agent/.../AgentError.kt`:

```kotlin
sealed interface AgentError {
    /** User-presentable summary of the failure. */
    val message: String

    /** A provider/client failure; [cause] is :core:ai's typed error. */
    data class Provider(val cause: AiError) : AgentError {
        override val message: String get() = cause.userTitle
    }

    /** One tool call failed in execution. */
    data class Tool(val toolName: String, override val message: String) : AgentError

    /** The single safety policy (ADR-0003) or the user denied the call. */
    data class Policy(val reason: String) : AgentError {
        override val message: String get() = reason
    }

    /** Anything else: loop bugs, contract violations, IO on progress files. */
    data class Internal(override val message: String) : AgentError
}
```

And in `AgentEvent.kt`:

```kotlin
data class TurnFailed(val turnId: String, val error: AgentError) : AgentEvent
```

(`turnId` pairs with `TurnStarted(turnId)`/`TurnCompleted(turnId)`,
`AgentEvent.kt:13`/`:34`, so a bridge can attribute the failure to
its turn without tracking state.)

Bridge mapping (implemented in `:core:android` when the loop is wired,
A3 — specified now so the taxonomy is designed against it):
`AgentError.Provider` → `UiState.error` (the `AiError` itself travels,
so the UI keeps `userTitle`/kind handling); `Tool`/`Policy`/`Internal`
→ `UiState.engineError` (their `message`).

### Dependency consequence (D2 applied)

`AiError` appears in `:core:agent`'s **public API** (a public data
class constructor property on a public sealed variant, itself carried
by the public `TurnFailed`), so per D2's own exception the dep is
**`api(project(":core:ai"))`** in `core/agent/build.gradle.kts`
(today it declares only `api(libs.kotlinx.coroutines.core)`,
`core/agent/build.gradle.kts:16`). Precedent: `:core:engine` and
`:core:ai` both expose `:core:model` as `api`
(`core/engine/build.gradle.kts:12`, `core/ai/build.gradle.kts:13`);
`:core:ai`'s own `api` dep makes `:core:model` types available
transitively. The §3/§4 work adds `implementation(project(":core:engine"))`
to `:core:agent` in the same stage — `BoltAction` stays internal to
the loop's gate, never in a public contract type, so `implementation`
is correct there.

### Red → green plan (contract tests)

1. **Red:** update `AgentContractsTest` first — the `FakeEngine`
   denial path emits `TurnFailed(turnId, AgentError.Policy("denied"))`,
   plus new assertions: a `TurnFailed` event's `error.message`
   delegates per variant (incl. `Provider(AiError(Kind.NETWORK, "…"))`
   → that error's **`userTitle`**, per addendum (a)). Against the A1
   contracts this does not compile / fails — the red state.
2. **Green:** add `AgentError.kt`, change `TurnFailed`, declare the
   `:core:ai` dep. `:core:agent:test` green; no other module's tests
   change (no other call sites exist — verified above).

---

## 3. NEW PORT — `ToolExecutor` (the missing execution boundary)

A1 gave the loop tool *descriptions* (`ToolSpec`/`ToolCall`/
`ToolResult`, `Tools.kt:12–32`) but no port that *runs* a call.
Without one, A2a's `TurnRunner` cannot close the loop.

### Port (new contract in `:core:agent`, A2a)

```kotlin
interface ToolExecutor {
    suspend fun execute(context: TurnContext, call: ToolCall): ToolResult
}
```

Signature note: the brief's sketch was `execute(call)`. The context
parameter is required by the contracts themselves — the workspace is
identified by `TurnContext.projectId` (`TurnContext.kt:13–18`), and
`ActionPlanner.nextAction` already passes `TurnContext` separately
for the same reason (`ActionPlanner.kt:27–31`). The executor returns
`ToolResult` (never throws for an expected tool failure: failures are
`ToolResult(isError = true)`, `Tools.kt:27–32`); unexpected exceptions
are the loop's `AgentError.Internal` concern.

Precedent: `:core:engine` already has this exact boundary shape for
the batch path — `AgentActionExecutor`
(`AgentOrchestration.kt:34–36`): `suspend fun execute(action: BoltAction): ActionResult`.
`ToolExecutor` is its `ToolCall`-typed counterpart for the new loop;
the two coexist because their paths do (see §1).

### Who does what (no parallel policy — ADR-0003)

The **gate lives in the loop** (`TurnRunner`, `:core:agent`), the
**side effects live behind the port** (`:core:android`):

1. Planner proposes a `ToolCall` → loop emits `ToolCallProposed`.
2. Loop maps the call through the §4 shared primitive →
   `BoltAction`, then `ActionSafetyPolicy.assess(action)`
   (`ActionSafetyPolicy.kt:411`; `Assessment.requiresApproval` at
   `:14–15`).
3. Approval is required when the assessment requires it **or** the
   spec does (`CoreTools.BASH_SPEC.requiresApproval`,
   `Tools.kt:62–67`): the loop asks `ApprovalGateway.requestApproval`
   (`ApprovalGateway.kt:24–26`) and emits
   `ToolCallApproved`/`ToolCallDenied`. A hard policy denial or a
   user denial ends the turn as `TurnFailed(turnId, AgentError.Policy)`
   — preserving the A1 contract test's denial semantics
   (`AgentContractsTest.kt:81–84`).
4. Only then `ToolExecutor.execute(context, call)` runs.

This mirrors the Pi path's interplay — `PiEngine` maps
(`PiEngine.kt:355`), `CommandApprovalService.decide` assesses
(`CommandApprovalService.kt:13`), the gateway ask is raised
(`PiEngine.kt:379`) — except the new loop prompts through the
`ApprovalGateway` contract instead of Pi's gateway wire format, and
emits the approval cycle as events (which the Pi path cannot).

The rejected alternative — hiding the gate inside the executor
implementation — would make `ToolCallApproved`/`ToolCallDenied`
un-emittable by the loop and is therefore incompatible with the A1
event contract.

### Implementation home (`:core:android`, A2a)

- `read`/`write`/`edit` → the project file system through
  `ActionDispatcher.applyFileAction` (`ActionDispatcher.kt:72`) and
  its read path — reusing, among others, the dispatcher's
  execution-layer protected-path guard (`ActionDispatcher.kt:226`).
  Those guards are defense-in-depth at the execution layer, not a
  second policy: the assess decision was already made in step 2.
- `bash` → the Android shell runtime (`AndroidShellRuntime`, which
  re-assesses the raw command at `AndroidShellRuntime.kt:29` —
  existing behavior, unchanged).
- Path hygiene inside execution uses `PathSanitizer.sanitizeOrNull`
  (`PathSanitizer.kt:55`) and `SensitivePaths` (e.g. `isSensitive`,
  `SensitivePaths.kt:203`) exactly as the existing paths do.

### Fail-closed in direct chat

`TurnContext.projectId == null` is direct-chat mode — "no project
workspace, file tools unavailable" (`TurnContext.kt:9–11`; the legacy
flag is `ChatSession.directChat`, `ChatSession.kt:94`). The executor
must return `ToolResult(isError = true)` for `read`/`write`/`edit`
**without touching storage** in that mode. (The task path enforces
the same invariant today: "Agent tasks require a project workspace",
`AgentTaskBridge.kt:39`.) Whether `bash` may run without a
project is **not** specified by the A1 contracts — open question Q1
(§7); A2a does not guess.

---

## 4. MAPPING UNIFICATION — one `ToolCall → BoltAction` primitive

### The divergence risk is present-tense, not hypothetical

- Pi maps its tool calls in `PiToolMapping.toBoltAction(toolName, input: JSONObject)`
  (`PiToolMapping.kt:31–56`): `write`/`edit` → `FileAction`,
  `bash` → `ShellAction`, `read` → `ToolAction("read", path)`,
  plus Pi-only tools (`grep`/`find`/`ls`/`mcp`, `:47–54`) and an
  `UnknownAction` fail-closed tail (`:54`).
- The native chat path has no `ToolCall` mapping at all —
  `BoltStreamParser` produces `BoltAction`s directly from bolt text
  (`BoltStreamParser.kt:323`).
- The protected-path set already exists **twice**, hand-synced:
  `ActionDispatcher.isProtectedPath` (private,
  `ActionDispatcher.kt:226`) and `PiToolMapping.protectedPath`
  (`PiToolMapping.kt:110–115`), whose own KDoc admits it mirrors the
  dispatcher (`PiToolMapping.kt:101–109`). A third copy for the new
  loop is exactly what ADR-0003 forbids.

> **Post-sign-off errata (A2a-0, factual correction — no decision
> changed):** the two copies above were near-mirrors, not exact
> mirrors. The Pi copy carries a blank guard and backslash
> normalization (`path.replace('\\','/')` before segment matching);
> the dispatcher copy has neither. They diverge only on
> backslash-separated metadata segments (e.g. `sub\.git\config`:
> Pi → protected, dispatcher → not). On the dispatcher side the
> difference is unreachable: every `isProtectedPath` call site
> passes `PathSanitizer.sanitizeOrNull` output, which is already
> backslash-normalized and never blank. The shared primitive
> (`SensitivePaths.isProtectedPath`, §4 item 1) therefore adopts
> the **Pi form** — identical on Pi by construction, and identical
> on the dispatcher's entire reachable input domain.

### The extraction (in `:core:engine`, lands with A2a)

1. **`SensitivePaths.isProtectedPath(path: String): Boolean`** — the
   protected-set test moves next to the other path knowledge
   (`SensitivePaths.kt`; `isWorkflowPath` already lives there at
   `:143` and is already consumed by `PiToolMapping.kt:114`).
   `ActionDispatcher` and `PiToolMapping` both delegate to it;
   their private copies are deleted.
2. **`CoreToolActions`** (proposed name; `object` in `:core:engine`):

   ```kotlin
   object CoreToolActions {
       fun toBoltAction(toolName: String, path: String?, content: String?, command: String?): BoltAction
   }
   ```

   Branches: `write` → `FileAction(content ?: "", path ?: "")`;
   `edit` → `FileAction(content ?: "", path ?: "")` — for `edit`,
   `content` is the *mutation fingerprint* string, exactly as Pi
   does today (its edits array stands in for the result,
   `PiToolMapping.kt:38–44`); `bash` → `ShellAction(command ?: "")`;
   `read` → `ToolAction("read", path ?: "")`; anything else →
   `UnknownAction(toolName, …)` (fail-closed, as `PiToolMapping.kt:54`).
   Field extraction from each consumer's wire shape stays with the
   consumer: Pi flattens its `JSONObject`; the new loop parses
   `ToolCall.argumentsJson` (`Tools.kt:20–24`). The primitive is
   pure strings in, `BoltAction` out — unit-testable in `:core:engine`.

   **Caller contract for the `edit` fingerprint (sign-off addendum
   (b), security-relevant):** for `edit`, the caller (the new loop;
   Pi's mapping already behaves this way) MUST pass
   `content = the FULL `argumentsJson`` — deterministic, covering the
   complete mutation (oldText + newText), with **no truncation** —
   and `path` as the file path. This string is the mutation
   fingerprint that feeds durable grants in
   `CommandApprovalService`: a weak or truncated fingerprint means
   grant collisions (an approval granted for one edit silently
   covering a different one). The M-02 lesson applies: the
   fingerprint must cover the whole mutation.
3. **Consumers:** `PiToolMapping.toBoltAction` delegates for the
   four shared tools and keeps its Pi-only branches
   (`grep`/`find`/`ls`/`mcp`) and all of `isHardDenied`
   (`PiToolMapping.kt:86`) Pi-side — those encode Pi-process-specific
   risks (Pi executes its own tools; see its KDoc `:58–85`) and are
   not part of the shared mapping. The new loop's `TurnRunner` calls
   the primitive directly (§3 step 2).

### Parity locks

- Existing tests must pass **unchanged**: `PiMappingsTest`
  (`core/android/src/test/.../pi/PiMappingsTest.kt` — write→safe
  `FileAction` `:30`, read-only tools safe `:51`, mcp/unknown
  fail-closed `:60`, hard-deny set `:96`) and the engine policy
  suites (`ActionSafetyPolicyTest`, `SafetyPolicyM0Test` in
  `:core:engine` tests).
- New in A2a: a `:core:engine` unit test for `CoreToolActions` +
  `SensitivePaths.isProtectedPath`, and a **cross-consumer parity
  test** (in `:core:android` tests, beside `PiMappingsTest`): a
  fixture table of `(toolName, path, content, command)` cases run
  through both `PiToolMapping.toBoltAction` (via `JSONObject`) and
  `CoreToolActions.toBoltAction`, asserting **equal `BoltAction` and
  equal `ActionSafetyPolicy.assess` verdict** for every row. The
  fixture includes the **edit-fingerprint cases** (addendum (b)):
  two different edits produce different fingerprints, and an edit
  larger than 2 MB assesses as `REQUIRES_APPROVAL`.

---

## 5. D4 — Three planner strategies behind `ActionPlanner`

### 5.0 Verified starting point: native tool-calling is absent

- The request type has no tool surface: `ChatRequest`
  (`AiTypes.kt:21–30`) is systemPrompt/turns/attachments/maxTokens/
  temperature/thinkingLevel/webSearch only; a turn is
  `ChatTurn(role, content)` (`AiTypes.kt:18–19`) — no tool-call or
  tool-result representation.
- The response type has none either: `StreamEvent`
  (`AiTypes.kt:38–60`) is Started/Reasoning/Token/Completed/Failed/
  Sources — no tool-call delta or completion event.
- The client boundary is `ProviderClient.streamChat(ChatRequest): Flow<StreamEvent>`
  (`ProviderClient.kt:14–19`), factory-dispatched by protocol family
  (`ProviderClient.kt:33–35`).
- A grep for `tool_call|toolCall|function_call|functionCall|tool_use|toolUse`
  over `core/ai/src/main` returns **no matches**. The only `tools`
  payloads any client sends are the providers' **built-in web-search
  server tools**: Anthropic's `web_search_20250305`
  (`AnthropicClient.kt:98–106`, gated by `ChatRequest.webSearch`) and
  Gemini's `google_search` grounding (`GeminiClient.kt:66`). Those
  return sources, never app-executed calls, and are not a foundation
  to build on. (MCP — `core/ai/.../mcp/McpClient.kt` — is a separate
  client protocol and stays on its current path per ADR-0002.)

### 5.1 New `:core:ai` surface (shared design; OpenAI lands in A2a)

New types in `:core:ai` (names proposed):

- `AiToolDefinition(name, description, parametersJsonSchema)` — the
  wire-neutral tool definition. It deliberately mirrors
  `ToolSpec`'s fields (`Tools.kt:12–17`): `:core:ai` may not depend
  on `:core:agent` (no such edge in the module map), so the
  `TurnRunner`/bridge converts `ToolSpec → AiToolDefinition`.
- `ChatRequest` gains `tools: List<AiToolDefinition> = emptyList()`
  (defaulted: every existing caller is unaffected).
- Tool conversation turns: `ChatTurn` gains optional
  `toolCalls: List<AiToolCall>` (assistant turns) and
  `toolCallId: String?` (tool-result turns), with
  `AiToolCall(id, name, argumentsJson)`. Each client renders these
  into its format's native shape (below).
- `StreamEvent` gains **`ToolCallReady(calls: List<AiToolCall>)`**:
  clients assemble streaming fragments **internally** and emit the
  completed calls once, just before `Completed`. Raw per-delta tool
  events are *not* exposed — this keeps every existing
  `StreamEvent` consumer (notably `ChatSession`) untouched when no
  tools are requested. (Open question Q7 asks the architect to
  confirm this boundary.)

### 5.2 Per wire format

**OpenAI (A2a — implementable from this note).**
Client: `OpenAiCompatibleClient` (also serves OpenRouter/DeepSeek/
Groq/Mistral/custom endpoints via the factory,
`ProviderClient.kt:35`).

- *Request:* in the body built at `OpenAiCompatibleClient.kt:146`,
  add `tools: [{type: "function", function: {name, description,
  parameters}}]`, `parameters` being `parametersJsonSchema` parsed
  into a JSON object; `tool_choice` left `"auto"`. Messages render
  from the turns built at `OpenAiCompatibleClient.kt:48` onward:
  assistant tool-call turns → `{role: "assistant", content: null,
  tool_calls: [{id, type: "function", function: {name, arguments}}]}`;
  tool-result turns → `{role: "tool", tool_call_id, content}`.
- *Streaming assembly:* deltas are parsed at
  `OpenAiCompatibleClient.kt:184–201` (`choices[0].delta`, finish
  reason captured at `:201`). Accumulate `delta.tool_calls[]`
  fragments per `index` (id + function.name from the first fragment,
  function.arguments concatenated). On `finish_reason == "tool_calls"`
  (or stream end with pending fragments), emit `ToolCallReady`.
- *Parallel-call policy:* a response may carry several calls. The
  loop executes them **sequentially in index order** — the four
  tools are stateful file/shell operations; parallel execution is
  explicitly out of scope for A2a.
- *Error shapes:* HTTP failures keep the existing `AiError` mapping
  (`AiTypes.kt:73–99`); a tool-call whose assembled `arguments`
  string is not valid JSON fails the turn with
  `AiError(Kind.PROTOCOL)` surfaced as
  `AgentError.Provider` — never a silent empty-arguments call.

**Anthropic (A2b — design-level sketch).**
Client: `AnthropicClient`. Request: a `tools` array of
`{name, description, input_schema}` beside the existing web-search
insertion point (`AnthropicClient.kt:98–106`; the server tool and
client tools coexist in one array). Messages: assistant turns carry
`tool_use` content blocks; results return as `tool_result` blocks
in a user turn. Streaming: `content_block_start` for a `tool_use`
block (`AnthropicClient.kt:179`) then `input_json_delta` fragments
handled at the `content_block_delta` site (`AnthropicClient.kt:154`)
— accumulate `partial_json` per block index; `stop_reason ==
"tool_use"` triggers `ToolCallReady`. Parallel policy: same
sequential rule.

**Gemini (A2b — design-level sketch).**
Client: `GeminiClient`. Request: `tools: [{functionDeclarations:
[{name, description, parameters}]}]`, merged with the existing
`google_search` tool entry (`GeminiClient.kt:66`) into one `tools`
array. Responses: `functionCall` parts arrive **whole** in the
candidate parts parsed at `GeminiClient.kt:132` — no fragment
assembly — but Gemini calls carry **no id**, so the planner
synthesizes stable ids (`gemini-<turnIndex>-<callIndex>`); results
return as `functionResponse` parts. Parallel policy: same
sequential rule.

### 5.3 Capability detection (no probing)

- **Static table first.** `:core:ai` already has the pattern:
  `ProviderCapabilities` (`ProviderFeatures.kt:24–30`) answers
  per-`ProviderKind` booleans for webSearch/imageGeneration. A2 adds
  `toolCalling(kind): ToolCallingSupport` there, with
  `ToolCallingSupport ∈ { NATIVE, WIRE_CAPABLE_UNKNOWN, NONE }`:
  `OPENAI`/`ANTHROPIC`/`GEMINI` (and their custom-compatible
  counterparts, which the factory routes to the same clients,
  `ProviderClient.kt:33–35`) → `NATIVE`;
  OpenAI-wire aggregators/models of unknown ability
  (`OPENROUTER`, `DEEPSEEK`, `GROQ`, `MISTRAL`, `ZAI_ACCOUNT`,
  `OPENCODE_*`, local servers; kinds at `Provider.kt:87–110`)
  → `WIRE_CAPABLE_UNKNOWN`; anything else → `NONE`. Conservative by
  default, per the `GeminiCapabilities` precedent
  (`GeminiCapabilities.kt:3–8`: unknown models get no optimistic
  request).
- **Explicit user override** for custom endpoints:
  `ToolCallingOverride ∈ { AUTO, FORCE_NATIVE, FORCE_BOLT }`,
  resolved by the settings layer (`:core:android`) per provider and
  handed to the router as part of a capability snapshot — the
  override is a *value*, never a settings read inside `:core:ai` or
  `:core:agent`. (Persistence shape is open question Q2.)
- **Bounded runtime fallback:** if a tools request fails in a
  tool-specific way — an HTTP 400 parameter rejection (the client
  already has a detector precedent, `isParameterRejection`,
  `OpenAiCompatibleClient.kt:128`) or a `PROTOCOL` failure while
  tools were attached — the router marks that provider+model
  bolt-only **for the session** and re-plans the turn once through
  the bolt strategy. One downgrade, no retry loop, no probing
  requests.

### 5.4 Router

`PlannerRouter` (proposed name) lives in **`:core:agent`**: given a
`TurnContext` plus the capability snapshot resolved by the
`:core:android` wiring (resolving opaque ids is the bridge's job,
`TurnContext.kt:3–7`), it selects the `ActionPlanner` for the turn.
Selection order:

1. `FORCE_BOLT` → bolt planner.
2. `FORCE_NATIVE` → native planner (runtime downgrade armed).
3. `AUTO` → table: `NATIVE` → native; `WIRE_CAPABLE_UNKNOWN` →
   native with downgrade armed; `NONE` → bolt.

Capability *knowledge* lives in `:core:ai` (`ProviderCapabilities` +
the override type); *selection* lives in `:core:agent`; *resolution*
(settings, stored override) lives in `:core:android`. A2a ships one
native planner and no router yet — the router arrives with the
second strategy in A2c, so A2a hard-wires nothing it must undo:
`TurnRunner` takes its `ActionPlanner` as a constructor parameter.

### 5.5 Bolt-text fallback (A2c) — adapt, never rewrite

`BoltStreamParser` (`BoltStreamParser.kt:19`) is the tested,
incremental parser of the `<boltArtifact>/<boltAction>` protocol:
`parse(messageId, input)` (`:35`) consumes accumulated text,
callbacks (`ParserCallbacks`, `BoltActions.kt:87`) deliver closed
actions as `ActionCallbackData`, and `flush` (`BoltStreamParser.kt:267`)
closes a turn. The adaptation pattern already exists in production:
`ProviderAgentPlanner` wraps the parser with `ParserCallbacks` and
collects actions (`ProviderAgentPlanner.kt:20–25`) — for the *task*
path's batch planner.

`BoltTextPlanner` (A2c, `:core:agent`) reuses the parser unchanged:
stream the response text through `parse`, and on each closed action
convert `BoltAction → ToolCall` — `FileAction` → `write`
(`{path, content}`), `ShellAction` → `bash` (`{command}`),
`ToolAction("read", …)` → `read` (`{path}`); non-core actions
(artifact/unknown) have no core `ToolCall` and surface as text.
Parser behavior stays locked by `BoltStreamParserTest`
(`:core:engine` tests). No new parser is designed or written.

---

## 6. A2a — Vertical slice (scope after sign-off)

**Goal:** one end-to-end native path — OpenAI tool-calling → loop →
four tools → `AgentEvent` stream — proven by scripted fake-provider
tests, with the D3 refinement landed first and the §4 extraction
landed before it as its own pre-slice.

**Scope split (sign-off addendum (c), Q5 DECIDED):** the §4
extraction is the ONLY part of this stage that touches live
production code (`PiToolMapping` + `ActionDispatcher`), so it lands
FIRST as a separate pre-slice PR, **A2a-0** = the signed note + the
§4 extraction + parity tests (behavior-identical, independently
reviewable and revertable). **A2a** proper (below) is then fully
additive — no live-code edits.

In scope for A2a, in commit order:

1. **D3 refinement** (§2): `AgentError`, `TurnFailed(turnId, error)`,
   `api(project(":core:ai"))`, contract-test red → green.
2. **Mapping extraction** (§4): landed in **A2a-0** (see above);
   A2a consumes the primitive, it does not introduce it.
3. **`ToolExecutor` port** (§3) in `:core:agent` + its
   `:core:android` implementation for the four tools.
4. **`:core:ai` OpenAI tool-calling** (§5.1–5.2): `AiToolDefinition`,
   `ChatRequest.tools`, tool turns, internal fragment assembly,
   `ToolCallReady`.
5. **`TurnRunner`** in `:core:agent`: minimal `AgentEngine`
   implementation — planner → gate (§3) → executor → events,
   `AgentError` mapping on every failure path; `NativeOpenAiPlanner`
   (the §5.2 strategy) as its `ActionPlanner`. Constructor-injected
   planner/executor/gateway (no router yet, §5.4).
6. **Fake-provider scripted tests** at every layer (see §7).

**NON-GOALS (explicit):** no UI wiring and no `agentEngine` flag
flip (A3); no `EngineChoice` rename (pre-A3 micro-task); no
Anthropic/Gemini tool-calling (A2b); no router and no bolt fallback
(A2c); no task-path changes (§1: keep); no KB-001; no new catalog
dependencies (project deps only, per D2); no MCP/web/artifact tools
in the loop (ADR-0002 parity items, later).

---

## 7. Test plan, CI receipts, open questions

### Test plan (red first, per slice)

- **D3:** `AgentContractsTest` updated assertions fail/compile-fail
  against A1 contracts → green after the refinement
  (`:core:agent:test`).
- **§4 extraction (in A2a-0):** `CoreToolActionsTest` +
  `isProtectedPath` cases (`:core:engine:test`); parity fixture test
  (incl. the edit-fingerprint rows) + unchanged `PiMappingsTest`
  (`:core:android:testDebugUnitTest`).
- **`:core:ai` OpenAI:** client tests feeding recorded SSE chunks —
  fragmented `tool_calls` deltas across chunk boundaries assemble
  into one `ToolCallReady`; malformed arguments JSON →
  `Failed(PROTOCOL)`; a no-tools request is byte-identical to
  today's body (regression lock) (`:core:ai:test`).
- **`TurnRunner`:** scripted fakes for planner/executor/gateway —
  full event sequence for a bash turn (proposed → approved → result
  → completed); user denial → `TurnFailed(Policy)`; policy denial →
  `TurnFailed(Policy)`; provider failure → `TurnFailed(Provider)`;
  tool failure → `ToolResult(isError)` fed back, not a dead turn
  (`:core:agent:test`).
- **`ToolExecutor` impl:** direct-chat fail-closed cases
  (projectId = null → error results, zero storage writes) and
  protected-path write refusal (`:core:android:testDebugUnitTest`).

### CI receipts shape for A2a (same gates as A1)

- PR checks: Build workflow **test** job green — its command must
  show `:core:engine:test :core:ai:test :core:agent:test
  :core:android:testDebugUnitTest :app:testDebugUnitTest`;
  **apk** job green; CodeQL **analyze** with no new alerts.
- Emulator **smoke** dispatched on the branch, conclusion green,
  and the `android-test-reports` artifact present in the run
  (the A1-established per-test receipt).
- Diff confined to `:core:agent`, `:core:ai`, `:core:engine`,
  `:core:android` (+ tests) — no `:app` changes in A2a.

### Open questions for the architect — ALL RESOLVED at sign-off (2026-11-14)

- **Q1.** `bash` when `projectId == null` (direct chat): fail
  closed like the file tools, or run against an app-private working
  directory? A1 contracts are silent; A2a will not guess.
  **DECIDED: FAIL CLOSED.** Legacy direct chat never had a shell;
  a shell without a workspace has no meaningful policy root (the
  absolute-path checks in `bashDenied` are bound to `projectRoot`),
  and an app-private cwd would be new attack surface + scope creep.
  A2a: `projectId == null` → `ToolResult(isError = true)`, zero
  execution. A future sandbox-root variant would need its own ADR.
- **Q2.** `ToolCallingOverride` persistence: a field on the
  provider config (per-provider, survives backup?) or a settings
  flag keyed by provider id? A2a needs the type; persistence can
  land with A3 wiring if the architect prefers.
  **DECIDED: settings map keyed by provider id** (DataStore JSON —
  the `providerModelSets` precedent; deliberately NOT a Room column,
  no schema bump). The `ToolCallingOverride` type lands in A2a;
  persistence + resolution land with the A3 wiring. NOT restored
  from backup (device-local, like `providerModelSets`).
  `:core:ai`/`:core:agent` never read settings — a snapshot is
  passed in.
- **Q3.** `AgentError.Provider.message` delegates to
  `AiError.message` (raw provider text). The bridge passes the
  `AiError` itself to `UiState.error`, whose UI renders
  `userTitle` — so the raw message only surfaces where an
  `AgentError` is rendered directly. Confirm raw delegation is
  acceptable, vs. delegating to `userTitle`.
  **DECIDED: delegate to `userTitle`** — see §2, addendum (a).
- **Q4.** User denial at the gateway ends the turn
  (`TurnFailed(Policy)`, A1 semantics). Alternative: feed the
  denial back to the planner as an error `ToolResult` and let the
  model adapt. A2a keeps A1 semantics unless directed otherwise.
  **DECIDED: A1 semantics stand** — denial ends the turn
  (`TurnFailed(Policy)`); same philosophy as ChatSession's existing
  approval latch (a denial is a decision, not an invitation to
  negotiate; feeding it back risks dialog spam and fatigue). Any
  revisit only via an explicit later amendment.
- **Q5.** §4 extraction inside A2a (recommended: it is on
  `TurnRunner`'s critical path and is behavior-identical) vs. its
  own pre-slice PR. Either way it lands before `TurnRunner`.
  **DECIDED: its own pre-slice PR, A2a-0** (the note's
  recommendation is overruled) — see §6, addendum (c).
- **Q6.** Gemini synthesized call ids (`gemini-<turn>-<index>`,
  §5.2) — acceptable, or should `:core:ai` grow a real id concept
  for Gemini first? (A2b question; flagged now because
  `AiToolCall.id` is designed in A2a.)
  **DECIDED: acceptable for A2b, with constraints** — ids unique
  **per session** (sessionId or a monotonic counter — NOT a turn
  index, which resets on resume); synthesis inside the Gemini
  client; A2b tests pin distinct, stable ids and correct result
  mapping. No new "real id" concept needed (`AiToolCall.id`
  exists).
- **Q7.** Confirm the `StreamEvent.ToolCallReady` boundary (§5.1):
  clients assemble fragments internally and emit completed calls
  once, keeping raw tool deltas invisible to existing consumers.
  **DECIDED: CONFIRMED.** Clients assemble fragments internally
  and emit one completed call; raw deltas stay invisible. Existing
  `StreamEvent` consumers (ChatSession) remain untouched while
  `tools` is empty (the default); provider-specific assembly
  belongs to the client; the loop only ever wants complete calls.

---

*End of note. SIGNED 2026-11-14 (Q1–Q7 resolved; addenda (a)–(c)
applied). Next: A2a-0 (signed note + §4 extraction), then A2a.*
