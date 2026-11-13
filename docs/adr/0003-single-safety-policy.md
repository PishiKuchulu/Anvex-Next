# ADR-0003: One safety policy in `:core:engine` for every execution path

## Status

Accepted — owner decision, 2026-11-13 (stage A1, Anvex-Next). This ADR
formalizes the arrangement already landed by stages M0/M-1; it changes no
code.

## Context

Anvex executes model-proposed actions through several paths: the native
dispatcher (`ActionDispatcher` in `:core:engine`), the shell/terminal
runtimes and `ChatSession` in `:core:android`, and the Pi engine's tool
mapping (`PiToolMapping`). Historically each path grew its own checks,
and the gaps between them were exploitable in principle: a read that was
denied in one path could be phrased (glob, recursive flag, session-written
script) to slip through another.

Stage M0 closed the concrete gaps by extracting **shared primitives** in
`:core:engine` and making every path consume them. The M0 work is a
denylist-based speed bump, not a sandbox — but its real durability comes
from there being exactly one implementation to fix.

## Decision

- The single safety policy lives in **`:core:engine`**:
  - `SensitivePaths` (`core/engine/src/main/kotlin/com/anvex/core/engine/
    SensitivePaths.kt`) — secret-path knowledge, including the shared
    primitives `globCouldMatchSecret` and `isRecursiveRead` (M0).
  - `ActionSafetyPolicy` (same directory) — the assess/decide gate built
    on those primitives (allow / require-approval / deny).
- **Every execution path must consume this policy**: the native
  dispatcher, shell and terminal runtimes, the Pi tool mapping
  (`PiToolMapping.bashDenied` and its approval forcing), and the future
  `:core:agent` loop (ADR-0002) — whose `bash` tool is approval-gated
  through this policy.
- No module may introduce a parallel or partial safety policy. A check
  that is needed in two places is added **once**, to the `:core:engine`
  primitives, with tests at both consuming layers (the M0 pattern).
- Changes to the policy require tests demonstrating the previous bypass
  (red) and the fix (green), as in M0.

## Consequences

- One implementation to audit, fuzz, and fix; a bypass closed once is
  closed on every path.
- The policy stays pure-JVM and Android-free, so it is unit-testable
  without the Android runtime and reusable by `:core:agent` unchanged.
- Known residual (accepted, logged in `KNOWN_BUGS.md` as KB-001): nested
  brace groups can degrade the glob matcher to no-match; the policy is a
  speed bump layered with approval gating, not a sandbox boundary. The
  durable direction (native allowlist) is unchanged by this ADR.
