package io.github.ffuf.pkg.ffuf;

import io.github.ffuf.pkg.ffuf.toml.GoType;
import io.github.ffuf.pkg.ffuf.toml.Toml;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads ffufrc configuration files with go-toml's decoding rules into the option groups, matching each field by its Go name or {@code toml} tag.
 */
public final class TomlConfig {
    private TomlConfig() {
    }

    /** Thrown for unreadable or malformed files, with ffuf's message. */
    public static class TomlException extends Exception {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with its message. */
        public TomlException(String message) {
            super(message);
        }
    }

    private static final GoType STRINGS = GoType.sliceOf(GoType.STRING);

    private static final GoType VAR_EXTRACT = GoType.struct("ffuf.VarExtract", VarExtract::new, List.of(
            GoType.<VarExtract>field("name", GoType.STRING, v -> v.name, (v, x) -> v.name = (String) x),
            GoType.<VarExtract>field("regex", GoType.STRING, v -> v.regex, (v, x) -> v.regex = (String) x),
            GoType.<VarExtract>field("source", GoType.STRING, v -> v.source, (v, x) -> v.source = (String) x),
            GoType.<VarExtract>field("key", GoType.STRING, v -> v.key, (v, x) -> v.key = (String) x)));

    private static final GoType PREFLIGHT = GoType.struct("ffuf.PreflightConfig", () -> new PreflightConfig(""),
            List.of(GoType.<PreflightConfig>field("request_file", GoType.STRING, p -> p.requestFile,
                            (p, x) -> p.requestFile = (String) x),
                    GoType.<PreflightConfig>field("vars", GoType.sliceOf(VAR_EXTRACT), p -> p.vars,
                            (p, x) -> p.vars = cast(x))));

    private static final GoType PREFLIGHTS = GoType.sliceOf(PREFLIGHT);

    private static final GoType HTTP = GoType.struct("ffuf.HTTPOptions", ConfigOptions.Http::new, List.of(
            GoType.<ConfigOptions.Http>field("Cookies", STRINGS, h -> h.cookies, (h, x) -> h.cookies = cast(x)),
            GoType.<ConfigOptions.Http>field("Data", GoType.STRING, h -> h.data, (h, x) -> h.data = (String) x),
            GoType.<ConfigOptions.Http>field("FollowRedirects", GoType.BOOL, h -> h.followRedirects,
                    (h, x) -> h.followRedirects = (Boolean) x),
            GoType.<ConfigOptions.Http>field("Headers", STRINGS, h -> h.headers, (h, x) -> h.headers = cast(x)),
            GoType.<ConfigOptions.Http>field("IgnoreBody", GoType.BOOL, h -> h.ignoreBody,
                    (h, x) -> h.ignoreBody = (Boolean) x),
            GoType.<ConfigOptions.Http>field("Method", GoType.STRING, h -> h.method, (h, x) -> h.method = (String) x),
            GoType.<ConfigOptions.Http>field("ProxyURL", GoType.STRING, h -> h.proxyUrl,
                    (h, x) -> h.proxyUrl = (String) x),
            GoType.<ConfigOptions.Http>field("Raw", GoType.BOOL, h -> h.raw, (h, x) -> h.raw = (Boolean) x),
            GoType.<ConfigOptions.Http>field("Recursion", GoType.BOOL, h -> h.recursion,
                    (h, x) -> h.recursion = (Boolean) x),
            GoType.<ConfigOptions.Http>field("RecursionDepth", GoType.INT, h -> h.recursionDepth,
                    (h, x) -> h.recursionDepth = toLong(x)),
            GoType.<ConfigOptions.Http>field("RecursionStrategy", GoType.STRING, h -> h.recursionStrategy,
                    (h, x) -> h.recursionStrategy = (String) x),
            GoType.<ConfigOptions.Http>field("ReplayProxyURL", GoType.STRING, h -> h.replayProxyUrl,
                    (h, x) -> h.replayProxyUrl = (String) x),
            GoType.<ConfigOptions.Http>field("SNI", GoType.STRING, h -> h.sni, (h, x) -> h.sni = (String) x),
            GoType.<ConfigOptions.Http>field("Timeout", GoType.INT, h -> h.timeout, (h, x) -> h.timeout = toLong(x)),
            GoType.<ConfigOptions.Http>field("URL", GoType.STRING, h -> h.url, (h, x) -> h.url = (String) x),
            GoType.<ConfigOptions.Http>field("Http2", GoType.BOOL, h -> h.http2, (h, x) -> h.http2 = (Boolean) x),
            GoType.<ConfigOptions.Http>field("ClientCert", GoType.STRING, h -> h.clientCert,
                    (h, x) -> h.clientCert = (String) x),
            GoType.<ConfigOptions.Http>field("ClientKey", GoType.STRING, h -> h.clientKey,
                    (h, x) -> h.clientKey = (String) x),
            GoType.<ConfigOptions.Http>field("preflights", PREFLIGHTS, h -> h.preflights,
                    (h, x) -> h.preflights = cast(x)),
            GoType.<ConfigOptions.Http>field("postflights", PREFLIGHTS, h -> h.postflights,
                    (h, x) -> h.postflights = cast(x)),
            GoType.<ConfigOptions.Http>field("preflight_mode", GoType.STRING, h -> h.preflightMode,
                    (h, x) -> h.preflightMode = (String) x),
            GoType.<ConfigOptions.Http>field("preflight_error", GoType.STRING, h -> h.preflightError,
                    (h, x) -> h.preflightError = (String) x),
            GoType.<ConfigOptions.Http>field("preflight_anyhost", GoType.BOOL, h -> h.preflightAnyHost,
                    (h, x) -> h.preflightAnyHost = (Boolean) x)));

