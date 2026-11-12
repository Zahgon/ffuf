package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.GoUnicode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** The payload encoders of -enc, applied as a chain. */
public final class Encoders {
    private static final Map<String, UnaryOperator<byte[]>> AVAILABLE = Map.ofEntries(
            Map.entry("b64encode", in -> Base64.getEncoder().encode(in)),
            Map.entry("b64decode", Encoders::b64decode),
            Map.entry("hexencode", in -> HexFormat.of().formatHex(in).getBytes(StandardCharsets.UTF_8)),
            Map.entry("hexdecode", Encoders::hexdecode),
            Map.entry("lower", in -> GoString.encode(GoUnicode.mapRunes(GoString.decode(in), Character::toLowerCase))),
            Map.entry("upper", in -> GoString.encode(GoUnicode.mapRunes(GoString.decode(in), Character::toUpperCase))),
            Map.entry("md5", in -> hash("MD5", in)),
            Map.entry("sha1", in -> hash("SHA-1", in)),
            Map.entry("sha224", in -> hash("SHA-224", in)),
            Map.entry("sha256", in -> hash("SHA-256", in)),
            Map.entry("sha384", in -> hash("SHA-384", in)),
            Map.entry("sha512", in -> hash("SHA-512", in)),
            Map.entry("urlencode", in -> queryEscape(in).getBytes(StandardCharsets.UTF_8)),
            Map.entry("urlencodeall", Encoders::urlEncodeAll),
            Map.entry("urldecode", Encoders::urldecode),
            Map.entry("utf16", in -> runes(in).getBytes(StandardCharsets.UTF_16LE)),
            Map.entry("utf16be", in -> runes(in).getBytes(StandardCharsets.UTF_16BE)),
            Map.entry("htmlescape", GoHtml::escapeString),
            Map.entry("htmlunescape", GoHtml::unescapeString),
            Map.entry("xmlescape", Encoders::xmlEscape),
            Map.entry("xmlunescape", GoXml::unescapeInput),
            Map.entry("jsonescape", GoJson::escape),
            Map.entry("jsonunescape", GoJson::unescapeInput),
            Map.entry("unicodeencodeall", Encoders::unicodeEncodeAll),
            Map.entry("unicodedecode", Encoders::unquoteBytes));

    /** An encoder failure; the value becomes empty and the message is printed. */
    public static class EncodeException extends RuntimeException {
        /** Creates the exception with its message. */
        public EncodeException(String message) {
            super(message);
        }
    }

    private final List<UnaryOperator<byte[]>> chain = new ArrayList<>();

    /** Builds a chain from encoder names; throws with ffuf's message on an unknown name. */
    public Encoders(List<String> names) {
        for (String n : names) {
            UnaryOperator<byte[]> e = AVAILABLE.get(n);
            if (e == null) {
                throw new IllegalArgumentException("Encoder " + n + " requested but not found.\n");
            }
            chain.add(e);
        }
    }

    /**
     * Applies every encoder of the chain in order.
     *
     * @param in the payload
     * @return the encoded payload
     */
    public byte[] encode(byte[] in) {
        byte[] v = in;
        for (UnaryOperator<byte[]> e : chain) {
            v = e.apply(v);
        }
        return v;
    }

