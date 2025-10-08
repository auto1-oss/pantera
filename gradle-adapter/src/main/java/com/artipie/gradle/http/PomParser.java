/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.cooldown.CooldownDependency;
import com.jcabi.xml.XML;
import com.jcabi.xml.XMLDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parser for Maven POM files.
 *
 * @since 1.0
 */
final class PomParser {

    private PomParser() {
    }

    static PomView parse(final String pom) {
        final XMLDocument xml = new XMLDocument(pom);
        return new PomView(parseDependencies(xml), parseParent(xml));
    }

    private static List<CooldownDependency> parseDependencies(final XML xml) {
        final Collection<XML> deps = xml.nodes(
            "//*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']"
        );
        if (deps.isEmpty()) {
            return Collections.<CooldownDependency>emptyList();
        }
        final List<CooldownDependency> result = new ArrayList<>(deps.size());
        for (final XML dep : deps) {
            final String scope = text(dep, "scope").map(val -> val.toLowerCase(Locale.US)).orElse("compile");
            final boolean optional = text(dep, "optional").map("true"::equalsIgnoreCase).orElse(false);
            if (optional || "test".equals(scope) || "provided".equals(scope)) {
                continue;
            }
            final Optional<String> group = text(dep, "groupId");
            final Optional<String> name = text(dep, "artifactId");
            final Optional<String> version = text(dep, "version");
            if (group.isEmpty() || name.isEmpty() || version.isEmpty()) {
                continue;
            }
            result.add(new CooldownDependency(group.get() + "." + name.get(), version.get()));
        }
        return result;
    }

    private static Optional<CooldownDependency> parseParent(final XML xml) {
        return xml.nodes("//*[local-name()='project']/*[local-name()='parent']").stream()
            .findFirst()
            .flatMap(node -> {
                final Optional<String> group = text(node, "groupId");
                final Optional<String> name = text(node, "artifactId");
                final Optional<String> version = text(node, "version");
                if (group.isEmpty() || name.isEmpty() || version.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new CooldownDependency(group.get() + "." + name.get(), version.get()));
            });
    }

    private static Optional<String> text(final XML xml, final String localName) {
        final List<String> values = xml.xpath(String.format("./*[local-name()='%s']/text()", localName));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(values.get(0).trim());
    }

    static final class PomView {
        private final List<CooldownDependency> dependencies;
        private final Optional<CooldownDependency> parent;

        PomView(final List<CooldownDependency> dependencies, final Optional<CooldownDependency> parent) {
            this.dependencies = dependencies;
            this.parent = parent;
        }

        List<CooldownDependency> dependencies() {
            return this.dependencies;
        }

        Optional<CooldownDependency> parent() {
            return this.parent;
        }
    }
}
