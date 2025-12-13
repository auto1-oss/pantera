/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.api.perms.ApiRepositoryPermission;
import com.artipie.api.verifier.ExistenceVerifier;
import com.artipie.api.verifier.ReservedNamesVerifier;
import com.artipie.api.verifier.SettingsDuplicatesVerifier;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.auth.AuthUser;
import com.artipie.scheduling.MetadataEventQueues;
import com.artipie.security.policy.Policy;
import com.artipie.settings.RepoData;
import com.artipie.settings.cache.FiltersCache;
import com.artipie.settings.repo.CrudRepoSettings;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.security.PermissionCollection;
import java.util.Optional;
import javax.json.JsonObject;
import org.eclipse.jetty.http.HttpStatus;
import com.artipie.http.log.EcsLogger;

/**
 * Rest-api operations for repositories settings CRUD
 * (create/read/update/delete) operations.
 */
public final class RepositoryRest extends BaseRest {

    /**
     * Update repo permission.
     */
    private static final ApiRepositoryPermission UPDATE =
        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE);

    /**
     * Create repo permission.
     */
    private static final ApiRepositoryPermission CREATE =
        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE);

    /**
     * Artipie filters cache.
     */
    private final FiltersCache cache;

    /**
     * Repository settings create/read/update/delete.
     */
    private final CrudRepoSettings crs;

    /**
     * Repository data management.
     */
    private final RepoData data;

    /**
     * Artipie policy.
     */
    private final Policy<?> policy;

    /**
     * Artifact metadata events queue.
     */
    private final Optional<MetadataEventQueues> events;

    /**
     * Vert.x event bus for publishing repository change events.
     */
    private final EventBus bus;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Ctor.
     * @param cache Artipie filters cache
     * @param crs Repository settings create/read/update/delete
     * @param data Repository data management
     * @param policy Artipie policy
     * @param events Artifact events queue
     */
    public RepositoryRest(
        final FiltersCache cache, final CrudRepoSettings crs, final RepoData data,
        final Policy<?> policy, final Optional<MetadataEventQueues> events,
        final CooldownService cooldown,
        final EventBus bus
    ) {
        this.cache = cache;
        this.crs = crs;
        this.data = data;
        this.policy = policy;
        this.events = events;
        this.cooldown = cooldown;
        this.bus = bus;
    }

    @Override
    @SuppressWarnings("PMD.ExcessiveMethodLength")
    public void init(final RouterBuilder rbr) {
        rbr.operation("listAll")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ)
                )
            )
            .handler(this::listAll)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("getRepo")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ)
                )
            )
            .handler(this::getRepo)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("existRepo")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ)
                )
            )
            .handler(this::existRepo)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("createOrUpdateRepo")
            .handler(this::createOrUpdateRepo)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("removeRepo")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE)
                )
            )
            .handler(this::removeRepo)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("unblockCooldown")
            .handler(new AuthzHandler(this.policy, RepositoryRest.UPDATE))
            .handler(this::unblockCooldown)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("unblockAllCooldown")
            .handler(new AuthzHandler(this.policy, RepositoryRest.UPDATE))
            .handler(this::unblockAllCooldown)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("deleteArtifact")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE)
                )
            )
            .handler(this::deleteArtifact)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("moveRepo")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.MOVE)
                )
            )
            .handler(this::moveRepo)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Get a repository settings json.
     * @param context Routing context
     */
    private void getRepo(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator validator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404),
            Validator.validator(
                new SettingsDuplicatesVerifier(rname, this.crs),
                HttpStatus.CONFLICT_409
            )
        );
        if (validator.validate(context)) {
            context.response()
                .setStatusCode(HttpStatus.OK_200)
                .end(this.crs.value(rname).toString());
        }
    }

    /**
     * Checks if repository settings exist.
     * @param context Routing context
     */
    private void existRepo(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator validator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404),
            Validator.validator(
                new SettingsDuplicatesVerifier(rname, this.crs),
                HttpStatus.CONFLICT_409
            )
        );
        if (validator.validate(context)) {
            context.response()
                .setStatusCode(HttpStatus.OK_200)
                .end();
        }
    }

    /**
     * List all existing repositories.
     * @param context Routing context
     */
    private void listAll(final RoutingContext context) {
        context.response().setStatusCode(HttpStatus.OK_200).end(
            JsonArray.of(this.crs.listAll().toArray()).encode()
        );
    }

    /**
     * Create a repository.
     * @param context Routing context
     */
    private void createOrUpdateRepo(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator validator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400)
        );
        final boolean exists = this.crs.exists(rname);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                context.user().principal().getString(AuthTokenRest.SUB),
                context.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        if ((exists && perms.implies(RepositoryRest.UPDATE)
            || !exists && perms.implies(RepositoryRest.CREATE)) && validator.validate(context)) {
            final JsonObject json = BaseRest.readJsonObject(context);
            final String repomsg = "Section `repo` is required";
            final Validator jsvalidator = new Validator.All(
                Validator.validator(
                    () -> json != null, "JSON body is expected",
                    HttpStatus.BAD_REQUEST_400
                ),
                Validator.validator(
                    () -> json.containsKey(RepositoryRest.REPO), repomsg,
                    HttpStatus.BAD_REQUEST_400
                ),
                Validator.validator(
                    () -> json.getJsonObject(RepositoryRest.REPO) != null, repomsg,
                    HttpStatus.BAD_REQUEST_400
                ),
                Validator.validator(
                    () -> json.getJsonObject(RepositoryRest.REPO).containsKey("type"),
                    "Repository type is required", HttpStatus.BAD_REQUEST_400
                ),
                Validator.validator(
                    () -> json.getJsonObject(RepositoryRest.REPO).containsKey("storage"),
                    "Repository storage is required", HttpStatus.BAD_REQUEST_400
                )
            );
            if (jsvalidator.validate(context)) {
                this.crs.save(rname, json);
                this.cache.invalidate(rname.toString());
                // Notify runtime to refresh repositories and caches
                this.bus.publish(RepositoryEvents.ADDRESS, RepositoryEvents.upsert(rname.toString()));
                context.response().setStatusCode(HttpStatus.OK_200).end();
            }
        } else {
            context.response().setStatusCode(HttpStatus.FORBIDDEN_403).end();
        }
    }

    /**
     * Remove a repository settings json and repository data.
     * @param context Routing context
     */
    private void removeRepo(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator validator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(
                () -> this.crs.exists(rname),
                () -> String.format("Repository %s does not exist. ", rname),
                HttpStatus.NOT_FOUND_404
            )
        );
        if (validator.validate(context)) {
            this.data.remove(rname)
                .thenRun(() -> this.crs.delete(rname))
                .exceptionally(
                    exc -> {
                        this.crs.delete(rname);
                        return null;
                    }
                );
            this.cache.invalidate(rname.toString());
            this.bus.publish(RepositoryEvents.ADDRESS, RepositoryEvents.remove(rname.toString()));
            this.events.ifPresent(item -> item.stopProxyMetadataProcessing(rname.toString()));
            context.response()
                .setStatusCode(HttpStatus.OK_200)
                .end();
        }
    }

    private void unblockCooldown(final RoutingContext context) {
        final RepositoryName name = new RepositoryName.FromRequest(context);
        final Optional<JsonObject> repo = this.repositoryConfig(name);
        if (repo.isEmpty()) {
            context.response().setStatusCode(HttpStatus.NOT_FOUND_404).end();
            return;
        }
        final String type = repo.get().getString("type", "").trim();
        if (type.isEmpty()) {
            context.response().setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Repository type is required");
            return;
        }
        final JsonObject body = BaseRest.readJsonObject(context);
        final String artifact = body.getString("artifact", "").trim();
        final String version = body.getString("version", "").trim();
        if (artifact.isEmpty() || version.isEmpty()) {
            context.response().setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("artifact and version are required");
            return;
        }
        final String actor = context.user().principal().getString(AuthTokenRest.SUB);
        this.cooldown.unblock(type, name.toString(), artifact, version, actor)
            .whenComplete((ignored, error) -> {
                if (error == null) {
                    context.response().setStatusCode(HttpStatus.NO_CONTENT_204).end();
                } else {
                    EcsLogger.error("com.artipie.api")
                        .message("Failed to unblock artifact from cooldown")
                        .eventCategory("api")
                        .eventAction("cooldown_unblock")
                        .eventOutcome("failure")
                        .field("repository.name", name.toString())
                        .field("package.name", artifact)
                        .field("package.version", version)
                        .error(error)
                        .log();
                    context.response().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .end(error.getMessage());
                }
            });
    }

    /**
     * Delete artifact from repository storage.
     * @param context Routing context
     */
    private void deleteArtifact(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator validator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(
                () -> this.crs.exists(rname),
                () -> String.format("Repository %s does not exist.", rname),
                HttpStatus.NOT_FOUND_404
            )
        );
        if (validator.validate(context)) {
            final JsonObject body = BaseRest.readJsonObject(context);
            final String path = body == null ? null : body.getString("path", "").trim();
            if (path == null || path.isEmpty()) {
                context.response().setStatusCode(HttpStatus.BAD_REQUEST_400)
                    .end("path is required");
                return;
            }
            final String actor = context.user().principal().getString(AuthTokenRest.SUB);
            this.data.deleteArtifact(rname, path)
                .whenComplete((deleted, error) -> {
                    if (error != null) {
                        EcsLogger.error("com.artipie.api")
                            .message("Failed to delete artifact")
                            .eventCategory("api")
                            .eventAction("artifact_delete")
                            .eventOutcome("failure")
                            .field("repository.name", rname.toString())
                            .field("artifact.path", path)
                            .userName(actor)
                            .error(error)
                            .log();
                        context.response().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                            .end(error.getMessage());
                    } else if (deleted) {
                        EcsLogger.info("com.artipie.api")
                            .message("Artifact deleted via API")
                            .eventCategory("api")
                            .eventAction("artifact_delete")
                            .eventOutcome("success")
                            .field("repository.name", rname.toString())
                            .field("artifact.path", path)
                            .userName(actor)
                            .log();
                        context.response().setStatusCode(HttpStatus.NO_CONTENT_204).end();
                    } else {
                        context.response().setStatusCode(HttpStatus.NOT_FOUND_404)
                            .end("Artifact not found: " + path);
                    }
                });
        }
    }

    private void unblockAllCooldown(final RoutingContext context) {
        final RepositoryName name = new RepositoryName.FromRequest(context);
        final Optional<JsonObject> repo = this.repositoryConfig(name);
        if (repo.isEmpty()) {
            context.response().setStatusCode(HttpStatus.NOT_FOUND_404).end();
            return;
        }
        final String type = repo.get().getString("type", "").trim();
        if (type.isEmpty()) {
            context.response().setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Repository type is required");
            return;
        }
        final String actor = context.user().principal().getString(AuthTokenRest.SUB);
        this.cooldown.unblockAll(type, name.toString(), actor)
            .whenComplete((ignored, error) -> {
                if (error == null) {
                    context.response().setStatusCode(HttpStatus.NO_CONTENT_204).end();
                } else {
                    EcsLogger.error("com.artipie.api")
                        .message("Failed to unblock all artifacts from cooldown")
                        .eventCategory("api")
                        .eventAction("cooldown_unblock_all")
                        .eventOutcome("failure")
                        .field("repository.name", name.toString())
                        .error(error)
                        .log();
                    context.response().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .end(error.getMessage());
                }
            });
    }

    private Optional<JsonObject> repositoryConfig(final RepositoryName name) {
        final javax.json.JsonStructure config = this.crs.value(name);
        if (config == null || !(config instanceof JsonObject)) {
            return Optional.empty();
        }
        final JsonObject obj = (JsonObject) config;
        if (obj.containsKey(BaseRest.REPO)
            && obj.get(BaseRest.REPO).getValueType() == javax.json.JsonValue.ValueType.OBJECT) {
            return Optional.of(obj.getJsonObject(BaseRest.REPO));
        }
        return Optional.of(obj);
    }

    /**
     * Move a repository settings.
     * @param context Routing context
     */
    private void moveRepo(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        Validator validator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404),
            Validator.validator(
                new SettingsDuplicatesVerifier(rname, this.crs), HttpStatus.CONFLICT_409
            )
        );
        if (validator.validate(context)) {
            final RepositoryName newrname = new RepositoryName.Simple(
                BaseRest.readJsonObject(context).getString("new_name")
            );
            validator = new Validator.All(
                Validator.validator(
                    new ReservedNamesVerifier(newrname), HttpStatus.BAD_REQUEST_400
                ),
                Validator.validator(
                    new SettingsDuplicatesVerifier(newrname, this.crs), HttpStatus.CONFLICT_409
                )
            );
            if (validator.validate(context)) {
                this.data.move(rname, newrname).thenRun(() -> this.crs.move(rname, newrname));
                this.cache.invalidate(rname.toString());
                this.bus.publish(
                    RepositoryEvents.ADDRESS,
                    RepositoryEvents.move(rname.toString(), newrname.toString())
                );
                context.response().setStatusCode(HttpStatus.OK_200).end();
            }
        }
    }
}
