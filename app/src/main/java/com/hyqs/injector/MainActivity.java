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
            if (com.hyqs.injector.patch.Options.isAlwaysOn(it[0])) continue;
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(it[1] + "  —  " + it[2]);
            cb.setTextSize(12);
            cb.setChecked(com.hyqs.injector.patch.Options.get(this, it[0], "1".equals(it[3])));
            optBox.addView(cb);
            // 解锁图纸 / 特殊道具：勾上后展开子列表
            final View sub = "blueprints".equals(it[0]) ? buildBlueprintPanel(pad)
                    : "consumables".equals(it[0]) ? buildConsumablePanel(pad) : null;
            if (sub != null) {
                sub.setVisibility(cb.isChecked() ? View.VISIBLE : View.GONE);
                optBox.addView(sub);
            }
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    com.hyqs.injector.patch.Options.set(MainActivity.this, it[0], v);
                    if (sub != null) sub.setVisibility(v ? View.VISIBLE : View.GONE);
                }
            });
        }
        optBox.addView(buildAlwaysOnPanel(pad));
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

    /** 默认优化：始终开启，折叠成一行，点开是只读清单（不能取消） */
    private View buildAlwaysOnPanel(int pad) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, pad / 2, 0, pad / 2);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String[] it : com.hyqs.injector.patch.Options.ITEMS) {
            if (!com.hyqs.injector.patch.Options.isAlwaysOn(it[0])) continue;
            sb.append(sb.length() > 0 ? "\n" : "").append("· ").append(it[1]).append("  —  ").append(it[2]);
            n++;
        }
        final String collapsed = "▸ 默认优化 " + n + " 项（始终开启，点击查看）";
        final String expanded = "▾ 默认优化 " + n + " 项（始终开启，点击收起）";
        final TextView head = new TextView(this);
        head.setText(collapsed);
        head.setTextSize(13);
        head.setTextColor(Color.rgb(0x33, 0x66, 0x99));
        head.setPadding(0, pad / 4, 0, pad / 4);
        final TextView body = new TextView(this);
        body.setText(sb.toString());
        body.setTextSize(12);
        body.setTextColor(Color.DKGRAY);
        body.setPadding(pad, 0, 0, 0);
        body.setVisibility(View.GONE);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                head.setText(show ? expanded : collapsed);
            }
        });
        panel.addView(head);
        panel.addView(body);
        return panel;
    }

    /** 图纸子列表，两列排布，缩进在父选项下面 */
    private View buildBlueprintPanel(int pad) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad * 2, 0, 0, pad / 2);
        String[][] bps = com.hyqs.injector.patch.Options.BLUEPRINTS;
        final List<android.widget.CheckBox> boxes = new ArrayList<>();
        final Button all = new Button(this);
        all.setTextSize(12);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean allOn = true;
                for (android.widget.CheckBox b : boxes) allOn &= b.isChecked();
                for (android.widget.CheckBox b : boxes) b.setChecked(!allOn); // 会触发各自的监听器保存
                all.setText(allOn ? "全选" : "全不选");
            }
        });
        panel.addView(all, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout line = null;
        for (int i = 0; i < bps.length; i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(line);
            }
            final String key = com.hyqs.injector.patch.Options.bpKey(bps[i][0]);
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(bps[i][1]);
            cb.setTextSize(12);
            cb.setChecked(com.hyqs.injector.patch.Options.get(this, key, false));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    com.hyqs.injector.patch.Options.set(MainActivity.this, key, v);
                    boolean on = true;
                    for (android.widget.CheckBox x : boxes) on &= x.isChecked();
                    all.setText(on ? "全不选" : "全选");
                }
            });
            line.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            boxes.add(cb);
        }
        boolean allOn = true;
        for (android.widget.CheckBox b : boxes) allOn &= b.isChecked();
        all.setText(allOn ? "全不选" : "全选");
        return panel;
    }

    /** 特殊道具子列表：每行 名称 + 数量输入框，0 表示不发 */
    private View buildConsumablePanel(int pad) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad * 2, 0, 0, pad / 2);
        for (final String[] it : com.hyqs.injector.patch.Options.CONSUMABLES) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(it[1]);
            name.setTextSize(12);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            android.widget.EditText num = new android.widget.EditText(this);
            num.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            num.setTextSize(12);
            num.setEms(3);
            num.setGravity(Gravity.CENTER);
            num.setText(String.valueOf(com.hyqs.injector.patch.Options.getCount(this, it[0])));
            num.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    int v = 0;
                    try {
                        v = Integer.parseInt(s.toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    com.hyqs.injector.patch.Options.setCount(MainActivity.this, it[0], v);
                }
            });
            line.addView(num);
            panel.addView(line);
        }
        return panel;
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
