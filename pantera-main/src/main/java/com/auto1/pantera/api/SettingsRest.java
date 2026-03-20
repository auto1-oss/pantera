/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.settings.PrefixesPersistence;
import com.auto1.pantera.settings.Settings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import org.eclipse.jetty.http.HttpStatus;

/**
 * REST API methods to manage Artipie settings.
 * @since 0.27
 */
public final class SettingsRest extends BaseRest {

    /**
     * Artipie port.
     */
    private final int port;

    /**
     * Artipie settings.
     */
    private final Settings settings;

    /**
     * Repository settings manager (optional, for dashboard stats).
     */
    private final Optional<ManageRepoSettings> crs;

    /**
     * Ctor.
     * @param port Artipie port
     * @param settings Artipie settings
     */
    public SettingsRest(final int port, final Settings settings) {
        this(port, settings, null);
    }

    /**
     * Ctor with repo settings for dashboard.
     * @param port Artipie port
     * @param settings Artipie settings
     * @param crs Repository settings manager
     */
    public SettingsRest(final int port, final Settings settings,
        final ManageRepoSettings crs) {
        this.port = port;
        this.settings = settings;
        this.crs = Optional.ofNullable(crs);
    }

    @Override
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    public void init(final RouterBuilder rbr) {
        rbr.operation("getDashboard")
            .handler(this::getDashboard)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("port")
            .handler(this::portRest)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("getGlobalPrefixes")
            .handler(this::getGlobalPrefixes)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("updateGlobalPrefixes")
            .handler(this::updateGlobalPrefixes)
            .failureHandler(this.errorHandler(HttpStatus.BAD_REQUEST_400));
    }

    /**
     * Dashboard statistics.
     * @param context Request context
     */
    private void getDashboard(final RoutingContext context) {
        final JsonObject dashboard = new JsonObject()
            .put("port", this.port)
            .put("version", new com.auto1.pantera.misc.ArtipieProperties().version());
        this.crs.ifPresent(
            manage -> dashboard.put("repositories", manage.listAll().size())
        );
        context.response()
            .setStatusCode(HttpStatus.OK_200)
            .putHeader("Content-Type", "application/json")
            .end(dashboard.encode());
    }

    /**
     * Send json with Artipie's port and status code OK_200.
     * @param context Request context
     */
    private void portRest(final RoutingContext context) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("port", this.port);
        context.response()
            .setStatusCode(HttpStatus.OK_200)
            .end(builder.build().toString());
    }

    /**
     * Get global prefixes configuration.
     * @param context Request context
     */
    private void getGlobalPrefixes(final RoutingContext context) {
        final JsonObject response = new JsonObject()
            .put("prefixes", new JsonArray(this.settings.prefixes().prefixes()))
            .put("version", this.settings.prefixes().version());
        context.response()
            .setStatusCode(HttpStatus.OK_200)
            .putHeader("Content-Type", "application/json")
            .end(response.encode());
    }

    /**
     * Update global prefixes configuration.
     * @param context Request context
     */
    private void updateGlobalPrefixes(final RoutingContext context) {
        try {
            final JsonObject body = context.body().asJsonObject();
            if (body == null || !body.containsKey("prefixes")) {
                context.response()
                    .setStatusCode(HttpStatus.BAD_REQUEST_400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("code", HttpStatus.BAD_REQUEST_400)
                        .put("message", "Missing 'prefixes' field in request body")
                        .encode());
                return;
            }
            final JsonArray prefixesArray = body.getJsonArray("prefixes");
            final java.util.List<String> prefixes = new java.util.ArrayList<>();
            for (int i = 0; i < prefixesArray.size(); i++) {
                prefixes.add(prefixesArray.getString(i));
            }
            // Validate: check for conflicts with existing repository names
            final java.util.Collection<String> existingRepos =
                this.settings.repoConfigsStorage().list(com.auto1.pantera.asto.Key.ROOT)
                    .join().stream()
                    .map(key -> key.string().replaceAll("\\.yaml|\\.yml$", ""))
                    .collect(java.util.stream.Collectors.toList());
            final java.util.List<String> conflicts = prefixes.stream()
                .filter(existingRepos::contains)
                .collect(java.util.stream.Collectors.toList());
            if (!conflicts.isEmpty()) {
                context.response()
                    .setStatusCode(HttpStatus.CONFLICT_409)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("code", HttpStatus.CONFLICT_409)
                        .put("message", String.format(
                            "Prefix(es) conflict with existing repository names: %s",
                            String.join(", ", conflicts)
                        ))
                        .encode());
                return;
            }
            // Update in-memory configuration
            this.settings.prefixes().update(prefixes);
            // Persist to artipie.yaml file using the persistence service
            new PrefixesPersistence(this.settings.configPath()).save(prefixes);
            context.response()
                .setStatusCode(HttpStatus.OK_200)
                .end();
        } catch (final Exception ex) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("code", HttpStatus.BAD_REQUEST_400)
                    .put("message", ex.getMessage())
                    .encode());
        }
    }
}
