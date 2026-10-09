package com.haojing.battlepass.server.egg;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：彩蛋的判定条件（需求文档 §6 的量化定义）。
 *
 * <p>为什么是本类所有字段的并集，而不是每种类型一个子类：见 {@link EggConditionType} 的说明。
 * 哪种类型读哪些字段，由 {@link EggEvaluator} 的分支与默认配置的注释共同说明；
 * 未用到的字段留空即可（Gson 会保留默认值）。
 *
 * <p>所有数值都带默认值，且默认值就是 §6 表格里写死的那些数（例如 08:00±5 分钟、Y≥90、
 * 半径 16 格、连续 5 分钟…）。管理员改配置即可调整，不必改代码。
 */
public class EggCondition {

    /** 条件类型名，取值见 {@link EggConditionType}。 */
    public String type = "";

    // ---------- TIME_ALTITUDE（长安拂晓） ----------

    /** 目标时刻，HH:mm（默认 08:00）。 */
    public String time = "08:00";

    /** 容差分钟数（默认 ±5 分钟）。 */
    public int toleranceMin = 5;

    /** 最低 Y 坐标（默认 90）。 */
    public int minY = 90;

    /** 是否要求天空可达（默认 true，即"户外"）。 */
    public boolean requireOutdoor = true;

    // ---------- MOON_FULL_PLACE（月下筑者） ----------

    /** 累计放置方块目标数（默认 100）。 */
    public int placeTarget = 100;

    /** 触发瞬间的"无敌对生物"检查半径（默认 16 格）。 */
    public int safeRadius = 16;

    // ---------- RAIN_RIVER_FISH（鱼信） ----------

    /** 要求的群系 ID（默认河流）。 */
    public String biome = "minecraft:river";

    /** 是否要求雷雨（默认 true；关掉就变成"下雨即可"）。 */
    public boolean requireThunder = true;

    // ---------- BIOME_SET（大地勘探者） ----------

    /** 要求集齐的群系 ID 列表（默认三种洞穴群系）。 */
    public List<String> biomes = new ArrayList<>();

    // ---------- GROUP_STAY（社团同游） ----------

    /** 同伴统计半径（默认 32 格）。 */
    public int radius = 32;

    /** 至少需要的同伴人数（默认 3 人）。 */
    public int minPlayers = 3;

    /** 需要连续停留的秒数（默认 5 分钟）。 */
    public int staySeconds = 300;

    /** 中途分离超过多少秒就重置（默认 10 秒）。 */
    public int breakToleranceSeconds = 10;

    // ---------- NIGHT_STREAK（守夜人） ----------

    /** 需要的连续长夜窗口数（默认 3）。 */
    public int nights = 3;

    /** 每个窗口至少在线多少秒（默认 10 分钟）。 */
    public int minOnlineSeconds = 600;

    // ---------- NIGHT_TORCH_OUTDOOR（长夜微光） ----------

    /** 需要连续满足的秒数（默认 60 秒）。 */
    public int holdSeconds = 60;

    // ---------- NIGHT_STILL（静听） ----------

    /** 每 tick 允许的最大位移（默认 0.01 格）。 */
    public double maxMovePerTick = 0.01D;

    /** 需要持续的秒数（默认 5 分钟）。 */
    public int stillSeconds = 300;

    /** @return 解析后的条件类型；无法识别时为 null（该彩蛋会在校验阶段被丢弃）。 */
    public EggConditionType typeOrNull() {
        return EggConditionType.fromName(type);
    }

    /** @return 该条件配置是否合法（类型可识别 + 关键数值为正）。 */
    public boolean isValid() {
        EggConditionType resolved = typeOrNull();

        if (resolved == null) {
            return false;
        }

        switch (resolved) {
            case TIME_ALTITUDE:
                return toleranceMin >= 0;
            case MOON_FULL_PLACE:
                return placeTarget > 0 && safeRadius >= 0;
            case RAIN_RIVER_FISH:
                return biome != null && !biome.isBlank();
            case BIOME_SET:
                return biomes != null && !biomes.isEmpty();
            case GROUP_STAY:
                return minPlayers > 0 && staySeconds > 0 && radius > 0 && breakToleranceSeconds >= 0;
            case NIGHT_STREAK:
                return nights > 0 && minOnlineSeconds >= 0;
            case NIGHT_TORCH_OUTDOOR:
                return holdSeconds > 0;
            case NIGHT_STILL:
                return stillSeconds > 0 && maxMovePerTick >= 0;
            case SAPLING_GROW:
                return biome != null && !biome.isBlank();
            default:
                return true;
        }
    }

    /** 就地规范化：去掉 ID 类字段的空白。 */
    public void normalize() {
        type = type == null ? "" : type.trim();
        time = time == null ? "" : time.trim();
        biome = biome == null ? "" : biome.trim();

        if (biomes == null) {
            biomes = new ArrayList<>();
        }

        List<String> cleaned = new ArrayList<>();

        for (String entry : biomes) {
            if (entry != null && !entry.isBlank()) {
                cleaned.add(entry.trim());
            }
        }

        biomes = cleaned;
    }
}
