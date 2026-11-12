package io.github.ffuf.pkg.runner;

/**
 * A parsed URL with the parsing, formatting and resolution rules of Go's net/url (Parse, String, EscapedPath,
 * RequestURI, ResolveReference, Hostname and Port), including its error texts.
 *
 * <p>Go strings are byte strings. Every String in this class holds one byte per char (ISO-8859-1), so non-ASCII
 * input has to be converted with {@link GoText#bin(String)} first.
 */
public final class GoUrl {
    private static final int ENCODE_PATH = 1;
    private static final int ENCODE_PATH_SEGMENT = 2;
    private static final int ENCODE_HOST = 4;
    private static final int ENCODE_ZONE = 8;
    private static final int ENCODE_USER_PASSWORD = 16;
    private static final int ENCODE_QUERY_COMPONENT = 32;
    private static final int ENCODE_FRAGMENT = 64;
    private static final String UPPERHEX = "0123456789ABCDEF";

    public String scheme = "";
    public String opaque = "";
    public boolean hasUser;
    public String username = "";
    public String password = "";
    public boolean passwordSet;
    public String host = "";
    public String path = "";
    public String rawPath = "";
    public String rawQuery = "";
    public String fragment = "";
    public String rawFragment = "";
    public boolean forceQuery;
    public boolean omitHost;

    /** A url.Error, or the bare error of a parse step before it is wrapped. */
    public static final class UrlException extends Exception {
        /** Creates the exception with its message. */
        public UrlException(String message) {
            super(message);
        }
    }

