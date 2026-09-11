package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.ClockConfig;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportRecord;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportSummary;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the reviewed Gita corpus artifact (src/main/resources/wisdom/gita-besant-1922-4th-edition.json)
 * imports cleanly, completely, and idempotently through the real {@link WisdomVerseImportService}
 * against a real disposable PostgreSQL database -- the same disposable instance every other
 * Wisdom Postgres IT test uses, never a shared/persistent one.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WisdomVerseImportService.class, GitaCorpusImportIT.Config.class})
@EnabledIfEnvironmentVariable(named = "WISDOM_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55439/wisdom_verify")
class GitaCorpusImportIT {

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-13T18:30:00Z"), ZoneOffset.UTC); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("WISDOM_TEST_DB_URL");
        if (!"jdbc:postgresql://127.0.0.1:55439/wisdom_verify".equals(url)) throw new IllegalStateException("Disposable database required");
        Flyway before = Flyway.configure().dataSource(url, "wisdom_test", "").target("58").load();
        before.migrate();
        Flyway after = Flyway.configure().dataSource(url, "wisdom_test", "").load();
        after.migrate();
        after.validate();
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> "wisdom_test");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WisdomVerseImportService importService;
    @Autowired ObjectMapper mapper;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;

    List<VerseImportRecord> artifact;

    @BeforeEach void loadArtifactAndActAsSuperAdmin() throws IOException {
        when(security.getRole()).thenReturn("SUPER_ADMIN");
        byte[] json = new ClassPathResource("wisdom/gita-besant-1922-4th-edition.json").getInputStream().readAllBytes();
        artifact = List.of(mapper.readValue(json, VerseImportRecord[].class));
    }

    @Test void artifactParsesAndHasTheExpectedShapeAndSize() {
        assertThat(artifact).isNotEmpty();
        assertThat(artifact).allSatisfy(r -> {
            assertThat(r.chapter()).isBetween(1, 18);
            assertThat(r.verse()).isGreaterThan(0);
            assertThat(r.sanskrit()).isNotBlank();
            assertThat(r.translation()).isNotBlank();
            assertThat(r.sourceName()).isNotBlank();
            assertThat(r.sourceUrl()).isNotBlank();
            assertThat(r.license()).isNotBlank();
            assertThat(r.sourceVersion()).isNotBlank();
            assertThat(r.verifiedBy()).isNotBlank();
        });
    }

    @Test void firstImportAcceptsEveryArtifactRecordWithZeroUnexpectedRejections() {
        VerseImportSummary result = importService.importVerified(artifact);
        assertThat(result.imported()).hasSize(artifact.size());
        assertThat(result.rejected()).isEmpty();
        assertThat(result.skippedExisting()).isEmpty();
        Integer dbCount = jdbc.queryForObject(
            "select count(*) from wisdom_verse where source_name=? and source_version=?",
            Integer.class, artifact.get(0).sourceName(), artifact.get(0).sourceVersion());
        assertThat(dbCount).isEqualTo(artifact.size());
    }

    @Test void secondIdenticalImportIsFullyIdempotent_zeroNewRecords() {
        VerseImportSummary first = importService.importVerified(artifact);
        assertThat(first.imported()).hasSize(artifact.size());

        VerseImportSummary second = importService.importVerified(artifact);
        assertThat(second.imported()).isEmpty();
        assertThat(second.skippedExisting()).hasSize(artifact.size());
        assertThat(second.rejected()).isEmpty();

        Integer dbCount = jdbc.queryForObject(
            "select count(*) from wisdom_verse where source_name=? and source_version=?",
            Integer.class, artifact.get(0).sourceName(), artifact.get(0).sourceVersion());
        assertThat(dbCount).isEqualTo(artifact.size());
    }

    @Test void resultingChapterDistributionExactlyMatchesTheArtifact() {
        importService.importVerified(artifact);
        Map<Integer, Long> expected = artifact.stream()
            .collect(java.util.stream.Collectors.groupingBy(VerseImportRecord::chapter, java.util.stream.Collectors.counting()));
        for (var entry : expected.entrySet()) {
            Integer dbCount = jdbc.queryForObject(
                "select count(*) from wisdom_verse where chapter=? and source_name=?",
                Integer.class, entry.getKey(), artifact.get(0).sourceName());
            assertThat(dbCount).as("chapter %d", entry.getKey()).isEqualTo(entry.getValue().intValue());
        }
    }

    @Test void noDuplicateChapterVerseAfterImport() {
        importService.importVerified(artifact);
        Integer dupes = jdbc.queryForObject(
            "select count(*) from (select chapter,verse from wisdom_verse where source_name=? group by chapter,verse having count(*)>1) d",
            Integer.class, artifact.get(0).sourceName());
        assertThat(dupes).isEqualTo(0);
    }

    @Test void transliterationIsDerivedMechanically_notTrustedFromArtifact() {
        importService.importVerified(artifact.subList(0, 1));
        VerseImportRecord first = artifact.get(0);
        String stored = jdbc.queryForObject(
            "select transliteration from wisdom_verse where source_name=? and chapter=? and verse=?",
            String.class, first.sourceName(), first.chapter(), first.verse());
        assertThat(stored).isEqualTo(com.indraacademy.ias_management.util.DevanagariTransliterator.toIast(first.sanskrit()));
    }

    @Test void noVedabaseOrBbtProvenanceAnywhereInTheArtifact() {
        for (VerseImportRecord r : artifact) {
            String combined = (r.sanskrit() + r.translation() + r.sourceName() + r.sourceUrl() + r.verifiedBy() + r.license()).toLowerCase();
            assertThat(combined).doesNotContain("prabhupada", "bhaktivedanta", "iskcon", "vedabase", "bbt");
        }
    }
}
