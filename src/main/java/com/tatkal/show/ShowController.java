package com.tatkal.show;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import com.tatkal.seat.SeatStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/shows")
public class ShowController {

    public record ShowView(long id, String name, Instant startsAt, int totalSeats) {
    }

    public record SeatView(long id, String seatNo, SeatStatus status, String heldBy,
                           Instant holdExpiresAt, boolean holdExpired, long version) {
    }

    public record SeatMap(ShowView show, Map<SeatStatus, Long> counts, List<SeatView> seats) {
    }

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final Clock clock;

    public ShowController(ShowRepository shows, SeatRepository seats, Clock clock) {
        this.shows = shows;
        this.seats = seats;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ShowView> list() {
        return shows.findAll().stream().map(ShowController::view).toList();
    }

    @GetMapping("/{showId}/seats")
    @Transactional(readOnly = true)
    public SeatMap seatMap(@PathVariable long showId) {
        Show show = shows.findById(showId)
                .orElseThrow(() -> new TatkalException(ErrorCode.NOT_FOUND, "Show " + showId + " not found"));
        Instant now = clock.instant();
        List<Seat> all = seats.findByShowIdOrderByIdAsc(showId);
        Map<SeatStatus, Long> counts = new EnumMap<>(SeatStatus.class);
        for (SeatStatus s : SeatStatus.values()) {
            counts.put(s, all.stream().filter(seat -> seat.getStatus() == s).count());
        }
        List<SeatView> views = all.stream()
                .map(s -> new SeatView(s.getId(), s.getSeatNo(), s.getStatus(), s.getHeldBy(),
                        s.getHoldExpiresAt(), s.isExpiredHoldAt(now), s.getVersion()))
                .toList();
        return new SeatMap(view(show), counts, views);
    }

    private static ShowView view(Show s) {
        return new ShowView(s.getId(), s.getName(), s.getStartsAt(), s.getTotalSeats());
    }
}
