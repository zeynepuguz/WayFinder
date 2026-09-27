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
     * Only the location is required; everything else falls back to the user's preferences
     * or sensible defaults (today, now, a full day template).
     */
    public record RoutePlanRequest(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            @Min(1) @Max(20) Integer partySize,
            @PositiveOrZero Integer budget,
            WalkingTolerance walkingTolerance,
            @Size(max = 10) List<StopType> stops,
            @Size(max = 20) List<@NotBlank String> interests,
            @Size(max = 255) String title
    ) {
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
            String interest
    ) {
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
            boolean indoor
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
            Instant createdAt
    ) {
    }

    public record ReplanResponse(RouteResponse route, List<String> changes) {
    }
}
