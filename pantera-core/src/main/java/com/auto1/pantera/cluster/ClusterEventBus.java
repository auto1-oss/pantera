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
package com.auto1.pantera.cluster;

import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.MDC;

/**
 * Cross-instance event bus using Valkey pub/sub.
 * Broadcasts events to all connected Pantera instances for HA clustering.
 * <p>
 * Events are published as strings on Valkey channels with the naming
 * convention {@code pantera:events:{topic}}. Each instance subscribes
 * to channels of interest and dispatches received messages to all
 * registered handlers for that topic.
 * <p>
 * Each instance generates a unique identifier on startup. Messages
 * published by the local instance are ignored on receipt to avoid
 * double-processing events that were already handled locally.
 * <p>
 * Message format on the wire (current, v2):
 * {@code v2:instanceId|traceId|spanId|payload}. The {@code v2:} prefix
 * carries the originating-request trace context so the receiver can
 * restore MDC trace.id / span.id for the duration of handler dispatch.
 * Legacy {@code instanceId|payload} (v1) is still parsed for
 * rolling-deploy compatibility — when received the handler runs with no
 * trace MDC, exactly as it did before this change.
 * <p>
 * Thread safety: this class is thread-safe. Handler lists use
 * {@link CopyOnWriteArrayList} and topic subscriptions use
 * {@link ConcurrentHashMap}.
 *
 * @since 1.20.13
 */
public final class ClusterEventBus implements AutoCloseable {

    /**
     * Channel prefix for all event bus topics.
     */
    static final String CHANNEL_PREFIX = "pantera:events:";

    /**
     * Message field separator between instance ID and payload.
     */
    private static final String SEP = "|";

    /**
     * Wire-format version that prepends trace.id / span.id between
     * instance ID and JSON payload. Receivers that see this prefix
     * restore the trace context into MDC for the handler callback;
     * receivers running an older v1 build simply observe an extra
     * couple of pipe-delimited tokens at the front of the payload
     * and degrade gracefully (no MDC restore).
     */
    private static final String PAYLOAD_VERSION_TRACE = "v2:";

    /**
     * Unique instance identifier to filter out self-published messages.
     */
    private final String instanceId;

    /**
     * Connection for subscribing (receiving messages).
     */
    private final StatefulRedisPubSubConnection<String, String> subConn;

    /**
     * Connection for publishing (sending messages).
     * Pub/sub spec requires separate connections for subscribe and publish.
     */
    private final StatefulRedisPubSubConnection<String, String> pubConn;

    /**
     * Async publish commands.
     */
    private final RedisPubSubAsyncCommands<String, String> pubCommands;

    /**
     * Registered handlers keyed by topic name.
     * Each topic can have multiple handlers.
     */
    private final Map<String, List<Consumer<String>>> handlers;

