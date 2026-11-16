package io.github.ffuf.pkg.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** -cc and -ck: tls.LoadX509KeyPair's accepted formats, and the certificate the TLS server receives. */
class ClientCertTest {
    private static final String DIR = "testdata/clientcert/";

    @Test
    void testLoadsMatchingPairs() {
        assertNotNull(ClientCert.load(DIR + "rsa.crt", DIR + "rsa-pkcs8.key"));
        assertNotNull(ClientCert.load(DIR + "rsa.crt", DIR + "rsa-pkcs1.key"));
        assertNotNull(ClientCert.load(DIR + "ec.crt", DIR + "ec-pkcs8.key"));
        assertNotNull(ClientCert.load(DIR + "ec.crt", DIR + "ec-sec1.key"));
    }

    @Test
    void testBadPairsMeanNoCertificate() {
        assertNull(ClientCert.load(DIR + "rsa.crt", DIR + "server.key"));
        assertNull(ClientCert.load(DIR + "rsa.crt", DIR + "ec-pkcs8.key"));
        assertNull(ClientCert.load(DIR + "missing.crt", DIR + "rsa-pkcs8.key"));
        assertNull(ClientCert.load(DIR + "rsa.crt", DIR + "missing.key"));
        assertNull(ClientCert.load(DIR + "rsa.crt", DIR + "rsa.crt"));
        assertNull(ClientCert.load(DIR + "rsa-pkcs8.key", DIR + "rsa-pkcs8.key"));
        assertNull(ClientCert.load("", DIR + "rsa-pkcs8.key"));
        assertNull(ClientCert.load(DIR + "rsa.crt", ""));
    }

    private static String peerSubject(String cert, String key) throws Exception {
        return peerSubject(cert, key, false);
    }

    private static String peerSubject(String cert, String key, boolean http2) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(ClientCert.load(DIR + "server.crt", DIR + "server.key"), new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                params.setWantClientAuth(true);
            }
        });
        server.createContext("/", ex -> {
            String subject = ((HttpsExchange) ex).getSSLSession().getProtocol() + " " + ex.getProtocol() + " ";
            try {
                subject += ((X509Certificate) ((HttpsExchange) ex).getSSLSession().getPeerCertificates()[0])
                        .getSubjectX500Principal().getName();
            } catch (SSLPeerUnverifiedException e) {
                subject += "none";
            }
            byte[] body = subject.getBytes(StandardCharsets.UTF_8);
            try {
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            } finally {
                ex.close();
            }
        });
        server.start();
        try {
            Config conf = new Config();
            conf.method = "GET";
            conf.timeout = 10;
            conf.clientCert = cert;
            conf.clientKey = key;
            conf.http2 = http2;
            Request req = Request.base(conf);
            req.method = "GET";
            req.url = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
            Response resp = new SimpleRunner(conf).execute(req);
            return new String(resp.data, StandardCharsets.UTF_8);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testServerReceivesClientCertificate() throws Exception {
        assertEquals("TLSv1.3 HTTP/1.1 CN=rsaclient", peerSubject(DIR + "rsa.crt", DIR + "rsa-pkcs1.key"));
        assertEquals("TLSv1.3 HTTP/1.1 CN=ecclient", peerSubject(DIR + "ec.crt", DIR + "ec-sec1.key"));
    }

    @Test
    void testNoCertificateWithoutBothFlagsOrWithABadPair() throws Exception {
        assertEquals("TLSv1.3 HTTP/1.1 none", peerSubject("", ""));
        assertEquals("TLSv1.3 HTTP/1.1 none", peerSubject(DIR + "rsa.crt", ""));
        assertEquals("TLSv1.3 HTTP/1.1 none", peerSubject(DIR + "rsa.crt", DIR + "ec-pkcs8.key"));
    }

    @Test
    void testHttp2FallsBackToHttp1WhenTheServerDoesNotChooseH2() throws Exception {
        assertEquals("TLSv1.3 HTTP/1.1 CN=rsaclient", peerSubject(DIR + "rsa.crt", DIR + "rsa-pkcs8.key", true));
    }

    @Test
    void testGoStatusText() {
        assertEquals("OK", GoStatus.text(200));
        assertEquals("Moved Permanently", GoStatus.text(301));
        assertEquals("I'm a teapot", GoStatus.text(418));
        assertEquals("", GoStatus.text(299));
    }
}
