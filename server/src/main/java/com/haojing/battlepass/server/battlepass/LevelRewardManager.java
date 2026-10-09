package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.FileChangeDetector;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用途：等级奖励表的加载、保存与热重载，对应 config/haojing_battlepass/rewards.json。
 *
 * <p>为什么与任务池用完全相同的三段式（首次落地默认文件 / 加载后回写一次 / 变更检测热重载）：
 * 这三个坑（看不到默认格式、规范化结果不落盘、自己回写被当成外部改动而反复重载）
 * 在任务池上已经踩过并解决，这里直接复用同一套做法与同一个 {@link FileChangeDetector}，
 * 不再重新发明一遍。
 *
 * <p>为什么校验要传入当前 maxLevel：配置里的等级上限是可改的（例如赛季缩短成 20 级），
 * 奖励表里超出上限的条目必须被丢弃，否则会存在"永远发不出去的死配置"，
 * 管理员在面板上也会看到不存在的等级。
 */
public final class LevelRewardManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认奖励表的资源路径（位于 server 子工程的 resources 下）。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_rewards.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final ConfigManager configManager;
    private final FileChangeDetector changeDetector = new FileChangeDetector("等级奖励表");

    /** 当前生效的奖励表。用 volatile：热重载会替换引用，读取方可能在任何线程。 */
    private volatile LevelRewardTable table;

    public LevelRewardManager(StoragePaths paths, JsonStore jsonStore, ConfigManager configManager) {
        this.paths = paths;
        this.jsonStore = jsonStore;
        this.configManager = configManager;
    }

    /** @return 当前生效的奖励表；未加载时为 null。 */
    public LevelRewardTable table() {
        return table;
    }

    /** @return 奖励表配置文件路径。 */
    public Path configFile() {
        return paths.rewardsConfigFile();
    }

    /**
     * 从磁盘加载奖励表，必要时先生成默认表。
     *
     * @return 加载并校验后的奖励表（永不为 null）
     */
    public LevelRewardTable load() {
        Path file = paths.rewardsConfigFile();
        copyBundledDefaultIfMissing(file);

        LevelRewardTable loaded = jsonStore.read(file, LevelRewardTable.class, () -> {
            LOGGER.error("{} 等级奖励表缺失或损坏，已退回空表 —— 在补齐配置前升级不会发放任何等级奖励（星币照发）：{}",
                    ModConstants.LOG_PREFIX, file);
            return new LevelRewardTable();
        });

        loaded.validate(currentMaxLevel());
        table = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 等级奖励表已加载：{} 个等级配有奖励（等级上限 {}）",
                ModConstants.LOG_PREFIX, loaded.configuredLevelCount(), currentMaxLevel());

        return loaded;
    }

    /** 把当前内存中的奖励表写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        LevelRewardTable current = table;

        if (current == null) {
            LOGGER.warn("{} 等级奖励表尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate(currentMaxLevel());
        writeQuietly(current);
    }

    /**
     * 热重载奖励表（需求文档 §2：管理员在 GUI 修改的所有配置支持热重载）。
     *
     * @return 重载后的奖励表
     */
    public LevelRewardTable reload() {
        LOGGER.info("{} 正在热重载等级奖励表：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若奖励表文件被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.rewardsConfigFile();

        if (!changeDetector.hasChanged(file)) {
            return false;
        }

        reload();
        changeDetector.reset(file);
        return true;
    }

    private int currentMaxLevel() {
        SeasonConfig config = configManager.config();
        return config == null || config.maxLevel < 1 ? 30 : config.maxLevel;
    }

    private void copyBundledDefaultIfMissing(Path file) {
        if (Files.isRegularFile(file)) {
            return;
        }

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            try (InputStream in = LevelRewardManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认等级奖励表资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认奖励表生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认等级奖励表失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(LevelRewardTable value) {
        try {
            jsonStore.write(paths.rewardsConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入等级奖励表失败（内存中的奖励表仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.rewardsConfigFile(), e);
        }
    }
}
