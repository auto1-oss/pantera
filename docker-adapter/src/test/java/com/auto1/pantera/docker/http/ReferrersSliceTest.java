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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.TrustedBlobSource;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link ReferrersSlice}.
 */
final class ReferrersSliceTest {

    /**
     * Sample subject digest — 64 hex chars, matching a real SHA-256 shape.
     */
    private static final String SUBJECT = "sha256:" + "a".repeat(64);

    /**
     * A second, distinct subject digest — used to prove a listing is
     * scoped to its own subject and doesn't leak other subjects' entries.
     */
    private static final String OTHER_SUBJECT = "sha256:" + "b".repeat(64);

    private DockerSlice slice;

    private Docker docker;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.slice = TestDockerAuth.slice(this.docker);
    }

    @Test
    @Timeout(5)
    void returnsEmptyImageIndexWhenNoReferrersIndexed() {
        final Response response = this.get("my-repo", SUBJECT, null);
        MatcherAssert.assertThat(
            "Status is OK", response.status(), new IsEqual<>(RsStatus.OK)
        );
        final String body = this.bodyOf(response);
        MatcherAssert.assertThat(
            "Body contains schemaVersion 2", body, Matchers.containsString("\"schemaVersion\":2")
        );
        MatcherAssert.assertThat(
            "Body contains empty manifests array", body, Matchers.containsString("\"manifests\":[]")
        );
    }

    @Test
    @Timeout(5)
    void returnsCorrectContentType() {
        final Response response = this.get("my-repo", SUBJECT, null);
        MatcherAssert.assertThat(
            "Content-Type is OCI image index",
            response.headers().single("Content-Type").getValue(),
            new IsEqual<>("application/vnd.oci.image.index.v1+json")
        );
    }

    @Test
    @Timeout(5)
    void handlesNestedRepoNames() {
        final Response response = this.get("library/nginx", SUBJECT, null);
        MatcherAssert.assertThat(
            "Nested repo name returns OK", response.status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    @Timeout(5)
    void indexesAndServesReferrerOnPush() {
        final String pushed = this.pushDigestOf(
            this.push("my-repo", "sig-tag", SUBJECT, "application/vnd.example.sig.v1+json")
        );
        final String body = this.bodyOf(this.get("my-repo", SUBJECT, null));
        MatcherAssert.assertThat(
            "Listing contains the pushed referrer digest", body, Matchers.containsString(pushed)
        );
        MatcherAssert.assertThat(
            "Listing carries the referrer's artifactType",
            body,
            Matchers.containsString("application/vnd.example.sig.v1+json")
        );
    }

    @Test
    @Timeout(5)
    void indexIsScopedPerSubject() {
        this.push("my-repo", "sig-tag", SUBJECT, "application/vnd.example.sig.v1+json");
        final String body = this.bodyOf(this.get("my-repo", OTHER_SUBJECT, null));
        MatcherAssert.assertThat(
            "A referrer of one subject is not listed under a different subject",
            body,
            Matchers.containsString("\"manifests\":[]")
        );
    }

    @Test
    @Timeout(5)
    void filtersByArtifactTypeAndSetsHeader() {
        final String sbom = this.pushDigestOf(
            this.push("my-repo", "sbom-tag", SUBJECT, "application/vnd.example.sbom.v1+json")
        );
        final String signature = this.pushDigestOf(
            this.push("my-repo", "sig-tag", SUBJECT, "application/vnd.example.sig.v1+json")
        );
        final Response filtered = this.get("my-repo", SUBJECT, "application/vnd.example.sbom.v1+json");
        MatcherAssert.assertThat(
            "Filtered listing carries OCI-Filters-Applied",
            filtered.headers().single("OCI-Filters-Applied").getValue(),
            new IsEqual<>("artifactType")
        );
        final String filteredBody = this.bodyOf(filtered);
        MatcherAssert.assertThat(
            "Filtered listing contains the matching SBOM referrer",
            filteredBody,
            Matchers.containsString(sbom)
        );
        MatcherAssert.assertThat(
            "Filtered listing excludes the non-matching signature referrer",
            filteredBody,
            Matchers.not(Matchers.containsString(signature))
        );
        final Response unfiltered = this.get("my-repo", SUBJECT, null);
        MatcherAssert.assertThat(
            "Unfiltered request carries no OCI-Filters-Applied header",
            unfiltered.headers().find("OCI-Filters-Applied").isEmpty(),
            new IsEqual<>(true)
        );
    }

    private Response get(final String name, final String subject, final String artifactType) {
        final String query = artifactType == null ? "" : "?artifactType=" + artifactType;
        return this.slice.response(
            new RequestLine(RqMethod.GET, "/v2/" + name + "/referrers/" + subject + query),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
    }

    private Response push(
        final String name, final String tag, final String subject, final String artifactType
    ) {
        final byte[] config = "config".getBytes(StandardCharsets.UTF_8);
        final Digest configDigest = this.docker.repo(name).layers()
            .put(new TrustedBlobSource(config))
            .toCompletableFuture().join();
        final String json = String.format(
            "{\"config\":{\"digest\":\"%s\"},\"layers\":[],"
                + "\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"%s\","
                + "\"subject\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"digest\":\"%s\",\"size\":42}}",
            configDigest.string(), artifactType, subject
        );
        return this.slice.response(
            new RequestLine(RqMethod.PUT, "/v2/" + name + "/manifests/" + tag),
            TestDockerAuth.headers(),
            new Content.From(json.getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    private String pushDigestOf(final Response response) {
        return response.headers().single("Docker-Content-Digest").getValue();
    }

    private String bodyOf(final Response response) {
        return new String(response.body().asBytes(), StandardCharsets.UTF_8);
    }
}
