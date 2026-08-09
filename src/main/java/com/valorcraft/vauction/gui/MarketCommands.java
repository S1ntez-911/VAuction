package com.valorcraft.vauction.gui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

final class MarketCommands {
    private MarketCommands() {}

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(root("market"));
        event.getDispatcher().register(root("auction"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .executes(ctx -> open(ctx.getSource()))
                .then(Commands.literal("search")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> search(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "text")))))
                .then(Commands.literal("quantity")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                .executes(ctx -> quantity(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "value")))))
                .then(Commands.literal("price")
                        .then(Commands.argument("minor", LongArgumentType.longArg(1))
                                .executes(ctx -> price(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "minor")))));
    }

    private static int open(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        MarketController.instance().open(player);
        return 1;
    }

    private static int search(CommandSourceStack source, String text) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        MarketController.instance().search(player, text, 0);
        return 1;
    }

    private static int quantity(CommandSourceStack source, int value) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        if (!MarketController.instance().setQuantity(player, value)) {
            source.sendFailure(Component.literal("Сначала откройте настройку заявки в /market."));
            return 0;
        }
        return 1;
    }

    private static int price(CommandSourceStack source, long value) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        if (!MarketController.instance().setPrice(player, value)) {
            source.sendFailure(Component.literal("Сначала откройте настройку заявки в /market."));
            return 0;
        }
        return 1;
    }

    private static ServerPlayer player(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) return player;
        source.sendFailure(Component.literal("Команда доступна только игрокам.")
                .withStyle(ChatFormatting.RED));
        return null;
    }
}
