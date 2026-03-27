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
package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.test.TestResource;

/**
 * Newton.Json package resource.
 *
 * @since 0.1
 */
public final class NewtonJsonResource {

    /**
     * Resource name.
     */
    private final String name;

    /**
     * Ctor.
     *
     * @param name Resource name.
     */
    public NewtonJsonResource(final String name) {
        this.name = name;
    }

    /**
     * Reads binary data.
     *
     * @return Binary data.
     */
    public Content content() {
        return new Content.From(this.bytes());
    }

    /**
     * Reads binary data.
     *
     * @return Binary data.
     */
    public byte[] bytes() {
        return new TestResource(String.format("newtonsoft.json/12.0.3/%s", this.name)).asBytes();
    }
}
