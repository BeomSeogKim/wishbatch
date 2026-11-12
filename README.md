# Wishbatch

Wishbatch helps a maker with an audience decide what to produce. Fans submit
free-text wishes, the application groups compatible wishes into one producible
offer, and fans commit to that exact offer through a PayPal payment
authorization. The batch is made only when enough payments complete.

It is a prototype for the PayPal AI hackathon and uses the PayPal sandbox only.
No real money moves.

## Status

Repository foundation and an empty application skeleton. No product feature
works yet, and there is no hosted build.

| Area | State |
|---|---|
| Repository guards, hooks, and contribution rules | In place |
| Spring Boot application skeleton | Builds and starts; contains no feature |
| Wish analysis, offers, pledges, settlement | Not started |

## Getting started

Requirements: Git, Bash, Perl, Python 3, and JDK 21.

```sh
git config core.hooksPath .githooks
tools/verify.sh
```

`tools/verify.sh` runs the repository guards, their tests, and the application
build and tests. None of them needs credentials, network services, or a
database.

To start the application locally:

```sh
./gradlew bootRun
```

It listens on port 8200 unless `SERVER_PORT` is set, and uses an in-memory
database unless `DATABASE_URL` is set. [.env.example](.env.example) lists the
configuration keys; keep their values in a secret store rather than a tracked
file.

## Development

- [Architecture](docs/architecture.md): modules, their boundaries, and
  configuration.
- [Documentation index](docs/README.md): contribution policies by task.
- Parallel work uses one branch and one worktree per task; see the
  [Git workflow](docs/contributing/git-workflow.md#parallel-work).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md). Automated contributors also follow
[AGENTS.md](AGENTS.md).

## License

[MIT](LICENSE).
