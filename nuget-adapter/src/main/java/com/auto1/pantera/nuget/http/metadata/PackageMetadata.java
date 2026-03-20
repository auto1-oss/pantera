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
package com.auto1.pantera.nuget.http.metadata;

import com.auto1.pantera.nuget.Repository;
import com.auto1.pantera.nuget.http.Absent;
import com.auto1.pantera.nuget.http.Resource;
import com.auto1.pantera.nuget.http.Route;
import com.auto1.pantera.nuget.metadata.PackageId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package metadata route.
 * See <a href="https://docs.microsoft.com/en-us/nuget/api/registration-base-url-resource">Package Metadata</a>
 *
 * @since 0.1
 */
public final class PackageMetadata implements Route {

    /**
     * Base path for the route.
     */
    private static final String BASE = "/registrations";

    /**
     * RegEx pattern for registration path.
     */
    private static final Pattern REGISTRATION = Pattern.compile(
        String.format("%s/(?<id>[^/]+)/index.json$", PackageMetadata.BASE)
    );

    /**
     * Repository to read data from.
     */
    private final Repository repository;

    /**
     * Package content location.
     */
    private final ContentLocation content;

    /**
     * Ctor.
     *
     * @param repository Repository to read data from.
     * @param content Package content storage.
     */
    public PackageMetadata(final Repository repository, final ContentLocation content) {
        this.repository = repository;
        this.content = content;
    }

    @Override
    public String path() {
        return PackageMetadata.BASE;
    }

    @Override
    public Resource resource(final String path) {
        final Matcher matcher = REGISTRATION.matcher(path);
        final Resource resource;
        if (matcher.find()) {
            resource = new Registration(
                this.repository,
                this.content,
                new PackageId(matcher.group("id"))
            );
        } else {
            resource = new Absent();
        }
        return resource;
    }
}
