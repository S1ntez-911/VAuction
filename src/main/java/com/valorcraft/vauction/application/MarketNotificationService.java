package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.trade.Trade;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import com.valorcraft.vauction.persistence.PlayerMarketStateRepository;
import com.valorcraft.vauction.persistence.TradeRepository;
import com.valorcraft.vauction.persistence.IocOrderRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Post-settlement, bounded player feedback. It never participates in matching. */
public final class MarketNotificationService {
    private static final int FLUSH_INTERVAL_TICKS = 60;
    private static final int MAX_BATCHES_PER_FLUSH = 32;

    private final DatabaseManager database;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final DeliveryRepository deliveries;
    private final PlayerMarketStateRepository states;
    private final MinecraftServer server;
    private final IocOrderRepository iocOrders = new IocOrderRepository();
    private final Map<UUID, MarketNotificationBatch> batches = new LinkedHashMap<>();
    private int ticksUntilFlush = FLUSH_INTERVAL_TICKS;

    public MarketNotificationService(DatabaseManager database, OrderRepository orders,
                                     TradeRepository trades, DeliveryRepository deliveries,
                                     PlayerMarketStateRepository states, MinecraftServer server) {
        this.database = database;
        this.orders = orders;
        this.trades = trades;
        this.deliveries = deliveries;
        this.states = states;
        this.server = server;
    }

    public void onSettled(Trade trade) {
        ServerPlayer buyer = server.getPlayerList().getPlayer(trade.buyerUuid());
        boolean instantBuyer = database.query(c -> iocOrders.exists(c, trade.buyOrderId()));
        if (buyer != null && !instantBuyer) {
            batches.computeIfAbsent(trade.buyerUuid(), ignored -> new MarketNotificationBatch())
                    .add(trade, OrderSide.BUY);
        } else if (buyer != null) {
            markSeenNow(buyer, trade);
        }
        ServerPlayer seller = server.getPlayerList().getPlayer(trade.sellerUuid());
        boolean instantSeller = database.query(c -> iocOrders.exists(c, trade.sellOrderId()));
        if (seller != null && !instantSeller) {
            batches.computeIfAbsent(trade.sellerUuid(), ignored -> new MarketNotificationBatch())
                    .add(trade, OrderSide.SELL);
        } else if (seller != null) {
            markSeenNow(seller, trade);
        }
    }

    public void tick() {
        if (--ticksUntilFlush > 0) return;
        ticksUntilFlush = FLUSH_INTERVAL_TICKS;
        int sent = 0;
        Iterator<Map.Entry<UUID, MarketNotificationBatch>> iterator = batches.entrySet().iterator();
        while (iterator.hasNext() && sent++ < MAX_BATCHES_PER_FLUSH) {
            Map.Entry<UUID, MarketNotificationBatch> entry = iterator.next();
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue; // durable cursor is intentionally not advanced
            MarketNotificationBatch batch = entry.getValue();
            for (MarketNotificationBatch.OrderBatch orderBatch : batch.orders()) {
                Order order = database.query(c -> orders.findById(c, orderBatch.orderId()).orElse(null));
                String item = order == null ? "предметов" : order.item().displayName();
                String action = orderBatch.side() == OrderSide.BUY ? "Куплено " : "Продано ";
                String progress = order == null ? "" : order.remainingQuantity() == 0
                        ? " Заявка выполнена полностью."
                        : " Осталось: " + order.remainingQuantity() + ".";
                player.sendSystemMessage(Component.literal("[Биржа] " + action + orderBatch.quantity()
                        + " " + item + " в " + orderBatch.fills()
                        + pluralDeals(orderBatch.fills()) + "." + progress)
                        .withStyle(orderBatch.side() == OrderSide.BUY
                                ? ChatFormatting.GREEN : ChatFormatting.GOLD));
            }
            if (batch.hasPurchases()) {
                player.sendSystemMessage(Component.literal("[Получить предметы]")
                        .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah"))));
            }
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.45f, 1.1f);
            long deliveryCursor = database.query(c -> deliveries.latestClaimableId(c, player.getUUID()));
            MarketNotificationBatch.Cursor cursor = batch.cursor();
            database.inTransaction(c -> {
                states.advance(c, player.getUUID(), cursor.settledAt(), cursor.tradeId(), deliveryCursor);
                return null;
            });
        }
    }

    public void playerLoggedIn(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerMarketStateRepository.State state = database.query(c -> states.find(c, playerId).orElse(null));
        if (state == null) {
            TradeRepository.Cursor trade = database.query(c -> trades.latestForPlayer(c, playerId));
            long delivery = database.query(c -> deliveries.latestClaimableId(c, playerId));
            database.inTransaction(c -> {
                states.insertCurrent(c, playerId, trade.settledAt(), trade.tradeId(), delivery);
                return null;
            });
            return;
        }
        TradeRepository.PlayerSummary summary = database.query(c ->
                trades.summaryAfter(c, playerId, state.tradeAt(), state.tradeId()));
        DeliveryRepository.ClaimableSummary claims = database.query(c ->
                deliveries.claimableAfter(c, playerId, state.deliveryId()));
        if (summary.empty() && claims.count() == 0) return;

        StringBuilder text = new StringBuilder("[Биржа] Пока вас не было:");
        if (summary.completedOrders() > 0) text.append(" завершено заявок: ").append(summary.completedOrders()).append(';');
        if (summary.partialOrders() > 0) text.append(" частично исполнено: ").append(summary.partialOrders()).append(';');
        if (summary.boughtQuantity() > 0) text.append(" куплено предметов: ").append(summary.boughtQuantity()).append(';');
        if (summary.soldQuantity() > 0) text.append(" продано предметов: ").append(summary.soldQuantity()).append(';');
        if (claims.count() > 0) text.append(" получений: ").append(claims.count()).append('.');
        player.sendSystemMessage(Component.literal(text.toString()).withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("[Открыть биржу]  [Получить предметы]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah"))));
        database.inTransaction(c -> {
            states.advance(c, playerId, summary.cursor().settledAt(), summary.cursor().tradeId(),
                    claims.latestId());
            return null;
        });
    }

    public boolean firstMarketOpen(UUID playerId) {
        return database.inTransaction(c -> states.markOnboardingShown(c, playerId));
    }

    public void clear() {
        batches.clear();
    }

    private void markSeenNow(ServerPlayer player, Trade trade) {
        long at = trade.settledAt() == null ? trade.createdAt() : trade.settledAt();
        long delivery = database.query(c -> deliveries.latestClaimableId(c, player.getUUID()));
        database.inTransaction(c -> {
            states.advance(c, player.getUUID(), at, trade.tradeId().toString(), delivery);
            return null;
        });
    }

    private static String pluralDeals(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) return " сделке";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return " сделках";
        return " сделках";
    }
}
