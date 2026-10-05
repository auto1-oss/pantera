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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.http.attestation.AttestationStore;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The repository {@code immutable} setting on npm publishes.
 *
 * @since 2.2.10
 */
final class ImmutablePublishTest {

    /**
     * Package name.
     */
    private static final String PKG = "@hello/simple-npm-project";

    /**
     * Repository name.
     */
    private static final String REPO = "npm-local";

    /**
     * Bearer token accepted by the test authentication.
     */
    private static final String TOKEN = "immutable-token";

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Publish events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
    }

    @Test
    void immutableRepositoryRefusesARepublishOfTheSameVersion() {
        final Slice slice = this.cli(true);
        MatcherAssert.assertThat(
            "first publish accepted",
            this.put(slice, ImmutablePublishTest.payload()).status(),
            new IsEqual<>(RsStatus.OK)
        );
        this.events.clear();
        final Response second = this.put(slice, ImmutablePublishTest.payload());
        MatcherAssert.assertThat(
            "identical re-publish refused",
            second.status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the refusal is an npm-style error the CLI prints",
            Json.createReader(new StringReader(second.body().asString())).readObject()
                .getString("error"),
            new IsEqual<>("cannot publish over the previously published version 1.0.1")
        );
        MatcherAssert.assertThat(
            "a refused publish is not an event",
            this.events.size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the uploaded temp file is removed",
            this.storage.list(Key.ROOT).join().stream()
                .anyMatch(key -> key.string().endsWith("-uploaded")),
            new IsEqual<>(false)
        );
    }

    @Test
    void immutableRepositoryKeepsThePublishedMetadata() {
        final Slice slice = this.cli(true);
        this.put(slice, ImmutablePublishTest.payload());
        final JsonObject before = this.version("1.0.1");
        this.put(
            slice,
            ImmutablePublishTest.withVersionField(ImmutablePublishTest.payload(), "description", "changed")
        );
        MatcherAssert.assertThat(
            this.version("1.0.1"), new IsEqual<>(before)
        );
    }

    @Test
    void immutableRepositoryRefusesWhenOnlyTheTarballIsStored() {
        final Slice slice = this.cli(true);
        this.storage.save(
            new Key.From(
                ImmutablePublishTest.PKG, "-",
                String.format("%s-1.0.1.tgz", ImmutablePublishTest.PKG)
            ),
            new Content.From(new byte[] {1})
        ).join();
        MatcherAssert.assertThat(
            this.put(slice, ImmutablePublishTest.payload()).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
    }

    @Test
    void immutableRepositoryAcceptsANewVersionNextToPublishedOnes() {
        final Slice slice = this.cli(true);
        this.put(slice, ImmutablePublishTest.payload());
        final JsonObject published = this.version("1.0.1");
        final JsonObject first = ImmutablePublishTest.payload();
        final JsonObject manifest = first.getJsonObject("versions").getJsonObject("1.0.1");
        final JsonObject next = Json.createObjectBuilder(first)
            .add(
                "versions",
                Json.createObjectBuilder()
                    .add("1.0.1", manifest)
                    .add("1.0.2", Json.createObjectBuilder(manifest).add("version", "1.0.2"))
            )
            .add("dist-tags", Json.createObjectBuilder().add("latest", "1.0.2"))
            .add(
                "_attachments",
                Json.createObjectBuilder().add(
                    String.format("%s-1.0.2.tgz", ImmutablePublishTest.PKG),
                    first.getJsonObject("_attachments").getJsonObject(
                        String.format("%s-1.0.1.tgz", ImmutablePublishTest.PKG)
                    )
                )
            )
            .build();
        MatcherAssert.assertThat(
            "a payload that also lists the published version is not refused",
            this.put(slice, next).status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the new version is published",
            this.published("1.0.2"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the listed, already published version is not rewritten",
            this.version("1.0.1"), new IsEqual<>(published)
        );
    }

    @Test
    void immutableRepositoryRefusesAProvenanceOverwriteSmuggledUnderANewTarget() {
        final Slice slice = this.cli(true);
        MatcherAssert.assertThat(
            "the provenance publish of 1.0.1 is accepted",
            this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.1"), "1.0.1", "original")).status(),
            new IsEqual<>(RsStatus.OK)
        );
        final JsonObject before = this.version("1.0.1");
        final byte[] provenance = this.attestation("1.0.1");
        this.events.clear();
        final JsonObject manifest = ImmutablePublishTest.payload()
            .getJsonObject("versions").getJsonObject("1.0.1");
        final JsonObject exploit = Json.createObjectBuilder(ImmutablePublishTest.payload())
            .add("dist-tags", Json.createObjectBuilder().add("latest", "1.0.2"))
            .add(
                "versions",
                Json.createObjectBuilder().add(
                    "1.0.1", Json.createObjectBuilder(manifest).add("description", "forged")
                )
            )
            .add(
                "_attachments",
                Json.createObjectBuilder().add(
                    String.format("%s-1.0.1.sigstore", ImmutablePublishTest.PKG),
                    ImmutablePublishTest.bundle("forged")
                )
            )
            .build();
        final Response refused = this.put(slice, exploit);
        MatcherAssert.assertThat(
            "the inconsistent payload is refused as a bad request",
            refused.status(), new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the refusal names the inconsistency",
            Json.createReader(new StringReader(refused.body().asString())).readObject()
                .getString("error"),
            new IsEqual<>("publish payload targets version 1.0.2 but carries metadata of [1.0.1]")
        );
        MatcherAssert.assertThat(
            "the published version metadata is untouched",
            this.version("1.0.1"), new IsEqual<>(before)
        );
        MatcherAssert.assertThat(
            "the published provenance is untouched",
            this.attestation("1.0.1"), new IsEqual<>(provenance)
        );
        MatcherAssert.assertThat(
            "the declared target is not published",
            this.published("1.0.2"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a refused publish is not an event",
            this.events.size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the uploaded temp file is removed",
            this.storage.list(Key.ROOT).join().stream()
                .anyMatch(key -> key.string().endsWith("-uploaded")),
            new IsEqual<>(false)
        );
    }

    @Test
    void immutableRepositoryRefusesAProvenanceRepublishOfThePublishedVersion() {
        final Slice slice = this.cli(true);
        this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.1"), "1.0.1", "original"));
        final JsonObject before = this.version("1.0.1");
        final byte[] provenance = this.attestation("1.0.1");
        final JsonObject attestationOnly = Json.createObjectBuilder(ImmutablePublishTest.release("1.0.1"))
            .add(
                "_attachments",
                Json.createObjectBuilder().add(
                    String.format("%s-1.0.1.sigstore", ImmutablePublishTest.PKG),
                    ImmutablePublishTest.bundle("forged")
                )
            )
            .build();
        MatcherAssert.assertThat(
            "an attestation-only re-publish is refused",
            this.put(slice, attestationOnly).status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the published version metadata is untouched",
            this.version("1.0.1"), new IsEqual<>(before)
        );
        MatcherAssert.assertThat(
            "the published provenance is untouched",
            this.attestation("1.0.1"), new IsEqual<>(provenance)
        );
    }

    @Test
    void immutableRepositoryRefusesAPayloadNamingAnotherPackage() {
        final Slice slice = this.cli(true);
        this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.1"), "1.0.1", "original"));
        final byte[] provenance = this.attestation("1.0.1");
        final JsonObject foreign = Json.createObjectBuilder(ImmutablePublishTest.release("1.0.1"))
            .add(
                "_attachments",
                Json.createObjectBuilder().add(
                    String.format("%s-1.0.1.sigstore", ImmutablePublishTest.PKG),
                    ImmutablePublishTest.bundle("forged")
                )
            )
            .build();
        MatcherAssert.assertThat(
            "a payload for another package than the request's is refused",
            ImmutablePublishTest.send(
                slice, "/other-package",
                foreign.toString().getBytes(StandardCharsets.UTF_8), Headers.EMPTY
            ).status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the other package's provenance is untouched",
            this.attestation("1.0.1"), new IsEqual<>(provenance)
        );
    }

    @Test
    void immutableRepositoryRefusesATarballOfAnotherVersion() {
        final Slice slice = this.cli(true);
        final JsonObject payload = ImmutablePublishTest.release("1.0.2");
        final JsonObject mismatched = Json.createObjectBuilder(payload)
            .add(
                "_attachments",
                Json.createObjectBuilder().add(
                    String.format("%s-1.0.3.tgz", ImmutablePublishTest.PKG),
                    ImmutablePublishTest.tarball()
                )
            )
            .build();
        MatcherAssert.assertThat(
            "a tarball attachment of another version is refused",
            this.put(slice, mismatched).status(), new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "nothing is written",
            this.storage.list(new Key.From(ImmutablePublishTest.PKG)).join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void immutableRepositoryAcceptsAProvenancePublishOfANewVersion() {
        final Slice slice = this.cli(true);
        this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.1"), "1.0.1", "first"));
        final JsonObject first = this.version("1.0.1");
        final byte[] provenance = this.attestation("1.0.1");
        this.events.clear();
        MatcherAssert.assertThat(
            "the provenance publish of a new version is accepted",
            this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.2"), "1.0.2", "second")).status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the new version's provenance is stored under the new version",
            new String(this.attestation("1.0.2"), StandardCharsets.UTF_8),
            new IsEqual<>(ImmutablePublishTest.bundleJson("second"))
        );
        MatcherAssert.assertThat(
            "the new version is signed",
            this.version("1.0.2").getJsonObject("dist").containsKey("signatures"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the earlier version's provenance is untouched",
            this.attestation("1.0.1"), new IsEqual<>(provenance)
        );
        MatcherAssert.assertThat(
            "the earlier version's metadata is untouched",
            this.version("1.0.1"), new IsEqual<>(first)
        );
        MatcherAssert.assertThat(
            "the event reports the new version",
            this.events.peek().artifactVersion(), new IsEqual<>("1.0.2")
        );
    }

    @Test
    void immutableRepositoryAcceptsAPublishUnderACustomDistTag() {
        final Slice slice = this.cli(true);
        this.put(slice, ImmutablePublishTest.release("1.0.1"));
        final JsonObject beta = Json.createObjectBuilder(ImmutablePublishTest.release("2.0.0-beta.1"))
            .add("dist-tags", Json.createObjectBuilder().add("beta", "2.0.0-beta.1"))
            .build();
        MatcherAssert.assertThat(
            "npm publish --tag beta is accepted",
            this.put(slice, beta).status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the beta version is published",
            this.published("2.0.0-beta.1"), new IsEqual<>(true)
        );
    }

    @Test
    void mutableRepositoryRefusesAnInconsistentPayload() {
        final Slice slice = this.cli(false);
        final JsonObject inconsistent = Json.createObjectBuilder(ImmutablePublishTest.payload())
            .add("dist-tags", Json.createObjectBuilder().add("latest", "1.0.2"))
            .build();
        MatcherAssert.assertThat(
            "a payload whose target is absent from versions is refused",
            this.put(slice, inconsistent).status(), new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "nothing is published under the declared target",
            this.published("1.0.2"), new IsEqual<>(false)
        );
    }

    @Test
    void mutableRepositoryOverwritesAPublishedProvenance() {
        final Slice slice = this.cli(false);
        this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.1"), "1.0.1", "first"));
        MatcherAssert.assertThat(
            "the provenance re-publish is accepted",
            this.put(slice, ImmutablePublishTest.withProvenance(ImmutablePublishTest.release("1.0.1"), "1.0.1", "second")).status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the provenance is replaced",
            new String(this.attestation("1.0.1"), StandardCharsets.UTF_8),
            new IsEqual<>(ImmutablePublishTest.bundleJson("second"))
        );
    }

    @Test
    void mutableRepositoryOverwritesAPublishedVersion() {
        final Slice slice = this.cli(false);
        this.put(slice, ImmutablePublishTest.payload());
        this.events.clear();
        MatcherAssert.assertThat(
            "re-publish accepted",
            this.put(
                slice,
                ImmutablePublishTest.withVersionField(
                    ImmutablePublishTest.payload(), "description", "changed"
                )
            ).status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the version metadata is replaced",
            this.version("1.0.1").getString("description"), new IsEqual<>("changed")
        );
        MatcherAssert.assertThat(
            "the overwrite is published so the search index is upserted",
            this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void immutableCurlPublishRefusesARepublish() {
        final Slice slice = new UploadSlice(
            new CurlPublish(this.storage, true), this.storage, Optional.of(this.events),
            ImmutablePublishTest.REPO, SyncArtifactIndexer.NOOP, true
        );
        final byte[] tgz = new TestResource("binaries/simple-npm-project-1.0.2.tgz").asBytes();
        final String path = String.format(
            "/%s/-/%s-1.0.2.tgz", ImmutablePublishTest.PKG, ImmutablePublishTest.PKG
        );
        MatcherAssert.assertThat(
            "first upload accepted",
            ImmutablePublishTest.send(slice, path, tgz, Headers.EMPTY).status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "second upload refused",
            ImmutablePublishTest.send(slice, path, tgz, Headers.EMPTY).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
    }

    @Test
    void mutableCurlPublishOverwrites() {
        final Slice slice = new UploadSlice(
            new CurlPublish(this.storage, false), this.storage, Optional.of(this.events),
            ImmutablePublishTest.REPO, SyncArtifactIndexer.NOOP, false
        );
        final byte[] tgz = new TestResource("binaries/simple-npm-project-1.0.2.tgz").asBytes();
        final String path = String.format(
            "/%s/-/%s-1.0.2.tgz", ImmutablePublishTest.PKG, ImmutablePublishTest.PKG
        );
        ImmutablePublishTest.send(slice, path, tgz, Headers.EMPTY);
        MatcherAssert.assertThat(
            ImmutablePublishTest.send(slice, path, tgz, Headers.EMPTY).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void npmSliceThreadsTheImmutableFlag() {
        final Slice immutable = this.npm(true);
        final Slice mutable = this.npm(false);
        final Headers headers = Headers.from(
            new Authorization.Bearer(ImmutablePublishTest.TOKEN),
            new Header("npm-command", "publish")
        );
        final byte[] body = ImmutablePublishTest.payload().toString()
            .getBytes(StandardCharsets.UTF_8);
        final String path = "/" + ImmutablePublishTest.PKG;
        MatcherAssert.assertThat(
            "first publish accepted",
            ImmutablePublishTest.send(immutable, path, body, headers).status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "immutable repository refuses the re-publish",
            ImmutablePublishTest.send(immutable, path, body, headers).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "mutable repository accepts the re-publish",
            ImmutablePublishTest.send(mutable, path, body, headers).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void legacyConstructorsKeepOverwriting() {
        final Slice slice = new UploadSlice(
            new CliPublish(this.storage), this.storage, Optional.of(this.events),
            ImmutablePublishTest.REPO
        );
        this.put(slice, ImmutablePublishTest.payload());
        MatcherAssert.assertThat(
            this.put(slice, ImmutablePublishTest.payload()).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    private Slice cli(final boolean immutable) {
        return new UploadSlice(
            new CliPublish(this.storage, immutable), this.storage, Optional.of(this.events),
            ImmutablePublishTest.REPO, SyncArtifactIndexer.NOOP, immutable
        );
    }

    private Slice npm(final boolean immutable) {
        final TokenAuthentication auth = token -> CompletableFuture.completedFuture(
            ImmutablePublishTest.TOKEN.equals(token)
                ? Optional.of(new AuthUser("publisher", "test"))
                : Optional.empty()
        );
        try {
            return new NpmSlice(
                Optional.of(URI.create("http://pantera.local").toURL()), this.storage,
                Policy.FREE, (user, pswd) -> Optional.empty(), auth, null,
                ImmutablePublishTest.REPO, Optional.of(this.events), true,
                SyncArtifactIndexer.NOOP, com.auto1.pantera.index.ArtifactIndex.NOP, immutable
            );
        } catch (final java.net.MalformedURLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Response put(final Slice slice, final JsonObject json) {
        return ImmutablePublishTest.send(
            slice, "/" + ImmutablePublishTest.PKG,
            json.toString().getBytes(StandardCharsets.UTF_8), Headers.EMPTY
        );
    }

    private static Response send(
        final Slice slice, final String path, final byte[] body, final Headers headers
    ) {
        return slice.response(
            new RequestLine(RqMethod.PUT, path), headers, new Content.From(body)
        ).join();
    }

    private JsonObject version(final String version) {
        return new PerVersionLayout(this.storage)
            .readVersion(new Key.From(ImmutablePublishTest.PKG), version)
            .toCompletableFuture().join();
    }

    private boolean published(final String version) {
        return new PerVersionLayout(this.storage)
            .hasVersion(new Key.From(ImmutablePublishTest.PKG), version)
            .toCompletableFuture().join();
    }

    private byte[] attestation(final String version) {
        return new AttestationStore(this.storage).read(ImmutablePublishTest.PKG, version)
            .join().orElseThrow();
    }

    /**
     * A regular npm publish payload of one version: {@code versions} holds
     * that version only, {@code dist-tags.latest} points at it and the
     * tarball attachment is named after it.
     * @param version Version
     * @return Payload
     */
    private static JsonObject release(final String version) {
        final JsonObject base = ImmutablePublishTest.payload();
        final JsonObject manifest = base.getJsonObject("versions").getJsonObject("1.0.1");
        return Json.createObjectBuilder(base)
            .add(
                "versions",
                Json.createObjectBuilder().add(
                    version, Json.createObjectBuilder(manifest).add("version", version)
                )
            )
            .add("dist-tags", Json.createObjectBuilder().add("latest", version))
            .add(
                "_attachments",
                Json.createObjectBuilder().add(
                    String.format("%s-%s.tgz", ImmutablePublishTest.PKG, version),
                    ImmutablePublishTest.tarball()
                )
            )
            .build();
    }

    private static JsonObject withProvenance(
        final JsonObject payload, final String version, final String marker
    ) {
        return Json.createObjectBuilder(payload)
            .add(
                "_attachments",
                Json.createObjectBuilder(payload.getJsonObject("_attachments")).add(
                    String.format("%s-%s.sigstore", ImmutablePublishTest.PKG, version),
                    ImmutablePublishTest.bundle(marker)
                )
            )
            .build();
    }

    private static JsonObject tarball() {
        return ImmutablePublishTest.payload().getJsonObject("_attachments").getJsonObject(
            String.format("%s-1.0.1.tgz", ImmutablePublishTest.PKG)
        );
    }

    private static JsonObject bundle(final String marker) {
        final String data = Base64.getEncoder().encodeToString(
            ImmutablePublishTest.bundleJson(marker).getBytes(StandardCharsets.UTF_8)
        );
        return Json.createObjectBuilder()
            .add("content_type", "application/vnd.dev.sigstore.bundle.v0.3+json")
            .add("data", data)
            .add("length", data.length())
            .build();
    }

    private static String bundleJson(final String marker) {
        return String.format(
            "{\"predicateType\":\"https://slsa.dev/provenance/v1\",\"marker\":\"%s\"}",
            marker
        );
    }

    private static JsonObject payload() {
        return Json.createReader(
            new StringReader(
                new String(
                    new TestResource("json/cli_publish.json").asBytes(), StandardCharsets.UTF_8
                )
            )
        ).readObject();
    }

    private static JsonObject withVersionField(
        final JsonObject payload, final String field, final String value
    ) {
        final JsonObject manifest = payload.getJsonObject("versions").getJsonObject("1.0.1");
        return Json.createObjectBuilder(payload)
            .add(
                "versions",
                Json.createObjectBuilder().add(
                    "1.0.1", Json.createObjectBuilder(manifest).add(field, value)
                )
            )
            .build();
    }
}
