package com.haojing.battlepass.server.task;

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
 * 用途：任务池配置的加载、保存与热重载，对应 config/haojing_battlepass/daily_tasks.json。
 *
 * <p>为什么首次运行要"从内置资源复制一份出来"而不是直接在代码里构造默认池：
 * §5.1 要求每组池规模 12 个、§5.4 要求每周数量可调，这些内容必须能被管理员在管理面板里
 * 增删改（§9）。把默认池作为 jar 内的资源随模组分发、首次运行落地成可见的配置文件，
 * 管理员就有了一份带完整示例、可直接编辑的文件，而不是对着空文件猜格式。
 *
 * <p>为什么加载后必定回写一次：与赛季配置同理 ——
 * ① 让规范化后的结果落盘（管理员看到的就是模组真正在用的值）；
 * ② 配置被改坏时，回滚/丢弃的结果会写回，避免每次启动重复告警。
 */
public final class TaskPoolManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认任务池的资源路径（位于 server 子工程的 resources 下）。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_daily_tasks.json";

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("任务池配置");

    /** 当前生效任务池。用 volatile：热重载会替换引用。 */
    private volatile TaskPool pool;

    public TaskPoolManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前生效的任务池；未加载时为 null。 */
    public TaskPool pool() {
        return pool;
    }

    /** @return 任务池配置文件路径。 */
    public Path configFile() {
        return paths.dailyTasksConfigFile();
    }

    /**
     * 从磁盘加载任务池，必要时先生成默认池。
     *
     * @return 加载并校验后的任务池（永不为 null）
     */
    public TaskPool load() {
        Path file = paths.dailyTasksConfigFile();
        copyBundledDefaultIfMissing(file);

        TaskPool loaded = jsonStore.read(file, TaskPool.class, () -> {
            LOGGER.error("{} 任务池配置缺失或损坏，已退回空池 —— 在补齐配置前不会抽出任何任务：{}",
                    ModConstants.LOG_PREFIX, file);
            return new TaskPool();
        });

        loaded.validate();
        pool = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 任务池已加载：每日 {} 个（探险 {}/建造 {}/综合 {}），每周 {} 个（抽取 {}）",
                ModConstants.LOG_PREFIX, loaded.dailyPoolSize(),
                loaded.group("explore").size(), loaded.group("build").size(), loaded.group("general").size(),
                loaded.allWeekly().size(), Math.min(loaded.weeklyCount, loaded.allWeekly().size()));

        return loaded;
    }

    /** 把当前内存中的任务池写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        TaskPool current = pool;

        if (current == null) {
            LOGGER.warn("{} 任务池尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /**
     * 热重载任务池（需求文档 §2：管理员在 GUI 修改的所有配置支持热重载，无需重启）。
     *
     * @return 重载后的任务池
     */
    public TaskPool reload() {
        LOGGER.info("{} 正在热重载任务池配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若任务池配置文件被外部改动过，则自动热重载。理由与赛季配置相同（需求文档 §16）。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.dailyTasksConfigFile();

        if (!changeDetector.hasChanged(file)) {
            return false;
        }

        reload();
        // load/reload 内部会回写文件，基线必须重新取，否则下一轮又会"检测到变更"。
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

            try (InputStream in = TaskPoolManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认任务池资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认任务池生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认任务池配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(TaskPool value) {
        try {
            jsonStore.write(paths.dailyTasksConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            // 配置写不进去不应让服务端崩：内存中的任务池依然有效，本次运行照常。
            LOGGER.error("{} 写入任务池配置失败（内存中的任务池仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.dailyTasksConfigFile(), e);
        }
    }
}
