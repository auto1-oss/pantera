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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WWW-Authenticate header.
 *
 * @since 0.12
 */
public final class WwwAuthenticate extends Header {

    /**
     * Header name.
     */
    public static final String NAME = "WWW-Authenticate";

    /**
     * Header value RegEx.
     */
    private static final Pattern VALUE = Pattern.compile("(?<scheme>[^\"]*)( (?<params>.*))?");

    /**
     * @param value Header value.
     */
    public WwwAuthenticate(final String value) {
        super(new Header(WwwAuthenticate.NAME, value));
    }

    /**
     * @param headers Headers to extract header from.
     */
    public WwwAuthenticate(final Headers headers) {
        this(new RqHeaders.Single(headers, WwwAuthenticate.NAME).asString());
    }

    /**
     * Get authorization scheme.
     *
     * @return Authorization scheme.
     */
    public String scheme() {
        return this.matcher().group("scheme");
    }

    /**
     * Get parameters list.
     *
     * @return Parameters list.
     */
    public List<Param> params() {
        return Optional.ofNullable(this.matcher().group("params"))
            .map(String::trim)
            .filter(params -> !params.isEmpty())
            .map(WwwAuthenticate::splitParams)
            .orElseGet(Collections::emptyList);
    }

    /**
     * Split params string into individual parameters, preserving quoted commas.
     *
     * @param params Raw params string
     * @return List of parameter objects
     */
    private static List<Param> splitParams(final String params) {
        final StringBuilder current = new StringBuilder();
        final List<Param> result = new java.util.ArrayList<>();
        boolean quoted = false;
        for (int idx = 0; idx < params.length(); idx++) {
            final char symbol = params.charAt(idx);
            if (symbol == '"') {
                quoted = !quoted;
                current.append(symbol);
            } else if (symbol == ',' && !quoted) {
                if (current.length() > 0) {
                    result.add(new Param(current.toString().trim()));
                    current.setLength(0);
                }
            } else {
                current.append(symbol);
            }
        }
        if (current.length() > 0) {
            result.add(new Param(current.toString().trim()));
        }
        return result;
    }

    /**
     * Get realm parameter value.
     *
     * @return Realm parameter value.
     */
    public String realm() {
        return this.params().stream()
            .filter(param -> "realm".equals(param.name()))
            .map(Param::value)
            .findAny()
            .orElseThrow(
                () -> new IllegalStateException(
                    String.format("No realm param found: %s", this.getValue())
                )
            );
    }

    /**
     * Creates matcher for header value.
     *
     * @return Matcher for header value.
     */
    private Matcher matcher() {
        final String value = this.getValue();
        final Matcher matcher = VALUE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                String.format("Failed to parse header value: %s", value)
            );
        }
        return matcher;
    }

    /**
     * WWW-Authenticate header parameter.
     */
    public static class Param {

        /**
         * Param RegEx.
         */
        private static final Pattern PATTERN = Pattern.compile(
            "(?<name>[^=\\s]+)\\s*=\\s*\"(?<value>[^\"]*)\""
        );

        /**
         * Param raw string.
         */
        private final String string;

        /**
         * @param string Param raw string.
         */
        public Param(final String string) {
            this.string = string;
        }

        /**
         * Param name.
         *
         * @return Name string.
         */
        public String name() {
            return this.matcher().group("name");
        }

        /**
         * Param value.
         *
         * @return Value string.
         */
        public String value() {
            return this.matcher().group("value");
        }

        /**
         * Creates matcher for param.
         *
         * @return Matcher for param.
         */
        private Matcher matcher() {
            final String value = this.string.trim();
            final Matcher matcher = PATTERN.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                    String.format("Failed to parse param: %s", value)
                );
            }
            return matcher;
        }
    }
}
