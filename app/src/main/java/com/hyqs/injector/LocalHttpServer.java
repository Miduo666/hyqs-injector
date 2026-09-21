package com.hyqs.injector;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain loopback HTTP server. The forged checkupdate response points the game's packageUrl here,
 * so every manifest/asset download goes over 127.0.0.1 instead of our hand rolled TCP stack.
 */
public class LocalHttpServer {

    public static final int PORT = 8089;
    /** upstream host the paths belong to */
    private static final String UPSTREAM = "xxxy.dayukeji.com";

    private final HotfixServer server;
    private ServerSocket socket;
    private volatile boolean running;

    public LocalHttpServer(HotfixServer server) {
        this.server = server;
    }

    public boolean start() {
        try {
            socket = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
            running = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    accept();
                }
            }, "http-accept").start();
            LogBus.log("本地 HTTP 服务已启动: 127.0.0.1:" + PORT);
            return true;
        } catch (Throwable t) {
            LogBus.log("本地 HTTP 服务启动失败: " + t);
            return false;
        }
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
    }

    private void accept() {
        while (running) {
            final Socket s;
            try {
                s = socket.accept();
            } catch (Throwable t) {
                if (running) LogBus.log("accept 失败: " + t);
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(s);
                }
            }, "http-conn").start();
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line = r.readLine();
            if (line == null) {
                s.close();
                return;
            }
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = r.readLine()) != null && h.length() > 0) {
                int c = h.indexOf(':');
                if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
            }

            LogBus.log("本地请求 " + method + " " + path);
            byte[] resp = server.handle(method, path, UPSTREAM, 80, headers);

            OutputStream out = s.getOutputStream();
            out.write(resp);
            out.flush();
            s.close();
        } catch (Throwable t) {
            LogBus.log("本地请求处理失败: " + t);
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
