package com.haojing.battlepass.client;

import com.haojing.battlepass.client.gui.BattlePassScreen;
import com.haojing.battlepass.client.gui.BranchChoiceScreen;
import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.net.NetActions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用途：客户端模组入口。需求文档 §2/§8 规定客户端只接收服务端数据包并渲染中文 GUI，
 * 不参与任何业务判定，客户端提交的数据一律不作为判定依据。
 *
 * <p>阶段 7 在这里接入四件事：
 * <ol>
 *   <li>自定义数据包的收发与 configuration 阶段握手（{@link ClientNetworking}）；</li>
 *   <li>{@code /battlepass} 与 {@code /battlepass admin} 两条本地指令（§11）；</li>
 *   <li>10 级时的分支选择弹窗（§4、§8）；</li>
 *   <li>六个分页的中文界面（{@code client/gui}）。</li>
 * </ol>
 *
 * <p>为什么 {@code /battlepass admin} 由客户端指令转发成数据包而不是直接开界面：
 * §9 要求"需 OP 权限 + 服务端二次校验"。客户端自己开界面等于零校验，
 * 因此必须先问服务端，由服务端确认 OP 后再回包让客户端打开（见 {@code AdminPanelScreen}）。
 */
public class HaoJingBattlePassClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_CLIENT);

    /**
     * 打开战令界面的快捷键，默认 {@code B}（原版没有占用这个键）。
     *
     * <p>需求文档 §8 只要求"客户端 /battlepass 打开"，按键是额外的便利：
     * 玩家可以随时在「选项 → 控制 → 按键绑定」里改成别的键（本模组单独一组）。
     *
     * <p>为什么这样做而不是用字符串分类：1.21.11 的 {@code KeyBinding} 构造签名已变成
     * {@code (String, InputUtil.Type, int, KeyBinding.Category)}，
     * 分类必须是 {@code KeyBinding.Category} 实例（原版 MOVEMENT/MISC 等都是这样来的）。
     * 自定义分类通过 {@code Category.create(Identifier)} 注册，且**重复注册会抛异常**，
     * 因此这里兜一层：万一异常（例如开发环境热重载）就退回原版的「杂项」分类，按键依然可用。
     */
    private static final KeyBinding OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.haojing_battlepass.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            openKeyCategory()));

    /** 本次会话是否已经弹过分支选择窗（避免玩家每次关掉界面又立刻被弹一次）。 */
    private static boolean branchPromptShown;

    /** @return 本模组的按键分类；已被注册过（或注册失败）时退回原版的「杂项」。 */
    private static KeyBinding.Category openKeyCategory() {
        try {
            return KeyBinding.Category.create(Identifier.of(ModConstants.NETWORK_NAMESPACE, "main"));
        } catch (RuntimeException e) {
            return KeyBinding.Category.MISC;
        }
    }

    @Override
    public void onInitializeClient() {
        ClientNetworking.register();

        // §11：/battlepass 打开战令界面；/battlepass admin 向服务端申请管理面板。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("battlepass")
                        .executes(context -> {
                            MinecraftClient.getInstance().setScreen(new BattlePassScreen());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("admin")
                                .executes(context -> {
                                    ClientNetworking.sendAdmin(NetActions.AdminAction.OPEN_PANEL, "", "");
                                    return 1;
                                }))));

        // §4/§8：等级达到分支解锁等级且尚未选择时，弹窗二选一。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 快捷键：默认 B 键打开战令界面（可在按键设置里改）。
            while (OPEN_KEY.wasPressed()) {
                if (client.currentScreen == null && client.player != null) {
                    client.setScreen(new BattlePassScreen());
                }
            }

            if (client.player == null || client.currentScreen != null) {
                return;
            }

            boolean prompt = ClientNetworking.state().player().branchPrompt;

            if (prompt && !branchPromptShown) {
                branchPromptShown = true;
                client.setScreen(new BranchChoiceScreen());
            } else if (!prompt) {
                // 已经选过分支（或等级不够）：重置标记，以便下一赛季/下一次升级时还能弹。
                branchPromptShown = false;
            }
        });

        LOGGER.info("{} 客户端模组已加载（阶段 7：握手 + 双向包 + 中文界面）", ModConstants.LOG_PREFIX);
    }
}
