package com.hyqs.injector.patch;

import android.content.Context;

import com.hyqs.injector.LogBus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the patched project.jsc:
 *   upstream jsc -> XXTEA decrypt -> unzip encrypt.js -> read its GAME_VER
 *   our bundled patched encrypt.js -> same GAME_VER -> zip -> XXTEA encrypt
 */
public class JscPatcher {

    private static final String ENTRY = "encrypt.js";

    private final Context ctx;
    private String patchedJs;

    public JscPatcher(Context ctx) {
        this.ctx = ctx;
    }

    private String loadPatchedJs() throws Exception {
        if (patchedJs == null) {
            // 在线更新下载过更新的补丁就用它（Updater 已校验过 sha256 和签名），否则用 APK 内置的
            byte[] remote = com.hyqs.injector.update.Updater.activePatch(ctx);
            if (remote != null) {
                patchedJs = new String(remote, "UTF-8");
                LogBus.log("使用补丁: " + com.hyqs.injector.update.Updater.patchLabel(ctx));
                return patchedJs;
            }
            LogBus.log("使用补丁: " + com.hyqs.injector.update.Updater.patchLabel(ctx));
            InputStream in = ctx.getAssets().open("patched_encrypt.js");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2 * 1024 * 1024);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            patchedJs = new String(bos.toByteArray(), "UTF-8");
        }
        return patchedJs;
    }

    public static String extractGameVer(String js) {
        int i = js.indexOf("GAME_VER:");
        if (i < 0) return null;
        int a = js.indexOf('"', i);
        int b = js.indexOf('"', a + 1);
        if (a < 0 || b < 0) return null;
        return js.substring(a + 1, b);
    }

    private static String replaceGameVer(String js, String ver) {
        int i = js.indexOf("GAME_VER:");
        if (i < 0) return js;
        int a = js.indexOf('"', i);
        int b = js.indexOf('"', a + 1);
        if (a < 0 || b < 0) return js;
        return js.substring(0, a + 1) + ver + js.substring(b);
    }

    public static byte[] unzipEntry(byte[] zipBytes) throws Exception {
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if (ENTRY.equals(e.getName())) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(2 * 1024 * 1024);
                byte[] buf = new byte[65536];
                int n;
                while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                zis.close();
                return bos.toByteArray();
            }
        }
        zis.close();
        return null;
    }

    private static byte[] zipEntry(byte[] js) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(js.length / 2);
        ZipOutputStream zos = new ZipOutputStream(bos);
        ZipEntry e = new ZipEntry(ENTRY);
        zos.putNextEntry(e);
        zos.write(js);
        zos.closeEntry();
        zos.close();
        return bos.toByteArray();
    }

    /** @return patched jsc bytes, ready to serve */
    public byte[] patch(byte[] upstreamJsc) throws Exception {
        String ver = null;
        try {
            byte[] zip = XXTEA.decrypt(upstreamJsc, XXTEA.KEY);
            if (zip.length > 4 && zip[0] == 'P' && zip[1] == 'K') {
                byte[] js = unzipEntry(zip);
                if (js != null) {
                    ver = extractGameVer(new String(js, "UTF-8"));
                    LogBus.log("原版 GAME_VER = " + ver);
                }
            } else {
                LogBus.log("警告: 原版 jsc 解密后不是 ZIP，密钥可能不匹配");
            }
        } catch (Throwable t) {
            LogBus.log("读取原版 jsc 失败: " + t);
        }

        String js = Options.prelude(ctx) + loadPatchedJs();
        String mine = extractGameVer(js);
        if (ver != null && !ver.equals(mine)) {
            js = replaceGameVer(js, ver);
            LogBus.log("补丁 GAME_VER " + mine + " -> " + ver);
        }
        byte[] zip = zipEntry(js.getBytes("UTF-8"));
        byte[] out = XXTEA.encrypt(zip, XXTEA.KEY);

        // self check
        byte[] back = XXTEA.decrypt(out, XXTEA.KEY);
        if (back.length != zip.length) throw new IllegalStateException("xxtea round-trip failed");
        LogBus.log("补丁 jsc 生成完毕: " + out.length + " 字节, md5=" + md5(out));
        return out;
    }

    public static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
