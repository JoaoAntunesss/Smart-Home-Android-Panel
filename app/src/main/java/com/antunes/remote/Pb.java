package com.antunes.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Just enough protobuf to talk to the Android TV remote service (no library). */
final class Pb {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private void raw(long v) {
        while ((v & ~0x7FL) != 0) { out.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        out.write((int) v);
    }

    Pb num(int field, long v) { raw(((long) field << 3)); raw(v); return this; }
    Pb bytes(int field, byte[] b) { raw(((long) field << 3) | 2); raw(b.length); out.write(b, 0, b.length); return this; }
    Pb str(int field, String s) { return bytes(field, s.getBytes(StandardCharsets.UTF_8)); }
    Pb msg(int field, Pb m) { return bytes(field, m.toBytes()); }
    byte[] toBytes() { return out.toByteArray(); }

    /** Writes a length-prefixed message. */
    static void send(OutputStream os, Pb m) throws IOException {
        byte[] b = m.toBytes();
        Pb len = new Pb();
        len.raw(b.length);
        synchronized (os) {
            os.write(len.toBytes());
            os.write(b);
            os.flush();
        }
    }

    /** Reads one length-prefixed message. */
    static byte[] read(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("closed");
            len |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
        }
        byte[] b = new byte[(int) len];
        int got = 0;
        while (got < len) {
            int r = in.read(b, got, (int) len - got);
            if (r < 0) throw new IOException("closed");
            got += r;
        }
        return b;
    }

    /** Parsed message: field number to values (Long for varints, byte[] for length-delimited). */
    static final class Msg {
        final Map<Integer, List<Object>> f = new HashMap<>();
        boolean has(int n) { return f.containsKey(n); }
        long num(int n) { List<Object> l = f.get(n); return l == null || !(l.get(0) instanceof Long) ? 0 : (Long) l.get(0); }
        byte[] bytes(int n) { List<Object> l = f.get(n); return l == null || !(l.get(0) instanceof byte[]) ? null : (byte[]) l.get(0); }
        Msg sub(int n) { byte[] b = bytes(n); return b == null ? null : parse(b); }
    }

    static Msg parse(byte[] b) {
        Msg m = new Msg();
        int[] p = {0};
        while (p[0] < b.length) {
            long key = varint(b, p);
            int field = (int) (key >>> 3), type = (int) (key & 7);
            Object v;
            if (type == 0) v = varint(b, p);
            else if (type == 2) {
                int len = (int) varint(b, p);
                byte[] x = new byte[len];
                System.arraycopy(b, p[0], x, 0, len);
                p[0] += len;
                v = x;
            } else if (type == 5) { p[0] += 4; continue; }
            else if (type == 1) { p[0] += 8; continue; }
            else break;
            m.f.computeIfAbsent(field, k -> new ArrayList<>()).add(v);
        }
        return m;
    }

    private static long varint(byte[] b, int[] p) {
        long v = 0;
        int shift = 0;
        while (p[0] < b.length) {
            int c = b[p[0]++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
        }
        return v;
    }
}
