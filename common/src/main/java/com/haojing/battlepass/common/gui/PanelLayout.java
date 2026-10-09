package com.haojing.battlepass.common.gui;

/**
 * 用途：界面布局的**纯计算**部分（需求文档 §8"独立中文窗口"、§9 管理面板）。
 *
 * <p>解决的具体问题：界面里所有控件都必须落在**当前 GUI 缩放后的可用区域**内。
 * Minecraft 的 {@code Screen#width/height} 是已经按 GUI 缩放换算过的坐标
 * （例如 1920×1080 屏幕、GUI 缩放 4 → 只有 480×270），因此"写死 380 像素宽的面板 +
 * 把关闭按钮放在 height-4"在缩放调大或小窗口下必然溢出：控件跑出屏幕、按钮点不到。
 *
 * <p>为什么这类代码放在 :common 而不是客户端工程：它是**纯数学**（不引用任何 Minecraft 类型），
 * 放在 :common 就能用现成的服务端测试源集覆盖"极小窗口 / 常见窗口 / 大窗口"三种情况。
 * 布局错位是那种"只在别人的显示器上出现"的问题，没有回归测试就只能靠反复截图。
 *
 * <p>坐标约定：全部是 GUI 缩放后的坐标，左上角为原点，与 {@code Screen} 一致。
 */
public final class PanelLayout {

    /** 面板与屏幕边缘的最小间距（普通模式）。 */
    public static final int DEFAULT_MARGIN = 8;

    /** 紧凑模式下的边距。 */
    public static final int COMPACT_MARGIN = 4;

    /** 判定为紧凑模式的宽度阈值（GUI 缩放 4 时常见 480×270 以下）。 */
    public static final int COMPACT_WIDTH = 340;

    /** 判定为紧凑模式的高度阈值。 */
    public static final int COMPACT_HEIGHT = 240;

    /** 页签的最小宽度：低于这个值中文标签会挤在一起。 */
    public static final int MIN_TAB_WIDTH = 56;

    /** 紧凑模式下页签的最小宽度。 */
    public static final int MIN_TAB_WIDTH_COMPACT = 46;

    private final int screenWidth;
    private final int screenHeight;
    private final boolean compact;

    private final int panelX;
    private final int panelY;
    private final int panelWidth;
    private final int panelHeight;

    private final int titleHeight;
    private final int tabHeight;
    private final int tabsPerRow;
    private final int tabRows;
    private final int contentTop;
    private final int contentBottom;
    private final int rowHeight;
    private final int rowsPerPage;
    private final int footerHeight;
    private final int footerY;

    private PanelLayout(int screenWidth, int screenHeight, int desiredWidth, int minWidth, int maxWidth,
                        int tabCount, int desiredRowHeight) {
        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
        this.compact = this.screenWidth < COMPACT_WIDTH || this.screenHeight < COMPACT_HEIGHT;

        int margin = compact ? COMPACT_MARGIN : DEFAULT_MARGIN;

        // 面板宽度：先夹在 [minWidth, maxWidth]，再保证不超出屏幕（两侧各留 margin）。
        int available = Math.max(80, this.screenWidth - 2 * margin);
        int clamped = Math.max(minWidth, Math.min(desiredWidth, maxWidth));
        int width = Math.max(80, Math.min(clamped, available));

        this.panelWidth = width;
        this.panelX = Math.max(0, (this.screenWidth - width) / 2);
        this.panelY = margin;
        this.panelHeight = Math.max(40, this.screenHeight - 2 * margin);

        this.titleHeight = compact ? 12 : 16;
        this.tabHeight = compact ? 14 : 18;

        int minTabWidth = compact ? MIN_TAB_WIDTH_COMPACT : MIN_TAB_WIDTH;
        this.tabsPerRow = Math.max(1, panelWidth / minTabWidth);
        this.tabRows = Math.max(1, (Math.max(1, tabCount) + tabsPerRow - 1) / tabsPerRow);

        this.footerHeight = compact ? 16 : 20;
        this.footerY = panelY + panelHeight - footerHeight;

        this.contentTop = panelY + titleHeight + tabRows * tabHeight + 4;
        this.contentBottom = Math.max(contentTop + 1, footerY - 4);

        int row = desiredRowHeight <= 0 ? (compact ? 12 : 16) : desiredRowHeight;
        this.rowHeight = compact ? Math.max(10, row - 2) : row;
        this.rowsPerPage = Math.max(1, (contentBottom - contentTop) / this.rowHeight);
    }

