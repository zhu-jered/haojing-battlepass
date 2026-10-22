package com.haojing.battlepass.server.checkin;

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
 * 用途：签到奖励配置的加载、保存与热重载，对应 config/haojing_battlepass/checkin.json。
 *
 * <p>装配范式与 {@code RedeemCodeManager} / {@code EggManager} 一致：内置默认资源兜底、
 * 读盘后 {@code validate()} 过滤非法项、写回一份规范化文件、用 {@link FileChangeDetector}
 * 做外部改动检测。管理员调整每日奖励或里程碑后无需重启，下一轮维护循环自动生效。
 */
public final class CheckinManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认签到配置的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_checkin.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("每日签到");

    private volatile CheckinPool pool;

    public CheckinManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的签到配置；未加载时为 null。 */
    public CheckinPool pool() {
        return pool;
    }

    /** @return 签到功能是否启用（配置缺失时按关闭处理，避免无配置时白送奖励）。 */
    public boolean enabled() {
        CheckinPool current = pool;
        return current != null && current.enabled;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.checkinConfigFile();
    }

    /** 从磁盘加载签到配置，必要时先生成默认文件。 */
    public CheckinPool load() {
        Path file = paths.checkinConfigFile();
        copyBundledDefaultIfMissing(file);

        CheckinPool loaded = jsonStore.read(file, CheckinPool.class, CheckinPool::new);
        loaded.validate();
        pool = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 每日签到配置已加载：每日档位={} 里程碑={}",
                ModConstants.LOG_PREFIX, loaded.dailyRewards.size(), loaded.milestones().size());
        return loaded;
    }

    /** 把当前内存中的签到配置写回磁盘。 */
    public void save() {
        CheckinPool current = pool;

        if (current == null) {
            LOGGER.warn("{} 签到配置尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /** 热重载签到配置。 */
    public CheckinPool reload() {
        LOGGER.info("{} 正在热重载每日签到配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.checkinConfigFile();

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

            try (InputStream in = CheckinManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认签到配置资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认签到配置生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认签到配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(CheckinPool value) {
        try {
            jsonStore.write(paths.checkinConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入签到配置失败（内存中的配置仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.checkinConfigFile(), e);
        }
    }
}
