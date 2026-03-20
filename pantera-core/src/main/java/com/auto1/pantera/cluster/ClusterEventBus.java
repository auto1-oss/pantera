/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cluster;

import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.log.EcsLogger;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Cross-instance event bus using Valkey pub/sub.
 * Broadcasts events to all connected Pantera instances for HA clustering.
 * <p>
 * Events are published as strings on Valkey channels with the naming
 * convention {@code artipie:events:{topic}}. Each instance subscribes
 * to channels of interest and dispatches received messages to all
 * registered handlers for that topic.
 * <p>
 * Each instance generates a unique identifier on startup. Messages
 * published by the local instance are ignored on receipt to avoid
 * double-processing events that were already handled locally.
 * <p>
 * Message format on the wire: {@code instanceId|payload}
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
    static final String CHANNEL_PREFIX = "artipie:events:";

    /**
     * Message field separator between instance ID and payload.
     */
    private static final String SEP = "|";

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
            .eventCategory("cluster")
            .eventAction("eventbus_start")
            .eventOutcome("success")
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
        final String message = String.join(
            ClusterEventBus.SEP, this.instanceId, payload
        );
        this.pubCommands.publish(channel, message);
        EcsLogger.debug("com.auto1.pantera.cluster")
            .message("Event published: " + topic)
            .eventCategory("cluster")
            .eventAction("event_publish")
            .field("cluster.topic", topic)
            .eventOutcome("success")
            .log();
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
                .eventCategory("cluster")
                .eventAction("topic_subscribe")
                .field("cluster.topic", topic)
                .eventOutcome("success")
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
            .eventCategory("cluster")
            .eventAction("eventbus_stop")
            .eventOutcome("success")
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
            final int sep = message.indexOf(ClusterEventBus.SEP);
            if (sep < 0) {
                return;
            }
            final String sender = message.substring(0, sep);
            if (ClusterEventBus.this.instanceId.equals(sender)) {
                return;
            }
            final String payload = message.substring(sep + 1);
            final String topic = channel.substring(
                ClusterEventBus.CHANNEL_PREFIX.length()
            );
            final List<Consumer<String>> topicHandlers =
                ClusterEventBus.this.handlers.get(topic);
            if (topicHandlers == null || topicHandlers.isEmpty()) {
                return;
            }
            for (final Consumer<String> handler : topicHandlers) {
                try {
                    handler.accept(payload);
                } catch (final Exception ex) {
                    EcsLogger.error("com.auto1.pantera.cluster")
                        .message(
                            "Event handler failed for topic: " + topic
                        )
                        .error(ex)
                        .eventCategory("cluster")
                        .eventAction("event_dispatch")
                        .field("cluster.topic", topic)
                        .eventOutcome("failure")
                        .log();
                }
            }
            EcsLogger.debug("com.auto1.pantera.cluster")
                .message(
                    "Event dispatched: " + topic + " to "
                        + topicHandlers.size() + " handler(s)"
                )
                .eventCategory("cluster")
                .eventAction("event_dispatch")
                .field("cluster.topic", topic)
                .eventOutcome("success")
                .log();
        }
    }
}
