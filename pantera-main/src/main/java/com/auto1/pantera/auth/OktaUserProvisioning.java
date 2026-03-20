/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.auth;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.amihaiemil.eoyaml.YamlSequenceBuilder;
import com.artipie.asto.Key;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.http.log.EcsLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Just-in-time provisioning of users and role mappings for Okta-authenticated users.
 */
public final class OktaUserProvisioning {

    private final BlockingStorage asto;

    private final Map<String, String> groupRoles;

    public OktaUserProvisioning(final BlockingStorage asto,
        final Map<String, String> groupRoles) {
        this.asto = asto;
        this.groupRoles = groupRoles;
    }

    public void provision(final String username, final String email, final Collection<String> groups) {
        try {
            final Key yaml = new Key.From(String.format("users/%s.yaml", username));
            final Key yml = new Key.From(String.format("users/%s.yml", username));
            final boolean hasYaml = this.asto.exists(yaml);
            final boolean hasYml = this.asto.exists(yml);
            final Key target;
            YamlMapping existing = null;
            if (hasYaml) {
                target = yaml;
                existing = readYaml(this.asto.value(yaml));
            } else if (hasYml) {
                target = yml;
                existing = readYaml(this.asto.value(yml));
            } else {
                target = yml;
                existing = Yaml.createYamlMappingBuilder().build();
            }
            YamlMappingBuilder builder = Yaml.createYamlMappingBuilder();
            for (final YamlNode key : existing.keys()) {
                final String k = key.asScalar().value();
                if (!"roles".equals(k) && !"enabled".equals(k) && !"email".equals(k)) {
                    builder = builder.add(k, existing.value(k));
                }
            }
            builder = builder.add("enabled", "true");
            if (email != null && !email.isEmpty()) {
                builder = builder.add("email", email);
            }
            final Set<String> roles = new LinkedHashSet<>();
            final YamlSequence existingRoles = existing.yamlSequence("roles");
            if (existingRoles != null) {
                existingRoles.values().forEach(
                    node -> roles.add(node.asScalar().value())
                );
            }
            if (groups != null) {
                for (final String grp : groups) {
                    if (grp == null) {
                        continue;
                    }
                    final String mapped = this.groupRoles.get(grp);
                    if (mapped != null && !mapped.isEmpty()) {
                        roles.add(mapped);
                    }
                }
            }
            if (!roles.isEmpty()) {
                YamlSequenceBuilder seq = Yaml.createYamlSequenceBuilder();
                for (final String role : roles) {
                    seq = seq.add(role);
                }
                builder = builder.add("roles", seq.build());
            }
            final String out = builder.build().toString();
            this.asto.save(target, out.getBytes(StandardCharsets.UTF_8));
        } catch (final IOException err) {
            EcsLogger.error("com.artipie.auth")
                .message("Failed to provision Okta user")
                .eventCategory("authentication")
                .eventAction("user_provision")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err)
                .log();
        }
    }

    private static YamlMapping readYaml(final byte[] data) throws IOException {
        return Yaml.createYamlInput(new ByteArrayInputStream(data)).readYamlMapping();
    }
}
