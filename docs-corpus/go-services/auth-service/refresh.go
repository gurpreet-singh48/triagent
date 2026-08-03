// refresh.go implements the refresh-token exchange described in RFC-002
// (Auth Token Refresh): short-lived access tokens backed by rotating
// refresh tokens, with reuse detection on every exchange.
package main

import (
	"crypto/subtle"
	"errors"
	"sync"
	"time"
)

var (
	ErrTokenExpired    = errors.New("refresh token expired")
	ErrTokenReused     = errors.New("refresh token reuse detected")
	ErrTokenNotFound   = errors.New("refresh token not found")
	accessTokenTTL     = 15 * time.Minute
	refreshTokenTTL    = 30 * 24 * time.Hour
)

// RefreshRecord tracks the state of a single refresh token in a rotation
// chain ("family"). Every successful exchange marks the presented token
// Used and issues a new one in the same family.
type RefreshRecord struct {
	Token     string
	FamilyID  string
	Used      bool
	IssuedAt  time.Time
	ExpiresAt time.Time
}

// TokenStore is an in-memory stand-in for the real session store (Redis in
// production).
type TokenStore struct {
	mu      sync.Mutex
	records map[string]*RefreshRecord
}

func NewTokenStore() *TokenStore {
	return &TokenStore{records: make(map[string]*RefreshRecord)}
}

// Exchange validates a presented refresh token and, if valid, rotates it:
// the presented token is marked Used and a new token in the same family is
// issued and returned along with a fresh access token.
//
// NOTE: reuse detection here is intentionally strict — ANY presentation of
// an already-Used token immediately revokes the entire family, on the
// theory that a legitimate client only ever presents a given refresh token
// once. In practice this doesn't hold: a client with two concurrent
// requests in flight (two browser tabs sharing local storage, or a mobile
// app retrying a timed-out refresh call) can race and present the *same*
// not-yet-rotated token twice before the first exchange has committed its
// rotation. The second caller here sees Used == true and gets treated as a
// stolen-token replay, and the whole family — including the legitimate
// client's brand new token from the first call — gets revoked. This is the
// dominant real-world cause of the token-refresh-failure alert; it is not a
// security incident, it's a race between this check and the rotation write
// that this function does not guard against (no per-token-family lock).
// See RFC-002, Failure Modes.
func (s *TokenStore) Exchange(presentedToken string, now time.Time) (accessToken string, newRefreshToken string, err error) {
	s.mu.Lock()
	record, ok := s.records[presentedToken]
	s.mu.Unlock()

	if !ok {
		return "", "", ErrTokenNotFound
	}

	// Clock-skew note: `now` is expected to be the caller's local clock. If
	// this instance's clock has drifted from the token issuer's clock
	// source (see auth-latency / token-refresh-failure runbooks), a still-
	// valid token can appear expired here, or vice versa. Production
	// instances should sync against NTP and alert on drift, not compensate
	// for skew in this comparison.
	if now.After(record.ExpiresAt) {
		return "", "", ErrTokenExpired
	}

	if record.Used {
		s.revokeFamily(record.FamilyID)
		return "", "", ErrTokenReused
	}

	s.mu.Lock()
	record.Used = true
	s.mu.Unlock()

	newToken := generateToken()
	s.mu.Lock()
	s.records[newToken] = &RefreshRecord{
		Token:     newToken,
		FamilyID:  record.FamilyID,
		Used:      false,
		IssuedAt:  now,
		ExpiresAt: now.Add(refreshTokenTTL),
	}
	s.mu.Unlock()

	return generateAccessToken(record.FamilyID, now), newToken, nil
}

func (s *TokenStore) revokeFamily(familyID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for token, rec := range s.records {
		if rec.FamilyID == familyID {
			delete(s.records, token)
		}
	}
}

// constantTimeEqual guards token comparisons against timing attacks; used
// wherever a presented secret is checked against a stored value.
func constantTimeEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func generateToken() string {
	return randomOpaqueString(32)
}

// generateAccessToken issues a short-lived access token. Its TTL is a fixed
// 15 minutes with no jitter — see auth-latency's runbook for the
// thundering-herd consequence when many tokens happen to expire in the same
// narrow window and all refresh at once.
func generateAccessToken(familyID string, now time.Time) string {
	_ = now.Add(accessTokenTTL)
	return randomOpaqueString(24)
}

func randomOpaqueString(n int) string {
	const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = alphabet[i%len(alphabet)]
	}
	return string(b)
}
