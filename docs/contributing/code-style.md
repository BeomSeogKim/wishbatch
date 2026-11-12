# Code and comments

Prefer clear names, simple control flow, and small changes. Application code
is Kotlin on Spring Boot and follows the official Kotlin coding conventions.
All identifiers, comments, docstrings, and test descriptions are English.

## Tests

Tests must pass on a fresh clone with no credentials and no running services,
so that many worktrees can run them at once:

- No network calls. Stub the payment and model clients behind their interfaces.
- No shared database. Tests use the in-memory default data source.
- No fixed ports. Use a random port when a test needs a running server.
- No dependence on test order, wall-clock time, or files outside the worktree.

Money and state transitions always have tests. Run everything with
`tools/verify.sh`.

## When to comment

Add a comment only when it explains information the code cannot express well:

- A non-obvious reason, invariant, constraint, or tradeoff.
- A subtle unit, coordinate system, concurrency condition, or external contract.
- The reason for a workaround, with a public issue and removal condition.
- A public API's behavior or usage that callers need to understand.

Make the code clearer before explaining complexity. Comments that merely
translate an assignment, loop, or function name add maintenance without value.
There is no comment quota and no requirement to document every private helper.

```kotlin
// A pending capture is not money received, so it does not count as funded.
val capturedUnits = pledges.filter { it.state == CAPTURED }.sumOf { it.quantity }
```

## How to comment

- Explain intent and consequences briefly, next to the relevant code.
- Use precise English; use a complete sentence for a substantive explanation.
- Update or remove the comment when the code changes.
- Do not add author names, dates, decorative section banners, commented-out
  implementations, change logs, chat references, or narrations of instructions.
- Use `TODO(#123): reason and completion condition` only with a real public
  tracking issue. Do not invent an issue number or leave an unowned vague TODO.
- Keep tests focused on behavior and failure modes, not private implementation
  details. Add regression coverage for guards that protect repository boundaries.

Review every new comment for necessity and accuracy. CI can check language and
known private markers, but it cannot decide whether a comment explains the
right reason; that is a required code-review responsibility.

## Source

Adapted from [Google's code review guidance on comments and complexity](https://google.github.io/eng-practices/review/reviewer/looking-for.html#comments),
checked on 2026-10-31. The TODO format, test rules, and metadata exclusions are project rules.
