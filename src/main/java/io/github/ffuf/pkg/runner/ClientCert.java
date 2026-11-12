package io.github.ffuf.pkg.runner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;

/**
 * tls.LoadX509KeyPair for -cc and -ck: PEM certificates and a PKCS #1, PKCS #8 or SEC 1 private key that must match
 * the leaf certificate. ffuf ignores the error, so a missing or bad pair means no client certificate.
 */
final class ClientCert {
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([^\\r\\n-]*)-----\\r?\\n(.*?)-----END \\1-----", Pattern.DOTALL);
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
        (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
    private static final byte[] EC_PUBLIC_KEY_OID = {0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01};

    private ClientCert() {
    }

    /** The key managers holding the pair, or null when either file is unset, unreadable or invalid. */
    static KeyManager[] load(String certFile, String keyFile) {
        if (certFile.isEmpty() || keyFile.isEmpty()) {
            return null;
        }
        try {
            List<Certificate> chain = certificates(Files.readString(Path.of(certFile), StandardCharsets.ISO_8859_1));
            PrivateKey key = privateKey(Files.readString(Path.of(keyFile), StandardCharsets.ISO_8859_1));
            if (chain.isEmpty() || key == null || !matches(key, chain.get(0))) {
                return null;
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("client", key, new char[0], chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            return kmf.getKeyManagers();
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Certificate> certificates(String pem) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();
        Matcher m = PEM.matcher(pem);
        while (m.find()) {
            if (m.group(1).equals("CERTIFICATE")) {
                chain.add(cf.generateCertificate(new ByteArrayInputStream(decode(m.group(2)))));
            }
        }
        return chain;
    }

    /** The first PEM block of type PRIVATE KEY or "... PRIVATE KEY", parsed as PKCS #1, PKCS #8 or SEC 1. */
    private static PrivateKey privateKey(String pem) {
        Matcher m = PEM.matcher(pem);
        while (m.find()) {
            String type = m.group(1);
            if (type.equals("PRIVATE KEY") || type.endsWith(" PRIVATE KEY")) {
                byte[] der = decode(m.group(2));
                for (byte[] pkcs8 : new byte[][] {pkcs1ToPkcs8(der), der, sec1ToPkcs8(der)}) {
                    PrivateKey k = pkcs8 == null ? null : pkcs8Key(pkcs8);
                    if (k != null) {
                        return k;
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static byte[] decode(String body) {
        return Base64.getMimeDecoder().decode(body);
    }

    private static PrivateKey pkcs8Key(byte[] der) {
        for (String alg : new String[] {"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                continue;
            }
        }
        return null;
    }

    /** Whether key signs what the certificate's public key verifies, as LoadX509KeyPair's pair check. */
    private static boolean matches(PrivateKey key, Certificate cert) throws GeneralSecurityException {
        String alg = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> "Ed25519";
        };
        byte[] probe = "ffuf client certificate".getBytes(StandardCharsets.US_ASCII);
        Signature s = Signature.getInstance(alg);
        s.initSign(key);
        s.update(probe);
        byte[] sig = s.sign();
        Signature v = Signature.getInstance(alg);
        try {
            v.initVerify(cert.getPublicKey());
        } catch (InvalidKeyException e) {
            return false;
        }
        v.update(probe);
        return v.verify(sig);
    }

    /** A PKCS #1 RSAPrivateKey (a SEQUENCE of INTEGER 0 and then the modulus) wrapped as PKCS #8. */
    private static byte[] pkcs1ToPkcs8(byte[] der) {
        int[] seq = header(der, 0, 0x30);
        if (seq == null || seq[1] + 3 > der.length || der[seq[1]] != 0x02 || der[seq[1] + 1] != 1 || der[seq[1] + 2] != 0) {
            return null;
        }
        if (header(der, seq[1] + 3, 0x02) == null) {
            return null;
        }
        return sequence(new byte[] {0x02, 0x01, 0x00}, RSA_ALGORITHM, octetString(der));
    }

    /** A SEC 1 ECPrivateKey (version 1) with its curve parameters, wrapped as PKCS #8. */
    private static byte[] sec1ToPkcs8(byte[] der) {
        int[] seq = header(der, 0, 0x30);
        if (seq == null || seq[1] + 3 > der.length || der[seq[1]] != 0x02 || der[seq[1] + 1] != 1 || der[seq[1] + 2] != 1) {
            return null;
        }
        int[] key = header(der, seq[1] + 3, 0x04);
        if (key == null) {
            return null;
        }
        int at = key[0];
        int[] params = header(der, at, 0xa0);
        if (params == null) {
            return null;
        }
        int[] oid = header(der, params[1], 0x06);
        if (oid == null) {
            return null;
        }
        byte[] curve = Arrays.copyOfRange(der, params[1], oid[0]);
        return sequence(new byte[] {0x02, 0x01, 0x00}, sequence(EC_PUBLIC_KEY_OID, curve), octetString(der));
    }

    /** The DER element of the given tag at off as {end of element, start of contents}, or null. */
    private static int[] header(byte[] der, int off, int tag) {
        if (off + 2 > der.length || (der[off] & 0xff) != tag) {
            return null;
        }
        int len = der[off + 1] & 0xff;
        int start = off + 2;
        if (len >= 0x80) {
            int n = len & 0x7f;
            if (n == 0 || n > 3 || start + n > der.length) {
                return null;
            }
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (der[start + i] & 0xff);
            }
            start += n;
        }
        if (start + len > der.length) {
            return null;
        }
        return new int[] {start + len, start};
    }

    private static byte[] octetString(byte[] contents) {
        return element(0x04, contents);
    }

    private static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.writeBytes(p);
        }
        return element(0x30, b.toByteArray());
    }

    private static byte[] element(int tag, byte[] contents) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int len = contents.length;
        if (len < 0x80) {
            b.write(len);
        } else if (len < 0x100) {
            b.write(0x81);
            b.write(len);
        } else if (len < 0x10000) {
            b.write(0x82);
            b.write(len >> 8);
            b.write(len);
        } else {
            b.write(0x83);
            b.write(len >> 16);
            b.write(len >> 8);
            b.write(len);
        }
        b.writeBytes(contents);
        return b.toByteArray();
    }
}
