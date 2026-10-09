package com.haojing.battlepass.common.data;

/**
 * 用途：10 级时二选一的赛季分支（需求文档 §4：10 级时弹窗二选一【狩猎分支】/【建造分支】）。
 *
 * <p>为什么有一个 {@link #NONE}：玩家在 10 级之前还没有做选择，这个"未选择"状态必须能被表达，
 * 否则就得用 null 表示，而 null 序列化进 JSON 后在各处都要判空，容易漏。
 *
 * <p>为什么提供 {@link #fromName(String)} 而不是直接让 Gson 反序列化枚举：Gson 遇到不认识的
 * 枚举值会直接抛异常，而本项目的持久化层把"解析失败"当作文件损坏并回滚 .bak。若将来版本
 * 新增了分支，旧版本读到新数据就会误判为损坏并回滚，造成数据丢失。用宽容的字符串转换可以
 * 避免这个坑。
 */
public enum Branch {

    /** 尚未选择分支（等级未达 branchUnlockLevel）。 */
    NONE,

    /** 狩猎分支。 */
    HUNT,

    /** 建造分支。 */
    BUILD;

    /**
     * 宽容地把字符串转成枚举：不认识或为空一律按 {@link #NONE} 处理。
     *
     * @param name 存档里的分支名
     * @return 对应枚举，无法识别时返回 {@link #NONE}
     */
    public static Branch fromName(String name) {
        if (name == null || name.isEmpty()) {
            return NONE;
        }

        for (Branch branch : values()) {
            if (branch.name().equalsIgnoreCase(name)) {
                return branch;
            }
        }

        return NONE;
    }
}
