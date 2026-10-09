package com.haojing.battlepass.common.gui;

/**
 * 用途：界面配色常量（纯数据，不引用任何 Minecraft 类型，因此可用单元测试守住）。
 *
 * <p><b>为什么需要这个类</b>：1.21.11 的 {@code DrawContext#drawText} 实现是
 *
 * <pre>
 * if (ColorHelper.getAlpha(color) != 0) {
 *     this.state.addText(...);
 * }
 * </pre>
 *
 * 也就是说**alpha 为 0 的颜色会被静默丢弃，什么都不画**。而用惯旧版本的人（包括我）
 * 会顺手写 {@code 0xE0E0E0} 这种"RGB 三字节"常量 —— 它的 alpha 恰好是 0，
 * 于是文字全部隐形、只剩按钮可见（按钮的标签由控件自己用正确的 ARGB 渲染，
 * 所以现象非常具有误导性："只有按钮能看见"）。
 *
 * <p>因此本类做两件事：①所有颜色**只在这里定义**，一律写成 8 位 ARGB；
 * ②{@link #fullAlpha(int)} 提供"把 RGB 补成不透明"的显式转换，
 * 让"我确实是故意不给 alpha"这件事在代码里可见。
 * 配套的 {@code GuiColorsTest} 会逐条断言文字色不透明。
 */
public final class GuiColors {

    /** 面板标题（金色）。 */
    public static final int TITLE = 0xFFFFD700;

    /** 正文。 */
    public static final int TEXT = 0xFFE0E0E0;

    /** 次要文字（页码、说明）。 */
    public static final int TEXT_DIM = 0xFFA0A0A0;

    /** 诊断状态行（青色，醒目但不刺眼）。 */
    public static final int STATUS = 0xFF80FFFF;

    /** "正在等待服务端数据"提示（橙色）。 */
    public static final int WAITING = 0xFFFFAA00;

    /** 警示（例如"本赛季不可更换"）。 */
    public static final int WARN = 0xFFFF5555;

    /** 描述性小字。 */
    public static final int HINT = 0xFFC0C0C0;

    /**
     * 面板底色（半透明黑）。
     *
     * <p>这一项**故意**不是全不透明：原版背景是泥土纹理，压一层半透明黑既能提升中文可读性，
     * 又不会让界面显得突兀。
     */
    public static final int PANEL_BACKGROUND = 0x90000000;

    private GuiColors() {
    }

    /**
     * 把 RGB 补成不透明。
     *
     * @param rgb 形如 {@code 0xE0E0E0} 的颜色
     * @return 形如 {@code 0xFFE0E0E0} 的颜色
     */
    public static int fullAlpha(int rgb) {
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /**
     * 取 alpha 分量。
     *
     * @param argb 颜色
     * @return 0~255
     */
    public static int alphaOf(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /**
     * 全部"文字用"颜色，供测试逐条校验。
     *
     * @return 文字颜色数组（不含半透明底色）
     */
    public static int[] textColors() {
        return new int[] {TITLE, TEXT, TEXT_DIM, STATUS, WAITING, WARN, HINT};
    }

    // ------------------------------------------------------------------
    // § 颜色代码解析（管理员在配置里写 "§a" / "a" / "§6" 等）
    // ------------------------------------------------------------------

    /** § 颜色代码 → RGB（不含 alpha）。键为小写的 0~9、a~f。 */
    private static final int[] SECTION_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x55FFFF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    /**
     * 把管理员填写的颜色代码解析成不透明 ARGB。
     *
     * <p>接受三种写法："§a"、"a"、"yellow"（与原版颜色名一致）。
     * 解析失败（null / 空 / 未知代码）时返回 {@code null}，调用方据此回退默认色，
     * 这样管理员在配置里写错一个字符只会看到 WARN，不会让客户端崩溃。
     *
     * @param code 管理员填写的颜色代码
     * @return 8 位 ARGB（alpha 恒为 FF），或 null
     */
    public static Integer parseSectionColor(String code) {
        if (code == null) {
            return null;
        }

        String s = code.trim();

        if (s.isEmpty()) {
            return null;
        }

        if (s.startsWith("§") || s.startsWith("&")) {
            s = s.substring(1);
        }

        if (s.isEmpty()) {
            return null;
        }

        if (s.length() == 1) {
            char c = Character.toLowerCase(s.charAt(0));
            int index = "0123456789abcdef".indexOf(c);
            return index < 0 ? null : fullAlpha(SECTION_RGB[index]);
        }

        // 支持按颜色名兜底（与原版 Formatting 名一致的常见别名）。
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "black":   return fullAlpha(0x000000);
            case "dark_blue":
            case "navy":     return fullAlpha(0x0000AA);
            case "dark_green": return fullAlpha(0x00AA00);
            case "dark_aqua":
            case "teal":     return fullAlpha(0x00AAAA);
            case "dark_red":
            case "maroon":   return fullAlpha(0xAA0000);
            case "dark_purple": return fullAlpha(0xAA00AA);
            case "gold":
            case "orange":   return fullAlpha(0xFFAA00);
            case "gray":
            case "grey":     return fullAlpha(0xAAAAAA);
            case "dark_gray":
            case "dark_grey": return fullAlpha(0x555555);
            case "blue":     return fullAlpha(0x5555FF);
            case "green":     return fullAlpha(0x55FF55);
            case "aqua":
            case "cyan":     return fullAlpha(0x55FFFF);
            case "red":      return fullAlpha(0xFF5555);
            case "light_purple":
            case "magenta":  return fullAlpha(0xFF55FF);
            case "yellow":    return fullAlpha(0xFFFF55);
            case "white":     return fullAlpha(0xFFFFFF);
            default:         return null;
        }
    }

    /**
     * 归一化一个 § 颜色代码字符串：去掉前缀、统一小写。
     *
     * <p>用于直接拼进 {@code Text.literal("§" + code)} 的场景（文字着色不需要转成 int）。
     * 非法输入返回 {@code fallback}（通常是 "§7"）。
     *
     * @param code     管理员填写的代码
     * @param fallback 非法时的回退值
     * @return 形如 "§a" 的代码
     */
    public static String normalizeSection(String code, String fallback) {
        if (code == null) {
            return fallback;
        }

        String s = code.trim();

        if (s.isEmpty()) {
            return fallback;
        }

        if (s.startsWith("§") || s.startsWith("&")) {
            s = s.substring(1);
        }

        if (s.length() == 1) {
            char c = Character.toLowerCase(s.charAt(0));
            if ("0123456789abcdef".indexOf(c) >= 0) {
                return "§" + c;
            }
        }

        Integer parsed = parseSectionColor(code);

        if (parsed == null) {
            return fallback;
        }

        // 把解析成功的颜色反查回标准 § 代码（保持与原版一致）。
        int rgb = parsed & 0x00FFFFFF;

        for (int i = 0; i < SECTION_RGB.length; i++) {
            if (SECTION_RGB[i] == rgb) {
                return "§" + "0123456789abcdef".charAt(i);
            }
        }

        return fallback;
    }
}
