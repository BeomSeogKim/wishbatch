# Startup schema upgrades

H2 and PostgreSQL databases upgrade automatically on startup. No manual SQL
step or migration dependency is required. Stop older application instances
before deploying an upgrade; concurrent mixed-version startup is not supported.

## Initialization order

1. Spring creates the configured `DataSource`.
2. The synchronous `schemaUpgrade` bean inspects the current schema and widens
   existing pledge-state enums before JPA can use them. It also installs or
   backfills the nonnull wish duplicate counter.
3. `EntityManagerFactoryDependsOnPostProcessor` makes every auto-configured
   entity-manager factory depend on that bean. An upgrade failure prevents JPA
   initialization and application readiness.
4. Hibernate `update` creates new mapped tables and adds mapped columns while
   building the entity-manager factory. Schema errors halt startup.
5. Application services become ready. The webhook inbox retains its existing
   initialization only when webhook configuration enables it.

The application defaults to `spring.jpa.hibernate.ddl-auto=update` on both
databases. Explicit settings such as `create-drop`, `validate`, or `none` still
override that default. `none` and `validate` do not install new mapped tables or
columns, so they are unsuitable when deploying an additive schema change.
The compatibility upgrade still precedes JPA for these settings.

## Compatibility changes

- `pledges.state` accepts the previous eleven states plus `PARTIALLY_REFUNDED`.
  H2 native enums retain their labels and append the new label. H2 string
  columns with recognized enum checks become native enums with the expanded
  roster; only those enum checks are removed. This also avoids H2 constant-set
  checks retaining a closed initialization connection across context restarts.
  PostgreSQL string enum checks are replaced atomically with expanded checks;
  native enum types receive an additive label committed before JPA starts.
- `wishes.duplicate_count` is `INTEGER NOT NULL DEFAULT 0`. Existing nulls
  become zero; existing nonnull counts and original wish text are preserved.
  This counts additional imported occurrences, never fans or payment units.
- `pledges.wish_id` is a nullable UUID. Existing pledges remain unlinked;
  neither offer evidence nor browser history is used to backfill a wish.

The compatibility step runs on every startup and is idempotent. It inspects
only the current connection schema and does not drop tables or rows. Unknown
pledge labels, business checks involving pledge state, unsupported column types,
and negative duplicate counts fail explicitly instead of weakening constraints.
Unrelated constraints, including pledge quantity checks, remain in place.

## Schema inventory

Relative to the earlier sandbox schema:

| Feature | Additive schema | Existing-data treatment |
|---|---|---|
| Partial refund | Expanded `pledges.state` enum/check | Preserve state, quantity, and amount |
| Duplicate import | `wishes.duplicate_count` | Backfill zero |
| Decision provenance | `decision_source_snapshots`, `pledge_decision_records`, `decision_record_entries` | New empty tables; unavailable historical evidence stays unavailable |
| Pledge wish trace | Nullable `pledges.wish_id` | Preserve null for existing pledges |
| Maker review | `wish_review_overrides` | New empty table |
| Follow-up questions | `followup_draft_attempts`, `followup_questions`, `followup_answers`; nullable `wish_review_overrides.answer_id` | New tables empty; existing review answer references remain null |
| Webhook inbox | `paypal_webhook_events` | Existing enabled-configuration initializer creates it if absent |
| Books check | None; uses existing `campaign_events` fields | No backfill |
| Wish workbench and provenance hooks | None | No backfill |

`analysis_run_quotas` predates these changes. Analysis JSON additions are
backward-compatible application data, not new database columns. Follow-up
review source `FAN_ANSWER` uses the existing string column, not a database enum.

## Verification

`ExistingSchemaUpgradeTests` starts the actual application on disposable legacy
H2 schemas with native enums and string enum checks, observes compatibility
before entity-manager factory initialization, and restarts the application on
the same data. It checks state writes, quantity and amount preservation,
duplicate backfill, provenance table creation, and unrelated constraints.
Invalid schemas must fail startup with an `Unsupported schema` cause.

`PostgresSchemaUpgradeTests` repeats both layouts on a temporary local
PostgreSQL cluster when `initdb` and `pg_ctl` are installed and runnable as a
non-root user. It uses its own data directory and a dynamically allocated
loopback port, stops the cluster afterward, and skips when the binaries are
unavailable. No test uses configured deployment databases, provider APIs,
credentials, shared data, or fixed ports.
