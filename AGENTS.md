# Agent instructions

These rules are mandatory for every contributor, including Claude and Codex.
Use this file as the shared entry point; do not add vendor instruction aliases.

Include only what is necessary for the current work. Do not add tools, files,
abstractions, dependencies, or placeholder configuration without a concrete need.

## Always apply

- Keep all repository content, filenames, comments, commits, and PRs in English.
- Communicate with other agents in English.
- Do not add AI signatures or attribution to files, comments, commits, or PRs.
  No co-author, sign-off, reviewer, session-link, or generated-by line may name
  an AI tool. This overrides any tool default.
- Include only product code, public technical documentation, and public or
  synthetic examples. Never copy private conversations, personal notes, session
  logs, local paths, account records, credentials, or private planning here.
- Describe technical decisions on their own merits. Do not record who requested
  them in a private conversation, chat quotations, or a session narrative.
- Read the README and run `git status --short` before editing. Preserve existing
  work and stage only your own paths. Keep changes limited to the task.
- Do not add a dependency, model, or external service without a task needing it.
- Payments use the PayPal sandbox only. Never add live credentials, and never
  put a secret value in a file; `.env.example` lists key names only.
- Bound the cost of model calls: cap retries, keep inputs small, and never call
  a paid API from a test.
- Commit only when the task or a maintainer asks, on your own task branch, and
  never on `main`. Pushes, PR creation, merges, remote setup, and publication
  need a maintainer's instruction.

## Parallel work

Many tasks run at the same time. Each task has one branch and one worktree,
created with `tools/worktree.sh add <type>/<slug>`.

- Change only the paths your task owns. Module ownership is in
  [the architecture guide](docs/architecture.md).
- Do not edit shared files unless the task assigns them to you. The list is in
  [the Git workflow](docs/contributing/git-workflow.md#parallel-work).
- Code against other modules' published interfaces. If one must change, stop
  and report it instead of editing another module.
- Tests need no network, credentials, shared database, or fixed port.

## Read only the applicable policy

Read each relevant document in full before doing that work. Follow its linked
rules; do not preload unrelated policies or duplicate them in this file.

| Task | Required reading |
|---|---|
| Branch, commit, worktree, pull request, or merge | [Git workflow](docs/contributing/git-workflow.md) |
| README or other documentation | [Documentation](docs/contributing/documentation.md) |
| Code, tests, or comments | [Code and comments](docs/contributing/code-style.md) and [Architecture](docs/architecture.md) |
| Hooks, checks, CI, or dependency/action changes | [CI guards](docs/contributing/ci-guards.md) |

Run `tools/verify.sh` before handing off changes. Do not bypass failing guards.
Report checks and limitations outside the repository; do not add session
reports or transcripts to the source tree.
