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
package com.auto1.pantera.nuget.http.publish;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.nuget.AstoRepository;
import com.auto1.pantera.nuget.PackageIdentity;
import com.auto1.pantera.nuget.http.NuGet;
import com.auto1.pantera.nuget.http.NuGetApiKeySlice;
import com.auto1.pantera.nuget.http.TestAuthentication;
import com.auto1.pantera.nuget.metadata.PackageId;
import com.auto1.pantera.nuget.metadata.Version;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.PermissionCollection;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the NuGet package delete ({@code dotnet nuget delete}).
 *
 * @since 2.2.10
 */
final class NuGetPackageDeleteTest {

    /**
     * Repository name.
     */
    private static final String REPO = "test";

    /**
     * Token the fake token authentication accepts.
     */
    private static final String API_KEY = "good-key";

    /**
     * Delete path of the test package version.
     */
    private static final String PATH = "/package/Newtonsoft.Json/12.0.3";

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
    void deletesVersionAndPublishesDeleteEvent() throws Exception {
        final Slice nuget = this.nuget(new PolicyByUsername(TestAuthentication.USERNAME));
        this.push(nuget);
        this.events.clear();
        MatcherAssert.assertThat(
            "delete answers 204",
            this.delete(nuget, NuGetPackageDeleteTest.PATH, TestAuthentication.HEADERS),
            new IsEqual<>(RsStatus.NO_CONTENT)
        );
        final PackageIdentity identity = new PackageIdentity(
            new PackageId("newtonsoft.json"), new Version("12.0.3")
        );
        MatcherAssert.assertThat(
            "the nupkg is removed",
            this.storage.exists(identity.nupkgKey()).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the nuspec is removed",
            this.storage.exists(identity.nuspecKey()).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the hash is removed",
            this.storage.exists(identity.hashKey()).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the version list of the last version is removed",
            this.storage.exists(new Key.From("newtonsoft.json", "index.json")).join(),
            new IsEqual<>(false)
        );
        final ArtifactEvent event = this.events.peek();
        MatcherAssert.assertThat(
            "one delete-version event is published",
            this.events.size() == 1
                && event.eventType() == ArtifactEvent.Type.DELETE_VERSION
                && "newtonsoft.json".equals(event.artifactName())
                && "12.0.3".equals(event.artifactVersion()),
            new IsEqual<>(true)
        );
    }

    @Test
    void deleteOfMissingVersionIsNotFound() throws Exception {
        final Slice nuget = this.nuget(new PolicyByUsername(TestAuthentication.USERNAME));
        this.push(nuget);
        this.events.clear();
        MatcherAssert.assertThat(
            "an unknown version answers 404",
            this.delete(nuget, "/package/Newtonsoft.Json/9.9.9", TestAuthentication.HEADERS),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the stored version is untouched",
            this.storage.exists(
                new PackageIdentity(new PackageId("newtonsoft.json"), new Version("12.0.3"))
                    .nupkgKey()
            ).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "nothing is published", this.events.isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void deleteOfMalformedPathIsNotFound() throws Exception {
        MatcherAssert.assertThat(
            this.delete(
                this.nuget(new PolicyByUsername(TestAuthentication.USERNAME)),
                "/package/Newtonsoft.Json/../12.0.3", TestAuthentication.HEADERS
            ),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void anonymousDeleteIsUnauthorized() throws Exception {
        final Slice nuget = this.nuget(new PolicyByUsername(TestAuthentication.USERNAME));
        this.push(nuget);
        MatcherAssert.assertThat(
            this.delete(nuget, NuGetPackageDeleteTest.PATH, Headers.EMPTY),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void deleteNeedsDeletePermission() throws Exception {
        final AdapterBasicPermission write = new AdapterBasicPermission(
            NuGetPackageDeleteTest.REPO, "read,write"
        );
        final PermissionCollection perms = write.newPermissionCollection();
        perms.add(write);
        final Slice nuget = this.nuget(user -> perms);
        this.push(nuget);
        MatcherAssert.assertThat(
            "a writer without delete permission is refused",
            this.delete(nuget, NuGetPackageDeleteTest.PATH, TestAuthentication.HEADERS),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the version is untouched",
            this.storage.exists(
                new PackageIdentity(new PackageId("newtonsoft.json"), new Version("12.0.3"))
                    .nupkgKey()
            ).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void deleteWithApiKeyIsAuthenticated() throws Exception {
        final Slice nuget = this.nuget(new PolicyByUsername(TestAuthentication.USERNAME));
        this.push(nuget);
        MatcherAssert.assertThat(
            this.delete(
                nuget, NuGetPackageDeleteTest.PATH,
                Headers.from(new Header("X-NuGet-ApiKey", NuGetPackageDeleteTest.API_KEY))
            ),
            new IsEqual<>(RsStatus.NO_CONTENT)
        );
    }

    private RsStatus delete(final Slice nuget, final String path, final Headers headers) {
        return nuget.response(new RequestLine(RqMethod.DELETE, path), headers, Content.EMPTY)
            .join().status();
    }

    private void push(final Slice nuget) throws Exception {
        final HttpEntity entity = MultipartEntityBuilder.create()
            .addBinaryBody(
                "package.nupkg",
                new com.auto1.pantera.nuget.NewtonJsonResource("newtonsoft.json.12.0.3.nupkg")
                    .bytes()
            )
            .build();
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        entity.writeTo(sink);
        MatcherAssert.assertThat(
            "precondition: the package is pushed",
            nuget.response(
                new RequestLine(RqMethod.PUT, "/package"),
                Headers.from(
                    TestAuthentication.HEADER,
                    new Header("Content-Type", entity.getContentType())
                ),
                new Content.From(sink.toByteArray())
            ).join().status(),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    private Slice nuget(final com.auto1.pantera.security.policy.Policy<?> policy)
        throws Exception {
        return new NuGetApiKeySlice(
            new NuGet(
                URI.create("http://localhost").toURL(),
                new AstoRepository(this.storage),
                policy,
                new TestAuthentication(),
                token -> CompletableFuture.completedFuture(
                    NuGetPackageDeleteTest.API_KEY.equals(token)
                        ? Optional.of(new AuthUser(TestAuthentication.USERNAME, "test"))
                        : Optional.empty()
                ),
                NuGetPackageDeleteTest.REPO,
                Optional.of(this.events)
            )
        );
    }
}
