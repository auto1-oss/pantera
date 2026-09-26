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
package com.auto1.pantera.api.v1.admin;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Troubleshooter checks read off a package inspection: the cooldown state
 * of the requested version (or, for a metadata request, any inconsistent
 * version) and whether the repository's listing shows it.
 *
 * @since 2.2.9
 */
final class CooldownFindings {

    /**
     * Layer: cooldown.
     */
    private static final String COOLDOWN = "cooldown";

    /**
     * Layer: metadata.
     */
    private static final String METADATA = "metadata";

    /**
     * Repository.
     */
    private final RepoTopology.RepoInfo repo;

    /**
     * Parsed request.
     */
    private final ParsedRequest req;

    /**
     * Inspection document.
     */
    private final JsonObject doc;

    /**
     * Ctor.
     *
     * @param repo Repository
     * @param req Parsed request
     * @param doc Inspection of the package in the repository
     */
    CooldownFindings(
        final RepoTopology.RepoInfo repo, final ParsedRequest req, final JsonObject doc
    ) {
        this.repo = repo;
        this.req = req;
        this.doc = doc;
    }

    /**
     * Append the findings.
     *
     * @param checks Checks
     */
    void addTo(final JsonArray checks) {
        final JsonObject meta = this.repoMetadata();
        if (this.req.version() == null) {
            this.listingFindings(checks, meta);
        } else {
            this.versionFindings(checks, meta);
        }
    }

    /**
     * Findings for a metadata request: every inconsistent version.
     *
     * @param checks Checks
     * @param meta Metadata section of the repository
     */
    private void listingFindings(final JsonArray checks, final JsonObject meta) {
        final List<String> bad = new ArrayList<>();
        final JsonArray versions = this.doc.getJsonArray("versions", new JsonArray());
        int blocked = 0;
        for (int idx = 0; idx < versions.size(); idx = idx + 1) {
            final JsonObject ver = versions.getJsonObject(idx);
            if (ver.getBoolean("mismatch", false)) {
                bad.add(ver.getString("version") + " (" + ver.getString("mismatchReason") + ")");
            }
            if ("blocked".equals(ver.getJsonObject(COOLDOWN).getString("state"))) {
                blocked = blocked + 1;
            }
        }
        if (bad.isEmpty()) {
            checks.add(Troubleshooter.check(
                COOLDOWN, COOLDOWN, "ok",
                "Cooldown state and served versions agree"
                    + (blocked > 0 ? " (" + blocked + " version(s) blocked and hidden)" : ""),
                null
            ));
        } else {
            checks.add(Troubleshooter.check(
                COOLDOWN, COOLDOWN, "problem",
                "Cooldown state and served versions disagree: " + String.join(", ", bad),
                this.refreshFix()
            ));
        }
        CooldownFindings.metadataStatus(checks, meta);
    }

    /**
     * Findings for a versioned request.
     *
     * @param checks Checks
     * @param meta Metadata section of the repository
     */
    private void versionFindings(final JsonArray checks, final JsonObject meta) {
        final JsonObject ver = this.versionEntry();
        final JsonObject cooldown = ver.getJsonObject(COOLDOWN, new JsonObject());
        final String state = cooldown.getString("state", "none");
        if ("blocked".equals(state)) {
            checks.add(Troubleshooter.check(
                COOLDOWN, COOLDOWN, "problem",
                this.req.pkg() + " " + this.req.version() + " is blocked by cooldown in "
                    + cooldown.getString("repo") + " until " + cooldown.getString("blockedUntil"),
                new JsonObject()
                    .put("action", "unblock")
                    .put("endpoint", "/api/v1/repositories/" + cooldown.getString("repo")
                        + "/cooldown/unblock")
                    .put("body", new JsonObject()
                        .put("artifact", this.req.pkg())
                        .put("version", this.req.version()))
            ));
        } else if (ver.getBoolean("mismatch", false)) {
            checks.add(Troubleshooter.check(
                COOLDOWN, COOLDOWN, "problem",
                "Cooldown state is " + state + " but " + ver.getString("mismatchReason"),
                this.refreshFix()
            ));
        } else {
            checks.add(Troubleshooter.check(
                COOLDOWN, COOLDOWN, "ok",
                "Cooldown state of " + this.req.version() + ": " + state, null
            ));
        }
        this.visibility(checks, meta, state);
    }

    /**
     * Whether the repository's listing shows the requested version.
     *
     * @param checks Checks
     * @param meta Metadata section
     * @param state Cooldown state
     */
    private void visibility(final JsonArray checks, final JsonObject meta, final String state) {
        if (!meta.containsKey("visibleVersions")) {
            CooldownFindings.metadataStatus(checks, meta);
            return;
        }
        final boolean shown = meta.getJsonArray("visibleVersions").contains(this.req.version());
        if (shown) {
            checks.add(Troubleshooter.check(
                METADATA, METADATA, "ok",
                this.req.version() + " is listed by " + this.repo.name(), null
            ));
        } else if ("blocked".equals(state)) {
            checks.add(Troubleshooter.check(
                METADATA, METADATA, "info",
                this.req.version() + " is hidden by " + this.repo.name()
                    + " because it is blocked", null
            ));
        } else {
            checks.add(Troubleshooter.check(
                METADATA, METADATA, "problem",
                this.req.version() + " is not listed by " + this.repo.name()
                    + " although it is not blocked — a stale listing or envelope",
                this.refreshFix()
            ));
        }
    }

    /**
     * Metadata availability check.
     *
     * @param checks Checks
     * @param meta Metadata section
     */
    private static void metadataStatus(final JsonArray checks, final JsonObject meta) {
        if (meta.getBoolean("unsupported", false)) {
            checks.add(Troubleshooter.check(
                METADATA, METADATA, "info",
                "Version listing inspection is not supported for this format", null
            ));
        } else if (meta.getInteger("status", 0) != 200) {
            checks.add(Troubleshooter.check(
                METADATA, METADATA, "problem",
                "Version listing " + meta.getString("path") + " answers "
                    + meta.getInteger("status", 0)
                    + (meta.getString("error") == null ? "" : ": " + meta.getString("error")),
                null
            ));
        }
    }

    /**
     * Metadata section of the inspected repository.
     *
     * @return Section, empty when absent
     */
    private JsonObject repoMetadata() {
        final JsonArray repos = this.doc.getJsonArray("repos", new JsonArray());
        for (int idx = 0; idx < repos.size(); idx = idx + 1) {
            final JsonObject item = repos.getJsonObject(idx);
            if (this.repo.name().equals(item.getString("name"))) {
                return item.getJsonObject(METADATA, new JsonObject());
            }
        }
        return new JsonObject();
    }

    /**
     * Version entry of the requested version.
     *
     * @return Entry, empty when unknown
     */
    private JsonObject versionEntry() {
        final JsonArray versions = this.doc.getJsonArray("versions", new JsonArray());
        for (int idx = 0; idx < versions.size(); idx = idx + 1) {
            final JsonObject ver = versions.getJsonObject(idx);
            if (this.req.version().equals(ver.getString("version"))) {
                return ver;
            }
        }
        return new JsonObject();
    }

    /**
     * The refresh-package fix.
     *
     * @return Fix
     */
    private JsonObject refreshFix() {
        return new JsonObject()
            .put("action", "refresh-package")
            .put("endpoint", "/api/v1/cooldown/refresh-package")
            .put("body", new JsonObject()
                .put("repoType", this.repo.family())
                .put("package", this.req.pkg())
                .put("repo", this.repo.name()));
    }
}
