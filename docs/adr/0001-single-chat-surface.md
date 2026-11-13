# ADR-0001: One chat surface — `ChatScreen`, with DirectChat as a mode

## Status

Accepted — owner decision, 2026-11-13 (stage A1, Anvex-Next).

## Context

The app currently ships **two** Compose chat surfaces:

- `ChatScreen` (`app/src/main/kotlin/com/anvex/app/ui/screens/chat/
  ChatScreen.kt`) — the project chat, driven by `ChatViewModel`.
- `DirectChatScreen` (`app/src/main/kotlin/com/anvex/app/ui/screens/chat/
  DirectChatScreen.kt`) — a project-less conversational chat, driven by
  `DirectChatViewModel`.

Both already run on the **same** session class: `ChatSession`
(`core/android/.../session/ChatSession.kt`), which takes a `directChat`
mode flag, and both render from the same `ChatSession.UiState`. The split
therefore buys no behavioral difference, but every chat feature,
approval-dialog fix, and rendering improvement must be implemented,
reviewed, and device-tested twice — and the two surfaces have repeatedly
drifted apart (the audit history shows fixes landing on one surface and
being re-discovered on the other).

A third surface exists for development: the JavaScript WebView harness
(`HarnessScreen` in `:app`, `HarnessBridge` in `:core:android`, web app in
`app/src/main/assets/harness/`). It is a debugging tool, not a product
surface, but today nothing marks it as such.

## Decision

- **One chat surface:** Compose `ChatScreen` is the only chat UI.
- **DirectChat becomes a mode** of that surface (the `directChat` session
  mode already exists), not a separate screen with its own ViewModel.
- **The JS harness is debug-only until the native agent loop reaches
  parity, and is then retired.** It must not gain product features.
- New chat UI state goes into the single `ChatSession.UiState` (or its
  successor behind the `AgentEngine` facade) — never into a state class
  owned by an individual screen.

## Consequences

- One implementation of streaming rendering, approval UI, artifact cards,
  and error display to maintain and to parity-test.
- `DirectChatScreen`/`DirectChatViewModel` are folded into
  `ChatScreen`/its ViewModel during the transition (strangler steps, each
  behind the parity checklist — see `ARCHITECTURE.md`, Transition). They
  are **not** deleted in this stage; this ADR records the direction only.
- The harness's retirement is gated on native-loop parity evidence, so no
  debugging capability is lost before its replacement exists.
- Any future proposal for a second chat surface must supersede this ADR
  explicitly.
