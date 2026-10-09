package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.config.FileChangeDetector;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用途：随机事件配置的加载、保存与热重载，对应
 * config/haojing_battlepass/events.json。
 *
 * <p>做法与其它配置管理器一致（§9 要求世界随机事件可在管理面板编辑并热重载）。
 * 这里额外注意一点：热重载时**正在进行的事件不受影响** ——
 * 事件一旦开始就跑完自己的时长（否则管理员改一下配置，玩家会觉得事件凭空消失了）。
 * 这条语义由 {@code RandomEventService} 保证：它持有的是事件定义的引用，
 * 重载只替换清单，不会打断进行中的那一场。
 */
public final class RandomEventManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认事件配置的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_events.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("随机事件配置");

    private volatile RandomEventConfig config;

    public RandomEventManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的配置；未加载时为 null。 */
    public RandomEventConfig config() {
        return config;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.eventsConfigFile();
    }

    /** 从磁盘加载配置，必要时先生成默认文件。 */
    public RandomEventConfig load() {
        Path file = paths.eventsConfigFile();
        copyBundledDefaultIfMissing(file);

        RandomEventConfig loaded = jsonStore.read(file, RandomEventConfig.class, RandomEventConfig::new);
        loaded.validate();
        config = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 随机事件已加载：{} 种（判定间隔 {} 分钟 / 概率 {} / 最小间隔 {} 分钟）",
                ModConstants.LOG_PREFIX, loaded.size(), loaded.checkIntervalMinutes,
                loaded.rollChance, loaded.minIntervalMinutes);

        return loaded;
    }

    /** 把当前内存中的配置写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        RandomEventConfig current = config;

        if (current == null) {
            LOGGER.warn("{} 随机事件配置尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /**
     * 热重载随机事件配置。
     *
     * @return 重载后的配置
     */
    public RandomEventConfig reload() {
        LOGGER.info("{} 正在热重载随机事件配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.eventsConfigFile();

        if (!changeDetector.hasChanged(file)) {
            return false;
        }

        reload();
        changeDetector.reset(file);
        return true;
    }

    private void copyBundledDefaultIfMissing(Path file) {
        if (Files.isRegularFile(file)) {
            return;
        }

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            try (InputStream in = RandomEventManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认随机事件资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认随机事件生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认随机事件配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(RandomEventConfig value) {
        try {
            jsonStore.write(paths.eventsConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入随机事件配置失败（内存中的配置仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.eventsConfigFile(), e);
        }
    }
}
