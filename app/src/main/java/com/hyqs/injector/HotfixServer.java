package com.hyqs.injector;

import android.content.Context;

import com.hyqs.injector.patch.JscPatcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the hotfix files for the intercepted host.
 *  - version.manifest / project.manifest : upstream copy with our version + patched jsc md5
 *  - src/project.jsc                     : patched build
 *  - everything else                     : straight pass-through
 */
public class HotfixServer {

    /** bump this whenever the bundled patch changes, so the game sees a newer version */
    public static final String PATCH_SERIAL = "57";

    /** 补丁要用到的图标，跟着热更清单一起下发到热更目录 */
    static final String[] EXTRA_ASSETS = {"repair_icon.png", "reinforce_icon.png", "huoxiang_water.png"};

    private final Context ctx;
    private volatile JscPatcher patcher;
    private final Map<String, byte[]> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 注入服务运行中拿到了新的在线补丁：换掉补丁处理器、清缓存、重置"已交付"，
     * 游戏下次检查更新（重开游戏）时就会重新走热更、拿到新补丁。
     */
    public void reloadPatch() {
        cache.clear();
        patcher = new JscPatcher(ctx);
        jscDelivered = false;
        LogBus.log("已切换到新补丁 " + com.hyqs.injector.update.Updater.patchLabel(ctx) + "，完全退出并重开游戏即可生效");
    }

    /** 本次要下发的图标：内置列表 ∪ 在线更新补丁带的 */
    private Iterable<String> extraAssets() {
        return com.hyqs.injector.update.Updater.assetNames(ctx, EXTRA_ASSETS);
    }

