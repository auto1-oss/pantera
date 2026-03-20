/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.ArtipieException;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.http.auth.ArtipieAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.DomainFilteredAuth;
import com.auto1.pantera.settings.YamlSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for auth from Okta.
 */
@ArtipieAuthFactory("okta")
public final class AuthFromOktaFactory implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping cfg) {
        final YamlMapping creds = cfg.yamlSequence("credentials")
            .values().stream().map(YamlNode::asMapping)
            .filter(node -> "okta".equals(node.string("type")))
            .findFirst().orElseThrow();
        final String issuer = resolveEnvVar(creds.string("issuer"));
        if (issuer == null || issuer.isEmpty()) {
            throw new ArtipieException("Okta issuer is not configured");
        }
        final String clientId = resolveEnvVar(creds.string("client-id"));
        final String clientSecret = resolveEnvVar(creds.string("client-secret"));
        if (clientId == null || clientSecret == null) {
            throw new ArtipieException("Okta client-id/client-secret are not configured");
        }
        final String authnUrl = resolveEnvVar(creds.string("authn-url"));
        final String authorizeUrl = resolveEnvVar(creds.string("authorize-url"));
        final String tokenUrl = resolveEnvVar(creds.string("token-url"));
        final String redirectUri = resolveEnvVar(creds.string("redirect-uri"));
        final String scope = resolveEnvVar(creds.string("scope"));
        final String groupsClaim = resolveEnvVar(creds.string("groups-claim"));
        final Map<String, String> groupRoles = new HashMap<>(0);
        final YamlMapping mapping = creds.yamlMapping("group-roles");
        if (mapping != null) {
            for (final YamlNode key : mapping.keys()) {
                final String oktaGroup = key.asScalar().value();
                final String role = mapping.string(oktaGroup);
                if (role != null && !role.isEmpty()) {
                    groupRoles.put(oktaGroup, role);
                }
            }
        }
        final BlockingStorage asto = new YamlSettings.PolicyStorage(cfg).parse()
            .map(BlockingStorage::new)
            .orElseThrow(
                () -> new ArtipieException(
                    "Failed to create okta auth, policy storage is not configured"
                )
            );
        final OktaOidcClient client = new OktaOidcClient(
            issuer,
            authnUrl,
            authorizeUrl,
            tokenUrl,
            clientId,
            clientSecret,
            redirectUri,
            scope,
            groupsClaim
        );
        final OktaUserProvisioning provisioning = new OktaUserProvisioning(asto, groupRoles);
        final Authentication auth = new AuthFromOkta(client, provisioning);
        // Wrap with domain filter if user-domains is configured
        final List<String> domains = parseUserDomains(creds);
        if (domains.isEmpty()) {
            return auth;
        }
        return new DomainFilteredAuth(auth, domains, "okta");
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
