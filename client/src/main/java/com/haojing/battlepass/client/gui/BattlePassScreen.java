package com.haojing.battlepass.client.gui;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.gui.PanelLayout;
import com.haojing.battlepass.common.net.ModSnapshots;
import com.haojing.battlepass.common.net.NetActions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：玩家战令界面（需求文档 §8：{@code /battlepass} 或快捷键 {@code B} 打开，
 * 独立中文窗口，支持滚动分页）。
 *
 * <p>六个分页与 §8 一一对应：首页 / 每日任务 / 每周挑战 / 战令商店 / 称号库 / 收藏册。
 *
 * <p>本版本新增的纯客户端渲染特性（不改动任何服务端逻辑与存档）：
 * <ul>
 *   <li>每日/每周任务名称悬浮 Tooltip（显示任务 desc）；</li>
 *   <li>任务条目点击选中（仿"选择世界"界面：边框 + 背景变暗）；</li>
 *   <li>当前 Tab 高亮（文字变色 + 底色加深）；</li>
 *   <li>商店商品按 group 着色，价格数字独立颜色；</li>
 *   <li>首页右下角常驻"支持社团：镐京方块协会"可点击文字（打开配置的链接）。</li>
 * </ul>
 *
 * <p>所有颜色/链接都来自服务端下发的 {@link ModSnapshots.GuiStyle}，非法值已在服务端
 * 回退默认；这里只是"照着画"，任何渲染异常都被 try/catch 兜住，不会让界面崩。
 */
@Environment(EnvType.CLIENT)
public class BattlePassScreen extends Screen {

    /** 六个分页的翻译键（顺序即页签顺序）。 */
    private static final String[] TAB_KEYS = {
            "haojing_battlepass.gui.tab.home",
            "haojing_battlepass.gui.tab.daily",
            "haojing_battlepass.gui.tab.weekly",
            "haojing_battlepass.gui.tab.shop",
            "haojing_battlepass.gui.tab.titles",
            "haojing_battlepass.gui.tab.collection"
    };

    private int tab;
    private int page;
    private PanelLayout layout;
    private List<Row> rows = List.of();

    /** 当前选中的任务行（任务 id）；null 表示没有选中。 */
    private String selectedRowKey;

    /** Tab 按钮引用，用于绘制激活高亮（按钮本身的位置/大小不变）。 */
    private final List<ButtonWidget> tabButtons = new ArrayList<>();

    public BattlePassScreen() {
        super(Text.translatable("haojing_battlepass.gui.title"));
    }

    /**
     * 界面的一行。
     *
     * @param text        正文
     * @param actionKey   按钮文案翻译键；null 表示该行没有按钮
     * @param action      按钮点击后的动作
     * @param enabled     按钮是否可用
     * @param tooltip     鼠标悬浮在"任务名称"区域时显示的 Tooltip；null 表示不触发
     * @param rowKey      可选中任务行的唯一键（任务 id）；null 表示不可选中
     * @param nameWidth   任务名称段的像素宽度（Tooltip 命中区）；非任务行用不到
     * @param shopGroup   商店行分组（用于着色）；null/空串回退默认
     */
    private record Row(Text text, String actionKey, Runnable action, boolean enabled,
                       Text tooltip, String rowKey, int nameWidth, String shopGroup) {
    }

