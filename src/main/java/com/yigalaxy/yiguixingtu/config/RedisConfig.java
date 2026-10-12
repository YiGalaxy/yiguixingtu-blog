package com.yigalaxy.yiguixingtu.config;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Spring Cache backed by Redis with bounded TTLs and JSON values. */
@Configuration
@EnableCaching
public class RedisConfig {

    public static final String CACHE_ARTICLE_PAGE = "article:page";

    public static final String CACHE_ARTICLE_DETAIL = "article:detail";

    public static final String CACHE_ARTICLE_STATS = "article:stats";

    public static final String CACHE_TAG_LIST = "tag:list";

    public static final String CACHE_CATEGORY_LIST = "category:list";

    public static final String CACHE_ARTICLE_ARCHIVE = "article:archive";

    public static final String CACHE_ARTICLE_RSS = "article:rss";

    public static final String CACHE_FRIEND_LINK_LIST = "link:list";

    public static final String CACHE_PROJECT_LIST = "project:list";

    public static final String CACHE_FAVORITE_LIST = "favorite:list";

    public static final String CACHE_ABOUT = "about:detail";

    public static final String CACHE_MUSIC_LIST = "music:list";

    public static final String CACHE_SITE_SETTING = "setting:detail";

    private static final Duration TTL_STATS = Duration.ofSeconds(60);

    private static final Duration TTL_NORMAL = Duration.ofMinutes(5);

    private static final Duration TTL_EMPTY = Duration.ofSeconds(30);

    private static final int TTL_JITTER_MAX_SECONDS = 60;

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // Cache values include LocalDateTime, so register Jackson's Java time module.
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer()
                        .configure(mapper -> {
                            mapper.registerModule(new JavaTimeModule());
                            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                        });

        StringRedisSerializer keySerializer = new StringRedisSerializer();

        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                // Bump the prefix on incompatible cache schema changes.
                .computePrefixWith(cacheName -> cacheName + ":v1:")
                .entryTtl(this::resolveTtl)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(keySerializer))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        // Bounded per-cache lock prevents a crashed loader from blocking future misses indefinitely.
        // SCAN avoids blocking Redis with KEYS if a cache is explicitly cleared.
        RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(
                connectionFactory,
                Duration.ofMillis(50),
                RedisCacheWriter.TtlFunction.just(Duration.ofSeconds(10)),
                BatchStrategies.scan(1000));

        // View counts are persisted asynchronously, so statistics get a shorter TTL.
        RedisCacheConfiguration statsConfig = baseConfig
                .entryTtl(this::resolveStatsTtl);

        return RedisCacheManager.builder(cacheWriter)
                .cacheDefaults(baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_PAGE, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_DETAIL, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_STATS, statsConfig)
                .withCacheConfiguration(CACHE_TAG_LIST, baseConfig)
                .withCacheConfiguration(CACHE_CATEGORY_LIST, baseConfig)
                .withCacheConfiguration(CACHE_FRIEND_LINK_LIST, baseConfig)
                .withCacheConfiguration(CACHE_PROJECT_LIST, baseConfig)
                .withCacheConfiguration(CACHE_FAVORITE_LIST, baseConfig)
                .withCacheConfiguration(CACHE_ABOUT, baseConfig)
                .withCacheConfiguration(CACHE_MUSIC_LIST, baseConfig)
                .withCacheConfiguration(CACHE_SITE_SETTING, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_ARCHIVE, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_RSS, baseConfig)
                .build();
    }

    private Duration resolveStatsTtl(Object key, Object value) {
        return TTL_STATS.plusSeconds(ThreadLocalRandom.current().nextInt(10));
    }

    // Empty pages expire sooner; jitter spreads expirations across writes.
    private Duration resolveTtl(Object key, Object value) {
        int jitter = ThreadLocalRandom.current().nextInt(TTL_JITTER_MAX_SECONDS);

        if (value instanceof IPage<?> page) {
            boolean empty = page.getRecords() == null || page.getRecords().isEmpty();
            if (empty) {
                return TTL_EMPTY.plusSeconds(jitter % 10);
            }
        }
        return TTL_NORMAL.plusSeconds(jitter);
    }
}
