package com.valorcraft.exchange.network;

import com.valorcraft.exchange.screen.ExchangeScreenClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Синхронизация рынка с сервера на клиент: лоты, заявки, почта игрока.
 */
public class SyncMarketPacket {

    public static final class SellEntry {
        public final UUID id;
        public final ItemStack item;
        public final long price;
        public final int remaining;

        public SellEntry(UUID id, ItemStack item, long price, int remaining) {
            this.id = id;
            this.item = item;
            this.price = price;
            this.remaining = remaining;
        }

        void write(FriendlyByteBuf buf) {
            buf.writeBoolean(id != null);
            if (id != null) {
                buf.writeUUID(id);
            }
            buf.writeItem(item);
            buf.writeLong(price);
            buf.writeInt(remaining);
        }

        static SellEntry read(FriendlyByteBuf buf) {
            UUID id = buf.readBoolean() ? buf.readUUID() : null;
            return new SellEntry(id, buf.readItem(), buf.readLong(), buf.readInt());
        }
    }

    public static final class BuyEntry {
        public final UUID id;
        public final ItemStack item;
        public final long price;
        public final int total;
        public final int fulfilled;

        public BuyEntry(UUID id, ItemStack item, long price, int total, int fulfilled) {
            this.id = id;
            this.item = item;
            this.price = price;
            this.total = total;
            this.fulfilled = fulfilled;
        }

        void write(FriendlyByteBuf buf) {
            buf.writeBoolean(id != null);
            if (id != null) {
                buf.writeUUID(id);
            }
            buf.writeItem(item);
            buf.writeLong(price);
            buf.writeInt(total);
            buf.writeInt(fulfilled);
        }

        static BuyEntry read(FriendlyByteBuf buf) {
            UUID id = buf.readBoolean() ? buf.readUUID() : null;
            return new BuyEntry(id, buf.readItem(), buf.readLong(), buf.readInt(), buf.readInt());
        }
    }

    private final List<SellEntry> sellOrders;
    private final List<BuyEntry> buyOrders;
    private final List<ItemStack> mailbox;
    private final long balance;

    public SyncMarketPacket(List<SellEntry> sellOrders, List<BuyEntry> buyOrders,
                            List<ItemStack> mailbox, long balance) {
        this.sellOrders = sellOrders;
        this.buyOrders = buyOrders;
        this.mailbox = mailbox;
        this.balance = balance;
    }

    public static void encode(SyncMarketPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.sellOrders.size());
        for (SellEntry e : msg.sellOrders) {
            e.write(buf);
        }
        buf.writeVarInt(msg.buyOrders.size());
        for (BuyEntry e : msg.buyOrders) {
            e.write(buf);
        }
        buf.writeVarInt(msg.mailbox.size());
        for (ItemStack s : msg.mailbox) {
            buf.writeItem(s);
        }
        buf.writeLong(msg.balance);
    }

    public SyncMarketPacket(FriendlyByteBuf buf) {
        int sc = buf.readVarInt();
        sellOrders = new ArrayList<>(sc);
        for (int i = 0; i < sc; i++) {
            sellOrders.add(SellEntry.read(buf));
        }
        int bc = buf.readVarInt();
        buyOrders = new ArrayList<>(bc);
        for (int i = 0; i < bc; i++) {
            buyOrders.add(BuyEntry.read(buf));
        }
        int mc = buf.readVarInt();
        mailbox = new ArrayList<>(mc);
        for (int i = 0; i < mc; i++) {
            mailbox.add(buf.readItem());
        }
        balance = buf.readLong();
    }

    public List<SellEntry> sellOrders() {
        return sellOrders;
    }

    public List<BuyEntry> buyOrders() {
        return buyOrders;
    }

    public List<ItemStack> mailbox() {
        return mailbox;
    }

    public long balance() {
        return balance;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ExchangeScreenClient.receiveMarket(this));
        ctx.get().setPacketHandled(true);
    }
}