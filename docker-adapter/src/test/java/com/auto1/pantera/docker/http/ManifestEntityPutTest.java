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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Tests for {@link DockerSlice}.
 * Manifest PUT endpoint.
 */
class ManifestEntityPutTest {

    private DockerSlice slice;

    private Docker docker;

    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.events = new LinkedList<>();
        this.slice = new DockerSlice(this.docker, this.events);
    }

    @Test
    void shouldPushManifestByTag() {
        final String path = "/v2/my-alpine/manifests/1";
        ResponseAssert.check(
            this.slice.response(
                new RequestLine(RqMethod.PUT, path), Headers.EMPTY, this.manifest()
            ).join(),
            RsStatus.CREATED,
            new Header("Location", path),
            new Header("Content-Length", "0"),
            new Header(
                "Docker-Content-Digest",
                "sha256:ef0ff2adcc3c944a63f7cafb386abc9a1d95528966085685ae9fab2a1c0bedbf"
            )
        );
        MatcherAssert.assertThat("One event was added to queue", this.events.size() == 1);
        final ArtifactEvent item = this.events.element();
        MatcherAssert.assertThat(item.artifactName(), new IsEqual<>("my-alpine"));
        MatcherAssert.assertThat(item.artifactVersion(), new IsEqual<>("1"));
    }

    @Test
    void shouldPushManifestByDigest() {
        String digest = "sha256:ef0ff2adcc3c944a63f7cafb386abc9a1d95528966085685ae9fab2a1c0bedbf";
        String path = "/v2/my-alpine/manifests/" + digest;
        ResponseAssert.check(
            this.slice.response(
                new RequestLine(RqMethod.PUT, path), Headers.EMPTY, this.manifest()
            ).join(),
            RsStatus.CREATED,
            new Header("Location", path),
            new Header("Content-Length", "0"),
            new Header("Docker-Content-Digest", digest)
        );
        Assertions.assertTrue(events.isEmpty(),  events.toString());
    }

    @Test
    void shouldOmitOciSubjectHeaderWhenManifestHasNoSubject() {
        final var response = this.slice.response(
            new RequestLine(RqMethod.PUT, "/v2/my-alpine/manifests/1"), Headers.EMPTY, this.manifest()
        ).join();
        MatcherAssert.assertThat(
            "No OCI-Subject header on a manifest that carries no `subject` field",
            response.headers().find("OCI-Subject").isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldEmitOciSubjectHeaderWhenManifestHasSubject() {
        final String subject = "sha256:" + "a".repeat(64);
        final var response = this.slice.response(
            new RequestLine(RqMethod.PUT, "/v2/my-alpine/manifests/sig-tag"),
            Headers.EMPTY,
            this.manifestWithSubject(subject)
        ).join();
        ResponseAssert.check(
            response,
            RsStatus.CREATED,
            new Header("OCI-Subject", subject)
        );
    }

    /**
     * Create manifest content.
     *
     * @return Manifest content.
     */
    private Content manifest() {
        final byte[] content = "config".getBytes();
        final Digest digest = this.docker.repo("my-alpine").layers()
            .put(new TrustedBlobSource(content))
            .toCompletableFuture().join();
        final byte[] data = String.format(
            "{\"config\":{\"digest\":\"%s\"},\"layers\":[],\"mediaType\":\"my-type\"}",
            digest.string()
        ).getBytes();
        return new Content.From(data);
    }

    /**
     * Create manifest content carrying an OCI 1.1 {@code subject} field.
     *
     * @param subject Subject digest.
     * @return Manifest content.
     */
    private Content manifestWithSubject(final String subject) {
        final byte[] content = "sig-config".getBytes();
        final Digest digest = this.docker.repo("my-alpine").layers()
            .put(new TrustedBlobSource(content))
            .toCompletableFuture().join();
        final byte[] data = String.format(
            "{\"config\":{\"digest\":\"%s\"},\"layers\":[],"
                + "\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"application/vnd.example.sig.v1+json\","
                + "\"subject\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"digest\":\"%s\",\"size\":42}}",
            digest.string(), subject
        ).getBytes();
        return new Content.From(data);
    }
}
