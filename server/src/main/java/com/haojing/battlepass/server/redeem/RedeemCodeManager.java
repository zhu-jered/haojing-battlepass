package com.haojing.battlepass.server.redeem;

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
 * 用途：口令清单的加载、保存与热重载，对应 config/haojing_battlepass/codes.json。
 *
 * <p>做法与其它配置管理器一致。口令尤其需要热重载：管理员经常在活动开始前几秒
 * 才把口令配好（§10 要求"生效时间段可配"），若要重启服务端才能生效就太晚了。
 */
public final class RedeemCodeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认口令的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_codes.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("节日口令");

    private volatile RedeemCodePool pool;

    public RedeemCodeManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的口令清单；未加载时为 null。 */
    public RedeemCodePool pool() {
        return pool;
    }

    /** @return 配置文件路径。 */
    public Path configFile() {
        return paths.codesConfigFile();
    }

    /** 从磁盘加载口令清单，必要时先生成默认文件。 */
    public RedeemCodePool load() {
        Path file = paths.codesConfigFile();
        copyBundledDefaultIfMissing(file);

        RedeemCodePool loaded = jsonStore.read(file, RedeemCodePool.class, RedeemCodePool::new);
        loaded.validate();
        pool = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 节日口令已加载：{} 条", ModConstants.LOG_PREFIX, loaded.size());
        return loaded;
    }

    /** 把当前内存中的口令清单写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        RedeemCodePool current = pool;

        if (current == null) {
            LOGGER.warn("{} 口令清单尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /**
     * 热重载口令清单。
     *
     * @return 重载后的清单
     */
    public RedeemCodePool reload() {
        LOGGER.info("{} 正在热重载节日口令：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.codesConfigFile();

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

            try (InputStream in = RedeemCodeManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认口令资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认口令生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认口令配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(RedeemCodePool value) {
        try {
            jsonStore.write(paths.codesConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入口令配置失败（内存中的清单仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.codesConfigFile(), e);
        }
    }
}
