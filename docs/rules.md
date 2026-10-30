# Billing rules (rule version 1)

These rules run in this exact order. A change to any of them must raise `ruleVersion`.

## 1. Clean the pings

1. Sort by time. Ties are broken by latitude, longitude and accuracy, so arrival order cannot change the result.
2. Remove repeats of the same time and location.
3. Drop fixes with accuracy worse than 50 m.
4. Drop teleports above 120 km/h by building chains of pings that make sense together and keeping the longest chain.

## 2. Add anchors

| Anchor | Rule |
|---|---|
| Pickup or drop | The actual point if valid. Otherwise the fallback: profile home and sign-in time for an ad-hoc rider, or planned location and time for a planned rider |
| No-show | Same fallback, and only when `includeNoShows` is on |
| Office | The actual point if within 400 m of the planned office. Otherwise the planned office, with the milestone's time |

A location is invalid if it is missing, out of range, or exactly 0,0. A milestone with no valid point and no valid fallback adds nothing.

## 3. Measure each pair of neighbouring points

| Order | Condition | Distance | Label |
|---|---|---|---|
| 1 | Under 70 s at a plausible speed | Straight line | `SHORT_GAP_AERIAL` |
| 2 | 70 s or more, under 5 km/h | Straight line | `PARKED` |
| 3 | Otherwise, provider gave no answer | Straight line × 1.4 | `PROVIDER_DOWN_ESTIMATE` |
| 4 | Otherwise, route above 100 km | Straight line × 1.4 | `REJECTED_DISTANCE` |
| 5 | Otherwise, route speed and estimate speed both above 120 km/h | Straight line | `REJECTED_SPEED` |
| 6 | Otherwise | Route distance | `ROUTED` |

## 4. Add up and round

Each segment is rounded to whole metres, and the total is the exact sum of those. The billable figure is rounded once, half up, to 0.1 km, in `TripResult.billableKm()`.

## 5. Trip status

| Status | Meaning | Can it be billed? |
|---|---|---|
| `OK` | All rules passed | Yes |
| `DEGRADED` | Contains an estimate because the provider was down | No, it is recomputed automatically |
| `NEEDS_REVIEW` | A route was rejected, the auditor disagreed, or there were too few points | Only with `?reviewed=true` |

## Decisions where the spec is silent

| Gap | Decision | Why |
|---|---|---|
| Accuracy limit | 50 m | The spec gives no number. It is configurable |
| Bad first ping | Longest chain of plausible pings | Comparing to the last good ping makes one bad first ping delete the whole trip |
| Rejected route, over 100 km | Straight line × 1.4, flagged for review | The same estimate used when the provider is down |
| Rejected route, too fast | Plain straight line, flagged for review | Even the estimate is impossible, so pay the lowest defensible figure until a human looks |
| Logout trips | The office anchor goes at the trip start | The spec's end-of-trip rule would add a false leg back to the office after the last drop |
| Same timestamp, few metres apart | A jump within 50 m counts as a plausible speed | Otherwise a ping and an anchor at one instant give an infinite speed and a needless rejection. Found by the simulator |
