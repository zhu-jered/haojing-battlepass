package com.haojing.battlepass.server.reward;

import java.util.List;

/**
 * 用途：COMMAND 类奖励的命令白名单判定（需求文档 §9：
 * 「COMMAND 必须走管理员命令白名单，禁止将 GUI 输入直接作为命令执行」）。
 *
 * <p>为什么这是本工程唯一的安全边界、必须做成纯函数：管理面板（阶段 7）允许管理员
 * 在 GUI 里填命令，而 GUI 的输入最终会变成服务端以管理员权限执行的字符串。
 * 判定逻辑写成纯函数，就能用单元测试把"什么算命中白名单"钉死，
 * 包括各种绕过写法（前导斜杠、大小写、命名空间前缀、命令里带参数）。
 *
 * <p>为什么按"命令根"匹配而不是整串匹配：白名单要表达的是"允许 give 这一类命令"，
 * 不可能把每条带具体参数的完整命令都列出来（参数是随奖励变化的）。
 */
public final class CommandWhitelist {

    private CommandWhitelist() {
    }

    /**
     * 取一条命令的"根"（第一个空白之前的部分），并剥掉前导斜杠与命名空间前缀。
     *
     * <p>为什么要剥命名空间：原版允许 {@code /minecraft:give @s diamond} 这种写法
     * （维度/命名空间限定），若不做归一化，管理员在白名单里写了 give
     * 却会因为玩家配置里带了 {@code minecraft:} 前缀而被判为"不在白名单"。
     *
     * @param command 原始命令，例如 {@code "/minecraft:give @s minecraft:diamond 1"}
     * @return 命令根（例如 {@code give}）；非法输入返回空串
     */
    public static String rootOf(String command) {
        if (command == null) {
            return "";
        }

        String trimmed = command.trim();

        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }

        if (trimmed.isEmpty()) {
            return "";
        }

        int space = trimmed.indexOf(' ');
        String root = space < 0 ? trimmed : trimmed.substring(0, space);

        int colon = root.lastIndexOf(':');

        if (colon >= 0 && colon < root.length() - 1) {
            root = root.substring(colon + 1);
        }

        return root;
    }

    /**
     * 判断某条命令是否被允许执行。
     *
     * @param command   待执行的命令
     * @param whitelist 白名单（命令根列表，大小写不敏感）
     * @return 是否允许
     */
    public static boolean isAllowed(String command, List<String> whitelist) {
        String root = rootOf(command);

        if (root.isEmpty() || whitelist == null || whitelist.isEmpty()) {
            return false;
        }

        for (String entry : whitelist) {
            if (entry == null || entry.isBlank()) {
                continue;
            }

            String allowed = rootOf(entry);

            if (!allowed.isEmpty() && allowed.equalsIgnoreCase(root)) {
                return true;
            }
        }

        return false;
    }
}
