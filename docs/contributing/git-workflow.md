# Git workflow

## Commits

Use Conventional Commits: `<type>[optional scope][!]: <description>`. Put a
scope in parentheses, such as `fix(scene)`. Types are
`feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`,
and `revert`. Mark incompatible changes with `!` or a `BREAKING CHANGE` footer.

This project adds these local rules to the specification:

- Write in English. Use an imperative description, without a trailing period.
- Limit the header to 72 characters. Leave the second line blank.
- Include a body explaining the technical problem and reason for the change.
  Wrap prose at 72 columns where practical; do not restate the diff.
- Keep one complete purpose per commit. Do not mix unrelated cleanup.
- Exclude conversation quotations, personal requests, session history, private
  links, and AI attribution trailers. A public issue reference is appropriate.
- Do not credit Claude, Codex, or other AI tools through co-author, sign-off,
  reviewer, tool-name, or generated-by signatures. The same rule applies to
  repository files, comments, and PR descriptions. Guards reject these entries
  rather than silently removing them; human contributor attribution is allowed.

```text
fix(settlement): count only completed captures

A pending capture is not money received. Count a pledge toward the
funded threshold only after the payment provider reports completion.
```

The upstream specification permits an optional body and additional types;
this project's required body, type list, and length limit are project choices.

## Branches

Follow GitHub Flow: branch from current `main`, keep changes focused, open a PR,
review and validate it, merge, then remove the finished branch.

Use `<type>/<short-kebab-description>`, for example `feat/pledge-authorization`
or `fix/capture-retry`. The slug contains lowercase ASCII letters, digits, and single
hyphens. Do not include personal names or conversational task descriptions.
Do not commit directly to `main` or `master`, or from a detached HEAD. There is
no permanent `develop` branch. Do not rewrite someone else's branch or work.

Initial development uses `chore/repository-setup`. Shared base-branch and remote
bootstrap remain separate actions. Do not invent a commit on `main` to work
around that pending setup.

## Parallel work

Several contributors, human or automated, may work at the same time. These
rules keep their changes independent and mergeable.

- One task, one branch, one worktree. Create it with
  `tools/worktree.sh add <type>/<slug>`; it lives under `.worktrees/`, which
  is ignored. Never work on two tasks in one worktree.
- Change only the paths the task owns. The owning module for each area is in
  [the architecture guide](../architecture.md).
- Shared files have a single owner at a time: `build.gradle.kts`,
  `settings.gradle.kts`, `src/main/resources/application.yml`, the database
  schema, files under `.github/`, `.githooks/` and `tools/`, and the top-level
  documents. A task changes them only when it is assigned to.
- Code against the published interfaces of other modules. If an interface
  must change, stop and coordinate before editing another module.
- Run `tools/verify.sh` in the worktree before every commit.
- Before integration, rebase the task branch onto current `main` and run
  `tools/verify.sh` again. Integrate by fast-forward, or by a squash-merged
  pull request once a remote exists. Do not create merge commits.
- Remove the worktree after integration with
  `tools/worktree.sh remove <type>/<slug>`.

## Pull requests

- Keep one purpose per PR; use a draft while work is incomplete.
- Use a Conventional Commit title; it becomes the squash commit header.
- Fill every required section in [the template](../../.github/pull_request_template.md):
  Summary, Why, Changes, Verification, and Checklist. Notes covers risks or limitations.
- Explain the final behavior and its technical motivation for a reader without
  the conversation. Include relevant tests, reproduction steps, and limitations.
- Link public issues where relevant; never depend on private context links.
- Read the diff yourself. Remove accidental files, private material, and stale
  comments. Check English, comment necessity, and actual validation evidence.
- Address review findings and require a green CI Guard before squash merging.
  Do not invent an independent approval when no qualified reviewer has reviewed.
- Complete all three review attestations with `[x]` only after actually checking
  privacy and English, comment quality, and the reported validation results.

Commits, pushes, PR creation, merges, and publication require the applicable
human instruction.

## Sources

Adapted and checked on 2026-10-31:

- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
- [GitHub Flow](https://docs.github.com/en/get-started/using-github/github-flow)
- [Google: writing good change descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html)
