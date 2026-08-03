package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
)

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

type refreshResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
}

func main() {
	store := NewTokenStore()

	http.HandleFunc("/auth/refresh", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req refreshRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		access, refresh, err := store.Exchange(req.RefreshToken, time.Now())
		if err != nil {
			log.Printf("refresh exchange failed: %v", err)
			http.Error(w, err.Error(), http.StatusUnauthorized)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(refreshResponse{AccessToken: access, RefreshToken: refresh})
	})

	log.Println("auth-service listening on :8082")
	log.Fatal(http.ListenAndServe(":8082", nil))
}
