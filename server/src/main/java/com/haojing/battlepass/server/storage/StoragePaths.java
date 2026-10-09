package com.haojing.battlepass.server.storage;

import com.haojing.battlepass.common.ModConstants;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 用途：把需求文档 §12 规定的目录结构解析成绝对路径，并保证目录存在。
 *
 * <p>为什么单独抽一个类：需求文档里有三个不同的根（config 目录、游戏目录下的 data、
 * 游戏目录下的 data/history），而这些根在不同启动方式下取值不同。集中在一处解析，
 * 业务代码只拿 {@link #seasonFile(UUID)} 这类语义化方法，就不会有人再散落拼路径。
 *
 * <p>为什么构造函数接收两个根而不直接读 FabricLoader：这样单元测试与将来的工具类
 * 可以在任意临时目录上构造实例，无需启动 Minecraft。真实运行用
 * {@link #createDefault()}。
 */
public final class StoragePaths {

    private final Path configDir;
    private final Path playersDir;
    private final Path globalDir;
    private final Path historyDir;

    /**
     * @param configRoot Fabric 的 config 根目录（FabricLoader.getConfigDir()）
     * @param gameDir    游戏/服务端运行目录（FabricLoader.getGameDir()）
     */
    public StoragePaths(Path configRoot, Path gameDir) {
        this.configDir = configRoot.resolve(ModConstants.CONFIG_SUBDIR);

        // data 根不在 config 下，而在游戏目录下，与需求文档 §12 的写法一致。
        Path dataRoot = gameDir.resolve("data");
        Path modData = dataRoot.resolve(ModConstants.DATA_SUBDIR);
        this.playersDir = modData.resolve(ModConstants.PLAYERS_SUBDIR);
        this.globalDir = modData.resolve(ModConstants.GLOBAL_SUBDIR);
        this.historyDir = dataRoot.resolve(ModConstants.HISTORY_SUBDIR);
    }

    /** @return 按当前服务端实际目录构造的实例。 */
    public static StoragePaths createDefault() {
        FabricLoader loader = FabricLoader.getInstance();
        return new StoragePaths(loader.getConfigDir(), loader.getGameDir());
    }

    /**
     * 建立全部所需目录。启动时调用一次，避免后续写盘时才因为目录不存在而失败。
     *
     * @throws IOException 任一目录创建失败
     */
    public void ensureDirectories() throws IOException {
        Files.createDirectories(configDir);
        Files.createDirectories(playersDir);
        Files.createDirectories(globalDir);
        Files.createDirectories(historyDir);
    }

    /** @param uuid 玩家 UUID @return 该玩家的赛季数据文件路径。 */
    public Path seasonFile(UUID uuid) {
        return playersDir.resolve(uuid + ".json");
    }

    /** @param uuid 玩家 UUID @return 该玩家的永久数据文件路径。 */
    public Path globalFile(UUID uuid) {
        return globalDir.resolve(uuid + ".json");
    }

    /** @param seasonId 赛季 ID @return 该赛季的归档文件路径（data/history/season_&lt;id&gt;.json）。 */
    public Path historyFile(String seasonId) {
        return historyDir.resolve("season_" + seasonId + ".json");
    }

    /** @return 赛季总配置文件（config/&lt;模组名&gt;/season.json）。 */
    public Path seasonConfigFile() {
        return configDir.resolve("season.json");
    }

    /** @return 每日任务池配置文件（config/&lt;模组名&gt;/daily_tasks.json），阶段 4 使用。 */
    public Path dailyTasksConfigFile() {
        return configDir.resolve("daily_tasks.json");
    }

    /** @return 彩蛋配置文件（config/&lt;模组名&gt;/eggs.json），阶段 6 使用。 */
    public Path eggsConfigFile() {
        return configDir.resolve("eggs.json");
    }

    /**
     * @return 等级奖励表（config/&lt;模组名&gt;/rewards.json），阶段 5 使用。
     *
     * <p>为什么单独开一个文件而不是塞进 season.json：§9 要求管理员能编辑「1~30 级、双分支」
     * 的奖励，那是 30 个条目、每条可能带多条奖励的结构；塞进赛季配置会把
     * "赛季开关/时长/防肝参数"这类高频小改动与"奖励内容"这类大块数据混在一个文件里，
     * 手改任意一项都要重写整份文件，出错面更大。
     */
    public Path rewardsConfigFile() {
        return configDir.resolve("rewards.json");
    }

    /** @return 战令商店配置（config/&lt;模组名&gt;/shop.json），阶段 5 使用。 */
    public Path shopConfigFile() {
        return configDir.resolve("shop.json");
    }

    /** @return 节日口令配置（config/&lt;模组名&gt;/codes.json），阶段 6 使用。 */
    public Path codesConfigFile() {
        return configDir.resolve("codes.json");
    }

    /** @return 世界随机事件配置（config/&lt;模组名&gt;/events.json），阶段 6 使用。 */
    public Path eventsConfigFile() {
        return configDir.resolve("events.json");
    }

    /** @return 全服里程碑配置（config/&lt;模组名&gt;/milestones.json），阶段 6 使用。 */
    public Path milestonesConfigFile() {
        return configDir.resolve("milestones.json");
    }

    /** @return 称号配置（config/&lt;模组名&gt;/titles.json）。纯装饰，不含任何属性加成。 */
    public Path titlesConfigFile() {
        return configDir.resolve("titles.json");
    }

    /**
     * @return 全服状态文件（data/&lt;模组名&gt;/state.json）。
     *
     * <p>需求文档 §5.3 要求写入全局标记 {@code lastDailyRefreshDate} 以保证刷新幂等，
     * 但 §12 列出的目录结构里只有"玩家数据"与"历史归档"，没有全服状态的位置。
     * 这里补一个 state.json（该扩展登记在 docs/需求偏差记录.md 的 D-11）。
     */
    public Path stateFile() {
        return globalDir.getParent().resolve("state.json");
    }

    public Path configDir() {
        return configDir;
    }

    public Path playersDir() {
        return playersDir;
    }

    public Path globalDir() {
        return globalDir;
    }

    public Path historyDir() {
        return historyDir;
    }

    /** @return 便于日志输出的一行摘要。 */
    public String describe() {
        return "config=" + configDir + ", players=" + playersDir
                + ", global=" + globalDir + ", history=" + historyDir;
    }
}
