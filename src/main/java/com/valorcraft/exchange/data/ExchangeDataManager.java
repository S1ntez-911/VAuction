package com.valorcraft.exchange.data;

import com.valorcraft.exchange.config.ExchangeConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Сохранённые данные биржи (World Saved Data, привязано к миру).
 * <p>
 * Потокобезопасные структуры {@link ConcurrentHashMap}; все мутации выполняются
 * на главном потоке сервера (см. {@code ExchangeService}), поэтому дополнительные
 * блокировки не требуются, а {@link #setDirty()} вызывается на каждом изменении.
 */
public final class ExchangeDataManager extends SavedData {

    public static final String DATA_NAME = "resource_exchange_data";

    private final Map<UUID, SellOrder> sellOrders = new ConcurrentHashMap<>();
    private final Map<UUID, BuyOrder> buyOrders = new ConcurrentHashMap<>();
    private final Map<UUID, Long> frozenFunds = new ConcurrentHashMap<>();
    private final List<ExchangeLogEntry> history = new ArrayList<>();
    private final Map<UUID, List<ItemStack>> mailboxes = new ConcurrentHashMap<>();
    private long serverCommission = 0;
    private long lastExpiryCheck = 0;

    private ExchangeDataManager() {
        super();
    }

    private ExchangeDataManager(CompoundTag tag) {
        super();
        load(tag);
    }

    public static ExchangeDataManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ExchangeDataManager::new,
                ExchangeDataManager::new, DATA_NAME);
    }

    // ------------------------------------------------------------------ геттеры

    public List<SellOrder> sellOrders() {
        return sellOrders.values().stream().sorted(Comparator.comparingLong(SellOrder::createdAt)).toList();
    }

    public List<BuyOrder> buyOrders() {
        return buyOrders.values().stream().sorted(Comparator.comparingLong(BuyOrder::createdAt)).toList();
    }

    public SellOrder sellOrder(UUID id) {
        return sellOrders.get(id);
    }

    public BuyOrder buyOrder(UUID id) {
        return buyOrders.get(id);
    }

    /** Суммарная заморозка игрока по всем активным заявкам (minor units). */
    public long frozenOf(UUID playerId) {
        return frozenFunds.getOrDefault(playerId, 0L);
    }

    /** Пересчитать заморозку игрока из активных заявок (после каждого изменения заявок). */
    public void recalcFrozen(UUID playerId) {
        long sum = buyOrders.values().stream()
                .filter(BuyOrder::active)
                .filter(o -> o.buyerUUID().equals(playerId))
                .mapToLong(o -> o.pricePerUnit() * (long) o.remaining())
                .sum();
        if (sum > 0) {
            frozenFunds.put(playerId, sum);
        } else {
            frozenFunds.remove(playerId);
        }
        setDirty();
    }

    public long serverCommission() {
        return serverCommission;
    }

    public List<ExchangeLogEntry> history() {
        return List.copyOf(history);
    }

    public List<ItemStack> mailbox(UUID playerId) {
        return List.copyOf(mailboxes.getOrDefault(playerId, List.of()));
    }

    public boolean hasMailboxItems(UUID playerId) {
        List<ItemStack> items = mailboxes.get(playerId);
        return items != null && items.stream().anyMatch(s -> !s.isEmpty());
    }

    public int sellOrderCountFor(UUID playerId) {
        return (int) sellOrders.values().stream()
                .filter(o -> o.sellerUUID().equals(playerId)).count();
    }

    public int buyCountFor(UUID playerId) {
        return (int) buyOrders.values().stream()
                .filter(o -> o.buyerUUID().equals(playerId) && o.active()).count();
    }

    /** Активные заявки на покупку этого предмета по цене >= указанной. */
    public List<BuyOrder> activeBuyOrdersMatching(ItemStack sample, long maxPrice) {
        return buyOrders.values().stream()
                .filter(BuyOrder::active)
                .filter(o -> ItemStack.isSameItemSameTags(o.sample(), sample))
                .filter(o -> o.pricePerUnit() >= maxPrice)
                .collect(Collectors.toList());
    }

    /** Активные лоты на продажу предмета по цене <= указанной. */
    public List<SellOrder> sellOrdersMatching(ItemStack sample, long maxPrice) {
        return sellOrders.values().stream()
                .filter(o -> o.remainingQuantity() > 0)
                .filter(o -> ItemStack.isSameItemSameTags(o.sample(), sample))
                .filter(o -> o.pricePerUnit() <= maxPrice)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ мутации

    public void putSellOrder(SellOrder order) {
        sellOrders.put(order.id(), order);
        setDirty();
    }

    public void putBuyOrder(BuyOrder order) {
        buyOrders.put(order.id(), order);
        setDirty();
    }

    public void removeSellOrder(UUID id) {
        sellOrders.remove(id);
        setDirty();
    }

    public void removeBuyOrder(UUID id) {
        buyOrders.remove(id);
        setDirty();
    }

    public void updateSellQuantity(UUID id, int newRemaining) {
        SellOrder current = sellOrders.get(id);
        if (current == null) {
            return;
        }
        if (newRemaining <= 0) {
            sellOrders.remove(id);
        } else {
            sellOrders.put(id, new SellOrder(current.id(), current.sellerUUID(), current.sample(),
                    current.pricePerUnit(), current.totalQuantity(), newRemaining, current.createdAt()));
        }
        setDirty();
    }

    public void updateBuyOrder(UUID id, int newFulfilled, boolean active, int refEpoch) {
        BuyOrder current = buyOrders.get(id);
        if (current == null) {
            return;
        }
        buyOrders.put(id, new BuyOrder(current.id(), current.buyerUUID(), current.sample(),
                current.pricePerUnit(), current.totalRequested(), newFulfilled, current.createdAt(),
                active, refEpoch));
        setDirty();
    }

    public void freeze(UUID playerId, long amount) {
        frozenFunds.merge(playerId, amount, Long::sum);
        setDirty();
    }

    public void unfreeze(UUID playerId, long amount) {
        if (amount <= 0) {
            return;
        }
        frozenFunds.computeIfPresent(playerId, (id, current) -> Math.max(0, current - amount));
        setDirty();
    }

    public void addCommission(long amount) {
        if (amount > 0) {
            serverCommission += amount;
            setDirty();
        }
    }

    public void addMailboxItems(UUID playerId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        mailboxes.computeIfAbsent(playerId, k -> new ArrayList<>()).add(stack.copy());
        setDirty();
    }

    public void setMailbox(UUID playerId, List<ItemStack> items) {
        List<ItemStack> cleaned = items == null ? List.of()
                : items.stream().filter(s -> s != null && !s.isEmpty()).toList();
        if (cleaned.isEmpty()) {
            mailboxes.remove(playerId);
        } else {
            mailboxes.put(playerId, new ArrayList<>(cleaned));
        }
        setDirty();
    }

    /** Записать событие истории (усекая по конфигу). */
    public void note(ExchangeLogEntry entry) {
        history.add(0, entry);
        int limit = ExchangeConfig.maxTransactionHistory();
        while (history.size() > limit) {
            history.remove(history.size() - 1);
        }
        setDirty();
    }

    // ------------------------------------------------------------------ сериализация

    private void load(CompoundTag root) {
        serverCommission = root.getLong("serverCommission");
        lastExpiryCheck = root.getLong("lastExpiryCheck");

        ListTag sellList = root.getList("sellOrders", Tag.TAG_COMPOUND);
        for (int i = 0; i < sellList.size(); i++) {
            SellOrder order = SellOrder.fromNbt(sellList.getCompound(i));
            if (order != null) {
                sellOrders.put(order.id(), order);
            }
        }
        ListTag buyList = root.getList("buyOrders", Tag.TAG_COMPOUND);
        for (int i = 0; i < buyList.size(); i++) {
            BuyOrder order = BuyOrder.fromNbt(buyList.getCompound(i));
            if (order != null) {
                buyOrders.put(order.id(), order);
            }
        }
        CompoundTag frozen = root.getCompound("frozenFunds");
        for (String key : frozen.getAllKeys()) {
            try {
                frozenFunds.put(UUID.fromString(key), frozen.getLong(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag hist = root.getList("history", Tag.TAG_COMPOUND);
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag h = hist.getCompound(i);
            ExchangeLogEntry entry = ExchangeLogEntry.fromNbt(h);
            if (entry != null) {
                history.add(entry);
            }
        }
        CompoundTag boxes = root.getCompound("mailboxes");
        for (String key : boxes.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                ListTag items = boxes.getList(key, Tag.TAG_COMPOUND);
                List<ItemStack> stacks = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    ItemStack stack = ItemStack.of(items.getCompound(i));
                    if (!stack.isEmpty()) {
                        stacks.add(stack);
                    }
                }
                if (!stacks.isEmpty()) {
                    mailboxes.put(uuid, stacks);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("serverCommission", serverCommission);
        tag.putLong("lastExpiryCheck", lastExpiryCheck);

        ListTag sellList = new ListTag();
        for (SellOrder order : sellOrders.values()) {
            sellList.add(order.toNbt());
        }
        tag.put("sellOrders", sellList);

        ListTag buyList = new ListTag();
        for (BuyOrder order : buyOrders.values()) {
            buyList.add(order.toNbt());
        }
        tag.put("buyOrders", buyList);

        CompoundTag frozen = new CompoundTag();
        frozenFunds.forEach((id, amount) -> frozen.putLong(id.toString(), amount));
        tag.put("frozenFunds", frozen);

        ListTag hist = new ListTag();
        history.forEach(e -> hist.add(e.toNbt()));
        tag.put("history", hist);

        CompoundTag boxes = new CompoundTag();
        mailboxes.forEach((uuid, stacks) -> {
            ListTag items = new ListTag();
            stacks.forEach(s -> items.add(s.save(new CompoundTag())));
            boxes.put(uuid.toString(), items);
        });
        tag.put("mailboxes", boxes);
        return tag;
    }
}