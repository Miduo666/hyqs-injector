package com.hyqs.injector.patch;

/** XXTEA (btea) exactly as cocos2d-x jsb uses it: little-endian words, tail word = plain length. */
public final class XXTEA {

    private static final int DELTA = 0x9E3779B9;

    public static final byte[] KEY = "ebf83d12-bc75-4b".getBytes();

    private XXTEA() {
    }

    private static int mx(int sum, int y, int z, int p, int e, int[] key) {
        return (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4))) ^ ((sum ^ y) + (key[(p & 3) ^ e] ^ z));
    }

    private static int[] toWords(byte[] data, boolean appendLength) {
        int pad = (4 - (data.length % 4)) % 4;
        int n = (data.length + pad) / 4;
        int[] v = new int[n + (appendLength ? 1 : 0)];
        for (int i = 0; i < data.length; i++) {
            v[i >>> 2] |= (data[i] & 0xFF) << ((i & 3) << 3);
        }
        if (appendLength) {
            v[n] = data.length;
        }
        return v;
    }

    private static byte[] toBytes(int[] v) {
        byte[] out = new byte[v.length * 4];
        for (int i = 0; i < v.length; i++) {
            out[i * 4] = (byte) (v[i]);
            out[i * 4 + 1] = (byte) (v[i] >>> 8);
            out[i * 4 + 2] = (byte) (v[i] >>> 16);
            out[i * 4 + 3] = (byte) (v[i] >>> 24);
        }
        return out;
    }

    private static int[] keyWords(byte[] keyBytes) {
        int[] k = toWords(keyBytes, false);
        int[] out = new int[4];
        System.arraycopy(k, 0, out, 0, Math.min(4, k.length));
        return out;
    }

    public static byte[] encrypt(byte[] data, byte[] keyBytes) {
        if (data.length == 0) return new byte[0];
        int[] v = toWords(data, true);
        int[] key = keyWords(keyBytes);
        int n = v.length;
        if (n < 2) return toBytes(v);
        int rounds = 6 + 52 / n;
        int sum = 0;
        int z = v[n - 1];
        int y;
        while (rounds-- > 0) {
            sum += DELTA;
            int e = (sum >>> 2) & 3;
            for (int p = 0; p < n - 1; p++) {
                y = v[p + 1];
                z = v[p] += mx(sum, y, z, p, e, key);
            }
            y = v[0];
            z = v[n - 1] += mx(sum, y, z, n - 1, e, key);
        }
        return toBytes(v);
    }

    public static byte[] decrypt(byte[] data, byte[] keyBytes) {
        if (data.length == 0) return new byte[0];
        int[] v = toWords(data, false);
        int[] key = keyWords(keyBytes);
        int n = v.length;
        if (n < 2) return toBytes(v);
        int rounds = 6 + 52 / n;
        int sum = rounds * DELTA;
        int y = v[0];
        int z;
        while (sum != 0) {
            int e = (sum >>> 2) & 3;
            for (int p = n - 1; p > 0; p--) {
                z = v[p - 1];
                y = v[p] -= mx(sum, y, z, p, e, key);
            }
            z = v[n - 1];
            y = v[0] -= mx(sum, y, z, 0, e, key);
            sum -= DELTA;
        }
        byte[] plain = toBytes(v);
        long len = v[n - 1] & 0xFFFFFFFFL;
        if (len <= plain.length) {
            byte[] out = new byte[(int) len];
            System.arraycopy(plain, 0, out, 0, (int) len);
            return out;
        }
        return plain;
    }
}
