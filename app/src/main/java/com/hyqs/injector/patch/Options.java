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
            {"attr", "属性面板", "设置里的\"属性\"，含体温；点开顶部生命/精神/失眠/外伤/内伤/饥饿图标显示 当前/上限", "1"},
            {"workbench", "工具台增强", "一键维修武器、维修家具", "1"},
            {"smelter", "冶金炉增强", "加固远程/近战武器 +120 耐久", "1"},
            {"barb", "野蛮人交易", "每日赠送、随机商品、猪牙掉落", "1"},
            {"recipes", "配方解锁", "医药台女巫药丸、杰克之刃、血匕、子弹图纸", "1"},
            {"tuning", "数值调整", "朱莉长弓 +15、资源点上限、草裙保暖", "1"},
            {"fixes", "Bug 修复", "修掉鳕鱼 buff 期间不受冻的 bug", "1"},
            {"ui", "界面精简", "隐藏帮助按钮和邮件/活动红点，设置里加快捷入口", "1"},
            {"secretCamp", "秘密营地", "生存满 20 天后，地图上出现两处秘密营地（瀑布下方的平原、原始森林与丘陵之间）；存够材料（木材12、石块6、藤条8、石片6、黏土3）花 6 小时建成。不会被野兽袭击、不用栅栏、家具耐久消耗减半，仓库容量 3000，主营地已解锁的建筑（如角色专属的冶金炉）在这里也可建造。关掉后已建成的营地不再显示（数据保留，重新打开即恢复）", "1"},
            {"beastDen", "野兽暴动兽巢", "生存满 5 天后，地图上已探索的空地会出现一个兽巢（同一时间只有一个）：8~10 波野兽（75 种组合按地形抽取，带破甲，无视 5~10 防御）+ 3~4 个沉船箱子；打完并把东西领空后，3 天后在别处重新出现", "1"},
            {"blueprints", "解锁图纸", "在下方选择要解锁的图纸（写入存档，之后取消勾选不会收回）", "1"},
            {"consumables", "特殊道具", "永久加属性的活动食物，按下方数量发到邮箱（每次注入只发一次；只发还能吃出效果的数量，已吃的和身上已有的会扣掉）", "1"},
            {"gifts", "友 → 礼", "获得友·食/医/远武/近武/材时直接变成可自己打开的礼·X（每种每天仍只能开一个）", "1"},
            {"huoxiang", "藿香正气水", "医药台 Lv2 制作藿香正气水；林中暗流通关后可采集藿香；野蛮人收藿香按 1 计价", "1"},
            {"brewery", "净水台升级酿酒台", "生存 15 天后净水台可升级为酿酒台（石块 6、藤条 4、木材 6、黏土 4），保留原功能并能酿土豆酒；罗兰自带酿酒台，不开放", "1"},
            {"veg", "野菜", "到达通关后的采集点时 30% 刷出 2 个野菜；火堆做烤肉/烤土豆/荒野烤肉/肉汤/土豆泥/秘制炖肉时有野菜就多耗 1 个，美味概率变 50%；野蛮人收野菜按 1 计价", "1"},
            {"offhand", "机关剑副手", "机关剑装在远程位且没有箭时，远程轮次改用近战形态额外攻击一次", "1"},
            {"camel", "骆驼负重", "解锁骆驼后背包负重 +20", "1"},
    };

    /**
     * 图纸/解锁类商品：shop ID, 名称。解锁即写 base.shop.<ID>。
     * 36~40 是角色专属配方：配方条件是"图纸 且 当前角色"，写 base.shop 只满足图纸那一半，别的角色照样做不了。
     */
    public static final String[][] BLUEPRINTS = {
            {"34", "改良平底锅"}, {"35", "某人的新衣"},
            {"36", "奇特香料（罗兰专属）"}, {"37", "研磨器（老贝专属）"},
            {"38", "伪袖珍手枪（朱莉专属）"}, {"39", "小哥肉酱（小哥专属）"},
            {"40", "扑克/纸牌（基德专属）"},
            {"41", "石锤"}, {"42", "暖身丸"},
            {"43", "强身丸"}, {"44", "军粮丸"},
            {"45", "驯兽鞭"}, {"46", "吹箭"},
            {"47", "机关剑"}, {"48", "机关连弩"},
            {"49", "麻痹针"}, {"50", "女巫扫帚"},
            {"53", "羽毛裙"}, {"54", "姜饼人"},
            {"55", "可可豆种植"}, {"56", "巧克力"},
            {"57", "防护服"}, {"58", "冰雪卷轴（需冰雪女王皮肤）"},
            {"51", "南瓜包（负重 +6）"},
    };

    /**
     * 特殊道具：key, 名称, 物品 ID（同一种食物历年活动用了不同 ID，USEMAX 按 ID 各自计数）。
     * 补丁里只发有效数量：每个 ID 最多 USEMAX - 已吃次数 - 背包仓库现有数量，超出不发。
     */
    public static final String[][] CONSUMABLES = {
            {"pokeball", "口袋球  负重+3，吃满 6 个", "4500"},
            {"zongzi_meat", "普通的肉粽  攻击+2，吃满 24 个（4 个 ID 各 6）", "4502,4516,4518,4520"},
            {"zongzi_date", "红枣粽子  饥饿上限+2，吃满 32 个（4 个 ID 各 8）", "4501,4515,4519,4521"},
            {"icepop", "冰棍  精神上限+2，吃满 12 个（2 个 ID 各 6）", "4503,4517"},
            {"huagua", "花瓜  失眠上限+2，吃满 17 个", "4504"},
    };

    /**
     * 纯正向优化：始终开启，界面上折叠成一行只读说明，不给勾选。
     * 以前手动关过的也会被强制打开（忽略 SharedPreferences 里存的 false）。
     */
    public static final java.util.Set<String> ALWAYS_ON = new java.util.HashSet<>(java.util.Arrays.asList(
            "attr", "workbench", "smelter", "barb", "recipes", "tuning", "fixes", "ui",
            "gifts", "huoxiang", "brewery", "veg", "offhand", "camel"));

    public static boolean isAlwaysOn(String key) {
        return ALWAYS_ON.contains(key);
    }

    public static final int CONSUMABLE_DEFAULT = 20;

    public static String bpKey(String id) {
        return "bp_" + id;
    }

    public static String cntKey(String key) {
        return "cnt_" + key;
    }

    public static boolean get(Context ctx, String key, boolean def) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(key, def);
    }

    /** 按 ITEMS 里的默认值读取开关；ALWAYS_ON 里的恒为开 */
    public static boolean isOn(Context ctx, String key) {
        if (isAlwaysOn(key)) return true;
        for (String[] it : ITEMS) if (it[0].equals(key)) return get(ctx, key, "1".equals(it[3]));
        return get(ctx, key, false);
    }

    public static void set(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    public static int getCount(Context ctx, String key) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(cntKey(key), CONSUMABLE_DEFAULT);
    }

    public static void setCount(Context ctx, String key, int value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(cntKey(key), Math.max(0, value)).apply();
    }

    /** 生成写在补丁最前面的一行：window.__OPT = {...}; */
    public static String prelude(Context ctx) {
        StringBuilder sb = new StringBuilder("window.__OPT = {");
        StringBuilder on = new StringBuilder();
        for (int i = 0; i < ITEMS.length; i++) {
            String key = ITEMS[i][0];
            boolean v = isOn(ctx, key);
            if (i > 0) sb.append(',');
            sb.append('"').append(key).append("\":").append(v ? 1 : 0);
            if (v && !isAlwaysOn(key)) on.append(on.length() > 0 ? "、" : "").append(ITEMS[i][1]);
        }
        on.append(on.length() > 0 ? "，" : "").append("另含 ").append(ALWAYS_ON.size()).append(" 项默认优化");
        StringBuilder bp = new StringBuilder();
        StringBuilder bpNames = new StringBuilder();
        boolean bpOn = isOn(ctx, "blueprints");
        if (bpOn) {
            for (String[] it : BLUEPRINTS) {
                if (!get(ctx, bpKey(it[0]), false)) continue;
                bp.append(bp.length() > 0 ? "," : "").append('"').append(it[0]).append('"');
                bpNames.append(bpNames.length() > 0 ? "、" : "").append(it[1]);
            }
        }
        sb.append(",\"bpList\":[").append(bp).append(']');

        StringBuilder grant = new StringBuilder();
        StringBuilder grantNames = new StringBuilder();
        boolean grantOn = isOn(ctx, "consumables");
        if (grantOn) {
            for (String[] it : CONSUMABLES) {
                int n = getCount(ctx, it[0]);
                if (n <= 0) continue;
                grant.append(grant.length() > 0 ? "," : "").append("{\"ids\":[");
                String[] ids = it[2].split(",");
                for (int j = 0; j < ids.length; j++) {
                    grant.append(j > 0 ? "," : "").append('"').append(ids[j]).append('"');
                }
                grant.append("],\"n\":").append(n).append('}');
                grantNames.append(grantNames.length() > 0 ? "、" : "")
                        .append(it[1].split(" ")[0]).append('×').append(n);
            }
        }
        // 每次生成补丁换一个令牌，补丁据此保证同一次注入只发一次邮件
        sb.append(",\"grant\":[").append(grant).append("],\"grantTok\":\"")
                .append(System.currentTimeMillis()).append("\"};\n");

        LogBus.log("本次注入的功能: " + (on.length() > 0 ? on.toString() : "无"));
        if (bpOn) {
            LogBus.log("解锁图纸: " + (bpNames.length() > 0 ? bpNames.toString() : "未选择任何图纸"));
        }
        if (grantOn) {
            LogBus.log("特殊道具: " + (grantNames.length() > 0 ? grantNames.toString() : "数量都是 0，不发放"));
        }
        return sb.toString();
    }
}
