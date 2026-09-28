package com.nomi.wayfinder.photo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * user_photos, with JDBC like the other PostGIS-backed tables (districts, cities): the queries join places,
 * districts and cities and measure distances to district polygons.
 */
@Repository
public class UserPhotoRepository {

    private final JdbcTemplate jdbc;

    public UserPhotoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- rows ----------

    public record PhotoRow(long id, long userId, Long placeId, Long districtId, PhotoStatus status,
                           RejectReason rejectReason, String fileKey, int width, int height,
                           Double aiConfidence, Instant createdAt) {

        public PhotoTargetType targetType() {
            return placeId != null ? PhotoTargetType.PLACE : PhotoTargetType.DISTRICT;
        }
    }

    public record NewPhoto(long userId, Long placeId, Long districtId, PhotoStatus status, RejectReason rejectReason,
                           String fileKey, int width, int height, Instant takenAt, ProofSource proof,
                           Double distanceMeters, Instant createdAt) {
    }

    private static final RowMapper<PhotoRow> ROW = (rs, i) -> new PhotoRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getObject("place_id", Long.class),
            rs.getObject("district_id", Long.class),
            PhotoStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason") == null ? null : RejectReason.valueOf(rs.getString("reject_reason")),
            rs.getString("file_key"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getObject("ai_confidence", Double.class),
            rs.getTimestamp("created_at").toInstant());

