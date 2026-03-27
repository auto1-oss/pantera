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
package com.auto1.pantera.nuget.metadata;

/**
 * Names of the optional fields of nuspes file.
 * Check <a href="https://learn.microsoft.com/en-us/nuget/reference/nuspec">docs</a> for more info.
 * @since 0.7
 */
public enum OptFieldName {

    TITLE("title"),

    SUMMARY("summary"),

    ICON("icon"),

    ICON_URL("iconUrl"),

    LICENSE("license"),

    LICENSE_URL("licenseUrl"),

    REQUIRE_LICENSE_ACCEPTANCE("requireLicenseAcceptance"),

    TAGS("tags"),

    PROJECT_URL("projectUrl"),

    RELEASE_NOTES("releaseNotes");

    /**
     * Xml field name.
     */
    private final String name;

    /**
     * Ctor.
     * @param name Xml field name
     */
    OptFieldName(final String name) {
        this.name = name;
    }

    /**
     * Get xml field name.
     * @return String xml name
     */
    public String get() {
        return this.name;
    }
}
