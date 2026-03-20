/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scripting;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.HashMap;
import java.util.Map;
import javax.script.ScriptException;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Script runner.
 * Job for running script in quartz
 * @since 0.30
 */
public final class ScriptRunner implements Job {

    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
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
                    .eventCategory("scripting")
                    .eventAction("script_execute")
                    .eventOutcome("failure")
                    .error(exc)
                    .log();
            }
        } else {
            EcsLogger.warn("com.auto1.pantera.scripting")
                .message("Cannot find script: " + key.toString())
                .eventCategory("scripting")
                .eventAction("script_execute")
                .eventOutcome("failure")
                .log();
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
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .field("process.name", key.toString())
                .log();
            new StdSchedulerFactory().getScheduler().deleteJob(key);
            EcsLogger.error("com.auto1.pantera.scripting")
                .message("Job stopped")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("success")
                .field("process.name", key.toString())
                .log();
        } catch (final SchedulerException error) {
            EcsLogger.error("com.auto1.pantera.scripting")
                .message("Error while stopping job")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .error(error)
                .log();
            throw new PanteraException(error);
        }
    }
}
