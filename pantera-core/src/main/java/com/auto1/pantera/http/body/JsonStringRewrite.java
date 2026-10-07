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

package com.auto1.pantera.http.body;

import com.auto1.pantera.asto.Content;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.reactivestreams.Subscriber;

/**
 * Stream-through rewrite of selected string values in a JSON body.
 *
 * <p>The body is never buffered or parsed into a document model: a
 * {@link JsonStringScanner} walks the bytes as they flow, tracks the key
 * path it is at, and hands only the string values that sit at a rule's path
 * to that rule. Every other byte is passed through untouched, so key order,
 * whitespace and escaping of the stored document survive and memory stays at
 * the size of one chunk plus the string being rewritten. A rule that returns
 * its input unchanged leaves the original bytes in place.</p>
 *
 * <p>Rule paths are slash-separated key paths from the root; {@code *}
 * matches any one object key or array element. {@code versions/*}{@code
 * /dist/tarball} selects the tarball of every version of an npm packument,
 * {@code packages/*}{@code /*}{@code /dist/url} the dist of every version of
 * a Composer package, whether its versions are keyed by name (v1) or listed
 * in an array (v2). Keys that contain a slash cannot be addressed.</p>
 *
 * <p>Malformed JSON is passed through as stored: the scanner never throws
 * and only rewrites what it positively recognised.</p>
 *
 * @since 2.2.10
 */
public final class JsonStringRewrite implements Content {

    /**
     * Stored body.
     */
    private final Content origin;

    /**
     * Rules by key path.
     */
    private final Map<String, UnaryOperator<String>> rules;

    /**
     * Ctor.
     *
     * @param origin Stored body
     * @param rules Rewrite function by key path, see class docs for the syntax
     */
    public JsonStringRewrite(final Content origin, final Map<String, UnaryOperator<String>> rules) {
        this.origin = origin;
        this.rules = Map.copyOf(rules);
    }

    @Override
    public Optional<Long> size() {
        return Optional.empty();
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        Flowable.defer(
            () -> {
                final JsonStringScanner scanner = new JsonStringScanner(this.rules);
                return Flowable.fromPublisher(this.origin)
                    .concatMap(chunk -> Flowable.fromIterable(scanner.feed(chunk)))
                    .concatWith(Flowable.defer(() -> Flowable.fromIterable(scanner.finish())));
            }
        ).subscribe(subscriber);
    }
}
