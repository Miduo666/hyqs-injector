package com.hyqs.injector.patch;

import android.content.Context;
import android.content.SharedPreferences;

import com.hyqs.injector.LogBus;

/** 功能开关：界面上勾选，注入时写成补丁最前面的 window.__OPT */
public class Options {

    public static final String PREFS = "inject_opts";

    /** key, 名称, 说明, 默认开 */
    public static final String[][] ITEMS = {
            {"roles", "解锁角色皮肤", "全部角色 + 全部皮肤", "1"},
            {"talents", "解锁天赋", "23 个天赋全部解锁", "1"},
            {"coins", "资源与货币", "20 万贝壳，书页/求生精选/天赋原石/天赋结晶各 5000", "1"},
            {"shop", "商城永久包", "月卡 + 四个永久补给包", "1"},
            {"keys", "神秘钥匙", "每次登录 +1000", "1"},
            {"robot", "机关甲人", "解锁 17 个配方，不再与服务器同步", "1"},
            {"attr", "属性面板", "设置里的\"属性\"，含体温", "1"},
            {"workbench", "工具台增强", "一键维修武器、维修家具", "1"},
            {"smelter", "冶金炉增强", "加固远程/近战武器 +120 耐久", "1"},
            {"barb", "野蛮人交易", "每日赠送、随机商品、猪牙掉落", "1"},
            {"recipes", "配方解锁", "医药台女巫药丸、杰克之刃、血匕、子弹图纸", "1"},
            {"tuning", "数值调整", "朱莉长弓 +15、资源点上限、草裙保暖", "1"},
            {"fixes", "Bug 修复", "修掉鳕鱼 buff 期间不受冻的 bug", "1"},
            {"ui", "界面精简", "隐藏帮助按钮和邮件/活动红点，设置里加快捷入口", "1"},
    };

    public static boolean get(Context ctx, String key, boolean def) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(key, def);
    }

    public static void set(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    /** 生成写在补丁最前面的一行：window.__OPT = {...}; */
    public static String prelude(Context ctx) {
        StringBuilder sb = new StringBuilder("window.__OPT = {");
        StringBuilder on = new StringBuilder();
        for (int i = 0; i < ITEMS.length; i++) {
            String key = ITEMS[i][0];
            boolean def = "1".equals(ITEMS[i][3]);
            boolean v = get(ctx, key, def);
            if (i > 0) sb.append(',');
            sb.append('"').append(key).append("\":").append(v ? 1 : 0);
            if (v) on.append(on.length() > 0 ? "、" : "").append(ITEMS[i][1]);
        }
        sb.append("};\n");
        LogBus.log("本次注入的功能: " + (on.length() > 0 ? on.toString() : "无"));
        return sb.toString();
    }
}
