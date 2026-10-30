#!/bin/bash
# Walks through the main flows against a running stack: docker compose up --build
# Needs curl and jq.
set -euo pipefail

API=http://localhost:${APP_PORT:-8080}
HERE="$(cd "$(dirname "$0")" && pwd)"
PASSWORD="${DEMO_PASSWORD:-demo-password}"

login() {
  curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$1\",\"password\":\"$PASSWORD\"}" | jq -r .token
}

status_of() {
  curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1" "$2"
}

summary='{tripId, status, billableKm, providerCalls, metersByRule, segments: (.segments | length)}'

# Each run moves the trip a little, so its routes are never already in the cache.
run_id=$(date +%s)
shift_degrees=$(awk -v r="$run_id" 'BEGIN { printf "%.4f", (r % 1000) * 0.002 }')
new_trip() {
  jq --arg id "$1" --argjson d "$shift_degrees" '
      .tripId = $id
      | .pings[].latitude += $d
      | .officeLatitude += $d
      | .plannedStops[].latitude += $d
      | .adHocRiders[].homeLatitude += $d
      | .milestones[] |= (if .latitude == null then . else .latitude += $d end)' \
    "$HERE/sample-trip.json"
}
post_trip() {
  new_trip "$2" | curl -s -X POST "$API/api/trips" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d @-
}

echo "== 1. Log in as an admin of client-a =="
ADMIN=$(login admin-a)
echo "got a token"

echo
echo "== 2. Submit a trip. Every km carries the rule that produced it =="
TRIP="demo-$run_id"
post_trip "$ADMIN" "$TRIP" | jq "$summary"

echo
echo "== 3. Replaying the same trip changes nothing =="
post_trip "$ADMIN" "$TRIP" | jq "$summary"

echo
echo "== 4. Another client cannot see it, and an employee not on the trip cannot either =="
echo "client-b admin gets HTTP $(status_of "$(login admin-b)" "$API/api/trips/$TRIP")"
echo "no token gets HTTP $(curl -s -o /dev/null -w '%{http_code}' "$API/api/trips/$TRIP")"

echo
echo "== 5. Close the invoice =="
curl -s -X POST "$API/api/trips/$TRIP/close" -H "Authorization: Bearer $ADMIN" | jq .

echo
echo "== 6. Late pings arrive after billing: a signed correction, invoice unchanged =="
curl -s -X POST "$API/api/trips/$TRIP/late-pings" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d @<(
    jq --argjson d "$shift_degrees" 'map(.latitude += $d)' "$HERE/late-pings.json") | jq "$summary"
curl -s "$API/api/trips/$TRIP/invoice" -H "Authorization: Bearer $ADMIN" | jq .

echo
echo "== 7. The provider goes down: the trip is still answered, marked DEGRADED =="
curl -s -X POST "$API/api/admin/provider?down=true" -H "Authorization: Bearer $ADMIN" | jq -c .
DOWN="demo-down-$run_id"
shift_degrees=$(awk -v d="$shift_degrees" 'BEGIN { printf "%.4f", d + 0.5 }')
post_trip "$ADMIN" "$DOWN" | jq "$summary"
echo "closing it gets HTTP $(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ADMIN" "$API/api/trips/$DOWN/close")"

echo
echo "== 8. The provider recovers: the trip fixes itself with no request from us =="
curl -s -X POST "$API/api/admin/provider?down=false" -H "Authorization: Bearer $ADMIN" | jq -c .
for _ in $(seq 1 30); do
  STATUS=$(curl -s "$API/api/trips/$DOWN" -H "Authorization: Bearer $ADMIN" | jq -r .status)
  [ "$STATUS" != "DEGRADED" ] && break
  sleep 2
done
curl -s "$API/api/trips/$DOWN" -H "Authorization: Bearer $ADMIN" | jq "$summary"

echo
echo "== 9. What an on-call engineer sees =="
curl -s "$API/actuator/prometheus" -H "Authorization: Bearer $ADMIN" |
  grep -E '^(trips_processed|trips_degraded_waiting|trips_degraded_oldest_age_seconds|trips_failed_audit|segments_rejected|invoice_corrections|provider_failures|provider_timeouts|provider_retries|provider_breaker_open|route_cache_(hits|misses))' || true
