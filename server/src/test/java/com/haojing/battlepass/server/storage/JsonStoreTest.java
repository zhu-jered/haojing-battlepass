package com.haojing.battlepass.server.storage;

import com.haojing.battlepass.common.data.GlobalData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证需求文档 §12 对持久化层的硬性要求 —— 原子写、保留 .bak、损坏回滚、非法内容不崩溃。
 *
 * <p>为什么这些行为必须用测试而不是"肉眼看代码"：回滚逻辑只有在文件真的损坏时才会走到，
 * 而人工制造损坏再重启服务端来验证成本很高。这里的用例直接用临时目录模拟，
 * 是本阶段唯一能真正证明"损坏能回滚"的手段。
 */
class JsonStoreTest {

    private final JsonStore store = new JsonStore();

    @Test
    void 文件不存在时返回默认值且不写盘(@TempDir Path dir) {
        Path file = dir.resolve("players").resolve("never-written.json");

        GlobalData data = store.read(file, GlobalData.class, () -> new GlobalData("fresh"));

        assertEquals("fresh", data.playerUuid);
        assertFalse(Files.exists(file), "仅读取不应产生任何写盘");
    }

    @Test
    void 写入后能原样读回并带上schemaVersion(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("round-trip.json");

        GlobalData written = new GlobalData("uuid-1");
        written.starCoin = 120;
        written.unlockTitle("title_dawn");
        written.collect("egg_dawn");
        written.lastKnownName = "镐京玩家";
        store.write(file, written);

        GlobalData read = store.read(file, GlobalData.class, GlobalData::new);

        assertEquals(120, read.starCoin);
        assertTrue(read.unlockedTitles.contains("title_dawn"));
        assertTrue(read.collection.contains("egg_dawn"));
        assertEquals("镐京玩家", read.lastKnownName, "中文应原样保存，不被转义成 \\uXXXX");
        assertEquals(GlobalData.CURRENT_SCHEMA_VERSION, read.schemaVersion);
    }

    @Test
    void 覆盖写入时把上一份留成bak(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("backup.json");

        GlobalData first = new GlobalData("uuid-2");
        first.starCoin = 1;
        store.write(file, first);

        GlobalData second = new GlobalData("uuid-2");
        second.starCoin = 2;
        store.write(file, second);

        Path backup = JsonStore.backupOf(file);
        assertTrue(Files.isRegularFile(backup), "第二次写入应产生 .bak");

        GlobalData backupData = store.read(backup, GlobalData.class, GlobalData::new);
        assertEquals(1, backupData.starCoin, ".bak 里应是上一份内容而不是最新内容");

        GlobalData currentData = store.read(file, GlobalData.class, GlobalData::new);
        assertEquals(2, currentData.starCoin, "正式文件应是最新内容");
    }

    @Test
    void 主文件损坏时从bak回滚并修复主文件(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("corrupt.json");

        GlobalData good = new GlobalData("uuid-3");
        good.starCoin = 42;
        store.write(file, good);
        store.write(file, good); // 这一步把 good 留进 .bak

        Files.writeString(file, "{ 这显然不是合法 JSON", StandardCharsets.UTF_8);

        GlobalData recovered = store.read(file, GlobalData.class, GlobalData::new);
        assertEquals(42, recovered.starCoin, "应从 .bak 回滚出 42");

        // 回滚后应顺手把主文件修好，避免下次启动再走一遍回滚
        GlobalData reread = store.read(file, GlobalData.class, GlobalData::new);
        assertEquals(42, reread.starCoin, "回滚后主文件应已被修复");
    }

    @Test
    void 主文件与bak都损坏时不抛异常并退回默认值(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hopeless.json");

        store.write(file, new GlobalData("uuid-4"));
        store.write(file, new GlobalData("uuid-4"));
        Files.writeString(file, "彻底坏了", StandardCharsets.UTF_8);
        Files.writeString(JsonStore.backupOf(file), "备份也坏了", StandardCharsets.UTF_8);

        GlobalData data = assertDoesNotThrow(
                () -> store.read(file, GlobalData.class, () -> new GlobalData("fallback")),
                "数据全坏也不能把异常抛给调用方，否则一个玩家的问题会拖垮整个服务端");

        assertEquals("fallback", data.playerUuid);
    }
}
