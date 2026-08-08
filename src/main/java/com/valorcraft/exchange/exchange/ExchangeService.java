package com.valorcraft.exchange.exchange;

import com.valorcraft.exchange.config.ExchangeConfig;
import com.valorcraft.exchange.data.BuyOrder;
import com.valorcraft.exchange.data.ExchangeDataManager;
import com.valorcraft.exchange.data.ExchangeLogEntry;
import com.valorcraft.exchange.data.ExchangeTransactionType;
import com.valorcraft.exchange.data.SellOrder;
import com.valorcraft.exchange.integration.VEconomyIntegration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Бизнес-логика биржи. Все операции — на главном потоке сервера; каждая операция,
 * меняющая деньги VEconomy, проверяет возвращаемое значение, при ошибке — полный
 * откат (возврат средств/предметов). Операции с конкретным ордером идемпотентны
 * по ключам транзакций VEconomy (защита от дупов при повторах).
 */
public final class ExchangeService {

    public static final String REASON_PREFIX = "Биржа: ";

    public enum Result {
        SUCCESS,
        NOT_A_PLAYER,
        ORDER_NOT_FOUND,
        NOT_YOUR_ORDER,
        SELF_TRADE,
        INVALID_QUANTITY,
        INVALID_PRICE,
        OVER_LIMIT,
        BLACKLISTED,
        INSUFFICIENT_FUNDS,
        INVENTORY_FULL,
        ECONOMY_FAILED
    }

    /** Результат операции: статус + сообщение игроку (null — без сообщения). */
    public record Outcome(Result status, String message) {
        public static Outcome ok(String message) {
            return new Outcome(Result.SUCCESS, message);
        }

        public boolean isSuccess() {
            return status == Result.SUCCESS;
        }
    }

    private static final ExchangeService INSTANCE = new ExchangeService();

    private ExchangeService() {}

    public static ExchangeService get() {
        return INSTANCE;
    }

    /** Reason для транзакций VEconomy с префиксом биржи. */
    public static String reason(String detail) {
        return REASON_PREFIX + detail;
    }

    private static ExchangeDataManager data(MinecraftServer server) {
        return ExchangeDataManager.get(server.overworld());
    }

    private static ExchangeDataManager data(ServerPlayer player) {
        return ExchangeDataManager.get(player.serverLevel());
    }

    // ================================================================ создание лота на продажу

    /**
     * Выставить лот из слота инвентаря игрока. Перед созданием матчимся с заявками
     * на покупку: сначала самые дорогие для продавца. Остаток — в лот.
     */
    public Outcome createSellOrder(ServerPlayer player, int slotIndex, long pricePerUnit, int quantity) {
        if (player == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эта команда доступна только игрокам.");
        }
        if (quantity <= 0 || slotIndex < 0 || slotIndex >= player.getInventory().getContainerSize()) {
            return new Outcome(Result.INVALID_QUANTITY, "Некорректные параметры лота.");
        }
        if (pricePerUnit <= 0) {
            return new Outcome(Result.INVALID_PRICE, "Цена должна быть положительной.");
        }
        ItemStack stack = player.getInventory().getItem(slotIndex);
        if (stack.isEmpty()) {
            return new Outcome(Result.INVALID_QUANTITY, "В этом слоте пусто.");
        }
        if (stack.getCount() < quantity || !isTradeable(stack)) {
            return new Outcome(Result.INVALID_QUANTITY, "В слоте недостаточно предметов или они запрещены.");
        }
        ExchangeDataManager data = data(player);
        if (data.sellOrderCountFor(player.getUUID()) >= ExchangeConfig.maxSellOrdersPerPlayer()) {
            return new Outcome(Result.OVER_LIMIT,
                    "Достигнут лимит лотов (" + ExchangeConfig.maxSellOrdersPerPlayer() + ").");
        }

        ItemStack sample = stack.copy();
        sample.setCount(1);

        // Матчинг с заявками на покупку: сначала самые дорогие для продавца.
        List<BuyOrder> candidates = data.activeBuyOrdersMatching(sample, pricePerUnit);
        candidates.sort(Comparator.comparingLong((BuyOrder b) -> b.pricePerUnit()).reversed());

        int matchedCount = 0;
        for (BuyOrder buy : candidates) {
            int want = quantity - matchedCount;
            if (want <= 0) {
                break;
            }
            if (buy.pricePerUnit() < pricePerUnit) {
                continue;
            }
            int chunk = Math.min(want, buy.remaining());
            long earnings = buy.pricePerUnit() * (long) chunk;
            Outcome fulfilled = fulfillBuyOrderInternal(player, buy, chunk, earnings);
            if (fulfilled.isSuccess()) {
                matchedCount += chunk;
            }
        }

        int remainingForLot = quantity - matchedCount;
        if (remainingForLot > 0) {
            reduceSlot(player, slotIndex, remainingForLot);
            SellOrder order = SellOrder.create(player.getUUID(), sample, pricePerUnit, remainingForLot);
            data.putSellOrder(order);
            data.note(new ExchangeLogEntry(System.currentTimeMillis(), ExchangeTransactionType.CREATE_SELL,
                    player.getUUID(), null, order.itemName(), order.remainingQuantity(),
                    order.pricePerUnit() * (long) order.remainingQuantity()));
            player.getInventory().setChanged();
            return Outcome.ok("Лот выставлен: " + order.itemName() + " x" + order.remainingQuantity()
                    + " по " + order.pricePerUnit());
        }
        player.getInventory().setChanged();
        return Outcome.ok("Весь объём продан по заявкам на покупку");
    }

