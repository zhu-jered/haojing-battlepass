package com.haojing.battlepass.server.config;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 用途：赛季配置的加载、保存与热重载。需求文档 §2：管理员在 GUI 修改的所有配置支持热重载，
 * 无需重启，玩家数据不丢失。
 *
 * <p>为什么"加载后必定回写一次文件"：这样能同时拿到三个好处 ——
 * ①首次运行自动落地一份带注释意义的默认配置，管理员有文件可改，不用对着空目录猜格式；
 * ②配置被改坏时，JsonStore 回滚或退回默认值后会把修好的内容写回，避免每次启动都重复报警；
 * ③加载时的规范化（时间统一成 HH:mm、白名单统一成小写 UUID、数值夹紧到合法区间）会落到磁盘上，
 * 而不是只存在于内存里，管理员打开文件看到的就是模组真正在用的值。
 *
 * <p>为什么校验采取"修正 + 告警"而不是"拒绝启动"：一个手滑写错的数字不应该让整个服务端起不来。
 * 但每处修正都必须留下 WARN，否则就成了静默改配置，比直接崩更难排查。
 */
public final class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 经验倍率允许区间。文档只给了默认值 0.5，这里给出一个显然安全的上下界防止手滑。 */
    private static final double MIN_XP_MULTIPLIER = 0.0D;
    private static final double MAX_XP_MULTIPLIER = 10.0D;

    /** 原版状态效果等级上限。 */
    private static final int MAX_AMPLIFIER = 255;

    private final StoragePaths paths;
    private final JsonStore jsonStore;

    /** 当前生效配置。用 volatile：热重载会替换引用，而读取方可能在任何线程。 */
    private volatile SeasonConfig config;

    /** 配置文件的变更检测器，用于"改完不重启即生效"（需求文档 §16）。 */
    private final FileChangeDetector changeDetector = new FileChangeDetector("赛季配置");

    public ConfigManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的配置；未加载时为 null。 */
    public SeasonConfig config() {
        return config;
    }

    /**
     * 从磁盘加载配置并规范化，随后回写一次。文件缺失或损坏时退回默认值。
     *
     * @return 加载后的配置（永不为 null）
     */
    public SeasonConfig load() {
        Path file = paths.seasonConfigFile();
        SeasonConfig loaded = jsonStore.read(file, SeasonConfig.class, () -> {
            LOGGER.info("{} 未找到可用的赛季配置，将生成默认配置：{}", ModConstants.LOG_PREFIX, file);
            return new SeasonConfig();
        });

        validate(loaded);
        config = loaded;
        writeQuietly(loaded);
        // 必须记录"回写之后"的修改时间：本方法会主动写回文件，若不重新取基线，
        // 下一次变更检查会把自己刚刚的写入误判成外部改动，从而陷入每轮都热重载的死循环。
        changeDetector.reset(file);
        return loaded;
    }

    /**
     * 若配置文件在磁盘上被外部改动过，则自动热重载。
     *
     * <p>为什么需要它：需求文档 §16 的验收用例要求「修改长夜起止时间后不重启即生效」。
     * 管理面板（阶段 7）会显式调用 {@link #reload()}，但在此之前管理员只能手改 season.json，
     * 那时若没有自动检测，改完不重启就不生效，验收用例过不了。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.seasonConfigFile();

        if (!changeDetector.hasChanged(file)) {
            return false;
        }

        reload();
        // reload 内部会回写文件，基线必须重新取，否则下一轮又会"检测到变更"。
        changeDetector.reset(file);
        return true;
    }

    /**
     * 热重载：重新读取磁盘上的配置并替换生效配置。
     * 需求文档 §2 要求热重载时玩家数据不丢失 —— 本方法只碰配置，完全不触碰 PlayerDataManager。
     *
     * @return 重载后的配置
     */
    public SeasonConfig reload() {
        LOGGER.info("{} 正在热重载赛季配置：{}", ModConstants.LOG_PREFIX, paths.seasonConfigFile());
        SeasonConfig reloaded = load();
        LOGGER.info("{} 赛季配置已热重载：赛季={} 开关={} 每日刷新={} 长夜={}({}~{} 倍率 {})",
                ModConstants.LOG_PREFIX, reloaded.seasonId, reloaded.enabled, reloaded.dailyRefreshTime,
                reloaded.longNightEnabled(), reloaded.longNight.start, reloaded.longNight.end,
                reloaded.longNight.xpMultiplier);
        return reloaded;
    }

    /** 把当前内存中的配置写回磁盘。供管理员 GUI 修改配置后调用。 */
    public void save() {
        SeasonConfig current = config;

        if (current == null) {
            LOGGER.warn("{} 配置尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        validate(current);
        writeQuietly(current);
    }

    /**
     * @param uuid 玩家 UUID
     * @return 该玩家是否在长夜管理员白名单中（需求文档 §7：白名单玩家不受长夜全部效果影响）
     */
    public boolean isAdminWhitelisted(UUID uuid) {
        SeasonConfig current = config;

        if (uuid == null || current == null || current.longNight == null
                || current.longNight.adminWhitelist == null) {
            return false;
        }

        // 白名单在 validate 阶段已统一为小写规范形式，UUID.toString() 也是小写，可直接比较。
        return current.longNight.adminWhitelist.contains(uuid.toString());
    }

    private void writeQuietly(SeasonConfig value) {
        try {
            jsonStore.write(paths.seasonConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            // 配置写不进去不应该让服务端崩：内存里的配置依然有效，本次运行照常。
            LOGGER.error("{} 写入赛季配置失败（内存中的配置仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.seasonConfigFile(), e);
        }
    }

    /** 逐项校验并就地修正配置。每一处修正都留下 WARN。 */
    private void validate(SeasonConfig value) {
        if (value.schemaVersion != SeasonConfig.CURRENT_SCHEMA_VERSION) {
            LOGGER.warn("{} 配置的 schemaVersion={} 与当前版本 {} 不一致，暂按当前结构读取，请检查是否漏了迁移",
                    ModConstants.LOG_PREFIX, value.schemaVersion, SeasonConfig.CURRENT_SCHEMA_VERSION);
        }

        if (value.seasonId == null || value.seasonId.isBlank()) {
            LOGGER.warn("{} 配置缺少 seasonId，已回退为 S1", ModConstants.LOG_PREFIX);
            value.seasonId = "S1";
        }

        if (value.themeName == null) {
            value.themeName = "";
        }

        if (value.durationDays < 1) {
            LOGGER.warn("{} durationDays={} 非法，已回退为 30", ModConstants.LOG_PREFIX, value.durationDays);
            value.durationDays = 30;
        }

        if (value.maxLevel < 1) {
            LOGGER.warn("{} maxLevel={} 非法，已回退为 30", ModConstants.LOG_PREFIX, value.maxLevel);
            value.maxLevel = 30;
        }

        if (value.branchUnlockLevel < 1 || value.branchUnlockLevel > value.maxLevel) {
            int clamped = Math.max(1, Math.min(value.branchUnlockLevel, value.maxLevel));

            if (clamped != value.branchUnlockLevel) {
                LOGGER.warn("{} branchUnlockLevel={} 超出 1~{}，已夹紧为 {}",
                        ModConstants.LOG_PREFIX, value.branchUnlockLevel, value.maxLevel, clamped);
                value.branchUnlockLevel = clamped;
            }
        }

        if (value.dailyXpCap < 0) {
            LOGGER.warn("{} dailyXpCap={} 非法，已回退为 0（等于不限制）", ModConstants.LOG_PREFIX, value.dailyXpCap);
            value.dailyXpCap = 0;
        }

        if (value.dailyRerollLimit < 0) {
            LOGGER.warn("{} dailyRerollLimit={} 非法，已回退为 0（等于禁止刷新任务）",
                    ModConstants.LOG_PREFIX, value.dailyRerollLimit);
            value.dailyRerollLimit = 0;
        }

        validateLevelCurve(value);

        if (value.starCoinPerLevel < 0) {
            LOGGER.warn("{} starCoinPerLevel={} 非法，已回退为 0（升级不再发京币）",
                    ModConstants.LOG_PREFIX, value.starCoinPerLevel);
            value.starCoinPerLevel = 0;
        }

        if (value.exemptCardMax < 0) {
            LOGGER.warn("{} exemptCardMax={} 非法，已回退为 0（不允许持有任务卡）",
                    ModConstants.LOG_PREFIX, value.exemptCardMax);
            value.exemptCardMax = 0;
        }

        value.commandWhitelist = normalizeCommandWhitelist(value.commandWhitelist);

        if (value.fakePlayerNamePrefix == null) {
            // 空串表示不启用假人过滤；这里只把 null 归一化，避免后续判空遗漏。
            value.fakePlayerNamePrefix = "";
        }

        // 规范化成 HH:mm：TimeUtil.parseTime 内部对非法值记 WARN 并给出兜底。
        value.dailyRefreshTime = formatTime(TimeUtil.parseTime(value.dailyRefreshTime, LocalTime.of(6, 0)));

        if (value.longNight == null) {
            LOGGER.warn("{} 配置缺少 longNight 节点，已补上默认值", ModConstants.LOG_PREFIX);
            value.longNight = new LongNightConfig();
        }

        validateLongNight(value.longNight);
        validateGui(value.gui);
    }

    /**
     * 规范化 GUI 视觉配置：颜色代码写错 → 回退默认并 WARN；透明度越界 → 夹紧。
     *
     * <p>为什么全部"修正而不是拒绝"：外观配置改错了不应该让服务端起不来，
     * 也不应该让客户端崩；回退默认 + 一条 WARN 是最稳的行为。
     */
    private void validateGui(SeasonConfig.GuiStyleConfig gui) {
        if (gui == null) {
            LOGGER.warn("{} 配置缺少 gui 节点，已补上默认值", ModConstants.LOG_PREFIX);
            gui = new SeasonConfig.GuiStyleConfig();
            return;
        }

        if (com.haojing.battlepass.common.gui.GuiColors.parseSectionColor(gui.selectionBorderColor) == null) {
            LOGGER.warn("{} gui.selectionBorderColor={} 非法，已回退为白色 §f",
                    ModConstants.LOG_PREFIX, gui.selectionBorderColor);
            gui.selectionBorderColor = "§f";
        }

        if (gui.selectionDimAlpha < 0 || gui.selectionDimAlpha > 255) {
            LOGGER.warn("{} gui.selectionDimAlpha={} 超出 0~255，已夹紧", ModConstants.LOG_PREFIX, gui.selectionDimAlpha);
            gui.selectionDimAlpha = Math.max(0, Math.min(255, gui.selectionDimAlpha));
        }

        if (com.haojing.battlepass.common.gui.GuiColors.parseSectionColor(gui.tabActiveTextColor) == null) {
            LOGGER.warn("{} gui.tabActiveTextColor={} 非法，已回退为黄色 §e",
                    ModConstants.LOG_PREFIX, gui.tabActiveTextColor);
            gui.tabActiveTextColor = "§e";
        }

        if (gui.tabActiveOverlayAlpha < 0 || gui.tabActiveOverlayAlpha > 255) {
            LOGGER.warn("{} gui.tabActiveOverlayAlpha={} 超出 0~255，已夹紧",
                    ModConstants.LOG_PREFIX, gui.tabActiveOverlayAlpha);
            gui.tabActiveOverlayAlpha = Math.max(0, Math.min(255, gui.tabActiveOverlayAlpha));
        }

        if (gui.communityText == null) {
            gui.communityText = "支持社团：镐京方块协会";
        }

        if (com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.communityColor, null) == null) {
            LOGGER.warn("{} gui.communityColor={} 非法，已回退为浅灰 §7",
                    ModConstants.LOG_PREFIX, gui.communityColor);
            gui.communityColor = "§7";
        } else {
            gui.communityColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.communityColor, "§7");
        }

        if (gui.communityUrl == null) {
            gui.communityUrl = "";
        }

        if (gui.shopPriceColor == null
                || com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.shopPriceColor, null) == null) {
            LOGGER.warn("{} gui.shopPriceColor={} 非法，已回退为金色 §6",
                    ModConstants.LOG_PREFIX, gui.shopPriceColor);
            gui.shopPriceColor = "§6";
        } else {
            gui.shopPriceColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.shopPriceColor, "§6");
        }

        // 欢迎语颜色校验
        if (gui.welcomeColor == null
                || com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.welcomeColor, null) == null) {
            gui.welcomeColor = "§7";
        } else {
            gui.welcomeColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.welcomeColor, "§7");
        }
        if (gui.welcomePlayerColor == null
                || com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.welcomePlayerColor, null) == null) {
            gui.welcomePlayerColor = "§f";
        } else {
            gui.welcomePlayerColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(gui.welcomePlayerColor, "§f");
        }
        if (gui.welcomeText == null || gui.welcomeText.isBlank()) {
            gui.welcomeText = "欢迎您，{player}";
        }

        if (gui.shopGroupColors == null) {
            gui.shopGroupColors = new java.util.LinkedHashMap<>();
        } else {
            java.util.Map<String, String> cleaned = new java.util.LinkedHashMap<>();

            for (java.util.Map.Entry<String, String> e : gui.shopGroupColors.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }

                String normalized = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(e.getValue(), null);

                if (normalized == null) {
                    LOGGER.warn("{} gui.shopGroupColors.{} 颜色代码 {} 非法，该分组回退白色",
                            ModConstants.LOG_PREFIX, e.getKey(), e.getValue());
                    continue;
                }

                cleaned.put(e.getKey().trim().toLowerCase(java.util.Locale.ROOT), normalized);
            }

            gui.shopGroupColors = cleaned;
        }
    }

    /**
     * 校验经验曲线参数。
     *
     * <p>为什么要专门挡住"基础值与递增量都为 0"：那会让每一级所需经验都等于 0，
     * 升级循环将无法推进（或反过来瞬间满级）。这是一个手滑就能造出来的死配置，
     * 因此直接回退成默认曲线并明确告警，而不是让它悄悄生效。
     */
    private void validateLevelCurve(SeasonConfig value) {
        if (value.xpPerLevelBase < 0) {
            LOGGER.warn("{} xpPerLevelBase={} 非法，已回退为 0", ModConstants.LOG_PREFIX, value.xpPerLevelBase);
            value.xpPerLevelBase = 0;
        }

        if (value.xpPerLevelStep < 0) {
            LOGGER.warn("{} xpPerLevelStep={} 非法，已回退为 0", ModConstants.LOG_PREFIX, value.xpPerLevelStep);
            value.xpPerLevelStep = 0;
        }

        if (value.xpPerLevelBase == 0 && value.xpPerLevelStep == 0) {
            LOGGER.warn("{} 经验曲线的基础值与递增量同时为 0，每级所需经验会是 0（无法升级），已回退为默认曲线 50 + 10×(等级-1)",
                    ModConstants.LOG_PREFIX);
            value.xpPerLevelBase = 50;
            value.xpPerLevelStep = 10;
        }
    }

    /**
     * 规范化命令白名单：去空白、剥掉前导斜杠、按大小写不敏感去重、丢弃空项。
     *
     * <p>为什么要做规范化：命令根是否命中白名单是字符串比较，而管理员写在白名单里的
     * "Give" 与写在奖励里的 "give" 应当视为同一条。不统一大小写就会出现
     * "白名单里明明加了却仍被判为不允许执行"。
     */
    private List<String> normalizeCommandWhitelist(List<String> raw) {
        List<String> normalized = new ArrayList<>();

        if (raw == null) {
            return normalized;
        }

        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }

            String name = entry.trim();

            while (name.startsWith("/")) {
                name = name.substring(1).trim();
            }

            if (name.isEmpty()) {
                continue;
            }

            // 取第一段作为命令根："give @s diamond" 这种连参数一起写进来的也能正确识别。
            int space = name.indexOf(' ');
            String root = space > 0 ? name.substring(0, space) : name;

            boolean duplicate = normalized.stream().anyMatch(existing -> existing.equalsIgnoreCase(root));

            if (!duplicate) {
                normalized.add(root);
            }
        }

        if (normalized.isEmpty()) {
            // 空白名单等于"禁止一切 COMMAND 奖励"。这可能是管理员的真实意图（比如他不想用命令奖励），
            // 因此只提示不修改，避免把管理员的安全收紧动作当成错误改回去。
            LOGGER.warn("{} 命令白名单为空：所有 COMMAND 类奖励都会被拒绝执行", ModConstants.LOG_PREFIX);
        }

        return normalized;
    }

    private void validateLongNight(LongNightConfig value) {
        value.start = formatTime(TimeUtil.parseTime(value.start, LocalTime.of(0, 0)));
        value.end = formatTime(TimeUtil.parseTime(value.end, LocalTime.of(6, 0)));

        if (value.start.equals(value.end)) {
            // 不擅自改管理员的开关，只把后果说清楚：按 TimeUtil.isWithin 的约定，起止相同视为空时段。
            LOGGER.warn("{} 长夜的 start 与 end 相同（{}），该时段长度为 0，长夜将永不生效",
                    ModConstants.LOG_PREFIX, value.start);
        }

        if (value.xpMultiplier < MIN_XP_MULTIPLIER || value.xpMultiplier > MAX_XP_MULTIPLIER) {
            double clamped = Math.max(MIN_XP_MULTIPLIER, Math.min(value.xpMultiplier, MAX_XP_MULTIPLIER));
            LOGGER.warn("{} longNight.xpMultiplier={} 超出 {}~{}，已夹紧为 {}",
                    ModConstants.LOG_PREFIX, value.xpMultiplier, MIN_XP_MULTIPLIER, MAX_XP_MULTIPLIER, clamped);
            value.xpMultiplier = clamped;
        }

        value.fatigueSlownessAmplifier = clampAmplifier(value.fatigueSlownessAmplifier, "fatigueSlownessAmplifier");
        value.fatigueMiningFatigueAmplifier = clampAmplifier(value.fatigueMiningFatigueAmplifier, "fatigueMiningFatigueAmplifier");

        value.adminWhitelist = normalizeWhitelist(value.adminWhitelist);
    }

    private int clampAmplifier(int value, String fieldName) {
        if (value < 0 || value > MAX_AMPLIFIER) {
            int clamped = Math.max(0, Math.min(value, MAX_AMPLIFIER));
            LOGGER.warn("{} longNight.{}={} 超出 0~{}，已夹紧为 {}",
                    ModConstants.LOG_PREFIX, fieldName, value, MAX_AMPLIFIER, clamped);
            return clamped;
        }

        return value;
    }

    /**
     * 规范化白名单：剔除非法 UUID、统一小写、去重。
     *
     * <p>为什么要规范化而不只是过滤：管理员手抄 UUID 时经常带大写或多余空格，
     * 而 UUID.toString() 永远是小写。不统一的话，白名单会"看起来配了但不生效"。
     */
    private List<String> normalizeWhitelist(List<String> raw) {
        Set<String> normalized = new LinkedHashSet<>();

        if (raw == null) {
            return new ArrayList<>(normalized);
        }

        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }

            try {
                normalized.add(UUID.fromString(entry.trim()).toString());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("{} 长夜白名单中的条目不是合法 UUID，已忽略：{}", ModConstants.LOG_PREFIX, entry);
            }
        }

        return new ArrayList<>(normalized);
    }

    private static String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /** @return 配置文件路径，便于日志与 GUI 展示。 */
    public Path configFile() {
        return paths.seasonConfigFile();
    }

    /** @return 配置是否已从磁盘加载过。 */
    public boolean isLoaded() {
        return config != null;
    }

    /** @return 配置文件当前是否存在于磁盘上（用于诊断）。 */
    public boolean configFileExists() {
        return Files.isRegularFile(paths.seasonConfigFile());
    }
}
