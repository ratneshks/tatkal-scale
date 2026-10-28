package com.tatkal.seat;

/**
 * AVAILABLE --hold()--> HELD --confirm()--> BOOKED
 *     ^                  |
 *     +----release()/expire()
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    BOOKED
}
