package io.github.ffuf.pkg.assembly;

import io.github.ffuf.pkg.engine.Job;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.MultiError;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.input.MainInputProvider;
import io.github.ffuf.pkg.output.AuditLogger;
import io.github.ffuf.pkg.output.Stdoutput;
import io.github.ffuf.pkg.runner.SimpleRunner;
import io.github.ffuf.pkg.scraper.Scraper;
import java.io.IOException;
import java.util.function.Function;

/**
 * Wires a Job together from a Config: the input provider, the runner and the replay runner, the output, the audit
 * logger and the scraper. Main and the integration tests both build their jobs here. Matchers and filters are not
 * installed here, they need the command line flag state.
 */
public final class Assembly {
    private Assembly() {
    }

    /**
     * Builds the job with the terminal output; errors are added to errs, and the job is returned in any case.
     *
     * @param conf the validated configuration
     * @param errs collects every error found while building the job
     * @return the job, also when errs holds errors
     */
    public static Job buildJob(Config conf, MultiError errs) {
        return buildJob(conf, errs, Stdoutput::new);
    }

    /**
     * Builds the job with the output that {@code output} makes for conf.
     *
     * @param conf the validated configuration
     * @param errs collects every error found while building the job
     * @param output creates the output the job reports to
     * @return the job, also when errs holds errors
     */
    public static Job buildJob(Config conf, MultiError errs, Function<Config, Stdoutput> output) {
        MainInputProvider input = new MainInputProvider(conf, errs);
        Job job = new Job(conf, input, new SimpleRunner(conf, false), output.apply(conf));
        if (!conf.replayProxyUrl.isEmpty()) {
            job.setReplayRunner(new SimpleRunner(conf, true));
        }
        if (!conf.auditLog.isEmpty()) {
            try {
                AuditLogger auditLogger = new AuditLogger(conf.auditLog);
                job.setAuditLogger(auditLogger);
                auditLogger.write(conf);
            } catch (IOException e) {
                errs.add(e.getMessage());
            }
        }
        MultiError scraperErrs = new MultiError();
        Scraper scraper = Scraper.fromDir(Util.configDir() + "/scraper", conf.scrapers, scraperErrs);
        if (!scraperErrs.isEmpty()) {
            errs.add(scraperErrs.errorOrNull());
        }
        job.setScraper(scraper);
        if (!conf.scraperFile.isEmpty()) {
            String serr = scraper.appendFromFile(conf.scraperFile);
            if (serr != null) {
                errs.add(serr);
            }
        }
        return job;
    }
}
