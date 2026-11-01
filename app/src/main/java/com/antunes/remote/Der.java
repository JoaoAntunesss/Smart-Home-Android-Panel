package com.antunes.remote;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Builds a minimal self-signed X.509 certificate (no extra libraries needed on the phone). */
final class Der {
    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = b.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >> 8); o.write(n & 0xFF); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xFF); o.write(n & 0xFF); }
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... p) { return tlv(0x30, p); }

    private static byte[] oid(String dotted) {
        String[] s = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(s[0]) * 40 + Integer.parseInt(s[1]));
        for (int i = 2; i < s.length; i++) {
            long v = Long.parseLong(s[i]);
            byte[] tmp = new byte[10];
            int k = 0;
            do { tmp[k++] = (byte) (v & 0x7F); v >>= 7; } while (v > 0);
            for (int j = k - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] time(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] name(String cn) {
        return seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
    }

    static byte[] selfSigned(KeyPair kp, String cn) throws Exception {
        byte[] alg = seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00});
        long now = System.currentTimeMillis();
        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[]{2})),                       // version 3
                tlv(0x02, BigInteger.valueOf(now).toByteArray()),          // serial
                alg,
                name(cn),
                seq(time(new Date(now - 86_400_000L)), time(new Date(now + 20L * 365 * 86_400_000L))),
                name(cn),
                kp.getPublic().getEncoded());                              // SubjectPublicKeyInfo
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] s = sig.sign();
        byte[] bits = new byte[s.length + 1];
        System.arraycopy(s, 0, bits, 1, s.length);
        return seq(tbs, alg, tlv(0x03, bits));
    }
}
