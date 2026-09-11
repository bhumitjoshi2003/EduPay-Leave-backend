package com.indraacademy.ias_management.dto;
import jakarta.validation.constraints.*;
import java.time.*;
public class WisdomDtos {
 public record ThoughtInput(@NotBlank @Size(max=300) String body, @NotBlank String audience, boolean active, long version) {}
 public record OverrideInput(@NotNull Long thoughtId, @NotNull LocalDate displayDate, @NotBlank String audience) {}
 public record ThoughtView(LocalDate date, String body, String audience, boolean overridden) {}
 public record Dashboard(ThoughtView thought, LocalDate today, String timezone) {}
 public record Management(LocalDate today, String timezone) {}
}
