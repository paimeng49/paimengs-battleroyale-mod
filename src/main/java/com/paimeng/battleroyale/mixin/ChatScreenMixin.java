package com.paimeng.battleroyale.mixin;

import com.paimeng.battleroyale.client.GuiDraw;
import com.paimeng.battleroyale.client.QuickChatListController;
import com.paimeng.battleroyale.config.QuickChatStore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * FPS 风格快捷消息：注入原版 {@link ChatScreen}（按 T 打开的聊天界面）。
 *
 * <p>三个注入点与需求逐条对应：
 * <ul>
 *   <li>{@code init()} TAIL：打开聊天框时高亮重置回第一条；</li>
 *   <li>{@code render()} TAIL：仅在输入框为空时，在其上方画快捷消息列表——选中项黄色高亮，
 *       其余白色，旁边灰色小字提示「↑↓ 切换，Tab 发送」；玩家一打字，列表立即隐藏；</li>
 *   <li>{@code keyPressed()} HEAD：仅在输入框为空时拦截 ↑↓ / Tab / Enter；输入框有字时，
 *       所有按键都先原样放行，不会干扰正常打字和移动光标。</li>
 * </ul>
 *
 * <p>所有按键逻辑都先过 {@link QuickChatListController#isActive()} 与输入框空值双重闸门：
 * 列表为空、不在游戏里、服务器没装本模组，或玩家已经开始打字时，完全不拦截按键。
 *
 * <p>[BR-QC-DIM] 渲染与按键还各自再加一道<b>纯客户端维度硬判断</b>
 * （{@link QuickChatListController#isInGameDimension()}）：只有身处
 * {@code battleroyale_game:*} 比赛维度才允许弹出快捷消息列表；登录大厅（主世界）、
 * 下界、末地等任何其它维度按 T，聊天框行为与原版完全一致。
 */
@Environment(EnvType.CLIENT)
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    /** ChatScreen 的输入框（1.20.1 官方映射字段名 input，类型 EditBox）。 */
    @Shadow
    protected EditBox input;

    protected ChatScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
        throw new IllegalStateException("chat-screen-mixin-not-initialized");
    }

    /** 打开聊天框：高亮回到第一条。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void battleroyale$onInit(CallbackInfo ci) {
        QuickChatListController.onChatOpened();
    }

    /** 在输入框上方画快捷消息列表（未激活或非比赛维度时什么都不画）。 */
    @Inject(method = "render", at = @At("TAIL"))
    private void battleroyale$onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                       CallbackInfo ci) {
        // [BR-QC-DIM] 维度硬闸门：不在 battleroyale_game:* 比赛维度就直接返回，
        // 大厅/主世界/下界/末地按 T 时聊天框干干净净，不画任何快捷消息 UI。
        if (!QuickChatListController.isInGameDimension()) {
            return;
        }
        if (!QuickChatListController.isActive() || this.input == null
                || !this.input.getValue().isEmpty()) {
            return;
        }
        List<String> messages = QuickChatStore.messages();
        Font font = this.font;
        int lineH = font.lineHeight + 2;
        int selected = QuickChatListController.clampedIndex();

        // 可视高度：输入框顶边到屏幕顶部提示区之间，最多 12 行。
        int spaceAboveInput = this.input.getY() - 18;
        int maxVisible = Math.max(1, Math.min(12, spaceAboveInput / lineH));
        int visibleCount = Math.min(messages.size(), maxVisible);

        // 长列表滚动窗口：保证选中行始终可见。
        int windowStart = 0;
        if (messages.size() > visibleCount) {
            if (selected >= windowStart + visibleCount) {
                windowStart = selected - visibleCount + 1;
            }
            if (selected < windowStart) {
                windowStart = selected;
            }
            windowStart = Math.max(0, Math.min(windowStart, messages.size() - visibleCount));
        }

        int left = this.input.getX();
        int right = left + this.input.getWidth();
        int listTop = this.input.getY() - 3 - visibleCount * lineH;
        int listBottom = this.input.getY() - 3;

        // 半透明底板 + 选中行高亮条
        GuiDraw.fill(graphics, left - 2, listTop - 2, right + 2, listBottom + 1, 0xC0101010);
        int selectedRowTop = listTop + (selected - windowStart) * lineH;
        GuiDraw.fill(graphics, left - 2, selectedRowTop - 1, right + 2,
                selectedRowTop + lineH - 1, 0x50FFFF55);

        for (int row = 0; row < visibleCount; row++) {
            int index = windowStart + row;
            String text = messages.get(index);
            boolean isSelected = index == selected;
            String line = (isSelected ? "» " : "  ") + (index + 1) + ". " + text;
            int color = isSelected ? 0xFFFFFF00 : 0xFFFFFFFF;
            GuiDraw.drawTextWithShadow(graphics, font, line, left + 1.0F,
                    listTop + row * lineH + 2.0F, color);
        }

        // 灰色小字操作提示（右上角，超出列表范围时贴屏幕右边）
        String hint = "↑↓ 切换，Tab 发送";
        int hintWidth = font.width(hint);
        int hintX = Math.min(right - hintWidth - 2, this.width - hintWidth - 6);
        GuiDraw.drawTextWithShadow(graphics, font, hint, hintX, listTop - 12.0F, 0xFFA0A0A0);
    }

    /**
     * 按键拦截（HEAD，可取消）：↑↓ / Tab / 空输入 Enter。
     * 返回 true 即消费按键，原版逻辑（历史翻阅、自动补全、发空消息）不再执行。
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void battleroyale$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                           CallbackInfoReturnable<Boolean> cir) {
        // [BR-QC-DIM] 与渲染同一口径：非比赛维度不拦任何按键，
        // 大厅里 ↑↓ 照常翻聊天历史，Tab/Enter 也归原版管。
        if (!QuickChatListController.isInGameDimension()) {
            return;
        }
        if (!QuickChatListController.isActive() || this.input == null) {
            return;
        }
        // 玩家一开始打字，快捷消息面板立即隐藏，所有按键完全交还给原版聊天输入。
        if (!this.input.getValue().isEmpty()) {
            return;
        }
        ChatScreen self = (ChatScreen) (Object) this;
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> {
                QuickChatListController.moveSelection(-1);
                cir.setReturnValue(true);
            }
            case GLFW.GLFW_KEY_DOWN -> {
                QuickChatListController.moveSelection(1);
                cir.setReturnValue(true);
            }
            case GLFW.GLFW_KEY_TAB -> {
                // 拦截原版自动补全：直接发送高亮消息并关闭聊天框。
                // 发送失败（如通道刚断开）时不拦截，按键回退给原版处理。
                if (QuickChatListController.sendSelectedAndClose(self)) {
                    this.input.setValue("");
                    QuickChatListController.onChatOpened();
                    cir.setReturnValue(true);
                }
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                // 此处输入框必定为空：Enter 与 Tab 一样发送当前高亮的快捷消息。
                if (QuickChatListController.sendSelectedAndClose(self)) {
                    this.input.setValue("");
                    QuickChatListController.onChatOpened();
                    cir.setReturnValue(true);
                }
            }
            default -> {
                // 其余按键（包括正常打字）原样放行，不影响输入。
            }
        }
    }
}
