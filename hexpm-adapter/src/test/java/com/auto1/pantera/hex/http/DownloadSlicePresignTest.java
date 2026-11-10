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
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.hex.ResourceUtil;
import com.auto1.pantera.hex.proto.generated.PackageOuterClass;
import com.auto1.pantera.hex.proto.generated.SignedOuterClass;
import com.auto1.pantera.hex.utils.Gzip;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.security.policy.Policy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * WS1.7 presigned-direct-download tests for {@link DownloadSlice}: only the
 * {@code /tarballs/} package-byte route is redirect-eligible; the {@code
 * /packages/} registry-record route MUST keep streaming even under a {@link
 * DownloadMode#REDIRECT} policy with a presign-capable backend -- and, when
 * the repository signs its registry, the record served is the re-signed one,
 * which a redirect would have bypassed. Adapter-level proof that registry
 * metadata is never redirected.
 */
@Timeout(15)
final class DownloadSlicePresignTest {

    private static final String PRESIGNED =
        "https://blobs.example.test/tarballs/decimal-2.0.0.tar?sig=abc";

    private static final String REPO = "presign-hex";

    private static final String TARBALL = "tarballs/decimal-2.0.0.tar";

    private static final String RECORD = "packages/decimal";

    private static final DownloadPolicy REDIRECT =
        new DownloadPolicy(DownloadMode.REDIRECT, 600L);

    @Test
    void tarballRedirectsButPackagesStream() {
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        storage.save(new Key.From(TARBALL), content("tar")).join();
        storage.save(new Key.From(RECORD), content("registry")).join();
        final DownloadSlice slice = new DownloadSlice(storage, REDIRECT);

        final Response tarball = get(slice, "/" + TARBALL);
        MatcherAssert.assertThat(
            "the package-tarball GET must redirect (302) under REDIRECT policy",
            tarball.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "redirect must point at the presigned URL",
            new RqHeaders.Single(tarball.headers(), Location.NAME).asString(),
            new IsEqual<>(PRESIGNED)
        );
        MatcherAssert.assertThat(
            "exactly one presign for the single tarball GET",
            storage.presignCalls.get(), new IsEqual<>(1)
        );

        MatcherAssert.assertThat(
            "the /packages/ registry-metadata route must stream (200), never redirect",
            get(slice, "/" + RECORD).status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "registry metadata must not have triggered any further presign attempts",
            storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void signedRegistryRecordStreamsResignedWhileTarballRedirects() throws Exception {
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        storage.save(new Key.From(TARBALL), content("tar")).join();
        storage.save(new Key.From(RECORD), resource(RECORD)).join();
        final RegistrySigner signer = new RegistrySigner();
        final DownloadSlice slice = new DownloadSlice(storage, REPO, signer, REDIRECT);

        final Response record = get(slice, "/" + RECORD);
        MatcherAssert.assertThat(
            "a signed registry record must stream (200) under REDIRECT policy",
            record.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the registry record must never be presigned",
            storage.presignCalls.get(), new IsEqual<>(0)
        );
        final SignedOuterClass.Signed signed = SignedOuterClass.Signed.parseFrom(
            new Gzip(record.body().asBytes()).decompress()
        );
        MatcherAssert.assertThat(
            "the served record must be re-signed for this repository",
            PackageOuterClass.Package.parseFrom(signed.getPayload()).getRepository(),
            new IsEqual<>(REPO)
        );
        final Signature verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(publicKey(signer));
        verifier.update(signed.getPayload().toByteArray());
        MatcherAssert.assertThat(
            "the served record's signature must verify with the repository's public key",
            verifier.verify(signed.getSignature().toByteArray()), new IsEqual<>(true)
        );

        MatcherAssert.assertThat(
            "the tarball GET must still redirect (302) beside a configured signer",
            get(slice, "/" + TARBALL).status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "exactly one presign, for the tarball only",
            storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void hexSliceWiresSignerAndPolicyTogether() throws Exception {
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        storage.save(new Key.From(TARBALL), content("tar")).join();
        storage.save(new Key.From(RECORD), resource(RECORD)).join();
        final RegistrySigner signer = new RegistrySigner();
        final HexSlice slice = new HexSlice(
            storage, Policy.FREE,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            Optional.empty(), REPO,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            signer, REDIRECT
        );
        final Headers auth = Headers.from(new Authorization.Basic("alice", "pw"));

        final Response tarball = slice.response(
            new RequestLine("GET", "/" + TARBALL), auth, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the tarball route must redirect (302) through HexSlice",
            tarball.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "redirect must point at the presigned URL",
            new RqHeaders.Single(tarball.headers(), Location.NAME).asString(),
            new IsEqual<>(PRESIGNED)
        );

        final Response key = slice.response(
            new RequestLine("GET", "/public_key"), auth, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "/public_key must serve the signer's PEM (200)",
            key.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "/public_key body must be a PEM public key",
            key.body().asString(), new StringStartsWith("-----BEGIN PUBLIC KEY-----")
        );

        final Response record = slice.response(
            new RequestLine("GET", "/" + RECORD), auth, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the registry record must stream (200) through HexSlice",
            record.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the served record must name this repository",
            PackageOuterClass.Package.parseFrom(
                SignedOuterClass.Signed.parseFrom(
                    new Gzip(record.body().asBytes()).decompress()
                ).getPayload()
            ).getRepository(),
            new IsEqual<>(REPO)
        );
        MatcherAssert.assertThat(
            "exactly one presign, for the tarball only",
            storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    private static Response get(final Slice slice, final String path) {
        return slice.response(
            new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY
        ).toCompletableFuture().join();
    }

    private static Content content(final String value) {
        return new Content.From(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Content resource(final String path) throws Exception {
        return new Content.From(Files.readAllBytes(new ResourceUtil(path).asPath()));
    }

    private static PublicKey publicKey(final RegistrySigner signer) throws Exception {
        final String pem = new String(signer.publicKeyPem(), StandardCharsets.US_ASCII)
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    /** {@link Storage} that also presigns -- the "bare presigner" composition. */
    private static final class PresigningStorage implements Storage, Presigner {

        private final Storage delegate;
        private final AtomicInteger presignCalls = new AtomicInteger();

        PresigningStorage(final Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public URI presignGet(final Key key, final long ttlSeconds) {
            this.presignCalls.incrementAndGet();
            return URI.create(PRESIGNED);
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.delegate.exists(key);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.delegate.metadata(key);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.delegate.list(prefix);
        }

        @Override
        public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
            return this.delegate.list(prefix, delimiter);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.delegate.value(key);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content data) {
            return this.delegate.save(key, data);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.delegate.move(source, destination);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.delegate.delete(key);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.delegate.exclusively(key, operation);
        }
    }
}
