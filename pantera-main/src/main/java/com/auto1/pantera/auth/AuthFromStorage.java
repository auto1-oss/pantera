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
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Authentication from abstract storage.
 * The storage is expected to have yaml files with credentials in the following structure:
 * <pre>
 * ..
 * ├── users
 * │   ├── david.yaml
 * │   ├── jane.yml
 * │   ├── ...
 * </pre>
 * where the name of the file is username (case-sensitive), both yml and yaml extensions are
 * supported. The yaml format file is the following:
 * <pre>{@code
 *   type: plain # plain and sha256 types are supported
 *   pass: qwerty
 *   email: david@example.com # Optional
 *   enabled: true # optional default true
 *   roles:
 *     - java-dev
 *   permissions:
 *     pantera_basic_permission:
 *       rpm-repo:
 *         - read
 * }</pre>
 * @since 1.29
 */
public final class AuthFromStorage implements Authentication {

    /**
     * Auth type name.
     */
    private static final String ARTIPIE = "local";

    /**
     * The storage to obtain users files from.
     */
    private final BlockingStorage asto;

    /**
     * Ctor.
     * @param asto Abstract blocking storage
     */
    public AuthFromStorage(final BlockingStorage asto) {
        this.asto = asto;
    }

    @Override
    public Optional<AuthUser> user(final String name, final String pass) {
        final Optional<byte[]> res;
        final Key yaml = new Key.From(String.format("users/%s.yaml", name));
        final Key yml = new Key.From(String.format("users/%s.yml", name));
        if (this.asto.exists(yaml)) {
            res = Optional.of(this.asto.value(yaml));
        } else if (this.asto.exists(yml)) {
            res = Optional.of(this.asto.value(yml));
        } else {
            res = Optional.empty();
        }
        return res.map(bytes -> AuthFromStorage.readAndCheckFromYaml(bytes, name, pass))
            .flatMap(opt -> opt);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    /**
     * Reads bytes as yaml and check the password.
     * @param bytes Yaml bytes
     * @param name Username
     * @param pass Password to check
     * @return User if yaml parsed and password is correct
     */
    private static Optional<AuthUser> readAndCheckFromYaml(final byte[] bytes, final String name,
        final String pass) {
        Optional<AuthUser> res = Optional.empty();
        try {
            final YamlMapping info = Yaml.createYamlInput(new ByteArrayInputStream(bytes))
                .readYamlMapping();
            if (info != null
                && !Boolean.FALSE.toString().equalsIgnoreCase(info.string("enabled"))) {
                final String type = info.string("type");
                final String origin = info.string("pass");
                if ("plain".equals(type) && Objects.equals(origin, pass)) {
                    res = Optional.of(new AuthUser(name, AuthFromStorage.ARTIPIE));
                } else if ("sha256".equals(type) && DigestUtils.sha256Hex(pass).equals(origin)) {
                    res = Optional.of(new AuthUser(name, AuthFromStorage.ARTIPIE));
                }
            }
        } catch (final IOException err) {
            EcsLogger.error("com.auto1.pantera.auth")
                .message("Failed to parse yaml for user")
                .eventCategory("authentication")
                .eventAction("user_lookup")
                .eventOutcome("failure")
                .field("user.name", name)
                .error(err)
                .log();
        }
        return res;
    }
}
