package com.haojing.battlepass.common.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：守住"文字颜色必须不透明"这条 1.21.11 特有的约束。
 *
 * <p>背景：1.21.11 的 {@code DrawContext#drawText} 里有一句
 * {@code if (ColorHelper.getAlpha(color) != 0)}，**alpha 为 0 的颜色会被静默丢弃**。
 * 一旦有人（包括我）按旧习惯写 {@code 0xE0E0E0} 这种 RGB 常量，
 * 文字就会全部消失、只剩按钮可见 —— 而且编译期毫无提示。
 * 这组断言把这条约束变成可回归的测试。
 */
class GuiColorsTest {

    @Test
    void 所有文字颜色都必须不透明() {
        for (int color : GuiColors.textColors()) {
            assertNotEquals(0, GuiColors.alphaOf(color),
                    "alpha 为 0 的文字颜色在 1.21.11 里会被静默丢弃（什么都不画）："
                            + Integer.toHexString(color));
            assertEquals(0xFF, GuiColors.alphaOf(color),
                    "文字颜色应当完全不透明，否则会被背景压暗：" + Integer.toHexString(color));
        }
    }

    @Test
    void 面板底色应当半透明但不全透明() {
        int alpha = GuiColors.alphaOf(GuiColors.PANEL_BACKGROUND);

        assertTrue(alpha > 0, "底色 alpha 为 0 等于没画");
        assertTrue(alpha < 0xFF, "底色若是全不透明，会把原版背景完全盖住");
    }

    @Test
    void 补alpha不会改变色相() {
        assertEquals(0xFFE0E0E0, GuiColors.fullAlpha(0xE0E0E0));
        assertEquals(0xFFFFFFFF, GuiColors.fullAlpha(0xFFFFFF));
        assertEquals(0xFF, GuiColors.alphaOf(GuiColors.fullAlpha(0x123456)));
        assertEquals(0x123456, GuiColors.fullAlpha(0x123456) & 0x00FFFFFF);
    }

    @Test
    void 补alpha对已经是ARGB的颜色幂等() {
        assertEquals(GuiColors.TEXT, GuiColors.fullAlpha(GuiColors.TEXT));
    }
}
