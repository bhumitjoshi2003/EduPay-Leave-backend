package com.indraacademy.ias_management.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs once on every startup, after Hibernate's own ddl-auto=update has already
 * created/updated every JPA-mapped table (ApplicationRunner beans run after the
 * context — and therefore the EntityManagerFactory — is fully initialized).
 * Enables pgvector and adds the one column ddl-auto can't create on its own: a
 * custom `vector` type isn't something Hibernate knows how to generate DDL for
 * — see KnowledgeSearchRepository's class comment for why that column is
 * deliberately left unmapped by JPA.
 *
 * Deliberately NOT wired through Spring Boot's generic spring.sql.init.mode +
 * schema.sql mechanism: this project already ships a data.sql full of local dev
 * seed data (test students/teachers/fees) that must never run against production,
 * and spring.sql.init.mode is a single global switch — turning it on for this
 * migration would also turn data.sql on. This runner stays fully separate from that.
 *
 * Both statements are idempotent (IF NOT EXISTS) — safe to run on every startup.
 */
@Component
public class KnowledgeBaseSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute("ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS embedding vector(1536)");
            log.info("Knowledge Base: pgvector extension and embedding column verified.");
        } catch (Exception e) {
            // Don't crash the whole app over this — every other feature should keep working;
            // Knowledge Base upload/search will just fail with a clear error until this is
            // resolved (most likely cause: DB user lacks privilege to CREATE EXTENSION).
            log.error("Knowledge Base: could not verify pgvector extension/embedding column. " +
                    "The Knowledge Base feature will not work until this is resolved.", e);
        }
    }
}
