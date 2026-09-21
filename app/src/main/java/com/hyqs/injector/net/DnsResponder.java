package com.hyqs.injector.net;

import com.hyqs.injector.LogBus;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Answers DNS for the VPN'd app.
 *  - hijacked hosts  -> our fake IP
 *  - other A queries -> resolved with the system resolver (this app is outside the VPN)
 *  - AAAA and others -> empty NOERROR, so the game sticks to IPv4 and cannot slip past us
 */
public class DnsResponder {

    private final int fakeIp;
    private final String[] hijack;

    public DnsResponder(int fakeIp, String[] hijack) {
        this.fakeIp = fakeIp;
        this.hijack = hijack;
    }

    public static String readName(byte[] q, int off) {
        StringBuilder sb = new StringBuilder();
        int i = off;
        while (i < q.length) {
            int len = q[i] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) break;
            i++;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(q, i, Math.min(len, q.length - i)));
            i += len;
        }
        return sb.toString();
    }

    private static int nameEnd(byte[] q, int off) {
        int i = off;
        while (i < q.length) {
            int len = q[i] & 0xFF;
            if (len == 0) return i + 1;
            i += len + 1;
        }
        return i;
    }

    public boolean isHijacked(String name) {
        for (String h : hijack) if (name.equalsIgnoreCase(h)) return true;
        return false;
    }

    public byte[] answer(byte[] query) {
        try {
            if (query.length < 13) return null;
            String name = readName(query, 12);
            int qEnd = nameEnd(query, 12);
            int qtype = Packets.u16(query, qEnd);

            if (qtype != 1) {                       // AAAA 等：回空答案，逼游戏走 IPv4
                return build(query, qEnd, new int[0]);
            }
            if (isHijacked(name)) {
                LogBus.log("DNS 劫持 " + name + " -> " + Packets.ipToString(fakeIp));
                return build(query, qEnd, new int[]{fakeIp});
            }

            int[] ips;
            try {
                InetAddress[] all = InetAddress.getAllByName(name);
                int n = 0;
                int[] tmp = new int[all.length];
                for (InetAddress a : all) {
                    if (a instanceof Inet4Address) {
                        byte[] b = a.getAddress();
                        tmp[n++] = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                    }
                }
                ips = new int[n];
                System.arraycopy(tmp, 0, ips, 0, n);
            } catch (Throwable t) {
                LogBus.log("DNS 解析失败 " + name + " : " + t);
                ips = new int[0];
            }
            return build(query, qEnd, ips);
        } catch (Throwable t) {
            LogBus.log("DNS 处理失败: " + t);
            return null;
        }
    }

    private static byte[] build(byte[] query, int qEnd, int[] ips) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] head = new byte[12];
        System.arraycopy(query, 0, head, 0, 12);
        head[2] = (byte) 0x81;
        head[3] = (byte) 0x80;
        Packets.put16(head, 4, 1);
        Packets.put16(head, 6, ips.length);
        Packets.put16(head, 8, 0);
        Packets.put16(head, 10, 0);
        bos.write(head);
        bos.write(query, 12, qEnd + 4 - 12);
        for (int ip : ips) {
            byte[] ans = new byte[16];
            ans[0] = (byte) 0xC0;
            ans[1] = 0x0C;
            Packets.put16(ans, 2, 1);
            Packets.put16(ans, 4, 1);
            Packets.put32(ans, 6, 60);
            Packets.put16(ans, 10, 4);
            Packets.put32(ans, 12, ip);
            bos.write(ans);
        }
        return bos.toByteArray();
    }
}
