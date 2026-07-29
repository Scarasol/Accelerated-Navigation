# Accelerated Navigation Agent Rules

These rules apply to the whole repository. They define how work is performed, not the
architecture that a future change must implement.

## Sources Of Truth

1. Current production source and runtime entry points describe what the product does now.
2. A user-approved active specification describes the intended change.
3. Accepted ADRs describe durable decisions and their rationale.
4. Tests, benchmarks, and reports are evidence. They do not define production reachability or
   justify an otherwise unused representation.
5. Plans, handoffs, recovered conversations, and superseded designs are context only unless the
   user explicitly promotes them to the active specification.

When sources disagree, inspect the production source and surface the conflict. Do not silently
force code to match a stale document.

## Before A Substantial Change

- Run the user-invoked `grill-me` skill for a new feature, architecture change, or broad rewrite.
- Discover facts from the repository, Minecraft sources, and primary references instead of asking
  the user factual questions that can be answered directly.
- Put each material design decision to the user one at a time with a recommended answer.
- Do not implement the proposal until the user confirms that shared understanding has been reached.
- Record the approved target in a dedicated specification. Record only durable, accepted
  architectural decisions as ADRs.

## Implementation Discipline

- Start from a real Forge, Mixin, Navigation, or public integration entry point and preserve a
  production-reachable vertical slice.
- Keep one canonical representation for each fact. Every derived representation must name its
  production consumer, identity, invalidators, lifetime, and bounded cost.
- Prefer a deep module with a small interface over pass-through coordinators and speculative seams.
  One implementation does not justify an abstraction intended for hypothetical alternatives.
- When replacing behavior, remove superseded production paths, state, metrics, codecs, and tests.
  Do not retain obsolete production code solely for compatibility with old tests.
- Keep changes within the approved specification. New infrastructure, caches, queues, workers, or
  compatibility layers require an explicit demonstrated need.
- Do not describe a feature as implemented until a production caller can reach it.

## White-Box Review

- Review the complete affected production source, not only the diff and not only test behavior.
- Trace runtime entry points, callers, canonical and derived data, retained state, publication,
  invalidation, failure, shutdown, replacement, loop bounds, allocations, and resource limits.
- Search production and test source for remaining consumers of replaced APIs and representations.
- Compilation and tests are useful feedback but cannot override an unresolved structural finding.
- Follow the validation policy agreed in the active specification or current user instruction.

## Documentation

- Keep current architecture descriptions separate from proposed changes.
- Keep unapproved or rolled-back plans clearly marked as historical proposals.
- Keep performance reports bound to the exact source that produced them.
- Avoid layered override sections. Rewrite or archive stale material when a decision changes.
