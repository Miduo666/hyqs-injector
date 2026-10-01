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
    private TextView logView, status, version;
    private ScrollView scroll;
    private String selected;
    /** 最近一次验签通过的在线更新 payload（qimeng.app/hyqs/manifest.json） */
    private volatile org.json.JSONObject lastPayload;
    private volatile long lastCheckMs, lastAttemptMs;
    private volatile boolean checking, pendingInject;

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

        version = new TextView(this);
        version.setTextSize(12);
        version.setTextColor(Color.GRAY);
        root.addView(version);

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
        LogBus.attachFile(getFilesDir());

        StringBuilder sb = new StringBuilder();
        for (String l : LogBus.snapshot()) sb.append(l).append('\n');
        logView.setText(sb.toString());
        refreshStatus();
        refreshVersion();

        // 调试用：adb start MainActivity --es auto <包名> 可直接开始注入
        String auto = getIntent() != null ? getIntent().getStringExtra("auto") : null;
        if (auto != null) {
            if (pkgs.contains(auto)) selected = auto;
            startInject();
        } else {
            checkUpdates(false);   // 打开就在后台查一次：有新补丁先下好，有新版注入器弹窗
        }
    }

    // ------------------------------------------------------------ 在线更新

    private void refreshVersion() {
        version.setText("注入器 v" + com.hyqs.injector.update.Updater.myVersionName(this)
                + "（" + com.hyqs.injector.update.Updater.myVersionCode(this) + "）  ·  补丁 "
                + com.hyqs.injector.update.Updater.patchLabel(this));
    }

    /**
     * 后台拉 manifest（验签）→ 有新补丁就下载 → 回到界面：
     * beforeInject=true 时随后继续注入（新版注入器标了 force 才拦住）；false 时只在有新版注入器时弹窗。
     * 网络不通、验签失败都不影响注入，直接用已有的补丁。
     */
    private void checkUpdates(final boolean beforeInject) {
        if (checking) {
            // 打开时的后台检查还没回来就点了注入：等它回来再接着注入
            if (beforeInject) pendingInject = true;
            return;
        }
        checking = true;
        lastAttemptMs = System.currentTimeMillis();
        if (beforeInject) LogBus.log("检查在线更新…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                org.json.JSONObject p = com.hyqs.injector.update.Updater.fetchManifest(MainActivity.this,
                        beforeInject ? 3000 : 5000, beforeInject ? 6000 : 10000);
                boolean got = false;
                if (p != null) {
                    lastPayload = p;
                    lastCheckMs = System.currentTimeMillis();
                    got = com.hyqs.injector.update.Updater.syncPatch(MainActivity.this, p);
                }
                final boolean patchUpdated = got;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        refreshVersion();
                        org.json.JSONObject apk = com.hyqs.injector.update.Updater.newerApk(MainActivity.this, lastPayload);
                        boolean inject = beforeInject || pendingInject;
                        pendingInject = false;
                        if (inject) {
                            if (apk != null && apk.optBoolean("force")) showApkDialog(apk);
                            else proceedInject();   // 正要注入：新补丁直接用上（服务在运行就让它换补丁）
                        } else if (apk != null) {
                            showApkDialog(apk);     // 新版注入器优先提示（新版自带更新的补丁）
                        } else if (patchUpdated) {
                            showPatchDialog(lastPayload.optJSONObject("patch"));
                        }
                    }
                });
            }
        }).start();
    }

    /** 打开时后台拿到了新补丁：补丁要注入后（游戏重开）才生效，直接问要不要现在注入 */
    private void showPatchDialog(org.json.JSONObject patch) {
        if (isFinishing() || patch == null) return;
        String notes = patch.optString("notes", "");
        boolean running = InjectVpnService.running;
        new android.app.AlertDialog.Builder(this)
                .setTitle("补丁已更新到 v" + patch.optInt("serial"))
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n")
                        + (running ? "注入服务正在运行，点【立即生效】换上新补丁，然后完全退出并重新打开游戏。"
                                   : "新补丁要注入后才会生效。点【立即注入】，然后打开（或重开）游戏。"))
                .setPositiveButton(running ? "立即生效" : "立即注入", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        proceedInject();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void showApkDialog(final org.json.JSONObject apk) {
        if (isFinishing()) return;
        final boolean force = apk.optBoolean("force");
        String notes = apk.optString("notes", "");
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("注入器有新版本 v" + apk.optString("versionName"))
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "大小 " + apk.optInt("size") / 1024 + " KB。"
                        + "下载后系统会弹出安装确认，点【安装】即可覆盖更新，设置和勾选都会保留。"
                        + (force ? "\n\n这个版本必须更新后才能继续注入。" : ""))
                .setCancelable(!force)
                .setPositiveButton("更新", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        downloadAndInstall(apk);
                    }
                });
        if (!force) b.setNegativeButton("稍后", null);
        b.show();
    }

    private void downloadAndInstall(final org.json.JSONObject apk) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请允许注入器\"安装未知应用\"，返回后再点一次更新", Toast.LENGTH_LONG).show();
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        LogBus.log("下载新版注入器 v" + apk.optString("versionName") + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.io.File f = com.hyqs.injector.update.Updater.downloadApk(MainActivity.this, apk);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (f == null) {
                            Toast.makeText(MainActivity.this, "下载失败，稍后再试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            com.hyqs.injector.update.Updater.install(MainActivity.this, f);
                            LogBus.log("新版注入器已下载，等待安装确认");
                        } catch (Throwable t) {
                            LogBus.log("调起安装失败: " + t);
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注入器一直挂在后台、过一阵切回来时 onCreate 不会再走：超过 10 分钟没查过就再查一次
        if (lastAttemptMs > 0 && System.currentTimeMillis() - lastAttemptMs > 10 * 60_000) checkUpdates(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !com.hyqs.injector.update.Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int st = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);   // 系统安装确认界面
        } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            LogBus.log("新版注入器安装完成");
        } else {
            LogBus.log("安装未完成（" + st + "）: " + intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
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
        // 注入前确认补丁是最新的：1 分钟内查过就不再查，否则先快速查一次（最多几秒，失败照常注入）
        if (System.currentTimeMillis() - lastCheckMs < 60_000) {
            org.json.JSONObject apk = com.hyqs.injector.update.Updater.newerApk(this, lastPayload);
            if (apk != null && apk.optBoolean("force")) showApkDialog(apk);
            else proceedInject();
        } else {
            checkUpdates(true);
        }
    }

    private void proceedInject() {
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