    public long insert(NewPhoto photo) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        boolean reviewed = photo.status() != PhotoStatus.PENDING;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO user_photos (user_id, place_id, district_id, status, reject_reason, file_key,
                                             width, height, taken_at, proof, distance_meters, created_at, reviewed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, photo.userId());
            ps.setObject(2, photo.placeId());
            ps.setObject(3, photo.districtId());
            ps.setString(4, photo.status().name());
            ps.setString(5, photo.rejectReason() == null ? null : photo.rejectReason().name());
            ps.setString(6, photo.fileKey());
            ps.setInt(7, photo.width());
            ps.setInt(8, photo.height());
            ps.setTimestamp(9, photo.takenAt() == null ? null : Timestamp.from(photo.takenAt()));
            ps.setString(10, photo.proof() == null ? null : photo.proof().name());
            ps.setObject(11, photo.distanceMeters());
            ps.setTimestamp(12, Timestamp.from(photo.createdAt()));
            ps.setTimestamp(13, reviewed ? Timestamp.from(photo.createdAt()) : null);
            return ps;
        }, keys);
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    public Optional<PhotoRow> findById(long id) {
        return jdbc.query("SELECT * FROM user_photos WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Only while still PENDING: the retry job and the upload's own check may race. */
    public boolean markApproved(long id, double confidence, Instant now) {
        return jdbc.update("""
                UPDATE user_photos SET status = 'APPROVED', reject_reason = NULL, ai_confidence = ?, reviewed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, confidence, Timestamp.from(now), id) == 1;
    }

    public boolean markRejected(long id, RejectReason reason, Double confidence, Instant now) {
        return jdbc.update("""
                UPDATE user_photos SET status = 'REJECTED', reject_reason = ?, ai_confidence = ?, reviewed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, reason.name(), confidence, Timestamp.from(now), id) == 1;
    }

    /** Deletes all but the newest {@code keep} approved photos of the target; returns the deleted file keys. */
    public List<String> pruneApproved(PhotoTargetType type, long targetId, int keep) {
        String column = type == PhotoTargetType.PLACE ? "place_id" : "district_id";
        return jdbc.queryForList("""
                DELETE FROM user_photos WHERE id IN (
                    SELECT id FROM user_photos WHERE %s = ? AND status = 'APPROVED'
                    ORDER BY created_at DESC, id DESC OFFSET ?)
                RETURNING file_key
                """.formatted(column), String.class, targetId, keep)
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM user_photos WHERE id = ?", id);
    }

    public List<String> fileKeysOfUser(long userId) {
        return jdbc.queryForList("SELECT file_key FROM user_photos WHERE user_id = ? AND file_key IS NOT NULL",
                String.class, userId);
    }

    public Set<String> allFileKeys() {
        return new HashSet<>(jdbc.queryForList("SELECT file_key FROM user_photos WHERE file_key IS NOT NULL",
                String.class));
    }

    // ---------- jobs ----------

    public List<Long> pendingCreatedBefore(Instant before, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM user_photos WHERE status = 'PENDING' AND created_at < ?
                ORDER BY created_at LIMIT ?
                """, Long.class, Timestamp.from(before), limit);
    }

    public record StoredFile(long id, String fileKey) {
    }

    public List<StoredFile> rejectedWithFilesReviewedBefore(Instant before) {
        return jdbc.query("""
                SELECT id, file_key FROM user_photos
                WHERE status = 'REJECTED' AND file_key IS NOT NULL AND reviewed_at < ?
                """, (rs, i) -> new StoredFile(rs.getLong("id"), rs.getString("file_key")), Timestamp.from(before));
    }

    public void clearFileKey(long id) {
        jdbc.update("UPDATE user_photos SET file_key = NULL WHERE id = ?", id);
    }

    /** Rejected rows are kept for the user's list only this long; their files are already gone. */
    public List<String> deleteRejectedReviewedBefore(Instant before) {
        return jdbc.queryForList("""
                DELETE FROM user_photos WHERE status = 'REJECTED' AND reviewed_at < ? RETURNING file_key
                """, String.class, Timestamp.from(before)).stream().filter(java.util.Objects::nonNull).toList();
    }

    // ---------- collages ----------

    public record PublicPhoto(long id, String fileKey, int width, int height, Instant createdAt,
                              String displayName, Long placeId, String placeName) {
    }

    private static final RowMapper<PublicPhoto> PUBLIC = (rs, i) -> new PublicPhoto(
            rs.getLong("id"),
            rs.getString("file_key"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("display_name"),
            rs.getObject("place_id", Long.class),
            rs.getString("place_name"));

    public List<PublicPhoto> approvedOfPlace(long placeId, int limit) {
        return jdbc.query("""
                SELECT up.id, up.file_key, up.width, up.height, up.created_at, u.display_name,
                       up.place_id, NULL AS place_name
                FROM user_photos up JOIN users u ON u.id = up.user_id
                WHERE up.place_id = ? AND up.status = 'APPROVED' AND up.file_key IS NOT NULL
                ORDER BY up.created_at DESC, up.id DESC LIMIT ?
                """, PUBLIC, placeId, limit);
    }

    // The district's own photos and those of the places inside it, newest first
    public List<PublicPhoto> approvedOfDistrict(long districtId, int limit) {
        return jdbc.query("""
                SELECT up.id, up.file_key, up.width, up.height, up.created_at, u.display_name,
                       up.place_id, p.name AS place_name
                FROM user_photos up
                JOIN users u ON u.id = up.user_id
                LEFT JOIN places p ON p.id = up.place_id
                WHERE up.status = 'APPROVED' AND up.file_key IS NOT NULL
                  AND (up.district_id = ? OR p.district_id = ?)
                ORDER BY up.created_at DESC, up.id DESC LIMIT ?
                """, PUBLIC, districtId, districtId, limit);
    }

    // ---------- "my photos" ----------

    public record OwnPhoto(long id, String fileKey, PhotoStatus status, RejectReason rejectReason,
                           Double aiConfidence, Instant createdAt, Long placeId, String placeName,
                           String citySlug, String cityName, String districtSlug, String districtName,
                           boolean districtTarget) {
    }

    public List<OwnPhoto> ofUser(long userId, int limit) {
        return jdbc.query("""
                SELECT up.id, up.file_key, up.status, up.reject_reason, up.ai_confidence, up.created_at,
                       up.place_id, p.name AS place_name,
                       COALESCE(pc.slug, dc.slug) AS city_slug, COALESCE(pc.name, dc.name) AS city_name,
                       COALESCE(d.slug, pd.slug) AS district_slug, COALESCE(d.name, pd.name) AS district_name,
                       up.district_id IS NOT NULL AS district_target
                FROM user_photos up
                LEFT JOIN places p ON p.id = up.place_id
                LEFT JOIN cities pc ON pc.id = p.city_id
                LEFT JOIN districts pd ON pd.id = p.district_id
                LEFT JOIN districts d ON d.id = up.district_id
                LEFT JOIN cities dc ON dc.id = d.city_id
                WHERE up.user_id = ?
                ORDER BY up.created_at DESC, up.id DESC LIMIT ?
                """, (rs, i) -> new OwnPhoto(
                rs.getLong("id"),
                rs.getString("file_key"),
                PhotoStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason") == null ? null : RejectReason.valueOf(rs.getString("reject_reason")),
                rs.getObject("ai_confidence", Double.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("place_id", Long.class),
                rs.getString("place_name"),
                rs.getString("city_slug"),
                rs.getString("city_name"),
                rs.getString("district_slug"),
                rs.getString("district_name"),
                rs.getBoolean("district_target")), userId, limit);
    }

    // ---------- targets ----------

    /**
     * What the verification needs to know about a place.
     *
     * @param imageUrl the place's Wikimedia photo (reference for the AI check), null when it has none
     */
    public record PlaceTarget(long id, String name, String category, double latitude, double longitude,
                              String cityName, String districtName, String imageUrl) {
    }

    public Optional<PlaceTarget> findPlaceTarget(long placeId) {
        return jdbc.query("""
                SELECT p.id, p.name, p.category, ST_Y(p.location::geometry) AS lat, ST_X(p.location::geometry) AS lon,
                       c.name AS city_name, d.name AS district_name, p.image_url
                FROM places p
                LEFT JOIN cities c ON c.id = p.city_id
                LEFT JOIN districts d ON d.id = p.district_id
                WHERE p.id = ? AND NOT p.hidden
                """, (rs, i) -> new PlaceTarget(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getString("city_name"),
                rs.getString("district_name"),
                rs.getString("image_url")), placeId).stream().findFirst();
    }

    public record DistrictTarget(long id, String name, String cityName) {
    }

    public Optional<DistrictTarget> findDistrictTarget(long districtId) {
        return jdbc.query("""
                SELECT d.id, d.name, c.name AS city_name
                FROM districts d LEFT JOIN cities c ON c.id = d.city_id
                WHERE d.id = ?
                """, (rs, i) -> new DistrictTarget(rs.getLong("id"), rs.getString("name"), rs.getString("city_name")),
                districtId).stream().findFirst();
    }

    /** Meters from the point to the district polygon (0 inside); null when the district has no polygon. */
    public Double distanceToDistrict(long districtId, double latitude, double longitude) {
        return jdbc.query("""
                SELECT ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS meters
                FROM districts WHERE id = ? AND geom IS NOT NULL
                """, (rs, i) -> rs.getDouble("meters"), longitude, latitude, districtId)
                .stream().findFirst().orElse(null);
    }
}
