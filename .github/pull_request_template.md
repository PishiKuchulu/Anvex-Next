## Scope statement

<!-- ONE concern per PR. State it in one sentence, and list the paths the
     diff is allowed to touch. Anything outside those paths needs its own PR. -->

## Evidence checklist

- [ ] Tests written before the change (red → green) where code changed —
      failing-then-passing test names:
- [ ] CI receipts: workflow run ids + conclusions (test / apk / analyze /
      smoke as applicable):
- [ ] Binary-integrity receipt (only if assets or zips were touched):
      pre-upload SHA-256, post-upload SHA-256 re-fetched from the branch,
      and tree-parity result:

## Size

- Added lines:
- Deleted lines:

## What was deleted / consolidated

<!-- Name anything removed or merged (code, files, duplicated logic) — or
     explicitly "nothing deleted". -->

## Open questions / follow-ups

<!-- Known gaps, deferred items (with their tracking id, e.g. KNOWN_BUGS),
     and anything the reviewer must decide. -->
