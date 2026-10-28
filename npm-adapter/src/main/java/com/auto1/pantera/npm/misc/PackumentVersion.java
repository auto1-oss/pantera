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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.npm.Tarballs;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * One version's manifest ({@code versions[<version>]}) streamed out of a
 * stored packument; nothing else of the document is kept.
 *
 * <p>The manifest is copied token by token from a non-blocking parser to a
 * generator, never built as a tree, so a value nested however deep is copied
 * without recursion. {@code dist.tarball} is re-rooted at the client-facing
 * prefix with {@link Tarballs#rewriteTarball} (absolute upstream URLs
 * included); every other relative {@code tarball} string in the manifest is
 * prefixed, as the packument serve does. Numbers are copied as stored. When
 * the version key occurs more than once only its last value is emitted, the
 * one a client parsing the packument sees, so the manifest is always one
 * JSON value; nothing after the root value of the packument is read.</p>
 *
 * @since 2.2.11
 */
public final class PackumentVersion {

    /**
     * Stored packument.
     */
    private final Content packument;

    /**
     * Version to extract.
     */
    private final String version;

    /**
     * Ctor.
     *
     * @param packument Stored packument
     * @param version Version to extract
     */
    public PackumentVersion(final Content packument, final String version) {
        this.packument = packument;
        this.version = version;
    }

    /**
     * Extract the manifest. The whole packument is consumed either way.
     *
     * @param prefix Client-facing tarball URL prefix
     * @return Compact manifest JSON, empty when the packument has no such version
     */
    public CompletableFuture<Optional<byte[]>> manifest(final String prefix) {
        return Single.defer(
            () -> Flowable.fromPublisher(this.packument)
                .reduce(new Extract(this.version, prefix), Extract::feed)
                .map(Extract::finish)
        ).to(SingleInterop.get()).toCompletableFuture();
    }

    /**
     * Where the extraction is.
     */
    private enum Stage {
        /**
         * Looking for the version key (again, after an occurrence was copied).
         */
        SCAN,
        /**
         * Copying the version's value.
         */
        COPY,
        /**
         * Finished; further input is ignored.
         */
        DONE
    }

    /**
     * How the next string value is rewritten.
     */
    private enum Url {
        /**
         * Copied as stored.
         */
        NONE,
        /**
         * {@code dist.tarball} of the manifest.
         */
        DIST,
        /**
         * Any other {@code tarball}: prefixed when relative.
         */
        RELATIVE
    }

    /**
     * Per-subscription parser/generator state.
     */
    private static final class Extract {

        /**
         * Key of a tarball URL.
         */
        private static final String TARBALL = "tarball";

        /**
         * Version to extract.
         */
        private final String version;

        /**
         * Tarball URL prefix.
         */
        private final String prefix;

        /**
         * Non-blocking parser.
         */
        private final JsonParser parser;

        /**
         * Feeder of {@link #parser}.
         */
        private final ByteArrayFeeder feeder;

        /**
         * Generator output.
         */
        private final ByteArrayOutputStream out;

        /**
         * Generator of the occurrence being copied.
         */
        private JsonGenerator gen;

        /**
         * Where the extraction is.
         */
        private Stage stage;

        /**
         * Containers of the manifest open in the copy.
         */
        private int open;

        /**
         * Rewrite of the next string value.
         */
        private Url url;

        /**
         * Whether an occurrence of the manifest was copied whole.
         */
        private boolean found;

