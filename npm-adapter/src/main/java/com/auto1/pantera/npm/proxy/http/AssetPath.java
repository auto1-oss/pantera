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
package com.auto1.pantera.npm.proxy.http;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Asset path helper. Pantera maps concrete repositories on the path prefixes in the URL.
 * This class provides the way to match asset requests with prefixes correctly.
 * Also, it allows to get relative asset path for using with the Storage instances.
 * @since 0.1
 */
public final class AssetPath extends NpmPath {
    /**
     * Ctor.
     * @param prefix Base prefix path
     */
    public AssetPath(final String prefix) {
        super(prefix);
    }

    @Override
    public Pattern pattern() {
        final Pattern result;
        if (StringUtils.isEmpty(this.prefix())) {
            result = Pattern.compile("^/(.+/-/.+)$");
        } else {
            result = Pattern.compile(
                String.format("^/%1$s/(.+/-/.+)$", Pattern.quote(this.prefix()))
            );
        }
        return result;
    }
}
