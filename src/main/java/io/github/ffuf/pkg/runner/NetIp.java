package io.github.ffuf.pkg.runner;

/** IP address validation with the rules of Go's netip.ParseAddr, used for bracketed URL hosts. Strings are byte strings. */
final class NetIp {
    private NetIp() {
    }

    private static String error(String in, String msg, String at) {
        String s = "ParseAddr(" + GoText.quote(in) + "): " + msg;
        return at.isEmpty() ? s : s + " (at " + GoText.quote(at) + ")";
    }

    /** Whether ParseAddr would take the IPv4 path for s. */
    static boolean isIpv4(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                return true;
            }
            if (c == ':' || c == '%') {
                return false;
            }
        }
        return false;
    }

    /** The ParseAddr error text for s, or null when s is a valid address. */
    static String parseAddrError(String s) {
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '.':
                    return ipv4FieldsError(s, 0, s.length());
                case ':':
                    return ipv6Error(s);
                case '%':
                    return error(s, "missing IPv6 address", "");
                default:
                    break;
            }
        }
        return error(s, "unable to parse IP", "");
    }

    private static String ipv4FieldsError(String in, int off, int end) {
        int val = 0;
        int pos = 0;
        int digLen = 0;
        String s = in.substring(off, end);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (digLen == 1 && val == 0) {
                    return error(in, "IPv4 field has octet with leading zero", "");
                }
                val = val * 10 + c - '0';
                digLen++;
                if (val > 255) {
                    return error(in, "IPv4 field has value >255", "");
                }
            } else if (c == '.') {
                if (i == 0 || i == s.length() - 1 || s.charAt(i - 1) == '.') {
                    return error(in, "IPv4 field must have at least one digit", s.substring(i));
                }
                if (pos == 3) {
                    return error(in, "IPv4 address too long", "");
                }
                pos++;
                val = 0;
                digLen = 0;
            } else {
                return error(in, "unexpected character", s.substring(i));
            }
        }
        if (pos < 3) {
            return error(in, "IPv4 address too short", "");
        }
        return null;
    }

    private static String ipv6Error(String in) {
        String s = in;
        String zone = "";
        int pct = s.indexOf('%');
        if (pct >= 0) {
            zone = s.substring(pct + 1);
            s = s.substring(0, pct);
            if (zone.isEmpty()) {
                return error(in, "zone must be a non-empty string", "");
            }
        }
        int ellipsis = -1;
        if (s.length() >= 2 && s.charAt(0) == ':' && s.charAt(1) == ':') {
            ellipsis = 0;
            s = s.substring(2);
            if (s.isEmpty()) {
                return null;
            }
        }
        int i = 0;
        while (i < 16) {
            int off = 0;
            long acc = 0;
            for (; off < s.length(); off++) {
                int d = Character.digit(s.charAt(off), 16);
                if (d < 0) {
                    break;
                }
                acc = (acc << 4) + d;
                if (off > 3) {
                    return error(in, "each group must have 4 or less digits", s);
                }
                if (acc > 0xffff) {
                    return error(in, "IPv6 field has value >=2^16", s);
                }
            }
            if (off == 0) {
                return error(in, "each colon-separated field must have at least one digit", s);
            }
            if (off < s.length() && s.charAt(off) == '.') {
                if (ellipsis < 0 && i != 12) {
                    return error(in, "embedded IPv4 address must replace the final 2 fields of the address", s);
                }
                if (i + 4 > 16) {
                    return error(in, "too many hex fields to fit an embedded IPv4 at the end of the address", s);
                }
                int end = in.length();
                if (!zone.isEmpty()) {
                    end -= zone.length() + 1;
                }
                String err = ipv4FieldsError(in, end - s.length(), end);
                if (err != null) {
                    return err;
                }
                s = "";
                i += 4;
                break;
            }
            i += 2;
            s = s.substring(off);
            if (s.isEmpty()) {
                break;
            }
            if (s.charAt(0) != ':') {
                return error(in, "unexpected character, want colon", s);
            } else if (s.length() == 1) {
                return error(in, "colon must be followed by more characters", s);
            }
            s = s.substring(1);
            if (s.charAt(0) == ':') {
                if (ellipsis >= 0) {
                    return error(in, "multiple :: in address", s);
                }
                ellipsis = i;
                s = s.substring(1);
                if (s.isEmpty()) {
                    break;
                }
            }
        }
        if (!s.isEmpty()) {
            return error(in, "trailing garbage after address", s);
        }
        if (i < 16) {
            if (ellipsis < 0) {
                return error(in, "address string too short", "");
            }
        } else if (ellipsis >= 0) {
            return error(in, "the :: must expand to at least one field of zeros", "");
        }
        return null;
    }
}
