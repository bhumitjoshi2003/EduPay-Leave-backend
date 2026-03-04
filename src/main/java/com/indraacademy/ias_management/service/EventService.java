package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Event;
import com.indraacademy.ias_management.repository.EventRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    @Autowired private EventRepository eventRepository;
    @Autowired private AuthService authService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    public Event createEvent(Event event, String authorizationHeader, HttpServletRequest request) {

        if (event == null) {
            throw new IllegalArgumentException("Event object must not be null.");
        }

        if (event.getEndDate() != null && event.getStartDate() != null &&
                event.getEndDate().isBefore(event.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }

        try {
            String userId = authService.getUserIdFromToken(authorizationHeader);
            event.setCreatedBy(userId);

            LocalDateTime now = LocalDateTime.now();
            event.setCreatedAt(now.toLocalDate());
            event.setUpdatedAt(now.toLocalDate());

            Event savedEvent = eventRepository.save(event);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_EVENT",
                    "Event",
                    savedEvent.getId().toString(),
                    null,
                    objectMapper.writeValueAsString(savedEvent),
                    request.getRemoteAddr()
            );

            return savedEvent;

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not create event", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<Event> getEventById(Long id) {
        if (id == null) {
            log.warn("Attempted to get event with null ID.");
            return Optional.empty();
        }
        log.info("Fetching event by ID: {}", id);
        try {
            Optional<Event> event = eventRepository.findById(id);
            if (event.isPresent()) {
                log.info("Event found with ID: {}", id);
            } else {
                log.warn("Event not found with ID: {}", id);
            }
            return event;
        } catch (DataAccessException e) {
            log.error("Data access error fetching event ID: {}", id, e);
            throw new RuntimeException("Could not retrieve event due to data access issue", e);
        }
    }

    public List<Event> getEventsForMonthAndYear(int year, int month) {
        if (month < 1 || month > 12) {
            log.error("Invalid month value: {}", month);
            return Collections.emptyList();
        }
        log.info("Fetching events for year: {} and month: {}", year, month);

        try {
            LocalDate firstDayOfMonth = LocalDate.of(year, month, 1);
            LocalDate lastDayOfMonth = firstDayOfMonth.withDayOfMonth(firstDayOfMonth.lengthOfMonth());

            List<Event> events = eventRepository.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(lastDayOfMonth, firstDayOfMonth);
            log.info("Found {} events for year: {} and month: {}", events.size(), year, month);
            return events;
        } catch (DataAccessException e) {
            log.error("Data access error fetching events for year: {} month: {}", year, month, e);
            throw new RuntimeException("Could not retrieve events due to data access issue", e);
        } catch (Exception e) {
            // Catches potential DateTimeException from LocalDate.of
            log.error("Error generating date range for year: {} month: {}", year, month, e);
            return Collections.emptyList();
        }
    }

    public Event updateEvent(Long id,
                             Event eventDetails,
                             String authorizationHeader,
                             HttpServletRequest request) {

        if (id == null || eventDetails == null) {
            throw new IllegalArgumentException("Invalid event update request.");
        }

        if (eventDetails.getEndDate() != null && eventDetails.getStartDate() != null &&
                eventDetails.getEndDate().isBefore(eventDetails.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }

        try {
            String userId = authService.getUserIdFromToken(authorizationHeader);

            Event event = eventRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Event not found with ID: " + id));

            String oldValue = objectMapper.writeValueAsString(event);

            event.setTitle(eventDetails.getTitle());
            event.setDescription(eventDetails.getDescription());
            event.setStartDate(eventDetails.getStartDate());
            event.setEndDate(eventDetails.getEndDate());
            event.setStartTime(eventDetails.getStartTime());
            event.setEndTime(eventDetails.getEndTime());
            event.setLocation(eventDetails.getLocation());
            event.setCategory(eventDetails.getCategory());
            event.setTargetAudience(eventDetails.getTargetAudience());
            event.setVideoLinks(eventDetails.getVideoLinks());
            event.setImageUrl(eventDetails.getImageUrl());
            event.setCreatedBy(userId);
            event.setUpdatedAt(LocalDateTime.now().toLocalDate());

            Event updatedEvent = eventRepository.save(event);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_EVENT",
                    "Event",
                    id.toString(),
                    oldValue,
                    objectMapper.writeValueAsString(updatedEvent),
                    request.getRemoteAddr()
            );

            return updatedEvent;

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not update event", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event for audit log", e);
        }
    }

    public void deleteEvent(Long id) {
        if (id == null) {
            log.warn("Attempted to delete event with null ID.");
            return;
        }
        log.info("Attempting to delete event with ID: {}", id);
        try {
            if (!eventRepository.existsById(id)) {
                log.error("Event not found for deletion with ID: {}", id);
                throw new IllegalArgumentException("Event not found with ID: " + id);
            }
            eventRepository.deleteById(id);
            log.info("Event deleted successfully with ID: {}", id);
        } catch (DataAccessException e) {
            log.error("Data access error deleting event ID: {}", id, e);
            throw new RuntimeException("Could not delete event due to data access issue", e);
        }
    }
}