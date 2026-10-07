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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.npm.InvalidPublishException;
import com.auto1.pantera.npm.MetaUpdate;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.Publish;
import com.auto1.pantera.npm.PublishedVersion;
import com.auto1.pantera.npm.TgzArchive;
import com.auto1.pantera.npm.http.attestation.AttestationStore;
import com.auto1.pantera.npm.security.NpmPackageSigner;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The NPM publish front.
 * The main goal is to consume a json uploaded by
 * {@code npm publish command} and to:
 *  1. to generate source archives
 *  2. meta.json file
 */
public final class CliPublish implements Publish {
    /**
     * Pattern for `referer` header value.
     */
    public static final Pattern HEADER = Pattern.compile("publish.*");

    /**
     * Attachments json field name.
     */
    private static final String ATTACHMENTS = "_attachments";

    /**
     * Package name json field name.
     */
    private static final String NAME = "name";

    /**
     * The storage.
     */
    private final Storage storage;

    /**
     * Attestation/provenance bundle store (WS4-npm.1).
     */
    private final AttestationStore attestations;

    /**
     * Registry package signer — signs {@code dist.signatures} at publish
     * time so {@code npm audit signatures} has something real to verify
     * against {@code GET /-/npm/v1/keys} (WS4-npm.1, security decision S1 = WIRE).
     */
    private final NpmPackageSigner signer;

    /**
     * Whether published versions are immutable.
     */
    private final boolean immutable;

    /**
     * Constructor of a mutable repository's publish front: a re-publish
     * overwrites the version.
     * @param storage The storage.
     */
    public CliPublish(final Storage storage) {
        this(storage, false);
    }

    /**
     * Constructor.
     * @param storage The storage.
     * @param immutable When true a publish of an already published version
     *  fails with {@link com.auto1.pantera.npm.VersionExistsException}
     *  before anything is written; when false it overwrites the version
     */
    public CliPublish(final Storage storage, final boolean immutable) {
        this.storage = storage;
        this.attestations = new AttestationStore(storage);
        this.signer = new NpmPackageSigner(storage);
        this.immutable = immutable;
    }

    @Override
    public CompletableFuture<Publish.PackageInfo> publishWithInfo(
        final Key prefix, final Key artifact
    ) {
        return this.artifactJson(artifact).thenCompose(
            uploaded -> {
                final String version;
                try {
                    version = new PublishedVersion(uploaded).validated();
                } catch (final InvalidPublishException ex) {
                    return CompletableFuture.failedFuture(ex);
                }
                final Supplier<CompletionStage<Publish.PackageInfo>> write =
                    () -> new MetaUpdate.ByJson(uploaded).update(prefix, this.storage)
                        .thenCompose(ignored -> this.signPublishedVersion(prefix, uploaded, version))
                        .thenCompose(ignored -> this.updateSourceArchives(uploaded, version))
                        .thenApply(
                            size -> new PackageInfo(
                                prefix.toString(), version, size,
                                new Key.From(
                                    prefix.string(), "-",
                                    String.format("%s-%s.tgz", prefix.string(), version)
                                ).string()
                            )
                        );
                if (!this.immutable) {
                    return write.get().toCompletableFuture();
                }
                final List<Key> tarballs;
                try {
                    tarballs = CliPublish.tarballs(prefix, uploaded, version);
                } catch (final InvalidPublishException ex) {
                    return CompletableFuture.failedFuture(ex);
                }
                // Check and write under one lock: a concurrent publish of the
                // same version must see this one's files, not race past the
                // check.
                return new ImmutableVersionGuard(this.storage)
                    .guarded(prefix, version, tarballs, write);
            }
        );
    }

    @Override
    public CompletableFuture<Void> publish(final Key prefix, final Key artifact) {
        return this.publishWithInfo(prefix, artifact).thenAccept(info -> { });
    }

    /**
     * The tarball keys an immutable publish writes, after checking the
     * payload is consistent: it names this package and attaches only the
     * tarball of the version it publishes (an attestation bundle is stored
     * for that version only and is covered by the version check).
     *
     * @param prefix Package key
     * @param uploaded The uploaded json
     * @param version Version the publish writes
     * @return Tarball keys
     * @throws InvalidPublishException For an inconsistent payload
     */
    private static List<Key> tarballs(
        final Key prefix, final JsonObject uploaded, final String version
    ) {
        final String name = uploaded.getString(CliPublish.NAME, null);
        if (!prefix.string().equals(name)) {
            throw new InvalidPublishException(
                String.format(
                    "publish payload name %s does not match the package %s",
                    name, prefix.string()
                )
            );
        }
        final String expected = String.format("%s-%s.tgz", name, version);
        final JsonObject attachments = CliPublish.attachments(uploaded);
        final List<Key> tarballs = new ArrayList<>(1);
        for (final String file : attachments.keySet()) {
            if (CliPublish.isAttestationBundle(file, attachments.getJsonObject(file))) {
                continue;
            }
            if (!expected.equals(file)) {
                throw new InvalidPublishException(
                    String.format(
                        "publish payload targets version %s but attaches tarball %s",
                        version, file
                    )
                );
            }
            tarballs.add(new Key.From(name, "-", file));
        }
        return tarballs;
    }

    /**
     * Get package json.
     * @param artifact Artifact key
     * @return Completable action with json
     */
    private CompletableFuture<JsonObject> artifactJson(final Key artifact) {
        return this.storage.value(artifact)
            .thenCompose(Content::asJsonObjectFuture);
    }

