package com.valorcraft.vauction.gui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.valorcraft.vauction.application.AuctionReadService;
import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.bootstrap.VAuctionCore;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.domain.order.Order;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Player-facing command fallback. Every root alias has the same tree. */
final class MarketCommands {
    private MarketCommands() {}

    static void register(RegisterCommandsEvent event) {
        CommandBuildContext context = event.getBuildContext();
        event.getDispatcher().register(root("market", context));
        event.getDispatcher().register(root("auction", context));
        event.getDispatcher().register(root("ah", context));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, CommandBuildContext context) {
        return Commands.literal(name)
                .executes(ctx -> open(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource()))
                        .then(Commands.literal("commands").executes(ctx -> helpCommands(ctx.getSource())))
                        .then(Commands.literal("sell").executes(ctx -> helpSell(ctx.getSource())))
                        .then(Commands.literal("buy").executes(ctx -> helpBuy(ctx.getSource()))))
                .then(Commands.literal("search").executes(ctx -> help(ctx.getSource()))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .suggests(MarketCommands::suggestItems)
                                .executes(ctx -> search(ctx.getSource(), StringArgumentType.getString(ctx, "text")))))
                .then(Commands.literal("set")
                        .requires(MarketCommands::inputDraftActive)
                        .then(Commands.argument("value", LongArgumentType.longArg(1))
                                .suggests(MarketCommands::suggestDraftInput)
                                .executes(ctx -> setExact(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "value")))))
                .then(Commands.literal("sell").executes(ctx -> helpSell(ctx.getSource()))
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .suggests(MarketCommands::suggestPrices)
                                .executes(ctx -> sell(ctx.getSource(), LongArgumentType.getLong(ctx, "price"), null))
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                        .suggests(MarketCommands::suggestQuantities)
                                        .executes(ctx -> sell(ctx.getSource(), LongArgumentType.getLong(ctx, "price"),
                                                IntegerArgumentType.getInteger(ctx, "quantity"))))))
                .then(Commands.literal("buy").executes(ctx -> helpBuy(ctx.getSource()))
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(ctx -> helpBuy(ctx.getSource()))
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                                        .suggests(MarketCommands::suggestCommonQuantities)
                                        .executes(ctx -> helpBuy(ctx.getSource()))
                                        .then(Commands.argument("maxPrice", LongArgumentType.longArg(1))
                                                .suggests(MarketCommands::suggestPrices)
                                                .executes(ctx -> buy(ctx, IntegerArgumentType.getInteger(ctx, "quantity"),
                                                        LongArgumentType.getLong(ctx, "maxPrice")))))))
                .then(Commands.literal("orders").executes(ctx -> orders(ctx.getSource())))
                .then(Commands.literal("cancel").executes(ctx -> helpCommands(ctx.getSource()))
                        .then(Commands.argument("orderId", StringArgumentType.word())
                                .suggests(MarketCommands::suggestOrders)
                                .executes(ctx -> cancel(ctx.getSource(), StringArgumentType.getString(ctx, "orderId")))))
                .then(Commands.literal("claims").executes(ctx -> claims(ctx.getSource())))
                .then(Commands.literal("claim").executes(ctx -> helpCommands(ctx.getSource()))
                        .then(Commands.argument("deliveryId", LongArgumentType.longArg(1))
                                .suggests(MarketCommands::suggestClaims)
                                .executes(ctx -> claim(ctx.getSource(), LongArgumentType.getLong(ctx, "deliveryId")))))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("ui")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(ctx -> uiReload(ctx.getSource()))))
                .then(Commands.argument("unknown", StringArgumentType.greedyString())
                        .executes(ctx -> help(ctx.getSource())));
    }

    /** Перечитывает config/vauction-ui.json и переоткрывает сессии (только оп). */
    private static int uiReload(CommandSourceStack source) {
        String error = UiConfig.reload();
        if (error != null) {
            return fail(source, UiConfig.fmt("ui.badJson", "error", error));
        }
        MarketController.instance().clear();
        source.sendSuccess(() -> Component.literal(UiConfig.text("ui.reloaded"))
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
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

    private static int orders(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        MarketController.instance().openOrders(player);
        return 1;
    }

    /**
     * Single contextual input command. Hidden from tab completion by
     * {@code requires} until the player owns an input draft, then applies
     * QUANTITY or PRICE depending on which GUI flow started the draft.
     */
    private static int setExact(CommandSourceStack source, long value) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        if (!MarketController.instance().setExact(player, value)) {
            return fail(source, "Сначала нажмите «Другое» или «Изменить цену» в /ah.");
        }
        return 1;
    }

    private static boolean inputDraftActive(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player
                && MarketController.instance().hasInputDraft(player.getUUID());
    }

    private static CompletableFuture<Suggestions> suggestDraftInput(CommandContext<CommandSourceStack> ctx,
                                                                    SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && readySilently()) {
            TradeDraft draft = MarketController.instance().inputDraft(player.getUUID());
            if (draft != null && !draft.expired()) {
                if (draft.expectedInput == TradeDraft.InputTarget.QUANTITY) {
                    builder.suggest(1);
                    builder.suggest(16);
                    builder.suggest(64);
                    int available = VAuctionCore.instance().auctionService()
                            .availableCount(player.getUUID(), draft.unit);
                    if (available > 0) builder.suggest(available, Component.literal("все доступные"));
                } else {
                    builder.suggest(1);
                    builder.suggest(100);
                    builder.suggest(1000);
                }
            }
        }
        return builder.buildFuture();
    }

    private static int claims(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return 0;
        MarketController.instance().openDeliveries(player);
        return 1;
    }

    private static int sell(CommandSourceStack source, long price, Integer requestedQuantity) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        ItemStack unit = player.getMainHandItem();
        if (unit.isEmpty()) return fail(source, "Возьмите продаваемый предмет в основную руку.");
        unit = unit.copy();
        unit.setCount(1);
        AuctionService service = VAuctionCore.instance().auctionService();
        int available = service.availableCount(player.getUUID(), unit);
        int quantity = requestedQuantity == null ? available : requestedQuantity;
        if (available <= 0 || quantity > available) {
            return fail(source, "Недостаточно точных предметов: доступно " + available + ".");
        }
        return outcome(source, service.createSellOrderFromInventory(player, unit, price, quantity,
                UUID.randomUUID()));
    }

    private static int buy(CommandContext<CommandSourceStack> ctx, int quantity, long maxPrice)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        ItemStack unit = ItemArgument.getItem(ctx, "item").createItemStack(1, false);
        return outcome(source, VAuctionCore.instance().auctionService().createBuyOrder(
                player.getUUID(), unit, maxPrice, quantity, UUID.randomUUID()));
    }

    private static int cancel(CommandSourceStack source, String rawId) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        try {
            return outcome(source, VAuctionCore.instance().auctionService().cancel(
                    player.getUUID(), UUID.fromString(rawId), "market-command"));
        } catch (IllegalArgumentException e) {
            return fail(source, "Некорректный UUID заявки. Нажмите Tab для выбора своей заявки.");
        }
    }

    private static int claim(CommandSourceStack source, long deliveryId) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        return outcome(source, VAuctionCore.instance().auctionService().claimDelivery(player.getUUID(), deliveryId));
    }

    private static int info(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null || !ready(source)) return 0;
        ItemStack unit = player.getMainHandItem();
        if (unit.isEmpty()) return fail(source, "Возьмите предмет в основную руку.");
        AuctionService service = VAuctionCore.instance().auctionService();
        MarketSummary summary = service.summary(unit);
        if (summary == null) return fail(source, "Этот предмет нельзя выставить на биржу.");
        int available = service.availableCount(player.getUUID(), unit);
        source.sendSuccess(() -> Component.literal("Биржа: " + unit.getHoverName().getString()
                + " | лучшая продажа: " + price(summary.bestAsk())
                + " | лучшая покупка: " + price(summary.bestBid())
                + " | у вас точных: " + available).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Биржа ValorCraft")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("На бирже игроки сами покупают и продают ресурсы.")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Как купить:").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Откройте /ah и нажмите ЛКМ по нужному товару. Выберите количество и нажмите «Купить сейчас».")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Как продать:").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Нажмите ПКМ по товару. Выберите количество и нажмите «Продать сейчас».")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Своя цена:").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Не нравится текущая цена — нажмите «Своя цена». Заявка будет ждать подходящего предложения и исполнится сама.")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Моё:").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Здесь находятся ваши активные заявки, купленные предметы и возвраты.")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("").withStyle(ChatFormatting.GRAY), false);
        helpLine(source, "/ah search <название>", "найти товар");
        source.sendSuccess(() -> Component.literal("[Открыть биржу]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah"))), false);
        source.sendSuccess(() -> Component.literal("[Дополнительные команды]").withStyle(style -> style
                .withColor(ChatFormatting.DARK_GRAY)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help commands"))), false);
        return 1;
    }

    private static int helpCommands(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Дополнительные команды:")
                .withStyle(ChatFormatting.GOLD), false);
        helpLine(source, "/ah search <название>", "найти товар");
        helpLine(source, "/ah sell <цена> [количество]", "создать заявку на продажу предмета из руки");
        helpLine(source, "/ah buy <предмет> <количество> <цена>", "создать заявку на покупку");
        source.sendSuccess(() -> Component.literal("Остальное делается в меню: /ah и «Моё».")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int helpSell(CommandSourceStack source) {
        helpLine(source, "/ah sell <цена> [количество]",
                "заявка на продажу точного предмета из основной руки; без количества — все совпадающие предметы");
        return 1;
    }

    private static int helpBuy(CommandSourceStack source) {
        helpLine(source, "/ah buy <предмет> <количество> <цена>",
                "заявка на покупку обычного предмета; точный вариант с особыми свойствами выбирайте в GUI");
        return 1;
    }

    private static void helpLine(CommandSourceStack source, String command, String description) {
        MutableComponent line = Component.literal(command).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)))
                .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> line, false);
    }

    private static CompletableFuture<Suggestions> suggestItems(CommandContext<CommandSourceStack> ctx,
                                                               SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);
    }

    private static CompletableFuture<Suggestions> suggestOrders(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && readySilently()) {
            for (Order order : read().playerOrders(player.getUUID(), 0).items()) {
                if (order.isActive()) builder.suggest(order.orderId().toString(),
                        Component.literal(order.item().displayName()));
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestClaims(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && readySilently()) {
            for (AuctionDelivery delivery : read().deliveries(player.getUUID(), 0).items()) {
                builder.suggest(Long.toString(delivery.deliveryId()), Component.literal(delivery.item().displayName()));
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestQuantities(CommandContext<CommandSourceStack> ctx,
                                                                    SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && readySilently() && !player.getMainHandItem().isEmpty()) {
            int available = VAuctionCore.instance().auctionService().availableCount(
                    player.getUUID(), player.getMainHandItem());
            if (available > 0) builder.suggest(available, Component.literal("все точные предметы"));
            int stack = player.getMainHandItem().getCount();
            if (stack > 1 && stack != available) builder.suggest(stack, Component.literal("стек в руке"));
        }
        builder.suggest(1);
        builder.suggest(16);
        builder.suggest(64);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestCommonQuantities(CommandContext<CommandSourceStack> ctx,
                                                                          SuggestionsBuilder builder) {
        builder.suggest(1); builder.suggest(16); builder.suggest(64);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPrices(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (readySilently()) {
            ItemStack unit = ctx.getNodes().stream().anyMatch(node -> "item".equals(node.getNode().getName()))
                    ? ItemArgument.getItem(ctx, "item").createItemStack(1, false)
                    : ctx.getSource().getPlayer() == null ? ItemStack.EMPTY
                    : ctx.getSource().getPlayer().getMainHandItem();
            if (!unit.isEmpty()) {
                MarketSummary summary = VAuctionCore.instance().auctionService().summary(unit);
                if (summary != null) {
                    if (summary.bestAsk() > 0) builder.suggest(Long.toString(summary.bestAsk()), Component.literal("лучшая продажа"));
                    if (summary.bestBid() > 0) builder.suggest(Long.toString(summary.bestBid()), Component.literal("лучшая покупка"));
                    if (summary.lastTradePrice() > 0) builder.suggest(Long.toString(summary.lastTradePrice()), Component.literal("последняя сделка"));
                }
            }
        }
        builder.suggest(1, Component.literal("минимальная цена"));
        builder.suggest(100); builder.suggest(1000); builder.suggest(10000);
        return builder.buildFuture();
    }

    private static int outcome(CommandSourceStack source, AuctionService.Outcome result) {
        if (result.isSuccess()) {
            String message;
            if (result.status() == AuctionService.Result.ACCEPTED_PENDING) {
                message = "Операция принята и безопасно завершается.";
            } else if (result.order() == null) {
                message = result.message();
            } else if (result.order().status() == com.valorcraft.vauction.domain.order.OrderStatus.CANCELLED) {
                message = "Заявка отменена; возврат доступен в «Моём».";
            } else if (result.order().remainingQuantity() == 0) {
                message = "Заявка исполнена полностью.";
            } else if (result.filledQuantity() > 0) {
                message = "Исполнено " + result.filledQuantity() + ", осталось "
                        + result.order().remainingQuantity() + "; заявка продолжает ждать.";
            } else {
                message = "Заявка создана и ждёт подходящего предложения.";
            }
            source.sendSuccess(() -> Component.literal(message)
                    .withStyle(ChatFormatting.GREEN), false);
            if (result.order() != null) {
                String command = result.order().remainingQuantity() == 0
                        && result.order().side() == com.valorcraft.vauction.domain.order.OrderSide.BUY
                        ? "/ah claims" : "/ah orders";
                String label = "[Моё]";
                source.sendSuccess(() -> Component.literal(label).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))), false);
            }
            return 1;
        }
        return fail(source, result.message());
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static ServerPlayer player(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) return player;
        source.sendFailure(Component.literal("Команда доступна только игрокам.").withStyle(ChatFormatting.RED));
        return null;
    }

    private static boolean ready(CommandSourceStack source) {
        if (readySilently()) return true;
        fail(source, "Биржа сейчас недоступна.");
        return false;
    }

    private static boolean readySilently() {
        return VAuctionCore.instance().isRunning()
                && VAuctionCore.instance().auctionService() != null
                && VAuctionCore.instance().auctionReadService() != null;
    }

    private static AuctionReadService read() {
        return VAuctionCore.instance().auctionReadService();
    }

    private static String price(long value) {
        return value <= 0 ? "—" : CurrencyText.format(value);
    }
}
