package com.haojing.battlepass.server.cape;

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
import java.util.HashMap;
import java.util.Map;

/**
 * 用途：披风配置与贴图的加载 / 热重载。与 TitleManager 同模式：
 * 首次启动从内置默认资源生成 capes.json，之后管理员手改即热重载。
 *
 * <p>贴图字节缓存在内存里：下发披风时直接取 bytes，避免每次发都读盘。
 * 文件缺失的披风返回 null，下发时跳过该贴图（客户端回退原版披风）。
 */
public final class CapeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_capes.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("披风配置");

    private volatile CapeCatalog catalog;
    /** capeId -> PNG 原始字节；贴图缺失时该 id 不在表里。 */
    private final Map<String, byte[]> textureCache = new HashMap<>();

    public CapeManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前披风目录；未加载时为 null。 */
    public CapeCatalog catalog() {
        return catalog;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.capesConfigFile();
    }

    /** @return 贴图目录（管理员把 PNG 放这里）。 */
    public Path textureDir() {
        return paths.capesDir();
    }

    /** 从磁盘加载；缺失则从内置默认生成。同时重新读取所有贴图字节。 */
    public CapeCatalog load() {
        Path file = paths.capesConfigFile();
        copyBundledDefaultIfMissing(file);

        CapeCatalog loaded = jsonStore.read(file, CapeCatalog.class, () -> {
            LOGGER.error("{} 披风配置缺失或损坏，已退回空目录：{}", ModConstants.LOG_PREFIX, file);
            return new CapeCatalog();
        });

        loaded.validate();
        catalog = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        reloadTextures();

        LOGGER.info("{} 披风目录已加载：{} 个披风", ModConstants.LOG_PREFIX, loaded.size());
        return loaded;
    }

    /** 热重载。 */
    public CapeCatalog reload() {
        LOGGER.info("{} 正在热重载披风配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /** 文件变化时自动重载。 */
    public boolean reloadIfChanged() {
        Path file = paths.capesConfigFile();
        if (!changeDetector.hasChanged(file)) {
            return false;
        }
        reload();
        changeDetector.reset(file);
        return true;
    }

    /** @return 按 id 查定义；无目录/不存在时返回 null。 */
    public CapeDefinition byId(String id) {
        CapeCatalog current = catalog;
        return current == null ? null : current.byId(id);
    }

    /**
     * 取某披风的 PNG 字节。
     *
     * @param capeId 披风 ID
     * @return PNG 原始字节；贴图缺失或未加载时返回 null（调用方据此跳过下发）
     */
    public byte[] textureBytes(String capeId) {
        if (capeId == null) {
            return null;
        }
        synchronized (textureCache) {
            return textureCache.get(capeId);
        }
    }

    /** 重新读取所有披风贴图字节到内存。 */
    private void reloadTextures() {
        synchronized (textureCache) {
            textureCache.clear();
            CapeCatalog current = catalog;
            if (current == null) {
                return;
            }
            Path dir = paths.capesDir();
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.warn("{} 创建披风贴图目录失败：{}", ModConstants.LOG_PREFIX, dir, e);
                return;
            }
            for (CapeDefinition def : current.all()) {
                if (def.textureFile == null || def.textureFile.isBlank()) {
                    continue;
                }
                Path png = dir.resolve(def.textureFile);
                if (!Files.isRegularFile(png)) {
                    LOGGER.warn("{} 披风 {} 的贴图不存在：{}（将回退原版披风）",
                            ModConstants.LOG_PREFIX, def.id, png);
                    continue;
                }
                try {
                    byte[] bytes = Files.readAllBytes(png);
                    textureCache.put(def.id, bytes);
                } catch (IOException e) {
                    LOGGER.warn("{} 读取披风贴图失败 {}：{}", ModConstants.LOG_PREFIX, def.id, e.toString());
                }
            }
        }
    }

    private void copyBundledDefaultIfMissing(Path file) {
        if (Files.isRegularFile(file)) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (InputStream in = CapeManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认披风资源不存在（{}）", ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }
                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认披风生成配置文件：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认披风配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(CapeCatalog value) {
        try {
            jsonStore.write(paths.capesConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入披风配置失败（内存目录仍生效）：{}", ModConstants.LOG_PREFIX, paths.capesConfigFile(), e);
        }
    }
}
