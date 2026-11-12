# Cold start and keep-warm

## Keep-warm behavior

The [Keep warm workflow](../.github/workflows/keep-warm.yml) requests `/` at
minutes 7, 17, 27, 37, 47, and 57 of each hour (UTC). It also supports manual
dispatch after the workflow reaches the default branch. Each request has a
10-second connection timeout and a 300-second total timeout; the job has a
six-minute timeout. HTTP errors fail the step. There are no retries or external
ping services. The token retains only `contents: read` permission.

GitHub [scheduled workflow rules](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)
require the workflow to exist on the default branch. Schedules can be delayed
or dropped under load; public repositories can have scheduled workflows
disabled after 60 days without activity. Off-hour scheduling reduces one common
source of contention but cannot guarantee execution.

When investigating missing runs, check the repository's default branch, its
workflow file, whether Actions and the workflow are enabled, and the workflow's
run history. An active workflow and a valid cron expression do not establish
that scheduled requests have run. The repository configuration does not explain
every missing run, and manual dispatch does not repair the scheduler.

Keep-warm is best effort. It cannot guarantee uptime, preserve an in-memory
database across restarts, or override hosting quotas, suspension, or database
expiry. See [deployment limits](deploying.md#free-plan-limits). A request to the
landing page does not verify database persistence or payment readiness.

## Startup safety

Application beans retain eager initialization. The PayPal URL guard must reject
every URL except `https://api-m.sandbox.paypal.com` during startup, including
when both credentials are absent. The
[startup tests](../src/test/kotlin/app/startup/StartupTests.kt) exercise the full
application configuration with isolated in-memory databases, no server port,
and explicit empty provider credentials. They check startup itself before
requesting any bean; a successful context must already contain the guard.

The landing page renders without creating a workspace or calling a payment or
model provider. Rendering it is included in cold-start measurements so work
deferred until the first template render remains visible.

## Container startup settings

The runtime keeps the 256 MiB heap and 128 MiB metaspace limits and adds
`-XX:TieredStopAtLevel=1`. Limiting compilation to C1 reduces startup compiler
work; it also prevents C2 optimization for sustained workloads. See the
[JVM compilation explanation](https://docs.aws.amazon.com/lambda/latest/dg/java-customization.html#java-tool-options).
This is a startup tradeoff for the small demo instance, not a measured
throughput improvement. Override `JAVA_TOOL_OPTIONS` to remove the option when
benchmarking a larger instance or sustained traffic. Preserve the memory limits
when doing so.

### Measurement method and limits

Container measurements use Linux ARM64, Temurin 21.0.13, a 0.1 CPU quota, and
512 MiB memory with no extra swap. Each fresh container has its own in-memory
H2 database, `SERVER_PORT=0`, no volumes, empty provider credentials, seed-run
generation disabled, and `--network none`. The first landing-page request runs
through loopback inside the container; no host port is published. External
payment and model services are unreachable.

The comparison uses the same executable JAR and image, overriding only
`JAVA_TOOL_OPTIONS` between the original memory flags and those flags plus
`-XX:TieredStopAtLevel=1`. Timing starts before `docker run`, polls startup logs
once per second, then fetches the full first page and checks its demo entry and
sandbox notice. It includes container creation, JVM startup, polling overhead,
and the first template render. Spring's own startup duration excludes some of
that work. Reported memory is a post-response sample, not peak memory.

### Local result (2026-11-12)

Both settings used source commit
`24423a565b044a4e6f4d7c4eb7e559a61782a915`, verified from the comparison
build's recorded VCS revision, and the Dockerfile at that commit. The measured
executable JAR had SHA-256
`c98feedf256b5ba93ff7833327ac4d6eff2143beb3d883b11576535eafee1944`.
Later application features are outside this measurement baseline.

| Runtime setting | Spring startup | Container creation to full first page | First request | Memory after response |
|---|---:|---:|---:|---:|
| Original memory flags | 241.302 s | 278.388 s | 17.006 s | 283.8 MiB |
| Memory flags plus C1 limit | 151.604 s | 172.557 s | 8.746 s | 259.9 MiB |

The completed samples ran sequentially, C1 first, then the original flags. The
full first-page duration decreased by 105.831 seconds (38.0%) in this comparison.
An earlier original-settings attempt did not finish startup within a 300-second
cutoff and was stopped without a page response. The retry allowed 600 seconds.
The rebuilt image's JAR matched the comparison image's JAR by SHA-256.

These local measurements exclude image pulls, builds, host provisioning,
PostgreSQL connection latency, proxy behavior, and provider calls. They do not
measure Render wake-up time, persistent storage, or sustained throughput, and
cannot establish hosted availability. A single completed sample per setting
does not characterize latency variance.
