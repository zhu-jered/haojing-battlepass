package com.haojing.battlepass.common.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证界面布局在各种屏幕尺寸下都不会把控件挤出屏幕
 * （需求文档 §8/§9 的界面必须在任意 GUI 缩放下可用）。
 *
 * <p>为什么这类测试值得写：布局错位是"只在别人的显示器/缩放设置上出现"的问题 ——
 * 开发机缩放 2 一切正常，玩家缩放 4 就点不到按钮。有了这组断言，
 * "面板是否落在屏幕内""关闭按钮是否在面板内""每页是否至少有一行""页签是否放得下"
 * 都不再依赖肉眼检查。
 */
class PanelLayoutTest {

    /** GUI 缩放 4 的 1080p：缩放后只有 480×270，是最容易溢出的常见情况。 */
    private static final int SMALL_W = 480;
    private static final int SMALL_H = 270;

    /** 极端情况：很小的窗口或缩放 5 以上。 */
    private static final int TINY_W = 320;
    private static final int TINY_H = 200;

    /** 常见桌面窗口。 */
    private static final int NORMAL_W = 854;
    private static final int NORMAL_H = 480;

    private PanelLayout layout(int width, int height, int tabs) {
        return PanelLayout.compute(width, height, 400, 260, 520, tabs, 16);
    }

    @Test
    void 面板始终落在屏幕内() {
        int[][] screens = {{SMALL_W, SMALL_H}, {TINY_W, TINY_H}, {NORMAL_W, NORMAL_H}, {1280, 720}, {320, 240}};

        for (int[] screen : screens) {
            PanelLayout layout = layout(screen[0], screen[1], 6);

            assertTrue(layout.panelX() >= 0, "左边界越界：" + layout);
            assertTrue(layout.panelY() >= 0, "上边界越界：" + layout);
            assertTrue(layout.panelX() + layout.panelWidth() <= screen[0], "右边界越界：" + layout);
            assertTrue(layout.panelY() + layout.panelHeight() <= screen[1], "下边界越界：" + layout);
        }
    }

    @Test
    void 底部按钮完全落在面板内() {
        int[][] screens = {{SMALL_W, SMALL_H}, {TINY_W, TINY_H}, {NORMAL_W, NORMAL_H}};

        for (int[] screen : screens) {
            PanelLayout layout = layout(screen[0], screen[1], 9);

            assertTrue(layout.footerButtonY() >= layout.panelY(), "底栏跑到面板上方：" + layout);
            assertTrue(layout.footerButtonY() + layout.footerButtonHeight() <= layout.panelY() + layout.panelHeight(),
                    "底栏按钮超出面板底部（早期版本正是把关闭按钮放在 height-4）：" + layout);
        }
    }

    @Test
    void 每页至少有一行且内容区非空() {
        int[][] screens = {{SMALL_W, SMALL_H}, {TINY_W, TINY_H}, {NORMAL_W, NORMAL_H}};

        for (int[] screen : screens) {
            PanelLayout layout = layout(screen[0], screen[1], 9);

            assertTrue(layout.rowsPerPage() >= 1, "每页行数至少为 1：" + layout);
            assertTrue(layout.contentBottom() > layout.contentTop(), "内容区高度必须为正：" + layout);
        }
    }

    @Test
    void 大屏幕能显示更多行() {
        PanelLayout small = layout(SMALL_W, SMALL_H, 6);
        PanelLayout normal = layout(NORMAL_W, NORMAL_H, 6);

        assertTrue(normal.rowsPerPage() > small.rowsPerPage(),
                "大屏幕应显示更多行（当前 small=" + small.rowsPerPage() + " normal=" + normal.rowsPerPage() + "）");
    }

    @Test
    void 小屏幕会进入紧凑模式() {
        assertTrue(layout(TINY_W, TINY_H, 6).compact(), "320x200 应判定为紧凑模式");
        assertTrue(!layout(NORMAL_W, NORMAL_H, 6).compact(), "854x480 不应是紧凑模式");
    }

    @Test
    void 九个页签会自动折行且不越界() {
        for (int[] screen : new int[][] {{SMALL_W, SMALL_H}, {TINY_W, TINY_H}, {NORMAL_W, NORMAL_H}}) {
            PanelLayout layout = layout(screen[0], screen[1], 9);

            assertTrue(layout.tabRows() >= 2 || layout.tabsPerRow() >= 9,
                    "9 个页签在窄屏上必须折行：" + layout);

            for (int index = 0; index < 9; index++) {
                int x = layout.tabX(index, 9);
                int width = layout.tabWidth(index, 9);
                int y = layout.tabY(index);

                assertTrue(x >= layout.panelX(), "页签 " + index + " 左越界：" + layout);
                assertTrue(x + width <= layout.panelX() + layout.panelWidth(), "页签 " + index + " 右越界：" + layout);
                assertTrue(width >= 30, "页签 " + index + " 太窄点不到：" + layout);
                assertTrue(y + layout.tabHeight() <= layout.contentTop(), "页签 " + index + " 与内容区重叠：" + layout);
            }
        }
    }

    @Test
    void 动作按钮右对齐且在面板内() {
        for (int[] screen : new int[][] {{SMALL_W, SMALL_H}, {TINY_W, TINY_H}, {NORMAL_W, NORMAL_H}}) {
            PanelLayout layout = layout(screen[0], screen[1], 6);

            assertTrue(layout.actionButtonX() >= layout.panelX(), "动作按钮左越界：" + layout);
            assertTrue(layout.actionButtonX() + layout.actionButtonWidth() <= layout.panelX() + layout.panelWidth(),
                    "动作按钮右越界：" + layout);
            assertTrue(layout.actionButtonWidth() >= 44, "动作按钮太窄：" + layout);
        }
    }

    @Test
    void 内容行不会与底栏重叠() {
        int[][] screens = {{SMALL_W, SMALL_H}, {NORMAL_W, NORMAL_H}};

        for (int[] screen : screens) {
            PanelLayout layout = layout(screen[0], screen[1], 6);
            int lastRowY = layout.rowY(layout.rowsPerPage() - 1);

            assertTrue(lastRowY + layout.rowHeight() <= layout.footerY(),
                    "最后一行会压到翻页栏上：" + layout);
        }
    }

    @Test
    void 极端参数不会产生负数尺寸() {
        PanelLayout layout = PanelLayout.compute(1, 1, 400, 260, 520, 9, 16);

        assertTrue(layout.panelWidth() > 0);
        assertTrue(layout.panelHeight() > 0);
        assertTrue(layout.rowsPerPage() >= 1);
        assertEquals(0, layout.panelX());
    }
}
