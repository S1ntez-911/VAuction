package com.valorcraft.vauction.gui;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.valorcraft.vauction.application.SimpleAuctionService;
import com.valorcraft.vauction.bootstrap.VAuctionCore;
import com.valorcraft.vauction.item.MarketCategoryClassifier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/** Minimal player command surface for the fixed-price auction. */
final class MarketCommands {
    private MarketCommands() {}

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(root("ah"));
        event.getDispatcher().register(root("auction"));
        event.getDispatcher().register(root("market"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .executes(ctx -> open(ctx.getSource()))
                .then(Commands.literal("sell")
                        .executes(ctx -> sellHelp(ctx.getSource()))
                        .then(Commands.argument("price", StringArgumentType.word())
                                .suggests(MarketCommands::suggestPrices)
                                .executes(ctx -> sell(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "price")))))
                .then(Commands.literal("search")
                        .executes(ctx -> fail(ctx.getSource(), "Использование: /ah search <название>"))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> search(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "text")))))
                .then(Commands.literal("mine").executes(ctx -> mine(ctx.getSource())))
                .then(Commands.literal("claims").executes(ctx -> mine(ctx.getSource())))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("ui").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(ctx -> uiReload(ctx.getSource()))))
                .then(Commands.literal("admin").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reloadui").executes(ctx -> uiReload(ctx.getSource())))
                        .then(Commands.literal("reloadcategories").executes(ctx -> categoryReload(ctx.getSource())))
                        .then(Commands.literal("category").executes(ctx -> categoryInfo(ctx.getSource())))
                        .then(Commands.literal("recover").executes(ctx -> recover(ctx.getSource()))))
                .then(Commands.argument("unknown", StringArgumentType.greedyString())
                        .executes(ctx -> help(ctx.getSource())));
    }

    private static int open(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        MarketController.instance().open(player);
        return 1;
    }

    private static int search(CommandSourceStack source, String query) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        MarketController.instance().search(player, query, 0);
        return 1;
    }

    private static int mine(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        MarketController.instance().openOrders(player);
        return 1;
    }

    private static int sell(CommandSourceStack source, String priceText) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        final long price;
        try { price = CurrencyInput.parse(priceText); }
        catch (CurrencyInput.InvalidPrice e) { return fail(source, e.getMessage()); }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return fail(source, "Возьмите продаваемый стек в основную руку.");
        SimpleAuctionService.Outcome outcome = VAuctionCore.instance().simpleAuctionService()
                .create(player.getUUID(), stack.copy(), price);
        if (!outcome.success()) return fail(source, outcome.message());
        source.sendSuccess(() -> Component.literal(outcome.message()
                + " Цена за весь стек: " + CurrencyText.format(price) + ".")
                .withStyle(outcome.result() == SimpleAuctionService.Result.ACCEPTED_PENDING
                        ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);
        return 1;
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Аукцион ValorCraft").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("/ah — открыть все лоты").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("/ah sell <цена> — продать весь стек в руке")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("/ah mine — показать только свои лоты")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Покупка: нажмите на товар и подтвердите.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int sellHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Использование: /ah sell <цена>")
                .withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Команда выставляет весь стек из основной руки. Цена указывается за весь лот.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int uiReload(CommandSourceStack source) {
        String error = UiConfig.reload();
        if (error != null) return fail(source, "Не удалось применить интерфейс: " + error);
        MarketController.instance().closeAll(source.getServer());
        source.sendSuccess(() -> Component.literal("Интерфейс аукциона обновлён.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int categoryReload(CommandSourceStack source) {
        String error = VAuctionCore.instance().reloadMarketCategories();
        if (error != null) return fail(source, error);
        MarketController.instance().closeAll(source.getServer());
        source.sendSuccess(() -> Component.literal("Категории обновлены.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int categoryInfo(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return fail(source, "Возьмите предмет в основную руку.");
        MarketCategoryClassifier.Result result = MarketCategoryClassifier.diagnose(stack);
        source.sendSuccess(() -> Component.literal("Раздел: " + result.category().id()
                + ". Причина: " + result.reason()).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int recover(CommandSourceStack source) {
        try {
            int simple = VAuctionCore.instance().simpleAuctionService().recoverReserved(128);
            var old = VAuctionCore.instance().runRecoverySlice();
            source.sendSuccess(() -> Component.literal("Восстановление завершено: новых покупок " + simple
                    + ", старых операций " + old.operationsAttempted() + ".").withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (RuntimeException e) {
            return fail(source, "Не удалось выполнить восстановление: " + e.getMessage());
        }
    }

    private static CompletableFuture<Suggestions> suggestPrices(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        suggest(builder, minor(1)); suggest(builder, minor(10)); suggest(builder, minor(100));
        return builder.buildFuture();
    }

    private static long minor(long major) {
        try { return Math.multiplyExact(major, BigDecimal.TEN.pow(CurrencyText.decimalPlaces()).longValueExact()); }
        catch (RuntimeException e) { return major; }
    }

    private static void suggest(SuggestionsBuilder builder, long minor) {
        try { builder.suggest(CurrencyInput.formatAmount(minor)); } catch (RuntimeException ignored) {}
    }

    private static boolean ready(CommandSourceStack source) {
        if (VAuctionCore.instance().isRunning() && VAuctionCore.instance().simpleAuctionService() != null) return true;
        fail(source, "Аукцион сейчас недоступен.");
        return false;
    }

    private static ServerPlayer player(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) return player;
        fail(source, "Команда доступна только игрокам.");
        return null;
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text).withStyle(ChatFormatting.RED));
        return 0;
    }
}
