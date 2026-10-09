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

    public ChatTitleDecorator(PlayerDataManager dataManager, TitleManager titleManager) {
        this.dataManager = dataManager;
        this.titleManager = titleManager;
    }

    /** 注册聊天装饰器。应在服务端初始化时调用一次。 */
    public void register() {
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.STYLING_PHASE,
                (sender, message) -> decorate(sender, message));
    }

    /**
     * 给聊天消息加上称号前缀。
     *
     * @param sender  发言者
     * @param message 原消息
     * @return 处理后的消息
     */
    public Text decorate(ServerPlayerEntity sender, Text message) {
        if (sender == null || message == null) {
            return message;
        }

        try {
            GlobalData global = dataManager.global(sender.getUuid());

            // 玩家自己关闭了聊天称号显示：本条消息不加前缀。
            if (global != null && !global.chatTitleVisible) {
                return message;
            }

            String titleId = global == null ? "" : global.equippedTitle;

            if (titleId == null || titleId.isBlank()) {
                return message;
            }

            // 只给"确实解锁过"的称号加前缀。
            if (global.unlockedTitles == null || !global.unlockedTitles.contains(titleId)) {
                return message;
            }

            TitleDefinition def = titleManager == null ? null : titleManager.byId(titleId);
            String wrapPrefix = def == null || def.wrapPrefix == null ? "【" : def.wrapPrefix;
            String wrapSuffix = def == null || def.wrapSuffix == null ? "】" : def.wrapSuffix;
            String colorCode = def == null || def.color == null || def.color.isBlank() ? "§f" : def.color;

            // 称号显示名：优先 titles.json 里的 name，否则走 lang 译文。
            MutableText titleText;
            String configuredName = def == null ? "" : def.normalizedName();
            if (configuredName.isEmpty()) {
                titleText = Text.translatable(TITLE_KEY_PREFIX + titleId);
            } else {
                titleText = Text.literal(configuredName);
            }

            // 悬浮 Tooltip：与战令 GUI 一致（名称 / 获取途径 / 描述 / 装饰标注）。
            MutableText tooltip = tooltipText(def, titleId);
            Style titleStyle = Style.EMPTY
                    .withHoverEvent(new HoverEvent.ShowText(tooltip));

            // 把颜色代码应用到整段（包裹符 + 称号）。
            MutableText prefix = Text.literal(wrapPrefix)
                    .append(titleText)
                    .append(wrapSuffix)
                    .append(" ")
                    .setStyle(titleStyle);

            // 手动按 § 上色（避免依赖 Formatting 枚举，管理员写 §e 就用 §e）。
            prefix = applyLegacyColor(prefix, colorCode);

            return prefix.append(message);
        } catch (RuntimeException e) {
            LOGGER.warn("{} 为玩家 {} 添加称号前缀失败，本条消息按原样发送：{}",
                    ModConstants.LOG_PREFIX, sender.getName().getString(), e.toString());
            return message;
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
