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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsMdc;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.MDC;

/**
 * Quartz {@link Job} wrapper that restores the scheduling thread's MDC trace
 * context for the duration of a job's execution.
 *
 * <p>{@link QuartzService} stamps {@code trace.id}, {@code span.id}, and
 * {@code user.name} into the {@link JobDataMap} at {@code scheduleJob}-time.
 * When Quartz later fires {@code Job.execute(JobExecutionContext)} on one of
 * its worker threads (potentially on a different node in JDBC-clustered
 * mode), that thread has no MDC of its own — log lines emitted from inside
 * the Job would otherwise carry no trace correlation back to the request
 * that scheduled them.
 *
 * <p>Two integration styles are supported:
 *
 * <ol>
 *   <li>Wrap an arbitrary delegate {@link Job} by scheduling
 *       {@link TracingJobWrapper} itself and storing the delegate's fully
 *       qualified class name under {@link #DELEGATE_CLASS_KEY}. The wrapper
 *       resolves the delegate, restores MDC, calls {@code execute}, then
 *       clears MDC in {@code finally}.</li>
 *   <li>For existing Job classes that the codebase schedules directly
 *       (e.g. {@code EventsProcessor}), call
 *       {@link #applyContext(JobExecutionContext)} at the start of
 *       {@code execute(...)} and {@link #clearContext()} in a {@code finally}
 *       block. {@link QuartzService} stamps the keys regardless of which
 *       style the Job uses; Jobs that don't opt in just lose the trace
 *       correlation as before — no behaviour regression.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class TracingJobWrapper implements Job {

    /** JobDataMap key for the trace.id captured at schedule time. */
    public static final String TRACE_ID_KEY = "pantera.trace.id";

    /** JobDataMap key for the span.id captured at schedule time. */
    public static final String SPAN_ID_KEY = "pantera.span.id";

    /** JobDataMap key for the user.name captured at schedule time. */
    public static final String USER_NAME_KEY = "pantera.user.name";

    /**
     * JobDataMap key for the delegate Job class fully qualified name. Set
     * only when this wrapper is used in delegating mode (integration style
     * 1 in the class Javadoc). When absent the wrapper executes as a no-op
     * Job — useful as a smoke target in tests.
     */
    public static final String DELEGATE_CLASS_KEY = "pantera.delegate.class";

    @Override
    public void execute(final JobExecutionContext context)
        throws JobExecutionException {
        applyContext(context);
        try {
            final JobDataMap data = context.getMergedJobDataMap();
            final String delegateClassName = data.getString(DELEGATE_CLASS_KEY);
            if (delegateClassName == null) {
                return;
            }
            final Job delegate;
            try {
                delegate = (Job) Class.forName(delegateClassName)
                    .getDeclaredConstructor()
                    .newInstance();
            } catch (final ReflectiveOperationException ex) {
                throw new JobExecutionException(
                    "Failed to instantiate delegate job " + delegateClassName,
                    ex
                );
            }
            delegate.execute(context);
        } finally {
            clearContext();
        }
    }

    /**
     * Stamp the current thread's MDC with trace context fields from the
     * given JobExecutionContext's data map. Safe to call when MDC is already
     * populated — it overwrites trace.id / span.id only, not adapter-set
     * keys like repository.name.
     *
     * @param context Job execution context (supplied by Quartz).
     */
    public static void applyContext(final JobExecutionContext context) {
        final JobDataMap data = context.getMergedJobDataMap();
        final String traceId = data.getString(TRACE_ID_KEY);
        final String spanId = data.getString(SPAN_ID_KEY);
        final String userName = data.getString(USER_NAME_KEY);
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(EcsMdc.TRACE_ID, traceId);
        }
        if (spanId != null && !spanId.isEmpty()) {
            MDC.put(EcsMdc.SPAN_ID, spanId);
        }
        if (userName != null && !userName.isEmpty()) {
            MDC.put(EcsMdc.USER_NAME, userName);
        }
    }

    /**
     * Remove the trace MDC keys this wrapper sets. Idempotent. Call from
     * {@code finally} in the wrapped Job's {@code execute(...)} to avoid
     * trace-context bleed across Quartz worker thread reuse.
     */
    public static void clearContext() {
        MDC.remove(EcsMdc.TRACE_ID);
        MDC.remove(EcsMdc.SPAN_ID);
        MDC.remove(EcsMdc.USER_NAME);
    }

    /**
     * Capture the current MDC trace context into a {@link JobDataMap} the
     * caller intends to pass to {@code scheduleJob(...)}. Empty / null
     * values are silently skipped so JobDataMap doesn't gain explicit
     * null entries (which Quartz JDBC mode does not serialise cleanly).
     *
     * @param data JobDataMap to populate in-place.
     * @return The same JobDataMap for chaining.
     */
    public static JobDataMap stampMdc(final JobDataMap data) {
        final String traceId = MDC.get(EcsMdc.TRACE_ID);
        final String spanId = MDC.get(EcsMdc.SPAN_ID);
        final String userName = MDC.get(EcsMdc.USER_NAME);
        if (traceId != null && !traceId.isEmpty()) {
            data.put(TRACE_ID_KEY, traceId);
        }
        if (spanId != null && !spanId.isEmpty()) {
            data.put(SPAN_ID_KEY, spanId);
        }
        if (userName != null && !userName.isEmpty()) {
            data.put(USER_NAME_KEY, userName);
        }
        return data;
    }
}
