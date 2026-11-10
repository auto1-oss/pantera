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

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.PresignResolver;
import com.auto1.pantera.hex.proto.generated.PackageOuterClass;
import com.auto1.pantera.hex.proto.generated.SignedOuterClass;
import com.auto1.pantera.hex.utils.Gzip;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * This slice returns content as bytes by Key from request path.
 */
public final class DownloadSlice implements Slice {
    /**
     * Path to packages.
     */
    static final String PACKAGES = "packages";

    /**
     * Pattern for packages.
     */
    static final Pattern PACKAGES_PTRN =
        Pattern.compile(String.format("/%s/\\S+", DownloadSlice.PACKAGES));

    /**
     * Path to tarballs.
     */
    static final String TARBALLS = "tarballs";

    /**
     * Pattern for tarballs.
     */
    static final Pattern TARBALLS_PTRN =
        Pattern.compile(String.format("/%s/\\S+", DownloadSlice.TARBALLS));

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Registry signing: repository name and signer; empty serves the
     * stored records as they are.
     */
    private final Optional<Map.Entry<String, RegistrySigner>> registry;

    /**
     * WS1.7 per-repo presigned-direct-download policy. {@link
     * DownloadPolicy#streamOnly()} (the default of every ctor that takes no
     * policy) is byte-identical to pre-WS1.7 behaviour: no {@code 302} is
     * ever issued.
     */
    private final DownloadPolicy policy;

    /**
     * Repo name for the {@code pantera.storage.download.decision} metric tag:
     * the repository the registry signs for when signing is configured,
     * otherwise derived from the outermost {@link SubStorage} prefix.
     */
    private final String repoName;

    /**
     * @param storage Repository storage.
     */
    public DownloadSlice(final Storage storage) {
        this(storage, Optional.empty(), DownloadPolicy.streamOnly());
    }

    /**
     * Download slice with an explicit WS1.7 (spec {@code
     * WS1-storage-for-scale.md} &sect;3.B2) download policy and no registry
     * signing. Only the {@code /tarballs/} package-byte route is
     * redirect-eligible; the {@code /packages/} registry-record route always
     * streams.
     *
     * @param storage Repository storage.
     * @param policy WS1.7 download policy.
     */
    public DownloadSlice(final Storage storage, final DownloadPolicy policy) {
        this(storage, Optional.empty(), policy);
    }

    /**
     * Download slice serving package records signed for the repository.
     * @param storage Repository storage
     * @param repo Repository name the records must name
     * @param signer Registry signer
     */
    public DownloadSlice(final Storage storage, final String repo, final RegistrySigner signer) {
        this(storage, repo, signer, DownloadPolicy.streamOnly());
    }

