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
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.TrustedBlobSource;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * B74: the docker {@code overwrite} action guards moving an existing tag.
 */
final class ManifestOverwriteTest {

    /**
     * Tag under test.
     */
    private static final String PATH = "/v2/my-app/manifests/1.0";

    private Docker docker;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
    }

    @Test
    void pushWithoutOverwriteCannotMoveExistingTag() {
        final Slice slice = this.slice(DockerActions.PULL.mask() | DockerActions.PUSH.mask());
        final byte[] first = this.manifest("one");
        final Response created = this.push(slice, first);
        final Response moved = this.push(slice, this.manifest("two"));
        MatcherAssert.assertThat(
            "first push of a new tag is allowed",
            created.status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "moving the tag without overwrite is denied",
            moved.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "denial carries the OCI DENIED code",
            moved.body().asString(), new StringContains("DENIED")
        );
        MatcherAssert.assertThat(
            "tag still points at the first manifest",
            this.docker.repo("my-app").manifests()
                .get(ManifestReference.fromTag("1.0")).join().orElseThrow().digest().string(),
            new IsEqual<>(new Digest.Sha256(first).string())
        );
    }

    @Test
    void pushWithoutOverwriteMayRepeatIdenticalManifest() {
        final Slice slice = this.slice(DockerActions.PULL.mask() | DockerActions.PUSH.mask());
        final byte[] manifest = this.manifest("one");
        this.push(slice, manifest);
        MatcherAssert.assertThat(
            this.push(slice, manifest).status(), new IsEqual<>(RsStatus.CREATED)
        );
    }

    @Test
    void pushWithOverwriteMovesExistingTag() {
        final Slice slice = this.slice(DockerActions.ALL.mask());
        this.push(slice, this.manifest("one"));
        MatcherAssert.assertThat(
            this.push(slice, this.manifest("two")).status(), new IsEqual<>(RsStatus.CREATED)
        );
    }

    private Slice slice(final int mask) {
        return new DockerSlice(
            this.docker,
            new AuthTest.TestPolicy(
                new DockerRepositoryPermission("test_registry", "*", mask)
            ),
            new BasicAuthScheme(new TestAuthentication()),
            Optional.empty()
        );
    }

    private Response push(final Slice slice, final byte[] manifest) {
        return slice.response(
            new RequestLine(RqMethod.PUT, ManifestOverwriteTest.PATH),
            TestAuthentication.ALICE.headers(),
            new Content.From(manifest)
        ).join();
    }

    private byte[] manifest(final String config) {
        final Digest digest = this.docker.repo("my-app").layers()
            .put(new TrustedBlobSource(config.getBytes(StandardCharsets.UTF_8)))
            .join();
        return String.format(
            "{\"config\":{\"digest\":\"%s\"},\"layers\":[],\"mediaType\":\"my-type\"}",
            digest.string()
        ).getBytes(StandardCharsets.UTF_8);
    }
}