    /**
     * Generate .tgz archives extracted from the uploaded json — or, for an
     * {@code npm publish --provenance} attachment, route the bundle to the
     * attestation sidecar store instead (never mis-stored as a tarball).
     *
     * @param uploaded The uploaded json
     * @param version Version the publish writes
     * @return Completion or error signal carrying the total tarball size
     *  (attestation bundles do not count toward package size).
     */
    private CompletableFuture<Long> updateSourceArchives(
        final JsonObject uploaded, final String version
    ) {
        final AtomicLong size = new AtomicLong();
        final JsonObject attachments = CliPublish.attachments(uploaded);
        final String pkgName = uploaded.getString(CliPublish.NAME, null);
        final List<CompletableFuture<Void>> futures = new ArrayList<>(attachments.size());
        for (final String file : attachments.keySet()) {
            final JsonObject attachment = attachments.getJsonObject(file);
            if (CliPublish.isAttestationBundle(file, attachment)) {
                futures.add(this.storeAttestation(pkgName, version, attachment));
            } else {
                final byte[] bytes = new TgzArchive(attachment.getString("data")).bytes();
                futures.add(
                    this.storage.save(
                        new Key.From(pkgName, "-", file), new Content.From(bytes)
                    ).toCompletableFuture()
                );
                size.getAndAdd(bytes.length);
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> size.get());
    }

    /**
     * Distinguish an {@code npm publish --provenance} attestation/Sigstore
     * bundle attachment from a regular tarball attachment. Unknown/unmatched
     * attachments fall through to the existing tarball path unchanged
     * (strictly additive — see WS4-npm.1 risk notes: the exact carrier
     * content-type is a VERIFY item against the live npm CLI).
     *
     * @param file Attachment key (filename)
     * @param attachment Attachment JSON object
     * @return True when this attachment is an attestation bundle, not a tarball
     */
    private static boolean isAttestationBundle(final String file, final JsonObject attachment) {
        final String contentType = attachment.getString("content_type", "")
            .toLowerCase(Locale.ROOT);
        final String lowerFile = file.toLowerCase(Locale.ROOT);
        return contentType.contains("sigstore")
            || lowerFile.endsWith(".sigstore")
            || lowerFile.endsWith(".sigstore.json");
    }

    /**
     * Base64-decode and persist a provenance/attestation bundle attachment.
     *
     * @param pkgName Package name
     * @param version Version being published
     * @param attachment Attachment JSON object (its {@code data} field is base64)
     * @return Completion stage
     */
    private CompletableFuture<Void> storeAttestation(
        final String pkgName, final String version, final JsonObject attachment
    ) {
        final byte[] bundle = Base64.getDecoder().decode(attachment.getString("data"));
        return this.attestations.store(pkgName, version, bundle).whenComplete((ignored, error) -> {
            final EcsLogger logger = error == null
                ? EcsLogger.info("com.auto1.pantera.npm") : EcsLogger.warn("com.auto1.pantera.npm");
            logger.message(
                error == null
                    ? "Stored npm provenance/attestation bundle from publish attachment"
                    : "Failed to store npm provenance/attestation bundle"
            )
                .eventCategory("file")
                .eventAction("attestation_store")
                .eventOutcome(error == null ? "success" : "failure")
                .field("package.name", pkgName)
                .field("package.version", version)
                .field("log.source", "application")
                .log();
        });
    }

    /**
     * Sign the just-published version's {@code dist} block with the
     * registry's own keypair, the same way the public npm registry signs
     * published packages, so {@code npm audit signatures} has something
     * real to verify. A no-op when the version has no {@code dist.integrity}
     * (never blocks or corrupts an otherwise-valid publish).
     *
     * @param packageKey Package key
     * @param uploaded Uploaded publish payload
     * @param version Version the publish wrote
     * @return Completion stage
     */
    private CompletableFuture<Void> signPublishedVersion(
        final Key packageKey, final JsonObject uploaded, final String version
    ) {
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        return layout.readVersion(packageKey, version).thenCompose(versionJson -> {
            if (versionJson.isEmpty() || !versionJson.containsKey("dist")) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            final String integrity = versionJson.getJsonObject("dist").getString("integrity", null);
            final String name = versionJson.getString("name", uploaded.getString(CliPublish.NAME, packageKey.string()));
            return this.signer.sign(name, version, integrity).thenCompose(signed -> {
                if (signed.isEmpty()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                final JsonObject distWithSig = Json.createObjectBuilder(versionJson.getJsonObject("dist"))
                    .add(
                        "signatures",
                        Json.createArrayBuilder().add(
                            Json.createObjectBuilder()
                                .add("keyid", signed.get().keyId())
                                .add("sig", signed.get().sig())
                                .build()
                        ).build()
                    )
                    .build();
                final JsonObject patched = Json.createObjectBuilder(versionJson)
                    .add("dist", distWithSig)
                    .build();
                return layout.writeVersion(packageKey, version, patched).toCompletableFuture();
            });
        }).toCompletableFuture();
    }

    /**
     * Attachments of the uploaded json.
     * @param uploaded The uploaded json
     * @return Attachments, empty when the payload carries none
     */
    private static JsonObject attachments(final JsonObject uploaded) {
        final JsonObject res;
        if (uploaded.get(CliPublish.ATTACHMENTS) instanceof JsonObject) {
            res = uploaded.getJsonObject(CliPublish.ATTACHMENTS);
        } else {
            res = JsonValue.EMPTY_JSON_OBJECT;
        }
        return res;
    }
}
