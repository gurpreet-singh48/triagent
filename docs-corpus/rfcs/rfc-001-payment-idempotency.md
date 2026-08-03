# RFC-001: Payment Idempotency

- **Status:** Accepted
- **Owner:** payment-service
- **Related alerts:** `duplicate-charge`, `5xx-spike`

## Summary
This RFC defines how `payment-service` guarantees that a single logical
charge request — regardless of how many times the client or an upstream
webhook retries it — results in exactly one `Charge` row and exactly one
downstream card-network authorization.

## Motivation
Clients (our own frontend, and third-party integrations calling our public
API) retry on timeout by design — a request that times out client-side may
have actually succeeded server-side. Without an idempotency guarantee, a
naive retry-on-timeout policy double-charges customers. This is one of the
few classes of bug in this system with direct, immediate financial and trust
impact, so it gets a dedicated RFC rather than being folded into general API
design guidance.

## Design
Every charge request must carry a client-supplied `idempotency_key` (a UUID
generated once per logical purchase attempt, reused across retries of that
same attempt). On receipt:

1. Look up an existing `Charge` row by `idempotency_key`.
2. If found, return the existing charge's result — do not call the card
   network again.
3. If not found, create the `Charge` row and call the card network.

Step 1 and step 3 must be atomic with respect to concurrent requests carrying
the same key — otherwise two concurrent retries can both miss the lookup in
step 1 and both proceed to step 3. The correct implementation uses a unique
constraint on `idempotency_key` and an `INSERT ... ON CONFLICT DO NOTHING
RETURNING`, treating "0 rows returned" as "another request won the race,
go read its result" — not a separate `SELECT` followed by a separate
`INSERT`.

## Failure Modes
- **`duplicate-charge`** — the check-then-act race described above: two
  concurrent requests with the same `idempotency_key` both pass the existence
  check before either has committed its insert, because the check and the
  insert are two separate statements instead of one atomic
  `INSERT ... ON CONFLICT`. This is the single most severe failure mode this
  RFC exists to prevent, and it is currently **not fully closed** in the
  `payment-service` implementation — see `charge.go`.
- **`duplicate-charge` (secondary cause)** — a client or webhook sender
  generates a *new* `idempotency_key` on each retry (e.g. derived from
  `time.Now()` instead of a stable per-purchase-attempt identifier), which
  defeats the guarantee entirely regardless of how correct the server-side
  logic is. Idempotency keys must be generated once per logical attempt and
  reused across retries of that attempt, not once per HTTP request.
- **`5xx-spike`** — if the idempotency-key uniqueness constraint is violated
  under load (e.g. constraint check contention) without a graceful retry-on-
  conflict path, this surfaces as elevated 500s rather than a clean
  idempotent response.

## Non-goals
Distributed-transaction guarantees across payment-service and the card
network itself are out of scope — we guarantee our own write is idempotent,
not that the card network's response is deterministic on retry (that's the
card network's problem, addressed separately by contract).
