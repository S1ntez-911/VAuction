package com.valorcraft.vauction.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.valorcraft.vauction.VAuctionMod;
import com.valorcraft.vauction.model.AuctionListing;
import com.valorcraft.vauction.service.AuctionService;
import com.valorcraft.vauction.ui.AuctionMenu;
import com.valorcraft.vauction.ui.ConfirmMenu;
import com.valorcraft.vauction.ui.UserAuctionsMenu;
import com.valorcraft.vauction.lang.AuctionLang;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class AuctionCommands {
    private AuctionCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("ah")
                .executes(AuctionCommands::open)
                .then(Commands.literal("open").executes(AuctionCommands::open))
                .then(Commands.literal("help").executes(AuctionCommands::help))
                .then(Commands.literal("?").executes(AuctionCommands::help))
                .then(Commands.literal("search").then(Commands.argument("query", StringArgumentType.greedyString())
                        .executes(AuctionCommands::search)))
                .then(Commands.literal("player").then(Commands.argument("name", StringArgumentType.word())
                        .executes(AuctionCommands::player)))
                .then(Commands.literal("history").executes(AuctionCommands::history))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2))
                        .executes(AuctionCommands::reload))
                .then(Commands.literal("perf").requires(source -> source.hasPermission(2))
                        .executes(AuctionCommands::perf))
                .then(Commands.literal("recover").requires(source -> source.hasPermission(2))
                        .executes(AuctionCommands::recover))
                .then(Commands.literal("sell")
                        .then(Commands.argument("price", StringArgumentType.word())
                                .executes(ctx -> sell(ctx, Integer.MAX_VALUE))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 9999))
                                        .executes(ctx -> sell(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(AuctionCommands::cancel)))
                .then(Commands.literal("claim").executes(AuctionCommands::claim))
                .then(Commands.literal("list").executes(AuctionCommands::list));
        dispatcher.register(root);
        dispatcher.register(Commands.literal("auction").redirect(dispatcher.getRoot().getChild("ah")));
    }

    private static int open(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!ready(player)) return 0;
        AuctionService service = VAuctionMod.service();
        AuctionMenu.open(player, service, 0);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        for (String line : AuctionLang.text("chat.help").split("\\n", -1))
            player.sendSystemMessage(AuctionLang.legacy(line));
        int decimals = com.valorcraft.veconomy.EconomyCore.isStarted()
                ? com.valorcraft.veconomy.EconomyCore.settings().decimalPlaces : 0;
        player.sendSystemMessage(AuctionLang.component(decimals > 0 ? "chat.decimals.enabled" : "chat.decimals.disabled",
                "decimals", decimals));
        return 1;
    }

    private static void helpLine(ServerPlayer player, String command, String description) {
        player.sendSystemMessage(Component.literal(command).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY)));
    }

    private static int sell(CommandContext<CommandSourceStack> context, int amount)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!ready(player)) return 0;
        if (player.getMainHandItem().isEmpty()) {
            player.sendSystemMessage(AuctionLang.legacy("&c" + AuctionLang.text("error.empty_hand")));
            return 0;
        }
        ConfirmMenu.openSell(player, VAuctionMod.service(), StringArgumentType.getString(context, "price"), amount);
        return 1;
    }

    private static int search(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = context.getSource().getPlayerOrException();
        if (!ready(p)) return 0;
        AuctionMenu.open(p, VAuctionMod.service(), 0, com.valorcraft.vauction.ui.AuctionCategory.ALL,
                com.valorcraft.vauction.ui.AuctionSort.NEWEST, StringArgumentType.getString(context, "query"), ""); return 1;
    }

    private static int player(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = context.getSource().getPlayerOrException();
        if (!ready(p)) return 0;
        UserAuctionsMenu.openPlayer(p, VAuctionMod.service(), StringArgumentType.getString(context, "name"), 0);
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!ready(player)) return 0;
        UserAuctionsMenu.open(player, VAuctionMod.service(), UserAuctionsMenu.Mode.HISTORY, 0); return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        try {
            com.valorcraft.vauction.config.AuctionConfig.reload();
        } catch (RuntimeException e) {
            VAuctionMod.LOGGER.error("Не удалось перезагрузить VAuction.toml", e);
            context.getSource().sendFailure(AuctionLang.component("chat.config_reload_failed"));
            return 0;
        }
        boolean loaded = AuctionLang.load();
        com.valorcraft.vauction.ui.AuctionCategory.clearCache();
        VAuctionMod.service().invalidateCaches();
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof com.valorcraft.vauction.ui.ReloadableMenu menu) menu.refreshConfig();
        }
        if (loaded) context.getSource().sendSuccess(() -> AuctionLang.component("chat.config_reload"), false);
        else context.getSource().sendFailure(AuctionLang.component("chat.config_reload_partial"));
        return loaded ? 1 : 0;
    }

    private static int perf(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> AuctionLang.component("chat.perf", "stats",
                VAuctionMod.service().performanceReport()), false);
        return 1;
    }

    private static int recover(CommandContext<CommandSourceStack> context) {
        if (!VAuctionMod.service().isAvailable()) {
            context.getSource().sendFailure(AuctionLang.component("error.storage_unavailable"));
            return 0;
        }
        VAuctionMod.service().recoverPending();
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers())
            VAuctionMod.service().recoverSaleIntents(player);
        context.getSource().sendSuccess(() -> AuctionLang.component("chat.recover"), false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!ready(player)) return 0;
        return VAuctionMod.service().cancel(player,
                StringArgumentType.getString(context, "id")) ? 1 : 0;
    }

    private static int claim(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return ready(player) ? VAuctionMod.service().claim(player) : 0;
    }

    private static int list(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!ready(player)) return 0;
        List<AuctionListing> own = VAuctionMod.service().activeListings().stream()
                .filter(it -> it.sellerId().equals(player.getUUID())).toList();
        if (own.isEmpty()) {
            player.sendSystemMessage(AuctionLang.component("chat.list.empty"));
            return 0;
        }
        player.sendSystemMessage(AuctionLang.component("chat.list.title"));
        for (AuctionListing listing : own) {
            player.sendSystemMessage(AuctionLang.component("chat.list.entry", "id", AuctionService.shortId(listing.id()),
                    "item", listing.item().getHoverName().getString(), "count", listing.item().getCount(),
                    "price", VAuctionMod.service().format(listing.price())));
        }
        return own.size();
    }

    private static boolean ready(ServerPlayer player) {
        if (VAuctionMod.service().isAvailable()) return true;
        player.sendSystemMessage(AuctionLang.component("error.storage_unavailable"));
        return false;
    }
}
