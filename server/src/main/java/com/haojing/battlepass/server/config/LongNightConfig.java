package com.haojing.battlepass.server.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：长夜时段的配置（需求文档 §7、§18 的 {@code season.json} 里 {@code longNight} 子对象）。
 *
 * <p>为什么用 public 字段：本类是配置文件的内存映射，由 Gson 直接读写。用 public 字段省掉大量
 * 样板 getter/setter，而且管理员 GUI（阶段 7）在编辑配置时能直接看到全部可调项，不会漏。
 * 行为逻辑（例如"当前是否处于长夜"）一律放在 TimeUtil 与 LongNightManager 里，本类只承载数据。
 *
 * <p><b>注意本类刻意不包含三个文档里有的字段</b>：{@code mobSpawnMultiplier}、{@code entityCap}、
 * {@code creeperGriefProtect}。原因见 docs/需求偏差记录.md 的 D-2 与 D-3 ——
 * Fabric API 既没有否决刷怪的事件，也完全没有爆炸事件，这两项功能已被用户明确放弃。
 * 保留"能配但不起作用"的字段比直接删掉更危险：管理员会以为开了保护，实际毫无效果。
 */
public class LongNightConfig {

    /** 长夜总开关。需求文档 §7：起止时间、总开关均由管理员 GUI 配置，热重载。 */
    public boolean enabled = true;

    /** 长夜开始时刻，HH:mm。文档 §7 默认 00:00（北京时间）。 */
    public String start = "00:00";

    /** 长夜结束时刻，HH:mm。文档 §7 默认 06:00。 */
    public String end = "06:00";

    /**
     * 长夜期间的经验倍率。需求文档 §5.11：长夜时段内进度正常累计，经验 ×0.5（倍率可配，默认 0.5）。
     */
    public double xpMultiplier = 0.5;

    /** 长夜疲劳总开关。需求文档 §7 要求在线玩家获得长夜疲劳，因此默认开启。 */
    public boolean fatigueEnabled = true;

    /**
     * 疲劳的缓慢等级（amplifier，0 表示 I 级）。文档 §7 未给数值，
     * 按"不自行发明平衡数值"的原则取最小档 I 级，已在待确认清单中登记。
     */
    public int fatigueSlownessAmplifier = 0;

    /** 疲劳的挖掘疲劳等级（amplifier，0 表示 I 级）。同上，取最小档。 */
    public int fatigueMiningFatigueAmplifier = 0;

    /** 是否叠加饥饿加速。需求文档 §7 写作"可选"，因此默认关闭。 */
    public boolean fatigueHungerAcceleration = false;

    /**
     * 不受长夜全部效果影响的玩家 UUID 列表（需求文档 §7：管理员白名单，用于夜间维护）。
     * 以字符串存储，便于人工编辑与校验；非法项在配置加载时会被剔除并告警。
     */
    public List<String> adminWhitelist = new ArrayList<>();

    // 说明：原本按需求文档 §7 这里还应有"怪物移速/伤害/血量增益与玩家探测范围扩大"四个旋钮，
    // 现已一并移除。原因：文档通篇没有给出这四个数值，而用户明确表示长夜"只有疲劳 + 经验减半就足够"。
    // 若只保留默认 0 的旋钮却不实现生效逻辑，就成了"能配但没用"的死配置，比删掉更容易误导管理员。
    // 详见 docs/需求偏差记录.md 的 D-4。
}
