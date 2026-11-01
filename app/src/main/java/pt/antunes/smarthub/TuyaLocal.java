package pt.antunes.smarthub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protocolo local da Tuya (versões 3.3, 3.4 e 3.5): fala diretamente com o aparelho na rede de casa, porta 6668.
 * Sem JSON aqui dentro: quem chama passa e recebe texto JSON.
 */
final class TuyaLocal {
    static final class Dev {
        String id, key, ip, ver;
        Dev(String id, String key, String ip, String ver) { this.id = id; this.key = key; this.ip = ip; this.ver = ver; }
    }

    interface Found { void on(String id, String ip, String ver); }

    private static final Charset U8 = Charset.forName("UTF-8");
    static int port = 6668;
    private static final SecureRandom RND = new SecureRandom();

    // ------------------------------------------------------------------ pedidos

    /** Muda pontos de dados. dps é um objeto JSON, ex.: {"20":true}. Devolve null se correu bem, ou a mensagem de erro. */
    static String control(Dev d, String dps) {
        Session s = null;
        try {
            s = new Session(d);
            s.connect();
            String t = String.valueOf(System.currentTimeMillis() / 1000);
            if (s.v >= 3.4) {
                s.send(13, ("{\"protocol\":5,\"t\":" + t + ",\"data\":{\"dps\":" + dps + "}}").getBytes(U8));
                Msg m = s.waitFor(13, 8);
                return m == null ? "sem resposta" : (m.ret != 0 ? "recusado (" + m.ret + ")" : null);
            }
            s.send(7, ("{\"devId\":\"" + d.id + "\",\"uid\":\"" + d.id + "\",\"t\":\"" + t + "\",\"dps\":" + dps + "}").getBytes(U8));
            Msg m = s.waitFor(7, 8);
            return m == null ? "sem resposta" : (m.ret != 0 ? "recusado (" + m.ret + ")" : null);
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (s != null) s.close();
        }
    }

    /** Lê o estado. Devolve o texto JSON do objeto dps, ex.: {"20":true,"22":500}; ou lança IOException. */
    static String query(Dev d) throws Exception {
        Session s = null;
        try {
            s = new Session(d);
            s.connect();
            if (s.v >= 3.4) {
                s.send(16, "{}".getBytes(U8));
                Msg m = s.waitFor(16);
                if (m == null) throw new IOException("sem resposta");
                return dpsOf(new String(m.body, U8));
            }
            String t = String.valueOf(System.currentTimeMillis() / 1000);
            s.send(10, ("{\"gwId\":\"" + d.id + "\",\"devId\":\"" + d.id + "\",\"uid\":\"" + d.id + "\",\"t\":\"" + t + "\"}").getBytes(U8));
            Msg m = s.waitFor(10);
            if (m == null) throw new IOException("sem resposta");
            return dpsOf(new String(m.body, U8));
        } finally {
            if (s != null) s.close();
        }
    }

