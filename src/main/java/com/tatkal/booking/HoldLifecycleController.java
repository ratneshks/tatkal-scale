package com.tatkal.booking;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/holds/{holdToken}")
public class HoldLifecycleController {

    private final HoldLifecycleService service;

    public HoldLifecycleController(HoldLifecycleService service) {
        this.service = service;
    }

    @GetMapping
    public BookingView get(@PathVariable UUID holdToken) {
        return service.get(holdToken);
    }

    /** Simulated payment success. Idempotent: calling twice returns the same confirmed booking. */
    @PostMapping("/confirm")
    public BookingView confirm(@PathVariable UUID holdToken) {
        return service.confirm(holdToken);
    }

    /** User abandons checkout; the seat goes straight back to the pool. */
    @DeleteMapping
    public BookingView release(@PathVariable UUID holdToken) {
        return service.release(holdToken);
    }
}