    private static final GoType GENERAL = GoType.struct("ffuf.GeneralOptions", ConfigOptions.General::new, List.of(
            GoType.<ConfigOptions.General>field("AutoCalibration", GoType.BOOL, g -> g.autoCalibration,
                    (g, x) -> g.autoCalibration = (Boolean) x),
            GoType.<ConfigOptions.General>field("AutoCalibrationKeyword", GoType.STRING, g -> g.autoCalibrationKeyword,
                    (g, x) -> g.autoCalibrationKeyword = (String) x),
            GoType.<ConfigOptions.General>field("AutoCalibrationPerHost", GoType.BOOL, g -> g.autoCalibrationPerHost,
                    (g, x) -> g.autoCalibrationPerHost = (Boolean) x),
            GoType.<ConfigOptions.General>field("AutoCalibrationStrategies", STRINGS,
                    g -> g.autoCalibrationStrategies, (g, x) -> g.autoCalibrationStrategies = cast(x)),
            GoType.<ConfigOptions.General>field("AutoCalibrationStrings", STRINGS, g -> g.autoCalibrationStrings,
                    (g, x) -> g.autoCalibrationStrings = cast(x)),
            GoType.<ConfigOptions.General>field("Colors", GoType.BOOL, g -> g.colors, (g, x) -> g.colors = (Boolean) x),
            GoType.<ConfigOptions.General>field("Delay", GoType.STRING, g -> g.delay, (g, x) -> g.delay = (String) x),
            GoType.<ConfigOptions.General>field("Json", GoType.BOOL, g -> g.json, (g, x) -> g.json = (Boolean) x),
            GoType.<ConfigOptions.General>field("MaxTime", GoType.INT, g -> g.maxTime, (g, x) -> g.maxTime = toLong(x)),
            GoType.<ConfigOptions.General>field("MaxTimeJob", GoType.INT, g -> g.maxTimeJob,
                    (g, x) -> g.maxTimeJob = toLong(x)),
            GoType.<ConfigOptions.General>field("Noninteractive", GoType.BOOL, g -> g.noninteractive,
                    (g, x) -> g.noninteractive = (Boolean) x),
            GoType.<ConfigOptions.General>field("Quiet", GoType.BOOL, g -> g.quiet, (g, x) -> g.quiet = (Boolean) x),
            GoType.<ConfigOptions.General>field("Rate", GoType.INT, g -> g.rate, (g, x) -> g.rate = toLong(x)),
            GoType.<ConfigOptions.General>field("ScraperFile", GoType.STRING, g -> g.scraperFile,
                    (g, x) -> g.scraperFile = (String) x),
            GoType.<ConfigOptions.General>field("Scrapers", GoType.STRING, g -> g.scrapers,
                    (g, x) -> g.scrapers = (String) x),
            GoType.<ConfigOptions.General>field("Searchhash", GoType.STRING, g -> g.searchhash,
                    (g, x) -> g.searchhash = (String) x),
            GoType.<ConfigOptions.General>field("StopOn403", GoType.BOOL, g -> g.stopOn403,
                    (g, x) -> g.stopOn403 = (Boolean) x),
            GoType.<ConfigOptions.General>field("StopOnAll", GoType.BOOL, g -> g.stopOnAll,
                    (g, x) -> g.stopOnAll = (Boolean) x),
            GoType.<ConfigOptions.General>field("StopOnErrors", GoType.BOOL, g -> g.stopOnErrors,
                    (g, x) -> g.stopOnErrors = (Boolean) x),
            GoType.<ConfigOptions.General>field("Threads", GoType.INT, g -> g.threads, (g, x) -> g.threads = toLong(x)),
            GoType.<ConfigOptions.General>field("Verbose", GoType.BOOL, g -> g.verbose,
                    (g, x) -> g.verbose = (Boolean) x)));

