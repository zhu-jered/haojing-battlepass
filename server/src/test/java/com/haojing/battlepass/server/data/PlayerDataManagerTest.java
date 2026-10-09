package com.haojing.battlepass.server.data;

import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证需求文档 §12 对内存态与写盘调度的要求 —— ConcurrentHashMap 内存态、
 * 标记脏 + 节流落盘、离线玩家落盘后再释放、单玩家失败不牵连其他玩家。
 *
 * <p>为什么用临时目录而不是游戏目录：StoragePaths 的构造函数接收目录根，
 * 因此这里不需要启动 Minecraft 就能跑出与真实运行完全一致的目录结构。
 * 测试里刻意不调用 {@link PlayerDataManager#start()}，避免后台线程干扰断言。
 */
class PlayerDataManagerTest {

    private StoragePaths paths;

    private PlayerDataManager newManager(Path tmp) throws IOException {
        StoragePaths created = new StoragePaths(tmp.resolve("config"), tmp.resolve("game"));
        created.ensureDirectories();
        this.paths = created;
        return new PlayerDataManager(created, new JsonStore(), "S1");
    }

    @Test
    void 目录结构符合需求文档第12节(@TempDir Path tmp) throws IOException {
        newManager(tmp);

        assertTrue(Files.isDirectory(paths.configDir()), "config/<模组名>/ 应存在");
        assertTrue(Files.isDirectory(paths.playersDir()), "data/<模组名>/players/ 应存在");
        assertTrue(Files.isDirectory(paths.globalDir()), "data/<模组名>/global/ 应存在");
        assertTrue(Files.isDirectory(paths.historyDir()), "data/history/ 应存在");
        assertTrue(paths.seasonFile(UUID.randomUUID()).toString().contains("players"));
        assertTrue(paths.historyFile("S1").toString().endsWith("season_S1.json"));
    }

    @Test
    void 标记脏数据后flush才落盘(@TempDir Path tmp) throws IOException {
        PlayerDataManager manager = newManager(tmp);
        UUID id = UUID.randomUUID();

        // 只改内存、不标脏：flush 不应产生文件（体现"节流合并"而不是"每次改动都写"）
        manager.season(id).level = 7;
        manager.flushDirty();
        assertTrue(Files.notExists(paths.seasonFile(id)), "未标脏不应落盘");

        manager.markSeasonDirty(id);
        manager.flushDirty();

        assertTrue(Files.isRegularFile(paths.seasonFile(id)), "标脏后 flush 应落盘");
        SeasonData reloaded = new JsonStore().read(paths.seasonFile(id), SeasonData.class, SeasonData::new);
        assertEquals(7, reloaded.level);
        assertEquals(id.toString(), reloaded.playerUuid, "落盘时应以文件名为准写入 UUID");
        assertTrue(manager.writeCount() > 0, "写盘计数器应递增（需求文档 §13 的可测量指标）");
    }

    @Test
    void 玩家退出后落盘并从内存释放(@TempDir Path tmp) throws IOException {
        PlayerDataManager manager = newManager(tmp);
        UUID id = UUID.randomUUID();

        manager.global(id).starCoin = 30;
        manager.markGlobalDirty(id);
        manager.onPlayerQuit(id);

        assertEquals(1, manager.cachedPlayers(), "退出瞬间数据仍在内存里，等写盘线程处理");
        manager.flushDirty();

        GlobalData reloaded = new JsonStore().read(paths.globalFile(id), GlobalData.class, GlobalData::new);
        assertEquals(30, reloaded.starCoin, "退出后数据必须已落盘，否则掉线就丢");
        assertEquals(0, manager.cachedPlayers(), "落盘成功后应释放离线玩家的内存数据");
    }

    @Test
    void 单个玩家写盘失败不影响其他玩家(@TempDir Path tmp) throws IOException {
        PlayerDataManager manager = newManager(tmp);

        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();

        // 在"坏"玩家的数据文件位置放一个非空目录，让原子移动必然失败，模拟这个玩家写不进去。
        Files.createDirectories(paths.seasonFile(broken).resolve("blocker"));
        manager.season(broken).level = 1;
        manager.markSeasonDirty(broken);

        manager.season(healthy).level = 9;
        manager.markSeasonDirty(healthy);

        // 关键断言之一：写盘失败不能把异常抛出来
        assertDoesNotThrow(manager::flushDirty, "单玩家写盘失败不得向外抛异常");

        // 关键断言之二：健康玩家照常落盘
        assertTrue(Files.isRegularFile(paths.seasonFile(healthy)), "健康玩家应正常落盘");
        SeasonData reloaded = new JsonStore().read(paths.seasonFile(healthy), SeasonData.class, SeasonData::new);
        assertEquals(9, reloaded.level);
    }

    @Test
    void 豁免卡写在赛季数据里而不是永久数据里(@TempDir Path tmp) throws IOException {
        // 这条用例把用户的决定固化成回归保护：豁免卡赛季结束不保留，
        // 所以它必须落在赛季文件里；一旦有人把它挪回永久数据，这个断言会立刻失败。
        PlayerDataManager manager = newManager(tmp);
        UUID id = UUID.randomUUID();

        manager.season(id).exemptCards = 3;
        manager.global(id).starCoin = 50;
        manager.markSeasonDirty(id);
        manager.markGlobalDirty(id);
        manager.flushDirty();

        String seasonJson = Files.readString(paths.seasonFile(id));
        String globalJson = Files.readString(paths.globalFile(id));

        assertTrue(seasonJson.contains("exemptCards"), "豁免卡应写在赛季数据里");
        assertFalse(globalJson.contains("exemptCards"), "豁免卡不应出现在永久数据里（赛季结束会清零）");
    }
}
