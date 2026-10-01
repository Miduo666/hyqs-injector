package com.hyqs.injector.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Base64;

import com.hyqs.injector.LogBus;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 在线更新（qimeng.app/hyqs/*，Cloudflare KV，发布用 tools/publish.mjs）。
 *
 * manifest.json = {"payload": "<JSON 字符串>", "sig": "<base64 DER ECDSA-SHA256>"}，公钥在 assets/update_pubkey.b64。
 * 验签不过、不是 JSON（站点对未知路径会兜底返回首页 HTML）、超时、sha256 对不上 —— 一律当作没有更新，用 APK 内置版。
 *
 * 补丁：payload.patch.serial 比 内置(assets/patch_serial.txt) 和 已缓存 都新、且本 APK versionCode ≥ minApk 才下载，
 * 存 filesDir/hotpatch/{patch.js, assets/*, meta.json}；meta.json 最后写，写完才算生效。
 * APK：payload.apk.versionCode 比自己大就提示，下载到 cacheDir 校验 sha256 后用 PackageInstaller 安装（系统会弹一次确认）。
 */
public final class Updater {

    public static final String BASE = "https://qimeng.app/hyqs/";
    public static final String ACTION_INSTALL_STATUS = "com.hyqs.injector.INSTALL_STATUS";

    private Updater() {
    }

    // ---------------------------------------------------------------- manifest

    /** 拉 manifest 并验签，返回 payload；任何问题返回 null（原因写日志） */
    public static JSONObject fetchManifest(Context ctx, int connectMs, int readMs) {
        try {
            byte[] body = get(BASE + "manifest.json?t=" + System.currentTimeMillis(), connectMs, readMs, 64 * 1024);
            if (body == null) return null;
            JSONObject m = new JSONObject(new String(body, "UTF-8"));
            String payload = m.getString("payload");
            byte[] sig = Base64.decode(m.getString("sig"), Base64.DEFAULT);
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(publicKey(ctx));
            v.update(payload.getBytes("UTF-8"));
            if (!v.verify(sig)) {
                LogBus.log("在线更新：manifest 签名不对，忽略");
                return null;
            }
            return new JSONObject(payload);
        } catch (Throwable t) {
            LogBus.log("在线更新：检查失败（" + t.getClass().getSimpleName() + "），使用内置版本");
            return null;
        }
    }

