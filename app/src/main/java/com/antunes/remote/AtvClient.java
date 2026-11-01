package com.antunes.remote;

import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Talks to the TV's built-in "Android TV Remote Service" (the same one the Google TV phone app uses):
 * port 6467 to pair once with the code shown on the TV, port 6466 to send keys, text and open apps.
 */
final class AtvClient {
    interface Listener {
        void onState(String state, boolean connected);
    }

    static final int K_HOME = 3, K_BACK = 4, K_UP = 19, K_DOWN = 20, K_LEFT = 21, K_RIGHT = 22, K_CENTER = 23,
            K_VOL_UP = 24, K_VOL_DOWN = 25, K_POWER = 26, K_MENU = 82, K_SEARCH = 84, K_PLAY_PAUSE = 85,
            K_REWIND = 89, K_FAST_FORWARD = 90, K_MUTE = 164, K_CH_UP = 166, K_CH_DOWN = 167, K_SETTINGS = 176,
            K_TV_INPUT = 178;

    private final String host;
    private final Listener listener;
    private volatile SSLSocket remote;
    private volatile OutputStream remoteOut;
    private volatile boolean running;
    private SSLSocket pairing;
    private volatile long imeCounter, fieldCounter;

    AtvClient(String host, Listener l) {
        this.host = host;
        this.listener = l;
    }

    // ------------------------------------------------------------ TLS with our own key (kept in the phone's keystore)

    /**
     * Our TLS identity: a normal RSA key with a self-signed certificate, kept in the app's private files.
     * (Keys inside the Android keystore fail in the TLS handshake on some phones, e.g. Xiaomi.)
     */
    static java.io.File filesDir;
    private static java.security.PrivateKey key;
    private static X509Certificate cert;

