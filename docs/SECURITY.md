# Security and privacy: attacks and answers

This file is organised as a list of attacks. Each one says what someone might try, what stops it, and how that was proved.

For the known gaps, see [LIMITATIONS.md](LIMITATIONS.md).

## Who can do what

| Action | Employee | Operator | Admin |
|---|---|---|---|
| Log in | Yes | Yes | Yes |
| Read a trip | Own trips only, no coordinates | Yes, own client | Yes, own client |
| Submit a trip | No | Yes | Yes |
| Send late pings | No | Yes | Yes |
| Close an invoice | No | Yes | Yes |
| Read an invoice | No | Yes | Yes |
| Read metrics | No | Yes | Yes |
| Switch the demo provider | No | No | Yes |

All of these apply within the caller's own client. Nobody can cross clients.

## Access attacks

| Attack | What stops it | Result | Proof |
|---|---|---|---|
| Read another client's trip | Every lookup includes the tenant from the token | 404, so the trip's existence stays hidden | `TenantIsolationTest` |
| Change or close another client's trip | Same | 404 | `TenantIsolationTest` |
| Claim another tenant in the request body | The request has no tenant field | The token's tenant always applies | `TripRequest`, `TenantIsolationTest` |
| Reuse a trip id from another client | Unique per tenant and trip | Both clients keep separate trips | `TenantIsolationTest` |
| Call with no token | Spring Security | 401 | `TenantIsolationTest` |
| Tamper with a token | The signature check fails | 401 | Manual check on the running stack |
| Use a wrong password | bcrypt comparison | 401 | `TenantIsolationTest` |
| Employee submits a trip | Role rule on `POST /api/trips/**` | 403 | `TenantIsolationTest` |
| Employee reads a colleague's trip | Trip must list the employee | 404 | `TenantIsolationTest` |
| Operator switches the provider | Admin-only rule | 403 | `TenantIsolationTest` |
| Anyone reads metrics | Role rule on `/actuator/**` | 401 or 403 | `TenantIsolationTest` |

## Privacy attacks

| Attack | What stops it | Proof |
|---|---|---|
| Find a home location in the logs | Logs carry trip ids and counts only | `LogPrivacyTest` plants a home location and rider id and confirms they don't appear |
| Trigger an error that echoes personal data | Messages name fields, never values | `TenantIsolationTest`, `LogPrivacyTest` |
| Read a colleague's home from a shared trip | Coordinates are removed for employees | `TenantIsolationTest` |
| Learn internals from a server error | Unknown errors say only "Something went wrong" | `GlobalExceptionHandler` |
| Learn the database host from an outage | A 503 with a fixed message | `DatabaseDownTest` |

## Data handling

| Data | Where it lives | Note |
|---|---|---|
| Passwords | bcrypt hashes in memory | Demo accounts only |
| JWT secret | Environment variable | At least 32 bytes, or the app refuses to start |
| Trip evidence, including home locations | Postgres, as text | Not encrypted by the app |
| Tokens | With the client | Expire after one hour |

## Input limits

| Field | Limit |
|---|---|
| Pings on a trip | 20,000 |
| Late pings in one request | 20,000 |
| Milestones, planned stops, ad-hoc riders | 200 each |
| Trip id | 64 characters |
| Latitude and longitude | Real-world ranges |
