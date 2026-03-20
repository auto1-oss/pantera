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
import java.util.Map;
import org.keycloak.authorization.client.Configuration;

/**
 * Factory for auth from keycloak.
 * @since 0.30
 */
@PanteraAuthFactory("keycloak")
public final class AuthFromKeycloakFactory implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping cfg) {
        final YamlMapping creds = cfg.yamlSequence("credentials")
            .values().stream().map(YamlNode::asMapping)
            .filter(node -> "keycloak".equals(node.string("type")))
            .findFirst().orElseThrow();
        final Authentication auth = new AuthFromKeycloak(
            new Configuration(
                resolveEnvVar(creds.string("url")),
                resolveEnvVar(creds.string("realm")),
                resolveEnvVar(creds.string("client-id")),
                Map.of("secret", resolveEnvVar(creds.string("client-password"))),
                null
            )
        );
        // Wrap with domain filter if user-domains is configured
        final List<String> domains = parseUserDomains(creds);
        if (domains.isEmpty()) {
            return auth;
        }
        return new DomainFilteredAuth(auth, domains, "keycloak");
    }

    /**
     * Parse user-domains from config.
     * @param creds Credentials YAML
     * @return List of domain patterns (empty if not configured)
     */
    private static List<String> parseUserDomains(final YamlMapping creds) {
        final List<String> result = new ArrayList<>();
        final YamlSequence domains = creds.yamlSequence("user-domains");
        if (domains != null) {
            for (final YamlNode node : domains.values()) {
                final String domain = node.asScalar().value();
                if (domain != null && !domain.isEmpty()) {
                    result.add(domain);
                }
            }
        }
        return result;
    }

    private static String resolveEnvVar(final String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String envValue = System.getenv(envVar);
            if (envValue != null) {
                result = result.replace("${" + envVar + "}", envValue);
            }
        }
        return result;
    }
}
