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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.reactivestreams.Subscriber;

/**
 * Token-level streaming rewrite of an npm packument (full or abbreviated).
 *
 * <p>The document is never held whole: a non-blocking Jackson parser is fed
 * the body chunk by chunk and the kept tokens are re-emitted through a
 * generator as each chunk is consumed. Memory is one chunk plus the largest
 * single JSON token (a README string, say), whatever the document size.</p>
 *
 * <p>What happens to each root member is decided by {@link Rules}: copied,
 * dropped, replaced by a given tree, or filtered member by member (the
 * {@code versions} and {@code time} objects). Relative {@code tarball} URLs
 * ({@code "/pkg/-/pkg-1.0.0.tgz"}) can be prefixed on the way through: every
 * kept string value of a {@code tarball} key, at any depth including the
 * root, the same values the unfiltered serve selects with the {@code
 * **}{@code /tarball} rule of
 * {@link com.auto1.pantera.http.body.JsonStringRewrite}.</p>
 *
 * <p>The output is compact JSON; formatting of the stored document is not
 * kept. Numbers are copied as their stored text, so {@code -0.0},
 * {@code 1.10} and {@code 1e5} come out as stored. A {@link Section#MEMBERS},
 * {@link Section#STUBS}, {@link Section#STRINGS} or
 * {@link Section#STRINGS_OR_STUBS} member whose value is not an object is
 * dropped, so the shape of what is kept never depends on the document.</p>
 *
 * <p>Only the first root value is read: whatever follows it (a second
 * document, trailing garbage) is ignored, as a tree parser reading the
 * document would, so the output is always one JSON value.</p>
 *
 * @since 2.2.11
 */
public final class PackumentTransform implements Content {

    /**
     * Mapper providing the parser factory and the codec for replacement trees.
     */
    private static final ObjectMapper MAPPER = PackumentJson.MAPPER;

    /**
     * Stored packument.
     */
    private final Content origin;

    /**
     * What to keep.
     */
    private final Rules rules;

    /**
     * Ctor.
     *
     * @param origin Stored packument
     * @param rules What to keep
     */
    public PackumentTransform(final Content origin, final Rules rules) {
        this.origin = origin;
        this.rules = rules;
    }

    @Override
    public Optional<Long> size() {
        return Optional.empty();
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        Flowable.defer(
            () -> {
                final Pump pump = new Pump(this.rules);
                return Flowable.fromPublisher(this.origin)
                    .concatMap(chunk -> Flowable.fromIterable(pump.feed(chunk)))
                    .concatWith(Flowable.defer(() -> Flowable.fromIterable(pump.finish())));
            }
        ).subscribe(subscriber);
    }

    /**
     * Handling of one root member.
     */
    public enum Section {
        /**
         * Copy the member as stored.
         */
        COPY,
        /**
         * Drop the member.
         */
        SKIP,
        /**
         * Keep the member only when its value is a string.
         */
        STRING,
        /**
         * Keep the member, filtering its object's entries by key.
         */
        MEMBERS,
        /**
         * Keep the member, filtering its object's entries by key and
         * replacing each kept entry's value by an empty object.
         */
        STUBS,
        /**
         * Keep the member, filtering its object's entries by key and keeping
         * only the entries whose value is a string.
         */
        STRINGS,
        /**
         * Keep the member, filtering its object's entries by key; a string
         * value is kept, any other value is replaced by an empty object, so
         * every key survives and the kept part stays shallow.
         */
        STRINGS_OR_STUBS,
        /**
         * Replace the member's value by a given tree; the tree is appended
         * at the end of the document when the member is absent.
         */
        REPLACE
    }

    /**
     * Rewrite rules.
     *
     * @param sections Handling per root member name
     * @param fallback Handling of root members not in {@code sections}
     * @param keep Entry filter per {@link Section#MEMBERS}/{@link Section#STUBS} member
     * @param replacements Tree per {@link Section#REPLACE} member
     * @param prefix Prefix for relative tarball URLs, empty to leave them
     */
    public record Rules(
        Map<String, Section> sections,
        Section fallback,
        Map<String, Predicate<String>> keep,
        Map<String, JsonNode> replacements,
        Optional<String> prefix
    ) {
        /**
         * Handling of a root member.
         *
         * @param name Root member name
         * @return Handling
         */
        Section section(final String name) {
            return this.sections.getOrDefault(name, this.fallback);
        }
    }

