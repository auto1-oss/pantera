/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
}
