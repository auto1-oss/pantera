/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
