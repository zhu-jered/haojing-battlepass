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
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
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
    /** capeId -> 上次读到该贴图时的 (修改时间, 字节长度)，用于判断贴图有没有被换过。 */
    private final Map<String, long[]> textureBaseline = new HashMap<>();
    /** 贴图版本号：每检测到一次外部改动 +1；调用方比较前后值即可知道"要不要重新下发贴图"。 */
    private volatile long textureVersion;

    public CapeManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前贴图版本号（每次检测到贴图改动都会 +1）。 */
    public long textureVersion() {
        return textureVersion;
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

    /**
     * 文件变化时自动重载（配置文件**或**贴图文件）。
     *
     * @return 配置是否被重载（仅贴图变化时返回 false；贴图仍会重新读入内存，并按需递增
     *         {@link #textureVersion()}）
     */
    public boolean reloadIfChanged() {
        Path file = paths.capesConfigFile();
        boolean regenerated = changeDetector.hasChanged(file);

        if (regenerated) {
            reload();
            changeDetector.reset(file);
            return true;
        }

        // 配置没变也要看贴图：管理员常常只换 PNG 而不动 capes.json。
        reloadTextures();
        return false;
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

    /**
     * 重新读取所有披风贴图字节到内存。
     *
     * <p>每次都重读：贴图只有几十 KB，读盘开销远小于"漏检一次换图"的代价；
     * 但是否**重新下发**由内容比对决定（{@link #textureBaseline}），
     * 没变就不打扰客户端，避免每秒刷一遍贴图包。
     *
     * <p>发现贴图内容变化时会把 {@link #textureVersion} 加一；
     * 调用方（服务端维护线程）比较前后版本号，决定是否把新贴图重新推给在线客户端。
     */
    private void reloadTextures() {
        int changedCount = 0;

        synchronized (textureCache) {
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

            // 本次扫描到的全部条目：先收集，最后整体替换内存表。
            Map<String, byte[]> loaded = new HashMap<>();
            Map<String, long[]> baseline = new HashMap<>();

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
                    FileTime modified = Files.getLastModifiedTime(png);
                    byte[] bytes = Files.readAllBytes(png);
                    loaded.put(def.id, bytes);
                    baseline.put(def.id, new long[]{modified.toMillis(), bytes.length});

                    if (hasTextureChanged(def.id, modified.toMillis(), bytes)) {
                        changedCount++;
                        LOGGER.info("{} 检测到披风 {} 的贴图已更新（{} 字节），将重新下发给在线客户端",
                                ModConstants.LOG_PREFIX, def.id, bytes.length);
                    }
                } catch (IOException e) {
                    LOGGER.warn("{} 读取披风贴图失败 {}：{}", ModConstants.LOG_PREFIX, def.id, e.toString());
                }
            }

            // 有贴图从"存在"变成"缺失"时也要通知（客户端应回退原版披风）。
            boolean removed = textureCache.keySet().stream().anyMatch(id -> !loaded.containsKey(id));

            textureCache.clear();
            textureCache.putAll(loaded);
            textureBaseline.clear();
            textureBaseline.putAll(baseline);

            if (changedCount > 0 || removed) {
                textureVersion++;
            }
        }
    }

    /**
     * 判断某件披风的贴图内容相对上次扫描是否发生了变化。
     *
     * <p>为什么不用"文件修改时间"一个判据：同秒内覆盖文件（脚本写入很常见）时间戳可能不变，
     * 因此同时比对字节长度；两者任一不同即视为变化。首次扫描时基线为空，不算变化。
     *
     * @param capeId  披风 ID
     * @param modified 文件修改时间（毫秒）
     * @param bytes   读到的字节
     * @return 是否变化
     */
    private boolean hasTextureChanged(String capeId, long modified, byte[] bytes) {
        long[] previous = textureBaseline.get(capeId);
        if (previous == null) {
            return false;
        }
        if (previous[0] != modified || previous[1] != bytes.length) {
            return true;
        }
        byte[] cached = textureCache.get(capeId);
        return cached == null || !Arrays.equals(cached, bytes);
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
