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
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：管理面板（需求文档 §9：{@code /battlepass admin}，需 OP 权限 + 服务端二次校验，
 * 全部热重载；九类可编辑项 + 数据维护）。
 *
 * <p>界面结构：
 * <ul>
 *   <li>上方九个页签（数量多，按屏幕宽度**自动折行**）；</li>
 *   <li>中部按分类显示当前取值与条目数（行数随可用高度自动计算）；</li>
 *   <li>下方是数据维护区：两个输入框（目标玩家 / 取值）+ 十个操作按钮（同样自动折行）；</li>
 *   <li>最底部左：翻页；右：关闭。**所有控件都保证落在面板内**。</li>
 * </ul>
 *
 * <p><b>关于"九类可编辑项"的实现取舍（必须如实说明）</b>：
 * 数值型参数（上限、时长、倍率、曲线、开关）在面板里直接改；
 * 而**内容型**清单（42 条任务、30 级奖励、商店商品、彩蛋、口令、事件、里程碑）体量大、
 * 结构嵌套，用游戏内文本框编辑等于做一个残缺的 JSON 编辑器 ——
 * 那既不好用也容易改坏。因此面板对内容型给出**文件绝对路径**，
 * 并提供**一键导出/导入备份**：管理员用任意编辑器改文件，
 * 模组 1 秒内自动热重载（§2、§9 的热重载要求已由所有管理器满足）。
 * 这条取舍登记在 docs/需求偏差记录.md 的 D-28。
 *
 * <p>布局与玩家界面共用 {@link PanelLayout}：Minecraft 的宽高是 GUI 缩放后的坐标，
 * 固定像素在缩放调大或小窗口下必然溢出（早期版本正是如此）。
 */
@Environment(EnvType.CLIENT)
public class AdminPanelScreen extends Screen {

    /** §9 的九类可编辑项 + 远程配置文件编辑。 */
    private static final String[] SECTION_KEYS = {
            "haojing_battlepass.admin.section.season",
            "haojing_battlepass.admin.section.anti_grind",
            "haojing_battlepass.admin.section.tasks",
            "haojing_battlepass.admin.section.rewards",
            "haojing_battlepass.admin.section.shop",
            "haojing_battlepass.admin.section.milestones",
            "haojing_battlepass.admin.section.events",
            "haojing_battlepass.admin.section.codes",
            "haojing_battlepass.admin.section.titles_eggs",
            "haojing_battlepass.admin.section.config_editor"
    };

    /** 数据维护区的按钮（文案键 + 动作 + 取哪个输入框当参数）。 */
    private static final String[][] ACTION_BUTTONS = {
            // 一、测试战令本身（阶段 7 补：没有这些手段，管理员无法重复验证战令链路）
            {"haojing_battlepass.admin.action.open_player", NetActions.AdminAction.OPEN_PLAYER_PANEL.name(), "none"},
            {"haojing_battlepass.admin.action.add_xp", NetActions.AdminAction.ADD_XP.name(), "target"},
            {"haojing_battlepass.admin.action.give_card", NetActions.AdminAction.GIVE_EXEMPT_CARD.name(), "target"},
            {"haojing_battlepass.admin.action.refresh_tasks", NetActions.AdminAction.FORCE_TASK_REFRESH.name(), "target"},
            {"haojing_battlepass.admin.action.reset_self", NetActions.AdminAction.RESET_SELF.name(), "none"},
            // 二、查改玩家数据
            {"haojing_battlepass.admin.action.query", NetActions.AdminAction.QUERY.name(), "target"},
            {"haojing_battlepass.admin.action.set_level", NetActions.AdminAction.SET_LEVEL.name(), "target"},
            {"haojing_battlepass.admin.action.set_xp", NetActions.AdminAction.SET_XP.name(), "target"},
            {"haojing_battlepass.admin.action.set_coin", NetActions.AdminAction.SET_STAR_COIN.name(), "target"},
            {"haojing_battlepass.admin.action.set_branch", NetActions.AdminAction.SET_BRANCH.name(), "target"},
            // 三、配置与运维
            {"haojing_battlepass.admin.action.reload", NetActions.AdminAction.RELOAD.name(), "none"},
            {"haojing_battlepass.admin.action.export", NetActions.AdminAction.EXPORT_CONFIG.name(), "none"},
            {"haojing_battlepass.admin.action.import", NetActions.AdminAction.IMPORT_CONFIG.name(), "value"},
            {"haojing_battlepass.admin.action.reset_season", NetActions.AdminAction.RESET_SEASON.name(), "none"},
            {"haojing_battlepass.admin.action.start_event", NetActions.AdminAction.START_EVENT.name(), "value"}
    };