    private static PublicKey publicKey(Context ctx) throws Exception {
        String b64 = new String(readAll(ctx.getAssets().open("update_pubkey.b64")), "UTF-8").trim();
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)));
    }

    // ---------------------------------------------------------------- 补丁

    public static int builtinSerial(Context ctx) {
        try {
            return Integer.parseInt(new String(readAll(ctx.getAssets().open("patch_serial.txt")), "UTF-8").trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static File hotDir(Context ctx) {
        return new File(ctx.getFilesDir(), "hotpatch");
    }

    private static JSONObject meta(Context ctx) {
        try {
            File f = new File(hotDir(ctx), "meta.json");
            return f.exists() ? new JSONObject(new String(readAll(new FileInputStream(f)), "UTF-8")) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已缓存且可用的远程补丁序号；比内置旧/一样、或者要求更高版本的 APK 时返回 0 */
    public static int activeSerial(Context ctx) {
        JSONObject m = meta(ctx);
        if (m == null) return 0;
        int serial = m.optInt("serial");
        if (serial <= builtinSerial(ctx) || m.optInt("minApk", 1) > myVersionCode(ctx)) return 0;
        return new File(hotDir(ctx), "patch.js").exists() ? serial : 0;
    }

    /** 当前要用的补丁序号（远程缓存优先，否则内置），给界面和日志用 */
    public static String patchLabel(Context ctx) {
        int a = activeSerial(ctx);
        return a > 0 ? "远程 v" + a : "内置 v" + builtinSerial(ctx);
    }

    /** 补丁正文：有可用的远程缓存就读它，否则 null（调用方读 assets） */
    public static byte[] activePatch(Context ctx) {
        if (activeSerial(ctx) <= 0) return null;
        try {
            return readAll(new FileInputStream(new File(hotDir(ctx), "patch.js")));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 图标：远程补丁生效时优先用它带的那份 */
    public static byte[] activeAsset(Context ctx, String name) {
        if (activeSerial(ctx) <= 0) return null;
        File f = new File(new File(hotDir(ctx), "assets"), name);
        try {
            return f.exists() ? readAll(new FileInputStream(f)) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 要随热更下发的图标名：APK 内置列表 ∪ 远程补丁带的 */
    public static Set<String> assetNames(Context ctx, String[] builtin) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : builtin) out.add(s);
        JSONObject m = meta(ctx);
        if (m != null && activeSerial(ctx) > 0) {
            JSONObject a = m.optJSONObject("assets");
            if (a != null) for (Iterator<String> it = a.keys(); it.hasNext(); ) out.add(it.next());
        }
        return out;
    }

    /** payload 里的补丁比本地新就下载；返回是否有更新落地 */
    public static boolean syncPatch(Context ctx, JSONObject payload) {
        JSONObject p = payload != null ? payload.optJSONObject("patch") : null;
        if (p == null) return false;
        int serial = p.optInt("serial");
        int have = Math.max(builtinSerial(ctx), activeSerial(ctx));
        if (serial <= have) return false;
        if (p.optInt("minApk", 1) > myVersionCode(ctx)) {
            LogBus.log("在线更新：补丁 v" + serial + " 需要更新注入器后才能用");
            return false;
        }
        try {
            File dir = hotDir(ctx), adir = new File(dir, "assets");
            adir.mkdirs();
            byte[] js = get(BASE + p.getString("file"), 8000, 30000, 16 * 1024 * 1024);
            if (js == null || !sha256(js).equals(p.getString("sha256"))) {
                LogBus.log("在线更新：补丁 v" + serial + " 下载失败或校验不符，继续用 " + patchLabel(ctx));
                return false;
            }
            JSONObject assets = p.optJSONObject("assets");
            if (assets != null) {
                for (Iterator<String> it = assets.keys(); it.hasNext(); ) {
                    String name = it.next();
                    JSONObject a = assets.getJSONObject(name);
                    File f = new File(adir, name);
                    if (f.exists() && sha256(readAll(new FileInputStream(f))).equals(a.getString("sha256"))) continue;
                    byte[] data = get(BASE + a.getString("file"), 8000, 30000, 8 * 1024 * 1024);
                    if (data == null || !sha256(data).equals(a.getString("sha256"))) {
                        LogBus.log("在线更新：图标 " + name + " 下载失败或校验不符，本次不更新补丁");
                        return false;
                    }
                    write(f, data);
                }
            }
            write(new File(dir, "patch.js"), js);
            JSONObject m = new JSONObject();
            m.put("serial", serial);
            m.put("minApk", p.optInt("minApk", 1));
            m.put("assets", assets != null ? assets : new JSONObject());
            write(new File(dir, "meta.json"), m.toString().getBytes("UTF-8"));   // 最后写，写完才生效
            LogBus.log("在线更新：已下载补丁 v" + serial + "（" + js.length / 1024 + " KB）");
            return true;
        } catch (Throwable t) {
            LogBus.log("在线更新：保存补丁失败 " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- APK

    public static int myVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String myVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** payload 里有比自己新的注入器就返回那一段，否则 null */
    public static JSONObject newerApk(Context ctx, JSONObject payload) {
        JSONObject a = payload != null ? payload.optJSONObject("apk") : null;
        return a != null && a.optInt("versionCode") > myVersionCode(ctx) ? a : null;
    }

    /** 下载并校验新 APK（在后台线程调用），成功返回文件 */
    public static File downloadApk(Context ctx, JSONObject apk) {
        try {
            byte[] data = get(BASE + apk.getString("file"), 8000, 60000, 64 * 1024 * 1024);
            if (data == null || !sha256(data).equals(apk.getString("sha256"))) {
                LogBus.log("在线更新：新版注入器下载失败或校验不符");
                return null;
            }
            File f = new File(ctx.getCacheDir(), "update-" + apk.optInt("versionCode") + ".apk");
            write(f, data);
            return f;
        } catch (Throwable t) {
            LogBus.log("在线更新：新版注入器下载失败 " + t);
            return null;
        }
    }

    /**
     * 调起系统安装器：优先 ACTION_VIEW + content://（小米/澎湃等国内 ROM 都认），没有能处理的安装器才退回 PackageInstaller 会话。
     * （实测澎湃 OS 的安装器收到会话确认会 0.2 秒内直接销毁会话："User rejected permissions"）
     */
    public static void install(Activity act, File apk) throws Exception {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uriFor(apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (view.resolveActivity(act.getPackageManager()) != null) {
            act.startActivity(view);
            return;
        }
        installBySession(act, apk);
    }

    /** PackageInstaller 会话安装；结果（含"需要用户确认"）通过 PendingIntent 回到 activity 的 onNewIntent */
    private static void installBySession(Activity act, File apk) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setSize(apk.length());
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("update.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            s.fsync(out);
            out.close();
            Intent it = new Intent(act, act.getClass()).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            s.commit(PendingIntent.getActivity(act, 0, it, flags).getIntentSender());
        } finally {
            s.close();
        }
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] get(String url, int connectMs, int readMs, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "HyqsInjector");
            int code = c.getResponseCode();
            if (code != 200) {
                LogBus.log("在线更新：" + code + " " + url.replace(BASE, ""));
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) throw new java.io.IOException("too large");
            }
            in.close();
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (f.exists() && !f.delete()) throw new java.io.IOException("cannot replace " + f);
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot rename " + tmp);
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