    private static void reduceSlot(ServerPlayer player, int slotIndex, int count) {
        ItemStack stack = player.getInventory().getItem(slotIndex);
        stack.shrink(count);
        if (stack.getCount() <= 0) {
            player.getInventory().setItem(slotIndex, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
    }

    // ================================================================ покупка из лота

    /** Купить {@code quantity} из лота {@code sellOrderId}. */
    public Outcome buyFromSellOrder(ServerPlayer buyer, UUID sellOrderId, int quantity) {
        if (buyer == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту команду может выполнить только игрок.");
        }
        if (quantity <= 0) {
            return new Outcome(Result.INVALID_QUANTITY, "Количество должно быть положительным.");
        }
        ExchangeDataManager data = data(buyer);
        SellOrder order = data.sellOrder(sellOrderId);
        if (order == null || order.remainingQuantity() <= 0) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Лот не найден или распродан.");
        }
        if (order.sellerUUID().equals(buyer.getUUID())) {
            return new Outcome(Result.SELF_TRADE, "Нельзя покупать собственный лот.");
        }
        int buyQty = Math.min(quantity, order.remainingQuantity());
        long totalPrice = order.pricePerUnit() * (long) buyQty;
        if (!VEconomyIntegration.has(buyer.getUUID(), totalPrice)) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, "Недостаточно средств на балансе.");
        }

        // 1. Списание с покупателя.
        if (!VEconomyIntegration.withdraw(buyer.getUUID(), totalPrice,
                reason("покупка лота #" + order.id()), order.id() + ":buy")) {
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось списать средства.");
        }

        // 2. Выдача предметов (инвентарь → почта).
        addItemsToInventoryOrMailboxByUuid(buyer.getServer(), buyer.getUUID(),
                order.sample(), buyQty);

        // 3. Комиссия + выплата продавцу (продавец может быть офлайн).
        long sellerNet = commissionAndNet(totalPrice);
        long commission = totalPrice - sellerNet;
        if (VEconomyIntegration.deposit(order.sellerUUID(), sellerNet,
                reason("продажа лота #" + order.id()), order.id() + ":sell")) {
            data.addCommission(commission);
        }

        // 4. Обновление лота.
        data.updateSellQuantity(order.id(), order.remainingQuantity() - buyQty);

