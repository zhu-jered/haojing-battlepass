package com.haojing.battlepass.server.title;

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
 * 用途：称号配置的加载 / 热重载。与 ShopManager 同模式：首次启动从内置默认资源生成
 * titles.json，之后管理员手改即热重载。
 */
public final class TitleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_titles.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("称号配置");

    private volatile TitleCatalog catalog;

    public TitleManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的称号目录；未加载时为 null。 */
    public TitleCatalog catalog() {
        return catalog;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.titlesConfigFile();
    }

    /** 从磁盘加载；缺失则从内置默认生成。 */
    public TitleCatalog load() {
        Path file = paths.titlesConfigFile();
        copyBundledDefaultIfMissing(file);

        TitleCatalog loaded = jsonStore.read(file, TitleCatalog.class, () -> {
            LOGGER.error("{} 称号配置缺失或损坏，已退回空目录：{}", ModConstants.LOG_PREFIX, file);
            return new TitleCatalog();
        });

        loaded.validate();
        catalog = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 称号目录已加载：{} 个称号", ModConstants.LOG_PREFIX, loaded.size());
        return loaded;
    }

    /** 热重载。 */
    public TitleCatalog reload() {
        LOGGER.info("{} 正在热重载称号配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /** 文件变化时自动重载。 */
    public boolean reloadIfChanged() {
        Path file = paths.titlesConfigFile();
        if (!changeDetector.hasChanged(file)) {
            return false;
        }
        reload();
        changeDetector.reset(file);
        return true;
    }

    /** @return 按 id 查定义；无目录/不存在时返回 null。 */
    public TitleDefinition byId(String id) {
        TitleCatalog current = catalog;
        return current == null ? null : current.byId(id);
    }

    private void copyBundledDefaultIfMissing(Path file) {
        if (Files.isRegularFile(file)) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (InputStream in = TitleManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认称号资源不存在（{}）", ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }
                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认称号生成配置文件：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认称号配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(TitleCatalog value) {
        try {
            jsonStore.write(paths.titlesConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入称号配置失败（内存目录仍生效）：{}", ModConstants.LOG_PREFIX, paths.titlesConfigFile(), e);
        }
    }
}
