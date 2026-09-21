package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.Event;
import com.indraacademy.ias_management.repository.EventRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Only covers getEventById/getEventsForMonthAndYear's object-storage imageUrl resolution —
 * resolveImageUrl's pre-existing legacy http(s)/api-prefix normalization is unchanged (see its
 * own doc comment); this proves an object-storage key passes through that normalization
 * untouched and is then swapped for a fresh presigned GET URL, while a legacy /uploads/... value
 * is left completely alone.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Long SCHOOL_ID = 1L;

    @Mock private EventRepository eventRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private BusinessNotificationService businessNotifications;
    @Mock private HttpServletRequest request;

    private EventService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new EventService();
        ReflectionTestUtils.setField(service, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);

        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        lenient().when(eventRepository.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            if (e.getId() == null) e.setId(99L);
            return e;
        });
    }

    private Event event(Long id, String imageUrl) {
        Event e = new Event();
        e.setId(id);
        e.setSchoolId(SCHOOL_ID);
        e.setImageUrl(imageUrl);
        return e;
    }

    @Test
    void getEventById_objectStorageKeyImage_resolvedToFreshPresignedUrl() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(event(5L, "schools/1/events/5/images/uuid.jpg")));
        when(objectStorageService.resolveDisplayUrl("schools/1/events/5/images/uuid.jpg"))
                .thenReturn("https://storage.example/signed-get-url");

        Optional<Event> result = service.getEventById(5L);

        assertThat(result).isPresent();
        assertThat(result.get().getImageUrl()).isEqualTo("https://storage.example/signed-get-url");
    }

    @Test
    void getEventById_legacyLocalDiskImage_leftCompletelyUntouched() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(event(5L, "/uploads/events/images/legacy.jpg")));
        when(objectStorageService.resolveDisplayUrl("/uploads/events/images/legacy.jpg"))
                .thenReturn("/uploads/events/images/legacy.jpg");

        Optional<Event> result = service.getEventById(5L);

        assertThat(result.get().getImageUrl()).isEqualTo("/uploads/events/images/legacy.jpg");
    }

    @Test
    void getEventById_noImage_resolvesToNullWithoutError() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(event(5L, null)));

        Optional<Event> result = service.getEventById(5L);

        assertThat(result.get().getImageUrl()).isNull();
    }

    @Test
    void getEventsForMonthAndYear_resolvesImageUrlForEveryEventInList() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        Event withKey = event(1L, "schools/1/events/1/images/a.jpg");
        Event legacy = event(2L, "/uploads/events/images/b.jpg");
        when(eventRepository.findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of(withKey, legacy));
        when(objectStorageService.resolveDisplayUrl("schools/1/events/1/images/a.jpg")).thenReturn("https://storage.example/a");
        when(objectStorageService.resolveDisplayUrl("/uploads/events/images/b.jpg")).thenReturn("/uploads/events/images/b.jpg");

        List<Event> results = service.getEventsForMonthAndYear(2026, 1);

        assertThat(results.get(0).getImageUrl()).isEqualTo("https://storage.example/a");
        assertThat(results.get(1).getImageUrl()).isEqualTo("/uploads/events/images/b.jpg");
    }

    // ─── Presigned-URL-never-persisted guard (createEvent / updateEvent) ────────────────────

    private Event newEventPayload(String imageUrl) {
        Event e = new Event();
        e.setTitle("Sports Day");
        e.setDescription("Annual sports day");
        e.setStartDate(LocalDate.of(2026, 3, 1));
        e.setCategory("GENERAL");
        e.setTargetAudience(List.of("ALL"));
        e.setImageUrl(imageUrl);
        return e;
    }

    @Test
    void createEvent_objectStorageKey_persistedAsIs() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        Event saved = service.createEvent(newEventPayload("schools/1/events/new/images/uuid.jpg"), request);

        assertThat(saved.getImageUrl()).isEqualTo("schools/1/events/new/images/uuid.jpg");
    }

    @Test
    void createEvent_presignedLookingUrl_neverPersisted() {
        // A brand-new event has nothing to fall back to — the absolute URL is simply dropped
        // rather than ever reaching the DB.
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        Event saved = service.createEvent(newEventPayload("https://storage.example/bucket/schools/1/events/new/images/uuid.jpg?X-Amz-Signature=abc"), request);

        assertThat(saved.getImageUrl()).isNull();
    }

    @Test
    void createEvent_nullImage_persistedAsNull() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        Event saved = service.createEvent(newEventPayload(null), request);

        assertThat(saved.getImageUrl()).isNull();
    }

    @Test
    void updateEvent_newObjectStorageKey_persisted() {
        Event existing = event(5L, "schools/1/events/5/images/old.jpg");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(existing));

        Event updated = service.updateEvent(5L, newEventPayload("schools/1/events/5/images/new.jpg"), request);

        assertThat(updated.getImageUrl()).isEqualTo("schools/1/events/5/images/new.jpg");
    }

    @Test
    void updateEvent_presignedLookingUrl_fallsBackToExistingStoredValue_neverPersistsDisplayUrl() {
        // Simulates the exact bug: the edit form was populated from a GET response whose
        // imageUrl had already been resolved to a presigned display URL (see getEventById), and
        // the user saved without touching the photo. The stable stored key must survive.
        Event existing = event(5L, "schools/1/events/5/images/old.jpg");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(existing));

        Event updated = service.updateEvent(5L,
                newEventPayload("https://storage.example/bucket/schools/1/events/5/images/old.jpg?X-Amz-Signature=abc"), request);

        assertThat(updated.getImageUrl()).isEqualTo("schools/1/events/5/images/old.jpg");
    }

    @Test
    void updateEvent_explicitNull_removesImage() {
        Event existing = event(5L, "schools/1/events/5/images/old.jpg");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(existing));

        Event updated = service.updateEvent(5L, newEventPayload(null), request);

        assertThat(updated.getImageUrl()).isNull();
    }

    @Test
    void updateEvent_legacyLocalDiskPath_persistedAsIs() {
        Event existing = event(5L, "/uploads/events/images/old.jpg");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(existing));

        Event updated = service.updateEvent(5L, newEventPayload("/uploads/events/images/new.jpg"), request);

        assertThat(updated.getImageUrl()).isEqualTo("/uploads/events/images/new.jpg");
    }
}
