package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.RouteStatus;
import com.nomi.wayfinder.entity.StopStatus;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class RouteDtos {

    private RouteDtos() {
    }

    /**
     * Where the route starts: the user's position (latitude / longitude, startMode LOCATION) or a chosen city /
     * district (city / district slugs, startMode AREA: its most popular sight, else its centre). startMode defaults
     * to LOCATION when a position is given, else AREA. Everything else falls back to the user's preferences or
     * sensible defaults (today, now, a full day template).
     */
    public record RoutePlanRequest(
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            @Min(1) @Max(20) Integer partySize,
            @PositiveOrZero Integer budget,
            WalkingTolerance walkingTolerance,
            @Size(max = 10) List<StopType> stops,
            @Size(max = 20) List<@NotBlank String> interests,
            @Size(max = 255) String title,
            // City slug ("istanbul"); with district: a district slug of that city ("kadikoy")
            @Size(max = 100) String city,
            @Size(max = 100) String district,
            StartMode startMode
    ) {

        public RoutePlanRequest(Double latitude, Double longitude, LocalDate date, LocalTime startTime,
                                LocalTime endTime, Integer partySize, Integer budget,
                                WalkingTolerance walkingTolerance, List<StopType> stops, List<String> interests,
                                String title) {
            this(latitude, longitude, date, startTime, endTime, partySize, budget, walkingTolerance, stops, interests,
                    title, null, null, null);
        }
    }

    public enum StartMode {
        // At the user's position
        LOCATION,
        // At the chosen city / district's best starting point
        AREA
    }

    public record ReplanRequest(
            @NotNull ReplanType type,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            // REMOVE_STOP / REPLACE_STOP
            Long stopId,
            // ADD_STOP
            StopType stopType,
            // ADD_INTEREST
            String interest,
            // REPLACE_STOP (optional): what the new place should be like, in the user's words ("kebap", "daha ucuz")
            @Size(max = 200) String wish
    ) {

        public ReplanRequest(ReplanType type, Double latitude, Double longitude, Long stopId, StopType stopType,
                             String interest) {
            this(type, latitude, longitude, stopId, stopType, interest, null);
        }
    }

    public record RouteUpdateRequest(
            RouteStatus status,
            Boolean saved,
            @Size(min = 1, max = 255) String title
    ) {
    }

    public record StopStatusRequest(@NotNull StopStatus status) {
    }

    public record RouteResponse(
            Long id,
            String title,
            LocalDate date,
            RouteStatus status,
            boolean saved,
            double startLatitude,
            double startLongitude,
            // "Konumun" / "Your location", the start sight's name or "Kadıköy merkezi"
            String startLabel,
            LocalTime startTime,
            LocalTime endTime,
            int partySize,
            Integer budget,
            int totalEstimatedCost,
            int totalWalkingMeters,
            int totalWalkingMinutes,
            WalkingTolerance walkingTolerance,
            List<String> interests,
            WeatherSnapshot weather,
            List<String> notes,
            List<StopResponse> stops,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record WeatherSnapshot(String condition, Double temperature, String advice) {
    }

    public record StopResponse(
            Long id,
            int position,
            StopType type,
            String typeLabel,
            LocalTime plannedStart,
            LocalTime plannedEnd,
            int distanceFromPreviousMeters,
            int walkingMinutes,
            List<String> reasons,
            StopStatus status,
            StopPlace place
    ) {
    }

    // The place fields a route screen needs (full details via /places/{id})
    public record StopPlace(
            Long id,
            String name,
            PlaceCategory category,
            String address,
            String neighborhood,
            double latitude,
            double longitude,
            Integer estimatedCost,
            Double rating,
            boolean indoor,
            // Wikimedia Commons photo with attribution; null = no image
            PlaceImage image
    ) {
    }

    public record RouteSummary(
            Long id,
            String title,
            LocalDate date,
            RouteStatus status,
            boolean saved,
            int stopCount,
            int totalEstimatedCost,
            Instant createdAt,
            // false = no stop has a known price: show "no price info", not "~0 TL"
            boolean costKnown
    ) {
    }

    public record ReplanResponse(RouteResponse route, List<String> changes) {
    }
}
