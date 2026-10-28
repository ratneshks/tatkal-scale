package com.tatkal.stampede;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * POST /api/v1/stampede          {"strategy":"PESSIMISTIC","mode":"ANY_SEAT","threads":500,"seats":5}
 * POST /api/v1/stampede/compare  same body; runs every strategy (or "strategies":[...]) back to back
 * GET  /api/v1/stampede/runs     history of past runs
 */
@RestController
@RequestMapping("/api/v1/stampede")
public class StampedeController {

    private final StampedeService service;

    public StampedeController(StampedeService service) {
        this.service = service;
    }

    @PostMapping
    public StampedeReport run(@RequestBody(required = false) StampedeRequest req) {
        return service.run(req == null ? empty() : req);
    }

    @PostMapping("/compare")
    public List<StampedeReport> compare(@RequestBody(required = false) StampedeRequest req) {
        return service.compare(req == null ? empty() : req);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "50") int limit) {
        return service.history(limit);
    }

    private static StampedeRequest empty() {
        return new StampedeRequest(null, null, null, null, null, null, null, null);
    }
}
