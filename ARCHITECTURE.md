# Anvex — Target Architecture

Status: **authoritative target** for Anvex-Next, established in stage A1
(2026-11-13). This document describes where the architecture is going and,
where they differ, what the code looks like today. Nothing here claims the
target already exists. Decisions are recorded in `docs/adr/0001`–`0003`.

## Module map and allowed dependencies (TARGET)

```
:app ──► :core:android ──► :core:agent, :core:engine, :core:ai, :core:model
:core:agent ──► :core:engine, :core:ai, :core:model
:core:engine ──► :core:model
:core:ai ──► :core:model
:core:model ──► (nothing)
```

- `:core:agent`, `:core:engine`, `:core:ai`, `:core:model` are **pure JVM**
  modules (`kotlin.jvm` plugin, JVM 17): no `android.*` imports, no Android
  SDK types in their APIs.
- `:core:android` is the only Android-library core module: it hosts the
  Android runtimes, persistence, and the bridges into the pure modules.
- `:app` is the Android application: Compose UI and ViewModels only. It
  depends on `:core:android`; it must not reach around it into the pure
  modules (see current-state note below).

### Current state (at `main` = `216b230`, verified)

- Modules in `settings.gradle.kts`: `:app`, `:core:model`, `:core:engine`,
  `:core:ai`, `:core:android`. **`:core:agent` does not exist yet** — it is
  introduced by A1 Task 2 as interfaces only, after ADR sign-off.
- `:core:model`, `:core:engine`, `:core:ai` already satisfy the purity rule
  (Kotlin/JVM plugins, zero `android.*` imports in main sources).
- Dependencies today: `app → {core:android, core:engine, core:model,
  core:ai}` (app still depends on the pure modules directly — target is
  `app → core:android` only); `core:android → {core:model, core:engine,
  core:ai}`; `core:engine → core:model` (api); `core:ai → core:model` (api);
  `core:model →` none.

## Single sources of truth

### ONE chat UI — `ChatScreen` (ADR-0001)

There is exactly one chat surface: the Compose `ChatScreen`
(`app/src/main/kotlin/com/anvex/app/ui/screens/chat/ChatScreen.kt`).
What is today a separate screen, `DirectChatScreen`
(`app/.../ui/screens/chat/DirectChatScreen.kt`, with
`app/.../vm/DirectChatViewModel.kt`), becomes a **mode** of `ChatScreen`,
not a second surface. The session layer already anticipates this:
`ChatSession` takes a `directChat` mode flag and both ViewModels already
drive the same session class.

The JavaScript WebView harness (`HarnessScreen` in `:app`,
`HarnessBridge` in `:core:android`, web app under `app/src/main/assets/
harness/`; see `docs/HARNESS.md`) is **debug-only until the native loop
reaches parity, and is then retired**. It is not a product surface.

### ONE UI state — `ChatSession.UiState`

`ChatSession.UiState` (`core/android/src/main/kotlin/com/anvex/core/android/
session/ChatSession.kt`), exposed as `StateFlow<UiState>`, is the single
chat UI state: streaming text/reasoning, artifacts, quick actions, typed
error, status line, memory/context notes. New chat state is added here or
in its direct successor under the `AgentEngine` facade — never in a
parallel state class owned by a screen.

### ONE orchestrator facade — `AgentEngine` (ADR-0002)

All chat/agent turns go through one facade, `AgentEngine` in `:core:agent`
(interfaces arrive in A1 Task 2). Today the de-facto orchestrator is
`ChatSession` itself (in `:core:android`), assisted by
`:core:engine`'s orchestration types (`AgentOrchestration.kt`). The facade
absorbs that role behind the `agentEngine` flag (see Transition); screens
and ViewModels never talk to engines (native loop, Pi, planners) directly.

Naming note (DECIDED at A1 sign-off): the :core:agent facade keeps the name
AgentEngine. The existing user-facing selector enum AgentEngine {NATIVE, PI} in
:core:android will be renamed to EngineChoice (values NATIVE/PI preserved) in a
dedicated rename micro-task scheduled BEFORE the facade is wired into the app (A3)
— not in A1/A2. The rename is persistence-safe (verified): DataStore stores the
value via .name ("NATIVE"/"PI") under key "agent_engine", kotlinx.serialization
encodes the enum by entry name, and restoreFromBackup deliberately does not restore
this field. No A1/A2 source file imports both types, so there is no active collision
before the rename.

### ONE safety policy — `:core:engine` (ADR-0003)

Every execution path — native dispatcher, shell/terminal runtimes, and the
Pi tool mapping — is gated by the single safety policy in `:core:engine`:
`SensitivePaths` and `ActionSafetyPolicy`
(`core/engine/src/main/kotlin/com/anvex/core/engine/`). No path may grow
its own parallel policy. This is already true today and is formalized by
ADR-0003.

## Error policy (new code)

- **No `runCatching { … }.getOrNull()` in new code.** Silent
  null-on-failure hides defects and was a recurring audit theme.
- Fallible operations return a **typed `Result`** (sealed success/failure
  carrying a typed error), and unexpected failures are reported through a
  **single `ErrorReporter`** — one funnel for logging/reporting, introduced
  with the `:core:agent` contracts (it does not exist in the codebase yet).
- Legacy code is migrated opportunistically when touched, never by sweep:
  main sources currently contain ≈480 `runCatching` call sites.

## Test and CI policy

Required PR checks on `main`, all four must be green to merge:

1. **test** — Gradle unit suites (Build workflow `test` job).
2. **apk** — debug + release assembly (Build workflow `apk` job).
3. **analyze** — CodeQL, no new alerts.
4. **smoke** — emulator smoke (`connectedDebugAndroidTest`), dispatched on
   the PR branch; its test-report artifacts are part of the receipt.

Tests are written before the fix/behavior they cover (red → green), and CI
is the authoritative gate — a green local harness is evidence, not proof.

## Transition — strangler, no big-bang

- The new loop replaces the old **incrementally** (strangler pattern). No
  big-bang rewrite of engines, UI, or runtimes.
- A feature flag selects the engine: **`agentEngine = legacy | native`**,
  with **legacy the default** until the parity checklist passes **on a
  real device**. (Today's settings selector, the `AgentEngine` enum
  `NATIVE | PI`, is the precursor of this flag.)
- **Node/preview is KEPT in v1** — it is a headline product feature
  (owner decision, 2026-11-13). Nothing in this transition removes it.
- **The Pi engine stays as the fallback until parity** (owner decision,
  2026-11-13): the bundled Pi runtime
  (`core/android/src/main/assets/pi-runtime.zip`, driven by `PiEngine` /
  `PiChatBridge` in `:core:android`) keeps serving production users, and
  the M0/M-1 hardening on that path (shared safety primitives, key out of
  the process environment) protects them meanwhile.
- Retirement order, each step gated on parity evidence: harness becomes
  debug-only → `DirectChatScreen` folds into `ChatScreen` as a mode →
  native loop becomes default → Pi fallback and the JS harness retire.
