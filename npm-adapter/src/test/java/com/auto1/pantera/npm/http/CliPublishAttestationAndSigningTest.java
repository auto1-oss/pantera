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
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.http.attestation.AttestationStore;
import com.auto1.pantera.npm.security.NpmSigningKeys;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * WS4-npm.1 (npm provenance/attestations, security decision S1 = WIRE): a
 * publish that includes an {@code npm publish --provenance}-style attestation
 * attachment must (a) route the bundle to the attestation sidecar rather
 * than mis-store it as a tarball, and (b) still sign the published version's
 * {@code dist} block with the registry's own keypair — cryptographically
 * verifiable against {@code GET /-/npm/v1/keys}.
 */
final class CliPublishAttestationAndSigningTest {

    @Test
    void routesAttestationAttachmentToStoreLeavingTarballAloneAndSignsTheVersion() throws Exception {
        final Storage asto = new InMemoryStorage();
        final Key prefix = new Key.From("@hello/simple-npm-project");
        final Key name = new Key.From("uploaded-artifact");
        asto.save(name, new Content.From(withAttestationAttachment())).join();

        new CliPublish(asto).publish(prefix, name).join();

        MatcherAssert.assertThat(
            "the real tarball attachment is still stored as a tarball",
            asto.exists(new Key.From(prefix, "-", "@hello/simple-npm-project-1.0.1.tgz")).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the provenance attachment is never mis-stored as a tarball",
            asto.exists(new Key.From(prefix, "-", "@hello/simple-npm-project-1.0.1.sigstore")).join(),
            new IsEqual<>(false)
        );
        final boolean bundleStored = new AttestationStore(asto)
            .read("@hello/simple-npm-project", "1.0.1").join().isPresent();
        MatcherAssert.assertThat(
            "the provenance bundle was routed to the attestation sidecar",
            bundleStored,
            new IsEqual<>(true)
        );

        assertPublishedVersionIsSigned(asto, prefix);
    }

    private static void assertPublishedVersionIsSigned(
        final Storage asto, final Key prefix
    ) throws Exception {
        final JsonObject versionJson = new PerVersionLayout(asto)
            .readVersion(prefix, "1.0.1").toCompletableFuture().join();
        final JsonObject dist = versionJson.getJsonObject("dist");
        MatcherAssert.assertThat(
            "dist.signatures was added at publish time",
            dist.containsKey("signatures"),
            new IsEqual<>(true)
        );
        final JsonObject signature = dist.getJsonArray("signatures").getJsonObject(0);
        final NpmSigningKeys.SigningKeyPair pair = new NpmSigningKeys(asto)
            .keyPair().toCompletableFuture().join();
        MatcherAssert.assertThat(
            signature.getString("keyid"),
            new IsEqual<>(pair.keyId())
        );
        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.publicKey());
        verifier.update(
            String.format(
                "%s@%s:%s", "@hello/simple-npm-project", "1.0.1", dist.getString("integrity")
            ).getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(
            "the registry's own signature verifies against its own served public key "
                + "(the check npm audit signatures performs)",
            verifier.verify(Base64.getDecoder().decode(signature.getString("sig"))),
            new IsEqual<>(true)
        );
    }

    /**
     * The stock {@code cli_publish.json} fixture with a second
     * {@code _attachments} entry mimicking an {@code npm publish
     * --provenance} Sigstore bundle attachment.
     */
    private static byte[] withAttestationAttachment() {
        final JsonObject original = Json.createReader(
            new StringReader(new TestResource("json/cli_publish.json").asString())
        ).readObject();
        final String bundle = Base64.getEncoder().encodeToString(
            "{\"predicateType\":\"https://slsa.dev/provenance/v1\"}"
                .getBytes(StandardCharsets.UTF_8)
        );
        final JsonObjectBuilder attachments = Json.createObjectBuilder(
            original.getJsonObject("_attachments")
        );
        attachments.add(
            "@hello/simple-npm-project-1.0.1.sigstore",
            Json.createObjectBuilder()
                .add("content_type", "application/vnd.dev.sigstore.bundle+json")
                .add("data", bundle)
                .add("length", bundle.length())
                .build()
        );
        return Json.createObjectBuilder(original)
            .add("_attachments", attachments.build())
            .build()
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
