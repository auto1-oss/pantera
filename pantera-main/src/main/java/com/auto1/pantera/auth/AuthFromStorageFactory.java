/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.DomainFilteredAuth;
import com.auto1.pantera.settings.YamlSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for auth from pantera storage.
 * @since 0.30
 */
@PanteraAuthFactory("local")
public final class AuthFromStorageFactory implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping yaml) {
        final Authentication auth = new YamlSettings.PolicyStorage(yaml).parse().map(
            asto -> new AuthFromStorage(new BlockingStorage(asto))
        ).orElseThrow(
            () -> new PanteraException(
                "Failed to create local auth, storage is not configured"
            )
        );
        final List<String> domains = parseUserDomains(yaml, "local");
        if (domains.isEmpty()) {
            return auth;
        }
        return new DomainFilteredAuth(auth, domains, "local");
    }

    /**
     * Parse user-domains from config for the specified type.
     * @param cfg Full config YAML
     * @param type Auth type to find
     * @return List of domain patterns (empty if not configured)
     */
    private static List<String> parseUserDomains(final YamlMapping cfg, final String type) {
        final List<String> result = new ArrayList<>();
        final YamlSequence creds = cfg.yamlSequence("credentials");
        if (creds == null) {
            return result;
        }
        for (final YamlNode node : creds.values()) {
            final YamlMapping mapping = node.asMapping();
            if (type.equals(mapping.string("type"))) {
                final YamlSequence domains = mapping.yamlSequence("user-domains");
                if (domains != null) {
                    for (final YamlNode domainNode : domains.values()) {
                        final String domain = domainNode.asScalar().value();
                        if (domain != null && !domain.isEmpty()) {
                            result.add(domain);
                        }
                    }
                }
                break;
            }
        }
        return result;
    }
}
