/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.vertx;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Transaction;
import co.elastic.apm.attach.ElasticApmAttacher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elastic APM instrumentation for Artipie.
 * Provides automatic tracing, metrics collection, and error tracking.
 * 
 * @since 1.18.19
 */
public final class ApmInstrumentation {

    /**
     * Shared OTLP meter registry for exporting metrics to Elastic APM.
     */
    private static volatile MeterRegistry REGISTRY;

    /**
     * Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ApmInstrumentation.class);

    /**
     * APM enabled flag.
     */
    private final boolean enabled;

    /**
     * Elastic APM server URL.
     */
    private final String serverUrl;

    /**
     * Service name.
     */
    private final String serviceName;

    /**
     * Environment name.
     */
    private final String environment;

    /**
     * Ctor.
     */
    public ApmInstrumentation() {
        this.enabled = Boolean.parseBoolean(
            System.getenv().getOrDefault("ELASTIC_APM_ENABLED", "false")
        );
        this.serverUrl = System.getenv().getOrDefault(
            "ELASTIC_APM_SERVER_URL", 
            "http://localhost:8200"
        );
        this.serviceName = System.getenv().getOrDefault(
            "ELASTIC_APM_SERVICE_NAME", 
            "artipie"
        );
        this.environment = System.getenv().getOrDefault(
            "ELASTIC_APM_ENVIRONMENT", 
            "production"
        );
    }

    /**
     * Initialize and attach Elastic APM agent.
     * Must be called early in application startup.
     */
    public void attach() {
        if (!this.enabled) {
            LOGGER.info("Elastic APM is disabled (ELASTIC_APM_ENABLED=false or not set)");
            return;
        }

        LOGGER.info("Initializing Elastic APM with server_url={}, service_name={}, environment={}", 
            this.serverUrl, this.serviceName, this.environment);

        try {
            final Map<String, String> config = new HashMap<>();
            config.put("server_url", this.serverUrl);
            config.put("service_name", this.serviceName);
            config.put("environment", this.environment);
            config.put("application_packages", "com.artipie");
            config.put("log_level", "DEBUG");  // Changed to DEBUG for troubleshooting
            
            // Authentication - check for secret token or API key
            final String secretToken = System.getenv("ELASTIC_APM_SECRET_TOKEN");
            final String apiKey = System.getenv("ELASTIC_APM_API_KEY");
            if (secretToken != null && !secretToken.isEmpty()) {
                config.put("secret_token", secretToken);
                LOGGER.info("Using APM secret token authentication");
            } else if (apiKey != null && !apiKey.isEmpty()) {
                config.put("api_key", apiKey);
                LOGGER.info("Using APM API key authentication");
            } else {
                LOGGER.warn("⚠️  No APM authentication configured (ELASTIC_APM_SECRET_TOKEN or ELASTIC_APM_API_KEY)");
            }
            
            // Enable distributed tracing
            config.put("enable_log_correlation", "true");
            config.put("trace_methods", "com.artipie.*");
            
            // Performance settings
            config.put("transaction_sample_rate", "1.0");
            config.put("span_frames_min_duration", "5ms");
            config.put("stack_trace_limit", "50");
            
            // Span limits - increase for GroupSlice operations
            config.put("transaction_max_spans", "2000");  // Increased from default 500
            config.put("span_compression_enabled", "true");
            config.put("span_compression_exact_match_max_duration", "50ms");
            config.put("span_compression_same_kind_max_duration", "0ms");
            
            // Capture settings
            config.put("capture_body", "errors");
            config.put("capture_headers", "true");
            
            // SSL verification - allow override for testing
            final String verifyServerCert = System.getenv().getOrDefault(
                "ELASTIC_APM_VERIFY_SERVER_CERT", 
                "true"
            );
            config.put("verify_server_cert", verifyServerCert);
            if ("false".equalsIgnoreCase(verifyServerCert)) {
                LOGGER.warn("⚠️  SSL certificate verification is DISABLED - not recommended for production!");
            }
            
            LOGGER.info("Attaching Elastic APM agent...");
            ElasticApmAttacher.attach(config);
            
            LOGGER.info(
                "✅ Elastic APM agent attached successfully! service={}, environment={}, server={}",
                this.serviceName, this.environment, this.serverUrl
            );
            LOGGER.info("APM agent will start sending data on first HTTP request");
            
            // Initialize OTLP registry and custom Artipie metrics
            REGISTRY = this.createOtlpRegistry();
            this.initializeCustomMetrics();
        } catch (Exception ex) {
            LOGGER.error("❌ Failed to attach Elastic APM agent - check server URL and connectivity", ex);
        }
    }
    