    /**
     * 计算一套布局。
     *
     * @param screenWidth      当前界面的宽度（GUI 缩放后）
     * @param screenHeight     当前界面的高度（GUI 缩放后）
     * @param desiredWidth     期望的面板宽度
     * @param minWidth         面板最小宽度
     * @param maxWidth         面板最大宽度
     * @param tabCount         页签数量（用于计算需要几行页签）
     * @param desiredRowHeight 一行的高度
     * @return 布局
     */
    public static PanelLayout compute(int screenWidth, int screenHeight, int desiredWidth, int minWidth,
                                     int maxWidth, int tabCount, int desiredRowHeight) {
        return new PanelLayout(screenWidth, screenHeight, desiredWidth, minWidth, maxWidth, tabCount, desiredRowHeight);
    }

    /** @return 是否为紧凑模式（小窗口/高 GUI 缩放）。 */
    public boolean compact() {
        return compact;
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    public int panelX() {
        return panelX;
    }

    public int panelY() {
        return panelY;
    }

    public int panelWidth() {
        return panelWidth;
    }

    public int panelHeight() {
        return panelHeight;
    }

    public int titleHeight() {
        return titleHeight;
    }

    public int tabHeight() {
        return tabHeight;
    }

    public int tabRows() {
        return tabRows;
    }

    public int tabsPerRow() {
        return tabsPerRow;
    }

    public int contentTop() {
        return contentTop;
    }

    public int contentBottom() {
        return contentBottom;
    }

    public int rowHeight() {
        return rowHeight;
    }

    public int rowsPerPage() {
        return rowsPerPage;
    }

    public int footerHeight() {
        return footerHeight;
    }

    public int footerY() {
        return footerY;
    }

    /** @return 面板内容的可用宽度（左右各留 4 像素内边距）。 */
    public int contentWidth() {
        return Math.max(40, panelWidth - 8);
    }

    /**
     * 第 index 个页签所在的行。
     *
     * @param index 页签序号（0 起）
     * @return 行号（0 起）
     */
    public int tabRowOf(int index) {
        return Math.max(0, index) / tabsPerRow;
    }

    /**
     * 第 index 个页签的 x 坐标。
     *
     * @param index     页签序号
     * @param tabCount  页签总数（用于让最后一行的页签均分而不是留一大块空白）
     * @return x 坐标
     */
    public int tabX(int index, int tabCount) {
        int row = tabRowOf(index);
        int firstIndex = row * tabsPerRow;
        int inRow = Math.max(1, Math.min(tabsPerRow, Math.max(1, tabCount) - firstIndex));
        int width = panelWidth / inRow;
        return panelX + (index - firstIndex) * width;
    }

    /**
     * 第 index 个页签的宽度。
     *
     * @param index    页签序号
     * @param tabCount 页签总数
     * @return 宽度（至少 30，保证能点得到）
     */
    public int tabWidth(int index, int tabCount) {
        int row = tabRowOf(index);
        int firstIndex = row * tabsPerRow;
        int inRow = Math.max(1, Math.min(tabsPerRow, Math.max(1, tabCount) - firstIndex));
        return Math.max(30, panelWidth / inRow - 2);
    }

    /**
     * 第 index 个页签的 y 坐标。
     *
     * @param index 页签序号
     * @return y 坐标
     */
    public int tabY(int index) {
        return panelY + titleHeight + tabRowOf(index) * tabHeight;
    }

    /**
     * 内容区第 row 行的 y 坐标。
     *
     * @param row 行号（0 起，指当前页内的第几行）
     * @return y 坐标
     */
    public int rowY(int row) {
        return contentTop + Math.max(0, row) * rowHeight;
    }

    /** @return 行内"动作按钮"的宽度。 */
    public int actionButtonWidth() {
        return Math.max(44, Math.min(110, panelWidth / 3));
    }

    /** @return 行内"动作按钮"的 x 坐标（右对齐）。 */
    public int actionButtonX() {
        return panelX + panelWidth - actionButtonWidth() - 4;
    }

    /** @return 底部按钮的高度。 */
    public int footerButtonHeight() {
        return Math.max(12, footerHeight - 4);
    }

    /** @return 底部按钮的 y 坐标（完全落在面板内）。 */
    public int footerButtonY() {
        return footerY + 2;
    }

    /** @return 便于日志的一行摘要。 */
    @Override
    public String toString() {
        return "PanelLayout(" + screenWidth + "x" + screenHeight + (compact ? " 紧凑" : "")
                + " 面板=" + panelWidth + "x" + panelHeight + " @" + panelX + "," + panelY
                + " 页签行=" + tabRows + " 每页行数=" + rowsPerPage + ")";
    }
}