        Extract(final String version, final String prefix) {
            this.version = version;
            this.prefix = prefix;
            this.out = new ByteArrayOutputStream();
            this.stage = Stage.SCAN;
            this.url = Url.NONE;
            try {
                this.parser = PackumentJson.FACTORY.createNonBlockingByteArrayParser();
                this.gen = PackumentJson.FACTORY.createGenerator(this.out);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
            this.feeder = (ByteArrayFeeder) this.parser.getNonBlockingInputFeeder();
        }

        Extract feed(final ByteBuffer chunk) throws IOException {
            if (this.stage != Stage.DONE) {
                final byte[] bytes = new byte[chunk.remaining()];
                chunk.duplicate().get(bytes);
                this.feeder.feedInput(bytes, 0, bytes.length);
                this.drain();
            }
            return this;
        }

        Optional<byte[]> finish() throws IOException {
            if (this.stage != Stage.DONE) {
                this.feeder.endOfInput();
                this.drain();
            }
            this.gen.close();
            final Optional<byte[]> result;
            if (this.found) {
                result = Optional.of(this.out.toByteArray());
            } else {
                result = Optional.empty();
            }
            return result;
        }

        private void drain() throws IOException {
            JsonToken token = this.parser.nextToken();
            while (token != null && token != JsonToken.NOT_AVAILABLE) {
                if (this.stage == Stage.SCAN) {
                    this.scan(token);
                } else {
                    this.copy(token);
                }
                if (this.parser.getParsingContext().inRoot()) {
                    this.stage = Stage.DONE;
                }
                if (this.stage == Stage.DONE) {
                    break;
                }
                token = this.parser.nextToken();
            }
        }

        /**
         * Spot the key of the version in the root {@code versions} object;
         * its value is copied from the next token on, replacing any earlier
         * occurrence.
         *
         * @param token Token
         * @throws IOException On a generator failure
         */
        private void scan(final JsonToken token) throws IOException {
            if (token == JsonToken.FIELD_NAME) {
                final JsonStreamContext ctx = this.parser.getParsingContext();
                final JsonStreamContext parent = ctx.getParent();
                if (parent != null && parent.inObject() && parent.getParent().inRoot()
                    && "versions".equals(parent.getCurrentName())
                    && this.version.equals(ctx.getCurrentName())) {
                    this.gen.close();
                    this.out.reset();
                    this.gen = PackumentJson.FACTORY.createGenerator(this.out);
                    this.found = false;
                    this.stage = Stage.COPY;
                }
            }
        }

        private void copy(final JsonToken token) throws IOException {
            final Url rewrite = this.url;
            this.url = Url.NONE;
            if (token == JsonToken.FIELD_NAME) {
                this.url = this.urlKind(this.parser.currentName());
                this.gen.copyCurrentEvent(this.parser);
            } else if (token.isStructStart()) {
                ++this.open;
                this.gen.copyCurrentEvent(this.parser);
            } else if (token.isStructEnd()) {
                --this.open;
                this.gen.copyCurrentEvent(this.parser);
            } else if (token == JsonToken.VALUE_STRING && rewrite != Url.NONE) {
                this.gen.writeString(this.rewritten(rewrite, this.parser.getText()));
            } else if (token.isNumeric()) {
                this.gen.writeNumber(this.parser.getText());
            } else {
                this.gen.copyCurrentEvent(this.parser);
            }
            if (this.open == 0) {
                this.found = true;
                this.stage = Stage.SCAN;
            }
        }

        /**
         * Rewrite kind of the value of a field of the manifest.
         *
         * @param name Field name
         * @return {@link Url#DIST} for {@code dist.tarball}, {@link Url#RELATIVE}
         *  for any other {@code tarball}
         */
        private Url urlKind(final String name) {
            final Url kind;
            if (!Extract.TARBALL.equals(name)) {
                kind = Url.NONE;
            } else if (this.open == 2 && this.inDist()) {
                kind = Url.DIST;
            } else {
                kind = Url.RELATIVE;
            }
            return kind;
        }

        /**
         * Whether the current object is the manifest's {@code dist}.
         *
         * @return True when the parent container is an object whose current
         *  member is {@code dist}
         */
        private boolean inDist() {
            final JsonStreamContext parent = this.parser.getParsingContext().getParent();
            return parent.inObject() && "dist".equals(parent.getCurrentName());
        }

        private String rewritten(final Url kind, final String stored) {
            final String result;
            if (kind == Url.DIST) {
                result = Tarballs.rewriteTarball(stored, this.prefix);
            } else if (stored.startsWith("/")) {
                result = this.prefix + stored;
            } else {
                result = stored;
            }
            return result;
        }
    }
}
