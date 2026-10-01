package com.hyqs.injector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.hyqs.injector.net.DnsResponder;
import com.hyqs.injector.net.Packets;
import com.hyqs.injector.net.TcpConn;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class InjectVpnService extends VpnService {

    public static final String EXTRA_PKG = "pkg";
    public static final String ACTION_STOP = "com.hyqs.injector.STOP";

    private static final String TUN_ADDR = "10.66.0.1";
    private static final String FAKE_ADDR = "10.66.0.2";
    private static final String DNS_ADDR = "10.66.0.53";
    private static final String[] HIJACK = {"xxxy.dayukeji.com", "entrance1.xxxy.dayukeji.com"};

    public static volatile boolean running = false;

    private ParcelFileDescriptor tun;
    private Thread worker, ticker;
    private FileOutputStream tunOut;
    private final Map<String, TcpConn> conns = new HashMap<>();
    private final java.util.Set<Integer> realIps = new java.util.HashSet<>();
    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    private HotfixServer server;
    private LocalHttpServer local;
    private DnsResponder dns;
    private int fakeIp, dnsIp;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }
        String pkg = intent != null ? intent.getStringExtra(EXTRA_PKG) : null;
        LogBus.attachFile(getFilesDir());
        LogBus.log("onStartCommand 目标应用 = " + pkg);
        try {
            startForegroundNotice();
        } catch (Throwable t) {
            LogBus.log("前台通知启动失败(不影响注入): " + t);
        }
        final String target = pkg;
        new Thread(new Runnable() {          // 解析域名要联网，不能在主线程
            @Override
            public void run() {
                try {
                    start(target);
                } catch (Throwable t) {
                    LogBus.log("注入服务启动异常: " + t);
                }
            }
        }, "vpn-start").start();
        return START_STICKY;
    }

    private void startForegroundNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String chan = "inject";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(chan, "注入服务", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, chan) : new Notification.Builder(this);
        Notification n = b.setContentTitle("荒野日记注入器")
                .setContentText("正在等待游戏检查更新")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .build();
        startForeground(1, n);
    }

    private void start(String pkg) {
        try {
            if (running) {
                // 运行中再次注入（通常是在线补丁更新了）：不重建 VPN，只让补丁服务换上新补丁
                if (server != null) server.reloadPatch();
                else LogBus.log("注入服务已在运行，忽略重复启动");
                return;
            }
            fakeIp = Packets.parseIp(FAKE_ADDR);
            dnsIp = Packets.parseIp(DNS_ADDR);
            server = new HotfixServer(this);
            if (local != null) local.stop();
            local = new LocalHttpServer(server);
            local.start();
            dns = new DnsResponder(fakeIp, HIJACK);

            // 真实 IP 也一并路由：万一 DNS 被系统缓存或别的途径绕过，照样拦得到
            realIps.clear();
            for (String h : HIJACK) {
                try {
                    for (java.net.InetAddress a : java.net.InetAddress.getAllByName(h)) {
                        if (a instanceof java.net.Inet4Address) {
                            byte[] x = a.getAddress();
                            int ip = ((x[0] & 0xFF) << 24) | ((x[1] & 0xFF) << 16) | ((x[2] & 0xFF) << 8) | (x[3] & 0xFF);
                            realIps.add(ip);
                            LogBus.log("目标真实 IP " + h + " = " + Packets.ipToString(ip));
                        }
                    }
                } catch (Throwable t) {
                    LogBus.log("解析 " + h + " 失败: " + t);
                }
            }

            Builder b = new Builder();
            b.setSession("荒野日记注入");
            b.setMtu(1500);
            b.addAddress(TUN_ADDR, 24);
            b.addDnsServer(DNS_ADDR);
            b.addRoute(FAKE_ADDR, 32);
            b.addRoute(DNS_ADDR, 32);
            for (Integer ip : realIps) {
                try {
                    b.addRoute(Packets.ipToString(ip), 32);
                } catch (Throwable ignored) {
                }
            }
            if (pkg != null) {
                try {
                    b.addAllowedApplication(pkg);
                    LogBus.log("只捕获应用: " + pkg);
                } catch (Exception e) {
                    LogBus.log("添加目标应用失败: " + e);
                }
            }
            tun = b.establish();
            if (tun == null) {
                LogBus.log("建立 VPN 失败");
                stopSelf();
                return;
            }
            tunOut = new FileOutputStream(tun.getFileDescriptor());
            running = true;
            LogBus.log("VPN 已启动，等待游戏请求热更新…");

            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "tun");
            worker.start();

            ticker = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        synchronized (conns) {
                            Iterator<Map.Entry<String, TcpConn>> it = conns.entrySet().iterator();
                            while (it.hasNext()) {
                                TcpConn c = it.next().getValue();
                                try {
                                    c.pump();
                                } catch (Throwable ignored) {
                                }
                                if (c.isClosed()) it.remove();
                            }
                        }
                    }
                }
            }, "tick");
            ticker.start();
        } catch (Throwable t) {
            LogBus.log("启动失败: " + t);
            stopSelf();
        }
    }

    private void writeTun(byte[] p) {
        try {
            synchronized (InjectVpnService.this) {
                tunOut.write(p);
            }
        } catch (Throwable t) {
            LogBus.log("写回失败: " + t);
        }
    }

    private void loop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                byte[] p = new byte[n];
                System.arraycopy(buf, 0, p, 0, n);
                handle(p);
            }
        } catch (Throwable t) {
            if (running) LogBus.log("读取中断: " + t);
        }
    }

    private int seen = 0;

    private void handle(byte[] p) {
        if (Packets.ipVersion(p) != 4) {
            if (seen++ < 5) LogBus.log("收到非 IPv4 包, 版本=" + Packets.ipVersion(p));
            return;
        }
        int hdr = Packets.ipHeaderLen(p);
        int proto = Packets.ipProto(p);
        int src = Packets.srcIp(p), dst = Packets.dstIp(p);
        if (seen++ < 5) {
            LogBus.log("收到数据包 proto=" + proto + " " + Packets.ipToString(src) + " -> " + Packets.ipToString(dst));
        }

        if (proto == Packets.PROTO_UDP && dst == dnsIp) {
            final int sport = Packets.u16(p, hdr);
            int dport = Packets.u16(p, hdr + 2);
            if (dport != 53) return;
            int ulen = Packets.u16(p, hdr + 4);
            final byte[] q = new byte[ulen - 8];
            System.arraycopy(p, hdr + 8, q, 0, q.length);
            final int fsrc = src, fdst = dst;
            pool.execute(new Runnable() {          // 解析可能阻塞，别卡住收包线程
                @Override
                public void run() {
                    byte[] a = dns.answer(q);
                    if (a != null) writeTun(Packets.udp(fdst, 53, fsrc, sport, a));
                }
            });
            return;
        }

        if (proto == Packets.PROTO_TCP && (dst == fakeIp || realIps.contains(dst))) {
            int sport = Packets.u16(p, hdr);
            int dport = Packets.u16(p, hdr + 2);
            int tcpLen = Packets.totalLen(p) - hdr;
            String key = src + ":" + sport;
            TcpConn c;
            synchronized (conns) {
                c = conns.get(key);
                if (c == null) {
                    if ((Packets.u8(p, hdr + 13) & Packets.SYN) == 0) return;
                    LogBus.log("新连接 " + Packets.ipToString(src) + ":" + sport + " -> " + Packets.ipToString(dst) + ":" + dport);
                    c = new TcpConn(src, sport, dst, dport, new TcpConn.Output() {
                        @Override
                        public void write(byte[] ipPacket) {
                            writeTun(ipPacket);
                        }
                    }, new TcpConn.RequestHandler() {
                        @Override
                        public byte[] respond(byte[] request) {
                            return serve(request);
                        }
                    });
                    conns.put(key, c);
                }
            }
            c.onPacket(p, hdr, tcpLen);
            if (c.isClosed()) {
                synchronized (conns) {
                    conns.remove(key);
                }
            }
        }
    }

    private byte[] serve(byte[] request) {
        String text = new String(request);
        String[] lines = text.split("\r\n");
        String method = "GET", path = "/";
        String host = HIJACK[0];
        if (lines.length > 0) {
            String[] parts = lines[0].split(" ");
            if (parts.length >= 2) {
                method = parts[0];
                path = parts[1];
            }
        }
        java.util.Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                String k = lines[i].substring(0, c).trim().toLowerCase();
                String v = lines[i].substring(c + 1).trim();
                headers.put(k, v);
                if ("host".equals(k)) {
                    host = v.contains(":") ? v.substring(0, v.indexOf(':')) : v;
                }
            }
        }
        if (path.startsWith("http://")) {
            int slash = path.indexOf('/', 7);
            if (slash > 0) path = path.substring(slash);
        }
        String hostHdr = headers.get("host");
        int port = 80;
        if (hostHdr != null && hostHdr.contains(":")) {
            try {
                port = Integer.parseInt(hostHdr.substring(hostHdr.indexOf(':') + 1).trim());
            } catch (Throwable ignored) {
            }
        }
        LogBus.log("请求 " + method + " " + host + ":" + port + path);
        return server.handle(method, path, host, port, headers);
    }

    private void stopEverything() {
        running = false;
        try {
            if (local != null) local.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (worker != null) worker.interrupt();
        } catch (Throwable ignored) {
        }
        try {
            if (ticker != null) ticker.interrupt();
        } catch (Throwable ignored) {
        }
        try {
            if (tun != null) tun.close();
        } catch (Throwable ignored) {
        }
        tun = null;
        LogBus.log("VPN 已停止");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        LogBus.log("VPN 被系统收回");
        stopEverything();
        super.onRevoke();
    }
}
