package com.tatkal.hold;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint per locking strategy, same contract for all:
 *   POST /api/v1/optimistic/shows/{showId}/holds
 *   POST /api/v1/pessimistic/shows/{showId}/holds
 *   POST /api/v1/skip-locked/shows/{showId}/holds
 *   POST /api/v1/naive/shows/{showId}/holds        (unsafe, for demos)
 * Body: {"userId": "...", "seatId": 12}   (omit seatId to get any free seat)
 */
@RestController
@RequestMapping("/api/v1")
public class HoldController {

    public record HoldRequest(@NotBlank String userId, Long seatId, @Min(0) Integer thinkTimeMs) {
    }

    private final HoldOrchestrator orchestrator;

    public HoldController(HoldOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/optimistic/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult holdOptimistic(@PathVariable long showId, @Valid @RequestBody HoldRequest req) {
        return hold(StrategyType.OPTIMISTIC, showId, req);
    }

    @PostMapping("/pessimistic/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult holdPessimistic(@PathVariable long showId, @Valid @RequestBody HoldRequest req) {
        return hold(StrategyType.PESSIMISTIC, showId, req);
    }

    @PostMapping("/skip-locked/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult holdSkipLocked(@PathVariable long showId, @Valid @RequestBody HoldRequest req) {
        return hold(StrategyType.SKIP_LOCKED, showId, req);
    }

    @PostMapping("/naive/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult holdNaive(@PathVariable long showId, @Valid @RequestBody HoldRequest req) {
        return hold(StrategyType.NAIVE, showId, req);
    }

    private HoldResult hold(StrategyType type, long showId, HoldRequest req) {
        int think = req.thinkTimeMs() == null ? 0 : req.thinkTimeMs();
        return orchestrator.hold(type, new HoldCommand(showId, req.seatId(), req.userId(), think));
    }
}
