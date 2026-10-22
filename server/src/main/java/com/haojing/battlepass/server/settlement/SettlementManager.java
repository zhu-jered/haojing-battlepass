package com.haojing.battlepass.server.settlement;

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
 * 用途：赛季结算奖励配置的加载、保存与热重载，对应 config/haojing_battlepass/settlement.json。
 *
 * <p>装配范式与 {@code CheckinManager} 一致：内置默认资源兜底、读盘后 {@code validate()}
 * 过滤非法项、写回一份规范化文件、用 {@link FileChangeDetector} 做外部改动检测。
 * 管理员调整结算档位后无需重启，下一轮轮换立即按新配置结算。
 */
public final class SettlementManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认结算配置的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_settlement.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("赛季结算");

    private volatile SettlementPool pool;

    public SettlementManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的结算配置；未加载时为 null。 */
    public SettlementPool pool() {
        return pool;
    }

    /** @return 结算功能是否启用（配置缺失时按关闭处理，避免无配置时白送奖励）。 */
    public boolean enabled() {
        SettlementPool current = pool;
        return current != null && current.enabled;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.settlementConfigFile();
    }

    /** 从磁盘加载结算配置，必要时先生成默认文件。 */
    public SettlementPool load() {
        Path file = paths.settlementConfigFile();
        copyBundledDefaultIfMissing(file);

        SettlementPool loaded = jsonStore.read(file, SettlementPool.class, SettlementPool::new);
        loaded.validate();
        pool = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 赛季结算配置已加载：档位={}", ModConstants.LOG_PREFIX, loaded.tiers().size());
        return loaded;
    }

    /** 把当前内存中的结算配置写回磁盘。 */
    public void save() {
        SettlementPool current = pool;

        if (current == null) {
            LOGGER.warn("{} 结算配置尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /** 热重载结算配置。 */
    public SettlementPool reload() {
        LOGGER.info("{} 正在热重载赛季结算配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.settlementConfigFile();

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

            try (InputStream in = SettlementManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认结算配置资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认结算配置生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认结算配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(SettlementPool value) {
        try {
            jsonStore.write(paths.settlementConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入结算配置失败（内存中的配置仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.settlementConfigFile(), e);
        }
    }
}
