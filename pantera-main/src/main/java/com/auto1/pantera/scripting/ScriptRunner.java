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
package com.auto1.pantera.scripting;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.trace.SpanContext;
import java.util.HashMap;
import java.util.Map;
import javax.script.ScriptException;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.MDC;

/**
 * Script runner.
 * Job for running script in quartz
 * @since 0.30
 */
public final class ScriptRunner implements Job {

    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        MDC.put(EcsMdc.TRACE_ID, SpanContext.generateHex16());
        MDC.put(EcsMdc.SPAN_ID, SpanContext.generateHex16());
        try {
            final ScriptContext scontext = (ScriptContext) context
                .getJobDetail().getJobDataMap().get("context");
            final Key key = (Key) context.getJobDetail().getJobDataMap().get("key");
            if (scontext == null || key == null) {
                this.stopJob(context);
                return;
            }
            if (scontext.getStorage().exists(key)) {
                final Script.PrecompiledScript script = scontext.getScripts().getUnchecked(key);
                try {
                    final Map<String, Object> vars = new HashMap<>();
                    vars.put("_settings", scontext.getSettings());
                    vars.put("_repositories", scontext.getRepositories());
                    script.call(vars);
                } catch (final ScriptException exc) {
                    EcsLogger.error("com.auto1.pantera.scripting")
                        .message("Execution error in script: " + key.toString())
                        .eventCategory("process")
                        .eventAction("script_execute")
                        .eventOutcome("failure")
                        .error(exc)
                        .log();
                }
            } else {
                EcsLogger.warn("com.auto1.pantera.scripting")
                    .message("Cannot find script: " + key.toString())
                    .eventCategory("process")
                    .eventAction("script_execute")
                    .eventOutcome("failure")
                    .log();
            }
        } finally {
            MDC.remove(EcsMdc.TRACE_ID);
            MDC.remove(EcsMdc.SPAN_ID);
        }
    }

    /**
     * Stops the job and logs error.
     * @param context Job context
     */
    private void stopJob(final JobExecutionContext context) {
        final JobKey key = context.getJobDetail().getKey();
        try {
            EcsLogger.error("com.auto1.pantera.scripting")
                .message("Force stopping job")
                .eventCategory("process")
                .eventAction("job_stop")
                .field("process.name", key.toString())
                .log();
            new StdSchedulerFactory().getScheduler().deleteJob(key);
            EcsLogger.error("com.auto1.pantera.scripting")
                .message("Job stopped")
                .eventCategory("process")
                .eventAction("job_stop")
                .eventOutcome("success")
                .field("process.name", key.toString())
                .log();
        } catch (final SchedulerException error) {
            EcsLogger.error("com.auto1.pantera.scripting")
                .message("Error while stopping job")
                .eventCategory("process")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .error(error)
                .log();
            throw new PanteraException(error);
        }
    }
}
