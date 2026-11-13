# ADR-0002: Native agent loop in `:core:agent`, behind one planner interface

## Status

Accepted (direction) — owner decision, 2026-11-13 (stage A1, Anvex-Next).
Implementation lands in later stages (A2+); A1 Task 2 introduces only the
`:core:agent` interface contracts, after sign-off of these ADRs.

## Context

Agent behavior today is split across two engines: the native Kotlin path
(`ChatSession` in `:core:android`, with `:core:engine` orchestration
types — `AgentOrchestration.kt` defines a narrow `AgentPlanner`
fun-interface, `plan(goal, context): AgentPlan`) and the bundled
JavaScript Pi runtime (`core/android/src/main/assets/pi-runtime.zip`,
driven by `PiEngine`/`PiChatBridge`), which carries its own tool-calling
conventions inside a Node process.

Neither path offers first-class **native tool-calling** against the
providers' wire formats; behavior differs per engine, and adding a tool
or a provider means touching engine-specific code. The target is a single
Kotlin-native loop with explicit, testable contracts — introduced by
strangler steps, with Pi kept as fallback until parity (see
`ARCHITECTURE.md`, Transition).

## Decision

- The new agent loop lives in a new **pure-JVM module `:core:agent`**
  (no `android.*`), behind the single orchestrator facade `AgentEngine`.
- Planning/tool-calling is unified behind **one `ActionPlanner`
  interface** with three strategies:
  1. **Native tool-calling** for providers that support it, speaking the
     **OpenAI, Anthropic, and Gemini wire formats** directly.
  2. **Capability detection** per provider/model: native tool-calling is
     used only when the endpoint is known to support it.
  3. **Bolt-text fallback**: where native tool-calling is unavailable,
     the planner falls back to the established bolt-text action format —
     behind the same interface, so the loop and tools do not change.
- The tool set is exactly four tools: **read, write, edit, bash**.
  `bash` is **approval-gated** through the single safety policy
  (ADR-0003); file tools run inside the project workspace.

  This four-tool set is the new loop's minimal core surface, not a removal of existing
  capabilities. Web fetch/search, MCP, artifact generation, and Node/preview remain
  available through their current paths during the transition; integrating them into
  the new loop (or keeping them as extensions) is an A2+ parity item tracked against
  the parity checklist, so no user-facing capability regresses.
- **Progress and memory are file-based** (in the project/app-private
  files), not hidden in process state, so they survive process death and
  are inspectable.

Naming note (DECIDED at A1 sign-off): ActionPlanner is a new :core:agent contract.
The existing narrower AgentPlanner fun-interface in :core:engine
(AgentOrchestration.kt, plan(goal, context): AgentPlan) remains UNTOUCHED in A1/A2.
Whether and how ActionPlanner subsumes AgentPlanner is an explicit A2 design
decision — no silent duplication and no rename before then. (AgentOrchestrator in
the same file already implements a plan→approve→execute pattern and is the natural
starting point for the A2 loop design.)

## Consequences

- One loop to test, one place where provider differences live (the
  planner strategies), and tool behavior identical across providers.
- The four-tool surface is deliberately minimal; new tools require the
  same review bar as a new ADR amendment.
- Pi remains a fully supported fallback until the parity checklist passes
  on device, so this direction creates no user-facing risk while it is
  being built.
- `:core:agent` depends only on `:core:engine`, `:core:ai`, `:core:model`
  (see `ARCHITECTURE.md` module map); Android concerns stay behind
  `:core:android` bridges.
