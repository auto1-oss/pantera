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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.hex.ResourceUtil;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.nio.file.Files;
import java.security.PermissionCollection;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ReleaseDeleteSlice}: {@code mix hex.publish --revert}.
 *
 * @since 2.2.10
 */
final class ReleaseDeleteSliceTest {

    /**
     * Repository name.
     */
    private static final String REPO = "my-hexpm";

    /**
     * User.
     */
    private static final String USER = "alice";

    /**
     * Password.
     */
    private static final String PSWD = "secret";

    /**
     * Release tarball key.
     */
    private static final Key TARBALL = new Key.From("tarballs", "decimal-2.0.0.tar");

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Artifact events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
    }

    @Test
    void revertRemovesReleaseAndRegistryAndPublishesDeleteEvent() throws Exception {
        final Slice hex = this.hex(new PolicyByUsername(ReleaseDeleteSliceTest.USER));
        this.publish(hex);
        this.events.clear();
        MatcherAssert.assertThat(
            "revert answers 204",
            this.delete(hex, "/packages/decimal/releases/2.0.0", ReleaseDeleteSliceTest.auth()),
            new IsEqual<>(RsStatus.NO_CONTENT)
        );
        MatcherAssert.assertThat(
            "the release tarball is removed",
            this.storage.exists(ReleaseDeleteSliceTest.TARBALL).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the registry record of the last release is removed",
            this.storage.exists(new Key.From("packages", "decimal")).join(),
            new IsEqual<>(false)
        );
        final ArtifactEvent event = this.events.peek();
        MatcherAssert.assertThat(
            "one delete-version event is published",
            this.events.size() == 1
                && event.eventType() == ArtifactEvent.Type.DELETE_VERSION
                && "decimal".equals(event.artifactName())
                && "2.0.0".equals(event.artifactVersion()),
            new IsEqual<>(true)
        );
    }

    @Test
    void revertUnderApiPrefixIsAccepted() throws Exception {
        final Slice hex = this.hex(new PolicyByUsername(ReleaseDeleteSliceTest.USER));
        this.publish(hex);
        MatcherAssert.assertThat(
            this.delete(
                hex, "/api/packages/decimal/releases/2.0.0", ReleaseDeleteSliceTest.auth()
            ),
            new IsEqual<>(RsStatus.NO_CONTENT)
        );
    }

    @Test
    void revertOfMissingReleaseIsNotFound() throws Exception {
        final Slice hex = this.hex(new PolicyByUsername(ReleaseDeleteSliceTest.USER));
        this.publish(hex);
        this.events.clear();
        MatcherAssert.assertThat(
            "an unknown release answers 404",
            this.delete(hex, "/packages/decimal/releases/9.9.9", ReleaseDeleteSliceTest.auth()),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the stored release is untouched",
            this.storage.exists(ReleaseDeleteSliceTest.TARBALL).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "nothing is published", this.events.isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void revertNeedsDeletePermission() throws Exception {
        final AdapterBasicPermission write = new AdapterBasicPermission(
            ReleaseDeleteSliceTest.REPO, "read,write"
        );
        final PermissionCollection perms = write.newPermissionCollection();
        perms.add(write);
        final Slice hex = this.hex(user -> perms);
        this.publish(hex);
        MatcherAssert.assertThat(
            "a writer without delete permission is refused",
            this.delete(hex, "/packages/decimal/releases/2.0.0", ReleaseDeleteSliceTest.auth()),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the release is untouched",
            this.storage.exists(ReleaseDeleteSliceTest.TARBALL).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void anonymousRevertIsUnauthorized() throws Exception {
        MatcherAssert.assertThat(
            this.delete(
                this.hex(new PolicyByUsername(ReleaseDeleteSliceTest.USER)),
                "/packages/decimal/releases/2.0.0", Headers.EMPTY
            ),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    private RsStatus delete(final Slice hex, final String path, final Headers headers) {
        return hex.response(new RequestLine(RqMethod.DELETE, path), headers, Content.EMPTY)
            .join().status();
    }

    private void publish(final Slice hex) throws Exception {
        final byte[] tar = Files.readAllBytes(
            new ResourceUtil("tarballs/decimal-2.0.0.tar").asPath()
        );
        MatcherAssert.assertThat(
            "precondition: the release is published",
            hex.response(
                new RequestLine(RqMethod.POST, "/publish?replace=false"),
                ReleaseDeleteSliceTest.auth().copy().add(new ContentLength(tar.length)),
                new Content.From(tar)
            ).join().status(),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    private Slice hex(final Policy<?> policy) {
        return new HexSlice(
            this.storage, policy,
            new Authentication.Single(ReleaseDeleteSliceTest.USER, ReleaseDeleteSliceTest.PSWD),
            Optional.of(this.events), ReleaseDeleteSliceTest.REPO
        );
    }

    private static Headers auth() {
        return Headers.from(
            new Authorization.Basic(ReleaseDeleteSliceTest.USER, ReleaseDeleteSliceTest.PSWD)
        );
    }
}
