/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.webhook;

import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Dispatches webhook events to configured endpoints.
 * Supports HMAC-SHA256 signing and async delivery with retry.
 *
 * @since 1.20.13
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class WebhookDispatcher {

    /**
     * Max retry attempts per webhook delivery.
     */
    private static final int MAX_RETRIES = 3;

    /**
     * Webhook configurations.
     */
    private final List<WebhookConfig> webhooks;

    /**
     * Vert.x web client for async HTTP.
     */
    private final WebClient client;

    /**
     * Ctor.
     * @param webhooks Webhook configurations
     * @param vertx Vert.x instance
     */
    public WebhookDispatcher(final List<WebhookConfig> webhooks, final Vertx vertx) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        final WebClientOptions opts = new WebClientOptions()
            .setConnectTimeout(5000)
            .setIdleTimeout(10);
        this.client = WebClient.create(vertx, opts);
    }

    /**
     * Dispatch an artifact event to all matching webhooks.
     *
     * @param eventType Event type (e.g., "artifact.published", "artifact.deleted")
     * @param repoName Repository name
     * @param artifactPath Artifact path
     * @param repoType Repository type
     */
    public void dispatch(
        final String eventType,
        final String repoName,
        final String artifactPath,
        final String repoType
    ) {
        final JsonObject payload = new JsonObject()
            .put("event", eventType)
            .put("timestamp", Instant.now().toString())
            .put("repository", new JsonObject()
                .put("name", repoName)
                .put("type", repoType))
            .put("artifact", new JsonObject()
                .put("path", artifactPath));
        for (final WebhookConfig webhook : this.webhooks) {
            if (webhook.matchesEvent(eventType) && webhook.matchesRepo(repoName)) {
                this.deliverAsync(webhook, payload, 0);
            }
        }
    }

    /**
     * Deliver payload to a webhook endpoint with retry.
     */
    private void deliverAsync(
        final WebhookConfig webhook, final JsonObject payload, final int attempt
    ) {
        final String body = payload.encode();
        final io.vertx.ext.web.client.HttpRequest<Buffer> request =
            this.client.postAbs(webhook.url())
                .putHeader("Content-Type", "application/json")
                .putHeader("X-Pantera-Event", payload.getString("event"));
        webhook.signingSecret().ifPresent(secret -> {
            final String signature = computeHmac(body, secret);
            request.putHeader("X-Pantera-Signature", "sha256=" + signature);
        });
        request.sendBuffer(Buffer.buffer(body), ar -> {
            if (ar.succeeded() && ar.result().statusCode() < 300) {
                EcsLogger.debug("com.auto1.pantera.webhook")
                    .message("Webhook delivered")
                    .eventCategory("webhook")
                    .eventAction("deliver")
                    .eventOutcome("success")
                    .field("url.full", webhook.url())
                    .field("event.type", payload.getString("event"))
                    .log();
            } else if (attempt < MAX_RETRIES) {
                final long delay = (long) Math.pow(2, attempt) * 1000L;
                io.vertx.core.Vertx.currentContext().owner().setTimer(delay, id ->
                    this.deliverAsync(webhook, payload, attempt + 1)
                );
            } else {
                final String error = ar.succeeded()
                    ? "HTTP " + ar.result().statusCode()
                    : ar.cause().getMessage();
                EcsLogger.warn("com.auto1.pantera.webhook")
                    .message("Webhook delivery failed after retries")
                    .eventCategory("webhook")
                    .eventAction("deliver")
                    .eventOutcome("failure")
                    .field("url.full", webhook.url())
                    .field("error.message", error)
                    .log();
            }
        });
    }

    /**
     * Compute HMAC-SHA256 signature.
     * @param payload Payload string
     * @param secret Signing secret
     * @return Hex-encoded HMAC signature
     */
    static String computeHmac(final String payload, final String secret) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            ));
            return HexFormat.of().formatHex(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (final NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", ex);
        }
    }
}
