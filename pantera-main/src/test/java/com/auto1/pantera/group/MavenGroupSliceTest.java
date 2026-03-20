/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link MavenGroupSlice}.
 */
public final class MavenGroupSliceTest {

    @Test
    void returnsMetadataFromFirstSuccessfulMember() throws Exception {
        // Setup: Two members, first returns 404, second returns metadata
        // Use unique path to avoid cache collision
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new StaticSlice(RsStatus.NOT_FOUND));
        members.put("repo2", new FakeMetadataSlice(
            "<?xml version=\"1.0\"?><metadata><groupId>com.test</groupId><artifactId>test</artifactId><versioning><versions><version>2.0</version></versions></versioning></metadata>"
        ));

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "test-group",
            List.of("repo1", "repo2"),
            new MapResolver(members),
            8080,
            0
        );

        final Response response = slice.response(
            new RequestLine("GET", "/com/test/unique1/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Response status is OK",
            response.status(),
            Matchers.equalTo(RsStatus.OK)
        );

        final String body = new String(response.body().asBytes(), StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "Metadata contains version 2.0",
            body,
            Matchers.containsString("<version>2.0</version>")
        );
    }

    @Test
    void returns404WhenNoMemberHasMetadata() throws Exception {
        // Use unique path to avoid cache collision
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new StaticSlice(RsStatus.NOT_FOUND));
        members.put("repo2", new StaticSlice(RsStatus.NOT_FOUND));

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "test-group",
            List.of("repo1", "repo2"),
            new MapResolver(members),
            8080,
            0
        );

        final Response response = slice.response(
            new RequestLine("GET", "/com/test/unique2/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Response status is NOT_FOUND",
            response.status(),
            Matchers.equalTo(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void cachesMetadataResults() throws Exception {
        // Use unique path to avoid cache collision with other tests
        final AtomicInteger callCount = new AtomicInteger(0);
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new CountingSlice(callCount, new FakeMetadataSlice(
            "<?xml version=\"1.0\"?><metadata><groupId>com.test</groupId><artifactId>test</artifactId><versioning><versions><version>1.0</version></versions></versioning></metadata>"
        )));

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "test-group",
            List.of("repo1"),
            new MapResolver(members),
            8080,
            0
        );

        // First request - use unique path
        slice.response(
            new RequestLine("GET", "/com/test/unique3/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "First request calls member",
            callCount.get(),
            Matchers.equalTo(1)
        );

        // Second request (should use cache)
        slice.response(
            new RequestLine("GET", "/com/test/unique3/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Second request uses cache (no additional call)",
            callCount.get(),
            Matchers.equalTo(1)
        );
    }

    @Test
    void rewritesPathWithMemberPrefix() throws Exception {
        // CRITICAL TEST: Verify that paths ARE prefixed with member name
        // This is required because member slices are wrapped in TrimPathSlice in production
        final AtomicReference<String> seenPath = new AtomicReference<>();
        final Slice recordingSlice = (line, headers, body) -> {
            seenPath.set(line.uri().getPath());
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "application/xml")
                    .body("<?xml version=\"1.0\"?><metadata></metadata>".getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        };

        final Map<String, Slice> members = new HashMap<>();
        members.put("plugins-snapshot", recordingSlice);

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "test-group",
            List.of("plugins-snapshot"),
            new MapResolver(members),
            8080,
            0
        );

        slice.response(
            new RequestLine("GET", "/org/sonarsource/scanner/maven/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Path is prefixed with member name (required for TrimPathSlice in production)",
            seenPath.get(),
            Matchers.equalTo("/plugins-snapshot/org/sonarsource/scanner/maven/maven-metadata.xml")
        );
    }

    @Test
    void delegatesNonMetadataRequestsToGroupSlice() throws Exception {
        final AtomicBoolean delegateCalled = new AtomicBoolean(false);
        final Slice delegate = (line, headers, body) -> {
            delegateCalled.set(true);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };

        final MavenGroupSlice slice = new MavenGroupSlice(
            delegate,
            "test-group",
            List.of("repo1"),
            new MapResolver(new HashMap<>()),
            8080,
            0
        );

        slice.response(
            new RequestLine("GET", "/com/test/test/1.0/test-1.0.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Non-metadata request delegated to GroupSlice",
            delegateCalled.get(),
            Matchers.is(true)
        );
    }

    @Test
    void handlesConcurrentMetadataRequests() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new FakeMetadataSlice(
            "<?xml version=\"1.0\"?><metadata><groupId>com.test</groupId><artifactId>test</artifactId><versioning><versions><version>1.0</version></versions></versioning></metadata>"
        ));

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "test-group",
            List.of("repo1"),
            new MapResolver(members),
            8080,
            0
        );

        // Fire 50 concurrent requests
        final List<CompletableFuture<Response>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(slice.response(
                new RequestLine("GET", "/com/test/test/maven-metadata.xml"),
                Headers.EMPTY,
                Content.EMPTY
            ));
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // Verify all succeeded
        for (CompletableFuture<Response> future : futures) {
            final Response response = future.get();
            MatcherAssert.assertThat(
                "All concurrent requests succeed",
                response.status(),
                Matchers.equalTo(RsStatus.OK)
            );
        }
    }

    @Test
    void supportsMavenPomRelocation() throws Exception {
        // Test scenario: Sonar plugin was relocated from org.codehaus.mojo to org.sonarsource.scanner.maven
        // This test verifies that both old and new coordinates work correctly through MavenGroupSlice

        final Map<String, Slice> members = new HashMap<>();
        members.put("maven-central", new RelocationAwareSlice());

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "test-group",
            List.of("maven-central"),
            new MapResolver(members),
            8080,
            0
        );

        // Step 1: Request old metadata (org.codehaus.mojo:sonar-maven-plugin)
        final Response oldMetadataResp = slice.response(
            new RequestLine("GET", "/org/codehaus/mojo/sonar-maven-plugin/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Old metadata request succeeds",
            oldMetadataResp.status(),
            Matchers.equalTo(RsStatus.OK)
        );

        final String oldMetadata = new String(
            oldMetadataResp.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Old metadata contains old groupId",
            oldMetadata,
            Matchers.containsString("<groupId>org.codehaus.mojo</groupId>")
        );

        // Step 2: Request old POM with relocation directive
        // Note: POM requests are NOT metadata, so they go through GroupSlice, not MavenGroupSlice
        // We need to test this through the member slice directly
        final Response oldPomResp = members.get("maven-central").response(
            new RequestLine("GET", "/org/codehaus/mojo/sonar-maven-plugin/4.0.0.4121/sonar-maven-plugin-4.0.0.4121.pom"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Old POM request succeeds",
            oldPomResp.status(),
            Matchers.equalTo(RsStatus.OK)
        );

        final String oldPom = new String(
            oldPomResp.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Old POM contains relocation directive",
            oldPom,
            Matchers.containsString("<relocation>")
        );
        MatcherAssert.assertThat(
            "Old POM contains new groupId in relocation",
            oldPom,
            Matchers.containsString("<groupId>org.sonarsource.scanner.maven</groupId>")
        );

        // Step 3: Request new metadata (org.sonarsource.scanner.maven:sonar-maven-plugin)
        // This simulates Maven client following the relocation
        final Response newMetadataResp = slice.response(
            new RequestLine("GET", "/org/sonarsource/scanner/maven/sonar-maven-plugin/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "New metadata request succeeds",
            newMetadataResp.status(),
            Matchers.equalTo(RsStatus.OK)
        );

        final String newMetadata = new String(
            newMetadataResp.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "New metadata contains new groupId",
            newMetadata,
            Matchers.containsString("<groupId>org.sonarsource.scanner.maven</groupId>")
        );

        // Step 4: Request new POM (no relocation)
        // Note: POM requests are NOT metadata, so they go through GroupSlice, not MavenGroupSlice
        // We need to test this through the member slice directly
        final Response newPomResp = members.get("maven-central").response(
            new RequestLine("GET", "/org/sonarsource/scanner/maven/sonar-maven-plugin/5.0.0/sonar-maven-plugin-5.0.0.pom"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "New POM request succeeds",
            newPomResp.status(),
            Matchers.equalTo(RsStatus.OK)
        );

        final String newPom = new String(
            newPomResp.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "New POM contains new groupId",
            newPom,
            Matchers.containsString("<groupId>org.sonarsource.scanner.maven</groupId>")
        );
        MatcherAssert.assertThat(
            "New POM does not contain relocation",
            newPom,
            Matchers.not(Matchers.containsString("<relocation>"))
        );
    }

    @Test
    void returnsStaleMetadataWhenAllMembersFailAndCacheExpired() throws Exception {
        // Pre-populate a GroupMetadataCache with stale data
        final GroupMetadataCache cache = new GroupMetadataCache("stale-test-group");
        final String staleMetadata = "<?xml version=\"1.0\"?><metadata>"
            + "<groupId>com.stale</groupId>"
            + "<artifactId>fallback</artifactId>"
            + "<versioning><versions>"
            + "<version>1.0-stale</version>"
            + "</versions></versioning></metadata>";
        final String stalePath = "/com/stale/fallback/maven-metadata.xml";
        // Directly put into cache (populates both L1 and last-known-good)
        cache.put(stalePath, staleMetadata.getBytes(StandardCharsets.UTF_8));
        // Invalidate L1/L2 to simulate cache expiry
        cache.invalidate(stalePath);

        // All members return 503 (upstream down)
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new StaticSlice(RsStatus.SERVICE_UNAVAILABLE));

        final MavenGroupSlice slice = new MavenGroupSlice(
            new FakeGroupSlice(),
            "stale-test-group",
            List.of("repo1"),
            new MapResolver(members),
            8080,
            0,
            cache
        );

        final Response response = slice.response(
            new RequestLine("GET", stalePath),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Stale metadata is returned when all members fail",
            response.status(),
            Matchers.equalTo(RsStatus.OK)
        );

        final String body = new String(
            response.body().asBytes(), StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Response contains stale version",
            body,
            Matchers.containsString("<version>1.0-stale</version>")
        );
    }

    // Helper classes

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;
        private MapResolver(Map<String, Slice> map) { this.map = map; }
        @Override
        public Slice slice(Key name, int port, int depth) {
            return map.get(name.string());
        }
    }

    private static final class StaticSlice implements Slice {
        private final RsStatus status;
        private StaticSlice(RsStatus status) { this.status = status; }
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.from(status).build()
            );
        }
    }

    private static final class FakeMetadataSlice implements Slice {
        private final String metadata;
        private FakeMetadataSlice(String metadata) { this.metadata = metadata; }
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.ok()
                    .header("Content-Type", "application/xml")
                    .body(metadata.getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        }
    }

    private static final class CountingSlice implements Slice {
        private final AtomicInteger counter;
        private final Slice delegate;
        private CountingSlice(AtomicInteger counter, Slice delegate) {
            this.counter = counter;
            this.delegate = delegate;
        }
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            counter.incrementAndGet();
            return delegate.response(line, headers, body);
        }
    }

    private static final class FakeGroupSlice implements Slice {
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.ok().build()
            );
        }
    }

    /**
     * Slice that simulates a Maven repository with POM relocation.
     * Serves both old coordinates (with relocation directive) and new coordinates.
     * Handles paths with or without member prefix (e.g., /maven-central/org/... or /org/...)
     */
    private static final class RelocationAwareSlice implements Slice {
        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return body.asBytesFuture().thenApply(ignored -> {
                String path = line.uri().getPath();

                // Strip member prefix if present (e.g., /maven-central/org/... → /org/...)
                if (path.startsWith("/maven-central/")) {
                    path = path.substring("/maven-central".length());
                }

                // Old metadata: org.codehaus.mojo:sonar-maven-plugin
                if (path.equals("/org/codehaus/mojo/sonar-maven-plugin/maven-metadata.xml")) {
                    return ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(oldMetadata().getBytes(StandardCharsets.UTF_8))
                        .build();
                }

                // Old POM with relocation directive
                if (path.equals("/org/codehaus/mojo/sonar-maven-plugin/4.0.0.4121/sonar-maven-plugin-4.0.0.4121.pom")) {
                    return ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(oldPomWithRelocation().getBytes(StandardCharsets.UTF_8))
                        .build();
                }

                // New metadata: org.sonarsource.scanner.maven:sonar-maven-plugin
                if (path.equals("/org/sonarsource/scanner/maven/sonar-maven-plugin/maven-metadata.xml")) {
                    return ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(newMetadata().getBytes(StandardCharsets.UTF_8))
                        .build();
                }

                // New POM (no relocation)
                if (path.equals("/org/sonarsource/scanner/maven/sonar-maven-plugin/5.0.0/sonar-maven-plugin-5.0.0.pom")) {
                    return ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(newPom().getBytes(StandardCharsets.UTF_8))
                        .build();
                }

                return ResponseBuilder.notFound().build();
            });
        }

        private static String oldMetadata() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<metadata>\n" +
                "  <groupId>org.codehaus.mojo</groupId>\n" +
                "  <artifactId>sonar-maven-plugin</artifactId>\n" +
                "  <versioning>\n" +
                "    <latest>4.0.0.4121</latest>\n" +
                "    <release>4.0.0.4121</release>\n" +
                "    <versions>\n" +
                "      <version>4.0.0.4121</version>\n" +
                "    </versions>\n" +
                "    <lastUpdated>20200101120000</lastUpdated>\n" +
                "  </versioning>\n" +
                "</metadata>";
        }

        private static String oldPomWithRelocation() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>org.codehaus.mojo</groupId>\n" +
                "  <artifactId>sonar-maven-plugin</artifactId>\n" +
                "  <version>4.0.0.4121</version>\n" +
                "  <distributionManagement>\n" +
                "    <relocation>\n" +
                "      <groupId>org.sonarsource.scanner.maven</groupId>\n" +
                "      <artifactId>sonar-maven-plugin</artifactId>\n" +
                "      <message>SonarQube plugin was moved to SonarSource organisation</message>\n" +
                "    </relocation>\n" +
                "  </distributionManagement>\n" +
                "</project>";
        }

        private static String newMetadata() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<metadata>\n" +
                "  <groupId>org.sonarsource.scanner.maven</groupId>\n" +
                "  <artifactId>sonar-maven-plugin</artifactId>\n" +
                "  <versioning>\n" +
                "    <latest>5.0.0</latest>\n" +
                "    <release>5.0.0</release>\n" +
                "    <versions>\n" +
                "      <version>5.0.0</version>\n" +
                "    </versions>\n" +
                "    <lastUpdated>20240101120000</lastUpdated>\n" +
                "  </versioning>\n" +
                "</metadata>";
        }

        private static String newPom() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>org.sonarsource.scanner.maven</groupId>\n" +
                "  <artifactId>sonar-maven-plugin</artifactId>\n" +
                "  <version>5.0.0</version>\n" +
                "  <name>SonarQube Scanner for Maven</name>\n" +
                "</project>";
        }
    }
}

