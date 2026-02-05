/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.cache.GroupSettings;
import com.artipie.cache.MetadataMerger;
import com.artipie.cache.UnifiedGroupCache;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Integration tests for metadata merging in adapter-specific group slices.
 * Tests verify end-to-end behavior of NpmGroupSlice, GoGroupSlice, and PypiGroupSlice
 * correctly merging metadata from multiple group members.
 *
 * <p>These tests use test implementations of MetadataMerger that replicate
 * the behavior of the actual adapter mergers, allowing us to test the
 * group slice integration without direct adapter dependencies.
 *
 * @since 1.18.0
 */
public final class MetadataMergingIT {

    // ==================== NPM Tests ====================

    /**
     * Test that NpmGroupSlice merges package versions from multiple members.
     * Member 1 has versions 4.17.20 and 4.17.21
     * Member 2 has versions 4.17.21 and 4.17.22
     * Result should contain all three unique versions.
     */
    @Test
    void npmMergesVersionsFromMultipleMembers() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: lodash with versions 4.17.20, 4.17.21
        members.put("npm-local", createNpmMemberSlice(
            "lodash",
            "{\"name\":\"lodash\",\"versions\":{" +
                "\"4.17.20\":{\"version\":\"4.17.20\",\"name\":\"lodash\"}," +
                "\"4.17.21\":{\"version\":\"4.17.21\",\"name\":\"lodash\"}" +
            "},\"dist-tags\":{\"latest\":\"4.17.21\"}}"
        ));
        // Member 2: lodash with versions 4.17.21, 4.17.22
        members.put("npm-proxy", createNpmMemberSlice(
            "lodash",
            "{\"name\":\"lodash\",\"versions\":{" +
                "\"4.17.21\":{\"version\":\"4.17.21\",\"name\":\"lodash\"}," +
                "\"4.17.22\":{\"version\":\"4.17.22\",\"name\":\"lodash\"}" +
            "},\"dist-tags\":{\"latest\":\"4.17.22\"}}"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "npm-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final NpmGroupSlice slice = new NpmGroupSlice(
            new MapResolver(members),
            "npm-group",
            List.of("npm-local", "npm-proxy"),
            8080,
            cache,
            new TestNpmMetadataMerger()
        );

        // Request package metadata
        final Response response = slice.response(
            new RequestLine("GET", "/lodash"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        // Verify all three versions are present
        MatcherAssert.assertThat(
            "Merged response should contain version 4.17.20",
            body,
            Matchers.containsString("4.17.20")
        );
        MatcherAssert.assertThat(
            "Merged response should contain version 4.17.21",
            body,
            Matchers.containsString("4.17.21")
        );
        MatcherAssert.assertThat(
            "Merged response should contain version 4.17.22",
            body,
            Matchers.containsString("4.17.22")
        );
        // Verify priority member's dist-tags win (npm-local is first)
        MatcherAssert.assertThat(
            "Priority member's latest tag should win (4.17.21 from npm-local)",
            body,
            Matchers.containsString("\"latest\":\"4.17.21\"")
        );
    }

    /**
     * Test that NpmGroupSlice handles scoped packages correctly.
     */
    @Test
    void npmMergesScopedPackageVersions() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: @types/node with versions 18.0.0
        members.put("npm-local", createNpmMemberSlice(
            "@types/node",
            "{\"name\":\"@types/node\",\"versions\":{" +
                "\"18.0.0\":{\"version\":\"18.0.0\",\"name\":\"@types/node\"}" +
            "},\"dist-tags\":{\"latest\":\"18.0.0\"}}"
        ));
        // Member 2: @types/node with versions 18.0.0, 20.0.0
        members.put("npm-proxy", createNpmMemberSlice(
            "@types/node",
            "{\"name\":\"@types/node\",\"versions\":{" +
                "\"18.0.0\":{\"version\":\"18.0.0\",\"name\":\"@types/node\"}," +
                "\"20.0.0\":{\"version\":\"20.0.0\",\"name\":\"@types/node\"}" +
            "},\"dist-tags\":{\"latest\":\"20.0.0\"}}"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "npm-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final NpmGroupSlice slice = new NpmGroupSlice(
            new MapResolver(members),
            "npm-group",
            List.of("npm-local", "npm-proxy"),
            8080,
            cache,
            new TestNpmMetadataMerger()
        );

        // Request scoped package metadata
        final Response response = slice.response(
            new RequestLine("GET", "/@types/node"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        MatcherAssert.assertThat(
            "Merged response should contain version 18.0.0",
            body,
            Matchers.containsString("18.0.0")
        );
        MatcherAssert.assertThat(
            "Merged response should contain version 20.0.0",
            body,
            Matchers.containsString("20.0.0")
        );
    }

    /**
     * Test that NPM tarball requests use race strategy (not metadata merging).
     */
    @Test
    void npmTarballUsesRaceStrategy() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: 404 for tarball
        members.put("npm-local", (line, headers, body) -> {
            return ResponseBuilder.notFound().completedFuture();
        });
        // Member 2: has the tarball
        members.put("npm-proxy", (line, headers, body) -> {
            if (line.uri().getPath().endsWith(".tgz")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body("tarball-content".getBytes()).build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        });

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "npm-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final NpmGroupSlice slice = new NpmGroupSlice(
            new MapResolver(members),
            "npm-group",
            List.of("npm-local", "npm-proxy"),
            8080,
            cache,
            new TestNpmMetadataMerger()
        );

        // Request tarball (not metadata)
        final Response response = slice.response(
            new RequestLine("GET", "/lodash/-/lodash-4.17.21.tgz"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Tarball request should succeed",
            response.status(),
            Matchers.is(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Tarball content should be from proxy",
            response.body().asString(),
            Matchers.is("tarball-content")
        );
    }

    // ==================== Go Tests ====================

    /**
     * Test that GoGroupSlice merges @v/list from multiple members.
     * Member 1 has versions v1.0.0, v1.1.0
     * Member 2 has versions v1.1.0, v1.2.0
     * Result should contain v1.0.0, v1.1.0, v1.2.0 sorted.
     */
    @Test
    void goMergesVersionListsFromMultipleMembers() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: versions v1.0.0, v1.1.0
        members.put("go-local", createGoMemberSlice(
            "github.com/pkg/errors",
            "v1.0.0\nv1.1.0"
        ));
        // Member 2: versions v1.1.0, v1.2.0
        members.put("go-proxy", createGoMemberSlice(
            "github.com/pkg/errors",
            "v1.1.0\nv1.2.0"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "go-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final GoGroupSlice slice = new GoGroupSlice(
            new MapResolver(members),
            "go-group",
            List.of("go-local", "go-proxy"),
            8080,
            cache,
            new TestGoMetadataMerger()
        );

        // Request version list
        final Response response = slice.response(
            new RequestLine("GET", "/github.com/pkg/errors/@v/list"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        // Verify all three versions are present and sorted
        MatcherAssert.assertThat(
            "Merged response should contain v1.0.0",
            body,
            Matchers.containsString("v1.0.0")
        );
        MatcherAssert.assertThat(
            "Merged response should contain v1.1.0",
            body,
            Matchers.containsString("v1.1.0")
        );
        MatcherAssert.assertThat(
            "Merged response should contain v1.2.0",
            body,
            Matchers.containsString("v1.2.0")
        );

        // Verify sorted order
        final String[] lines = body.split("\n");
        MatcherAssert.assertThat(
            "Should have 3 versions",
            lines.length,
            Matchers.is(3)
        );
        MatcherAssert.assertThat(
            "First version should be v1.0.0",
            lines[0].trim(),
            Matchers.is("v1.0.0")
        );
        MatcherAssert.assertThat(
            "Second version should be v1.1.0",
            lines[1].trim(),
            Matchers.is("v1.1.0")
        );
        MatcherAssert.assertThat(
            "Third version should be v1.2.0",
            lines[2].trim(),
            Matchers.is("v1.2.0")
        );
    }

    /**
     * Test that GoGroupSlice handles prerelease versions correctly.
     */
    @Test
    void goMergesPrereleaseVersions() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: v1.0.0-alpha, v1.0.0
        members.put("go-local", createGoMemberSlice(
            "github.com/test/pkg",
            "v1.0.0-alpha\nv1.0.0"
        ));
        // Member 2: v1.0.0-beta, v1.1.0
        members.put("go-proxy", createGoMemberSlice(
            "github.com/test/pkg",
            "v1.0.0-beta\nv1.1.0"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "go-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final GoGroupSlice slice = new GoGroupSlice(
            new MapResolver(members),
            "go-group",
            List.of("go-local", "go-proxy"),
            8080,
            cache,
            new TestGoMetadataMerger()
        );

        final Response response = slice.response(
            new RequestLine("GET", "/github.com/test/pkg/@v/list"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        // Verify prerelease versions come before release
        final String[] lines = body.split("\n");
        MatcherAssert.assertThat(
            "Should have 4 versions",
            lines.length,
            Matchers.is(4)
        );
        // Prerelease versions should come first, then release
        MatcherAssert.assertThat(
            "First should be alpha (prerelease comes before release)",
            lines[0].trim(),
            Matchers.is("v1.0.0-alpha")
        );
        MatcherAssert.assertThat(
            "Second should be beta",
            lines[1].trim(),
            Matchers.is("v1.0.0-beta")
        );
        MatcherAssert.assertThat(
            "Third should be v1.0.0 release",
            lines[2].trim(),
            Matchers.is("v1.0.0")
        );
        MatcherAssert.assertThat(
            "Fourth should be v1.1.0",
            lines[3].trim(),
            Matchers.is("v1.1.0")
        );
    }

    /**
     * Test that Go zip requests use race strategy (not metadata merging).
     */
    @Test
    void goZipUsesRaceStrategy() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: 404 for zip
        members.put("go-local", (line, headers, body) -> {
            return ResponseBuilder.notFound().completedFuture();
        });
        // Member 2: has the zip
        members.put("go-proxy", (line, headers, body) -> {
            if (line.uri().getPath().endsWith(".zip")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body("zip-content".getBytes()).build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        });

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "go-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final GoGroupSlice slice = new GoGroupSlice(
            new MapResolver(members),
            "go-group",
            List.of("go-local", "go-proxy"),
            8080,
            cache,
            new TestGoMetadataMerger()
        );

        // Request zip file (not metadata)
        final Response response = slice.response(
            new RequestLine("GET", "/github.com/pkg/errors/@v/v0.9.1.zip"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Zip request should succeed",
            response.status(),
            Matchers.is(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Zip content should be from proxy",
            response.body().asString(),
            Matchers.is("zip-content")
        );
    }

    // ==================== PyPI Tests ====================

    /**
     * Test that PypiGroupSlice merges /simple/ index from multiple members.
     * Member 1 has requests-2.28.0.whl, requests-2.28.1.whl
     * Member 2 has requests-2.28.1.whl, requests-2.29.0.whl
     * Result should contain all three unique files.
     */
    @Test
    void pypiMergesSimpleIndexFromMultipleMembers() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: requests with 2.28.0 and 2.28.1
        members.put("pypi-local", createPypiMemberSlice(
            "requests",
            "<!DOCTYPE html><html><body>" +
                "<a href=\"/packages/requests-2.28.0.whl\">requests-2.28.0.whl</a>" +
                "<a href=\"/packages/requests-2.28.1.whl\">requests-2.28.1.whl</a>" +
                "</body></html>"
        ));
        // Member 2: requests with 2.28.1 and 2.29.0
        members.put("pypi-proxy", createPypiMemberSlice(
            "requests",
            "<!DOCTYPE html><html><body>" +
                "<a href=\"/packages/requests-2.28.1.whl\">requests-2.28.1.whl</a>" +
                "<a href=\"/packages/requests-2.29.0.whl\">requests-2.29.0.whl</a>" +
                "</body></html>"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "pypi-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final PypiGroupSlice slice = new PypiGroupSlice(
            new MapResolver(members),
            "pypi-group",
            List.of("pypi-local", "pypi-proxy"),
            8080,
            cache,
            new TestPypiMetadataMerger()
        );

        // Request simple index
        final Response response = slice.response(
            new RequestLine("GET", "/simple/requests/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        // Verify all three files are present
        MatcherAssert.assertThat(
            "Merged response should contain requests-2.28.0.whl",
            body,
            Matchers.containsString("requests-2.28.0.whl")
        );
        MatcherAssert.assertThat(
            "Merged response should contain requests-2.28.1.whl",
            body,
            Matchers.containsString("requests-2.28.1.whl")
        );
        MatcherAssert.assertThat(
            "Merged response should contain requests-2.29.0.whl",
            body,
            Matchers.containsString("requests-2.29.0.whl")
        );
        // Verify valid HTML structure
        MatcherAssert.assertThat(
            "Response should be valid HTML",
            body,
            Matchers.containsString("<!DOCTYPE html>")
        );
    }

    /**
     * Test that PypiGroupSlice preserves hash fragments in links.
     */
    @Test
    void pypiPreservesHashFragments() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: with sha256 hash
        members.put("pypi-local", createPypiMemberSlice(
            "django",
            "<!DOCTYPE html><html><body>" +
                "<a href=\"/packages/django-4.0.0.whl#sha256=abc123\">django-4.0.0.whl</a>" +
                "</body></html>"
        ));
        // Member 2: with different version and hash
        members.put("pypi-proxy", createPypiMemberSlice(
            "django",
            "<!DOCTYPE html><html><body>" +
                "<a href=\"/packages/django-4.1.0.whl#sha256=def456\">django-4.1.0.whl</a>" +
                "</body></html>"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "pypi-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final PypiGroupSlice slice = new PypiGroupSlice(
            new MapResolver(members),
            "pypi-group",
            List.of("pypi-local", "pypi-proxy"),
            8080,
            cache,
            new TestPypiMetadataMerger()
        );

        final Response response = slice.response(
            new RequestLine("GET", "/simple/django/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        // Verify hash fragments are preserved
        MatcherAssert.assertThat(
            "Should preserve sha256 hash for 4.0.0",
            body,
            Matchers.containsString("#sha256=abc123")
        );
        MatcherAssert.assertThat(
            "Should preserve sha256 hash for 4.1.0",
            body,
            Matchers.containsString("#sha256=def456")
        );
    }

    /**
     * Test that PyPI wheel download uses race strategy (not metadata merging).
     */
    @Test
    void pypiWheelUsesRaceStrategy() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1: 404 for wheel
        members.put("pypi-local", (line, headers, body) -> {
            return ResponseBuilder.notFound().completedFuture();
        });
        // Member 2: has the wheel
        members.put("pypi-proxy", (line, headers, body) -> {
            if (line.uri().getPath().endsWith(".whl")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body("wheel-content".getBytes()).build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        });

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "pypi-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final PypiGroupSlice slice = new PypiGroupSlice(
            new MapResolver(members),
            "pypi-group",
            List.of("pypi-local", "pypi-proxy"),
            8080,
            cache,
            new TestPypiMetadataMerger()
        );

        // Request wheel file (not metadata)
        final Response response = slice.response(
            new RequestLine("GET", "/simple/requests/requests-2.28.0-py3-none-any.whl"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Wheel request should succeed",
            response.status(),
            Matchers.is(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Wheel content should be from proxy",
            response.body().asString(),
            Matchers.is("wheel-content")
        );
    }

    // ==================== Priority Tests ====================

    /**
     * Test that priority member wins for conflicting metadata values.
     * In NPM, if both members have the same version with different data,
     * the first member's data should be used.
     */
    @Test
    void priorityMemberWinsForConflicts() {
        final Map<String, Slice> members = new HashMap<>();
        // Member 1 (priority): lodash 4.17.21 with description "priority-desc"
        members.put("npm-local", createNpmMemberSlice(
            "lodash",
            "{\"name\":\"lodash\",\"description\":\"priority-desc\",\"versions\":{" +
                "\"4.17.21\":{\"version\":\"4.17.21\",\"name\":\"lodash\"}" +
            "}}"
        ));
        // Member 2: lodash 4.17.21 with description "secondary-desc"
        members.put("npm-proxy", createNpmMemberSlice(
            "lodash",
            "{\"name\":\"lodash\",\"description\":\"secondary-desc\",\"versions\":{" +
                "\"4.17.21\":{\"version\":\"4.17.21\",\"name\":\"lodash\"}" +
            "}}"
        ));

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "npm-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final NpmGroupSlice slice = new NpmGroupSlice(
            new MapResolver(members),
            "npm-group",
            List.of("npm-local", "npm-proxy"),
            8080,
            cache,
            new TestNpmMetadataMerger()
        );

        final Response response = slice.response(
            new RequestLine("GET", "/lodash"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be OK",
            response.status(),
            Matchers.is(RsStatus.OK)
        );

        final String body = response.body().asString();
        // Priority member's description should win
        MatcherAssert.assertThat(
            "Priority member's description should be used",
            body,
            Matchers.containsString("priority-desc")
        );
        MatcherAssert.assertThat(
            "Secondary member's description should not be used",
            body,
            Matchers.not(Matchers.containsString("secondary-desc"))
        );
    }

    // ==================== 404 Tests ====================

    /**
     * Test that 404 is returned when no members have the package.
     */
    @Test
    void returns404WhenNoMembersHavePackage() {
        final Map<String, Slice> members = new HashMap<>();
        members.put("npm-local", (line, headers, body) ->
            ResponseBuilder.notFound().completedFuture()
        );
        members.put("npm-proxy", (line, headers, body) ->
            ResponseBuilder.notFound().completedFuture()
        );

        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "npm-group",
            GroupSettings.defaults(),
            Optional.empty()
        );

        final NpmGroupSlice slice = new NpmGroupSlice(
            new MapResolver(members),
            "npm-group",
            List.of("npm-local", "npm-proxy"),
            8080,
            cache,
            new TestNpmMetadataMerger()
        );

        final Response response = slice.response(
            new RequestLine("GET", "/nonexistent-package"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Response should be NOT_FOUND",
            response.status(),
            Matchers.is(RsStatus.NOT_FOUND)
        );
    }

    // ==================== Helper Methods ====================

    /**
     * Create an NPM member slice that returns metadata for a specific package.
     *
     * @param packageName Package name to match
     * @param metadata JSON metadata to return
     * @return Slice that returns the metadata
     */
    private static Slice createNpmMemberSlice(
        final String packageName,
        final String metadata
    ) {
        return (line, headers, body) -> {
            final String path = line.uri().getPath();
            // Match unscoped: /member/package or /member/package.json
            // Match scoped: /member/@scope/package
            if (path.contains(packageName.replace("@", "%40"))
                || path.contains(packageName)
                || path.endsWith("/" + packageName.replace("@", "%40"))
                || path.endsWith("/" + packageName)) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "application/json")
                        .body(metadata.getBytes(StandardCharsets.UTF_8))
                        .build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        };
    }

    /**
     * Create a Go member slice that returns @v/list for a specific module.
     *
     * @param modulePath Module path to match
     * @param versionList Version list content (newline-separated)
     * @return Slice that returns the version list
     */
    private static Slice createGoMemberSlice(
        final String modulePath,
        final String versionList
    ) {
        return (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.contains(modulePath) && path.contains("/@v/list")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "text/plain")
                        .body(versionList.getBytes(StandardCharsets.UTF_8))
                        .build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        };
    }

    /**
     * Create a PyPI member slice that returns simple index for a specific package.
     *
     * @param packageName Package name to match
     * @param indexHtml HTML index content
     * @return Slice that returns the index
     */
    private static Slice createPypiMemberSlice(
        final String packageName,
        final String indexHtml
    ) {
        return (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.contains("/simple/" + packageName)) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "text/html")
                        .body(indexHtml.getBytes(StandardCharsets.UTF_8))
                        .build()
                );
            }
            return ResponseBuilder.notFound().completedFuture();
        };
    }

    /**
     * Map-based slice resolver for testing.
     */
    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;

        private MapResolver(final Map<String, Slice> map) {
            this.map = map;
        }

        @Override
        public Slice slice(final Key name, final int port, final int depth) {
            return this.map.get(name.string());
        }
    }

    // ==================== Test Merger Implementations ====================

    /**
     * Test implementation of NPM metadata merger.
     * Replicates the behavior of NpmMetadataMerger.
     */
    private static final class TestNpmMetadataMerger implements MetadataMerger {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
            if (responses.isEmpty()) {
                return "{}".getBytes(StandardCharsets.UTF_8);
            }
            try {
                final ObjectNode result = MAPPER.createObjectNode();
                final ObjectNode versions = MAPPER.createObjectNode();
                final ObjectNode distTags = MAPPER.createObjectNode();
                final ObjectNode time = MAPPER.createObjectNode();
                for (final Map.Entry<String, byte[]> entry : responses.entrySet()) {
                    final JsonNode root = MAPPER.readTree(entry.getValue());
                    if (root.isObject()) {
                        mergeNpmObject((ObjectNode) root, result, versions, distTags, time);
                    }
                }
                if (versions.size() > 0) {
                    result.set("versions", versions);
                }
                if (distTags.size() > 0) {
                    result.set("dist-tags", distTags);
                }
                if (time.size() > 0) {
                    result.set("time", time);
                }
                return MAPPER.writeValueAsBytes(result);
            } catch (final IOException ex) {
                throw new IllegalArgumentException("Failed to merge NPM metadata", ex);
            }
        }

        private static void mergeNpmObject(
            final ObjectNode source,
            final ObjectNode result,
            final ObjectNode versions,
            final ObjectNode distTags,
            final ObjectNode time
        ) {
            final Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> field = fields.next();
                final String name = field.getKey();
                final JsonNode value = field.getValue();
                if ("versions".equals(name) && value.isObject()) {
                    mergeWithPriority(versions, (ObjectNode) value);
                } else if ("dist-tags".equals(name) && value.isObject()) {
                    mergeWithPriority(distTags, (ObjectNode) value);
                } else if ("time".equals(name) && value.isObject()) {
                    mergeWithPriority(time, (ObjectNode) value);
                } else if (!result.has(name)) {
                    result.set(name, value);
                }
            }
        }

        private static void mergeWithPriority(final ObjectNode target, final ObjectNode source) {
            final Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> field = fields.next();
                if (!target.has(field.getKey())) {
                    target.set(field.getKey(), field.getValue());
                }
            }
        }
    }

    /**
     * Test implementation of Go metadata merger.
     * Replicates the behavior of GoMetadataMerger.
     */
    private static final class TestGoMetadataMerger implements MetadataMerger {
        private static final Pattern SEMVER = Pattern.compile(
            "v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([\\w.]+))?"
        );

        @Override
        public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
            if (responses.isEmpty()) {
                return new byte[0];
            }
            final TreeSet<String> versions = new TreeSet<>(new SemverComparator());
            for (final Map.Entry<String, byte[]> entry : responses.entrySet()) {
                final String content = new String(entry.getValue(), StandardCharsets.UTF_8);
                content.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .forEach(versions::add);
            }
            return versions.stream()
                .collect(Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8);
        }

        private static final class SemverComparator implements Comparator<String> {
            @Override
            public int compare(final String ver1, final String ver2) {
                final Matcher match1 = SEMVER.matcher(ver1);
                final Matcher match2 = SEMVER.matcher(ver2);
                if (!match1.matches() || !match2.matches()) {
                    return ver1.compareTo(ver2);
                }
                int cmp = Integer.compare(
                    Integer.parseInt(match1.group(1)),
                    Integer.parseInt(match2.group(1))
                );
                if (cmp != 0) return cmp;
                cmp = Integer.compare(
                    Integer.parseInt(match1.group(2)),
                    Integer.parseInt(match2.group(2))
                );
                if (cmp != 0) return cmp;
                cmp = Integer.compare(
                    Integer.parseInt(match1.group(3)),
                    Integer.parseInt(match2.group(3))
                );
                if (cmp != 0) return cmp;
                final String pre1 = match1.group(4);
                final String pre2 = match2.group(4);
                if (pre1 == null && pre2 == null) return 0;
                if (pre1 == null) return 1;
                if (pre2 == null) return -1;
                return pre1.compareTo(pre2);
            }
        }
    }

    /**
     * Test implementation of PyPI metadata merger.
     * Replicates the behavior of PypiMetadataMerger.
     */
    private static final class TestPypiMetadataMerger implements MetadataMerger {
        private static final Pattern LINK_PATTERN = Pattern.compile(
            "<a\\s+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE
        );

        @Override
        public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
            final TreeMap<String, String> links = new TreeMap<>();
            for (final Map.Entry<String, byte[]> entry : responses.entrySet()) {
                final String html = new String(entry.getValue(), StandardCharsets.UTF_8);
                final Matcher matcher = LINK_PATTERN.matcher(html);
                while (matcher.find()) {
                    final String href = matcher.group(1);
                    final String filename = extractFilename(href);
                    if (!links.containsKey(filename)) {
                        links.put(filename, href);
                    }
                }
            }
            return generateHtml(links);
        }

        private static String extractFilename(final String href) {
            final int hashIdx = href.indexOf('#');
            final String withoutHash = hashIdx >= 0 ? href.substring(0, hashIdx) : href;
            final int lastSlash = withoutHash.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < withoutHash.length() - 1) {
                return withoutHash.substring(lastSlash + 1);
            }
            return withoutHash;
        }

        private static byte[] generateHtml(final TreeMap<String, String> links) {
            final StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html>\n<body>\n");
            for (final Map.Entry<String, String> entry : links.entrySet()) {
                html.append("<a href=\"")
                    .append(entry.getValue())
                    .append("\">")
                    .append(entry.getKey())
                    .append("</a>\n");
            }
            html.append("</body>\n</html>");
            return html.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
