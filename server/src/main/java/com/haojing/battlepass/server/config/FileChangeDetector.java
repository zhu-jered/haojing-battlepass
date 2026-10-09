package com.haojing.battlepass.server.config;

import com.haojing.battlepass.common.ModConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用途：检测配置文件是否被外部改动过，供热重载使用。
 *
 * <p>为什么单独抽出来：这套逻辑有个非常容易写错的地方 —— 加载完成后程序通常会
 * **回写一次文件**（规范化取值、自愈损坏内容），如果不把回写之后的时间重新记为基线，
 * 下一次检测就会把自己刚写的内容当成外部改动，于是每一轮都重载一次，
 * 表现为日志被刷屏、磁盘被反复写，而且很难看出是这个原因。
 *
 * <p>抽成一个类之后，赛季配置、任务池、彩蛋池共用同一份正确实现，
 * 不会出现"每新增一个热重载配置文件又踩一次同样的坑"。
 */
public final class FileChangeDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final String label;
    private volatile long baseline;

    /**
     * @param label 用于日志的配置名（例如"赛季配置"）
     */
    public FileChangeDetector(String label) {
        this.label = label;
    }

    /**
     * 把文件当前的修改时间记为基线。应在**加载并回写完成之后**调用。
     *
     * @param file 配置文件
     */
    public void reset(Path file) {
        baseline = modifiedTime(file);
    }

    /**
     * 判断文件是否被外部改动过。
     *
     * <p>注意本方法会同时把基线推进到最新值，因此调用方在检测到变更并完成重载后，
     * **必须再调用一次 {@link #reset(Path)}** —— 因为重载过程会回写文件。
     *
     * @param file 配置文件
     * @return 是否发生了外部改动
     */
    public boolean hasChanged(Path file) {
        long modified = modifiedTime(file);

        if (modified == 0L) {
            return false;
        }

        if (baseline == 0L) {
            // 首次观察，只建立基线，不算改动。
            baseline = modified;
            return false;
        }

        if (modified == baseline) {
            return false;
        }

        baseline = modified;
        return true;
    }

    private long modifiedTime(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            LOGGER.warn("{} 读取{}的修改时间失败：{}", ModConstants.LOG_PREFIX, label, e.toString());
            return 0L;
        }
    }
}
