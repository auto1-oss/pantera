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
package com.auto1.pantera.misc;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.ext.ContentAs;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.reactivestreams.Publisher;

/**
 * Rx publisher transformer to yaml mapping.
 * @since 0.1
 */
public final class ContentAsYaml
    implements Function<Single<? extends Publisher<ByteBuffer>>, Single<? extends YamlMapping>> {

    @Override
    public Single<? extends YamlMapping> apply(
        final Single<? extends Publisher<ByteBuffer>> content
    ) {
        return new ContentAs<>(
            bytes -> Yaml.createYamlInput(
                new String(bytes, StandardCharsets.US_ASCII)
            ).readYamlMapping()
        ).apply(content);
    }
}
