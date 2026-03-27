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
package com.auto1.pantera.conda.asto;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.misc.UncheckedIOFunc;
import com.auto1.pantera.asto.streams.StorageValuePipeline;
import com.auto1.pantera.conda.meta.MergedJson;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.json.JsonObject;

/**
 * Asto merged json adds packages metadata to repodata index, reading and writing to/from
 * abstract storage.
 * @since 0.4
 */
public final class AstoMergedJson {

    /**
     * Abstract storage.
     */
    private final Storage asto;

    /**
     * Repodata file key.
     */
    private final Key key;

    /**
     * Ctor.
     * @param asto Abstract storage
     * @param key Repodata file key
     */
    public AstoMergedJson(final Storage asto, final Key key) {
        this.asto = asto;
        this.key = key;
    }

    /**
     * Merges or adds provided new packages items into repodata.json.
     * @param items Items to merge
     * @return Completable operation
     */
    public CompletionStage<Void> merge(final Map<String, JsonObject> items) {
        return new StorageValuePipeline<>(this.asto, this.key).process(
            (opt, out) -> {
                try {
                    final JsonFactory factory = new JsonFactory();
                    final Optional<JsonParser> parser = opt.map(
                        new UncheckedIOFunc<>(factory::createParser)
                    );
                    new MergedJson.Jackson(
                        factory.createGenerator(out),
                        parser
                    ).merge(items);
                    if (parser.isPresent()) {
                        parser.get().close();
                    }
                } catch (final IOException err) {
                    throw new PanteraIOException(err);
                }
            }
        );
    }
}