    /**
     * Download slice serving package records signed for the repository, with
     * an explicit WS1.7 download policy for the {@code /tarballs/} route.
     * A registry record is never redirected: it is re-signed for this
     * repository on the way out, which a redirect would bypass.
     * @param storage Repository storage
     * @param repo Repository name the records must name
     * @param signer Registry signer
     * @param policy WS1.7 download policy
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public DownloadSlice(final Storage storage, final String repo, final RegistrySigner signer,
        final DownloadPolicy policy) {
        this(storage, Optional.of(Map.entry(repo, signer)), policy);
    }

    /**
     * Primary ctor.
     * @param storage Repository storage
     * @param registry Repository name and signer
     * @param policy WS1.7 download policy
     */
    private DownloadSlice(final Storage storage,
        final Optional<Map.Entry<String, RegistrySigner>> registry,
        final DownloadPolicy policy) {
        this.storage = storage;
        this.registry = registry;
        this.policy = policy;
        this.repoName = registry.map(Map.Entry::getKey)
            .orElseGet(() -> DownloadSlice.repoNameOf(storage));
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String path = line.uri().getPath();
        final Key.From key = new Key.From(path.replaceFirst("/", ""));
        final Optional<Response> redirect = this.redirectResponse(line, key);
        if (redirect.isPresent()) {
            // Consume the (empty) GET body -- reactive bodies must always be
            // drained, even when we do not serve them.
            return body.asBytesFuture().thenApply(ignored -> redirect.get());
        }
        final boolean record = this.registry.isPresent()
            && DownloadSlice.PACKAGES_PTRN.matcher(path).matches();
        return this.storage.exists(key)
            .thenCompose(exist -> {
                    if (exist && record) {
                        return this.storage.value(key)
                            .thenCompose(Content::asBytesFuture)
                            .thenApply(
                                bytes -> DownloadSlice.octets(
                                    new Content.From(this.signed(bytes))
                                )
                            );
                    }
                    if (exist) {
                        return this.storage.value(key).thenApply(DownloadSlice::octets);
                    }
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
            );
    }

    /**
     * WS1.7 serving decision: on a package-tarball GET under a non-stream
     * policy, resolve a presigned URL and answer {@code 302 + Location} when
     * one is currently possible (presigner configured + durably present);
     * otherwise record the {@code stream} decision and return empty so the
     * caller falls through to the unchanged streaming path. STREAM mode,
     * non-GET methods and {@code /packages/} registry records never reach
     * the resolver.
     *
     * @param line Request line
     * @param key Storage key derived from the request path
     * @return A {@code 302} response, or empty to serve normally
     */
    private Optional<Response> redirectResponse(final RequestLine line, final Key key) {
        if (this.policy.mode() == DownloadMode.STREAM
            || line.method() != RqMethod.GET
            || !DownloadSlice.isTarball(key)) {
            return Optional.empty();
        }
        final Optional<URI> presigned = PresignResolver.resolve(this.storage, key)
            .flatMap(target -> target.presignIfDurable(this.policy.presignTtlSeconds()));
        final Optional<Response> result;
        if (presigned.isPresent()) {
            this.recordDecision("redirect");
            result = Optional.of(
                ResponseBuilder.found().header(new Location(presigned.get().toString())).build()
            );
        } else {
            this.recordDecision("stream");
            result = Optional.empty();
        }
        return result;
    }

    /**
     * @param key Storage key
     * @return {@code true} iff the key addresses a package tarball ({@code
     *  tarballs/...}) -- the only redirect-eligible route; {@code packages/...}
     *  registry records are excluded
     */
    private static boolean isTarball(final Key key) {
        return key.string().startsWith(DownloadSlice.TARBALLS + "/");
    }

    /**
     * @param storage Repository storage
     * @return Outermost {@link SubStorage} prefix (a repo's storage is
     *  {@code SubStorage(repoName, <alias storage>)}), or {@code "unknown"}
     */
    private static String repoNameOf(final Storage storage) {
        return storage instanceof SubStorage sub ? sub.prefix().string() : "unknown";
    }

    /**
     * WS1.7: record the redirect-vs-stream serving decision (only ever called
     * for redirect-eligible tarball GETs -- {@code policy.mode() != STREAM}).
     *
     * @param decision {@code "redirect"} or {@code "stream"}
     */
    private void recordDecision(final String decision) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordDownloadDecision(this.repoName, decision);
        }
    }

    /**
     * Re-sign a stored package record for this repository: hex_core
     * verifies the signature with the key served at /public_key and checks
     * that the record names the repository the client configured.
     * @param stored Stored gzipped Signed record
     * @return Gzipped, signed record
     */
    private byte[] signed(final byte[] stored) {
        final Map.Entry<String, RegistrySigner> reg = this.registry.orElseThrow();
        try {
            final PackageOuterClass.Package pkg = PackageOuterClass.Package.parseFrom(
                SignedOuterClass.Signed.parseFrom(new Gzip(stored).decompress()).getPayload()
            ).toBuilder().setRepository(reg.getKey()).build();
            final byte[] payload = pkg.toByteArray();
            return new Gzip(
                SignedOuterClass.Signed.newBuilder()
                    .setPayload(ByteString.copyFrom(payload))
                    .setSignature(ByteString.copyFrom(reg.getValue().sign(payload)))
                    .build()
                    .toByteArray()
            ).compress();
        } catch (final InvalidProtocolBufferException ex) {
            throw new PanteraException("Cannot parse stored Hex package record", ex);
        }
    }

    /**
     * Binary response.
     * @param value Content
     * @return Response
     */
    private static Response octets(final Content value) {
        return ResponseBuilder.ok()
            .header(ContentType.mime("application/octet-stream"))
            .body(value)
            .build();
    }
}