    private static final GoType INPUT = GoType.struct("ffuf.InputOptions", ConfigOptions.Input::new, List.of(
            GoType.<ConfigOptions.Input>field("DirSearchCompat", GoType.BOOL, i -> i.dirSearchCompat,
                    (i, x) -> i.dirSearchCompat = (Boolean) x),
            GoType.<ConfigOptions.Input>field("Encoders", STRINGS, i -> i.encoders, (i, x) -> i.encoders = cast(x)),
            GoType.<ConfigOptions.Input>field("Extensions", GoType.STRING, i -> i.extensions,
                    (i, x) -> i.extensions = (String) x),
            GoType.<ConfigOptions.Input>field("IgnoreWordlistComments", GoType.BOOL, i -> i.ignoreWordlistComments,
                    (i, x) -> i.ignoreWordlistComments = (Boolean) x),
            GoType.<ConfigOptions.Input>field("InputMode", GoType.STRING, i -> i.inputMode,
                    (i, x) -> i.inputMode = (String) x),
            GoType.<ConfigOptions.Input>field("InputNum", GoType.INT, i -> i.inputNum, (i, x) -> i.inputNum = toLong(x)),
            GoType.<ConfigOptions.Input>field("InputShell", GoType.STRING, i -> i.inputShell,
                    (i, x) -> i.inputShell = (String) x),
            GoType.<ConfigOptions.Input>field("Inputcommands", STRINGS, i -> i.inputCommands,
                    (i, x) -> i.inputCommands = cast(x)),
            GoType.<ConfigOptions.Input>field("Request", GoType.STRING, i -> i.request,
                    (i, x) -> i.request = (String) x),
            GoType.<ConfigOptions.Input>field("RequestProto", GoType.STRING, i -> i.requestProto,
                    (i, x) -> i.requestProto = (String) x),
            GoType.<ConfigOptions.Input>field("Wordlists", STRINGS, i -> i.wordlists,
                    (i, x) -> i.wordlists = cast(x))));

    private static final GoType OUTPUT = GoType.struct("ffuf.OutputOptions", ConfigOptions.Output::new, List.of(
            GoType.<ConfigOptions.Output>field("AuditLog", GoType.STRING, o -> o.auditLog,
                    (o, x) -> o.auditLog = (String) x),
            GoType.<ConfigOptions.Output>field("DebugLog", GoType.STRING, o -> o.debugLog,
                    (o, x) -> o.debugLog = (String) x),
            GoType.<ConfigOptions.Output>field("OutputDirectory", GoType.STRING, o -> o.outputDirectory,
                    (o, x) -> o.outputDirectory = (String) x),
            GoType.<ConfigOptions.Output>field("OutputFile", GoType.STRING, o -> o.outputFile,
                    (o, x) -> o.outputFile = (String) x),
            GoType.<ConfigOptions.Output>field("OutputFormat", GoType.STRING, o -> o.outputFormat,
                    (o, x) -> o.outputFormat = (String) x),
            GoType.<ConfigOptions.Output>field("OutputSkipEmptyFile", GoType.BOOL, o -> o.outputSkipEmptyFile,
                    (o, x) -> o.outputSkipEmptyFile = (Boolean) x)));

