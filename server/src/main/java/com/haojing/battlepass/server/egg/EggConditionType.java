package com.haojing.battlepass.server.egg;

/**
 * 用途：彩蛋的判定条件类型（需求文档 §6 的量化定义，一条对应一个彩蛋）。
 *
 * <p>为什么把 10 个彩蛋做成 10 种"条件类型"而不是 10 个子类：
 * 每个彩蛋的配置都必须能被管理员在管理面板里增删改（§6、§9），
 * 而子类化需要自定义 Gson TypeAdapter 才能按 type 反序列化，
 * 配置文件的可读性与容错性都会变差。做成"一个条件 DTO + 一个类型枚举"，
 * 管理员看到的就是"type 决定读哪些字段"，与项目里 Reward 的做法一致。
 *
 * <p>为什么 {@link #fromName(String)} 认不出来时返回 null：条件认错会让彩蛋在完全错误的
 * 时机触发（例如把"钓鱼"当成"放置方块"），比"丢弃该彩蛋并告警"严重得多。
 * 与任务池对未知 action 的处理保持一致。
 */
public enum EggConditionType {

    /** 长安拂晓：北京时间 08:00±5 分钟，玩家 Y≥90 且天空可达（户外）。 */
    TIME_ALTITUDE,

    /** 月下筑者：满月且夜晚，累计放置 100 方块，触发瞬间半径 16 格内无敌对生物。 */
    MOON_FULL_PLACE,

    /** 鱼信：雷雨天气 + 河流群系 + 钓鱼成功一次。 */
    RAIN_RIVER_FISH,

    /** 大地勘探者：本赛季内分别进入深邃洞穴、繁茂洞穴、溶洞三种群系。 */
    BIOME_SET,

    /** 社团同游：半径 32 格内 ≥3 名协会玩家，同群系连续停留 5 分钟（分离超 10 秒重置）。 */
    GROUP_STAY,

    /** 樱落归镐：在樱花林群系种下树苗并成功生长。 */
    SAPLING_GROW,

    /** 守夜人：连续 3 个长夜窗口，每段在线 ≥10 分钟且在线至 06:00。 */
    NIGHT_STREAK,

    /** 长夜微光：长夜内四件防具位为空 + 手持火把 + 户外连续停留 60 秒。 */
    NIGHT_TORCH_OUTDOOR,

    /** 晨归：长夜内保持存活至 06:00 且当时处于户外。 */
    NIGHT_SURVIVE_DAWN,

    /** 静听：长夜内每 tick 位移 < 0.01、无破坏/放置/攻击行为，持续 5 分钟。 */
    NIGHT_STILL;

    /**
     * 宽容地把字符串转成枚举：大小写不敏感、容忍首尾空白，认不出来返回 null。
     *
     * @param name 配置里的类型名
     * @return 对应枚举；为 null 表示无法识别
     */
    public static EggConditionType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String trimmed = name.trim();

        for (EggConditionType type : values()) {
            if (type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }

        return null;
    }

    /**
     * @return 该条件是否只在长夜时段内才有意义。
     *
     * <p>用于校验：把长夜类条件配到非长夜分类（或反过来）几乎一定是配错了，
     * 加载时告警提示管理员，但不丢弃配置（管理员可能就是想自定义）。
     */
    public boolean isLongNightOnly() {
        return this == NIGHT_STREAK || this == NIGHT_TORCH_OUTDOOR
                || this == NIGHT_SURVIVE_DAWN || this == NIGHT_STILL;
    }
}
