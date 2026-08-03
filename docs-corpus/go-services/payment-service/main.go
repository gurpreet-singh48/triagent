package main

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"
)

type createChargeRequest struct {
	IdempotencyKey string `json:"idempotency_key"`
	AmountCents    int64  `json:"amount_cents"`
	Currency       string `json:"currency"`
}

func main() {
	db, err := sql.Open("postgres", "postgres://payment:payment@localhost:5432/payment?sslmode=disable")
	if err != nil {
		log.Fatalf("connect to postgres: %v", err)
	}
	store := NewChargeStore(db)

	http.HandleFunc("/charges", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req createChargeRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		charge, err := store.CreateCharge(req.IdempotencyKey, req.AmountCents, req.Currency)
		if err != nil {
			log.Printf("create charge failed: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(charge)
	})

	log.Println("payment-service listening on :8081")
	log.Fatal(http.ListenAndServe(":8081", nil))
}
