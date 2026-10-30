# The 3 AM runbook

For the engineer who just got paged. Find your symptom, read the metric, follow the steps.

Metrics are at `/actuator/prometheus` and need an admin or operator token.

## Is it healthy?

```bash
API=http://localhost:8080
curl -s $API/actuator/health
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"userId":"admin-a","password":"demo-password"}' | jq -r .token)
curl -s $API/actuator/prometheus -H "Authorization: Bearer $TOKEN" | grep -E '^(trips_|provider_|segments_|invoice_|route_cache_)'
```

## Signals

| The question | Metric | Healthy | Worry when |
|---|---|---|---|
| Lag: how long has the oldest trip waited? | `trips_degraded_oldest_age_seconds` | 0 | It keeps rising |
| Queue depth | `trips_degraded_waiting` | 0 | It keeps rising |
| Provider state | `provider_breaker_open` | 0 | 1 for more than a few minutes |
| Provider errors | `provider_failures`, `provider_timeouts` | Flat | Rising quickly |
| Retries | `provider_retries`, `trip_update_retries_total` | Low | Rising with failures |
| Rejections | `segments_rejected_total` | Low | A sudden jump |
| Audit failures | `trips_failed_audit_total` | **0** | Any increase |
| API errors | `http_server_requests_seconds_count` with `outcome="SERVER_ERROR"` | Flat | Rising |
| Cache use | `route_cache_hits`, `route_cache_misses` | Hits growing | Misses dominate after a restart |

## Symptoms

### Trips are coming back DEGRADED

**Means:** the provider is down, timing out or refusing calls. Trips still return, and billing is blocked for them.

1. Check `provider_breaker_open`, `provider_failures` and `provider_timeouts`.
2. Check the provider's own status.
3. Do nothing else if it's an outage. Degraded trips are recomputed every 5 seconds once it returns.
4. Confirm `trips_degraded_waiting` falls to 0.

### The degraded queue isn't draining

**Means:** the provider is still failing, or the breaker is reopening.

1. Check whether `trips_degraded_oldest_age_seconds` keeps rising.
2. Read the logs for `recomputed degraded trips`.
3. Each pass handles up to 100 trips and stops at the first one still degraded. A large backlog takes several passes.

### Callers are getting 503

**Means:** the app can't reach Postgres. Requests fail in about 3 seconds, and it's safe to retry them.

1. Check the database: `docker compose ps db`.
2. Start it if needed: `docker compose start db`.
3. The app needs no restart.
4. Search the logs for `database unavailable`.

### Trips are NEEDS_REVIEW

**Means:** a route was rejected, the audit failed, or there were too few points.

1. Read the trip's `metersByRule` and look for `REJECTED_DISTANCE` or `REJECTED_SPEED`.
2. Check `trips_failed_audit_total`. Any audit failure means the engine and auditor disagree, which needs an engineer, not just a review.
3. After review, bill it:

```bash
curl -X POST "$API/api/trips/{id}/close?reviewed=true" -H "Authorization: Bearer $TOKEN"
```

A correction held on a closed trip is applied by that same call.

### Close returns 409

| Message | Cause | Action |
|---|---|---|
| "Trip uses estimated distances" | Trip is `DEGRADED` | Wait for recompute |
| "Trip needs review" | Trip is `NEEDS_REVIEW` | Review, then close with `reviewed=true` |

### Late pings return 409

**Means:** the trip changed 10 times while this update was retrying. Send it again. Replays are safe.

### The app crashed

1. Start it: `docker compose up -d app`.
2. Nothing is lost, because results and evidence are in Postgres.
3. Degraded trips resume on the next recompute pass.
4. The route cache starts empty, so provider calls rise for a while.

## Routine actions

| Task | Command |
|---|---|
| Start everything | `docker compose up --build` |
| Stop, keep data | `docker compose down` |
| Stop, delete data | `docker compose down -v` |
| Follow logs | `docker compose logs -f app` |
| Use another port | `APP_PORT=8090 docker compose up --build` |
| Simulate an outage | `curl -X POST "$API/api/admin/provider?down=true" -H "Authorization: Bearer $TOKEN"` |
| End the outage | Same call with `down=false` |

## Changing a limit

1. Change the value under `app.rules` in `application.yml`.
2. Raise `app.rules.version`. This is what keeps "same input, same rule version, same km" true.
3. Deploy.

Limits and their meanings are in the [rulebook](rules.md).
