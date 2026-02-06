/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.timeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

final class TimeoutSettingsTest {

    @Test
    void usesDefaults() {
        final TimeoutSettings settings = TimeoutSettings.defaults();
        assertThat(settings.connectionTimeout(), equalTo(Duration.ofSeconds(5)));
        assertThat(settings.idleTimeout(), equalTo(Duration.ofSeconds(30)));
        assertThat(settings.requestTimeout(), equalTo(Duration.ofSeconds(120)));
    }

    @Test
    void overridesWithCustomValues() {
        final TimeoutSettings settings = new TimeoutSettings(
            Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(60)
        );
        assertThat(settings.connectionTimeout(), equalTo(Duration.ofSeconds(3)));
        assertThat(settings.idleTimeout(), equalTo(Duration.ofSeconds(15)));
        assertThat(settings.requestTimeout(), equalTo(Duration.ofSeconds(60)));
    }

    @Test
    void mergesWithParent() {
        final TimeoutSettings parent = new TimeoutSettings(
            Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(180)
        );
        final TimeoutSettings child = TimeoutSettings.builder()
            .connectionTimeout(Duration.ofSeconds(3))
            .buildWithParent(parent);
        assertThat(child.connectionTimeout(), equalTo(Duration.ofSeconds(3)));
        assertThat(
            "inherits idle from parent",
            child.idleTimeout(), equalTo(Duration.ofSeconds(60))
        );
        assertThat(
            "inherits request from parent",
            child.requestTimeout(), equalTo(Duration.ofSeconds(180))
        );
    }

    @Test
    void builderWithoutParentUsesDefaults() {
        final TimeoutSettings settings = TimeoutSettings.builder()
            .connectionTimeout(Duration.ofSeconds(2))
            .build();
        assertThat(settings.connectionTimeout(), equalTo(Duration.ofSeconds(2)));
        assertThat(settings.idleTimeout(), equalTo(Duration.ofSeconds(30)));
        assertThat(settings.requestTimeout(), equalTo(Duration.ofSeconds(120)));
    }
}