        data.note(new ExchangeLogEntry(System.currentTimeMillis(),
                ExchangeTransactionType.BUY_SELL_ORDER, order.sellerUUID(), buyer.getUUID(),
                order.itemName(), buyQty, totalPrice));
        return Outcome.ok("Куплено: +" + buyQty + " " + order.itemName());
    }

    // ================================================================ создание заявки на покупку

    /** Заявка на покупку: сначала мгновенный матчинг с лотами, заявка — на остаток. */
    public Outcome createBuyOrder(ServerPlayer buyer, ItemStack sample, long pricePerUnit, int totalAmount) {
        if (buyer == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту команду может выполнить только игрок.");
        }
        if (totalAmount <= 0 || pricePerUnit <= 0) {
            return new Outcome(Result.INVALID_QUANTITY, "Некорректные параметры заявки.");
        }
        if (sample == null || sample.isEmpty() || !isTradeable(sample)) {
            return new Outcome(Result.BLACKLISTED, "Предмет запрещён к торговле (конфиг).");
        }
        long needBalance = Math.multiplyExact(pricePerUnit, (long) totalAmount);
        if (!VEconomyIntegration.has(buyer.getUUID(), needBalance)) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, "Недостаточно средств на полную стоимость.");
        }
        ExchangeDataManager data = data(buyer);
        if (data.buyCountFor(buyer.getUUID()) >= ExchangeConfig.maxBuyOrdersPerPlayer()) {
            return new Outcome(Result.OVER_LIMIT,
                    "Достигнут лимит заявок (" + ExchangeConfig.maxBuyOrdersPerPlayer() + ").");
        }

        ItemStack sampleCopy = sample.copy();
        sampleCopy.setCount(1);

        // 1. Мгновенный матчинг: покупаем у лотов по цене <= заявки (дешёвые раньше).
        int bought = matchWithLots(buyer, sampleCopy, pricePerUnit, totalAmount);

        // 2. Заявка — только на остаток.
        int remainingAmount = totalAmount - bought;
        if (remainingAmount <= 0) {
            return Outcome.ok("Заявка исполнена мгновенно из лотов (" + bought + " шт).");
        }
        long freezeAmount = Math.multiplyExact(pricePerUnit, (long) remainingAmount);
        if (!VEconomyIntegration.has(buyer.getUUID(), freezeAmount)) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, "Недостаточно средств после покупки из лотов.");
        }

        BuyOrder order = BuyOrder.create(buyer.getUUID(), sample, pricePerUnit, remainingAmount);
        if (!VEconomyIntegration.freezeFunds(buyer.getUUID(), freezeAmount,
                buyRef(order.id(), 0),
                reason("заморозка заявки #" + order.id()), order.id() + ":freeze:0")) {
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось заморозить средства.");
        }
        data.putBuyOrder(order);
        data.recalcFrozen(buyer.getUUID());
        data.note(new ExchangeLogEntry(System.currentTimeMillis(), ExchangeTransactionType.CREATE_BUY,
                null, buyer.getUUID(), order.itemName(), order.remaining(), freezeAmount));
        return Outcome.ok("Заявка размещена: " + order.itemName() + " x" + order.remaining()
                + " по " + order.pricePerUnit());
    }

    private static int matchWithLots(ServerPlayer buyer, ItemStack sample, long pricePerUnit, int totalAmount) {
        ExchangeDataManager data = data(buyer);
        List<SellOrder> candidates = data.sellOrdersMatching(sample, pricePerUnit);
        candidates.sort(Comparator.comparingLong(o -> o.pricePerUnit()));
        int bought = 0;
        for (SellOrder sell : candidates) {
            int need = totalAmount - bought;
            if (need <= 0) {
                break;
            }
            int chunk = Math.min(need, sell.remainingQuantity());
            Outcome res = get().buyFromSellOrder(buyer, sell.id(), chunk);
            if (res.isSuccess()) {
                bought += chunk;
            }
        }
        return bought;
    }

    // ================================================================ исполнение заявки продавцом

    /** Продавец исполняет заявку {@code buyOrderId} на {@code amountToSell} единиц. */
    public Outcome fulfillBuyOrder(ServerPlayer executor, UUID buyOrderId, int amountToSell) {
        if (executor == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту команду может выполнить только игрок.");
        }
        if (amountToSell <= 0) {
            return new Outcome(Result.INVALID_QUANTITY, "Количество должно быть положительным.");
        }
        ExchangeDataManager data = data(executor);
        BuyOrder order = data.buyOrder(buyOrderId);
        if (order == null || !order.active()) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Заявка не найдена или завершена.");
        }
        if (order.buyerUUID().equals(executor.getUUID())) {
            return new Outcome(Result.SELF_TRADE, "Нельзя исполнять собственную заявку.");
        }
        int toSell = Math.min(amountToSell, order.remaining());
        if (countInInventory(executor, order.sample()) < toSell) {
            return new Outcome(Result.INVALID_QUANTITY, "Недостаточно предметов в инвентаре.");
        }
        long earnings = order.pricePerUnit() * (long) toSell;
        long frozen = data.frozenOf(order.buyerUUID());
        if (frozen < earnings) {
            return new Outcome(Result.ECONOMY_FAILED, "Заморозка покупателя недостаточна.");
        }
        return fulfillBuyOrderInternal(executor, order, toSell, earnings);
    }

    /**
     * Общая реализация передачи по заявке (используется из createSellOrder при
     * матчинге и из fulfillBuyOrder). Предметы снимаются у исполнителя (продавца),
     * деньги покупателя — из нативного эскроу VEconomy.
     * <p>
     * Нативный эскроу работает по reference целиком (частичных снятий нет), поэтому
     * применяется схема: release всей заморозки → списание у покупателя доли →
     * выплата продавцу → повторная заморозка остатка под новый reference (epoch+1).
     * Каждый шаг проверяется; при сбое — предметы возвращаются исполнителю, заявка
     * живёт с корректной картой frozenFunds.
     */
    private static Outcome fulfillBuyOrderInternal(ServerPlayer executor, BuyOrder order,
                                                   int toSell, long earnings) {
        ExchangeDataManager data = data(executor);
        UUID buyerId = order.buyerUUID();
        int newFulfilled = order.fulfilledAmount() + toSell;
        boolean done = newFulfilled >= order.totalRequested();
        long remainingAfter = order.pricePerUnit() * (long) (order.totalRequested() - newFulfilled);

        // 1. Предметы от исполнителя.
        removeFromInventory(executor, order.sample(), toSell);

        // 2. Освобождаем всю заморозку заявки (деньги вернутся покупателю на баланс).
        String oldRef = buyRef(order.id(), order.refEpoch());
        if (!VEconomyIntegration.unfreezeRefund(oldRef,
                reason("сброс заявки #" + order.id()), order.id() + ":release")) {
            addItemsToInventoryOrMailbox(executor, order.sample(), toSell);
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось снять заморозку заявки.");
        }

        // 3. Покупатель оплачивает исполненную часть.
        if (!VEconomyIntegration.withdraw(buyerId, earnings,
                reason("исполнение заявки #" + order.id()), order.id() + ":pay:" + order.refEpoch())) {
            addItemsToInventoryOrMailbox(executor, order.sample(), toSell);
            data.recalcFrozen(buyerId);
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось списать долю покупателя.");
        }

        // 4. Выплата продавцу за вычетом комиссии.
        long sellerNet = commissionAndNet(earnings);
        long commission = earnings - sellerNet;
        if (!VEconomyIntegration.deposit(executor.getUUID(), sellerNet,
                reason("выплата заявки #" + order.id()), order.id() + ":sell:" + order.refEpoch())) {
            VEconomyIntegration.deposit(buyerId, earnings, reason("откат заявки #" + order.id()),
                    order.id() + ":refund:" + order.refEpoch());
            addItemsToInventoryOrMailbox(executor, order.sample(), toSell);
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось выплатить продавцу.");
        }
        data.addCommission(commission);

        // 5. Остаток заявки: заморозка под новым reference, если ещё не выполнена.
        if (done) {
            data.removeBuyOrder(order.id());
        } else {
            int nextEpoch = order.refEpoch() + 1;
            String newRef = buyRef(order.id(), nextEpoch);
            if (!VEconomyIntegration.freezeFunds(buyerId, remainingAfter, newRef,
                    reason("заморозка остатка #" + order.id()), order.id() + ":freeze:" + nextEpoch)) {
                data.removeBuyOrder(order.id());
                data.recalcFrozen(buyerId);
                return Outcome.ok("Заявка частично исполнена; остаток закрыт (заморозка невозможна).");
            }
            data.updateBuyOrder(order.id(), newFulfilled, true, nextEpoch);
        }
        data.recalcFrozen(buyerId);

        data.note(new ExchangeLogEntry(System.currentTimeMillis(),
                ExchangeTransactionType.FULFILL_BUY_ORDER, executor.getUUID(), buyerId,
                order.itemName(), toSell, sellerNet));
        return Outcome.ok("Заявка исполнена: " + order.itemName() + " x" + toSell);
    }

    // ================================================================ отмены

    /** Отмена лота: возврат предметов продавцу. */
    public Outcome cancelSellOrder(ServerPlayer player, UUID sellOrderId) {
        if (player == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту команду может выполнить только игрок.");
        }
        ExchangeDataManager data = data(player);
        SellOrder order = data.sellOrder(sellOrderId);
        if (order == null) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Лот не найден.");
        }
        if (!order.sellerUUID().equals(player.getUUID())) {
            return new Outcome(Result.NOT_YOUR_ORDER, "Это чужой лот.");
        }
        addItemsToInventoryOrMailbox(player, order.sample(), order.remainingQuantity());
        data.removeSellOrder(sellOrderId);
        data.note(new ExchangeLogEntry(System.currentTimeMillis(), ExchangeTransactionType.CANCEL,
                player.getUUID(), null, order.itemName(), order.remainingQuantity(), 0));
        return Outcome.ok("Лот отменён, предметы возвращены.");
    }

    /** Отмена заявки: возврат замороженного остатка покупателю. */
    public Outcome cancelBuyOrder(ServerPlayer player, UUID buyOrderId) {
        if (player == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту команду может выполнить только игрок.");
        }
        ExchangeDataManager data = data(player);
        BuyOrder order = data.buyOrder(buyOrderId);
        if (order == null) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Заявка не найдена.");
        }
        if (!order.buyerUUID().equals(player.getUUID())) {
            return new Outcome(Result.NOT_YOUR_ORDER, "Нельзя отменить чужую заявку.");
        }
        if (order.refEpoch() != -1 && !VEconomyIntegration.unfreezeRefund(buyRef(order.id(), order.refEpoch()),
                    reason("отмена заявки #" + order.id()), order.id() + ":cancel:" + order.refEpoch())) {
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось вернуть замороженные средства.");
        }
        data.removeBuyOrder(buyOrderId);
        data.recalcFrozen(player.getUUID());
        data.note(new ExchangeLogEntry(System.currentTimeMillis(), ExchangeTransactionType.CANCEL,
                null, player.getUUID(), order.itemName(), order.remaining(), 0));
        return Outcome.ok("Заявка отменена, заморозка возвращена.");
    }

    // ================================================================ почта

    /** Забрать почту в инвентарь; возвращает число забранных стаков. */
    public int claimMailbox(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        ExchangeDataManager data = data(player);
        List<ItemStack> mailbox = data.mailbox(player.getUUID());
        if (mailbox.isEmpty()) {
            return 0;
        }
        List<ItemStack> leftover = new ArrayList<>();
        int claimed = 0;
        for (ItemStack stack : mailbox) {
            ItemStack rest = placeStack(player, stack);
            if (rest.isEmpty()) {
                claimed++;
            } else {
                leftover.add(rest);
            }
        }
        data.setMailbox(player.getUUID(), leftover);
        return claimed;
    }

    /** Попытаться положить стак в инвентарь: возвращает не влезший остаток (может быть EMPTY). */
    private static ItemStack placeStack(ServerPlayer player, ItemStack stack) {
        int maxStack = Math.max(1, stack.getMaxStackSize());
        ItemStack remainingCopy = stack.copy();
        for (int i = 0; i < player.getInventory().getContainerSize() && !remainingCopy.isEmpty(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty()) {
                ItemStack add = remainingCopy.copy();
                add.setCount(Math.min(remainingCopy.getCount(), maxStack));
                player.getInventory().setItem(i, add);
                remainingCopy.shrink(add.getCount());
            } else if (ItemStack.isSameItemSameTags(slot, remainingCopy) && slot.getCount() < maxStack) {
                int put = Math.min(maxStack - slot.getCount(), remainingCopy.getCount());
                slot.grow(put);
                remainingCopy.shrink(put);
            }
        }
        player.getInventory().setChanged();
        return remainingCopy;
    }

    /** Есть ли у игрока неподученная почта. */
    public boolean hasMail(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return data(player).hasMailboxItems(player.getUUID());
    }

    // ================================================================ доставка

    /** Выдать предметы игроку: сначала инвентарь (онлайн), излишек — на почту. */
    public static boolean giveItemsToPlayer(MinecraftServer server, UUID playerId,
                                            ItemStack sample, int quantity) {
        if (quantity <= 0 || server == null) {
            return false;
        }
        ExchangeDataManager data = data(server);
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        int maxStack = Math.max(1, sample.getMaxStackSize());
        int remaining = quantity;
        if (online != null) {
            for (int i = 0; i < online.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack slot = online.getInventory().getItem(i);
                if (slot.isEmpty()) {
                    ItemStack add = sample.copy();
                    add.setCount(Math.min(remaining, maxStack));
                    online.getInventory().setItem(i, add);
                    remaining -= add.getCount();
                } else if (ItemStack.isSameItemSameTags(slot, sample) && slot.getCount() < maxStack) {
                    int put = Math.min(maxStack - slot.getCount(), remaining);
                    slot.grow(put);
                    remaining -= put;
                }
            }
            online.getInventory().setChanged();
        }
        while (remaining > 0) {
            ItemStack copy = sample.copy();
            int chunk = Math.min(remaining, maxStack);
            copy.setCount(chunk);
            data.addMailboxItems(playerId, copy);
            remaining -= chunk;
        }
        return true;
    }

    /** Обёртка для онлайн-игрока (тот же путь инвентарь → почта). */
    public static boolean giveItemsToPlayer(ServerPlayer player, ItemStack sample, int quantity) {
        return giveItemsToPlayer(player.getServer(), player.getUUID(), sample, quantity);
    }

    /** Возврат предметов исполнителю (тот же путь, что и доставка). */
    public static boolean addItemsToInventoryOrMailbox(ServerPlayer player, ItemStack sample, int quantity) {
        return giveItemsToPlayer(player, sample, quantity);
    }

    public static boolean addItemsToInventoryOrMailboxByUuid(MinecraftServer server, UUID playerId,
                                                             ItemStack sample, int quantity) {
        return giveItemsToPlayer(server, playerId, sample, quantity);
    }

    // ================================================================ комиссия

    /** Сумма продавца после вычета комиссии (net). */
    private static long commissionAndNet(long totalPrice) {
        double percent = ExchangeConfig.commissionPercent();
        long commission = (long) Math.floor(totalPrice * percent / 100.0d);
        return Math.max(0, totalPrice - commission);
    }

    private static String buyRef(UUID buyOrderId, int epoch) {
        return "exchange:buy:" + buyOrderId + ":" + epoch;
    }

    /** Разрешён ли предмет: блэклист + политика NBT. */
    public static boolean isTradeable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && ExchangeConfig.isBlacklisted(id.toString())) {
            return false;
        }
        if (!ExchangeConfig.blockCustomNbt()) {
            return true;
        }
        return safeNbt(stack);
    }

    /** NBT-политика: шалкеры/контейнеры и нестандартный NBT запрещены. */
    private static boolean safeNbt(ItemStack stack) {
        if (!stack.hasTag()) {
            return true;
        }
        CompoundTag tag = stack.getTag();
        if (tag.contains("BlockEntityTag")) {
            return false;
        }
        CompoundTag copy = tag.copy();
        copy.remove("Damage");
        if (stack.is(Items.ENCHANTED_BOOK)) {
            return ExchangeConfig.allowEnchantedBooks();
        }
        return copy.size() == 0;
    }

    private static void removeFromInventory(ServerPlayer player, ItemStack sample, int count) {
        int need = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && need > 0; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(slot, sample)) {
                int take = Math.min(need, slot.getCount());
                slot.shrink(take);
                need -= take;
                if (slot.getCount() <= 0) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
        player.getInventory().setChanged();
    }

    private static int countInInventory(ServerPlayer player, ItemStack sample) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(slot, sample)) {
                count += slot.getCount();
            }
        }
        return count;
    }
}