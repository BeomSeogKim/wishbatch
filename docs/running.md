# Running locally

The current application is a skeleton: it starts an HTTP server, but product
features are not yet implemented. It uses the PayPal sandbox only; no real
payments are made.

## Gradle and the in-memory database

Install JDK 21, then run from the repository root:

```sh
unset DATABASE_URL DB_USERNAME DB_PASSWORD
./gradlew bootRun
```

The server listens on port 8200 by default. In another terminal, run
`curl -i http://localhost:8200/`. The skeleton responds with HTTP 404 because
it has no home page yet. Stop it with Ctrl+C. Data in the in-memory database
is lost when the process stops. Set `SERVER_PORT` before starting to use a
different port.

## Docker Compose and PostgreSQL

Install Docker with Compose and start its engine, then run from the repository
root:

```sh
docker compose up --build -d
curl --retry 30 --retry-all-errors --retry-delay 1 -i http://localhost:8200/
```

The first build downloads the base images and Gradle dependencies. The server
returns HTTP 404 until a home page is implemented. Set `SERVER_PORT` before
starting to change both the host and container port. Compose waits for the
PostgreSQL health check before starting the application; use
`docker compose logs app` to inspect its startup. The database credentials in
Compose are local-only development credentials for a disposable database.

```sh
docker compose stop
docker compose start
docker compose down
```

The named PostgreSQL volume survives these commands and later starts. To remove
that data deliberately, run `docker compose down --volumes` instead.

## Tests

With JDK 21 installed, run:

```sh
./gradlew test
```

The tests use an isolated in-memory database and need no credentials or running
services. Gradle reports `BUILD SUCCESSFUL` when they pass. To run the full
repository checks as well, use `tools/verify.sh`.

## Optional service configuration

For payment integration, supply `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`
for a PayPal sandbox REST application. Create a sandbox app in the PayPal
Developer Dashboard and use its sandbox credentials. `PAYPAL_WEBHOOK_ID` is
needed for webhook signature verification when webhooks are configured. For
analysis, supply `LLM_API_KEY`, `LLM_MODEL`, and `LLM_DAILY_RUN_CAP`. Export
these variables in the shell before starting Gradle or Compose; Compose passes
them through without defaults. Never commit their values.

The current skeleton reads only the port and database settings. Payment and
analysis configuration will be used when those integrations are implemented.
See [the configuration reference](architecture.md#configuration) and
[the key list](../.env.example) for the remaining settings.
