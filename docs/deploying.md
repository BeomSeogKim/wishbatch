# Deploying on Render

The root [Blueprint](../render.yaml) creates one free Docker web service and
one free Render Postgres database. Both resources must be in the same region.
The service uses the PayPal sandbox only.

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

## Check the deployment

In the Dashboard, wait for the database and web service to become available
and inspect the web service's build and deploy logs for errors. Open the
service's `onrender.com` URL and confirm the home page returns successfully;
the first request after inactivity may need about a minute. Check the service's
health and memory graphs. Create a demo workspace and confirm it remains
available after a service restart to check PostgreSQL persistence. Use only
PayPal sandbox accounts when exercising payments.
