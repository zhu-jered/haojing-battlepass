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
        layout = PanelLayout.compute(this.width, this.height, 340, 240, 460, TAB_KEYS.length, 16);

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

        for (int index = 0; index < visibleRowCount(); index++) {
            Row row = rows.get(firstRow + index);

            if (row.actionKey() != null) {
                ButtonWidget action = ButtonWidget.builder(Text.translatable(row.actionKey()), widget -> {
                    if (row.action() != null) {
                        row.action().run();
                    }
                }).dimensions(layout.actionButtonX(), layout.rowY(index) - 2,
                        layout.actionButtonWidth(), layout.rowHeight() - 2).build();

                action.active = row.enabled();
                addDrawableChild(action);
            }
        }

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
        // 绘制顺序（1.21.11 起 super.render 只负责渲染子控件，不再画背景）：
        // ① 面板底色 → ② 行选中底色/边框 → ③ 文字 → ④ super.render 按钮 → ⑤ Tab 高亮 → ⑥ Tooltip。
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
                int firstRow = page * layout.rowsPerPage();

                for (int index = 0; index < visibleRowCount(); index++) {
                    Row row = rows.get(firstRow + index);
                    int rowTop = layout.rowY(index);
                    int rowBottom = rowTop + layout.rowHeight();

                    // ② 选中行：背景加深 + 边框矩形（仿"选择世界"界面）。
                    if (row.rowKey() != null && row.rowKey().equals(selectedRowKey)) {
                        int bodyRight = layout.actionButtonX() - 2;
                        context.fill(layout.panelX() + 2, rowTop, bodyRight, rowBottom,
                                style.selectionDimArgb);
                        // 1 像素边框：上、下、左、右。
                        context.fill(layout.panelX() + 2, rowTop, bodyRight, rowTop + 1,
                                style.selectionBorderColor);
                        context.fill(layout.panelX() + 2, rowBottom - 1, bodyRight, rowBottom,
                                style.selectionBorderColor);
                        context.fill(layout.panelX() + 2, rowTop, layout.panelX() + 3, rowBottom,
                                style.selectionBorderColor);
                        context.fill(bodyRight - 1, rowTop, bodyRight, rowBottom,
                                style.selectionBorderColor);
                    }

                    context.drawTextWithShadow(this.textRenderer, row.text(),
                            layout.panelX() + 4, rowTop + 2, GuiColors.TEXT);
                }

                context.drawTextWithShadow(this.textRenderer,
                        Text.translatable("haojing_battlepass.gui.page", page + 1, pageCount()),
                        layout.panelX() + layout.panelWidth() / 2 - 24, layout.footerY() + 4, GuiColors.TEXT_DIM);

                // 首页右下角：常驻社团可点击文字（不遮挡任何按钮/组件）。
                if (tab == 0) {
                    renderCommunityLine(context, style);
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

    private void buildHome(List<Row> built) {
        ModSnapshots.Player player = ClientNetworking.state().player();

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
}
