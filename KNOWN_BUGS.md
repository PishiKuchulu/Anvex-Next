# Known bugs / tracked residuals

Residuals that are **known, assessed, and deliberately not fixed yet**. Each entry
states the exact vector, why it is not being chased now, and what the planned
remedy is. Logging an item here is a decision, not an oversight — do not
whack-a-mole these inside unrelated changes.

## KB-001 — Nested brace expansion can evade `globCouldMatchSecret` (M0 residual)

- **Where:** `SensitivePaths.braceAlternatives` (core:engine), added in M0
  (landed 2026-11-13, tag `m0-landed`, merge `30641f5`).
- **Vector:** `braceAlternatives` expands only the first `{a,b}` group
  (recursively for later groups). For a token with a **nested** group such as
  `{x,{*.env,*.key}}`, parsing degrades to no-match, so
  `globCouldMatchSecret` returns false. On a shell that performs nested brace
  expansion (real bash — which Pi resolves whenever `/bin/bash` or a PATH bash
  exists; see the M0 shell determination), `cat {x,{*.env,*.key}}` can expand
  to secret paths and evade the glob gate.
- **Why not fixed now (owner decision, 2026-11-13):** M0 is a speed-bump
  denylist, not a sandbox; chasing glob-parser completeness is the
  whack-a-mole trap. The class-level remedies are M-1 (remove the provider API
  key from the Pi process environment) and the native-allowlist boundary in
  the new architecture. This residual must NOT ride along inside M-1 or any
  other unrelated change.
- **Planned remedy (if/when scheduled as its own concern, "M0.1"):** a
  simpler, stronger rule — hard-deny any token that contains **both** a brace
  and a glob metacharacter. That covers nesting by construction and does not
  break legit braces (`{"a":1}` JSON, `{}` in `find -exec`), which contain no
  glob metacharacters.
- **Status:** OPEN — logged, not being chased.