    /**
     * Initialize custom Artipie business metrics.
     */
    private void initializeCustomMetrics() {
        try {
            final MeterRegistry registry = REGISTRY != null ? REGISTRY : this.createOtlpRegistry();
            
            // Initialize general Artipie metrics
            try {
                Class.forName("com.artipie.metrics.ArtipieMetrics")
                    .getMethod("initialize", io.micrometer.core.instrument.MeterRegistry.class)
                    .invoke(null, registry);
                LOGGER.info("✅ Artipie custom metrics initialized (cache, downloads, bandwidth, storage)");
            } catch (ClassNotFoundException e) {
                LOGGER.debug("Artipie metrics not available (optional)");
            }
            
            // Initialize GroupSlice metrics
            try {
                Class.forName("com.artipie.metrics.GroupSliceMetrics")
                    .getMethod("initialize", io.micrometer.core.instrument.MeterRegistry.class)
                    .invoke(null, registry);
                LOGGER.info("✅ GroupSlice metrics initialized (requests, successes, batch processing)");
            } catch (ClassNotFoundException e) {
                LOGGER.debug("GroupSlice metrics not available");
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize Artipie custom metrics", e);
        }
    }

    /**
     * Create Vert.x instance with Micrometer metrics enabled.
     * Metrics will be sent to Elastic APM.
     * 
     * @return Vertx instance with metrics
     */
    public Vertx createVertxWithMetrics() {
        if (!this.enabled) {
            return Vertx.vertx();
        }

        final MicrometerMetricsOptions metricsOptions = new MicrometerMetricsOptions()
            .setEnabled(true)
            .setMicrometerRegistry(REGISTRY != null ? REGISTRY : this.createOtlpRegistry())
            .setJvmMetricsEnabled(true)
            .setPrometheusOptions(
                new VertxPrometheusOptions()
                    .setEnabled(true)
                    .setPublishQuantiles(true)
            );

        final VertxOptions options = new VertxOptions()
            .setMetricsOptions(metricsOptions);

        LOGGER.info("Vert.x metrics enabled with Elastic APM backend");
        return Vertx.vertx(options);
    }

    /**
     * Create Elastic meter registry for metrics.
     * 
     * @return Meter registry
     */
    private MeterRegistry createOtlpRegistry() {
        final String explicit = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        final String base = explicit != null && !explicit.isEmpty() ? explicit : this.serverUrl;
        final String endpoint = base.endsWith("/") ? base + "v1/metrics" : base + "/v1/metrics";

        final String secretToken = System.getenv("ELASTIC_APM_SECRET_TOKEN");
        final String apiKey = System.getenv("ELASTIC_APM_API_KEY");

        final OtlpConfig cfg = new OtlpConfig() {
            @Override
            public String get(final String k) {
                return null;
            }

            @Override
            public String url() {
                return endpoint;
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(10);
            }

            @Override
            public java.util.Map<String,String> headers() {
                final java.util.Map<String,String> map = new java.util.HashMap<>();
                if (secretToken != null && !secretToken.isEmpty()) {
                    map.put("Authorization", "Bearer " + secretToken);
                } else if (apiKey != null && !apiKey.isEmpty()) {
                    map.put("Authorization", "ApiKey " + apiKey);
                }
                return map;
            }
        };

        return new OtlpMeterRegistry(cfg, io.micrometer.core.instrument.Clock.SYSTEM);
    }

    /**
     * Start a new APM transaction.
     * Use this for manual instrumentation of important operations.
     * 
     * @param name Transaction name
     * @param type Transaction type (e.g., "request", "background-job")
     * @return Transaction
     */
    public Optional<Transaction> startTransaction(final String name, final String type) {
        if (!this.enabled) {
            return Optional.empty();
        }
        return Optional.of(ElasticApm.startTransaction().setName(name).setType(type));
    }

    /**
     * Check if APM is enabled.
     * 
     * @return True if enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Get shared meter registry if initialized.
     * @return Registry or null if APM not attached
     */
    public static MeterRegistry registry() {
        return REGISTRY;
    }
}
