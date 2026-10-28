package com.tatkal.hold;

/**
 * A request to hold a seat.
 *
 * @param seatId      a specific seat, or null for "give me any free seat"
 * @param thinkTimeMs simulated work between reading the seat and writing it (pricing, validation...).
 *                    It widens the race window, the same way real business logic does.
 */
public record HoldCommand(long showId, Long seatId, String userId, int thinkTimeMs) {

    public HoldCommand {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (thinkTimeMs < 0) {
            throw new IllegalArgumentException("thinkTimeMs must be >= 0");
        }
    }

    public void think() {
        if (thinkTimeMs > 0) {
            try {
                Thread.sleep(thinkTimeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
