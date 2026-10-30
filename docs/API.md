# API cookbook

Recipes you can copy and run. Start the stack with `docker compose up --build`, then run these from the repo folder, in order. Recipes 4 to 6 build on Recipe 1, so run them on a fresh database with `docker compose down -v` first if you want the same numbers.

```bash
API=http://localhost:8080
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"userId":"admin-a","password":"demo-password"}' | jq -r .token)
```

| Demo user | Role | Client |
|---|---|---|
| `admin-a` | Admin | `client-a` |
| `operator-a` | Operator | `client-a` |
| `emp-1` | Employee | `client-a` |
| `admin-b` | Admin | `client-b` |

The password for all of them is `demo-password`. Tokens last one hour.

## Recipe 1: Submit a trip

```bash
curl -s -X POST $API/api/trips -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @scripts/sample-trip.json | jq 'del(.segments)'
```

What you send:

| Field | Required | Meaning |
|---|---|---|
| `tripId` | Yes | Your id, up to 64 characters |
| `direction` | Yes | `LOGIN` or `LOGOUT` |
| `pings` | Yes | Raw GPS dots: `latitude`, `longitude`, `timestampMillis`, `accuracyMeters` |
| `milestones` | Yes | `type`, `employeeId`, and optional `latitude`, `longitude`, `timeMillis` |
| `plannedStops` | Yes | Planned riders: `employeeId`, `latitude`, `longitude`, `timeMillis` |
| `adHocRiders` | Yes | `employeeId`, `homeLatitude`, `homeLongitude`, `signInTimeMillis` |
| `officeLatitude`, `officeLongitude` | Yes | The planned office |

`type` is one of `TRIP_START`, `PICKUP`, `DROP`, `NO_SHOW` or `TRIP_END`.

What you get back:

```json
{
  "tripId": "demo",
  "status": "OK",
  "ruleVersion": 1,
  "totalMeters": 34194,
  "billableKm": 34.2,
  "providerCalls": 1,
  "metersByRule": { "SHORT_GAP_AERIAL": 22128, "ROUTED": 12066 },
  "segments": [
    {
      "fromTimeMillis": 1790000420000,
      "toTimeMillis": 1790000420000,
      "distanceMeters": 1,
      "rule": "SHORT_GAP_AERIAL",
      "fromLatitude": 12.82707956274702,
      "fromLongitude": 77.42109327638806,
      "toLatitude": 12.82708354683285,
      "toLongitude": 77.42108612896347
    }
  ]
}
```

This is the real output for the sample trip on a fresh database, with only the first segment shown. The Recipe 1 command above removes `segments` to keep the output short.

## Recipe 2: Read a trip

```bash
curl -s $API/api/trips/demo -H "Authorization: Bearer $TOKEN" | jq '{status, billableKm, metersByRule}'
```

Employees can read their own trips, and the coordinates come back empty.

## Recipe 3: Answer a vendor dispute

List every stretch that wasn't plain GPS:

```bash
curl -s $API/api/trips/demo -H "Authorization: Bearer $TOKEN" |
  jq '.segments[] | select(.rule != "SHORT_GAP_AERIAL") | {rule, km: (.distanceMeters / 1000)}'
```

## Recipe 4: Close the invoice

```bash
curl -s -X POST $API/api/trips/demo/close -H "Authorization: Bearer $TOKEN" | jq .
```

Closing twice returns the same invoice. For a trip flagged `NEEDS_REVIEW`, add `?reviewed=true`.

## Recipe 5: Send late pings

```bash
curl -s -X POST $API/api/trips/demo/late-pings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @scripts/late-pings.json | jq 'del(.segments)'
```

Sending the same pings again changes nothing.

## Recipe 6: See the correction

```bash
curl -s $API/api/trips/demo/invoice -H "Authorization: Bearer $TOKEN" | jq .
```

```json
{
  "tripId": "demo",
  "billedMeters": 34194,
  "ruleVersion": 1,
  "corrections": [ { "deltaMeters": -59, "deltaKm": "-0.1 km" } ],
  "currentMeters": 34135
}
```

`billedMeters` never changes. `currentMeters` is the bill plus its corrections.

## Recipe 7: Simulate a provider outage

```bash
curl -s -X POST "$API/api/admin/provider?down=true" -H "Authorization: Bearer $TOKEN"
```

New trips now return `DEGRADED`, unless their routes are already cached. **Switch it back with `down=false` afterwards**, and they recover by themselves within seconds.

## Recipe 8: Read the metrics

```bash
curl -s $API/actuator/prometheus -H "Authorization: Bearer $TOKEN" | grep -E '^(trips_|provider_|segments_)'
```

What each metric means is in the [runbook](OPERATIONS.md).

## Status codes

| Code | Meaning | Example message |
|---|---|---|
| 200 | Worked | |
| 400 | Invalid request | "Invalid or missing fields: officeLatitude, tripId" |
| 401 | No token, a bad token, or a wrong password | "Wrong user or password" |
| 403 | Your role can't do this | |
| 404 | Not found, or belongs to another client | "Trip not found" |
| 409 | Trip isn't in the right state | "Trip needs review before it can be billed" |
| 503 | Database unavailable | "Temporarily unavailable, safe to retry" |

Errors use the standard problem format:

```json
{ "status": 409, "title": "Conflict", "detail": "Trip needs review before it can be billed", "instance": "/api/trips/demo/close" }
```
