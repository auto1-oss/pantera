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

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import java.util.HashMap;
import java.util.Map;

/**
 * Okta group to Pantera role mapping of an {@code okta} credentials entry.
 * {@code group-roles} is either a list of single-key maps (the documented
 * form, also what the SSO sign-in reads) or one map.
 *
 * @since 2.2.14
 */
final class OktaGroupRoles {

    /**
     * Credentials entry of type okta.
     */
    private final YamlMapping creds;

    /**
     * Ctor.
     * @param creds Credentials entry of type okta
     */
    OktaGroupRoles(final YamlMapping creds) {
        this.creds = creds;
    }

    /**
     * Mapping.
     * @return Okta group to Pantera role, empty if not configured
     */
    Map<String, String> asMap() {
        final Map<String, String> res = new HashMap<>(0);
        final YamlSequence list = this.creds.yamlSequence("group-roles");
        if (list != null) {
            for (final YamlNode entry : list.values()) {
                if (entry instanceof YamlMapping) {
                    this.putAll(res, entry.asMapping());
                }
            }
        }
        final YamlMapping single = this.creds.yamlMapping("group-roles");
        if (single != null) {
            this.putAll(res, single);
        }
        return res;
    }

    private void putAll(final Map<String, String> res, final YamlMapping mapping) {
        for (final YamlNode key : mapping.keys()) {
            final String group = key.asScalar().value();
            final String role = mapping.string(group);
            if (role != null && !role.isEmpty()) {
                res.put(group, role);
            }
        }
    }
}