    /**
     * Per-subscription parser/generator state.
     */
    private static final class Pump {

        /**
         * Key of a tarball URL.
         */
        private static final String TARBALL = "tarball";

        /**
         * Rules.
         */
        private final Rules rules;

        /**
         * Non-blocking parser.
         */
        private final JsonParser parser;

        /**
         * Feeder of {@link #parser}.
         */
        private final ByteArrayFeeder feeder;

        /**
         * Generator output, drained after each chunk.
         */
        private final ByteArrayOutputStream out;

        /**
         * Generator.
         */
        private final JsonGenerator gen;

        /**
         * Replaced root members already written.
         */
        private final Set<String> replaced;

        /**
         * Container depth of the kept stream (root object is 1).
         */
        private int depth;

        /**
         * Handling of the root member being copied.
         */
        private Section section;

        /**
         * Name of the root member being copied.
         */
        private String member;

        /**
         * Next value is to be dropped.
         */
        private boolean pending;

        /**
         * Open containers of the value being dropped.
         */
        private int skipping;

        /**
         * Next value is a tarball URL.
         */
        private boolean tarball;

        /**
         * Field name held back until its value shows whether it is kept.
         */
        private String held;

        /**
         * Which values of the held field are kept.
         */
        private Held kind;

        /**
         * The root value has been read; everything after it is ignored.
         */
        private boolean done;

