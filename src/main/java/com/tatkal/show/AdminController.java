package com.tatkal.show;

import com.tatkal.sweeper.ExpiredHoldReleaser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ShowAdminService admin;
    private final ExpiredHoldReleaser releaser;
    private final Clock clock;

    public AdminController(ShowAdminService admin, ExpiredHoldReleaser releaser, Clock clock) {
        this.admin = admin;
        this.releaser = releaser;
        this.clock = clock;
    }

    @PostMapping("/shows/{showId}/reset")
    public Map<String, Object> reset(@PathVariable long showId, @RequestParam(defaultValue = "5") int seats) {
        admin.resetShow(showId, seats);
        return Map.of("showId", showId, "seats", seats);
    }

    /** Run the expiry sweep now instead of waiting up to 30s (handy in demos). */
    @PostMapping("/sweeper/run")
    public Map<String, Object> sweepNow() {
        return Map.of("released", releaser.releaseExpired(clock.instant()));
    }
}
