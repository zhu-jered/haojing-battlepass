package com.haojing.battlepass.server.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.haojing.battlepass.common.ModConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

/**
 * 用途：JSON 文件的原子读写与损坏自愈。需求文档 §12：所有 JSON 含 schemaVersion；
 * 原子写（写临时文件 + rename）并保留上一份 .bak；启动时校验，损坏则回滚 .bak 并记 ERROR。
 *
 * <p>为什么不用简单的 Files.write：直接覆写正式文件时，一旦写到一半断电或进程被杀，
 * 留下的就是半截 JSON，下一次启动直接丢数据。这里先把内容写进 .tmp 并 fsync 落盘，
 * 再把上一份正式文件留成 .bak，最后用 rename 原子替换 —— rename 在同一文件系统上是
 * 原子的，所以正式文件要么是旧的完整内容，要么是新的完整内容，不存在"半截"状态。
 *
 * <p>为什么读取要回退到 .bak：需求文档 §14 要求"JSON 读写异常捕获 + 错误日志 + .bak 回滚"。
 * 回滚后立刻把好数据写回主文件，避免下次启动又走一遍回滚流程。
 *
 * <p>为什么这个类不引用任何 Minecraft 类型：这样它可以被普通单元测试直接覆盖
 * （见 server/src/test 下的 JsonStoreTest），而不必启动服务端。
 */
public final class JsonStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /**
     * 为什么关掉 HTML 转义：默认配置会把中文等非 ASCII 字符写成 \\uXXXX，
     * 存档可读性极差且不利于人工排查。项目里所有 JSON 都只给本模组自己读写，没有注入风险。
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * 读取 JSON 文件；文件不存在时返回 {@code fallback} 提供的默认值。
     * 文件损坏时依次尝试 .bak，并在回滚后修复主文件。
     *
     * @param file     目标文件
     * @param type     目标类型
     * @param fallback 文件不存在或无法恢复时的默认值工厂
     * @param <T>      数据类型
     * @return 读取到的对象，或 fallback 的产物；本方法不抛异常
     */
    public <T> T read(Path file, Class<T> type, Supplier<T> fallback) {
        if (Files.isRegularFile(file)) {
            T value = parseQuietly(file, type, false);

            if (value != null) {
                return value;
            }

            // 主文件损坏，尝试 .bak
            Path backup = backupOf(file);

            if (Files.isRegularFile(backup)) {
                T restored = parseQuietly(backup, type, true);

                if (restored != null) {
                    LOGGER.error("{} 数据文件损坏，已从 .bak 回滚：{}", ModConstants.LOG_PREFIX, file);

                    try {
                        // 立刻修复主文件，避免下次启动重复回滚。
                        write(file, restored);
                    } catch (IOException e) {
                        LOGGER.error("{} 回滚后重写主文件失败（数据已在内存中，不影响本次运行）：{}",
                                ModConstants.LOG_PREFIX, file, e);
                    }

                    return restored;
                }

                LOGGER.error("{} 数据文件与 .bak 均损坏，无法回滚，将按默认值重建：{}",
                        ModConstants.LOG_PREFIX, file);
            } else {
                LOGGER.error("{} 数据文件损坏且没有可用 .bak，将按默认值重建：{}",
                        ModConstants.LOG_PREFIX, file);
            }
        }

        return fallback.get();
    }

    /**
     * 原子写入：临时文件 + fsync + rename，并在覆盖前保留上一份为 .bak。
     *
     * @param file  目标文件
     * @param value 待写入对象
     * @throws IOException 写盘失败
     */
    public void write(Path file, Object value) throws IOException {
        Path parent = file.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tmp = sibling(file, ".tmp");
        byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);

        // fsync 是必要的：否则 rename 可能在数据真正落盘前就完成，断电后会得到一个空文件。
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            channel.force(true);
        }

        // 先备份旧的正式文件，这样"上一份好数据"永远有一份留存。
        if (Files.isRegularFile(file)) {
            Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 某些网络盘/容器卷不支持原子移动，退化为普通替换并明确告警，不静默降级。
            LOGGER.warn("{} 文件系统不支持原子移动，已退化为普通替换：{}", ModConstants.LOG_PREFIX, file);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @param file 数据文件 @return 对应的 .bak 路径。 */
    public static Path backupOf(Path file) {
        return sibling(file, ".bak");
    }

    private static Path sibling(Path file, String suffix) {
        String name = file.getFileName().toString() + suffix;
        Path parent = file.getParent();
        return parent == null ? Paths.get(name) : parent.resolve(name);
    }

    /**
     * 尝试解析文件，任何异常都只记日志并返回 null，绝不向外抛 ——
     * 需求文档 §12 要求"单玩家读写失败不得影响其他玩家"，把异常收敛在这里是最省心的做法。
     *
     * @param file       目标文件
     * @param type       目标类型
     * @param fromBackup 是否在解析 .bak（仅用于日志措辞）
     */
    private <T> T parseQuietly(Path file, Class<T> type, boolean fromBackup) {
        String label = fromBackup ? ".bak" : "数据文件";

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            T value = GSON.fromJson(json, type);

            if (value == null) {
                LOGGER.error("{} {} 内容为空：{}", ModConstants.LOG_PREFIX, label, file);
            }

            return value;
        } catch (IOException e) {
            LOGGER.error("{} 读取{}失败：{}", ModConstants.LOG_PREFIX, label, file, e);
        } catch (RuntimeException e) {
            // Gson 解析失败会抛 JsonSyntaxException / JsonIOException，两者都是 RuntimeException。
            LOGGER.error("{} 解析{}失败（JSON 格式错误）：{}", ModConstants.LOG_PREFIX, label, file, e);
        }

        return null;
    }
}
