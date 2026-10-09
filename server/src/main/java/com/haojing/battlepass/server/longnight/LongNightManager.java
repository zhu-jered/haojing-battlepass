package com.haojing.battlepass.server.longnight;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.time.TimeUtil;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：长夜时段的状态机与提示。需求文档 §7：
 * 开始前 10 分钟全服弹窗预告；开始时全服标题；结束前 10 分钟预告结束；
 * 定时打印当前北京时间与长夜状态；时间获取异常写 WARN。
 *
 * <p>为什么状态用"每次轮询重新计算，只在跃变时动作"而不是一堆定时器：
 * 定时器一旦配置热重载（改长夜起止时间）就得全部重建，很容易漏掉某个没取消的旧任务；
 * 而"算出来和上次不一样就动作"这种写法天然支持热重载 —— 改完配置下一轮轮询就自动纠正。
 *
 * <p>为什么轮询间隔是 20 tick：需求文档 §13 的性能预算硬指标规定
 * "轮询类检测统一每 20 tick 一次"。这里即使在 30 人在线时也只有每秒钟一次极轻量的时间比较。
 */
public final class LongNightManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 需求文档 §13：轮询类检测统一每 20 tick 一次。 */
    private static final int POLL_INTERVAL_TICKS = 20;

    /** 状态日志间隔：每 5 分钟一条。既能事后复盘长夜是否按时开关，又不至于刷屏。 */
    private static final int STATUS_LOG_INTERVAL_TICKS = 20 * 60 * 5;

    /** 配置变更检查间隔：每 5 秒 stat 一次配置文件，兼顾"改完立刻生效"与不做无谓 IO。 */
    private static final int CONFIG_CHECK_INTERVAL_TICKS = 20 * 5;

    private final ConfigManager configManager;
    private final LongNightEffects effects = new LongNightEffects();

    /** 已发出过的预告去重键，避免"距开始 10 分钟"这一分钟内连发多条。 */
    private final Set<String> firedNoticeKeys = ConcurrentHashMap.newKeySet();

    /** 当前是否处于长夜。用 volatile：写盘线程与指令线程都可能读它。 */
    private volatile boolean active;

    private int tickCounter;
    private int statusLogCounter;
    private int configCheckCounter;

    public LongNightManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** @return 当前是否处于长夜时段。 */
    public boolean isActive() {
        return active;
    }

    /**
     * @return 当前长夜窗口的键（例如 {@code 2026-10-08|00:00-06:00}）；长夜未启用时为空串。
     *
     * <p>阶段 6 的长夜彩蛋用它做"跨窗口归零"，见 {@code LongNightSchedule#windowKey}。
     */
    public String windowKey() {
        return LongNightSchedule.windowKey(TimeUtil.now(), configManager.config());
    }

    /**
     * 计算某玩家的经验倍率。需求文档 §5.11：长夜时段内经验 ×0.5（倍率可配）。
     * 需求文档 §7：管理员白名单不受长夜全部效果影响 —— 经确认**包括经验减半**。
     *
     * <p>为什么按玩家计算而不是给一个全局倍率：白名单是按 UUID 判定的，同一时刻
     * 普通玩家是 0.5、管理员是 1.0。阶段 4/5 发放经验时**必须**传玩家 UUID 进来。
     *
     * @param playerUuid 获得经验的玩家
     * @return 该玩家的经验倍率
     */
    public double xpMultiplier(UUID playerUuid) {
        return LongNightSchedule.resolveXpMultiplier(active, configManager.config(),
                configManager.isAdminWhitelisted(playerUuid));
    }

    /**
     * 每 tick 由服务端调用，内部自行按 20 tick 节流。
     *
     * @param server 服务端实例
     */
    public void tick(MinecraftServer server) {
        SeasonConfig config = configManager.config();

        if (config == null) {
            return;
        }

        if (++tickCounter < POLL_INTERVAL_TICKS) {
            return;
        }

        tickCounter = 0;

        // 需求文档 §16：修改长夜起止时间后不重启即生效。管理面板（阶段 7）会显式触发重载，
        // 在那之前管理员手改 season.json 也要能生效，因此这里顺带做变更检测。
        // 重载后配置引用会换新，下面的 isActive / 倍率都会在下一轮立即按新配置计算。
        if (++configCheckCounter >= CONFIG_CHECK_INTERVAL_TICKS) {
            configCheckCounter = 0;

            if (configManager.reloadIfChanged()) {
                config = configManager.config();
            }
        }

        ZonedDateTime now;

        try {
            now = TimeUtil.now();
        } catch (RuntimeException e) {
            // 需求文档 §7：时间获取异常写 WARN。这里不参与业务判定，只跳过本轮。
            LOGGER.warn("{} 获取北京时间失败，本轮长夜判定已跳过：{}", ModConstants.LOG_PREFIX, e.toString());
            return;
        }

        boolean shouldBeActive = LongNightSchedule.isActive(now, config);

        if (shouldBeActive != active) {
            active = shouldBeActive;

            if (shouldBeActive) {
                onNightStart(server, now, config);
            } else {
                onNightEnd(now);
            }
        }

        for (LongNightSchedule.Notice notice : LongNightSchedule.collectDueNotices(
                now, config, active, firedNoticeKeys, LongNightSchedule.WARN_MINUTES)) {
            announce(server, notice);
        }

        if (active) {
            effects.applyFatigue(server, config.longNight, configManager);
        }

        if (++statusLogCounter >= STATUS_LOG_INTERVAL_TICKS) {
            statusLogCounter = 0;
            // 需求文档 §7：定时打印当前北京时间与长夜状态。
            // 这里打的是"配置倍率"，因为管理员白名单玩家的实际倍率是 1.0，逐人打印没有意义。
            LOGGER.info("{} 长夜状态={} 北京时间={} 经验倍率={} 在线玩家={}",
                    ModConstants.LOG_PREFIX, active ? "进行中" : "未开始", TimeUtil.stamp(now),
                    active ? config.longNight.xpMultiplier : 1.0D,
                    server.getPlayerManager().getPlayerList().size());
        }
    }

    /**
     * 玩家上线时的长夜规则说明。需求文档 §7：长夜期间新玩家上线弹 GUI 规则说明。
     *
     * <p>阶段 3 先用动作栏文字提示；等阶段 7 做出客户端 GUI 后改为弹窗，届时只改这一处。
     *
     * @param player 刚上线的玩家
     */
    public void sendRulesIfActive(ServerPlayerEntity player) {
        if (!active || player == null) {
            return;
        }

        player.sendMessage(Text.translatable("haojing_battlepass.longnight.rules"), true);
    }

    private void onNightStart(MinecraftServer server, ZonedDateTime now, SeasonConfig config) {
        LOGGER.info("{} 长夜开始：北京时间={} 时段={}~{} 经验倍率={}",
                ModConstants.LOG_PREFIX, TimeUtil.stamp(now),
                config.longNight.start, config.longNight.end, config.longNight.xpMultiplier);

        // 需求文档 §7：开始时全服标题。三条包（渐隐时间 + 主标题 + 副标题）缺一不可，
        // 少发 TitleFadeS2CPacket 的话客户端会用上一次的淡入淡出参数。
        TitleFadeS2CPacket fade = new TitleFadeS2CPacket(10, 70, 20);
        TitleS2CPacket title = new TitleS2CPacket(Text.translatable("haojing_battlepass.longnight.title"));
        SubtitleS2CPacket subtitle = new SubtitleS2CPacket(Text.translatable("haojing_battlepass.longnight.subtitle"));

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.networkHandler.sendPacket(fade);
            player.networkHandler.sendPacket(title);
            player.networkHandler.sendPacket(subtitle);
        }
    }

    private void onNightEnd(ZonedDateTime now) {
        LOGGER.info("{} 长夜结束：北京时间={}", ModConstants.LOG_PREFIX, TimeUtil.stamp(now));

        // 长夜结束后清空去重键：下一晚的键本就按日期区分，这里清掉可避免集合随天数无限增长。
        firedNoticeKeys.clear();
    }

    private void announce(MinecraftServer server, LongNightSchedule.Notice notice) {
        // 文案走 TranslationKey（需求文档 §1：界面文案走 TranslationKey，不在代码里硬编码中文），
        // 具体译文由客户端 mod 的 zh_cn.json 提供；第二个参数 true 表示走动作栏，不刷聊天框。
        Text message = notice == LongNightSchedule.Notice.PRE_START
                ? Text.translatable("haojing_battlepass.longnight.pre_start", LongNightSchedule.WARN_MINUTES)
                : Text.translatable("haojing_battlepass.longnight.pre_end", LongNightSchedule.WARN_MINUTES);

        server.getPlayerManager().broadcast(message, true);
    }
}
