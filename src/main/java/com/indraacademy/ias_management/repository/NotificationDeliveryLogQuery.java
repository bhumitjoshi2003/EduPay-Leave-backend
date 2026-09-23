package com.indraacademy.ias_management.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only, cross-school view over what the notification pipeline already persists:
 * notification_deliveries (PUSH/EMAIL outbox rows) plus user_notifications (the IN_APP inbox
 * row, which has no delivery row of its own). SUPER_ADMIN-only — see
 * NotificationDeliveryLogController. SQL is assembled only from fixed clauses for the filters
 * actually supplied; every value is a bound parameter.
 */
@Repository
public class NotificationDeliveryLogQuery {

    public static final String IN_APP = "IN_APP";
    /** Synthetic status for an IN_APP row: the inbox row exists. Not a delivery/read claim. */
    public static final String IN_APP_STORED = "STORED";

    public record Filter(String status, String channel, String eventCode, Long schoolId,
                         String recipientUserId, LocalDateTime from, LocalDateTime to, Long notificationId) {
        public Filter(String status, String channel, String eventCode, Long schoolId,
                      String recipientUserId, LocalDateTime from, LocalDateTime to) {
            this(status, channel, eventCode, schoolId, recipientUserId, from, to, null);
        }
    }

    public record Row(long rowId, String channel, String status, long schoolId, String recipientUserId,
                      long notificationId, String eventCode, String title, int attemptCount,
                      String lastError, String providerMessageId, LocalDateTime createdAt,
                      LocalDateTime sentAt, LocalDateTime nextAttemptAt, Boolean read, LocalDateTime readAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeliveryLogQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> search(Filter filter, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        List<String> branches = new ArrayList<>(2);

        boolean channelIsInApp = IN_APP.equals(filter.channel());
        boolean statusIsInApp = IN_APP_STORED.equals(filter.status());

        if (!channelIsInApp && !statusIsInApp) {
            List<String> where = new ArrayList<>();
            if (filter.channel() != null) { where.add("d.channel = :channel"); params.addValue("channel", filter.channel()); }
            if (filter.status() != null) { where.add("d.status = :status"); params.addValue("status", filter.status()); }
            addCommon(where, params, filter, "d.school_id", "d.recipient_user_id", "d.created_at");
            branches.add("""
                    SELECT d.id AS row_id, CAST(d.channel AS VARCHAR(20)) AS channel, CAST(d.status AS VARCHAR(30)) AS status,
                           d.school_id AS school_id,
                           d.recipient_user_id AS recipient_user_id, n.id AS notification_id,
                           n.event_code AS event_code, n.title AS title, d.attempt_count AS attempt_count,
                           d.last_error AS last_error, d.provider_message_id AS provider_message_id,
                           d.created_at AS created_at, d.sent_at AS sent_at, d.next_attempt_at AS next_attempt_at,
                           CAST(NULL AS BOOLEAN) AS is_read, CAST(NULL AS TIMESTAMP) AS read_at
                    FROM notification_deliveries d
                    JOIN notifications n ON n.id = d.notification_id
                    """ + whereClause(where));
        }

        boolean channelAllowsInApp = filter.channel() == null || channelIsInApp;
        boolean statusAllowsInApp = filter.status() == null || statusIsInApp;
        if (channelAllowsInApp && statusAllowsInApp) {
            List<String> where = new ArrayList<>();
            addCommon(where, params, filter, "u.school_id", "u.user_id", "u.created_at");
            branches.add("""
                    SELECT u.id AS row_id, 'IN_APP' AS channel, 'STORED' AS status, u.school_id AS school_id,
                           u.user_id AS recipient_user_id, n.id AS notification_id,
                           n.event_code AS event_code, n.title AS title, 0 AS attempt_count,
                           CAST(NULL AS VARCHAR(2000)) AS last_error, CAST(NULL AS VARCHAR(255)) AS provider_message_id,
                           u.created_at AS created_at, CAST(NULL AS TIMESTAMP) AS sent_at,
                           CAST(NULL AS TIMESTAMP) AS next_attempt_at, u.is_read AS is_read, u.read_at AS read_at
                    FROM user_notifications u
                    JOIN notifications n ON n.id = u.notification_id
                    """ + whereClause(where));
        }

        if (branches.isEmpty()) return List.of();
        String sql = String.join("\nUNION ALL\n", branches)
                + "\nORDER BY created_at DESC, row_id DESC, channel\nLIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> map(rs));
    }

    private void addCommon(List<String> where, MapSqlParameterSource params, Filter filter,
                           String schoolCol, String recipientCol, String createdCol) {
        if (filter.notificationId() != null) { where.add("n.id = :notificationId"); params.addValue("notificationId", filter.notificationId()); }
        if (filter.eventCode() != null) { where.add("n.event_code = :eventCode"); params.addValue("eventCode", filter.eventCode()); }
        if (filter.schoolId() != null) { where.add(schoolCol + " = :schoolId"); params.addValue("schoolId", filter.schoolId()); }
        if (filter.recipientUserId() != null) { where.add(recipientCol + " = :recipient"); params.addValue("recipient", filter.recipientUserId()); }
        if (filter.from() != null) { where.add(createdCol + " >= :fromTs"); params.addValue("fromTs", Timestamp.valueOf(filter.from())); }
        if (filter.to() != null) { where.add(createdCol + " < :toTs"); params.addValue("toTs", Timestamp.valueOf(filter.to())); }
    }

    private String whereClause(List<String> where) {
        return where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
    }

    private Row map(ResultSet rs) throws SQLException {
        Boolean read = rs.getObject("is_read") == null ? null : rs.getBoolean("is_read");
        return new Row(rs.getLong("row_id"), rs.getString("channel"), rs.getString("status"),
                rs.getLong("school_id"), rs.getString("recipient_user_id"),
                rs.getLong("notification_id"), rs.getString("event_code"), rs.getString("title"),
                rs.getInt("attempt_count"), rs.getString("last_error"), rs.getString("provider_message_id"),
                ts(rs, "created_at"), ts(rs, "sent_at"), ts(rs, "next_attempt_at"), read, ts(rs, "read_at"));
    }

    private LocalDateTime ts(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
