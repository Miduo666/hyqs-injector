package com.hyqs.injector.net;

/** Minimal IPv4 / TCP / UDP packet helpers (no options, no fragmentation). */
public final class Packets {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    public static final int FIN = 0x01;
    public static final int SYN = 0x02;
    public static final int RST = 0x04;
    public static final int PSH = 0x08;
    public static final int ACK = 0x10;

    private Packets() {
    }

    public static int u8(byte[] p, int i) {
        return p[i] & 0xFF;
    }

    public static int u16(byte[] p, int i) {
        return ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
    }

    public static int i32(byte[] p, int i) {
        return ((p[i] & 0xFF) << 24) | ((p[i + 1] & 0xFF) << 16) | ((p[i + 2] & 0xFF) << 8) | (p[i + 3] & 0xFF);
    }

    public static void put16(byte[] p, int i, int v) {
        p[i] = (byte) (v >>> 8);
        p[i + 1] = (byte) v;
    }

    public static void put32(byte[] p, int i, int v) {
        p[i] = (byte) (v >>> 24);
        p[i + 1] = (byte) (v >>> 16);
        p[i + 2] = (byte) (v >>> 8);
        p[i + 3] = (byte) v;
    }

    public static int ipVersion(byte[] p) {
        return (p[0] & 0xFF) >>> 4;
    }

    public static int ipHeaderLen(byte[] p) {
        return (p[0] & 0x0F) * 4;
    }

    public static int ipProto(byte[] p) {
        return p[9] & 0xFF;
    }

    public static int srcIp(byte[] p) {
        return i32(p, 12);
    }

    public static int dstIp(byte[] p) {
        return i32(p, 16);
    }

    public static int totalLen(byte[] p) {
        return u16(p, 2);
    }

    public static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "." + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    public static int parseIp(String s) {
        String[] parts = s.split("\\.");
        int v = 0;
        for (int i = 0; i < 4; i++) v = (v << 8) | (Integer.parseInt(parts[i]) & 0xFF);
        return v;
    }

    private static int checksum(byte[] data, int off, int len, int initial) {
        int sum = initial;
        int i = off;
        while (i < off + len - 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
        }
        if (i < off + len) sum += (data[i] & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (~sum) & 0xFFFF;
    }

    /** builds a complete IPv4 packet with the given payload protocol */
    private static byte[] ipPacket(int srcIp, int dstIp, int proto, byte[] payload) {
        byte[] p = new byte[20 + payload.length];
        p[0] = 0x45;
        p[1] = 0;
        put16(p, 2, p.length);
        put16(p, 4, 0);
        put16(p, 6, 0x4000); // don't fragment
        p[8] = 64;
        p[9] = (byte) proto;
        put32(p, 12, srcIp);
        put32(p, 16, dstIp);
        put16(p, 10, 0);
        put16(p, 10, checksum(p, 0, 20, 0));
        System.arraycopy(payload, 0, p, 20, payload.length);
        return p;
    }

    private static int pseudoSum(int srcIp, int dstIp, int proto, int len) {
        int sum = 0;
        sum += (srcIp >>> 16) & 0xFFFF;
        sum += srcIp & 0xFFFF;
        sum += (dstIp >>> 16) & 0xFFFF;
        sum += dstIp & 0xFFFF;
        sum += proto & 0xFFFF;
        sum += len & 0xFFFF;
        return sum;
    }

    public static byte[] tcp(int srcIp, int srcPort, int dstIp, int dstPort,
                             int seq, int ack, int flags, int window, byte[] data) {
        if (data == null) data = new byte[0];
        byte[] t = new byte[20 + data.length];
        put16(t, 0, srcPort);
        put16(t, 2, dstPort);
        put32(t, 4, seq);
        put32(t, 8, ack);
        t[12] = 0x50; // data offset 5 words, no options
        t[13] = (byte) flags;
        put16(t, 14, window);
        put16(t, 16, 0);
        put16(t, 18, 0);
        System.arraycopy(data, 0, t, 20, data.length);
        put16(t, 16, checksum(t, 0, t.length, pseudoSum(srcIp, dstIp, PROTO_TCP, t.length)));
        return ipPacket(srcIp, dstIp, PROTO_TCP, t);
    }

    public static byte[] udp(int srcIp, int srcPort, int dstIp, int dstPort, byte[] data) {
        byte[] u = new byte[8 + data.length];
        put16(u, 0, srcPort);
        put16(u, 2, dstPort);
        put16(u, 4, u.length);
        put16(u, 6, 0);
        System.arraycopy(data, 0, u, 8, data.length);
        int ck = checksum(u, 0, u.length, pseudoSum(srcIp, dstIp, PROTO_UDP, u.length));
        put16(u, 6, ck == 0 ? 0xFFFF : ck);
        return ipPacket(srcIp, dstIp, PROTO_UDP, u);
    }
}
