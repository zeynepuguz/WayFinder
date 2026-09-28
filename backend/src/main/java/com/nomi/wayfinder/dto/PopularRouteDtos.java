package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.planning.PopularTheme;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

// "Popüler rotalar" (GET /api/v1/routes/popular, POST /api/v1/routes/popular/start). Serializable: cached in Redis
public final class PopularRouteDtos {

    private PopularRouteDtos() {
    }

    /**
     * One themed day planned from real places.
     *
     * @param startLabel             the district or city the route starts in ("Üsküdar", "Ankara")
     * @param estimatedCostPerPerson sum of the known prices only; null when no stop has a known price
     * @param unknownPriceStops      stops without price info (not in estimatedCostPerPerson)
     */
    public record PopularRouteResponse(
            PopularTheme theme,
            String title,
            String description,
            String startLabel,
            double startLatitude,
            double startLongitude,
            LocalDate date,
            List<PopularStop> stops,
            int totalWalkingMinutes,
            Integer estimatedCostPerPerson,
            int unknownPriceStops
    ) implements Serializable {
    }

    /**
     * @param time           planned arrival, "HH:mm"
     * @param walkingMinutes from the previous stop (the first: from the start point)
     */
    public record PopularStop(
            String time,
            StopType type,
            String typeLabel,
            int walkingMinutes,
            int distanceMeters,
            PopularPlace place
    ) implements Serializable {
    }

    // The place fields a route card needs (details via /places/{id})
    public record PopularPlace(
            Long id,
            String name,
            PlaceCategory category,
            double latitude,
            double longitude,
            PlaceImage image,
            Integer estimatedCost,
            boolean verified,
            String district
    ) implements Serializable {
    }

    /**
     * @param city     city slug from GET /api/v1/cities
     * @param district district slug of that city (optional)
     * @param date     null = today (Istanbul time)
     */
    public record PopularRouteStartRequest(
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String district,
            @NotNull PopularTheme theme,
            LocalDate date
    ) {
    }
}
