package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

// "Popüler rotalar" (GET /api/v1/routes/popular, POST /api/v1/routes/popular/start). Serializable: cached in Redis
public final class PopularRouteDtos {

    private PopularRouteDtos() {
    }

    /**
     * The most popular sights of one walkable area in walking order, with meals where the day needs them.
     *
     * @param key                    stable id of the route (its area and sights); POST /start takes it
     * @param title                  real area name(s): "Sultanahmet ve çevresi", "Galata–Karaköy"
     * @param popularityNote         why these places count as popular ("Wikipedia’da en çok okunan yerler")
     * @param startLabel             the first stop's name (the route starts there)
     * @param estimatedCostPerPerson sum of the known prices only; null when no stop has a known price
     * @param unknownPriceStops      stops without price info (not in estimatedCostPerPerson)
     */
    public record PopularRouteResponse(
            String key,
            String title,
            String description,
            String popularityNote,
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
     * @param time            planned arrival, "HH:mm"
     * @param durationMinutes planned length of the stop
     * @param walkingMinutes  from the previous stop (the first: 0, the route starts there)
     */
    public record PopularStop(
            String time,
            int durationMinutes,
            StopType type,
            String typeLabel,
            int walkingMinutes,
            int distanceMeters,
            PopularPlace place
    ) implements Serializable {
    }

    /**
     * The place fields a route card needs (details via /places/{id}).
     *
     * @param popularity Wikipedia-based popularity (popularity/PlacePopularity); null = unknown
     */
    public record PopularPlace(
            Long id,
            String name,
            PlaceCategory category,
            double latitude,
            double longitude,
            PlaceImage image,
            Integer estimatedCost,
            boolean verified,
            String district,
            Double popularity
    ) implements Serializable {
    }

    /**
     * @param city     city slug from GET /api/v1/cities
     * @param district district slug of that city (optional; the same as for the list the key came from)
     * @param key      PopularRouteResponse.key
     * @param date     null = today (Istanbul time)
     */
    public record PopularRouteStartRequest(
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String district,
            @NotBlank @Size(max = 60) String key,
            LocalDate date
    ) {
    }
}
