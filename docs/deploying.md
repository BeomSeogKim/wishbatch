# Deploying on Render

The root [Blueprint](../render.yaml) creates one free Docker web service and
one free Render Postgres database. Both resources must be in the same region.
The service uses the PayPal sandbox only.

The hosted build is <https://wishbatch.onrender.com/>. Visitors can browse
without credentials and follow the [testing guide](https://wishbatch.onrender.com/guide);
the payment step requires a PayPal sandbox personal account. See the
[manual hosted verification and its limits](../README.md#evidence-and-limits).

## Create the Blueprint

1. Make the desired commit available on a Git provider that Render can access.
2. In the [Render Dashboard](https://dashboard.render.com/), choose **New >
   Blueprint**, connect the provider if needed, and connect the repository.
3. Name the Blueprint and select the branch containing `render.yaml`. Leave
   the Blueprint path at its root-file default.
4. Enter these prompted values from the appropriate credential providers:
   - `PAYPAL_CLIENT_ID`: the client ID of a PayPal sandbox REST app.
   - `PAYPAL_CLIENT_SECRET`: its sandbox secret.
   - `LLM_API_KEY`: the model provider API key.
5. Review the proposed resources and confirm that both plans are **Free** and
   their regions match. Choose **Deploy Blueprint**.

Render supplies the database connection and credentials, the service's public
`APP_BASE_URL`, and the sandbox `PAYPAL_BASE_URL`. Do not enter a PostgreSQL
URL or credentials manually. The container converts Render's PostgreSQL URL
to a JDBC URL without its embedded credentials before starting Java; the
separate username and password variables are used for authentication. Render
prompts for the three secret values only when the Blueprint is first created;
add later secrets in the service's Environment page. See the [Blueprint
setup](https://render.com/docs/infrastructure-as-code) and [environment variable
reference](https://render.com/docs/blueprint-spec#setting-environment-variables).
The Blueprint enables Hibernate schema updates for this disposable demo
database; it does not provide versioned migrations or production schema safety.

The service binds to Render's `PORT`; local runs continue to use `SERVER_PORT`.
The JVM has a 256 MB maximum heap and a 128 MB maximum metaspace within the
[512 MB free instance](https://render.com/docs/compute-plans). These limits do
not cap all native memory. Render's HTTP health check uses `/`, whose handler
renders the home page without accessing the database or an external service.
See [Docker on Render](https://render.com/docs/docker) and [health
checks](https://render.com/docs/health-checks).

## Temporary database on startup

When a configured PostgreSQL database is unreachable over the network at
startup, the application uses a uniquely named, temporary in-memory H2 database
and displays a notice. The startup connection probe has a five-second login
timeout. Healthy or already running PostgreSQL and the default H2 configuration
remain unchanged. Authentication, TLS, and schema errors still fail startup.

Temporary data is lost on restart. There is no runtime failover, data copy or
merge, or automatic migration back to PostgreSQL.

## Free plan limits

An idle free web service spins down after 15 minutes without inbound traffic.
The next request starts it again, taking about one minute. The workspace has
750 free instance hours per month; services suspend if those hours run out.
Local files are ephemeral. Build pipeline minutes and outbound bandwidth count
against workspace allowances and may incur charges depending on the workspace.

Only one free PostgreSQL database can be active per workspace. It has 1 GB of
storage and expires 30 days after creation, becoming inaccessible. After a
14-day grace period, Render deletes it and its data unless it is upgraded. Free
Postgres has no backups. See [Deploy for Free](https://render.com/docs/free)
for the current limits and details.

## Keep-warm workflow

Optionally set `KEEP_WARM_URL` to a trusted operator-controlled URL to have
the running application request it every ten minutes, starting ten minutes
after startup. It is disabled when unset or blank. Each request has a
3-second connection timeout and a 5-second request timeout; one result is
logged without the URL. Failures are not retried. This cannot wake an
instance that has already spun down, guarantee uptime, or prevent free-hour
usage. Configure the hosted URL in the deployment environment.

The [Keep warm workflow](../.github/workflows/keep-warm.yml) requests the hosted
landing page every ten minutes, at minutes 7, 17, 27, 37, 47, and 57 UTC, to
keep the free instance awake during judging. Its single `curl` step has a
90-second request limit; the job times out after two minutes and overlapping
runs are cancelled. It needs no secrets, checkout, or third-party action and
does not call payment or analysis endpoints. Running continuously consumes
Render's free instance hours.

GitHub schedules run only when the workflow exists on the default branch,
using that branch's latest commit. The minimum supported interval is five
minutes. Runs can be delayed or dropped under load, especially at the start
of an hour, so this is not an uptime guarantee and a visitor may still see a
cold start. Public repository schedules are disabled after 60 days without
repository activity. See [GitHub's schedule documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).

After judging, turn it off in the repository's **Actions > Keep warm > ... >
Disable workflow**, or remove the workflow file from the default branch.

## Check the deployment

In the Dashboard, wait for the database and web service to become available
and inspect the web service's build and deploy logs for errors. Open the
service's `onrender.com` URL and confirm the home page returns successfully;
the first request after inactivity may need about a minute. Check the service's
health and memory graphs. Create a demo workspace and confirm it remains
available after a service restart to check PostgreSQL persistence. Use only
PayPal sandbox accounts when exercising payments.