        Pump(final Rules rules) {
            this.rules = rules;
            this.out = new ByteArrayOutputStream();
            this.replaced = new HashSet<>();
            try {
                this.parser = MAPPER.getFactory().createNonBlockingByteArrayParser();
                this.gen = MAPPER.getFactory().createGenerator(this.out);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
            this.feeder = (ByteArrayFeeder) this.parser.getNonBlockingInputFeeder();
        }

        List<ByteBuffer> feed(final ByteBuffer chunk) throws IOException {
            if (!this.done) {
                final byte[] bytes = new byte[chunk.remaining()];
                chunk.duplicate().get(bytes);
                this.feeder.feedInput(bytes, 0, bytes.length);
                this.drain();
            }
            return this.take();
        }

        List<ByteBuffer> finish() throws IOException {
            if (!this.done) {
                this.feeder.endOfInput();
                this.drain();
            }
            this.gen.close();
            return this.take();
        }

        private void drain() throws IOException {
            JsonToken token = this.parser.nextToken();
            while (token != null && token != JsonToken.NOT_AVAILABLE) {
                this.on(token);
                this.done = this.parser.getParsingContext().inRoot();
                if (this.done) {
                    break;
                }
                token = this.parser.nextToken();
            }
        }

        private List<ByteBuffer> take() throws IOException {
            this.gen.flush();
            final List<ByteBuffer> result;
            if (this.out.size() == 0) {
                result = List.of();
            } else {
                result = List.of(ByteBuffer.wrap(this.out.toByteArray()));
                this.out.reset();
            }
            return result;
        }

        private void on(final JsonToken token) throws IOException {
            final boolean url = this.tarball;
            this.tarball = false;
            if (this.skipping > 0) {
                if (token.isStructStart()) {
                    ++this.skipping;
                } else if (token.isStructEnd()) {
                    --this.skipping;
                }
            } else if (this.pending) {
                this.pending = false;
                if (token.isStructStart()) {
                    this.skipping = 1;
                }
            } else if (this.held != null) {
                this.release(token);
            } else if (token == JsonToken.FIELD_NAME) {
                this.field(this.parser.currentName());
            } else if (token.isStructStart()) {
                ++this.depth;
                this.gen.copyCurrentEvent(this.parser);
            } else if (token.isStructEnd()) {
                if (this.depth == 1 && token == JsonToken.END_OBJECT) {
                    this.appendMissing();
                }
                --this.depth;
                this.gen.copyCurrentEvent(this.parser);
            } else {
                this.scalar(token, url);
            }
        }

        private void field(final String name) throws IOException {
            this.tarball = this.rules.prefix().isPresent() && Pump.TARBALL.equals(name);
            if (this.depth == 1) {
                this.root(name);
            } else if (this.depth == 2 && this.keyed()) {
                this.entry(name);
            } else {
                this.gen.writeFieldName(name);
            }
        }

        private void root(final String name) throws IOException {
            this.member = name;
            this.section = this.rules.section(name);
            if (this.section == Section.SKIP) {
                this.pending = true;
            } else if (this.section == Section.REPLACE) {
                this.writeReplacement(name);
                this.pending = true;
            } else if (this.section == Section.STRING) {
                this.hold(name, Held.STRING);
            } else if (this.keyed()) {
                this.hold(name, Held.OBJECT);
            } else {
                this.gen.writeFieldName(name);
            }
        }

        private boolean keyed() {
            return this.section == Section.MEMBERS || this.section == Section.STUBS
                || this.section == Section.STRINGS || this.section == Section.STRINGS_OR_STUBS;
        }

        private void hold(final String name, final Held what) {
            this.held = name;
            this.kind = what;
        }

        /**
         * Write the held field with its value when the value has the kept
         * kind, with an empty object for a stubbed one, drop both otherwise.
         */
        private void release(final JsonToken token) throws IOException {
            final String name = this.held;
            this.held = null;
            if (this.kind == Held.OBJECT && token == JsonToken.START_OBJECT) {
                this.gen.writeFieldName(name);
                ++this.depth;
                this.gen.copyCurrentEvent(this.parser);
            } else if (this.kind != Held.OBJECT && token == JsonToken.VALUE_STRING) {
                this.gen.writeFieldName(name);
                this.gen.writeString(this.parser.getText());
            } else {
                if (this.kind == Held.STRING_OR_STUB) {
                    this.gen.writeFieldName(name);
                    this.gen.writeStartObject();
                    this.gen.writeEndObject();
                }
                if (token.isStructStart()) {
                    this.skipping = 1;
                }
            }
        }

        private void entry(final String name) throws IOException {
            final Predicate<String> keep = this.rules.keep().get(this.member);
            if (keep != null && !keep.test(name)) {
                this.pending = true;
            } else if (this.section == Section.STRINGS) {
                this.hold(name, Held.STRING);
            } else if (this.section == Section.STRINGS_OR_STUBS) {
                this.hold(name, Held.STRING_OR_STUB);
            } else {
                this.gen.writeFieldName(name);
                if (this.section == Section.STUBS) {
                    this.gen.writeStartObject();
                    this.gen.writeEndObject();
                    this.pending = true;
                }
            }
        }

        private void scalar(final JsonToken token, final boolean url) throws IOException {
            if (url && token == JsonToken.VALUE_STRING) {
                this.gen.writeString(this.prefixed(this.parser.getText()));
            } else if (token.isNumeric()) {
                this.gen.writeNumber(this.parser.getText());
            } else {
                this.gen.copyCurrentEvent(this.parser);
            }
        }

        /**
         * A tarball URL with the prefix applied when it is relative.
         *
         * @param url Stored URL
         * @return URL to emit
         */
        private String prefixed(final String url) {
            final String result;
            if (url.startsWith("/")) {
                result = this.rules.prefix().get() + url;
            } else {
                result = url;
            }
            return result;
        }

        /**
         * Write a replacement tree. Its own {@code tarball} string members
         * (the tree is a shallow object of strings, the filtered
         * {@code dist-tags}) are prefixed like those of the stored document.
         */
        private void writeReplacement(final String name) throws IOException {
            final JsonNode tree = this.rules.replacements().get(name);
            if (tree != null && this.replaced.add(name)) {
                this.gen.writeFieldName(name);
                final JsonNode url = tree.get(Pump.TARBALL);
                if (this.rules.prefix().isPresent() && tree.isObject() && url != null
                    && url.isTextual()) {
                    final ObjectNode copy = ((ObjectNode) tree).deepCopy();
                    copy.put(Pump.TARBALL, this.prefixed(url.asText()));
                    MAPPER.writeTree(this.gen, copy);
                } else {
                    MAPPER.writeTree(this.gen, tree);
                }
            }
        }

        private void appendMissing() throws IOException {
            for (final Map.Entry<String, JsonNode> entry : this.rules.replacements().entrySet()) {
                if (!this.replaced.contains(entry.getKey())
                    && this.rules.section(entry.getKey()) == Section.REPLACE) {
                    this.writeReplacement(entry.getKey());
                }
            }
        }
    }

    /**
     * Which values of a held field are kept.
     */
    private enum Held {
        /**
         * An object.
         */
        OBJECT,
        /**
         * A string.
         */
        STRING,
        /**
         * A string; any other value is kept as an empty object.
         */
        STRING_OR_STUB
    }
}