    private int section;
    private int page;
    private PanelLayout layout;
    private int linesPerPage = 8;
    private int lineHeight = 12;

    /**
     * 本次打开的界面上是否已经向服务端要过数据。
     *
     * <p>为什么需要它：{@code init()} 在每次"重建控件"（切页签、翻页、窗口改尺寸）时都会被调用。
     * 早期版本在这里发 {@code OPEN_PANEL}，而服务端收到后会回一个"打开面板" ——
     * 于是形成死循环（真机日志刷满 {@code TOO_FREQUENT：OPEN_PANEL}）。
     * 现在只在本界面实例第一次初始化时发一次 {@code SYNC_ADMIN}（只取数据、不回打开面板）。
     */
    private boolean requestedData;

    private TextFieldWidget targetField;
    private TextFieldWidget valueField;

    /** 配置文件编辑器：当前正在编辑的文件名；null 表示未选。 */
    private String editingFile;
    /** 配置文件内容缓冲（从服务端拉取或剪贴板粘贴来的）。 */
    private String configBuffer = "";
    /** 配置文件预览滚动偏移（行数）。 */
    private int configScroll;

    public AdminPanelScreen() {
        super(Text.translatable("haojing_battlepass.admin.title"));
    }

    @Override
    protected void init() {
        layout = PanelLayout.compute(this.width, this.height, 400, 260, 520, SECTION_KEYS.length, 14);
        lineHeight = Math.max(9, layout.rowHeight() - 2);

        // 只在首次打开时向服务端要数据（切页签/翻页不会重复请求）。
        // 之后服务端会每 2 秒自动推一次（它已经把这名 OP 登记为管理面板观察者）。
        if (!requestedData) {
            requestedData = true;
            ClientNetworking.sendAdmin(NetActions.AdminAction.SYNC_ADMIN, "", "");
        }

        for (int index = 0; index < SECTION_KEYS.length; index++) {
            final int target = index;
            addDrawableChild(ButtonWidget.builder(Text.translatable(SECTION_KEYS[index]), widget -> {
                section = target;
                page = 0;
                rebuild();
            }).dimensions(layout.tabX(index, SECTION_KEYS.length), layout.tabY(index),
                    layout.tabWidth(index, SECTION_KEYS.length), layout.tabHeight() - 2).build());
        }

        // 配置文件编辑页（section == 9）：独立布局，跳过下面的普通动作按钮区。
        int buttonHeight = layout.footerButtonHeight();
        if (section == 9) {
            buildConfigEditor(buttonHeight);
            return;
        }

        int perRow = Math.max(2, layout.contentWidth() / 92);
        int actionRows = (ACTION_BUTTONS.length + perRow - 1) / perRow;
        int actionButtonWidth = layout.contentWidth() / perRow - 2;

        int actionBottom = layout.footerY() - 4;
        int actionTop = Math.max(layout.contentTop() + 10, actionBottom - actionRows * (buttonHeight + 2));
        int inputY = Math.max(layout.contentTop(), actionTop - (buttonHeight + 3));

        // 内容区高度受"输入框 + 动作按钮 + 底部栏"挤压，因此每页行数在这里按实际情况算。
        int contentHeight = Math.max(lineHeight, inputY - 4 - layout.contentTop());
        linesPerPage = Math.max(1, contentHeight / lineHeight);

        // 两个输入框：宽度按可用宽度均分。
        int fieldWidth = Math.max(60, layout.contentWidth() / 2 - 2);
        targetField = new TextFieldWidget(this.textRenderer, layout.panelX() + 4, inputY, fieldWidth, buttonHeight,
                Text.translatable("haojing_battlepass.admin.field.target"));
        targetField.setMaxLength(64);
        targetField.setPlaceholder(Text.translatable("haojing_battlepass.admin.field.target_hint"));
        addDrawableChild(targetField);

        valueField = new TextFieldWidget(this.textRenderer, layout.panelX() + 4 + fieldWidth + 4, inputY,
                fieldWidth, buttonHeight, Text.translatable("haojing_battlepass.admin.field.value"));
        valueField.setMaxLength(64);
        valueField.setPlaceholder(Text.translatable("haojing_battlepass.admin.field.value_hint"));
        addDrawableChild(valueField);

        for (int index = 0; index < ACTION_BUTTONS.length; index++) {
            String[] spec = ACTION_BUTTONS[index];
            NetActions.AdminAction action = NetActions.AdminAction.fromName(spec[1]);
            String source = spec[2];

            int column = index % perRow;
            int rowIndex = index / perRow;

            addDrawableChild(ButtonWidget.builder(Text.translatable(spec[0]), widget ->
                    ClientNetworking.sendAdmin(action, argFor(source), valueFor(source)))
                    .dimensions(layout.panelX() + 4 + column * (actionButtonWidth + 2),
                            actionTop + rowIndex * (buttonHeight + 2), actionButtonWidth, buttonHeight).build());
        }

        // 底部：上一页 / 下一页 / 应用取值 / 关闭。
        int smallWidth = Math.max(40, layout.panelWidth() / 6);
        int footerY = layout.footerButtonY();

        ButtonWidget previous = ButtonWidget.builder(Text.translatable("haojing_battlepass.gui.prev"), widget -> {
            if (page > 0) {
                page--;
                rebuild();
            }
        }).dimensions(layout.panelX() + 4, footerY, smallWidth, buttonHeight).build();
        previous.active = page > 0;
        addDrawableChild(previous);

        ButtonWidget next = ButtonWidget.builder(Text.translatable("haojing_battlepass.gui.next"), widget -> {
            page++;
            rebuild();
        }).dimensions(layout.panelX() + 4 + smallWidth + 2, footerY, smallWidth, buttonHeight).build();
        next.active = true;
        addDrawableChild(next);

        int applyWidth = Math.max(80, smallWidth * 2);
        int applyX = layout.panelX() + layout.panelWidth() - smallWidth - applyWidth - 6;
        addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.admin.action.set_scalars"),
                widget -> applyScalarEdits())
                .dimensions(applyX, footerY, applyWidth, buttonHeight).build());

        // 新增：打开配置文件夹（本地服务器/单机时直接在资源管理器里打开 JSON 所在目录）。
        // 放在"下一页"和"应用取值"之间的空隙里。
        int openDirWidth = Math.max(50, layout.panelWidth() / 7);
        int nextRight = layout.panelX() + 4 + smallWidth + 2 + smallWidth;
        int openDirX = nextRight + 4;
        if (openDirX + openDirWidth < applyX - 4) {
            addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.admin.action.open_config_dir"),
                    widget -> openConfigDir())
                    .dimensions(openDirX, footerY, openDirWidth, buttonHeight).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), widget -> close())
                .dimensions(layout.panelX() + layout.panelWidth() - smallWidth - 2, footerY,
                        smallWidth, buttonHeight).build());
    }

    /**
     * 配置文件编辑器页：列出所有配置文件名按钮，点击从服务端拉取内容。
     * 内容以格式化多行文本显示（只读预览），通过剪贴板在外部编辑器与游戏间传递。
     */
    private void buildConfigEditor(int buttonHeight) {
        int footerY = layout.footerButtonY();
        int smallWidth = Math.max(40, layout.panelWidth() / 6);

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), widget -> close())
                .dimensions(layout.panelX() + layout.panelWidth() - smallWidth - 2, footerY,
                        smallWidth, buttonHeight).build());

        // 文件按钮网格。
        var admin = ClientNetworking.state().admin();
        int x = layout.panelX() + 4;
        int y = layout.contentTop() + 4;
        int btnW = Math.max(70, layout.contentWidth() / 4 - 4);
        int btnH = buttonHeight;

        if (admin != null && admin.configFiles != null) {
            for (String fullPath : admin.configFiles) {
                String name = fullPath;
                int sep = Math.max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'));
                if (sep >= 0 && sep < fullPath.length() - 1) {
                    name = fullPath.substring(sep + 1);
                }
                final String fileName = name;
                ButtonWidget btn = ButtonWidget.builder(Text.literal(fileName), w -> {
                    editingFile = fileName;
                    configBuffer = "";
                    configScroll = 0;
                    ClientNetworking.requestConfigFile(fileName);
                    rebuild();
                }).dimensions(x, y, btnW, btnH).build();
                if (fileName.equals(editingFile)) {
                    btn.active = false;
                }
                addDrawableChild(btn);
                x += btnW + 4;
                if (x + btnW > layout.panelX() + layout.panelWidth() - 4) {
                    x = layout.panelX() + 4;
                    y += btnH + 3;
                }
            }
        }

        // 操作按钮行：复制 / 粘贴 / 保存。
        int actionY = y + btnH + 4;
        int actionW = Math.max(80, layout.contentWidth() / 4 - 4);
        int actionX = layout.panelX() + 4;

        addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.admin.action.config_copy"),
                w -> {
                    if (!configBuffer.isEmpty() && this.client != null && this.client.keyboard != null) {
                        this.client.keyboard.setClipboard(configBuffer);
                    }
                }).dimensions(actionX, actionY, actionW, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.admin.action.config_paste"),
                w -> {
                    if (this.client != null && this.client.keyboard != null) {
                        String clip = this.client.keyboard.getClipboard();
                        if (clip != null && !clip.isEmpty()) {
                            configBuffer = clip;
                            configScroll = 0;
                        }
                    }
                    rebuild();
                }).dimensions(actionX + actionW + 4, actionY, actionW, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.admin.action.config_save"),
                w -> {
                    if (editingFile != null && !configBuffer.isEmpty()) {
                        ClientNetworking.saveConfigFile(editingFile, configBuffer);
                    }
                }).dimensions(actionX + (actionW + 4) * 2, actionY, actionW, buttonHeight).build());
    }

    /** 打开配置目录（取第一个 config 文件的父目录）。远程服务器上不存在该路径时静默失败。 */
    private void openConfigDir() {
        var admin = ClientNetworking.state().admin();
        if (admin == null || admin.configFiles == null || admin.configFiles.isEmpty()) {
            return;
        }

        try {
            String first = admin.configFiles.get(0);
            java.io.File file = new java.io.File(first);
            java.io.File dir = file.getParentFile();
            if (dir != null && dir.exists() && dir.isDirectory()) {
                net.minecraft.util.Util.getOperatingSystem().open(dir);
            }
        } catch (RuntimeException ignored) {
            // 远程服务器路径在本机不存在，静默忽略。
        }
    }

    /** 动作按钮的参数取值：按声明的来源取输入框内容。 */
    private String argFor(String source) {
        if ("target".equals(source)) {
            return targetField == null ? "" : targetField.getText();
        }

        return "";
    }

    /** 动作按钮的 value 取值：按声明的来源取输入框内容。 */
    private String valueFor(String source) {
        if ("value".equals(source)) {
            return valueField == null ? "" : valueField.getText();
        }

        if ("target".equals(source)) {
            return valueField == null ? "" : valueField.getText();
        }

        return "";
    }

    /**
     * 把"取值输入框"的解析结果应用到当前分页的数值参数上。
     *
     * <p>取值格式：{@code 字段=值}，例如 {@code dailyXpCap=800}、{@code longNight.start=23:00}。
     * 支持一次填多项，用逗号分隔。这样面板只需要两个输入框就能覆盖 §9 的全部数值型可编辑项。
     */
    private void applyScalarEdits() {
        if (valueField == null) {
            return;
        }

        String raw = valueField.getText();

        if (raw == null || raw.isBlank()) {
            return;
        }

        for (String part : raw.split(",")) {
            int equals = part.indexOf('=');

            if (equals <= 0) {
                continue;
            }

            applyField(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
    }

    /** 把 {@code 字段=值} 映射成对应的管理动作。 */
    private void applyField(String field, String value) {
        switch (field) {
            case "enabled":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_SEASON_ENABLED, "", value);
                break;
            case "dailyXpCap":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_DAILY_XP_CAP, "", value);
                break;
            case "dailyRefreshTime":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_DAILY_REFRESH, "", value);
                break;
            case "maxLevel":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_MAX_LEVEL, "", value);
                break;
            case "branchUnlockLevel":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_BRANCH_UNLOCK, "", value);
                break;
            case "xpPerLevelBase":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_XP_CURVE, "base", value);
                break;
            case "xpPerLevelStep":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_XP_CURVE, "step", value);
                break;
            case "longNight.enabled":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_LONG_NIGHT, "enabled", value);
                break;
            case "longNight.start":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_LONG_NIGHT, "start", value);
                break;
            case "longNight.end":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_LONG_NIGHT, "end", value);
                break;
            case "longNight.xpMultiplier":
                ClientNetworking.sendAdmin(NetActions.AdminAction.SET_LONG_NIGHT, "xpmultiplier", value);
                break;
            default:
                // 未识别的字段名不发送任何包：避免把拼错的字段当成合法请求打到服务端。
                break;
        }
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 配置编辑页：服务端回发的文件内容到了就格式化到缓冲里。
        if (section == 9) {
            String[] pending = ClientNetworking.drainPendingConfigFile();
            if (pending != null && pending[0] != null && pending[0].equals(editingFile)) {
                configBuffer = prettyJson(pending[1]);
                configScroll = 0;
            }
        }

        // 顺序同玩家界面：底色 → 文字 → super.render 画按钮（见 BattlePassScreen 的说明）。
        if (layout != null) {
            context.fill(layout.panelX(), layout.panelY(),
                    layout.panelX() + layout.panelWidth(), layout.panelY() + layout.panelHeight(),
                    GuiColors.PANEL_BACKGROUND);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                    layout.panelX() + layout.panelWidth() / 2, layout.panelY() + 2, GuiColors.TITLE);

            List<Text> lines = buildLines();
            int firstLine = page * linesPerPage;
            int y = layout.contentTop() + 1;

            if (section == 9) {
                // 配置编辑页：显示文件名 + 提示 + 多行 JSON 预览（带滚动）。
                if (editingFile == null) {
                    context.drawTextWithShadow(this.textRenderer,
                            Text.translatable("haojing_battlepass.admin.config.pick_hint"),
                            layout.panelX() + 5, y + 20, GuiColors.TEXT_DIM);
                } else {
                    context.drawTextWithShadow(this.textRenderer,
                            Text.translatable("haojing_battlepass.admin.config.editing", editingFile),
                            layout.panelX() + 5, y, 0xFFFFAA00);
                    y += lineHeight + 2;
                    context.drawTextWithShadow(this.textRenderer,
                            Text.translatable("haojing_battlepass.admin.config.workflow"),
                            layout.panelX() + 5, y, GuiColors.TEXT_DIM);
                    y += lineHeight + 2;

                    // 画背景框。
                    int boxY = y;
                    int boxH = Math.max(40, layout.footerY() - 4 - boxY);
                    context.fill(layout.panelX() + 3, boxY,
                            layout.panelX() + layout.panelWidth() - 3, boxY + boxH, 0x80000000);

                    // 多行文本，按 configScroll 滚动。
                    String[] previewLines = configBuffer.split("\n", -1);
                    int maxVisible = boxH / lineHeight;
                    int startLine = Math.max(0, Math.min(configScroll, previewLines.length - maxVisible));
                    for (int i = 0; i < maxVisible; i++) {
                        int idx = startLine + i;
                        if (idx >= previewLines.length) break;
                        String line = previewLines[idx];
                        // 截断过长的行。
                        String rendered = this.textRenderer.trimToWidth(line, layout.contentWidth() - 10);
                        context.drawTextWithShadow(this.textRenderer, rendered,
                                layout.panelX() + 6, boxY + 2 + i * lineHeight, 0xFFCCCCCC);
                    }

                    // 滚动位置提示。
                    if (previewLines.length > maxVisible) {
                        context.drawTextWithShadow(this.textRenderer,
                                Text.literal((startLine + 1) + "/" + previewLines.length),
                                layout.panelX() + layout.panelWidth() - 50, boxY + 2, GuiColors.TEXT_DIM);
                    }
                }
            } else {
                // 第一行固定是"自诊断状态"：数据没到、解析失败、被服务端拒绝，都能一眼看出来。
                context.drawTextWithShadow(this.textRenderer, statusLine(), layout.panelX() + 5, y, GuiColors.STATUS);
                y += lineHeight;

                for (int index = 0; index < Math.max(0, linesPerPage - 1); index++) {
                    int lineIndex = firstLine + index;

                    if (lineIndex >= lines.size()) {
                        break;
                    }

                    context.drawTextWithShadow(this.textRenderer, lines.get(lineIndex), layout.panelX() + 5, y,
                            GuiColors.TEXT);
                    y += lineHeight;
                }

                context.drawTextWithShadow(this.textRenderer,
                        Text.translatable("haojing_battlepass.gui.page", page + 1,
                                Math.max(1, (lines.size() + linesPerPage - 1) / linesPerPage)),
                        layout.panelX() + layout.panelWidth() / 2 - 20, layout.footerY() + 4, GuiColors.TEXT_DIM);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (section == 9 && !configBuffer.isEmpty()) {
            configScroll = Math.max(0, configScroll - (int) verticalAmount * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** 把 JSON 文本格式化（带缩进）；解析失败就原样返回。 */
    private static String prettyJson(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(raw);
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(el);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * 面板的自诊断状态行（客户端本地信息，不依赖服务端）。
     *
     * <p>为什么必须有它：界面"空白"有很多种原因 —— 服务端没推、推送了但解析失败、
     * 或者被服务端拒绝了（非 OP）。没有这行字，玩家/管理员只能看到"什么都没有"，
     * 而这句话本身不含任何可排查的信息。现在它直接告诉你是哪一种。
     *
     * @return 状态文本
     */
    private Text statusLine() {
        var state = ClientNetworking.state();
        ModSnapshots.Admin admin = state.admin();

        if (admin != null) {
            long ago = state.lastUpdateMillis() == 0 ? 0
                    : Math.max(0, (System.currentTimeMillis() - state.lastUpdateMillis()) / 1000L);
            return Text.translatable("haojing_battlepass.admin.status.ok",
                    state.revisionOf(com.haojing.battlepass.common.net.SyncChannels.ADMIN), ago);
        }

        if (state.parseFailures() > 0) {
            return Text.translatable("haojing_battlepass.admin.status.parse_failed", state.parseFailures());
        }

        if (!state.lastResultKey().isEmpty()) {
            // 服务端回过话了：多半是"需要管理员权限"这类明确原因。
            return Text.translatable("haojing_battlepass.admin.status.result",
                    Text.translatable(state.lastResultKey()));
        }

        return Text.translatable("haojing_battlepass.admin.status.waiting", state.receivedChannels());
    }

    /** 按当前分类生成要显示的行。 */
    private List<Text> buildLines() {
        List<Text> lines = new ArrayList<>();
        ModSnapshots.Admin admin = ClientNetworking.state().admin();

        if (admin == null) {
            lines.add(Text.translatable("haojing_battlepass.admin.hint.player_ui"));
            lines.add(Text.translatable("haojing_battlepass.gui.waiting"));
            return lines;
        }

        switch (section) {
            case 1:
                lines.add(Text.translatable("haojing_battlepass.admin.line.daily_xp_cap", admin.dailyXpCap));
                lines.add(Text.translatable("haojing_battlepass.admin.line.daily_refresh", admin.dailyRefreshTime));
                lines.add(Text.translatable("haojing_battlepass.admin.line.reroll_limit", admin.dailyRerollLimit));
                lines.add(Text.translatable("haojing_battlepass.admin.line.long_night", admin.longNightEnabled,
                        admin.longNightStart, admin.longNightEnd));
                lines.add(Text.translatable("haojing_battlepass.admin.line.long_night_multiplier",
                        admin.longNightXpMultiplier));
                lines.add(Text.translatable("haojing_battlepass.admin.line.xp_curve",
                        admin.xpPerLevelBase, admin.xpPerLevelStep));
                lines.add(Text.translatable("haojing_battlepass.admin.line.star_coin_per_level", admin.starCoinPerLevel));
                lines.add(Text.translatable("haojing_battlepass.admin.line.exempt_card_max", admin.exemptCardMax));
                lines.add(Text.translatable("haojing_battlepass.admin.line.dev_mode", admin.devMode));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.scalars"));
                break;
            case 2:
                lines.add(Text.translatable("haojing_battlepass.admin.line.task_count", admin.taskCount));
                lines.add(Text.translatable("haojing_battlepass.admin.line.config_file", firstConfig(admin, 1)));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            case 3:
                lines.add(Text.translatable("haojing_battlepass.admin.line.reward_levels", admin.rewardLevelCount));
                lines.add(Text.translatable("haojing_battlepass.admin.line.max_level", admin.maxLevel));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            case 4:
                lines.add(Text.translatable("haojing_battlepass.admin.line.shop_count", admin.shopCount));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            case 5:
                lines.add(Text.translatable("haojing_battlepass.admin.line.milestone_count", admin.milestoneCount));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            case 6:
                lines.add(Text.translatable("haojing_battlepass.admin.line.event_count", admin.eventCount));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.start_event"));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            case 7:
                lines.add(Text.translatable("haojing_battlepass.admin.line.code_count", admin.codeCount));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            case 8:
                lines.add(Text.translatable("haojing_battlepass.admin.line.egg_count", admin.eggCount));
                lines.add(Text.translatable("haojing_battlepass.admin.line.online", admin.onlinePlayers,
                        admin.storedPlayers));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.file_edit"));
                break;
            default:
                lines.add(Text.translatable("haojing_battlepass.admin.line.season", admin.seasonId, admin.themeName));
                lines.add(Text.translatable("haojing_battlepass.admin.line.season_enabled", admin.seasonEnabled));
                lines.add(Text.translatable("haojing_battlepass.admin.line.duration", admin.durationDays));
                lines.add(Text.translatable("haojing_battlepass.admin.line.branch_unlock", admin.branchUnlockLevel));
                lines.add(Text.translatable("haojing_battlepass.admin.line.online", admin.onlinePlayers,
                        admin.storedPlayers));
                lines.add(Text.translatable("haojing_battlepass.admin.hint.scalars"));
                break;
        }

        if (!admin.queryResult.isEmpty()) {
            for (String line : admin.queryResult.split("\n")) {
                lines.add(Text.literal(line));
            }
        }

        for (String file : admin.configFiles) {
            lines.add(Text.literal(file));
        }

        return lines;
    }

    private static String firstConfig(ModSnapshots.Admin admin, int index) {
        return admin.configFiles.size() > index ? admin.configFiles.get(index) : "";
    }

    /** 便于外部（客户端入口）在收到面板数据后刷新当前界面。 */
    public void refresh() {
        rebuild();
    }

    /** 供入口在"服务端推来新数据"时调用：只有当前界面是管理面板才重建。 */
    public static void refreshIfOpen(java.util.function.Consumer<AdminPanelScreen> action) {
        var current = net.minecraft.client.MinecraftClient.getInstance().currentScreen;

        if (current instanceof AdminPanelScreen panel) {
            action.accept(panel);
        }
    }
}
