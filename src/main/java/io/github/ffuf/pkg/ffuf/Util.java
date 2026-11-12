package io.github.ffuf.pkg.ffuf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers of ffuf: version metadata, configuration folders, random strings, and string helpers with Go standard library semantics. */
public final class Util {
    /** The build metadata the Maven build writes: release version, VCS revision and time. */
    static final String BUILD_INFO = "/io/github/ffuf/buildinfo.properties";
    private static final Properties BUILD = loadBuildInfo();
    /**
     * The release version a release build injects. The source value is a
     * placeholder, only ever reported when the injection, the VCS metadata and the module version are all missing.
     */
    public static final String VERSION = BUILD.getProperty("VERSION", "0.0.0");
    /** Marks a non-release build; a release build empties it, which is how version() tells a release apart. */
    public static final String VERSION_APPENDIX = BUILD.getProperty("VERSION_APPENDIX", "-dev");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", Pattern.DOTALL);

    private Util() {
    }

    private static Properties loadBuildInfo() {
        Properties p = new Properties();
        try (InputStream in = Util.class.getResourceAsStream(BUILD_INFO)) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            p.clear();
        }
        return p;
    }

    /** A version and whether it identifies a released build. */
    record ResolvedVersion(String version, boolean released) {
    }

    /** Resolves the version once: version() fills in the default User-Agent of every request. */
    private static final class VersionHolder {
        static final ResolvedVersion RESOLVED = selectVersion(VERSION, VERSION_APPENDIX, gitVersion(BUILD), moduleVersion(BUILD));
    }

    /**
     * The ffuf version string. It resolves through four sources, in this order: a release build's injected VERSION
     * (with VERSION_APPENDIX emptied), a "git-DATE-COMMIT" identifier from the VCS metadata of a build from a source
     * checkout, the module version a build from a published module records, and finally VERSION+VERSION_APPENDIX.
     *
     * @return the version string
     */
    public static String version() {
        return resolveVersion().version();
    }

    static ResolvedVersion resolveVersion() {
        return VersionHolder.RESOLVED;
    }

    /**
     * Picks which version source to report, and whether it is a release. VCS metadata outranks the module version, so
     * a working tree on a tagged commit with uncommitted changes cannot claim to be a clean release.
     */
    static ResolvedVersion selectVersion(String injected, String appendix, String git, String module) {
        if (appendix.isEmpty()) {
            return new ResolvedVersion(injected, true);
        }
        if (!git.isEmpty()) {
            return new ResolvedVersion(git, false);
        }
        if (!module.isEmpty()) {
            return new ResolvedVersion(module, true);
        }
        return new ResolvedVersion(injected + appendix, false);
    }

    /** The module version the build recorded, or "" when there is no real version to report. */
    static String moduleVersion(Properties info) {
        return normalizeModuleVersion(info.getProperty("mod.version", ""));
    }

    /** Strips the module version's leading "v" and rejects the placeholders used when no version is available. */
    static String normalizeModuleVersion(String v) {
        if (v.isEmpty() || v.equals("(devel)")) {
            return "";
        }
        return v.startsWith("v") ? v.substring(1) : v;
    }

    /**
     * Assembles "git-DATE-SHORTHASH" from the VCS metadata of the build (vcs.revision, vcs.time in RFC 3339 and
     * vcs.modified), or "" when the revision or the time is missing.
     */
    static String gitVersion(Properties info) {
        String revision = info.getProperty("vcs.revision", "");
        boolean modified = info.getProperty("vcs.modified", "").equals("true");
        OffsetDateTime stamp = null;
        try {
            stamp = OffsetDateTime.parse(info.getProperty("vcs.time", ""), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            stamp = null;
        }
        if (revision.isEmpty() || stamp == null) {
            return "";
        }
        if (revision.length() > 8) {
            revision = revision.substring(0, 8);
        }
        String date = stamp.atZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE).substring(0, 8);
        return "git-" + date + "-" + revision + (modified ? "-dirty" : "");
    }

    /** {@return the version for display: a released version gets a "v" prefix, a development build is shown as it is} */
    public static String formattedVersion() {
        ResolvedVersion v = resolveVersion();
        return v.released() ? "v" + v.version() : v.version();
    }

    /** {@return an unordered list of the unique strings of inslice: duplicates are dropped} */
    public static List<String> uniqStringSlice(List<String> inslice) {
        return new ArrayList<>(new LinkedHashSet<>(inslice));
    }

    /** {@return whether path exists and is not a directory; false when it cannot be described} */
    public static boolean fileExists(String path) {
        return Files.exists(Paths.get(path)) && !Files.isDirectory(Paths.get(path));
    }

    /** {@return whether a keyword is present in any field of a request} */
    public static boolean requestContainsKeyword(Request req, String kw) {
        return req.containsKeyword(kw);
    }

    /** {@return the host and path of a request without the file name or last part of the URL path: the per-host key} */
    public static String hostUrlFromRequest(Request req) {
        String path;
        try {
            path = io.github.ffuf.pkg.runner.GoText.text(io.github.ffuf.pkg.runner.GoUrl.parse(io.github.ffuf.pkg.runner.GoText.bin(req.url)).path);
        } catch (io.github.ffuf.pkg.runner.GoUrl.UrlException e) {
            path = "";
        }
        String[] parts = path.split("/", -1);
        String trimpath = goTrimSpace(String.join("/", java.util.Arrays.copyOf(parts, parts.length - 1)));
        return req.host + trimpath;
    }

    /** {@return whether key is one of the strings in slice} */
    public static boolean strInSlice(String key, List<String> slice) {
        return slice.contains(key);
    }

    /**
     * Creates path with its parents unless it already exists.
     *
     * @throws IOException when a folder cannot be created
     */
    public static void createConfigDir(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            Files.createDirectories(p);
        }
    }

    /** {@return the working directory: $PWD when it names the current directory, else the resolved path} */
    public static String workingDir() {
        String cwd = System.getProperty("user.dir");
        String pwd = System.getenv("PWD");
        if (pwd != null && pwd.startsWith("/")) {
            try {
                if (java.nio.file.Files.isSameFile(java.nio.file.Paths.get(pwd), java.nio.file.Paths.get(cwd))) {
                    return pwd;
                }
            } catch (java.io.IOException e) {
                return cwd;
            }
        }
        return cwd;
    }

    /** {@return the home directory, $HOME} */
    public static String homeDir() {
        String h = System.getenv("HOME");
        return h == null || h.isEmpty() ? System.getProperty("user.home") : h;
    }

    /** {@return the ffuf configuration folder} */
    public static String configDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg == null || xdg.isEmpty()) {
            xdg = Util.homeDir() + "/.config";
        }
        return xdg + "/ffuf";
    }

    /** {@return the folder that holds the run history} */
    public static String historyDir() {
        return configDir() + "/history";
    }

    /**
     * Creates the configuration folders and the default autocalibration strategy files.
     *
     * @throws IOException when a folder or file cannot be created
     */
    public static void checkOrCreateConfigDir() throws IOException {
        String base = configDir();
        createConfigDir(base);
        createConfigDir(base + "/history");
        createConfigDir(base + "/scraper");
        createConfigDir(base + "/autocalibration");
        setupDefaultAutocalibrationStrategies(base + "/autocalibration");
    }

    /** Writes basic.json, or when it exists advanced.json, with fresh random strings, unless that file exists. */
    static void setupDefaultAutocalibrationStrategies(String autocalibDir) throws IOException {
        String basic = "{\"basic_admin\":[\"admin" + randomString(16) + "\",\"admin" + randomString(8) + "\"],\"basic_random\":[\""
                + randomString(16) + "\",\"" + randomString(8) + "\"],\"htaccess\":[\".htaccess" + randomString(16) + "\",\".htaccess" + randomString(8) + "\"]}";
        String advanced = "{\"admin_dir\":[\"admin" + randomString(16) + "/\",\"admin" + randomString(8) + "/\"],\"basic_admin\":[\"admin" + randomString(16) + "\",\"admin"
                + randomString(8) + "\"],\"basic_random\":[\"" + randomString(16) + "\",\"" + randomString(8) + "\"],\"htaccess\":[\".htaccess"
                + randomString(16) + "\",\".htaccess" + randomString(8) + "\"],\"random_dir\":[\"" + randomString(16) + "/\",\"" + randomString(8) + "/\"]}";
        Path basicFile = Paths.get(autocalibDir, "basic.json");
        if (!fileExists(basicFile.toString())) {
            Files.write(basicFile, basic.getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path advancedFile = Paths.get(autocalibDir, "advanced.json");
        if (!fileExists(advancedFile.toString())) {
            Files.write(advancedFile, advanced.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** {@return n random ASCII letters} */
    public static String randomString(int n) {
        String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(letters.charAt(RANDOM.nextInt(letters.length())));
        }
        return sb.toString();
    }

    /** {@return true for the characters Go's unicode.IsSpace accepts in the Latin-1 range} */
    public static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == 0x0c || c == 0x85 || c == 0xa0;
    }

    /** {@return s without leading and trailing white space, like Go's strings.TrimSpace} */
    public static String goTrimSpace(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    /** {@return the number of non-overlapping occurrences of sub in s, as Go's strings.Count counts them} */
    public static int count(String s, String sub) {
        if (sub.isEmpty()) {
            return s.codePointCount(0, s.length()) + 1;
        }
        int c = 0;
        int i = s.indexOf(sub);
        while (i >= 0) {
            c++;
            i = s.indexOf(sub, i + sub.length());
        }
        return c;
    }

    /** {@return bytes read off the wire (one char per byte) as a string that keeps invalid UTF-8 bytes} */
    public static String latin1ToUtf8(String s) {
        return GoString.decode(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static boolean isTokenChar(char c) {
        if (c >= 0x80 || c <= 0x20 || c == 0x7f) {
            return false;
        }
        return "()<>@,;:\\\"/[]?={} \t".indexOf(c) < 0;
    }

    /** {@return the canonical form of a header name, as Go's textproto.CanonicalMIMEHeaderKey writes it} */
    public static String canonicalHeaderKey(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isTokenChar(s.charAt(i))) {
                return s;
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (upper && c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            } else if (!upper && c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            sb.append(c);
            upper = c == '-';
        }
        return sb.toString();
    }

    /** {@return s parsed with the rules of Go's strconv.ParseFloat(s, 64), or null on a syntax or range error} */
    public static Double parseGoFloat(String s) {
        Double special = goSpecialFloat(s);
        if (special != null) {
            return special;
        }
        if (!goFloatSyntax(s)) {
            return null;
        }
        double v;
        try {
            v = Double.parseDouble(s.replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        return Double.isInfinite(v) ? null : v;
    }

    private static Double goSpecialFloat(String s) {
        String t = s.toLowerCase();
        double sign = 1;
        if (t.startsWith("+") || t.startsWith("-")) {
            sign = t.startsWith("-") ? -1 : 1;
            t = t.substring(1);
            if (!t.equals("inf") && !t.equals("infinity")) {
                return null;
            }
        }
        if (t.equals("inf") || t.equals("infinity")) {
            return sign * Double.POSITIVE_INFINITY;
        }
        return t.equals("nan") ? Double.NaN : null;
    }

    /** The syntax check of strconv.readFloat, which must consume all of s. */
    private static boolean goFloatSyntax(String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        boolean hex = false;
        if (i + 1 < s.length() && s.charAt(i) == '0' && Character.toLowerCase(s.charAt(i + 1)) == 'x') {
            hex = true;
            i += 2;
        }
        boolean sawDot = false;
        boolean sawDigits = false;
        boolean underscores = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                underscores = true;
            } else if (c == '.') {
                if (sawDot) {
                    break;
                }
                sawDot = true;
            } else if ((c >= '0' && c <= '9') || (hex && "abcdefABCDEF".indexOf(c) >= 0)) {
                sawDigits = true;
            } else {
                break;
            }
        }
        if (!sawDigits) {
            return false;
        }
        if (i < s.length() && Character.toLowerCase(s.charAt(i)) == (hex ? 'p' : 'e')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
            while (i < s.length() && ((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '_')) {
                underscores |= s.charAt(i) == '_';
                i++;
            }
        } else if (hex) {
            return false;
        }
        if (underscores && !underscoreOk(s.substring(0, i))) {
            return false;
        }
        return i == s.length();
    }

    /** Whether the underscores of a number literal are well placed: only between digits (or after a base prefix). */
    private static boolean underscoreOk(String s) {
        char saw = '^';
        int i = 0;
        if (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            s = s.substring(1);
        }
        boolean hex = false;
        if (s.length() >= 2 && s.charAt(0) == '0' && "bBoOxX".indexOf(s.charAt(1)) >= 0) {
            i = 2;
            saw = '0';
            hex = Character.toLowerCase(s.charAt(1)) == 'x';
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (hex && "abcdefABCDEF".indexOf(c) >= 0)) {
                saw = '0';
                continue;
            }
            if (c == '_') {
                if (saw != '0') {
                    return false;
                }
                saw = '_';
                continue;
            }
            if (saw == '_') {
                return false;
            }
            saw = '!';
        }
        return saw != '_';
    }

    /** {@return the lower-case scheme of an absolute URL, or null when u has none} */
    public static String urlScheme(String u) {
        Matcher m = SCHEME.matcher(u);
        if (!m.matches()) {
            return null;
        }
        return m.group(1).toLowerCase();
    }

    /** {@return the host[:port] part of an absolute URL} */
    public static String urlHost(String u) {
        int i = u.indexOf("://");
        if (i < 0) {
            return "";
        }
        String rest = u.substring(i + 3);
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int j = rest.indexOf(c);
            if (j >= 0 && j < end) {
                end = j;
            }
        }
        String host = rest.substring(0, end);
        int at = host.lastIndexOf('@');
        return at >= 0 ? host.substring(at + 1) : host;
    }

    /** {@return the shortest equivalent of a slash-separated path, with the rules of Go's filepath.Clean} */
    public static String cleanPath(String p) {
        if (p.isEmpty()) {
            return ".";
        }
        boolean rooted = p.startsWith("/");
        Deque<String> parts = new ArrayDeque<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (!parts.isEmpty() && !parts.peekLast().equals("..")) {
                    parts.removeLast();
                } else if (!rooted) {
                    parts.addLast("..");
                }
                continue;
            }
            parts.addLast(seg);
        }
        String joined = String.join("/", parts);
        if (rooted) {
            return "/" + joined;
        }
        return joined.isEmpty() ? "." : joined;
    }
}
