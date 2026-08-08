package com.valorcraft.exchange.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.valorcraft.exchange.exchange.ExchangeService;
import com.valorcraft.exchange.exchange.ExchangeService.Outcome;
import com.valorcraft.exchange.integration.VEconomyIntegration;
import com.valorcraft.exchange.screen.ExchangeContainerMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Команды биржи: /exchange (краткий псевдоним /birge или /ex).
 * Подкоманды: open, sell <цена> [кол-во], buy <цена> <кол-во> <предмет>,
 * admin reload/balance/commission/purge.
 */
public final class ExchangeCommand {

    private ExchangeCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        var exchange = Commands.literal("exchange");
        exchange.then(Commands.literal("open").executes(ExchangeCommand::open));

        exchange.then(Commands.literal("sell")
                .then(Commands.argument("price", LongArgumentType.longArg(1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> sellStack(ctx,
                                        LongArgumentType.getLong(ctx, "price"),
                                        IntegerArgumentType.getInteger(ctx, "amount"))))));

        dispatcher.register(exchange);
        dispatcher.register(Commands.literal("birge")
                .executes(ExchangeCommand::open)
                .requires(s -> true));
        dispatcher.register(Commands.literal("ex").executes(ExchangeCommand::open));
    }

    private static int open(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Команда доступна только игрокам."));
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inv, host) -> new ExchangeContainerMenu(id, inv),
                Component.literal("Биржа Ресурсов")));
        return 1;
    }

    private static int sellStack(CommandContext<CommandSourceStack> ctx, long price, int amount) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Команда доступна только игрокам."));
            return 0;
        }
        int slot = player.getInventory().selected;
        Outcome outcome = ExchangeService.get().createSellOrder(player, slot, price, amount);
        sendOutcome(src, outcome);
        return outcome.isSuccess() ? 1 : 0;
    }

    private static void sendOutcome(CommandSourceStack src, Outcome outcome) {
        if (outcome.message() != null && !outcome.message().isEmpty()) {
            if (outcome.isSuccess()) {
                src.sendSuccess(() -> Component.literal(outcome.message()), false);
            } else {
                src.sendFailure(Component.literal(outcome.message()));
            }
        }
    }
}