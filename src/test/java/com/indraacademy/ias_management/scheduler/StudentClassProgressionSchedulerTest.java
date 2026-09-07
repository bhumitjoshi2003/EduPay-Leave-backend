package com.indraacademy.ias_management.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class StudentClassProgressionSchedulerTest {
    @Test void retiredSchedulerHasNoScheduleAndNoRepositoryDependencies() {
        assertThat(Arrays.stream(StudentClassProgressionScheduler.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Scheduled.class))).allMatch(a -> a == null);
        assertThat(Arrays.stream(StudentClassProgressionScheduler.class.getDeclaredFields())
                .map(Field::getType).map(Class::getSimpleName))
                .noneMatch(name -> name.endsWith("Repository"));
        assertThat(Arrays.stream(StudentClassProgressionScheduler.class.getDeclaredMethods())
                .map(Method::getName)).doesNotContain("incrementStudentClasses");
    }
}
