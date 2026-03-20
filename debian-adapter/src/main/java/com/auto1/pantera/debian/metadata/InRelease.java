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
package com.auto1.pantera.debian.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.GpgConfig;
import com.auto1.pantera.debian.misc.GpgClearsign;

import java.util.concurrent.CompletionStage;

/**
 * InRelease index file.
 * Check the <a href="https://wiki.debian.org/DebianRepository/Format#A.22Release.22_files">docs</a>
 * for more information.
 * @since 0.4
 */
public interface InRelease {

    /**
     * Generates InRelease index file by provided Release index.
     * @param release Release index key
     * @return Completion action
     */
    CompletionStage<Void> generate(Key release);

    /**
     * Key (storage item key) of the InRelease index.
     * @return Storage item
     */
    Key key();

    /**
     * Implementation of {@link InRelease} from abstract storage.
     * @since 0.4
     */
    final class Asto implements InRelease {

        /**
         * Abstract storage.
         */
        private final Storage asto;

        /**
         * Repository config.
         */
        private final Config config;

        /**
         * Ctor.
         * @param asto Abstract storage
         * @param config Repository config
         */
        public Asto(final Storage asto, final Config config) {
            this.asto = asto;
            this.config = config;
        }

        @Override
        public CompletionStage<Void> generate(final Key release) {
            final CompletionStage<Void> res;
            if (this.config.gpg().isPresent()) {
                final GpgConfig gpg = this.config.gpg().get();
                res = this.asto.value(release)
                    .thenCompose(Content::asBytesFuture)
                    .thenCompose(
                        bytes -> gpg.key().thenApply(
                            key -> new GpgClearsign(bytes).signedContent(key, gpg.password())
                        )
                    ).thenCompose(bytes -> this.asto.save(this.key(), new Content.From(bytes)));
            } else {
                res = this.asto.value(release).thenCompose(
                    content -> this.asto.save(this.key(), content)
                );
            }
            return res;
        }

        @Override
        public Key key() {
            return new Key.From("dists", this.config.codename(), "InRelease");
        }
    }
}
