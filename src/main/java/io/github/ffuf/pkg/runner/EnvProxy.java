package io.github.ffuf.pkg.runner;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proxy selection from the environment with the rules of Go's http.ProxyFromEnvironment: HTTP_PROXY, HTTPS_PROXY
 * and NO_PROXY, each also in lower case, read once, with the CGI REQUEST_METHOD rule. Hosts are byte strings.
 */
final class EnvProxy {
    static final String CGI_ERROR = "refusing to use HTTP_PROXY value in CGI environment; see golang.org/s/cgihttpproxy";
    private static EnvProxy fromSystem;

    private final GoUrl httpProxy;
    private final GoUrl httpsProxy;
    private final boolean cgi;
    private final List<Matcher> ipMatchers = new ArrayList<>();
    private final List<Matcher> domainMatchers = new ArrayList<>();

    /** HTTP_PROXY is set in a CGI environment (REQUEST_METHOD), which is never used. */
    static final class CgiException extends Exception {
        private static final long serialVersionUID = 1L;

        CgiException() {
            super(CGI_ERROR);
        }
    }

    private interface Matcher {
        boolean match(String host, String port, InetAddress ip);
    }

    EnvProxy(Map<String, String> env) {
        httpProxy = parseProxy(anyOf(env, "HTTP_PROXY", "http_proxy"));
        httpsProxy = parseProxy(anyOf(env, "HTTPS_PROXY", "https_proxy"));
        cgi = !env.getOrDefault("REQUEST_METHOD", "").isEmpty();
        parseNoProxy(anyOf(env, "NO_PROXY", "no_proxy"));
    }

    /** The configuration of this process's environment, read on first use as Go's envProxyFunc does. */
    static synchronized EnvProxy system() {
        if (fromSystem == null) {
            fromSystem = new EnvProxy(System.getenv());
        }
        return fromSystem;
    }

    private static String anyOf(Map<String, String> env, String... names) {
        for (String n : names) {
            String v = env.get(n);
            if (v != null && !v.isEmpty()) {
                return GoText.bin(v);
            }
        }
        return "";
    }

    /** A value without a scheme or host is retried with "http://" in front; a bad value is no proxy. */
    private static GoUrl parseProxy(String proxy) {
        if (proxy.isEmpty()) {
            return null;
        }
        GoUrl u = null;
        try {
            u = GoUrl.parse(proxy);
        } catch (GoUrl.UrlException e) {
            u = null;
        }
        if (u == null || u.scheme.isEmpty() || u.host.isEmpty()) {
            try {
                return GoUrl.parse("http://" + proxy);
            } catch (GoUrl.UrlException e) {
                return u;
            }
        }
        return u;
    }

    private void parseNoProxy(String noProxy) {
        for (String raw : noProxy.split(",", -1)) {
            String p = raw.strip().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals("*")) {
                ipMatchers.clear();
                domainMatchers.clear();
                ipMatchers.add((h, port, ip) -> true);
                domainMatchers.add((h, port, ip) -> true);
                return;
            }
            Matcher cidr = parseCidr(p);
            if (cidr != null) {
                ipMatchers.add(cidr);
                continue;
            }
            String phost;
            String pport;
            String[] hp = HttpGuts.splitHostPort(p);
            if (hp != null) {
                phost = hp[0];
                pport = hp[1];
                if (phost.isEmpty()) {
                    continue;
                }
                if (phost.startsWith("[") && phost.endsWith("]")) {
                    phost = phost.substring(1, phost.length() - 1);
                }
            } else {
                phost = p;
                pport = "";
            }
            InetAddress pip = parseIp(phost);
            if (pip != null) {
                String port = pport;
                ipMatchers.add((h, reqPort, ip) -> pip.equals(ip) && (port.isEmpty() || port.equals(reqPort)));
                continue;
            }
            if (phost.isEmpty()) {
                continue;
            }
            if (phost.startsWith("*.")) {
                phost = phost.substring(1);
            }
            boolean matchHost = false;
            if (phost.charAt(0) != '.') {
                matchHost = true;
                phost = "." + phost;
            }
            String asciiHost;
            try {
                asciiHost = HttpGuts.toAscii(phost);
            } catch (GoUrl.UrlException e) {
                // A name that has no ASCII (IDNA) form is matched as written.
                asciiHost = phost;
            }
            phost = asciiHost;
            String suffix = phost;
            String port = pport;
            boolean exact = matchHost;
            domainMatchers.add((h, reqPort, ip) -> (h.endsWith(suffix) || (exact && h.equals(suffix.substring(1))))
                    && (port.isEmpty() || port.equals(reqPort)));
        }
    }

    /** Parses an IP address with the rules of Go's net.ParseIP: a literal IPv4 or IPv6 address without a zone, or null. */
    static InetAddress parseIp(String s) {
        if (s.isEmpty() || s.indexOf('%') >= 0 || NetIp.parseAddrError(s) != null) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** net.ParseCIDR as a matcher, or null when p is not a CIDR. */
    private static Matcher parseCidr(String p) {
        int slash = p.indexOf('/');
        if (slash < 0) {
            return null;
        }
        InetAddress net = parseIp(p.substring(0, slash));
        String bits = p.substring(slash + 1);
        if (net == null || bits.isEmpty() || bits.length() > 3 || !bits.chars().allMatch(c -> c >= '0' && c <= '9')
                || (bits.length() > 1 && bits.charAt(0) == '0')) {
            return null;
        }
        int prefix = Integer.parseInt(bits);
        byte[] base = net.getAddress();
        if (prefix > base.length * 8) {
            return null;
        }
        return (h, port, ip) -> {
            byte[] a = ip.getAddress();
            if (a.length != base.length) {
                return false;
            }
            for (int i = 0; i < prefix; i++) {
                int mask = 0x80 >> (i % 8);
                if ((a[i / 8] & mask) != (base[i / 8] & mask)) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * The proxy for a request to target, or null for a direct connection.
     *
     * @throws CgiException when the proxy settings cannot be used for this request
     */
    GoUrl proxyFor(GoUrl target) throws CgiException {
        GoUrl proxy = null;
        if (target.scheme.equals("https")) {
            proxy = httpsProxy;
        } else if (target.scheme.equals("http")) {
            proxy = httpProxy;
            if (proxy != null && cgi) {
                throw new CgiException();
            }
        }
        if (proxy == null || !useProxy(target)) {
            return null;
        }
        return proxy;
    }

    private boolean useProxy(GoUrl target) {
        String host = target.hostname();
        try {
            host = HttpGuts.toAscii(host);
        } catch (GoUrl.UrlException e) {
            host = target.hostname();
        }
        String port = target.port();
        if (port.isEmpty()) {
            port = switch (target.scheme) {
                case "https" -> "443";
                case "socks5" -> "1080";
                default -> "80";
            };
        }
        if (host.equals("localhost")) {
            return false;
        }
        InetAddress ip = parseIp(host);
        if (ip != null && ip.isLoopbackAddress()) {
            return false;
        }
        String addr = host.strip().toLowerCase(Locale.ROOT);
        if (ip != null) {
            for (Matcher m : ipMatchers) {
                if (m.match(addr, port, ip)) {
                    return false;
                }
            }
        }
        for (Matcher m : domainMatchers) {
            if (m.match(addr, port, ip)) {
                return false;
            }
        }
        return true;
    }
}