    private static byte[] rd(java.io.File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray(); } finally { in.close(); }
    }
    private static void wr(java.io.File f, byte[] d) throws IOException {
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        try { o.write(d); } finally { o.close(); }
    }

    private static synchronized void identity() throws Exception {
        if (key != null) return;
        java.io.File dir = filesDir;
        java.io.File kf = new java.io.File(dir, "tls.key"), cf = new java.io.File(dir, "tls.crt");
        java.security.cert.CertificateFactory f = java.security.cert.CertificateFactory.getInstance("X.509");
        if (kf.exists() && cf.exists()) {
            key = java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(rd(kf)));
            cert = (X509Certificate) f.generateCertificate(new java.io.ByteArrayInputStream(rd(cf)));
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        java.security.KeyPair kp = g.generateKeyPair();
        byte[] der = Der.selfSigned(kp, "atvremote");
        cert = (X509Certificate) f.generateCertificate(new java.io.ByteArrayInputStream(der));
        key = kp.getPrivate();
        wr(kf, key.getEncoded());
        wr(cf, der);
    }

    private static SSLContext tls() throws Exception {
        identity();
        char[] pw = "comando".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("me", key, pw, new java.security.cert.Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        TrustManager[] trust = {new X509TrustManager() { // the TV's certificate is self-signed
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trust, null);
        return ctx;
    }

    private SSLSocket open(int port) throws Exception {
        SSLSocket s = (SSLSocket) tls().getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(0);
        s.startHandshake();
        return s;
    }

    private static RSAPublicKey myKey() throws Exception {
        identity();
        return (RSAPublicKey) cert.getPublicKey();
    }

    // ------------------------------------------------------------ pairing (port 6467)

    private static Pb outer() { return new Pb().num(1, 2).num(2, 200); }
    private static Pb hex6() { return new Pb().num(1, 3).num(2, 6); }

    private static Pb.Msg expect(InputStream in) throws IOException {
        Pb.Msg m = Pb.parse(Pb.read(in));
        if (m.num(2) != 200) throw new IOException("A TV recusou (" + m.num(2) + ")");
        return m;
    }

    /** Asks the TV to show a pairing code. Blocks until the TV is showing it. */
    void startPairing() throws Exception {
        pairing = open(6467);
        InputStream in = pairing.getInputStream();
        OutputStream out = pairing.getOutputStream();
        Pb.send(out, outer().msg(10, new Pb().str(1, "atvremote").str(2, "Smart Hub (" + Build.MODEL + ")")));
        expect(in);
        Pb.send(out, outer().msg(20, new Pb().msg(1, hex6()).num(3, 1)));
        expect(in);
        Pb.send(out, outer().msg(30, new Pb().msg(1, hex6()).num(2, 1)));
        expect(in);
    }

    /** Sends the 6-character code shown on the TV. */
    void finishPairing(String code) throws Exception {
        code = code.trim().toUpperCase();
        if (!code.matches("[0-9A-F]{6}")) throw new IOException("O código tem 6 caracteres (0-9, A-F)");
        RSAPublicKey mine = myKey();
        RSAPublicKey tv = (RSAPublicKey) pairing.getSession().getPeerCertificates()[0].getPublicKey();
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        h.update(unsigned(mine.getModulus()));
        h.update(unsigned(mine.getPublicExponent()));
        h.update(unsigned(tv.getModulus()));
        h.update(unsigned(tv.getPublicExponent()));
        h.update(fromHex(code.substring(2)));
        byte[] secret = h.digest();
        if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) throw new IOException("Código errado");
        Pb.send(pairing.getOutputStream(), outer().msg(40, new Pb().bytes(1, secret)));
        expect(pairing.getInputStream());
        try { pairing.close(); } catch (IOException ignored) { }
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] fromHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // ------------------------------------------------------------ remote control (port 6466)

    /** Connects and keeps the session alive; reconnects if the connection drops. */
    void run() {
        running = true;
        int fails = 0;
        while (running) {
            try {
                listener.onState("A ligar…", false);
                SSLSocket s = open(6466);
                remote = s;
                remoteOut = s.getOutputStream();
                InputStream in = s.getInputStream();
                fails = 0;
                while (running) {
                    Pb.Msg m = Pb.parse(Pb.read(in));
                    if (m.has(1)) {
                        Pb.send(remoteOut, new Pb().msg(1, new Pb().num(1, 622).msg(2, new Pb()
                                .str(1, Build.MODEL).str(2, Build.MANUFACTURER).num(3, 1).str(4, "1")
                                .str(5, "atvremote").str(6, "1.0.0"))));
                    } else if (m.has(2)) {
                        Pb.send(remoteOut, new Pb().msg(2, new Pb().num(1, 622)));
                        listener.onState("Ligado", true);
                    } else if (m.has(8)) {
                        Pb.Msg p = m.sub(8);
                        Pb.send(remoteOut, new Pb().msg(9, new Pb().num(1, p == null ? 0 : p.num(1))));
                    } else if (m.has(21)) { // the TV opened a text field: remember its counters for typing
                        Pb.Msg e = m.sub(21);
                        if (e != null) { imeCounter = e.num(1); fieldCounter = e.num(2); }
                    }
                }
            } catch (javax.net.ssl.SSLException e) {
                remoteOut = null;
                if (!running) return;
                listener.onState("PAIR", false); // the TV rejects phones it has not paired with
                running = false;
                return;
            } catch (Exception e) {
                remoteOut = null;
                if (!running) return;
                fails++;
                listener.onState(fails > 2 ? "TV desligada ou fora da rede" : "A ligar…", false);
                try { Thread.sleep(Math.min(10_000, 1500L * fails)); } catch (InterruptedException ie) { return; }
            }
        }
    }

    void stop() {
        running = false;
        try { if (remote != null) remote.close(); } catch (IOException ignored) { }
        try { if (pairing != null) pairing.close(); } catch (IOException ignored) { }
    }

    boolean connected() { return remoteOut != null; }

    private void send(Pb m) {
        OutputStream o = remoteOut;
        if (o == null) return;
        new Thread(() -> { try { Pb.send(o, m); } catch (IOException ignored) { } }).start();
    }

    /** direction: 1 = press, 2 = release, 3 = short press */
    void key(int code, int direction) { send(new Pb().msg(10, new Pb().num(1, code).num(2, direction))); }

    void key(int code) { key(code, 3); }

    /** Opens an app (by package) or a link on the TV. */
    void open(String pkgOrLink) {
        String link = pkgOrLink.contains("://") ? pkgOrLink : "market://launch?id=" + pkgOrLink;
        send(new Pb().msg(90, new Pb().str(1, link)));
    }

    /** Types text into the field that is open on the TV (search boxes, logins). Replaces what is there. */
    void type(String text) {
        int n = text.length();
        send(new Pb().msg(21, new Pb().num(1, imeCounter).num(2, fieldCounter)
                .msg(3, new Pb().num(1, 1).msg(2, new Pb().num(1, Math.max(0, n - 1)).num(2, Math.max(0, n - 1)).str(3, text)))));
    }
}
