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
package com.auto1.pantera.docker.manifest;

import com.auto1.pantera.docker.Digest;

import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

public record ManifestLayer(JsonObject json) {

    /**
     * Read layer content digest.
     *
     * @return Layer content digest..
     */
    public Digest digest() {
        return new Digest.FromString(this.json.getString("digest"));
    }

    /**
     * Provides a list of URLs from which the content may be fetched.
     *
     * @return URLs, might be empty
     */
    public Collection<URL> urls() {
        JsonArray urls = this.json.getJsonArray("urls");
        if (urls == null) {
            return Collections.emptyList();
        }
        return urls.getValuesAs(JsonString.class)
            .stream()
            .map(
                str -> {
                    try {
                        return URI.create(str.getString()).toURL();
                    } catch (final MalformedURLException ex) {
                        throw new IllegalArgumentException(ex);
                    }
                }
            )
            .collect(Collectors.toList());
    }

    /**
     * Layer size.
     *
     * @return Size of the blob
     */
    public long size() {
        JsonNumber res = this.json.getJsonNumber("size");
        return res != null ? res.longValue() : 0L;
    }
}
