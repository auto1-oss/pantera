/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GroupSettings}.
 *
 * @since 1.18.0
 */
final class GroupSettingsTest {

    @Test
    void createsWithDefaultSettings() {
        final GroupSettings settings = GroupSettings.defaults();
        // Index TTLs
        MatcherAssert.assertThat(
            "Default remote_exists_ttl should be 15 minutes",
            settings.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(15))
        );
        MatcherAssert.assertThat(
            "Default remote_not_exists_ttl should be 5 minutes",
            settings.indexSettings().remoteNotExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(5))
        );
        MatcherAssert.assertThat(
            "Default local_event_driven should be true",
            settings.indexSettings().localEventDriven(),
            Matchers.is(true)
        );
        // Metadata cache settings
        MatcherAssert.assertThat(
            "Default metadata ttl should be 5 minutes",
            settings.metadataSettings().ttl(),
            Matchers.equalTo(Duration.ofMinutes(5))
        );
        MatcherAssert.assertThat(
            "Default stale_serve should be 1 hour",
            settings.metadataSettings().staleServe(),
            Matchers.equalTo(Duration.ofHours(1))
        );
        MatcherAssert.assertThat(
            "Default background_refresh_at should be 0.8",
            settings.metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(0.8)
        );
        // Resolution settings
        MatcherAssert.assertThat(
            "Default upstream_timeout should be 5 seconds",
            settings.resolutionSettings().upstreamTimeout(),
            Matchers.equalTo(Duration.ofSeconds(5))
        );
        MatcherAssert.assertThat(
            "Default max_parallel should be 10",
            settings.resolutionSettings().maxParallel(),
            Matchers.equalTo(10)
        );
        // Cache sizing
        MatcherAssert.assertThat(
            "Default l1_max_entries should be 10000",
            settings.cacheSizing().l1MaxEntries(),
            Matchers.equalTo(10_000)
        );
        MatcherAssert.assertThat(
            "Default l2_max_entries should be 1000000",
            settings.cacheSizing().l2MaxEntries(),
            Matchers.equalTo(1_000_000)
        );
    }

    @Test
    void parsesFromYaml() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "30m")
                    .add("remote_not_exists_ttl", "10m")
                    .add("local_event_driven", "false")
                    .build()
            )
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("ttl", "10m")
                    .add("stale_serve", "2h")
                    .add("background_refresh_at", "0.7")
                    .build()
            )
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("upstream_timeout", "10s")
                    .add("max_parallel", "20")
                    .build()
            )
            .add(
                "cache_sizing",
                Yaml.createYamlMappingBuilder()
                    .add("l1_max_entries", "5000")
                    .add("l2_max_entries", "500000")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        // Index TTLs
        MatcherAssert.assertThat(
            settings.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(30))
        );
        MatcherAssert.assertThat(
            settings.indexSettings().remoteNotExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(10))
        );
        MatcherAssert.assertThat(
            settings.indexSettings().localEventDriven(),
            Matchers.is(false)
        );
        // Metadata cache
        MatcherAssert.assertThat(
            settings.metadataSettings().ttl(),
            Matchers.equalTo(Duration.ofMinutes(10))
        );
        MatcherAssert.assertThat(
            settings.metadataSettings().staleServe(),
            Matchers.equalTo(Duration.ofHours(2))
        );
        MatcherAssert.assertThat(
            settings.metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(0.7)
        );
        // Resolution
        MatcherAssert.assertThat(
            settings.resolutionSettings().upstreamTimeout(),
            Matchers.equalTo(Duration.ofSeconds(10))
        );
        MatcherAssert.assertThat(
            settings.resolutionSettings().maxParallel(),
            Matchers.equalTo(20)
        );
        // Cache sizing
        MatcherAssert.assertThat(
            settings.cacheSizing().l1MaxEntries(),
            Matchers.equalTo(5_000)
        );
        MatcherAssert.assertThat(
            settings.cacheSizing().l2MaxEntries(),
            Matchers.equalTo(500_000)
        );
    }

    @Test
    void repoLevelOverridesGlobalSettings() {
        // Global settings
        final YamlMapping global = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "30m")
                    .add("remote_not_exists_ttl", "10m")
                    .build()
            )
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("ttl", "10m")
                    .build()
            )
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("upstream_timeout", "10s")
                    .build()
            )
            .build();
        // Repo-level overrides (only some settings)
        final YamlMapping repoLevel = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "1h")
                    .build()
            )
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("max_parallel", "5")
                    .build()
            )
            .build();
        final GroupSettings globalSettings = GroupSettings.from(global);
        final GroupSettings merged = globalSettings.merge(repoLevel);
        // Overridden values from repo-level
        MatcherAssert.assertThat(
            "remote_exists_ttl should be overridden to 1h",
            merged.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofHours(1))
        );
        MatcherAssert.assertThat(
            "max_parallel should be overridden to 5",
            merged.resolutionSettings().maxParallel(),
            Matchers.equalTo(5)
        );
        // Inherited values from global
        MatcherAssert.assertThat(
            "remote_not_exists_ttl should be inherited from global",
            merged.indexSettings().remoteNotExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(10))
        );
        MatcherAssert.assertThat(
            "metadata ttl should be inherited from global",
            merged.metadataSettings().ttl(),
            Matchers.equalTo(Duration.ofMinutes(10))
        );
        MatcherAssert.assertThat(
            "upstream_timeout should be inherited from global",
            merged.resolutionSettings().upstreamTimeout(),
            Matchers.equalTo(Duration.ofSeconds(10))
        );
        // Defaults for non-specified values
        MatcherAssert.assertThat(
            "local_event_driven should be default true",
            merged.indexSettings().localEventDriven(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "l1_max_entries should be default 10000",
            merged.cacheSizing().l1MaxEntries(),
            Matchers.equalTo(10_000)
        );
    }

    @Test
    void handlesNullYamlGracefully() {
        final GroupSettings settings = GroupSettings.from(null);
        MatcherAssert.assertThat(
            "Should return defaults for null yaml",
            settings.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(15))
        );
    }

    @Test
    void handlesEmptyYamlGracefully() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder().build();
        final GroupSettings settings = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "Should return defaults for empty yaml",
            settings.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(15))
        );
    }

    @Test
    void mergeWithNullReturnsThis() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "30m")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        final GroupSettings merged = settings.merge(null);
        MatcherAssert.assertThat(
            "Merge with null should return same values",
            merged.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(30))
        );
    }

    @Test
    void handlesInvalidDurationFormatGracefully() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "abc")
                    .add("remote_not_exists_ttl", "10x")
                    .build()
            )
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("upstream_timeout", "invalid")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        // Invalid formats should fall back to defaults
        MatcherAssert.assertThat(
            "Invalid 'abc' should use default 15 minutes",
            settings.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(15))
        );
        MatcherAssert.assertThat(
            "Invalid '10x' should use default 5 minutes",
            settings.indexSettings().remoteNotExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(5))
        );
        MatcherAssert.assertThat(
            "Invalid 'invalid' should use default 5 seconds",
            settings.resolutionSettings().upstreamTimeout(),
            Matchers.equalTo(Duration.ofSeconds(5))
        );
    }

    @Test
    void parsesIso8601DurationFormat() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "PT15M")
                    .add("remote_not_exists_ttl", "PT2H30M")
                    .build()
            )
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("ttl", "PT10M")
                    .add("stale_serve", "PT1H")
                    .build()
            )
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("upstream_timeout", "PT30S")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "ISO-8601 PT15M should parse to 15 minutes",
            settings.indexSettings().remoteExistsTtl(),
            Matchers.equalTo(Duration.ofMinutes(15))
        );
        MatcherAssert.assertThat(
            "ISO-8601 PT2H30M should parse to 2.5 hours",
            settings.indexSettings().remoteNotExistsTtl(),
            Matchers.equalTo(Duration.ofHours(2).plusMinutes(30))
        );
        MatcherAssert.assertThat(
            "ISO-8601 PT10M should parse to 10 minutes",
            settings.metadataSettings().ttl(),
            Matchers.equalTo(Duration.ofMinutes(10))
        );
        MatcherAssert.assertThat(
            "ISO-8601 PT1H should parse to 1 hour",
            settings.metadataSettings().staleServe(),
            Matchers.equalTo(Duration.ofHours(1))
        );
        MatcherAssert.assertThat(
            "ISO-8601 PT30S should parse to 30 seconds",
            settings.resolutionSettings().upstreamTimeout(),
            Matchers.equalTo(Duration.ofSeconds(30))
        );
    }

    @Test
    void handlesInvalidNumbersGracefully() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("max_parallel", "not_a_number")
                    .build()
            )
            .add(
                "cache_sizing",
                Yaml.createYamlMappingBuilder()
                    .add("l1_max_entries", "abc123")
                    .add("l2_max_entries", "12.5")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "Invalid 'not_a_number' should use default 10",
            settings.resolutionSettings().maxParallel(),
            Matchers.equalTo(10)
        );
        MatcherAssert.assertThat(
            "Invalid 'abc123' should use default 10000",
            settings.cacheSizing().l1MaxEntries(),
            Matchers.equalTo(10_000)
        );
        MatcherAssert.assertThat(
            "Invalid '12.5' (float) should use default 1000000",
            settings.cacheSizing().l2MaxEntries(),
            Matchers.equalTo(1_000_000)
        );
    }

    @Test
    void validatesBackgroundRefreshAtRange() {
        // Value too high (> 1.0)
        final YamlMapping yamlTooHigh = Yaml.createYamlMappingBuilder()
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("background_refresh_at", "1.5")
                    .build()
            )
            .build();
        final GroupSettings settingsTooHigh = GroupSettings.from(yamlTooHigh);
        MatcherAssert.assertThat(
            "Value 1.5 out of range should use default 0.8",
            settingsTooHigh.metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(0.8)
        );
        // Value too low (< 0.0)
        final YamlMapping yamlTooLow = Yaml.createYamlMappingBuilder()
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("background_refresh_at", "-0.5")
                    .build()
            )
            .build();
        final GroupSettings settingsTooLow = GroupSettings.from(yamlTooLow);
        MatcherAssert.assertThat(
            "Value -0.5 out of range should use default 0.8",
            settingsTooLow.metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(0.8)
        );
        // Valid boundary values
        final YamlMapping yamlZero = Yaml.createYamlMappingBuilder()
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("background_refresh_at", "0.0")
                    .build()
            )
            .build();
        MatcherAssert.assertThat(
            "Value 0.0 should be valid",
            GroupSettings.from(yamlZero).metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(0.0)
        );
        final YamlMapping yamlOne = Yaml.createYamlMappingBuilder()
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("background_refresh_at", "1.0")
                    .build()
            )
            .build();
        MatcherAssert.assertThat(
            "Value 1.0 should be valid",
            GroupSettings.from(yamlOne).metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(1.0)
        );
    }

    @Test
    void validatesPositiveIntegers() {
        // Zero max_parallel
        final YamlMapping yamlZero = Yaml.createYamlMappingBuilder()
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("max_parallel", "0")
                    .build()
            )
            .build();
        MatcherAssert.assertThat(
            "Zero max_parallel should use default 10",
            GroupSettings.from(yamlZero).resolutionSettings().maxParallel(),
            Matchers.equalTo(10)
        );
        // Negative l1_max_entries
        final YamlMapping yamlNegative = Yaml.createYamlMappingBuilder()
            .add(
                "cache_sizing",
                Yaml.createYamlMappingBuilder()
                    .add("l1_max_entries", "-100")
                    .build()
            )
            .build();
        MatcherAssert.assertThat(
            "Negative l1_max_entries should use default 10000",
            GroupSettings.from(yamlNegative).cacheSizing().l1MaxEntries(),
            Matchers.equalTo(10_000)
        );
    }

    @Test
    void handlesInvalidDoubleFormat() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("background_refresh_at", "not_a_double")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "Invalid double should use default 0.8",
            settings.metadataSettings().backgroundRefreshAt(),
            Matchers.equalTo(0.8)
        );
    }

    @Test
    void parsesDaysFormat() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "metadata",
                Yaml.createYamlMappingBuilder()
                    .add("stale_serve", "7d")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "7d should parse to 7 days",
            settings.metadataSettings().staleServe(),
            Matchers.equalTo(Duration.ofDays(7))
        );
    }

    @Test
    void parsesSecondsAsPlainNumber() {
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "resolution",
                Yaml.createYamlMappingBuilder()
                    .add("upstream_timeout", "30")
                    .build()
            )
            .build();
        final GroupSettings settings = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "Plain number 30 should parse as 30 seconds",
            settings.resolutionSettings().upstreamTimeout(),
            Matchers.equalTo(Duration.ofSeconds(30))
        );
    }

    @Test
    void equalsAndHashCodeWorkCorrectly() {
        final GroupSettings settings1 = GroupSettings.defaults();
        final GroupSettings settings2 = GroupSettings.defaults();
        MatcherAssert.assertThat(
            "Two default settings should be equal",
            settings1,
            Matchers.equalTo(settings2)
        );
        MatcherAssert.assertThat(
            "Two default settings should have same hashCode",
            settings1.hashCode(),
            Matchers.equalTo(settings2.hashCode())
        );
        // Different settings
        final YamlMapping yaml = Yaml.createYamlMappingBuilder()
            .add(
                "index",
                Yaml.createYamlMappingBuilder()
                    .add("remote_exists_ttl", "30m")
                    .build()
            )
            .build();
        final GroupSettings settings3 = GroupSettings.from(yaml);
        MatcherAssert.assertThat(
            "Different settings should not be equal",
            settings1,
            Matchers.not(Matchers.equalTo(settings3))
        );
    }

    @Test
    void innerClassesEqualsAndHashCodeWorkCorrectly() {
        final GroupSettings settings1 = GroupSettings.defaults();
        final GroupSettings settings2 = GroupSettings.defaults();
        // IndexSettings
        MatcherAssert.assertThat(
            "IndexSettings should be equal",
            settings1.indexSettings(),
            Matchers.equalTo(settings2.indexSettings())
        );
        MatcherAssert.assertThat(
            "IndexSettings hashCode should be equal",
            settings1.indexSettings().hashCode(),
            Matchers.equalTo(settings2.indexSettings().hashCode())
        );
        // MetadataSettings
        MatcherAssert.assertThat(
            "MetadataSettings should be equal",
            settings1.metadataSettings(),
            Matchers.equalTo(settings2.metadataSettings())
        );
        // ResolutionSettings
        MatcherAssert.assertThat(
            "ResolutionSettings should be equal",
            settings1.resolutionSettings(),
            Matchers.equalTo(settings2.resolutionSettings())
        );
        // CacheSizing
        MatcherAssert.assertThat(
            "CacheSizing should be equal",
            settings1.cacheSizing(),
            Matchers.equalTo(settings2.cacheSizing())
        );
    }
}