    @Override
    protected void init() {
        // 每次打开界面（以及每次窗口尺寸变化）都重建布局：这样 GUI 缩放怎么调都不会错位。
        layout = PanelLayout.compute(this.width, this.height, 480, 280, 520, TAB_KEYS.length, 16);

        // 每次打开界面都请求一次全量同步：这样"界面里的数字"一定是最新的。
        ClientNetworking.requestResync();

        buildRows();
        tabButtons.clear();

        for (int index = 0; index < TAB_KEYS.length; index++) {
            final int target = index;
            ButtonWidget tabButton = ButtonWidget.builder(Text.translatable(TAB_KEYS[index]), widget -> {
                tab = target;
                page = 0;
                rebuild();
            }).dimensions(layout.tabX(index, TAB_KEYS.length), layout.tabY(index),
                    layout.tabWidth(index, TAB_KEYS.length), layout.tabHeight() - 2).build();
            tabButtons.add(tabButton);
            addDrawableChild(tabButton);
        }

        int rowsPerPage = layout.rowsPerPage();
        int firstRow = page * rowsPerPage;

        addCustomButtons();

        // 底部：翻页与关闭（全部落在面板内，不再有"按钮一半在屏幕外"的问题）。
        int buttonHeight = layout.footerButtonHeight();
        int footerY = layout.footerButtonY();
        int smallWidth = Math.max(40, layout.panelWidth() / 5);

        ButtonWidget previous = ButtonWidget.builder(Text.translatable("haojing_battlepass.gui.prev"), widget -> {
            if (page > 0) {
                page--;
                rebuild();
            }
        }).dimensions(layout.panelX(), footerY, smallWidth, buttonHeight).build();
        previous.active = page > 0;
        addDrawableChild(previous);

        ButtonWidget next = ButtonWidget.builder(Text.translatable("haojing_battlepass.gui.next"), widget -> {
            if (page + 1 < pageCount()) {
                page++;
                rebuild();
            }
        }).dimensions(layout.panelX() + smallWidth + 2, footerY, smallWidth, buttonHeight).build();
        next.active = page + 1 < pageCount();
        addDrawableChild(next);

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), widget -> close())
                .dimensions(layout.panelX() + layout.panelWidth() - smallWidth, footerY, smallWidth, buttonHeight).build());
    }

    private void rebuild() {
        // 重新初始化控件：先清空再 init，页签与按钮都会按新状态重建。
        this.clearChildren();
        this.init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1.21.11 起 super.render 不画背景，自己铺一层全屏暗色
        context.fill(0, 0, this.width, this.height, 0xC0101010);
        if (layout != null) {
            ModSnapshots.GuiStyle style = guiStyle();

            context.fill(layout.panelX(), layout.panelY(),
                    layout.panelX() + layout.panelWidth(), layout.panelY() + layout.panelHeight(),
                    GuiColors.PANEL_BACKGROUND);

            context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                    layout.panelX() + layout.panelWidth() / 2, layout.panelY() + 2, GuiColors.TITLE);

            if (!ClientNetworking.state().hasPlayerData()) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.translatable("haojing_battlepass.gui.waiting"),
                        layout.panelX() + layout.panelWidth() / 2, layout.contentTop() + 20, GuiColors.WAITING);
            } else {
                switch (tab) {
                    case 0 -> renderHomeCustom(context, mouseX, mouseY);
                    case 1 -> renderDailyCustom(context, mouseX, mouseY);
                    case 2 -> renderWeeklyCustom(context, mouseX, mouseY);
                    case 3 -> renderShopCustom(context, mouseX, mouseY);
                    case 4 -> renderTitlesCustom(context, mouseX, mouseY);
                    case 5 -> renderCollectionCustom(context);
                    default -> renderRowList(context);
                }
            }
        }

        super.render(context, mouseX, mouseY, delta);

        // ⑤ 激活 Tab 高亮：在按钮之上画一层底色 + 边框；按钮布局/位置不变。
        if (tab >= 0 && tab < tabButtons.size()) {
            ButtonWidget active = tabButtons.get(tab);
            ModSnapshots.GuiStyle style = guiStyle();
            context.fill(active.getX(), active.getY(),
                    active.getX() + active.getWidth(), active.getY() + active.getHeight(),
                    style.tabActiveOverlayArgb);
            context.drawTextWithShadow(this.textRenderer, active.getMessage(),
                    active.getX() + (active.getWidth() - this.textRenderer.getWidth(active.getMessage())) / 2,
                    active.getY() + (active.getHeight() - 8) / 2,
                    style.tabActiveTextColor);
        }

        // ⑥ 任务名称悬浮 Tooltip（最后画，压在最上层，不挡原有按钮）。
        renderTaskTooltip(context, mouseX, mouseY);
    }

    /** 首页右下角社团文字。 */
    private void renderCommunityLine(DrawContext context, ModSnapshots.GuiStyle style) {
        try {
            Integer rgb = GuiColors.parseSectionColor(style.communityColor);
            Text line = Text.literal(style.communityText).setStyle(
                    Style.EMPTY.withColor(rgb == null ? TextColor.fromRgb(0xFFA0A0A0) : TextColor.fromRgb(rgb)));

            int textWidth = this.textRenderer.getWidth(line);
            int x = layout.panelX() + layout.panelWidth() - 4 - textWidth;
            // 紧贴 footer 上方一行，避开所有底部按钮（footerY 是按钮顶）。
            int y = layout.footerY() - 11;

            if (y < layout.contentTop()) {
                communityBounds = null;
                return;
            }

            context.drawTextWithShadow(this.textRenderer, line, x, y, GuiColors.TEXT_DIM);
            communityBounds = new int[]{x, y, x + textWidth, y + 10};
        } catch (RuntimeException e) {
            // 社团文字渲染失败不能让整个界面崩。
            communityBounds = null;
        }
    }

    /** 上次绘制社团文字时的像素包围盒 [x1, y1, x2, y2]；null 表示当前不可点。 */
    private int[] communityBounds;
    private final java.util.List<int[]> titleClickBounds = new java.util.ArrayList<>();
    private final java.util.List<String> titleClickIds = new java.util.ArrayList<>();
    private int[] dailyRerollBounds;
    private String dailyRerollGroup;
    private int[] dailyExemptBounds;
    private String dailyExemptGroup;

    /** 任务名称悬浮 Tooltip。 */
    private void renderTaskTooltip(DrawContext context, int mouseX, int mouseY) {
        // 每日(1)/每周(2)/称号(4) 三个 tab 才需要 Tooltip。
        if (tab != 1 && tab != 2 && tab != 4) {
            return;
        }

        int firstRow = page * layout.rowsPerPage();

        for (int index = 0; index < visibleRowCount(); index++) {
            Row row = rows.get(firstRow + index);

            if (row.tooltip() == null) {
                continue;
            }

            // nameWidth<=0 时退化为整行都可悬浮（称号列表没有固定 nameWidth）。
            int nameWidth = row.nameWidth() > 0 ? row.nameWidth()
                    : (layout.actionButtonX() - layout.panelX() - 6);
            int x1 = layout.panelX() + 4;
            int x2 = x1 + nameWidth;
            int y1 = layout.rowY(index);
            int y2 = y1 + layout.rowHeight();

            if (mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2) {
                try {
                    context.drawTooltip(this.textRenderer, List.of(row.tooltip()), mouseX, mouseY);
                } catch (RuntimeException ignored) {
                    // Tooltip 画不出来（比如靠边溢出）不影响主界面。
                }
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        boolean handled = super.mouseClicked(click, doubled);

        if (handled) {
            return true;
        }

        if (button == 0) {
            // 首页：社团文字点击 → 本地浏览器打开链接。
            if (tab == 0 && communityBounds != null
                    && mouseX >= communityBounds[0] && mouseX <= communityBounds[2]
                    && mouseY >= communityBounds[1] && mouseY <= communityBounds[3]) {
                openCommunityUrl();
                return true;
            }

            // 称号：点击已拥有的称号装备
            if (tab == 4) {
                for (int i = 0; i < titleClickBounds.size(); i++) {
                    int[] b = titleClickBounds.get(i);
                    if (mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3]) {
                        ClientNetworking.sendAction(NetActions.ClientAction.EQUIP_TITLE, titleClickIds.get(i));
                        rebuild();
                        return true;
                    }
                }
            }

            // 每日：刷新/跳过按钮（手动命中检测）
            if (tab == 1) {
                if (dailyRerollBounds != null
                        && mouseX >= dailyRerollBounds[0] && mouseX <= dailyRerollBounds[2]
                        && mouseY >= dailyRerollBounds[1] && mouseY <= dailyRerollBounds[3]) {
                    ClientNetworking.sendAction(NetActions.ClientAction.REROLL, dailyRerollGroup);
                    return true;
                }
                if (dailyExemptBounds != null
                        && mouseX >= dailyExemptBounds[0] && mouseX <= dailyExemptBounds[2]
                        && mouseY >= dailyExemptBounds[1] && mouseY <= dailyExemptBounds[3]) {
                    ClientNetworking.sendAction(NetActions.ClientAction.EXEMPT, dailyExemptGroup);
                    return true;
                }
            }

            // 每日/每周：点击任务条目（非按钮区）切换选中。
            if (tab == 1 || tab == 2) {
                int firstRow = page * layout.rowsPerPage();

                for (int index = 0; index < visibleRowCount(); index++) {
                    Row row = rows.get(firstRow + index);

                    if (row.rowKey() == null) {
                        continue;
                    }

                    int y1 = layout.rowY(index);
                    int y2 = y1 + layout.rowHeight();
                    int bodyRight = layout.actionButtonX() - 2;

                    if (mouseX >= layout.panelX() + 2 && mouseX <= bodyRight
                            && mouseY >= y1 && mouseY <= y2) {
                        selectedRowKey = row.rowKey().equals(selectedRowKey) ? null : row.rowKey();
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** 在玩家本地浏览器打开社团链接；空链接/异常都安静忽略。 */
    private void openCommunityUrl() {
        String url = guiStyle().communityUrl;

        if (url == null || url.isBlank()) {
            return;
        }

        try {
            Util.getOperatingSystem().open(url);
        } catch (RuntimeException e) {
            // 打开浏览器失败不应该让界面崩。
        }
    }

    @Override
    public boolean shouldPause() {
        // 战令界面是"查阅型"界面：打开时不暂停单人游戏，避免给玩家造成"卡住了"的错觉。
        return false;
    }

    /** @return 当前生效的 GUI 视觉配置；永远非 null（字段自带默认值）。 */
    private ModSnapshots.GuiStyle guiStyle() {
        ModSnapshots.GuiStyle style = ClientNetworking.state().player().guiStyle;
        return style == null ? new ModSnapshots.GuiStyle() : style;
    }

    private int visibleRowCount() {
        if (layout == null) {
            return 0;
        }

        return Math.min(layout.rowsPerPage(), Math.max(0, rows.size() - page * layout.rowsPerPage()));
    }

    private int pageCount() {
        if (layout == null) {
            return 1;
        }

        return Math.max(1, (rows.size() + layout.rowsPerPage() - 1) / layout.rowsPerPage());
    }

    // ------------------------------------------------------------------
    // 各分页的内容
    // ------------------------------------------------------------------

    private void buildRows() {
        List<Row> built = new ArrayList<>();

        switch (tab) {
            case 1:
                buildDaily(built);
                break;
            case 2:
                buildWeekly(built);
                break;
            case 3:
                buildShop(built);
                break;
            case 4:
                buildTitles(built);
                break;
            case 5:
                buildCollection(built);
                break;
            default:
                buildHome(built);
                break;
        }

        rows = built;

        // 内容变少时把页码夹回范围内，避免停在空白页。
        page = Math.min(page, pageCount() - 1);
    }

    private static final int CARD_BG = 0x60202020;
    private static final int CARD_BORDER = 0xFF555555;
    private static final int CARD_BORDER_GOLD = 0xFFFFAA00;
    private static final int XP_BAR_BG = 0xFF303030;
    private static final int XP_BAR_FILL = 0xFF44AA44;

    /** 首页自定义卡片式渲染（不走 Row 列表）。 */
    private void renderHomeCustom(DrawContext context, int mouseX, int mouseY) {
        ModSnapshots.Player p = ClientNetworking.state().player();
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y = layout.contentTop();
        int textColor = 0xFFCCCCCC;
        int dimColor = 0xFF888888;

        // 欢迎语
        String playerName = this.client != null && this.client.player != null
                ? this.client.player.getName().getString() : "";
        String template = p.guiStyle.welcomeText == null || p.guiStyle.welcomeText.isBlank()
                ? "欢迎您，{player}" : p.guiStyle.welcomeText;
        String labelColor = p.guiStyle.welcomeColor == null ? "§7" : p.guiStyle.welcomeColor;
        String nameColor = p.guiStyle.welcomePlayerColor == null ? "§f" : p.guiStyle.welcomePlayerColor;
        String[] parts = template.split("\\{player}", 2);
        MutableText welcome = Text.literal(labelColor + parts[0]);
        welcome.append(Text.literal(nameColor + playerName));
        if (parts.length > 1 && !parts[1].isEmpty()) {
            welcome.append(Text.literal(labelColor + parts[1]));
        }
        context.drawTextWithShadow(this.textRenderer, welcome, px, y, 0xFFFFFFFF);
        y += 14;

        // ── Hero 卡片：等级 + 经验条 ──
        int heroH = 44;
        context.fill(px, y, px + pw, y + heroH, CARD_BG);
        context.fill(px, y, px + pw, y + 1, CARD_BORDER);
        context.fill(px, y + heroH - 1, px + pw, y + heroH, CARD_BORDER);
        context.fill(px, y, px + 1, y + heroH, CARD_BORDER);
        context.fill(px + pw - 1, y, px + pw, y + heroH, CARD_BORDER);

        // 大号等级数字
        String lvText = "Lv." + p.level;
        int lvW = this.textRenderer.getWidth(lvText);
        context.drawTextWithShadow(this.textRenderer, lvText, px + 8, y + 6, 0xFFFFDD44);
        // 赛季名在右边
        String seasonText = p.themeName + " (" + p.seasonId + ")";
        int sw = this.textRenderer.getWidth(seasonText);
        context.drawTextWithShadow(this.textRenderer, seasonText, px + pw - sw - 8, y + 6, dimColor);

        // 经验条
        int barX = px + 8;
        int barW = pw - 16;
        int barY = y + 22;
        int barH = 6;
        context.fill(barX, barY, barX + barW, barY + barH, XP_BAR_BG);
        if (p.xpForNext > 0) {
            int fillW = Math.max(2, Math.min(barW, barW * p.xp / p.xpForNext));
            context.fill(barX, barY, barX + fillW, barY + barH, XP_BAR_FILL);
        }
        // 经验数字
        String xpText = p.xp + " / " + p.xpForNext + " 经验";
        context.drawTextWithShadow(this.textRenderer, xpText, barX, barY + 8, textColor);
        // 今日剩余
        String dailyText = p.remainingDailyXp < 0 ? "今日经验不限" : "今日剩余可获取：" + p.remainingDailyXp;
        int dw = this.textRenderer.getWidth(dailyText);
        context.drawTextWithShadow(this.textRenderer, dailyText, px + pw - dw - 8, barY + 8, dimColor);

        y += heroH + 8;

        // ── 等级轨道（通行证样式） ──
        if (p.guiStyle.showLevelAxis) {
            int axisH = 36;
            context.fill(px, y, px + pw, y + axisH, CARD_BG);
            context.fill(px, y, px + pw, y + 1, CARD_BORDER);
            context.fill(px, y + axisH - 1, px + pw, y + axisH, CARD_BORDER);
            context.fill(px, y, px + 1, y + axisH, CARD_BORDER);
            context.fill(px + pw - 1, y, px + pw, y + axisH, CARD_BORDER);

            int lineY = y + 18;
            int axisLeft = px + 12;
            int axisRight = px + pw - 12;
            int axisW = axisRight - axisLeft;
            int maxLv = p.maxLevel;

            // 连接线
            int lineColor = safeColor(p.guiStyle.levelAxisLine, 0xFF888888);
            context.fill(axisLeft, lineY, axisRight, lineY + 1, lineColor);

            // 每个等级节点
            int hoverLv = -1;
            for (int lv = 1; lv <= maxLv; lv++) {
                float frac = (maxLv <= 1) ? 0 : (float) (lv - 1) / (maxLv - 1);
                int nx = axisLeft + Math.round(frac * axisW);

                // 悬停检测
                if (Math.abs(mouseX - nx) <= 6 && mouseY >= y + 4 && mouseY <= y + axisH - 4) {
                    hoverLv = lv;
                }

                int nodeColor;
                if (lv < p.level) {
                    nodeColor = safeColor(p.guiStyle.levelAxisCompleted, 0xFF55AA55);
                } else if (lv == p.level) {
                    nodeColor = safeColor(p.guiStyle.levelAxisCurrent, 0xFFFFFF55);
                } else {
                    nodeColor = safeColor(p.guiStyle.levelAxisLocked, 0xFF555555);
                }

                // 节点圆点
                int r = (lv == p.level) ? 3 : 2;
                context.fill(nx - r, lineY - r, nx + r, lineY + r, nodeColor);

                // 等级数字（每5级标一个 + 当前级）
                if (lv == 1 || lv == maxLv || lv % 5 == 0 || lv == p.level) {
                    String label = String.valueOf(lv);
                    int lw = this.textRenderer.getWidth(label);
                    context.drawTextWithShadow(this.textRenderer, label, nx - lw / 2, lineY + 5, nodeColor);
                }

                // 奖励标注（有奖励的等级）
                if (p.levelRewards != null && p.levelRewards.containsKey(lv)) {
                    String reward = p.levelRewards.get(lv);
                    int rw = this.textRenderer.getWidth(reward);
                    int ry = (lv == p.level) ? lineY - 12 : lineY + 15;
                    int rc = safeColor(p.guiStyle.levelAxisReward, 0xFFFFAA00);
                    context.drawTextWithShadow(this.textRenderer, reward, nx - rw / 2, ry, rc);
                }
            }

            // 悬停 tooltip
            if (hoverLv > 0) {
                String reward = (p.levelRewards != null && p.levelRewards.containsKey(hoverLv))
                        ? p.levelRewards.get(hoverLv) : "无奖励";
                String status = hoverLv < p.level ? "§a已达成"
                        : hoverLv == p.level ? "§e当前等级" : "§8未解锁";
                context.drawTooltip(this.textRenderer,
                        java.util.List.of(
                                Text.literal("§fLv." + hoverLv + "  " + status),
                                Text.literal("§7奖励：§6" + reward)),
                        mouseX, mouseY);
            }

            y += axisH + 8;
        }

        // ── 事件卡片 ──
        int evtH = p.eventActive ? 50 : 28;
        context.fill(px, y, px + pw, y + evtH, CARD_BG);
        int evtBorder = p.eventActive ? CARD_BORDER_GOLD : CARD_BORDER;
        context.fill(px, y, px + pw, y + 1, evtBorder);
        context.fill(px, y + evtH - 1, px + pw, y + evtH, evtBorder);
        context.fill(px, y, px + 1, y + evtH, evtBorder);
        context.fill(px + pw - 1, y, px + pw, y + evtH, evtBorder);

        context.drawTextWithShadow(this.textRenderer, "◆ 进行中的事件", px + 6, y + 4,
                p.eventActive ? 0xFFFFCC44 : dimColor);

        if (p.eventActive) {
            String remain = formatRemaining(p.eventRemainingSeconds);
            context.drawTextWithShadow(this.textRenderer,
                    "§6" + p.eventName + " §7(" + p.eventTypeLabel + ")  剩余 §e" + remain,
                    px + 6, y + 16, 0xFFFFFFFF);

            String progressText;
            if ("ONLINE".equals(p.eventParticipationType)) {
                progressText = p.eventPlayerParticipated ? "§a✓ 你已参与" : "§e在线即可参与";
            } else {
                String actionLabel = switch (p.eventParticipationType == null ? "" : p.eventParticipationType) {
                    case "KILL_ENTITY" -> "击杀";
                    case "TRADE" -> "交易";
                    default -> "参与";
                };
                int prog = Math.min(p.eventPlayerProgress, p.eventParticipationTarget);
                progressText = (p.eventPlayerParticipated ? "§a✓ 已参与  " : "")
                        + "§7" + actionLabel + "进度：§f" + prog + "§7/§f" + p.eventParticipationTarget;
            }
            context.drawTextWithShadow(this.textRenderer, progressText, px + 6, y + 28, textColor);

            if (p.eventRewardSummary != null && !p.eventRewardSummary.isEmpty()) {
                context.drawTextWithShadow(this.textRenderer,
                        "§7奖励：§f" + p.eventRewardSummary, px + 6, y + 38, dimColor);
            }
        } else {
            String idleText;
            if (p.eventCooldownSeconds > 60) {
                long mins = p.eventCooldownSeconds / 60;
                idleText = "§7当前无事件，约 §e" + mins + " §7分钟后触发";
            } else {
                idleText = "§7当前无事件，即将触发...";
            }
            context.drawTextWithShadow(this.textRenderer, idleText, px + 6, y + 16, dimColor);
        }

        y += evtH + 8;

        // ── 长夜状态 ──
        if (p.longNight) {
            int lnH = 22;
            context.fill(px, y, px + pw, y + lnH, CARD_BG);
            context.fill(px, y, px + pw, y + 1, 0xFF6644AA);
            context.fill(px, y + lnH - 1, px + pw, y + lnH, 0xFF6644AA);
            context.fill(px, y, px + 1, y + lnH, 0xFF6644AA);
            context.fill(px + pw - 1, y, px + pw, y + lnH, 0xFF6644AA);
            context.drawTextWithShadow(this.textRenderer,
                    "§5长夜降临 §7剩余 §d" + p.longNightMinutesRemaining + " 分钟 §7（经验获取减半）",
                    px + 6, y + 6, 0xFFFFFFFF);
            y += lnH + 8;
        }

        // ── 双列：资源 + 分支 ──
        int colW = (pw - 8) / 2;
        int colH = 44;
        // 左列：资源
        context.fill(px, y, px + colW, y + colH, CARD_BG);
        context.fill(px, y, px + colW, y + 1, CARD_BORDER);
        context.fill(px, y + colH - 1, px + colW, y + colH, CARD_BORDER);
        context.fill(px, y, px + 1, y + colH, CARD_BORDER);
        context.fill(px + colW - 1, y, px + colW, y + colH, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "资源", px + 6, y + 4, dimColor);
        context.drawTextWithShadow(this.textRenderer, "§e京币：§f" + p.starCoin, px + 6, y + 16, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "§b任务卡：§f" + p.exemptCards + " §7张", px + 6, y + 28, textColor);

        // 右列：分支
        int rx = px + colW + 8;
        context.fill(rx, y, rx + colW, y + colH, CARD_BG);
        context.fill(rx, y, rx + colW, y + 1, CARD_BORDER);
        context.fill(rx, y + colH - 1, rx + colW, y + colH, CARD_BORDER);
        context.fill(rx, y, rx + 1, y + colH, CARD_BORDER);
        context.fill(rx + colW - 1, y, rx + colW, y + colH, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "分支", rx + 6, y + 4, dimColor);
        String branchKey = "haojing_battlepass.branch." + p.branch.toLowerCase(java.util.Locale.ROOT);
        Text branchText = "NONE".equals(p.branch)
                ? Text.translatable("haojing_battlepass.gui.home.branch_none")
                : Text.translatableWithFallback(branchKey, p.branch);
        context.drawTextWithShadow(this.textRenderer, branchText, rx + 6, y + 16, 0xFFFFFFFF);
        String serverText = "服务器：正常";
        context.drawTextWithShadow(this.textRenderer, serverText, rx + 6, y + 28, textColor);

        // 分支选择提示（如果需要选分支）
        if (p.branchPrompt) {
            y += colH + 6;
            context.drawTextWithShadow(this.textRenderer,
                    "§e▶ " + Text.translatable("haojing_battlepass.gui.home.branch_prompt").getString(),
                    px, y, 0xFFFFFF55);
        }
    }

    /** 画一个卡片边框矩形。 */
    private void drawCard(DrawContext context, int x, int y, int w, int h, int borderColor) {
        context.fill(x, y, x + w, y + h, CARD_BG);
        context.fill(x, y, x + w, y + 1, borderColor);
        context.fill(x, y + h - 1, x + w, y + h, borderColor);
        context.fill(x, y, x + 1, y + h, borderColor);
        context.fill(x + w - 1, y, x + w, y + h, borderColor);
    }

    /** 画进度条。 */
    private void drawMiniButton(DrawContext context, int x, int y, int w, int h, String text, boolean enabled) {
        // 原版 MC 按钮样式
        int bg = enabled ? 0xFF8B8B8B : 0xFF5A5A5A;
        context.fill(x, y, x + w, y + h, bg);
        // 上/左亮边
        context.fill(x, y, x + w, y + 1, enabled ? 0xFFFFFFFF : 0xFFAAAAAA);
        context.fill(x, y, x + 1, y + h, enabled ? 0xFFFFFFFF : 0xFFAAAAAA);
        // 下/右暗边
        context.fill(x, y + h - 1, x + w, y + h, 0xFF3F3F3F);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF3F3F3F);
        // 内凹阴影
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF6F6F6F);
        int tw = this.textRenderer.getWidth(text);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - 8) / 2;
        int color = enabled ? 0xFFFFFFFF : 0xFFAAAAAA;
        context.drawTextWithShadow(this.textRenderer, text, tx, ty, color);
    }

    private int safeColor(String sectionCode, int fallback) {
        Integer c = GuiColors.parseSectionColor(sectionCode);
        return c == null ? fallback : (0xFF000000 | c);
    }

    private void drawProgressBar(DrawContext context, int x, int y, int w, int h, int progress, int target) {
        context.fill(x, y, x + w, y + h, 0xFF303030);
        if (target > 0) {
            int fillW = Math.max(1, Math.min(w, w * Math.min(progress, target) / target));
            int color = progress >= target ? 0xFF44AA44 : 0xFFCCAA33;
            context.fill(x, y, x + fillW, y + h, color);
        }
    }

    private void renderDailyCustom(DrawContext context, int mouseX, int mouseY) {
        ModSnapshots.Tasks tasks = ClientNetworking.state().tasks();
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y = layout.contentTop();
        int dimColor = 0xFF888888;

        String[] groups = {"explore", "build", "general"};
        String chosen = tasks.chosenGroup == null ? "" : tasks.chosenGroup;

        for (String group : groups) {
            String groupName = Text.translatable("haojing_battlepass.gui.group." + group).getString();
            boolean isChosen = group.equals(chosen);
            boolean isLocked = !chosen.isEmpty() && !isChosen;
            int borderColor = isChosen ? 0xFF55AA55 : (isLocked ? 0xFF444444 : CARD_BORDER);

            var lines = tasks.group(group);
            int cardH = 24 + lines.size() * 22 + (isChosen ? 30 : 0);
            if (chosen.isEmpty()) cardH = 40;

            drawCard(context, px, y, pw, cardH, borderColor);
            String status = isChosen ? "已选中" : (isLocked ? "已锁定" : "未选择");
            int statusColor = isChosen ? 0xFF55FF55 : (isLocked ? dimColor : 0xFFFFCC44);
            context.drawTextWithShadow(this.textRenderer, groupName, px + 6, y + 6,
                    isLocked ? dimColor : 0xFFFFFFFF);
            int sw = this.textRenderer.getWidth(status);
            context.drawTextWithShadow(this.textRenderer, status, px + pw - sw - 6, y + 6, statusColor);

            int ly = y + 20;
            for (ModSnapshots.TaskLine line : lines) {
                boolean claimed = "CLAIMED".equals(line.status);
                boolean completed = "COMPLETED".equals(line.status);
                String name = Text.translatableWithFallback("haojing_battlepass.task." + line.id, line.name).getString();
                if (claimed) name = "§7[已跳过] " + name;
                else if (completed) name = "§a[已完成] " + name;
                String progressStr = claimed ? "§7已跳过" : (line.progress + "/" + line.target);
                int nameColor = claimed ? 0xFF666666 : (isLocked ? dimColor : 0xFFFFFFFF);
                context.drawTextWithShadow(this.textRenderer, name, px + 10, ly, nameColor);
                int pw2 = this.textRenderer.getWidth(progressStr);
                context.drawTextWithShadow(this.textRenderer, progressStr, px + pw - pw2 - 10, ly,
                        claimed ? 0xFF666666 : (isLocked ? dimColor : 0xFFCCCCCC));
                if (!claimed) {
                    drawProgressBar(context, px + 10, ly + 12, pw - 20, 4, line.progress, line.target);
                } else {
                    context.fill(px + 10, ly + 13, px + pw - 10, ly + 15, 0xFF444444);
                }

                if (mouseX >= px + 6 && mouseX <= px + pw - 6 && mouseY >= ly && mouseY <= ly + 18) {
                    Text tip = Text.translatableWithFallback("haojing_battlepass.task." + line.id + ".desc",
                            line.desc == null ? "" : line.desc);
                    context.drawTooltip(this.textRenderer, List.of(tip), mouseX, mouseY);
                }

                ly += 22;
            }

            if (isChosen) {
                ly += 4;
                // 手动画刷新/跳过按钮
                int btnLy = ly;
                drawMiniButton(context, px + 10, btnLy, 44, 12, "刷新",
                        mouseX >= px + 10 && mouseX <= px + 54 && mouseY >= btnLy && mouseY <= btnLy + 12);
                ModSnapshots.Player self = ClientNetworking.state().player();
                boolean hasCards = self.exemptCards > 0;
                drawMiniButton(context, px + 60, btnLy, 52, 12,
                        hasCards ? "跳过" : "跳过(无卡)", hasCards);
            }

            y += cardH + 6;
        }
    }

    private void renderWeeklyCustom(DrawContext context, int mouseX, int mouseY) {
        ModSnapshots.Tasks tasks = ClientNetworking.state().tasks();
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y = layout.contentTop();
        int dimColor = 0xFF888888;

        int cardH = 20 + tasks.weekly.size() * 48;
        drawCard(context, px, y, pw, cardH, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "每周挑战", px + 6, y + 6, 0xFFFFFFFF);

        int ly = y + 22;
        for (ModSnapshots.TaskLine line : tasks.weekly) {
            String name = Text.translatableWithFallback("haojing_battlepass.task." + line.id, line.name).getString();
            boolean done = line.progress >= line.target;
            String status = done ? "已完成" : (line.progress > 0 ? "进行中" : "未开始");
            int statusColor = done ? 0xFF55FF55 : (line.progress > 0 ? 0xFFFFCC44 : dimColor);

            context.drawTextWithShadow(this.textRenderer, name, px + 10, ly, 0xFFFFFFFF);
            int sw = this.textRenderer.getWidth(status);
            context.drawTextWithShadow(this.textRenderer, status, px + pw - sw - 10, ly, statusColor);

            String progressStr = line.progress + "/" + line.target;
            context.drawTextWithShadow(this.textRenderer, progressStr, px + 10, ly + 12, 0xFFCCCCCC);
            drawProgressBar(context, px + 10, ly + 24, pw - 20, 5, line.progress, line.target);

            // 悬停 tooltip
            if (mouseX >= px + 6 && mouseX <= px + pw - 6 && mouseY >= ly && mouseY <= ly + 36) {
                Text tip = Text.translatableWithFallback("haojing_battlepass.task." + line.id + ".desc",
                        line.desc == null ? "" : line.desc);
                context.drawTooltip(this.textRenderer, List.of(tip), mouseX, mouseY);
            }

            ly += 48;
        }
    }

    private void renderShopCustom(DrawContext context, int mouseX, int mouseY) {
        ModSnapshots.Shop shop = ClientNetworking.state().shop();
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y = layout.contentTop();
        int dimColor = 0xFF888888;

        // 余额
        context.drawTextWithShadow(this.textRenderer, "京币余额：" + shop.starCoin, px, y, 0xFFFFDD44);
        y += 16;

        int cardH = 24 + shop.items.size() * 24;
        drawCard(context, px, y, pw, cardH, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "商品列表", px + 6, y + 6, 0xFFFFFFFF);

        int ly = y + 22;
        for (ModSnapshots.ShopLine item : shop.items) {
            String name = Text.translatableWithFallback(item.nameKey, item.id).getString();
            boolean limitReached = item.limitPerPlayer > 0 && item.purchased >= item.limitPerPlayer;
            boolean affordable = shop.starCoin >= item.price;

            int nameColor = limitReached ? dimColor : 0xFFFFFFFF;
            context.drawTextWithShadow(this.textRenderer, name, px + 10, ly, nameColor);

            // 按钮占位宽度（与 addCustomButtons 里一致）
            String btnText = limitReached ? "已售罄" : "购买";
            int btnW = this.textRenderer.getWidth(btnText) + 8;
            int btnRight = px + pw - 10;
            int btnLeft = btnRight - btnW;

            String priceStr = item.price + " 京币";
            int priceW = this.textRenderer.getWidth(priceStr);
            context.drawTextWithShadow(this.textRenderer, priceStr, btnLeft - 8 - priceW, ly,
                    limitReached ? dimColor : 0xFFFFDD44);

            if (item.limitPerPlayer > 0) {
                String limitStr = "限购" + item.purchased + "/" + item.limitPerPlayer;
                int limitW = this.textRenderer.getWidth(limitStr);
                context.drawTextWithShadow(this.textRenderer, limitStr, btnLeft - 16 - priceW - limitW, ly, dimColor);
            }

            ly += 24;
        }
    }

    private void renderTitlesCustom(DrawContext context, int mouseX, int mouseY) {
        ModSnapshots.Titles titles = ClientNetworking.state().titles();
        ModSnapshots.Player self = ClientNetworking.state().player();
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y = layout.contentTop();
        int dimColor = 0xFF888888;

        // 开关
        context.drawTextWithShadow(this.textRenderer, "聊天称号：" + (self.chatTitleVisible ? "开启" : "关闭"),
                px, y, 0xFFFFFFFF);
        int sw = this.textRenderer.getWidth("切换");
        context.drawTextWithShadow(this.textRenderer, "切换", px + pw - sw - 10, y, 0xFFCCCCCC);
        y += 14;
        context.drawTextWithShadow(this.textRenderer, "头顶称号：" + (self.nametagTitleVisible ? "开启" : "关闭"),
                px, y, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "切换", px + pw - sw - 10, y, 0xFFCCCCCC);
        y += 14;

        // 已装备卡片（不依赖 definitions 匹配）
        if (!titles.equipped.isEmpty()) {
            String eqName = titleText(titles.equipped).getString();
            if (titles.definitions != null) {
                for (ModSnapshots.TitleDef def : titles.definitions) {
                    if (def.id.equals(titles.equipped) && def.name != null && !def.name.isEmpty()) {
                        eqName = def.name;
                        break;
                    }
                }
            }
            drawCard(context, px, y, pw, 28, 0xFF55AA55);
            context.drawTextWithShadow(this.textRenderer, "当前装备", px + 6, y + 5, dimColor);
            context.drawTextWithShadow(this.textRenderer, eqName, px + 6, y + 15, 0xFFFFDD44);
            y += 34;
        }

        // 已拥有 / 未拥有
        java.util.List<ModSnapshots.TitleDef> owned = new java.util.ArrayList<>();
        java.util.List<ModSnapshots.TitleDef> locked = new java.util.ArrayList<>();
        if (titles.definitions != null) {
            for (ModSnapshots.TitleDef def : titles.definitions) {
                if (def.id.equals(titles.equipped)) continue; // 已在装备卡片里
                boolean un = titles.unlocked != null && titles.unlocked.contains(def.id);
                if (un) owned.add(def);
                else locked.add(def);
            }
        }

        titleClickBounds.clear();
        titleClickIds.clear();

        if (!owned.isEmpty()) {
            int rows = (owned.size() + 1) / 2;
            drawCard(context, px, y, pw, 24 + rows * 14, CARD_BORDER);
            context.drawTextWithShadow(this.textRenderer, "已拥有（点击装备）", px + 6, y + 5, dimColor);
            int colW = (pw - 16) / 2;
            for (int i = 0; i < owned.size(); i++) {
                ModSnapshots.TitleDef def = owned.get(i);
                String name = (def.name != null && !def.name.isEmpty())
                        ? def.name : titleText(def.id).getString();
                int col = i % 2;
                int row = i / 2;
                int tx = px + 10 + col * colW;
                int ty = y + 16 + row * 12;
                context.drawTextWithShadow(this.textRenderer, name, tx, ty, 0xFFFFFFFF);
                titleClickBounds.add(new int[]{tx - 2, ty - 2, tx + colW - 10, ty + 12});
                titleClickIds.add(def.id);

                // 悬停 tooltip
                if (mouseX >= tx - 2 && mouseX <= tx + colW - 10 && mouseY >= ty - 2 && mouseY <= ty + 12) {
                    Text tip = titleTooltip(def, true, false);
                    context.drawTooltip(this.textRenderer, java.util.List.of(tip), mouseX, mouseY);
                }
            }
            y += 24 + rows * 14 + 6;
        }

        if (!locked.isEmpty()) {
            int rows = (locked.size() + 1) / 2;
            drawCard(context, px, y, pw, 24 + rows * 12, 0xFF444444);
            context.drawTextWithShadow(this.textRenderer, "未拥有", px + 6, y + 5, dimColor);
            for (int i = 0; i < locked.size(); i++) {
                ModSnapshots.TitleDef def = locked.get(i);
                String name = (def.name != null && !def.name.isEmpty())
                        ? def.name : titleText(def.id).getString();
                int col = i % 2;
                int row = i / 2;
                int colW = (pw - 16) / 2;
                context.drawTextWithShadow(this.textRenderer, name, px + 10 + col * colW, y + 16 + row * 12,
                        dimColor);
            }
            y += 24 + rows * 12 + 6;
        }
    }

    private void renderCollectionCustom(DrawContext context) {
        ModSnapshots.Collection col = ClientNetworking.state().collection();
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y = layout.contentTop();
        int dimColor = 0xFF888888;

        context.drawTextWithShadow(this.textRenderer, "本赛季：" + col.seasonId, px, y, 0xFFFFFFFF);
        y += 16;

        // 本季彩蛋
        int h1 = 24 + Math.max(1, col.seasonEggs.size()) * 14;
        drawCard(context, px, y, pw, h1, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "本季彩蛋", px + 6, y + 5, dimColor);
        if (col.seasonEggs.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "暂无", px + 10, y + 18, dimColor);
        } else {
            int ly = y + 18;
            for (String egg : col.seasonEggs) {
                context.drawTextWithShadow(this.textRenderer, egg, px + 10, ly, 0xFFFFFFFF);
                ly += 14;
            }
        }
        y += h1 + 6;

        // 历史留档
        int h2 = 24 + Math.max(1, col.historySeasons.size()) * 14;
        drawCard(context, px, y, pw, h2, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "历史留档", px + 6, y + 5, dimColor);
        context.drawTextWithShadow(this.textRenderer, "历史留档彩蛋：" + col.allEggs.size() + " 个",
                px + 10, y + 18, 0xFFFFFFFF);
        y += h2 + 6;

        // 里程碑
        int h3 = 24 + Math.max(1, col.milestones.size()) * 14;
        drawCard(context, px, y, pw, h3, CARD_BORDER);
        context.drawTextWithShadow(this.textRenderer, "里程碑", px + 6, y + 5, dimColor);
        if (col.milestones.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "暂无", px + 10, y + 18, dimColor);
        } else {
            int ly = y + 18;
            for (String m : col.milestones) {
                context.drawTextWithShadow(this.textRenderer, m, px + 10, ly, 0xFFFFFFFF);
                ly += 14;
            }
        }
    }

    /** 旧版行列表渲染（兜底用）。 */
    private void renderRowList(DrawContext context) {
        ModSnapshots.GuiStyle style = guiStyle();
        int firstRow = page * layout.rowsPerPage();

        for (int index = 0; index < visibleRowCount(); index++) {
            Row row = rows.get(firstRow + index);
            int rowTop = layout.rowY(index);
            int rowBottom = rowTop + layout.rowHeight();

            if (row.rowKey() != null && row.rowKey().equals(selectedRowKey)) {
                int bodyRight = layout.actionButtonX() - 2;
                context.fill(layout.panelX() + 2, rowTop, bodyRight, rowBottom, style.selectionDimArgb);
                context.fill(layout.panelX() + 2, rowTop, bodyRight, rowTop + 1, style.selectionBorderColor);
                context.fill(layout.panelX() + 2, rowBottom - 1, bodyRight, rowBottom, style.selectionBorderColor);
                context.fill(layout.panelX() + 2, rowTop, layout.panelX() + 3, rowBottom, style.selectionBorderColor);
                context.fill(bodyRight - 1, rowTop, bodyRight, rowBottom, style.selectionBorderColor);
            }

            context.drawTextWithShadow(this.textRenderer, row.text(),
                    layout.panelX() + 4, rowTop + 2, GuiColors.TEXT);
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("haojing_battlepass.gui.page", page + 1, pageCount()),
                layout.panelX() + layout.panelWidth() / 2 - 24, layout.footerY() + 4, GuiColors.TEXT_DIM);
    }

    /** 为自定义卡片渲染的 tab 按卡片位置添加可点击按钮。 */
    private void addCustomButtons() {
        int px = layout.panelX() + 6;
        int pw = layout.panelWidth() - 12;
        int y0 = layout.contentTop();

        if (tab == 1) { // 每日
            ModSnapshots.Tasks tasks = ClientNetworking.state().tasks();
            String chosen = tasks.chosenGroup == null ? "" : tasks.chosenGroup;
            String[] groups = {"explore", "build", "general"};
            int cy = y0;
            for (String group : groups) {
                var lines = tasks.group(group);
                boolean isChosen = group.equals(chosen);
                boolean isLocked = !chosen.isEmpty() && !isChosen;
                int cardH = 24 + lines.size() * 22 + (isChosen ? 30 : 0);
                if (chosen.isEmpty()) cardH = 40;

                if (isChosen) {
                    int ly = cy + 20 + lines.size() * 22 + 4;
                    dailyRerollBounds = new int[]{px + 10, ly, px + 54, ly + 12};
                    dailyRerollGroup = group;
                    ModSnapshots.Player self = ClientNetworking.state().player();
                    boolean hasCards = self.exemptCards > 0;
                    if (hasCards) {
                        dailyExemptBounds = new int[]{px + 60, ly, px + 112, ly + 12};
                        dailyExemptGroup = group;
                    } else {
                        dailyExemptBounds = null;
                        dailyExemptGroup = null;
                    }
                } else {
                    dailyRerollBounds = null;
                    dailyRerollGroup = null;
                    dailyExemptBounds = null;
                    dailyExemptGroup = null;
                }
                cy += cardH + 6;
            }
        } else if (tab == 3) { // 商店
            ModSnapshots.Shop shop = ClientNetworking.state().shop();
            int cy = y0 + 16;
            int ly = cy + 22;
            for (ModSnapshots.ShopLine item : shop.items) {
                boolean limitReached = item.limitPerPlayer > 0 && item.purchased >= item.limitPerPlayer;
                boolean affordable = shop.starCoin >= item.price;
                String btnText = limitReached ? "已售罄" : "购买";
                int btnW = this.textRenderer.getWidth(btnText) + 8;
                ButtonWidget buy = ButtonWidget.builder(Text.literal(btnText), w -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.BUY, item.id);
                    rebuild();
                }).dimensions(px + pw - btnW - 10, ly - 2, btnW, 12).build();
                buy.active = affordable && !limitReached;
                addDrawableChild(buy);
                ly += 24;
            }
        } else if (tab == 4) { // 称号
            ModSnapshots.Titles titles = ClientNetworking.state().titles();
            ModSnapshots.Player self = ClientNetworking.state().player();
            // 聊天切换
            ButtonWidget chatToggle = ButtonWidget.builder(Text.literal("切换"), w -> {
                ClientNetworking.sendAction(NetActions.ClientAction.TOGGLE_CHAT_TITLE, "");
                rebuild();
            }).dimensions(px + pw - 50, y0 - 2, 44, 12).build();
            addDrawableChild(chatToggle);
            // 头顶切换
            ButtonWidget nameToggle = ButtonWidget.builder(Text.literal("切换"), w -> {
                ClientNetworking.sendAction(NetActions.ClientAction.TOGGLE_NAMETAG_TITLE, "");
                rebuild();
            }).dimensions(px + pw - 50, y0 + 12, 44, 12).build();
            addDrawableChild(nameToggle);

            int cy = y0 + 28;
            // 已装备卸下按钮
            if (!titles.equipped.isEmpty()) {
                ButtonWidget unequip = ButtonWidget.builder(Text.literal("卸下"), w -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.EQUIP_TITLE, "");
                    rebuild();
                }).dimensions(px + pw - 50, cy + 8, 44, 12).build();
                addDrawableChild(unequip);
                cy += 34;
            }
        }
    }

    private void buildHome(List<Row> built) {
        ModSnapshots.Player player = ClientNetworking.state().player();

        // 顶部欢迎语（文字/颜色均可在 season.json 的 gui 节点配置）
        String playerName = this.client != null && this.client.player != null
                ? this.client.player.getName().getString() : "";
        String template = player.guiStyle.welcomeText == null || player.guiStyle.welcomeText.isBlank()
                ? "欢迎您，{player}" : player.guiStyle.welcomeText;
        String labelColor = player.guiStyle.welcomeColor == null ? "§7" : player.guiStyle.welcomeColor;
        String nameColor = player.guiStyle.welcomePlayerColor == null ? "§f" : player.guiStyle.welcomePlayerColor;
        String[] parts = template.split("\\{player}", 2);
        MutableText welcome = Text.literal(labelColor + parts[0]);
        welcome.append(Text.literal(nameColor + playerName));
        if (parts.length > 1 && !parts[1].isEmpty()) {
            welcome.append(Text.literal(labelColor + parts[1]));
        }
        built.add(row(welcome));

        // ── 随机事件区块 ──
        if (player.eventActive) {
            built.add(divider("进行中的事件"));
            String remain = formatRemaining(player.eventRemainingSeconds);
            MutableText evtLine = Text.literal("§6" + player.eventName + " §r§7(" + player.eventTypeLabel + ")  剩余 §e" + remain);
            built.add(row(evtLine));

            // 参与要求
            String progressText;
            if ("ONLINE".equals(player.eventParticipationType)) {
                progressText = player.eventPlayerParticipated ? "§a✓ 你已参与" : "§e在线即可参与";
            } else {
                String actionLabel = switch (player.eventParticipationType == null ? "" : player.eventParticipationType) {
                    case "KILL_ENTITY" -> "击杀";
                    case "TRADE" -> "交易";
                    default -> "参与";
                };
                int prog = Math.min(player.eventPlayerProgress, player.eventParticipationTarget);
                progressText = (player.eventPlayerParticipated ? "§a✓ 已参与  " : "")
                        + "§7" + actionLabel + "进度：§f" + prog + "§7/§f" + player.eventParticipationTarget;
            }
            built.add(row(Text.literal(progressText)));

            if (player.eventRewardSummary != null && !player.eventRewardSummary.isEmpty()) {
                built.add(row(Text.literal("§7奖励：§f" + player.eventRewardSummary)));
            }
        } else {
            built.add(divider("随机事件"));
            if (player.eventCooldownSeconds > 60) {
                long mins = player.eventCooldownSeconds / 60;
                built.add(row(Text.literal("§7当前无进行中的事件，约 §e" + mins + " §7分钟后可触发")));
            } else {
                built.add(row(Text.literal("§7当前无进行中的事件，事件即将触发...")));
            }
        }

        built.add(divider("赛季"));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.season", player.themeName, player.seasonId)));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.level", player.level, player.maxLevel)));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.xp", player.xp, player.xpForNext)));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.remaining_xp",
                player.remainingDailyXp < 0
                        ? Text.translatable("haojing_battlepass.gui.unlimited")
                        : Text.literal(String.valueOf(player.remainingDailyXp)))));

        built.add(divider("资源"));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.star_coin", player.starCoin)));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.cards", player.exemptCards)));
        built.add(row(Text.translatable("haojing_battlepass.gui.home.reroll", player.rerollUsed, player.rerollLimit)));

        built.add(divider("分支"));
        String branchKey = "haojing_battlepass.branch." + player.branch.toLowerCase(java.util.Locale.ROOT);
        Text branchText = "NONE".equals(player.branch)
                ? Text.translatable("haojing_battlepass.gui.home.branch_none")
                : Text.translatableWithFallback(branchKey, player.branch);

        if (player.branchPrompt) {
            built.add(new Row(Text.translatable("haojing_battlepass.gui.home.branch_prompt"),
                    "haojing_battlepass.gui.choose_branch", () -> {
                if (this.client != null) {
                    this.client.setScreen(new BranchChoiceScreen());
                }
            }, true, null, null, 0, null));
        } else {
            built.add(row(Text.translatable("haojing_battlepass.gui.home.branch", branchText)));
        }

        built.add(row(player.longNight
                ? Text.translatable("haojing_battlepass.gui.home.long_night", player.longNightMinutesRemaining)
                : Text.translatable("haojing_battlepass.gui.home.normal")));
    }

    private void buildDaily(List<Row> built) {
        ModSnapshots.Tasks tasks = ClientNetworking.state().tasks();
        String[] groups = {"explore", "build", "general"};
        String chosen = tasks.chosenGroup == null ? "" : tasks.chosenGroup;
        boolean noneChosen = chosen.isEmpty();

        if (noneChosen) {
            built.add(row(Text.translatable("haojing_battlepass.gui.daily.group_prompt")
                    .formatted(Formatting.YELLOW)));
        }

        for (String group : groups) {
            String groupName = Text.translatable("haojing_battlepass.gui.group." + group).getString();
            boolean isChosen = group.equals(chosen);
            boolean isLocked = !noneChosen && !isChosen;

            // 分组标题行：当前组高亮，锁定组灰色，未选时普通。
            MutableText header = Text.literal("§e§l—— ").copy()
                    .append(Text.translatable("haojing_battlepass.gui.group." + group));
            if (isChosen) {
                header.append(Text.literal("  ").append(
                        Text.translatable("haojing_battlepass.gui.daily.group_active")).formatted(Formatting.GREEN));
            } else if (isLocked) {
                header.append(Text.literal("  ").append(
                        Text.translatable("haojing_battlepass.gui.daily.group_locked")).formatted(Formatting.DARK_GRAY));
            }
            header.append(Text.literal(" §r§e§l——"));
            built.add(row(header.formatted(isLocked ? Formatting.DARK_GRAY : Formatting.RESET)));

            if (noneChosen) {
                // 还没选组：每个组给一个"选择本组"按钮，不展开任务内容。
                built.add(new Row(
                        Text.translatable("haojing_battlepass.gui.daily.reroll", groupName),
                        "haojing_battlepass.gui.daily.group_choose_button", () -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.CHOOSE_DAILY_GROUP, group);
                    rebuild();
                }, true, null, null, 0, null));
                continue;
            }

            for (ModSnapshots.TaskLine line : tasks.group(group)) {
                if (isLocked) {
                    // 锁定组：任务行灰色显示但没有按钮（服务端也会拒绝领奖）。
                    built.add(lockedTaskRow(line));
                    continue;
                }

                built.add(taskRow(line));

                built.add(row(Text.translatable("haojing_battlepass.gui.daily.reroll", groupName),
                        "haojing_battlepass.gui.daily.reroll_button", () -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.REROLL, group);
                    rebuild();
                }, true));

                built.add(row(Text.translatable("haojing_battlepass.gui.daily.exempt", groupName),
                        "haojing_battlepass.gui.daily.exempt_button", () -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.EXEMPT, group);
                    rebuild();
                }, true));
            }
        }

        if (built.isEmpty()) {
            built.add(row(Text.translatable("haojing_battlepass.gui.empty")));
        }
    }

    /** 锁定组的任务行：灰色文字，不可选中、无按钮、无 Tooltip。 */
    private Row lockedTaskRow(ModSnapshots.TaskLine line) {
        MutableText text = Text.translatableWithFallback("haojing_battlepass.task." + line.id, line.name)
                .copy().formatted(Formatting.DARK_GRAY);
        text.append(Text.literal("  ").append(Text.translatable("haojing_battlepass.gui.task.progress",
                line.progress, line.target)).formatted(Formatting.DARK_GRAY));
        return new Row(text, null, null, false, null, null, 0, null);
    }

    private void buildWeekly(List<Row> built) {
        ModSnapshots.Tasks tasks = ClientNetworking.state().tasks();

        built.add(divider("每周挑战"));
        for (ModSnapshots.TaskLine line : tasks.weekly) {
            built.add(taskRow(line));
        }

        if (built.isEmpty()) {
            built.add(row(Text.translatable("haojing_battlepass.gui.empty")));
        }
    }

    private void buildShop(List<Row> built) {
        ModSnapshots.Shop shop = ClientNetworking.state().shop();
        ModSnapshots.GuiStyle style = guiStyle();

        built.add(row(Text.translatable("haojing_battlepass.gui.shop.balance", shop.starCoin)));
        built.add(divider("商品列表"));

        for (ModSnapshots.ShopLine item : shop.items) {
            boolean affordable = shop.starCoin >= item.price;
            boolean limitReached = item.limitPerPlayer > 0 && item.purchased >= item.limitPerPlayer;

            // 商品名按分组着色；价格数字用独立颜色。
            String groupColorCode = item.group == null ? null : style.shopGroupColors.get(item.group);
            Integer groupRgb = groupColorCode == null ? null : GuiColors.parseSectionColor(groupColorCode);
            Integer priceRgb = GuiColors.parseSectionColor(style.shopPriceColor);

            MutableText name = Text.translatableWithFallback(item.nameKey, item.id).copy();

            if (groupRgb != null) {
                name.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(groupRgb)));
            }

            MutableText text = name
                    .append(Text.literal("  "))
                    .append(Text.translatable("haojing_battlepass.gui.shop.price", item.price)
                            .setStyle(priceRgb == null
                                    ? Style.EMPTY.withColor(Formatting.GOLD)
                                    : Style.EMPTY.withColor(TextColor.fromRgb(priceRgb))));

            if (item.limitPerPlayer > 0) {
                text.append(Text.literal("  ")).append(Text.translatable(
                        "haojing_battlepass.gui.shop.limit", item.purchased, item.limitPerPlayer));
            }

            built.add(new Row(text,
                    limitReached ? "haojing_battlepass.gui.shop.sold_out" : "haojing_battlepass.gui.shop.buy",
                    () -> {
                        ClientNetworking.sendAction(NetActions.ClientAction.BUY, item.id);
                        rebuild();
                    }, affordable && !limitReached,
                    null, null, 0, item.group));
        }
    }

    private void buildTitles(List<Row> built) {
        ModSnapshots.Titles titles = ClientNetworking.state().titles();
        ModSnapshots.Player self = ClientNetworking.state().player();

        // 顶部两个显示开关（纯客户端偏好，服务端持久化）。
        built.add(new Row(
                Text.translatable("haojing_battlepass.gui.titles.toggle_chat",
                        self.chatTitleVisible
                                ? Text.translatable("haojing_battlepass.gui.state.on")
                                : Text.translatable("haojing_battlepass.gui.state.off")),
                "haojing_battlepass.gui.titles.toggle", () -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.TOGGLE_CHAT_TITLE, "");
                    rebuild();
                }, true, null, null, 0, null));

        built.add(new Row(
                Text.translatable("haojing_battlepass.gui.titles.toggle_nametag",
                        self.nametagTitleVisible
                                ? Text.translatable("haojing_battlepass.gui.state.on")
                                : Text.translatable("haojing_battlepass.gui.state.off")),
                "haojing_battlepass.gui.titles.toggle", () -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.TOGGLE_NAMETAG_TITLE, "");
                    rebuild();
                }, true, null, null, 0, null));

        built.add(row(Text.translatable("haojing_battlepass.gui.titles.divider")));

        if (!titles.equipped.isEmpty()) {
            built.add(new Row(Text.translatable("haojing_battlepass.gui.titles.equipped",
                    titleText(titles.equipped)), "haojing_battlepass.gui.titles.unequip", () -> {
                ClientNetworking.sendAction(NetActions.ClientAction.EQUIP_TITLE, "");
                rebuild();
            }, true, null, null, 0, null));
        }

        // 全量称号列表（含未解锁灰显）。
        if (titles.definitions != null && !titles.definitions.isEmpty()) {
            for (ModSnapshots.TitleDef def : titles.definitions) {
                boolean unlocked = titles.unlocked != null && titles.unlocked.contains(def.id);
                boolean equipped = def.id.equals(titles.equipped);

                MutableText label = titleDisplayText(def);

                Text tooltip = titleTooltip(def, unlocked, equipped);
                int nameWidth = this.textRenderer == null ? 0 : this.textRenderer.getWidth(label);

                if (equipped) {
                    // 已佩戴的称号：按钮变成"卸下"，可直接点击取下。
                    built.add(new Row(label, "haojing_battlepass.gui.titles.unequip",
                            () -> {
                                ClientNetworking.sendAction(NetActions.ClientAction.EQUIP_TITLE, "");
                                rebuild();
                            }, true, tooltip, "title:" + def.id, nameWidth, null));
                } else if (unlocked) {
                    built.add(new Row(label, "haojing_battlepass.gui.titles.equip", () -> {
                        ClientNetworking.sendAction(NetActions.ClientAction.EQUIP_TITLE, def.id);
                        rebuild();
                    }, true, tooltip, "title:" + def.id, nameWidth, null));
                } else {
                    // 未解锁：灰色、按钮禁用。
                    built.add(new Row(Text.literal("✦ ").append(label).formatted(Formatting.DARK_GRAY),
                            null, null, false, tooltip, "title:" + def.id, nameWidth, null));
                }
            }
        } else {
            // 回退：旧快照没有 definitions 时，至少把已解锁的列出来。
            for (String title : titles.unlocked) {
                boolean equipped = title.equals(titles.equipped);
                built.add(new Row(titleText(title), equipped ? "haojing_battlepass.gui.titles.equipped_short"
                        : "haojing_battlepass.gui.titles.equip", () -> {
                    ClientNetworking.sendAction(NetActions.ClientAction.EQUIP_TITLE, title);
                    rebuild();
                }, !equipped, null, null, 0, null));
            }
        }

        if (built.isEmpty()) {
            built.add(row(Text.translatable("haojing_battlepass.gui.empty")));
        }
    }

    /** 构造一条称号行的显示文本（按 titles.json 颜色）。 */
    private MutableText titleDisplayText(ModSnapshots.TitleDef def) {
        MutableText name = (def.name == null || def.name.isEmpty())
                ? titleText(def.id).copy()
                : Text.literal(def.name);

        Integer argb = GuiColors.parseSectionColor(def.color);
        if (argb != null) {
            name.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb)));
        }
        return name;
    }

    /** 构造称号悬浮 Tooltip：名称 / 获取途径 / 描述 / 装饰标注 / 状态。 */
    private Text titleTooltip(ModSnapshots.TitleDef def, boolean unlocked, boolean equipped) {
        MutableText tip = titleDisplayText(def).copy();

        if (def.acquireHint != null && !def.acquireHint.isEmpty()) {
            tip.append("\n").append(Text.translatable("haojing_battlepass.title.acquire",
                    def.acquireHint).formatted(Formatting.GRAY));
        }
        if (def.description != null && !def.description.isEmpty()) {
            tip.append("\n").append(Text.literal(def.description).formatted(Formatting.GRAY));
        }

        tip.append("\n").append(Text.translatable("haojing_battlepass.title.decorative")
                .formatted(Formatting.DARK_GRAY));

        if (equipped) {
            tip.append("\n").append(Text.translatable("haojing_battlepass.gui.titles.state_equipped")
                    .formatted(Formatting.GREEN));
        } else if (unlocked) {
            tip.append("\n").append(Text.translatable("haojing_battlepass.gui.titles.state_unlocked")
                    .formatted(Formatting.YELLOW));
        } else {
            String hint = def.acquireHint == null || def.acquireHint.isEmpty() ? "?" : def.acquireHint;
            tip.append("\n").append(Text.translatable("haojing_battlepass.gui.titles.state_locked", hint)
                    .formatted(Formatting.DARK_GRAY));
        }

        return tip;
    }

    private void buildCollection(List<Row> built) {
        ModSnapshots.Collection collection = ClientNetworking.state().collection();

        built.add(row(Text.translatable("haojing_battlepass.gui.collection.season", collection.seasonId)));
        built.add(divider("本季彩蛋"));

        for (String egg : collection.seasonEggs) {
            built.add(row(Text.translatable("haojing_battlepass.gui.collection.egg", eggText(egg))));
        }

        built.add(divider("历史留档"));
        built.add(row(Text.translatable("haojing_battlepass.gui.collection.all", collection.allEggs.size())));

        for (String season : collection.historySeasons) {
            built.add(row(Text.translatable("haojing_battlepass.gui.collection.history", season)));
        }

        built.add(divider("里程碑"));
        for (String milestone : collection.milestones) {
            built.add(row(Text.translatable("haojing_battlepass.gui.collection.milestone", milestone)));
        }
    }

    // ------------------------------------------------------------------
    // 文本工具
    // ------------------------------------------------------------------

    /** 一行没有按钮/没有特殊交互的普通文字行。 */
    private Row row(Text text) {
        return new Row(text, null, null, false, null, null, 0, null);
    }

    /** 分隔行：用于把同页内不同小节视觉隔开。 */
    private Row divider(String label) {
        return row(Text.literal("§7§m                                §r §f§l" + label + " §7§m                                §r"));
    }

    /** 一行带动作按钮的文字行。 */
    private Row row(Text text, String actionKey, Runnable action, boolean enabled) {
        return new Row(text, actionKey, action, enabled, null, null, 0, null);
    }

    /**
     * 构造一条任务行：正文 = 名称 + 进度 + 状态；Tooltip = 任务 desc；行可选中。
     */
    private Row taskRow(ModSnapshots.TaskLine line) {
        Text nameText = Text.translatableWithFallback("haojing_battlepass.task." + line.id, line.name);
        int nameWidth = this.textRenderer == null ? 0 : this.textRenderer.getWidth(nameText);

        MutableText text = nameText.copy();
        text.append(Text.literal("  ")).append(Text.translatable("haojing_battlepass.gui.task.progress",
                line.progress, line.target));
        text.append(Text.literal("  ")).append(Text.translatable("haojing_battlepass.task.status." + line.status));

        Text tooltip = (line.desc == null || line.desc.isBlank())
                ? null
                : Text.literal(line.desc);

        return new Row(text, claimKey(line), () -> {
            ClientNetworking.sendAction(NetActions.ClientAction.CLAIM, line.id);
            rebuild();
        }, "COMPLETED".equals(line.status),
                tooltip, line.id, nameWidth, null);
    }

    private static String claimKey(ModSnapshots.TaskLine line) {
        return "COMPLETED".equals(line.status) ? "haojing_battlepass.gui.task.claim" : null;
    }

    /** 称号显示：优先用翻译键，没有译文时退回 ID（管理员新增的称号不会因此显示成空白）。 */
    static Text titleText(String titleId) {
        return Text.translatableWithFallback("haojing_battlepass.title." + titleId, titleId);
    }

    /** 彩蛋名同样优先翻译键；彩蛋名本身在服务端配置里也有中文名，这里只作为兜底。 */
    static Text eggText(String eggId) {
        return Text.translatableWithFallback("haojing_battlepass.egg_name." + eggId, eggId);
    }

    /** 把剩余秒数格式化为 mm:ss。 */
    private static String formatRemaining(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
