/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsLogger;
import java.util.Queue;
import java.util.function.Consumer;
import org.quartz.JobExecutionContext;

/**
 * Job to process events from queue.
 * Class type is used as quarts job type and is instantiated inside {@link org.quartz}, so
 * this class must have empty ctor. Events queue and action to consume the event are
 * set by {@link org.quartz} mechanism via setters. Note, that job instance is created by
 * {@link org.quartz} on every execution, but job data is not.
 * <p/>
 * In the case of {@link EventProcessingError} processor retries each individual event up to three
 * times. If all attempts fail, the event is dropped (logged) and processing continues with the
 * next event. The job is never stopped due to individual event failures.
 * <p/>
 * Supports two data-binding modes:
 * <ul>
 *   <li><b>Direct (RAM mode):</b> Queue and Consumer are set directly via
 *       {@link #setElements(Queue)} and {@link #setAction(Consumer)}.</li>
 *   <li><b>Registry (JDBC mode):</b> Registry keys are set via
 *       {@link #setElements_key(String)} and {@link #setAction_key(String)},
 *       and actual objects are looked up from {@link JobDataRegistry}.</li>
 * </ul>
 * <a href="https://github.com/quartz-scheduler/quartz/blob/main/docs/tutorials/tutorial-lesson-02.md">Read more.</a>
 * @param <T> Elements type to process
 * @since 1.3
 */
public final class EventsProcessor<T> extends QuartzJob {

    /**
     * Retry attempts amount in the case of error.
     */
    private static final int MAX_RETRY = 3;

    /**
     * Elements.
     */
    private Queue<T> elements;

    /**
     * Action to perform on element.
     */
    private Consumer<T> action;

    @Override
    @SuppressWarnings("PMD.CognitiveComplexity")
    public void execute(final JobExecutionContext context) {
        this.resolveFromRegistry(context);
        if (this.action == null || this.elements == null) {
            super.stopJob(context);
        } else {
            int cnt = 0;
            while (!this.elements.isEmpty()) {
                final T item = this.elements.poll();
                if (item != null) {
                    boolean processed = false;
                    for (int attempt = 0; attempt < EventsProcessor.MAX_RETRY; attempt++) {
                        try {
                            this.action.accept(item);
                            cnt = cnt + 1;
                            processed = true;
                            break;
                        } catch (final EventProcessingError ex) {
                            EcsLogger.error("com.auto1.pantera.scheduling")
                                .message("Event processing failed (attempt "
                                    + (attempt + 1) + "/" + MAX_RETRY + ")")
                                .eventCategory("scheduling")
                                .eventAction("event_process")
                                .eventOutcome("failure")
                                .error(ex)
                                .log();
                        }
                    }
                    if (!processed) {
                        EcsLogger.error("com.auto1.pantera.scheduling")
                            .message("Dropping event after " + MAX_RETRY
                                + " failed attempts")
                            .eventCategory("scheduling")
                            .eventAction("event_drop")
                            .eventOutcome("failure")
                            .log();
                    }
                }
            }
            EcsLogger.debug("com.auto1.pantera.scheduling")
                .message("Processed " + cnt + " elements from queue")
                .eventCategory("scheduling")
                .eventAction("event_process")
                .eventOutcome("success")
                .field("process.thread.name", Thread.currentThread().getName())
                .log();
        }
    }

    /**
     * Set elements queue from job context (RAM mode).
     * @param queue Queue with elements to process
     */
    public void setElements(final Queue<T> queue) {
        this.elements = queue;
    }

    /**
     * Set elements consumer from job context (RAM mode).
     * @param consumer Action to consume the element
     */
    public void setAction(final Consumer<T> consumer) {
        this.action = consumer;
    }

    /**
     * Set registry key for elements queue (JDBC mode).
     * @param key Registry key to look up the queue from {@link JobDataRegistry}
     */
    @SuppressWarnings("PMD.MethodNamingConventions")
    public void setElements_key(final String key) {
        this.elements = JobDataRegistry.lookup(key);
    }

    /**
     * Set registry key for action consumer (JDBC mode).
     * @param key Registry key to look up the consumer from {@link JobDataRegistry}
     */
    @SuppressWarnings("PMD.MethodNamingConventions")
    public void setAction_key(final String key) {
        this.action = JobDataRegistry.lookup(key);
    }

    /**
     * Resolve elements and action from the job data registry if registry keys
     * are present in the context and the fields are not yet set.
     * @param context Job execution context
     */
    private void resolveFromRegistry(final JobExecutionContext context) {
        final org.quartz.JobDataMap data = context.getMergedJobDataMap();
        if (this.elements == null && data.containsKey("elements_key")) {
            this.elements = JobDataRegistry.lookup(data.getString("elements_key"));
        }
        if (this.action == null && data.containsKey("action_key")) {
            this.action = JobDataRegistry.lookup(data.getString("action_key"));
        }
    }

}
