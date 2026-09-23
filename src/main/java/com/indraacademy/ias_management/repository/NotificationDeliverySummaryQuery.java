package com.indraacademy.ias_management.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Event-level (per notification) delivery summary for SUPER_ADMIN. One notifications row is one
 * publication (see NotificationPublicationTransaction.publishNew), so notifications.id is the
 * group. Pages over notifications, then aggregates in SQL — GROUP BY over only that page's ids —
 * so the database never ships individual recipient rows to Java. Read-only.
 */
@Repository
public class NotificationDeliverySummaryQuery {

    public record Filter(String eventCode, Long schoolId, LocalDateTime from, LocalDateTime to, String search) {}

    public record Group(long notificationId, Long schoolId, String eventCode, String title, String message,
                        LocalDateTime createdAt) {}

    public record InboxCounts(long stored, long opened) {}

    /** channel → status → count, for one notification. */
    public record DeliveryCounts(Map<String, Map<String, Long>> byChannel) {}

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeliverySummaryQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Group> groups(Filter filter, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        List<String> where = new ArrayList<>();
        if (filter.eventCode() != null) { where.add("n.event_code = :eventCode"); params.addValue("eventCode", filter.eventCode()); }
        if (filter.schoolId() != null) { where.add("n.school_id = :schoolId"); params.addValue("schoolId", filter.schoolId()); }
        if (filter.from() != null) { where.add("n.created_at >= :fromTs"); params.addValue("fromTs", Timestamp.valueOf(filter.from())); }
        if (filter.to() != null) { where.add("n.created_at < :toTs"); params.addValue("toTs", Timestamp.valueOf(filter.to())); }
        if (filter.search() != null) {
            where.add("(LOWER(n.title) LIKE :search ESCAPE '\\' OR LOWER(n.message) LIKE :search ESCAPE '\\')");
            params.addValue("search", "%" + escapeLike(filter.search().toLowerCase(Locale.ROOT)) + "%");
        }
        String sql = "SELECT n.id, n.school_id, n.event_code, n.title, n.message, n.created_at FROM notifications n "
                + (where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where))
                + " ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> {
            long schoolId = rs.getLong("school_id");
            Long school = rs.wasNull() ? null : schoolId;
            Timestamp created = rs.getTimestamp("created_at");
            return new Group(rs.getLong("id"), school, rs.getString("event_code"), rs.getString("title"),
                    rs.getString("message"), created == null ? null : created.toLocalDateTime());
        });
    }

    public Map<Long, InboxCounts> inboxCounts(Collection<Long> notificationIds) {
        if (notificationIds.isEmpty()) return Map.of();
        Map<Long, InboxCounts> result = new HashMap<>();
        jdbc.query("""
                SELECT u.notification_id AS notification_id, COUNT(*) AS stored,
                       SUM(CASE WHEN u.is_read THEN 1 ELSE 0 END) AS opened
                FROM user_notifications u
                WHERE u.notification_id IN (:ids)
                GROUP BY u.notification_id
                """, new MapSqlParameterSource("ids", notificationIds),
                rs -> { result.put(rs.getLong("notification_id"), new InboxCounts(rs.getLong("stored"), rs.getLong("opened"))); });
        return result;
    }

    /**
     * Delivery rows always carry their notification's school_id (NotificationPublicationTransaction),
     * so filtering on it lets the existing (school_id, notification_id) index serve this query.
     */
    public Map<Long, DeliveryCounts> deliveryCounts(Collection<Long> schoolIds, Collection<Long> notificationIds) {
        if (schoolIds.isEmpty() || notificationIds.isEmpty()) return Map.of();
        Map<Long, Map<String, Map<String, Long>>> raw = new HashMap<>();
        jdbc.query("""
                SELECT d.notification_id AS notification_id, CAST(d.channel AS VARCHAR(20)) AS channel,
                       CAST(d.status AS VARCHAR(30)) AS status, COUNT(*) AS total
                FROM notification_deliveries d
                WHERE d.school_id IN (:schoolIds) AND d.notification_id IN (:ids)
                GROUP BY d.notification_id, d.channel, d.status
                """, new MapSqlParameterSource().addValue("schoolIds", schoolIds).addValue("ids", notificationIds),
                rs -> {
                    raw.computeIfAbsent(rs.getLong("notification_id"), k -> new HashMap<>())
                            .computeIfAbsent(rs.getString("channel"), k -> new HashMap<>())
                            .merge(rs.getString("status"), rs.getLong("total"), Long::sum);
                });
        Map<Long, DeliveryCounts> result = new HashMap<>();
        raw.forEach((id, byChannel) -> result.put(id, new DeliveryCounts(byChannel)));
        return result;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