    /**
     * Constructor. Sets up pub/sub connections and the message listener.
     *
     * @param valkey Valkey connection to create pub/sub connections from
     */
    public ClusterEventBus(final ValkeyConnection valkey) {
        this.instanceId = UUID.randomUUID().toString();
        this.subConn = valkey.connectPubSub();
        this.pubConn = valkey.connectPubSub();
        this.pubCommands = this.pubConn.async();
        this.handlers = new ConcurrentHashMap<>();
        this.subConn.addListener(new Dispatcher());
        EcsLogger.info("com.auto1.pantera.cluster")
            .message(
                "Cluster event bus started (instance: "
                    + this.instanceId.substring(0, 8) + ")"
            )
            .eventCategory("host")
            .eventAction("eventbus_start")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /**
     * Publish an event to a topic.
     * The event will be broadcast to all Pantera instances subscribed
     * to this topic. The publishing instance will ignore its own message.
     *
     * @param topic Topic name (e.g. "config.change", "repo.update")
     * @param payload Event payload (typically JSON)
     */
    public void publish(final String topic, final String payload) {
        final String channel = ClusterEventBus.CHANNEL_PREFIX + topic;
        // Stamp trace.id + span.id from MDC into the wire envelope so the
        // receiver can restore the originating-request trace context. Use
        // empty strings when MDC is absent (publish from a non-request path
        // like ConfigWatchService); v1 receivers see only the instance id.
        final String traceId = nullToEmpty(MDC.get(EcsMdc.TRACE_ID));
        final String spanId = nullToEmpty(MDC.get(EcsMdc.SPAN_ID));
        final String message = ClusterEventBus.PAYLOAD_VERSION_TRACE
            + String.join(
                ClusterEventBus.SEP, this.instanceId, traceId, spanId, payload
            );
        this.pubCommands.publish(channel, message);
        EcsLogger.debug("com.auto1.pantera.cluster")
            .message("Event published: " + topic)
            .eventCategory("host")
            .eventAction("event_publish")

            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /** Coalesce nulls to empty strings to keep the wire format positional. */
    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * Subscribe a handler to a topic.
     * The handler will be called with the event payload whenever a
     * remote instance publishes to this topic. If this is the first
     * handler for the topic, the Valkey channel subscription is created.
     *
     * @param topic Topic name (e.g. "config.change", "repo.update")
     * @param handler Consumer that receives the event payload
     */
    public void subscribe(final String topic, final Consumer<String> handler) {
        final String channel = ClusterEventBus.CHANNEL_PREFIX + topic;
        final boolean firstHandler = !this.handlers.containsKey(topic);
        this.handlers
            .computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>())
            .add(handler);
        if (firstHandler) {
            this.subConn.async().subscribe(channel);
            EcsLogger.debug("com.auto1.pantera.cluster")
                .message("Subscribed to topic: " + topic)
                .eventCategory("host")
                .eventAction("topic_subscribe")
    
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Returns the unique instance identifier for this event bus.
     *
     * @return Instance ID string
     */
    public String instanceId() {
        return this.instanceId;
    }

    /**
     * Returns the number of topics with active subscriptions.
     *
     * @return Number of subscribed topics
     */
    public int topicCount() {
        return this.handlers.size();
    }

    @Override
    public void close() {
        this.subConn.close();
        this.pubConn.close();
        EcsLogger.info("com.auto1.pantera.cluster")
            .message("Cluster event bus closed")
            .eventCategory("host")
            .eventAction("eventbus_stop")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /**
     * Listener that receives Valkey pub/sub messages and dispatches
     * them to registered topic handlers.
     */
    private final class Dispatcher extends RedisPubSubAdapter<String, String> {
        @Override
        public void message(final String channel, final String message) {
            if (!channel.startsWith(ClusterEventBus.CHANNEL_PREFIX)) {
                return;
            }
            // Decode wire envelope. v2: <instanceId>|<traceId>|<spanId>|<json>
            // v1 (no prefix): <instanceId>|<json>. Rolling-deploy compatible.
            final String body;
            final boolean v2;
            if (message.startsWith(ClusterEventBus.PAYLOAD_VERSION_TRACE)) {
                body = message.substring(
                    ClusterEventBus.PAYLOAD_VERSION_TRACE.length()
                );
                v2 = true;
            } else {
                body = message;
                v2 = false;
            }
            final String[] parts;
            if (v2) {
                parts = body.split("\\" + ClusterEventBus.SEP, 4);
                if (parts.length < 4) {
                    return;
                }
            } else {
                parts = body.split("\\" + ClusterEventBus.SEP, 2);
                if (parts.length < 2) {
                    return;
                }
            }
            final String sender = parts[0];
            if (ClusterEventBus.this.instanceId.equals(sender)) {
                return;
            }
            final String traceId = v2 ? parts[1] : "";
            final String spanId = v2 ? parts[2] : "";
            final String payload = v2 ? parts[3] : parts[1];
            final String topic = channel.substring(
                ClusterEventBus.CHANNEL_PREFIX.length()
            );
            final List<Consumer<String>> topicHandlers =
                ClusterEventBus.this.handlers.get(topic);
            if (topicHandlers == null || topicHandlers.isEmpty()) {
                return;
            }
            final boolean restoreTrace = !traceId.isEmpty();
            if (restoreTrace) {
                MDC.put(EcsMdc.TRACE_ID, traceId);
                if (!spanId.isEmpty()) {
                    MDC.put(EcsMdc.SPAN_ID, spanId);
                }
            }
            try {
                for (final Consumer<String> handler : topicHandlers) {
                    try {
                        handler.accept(payload);
                    } catch (final Exception ex) {
                        EcsLogger.error("com.auto1.pantera.cluster")
                            .message(
                                "Event handler failed for topic: " + topic
                            )
                            .error(ex)
                            .eventCategory("host")
                            .eventAction("event_dispatch")

                            .eventOutcome("failure")
                            .field("log.source", "application")
                            .log();
                    }
                }
                EcsLogger.debug("com.auto1.pantera.cluster")
                    .message(
                        "Event dispatched: " + topic + " to "
                            + topicHandlers.size() + " handler(s)"
                    )
                    .eventCategory("host")
                    .eventAction("event_dispatch")

                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            } finally {
                if (restoreTrace) {
                    MDC.remove(EcsMdc.TRACE_ID);
                    if (!spanId.isEmpty()) {
                        MDC.remove(EcsMdc.SPAN_ID);
                    }
                }
            }
        }
    }
}
