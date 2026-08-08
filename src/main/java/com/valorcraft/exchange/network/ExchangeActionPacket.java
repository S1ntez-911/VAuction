package com.valorcraft.exchange.network;

import com.valorcraft.exchange.exchange.ExchangeService;
import com.valorcraft.exchange.exchange.ExchangeService.Outcome;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Действие из GUI биржи: клиент → сервер. Выполняется на главном потоке сервера.
 */
public class ExchangeActionPacket {

    public enum Action {
        REQUEST_MARKET,   // запросить синхронизацию рынка
        SELL_FROM_SLOT,   // slot, price
        BUY_ORDER,        // price, quantity, item (образец)
        BUY_FROM_SELL,    // orderId, quantity
        FULFILL_BUY,      // orderId, quantity
        CANCEL_SELL,      // orderId
        CANCEL_BUY,       // orderId
        CLAIM_MAILBOX     // забрать почту
    }

    private final Action action;
    private final int slot;
    private final long price;
    private final int quantity;
    private final UUID orderId;
    private final ItemStack item;

    public ExchangeActionPacket(Action action, int slot, long price, int quantity, UUID orderId, ItemStack item) {
        this.action = action;
        this.slot = slot;
        this.price = price;
        this.quantity = quantity;
        this.orderId = orderId;
        this.item = item == null ? ItemStack.EMPTY : item;
    }

    public static void encode(ExchangeActionPacket msg, FriendlyByteBuf buf) {
        msg.write(buf);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeInt(slot);
        buf.writeLong(price);
        buf.writeInt(quantity);
        buf.writeBoolean(orderId != null);
        if (orderId != null) {
            buf.writeUUID(orderId);
        }
        buf.writeItem(item);
    }

    public ExchangeActionPacket(FriendlyByteBuf buf) {
        action = buf.readEnum(Action.class);
        slot = buf.readInt();
        price = buf.readLong();
        quantity = buf.readInt();
        orderId = buf.readBoolean() ? buf.readUUID() : null;
        item = buf.readItem();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ExchangeService service = ExchangeService.get();
            Outcome outcome;
            switch (action) {
                case REQUEST_MARKET -> {
                    MarketSyncHelper.sendFullSync(player);
                    ctx.get().setPacketHandled(true);
                    return;
                }
                case SELL_FROM_SLOT -> outcome = service.createSellOrder(player, slot, price, quantity);
                case BUY_ORDER -> outcome = service.createBuyOrder(player, item, price, quantity);
                case BUY_FROM_SELL -> outcome = service.buyFromSellOrder(player, orderId, quantity);
                case FULFILL_BUY -> outcome = service.fulfillBuyOrder(player, orderId, quantity);
                case CANCEL_SELL -> outcome = service.cancelSellOrder(player, orderId);
                case CANCEL_BUY -> outcome = service.cancelBuyOrder(player, orderId);
                case CLAIM_MAILBOX -> {
                    int claimed = service.claimMailbox(player);
                    player.sendSystemMessage(Component.literal(
                            claimed > 0 ? "Почта забрана: " + claimed : "Почта пуста."));
                    ctx.get().setPacketHandled(true);
                    return;
                }
                default -> outcome = new Outcome(ExchangeService.Result.ORDER_NOT_FOUND, "Неизвестное действие.");
            }
            sendOutcomeTo(player, outcome);
        }
        ctx.get().setPacketHandled(true);
    }

    private static void sendOutcomeTo(ServerPlayer player, Outcome outcome) {
        if (outcome == null || outcome.message() == null || outcome.message().isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.literal(outcome.message()));
    }
}