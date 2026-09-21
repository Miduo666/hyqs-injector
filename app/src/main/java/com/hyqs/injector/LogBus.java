package com.hyqs.injector;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogBus {

    public interface Listener {
        void onLog(String line);
    }

    private static final List<String> LINES = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener;
    private static java.io.File logFile;

    /** 日志同时落盘，不受 logcat 缓冲冲刷影响 */
    public static synchronized void attachFile(java.io.File dir) {
        try {
            logFile = new java.io.File(dir, "inject.log");
            if (logFile.exists() && logFile.length() > 512 * 1024) logFile.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void appendFile(String line) {
        java.io.File f;
        synchronized (LogBus.class) {
            f = logFile;
        }
        if (f == null) return;
        try {
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(LINES);
    }

    public static void log(String line) {
        final String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        Log.i("HyqsInjector", line);
        appendFile(stamped);
        final Listener l;
        synchronized (LogBus.class) {
            LINES.add(stamped);
            while (LINES.size() > 400) LINES.remove(0);
            l = listener;
        }
        if (l != null) MAIN.post(new Runnable() {
            @Override
            public void run() {
                l.onLog(stamped);
            }
        });
    }
}
