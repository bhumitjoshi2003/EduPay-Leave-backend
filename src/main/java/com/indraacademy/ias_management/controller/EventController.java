package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Event;
import com.indraacademy.ias_management.service.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "http://localhost:4200")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    @Autowired private EventService eventService;

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> createEvent(@RequestBody Event event, @RequestHeader(name = "Authorization") String authorizationHeader) {
        log.info("Request to create new event: {}", event.getTitle());
        try {
            Event createdEvent = eventService.createEvent(event, authorizationHeader);
            log.info("Event created successfully with ID: {}", createdEvent.getId());
            return new ResponseEntity<>(createdEvent, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.error("Bad request to create event: {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error creating event.", e);
            return new ResponseEntity<>("Failed to create event due to internal error.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEventById(@PathVariable Long id) {
        log.info("Request to get event by ID: {}", id);
        Optional<Event> event = eventService.getEventById(id);

        return event.map(value -> new ResponseEntity<>(value, HttpStatus.OK))
                .orElseGet(() -> {
                    log.warn("Event with ID {} not found.", id);
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                });
    }

    @GetMapping("/month/{month}/year/{year}")
    public ResponseEntity<List<Event>> getAllEvents(
            @PathVariable Integer month,
            @PathVariable Integer year) {

        log.info("Request to get events for month {} and year {}", month, year);

        if (year == null || month == null) {
            log.warn("Missing year or month for event retrieval.");
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        List<Event> events;
        try {
            events = eventService.getEventsForMonthAndYear(year, month);
        } catch (IllegalArgumentException e) {
            log.error("Invalid month/year for event retrieval: {}", e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>(events, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<Event> updateEvent(@PathVariable Long id, @RequestBody Event eventDetails,  @RequestHeader(name = "Authorization") String authorizationHeader) {
        log.info("Request to update event with ID: {}", id);
        try {
            Event updatedEvent = eventService.updateEvent(id, eventDetails, authorizationHeader);
            log.info("Event updated successfully with ID: {}", id);
            return new ResponseEntity<>(updatedEvent, HttpStatus.OK);
        } catch (NoSuchElementException e) {
            log.error("Event with ID {} not found for update.", id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException e) {
            log.error("Invalid data for event update (ID: {}): {}", id, e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id) {
        log.warn("Request to delete event with ID: {}", id);
        try {
            eventService.deleteEvent(id);
            log.info("Event deleted successfully with ID: {}", id);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (NoSuchElementException e) {
            log.error("Event with ID {} not found for deletion.", id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}