    private static GoType matchOptions(String name) {
        return GoType.struct(name, ConfigOptions.MatchOptions::new, List.of(
                GoType.<ConfigOptions.MatchOptions>field("Mode", GoType.STRING, m -> m.mode,
                        (m, x) -> m.mode = (String) x),
                GoType.<ConfigOptions.MatchOptions>field("Lines", GoType.STRING, m -> m.lines,
                        (m, x) -> m.lines = (String) x),
                GoType.<ConfigOptions.MatchOptions>field("Regexp", GoType.STRING, m -> m.regexp,
                        (m, x) -> m.regexp = (String) x),
                GoType.<ConfigOptions.MatchOptions>field("Size", GoType.STRING, m -> m.size,
                        (m, x) -> m.size = (String) x),
                GoType.<ConfigOptions.MatchOptions>field("Status", GoType.STRING, m -> m.status,
                        (m, x) -> m.status = (String) x),
                GoType.<ConfigOptions.MatchOptions>field("Time", GoType.STRING, m -> m.time,
                        (m, x) -> m.time = (String) x),
                GoType.<ConfigOptions.MatchOptions>field("Words", GoType.STRING, m -> m.words,
                        (m, x) -> m.words = (String) x)));
    }

    private static final GoType CONFIG_OPTIONS = GoType.struct("ffuf.ConfigOptions", ConfigOptions::new, List.of(
            GoType.<ConfigOptions>nested("Filter", matchOptions("ffuf.FilterOptions"), c -> c.filter),
            GoType.<ConfigOptions>nested("General", GENERAL, c -> c.general),
            GoType.<ConfigOptions>nested("HTTP", HTTP, c -> c.http),
            GoType.<ConfigOptions>nested("Input", INPUT, c -> c.input),
            GoType.<ConfigOptions>nested("Matcher", matchOptions("ffuf.MatcherOptions"), c -> c.matcher),
            GoType.<ConfigOptions>nested("Output", OUTPUT, c -> c.output)));

    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object list) {
        return new ArrayList<>((List<T>) list);
    }

    /** Go's int is 64-bit, as are the option fields. */
    private static long toLong(Object v) {
        return (Long) v;
    }

    /**
     * Reads a configuration file into fresh default options.
     *
     * @param file path of the TOML file
     * @return the default options with the values of the file
     * @throws TomlException when the file cannot be read or decoded
     */
    public static ConfigOptions readConfig(String file) throws TomlException {
        ConfigOptions conf = new ConfigOptions();
        readInto(conf, file);
        return conf;
    }

    /**
     * Decodes a configuration file into {@code conf}. On a decoding error the fields decoded before it keep their
     * values; the run still uses those options when the default config file is malformed.
     *
     * @param conf the options the file's values are written into
     * @param file path of the TOML file
     * @throws TomlException when the file cannot be read or decoded
     */
    public static void readInto(ConfigOptions conf, String file) throws TomlException {
        Path path = Paths.get(file);
        byte[] data;
        try {
            if (Files.isDirectory(path)) {
                throw new TomlException("read " + file + ": is a directory");
            }
            data = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            throw new TomlException("open " + file + ": no such file or directory");
        } catch (AccessDeniedException e) {
            throw new TomlException("open " + file + ": permission denied");
        } catch (FileSystemException e) {
            String reason = e.getReason() == null ? "input/output error" : e.getReason().toLowerCase(Locale.ROOT);
            throw new TomlException("open " + file + ": " + reason);
        } catch (IOException e) {
            throw new TomlException("read " + file + ": input/output error");
        }
        try {
            Toml.unmarshal(data, CONFIG_OPTIONS, conf);
        } catch (io.github.ffuf.pkg.ffuf.toml.TomlException e) {
            throw new TomlException(e.getMessage());
        }
    }
}
