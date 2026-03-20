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
package com.auto1.pantera.gem.ruby;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.gem.GemIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.commons.io.IOUtils;
import org.jruby.Ruby;
import org.jruby.javasupport.JavaEmbedUtils;

/**
 * Ruby runtime gem index implementation.
 *
 * @since 1.0
 */
public final class RubyGemIndex implements GemIndex, SharedRuntime.RubyPlugin {

    /**
     * Ruby runtime.
     */
    private final Ruby ruby;

    /**
     * New gem indexer.
     * @param ruby Runtime
     */
    public RubyGemIndex(final Ruby ruby) {
        this.ruby = ruby;
    }

    @Override
    public void update(final Path path) {
        JavaEmbedUtils.invokeMethod(
            this.ruby,
            JavaEmbedUtils.newRuntimeAdapter().eval(this.ruby, "MetaRunner"),
            "new",
            new Object[]{path.toString()},
            Object.class
        );
    }

    @Override
    public String identifier() {
        return this.getClass().getCanonicalName();
    }

    @Override
    public void initialize() {
        final String script;
        try {
            script = IOUtils.toString(
                RubyGemIndex.class.getResourceAsStream("/metarunner.rb"),
                StandardCharsets.UTF_8
            );
        } catch (final IOException err) {
            throw new PanteraIOException("Failed to initialize gem indexer", err);
        }
        JavaEmbedUtils.newRuntimeAdapter().eval(this.ruby, script);
    }
}
