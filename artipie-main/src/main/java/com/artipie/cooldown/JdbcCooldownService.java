/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

final class JdbcCooldownService implements CooldownService {

    private final CooldownSettings settings;
    private final CooldownRepository repository;
    private final Executor executor;

    private static final String SYSTEM_ACTOR = "system";

    JdbcCooldownService(final CooldownSettings settings, final CooldownRepository repository) {
        this(settings, repository, ForkJoinPool.commonPool());
    }

    JdbcCooldownService(
        final CooldownSettings settings,
        final CooldownRepository repository,
        final Executor executor
    ) {
        this.settings = Objects.requireNonNull(settings);
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public CompletableFuture<CooldownResult> evaluate(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        if (!this.settings.enabled()) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        return CompletableFuture.supplyAsync(
            () -> this.evaluateBlocking(request, inspector),
            this.executor
        );
    }

    @Override
    public CompletableFuture<Void> unblock(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final String actor
    ) {
        return CompletableFuture.runAsync(
            () -> this.unblockSingle(repoType, repoName, artifact, version, actor),
            this.executor
        );
    }

    @Override
    public CompletableFuture<Void> unblockAll(
        final String repoType,
        final String repoName,
        final String actor
    ) {
        return CompletableFuture.runAsync(
            () -> this.unblockAllBlocking(repoType, repoName, actor),
            this.executor
        );
    }

    @Override
    public CompletableFuture<List<CooldownBlock>> activeBlocks(
        final String repoType,
        final String repoName
    ) {
        return CompletableFuture.supplyAsync(
            () -> this.repository.findActiveForRepo(repoType, repoName).stream()
                .filter(record -> record.status() == BlockStatus.ACTIVE)
                .map(record -> this.toCooldownBlock(record, this.repository.dependenciesOf(record.id())))
                .collect(Collectors.toList()),
            this.executor
        );
    }

    private CooldownResult evaluateBlocking(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        final Instant now = request.requestedAt();
        final Optional<DbBlockRecord> existing = this.repository.find(
            request.repoType(),
            request.repoName(),
            request.artifact(),
            request.version()
        );
        if (existing.isPresent()) {
            final DbBlockRecord record = existing.get();
            if (record.status() == BlockStatus.ACTIVE) {
                if (record.blockedUntil().isAfter(now)) {
                    this.repository.recordAttempt(record.id(), request.requestedBy(), now);
                    final List<DbBlockRecord> deps = this.repository.dependenciesOf(record.id());
                    return CooldownResult.blocked(
                        this.toCooldownBlock(record, deps)
                    );
                }
                this.expire(record, now);
                return CooldownResult.allowed();
            }
            return CooldownResult.allowed();
        }
        final Optional<Instant> release =
            inspector.releaseDate(request.artifact(), request.version()).join();
        if (release.isEmpty()) {
            com.jcabi.log.Logger.warn(
                this,
                "No release date found for %s:%s:%s:%s - allowing",
                request.repoType(), request.repoName(), request.artifact(), request.version()
            );
            return CooldownResult.allowed();
        }
        final Duration fresh = this.settings.minimumAllowedAge();
        final Instant date = release.get();
        com.jcabi.log.Logger.debug(
            this,
            "Evaluating cooldown for %s:%s (released=%s, now=%s, minAge=%s)",
            request.artifact(), request.version(), date, now, fresh
        );
        if (date.plus(fresh).isAfter(now)
            && !fresh.isZero() && !fresh.isNegative()) {
            final Instant until = date.plus(fresh);
            com.jcabi.log.Logger.info(
                this,
                "BLOCKING %s:%s - too fresh (released=%s, blockedUntil=%s)",
                request.artifact(), request.version(), date, until
            );
            return this.createBlock(request, inspector, CooldownReason.FRESH_RELEASE, until);
        }
        com.jcabi.log.Logger.debug(
            this,
            "ALLOWING %s:%s - old enough (released=%s, age=%s)",
            request.artifact(), request.version(), date, Duration.between(date, now)
        );
        return CooldownResult.allowed();
    }

    private CooldownResult createBlock(
        final CooldownRequest request,
        final CooldownInspector inspector,
        final CooldownReason reason,
        final Instant blockedUntil
    ) {
        final Instant now = request.requestedAt();
        final DbBlockRecord main = this.repository.insertBlock(
            request.repoType(),
            request.repoName(),
            request.artifact(),
            request.version(),
            reason,
            now,
            blockedUntil,
            SYSTEM_ACTOR,
            Optional.empty()
        );
        final List<CooldownDependency> deps = deduplicateDependencies(
            inspector.dependencies(request.artifact(), request.version()).join(),
            request.artifact(),
            request.version()
        );
        if (!deps.isEmpty()) {
            this.repository.insertDependencies(
                request.repoType(),
                request.repoName(),
                deps,
                reason,
                now,
                blockedUntil,
                SYSTEM_ACTOR,
                main.id()
            );
        }
        this.repository.recordAttempt(main.id(), request.requestedBy(), now);
        final List<DbBlockRecord> storedDeps = this.repository.dependenciesOf(main.id());
        return CooldownResult.blocked(
            this.toCooldownBlock(main, storedDeps)
        );
    }

    private void expire(final DbBlockRecord record, final Instant when) {
        this.repository.updateStatus(record.id(), BlockStatus.EXPIRED, Optional.of(when), Optional.empty());
        this.repository.dependenciesOf(record.id()).forEach(
            dep -> this.repository.updateStatus(dep.id(), BlockStatus.EXPIRED, Optional.of(when), Optional.empty())
        );
    }

    private void unblockSingle(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final String actor
    ) {
        final Optional<DbBlockRecord> record = this.repository.find(repoType, repoName, artifact, version);
        record.ifPresent(value -> this.release(value, actor, Instant.now()));
    }

    private void unblockAllBlocking(
        final String repoType,
        final String repoName,
        final String actor
    ) {
        final Instant now = Instant.now();
        final List<DbBlockRecord> blocks = this.repository.findActiveForRepo(repoType, repoName);
        blocks.stream()
            .filter(block -> block.parentId().isEmpty())
            .forEach(block -> this.release(block, actor, now));
        blocks.stream()
            .filter(block -> block.parentId().isPresent())
            .forEach(block -> this.repository.updateStatus(
                block.id(),
                BlockStatus.INACTIVE,
                Optional.of(now),
                Optional.of(actor)
            ));
    }

    private void release(final DbBlockRecord record, final String actor, final Instant when) {
        this.repository.updateStatus(
            record.id(),
            BlockStatus.INACTIVE,
            Optional.of(when),
            Optional.of(actor)
        );
        if (record.parentId().isEmpty()) {
            this.repository.dependenciesOf(record.id()).forEach(
                dep -> this.repository.updateStatus(
                    dep.id(),
                    BlockStatus.INACTIVE,
                    Optional.of(when),
                    Optional.of(actor)
                )
            );
        }
    }

    private CooldownBlock toCooldownBlock(
        final DbBlockRecord record,
        final List<DbBlockRecord> dependencies
    ) {
        final List<CooldownDependency> deps = dependencies.stream()
            .map(dep -> new CooldownDependency(dep.artifact(), dep.version()))
            .collect(Collectors.toCollection(ArrayList::new));
        return new CooldownBlock(
            record.repoType(),
            record.repoName(),
            record.artifact(),
            record.version(),
            record.reason(),
            record.blockedAt(),
            record.blockedUntil(),
            deps
        );
    }

    // Version comparison helpers removed as newer-than-cache logic is no longer supported.

    private static List<CooldownDependency> deduplicateDependencies(
        final List<CooldownDependency> deps,
        final String artifact,
        final String version
    ) {
        return deps.stream()
            .filter(dep -> !sameArtifact(artifact, version, dep))
            .collect(
                Collectors.collectingAndThen(
                    Collectors.toMap(
                        dep -> dep.artifact().toLowerCase(Locale.US) + "@" + dep.version(),
                        dep -> dep,
                        (existing, replacement) -> existing
                    ),
                    map -> new ArrayList<>(map.values())
                )
            );
    }

    private static boolean sameArtifact(
        final String artifact,
        final String version,
        final CooldownDependency dep
    ) {
        return artifact.equalsIgnoreCase(dep.artifact()) && version.equals(dep.version());
    }
}
