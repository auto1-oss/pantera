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
import com.auto1.pantera.hex.proto.generated.PackageOuterClass;
import com.auto1.pantera.hex.proto.generated.SignedOuterClass;
import com.auto1.pantera.hex.utils.Gzip;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
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
     * @param storage Repository storage.
     */
    public DownloadSlice(final Storage storage) {
        this(storage, Optional.empty());
    }

    /**
     * Download slice serving package records signed for the repository.
     * @param storage Repository storage
     * @param repo Repository name the records must name
     * @param signer Registry signer
     */
    public DownloadSlice(final Storage storage, final String repo, final RegistrySigner signer) {
        this(storage, Optional.of(Map.entry(repo, signer)));
    }

    /**
     * Primary ctor.
     * @param storage Repository storage
     * @param registry Repository name and signer
     */
    private DownloadSlice(final Storage storage,
        final Optional<Map.Entry<String, RegistrySigner>> registry) {
        this.storage = storage;
        this.registry = registry;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String path = line.uri().getPath();
        final Key.From key = new Key.From(path.replaceFirst("/", ""));
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