    GoUrl copy() {
        GoUrl u = new GoUrl();
        u.scheme = scheme;
        u.opaque = opaque;
        u.hasUser = hasUser;
        u.username = username;
        u.password = password;
        u.passwordSet = passwordSet;
        u.host = host;
        u.path = path;
        u.rawPath = rawPath;
        u.rawQuery = rawQuery;
        u.fragment = fragment;
        u.rawFragment = rawFragment;
        u.forceQuery = forceQuery;
        u.omitHost = omitHost;
        return u;
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int unhex(int c) {
        return 9 * (c >> 6) + (c & 15);
    }

    static boolean shouldEscape(int c, int mode) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return false;
        }
        if ((mode == ENCODE_HOST || mode == ENCODE_ZONE) && "!$&'()*+,;=:[]<>\"".indexOf(c) >= 0) {
            return false;
        }
        switch (c) {
            case '-', '_', '.', '~':
                return false;
            case '$', '&', '+', ',', '/', ':', ';', '=', '?', '@':
                switch (mode) {
                    case ENCODE_PATH:
                        return c == '?';
                    case ENCODE_PATH_SEGMENT:
                        return c == '/' || c == ';' || c == ',' || c == '?';
                    case ENCODE_USER_PASSWORD:
                        return c == '@' || c == '/' || c == '?' || c == ':';
                    case ENCODE_QUERY_COMPONENT:
                        return true;
                    case ENCODE_FRAGMENT:
                        return false;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
        return !(mode == ENCODE_FRAGMENT && "!()*".indexOf(c) >= 0);
    }

    private static String escapeError(String s) {
        return "invalid URL escape " + GoText.quote(s);
    }

    static String unescape(String s, int mode) throws UrlException {
        int n = 0;
        boolean hasPlus = false;
        for (int i = 0; i < s.length();) {
            char c = s.charAt(i);
            if (c == '%') {
                n++;
                if (i + 2 >= s.length() || !isHex(s.charAt(i + 1)) || !isHex(s.charAt(i + 2))) {
                    String bad = s.substring(i);
                    throw new UrlException(escapeError(bad.length() > 3 ? bad.substring(0, 3) : bad));
                }
                if (mode == ENCODE_HOST && unhex(s.charAt(i + 1)) < 8 && !s.startsWith("%25", i)) {
                    throw new UrlException(escapeError(s.substring(i, i + 3)));
                }
                if (mode == ENCODE_ZONE) {
                    int v = unhex(s.charAt(i + 1)) << 4 | unhex(s.charAt(i + 2));
                    if (!s.startsWith("%25", i) && v != ' ' && shouldEscape(v, ENCODE_HOST)) {
                        throw new UrlException(escapeError(s.substring(i, i + 3)));
                    }
                }
                i += 3;
            } else if (c == '+') {
                hasPlus = mode == ENCODE_QUERY_COMPONENT;
                i++;
            } else {
                if ((mode == ENCODE_HOST || mode == ENCODE_ZONE) && c < 0x80 && shouldEscape(c, mode)) {
                    throw new UrlException("invalid character " + GoText.quote(s.substring(i, i + 1)) + " in host name");
                }
                i++;
            }
        }
        if (n == 0 && !hasPlus) {
            return s;
        }
        StringBuilder t = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                t.append((char) (unhex(s.charAt(i + 1)) << 4 | unhex(s.charAt(i + 2))));
                i += 2;
            } else if (c == '+') {
                t.append(mode == ENCODE_QUERY_COMPONENT ? ' ' : '+');
            } else {
                t.append(c);
            }
        }
        return t.toString();
    }

    static String escape(String s, int mode) {
        StringBuilder t = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' && mode == ENCODE_QUERY_COMPONENT) {
                t.append('+');
            } else if (shouldEscape(c, mode)) {
                t.append('%').append(UPPERHEX.charAt(c >> 4)).append(UPPERHEX.charAt(c & 15));
            } else {
                t.append(c);
            }
        }
        return t.toString();
    }

    static boolean containsCtlByte(String s) {
        for (int i = 0; i < s.length(); i++) {
            char b = s.charAt(i);
            if (b < ' ' || b == 0x7f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a URL with the rules of Go's url.Parse.
     *
     * @return the parsed URL
     * @throws UrlException with op "parse" when the URL is malformed
     */
    public static GoUrl parse(String rawUrl) throws UrlException {
        int hash = rawUrl.indexOf('#');
        String u = hash >= 0 ? rawUrl.substring(0, hash) : rawUrl;
        String frag = hash >= 0 ? rawUrl.substring(hash + 1) : "";
        GoUrl url;
        try {
            url = parse(u, false);
        } catch (UrlException e) {
            throw new UrlException("parse " + GoText.quote(u) + ": " + e.getMessage());
        }
        if (frag.isEmpty()) {
            return url;
        }
        try {
            url.setFragment(frag);
        } catch (UrlException e) {
            throw new UrlException("parse " + GoText.quote(rawUrl) + ": " + e.getMessage());
        }
        return url;
    }

    private static GoUrl parse(String rawUrl, boolean viaRequest) throws UrlException {
        if (containsCtlByte(rawUrl)) {
            throw new UrlException("net/url: invalid control character in URL");
        }
        if (rawUrl.isEmpty() && viaRequest) {
            throw new UrlException("empty url");
        }
        GoUrl url = new GoUrl();
        if (rawUrl.equals("*")) {
            url.path = "*";
            return url;
        }
        String rest = rawUrl;
        for (int i = 0; i < rawUrl.length(); i++) {
            char c = rawUrl.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                continue;
            }
            if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') {
                if (i == 0) {
                    break;
                }
                continue;
            }
            if (c == ':') {
                if (i == 0) {
                    throw new UrlException("missing protocol scheme");
                }
                url.scheme = GoText.asciiLower(rawUrl.substring(0, i));
                rest = rawUrl.substring(i + 1);
            }
            break;
        }
        if (rest.endsWith("?") && rest.indexOf('?') == rest.length() - 1) {
            url.forceQuery = true;
            rest = rest.substring(0, rest.length() - 1);
        } else {
            int q = rest.indexOf('?');
            if (q >= 0) {
                url.rawQuery = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }
        }
        if (!rest.startsWith("/")) {
            if (!url.scheme.isEmpty()) {
                url.opaque = rest;
                return url;
            }
            if (viaRequest) {
                throw new UrlException("invalid URI for request");
            }
            int slash = rest.indexOf('/');
            String segment = slash >= 0 ? rest.substring(0, slash) : rest;
            if (segment.contains(":")) {
                throw new UrlException("first path segment in URL cannot contain colon");
            }
        }
        if ((!url.scheme.isEmpty() || !viaRequest && !rest.startsWith("///")) && rest.startsWith("//")) {
            String authority = rest.substring(2);
            rest = "";
            int i = authority.indexOf('/');
            if (i >= 0) {
                rest = authority.substring(i);
                authority = authority.substring(0, i);
            }
            url.parseAuthority(authority);
        } else if (!url.scheme.isEmpty() && rest.startsWith("/")) {
            url.omitHost = true;
        }
        url.setPath(rest);
        return url;
    }

    private void parseAuthority(String authority) throws UrlException {
        int i = authority.lastIndexOf('@');
        host = parseHost(scheme, i < 0 ? authority : authority.substring(i + 1));
        if (i < 0) {
            return;
        }
        String userinfo = authority.substring(0, i);
        if (!validUserinfo(userinfo)) {
            throw new UrlException("net/url: invalid userinfo");
        }
        hasUser = true;
        int colon = userinfo.indexOf(':');
        if (colon < 0) {
            username = unescape(userinfo, ENCODE_USER_PASSWORD);
        } else {
            username = unescape(userinfo.substring(0, colon), ENCODE_USER_PASSWORD);
            password = unescape(userinfo.substring(colon + 1), ENCODE_USER_PASSWORD);
            passwordSet = true;
        }
    }

    private static String parseHost(String scheme, String host) throws UrlException {
        int open = host.lastIndexOf('[');
        if (open > 0) {
            throw new UrlException("invalid IP-literal");
        } else if (open == 0) {
            int close = host.lastIndexOf(']');
            if (close < 0) {
                throw new UrlException("missing ']' in host");
            }
            String colonPort = host.substring(close + 1);
            if (!validOptionalPort(colonPort)) {
                throw new UrlException("invalid port " + GoText.quote(colonPort) + " after host");
            }
            String unescapedColonPort = unescape(colonPort, ENCODE_HOST);
            String hostname = host.substring(1, close);
            String unescapedHostname;
            int zone = hostname.indexOf("%25");
            if (zone >= 0) {
                unescapedHostname = unescape(hostname.substring(0, zone), ENCODE_HOST) + unescape(hostname.substring(zone), ENCODE_ZONE);
            } else {
                unescapedHostname = unescape(hostname, ENCODE_HOST);
            }
            String err = NetIp.parseAddrError(unescapedHostname);
            if (err != null) {
                throw new UrlException("invalid host: " + err);
            }
            if (NetIp.isIpv4(unescapedHostname)) {
                throw new UrlException("invalid IP-literal");
            }
            return "[" + unescapedHostname + "]" + unescapedColonPort;
        }
        int i = host.indexOf(':');
        if (i >= 0) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                i = host.lastIndexOf(':');
            }
            String colonPort = host.substring(i);
            if (!validOptionalPort(colonPort)) {
                throw new UrlException("invalid port " + GoText.quote(colonPort) + " after host");
            }
        }
        return unescape(host, ENCODE_HOST);
    }

    private static boolean validUserinfo(String s) {
        for (int i = 0; i < s.length(); i++) {
            char r = s.charAt(i);
            if ((r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9')) {
                continue;
            }
            if ("-._:~!$&'()*+,;=%@".indexOf(r) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean validOptionalPort(String port) {
        if (port.isEmpty()) {
            return true;
        }
        if (port.charAt(0) != ':') {
            return false;
        }
        for (int i = 1; i < port.length(); i++) {
            char b = port.charAt(i);
            if (b < '0' || b > '9') {
                return false;
            }
        }
        return true;
    }

    void setPath(String p) throws UrlException {
        path = unescape(p, ENCODE_PATH);
        rawPath = escape(path, ENCODE_PATH).equals(p) ? "" : p;
    }

    private void setFragment(String f) throws UrlException {
        fragment = unescape(f, ENCODE_FRAGMENT);
        rawFragment = escape(fragment, ENCODE_FRAGMENT).equals(f) ? "" : f;
    }

    private static boolean validEncoded(String s, int mode) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("!$&'()*+,;=:@[]%".indexOf(c) < 0 && shouldEscape(c, mode)) {
                return false;
            }
        }
        return true;
    }

    private static boolean unescapesTo(String raw, int mode, String want) {
        try {
            return unescape(raw, mode).equals(want);
        } catch (UrlException e) {
            return false;
        }
    }

    /** {@return the escaped path, keeping the original raw path when it is a valid encoding} */
    public String escapedPath() {
        if (!rawPath.isEmpty() && validEncoded(rawPath, ENCODE_PATH) && unescapesTo(rawPath, ENCODE_PATH, path)) {
            return rawPath;
        }
        if (path.equals("*")) {
            return "*";
        }
        return escape(path, ENCODE_PATH);
    }

    /** {@return the escaped fragment, keeping the original raw fragment when it is a valid encoding} */
    public String escapedFragment() {
        if (!rawFragment.isEmpty() && validEncoded(rawFragment, ENCODE_FRAGMENT) && unescapesTo(rawFragment, ENCODE_FRAGMENT, fragment)) {
            return rawFragment;
        }
        return escape(fragment, ENCODE_FRAGMENT);
    }

    private String userString() {
        String s = escape(username, ENCODE_USER_PASSWORD);
        if (passwordSet) {
            s += ":" + escape(password, ENCODE_USER_PASSWORD);
        }
        return s;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (!scheme.isEmpty()) {
            buf.append(scheme).append(':');
        }
        if (!opaque.isEmpty()) {
            buf.append(opaque);
        } else {
            if ((!scheme.isEmpty() || !host.isEmpty() || hasUser) && !(omitHost && host.isEmpty() && !hasUser)) {
                if (!host.isEmpty() || !path.isEmpty() || hasUser) {
                    buf.append("//");
                }
                if (hasUser) {
                    buf.append(userString()).append('@');
                }
                if (!host.isEmpty()) {
                    buf.append(escape(host, ENCODE_HOST));
                }
            }
            String p = escapedPath();
            if (omitHost && host.isEmpty() && !hasUser && p.startsWith("//")) {
                buf.append("%2F");
                p = p.substring(1);
            }
            if (!p.isEmpty() && p.charAt(0) != '/' && !host.isEmpty()) {
                buf.append('/');
            }
            if (buf.length() == 0) {
                int slash = p.indexOf('/');
                String segment = slash >= 0 ? p.substring(0, slash) : p;
                if (segment.contains(":")) {
                    buf.append("./");
                }
            }
            buf.append(p);
        }
        if (forceQuery || !rawQuery.isEmpty()) {
            buf.append('?').append(rawQuery);
        }
        if (!fragment.isEmpty()) {
            buf.append('#').append(escapedFragment());
        }
        return buf.toString();
    }

    /** {@return the URL as net/http puts it in errors: String() with the password replaced by "***"} */
    public String stripPassword() {
        String s = toString();
        if (passwordSet) {
            String auth = userString() + "@";
            int i = s.indexOf(auth);
            if (i >= 0) {
                return s.substring(0, i) + username + ":***@" + s.substring(i + auth.length());
            }
        }
        return s;
    }

    /** {@return the URL text without the userinfo, as net/http builds a Referer} */
    public String withoutUserinfo() {
        String s = toString();
        if (hasUser) {
            String auth = userString() + "@";
            int i = s.indexOf(auth);
            if (i >= 0) {
                return s.substring(0, i) + s.substring(i + auth.length());
            }
        }
        return s;
    }

    /** {@return true when the URL has a scheme} */
    public boolean isAbs() {
        return !scheme.isEmpty();
    }

    /**
     * Parses ref and resolves it against this URL.
     *
     * @return the resolved URL
     * @throws UrlException when ref is malformed
     */
    public GoUrl parseRef(String ref) throws UrlException {
        return resolveReference(parse(ref));
    }

    /** {@return ref resolved against this URL with the rules of RFC 3986} */
    public GoUrl resolveReference(GoUrl ref) {
        GoUrl url = ref.copy();
        if (ref.scheme.isEmpty()) {
            url.scheme = scheme;
        }
        if (!ref.scheme.isEmpty() || !ref.host.isEmpty() || ref.hasUser) {
            url.setResolvedPath(resolvePath(ref.escapedPath(), ""));
            return url;
        }
        if (!ref.opaque.isEmpty()) {
            url.hasUser = false;
            url.host = "";
            url.path = "";
            return url;
        }
        if (ref.path.isEmpty() && !ref.forceQuery && ref.rawQuery.isEmpty()) {
            url.rawQuery = rawQuery;
            if (ref.fragment.isEmpty()) {
                url.fragment = fragment;
                url.rawFragment = rawFragment;
            }
        }
        if (ref.path.isEmpty() && !opaque.isEmpty()) {
            url.opaque = opaque;
            url.hasUser = false;
            url.host = "";
            url.path = "";
            return url;
        }
        url.host = host;
        url.hasUser = hasUser;
        url.username = username;
        url.password = password;
        url.passwordSet = passwordSet;
        url.setResolvedPath(resolvePath(escapedPath(), ref.escapedPath()));
        return url;
    }

    private void setResolvedPath(String p) {
        try {
            setPath(p);
        } catch (UrlException e) {
            // Go ignores this error: an escaped path always unescapes
        }
    }

    static String resolvePath(String base, String ref) {
        String full;
        if (ref.isEmpty()) {
            full = base;
        } else if (ref.charAt(0) != '/') {
            full = base.substring(0, base.lastIndexOf('/') + 1) + ref;
        } else {
            full = ref;
        }
        if (full.isEmpty()) {
            return "";
        }
        StringBuilder dst = new StringBuilder("/");
        String elem = "";
        String remaining = full;
        boolean found = true;
        boolean first = true;
        while (found) {
            int i = remaining.indexOf('/');
            found = i >= 0;
            elem = found ? remaining.substring(0, i) : remaining;
            remaining = found ? remaining.substring(i + 1) : "";
            switch (elem) {
                case "." -> first = false;
                case ".." -> {
                    int j = dst.lastIndexOf("/");
                    dst.setLength(j > 0 ? j : 1);
                    first = dst.length() == 1;
                }
                default -> {
                    if (!first) {
                        dst.append('/');
                    }
                    dst.append(elem);
                    first = false;
                }
            }
        }
        if (elem.equals(".") || elem.equals("..")) {
            dst.append('/');
        }
        if (dst.length() > 1 && dst.charAt(1) == '/') {
            return dst.substring(1);
        }
        return dst.toString();
    }

    /** {@return the encoded path and query sent in the request line} */
    public String requestUri() {
        String result = opaque;
        if (result.isEmpty()) {
            result = escapedPath();
            if (result.isEmpty()) {
                result = "/";
            }
        } else if (result.startsWith("//")) {
            result = scheme + ":" + result;
        }
        if (forceQuery || !rawQuery.isEmpty()) {
            result += "?" + rawQuery;
        }
        return result;
    }

    /** {@return the host without its port} */
    public String hostname() {
        return splitHostPort(host)[0];
    }

    /** {@return the port, or an empty string} */
    public String port() {
        return splitHostPort(host)[1];
    }

    private static String[] splitHostPort(String hostPort) {
        String h = hostPort;
        String p = "";
        int colon = h.lastIndexOf(':');
        if (colon >= 0 && validOptionalPort(h.substring(colon))) {
            p = h.substring(colon + 1);
            h = h.substring(0, colon);
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return new String[] {h, p};
    }
}
