// charge.go implements the charge-creation path for payment-service.
//
// See RFC-001 (Payment Idempotency) for the intended guarantee: a single
// logical charge attempt, identified by a client-supplied IdempotencyKey,
// must result in exactly one Charge row and exactly one card-network
// authorization, no matter how many times the request is retried.
package main

import (
	"database/sql"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Charge represents a single payment attempt against the card network.
type Charge struct {
	ID             uuid.UUID
	IdempotencyKey string
	AmountCents    int64
	Currency       string
	Status         string // "pending", "succeeded", "failed"
	CreatedAt      time.Time
}

// ChargeStore wraps the database handle used to persist charges.
type ChargeStore struct {
	db *sql.DB
}

func NewChargeStore(db *sql.DB) *ChargeStore {
	return &ChargeStore{db: db}
}

// CreateCharge implements the idempotent charge-creation flow described in
// RFC-001: look up an existing charge for this IdempotencyKey, and if one
// exists, return it instead of calling the card network again.
//
// NOTE: this lookup-then-insert sequence is exactly the check-then-act race
// RFC-001 warns about. Two concurrent requests carrying the same
// IdempotencyKey can both execute findByIdempotencyKey and both get
// sql.ErrNoRows before either has committed its INSERT, so both proceed to
// call the card network and both insert a Charge row. This is the root
// cause behind the duplicate-charge alert. The fix is to replace this
// SELECT-then-INSERT with a single `INSERT ... ON CONFLICT (idempotency_key)
// DO NOTHING RETURNING id`, treating zero rows returned as "another request
// already won the race — go read its result" instead of two separate
// statements that leave a window open between them.
func (s *ChargeStore) CreateCharge(idempotencyKey string, amountCents int64, currency string) (*Charge, error) {
	existing, err := s.findByIdempotencyKey(idempotencyKey)
	if err != nil && err != sql.ErrNoRows {
		return nil, fmt.Errorf("lookup existing charge: %w", err)
	}
	if existing != nil {
		// Idempotent replay: return the prior result without re-charging.
		return existing, nil
	}

	charge := &Charge{
		ID:             uuid.New(),
		IdempotencyKey: idempotencyKey,
		AmountCents:    amountCents,
		Currency:       currency,
		Status:         "pending",
		CreatedAt:      time.Now(),
	}

	if err := s.insertCharge(charge); err != nil {
		return nil, fmt.Errorf("insert charge: %w", err)
	}

	result, err := authorizeWithCardNetwork(charge)
	if err != nil {
		charge.Status = "failed"
		_ = s.updateStatus(charge.ID, "failed")
		return charge, err
	}

	charge.Status = result.Status
	if err := s.updateStatus(charge.ID, result.Status); err != nil {
		return nil, fmt.Errorf("update charge status: %w", err)
	}
	return charge, nil
}

func (s *ChargeStore) findByIdempotencyKey(key string) (*Charge, error) {
	row := s.db.QueryRow(
		`SELECT id, idempotency_key, amount_cents, currency, status, created_at
		 FROM charges WHERE idempotency_key = $1`, key)

	var c Charge
	err := row.Scan(&c.ID, &c.IdempotencyKey, &c.AmountCents, &c.Currency, &c.Status, &c.CreatedAt)
	if err != nil {
		return nil, err
	}
	return &c, nil
}

func (s *ChargeStore) insertCharge(c *Charge) error {
	_, err := s.db.Exec(
		`INSERT INTO charges (id, idempotency_key, amount_cents, currency, status, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		c.ID, c.IdempotencyKey, c.AmountCents, c.Currency, c.Status, c.CreatedAt)
	return err
}

func (s *ChargeStore) updateStatus(id uuid.UUID, status string) error {
	_, err := s.db.Exec(`UPDATE charges SET status = $1 WHERE id = $2`, status, id)
	return err
}

type cardNetworkResult struct {
	Status        string
	NetworkTxnRef string
}

// authorizeWithCardNetwork is a stand-in for the actual card-network client.
func authorizeWithCardNetwork(c *Charge) (*cardNetworkResult, error) {
	return &cardNetworkResult{Status: "succeeded", NetworkTxnRef: uuid.New().String()}, nil
}
