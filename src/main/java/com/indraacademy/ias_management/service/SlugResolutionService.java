package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.repository.SchoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Resolves a school slug to its corresponding schoolId.
 * Results are cached in Redis ("slug-school-id") to avoid a DB hit on every request.
 * Only active schools are resolved — inactive schools return null.
 */
@Service
public class SlugResolutionService {

    private static final Logger log = LoggerFactory.getLogger(SlugResolutionService.class);

    @Autowired
    private SchoolRepository schoolRepository;

    /**
     * Resolves a URL slug to a schoolId.
     * Returns null if the slug is unknown or the school is inactive.
     * Null results are NOT cached (RedisConfig disableCachingNullValues),
     * so deactivated schools are re-checked on the next request.
     */
    @Cacheable(value = "slug-school-id", key = "#slug")
    public Long resolveSlugToSchoolId(String slug) {
        log.debug("Resolving slug '{}' from DB", slug);
        return schoolRepository.findBySlug(slug)
                .filter(school -> school.isActive())
                .map(school -> school.getId())
                .orElse(null);
    }

    /**
     * Evicts the cached mapping for a slug.
     * Call this when a school's slug changes or when a school is deactivated.
     */
    @CacheEvict(value = "slug-school-id", key = "#slug")
    public void evictSlug(String slug) {
        log.debug("Evicted slug-school-id cache for slug '{}'", slug);
    }

    /**
     * Evicts all slug-to-school-id cache entries.
     * Use this as a safe fallback when bulk changes are made.
     */
    @CacheEvict(value = "slug-school-id", allEntries = true)
    public void evictAll() {
        log.debug("Evicted all slug-school-id cache entries");
    }
}
