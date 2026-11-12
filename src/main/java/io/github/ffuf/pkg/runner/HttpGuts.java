package io.github.ffuf.pkg.runner;

/**
 * Header, host and method validity checks and the IDNA Punycode profile applied to outgoing requests, following the
 * rules of golang.org/x/net/http/httpguts and net/http. Strings are byte strings (see {@link GoText}).
 */
final class HttpGuts {
    private static final String TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";
    private static final String HOST_SPECIALS = "!$%&()*+,-.:;=['_]~";

    private HttpGuts() {
    }

    private static boolean isAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isTokenByte(char c) {
        return isAlnum(c) || TOKEN_SPECIALS.indexOf(c) >= 0;
    }

    /** httpguts.ValidHeaderFieldName; net/http's validMethod is the same check. */
    static boolean validHeaderFieldName(String v) {
        if (v.isEmpty()) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            if (!isTokenByte(v.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean validHeaderFieldValue(String v) {
        for (int i = 0; i < v.length(); i++) {
            char b = v.charAt(i);
            boolean ctl = b < ' ' || b == 0x7f;
            if (ctl && b != ' ' && b != '\t') {
                return false;
            }
        }
        return true;
    }

    static boolean validHostHeader(String h) {
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!isAlnum(c) && HOST_SPECIALS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** {@return the host:port with a non-ASCII host converted to Punycode, as Go's httpguts.PunycodeHostPort does} */
    static String punycodeHostPort(String v) throws GoUrl.UrlException {
        if (isAscii(v)) {
            return v;
        }
        String host = v;
        String port = "";
        String[] hp = splitHostPort(v);
        if (hp != null) {
            host = hp[0];
            port = hp[1];
        }
        host = toAscii(host);
        if (port.isEmpty()) {
            return host;
        }
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** net.SplitHostPort, or null when it fails. */
    static String[] splitHostPort(String hostport) {
        int i = hostport.lastIndexOf(':');
        if (i < 0) {
            return null;
        }
        String host;
        if (hostport.startsWith("[")) {
            int end = hostport.indexOf(']');
            if (end < 0 || end + 1 != i) {
                return null;
            }
            host = hostport.substring(1, end);
            if (hostport.indexOf('[', 1) >= 0 || hostport.indexOf(']', end + 1) >= 0) {
                return null;
            }
        } else {
            host = hostport.substring(0, i);
            if (host.indexOf(':') >= 0 || host.indexOf('[') >= 0 || host.indexOf(']') >= 0 || hostport.indexOf(']') >= 0) {
                return null;
            }
        }
        return new String[] {host, hostport.substring(i + 1)};
    }

    /** idna.ToASCII with the Punycode profile: every non-ASCII label becomes "xn--" plus its Punycode. */
    static String toAscii(String host) throws GoUrl.UrlException {
        String[] labels = host.split("\\.", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                out.append('.');
            }
            out.append(isAscii(labels[i]) ? labels[i] : punycode(labels[i]));
        }
        return out.toString();
    }

    private static int adapt(int delta, int numPoints, boolean firstTime) {
        delta = firstTime ? delta / 700 : delta / 2;
        delta += delta / numPoints;
        int k = 0;
        while (delta > ((36 - 1) * 26) / 2) {
            delta /= 36 - 1;
            k += 36;
        }
        return k + (36 - 1 + 1) * delta / (delta + 38);
    }

    private static char encodeDigit(int d) {
        return (char) (d < 26 ? d + 'a' : d - 26 + '0');
    }

    /** Punycode of one label (RFC 3492) with the "xn--" prefix. */
    static String punycode(String label) throws GoUrl.UrlException {
        int[] runes = new int[label.length()];
        int count = 0;
        for (int i = 0; i < label.length();) {
            long d = GoText.decodeRune(label, i);
            int r = (int) (d >> 3);
            if (r == 0xfffd) {
                throw new GoUrl.UrlException("idna: invalid label " + GoText.quote(label));
            }
            runes[count++] = r;
            i += (int) (d & 7);
        }
        StringBuilder output = new StringBuilder("xn--");
        int b = 0;
        int remaining = 0;
        for (int k = 0; k < count; k++) {
            if (runes[k] < 0x80) {
                b++;
                output.append((char) runes[k]);
            } else {
                remaining++;
            }
        }
        int h = b;
        if (b > 0) {
            output.append('-');
        }
        int delta = 0;
        int n = 128;
        int bias = 72;
        while (remaining != 0) {
            int m = Integer.MAX_VALUE;
            for (int k = 0; k < count; k++) {
                if (m > runes[k] && runes[k] >= n) {
                    m = runes[k];
                }
            }
            delta += (m - n) * (h + 1);
            n = m;
            for (int k = 0; k < count; k++) {
                int r = runes[k];
                if (r < n) {
                    delta++;
                    continue;
                }
                if (r > n) {
                    continue;
                }
                int q = delta;
                for (int kk = 36;; kk += 36) {
                    int t = kk - bias;
                    if (kk <= bias) {
                        t = 1;
                    } else if (kk >= bias + 26) {
                        t = 26;
                    }
                    if (q < t) {
                        break;
                    }
                    output.append(encodeDigit(t + (q - t) % (36 - t)));
                    q = (q - t) / (36 - t);
                }
                output.append(encodeDigit(q));
                bias = adapt(delta, h + 1, h == b);
                delta = 0;
                h++;
                remaining--;
            }
            delta++;
            n++;
        }
        return output.toString();
    }
}
