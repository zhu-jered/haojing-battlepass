package com.haojing.battlepass.client.gui;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.gui.PanelLayout;
import com.haojing.battlepass.common.net.NetActions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 用途：分支选择弹窗（需求文档 §4、§8："10 级时弹窗二选一【狩猎分支】/【建造分支】；
 * 本赛季锁定，改选仅管理员指令可做"）。
 *
 * <p>为什么做成独立界面而不是在主界面里加两个按钮：§8 的要求是"弹窗"，
 * 而且这是一个**不可逆**的选择（本赛季锁定），必须让玩家在看清楚说明后主动确认。
 * 主界面的按钮太容易被误点。
 *
 * <p>布局同样走 {@link PanelLayout}：两个按钮的宽度按屏幕宽度算，
 * 小窗口/高 GUI 缩放下不会互相重叠或超出屏幕。
 */
@Environment(EnvType.CLIENT)
public class BranchChoiceScreen extends Screen {

    private PanelLayout layout;

    public BranchChoiceScreen() {
        super(Text.translatable("haojing_battlepass.gui.branch.title"));
    }

    @Override
    protected void init() {
        layout = PanelLayout.compute(this.width, this.height, 340, 200, 420, 1, 18);

        int buttonWidth = Math.max(70, (layout.contentWidth() - 8) / 2);
        int buttonHeight = Math.max(16, layout.footerButtonHeight());
        int centerY = this.height / 2;

        addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.branch.hunt"),
                widget -> choose("HUNT"))
                .dimensions(layout.panelX() + 4, centerY - 6, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("haojing_battlepass.branch.build"),
                widget -> choose("BUILD"))
                .dimensions(layout.panelX() + 4 + buttonWidth + 6, centerY - 6, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), widget -> close())
                .dimensions(this.width / 2 - buttonWidth / 2, centerY + 20, buttonWidth, buttonHeight).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int centerY = this.height / 2;
        int centerX = this.width / 2;

        if (layout != null) {
            // 顺序：底色 → 文字 → super.render 画按钮（见 BattlePassScreen 的说明）。
            context.fill(layout.panelX(), layout.panelY(),
                    layout.panelX() + layout.panelWidth(), layout.panelY() + layout.panelHeight(),
                    GuiColors.PANEL_BACKGROUND);

            context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, centerY - 56, GuiColors.TITLE);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("haojing_battlepass.gui.branch.locked_warning"), centerX, centerY - 40,
                    GuiColors.WARN);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("haojing_battlepass.gui.branch.hunt_desc"), centerX, centerY + 34,
                    GuiColors.HINT);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("haojing_battlepass.gui.branch.build_desc"), centerX, centerY + 46,
                    GuiColors.HINT);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        // 弹窗也会在单人游戏里出现，暂停会打断玩家正在做的事（本弹窗不危险）。
        return false;
    }

    private void choose(String branch) {
        ClientNetworking.sendAction(NetActions.ClientAction.CHOOSE_BRANCH, branch);

        // 立刻回到战令界面：服务端的回执与最新状态会紧接着推送过来。
        if (this.client != null) {
            this.client.setScreen(new BattlePassScreen());
        }
    }
}
