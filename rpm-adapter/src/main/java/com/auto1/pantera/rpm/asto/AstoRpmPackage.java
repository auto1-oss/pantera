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
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.misc.UncheckedIOFunc;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.RpmMetadata;
import com.auto1.pantera.rpm.pkg.Checksum;
import com.auto1.pantera.rpm.pkg.FilePackageHeader;
import com.auto1.pantera.rpm.pkg.Package;
import java.util.concurrent.CompletionStage;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.redline_rpm.header.Header;

/**
 * Rpm package metadata from the storage.
 * @since 1.9
 */
public final class AstoRpmPackage {

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
    public AstoRpmPackage(final Storage asto, final Digest dgst) {
        this.asto = asto;
        this.dgst = dgst;
    }

    /**
     * Obtain rpm package metadata, instance of {@link Package.Meta}.
     * @param key Package key
     * @return Completable action
     */
    public CompletionStage<Package.Meta> packageMeta(final Key key) {
        return this.packageMeta(key, key.string());
    }

    /**
     * Obtain rpm package metadata, instance of {@link Package.Meta}.
     * @param key Package key
     * @param path Package repository relative path
     * @return Completable action
     */
    public CompletionStage<Package.Meta> packageMeta(final Key key, final String path) {
        return this.asto.value(key).thenCompose(
            val -> new ContentDigest(val, this.dgst::messageDigest).hex().thenApply(
                hex -> new ImmutablePair<>(
                    hex,
                    val.size().orElseThrow(() -> new PanteraException("Content size unknown!"))
                )
            )
        ).thenCompose(
            pair -> this.asto.value(key).thenCompose(
                val -> new ContentAsStream<Header>(val).process(
                    new UncheckedIOFunc<>(input -> new FilePackageHeader(input).header())
                ).thenApply(
                    header -> new RpmMetadata.RpmItem(
                        header, pair.getValue(), new Checksum.Simple(this.dgst, pair.getKey()), path
                    )
                )
            )
        );
    }
}
