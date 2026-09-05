package com.valorcraft.vauction.service;

import com.valorcraft.vauction.VAuctionMod;
import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.model.AuctionListing;
import com.valorcraft.vauction.persistence.AuctionStore;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.EscrowLookupResult;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowState;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.util.CurrencyParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import com.valorcraft.vauction.ui.AuctionCategory;
import com.valorcraft.vauction.ui.AuctionSort;
import com.valorcraft.vauction.lang.AuctionLang;
import com.valorcraft.vauction.ui.AuctionMenu;
import com.valorcraft.vauction.ui.UserAuctionsMenu;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AuctionService {
    private static final String DELIVERY_RECEIPTS = "VAuctionDeliveryReceipts";
    private static final String SALE_INTENTS = "VAuctionSaleIntents";
    private final AuctionStore store;
    private volatile boolean available;
    private final Map<BrowseKey, List<AuctionListing>> browseCache = lruCache();
    private final Map<UserListKey, List<AuctionListing>> userCache = lruCache();
    private final Map<UUID, Boolean> contentCache = new LinkedHashMap<>(64, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > Math.max(64, AuctionConfig.QUERY_CACHE_ENTRIES.get());
        }
    };
    private long nextExpiryScanAt;
    private long nextPruneAt;
    private long cacheHits;
    private long cacheMisses;
    private long queryNanos;
    private long expiryScans;

    public AuctionService(AuctionStore store) {
        this.store = store;
    }

    public void load() {
        available = false;
        try {
            store.load();
            int pruned = store.pruneClaimed(AuctionConfig.HISTORY_RETENTION_DAYS.get());
            invalidateCaches();
            contentCache.clear();
            nextPruneAt = System.currentTimeMillis() + 3_600_000L;
            available = true;
            if (pruned > 0) VAuctionMod.LOGGER.info("Удалено старых завершённых лотов: {}", pruned);
        } catch (RuntimeException e) {
            store.close();
            throw e;
        }
    }

    public void close() {
        available = false;
        store.close();
    }

    public boolean isAvailable() { return available; }

    public synchronized void recoverPending() {
        if (!EconomyCore.isStarted()) {
            VAuctionMod.LOGGER.error("VEconomy не запущена: восстановление незавершённых покупок отложено");
            return;
        }
        List<AuctionListing> changed = new ArrayList<>();
        // Recovery is rare and must also see safe in-memory PENDING states whose SQLite write
        // returned an ambiguous error before the persisted-state index could be updated.
        for (AuctionListing listing : store.all()) {
            if (listing.state() != AuctionListing.State.PENDING_PAYMENT) continue;
            if (listing.escrowReference() == null) {
                listing.state(AuctionListing.State.ACTIVE);
                listing.buyerId(null);
                listing.buyerName(null);
                changed.add(listing);
                continue;
            }
            EscrowLookupResult lookup = EconomyCore.escrow().findEscrow(listing.escrowReference());
            if (lookup.status() == EscrowLookupResult.Status.DATABASE_ERROR) continue;
            if (lookup.status() == EscrowLookupResult.Status.NOT_FOUND
                    || lookup.snapshot().state() == EscrowState.RELEASED) {
                listing.state(AuctionListing.State.ACTIVE);
                listing.buyerId(null);
                listing.buyerName(null);
                listing.escrowReference(null);
                changed.add(listing);
                continue;
            }
            if (lookup.snapshot().state() == EscrowState.CAPTURED) {
                if (!captureMatches(lookup, listing)) {
                    VAuctionMod.LOGGER.error("Лот {} оставлен PENDING: CAPTURED escrow не совпадает с продавцом или суммой",
                            listing.id());
                    continue;
                }
                listing.state(AuctionListing.State.SOLD);
                if (listing.soldAt() == 0L) listing.soldAt(System.currentTimeMillis());
                try {
                    persistSoldAndNotification(listing);
                    notifyListingUnavailable(listing.id());
                } catch (RuntimeException e) {
                    // Keep it hidden in memory. SQLite still has PENDING_PAYMENT and the next
                    // startup/recovery can safely repeat the idempotent reconciliation.
                    listing.state(AuctionListing.State.PENDING_PAYMENT);
                    invalidateCaches();
                    VAuctionMod.LOGGER.error("Не удалось сохранить восстановленный проданный лот {}", listing.id(), e);
                }
                continue;
            }
            TransactionContext context = new TransactionContext(TransactionType.ESCROW_RELEASE,
                    listing.buyerId(), "VAuction: восстановление лота " + shortId(listing.id()),
                    listing.escrowReference() + ":recovery-release",
                    Map.of("listing_id", listing.id().toString()));
            EscrowResult release = EconomyCore.escrow().releaseMoney(listing.escrowReference(), context);
            if (release.status() == EscrowResult.Status.SUCCESS || release.status() == EscrowResult.Status.ALREADY_RELEASED) {
                listing.state(AuctionListing.State.ACTIVE);
                listing.buyerId(null);
                listing.buyerName(null);
                listing.escrowReference(null);
                changed.add(listing);
            }
        }
        try {
            store.updateAll(changed);
        } catch (RuntimeException e) {
            VAuctionMod.LOGGER.error("Не удалось сохранить восстановление {} escrow-лотов", changed.size(), e);
        }
        if (!changed.isEmpty()) invalidateCaches();
    }

    public synchronized List<AuctionListing> activeListings() {
        return browse(new UUID(0L, 0L), AuctionCategory.ALL, AuctionSort.NEWEST, "", "");
    }

    public synchronized List<AuctionListing> browse(UUID viewer, AuctionCategory category,
                                                     AuctionSort sort, String search, String seller) {
        expireListings();
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String sellerQuery = seller == null ? "" : seller.trim().toLowerCase(Locale.ROOT);
        AuctionSort selectedSort = sort == null ? AuctionSort.NEWEST : sort;
        AuctionCategory selectedCategory = category == null ? AuctionCategory.ALL : category;
        UUID scopedViewer = selectedSort == AuctionSort.ONLY_MINE ? viewer : null;
        BrowseKey key = new BrowseKey(scopedViewer, selectedCategory, selectedSort, query, sellerQuery);
        List<AuctionListing> cached = browseCache.get(key);
        if (cached != null) { cacheHits++; return cached; }
        long started = System.nanoTime();
        Comparator<AuctionListing> comparator = switch (selectedSort) {
            case OLDEST -> Comparator.comparingLong(AuctionListing::createdAt);
            case CHEAPEST -> Comparator.comparingLong(AuctionListing::price).thenComparingLong(AuctionListing::createdAt);
            case EXPENSIVE -> Comparator.comparingLong(AuctionListing::price).reversed();
            default -> Comparator.comparingLong(AuctionListing::createdAt).reversed();
        };
        List<AuctionListing> result = store.byState(AuctionListing.State.ACTIVE).stream()
                .filter(it -> it.state() == AuctionListing.State.ACTIVE)
                .filter(selectedCategory::accepts)
                .filter(it -> selectedSort != AuctionSort.ONLY_MINE || it.sellerId().equals(viewer))
                .filter(it -> sellerQuery.isEmpty() || it.sellerName().toLowerCase(java.util.Locale.ROOT).equals(sellerQuery))
                .filter(it -> query.isEmpty() || searchableName(it).contains(query))
                .sorted(comparator).toList();
        browseCache.put(key, result); cacheMisses++; queryNanos += System.nanoTime() - started;
        return result;
    }

    public synchronized List<AuctionListing> myListings(UUID player) {
        return browse(player, AuctionCategory.ALL, AuctionSort.ONLY_MINE, "", "");
    }

    public synchronized List<AuctionListing> archive(UUID player) {
        expireListings();
        UserListKey key = new UserListKey(player, UserListType.ARCHIVE);
        List<AuctionListing> cached = userCache.get(key);
        if (cached != null) { cacheHits++; return cached; }
        long started = System.nanoTime();
        java.util.LinkedHashSet<AuctionListing> relevant = new java.util.LinkedHashSet<>(store.bySeller(player));
        relevant.addAll(store.byBuyer(player));
        List<AuctionListing> result = relevant.stream().filter(it ->
                        (it.sellerId().equals(player) && (it.state() == AuctionListing.State.CANCELLED || it.state() == AuctionListing.State.EXPIRED))
                                || (player.equals(it.buyerId()) && it.state() == AuctionListing.State.SOLD))
                .sorted(Comparator.comparingLong(AuctionListing::createdAt).reversed()).toList();
        userCache.put(key, result); cacheMisses++; queryNanos += System.nanoTime() - started;
        return result;
    }

    public synchronized List<AuctionListing> salesHistory(UUID seller) {
        UserListKey key = new UserListKey(seller, UserListType.HISTORY);
        List<AuctionListing> cached = userCache.get(key);
        if (cached != null) { cacheHits++; return cached; }
        long started = System.nanoTime();
        long cutoff = System.currentTimeMillis() - AuctionConfig.HISTORY_RETENTION_DAYS.get() * 86_400_000L;
        List<AuctionListing> result = store.bySeller(seller).stream().filter(it -> it.buyerId() != null
                        && it.soldAt() >= cutoff && (it.state() == AuctionListing.State.SOLD || it.state() == AuctionListing.State.CLAIMED))
                .sorted(Comparator.comparingLong((AuctionListing it) -> it.soldAt()).reversed()).toList();
        userCache.put(key, result); cacheMisses++; queryNanos += System.nanoTime() - started;
        return result;
    }

    public synchronized AuctionListing findActive(UUID id, long createdAt) {
        AuctionListing listing = store.get(id);
        return listing != null && listing.state() == AuctionListing.State.ACTIVE && listing.createdAt() == createdAt
                ? listing : null;
    }

    public synchronized int sell(ServerPlayer seller, String priceText, int requestedAmount) {
        if (!economyReady(seller)) return 0;
        if (hasSaleIntents(seller)) {
            recoverSaleIntents(seller);
            if (hasSaleIntents(seller)) {
                error(seller, AuctionLang.text("error.sale_pending"));
                return 0;
            }
        }
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty()) {
            error(seller, AuctionLang.text("error.empty_hand"));
            return 0;
        }
        String itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(held.getItem()));
        if (AuctionConfig.FORBIDDEN_ITEMS.get().stream().anyMatch(itemId::equalsIgnoreCase)) {
            error(seller, AuctionLang.text("error.forbidden_item"));
            return 0;
        }
        int amount = Math.min(requestedAmount, held.getCount());
        if (amount <= 0) {
            error(seller, AuctionLang.text("error.invalid_amount"));
            return 0;
        }
        long price;
        try {
            price = CurrencyParser.parse(priceText, EconomyCore.settings().decimalPlaces);
        } catch (CurrencyParser.InvalidAmount e) {
            error(seller, AuctionLang.text("error.invalid_price", "reason", e.getMessage()));
            return 0;
        }
        if (price < AuctionConfig.MIN_PRICE.get() || price > AuctionConfig.MAX_PRICE.get()) {
            error(seller, AuctionLang.text("error.price_range"));
            return 0;
        }
        long ownListings = store.bySeller(seller.getUUID()).stream()
                .filter(it -> it.state() == AuctionListing.State.ACTIVE)
                .count();
        if (ownListings >= AuctionConfig.MAX_LISTINGS_PER_PLAYER.get()) {
            error(seller, AuctionLang.text("error.listing_limit"));
            return 0;
        }

        ItemStack lotItem = held.copy();
        lotItem.setCount(amount);
        held.shrink(amount);
        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), seller.getUUID(), seller.getGameProfile().getName(),
                lotItem, price, now, now + AuctionConfig.LISTING_DURATION_HOURS.get() * 3_600_000L,
                AuctionListing.State.ACTIVE, null, null);
        try {
            setSaleIntent(seller, listing);
        } catch (RuntimeException e) {
            clearSaleIntent(seller, listing.id());
            giveOrDrop(seller, lotItem);
            VAuctionMod.LOGGER.error("Не удалось сформировать квитанцию выставления лота {}", listing.id(), e);
            error(seller, AuctionLang.text("error.save"));
            return 0;
        }
        try {
            // Inventory removal and the recovery intent must reach playerdata before SQLite.
            // If the server dies afterwards, login recovery recreates the exact same listing.
            savePlayerNow(seller);
        } catch (RuntimeException e) {
            VAuctionMod.LOGGER.error("Не удалось сохранить намерение выставить лот {}", listing.id(), e);
            error(seller, AuctionLang.text("error.sale_pending"));
            return 0;
        }
        try {
            store.put(listing);
            invalidateCaches();
        } catch (RuntimeException e) {
            // Do not refund here: COMMIT may have succeeded despite the JDBC error. The saved
            // player intent is the single source used to retry idempotently on login/recovery.
            VAuctionMod.LOGGER.error("Не удалось подтвердить создание лота {}; намерение сохранено", listing.id(), e);
            error(seller, AuctionLang.text("error.sale_pending"));
            return 0;
        }
        clearSaleIntent(seller, listing.id());
        // Normal autosave/logout persists the cleanup. After a crash, a stale intent is harmless:
        // recovery sees the already existing UUID and only clears it.
        seller.sendSystemMessage(AuctionLang.component("chat.sale_success", "id", shortId(listing.id()), "price", format(price)));
        playConfiguredSound(seller, AuctionConfig.ACTION_SOUND.get(), SoundEvents.UI_BUTTON_CLICK.value());
        return 1;
    }

    public synchronized boolean buy(ServerPlayer buyer, UUID listingId) {
        if (!economyReady(buyer)) return false;
        expireListings();
        AuctionListing listing = store.get(listingId);
        if (listing == null) {
            error(buyer, AuctionLang.text("error.not_found"));
            return false;
        }
        if (listing.sellerId().equals(buyer.getUUID())) {
            error(buyer, AuctionLang.text("error.own_lot"));
            return false;
        }
        if (listing.state() == AuctionListing.State.PENDING_PAYMENT
                && !buyer.getUUID().equals(listing.buyerId())) {
            error(buyer, AuctionLang.text("error.being_bought"));
            return false;
        }
        if (listing.state() != AuctionListing.State.ACTIVE
                && listing.state() != AuctionListing.State.PENDING_PAYMENT) {
            error(buyer, AuctionLang.text("error.unavailable"));
            return false;
        }

        String escrowRef = listing.escrowReference();
        if (listing.state() == AuctionListing.State.ACTIVE) {
            escrowRef = "vauction:" + listing.id() + ":" + UUID.randomUUID();
            listing.state(AuctionListing.State.PENDING_PAYMENT);
            listing.buyerId(buyer.getUUID());
            listing.buyerName(buyer.getGameProfile().getName());
            listing.escrowReference(escrowRef);
            try {
                store.update(listing);
                invalidateCaches();
            } catch (RuntimeException e) {
                // Ambiguous commit: keep the listing hidden until restart/reconciliation.
                invalidateCaches();
                VAuctionMod.LOGGER.error("Не удалось сохранить начало покупки лота {}", listing.id(), e);
                error(buyer, AuctionLang.text("error.storage_pending"));
                return false;
            }
        }

        Map<String, String> metadata = Map.of("listing_id", listing.id().toString(),
                "seller_id", listing.sellerId().toString(), "buyer_id", buyer.getUUID().toString());
        TransactionContext reserveContext = new TransactionContext(TransactionType.ESCROW_RESERVE,
                buyer.getUUID(), "VAuction: покупка " + shortId(listing.id()), escrowRef + ":reserve", metadata);
        EscrowResult reserve = EconomyCore.escrow().reserveMoney(buyer.getUUID(), listing.price(), escrowRef, reserveContext);
        boolean reserved = reserve.status() == EscrowResult.Status.SUCCESS
                || reserve.status() == EscrowResult.Status.ALREADY_RESERVED
                || reserve.status() == EscrowResult.Status.ALREADY_SETTLED;
        if (!reserved) {
            EscrowLookupResult reserveLookup = EconomyCore.escrow().findEscrow(escrowRef);
            boolean matchingHold = reserveLookup.status() == EscrowLookupResult.Status.FOUND
                    && reserveLookup.snapshot().ownerId().equals(buyer.getUUID())
                    && reserveLookup.snapshot().amount() == listing.price()
                    && (reserveLookup.snapshot().state() == EscrowState.RESERVED
                    || reserveLookup.snapshot().state() == EscrowState.CAPTURED);
            if (matchingHold) {
                reserved = true;
            } else if (reserve.status() == EscrowResult.Status.ALREADY_RELEASED
                    || reserveLookup.status() == EscrowLookupResult.Status.NOT_FOUND
                    || (reserveLookup.status() == EscrowLookupResult.Status.FOUND
                    && reserveLookup.snapshot().state() == EscrowState.RELEASED
                    && reserveLookup.snapshot().ownerId().equals(buyer.getUUID())
                    && reserveLookup.snapshot().amount() == listing.price())) {
                if (!restoreActive(listing)) error(buyer, AuctionLang.text("error.storage_pending"));
                else error(buyer, reserve.status() == EscrowResult.Status.ALREADY_RELEASED
                        ? AuctionLang.text("error.retry") : escrowError(reserve.status()));
                return false;
            } else {
                // DATABASE_ERROR or conflicting snapshot: do not risk a second sale while a hold may exist.
                invalidateCaches();
                VAuctionMod.LOGGER.error("Лот {} оставлен PENDING после reserve={}; lookup={}",
                        listing.id(), reserve.status(), reserveLookup.status());
                error(buyer, AuctionLang.text("error.purchase_pending"));
                return false;
            }
        }

        TransactionContext settleContext = new TransactionContext(TransactionType.ESCROW_CAPTURE,
                buyer.getUUID(), "VAuction: расчёт по лоту " + shortId(listing.id()), escrowRef + ":settle", metadata);
        EscrowResult settle = EconomyCore.escrow().captureMoney(escrowRef, listing.sellerId(), settleContext);
        boolean captured = settle.status() == EscrowResult.Status.SUCCESS || settle.status() == EscrowResult.Status.ALREADY_SETTLED;
        if (!captured) {
            EscrowLookupResult lookup = EconomyCore.escrow().findEscrow(escrowRef);
            captured = lookup.status() == EscrowLookupResult.Status.FOUND
                    && captureMatches(lookup, listing);
            if (!captured) {
                EscrowResult release = EconomyCore.escrow().releaseMoney(escrowRef,
                        new TransactionContext(TransactionType.ESCROW_RELEASE, buyer.getUUID(),
                                "VAuction: отмена покупки " + shortId(listing.id()), escrowRef + ":release", metadata));
                if (release.status() == EscrowResult.Status.SUCCESS
                        || release.status() == EscrowResult.Status.ALREADY_RELEASED
                        || lookup.status() == EscrowLookupResult.Status.NOT_FOUND) {
                    if (!restoreActive(listing)) error(buyer, AuctionLang.text("error.storage_pending"));
                    else error(buyer, AuctionLang.text("error.purchase", "status", settle.status()));
                } else {
                    // Never resell while the monetary state is unknown.
                    invalidateCaches();
                    VAuctionMod.LOGGER.error("Лот {} оставлен PENDING: capture={}, release={}, lookup={}",
                            listing.id(), settle.status(), release.status(), lookup.status());
                    error(buyer, AuctionLang.text("error.purchase_pending"));
                }
                return false;
            }
        }

        listing.state(AuctionListing.State.SOLD);
        listing.soldAt(System.currentTimeMillis());
        try {
            persistSoldAndNotification(listing);
        } catch (RuntimeException e) {
            listing.state(AuctionListing.State.PENDING_PAYMENT);
            invalidateCaches();
            VAuctionMod.LOGGER.error("Деньги по лоту {} зачислены, но SOLD ещё не сохранён; требуется recovery", listing.id(), e);
            error(buyer, AuctionLang.text("error.purchase_pending"));
            return false;
        }
        notifyListingUnavailable(listing.id());
        ServerPlayer onlineSeller = buyer.server.getPlayerList().getPlayer(listing.sellerId());
        if (onlineSeller != null) sendNotifications(onlineSeller);
        deliverClaim(buyer, listing);
        buyer.sendSystemMessage(AuctionLang.component("chat.buy_success", "item", listing.item().getHoverName().getString(),
                "price", format(listing.price())));
        playConfiguredSound(buyer, AuctionConfig.PURCHASE_SOUND.get(), SoundEvents.EXPERIENCE_ORB_PICKUP);
        return true;
    }

    public synchronized boolean cancel(ServerPlayer seller, String idPrefix) {
        AuctionListing listing = resolve(idPrefix);
        if (listing == null || !listing.sellerId().equals(seller.getUUID())) {
            error(seller, AuctionLang.text("error.cancel_not_found"));
            return false;
        }
        if (listing.state() != AuctionListing.State.ACTIVE) {
            error(seller, AuctionLang.text("error.cannot_cancel"));
            return false;
        }
        listing.state(AuctionListing.State.CANCELLED);
        try {
            store.update(listing);
            invalidateCaches();
        } catch (RuntimeException e) {
            // CANCELLED is the safe in-memory state: do not expose a possibly cancelled lot for resale.
            invalidateCaches();
            VAuctionMod.LOGGER.error("Не удалось сохранить отмену лота {}", listing.id(), e);
            error(seller, AuctionLang.text("error.storage_pending"));
            return false;
        }
        notifyListingUnavailable(listing.id());
        seller.sendSystemMessage(AuctionLang.component("chat.cancel_success"));
        playConfiguredSound(seller, AuctionConfig.ACTION_SOUND.get(), SoundEvents.UI_BUTTON_CLICK.value());
        return true;
    }

    public synchronized int claim(ServerPlayer player) {
        int count = claimAvailable(player);
        if (count == 0) error(player, AuctionLang.text("chat.claim_none"));
        else {
            player.sendSystemMessage(AuctionLang.component("chat.claim_success", "count", count));
            playConfiguredSound(player, AuctionConfig.ACTION_SOUND.get(), SoundEvents.ITEM_PICKUP);
        }
        return count;
    }

    public synchronized void claimOnLogin(ServerPlayer player) {
        recoverSaleIntents(player);
        cleanupDeliveryReceipts(player);
        sendNotifications(player);
        int count = archive(player.getUUID()).size();
        if (count > 0) {
            player.sendSystemMessage(AuctionLang.component("chat.archive_waiting", "count", count));
        }
    }

    public synchronized boolean claimOne(ServerPlayer player, UUID id) {
        AuctionListing listing = store.get(id);
        if (listing == null) return false;
        boolean allowed = (listing.sellerId().equals(player.getUUID())
                && (listing.state() == AuctionListing.State.CANCELLED || listing.state() == AuctionListing.State.EXPIRED))
                || (player.getUUID().equals(listing.buyerId()) && listing.state() == AuctionListing.State.SOLD);
        return allowed && deliverClaim(player, listing);
    }

    private int claimAvailable(ServerPlayer player) {
        expireListings();
        int count = 0;
        java.util.LinkedHashSet<AuctionListing> claimable = new java.util.LinkedHashSet<>(store.bySeller(player.getUUID()));
        claimable.addAll(store.byBuyer(player.getUUID()));
        for (AuctionListing listing : claimable) {
            boolean sellerClaim = listing.sellerId().equals(player.getUUID())
                    && (listing.state() == AuctionListing.State.CANCELLED || listing.state() == AuctionListing.State.EXPIRED);
            boolean buyerClaim = player.getUUID().equals(listing.buyerId()) && listing.state() == AuctionListing.State.SOLD;
            if (sellerClaim || buyerClaim) {
                if (deliverClaim(player, listing)) count++;
            }
        }
        return count;
    }

    public AuctionListing resolve(String prefix) {
        String normalized = prefix.toLowerCase();
        List<AuctionListing> matches = store.byState(AuctionListing.State.ACTIVE).stream()
                .filter(it -> it.id().toString().toLowerCase().startsWith(normalized))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public String format(long amount) {
        return EconomyCore.isStarted() ? EconomyCore.formatter().format(amount) : Long.toString(amount);
    }

    public String formatGui(long amount) {
        if (!EconomyCore.isStarted()) return Long.toString(amount);
        String symbol = EconomyCore.settings().currencySymbol == null ? "" : EconomyCore.settings().currencySymbol;
        return EconomyCore.formatter().formatAmount(amount).replace(',', '\u00A0') + (symbol.isBlank() ? "" : " " + symbol);
    }

    public boolean hasFunds(UUID player, long amount) {
        return EconomyCore.isStarted() && EconomyCore.accounts().getBalance(player) >= amount;
    }

    public synchronized boolean hasContents(AuctionListing listing) {
        return contentCache.computeIfAbsent(listing.id(), ignored ->
                com.valorcraft.vauction.ui.ContainerInspector.hasContents(listing.item()));
    }

    public synchronized String performanceReport() {
        if (!available) return "хранилище недоступно";
        long requests = cacheHits + cacheMisses;
        double hitRate = requests == 0 ? 0.0D : cacheHits * 100.0D / requests;
        double averageMicros = cacheMisses == 0 ? 0.0D : queryNanos / 1_000.0D / cacheMisses;
        AuctionStore.TransactionStats db = store.transactionStats();
        double averageDbMs = db.count() == 0 ? 0.0D : db.totalNanos() / 1_000_000.0D / db.count();
        return String.format(Locale.ROOT,
                "Лотов: %d (активных: %d) | кеш: %d/%d | попадания: %.1f%% | выборка: %.1f мкс | SQLite: %.2f/%.2f мс avg/max | expiry: %d",
                store.size(), store.byState(AuctionListing.State.ACTIVE).size(),
                browseCache.size() + userCache.size(), AuctionConfig.QUERY_CACHE_ENTRIES.get(),
                hitRate, averageMicros, averageDbMs, db.maxNanos() / 1_000_000.0D, expiryScans);
    }

    public static String shortId(UUID id) { return id.toString().substring(0, 8); }

    private void persistSoldAndNotification(AuctionListing listing) {
        String message = AuctionConfig.SALE_NOTIFICATION.get()
                .replace("{item}", listing.item().getHoverName().getString() + " x" + listing.item().getCount())
                .replace("{price}", format(listing.price()))
                .replace("{seller}", listing.sellerName())
                .replace("{id}", shortId(listing.id()));
        store.updateWithNotification(listing, listing.sellerId(), listing.id(), message);
        invalidateCaches();
    }

    private void sendNotifications(ServerPlayer player) {
        try {
            List<AuctionStore.PendingNotification> notifications = store.peekNotifications(player.getUUID());
            List<String> delivered = new ArrayList<>(notifications.size());
            for (AuctionStore.PendingNotification notification : notifications) {
                player.sendSystemMessage(AuctionLang.legacy("&6[VAuction] &f" + notification.message()));
                delivered.add(notification.id());
            }
            store.acknowledgeNotifications(player.getUUID(), delivered);
            if (!notifications.isEmpty()) {
                playConfiguredSound(player, AuctionConfig.SALE_SOUND.get(), SoundEvents.PLAYER_LEVELUP);
            }
        } catch (RuntimeException e) {
            // Notification delivery must never interrupt a completed purchase or player login.
            VAuctionMod.LOGGER.error("Не удалось получить уведомления игрока {}; они останутся в SQLite", player.getUUID(), e);
        }
    }

    public void playInterfaceSound(ServerPlayer player) {
        playConfiguredSound(player, AuctionConfig.ACTION_SOUND.get(), SoundEvents.UI_BUTTON_CLICK.value());
    }

    private static void playConfiguredSound(ServerPlayer player, String configuredId, SoundEvent fallback) {
        if (!AuctionConfig.SOUNDS_ENABLED.get()) return;
        ResourceLocation id = ResourceLocation.tryParse(configuredId);
        SoundEvent sound = id == null ? fallback : ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (sound == null) sound = fallback;
        player.playNotifySound(sound, SoundSource.MASTER,
                AuctionConfig.SOUND_VOLUME.get().floatValue(), AuctionConfig.SOUND_PITCH.get().floatValue());
    }

    private void expireListings() {
        long now = System.currentTimeMillis();
        if (now < nextExpiryScanAt) return;
        nextExpiryScanAt = now + AuctionConfig.EXPIRY_SCAN_INTERVAL_MS.get();
        expiryScans++;
        List<AuctionListing> changed = new ArrayList<>();
        for (AuctionListing listing : store.byState(AuctionListing.State.ACTIVE)) {
            if (listing.state() == AuctionListing.State.ACTIVE && listing.expiresAt() <= now) {
                listing.state(AuctionListing.State.EXPIRED);
                changed.add(listing);
            }
        }
        try {
            store.updateAll(changed);
        } catch (RuntimeException e) {
            // Keep expired lots hidden in memory; a restart will retry from persisted timestamps.
            VAuctionMod.LOGGER.error("Не удалось сохранить истечение {} лотов", changed.size(), e);
        }
        if (!changed.isEmpty()) invalidateCaches();
        for (AuctionListing listing : changed) notifyListingUnavailable(listing.id());
        if (now >= nextPruneAt) {
            nextPruneAt = now + 3_600_000L;
            try {
                int pruned = store.pruneClaimed(AuctionConfig.HISTORY_RETENTION_DAYS.get());
                if (pruned > 0) invalidateCaches();
            } catch (RuntimeException e) {
                VAuctionMod.LOGGER.error("Не удалось выполнить периодическую очистку истории VAuction", e);
            }
        }
    }

    private boolean restoreActive(AuctionListing listing) {
        AuctionListing.State previousState = listing.state();
        UUID previousBuyer = listing.buyerId();
        String previousBuyerName = listing.buyerName();
        String previousEscrow = listing.escrowReference();
        listing.state(AuctionListing.State.ACTIVE);
        listing.buyerId(null);
        listing.buyerName(null);
        listing.escrowReference(null);
        try {
            store.update(listing);
            invalidateCaches();
            return true;
        } catch (RuntimeException e) {
            listing.state(previousState);
            listing.buyerId(previousBuyer);
            listing.buyerName(previousBuyerName);
            listing.escrowReference(previousEscrow);
            invalidateCaches();
            VAuctionMod.LOGGER.error("Не удалось вернуть лот {} в ACTIVE", listing.id(), e);
            return false;
        }
    }

    private static boolean captureMatches(EscrowLookupResult lookup, AuctionListing listing) {
        if (lookup.snapshot() == null || lookup.snapshot().state() != EscrowState.CAPTURED
                || lookup.snapshot().amount() != listing.price()
                || lookup.snapshot().settlement().size() != 1) return false;
        var credit = lookup.snapshot().settlement().get(0);
        return credit.recipientId().equals(listing.sellerId()) && credit.amount() == listing.price();
    }

    private boolean deliverClaim(ServerPlayer player, AuctionListing listing) {
        String receiptId = listing.id().toString();
        if (hasDeliveryReceipt(player, receiptId)) {
            try {
                // Re-persist before DB finalization: a previous playerdata write may have failed.
                savePlayerNow(player);
            } catch (RuntimeException e) {
                VAuctionMod.LOGGER.error("Не удалось сохранить квитанцию выдачи лота {}", listing.id(), e);
                player.sendSystemMessage(AuctionLang.component("chat.delivery_pending"));
                return false;
            }
            return finalizeDeliveredClaim(player, listing, receiptId);
        }

        ItemStack delivery = listing.item();
        if (!canFitWholeStack(player, delivery)) {
            player.sendSystemMessage(AuctionLang.component("chat.inventory_full"));
            return false;
        }

        setDeliveryReceipt(player, receiptId, true);
        ItemStack remainder = delivery.copy();
        player.getInventory().add(remainder);
        if (!remainder.isEmpty()) {
            // Defensive rollback: preflight should make this unreachable.
            setDeliveryReceipt(player, receiptId, false);
            player.sendSystemMessage(AuctionLang.component("chat.inventory_full"));
            return false;
        }

        // Persist inventory + receipt before committing CLAIMED in SQLite. If the DB write fails,
        // the next claim sees the receipt and finalizes without issuing a second item.
        try {
            savePlayerNow(player);
        } catch (RuntimeException e) {
            VAuctionMod.LOGGER.error("Не удалось сохранить предмет и квитанцию лота {}", listing.id(), e);
            player.sendSystemMessage(AuctionLang.component("chat.delivery_pending"));
            return false;
        }
        return finalizeDeliveredClaim(player, listing, receiptId);
    }

    private boolean finalizeDeliveredClaim(ServerPlayer player, AuctionListing listing, String receiptId) {
        AuctionListing.State previous = listing.state();
        try {
            listing.state(AuctionListing.State.CLAIMED);
            store.update(listing);
            invalidateCaches();
        } catch (RuntimeException e) {
            listing.state(previous);
            invalidateCaches();
            // Keep the persisted receipt: retry/relogin will finalize without another delivery.
            VAuctionMod.LOGGER.error("Не удалось подтвердить выдачу лота {}; квитанция сохранена", listing.id(), e);
            player.sendSystemMessage(AuctionLang.component("chat.delivery_pending"));
            return false;
        }
        // DB is now authoritative: never restore SOLD/EXPIRED if clearing playerdata fails.
        setDeliveryReceipt(player, receiptId, false);
        // The first forced save already contains item + receipt. Vanilla autosave/logout persists
        // cleanup; after a crash the CLAIMED DB state makes a stale receipt harmless.
        player.containerMenu.broadcastChanges();
        return true;
    }

    public synchronized int recoverSaleIntents(ServerPlayer player) {
        net.minecraft.nbt.CompoundTag intents = player.getPersistentData().getCompound(SALE_INTENTS);
        int recovered = 0;
        boolean changed = false;
        for (String rawId : java.util.Set.copyOf(intents.getAllKeys())) {
            try {
                UUID id = UUID.fromString(rawId);
                net.minecraft.nbt.CompoundTag raw = intents.getCompound(rawId);
                AuctionListing listing = saleIntentFromTag(player, id, raw);
                AuctionListing existing = store.get(id);
                if (existing == null) {
                    store.put(listing);
                    invalidateCaches();
                    recovered++;
                } else if (!sameSaleIntent(existing, listing)) {
                    VAuctionMod.LOGGER.error("Квитанция выставления {} не совпадает с лотом в SQLite; оставлена для администратора", id);
                    continue;
                }
                intents.remove(rawId);
                changed = true;
            } catch (RuntimeException e) {
                VAuctionMod.LOGGER.error("Не удалось восстановить квитанцию выставления {} игрока {}",
                        rawId, player.getUUID(), e);
            }
        }
        if (changed) {
            player.getPersistentData().put(SALE_INTENTS, intents);
        }
        if (recovered > 0) player.sendSystemMessage(AuctionLang.component("chat.sales_recovered", "count", recovered));
        return recovered;
    }

    private void cleanupDeliveryReceipts(ServerPlayer player) {
        net.minecraft.nbt.CompoundTag receipts = player.getPersistentData().getCompound(DELIVERY_RECEIPTS);
        boolean changed = false;
        for (String rawId : java.util.Set.copyOf(receipts.getAllKeys())) {
            try {
                AuctionListing listing = store.get(UUID.fromString(rawId));
                if (listing == null || listing.state() == AuctionListing.State.CLAIMED) {
                    receipts.remove(rawId);
                    changed = true;
                }
            } catch (IllegalArgumentException e) {
                receipts.remove(rawId);
                changed = true;
            }
        }
        if (changed) {
            player.getPersistentData().put(DELIVERY_RECEIPTS, receipts);
        }
    }

    private static void setSaleIntent(ServerPlayer player, AuctionListing listing) {
        net.minecraft.nbt.CompoundTag intents = player.getPersistentData().getCompound(SALE_INTENTS);
        net.minecraft.nbt.CompoundTag raw = new net.minecraft.nbt.CompoundTag();
        raw.putUUID("Seller", listing.sellerId());
        raw.putString("SellerName", listing.sellerName());
        raw.put("Item", listing.item().save(new net.minecraft.nbt.CompoundTag()));
        raw.putLong("Price", listing.price());
        raw.putLong("CreatedAt", listing.createdAt());
        raw.putLong("ExpiresAt", listing.expiresAt());
        intents.put(listing.id().toString(), raw);
        player.getPersistentData().put(SALE_INTENTS, intents);
    }

    private static void clearSaleIntent(ServerPlayer player, UUID id) {
        net.minecraft.nbt.CompoundTag intents = player.getPersistentData().getCompound(SALE_INTENTS);
        intents.remove(id.toString());
        player.getPersistentData().put(SALE_INTENTS, intents);
    }

    private static boolean hasSaleIntents(ServerPlayer player) {
        return !player.getPersistentData().getCompound(SALE_INTENTS).isEmpty();
    }

    private static AuctionListing saleIntentFromTag(ServerPlayer player, UUID id, net.minecraft.nbt.CompoundTag raw) {
        UUID seller = raw.hasUUID("Seller") ? raw.getUUID("Seller") : player.getUUID();
        ItemStack item = ItemStack.of(raw.getCompound("Item"));
        long price = raw.getLong("Price"), created = raw.getLong("CreatedAt"), expires = raw.getLong("ExpiresAt");
        if (!seller.equals(player.getUUID()) || item.isEmpty() || price <= 0L || created <= 0L || expires <= created)
            throw new IllegalArgumentException("Некорректная квитанция выставления " + id);
        String name = raw.getString("SellerName");
        if (name.isBlank()) name = player.getGameProfile().getName();
        return new AuctionListing(id, seller, name, item, price, created, expires,
                AuctionListing.State.ACTIVE, null, null);
    }

    private static boolean sameSaleIntent(AuctionListing existing, AuctionListing intended) {
        return existing.sellerId().equals(intended.sellerId())
                && existing.price() == intended.price()
                && existing.createdAt() == intended.createdAt()
                && ItemStack.matches(existing.item(), intended.item());
    }

    private static boolean canFitWholeStack(ServerPlayer player, ItemStack stack) {
        int remaining = stack.getCount();
        for (ItemStack existing : player.getInventory().items) {
            int capacity;
            if (existing.isEmpty()) capacity = Math.min(stack.getMaxStackSize(), 64);
            else if (ItemStack.isSameItemSameTags(existing, stack))
                capacity = Math.max(0, Math.min(existing.getMaxStackSize(), 64) - existing.getCount());
            else continue;
            remaining -= capacity;
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static boolean hasDeliveryReceipt(ServerPlayer player, String receiptId) {
        return player.getPersistentData().getCompound(DELIVERY_RECEIPTS).getBoolean(receiptId);
    }

    private static void setDeliveryReceipt(ServerPlayer player, String receiptId, boolean present) {
        net.minecraft.nbt.CompoundTag receipts = player.getPersistentData().getCompound(DELIVERY_RECEIPTS);
        if (present) receipts.putBoolean(receiptId, true); else receipts.remove(receiptId);
        player.getPersistentData().put(DELIVERY_RECEIPTS, receipts);
    }

    private static void savePlayerNow(ServerPlayer player) {
        player.server.getPlayerList().save(player);
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack original) {
        ItemStack stack = original.copy();
        player.getInventory().add(stack);
        if (!stack.isEmpty()) player.drop(stack, false);
        player.containerMenu.broadcastChanges();
    }

    private static String searchableName(AuctionListing listing) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(listing.item().getItem());
        return (listing.item().getHoverName().getString() + " " + (id == null ? "" : id))
                .toLowerCase(java.util.Locale.ROOT);
    }

    public void invalidateCaches() {
        browseCache.clear();
        userCache.clear();
    }

    private static <K> Map<K, List<AuctionListing>> lruCache() {
        return new LinkedHashMap<>(64, 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, List<AuctionListing>> eldest) {
                return size() > Math.max(8, AuctionConfig.QUERY_CACHE_ENTRIES.get() / 2);
            }
        };
    }

    private record BrowseKey(UUID viewer, AuctionCategory category, AuctionSort sort, String search, String seller) {}
    private enum UserListType { ARCHIVE, HISTORY }
    private record UserListKey(UUID player, UserListType type) {}

    private static void notifyListingUnavailable(UUID id) {
        AuctionMenu.notifyUnavailable(id);
        UserAuctionsMenu.notifyChanged(id);
    }

    private static boolean economyReady(ServerPlayer player) {
        if (EconomyCore.isStarted()) return true;
        error(player, AuctionLang.text("error.economy_offline"));
        return false;
    }

    private static String escrowError(EscrowResult.Status status) {
        return switch (status) {
            case INSUFFICIENT_FUNDS -> AuctionLang.text("error.insufficient_funds");
            case ACCOUNT_DISABLED -> AuctionLang.text("error.account_disabled");
            case LIMIT_EXCEEDED -> AuctionLang.text("error.limit_exceeded");
            default -> AuctionLang.text("error.purchase", "status", status);
        };
    }

    private static void error(ServerPlayer player, String message) {
        player.sendSystemMessage(AuctionLang.legacy("&c" + message));
    }
}