    /** 读随包文件：在线更新补丁带了同名文件就用它，否则读 APK assets */
    private byte[] asset(String name) {
        byte[] hit = cache.get("asset:" + name);
        if (hit != null) return hit;
        byte[] remote = com.hyqs.injector.update.Updater.activeAsset(ctx, name);
        if (remote != null) {
            cache.put("asset:" + name, remote);
            return remote;
        }
        try {
            java.io.InputStream in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] out = bos.toByteArray();
            cache.put("asset:" + name, out);
            return out;
        } catch (Throwable t) {
            LogBus.log("读取内置资源失败 " + name + " : " + t);
            return null;
        }
    }

    public HotfixServer(Context ctx) {
        this.ctx = ctx;
        this.patcher = new JscPatcher(ctx);
    }

    /** 补丁已交付后就不再强制更新，否则游戏每次更新成功都会 restartGame()，陷入重启死循环 */
    private volatile boolean jscDelivered = false;

    /** rewrites the update check so the game always runs the hotfix flow (event 1 = quiet update) */
    private byte[] forceUpdate(String base, String path) {
        if (jscDelivered) {
            byte[] real = fetch(base + path);
            if (real != null) {
                LogBus.log("补丁已交付，放行本次更新检查（游戏将正常进入登录）");
                return real;
            }
        }
        String gameVer = queryParam(path, "gameVer");
        String origVer = queryParam(path, "gameOrigVer");
        if (gameVer != null && gameVer.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) clientVer = gameVer;
        String ver = gameVer != null && gameVer.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") ? gameVer : origVer;

        byte[] up = fetch(base + path);
        if (up != null) {
            try {
                String text = new String(up, "UTF-8");
                String upVer = jsonValue(text, "ver");
                if (upVer != null && upVer.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) ver = upVer;
                LogBus.log("服务器原始更新应答: " + (text.length() > 300 ? text.substring(0, 300) : text));
            } catch (Throwable ignored) {
            }
        }

        // 打过补丁后游戏记录的版本会比服务器高一位，直接用会 404，这里挑一个服务器上真实存在的版本
        ver = pickExistingVersion(ver, gameVer, origVer);
        if (ver == null) ver = "1.9.0.070503";

        // 让游戏把热更包基地址指到本机 HTTP 服务，大文件走回环，不经过手写 TCP
        String local = "http://127.0.0.1:" + LocalHttpServer.PORT + "/hotfix/";
        String json = "{\"errorCode\":0,\"errorMsg\":\"ok\",\"data\":{\"event\":\"1\",\"ver\":\"" + ver
                + "\",\"url\":\"" + local + "\",\"desc\":\"\"}}";
        LogBus.log("强制热更: 目标版本 " + ver + "，下载地址改为 " + local);
        try {
            return json.getBytes("UTF-8");
        } catch (Exception e) {
            return json.getBytes();
        }
    }

    static String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) return kv.substring(e + 1);
        }
        return null;
    }

    public byte[] handle(String method, String path, String host, int port, Map<String, String> headers) {
        try {
            String lower = path.toLowerCase(Locale.US);
            String base = "http://" + host + (port == 80 ? "" : ":" + port);
            if (lower.contains("/hotfix/hotfix/checkupdate")) {
                return ok(forceUpdate(base, path), "application/json");
            }
            // res/raw-assets 下的清单副本是被父清单按 md5 校验的普通资源，必须原样透传
            boolean isAssetCopy = lower.contains("/res/raw-assets/");
            if (!isAssetCopy && (lower.endsWith("/version.manifest") || lower.endsWith("/project.manifest"))) {
                return ok(manifest(host, path), "application/json");
            }
            for (String icon : extraAssets()) {
                if (lower.endsWith("/" + icon)) {
                    byte[] body = asset(icon);
                    if (body == null) return notFound();
                    LogBus.log("交付图标 " + icon + " (" + body.length + " 字节)");
                    return ok(body, "image/png");
                }
            }
            if (lower.endsWith("/src/project.jsc")) {
                byte[] body = patchedJsc(host, path);
                if ("GET".equalsIgnoreCase(method)) {
                    jscDelivered = true;
                    LogBus.log("补丁 project.jsc 已交付游戏 (" + body.length + " 字节)");
                }
                return ok(body, "application/octet-stream");
            }
            byte[] up = fetch(base + path);
            if (up == null) return notFound();
            return ok(up, "application/octet-stream");
        } catch (Throwable t) {
            LogBus.log("处理请求失败 " + path + " : " + t);
            return notFound();
        }
    }

    private byte[] patchedJsc(String host, String path) throws Exception {
        String key = "jsc:" + path;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        byte[] up = fetch("http://" + host + path);
        if (up == null) throw new IllegalStateException("upstream jsc missing");
        byte[] out = patcher.patch(up);
        cache.put(key, out);
        return out;
    }

    private byte[] manifest(String host, String path) throws Exception {
        String key = "mf:" + path;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;

        byte[] up = fetch("http://" + host + path);
        if (up == null) throw new IllegalStateException("upstream manifest missing");
        String text = new String(up, "UTF-8");

        // jsc 的位置要按清单里的 packageUrl 算，不能用清单自身所在目录
        String jscPath;
        String pkgUrl = jsonValue(text, "packageUrl");
        if (pkgUrl != null && pkgUrl.startsWith("http")) {
            int slash = pkgUrl.indexOf('/', pkgUrl.indexOf("//") + 2);
            jscPath = (slash > 0 ? pkgUrl.substring(slash) : "/") ;
            if (!jscPath.endsWith("/")) jscPath += "/";
            jscPath += "src/project.jsc";
        } else {
            jscPath = path.substring(0, path.lastIndexOf('/') + 1) + "src/project.jsc";
        }
        byte[] jsc = patchedJsc(host, jscPath);
        String newMd5 = JscPatcher.md5(jsc);

        text = replaceAssetMd5(text, "src/project.jsc", newMd5);

        // 把补丁用到的图标加进资源表，游戏会把它们下载到热更目录
        for (String icon : extraAssets()) {
            byte[] data = asset(icon);
            if (data == null) continue;
            String entry = "\"" + icon + "\":{\"md5\":\"" + JscPatcher.md5(data) + "\"}";
            if (text.contains("\"" + icon + "\":{")) {
                text = replaceAssetMd5(text, icon, JscPatcher.md5(data));
            } else {
                int at = text.indexOf("\"assets\":{");
                if (at >= 0) {
                    int ins = at + "\"assets\":{".length();
                    text = text.substring(0, ins) + entry + "," + text.substring(ins);
                    LogBus.log("清单里加入图标 " + icon);
                }
            }
        }

        // 资源下载地址也指到本地，避免大文件再走手写 TCP
        String pkgBase = jsonValue(text, "packageUrl");
        if (pkgBase != null && pkgBase.startsWith("http") && !pkgBase.contains("127.0.0.1")) {
            int slash = pkgBase.indexOf('/', pkgBase.indexOf("//") + 2);
            String localPkg = "http://127.0.0.1:" + LocalHttpServer.PORT + (slash > 0 ? pkgBase.substring(slash) : "/");
            text = text.replace("\"packageUrl\":\"" + pkgBase + "\"", "\"packageUrl\":\"" + localPkg + "\"");
            String rm = jsonValue(text, "remoteManifestUrl");
            if (rm != null) text = text.replace("\"remoteManifestUrl\":\"" + rm + "\"",
                    "\"remoteManifestUrl\":\"" + localPkg + "project.manifest\"");
            String rv = jsonValue(text, "remoteVersionUrl");
            if (rv != null) text = text.replace("\"remoteVersionUrl\":\"" + rv + "\"",
                    "\"remoteVersionUrl\":\"" + localPkg + "version.manifest\"");
            LogBus.log("清单下载地址改为 " + localPkg);
        }

        String ver = jsonValue(text, "version");
        if (ver != null) {
            // 必须高于客户端当前版本，否则游戏判定"已是最新"，会不断重启
            String baseVer = ver;
            if (clientVer != null && compareVer(clientVer, baseVer) >= 0) baseVer = clientVer;
            String bumped = bumpVersion(baseVer);
            text = text.replace("\"version\":\"" + ver + "\"", "\"version\":\"" + bumped + "\"");
            LogBus.log("清单版本 " + ver + " -> " + bumped + "  (" + path + ")");
        }
        byte[] out = text.getBytes("UTF-8");
        cache.put(key, out);
        return out;
    }

    /** 依次尝试候选版本（含回退一位），返回服务器上真实存在的那个 */
    private String pickExistingVersion(String... candidates) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String c : candidates) {
            if (c == null || !c.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) continue;
            if (!list.contains(c)) list.add(c);
            String prev = stepVersion(c, -1);
            if (prev != null && !list.contains(prev)) list.add(prev);
        }
        for (String c : list) {
            if (fetch("http://xxxy.dayukeji.com/hotfix/2301/" + c + "/version.manifest") != null) {
                LogBus.log("采用服务器存在的版本: " + c);
                return c;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    static String stepVersion(String ver, int delta) {
        String[] p = ver.split("\\.");
        if (p.length != 4) return null;
        try {
            int width = p[3].length();
            long n = Long.parseLong(p[3]) + delta;
            if (n < 0) return null;
            String tail = String.valueOf(n);
            while (tail.length() < width) tail = "0" + tail;
            return p[0] + "." + p[1] + "." + p[2] + "." + tail;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 版本号必须保持 4 段数字：游戏原生层只认 a.b.c.d 这种格式，
     * 追加第 5 段（如 1.9.0.070503.56）会被判定为无效缓存，启动时整个热更目录会被清空。
     */
    /** 客户端在 checkupdate 里上报的当前版本，保证我们给出的版本一定更高 */
    private volatile String clientVer;

    static int compareVer(String a, String b) {
        if (a == null || b == null) return 0;
        String[] x = a.split("\\."), y = b.split("\\.");
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            try {
                long d = Long.parseLong(x[i]) - Long.parseLong(y[i]);
                if (d != 0) return d > 0 ? 1 : -1;
            } catch (Throwable t) {
                int d = x[i].compareTo(y[i]);
                if (d != 0) return d;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    static String bumpVersion(String ver) {
        String[] p = ver.split("\\.");
        if (p.length != 4) return ver + "." + PATCH_SERIAL;
        try {
            String last = p[3];
            int width = last.length();
            long n = Long.parseLong(last) + 1;
            String tail = String.valueOf(n);
            while (tail.length() < width) tail = "0" + tail;   // 保留前导零
            return p[0] + "." + p[1] + "." + p[2] + "." + tail;
        } catch (Throwable t) {
            return ver;
        }
    }

    static String jsonValue(String json, String field) {
        String pat = "\"" + field + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int a = i + pat.length();
        int b = json.indexOf('"', a);
        if (b < 0) return null;
        return json.substring(a, b);
    }

    static String replaceAssetMd5(String json, String asset, String md5) {
        String pat = "\"" + asset + "\":{\"md5\":\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            LogBus.log("清单里没有 " + asset + "，跳过 md5 替换");
            return json;
        }
        int a = i + pat.length();
        int b = json.indexOf('"', a);
        return json.substring(0, a) + md5 + json.substring(b);
    }

    private byte[] fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            if (code != 200) {
                LogBus.log("回源 " + code + " : " + url);
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] out = bos.toByteArray();
            LogBus.log("回源 200 " + out.length + " 字节 : " + url);
            return out;
        } catch (Throwable t) {
            LogBus.log("回源失败 " + url + " : " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] ok(byte[] body, String type) {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Accept-Ranges: none\r\n"
                + "Connection: close\r\n\r\n";
        return concat(head.getBytes(), body);
    }

    private static byte[] notFound() {
        byte[] body = "not found".getBytes();
        String head = "HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        return concat(head.getBytes(), body);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
