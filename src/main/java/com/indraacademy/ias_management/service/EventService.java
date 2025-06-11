package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Event;
import com.indraacademy.ias_management.repository.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    @Autowired private EventRepository eventRepository;
    @Autowired private AuthService authService;

    public Event createEvent(Event event, String authorizationHeader) {
        if (event.getEndDate() != null && event.getStartDate() != null && event.getEndDate().isBefore(event.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }

        event.setCreatedBy(authService.getUserIdFromToken(authorizationHeader));

        event.setCreatedAt(LocalDate.from(LocalDateTime.now()));
        event.setUpdatedAt(LocalDate.from(LocalDateTime.now()));

        return eventRepository.save(event);
    }

    public Optional<Event> getEventById(Long id) {
        return eventRepository.findById(id);
    }

    public List<Event> getEventsForMonthAndYear(int year, int month) {
        LocalDate firstDayOfMonth = LocalDate.of(year, month, 1);
        LocalDate lastDayOfMonth = firstDayOfMonth.withDayOfMonth(firstDayOfMonth.lengthOfMonth());
        return eventRepository.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(lastDayOfMonth, firstDayOfMonth);
    }

    public Event updateEvent(Long id, Event eventDetails, String authorizationHeader) {
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
            event.setCreatedBy(authService.getUserIdFromToken(authorizationHeader));
            return eventRepository.save(event);
        }).orElseThrow(() -> new IllegalArgumentException("Event not found with ID: " + id));
    }

    public void deleteEvent(Long id) {
        if (!eventRepository.existsById(id)) {
            throw new IllegalArgumentException("Event not found with ID: " + id);
        }
        eventRepository.deleteById(id);
    }
}