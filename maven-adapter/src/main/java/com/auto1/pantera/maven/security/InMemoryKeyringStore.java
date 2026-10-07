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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;

/**
 * In-memory {@link KeyringStore} backed by a {@link ConcurrentHashMap}.
 * Used by tests, by the no-DB boot, and as a fast L1 layer fronting the
 * JDBC store.
 *
 * @since 2.2.0
 */
public final class InMemoryKeyringStore implements KeyringStore {

    /** Map from long key ID to the parsed public key. */
    private final Map<Long, PGPPublicKey> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<PGPPublicKey> findByKeyId(final long keyId) {
        return Optional.ofNullable(this.byId.get(keyId));
    }

    /**
     * Add an ASCII-armored public key block to the store. The block may
     * contain a single key or a key ring (a master + sub-keys); every key
     * is indexed by its long key ID.
     *
     * @param asciiArmored ASCII-armored {@code -----BEGIN PGP PUBLIC KEY
     *                     BLOCK-----} bytes
     * @throws IOException on IO failure
     * @throws PGPException on PGP decoding failure
     */
    public void addAsciiArmored(final byte[] asciiArmored)
        throws IOException, PGPException {
        Objects.requireNonNull(asciiArmored, "asciiArmored");
        try (InputStream decoder = PGPUtil.getDecoderStream(
            new ByteArrayInputStream(asciiArmored)
        )) {
            final PGPPublicKeyRingCollection rings =
                new BcPGPPublicKeyRingCollection(decoder);
            final Iterator<PGPPublicKeyRing> ringIter = rings.getKeyRings();
            while (ringIter.hasNext()) {
                final PGPPublicKeyRing ring = ringIter.next();
                final Iterator<PGPPublicKey> keyIter = ring.getPublicKeys();
                while (keyIter.hasNext()) {
                    final PGPPublicKey key = keyIter.next();
                    this.byId.put(key.getKeyID(), key);
                }
            }
        }
    }

    /**
     * Remove every key from the store. Used by tests to reset state.
     */
    public void clear() {
        this.byId.clear();
    }

    /**
     * @return Number of indexed keys
     */
    public int size() {
        return this.byId.size();
    }

    /**
     * Parse an ASCII-armored public key block and describe every key it
     * contains (a master key plus any sub-keys) — one {@link KeyDescriptor}
     * per key, each carrying the same original armored text. Used by the
     * admin keyring-upload endpoint (WS4-maven.3) to derive the
     * {@code key_id_hex}/{@code fingerprint} pair(s) to persist, without
     * duplicating this parsing logic in {@code pantera-main}.
     *
     * @param asciiArmored ASCII-armored public key block bytes
     * @return One descriptor per key found in the block
     * @throws IOException on IO failure
     * @throws PGPException on PGP decoding failure
     */
    public static List<KeyDescriptor> describeKeys(final byte[] asciiArmored)
        throws IOException, PGPException {
        Objects.requireNonNull(asciiArmored, "asciiArmored");
        final List<KeyDescriptor> descriptors = new ArrayList<>();
        try (InputStream decoder = PGPUtil.getDecoderStream(
            new ByteArrayInputStream(asciiArmored)
        )) {
            final PGPPublicKeyRingCollection rings = new BcPGPPublicKeyRingCollection(decoder);
            final Iterator<PGPPublicKeyRing> ringIter = rings.getKeyRings();
            while (ringIter.hasNext()) {
                final Iterator<PGPPublicKey> keyIter = ringIter.next().getPublicKeys();
                while (keyIter.hasNext()) {
                    descriptors.add(describe(keyIter.next()));
                }
            }
        }
        return descriptors;
    }

    /**
     * @param key Parsed public key
     * @return Descriptor with the 16-char uppercase hex long key id and the
     *         40-char uppercase hex SHA-1 fingerprint
     */
    private static KeyDescriptor describe(final PGPPublicKey key) {
        final StringBuilder fingerprint = new StringBuilder(40);
        for (final byte b : key.getFingerprint()) {
            fingerprint.append(String.format(Locale.ROOT, "%02X", b));
        }
        return new KeyDescriptor(
            String.format(Locale.ROOT, "%016X", key.getKeyID()),
            fingerprint.toString()
        );
    }

    /**
     * A single key's identity, as derived from an uploaded ASCII-armored
     * block.
     *
     * @param keyIdHex 16-char uppercase hex long key id
     * @param fingerprintHex 40-char uppercase hex SHA-1 fingerprint
     */
    public record KeyDescriptor(String keyIdHex, String fingerprintHex) {
    }
}
