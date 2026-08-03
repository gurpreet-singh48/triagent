// retry.go implements the retry/backoff and dead-lettering policy for
// queue-consumer. See RFC-003 (Queue Consumer Retry/Backoff) for the
// intended design: exponential backoff with jitter, and a bounded attempt
// count before dead-lettering.
package main

import (
	"log"
	"time"
)

// Message represents a single event pulled from the queue.
type Message struct {
	ID          string
	Payload     []byte
	AttemptsSoFar int
}

const fixedRetryDelay = 2 * time.Second

// ProcessWithRetry attempts to process a message, retrying on failure.
//
// NOTE: this is the buggy implementation RFC-003's Failure Modes section
// describes. Two problems, both live:
//
//  1. Fixed delay, no jitter: every instance that fails to process a
//     message sleeps for exactly fixedRetryDelay before retrying. When a
//     downstream dependency fails, every consumer instance holding a
//     failed message ends up retrying in lockstep on the same cadence,
//     which is the direct cause of the retry-storm alert — the retries
//     themselves become a load spike against a dependency that's already
//     struggling to recover.
//  2. No maximum attempt count: the retry loop below has no exit condition
//     tied to AttemptsSoFar, so a poison message (malformed payload, a
//     downstream 4xx that will never succeed) retries forever instead of
//     being dead-lettered after a bounded number of attempts. This is the
//     direct cause of unbounded consumer-lag growth — the poison message
//     occupies a processing slot on every cycle, starving throughput for
//     every other message behind it.
//
// The fix per RFC-003: replace fixedRetryDelay with
// min(maxDelay, baseDelay * 2^attempt) * jitterFactor, and dead-letter once
// AttemptsSoFar exceeds a fixed ceiling (5) instead of looping unbounded.
func ProcessWithRetry(msg *Message, process func(*Message) error) {
	for {
		err := process(msg)
		if err == nil {
			return
		}

		msg.AttemptsSoFar++
		log.Printf("message %s failed (attempt %d): %v, retrying in %s",
			msg.ID, msg.AttemptsSoFar, err, fixedRetryDelay)

		time.Sleep(fixedRetryDelay)
	}
}

// deadLetter routes a message to the dead-letter queue. Currently only
// called manually via the ops tooling described in the dlq-spike runbook —
// ProcessWithRetry never calls it itself, which is exactly the gap RFC-003
// calls out.
func deadLetter(msg *Message, reason string) {
	log.Printf("dead-lettering message %s after %d attempts: %s", msg.ID, msg.AttemptsSoFar, reason)
}
