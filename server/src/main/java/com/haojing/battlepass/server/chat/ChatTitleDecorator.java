package com.haojing.battlepass.server.chat;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.title.TitleDefinition;
import com.haojing.battlepass.server.title.TitleManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聊天称号前缀。根据 titles.json 里每个称号的颜色与包裹符号渲染，
 * 并给称号组件挂上悬浮 Tooltip（内容与战令 GUI 一致）。
 *
 * <p>玩家自己关闭了"聊天称号显示"时，本条消息不加前缀。
 */
public final class ChatTitleDecorator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private static final String TITLE_KEY_PREFIX = "haojing_battlepass.title.";

    private final PlayerDataManager dataManager;
    private final TitleManager titleManager;

    /** 静态引用，供 Mixin 调用（服务端只实例化一次）。 */
    private static volatile ChatTitleDecorator INSTANCE;

    public ChatTitleDecorator(PlayerDataManager dataManager, TitleManager titleManager) {
        this.dataManager = dataManager;
        this.titleManager = titleManager;
        INSTANCE = this;
    }

    /** 注册聊天装饰器。应在服务端初始化时调用一次。 */
    public void register() {
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.STYLING_PHASE,
                (sender, message) -> decorate(sender, message));
    }

    /**
     * 聊天装饰器：不再在消息体前拼称号——称号已经拼到玩家显示名上了（见 Mixin）。
     * 这个事件保留为空操作，以后若需要在消息体上加装饰（比如关键词高亮）再扩展。
     */
    public Text decorate(ServerPlayerEntity sender, Text message) {
        return message;
    }

    /**
     * 供 {@link com.haojing.battlepass.server.mixin.PlayerEntityDisplayNameMixin} 调用：
     * 给玩家显示名前拼上称号。如果玩家没戴称号或关了显示，原样返回。
     */
    public static Text decorateDisplayName(ServerPlayerEntity player, Text originalName) {
        ChatTitleDecorator self = INSTANCE;
        if (self == null || player == null || originalName == null) {
            return originalName;
        }

        try {
            GlobalData global = self.dataManager.global(player.getUuid());
            if (global != null && !global.chatTitleVisible) {
                return originalName;
            }

            String titleId = global == null ? "" : global.equippedTitle;
            if (titleId == null || titleId.isBlank()) {
                return originalName;
            }
            if (global.unlockedTitles == null || !global.unlockedTitles.contains(titleId)) {
                return originalName;
            }

            TitleDefinition def = self.titleManager == null ? null : self.titleManager.byId(titleId);
            String wrapPrefix = def == null || def.wrapPrefix == null ? "【" : def.wrapPrefix;
            String wrapSuffix = def == null || def.wrapSuffix == null ? "】" : def.wrapSuffix;
            String colorCode = def == null || def.color == null || def.color.isBlank() ? "§f" : def.color;

            MutableText titleText;
            String configuredName = def == null ? "" : def.normalizedName();
            if (configuredName.isEmpty()) {
                titleText = Text.translatable(TITLE_KEY_PREFIX + titleId);
            } else {
                titleText = Text.literal(configuredName);
            }

            MutableText tooltip = self.tooltipText(def, titleId);
            Style titleStyle = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(tooltip));

            MutableText prefix = Text.literal(wrapPrefix)
                    .append(titleText)
                    .append(wrapSuffix)
                    .setStyle(titleStyle);
            prefix = self.applyLegacyColor(prefix, colorCode);

            return Text.empty().append(prefix).append(" ").append(originalName);
        } catch (RuntimeException e) {
            return originalName;
        }
    }

    /** 构造悬浮 Tooltip 文本。 */
    private MutableText tooltipText(TitleDefinition def, String titleId) {
        String name = def == null || def.normalizedName().isEmpty()
                ? TITLE_KEY_PREFIX + titleId : def.normalizedName();
        MutableText line1 = def == null || def.normalizedName().isEmpty()
                ? Text.translatable(TITLE_KEY_PREFIX + titleId).formatted(Formatting.GOLD)
                : Text.literal(name).styled(s -> s.withColor(
                        com.haojing.battlepass.common.gui.GuiColors.parseSectionColor(
                                def.color == null ? "§f" : def.color)));

        MutableText tip = Text.empty().append(line1);

        if (def != null) {
            if (!def.acquireHint.isEmpty()) {
                tip.append("\n").append(Text.translatable("haojing_battlepass.title.acquire",
                        def.acquireHint).formatted(Formatting.GRAY));
            }
            if (!def.description.isEmpty()) {
                tip.append("\n").append(Text.literal(def.description).formatted(Formatting.GRAY));
            }
        }

        tip.append("\n").append(Text.translatable("haojing_battlepass.title.decorative")
                .formatted(Formatting.DARK_GRAY));
        return tip;
    }

    /**
     * 把形如 "§e" 的颜色代码应用到一个 Text 的整段样式上。
     * 非法代码静默回退白色。
     */
    private MutableText applyLegacyColor(MutableText text, String colorCode) {
        Integer argb = com.haojing.battlepass.common.gui.GuiColors.parseSectionColor(colorCode);
        if (argb == null) {
            return text;
        }
        Style base = text.getStyle();
        return text.setStyle(base.withColor(net.minecraft.text.TextColor.fromRgb(argb)));
    }
}