    private static byte[] hash(String alg, byte[] in) {
        try {
            byte[] d = MessageDigest.getInstance(alg).digest(in);
            return HexFormat.of().formatHex(d).getBytes(StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(alg + " is part of every Java runtime", e);
        }
    }

    private static byte[] urlEncodeAll(byte[] in) {
        StringBuilder sb = new StringBuilder();
        for (byte b : in) {
            sb.append('%').append(String.format("%02X", b & 0xff));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@return the bytes escaped for a URL query component, with the rules of Go's url.QueryEscape} */
    public static String queryEscape(byte[] in) {
        StringBuilder sb = new StringBuilder();
        for (byte b : in) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }



    /** []rune(string(b)) as a string: invalid bytes become U+FFFD. */
    private static String runes(byte[] b) {
        return GoString.toValidUtf8(GoString.decode(b));
    }

    private static byte[] unicodeEncodeAll(byte[] in) {
        StringBuilder sb = new StringBuilder();
        runes(in).codePoints().forEach(cp -> sb.append("\\u").append(String.format("%04x", cp)));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** base64.StdEncoding.DecodeString: '\r' and '\n' are skipped, errors name the offending input byte. */
    private static byte[] b64decode(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int si = 0;
        while (si < src.length) {
            int[] dbuf = new int[4];
            int dlen = 4;
            boolean trailing = false;
            for (int j = 0; j < 4; j++) {
                if (si == src.length) {
                    if (j == 0) {
                        return out.toByteArray();
                    }
                    throw new EncodeException(corrupt(si - j));
                }
                int in = src[si] & 0xff;
                si++;
                int v = B64_ALPHABET.indexOf(in);
                if (v >= 0) {
                    dbuf[j] = v;
                    continue;
                }
                if (in == '\n' || in == '\r') {
                    j--;
                    continue;
                }
                if (in != '=') {
                    throw new EncodeException(corrupt(si - 1));
                }
                if (j == 0 || j == 1) {
                    throw new EncodeException(corrupt(si - 1));
                }
                if (j == 2) {
                    while (si < src.length && (src[si] == '\n' || src[si] == '\r')) {
                        si++;
                    }
                    if (si == src.length) {
                        throw new EncodeException(corrupt(src.length));
                    }
                    if (src[si] != '=') {
                        throw new EncodeException(corrupt(si - 1));
                    }
                    si++;
                }
                while (si < src.length && (src[si] == '\n' || src[si] == '\r')) {
                    si++;
                }
                if (si < src.length) {
                    throw new EncodeException(corrupt(si));
                }
                dlen = j;
                trailing = true;
                break;
            }
            int val = dbuf[0] << 18 | dbuf[1] << 12 | dbuf[2] << 6 | dbuf[3];
            out.write(val >> 16);
            if (dlen > 2) {
                out.write(val >> 8);
            }
            if (dlen > 3) {
                out.write(val);
            }
            if (trailing) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static final String B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static String corrupt(int offset) {
        return "illegal base64 data at input byte " + offset;
    }

    /** hex.DecodeString after removing every unicode.IsSpace rune (invalid bytes turn into U+FFFD on the way). */
    private static byte[] hexdecode(byte[] in) {
        StringBuilder cleaned = new StringBuilder();
        runes(in).codePoints().filter(cp -> !GoUnicode.isSpace(cp)).forEach(cleaned::appendCodePoint);
        byte[] src = cleaned.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length / 2);
        int j = 1;
        for (; j < src.length; j += 2) {
            int a = Character.digit(src[j - 1] & 0xff, 16);
            int b = Character.digit(src[j] & 0xff, 16);
            if (a < 0 || src[j - 1] < 0) {
                throw new EncodeException(invalidHexByte(src[j - 1]));
            }
            if (b < 0 || src[j] < 0) {
                throw new EncodeException(invalidHexByte(src[j]));
            }
            out.write(a << 4 | b);
        }
        if (src.length % 2 == 1) {
            if (Character.digit(src[j - 1] & 0xff, 16) < 0 || src[j - 1] < 0) {
                throw new EncodeException(invalidHexByte(src[j - 1]));
            }
            throw new EncodeException("encoding/hex: odd length hex string");
        }
        return out.toByteArray();
    }

    private static String invalidHexByte(byte b) {
        return "encoding/hex: invalid byte: " + GoUnicode.formatSharpU(b & 0xff);
    }

    /** {@return the bytes of a URL query component with its escapes decoded, with the rules of Go's url.QueryUnescape} */
    private static byte[] urldecode(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '%') {
                if (i + 2 >= in.length || hexValue(in[i + 1]) < 0 || hexValue(in[i + 2]) < 0) {
                    String bad = GoString.decode(in, i, Math.min(3, in.length - i));
                    throw new EncodeException("invalid URL escape \"" + GoUnicode.quoteBody(bad) + "\"");
                }
                out.write(hexValue(in[i + 1]) << 4 | hexValue(in[i + 2]));
                i += 2;
            } else if (b == '+') {
                out.write(' ');
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static int hexValue(byte b) {
        return b < 0 ? -1 : Character.digit(b, 16);
    }

    /** Escapes text with the rules of Go's xml.EscapeText: markup characters and tab/newline/CR as references, characters XML forbids as U+FFFD. */
    private static byte[] xmlEscape(byte[] in) {
        String s = GoString.decode(in);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            i += Character.charCount(r);
            switch (r) {
                case '"' -> sb.append("&#34;");
                case '\'' -> sb.append("&#39;");
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\t' -> sb.append("&#x9;");
                case '\n' -> sb.append("&#xA;");
                case '\r' -> sb.append("&#xD;");
                default -> {
                    boolean invalid = r <= 0xffff && GoString.isEscape((char) r);
                    sb.appendCodePoint(invalid || !GoXml.isInCharacterRange(r) ? 0xfffd : r);
                }
            }
        }
        return GoString.encode(sb.toString());
    }

    /** pencode's unquoteBytes: JSON-style escapes, stopping at the first one it cannot decode. */
    private static byte[] unquoteBytes(byte[] s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length);
        int r = 0;
        while (r < s.length) {
            int c = s[r] & 0xff;
            if (c == '\\') {
                r++;
                if (r >= s.length) {
                    return out.toByteArray();
                }
                switch (s[r]) {
                    case '"', '\\', '/', '\'' -> out.write(s[r++]);
                    case 'b' -> { out.write('\b'); r++; }
                    case 'f' -> { out.write('\f'); r++; }
                    case 'n' -> { out.write('\n'); r++; }
                    case 'r' -> { out.write('\r'); r++; }
                    case 't' -> { out.write('\t'); r++; }
                    case 'u' -> {
                        r--;
                        int rr = getu4(s, r);
                        if (rr < 0) {
                            return out.toByteArray();
                        }
                        r += 6;
                        if (rr >= 0xd800 && rr < 0xe000) {
                            int rr1 = getu4(s, r);
                            if (rr < 0xdc00 && rr1 >= 0xdc00 && rr1 < 0xe000) {
                                r += 6;
                                writeRune(out, Character.toCodePoint((char) rr, (char) rr1));
                                break;
                            }
                            rr = 0xfffd;
                        }
                        writeRune(out, rr);
                    }
                    default -> {
                        return out.toByteArray();
                    }
                }
            } else if (c < 0x80) {
                out.write(c);
                r++;
            } else {
                String one = GoString.decode(s, r, Math.min(4, s.length - r));
                int cp = one.codePointAt(0);
                r += cp <= 0xffff && GoString.isEscape((char) cp) ? 1 : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                writeRune(out, cp <= 0xffff && GoString.isEscape((char) cp) ? 0xfffd : cp);
            }
        }
        return out.toByteArray();
    }

    private static int getu4(byte[] s, int at) {
        if (s.length - at < 6 || s[at] != '\\' || s[at + 1] != 'u') {
            return -1;
        }
        int r = 0;
        for (int k = at + 2; k < at + 6; k++) {
            int d = hexValue(s[k]);
            if (d < 0) {
                return -1;
            }
            r = r * 16 + d;
        }
        return r;
    }

    private static void writeRune(ByteArrayOutputStream out, int r) {
        out.writeBytes(new String(Character.toChars(r)).getBytes(StandardCharsets.UTF_8));
    }
}
