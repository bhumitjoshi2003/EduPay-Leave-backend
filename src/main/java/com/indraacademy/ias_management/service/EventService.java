package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Event;
import com.indraacademy.ias_management.repository.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public Event createEvent(Event event, String authorizationHeader) {
        if (event == null) {
            log.warn("Attempted to create a null event object.");
            throw new IllegalArgumentException("Event object must not be null.");
        }
        if (event.getEndDate() != null && event.getStartDate() != null && event.getEndDate().isBefore(event.getStartDate())) {
            log.error("Validation failed: End date {} is before start date {}", event.getEndDate(), event.getStartDate());
            throw new IllegalArgumentException("End date cannot be before start date.");
        }

        log.info("Creating new event: {}", event.getTitle());

        try {
            String userId = authService.getUserIdFromToken(authorizationHeader);
            if (userId == null) {
                log.warn("User ID could not be extracted from token during event creation.");
                // Decide whether to throw an exception or allow creation without 'createdBy'
            }
            event.setCreatedBy(userId);

            // Use LocalDateTime.now() once and set LocalDate fields from it
            LocalDateTime now = LocalDateTime.now();
            event.setCreatedAt(now.toLocalDate());
            event.setUpdatedAt(now.toLocalDate());

            Event savedEvent = eventRepository.save(event);
            log.info("Event created successfully with ID: {}", savedEvent.getId());
            return savedEvent;
        } catch (DataAccessException e) {
            log.error("Data access error during event creation for event: {}", event.getTitle(), e);
            throw new RuntimeException("Could not create event due to data access issue", e);
        } catch (Exception e) {
            log.error("Unexpected error during event creation for event: {}", event.getTitle(), e);
            throw new RuntimeException("Unexpected error during event creation", e);
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

    public Event updateEvent(Long id, Event eventDetails, String authorizationHeader) {
        if (id == null) {
            log.error("Cannot update event: ID is null.");
            throw new IllegalArgumentException("Event ID must not be null.");
        }
        if (eventDetails == null) {
            log.error("Cannot update event ID {}: Event details object is null.", id);
            throw new IllegalArgumentException("Event details must not be null.");
        }
        if (eventDetails.getEndDate() != null && eventDetails.getStartDate() != null && eventDetails.getEndDate().isBefore(eventDetails.getStartDate())) {
            log.error("Validation failed: End date {} is before start date {} for event ID {}", eventDetails.getEndDate(), eventDetails.getStartDate(), id);
            throw new IllegalArgumentException("End date cannot be before start date.");
        }

        log.info("Attempting to update event with ID: {}", id);

        try {
            String userId = authService.getUserIdFromToken(authorizationHeader);
            if (userId == null) {
                log.warn("User ID could not be extracted from token during event update for ID: {}.", id);
            }

            return eventRepository.findById(id).map(event -> {
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

                // Only update 'createdBy' if the original service intended to overwrite it.
                // A better approach is usually to set 'updatedBy' and 'updatedAt'.
                event.setCreatedBy(userId);
                event.setUpdatedAt(LocalDateTime.now().toLocalDate());

                Event updatedEvent = eventRepository.save(event);
                log.info("Event updated successfully with ID: {}", id);
                return updatedEvent;
            }).orElseThrow(() -> {
                log.error("Event not found with ID: {}", id);
                return new IllegalArgumentException("Event not found with ID: " + id);
            });
        } catch (DataAccessException e) {
            log.error("Data access error during event update for ID: {}", id, e);
            throw new RuntimeException("Could not update event due to data access issue", e);
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