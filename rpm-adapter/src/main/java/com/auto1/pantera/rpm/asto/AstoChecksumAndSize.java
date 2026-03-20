/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.rpm.Digest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

/**
 * Calculates storage item checksums and size.
 * @since 1.9
 */
public final class AstoChecksumAndSize {

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Digest algorithm.
     */
    private final Digest dgst;

    /**
     * Ctor.
     * @param asto Asto storage
     * @param dgst Digest algorithm
     */
    public AstoChecksumAndSize(final Storage asto, final Digest dgst) {
        this.asto = asto;
        this.dgst = dgst;
    }

    /**
     * Calculates checksum and size of the item and saves them adding digest
     * algorithm name postfix in text format [hex size].
     * @param key Storage key
     * @return Completable action
     */
    CompletionStage<Void> calculate(final Key key) {
        return this.asto.value(key).thenCompose(
            val -> new ContentDigest(
                val, this.dgst::messageDigest
            ).hex().thenCompose(
                hex -> this.asto.save(
                    new Key.From(String.format("%s.%s", key, this.dgst.name())),
                    new Content.From(
                        String.format(
                            "%s %d", hex,
                            val.size().orElseThrow(
                                () -> new PanteraException("Content size unknown!")
                            )
                        ).getBytes(StandardCharsets.US_ASCII)
                    )
                )
            )
        );
    }
}
