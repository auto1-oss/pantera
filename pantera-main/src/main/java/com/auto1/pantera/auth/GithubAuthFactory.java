/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.DomainFilteredAuth;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for auth from github.
 * @since 0.30
 */
@PanteraAuthFactory("github")
public final class GithubAuthFactory implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping yaml) {
        final Authentication auth = new GithubAuth();
        final List<String> domains = parseUserDomains(yaml, "github");
        if (domains.isEmpty()) {
            return auth;
        }
        return new DomainFilteredAuth(auth, domains, "github");
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
