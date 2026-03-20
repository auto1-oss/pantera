/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http.search;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Package metadata for search results.
 *
 * @since 1.1
 */
public final class PackageMetadata {
    
    /**
     * Package name.
     */
    private final String name;
    
    /**
     * Latest version.
     */
    private final String version;
    
    /**
     * Description.
     */
    private final String description;
    
    /**
     * Keywords.
     */
    private final List<String> keywords;
    
    /**
     * Constructor.
     * @param name Package name
     * @param version Latest version
     * @param description Description
     * @param keywords Keywords
     */
    public PackageMetadata(
        final String name,
        final String version,
        final String description,
        final List<String> keywords
    ) {
        this.name = Objects.requireNonNull(name);
        this.version = Objects.requireNonNull(version);
        this.description = description != null ? description : "";
        this.keywords = keywords != null ? keywords : Collections.emptyList();
    }
    
    /**
     * Get package name.
     * @return Name
     */
    public String name() {
        return this.name;
    }
    
    /**
     * Get version.
     * @return Version
     */
    public String version() {
        return this.version;
    }
    
    /**
     * Get description.
     * @return Description
     */
    public String description() {
        return this.description;
    }
    
    /**
     * Get keywords.
     * @return Keywords
     */
    public List<String> keywords() {
        return Collections.unmodifiableList(this.keywords);
    }
}
