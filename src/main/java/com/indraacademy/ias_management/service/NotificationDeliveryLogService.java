package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.NotificationDeliveryLogDtos.*;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.NotificationDeliveryLogQuery;
import com.indraacademy.ias_management.repository.NotificationDeliverySummaryQuery;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Read-only SUPER_ADMIN visibility over persisted notification delivery state. */
@Service
public class NotificationDeliveryLogService {
    static final int MAX_PAGE_SIZE = 50;
    static final int LIST_ERROR_LENGTH = 300;
    static final int MESSAGE_PREVIEW_LENGTH = 160;
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,2})[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Set<String> STATUSES = Arrays.stream(NotificationDeliveryStatus.values())
            .map(Enum::name).collect(Collectors.toCollection(HashSet::new));
    static {
        STATUSES.add(NotificationDeliveryLogQuery.IN_APP_STORED);
    }

    private final NotificationDeliveryLogQuery query;
    private final NotificationDeliveryRepository deliveries;
    private final UserNotificationRepository inbox;
    private final SchoolRepository schools;
    private final UserRepository users;
    private final NotificationRetryPolicy retryPolicy;
    private final NotificationDeliverySummaryQuery summaryQuery;

    public NotificationDeliveryLogService(NotificationDeliveryLogQuery query, NotificationDeliveryRepository deliveries,
                                          UserNotificationRepository inbox, SchoolRepository schools,
                                          UserRepository users, NotificationRetryPolicy retryPolicy,
                                          NotificationDeliverySummaryQuery summaryQuery) {
        this.summaryQuery = summaryQuery;
        this.query = query;
        this.deliveries = deliveries;
        this.inbox = inbox;
        this.schools = schools;
        this.users = users;
        this.retryPolicy = retryPolicy;
    }

    public List<String> eventCodes() {
        return Arrays.stream(NotificationEventCode.values()).map(Enum::name).toList();
    }

    @Transactional(readOnly = true)
    public RowPage search(int page, int size, String status, String channel, String eventCode, Long schoolId,
                          String recipientUserId, LocalDate fromDate, LocalDate toDate, Long notificationId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        NotificationDeliveryLogQuery.Filter filter = new NotificationDeliveryLogQuery.Filter(
                oneOf(status, STATUSES, "status"),
                oneOf(channel, channels(), "channel"),
                oneOf(eventCode, new HashSet<>(eventCodes()), "eventCode"),
                schoolId,
                blankToNull(recipientUserId),
                fromDate == null ? null : fromDate.atStartOfDay(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(),
                notificationId);
        requireOrderedDates(fromDate, toDate);

        List<NotificationDeliveryLogQuery.Row> rows = query.search(filter, safeSize + 1, safePage * safeSize);
        boolean hasNext = rows.size() > safeSize;
        List<NotificationDeliveryLogQuery.Row> pageRows = hasNext ? rows.subList(0, safeSize) : rows;

        Map<Long, String> schoolNames = schoolNames(pageRows.stream().map(NotificationDeliveryLogQuery.Row::schoolId).collect(Collectors.toSet()));
        Map<String, String> roles = roles(pageRows.stream()
                .collect(Collectors.groupingBy(NotificationDeliveryLogQuery.Row::schoolId,
                        Collectors.mapping(NotificationDeliveryLogQuery.Row::recipientUserId, Collectors.toSet()))));

        List<Row> content = pageRows.stream().map(r -> new Row(r.rowId(), r.channel(), r.status(), r.schoolId(),
                schoolNames.get(r.schoolId()), r.recipientUserId(), roles.get(roleKey(r.schoolId(), r.recipientUserId())),
                r.notificationId(), r.eventCode(), r.title(), r.attemptCount(),
                truncate(maskEmails(r.lastError()), LIST_ERROR_LENGTH), r.providerMessageId(),
                r.createdAt(), r.sentAt(), r.nextAttemptAt(), r.read(), r.readAt())).toList();
        return new RowPage(content, safePage, safeSize, hasNext);
    }

    /** Event-level summary: one row per notification publication, every count aggregated in SQL. */
    @Transactional(readOnly = true)
    public SummaryPage summary(int page, int size, String eventCode, Long schoolId, LocalDate fromDate,
                               LocalDate toDate, String search) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        requireOrderedDates(fromDate, toDate);
        NotificationDeliverySummaryQuery.Filter filter = new NotificationDeliverySummaryQuery.Filter(
                oneOf(eventCode, new HashSet<>(eventCodes()), "eventCode"), schoolId,
                fromDate == null ? null : fromDate.atStartOfDay(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(),
                blankToNull(search));

        List<NotificationDeliverySummaryQuery.Group> groups = summaryQuery.groups(filter, safeSize + 1, safePage * safeSize);
        boolean hasNext = groups.size() > safeSize;
        List<NotificationDeliverySummaryQuery.Group> pageGroups = hasNext ? groups.subList(0, safeSize) : groups;

        List<Long> ids = pageGroups.stream().map(NotificationDeliverySummaryQuery.Group::notificationId).toList();
        Set<Long> schoolIds = pageGroups.stream().map(NotificationDeliverySummaryQuery.Group::schoolId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, NotificationDeliverySummaryQuery.InboxCounts> inboxCounts = summaryQuery.inboxCounts(ids);
        Map<Long, NotificationDeliverySummaryQuery.DeliveryCounts> deliveryCounts = summaryQuery.deliveryCounts(schoolIds, ids);
        Map<Long, String> schoolNames = schoolNames(schoolIds);

        List<SummaryRow> content = pageGroups.stream().map(g -> {
            NotificationDeliverySummaryQuery.InboxCounts inboxCount = inboxCounts.getOrDefault(g.notificationId(),
                    new NotificationDeliverySummaryQuery.InboxCounts(0, 0));
            Map<String, Map<String, Long>> byChannel = Optional.ofNullable(deliveryCounts.get(g.notificationId()))
                    .map(NotificationDeliverySummaryQuery.DeliveryCounts::byChannel).orElse(Map.of());
            return new SummaryRow(g.notificationId(), g.schoolId(), g.schoolId() == null ? null : schoolNames.get(g.schoolId()),
                    g.eventCode(), g.title(), truncate(g.message(), MESSAGE_PREVIEW_LENGTH), g.createdAt(),
                    inboxCount.stored(),
                    new InAppCounts(inboxCount.stored(), inboxCount.opened(), inboxCount.stored() - inboxCount.opened()),
                    channelCounts(byChannel.get(ExternalDeliveryChannel.PUSH.name())),
                    channelCounts(byChannel.get(ExternalDeliveryChannel.EMAIL.name())));
        }).toList();
        return new SummaryPage(content, safePage, safeSize, hasNext);
    }

    private static ChannelCounts channelCounts(Map<String, Long> byStatus) {
        if (byStatus == null || byStatus.isEmpty()) return null;
        long accepted = byStatus.getOrDefault(NotificationDeliveryStatus.SENT.name(), 0L);
        long failed = byStatus.getOrDefault(NotificationDeliveryStatus.FAILED_FINAL.name(), 0L);
        long retrying = byStatus.getOrDefault(NotificationDeliveryStatus.FAILED_RETRYABLE.name(), 0L);
        long skipped = byStatus.getOrDefault(NotificationDeliveryStatus.SKIPPED.name(), 0L);
        long queued = byStatus.getOrDefault(NotificationDeliveryStatus.PENDING.name(), 0L)
                + byStatus.getOrDefault(NotificationDeliveryStatus.PROCESSING.name(), 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new ChannelCounts(total, accepted, failed, retrying, skipped, queued);
    }

    private static void requireOrderedDates(LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        }
    }

    @Transactional(readOnly = true)
    public Detail detail(String channel, long id) {
        String safeChannel = oneOf(channel, channels(), "channel");
        if (safeChannel == null) throw notFound();
        if (NotificationDeliveryLogQuery.IN_APP.equals(safeChannel)) {
            UserNotification row = inbox.findById(id).orElseThrow(this::notFound);
            Notification n = row.getNotification();
            return new Detail(row.getId(), NotificationDeliveryLogQuery.IN_APP, NotificationDeliveryLogQuery.IN_APP_STORED,
                    row.getSchoolId(), schoolName(row.getSchoolId()), row.getUserId(), role(row.getSchoolId(), row.getUserId()),
                    null, n.getId(), name(n.getEventCode()), name(n.getCategory()), n.getTitle(), n.getMessage(),
                    n.getSourceEntityType(), n.getSourceEntityId(), 0, 0, null, null,
                    row.getCreatedAt(), null, null, null, row.getIsRead(), row.getReadAt(),
                    related(row, null));
        }
        NotificationDelivery d = deliveries.findById(id)
                .filter(row -> row.getChannel().name().equals(safeChannel))
                .orElseThrow(this::notFound);
        Notification n = d.getNotification();
        return new Detail(d.getId(), d.getChannel().name(), d.getStatus().name(), d.getSchoolId(),
                schoolName(d.getSchoolId()), d.getRecipientUserId(), role(d.getSchoolId(), d.getRecipientUserId()),
                d.getChannel() == ExternalDeliveryChannel.EMAIL ? maskEmail(d.getDestination()) : null,
                n.getId(), name(n.getEventCode()), name(n.getCategory()), n.getTitle(), n.getMessage(),
                n.getSourceEntityType(), n.getSourceEntityId(), d.getAttemptCount(), retryPolicy.maxAttempts(),
                maskEmails(d.getLastError()), d.getProviderMessageId(), d.getCreatedAt(), d.getSentAt(),
                d.getNextAttemptAt(), d.getProcessingStartedAt(), null, null,
                related(d.getUserNotification(), d.getId()));
    }

    private List<RelatedChannel> related(UserNotification inboxRow, Long excludeDeliveryId) {
        List<RelatedChannel> result = new ArrayList<>();
        if (excludeDeliveryId != null) {
            result.add(new RelatedChannel(inboxRow.getId(), NotificationDeliveryLogQuery.IN_APP,
                    NotificationDeliveryLogQuery.IN_APP_STORED, 0, null, null, inboxRow.getIsRead(), inboxRow.getReadAt()));
        }
        deliveries.findByUserNotificationId(inboxRow.getId()).stream()
                .filter(d -> !d.getId().equals(excludeDeliveryId))
                .sorted(Comparator.comparing(d -> d.getChannel().name()))
                .forEach(d -> result.add(new RelatedChannel(d.getId(), d.getChannel().name(), d.getStatus().name(),
                        d.getAttemptCount(), d.getSentAt(), truncate(maskEmails(d.getLastError()), LIST_ERROR_LENGTH),
                        null, null)));
        return result;
    }

    private Map<Long, String> schoolNames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> names = new HashMap<>();
        schools.findAllById(ids).forEach(s -> names.put(s.getId(), s.getName()));
        return names;
    }

    private Map<String, String> roles(Map<Long, Set<String>> userIdsBySchool) {
        Map<String, String> roles = new HashMap<>();
        userIdsBySchool.forEach((schoolId, userIds) -> users.findBySchoolIdAndUserIdIn(schoolId, userIds)
                .forEach(u -> roles.put(roleKey(schoolId, u.getUserId()), u.getRole())));
        return roles;
    }

    private String schoolName(Long schoolId) {
        return schoolId == null ? null : schools.findById(schoolId).map(School::getName).orElse(null);
    }

    private String role(Long schoolId, String userId) {
        if (schoolId == null || userId == null) return null;
        return users.findBySchoolIdAndUserIdIn(schoolId, List.of(userId)).stream()
                .findFirst().map(User::getRole).orElse(null);
    }

    private static String roleKey(long schoolId, String userId) {
        return schoolId + ":" + userId;
    }

    private static Set<String> channels() {
        Set<String> values = Arrays.stream(ExternalDeliveryChannel.values()).map(Enum::name)
                .collect(Collectors.toCollection(HashSet::new));
        values.add(NotificationDeliveryLogQuery.IN_APP);
        return values;
    }

    private static String oneOf(String value, Set<String> allowed, String field) {
        String v = blankToNull(value);
        if (v == null) return null;
        if (!allowed.contains(v)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown " + field + ".");
        return v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** "priya@example.com" → "pr***@example.com". */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    /** Provider errors (e.g. SMTP rejections) can quote the recipient address — mask any email in them. */
    static String maskEmails(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("$1***@$2");
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery record not found.");
    }
}
