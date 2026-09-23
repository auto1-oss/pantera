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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.hex.ResourceUtil;
import com.auto1.pantera.hex.proto.generated.PackageOuterClass;
import com.auto1.pantera.hex.proto.generated.SignedOuterClass;
import com.auto1.pantera.hex.utils.Gzip;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What mix needs from a Hex repository: a signed registry verifiable with the
 * key served at {@code /public_key}, records naming the repository they come
 * from, and {@code mix hex.publish}'s release endpoint.
 *
 * @since 2.2.9
 */
final class HexRegistryClientTest {

    /**
     * Repository name.
     */
    private static final String REPO = "my-hex";

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Slice.
     */
    private HexSlice slice;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.slice = new HexSlice(
            this.storage, Policy.FREE,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            Optional.empty(), HexRegistryClientTest.REPO
        );
    }

    @Test
    void servesPublicKeyAsPem() {
        final Response rsp = this.request(RqMethod.GET, "/public_key", Content.EMPTY);
        MatcherAssert.assertThat("status", rsp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "pem", rsp.body().asString(), new StringStartsWith("-----BEGIN PUBLIC KEY-----")
        );
    }

    @Test
    void mixPublishReleaseEndpointPublishes() throws Exception {
        MatcherAssert.assertThat(
            this.publish("/packages/decimal/releases").status(),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    @Test
    void registryIsSignedAndNamesTheRepository() throws Exception {
        this.publish("/publish?replace=false");
        final SignedOuterClass.Signed signed = SignedOuterClass.Signed.parseFrom(
            new Gzip(
                this.request(RqMethod.GET, "/packages/decimal", Content.EMPTY).body().asBytes()
            ).decompress()
        );
        final Signature verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(this.publicKey());
        verifier.update(signed.getPayload().toByteArray());
        MatcherAssert.assertThat(
            "signature verifies with /public_key",
            verifier.verify(signed.getSignature().toByteArray()), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "repository is the Pantera repository name",
            PackageOuterClass.Package.parseFrom(signed.getPayload()).getRepository(),
            new IsEqual<>(HexRegistryClientTest.REPO)
        );
    }

    private Response publish(final String path) throws Exception {
        return this.request(
            RqMethod.POST, path,
            new Content.From(
                Files.readAllBytes(new ResourceUtil("tarballs/decimal-2.0.0.tar").asPath())
            )
        );
    }

    private PublicKey publicKey() throws Exception {
        final String pem = this.request(RqMethod.GET, "/public_key", Content.EMPTY)
            .body().asString()
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private Response request(final RqMethod method, final String path, final Content body) {
        return this.slice.response(
            new RequestLine(method, path),
            Headers.from(new Authorization.Basic("alice", "pw")),
            body
        ).join();
    }
}
