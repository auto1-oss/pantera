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
package com.auto1.pantera.maven.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/**
 * Detached PGP / OpenPGP signature verifier for Maven {@code .asc}
 * sidecars — the T-S03 trust layer.
 *
 * <p>Industry context: Sonatype's Central recommends GPG-signed
 * artifacts; Maven Central rejects unsigned uploads for the Central
 * tier. Pantera proxies these signatures but historically did not
 * verify them — a man-in-the-middle could swap an unsigned artifact
 * and downstream consumers would have no way to detect it. This
 * verifier closes that gap when the per-repo {@code verifyPgp} flag
 * is enabled.
 *
 * <p>The verifier is stateless: each call resolves the signing key ID
 * from the {@code .asc} signature packet, looks up the matching public
 * key in the supplied {@link KeyringStore}, and runs BC's
 * {@code PGPSignature#verify}.
 *
 * <p>Failure modes are explicitly reported via {@link Result} so
 * callers can distinguish "no signature" from "signature present but
 * verification failed" — the latter is a hard reject (T-S03 spec:
 * 403 + audit event); the former is the per-repo policy decision.
 *
 * @since 2.2.0
 */
public final class PgpVerifier {

    /** Keyring store — provides trusted public keys by long key ID. */
    private final KeyringStore keyring;

    /**
     * Ctor.
     *
     * @param keyring Trusted key store
     */
    public PgpVerifier(final KeyringStore keyring) {
        this.keyring = keyring;
    }

    /**
     * Verify a detached ASCII-armored signature against a payload.
     *
     * @param payload Raw bytes the signature was computed over (e.g. the
     *                {@code .jar} primary)
     * @param asciiArmoredSignature ASCII-armored signature bytes (the
     *                              {@code .asc} sidecar content)
     * @return Verification outcome
     */
    public Result verify(final byte[] payload, final byte[] asciiArmoredSignature) {
        if (payload == null || payload.length == 0) {
            return Result.MALFORMED;
        }
        if (asciiArmoredSignature == null || asciiArmoredSignature.length == 0) {
            return Result.MISSING_SIGNATURE;
        }
        try {
            final PGPSignature signature = readSignature(asciiArmoredSignature);
            if (signature == null) {
                return Result.MALFORMED;
            }
            final Optional<PGPPublicKey> key =
                this.keyring.findByKeyId(signature.getKeyID());
            if (key.isEmpty()) {
                return Result.UNTRUSTED_KEY;
            }
            signature.init(
                new BcPGPContentVerifierBuilderProvider(), key.get()
            );
            signature.update(payload);
            if (signature.verify()) {
                return Result.VERIFIED;
            }
            return Result.TAMPERED;
        } catch (final IOException | PGPException ex) {
            return Result.MALFORMED;
        }
    }

    /**
     * Parse the first signature from an ASCII-armored {@code .asc} block.
     *
     * @param asc ASCII-armored signature bytes
     * @return The first signature in the block, or {@code null} when the
     *         block contains no signatures
     * @throws IOException on IO failure
     * @throws PGPException on PGP decoding failure
     */
    private static PGPSignature readSignature(final byte[] asc)
        throws IOException, PGPException {
        try (InputStream decoder = PGPUtil.getDecoderStream(
            new ByteArrayInputStream(asc)
        )) {
            final PGPObjectFactory factory = new BcPGPObjectFactory(decoder);
            Object obj = factory.nextObject();
            // .asc files may wrap the signature list inside a
            // PGPCompressedData container (rare for Maven, but spec-allowed).
            if (obj instanceof PGPCompressedData compressed) {
                final PGPObjectFactory inner =
                    new BcPGPObjectFactory(compressed.getDataStream());
                obj = inner.nextObject();
            }
            if (obj instanceof PGPSignatureList list && !list.isEmpty()) {
                return list.get(0);
            }
            // Stray single PGPSignature object — older tools emit this.
            if (obj instanceof PGPSignature signature) {
                return signature;
            }
            // Walk a few more entries — some .asc files put the signature
            // after a literal-data marker.
            int depth = 0;
            Object next = factory.nextObject();
            while (next != null && depth < 4) {
                if (next instanceof PGPSignatureList list && !list.isEmpty()) {
                    return list.get(0);
                }
                if (next instanceof PGPSignature signature) {
                    return signature;
                }
                next = factory.nextObject();
                depth++;
            }
            return null;
        }
    }

    /**
     * Verification outcome — used by the slice to decide whether to serve,
     * to reject with 403, or to bypass when no signature is present.
     */
    public enum Result {

        /** Signature present and matches a trusted key over the payload. */
        VERIFIED,

        /** Signature ASCII-armor present but verify() returned false. */
        TAMPERED,

        /** Signature present but signing key is not in the keyring. */
        UNTRUSTED_KEY,

        /** No signature bytes were supplied. */
        MISSING_SIGNATURE,

        /** Signature bytes could not be parsed as a PGP packet stream. */
        MALFORMED
    }
}
