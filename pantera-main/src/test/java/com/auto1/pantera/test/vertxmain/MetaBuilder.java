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
package com.auto1.pantera.test.vertxmain;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import org.apache.hc.core5.net.URIBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Pantera's meta config yaml builder.
 */
public class MetaBuilder {

    private URI baseUrl;

    private Path repos;

    private Path security;

    public MetaBuilder withBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public MetaBuilder withBaseUrl(String host, int port) {
        try {
            this.baseUrl = new URIBuilder()
                    .setScheme("http")
                    .setHost(host)
                    .setPort(port)
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public MetaBuilder withRepoDir(Path dir) {
        this.repos = Objects.requireNonNull(dir, "Directory cannot be null");
        return this;
    }

    public MetaBuilder withSecurityDir(Path dir) {
        this.security = Objects.requireNonNull(dir, "Directory cannot be null");
        return this;
    }

    public Path build(Path base) throws IOException {
        if (this.repos == null) {
            throw new IllegalStateException("Directory of repositories is not defined");
        }
        if (this.security == null) {
            throw new IllegalStateException("Security directory is not defined");
        }
        YamlMappingBuilder meta = Yaml.createYamlMappingBuilder()
                .add("storage", TestVertxMainBuilder.fileStorageCfg(this.repos));
        if (this.baseUrl != null) {
            meta = meta.add("base_url", this.baseUrl.toString());
        }
        meta = meta.add("credentials",
                Yaml.createYamlSequenceBuilder()
                        .add(
                                Yaml.createYamlMappingBuilder()
                                        .add("type", "local")
                                        .build()
                        )
                        .build()
        );
        meta = meta.add("policy",
                Yaml.createYamlMappingBuilder()
                        .add("type", "local")
                        .add("storage", TestVertxMainBuilder.fileStorageCfg(this.security))
                        .build());
        String data = Yaml.createYamlMappingBuilder()
                .add("meta", meta.build())
                .build()
                .toString();
        Path res = base.resolve("pantera.yml");
        Files.deleteIfExists(res);
        Files.createFile(res);
        return Files.write(res, data.getBytes());
    }
}
