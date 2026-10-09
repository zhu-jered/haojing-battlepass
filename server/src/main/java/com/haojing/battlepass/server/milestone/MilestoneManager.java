package com.haojing.battlepass.server.milestone;

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
 * 用途：全服里程碑清单的加载、保存与热重载，对应
 * config/haojing_battlepass/milestones.json。
 *
 * <p>做法与其它配置管理器一致（§9 要求管理面板里的九类可编辑项全部热重载）。
 */
public final class MilestoneManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认里程碑的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_milestones.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("全服里程碑");

    private volatile MilestoneTable table;

    public MilestoneManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的里程碑清单；未加载时为 null。 */
    public MilestoneTable table() {
        return table;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.milestonesConfigFile();
    }

    /** 从磁盘加载清单，必要时先生成默认文件。 */
    public MilestoneTable load() {
        Path file = paths.milestonesConfigFile();
        copyBundledDefaultIfMissing(file);

        MilestoneTable loaded = jsonStore.read(file, MilestoneTable.class, MilestoneTable::new);
        loaded.validate();
        table = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 全服里程碑已加载：{} 条", ModConstants.LOG_PREFIX, loaded.size());
        return loaded;
    }

    /** 把当前内存中的清单写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        MilestoneTable current = table;

        if (current == null) {
            LOGGER.warn("{} 里程碑尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /**
     * 热重载里程碑清单。
     *
     * @return 重载后的清单
     */
    public MilestoneTable reload() {
        LOGGER.info("{} 正在热重载全服里程碑：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.milestonesConfigFile();

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

            try (InputStream in = MilestoneManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认里程碑资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认里程碑生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认里程碑配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(MilestoneTable value) {
        try {
            jsonStore.write(paths.milestonesConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入里程碑配置失败（内存中的清单仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.milestonesConfigFile(), e);
        }
    }
}
