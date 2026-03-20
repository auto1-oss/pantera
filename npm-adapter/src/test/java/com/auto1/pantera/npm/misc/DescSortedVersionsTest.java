/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.misc;

import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test cases for {@link DescSortedVersions}.
 * @since 0.9
 */
final class DescSortedVersionsTest {

    @Test
    void sortsVersionsInDescendingOrder() {
        final JsonObject versions =
            Json.createObjectBuilder()
                .add("1", "")
                .add("2", "1.1")
                .add("3", "1.1.1")
                .add("4", "1.2.1")
                .add("5", "1.3.0")
                .build();
        MatcherAssert.assertThat(
            new DescSortedVersions(versions).value(),
            Matchers.contains("5", "4", "3", "2", "1")
        );
    }

    @Test
    void excludesPrereleaseVersionsWhenRequested() {
        final JsonObject versions =
            Json.createObjectBuilder()
                .add("0.25.5", Json.createObjectBuilder().build())
                .add("1.0.0-alpha.3", Json.createObjectBuilder().build())
                .add("0.23.0", Json.createObjectBuilder().build())
                .add("1.0.0-alpha.1", Json.createObjectBuilder().build())
                .add("0.25.4", Json.createObjectBuilder().build())
                .build();
        
        // With excludePrereleases = true, should only get stable versions
        MatcherAssert.assertThat(
            "Should exclude prerelease versions",
            new DescSortedVersions(versions, true).value(),
            Matchers.contains("0.25.5", "0.25.4", "0.23.0")
        );
    }

    @Test
    void selectsHighestStableVersionAsLatest() {
        final JsonObject versions =
            Json.createObjectBuilder()
                .add("0.25.5", Json.createObjectBuilder().build())
                .add("1.0.0-alpha.3", Json.createObjectBuilder().build())
                .add("0.23.0", Json.createObjectBuilder().build())
                .build();
        
        // Latest should be 0.25.5, NOT 1.0.0-alpha.3
        final String latest = new DescSortedVersions(versions, true).value().get(0);
        MatcherAssert.assertThat(
            "Latest stable should be 0.25.5, not prerelease 1.0.0-alpha.3",
            latest,
            Matchers.equalTo("0.25.5")
        );
    }

    @Test
    void includesPrereleaseVersionsWhenNotExcluded() {
        final JsonObject versions =
            Json.createObjectBuilder()
                .add("0.25.5", Json.createObjectBuilder().build())
                .add("1.0.0-alpha.3", Json.createObjectBuilder().build())
                .add("0.23.0", Json.createObjectBuilder().build())
                .build();
        
        // With excludePrereleases = false, should include all versions
        // Sorted: 1.0.0-alpha.3, 0.25.5, 0.23.0
        MatcherAssert.assertThat(
            "Should include all versions when not excluding prereleases",
            new DescSortedVersions(versions, false).value(),
            Matchers.contains("1.0.0-alpha.3", "0.25.5", "0.23.0")
        );
    }
}
