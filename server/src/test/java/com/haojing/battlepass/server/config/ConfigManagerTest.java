package com.haojing.battlepass.server.config;

import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §2（配置热重载）与 §18（season.json 结构）对配置层的要求，
 * 并锁死"非法配置只修正不崩溃"这一行为。
 *
 * <p>为什么这些用例值得写：配置是管理员天天会手改的东西。如果非法值导致模组加载失败，
 * 一次手滑就会让服务器起不来；如果非法值被静默忽略，管理员又会以为改生效了。
 * 两种失败都很贵，所以必须用测试钉住"修正 + 告警 + 能继续跑"。
 */
class ConfigManagerTest {

    private StoragePaths paths;

    private ConfigManager newManager(Path tmp) throws IOException {
        StoragePaths created = new StoragePaths(tmp.resolve("config"), tmp.resolve("game"));
        created.ensureDirectories();
        this.paths = created;
        return new ConfigManager(created, new JsonStore());
    }

    @Test
    void 首次加载会自动落地默认配置文件(@TempDir Path tmp) throws IOException {
        ConfigManager manager = newManager(tmp);
        SeasonConfig config = manager.load();

        assertTrue(Files.isRegularFile(paths.seasonConfigFile()),
                "应自动生成 season.json，否则管理员没有文件可改");
        assertEquals("S1", config.seasonId);
        assertEquals(30, config.durationDays);
        assertEquals(30, config.maxLevel);
        assertEquals(10, config.branchUnlockLevel);
        assertEquals("06:00", config.dailyRefreshTime);
        assertEquals(500, config.dailyXpCap);
        assertTrue(config.longNight.enabled);
        assertEquals("00:00", config.longNight.start);
        assertEquals("06:00", config.longNight.end);
        assertEquals(0.5D, config.longNight.xpMultiplier, 1e-9D);
    }

    @Test
    void 保存后重新加载内容保持一致(@TempDir Path tmp) throws IOException {
        ConfigManager manager = newManager(tmp);
        SeasonConfig config = manager.load();
        config.durationDays = 45;
        config.themeName = "镐京次章";
        config.dailyXpCap = 800;
        manager.save();

        SeasonConfig reloaded = new ConfigManager(paths, new JsonStore()).load();

        assertEquals(45, reloaded.durationDays);
        assertEquals("镐京次章", reloaded.themeName);
        assertEquals(800, reloaded.dailyXpCap);
    }

    @Test
    void 非法数值会被修正而不是让模组加载失败(@TempDir Path tmp) throws IOException {
        ConfigManager manager = newManager(tmp);
        manager.load();

        // 手工写一份"管理员乱填"的配置
        SeasonConfig bad = new SeasonConfig();
        bad.durationDays = 0;
        bad.maxLevel = 0;
        bad.branchUnlockLevel = 99;
        bad.dailyXpCap = -5;
        bad.dailyRefreshTime = "99:99";
        bad.longNight.xpMultiplier = 99.0D;
        bad.longNight.fatigueSlownessAmplifier = -3;
        new JsonStore().write(paths.seasonConfigFile(), bad);

        SeasonConfig fixed = manager.load();

        assertEquals(30, fixed.durationDays, "0 天应被修正");
        assertEquals(30, fixed.maxLevel, "0 级应被修正");
        assertTrue(fixed.branchUnlockLevel >= 1 && fixed.branchUnlockLevel <= fixed.maxLevel,
                "解锁等级应被夹进合法区间");
        assertEquals(0, fixed.dailyXpCap, "负数上限应回退为 0");
        assertEquals("06:00", fixed.dailyRefreshTime, "非法时间应回退默认并被规范化成 HH:mm");
        assertEquals(10.0D, fixed.longNight.xpMultiplier, 1e-9D, "倍率应夹紧到上界");
        assertEquals(0, fixed.longNight.fatigueSlownessAmplifier, "负数等级应回退为 0");
    }

    @Test
    void 白名单非法项被剔除且大小写被规范化(@TempDir Path tmp) throws IOException {
        ConfigManager manager = newManager(tmp);
        manager.load();

        UUID valid = UUID.randomUUID();

        SeasonConfig raw = new SeasonConfig();
        raw.longNight.adminWhitelist = List.of("  " + valid.toString().toUpperCase() + "  ", "并不是UUID", "12345");

        new JsonStore().write(paths.seasonConfigFile(), raw);
        SeasonConfig loaded = manager.load();

        assertEquals(List.of(valid.toString()), loaded.longNight.adminWhitelist,
                "应只保留规范化后的小写 UUID（大写手抄很常见，不统一就会'配了但不生效'）");
        assertTrue(manager.isAdminWhitelisted(valid));
        assertFalse(manager.isAdminWhitelisted(UUID.randomUUID()));
    }

    @Test
    void 配置文件损坏时能从bak回滚(@TempDir Path tmp) throws IOException {
        ConfigManager manager = newManager(tmp);

        SeasonConfig working = manager.load();
        working.durationDays = 45;
        manager.save();      // 写入 45，.bak 变成默认的 30
        working.durationDays = 60;
        manager.save();      // 写入 60，.bak 变成 45

        Files.writeString(paths.seasonConfigFile(), "{ 这不是合法 JSON", StandardCharsets.UTF_8);

        SeasonConfig recovered = new ConfigManager(paths, new JsonStore()).load();

        assertEquals(45, recovered.durationDays, "主文件损坏时应从 .bak 回滚出 45");
        assertTrue(manager.configFileExists(), "回滚后应把修好的配置写回");
    }

    @Test
    void 外部改动会触发热重载且不会自我循环(@TempDir Path tmp) throws IOException {
        // 这条用例对应需求文档 §16「修改长夜起止时间后不重启即生效」，
        // 同时防住一个很容易写错的坑：ConfigManager 加载后会主动回写文件，
        // 如果回写之后不重新记录修改时间，下一次变更检查就会把自己刚写的内容当成外部改动，
        // 于是每轮都重载一次 —— 表现为日志被刷屏、磁盘被反复写。
        ConfigManager manager = newManager(tmp);
        manager.load();
        assertFalse(manager.reloadIfChanged(), "刚加载完，不应触发重载");

        // 模拟管理员手改 season.json（直接写盘），并把修改时间往后拨，避免文件系统时间戳精度问题
        SeasonConfig edited = new SeasonConfig();
        edited.durationDays = 77;
        new JsonStore().write(paths.seasonConfigFile(), edited);
        Files.setLastModifiedTime(paths.seasonConfigFile(),
                FileTime.from(Files.getLastModifiedTime(paths.seasonConfigFile()).toInstant().plusSeconds(5)));

        assertTrue(manager.reloadIfChanged(), "文件被改过，应触发重载");
        assertEquals(77, manager.config().durationDays, "重载后应拿到新值");

        assertFalse(manager.reloadIfChanged(), "重载会回写文件，但不该被当成又一次外部改动");
    }
}
