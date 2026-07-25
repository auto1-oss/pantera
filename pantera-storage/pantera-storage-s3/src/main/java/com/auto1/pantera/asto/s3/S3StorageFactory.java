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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.factory.PanteraStorageFactory;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StorageFactory;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * Factory to create S3 storage.
 *
 * <p>Also the base for {@link S3ExpressStorageFactory}: S3 Express One Zone only
 * differs from standard S3 in two defaults (path-style access off, storage class
 * {@code EXPRESS_ONEZONE}), both exposed as overridable hooks
 * ({@link #defaultPathStyle()}, {@link #defaultStorageClass()}) so the subclass
 * carries no duplicated wiring logic (WS1.0 fold, spec &sect;I).</p>
 *
 * <p>Config alone -- custom {@code endpoint}, {@code region}, {@code path-style},
 * and {@code credentials} -- is what makes this factory also cover S3-API-compatible
 * stores (MinIO, Cloudflare R2, Backblaze B2, Wasabi, Ceph/RADOS Gateway, GCS via its
 * S3 interoperability endpoint): the AWS SDK client this factory builds speaks the
 * same wire protocol to all of them.</p>
 *
 * @since 0.1
 */
@PanteraStorageFactory("s3")
public class S3StorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final Config cfg) {
        final String bucket = new Config.StrictStorageConfig(cfg).string("bucket");
        final boolean multipart = !"false".equals(cfg.string("multipart"));

        // Support both human-readable (e.g., "32MB") and byte values
        final long minmp = parseSize(cfg, "multipart-min-size", "multipart-min-bytes").orElse(32L * 1024 * 1024);
        final int partsize = (int) (long) parseSize(cfg, "part-size", "part-size-bytes").orElse(8L * 1024 * 1024);
        final int mpconc = optInt(cfg, "multipart-concurrency").orElse(16);

        final ChecksumAlgorithm algo = Optional
            .ofNullable(cfg.string("checksum"))
            .map(String::toUpperCase)
            .map(val -> {
                if ("SHA256".equals(val)) {
                    return ChecksumAlgorithm.SHA256;
                } else if ("CRC32".equals(val)) {
                    return ChecksumAlgorithm.CRC32;
                } else if ("SHA1".equals(val)) {
                    return ChecksumAlgorithm.SHA1;
                }
                return ChecksumAlgorithm.SHA256;
            })
            .orElse(ChecksumAlgorithm.SHA256);

        final Config sse = cfg.config("sse");
        final ServerSideEncryption sseAlg = sse.isEmpty()
            ? null
            : Optional.ofNullable(sse.string("type"))
                .map(String::toUpperCase)
                .map(val -> "KMS".equals(val) ? ServerSideEncryption.AWS_KMS : ServerSideEncryption.AES256)
                .orElse(ServerSideEncryption.AES256);
        final String kmsId = sse.isEmpty() ? null : sse.string("kms-key-id");

        final boolean enablePdl = "true".equalsIgnoreCase(cfg.string("parallel-download"));
        final long pdlThreshold = parseSize(cfg, "parallel-download-min-size", "parallel-download-min-bytes").orElse(64L * 1024 * 1024);
        final int pdlChunk = (int) (long) parseSize(cfg, "parallel-download-chunk-size", "parallel-download-chunk-bytes").orElse(8L * 1024 * 1024);
        final int pdlConc = optInt(cfg, "parallel-download-concurrency").orElse(8);

        final String regionStr = cfg.string("region");
        final Optional<AwsCredentialsProvider> credentials = S3StorageFactory.credentialsProvider(cfg, regionStr);
        final Storage base = new S3Storage(
            this.s3Client(cfg, credentials, regionStr),
            bucket,
            multipart,
            endpoint(cfg).orElse("def endpoint"),
            minmp,
            partsize,
            mpconc,
            algo,
            sseAlg,
            kmsId,
            this.resolveStorageClass(cfg),
            enablePdl,
            pdlThreshold,
            pdlChunk,
            pdlConc,
            this.presigner(cfg, credentials, regionStr, bucket)
        );

        // Optional disk hot cache wrapper
        final Config cache = cfg.config("cache");
        if (!cache.isEmpty() && "true".equalsIgnoreCase(cache.string("enabled"))) {
            final java.nio.file.Path path = java.nio.file.Paths.get(
                Optional.ofNullable(cache.string("path")).orElseThrow(() -> new IllegalArgumentException("cache.path is required when cache.enabled=true"))
            );
            final long max = optLong(cache, "max-bytes").orElse(10L * 1024 * 1024 * 1024); // 10GiB default
            final int high = optInt(cache, "high-watermark-percent").orElse(90);
            final int low = optInt(cache, "low-watermark-percent").orElse(80);
            final long every = optLong(cache, "cleanup-interval-millis").orElse(300_000L);
            final boolean validate = !"false".equalsIgnoreCase(cache.string("validate-on-read"));
            final DiskCacheStorage.Policy pol = Optional.ofNullable(cache.string("eviction-policy"))
                .map(String::toUpperCase)
                .map(val -> "LFU".equals(val) ? DiskCacheStorage.Policy.LFU : DiskCacheStorage.Policy.LRU)
                .orElse(DiskCacheStorage.Policy.LRU);
            return new DiskCacheStorage(
                base,
                path,
                max,
                pol,
                every,
                high,
                low,
                validate
            );
        }
        return base;
    }

    /**
     * Creates {@link S3AsyncClient} instance based on YAML config.
     *
     * @param cfg Storage config.
     * @param credentials Resolved credentials provider (shared with the presigner
     *  so both agree and credential-chain resolution/initialization runs once).
     * @param regionStr Raw {@code region} config value, or {@code null}.
     * @return Built S3 client.
     */
    private S3AsyncClient s3Client(
        final Config cfg,
        final Optional<AwsCredentialsProvider> credentials,
        final String regionStr
    ) {
        final S3AsyncClientBuilder builder = S3AsyncClient.builder();

        // HTTP client: Netty async
        // Connection pool sizing: Balance between throughput and resource usage
        // For high-load scenarios (1000+ concurrent requests), increase max-concurrency proportionally
        // Rule of thumb: max-concurrency should be >= peak concurrent requests / 4
        final Config http = cfg.config("http");
        final int maxConc = optInt(http, "max-concurrency").orElse(1024);  // Reduced from 2048 for better memory usage
        final int maxPend = optInt(http, "max-pending-acquires").orElse(2048);  // Reduced from 4096
        final Duration acqTmo = Duration.ofMillis(optLong(http, "acquisition-timeout-millis").orElse(30_000L));
        final Duration readTmo = Duration.ofMillis(optLong(http, "read-timeout-millis").orElse(120_000L));  // Increased to 2 minutes
        final Duration writeTmo = Duration.ofMillis(optLong(http, "write-timeout-millis").orElse(120_000L));  // Increased to 2 minutes
        final Duration idleMax = Duration.ofMillis(optLong(http, "connection-max-idle-millis").orElse(30_000L));  // Reduced to 30s for faster cleanup

        if (maxConc < 64) {
            java.util.logging.Logger.getLogger(this.getClass().getName()).warning(
                String.format(
                    "%s max-concurrency=%d is low for production use. Recommend >= 256 for mixed read/write workloads.",
                    this.getClass().getSimpleName(), maxConc
                )
            );
        }
        final SdkAsyncHttpClient netty = NettyNioAsyncHttpClient.builder() // NOPMD CloseResource - lifecycle owned by built S3 client (closed via builder.httpClient(netty))
            .maxConcurrency(maxConc)
            .maxPendingConnectionAcquires(maxPend)
            .connectionAcquisitionTimeout(acqTmo)
            .readTimeout(readTmo)
            .writeTimeout(writeTmo)
            .connectionMaxIdleTime(idleMax)
            .tcpKeepAlive(true)
            .build();
        builder.httpClient(netty);

        // Region and endpoint
        Optional.ofNullable(regionStr).ifPresent(val -> builder.region(Region.of(val)));
        endpoint(cfg).ifPresent(val -> builder.endpointOverride(URI.create(val)));

        // S3-specific configuration: path-style vs virtual-hosted-style is what makes
        // this single client work against MinIO/R2/B2/Wasabi/Ceph/GCS-S3-interop, not
        // just AWS S3.
        final boolean dualstack = "true".equalsIgnoreCase(cfg.string("dualstack"));
        builder.serviceConfiguration(
            S3Configuration.builder()
                .dualstackEnabled(dualstack)
                .pathStyleAccessEnabled(this.pathStyle(cfg))
                .build()
        );
        builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
        builder.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);

        // Retries and adaptive backoff
        builder.overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.forRetryMode(RetryMode.ADAPTIVE))
                .build()
        );

        credentials.ifPresent(builder::credentialsProvider);
        return builder.build();
    }

    /**
     * Builds the {@link Presigner} for this storage: local SigV4 signing via the AWS
     * SDK's {@link S3Presigner}, sharing endpoint/region/path-style/credentials with
     * {@link #s3Client(Config, Optional, String)} so a presigned URL always matches
     * how this storage itself would address the object.
     *
     * @param cfg Storage config.
     * @param credentials Resolved credentials provider, shared with the S3 client.
     * @param regionStr Raw {@code region} config value, or {@code null}.
     * @param bucket Bucket name.
     * @return Presigner backing {@link S3Storage#presignGet(com.auto1.pantera.asto.Key, long)}.
     */
    private Presigner presigner(
        final Config cfg,
        final Optional<AwsCredentialsProvider> credentials,
        final String regionStr,
        final String bucket
    ) {
        final S3Presigner.Builder builder = S3Presigner.builder();
        Optional.ofNullable(regionStr).ifPresent(val -> builder.region(Region.of(val)));
        endpoint(cfg).ifPresent(val -> builder.endpointOverride(URI.create(val)));
        builder.serviceConfiguration(
            S3Configuration.builder()
                .dualstackEnabled("true".equalsIgnoreCase(cfg.string("dualstack")))
                .pathStyleAccessEnabled(this.pathStyle(cfg))
                .build()
        );
        credentials.ifPresent(builder::credentialsProvider);
        return new S3BlobPresigner(builder.build(), bucket);
    }

    /**
     * Resolves path-style-vs-virtual-hosted-style access, honoring an explicit
     * {@code path-style} config value or falling back to {@link #defaultPathStyle()}.
     *
     * @param cfg Storage config.
     * @return TRUE for path-style access, FALSE for virtual-hosted-style.
     */
    private boolean pathStyle(final Config cfg) {
        final String val = cfg.string("path-style");
        final boolean result;
        if (val == null) {
            result = this.defaultPathStyle();
        } else if (this.defaultPathStyle()) {
            result = !"false".equalsIgnoreCase(val);
        } else {
            result = "true".equalsIgnoreCase(val);
        }
        return result;
    }

    /**
     * Default path-style setting when {@code path-style} is not set explicitly.
     * Standard S3 (and most S3-API-compatible services: MinIO, Ceph, LocalStack)
     * default to path-style on; {@link S3ExpressStorageFactory} overrides this to
     * {@code false} -- S3 Express One Zone requires virtual-hosted-style access.
     *
     * @return TRUE by default.
     */
    protected boolean defaultPathStyle() {
        return true;
    }

    /**
     * Default S3 storage class when {@code storage-class} is not set explicitly.
     * {@code null} means the S3 default ({@code STANDARD}).
     * {@link S3ExpressStorageFactory} overrides this to {@code EXPRESS_ONEZONE}.
     *
     * @return {@code null} by default.
     */
    protected StorageClass defaultStorageClass() {
        return null;
    }

    /**
     * Resolves the S3 storage class: an explicit {@code storage-class} config value
     * (e.g. {@code STANDARD_IA}, {@code GLACIER}, {@code EXPRESS_ONEZONE}) wins,
     * otherwise {@link #defaultStorageClass()}.
     *
     * @param cfg Storage config.
     * @return Resolved storage class, or {@code null} for the S3 default.
     */
    private StorageClass resolveStorageClass(final Config cfg) {
        final String val = cfg.string("storage-class");
        final StorageClass result;
        if (val == null) {
            result = this.defaultStorageClass();
        } else {
            result = StorageClass.fromValue(val.toUpperCase(Locale.ROOT));
            if (result == StorageClass.UNKNOWN_TO_SDK_VERSION) {
                throw new IllegalArgumentException(
                    String.format("Unsupported S3 storage-class: %s", val)
                );
            }
        }
        return result;
    }

    /**
     * Resolves the credentials provider from {@code credentials} config, or
     * {@link Optional#empty()} to use the SDK default chain (env vars, instance
     * profile, etc.) -- resolved once and shared between the S3 client and the
     * presigner so credential-chain initialization (and any resources it opens)
     * happens a single time per storage instance.
     *
     * @param cfg Storage config.
     * @param regionStr Raw {@code region} config value, or {@code null}.
     * @return Resolved provider, or empty for the SDK default chain.
     */
    private static Optional<AwsCredentialsProvider> credentialsProvider(
        final Config cfg,
        final String regionStr
    ) {
        final Config credentials = cfg.config("credentials");
        final Optional<AwsCredentialsProvider> result;
        if (credentials.isEmpty()) {
            result = Optional.empty();
        } else {
            result = Optional.of(resolveCredentials(credentials, regionStr));
        }
        return result;
    }

    private static AwsCredentialsProvider resolveCredentials(final Config creds, final String regionStr) {
        final String type = creds.string("type");
        if (type == null || "default".equalsIgnoreCase(type)) {
            return DefaultCredentialsProvider.create();
        }
        if ("basic".equalsIgnoreCase(type)) {
            final String akid = creds.string("accessKeyId");
            final String secret = creds.string("secretAccessKey");
            final String token = creds.string("sessionToken");
            return StaticCredentialsProvider.create(
                token == null
                    ? AwsBasicCredentials.create(akid, secret)
                    : AwsSessionCredentials.create(akid, secret, token)
            );
        }
        if ("profile".equalsIgnoreCase(type)) {
            final String name = Optional.ofNullable(creds.string("profile"))
                .orElse(Optional.ofNullable(creds.string("profileName")).orElse("default"));
            return ProfileCredentialsProvider.builder().profileName(name).build();
        }
        if ("assume-role".equalsIgnoreCase(type) || "assume_role".equalsIgnoreCase(type)) {
            final String roleArn = Optional.ofNullable(creds.string("roleArn"))
                .orElse(Optional.ofNullable(creds.string("role_arn")).orElse(null));
            if (roleArn == null) {
                throw new IllegalArgumentException("credentials.roleArn is required for assume-role");
            }
            final String session = Optional.ofNullable(creds.string("sessionName")).orElse("pantera-session");
            final String externalId = creds.string("externalId");
            final AwsCredentialsProvider source = creds.config("source").isEmpty()
                ? DefaultCredentialsProvider.create()
                : resolveCredentials(creds.config("source"), regionStr);
            final StsClientBuilder sts = StsClient.builder().credentialsProvider(source);
            if (regionStr != null) {
                sts.region(Region.of(regionStr));
            }
            final StsAssumeRoleCredentialsProvider.Builder bld = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts.build())
                .refreshRequest(arb -> {
                    arb.roleArn(roleArn).roleSessionName(session);
                    if (externalId != null) {
                        arb.externalId(externalId);
                    }
                });
            return bld.build();
        }
        throw new IllegalArgumentException(String.format("Unsupported S3 credentials type: %s", type));
    }

    /**
     * Obtain endpoint from storage config. The parameter is optional.
     *
     * @param cfg Storage config
     * @return Endpoint value is present
     */
    private static Optional<String> endpoint(final Config cfg) {
        return Optional.ofNullable(cfg.string("endpoint"));
    }

    private static Optional<Integer> optInt(final Config cfg, final String key) {
        try {
            final String val = cfg.string(key);
            return val == null ? Optional.empty() : Optional.of(Integer.parseInt(val));
        } catch (final Exception err) {
            return Optional.empty();
        }
    }

    private static Optional<Long> optLong(final Config cfg, final String key) {
        try {
            final String val = cfg.string(key);
            return val == null ? Optional.empty() : Optional.of(Long.parseLong(val));
        } catch (final Exception err) {
            return Optional.empty();
        }
    }

    /**
     * Parse size from config supporting human-readable format (e.g., "32MB", "1GB").
     * Falls back to legacy byte-based key for backward compatibility.
     *
     * @param cfg Configuration
     * @param newKey New human-readable key (e.g., "part-size")
     * @param legacyKey Legacy byte key (e.g., "part-size-bytes")
     * @return Size in bytes
     */
    private static Optional<Long> parseSize(final Config cfg, final String newKey, final String legacyKey) {
        // Try new human-readable format first
        final String newVal = cfg.string(newKey);
        if (newVal != null) {
            return Optional.of(parseSizeString(newVal));
        }
        // Fall back to legacy byte format
        return optLong(cfg, legacyKey);
    }

    /**
     * Parse human-readable size string to bytes.
     * Supports: KB, MB, GB (case-insensitive)
     * Examples: "32MB", "1GB", "512KB", "1024" (bytes)
     *
     * @param size Size string
     * @return Size in bytes
     */
    private static long parseSizeString(final String size) {
        final String upper = size.trim().toUpperCase(Locale.ROOT);
        if (upper.endsWith("GB")) {
            return Long.parseLong(upper.substring(0, upper.length() - 2).trim()) * 1024 * 1024 * 1024;
        } else if (upper.endsWith("MB")) {
            return Long.parseLong(upper.substring(0, upper.length() - 2).trim()) * 1024 * 1024;
        } else if (upper.endsWith("KB")) {
            return Long.parseLong(upper.substring(0, upper.length() - 2).trim()) * 1024;
        } else {
            // Assume bytes if no unit
            return Long.parseLong(upper);
        }
    }
}
