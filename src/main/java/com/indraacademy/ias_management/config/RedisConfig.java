package com.indraacademy.ias_management.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * Default cache configuration:
     * - TTL: 30 minutes
     * - Keys serialized as plain strings
     * - Values serialized as JSON (so cached objects survive restarts and are readable)
     * - Null values not cached (avoids caching "not found" results)
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // Per-cache TTL overrides — some data changes more rarely than others
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                "fee-structures",   defaultConfig.entryTtl(Duration.ofHours(2)),
                "bus-fees",         defaultConfig.entryTtl(Duration.ofHours(2)),
                "exam-config",      defaultConfig.entryTtl(Duration.ofHours(2)),
                "subject-config",   defaultConfig.entryTtl(Duration.ofHours(2))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
