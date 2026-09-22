package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against a real database, that findLastActivityByUserIds' MAX(last_used_at) genuinely
 * picks the most recent session per user across several rows — a Mockito-based service test
 * cannot demonstrate this since a mock just returns whatever is configured.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = UserSession.class)
@EnableJpaRepositories(basePackageClasses = UserSessionRepository.class)
class UserSessionRepositoryStaffAdoptionTest {

    @Autowired private UserSessionRepository repository;

    @Test
    void selectsTheMostRecentSessionAcrossSeveralLoginsForTheSameUser() {
        session("T1", Instant.parse("2026-09-01T08:00:00Z"));
        session("T1", Instant.parse("2026-09-20T09:30:00Z"));
        session("T1", Instant.parse("2026-09-10T08:00:00Z"));

        List<UserSessionRepository.LastActivity> result =
                repository.findLastActivityByUserIds(List.of("T1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo("T1");
        assertThat(result.get(0).getLastActiveAt()).isEqualTo(Instant.parse("2026-09-20T09:30:00Z"));
    }

    @Test
    void aggregatesIndependentlyPerUserWithNoCrossContamination() {
        session("T1", Instant.parse("2026-09-20T08:00:00Z"));
        session("T2", Instant.parse("2026-09-21T08:00:00Z"));

        List<UserSessionRepository.LastActivity> result =
                repository.findLastActivityByUserIds(List.of("T1", "T2"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UserSessionRepository.LastActivity::getUserId)
                .containsExactlyInAnyOrder("T1", "T2");
    }

    @Test
    void userWithNoSessionsProducesNoResult() {
        session("T1", Instant.parse("2026-09-20T08:00:00Z"));

        List<UserSessionRepository.LastActivity> result =
                repository.findLastActivityByUserIds(List.of("T1", "T2"));

        assertThat(result).extracting(UserSessionRepository.LastActivity::getUserId)
                .containsExactly("T1");
    }

    private void session(String userId, Instant lastUsedAt) {
        UserSession s = new UserSession();
        s.setUserId(userId);
        s.setRefreshTokenHash("hash-" + userId + "-" + lastUsedAt.toEpochMilli());
        s.setCreatedAt(lastUsedAt);
        s.setLastUsedAt(lastUsedAt);
        s.setExpiresAt(lastUsedAt.plusSeconds(3600));
        repository.saveAndFlush(s);
    }
}
