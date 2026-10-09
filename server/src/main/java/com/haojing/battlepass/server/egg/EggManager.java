package com.haojing.battlepass.server.egg;

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
 * 用途：彩蛋池的加载、保存与热重载，对应 config/haojing_battlepass/eggs.json。
 *
 * <p>做法与任务池、等级奖励表、商店完全一致（首次落地默认文件 / 加载后回写一次 /
 * 变更检测热重载 / 丢弃非法项）—— 需求文档 §6 明确要求彩蛋"管理员 GUI 支持增删改与
 * 启用开关，热重载"，这条链路上任何一处偷懒都会让管理员改完不生效。
 */
public final class EggManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认彩蛋池的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_eggs.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("彩蛋池");

    private volatile EggPool pool;

    public EggManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的彩蛋池；未加载时为 null。 */
    public EggPool pool() {
        return pool;
    }

    /** @return 彩蛋配置文件路径。 */
    public Path configFile() {
        return paths.eggsConfigFile();
    }

    /** 从磁盘加载彩蛋池，必要时先生成默认文件。 */
    public EggPool load() {
        Path file = paths.eggsConfigFile();
        copyBundledDefaultIfMissing(file);

        EggPool loaded = jsonStore.read(file, EggPool.class, EggPool::new);
        loaded.validate();
        pool = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 彩蛋池已加载：{} 个（{}），轻量彩蛋：关键词 {} 条 / 节日 {} 个",
                ModConstants.LOG_PREFIX, loaded.size(), loaded.describeCategories(),
                loaded.light.chatKeywords.size(), loaded.light.festivals.size());

        return loaded;
    }

    /** 把当前内存中的彩蛋池写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        EggPool current = pool;

        if (current == null) {
            LOGGER.warn("{} 彩蛋池尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /**
     * 热重载彩蛋池（需求文档 §6：管理面板可增删改并热重载）。
     *
     * @return 重载后的彩蛋池
     */
    public EggPool reload() {
        LOGGER.info("{} 正在热重载彩蛋池：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若彩蛋配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.eggsConfigFile();

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

            try (InputStream in = EggManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认彩蛋池资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认彩蛋池生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认彩蛋池失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(EggPool value) {
        try {
            jsonStore.write(paths.eggsConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入彩蛋池失败（内存中的彩蛋池仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.eggsConfigFile(), e);
        }
    }
}