    /** Extrai o objeto "dps" de uma resposta (aceita {"dps":{}} ou {"data":{"dps":{}}}). */
    static String dpsOf(String json) throws IOException {
        int i = json.indexOf("\"dps\"");
        if (i < 0) throw new IOException("resposta sem dados: " + json);
        int b = json.indexOf('{', i);
        if (b < 0) throw new IOException("resposta sem dados");
        int depth = 0;
        for (int k = b; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(b, k + 1); }
        }
        throw new IOException("resposta cortada");
    }

    // ------------------------------------------------------------------ sessão

    private static final class Msg { int seq, cmd, ret; byte[] body; }

    private static final class Session {
        final Dev d;
        final double v;
        final byte[] realKey;
        byte[] key;
        Socket sock;
        InputStream in;
        OutputStream out;
        int seq = 1;

        Session(Dev d) throws IOException {
            this.d = d;
            double vv;
            try { vv = Double.parseDouble(d.ver == null || d.ver.isEmpty() ? "3.3" : d.ver); } catch (NumberFormatException e) { vv = 3.3; }
            this.v = vv;
            byte[] k = d.key.getBytes(U8);
            if (k.length != 16) throw new IOException("chave local inválida");
            this.realKey = k; this.key = k;
        }

        void connect() throws Exception {
            if (d.ip == null || d.ip.isEmpty()) throw new IOException("sem IP");
            sock = new Socket();
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(d.ip, port), 3000);
            sock.setSoTimeout(3000);
            in = sock.getInputStream();
            out = sock.getOutputStream();
            if (v >= 3.4) negotiate();
        }

        void close() { try { if (sock != null) sock.close(); } catch (IOException ignored) { } }

        private void negotiate() throws Exception {
            byte[] ln = new byte[16];
            RND.nextBytes(ln);
            send(3, ln);
            Msg r = waitFor(4);
            if (r == null || r.body.length < 48) throw new IOException("negociação falhou");
            byte[] rn = Arrays.copyOfRange(r.body, 0, 16);
            byte[] hm = hmac(realKey, ln);
            if (!Arrays.equals(hm, Arrays.copyOfRange(r.body, 16, 48))) throw new IOException("chave local errada");
            send(5, hmac(realKey, rn));
            byte[] x = new byte[16];
            for (int i = 0; i < 16; i++) x[i] = (byte) (ln[i] ^ rn[i]);
            if (v >= 3.5) key = Arrays.copyOfRange(gcmEnc(realKey, Arrays.copyOf(ln, 12), x, null), 0, 16);
            else key = ecb(realKey, x, true, false);
        }

        void send(int cmd, byte[] payload) throws IOException {
            boolean noHdr = cmd == 3 || cmd == 4 || cmd == 5 || cmd == 9 || cmd == 10 || cmd == 16 || cmd == 18;
            byte[] p = payload;
            byte[] pkt;
            try {
                if (v >= 3.5) {
                    if (!noHdr) p = cat(verHeader(), p);
                    byte[] iv = new byte[12]; RND.nextBytes(iv);
                    byte[] head = new byte[18];
                    put(head, 0, 0x00006699); put16(head, 4, 0); put(head, 6, seq++); put(head, 10, cmd); put(head, 14, p.length + 28);
                    byte[] enc = gcmEnc(key, iv, p, Arrays.copyOfRange(head, 4, 18)); // texto cifrado + etiqueta (16)
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    o.write(head); o.write(iv); o.write(enc); o.write(new byte[]{0, 0, (byte) 0x99, 0x66});
                    pkt = o.toByteArray();
                } else {
                    if (v >= 3.4) {
                        if (!noHdr) p = cat(verHeader(), p);
                        p = ecb(key, p, true, true);
                    } else {
                        p = ecb(key, p, true, true);
                        if (!noHdr) p = cat(verHeader(), p);
                    }
                    boolean hm = v >= 3.4;
                    byte[] head = new byte[16];
                    put(head, 0, 0x000055AA); put(head, 4, seq++); put(head, 8, cmd); put(head, 12, p.length + (hm ? 36 : 8));
                    byte[] body = cat(head, p);
                    byte[] crc;
                    if (hm) crc = hmac(key, body);
                    else { CRC32 c = new CRC32(); c.update(body); crc = new byte[4]; put(crc, 0, (int) c.getValue()); }
                    pkt = cat(cat(body, crc), new byte[]{0, 0, (byte) 0xAA, 0x55});
                }
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e.getMessage()); }
            out.write(pkt);
            out.flush();
        }

        byte[] verHeader() {
            byte[] h = new byte[15];
            byte[] vs = (v >= 3.5 ? "3.5" : v >= 3.4 ? "3.4" : "3.3").getBytes(U8);
            System.arraycopy(vs, 0, h, 0, 3);
            return h;
        }

        /** Lê mensagens até chegar uma com este comando (ignora as outras, ex.: atualizações de estado). */
        Msg waitFor(int cmd) throws IOException { return waitFor(cmd, -1); }

        Msg waitFor(int cmd, int alt) throws IOException {
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                Msg m = read();
                if (m.cmd == cmd || m.cmd == alt) return m;
            }
            return null;
        }

        Msg read() throws IOException {
            byte[] h4 = readN(4);
            boolean new6699 = h4[2] == 0x66 && h4[3] == (byte) 0x99;
            Msg m = new Msg();
            try {
                if (new6699) {
                    byte[] rest = readN(14);
                    int unk = 0, len;
                    m.seq = get(rest, 2); m.cmd = get(rest, 6); len = get(rest, 10);
                    if (len > 8192 || len < 28) throw new IOException("pacote inválido");
                    byte[] body = readN(len + 4);
                    byte[] iv = Arrays.copyOfRange(body, 0, 12);
                    byte[] ct = Arrays.copyOfRange(body, 12, len); // inclui etiqueta
                    byte[] aad = cat(Arrays.copyOfRange(h4, 0, 0), rest); // dados autenticados: cabeçalho sem o prefixo
                    byte[] plain = gcmDec(key, iv, ct, aad);
                    int off = 0;
                    if (plain.length >= 4) { m.ret = get(plain, 0); off = 4; }
                    byte[] p = Arrays.copyOfRange(plain, off, plain.length);
                    m.body = stripHeader(p);
                    return m;
                }
                if (h4[0] != 0 || h4[1] != 0 || h4[2] != 0x55 || (h4[3] & 0xFF) != 0xAA) throw new IOException("prefixo inválido");
                byte[] rest = readN(12);
                m.seq = get(rest, 0); m.cmd = get(rest, 4);
                int len = get(rest, 8);
                if (len > 8192 || len < 12) throw new IOException("pacote inválido");
                byte[] body = readN(len);
                int endLen = v >= 3.4 ? 36 : 8;
                if (len < 4 + endLen) throw new IOException("pacote curto");
                m.ret = get(body, 0);
                byte[] p = Arrays.copyOfRange(body, 4, len - endLen);
                if (v >= 3.4) {
                    if (p.length > 0) p = ecb(key, p, false, true);
                    m.body = stripHeader(p);
                } else {
                    if (p.length >= 3 && p[0] == '3' && p[1] == '.') p = Arrays.copyOfRange(p, 15, p.length);
                    m.body = p.length == 0 ? p : ecb(key, p, false, true);
                }
                return m;
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e.getMessage()); }
        }

        byte[] stripHeader(byte[] p) {
            if (p.length >= 15 && p[0] == '3' && p[1] == '.') return Arrays.copyOfRange(p, 15, p.length);
            return p;
        }

        byte[] readN(int n) throws IOException {
            byte[] b = new byte[n];
            int o = 0;
            while (o < n) {
                int r = in.read(b, o, n - o);
                if (r < 0) throw new IOException("ligação fechada");
                o += r;
            }
            return b;
        }
    }

    // ------------------------------------------------------------------ descoberta (UDP)

    private static final byte[] UDPKEY = md5("yGAdlopoPVldABfn".getBytes(U8));

    /** Escuta os anúncios dos aparelhos durante ms milissegundos e diz quem encontrou. */
    static void listen(final int ms, final Found cb) {
        final int[] ports = {6666, 6667, 7000};
        Thread[] ts = new Thread[ports.length];
        for (int i = 0; i < ports.length; i++) {
            final int port = ports[i];
            ts[i] = new Thread(new Runnable() {
                @Override public void run() {
                    DatagramSocket s = null;
                    try {
                        s = new DatagramSocket(null);
                        s.setReuseAddress(true);
                        s.setBroadcast(true);
                        s.bind(new InetSocketAddress(port));
                        long end = System.currentTimeMillis() + ms;
                        byte[] buf = new byte[2048];
                        while (System.currentTimeMillis() < end) {
                            s.setSoTimeout((int) Math.max(200, end - System.currentTimeMillis()));
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            try { s.receive(p); } catch (java.net.SocketTimeoutException e) { break; }
                            try {
                                String j = udpText(Arrays.copyOf(p.getData(), p.getLength()));
                                String id = jsonStr(j, "gwId"), ip = jsonStr(j, "ip"), ver = jsonStr(j, "version");
                                if (id != null && ip != null) cb.on(id, ip, ver == null ? "3.3" : ver);
                            } catch (Exception ignored) { }
                        }
                    } catch (Exception ignored) {
                    } finally { if (s != null) s.close(); }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) { try { t.join(); } catch (InterruptedException ignored) { } }
    }

    static String udpText(byte[] d) throws Exception {
        if (d.length > 24 && d[0] == 0 && d[1] == 0 && d[2] == 0x66 && d[3] == (byte) 0x99) {
            int len = get(d, 14);
            byte[] iv = Arrays.copyOfRange(d, 18, 30);
            byte[] ct = Arrays.copyOfRange(d, 30, 18 + len);
            byte[] plain = gcmDec(UDPKEY, iv, ct, Arrays.copyOfRange(d, 4, 18));
            int off = (plain.length > 4 && plain[0] != '{' && plain[4] == '{') ? 4 : 0;
            return new String(plain, off, plain.length - off, U8).trim();
        }
        byte[] p = d;
        if (d.length > 24 && d[0] == 0 && d[1] == 0 && d[2] == 0x55 && (d[3] & 0xFF) == 0xAA) {
            int len = get(d, 12);
            p = Arrays.copyOfRange(d, 20, 16 + len - 8);
        }
        if (p.length > 0 && p[0] == '{') return new String(p, U8);
        return new String(ecb(UDPKEY, p, false, true), U8);
    }

    static String jsonStr(String j, String k) {
        int i = j.indexOf("\"" + k + "\"");
        if (i < 0) return null;
        int c = j.indexOf(':', i);
        int a = j.indexOf('"', c + 1);
        int b = j.indexOf('"', a + 1);
        if (c < 0 || a < 0 || b < 0) return null;
        return j.substring(a + 1, b);
    }

    // ------------------------------------------------------------------ utilidades

    static byte[] md5(byte[] b) { try { return MessageDigest.getInstance("MD5").digest(b); } catch (Exception e) { throw new RuntimeException(e); } }

    static byte[] hmac(byte[] key, byte[] data) throws IOException {
        try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data); } catch (Exception e) { throw new IOException(e.getMessage()); }
    }

    static byte[] ecb(byte[] key, byte[] data, boolean enc, boolean pad) throws Exception {
        Cipher c = Cipher.getInstance(pad ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    static byte[] gcmEnc(byte[] key, byte[] iv, byte[] data, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(data);
    }

    static byte[] gcmDec(byte[] key, byte[] iv, byte[] dataWithTag, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(dataWithTag);
    }

    static byte[] cat(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
    static void put(byte[] b, int o, int v) { b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v; }
    static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    static int get(byte[] b, int o) { return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF); }
}
