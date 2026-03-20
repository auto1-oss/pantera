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
package com.auto1.pantera.http;

import com.auto1.pantera.http.headers.Header;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unmodifiable list of HTTP request headers.
 */
public class UnmodifiableHeaders extends Headers {

    UnmodifiableHeaders(List<Header> headers) {
        super(Collections.unmodifiableList(headers));
    }

    @Override
    public Headers add(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers add(Header header, boolean overwrite) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers add(Header header) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers add(Map.Entry<String, String> entry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers addAll(Headers src) {
        throw new UnsupportedOperationException();
    }
}
