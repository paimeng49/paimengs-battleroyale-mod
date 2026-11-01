package com.paimeng.battleroyale.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.paimeng.battleroyale.config.QuickChatStore;
import com.paimeng.battleroyale.network.TeamChat;
import com.paimeng.battleroyale.util.BrText;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** /paimengmods qc 快捷消息服务端指令分支。 */
public final class QuickChatCommand {

    private static final int SUCCESS = 1;
    private static final int FAILURE = 0;

    private QuickChatCommand() {
    }

    /** 返回 qc 子树，由 BrCommand 与 br 子树一起挂到同一个根节点。 */
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal(CommandRoots.QC)
                .executes(QuickChatCommand::listMessages)
                .then(Commands.literal("reload")
                        .executes(QuickChatCommand::reloadConfig))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .suggests(QuickChatCommand::suggestQuickMessages)
                        .executes(QuickChatCommand::sendQuickChat));
    }

    private static CompletableFuture<Suggestions> suggestQuickMessages(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String message : QuickChatStore.messages()) {
            String key = message.toLowerCase(Locale.ROOT);
            if (typed.isEmpty() || key.startsWith(typed) || key.contains(typed)) {
                builder.suggest(message, BrText.of("回车后发送到队伍频道"));
            }
        }
        return builder.buildFuture();
    }

    private static int sendQuickChat(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(BrText.of("这条指令只能由玩家执行。"));
            return FAILURE;
        }
        return TeamChat.sendPresetMessage(player,
                StringArgumentType.getString(context, "message")) ? SUCCESS : FAILURE;
    }

    private static int listMessages(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> messages = QuickChatStore.messages();
        String prefix = CommandRoots.QC_PREFIX;

        MutableComponent header = BrText.mutable("快捷消息（共 " + messages.size() + " 条） ")
                .withStyle(ChatFormatting.AQUA)
                .append(BrText.mutable("输入 " + prefix + " 加空格可选，或点击下方任意一条直接填入：")
                        .withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> header, false);

        for (int i = 0; i < messages.size(); i++) {
            final String message = messages.get(i);
            final int number = i + 1;
            source.sendSuccess(() -> BrText.mutable(" · " + number + ". " + message)
                    .withStyle(ChatFormatting.YELLOW)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                    prefix + " " + message))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    BrText.of("点击填入聊天框，回车发送到队伍频道")))
                            .withUnderlined(Boolean.TRUE)), false);
        }
        return SUCCESS;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        int count = QuickChatStore.reload();
        context.getSource().sendSuccess(() -> BrText.mutable("已重新加载快捷消息：")
                .withStyle(ChatFormatting.GREEN)
                .append(BrText.mutable("config/battleroyale/quickchat.json（当前 " + count + " 条）")
                        .withStyle(ChatFormatting.YELLOW)), false);
        return SUCCESS;
    }
}
