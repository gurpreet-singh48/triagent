# Alert: PaymentServiceDuplicateCharge

- **Category:** `duplicate-charge`
- **Team:** `payment-service`
- **Severity:** `critical`
- **Expr:** `sum(rate(payment_charge_duplicate_total{service="payment-service"}[10m])) > 0`
- **For:** `2m`
- **Runbook:** `https://runbooks.internal/payment-service/duplicate-charge`

## Description
Fires when `payment_charge_duplicate_total` — incremented whenever the charge
processor detects that two `Charge` rows were created for the same
`idempotency_key` — is non-zero. This is one of the most severe alerts in the
system: it means a customer was billed twice for a single logical purchase.

Root cause is almost always a gap in idempotency-key enforcement on the write
path: either the key isn't checked before the charge is created (a
check-then-act race between "look up existing charge" and "insert new charge"
under concurrent requests), or a client/webhook retry arrives with a
*different* idempotency key for what should be the same logical operation
because the key was derived from a timestamp instead of a stable request
identity. See RFC-001 (Payment Idempotency) for the full failure-mode
discussion, and `payment-service`'s `charge.go` for the current (buggy)
implementation of the check.

## Runbook Steps
1. Page `payment-service` on-call immediately — this alert requires a customer
   refund workflow, not just a technical fix.
2. Pull the offending `idempotency_key` and `charge_id`s from the
   `payment_charge_duplicate_total` exemplar trace.
3. Check whether the duplicate arrived within milliseconds of the original
   (race condition) or minutes later (retried webhook with a fresh key).
4. If it's a race condition, verify whether the idempotency check acquired a
   row lock / used `INSERT ... ON CONFLICT` — the current implementation does
   a separate `SELECT` then `INSERT`, which is not atomic under concurrency.
5. File a refund for the duplicate charge and notify the customer-support
   on-call.
