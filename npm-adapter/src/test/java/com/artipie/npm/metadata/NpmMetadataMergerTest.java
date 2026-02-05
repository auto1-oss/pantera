/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.metadata;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

/**
 * Tests for {@link NpmMetadataMerger}.
 *
 * @since 1.18.0
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class NpmMetadataMergerTest {

    /**
     * Merger under test.
     */
    private NpmMetadataMerger merger;

    @BeforeEach
    void setUp() {
        this.merger = new NpmMetadataMerger();
    }

    @Test
    void mergesVersionsFromMultipleMembers() throws JSONException {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", memberOneMetadata());
        responses.put("member2", memberTwoMetadata());
        final byte[] result = this.merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);
        // Should have versions from both members
        JSONAssert.assertEquals(
            "{\"versions\":{\"1.0.0\":{\"name\":\"pkg\"},\"2.0.0\":{\"name\":\"pkg\"}}}",
            json,
            JSONCompareMode.LENIENT
        );
    }

    @Test
    void priorityMemberWinsForConflictingVersions() throws JSONException {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        // Member1 has priority (first in map)
        responses.put("member1", conflictVersionMember1());
        responses.put("member2", conflictVersionMember2());
        final byte[] result = this.merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);
        // Version 1.0.0 should have data from member1 (priority)
        JSONAssert.assertEquals(
            "{\"versions\":{\"1.0.0\":{\"name\":\"pkg\",\"from\":\"member1\"}}}",
            json,
            JSONCompareMode.LENIENT
        );
    }

    @Test
    void mergesDistTags() throws JSONException {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", distTagsMember1());
        responses.put("member2", distTagsMember2());
        final byte[] result = this.merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);
        // Should have both tags, priority member wins for 'latest'
        JSONAssert.assertEquals(
            "{\"dist-tags\":{\"latest\":\"1.0.0\",\"beta\":\"2.0.0-beta\"}}",
            json,
            JSONCompareMode.LENIENT
        );
    }

    @Test
    void mergesTimeField() throws JSONException {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", timeMember1());
        responses.put("member2", timeMember2());
        final byte[] result = this.merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);
        // Should have time entries from both members
        JSONAssert.assertEquals(
            "{\"time\":{\"1.0.0\":\"2023-01-01\",\"2.0.0\":\"2023-02-01\"}}",
            json,
            JSONCompareMode.LENIENT
        );
    }

    @Test
    void handlesEmptyResponses() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        final byte[] result = this.merger.merge(responses);
        MatcherAssert.assertThat(
            new String(result, StandardCharsets.UTF_8),
            Matchers.equalTo("{}")
        );
    }

    @Test
    void handlesSingleResponse() throws JSONException {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", memberOneMetadata());
        final byte[] result = this.merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);
        JSONAssert.assertEquals(
            "{\"versions\":{\"1.0.0\":{\"name\":\"pkg\"}}}",
            json,
            JSONCompareMode.LENIENT
        );
    }

    @Test
    void preservesOtherFields() throws JSONException {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", metadataWithOtherFields());
        final byte[] result = this.merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);
        JSONAssert.assertEquals(
            "{\"name\":\"my-package\",\"description\":\"A test package\"}",
            json,
            JSONCompareMode.LENIENT
        );
    }

    private static byte[] memberOneMetadata() {
        return "{\"versions\":{\"1.0.0\":{\"name\":\"pkg\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] memberTwoMetadata() {
        return "{\"versions\":{\"2.0.0\":{\"name\":\"pkg\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] conflictVersionMember1() {
        return "{\"versions\":{\"1.0.0\":{\"name\":\"pkg\",\"from\":\"member1\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] conflictVersionMember2() {
        return "{\"versions\":{\"1.0.0\":{\"name\":\"pkg\",\"from\":\"member2\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] distTagsMember1() {
        return "{\"dist-tags\":{\"latest\":\"1.0.0\"}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] distTagsMember2() {
        return "{\"dist-tags\":{\"latest\":\"2.0.0\",\"beta\":\"2.0.0-beta\"}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] timeMember1() {
        return "{\"time\":{\"1.0.0\":\"2023-01-01\"}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] timeMember2() {
        return "{\"time\":{\"2.0.0\":\"2023-02-01\"}}"
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] metadataWithOtherFields() {
        return "{\"name\":\"my-package\",\"description\":\"A test package\",\"versions\":{}}"
            .getBytes(StandardCharsets.UTF_8);
    }
}
