package main

import (
	"log"
)

// consume is a stand-in for the real queue client's poll loop.
func consume(pollNext func() (*Message, bool)) {
	for {
		msg, ok := pollNext()
		if !ok {
			return
		}
		ProcessWithRetry(msg, handleMessage)
	}
}

func handleMessage(msg *Message) error {
	// Actual business logic (order-fulfillment events, notification
	// dispatch, webhook delivery) lives here in production; omitted for
	// brevity in this corpus.
	return nil
}

func main() {
	log.Println("queue-consumer starting")
	consume(func() (*Message, bool) {
		return nil, false
	})
}
