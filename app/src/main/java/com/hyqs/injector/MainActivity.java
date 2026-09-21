package com.hyqs.injector;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements LogBus.Listener {

    private static final int REQ_VPN = 1001;

    private final List<String> pkgs = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private Spinner spinner;
    private TextView logView, status;
    private ScrollView scroll;
    private String selected;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("荒野日记 热更新注入器");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        spinner = new Spinner(this);
        root.addView(spinner);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button start = new Button(this);
        start.setText("一键注入");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInject();
            }
        });
        row.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, InjectVpnService.class);
                i.setAction(InjectVpnService.ACTION_STOP);
                startService(i);
                refreshStatus();
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        TextView optTitle = new TextView(this);
        optTitle.setText("注入内容（每次注入以当前勾选为准，会整体覆盖上一次）");
        optTitle.setTextSize(13);
        optTitle.setPadding(0, pad / 2, 0, 0);
        root.addView(optTitle);

        LinearLayout optBox = new LinearLayout(this);
        optBox.setOrientation(LinearLayout.VERTICAL);
        for (final String[] it : com.hyqs.injector.patch.Options.ITEMS) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(it[1] + "  —  " + it[2]);
            cb.setTextSize(12);
            cb.setChecked(com.hyqs.injector.patch.Options.get(this, it[0], "1".equals(it[3])));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    com.hyqs.injector.patch.Options.set(MainActivity.this, it[0], v);
                }
            });
            optBox.addView(cb);
        }
        ScrollView optScroll = new ScrollView(this);
        optScroll.addView(optBox);
        root.addView(optScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f));

        TextView hint = new TextView(this);
        hint.setText("用法：先点【一键注入】并允许 VPN，再启动游戏。游戏检查更新时会自动装上补丁，"
                + "看到日志出现 project.jsc 即成功。注入完成后可以点【停止】。");
        hint.setTextSize(12);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(hint);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.DKGRAY);
        logView.setMovementMethod(new ScrollingMovementMethod());
        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        loadPackages();
        LogBus.setListener(this);

        // 调试用：adb start MainActivity --es auto <包名> 可直接开始注入
        String auto = getIntent() != null ? getIntent().getStringExtra("auto") : null;
        if (auto != null) {
            if (pkgs.contains(auto)) selected = auto;
            startInject();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : LogBus.snapshot()) sb.append(l).append('\n');
        logView.setText(sb.toString());
        refreshStatus();
    }

    private static final String[] CANDIDATES = {
            "com.dygame.hyqs.mi", "com.dygame.hyqs.aligames", "com.dygame.hyqs",
            "com.dygame.hyqs.huawei", "com.dygame.hyqs.oppo", "com.dygame.hyqs.vivo",
            "com.dygame.hyqs.yyb", "com.dygame.hyqs.qq", "com.dygame.hyqs.bili",
            "com.dygame.hyqs.m4399", "com.dygame.hyqs.baidu", "com.dygame.hyqs.lenovo",
            "com.dygame.hyqs.meizu", "com.dygame.hyqs.uc", "com.dygame.hyqs.taptap",
            "com.dygame.hyqs.nearme.gamecenter"
    };

    private void addPkg(PackageManager pm, PackageInfo pi) {
        if (pi == null || pi.packageName == null || pkgs.contains(pi.packageName)) return;
        pkgs.add(pi.packageName);
        String label = pi.packageName;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pi.packageName, 0);
            label = pm.getApplicationLabel(ai) + " (" + pi.packageName + ")  v" + pi.versionName;
        } catch (Throwable ignored) {
        }
        labels.add(label);
    }

    private void loadPackages() {
        PackageManager pm = getPackageManager();
        // 1) 先按候选包名逐个查，依赖 <queries> 声明，不需要任何权限
        for (String cand : CANDIDATES) {
            try {
                addPkg(pm, pm.getPackageInfo(cand, 0));
            } catch (Throwable ignored) {
            }
        }
        // 2) 一个都没找到才退回全量枚举（可能触发"获取应用列表"权限弹窗）
        if (pkgs.isEmpty()) {
            try {
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    if (pi.packageName != null && pi.packageName.startsWith("com.dygame.hyqs")) {
                        addPkg(pm, pi);
                    }
                }
            } catch (Throwable t) {
                LogBus.log("枚举应用失败: " + t);
            }
        }
        if (pkgs.isEmpty()) {
            labels.add("未检测到荒野日记，请先安装游戏");
            status.setText("状态：未找到游戏");
        } else {
            selected = pkgs.get(0);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spinner.setAdapter(ad);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < pkgs.size()) selected = pkgs.get(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (pkgs.size() == 1) {
            spinner.setEnabled(false);
            status.setText("状态：已自动识别 " + pkgs.get(0));
        } else if (pkgs.size() > 1) {
            status.setText("状态：检测到 " + pkgs.size() + " 个客户端，请选择");
        }
    }

    private void startInject() {
        if (selected == null) {
            Toast.makeText(this, "没有可注入的游戏", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_VPN && res == RESULT_OK) {
            Intent i = new Intent(this, InjectVpnService.class);
            i.putExtra(InjectVpnService.EXTRA_PKG, selected);
            startService(i);
            LogBus.log("已请求启动注入服务: " + selected);
            refreshStatus();
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    private void refreshStatus() {
        status.setText(InjectVpnService.running ? "状态：注入服务运行中" : "状态：未运行");
    }

    @Override
    public void onLog(String line) {
        logView.append(line + "\n");
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        LogBus.setListener(null);
        super.onDestroy();
    }
}
