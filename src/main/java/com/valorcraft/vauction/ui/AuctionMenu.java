package com.valorcraft.vauction.ui;

import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.model.AuctionListing;
import com.valorcraft.vauction.service.AuctionService;
import com.valorcraft.vauction.lang.AuctionLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionMenu extends AbstractContainerMenu {
    private static final int PAGE_SIZE = 45;
    private static final Map<UUID, AuctionViewState> SAVED_FILTERS = new ConcurrentHashMap<>();
    private static final Set<AuctionMenu> OPEN_MENUS = ConcurrentHashMap.newKeySet();
    private final SimpleContainer display = new SimpleContainer(54);
    private final AuctionService service;
    private final ServerPlayer viewer;
    private int page;
    private AuctionCategory category;
    private AuctionSort sort;
    private final String search;
    private final String seller;
    private List<AuctionListing> listings = List.of();
    private final Map<Integer, UUID> visibleIds = new HashMap<>();
    private final Set<Integer> unavailableSlots = new HashSet<>();
    private final Map<Integer, Long> noMoneyUntil = new HashMap<>();
    private long lastClickAt;

    private AuctionMenu(int id, Inventory inv, AuctionService service, int page, AuctionCategory category,
                        AuctionSort sort, String search, String seller) {
        super(MenuType.GENERIC_9x6, id);
        this.viewer = (ServerPlayer) inv.player; this.service = service; this.page = Math.max(0, page);
        this.category = category == null ? AuctionCategory.ALL : category;
        this.sort = sort == null ? AuctionSort.NEWEST : sort;
        this.search = search == null ? "" : search; this.seller = seller == null ? "" : seller;
        MenuSupport.slots(this::addSlot, display, inv, 6); OPEN_MENUS.add(this); rebuild();
    }

    public static void open(ServerPlayer p, AuctionService s, int ignoredPage) {
        open(p, s, SAVED_FILTERS.getOrDefault(p.getUUID(), AuctionViewState.initial()));
    }
    public static void open(ServerPlayer p, AuctionService s, int page, AuctionCategory category) {
        open(p, s, page, category, AuctionSort.NEWEST, "", "");
    }
    public static void open(ServerPlayer p, AuctionService s, AuctionViewState state) {
        open(p, s, state.page(), state.category(), state.sort(), state.search(), state.seller());
    }
    public static void open(ServerPlayer p, AuctionService s, int page, AuctionCategory category,
                            AuctionSort sort, String search, String seller) {
        int count = s.browse(p.getUUID(), category, sort, search, seller).size();
        int pages = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        int shownPage = Math.max(0, Math.min(page, pages - 1));
        String titleKey = search != null && !search.isBlank() ? "tm2.browse.title_search"
                : seller != null && !seller.isBlank() ? "tm2.browse.title_player" : "tm2.browse.title";
        p.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new AuctionMenu(id, inv, s, page, category, sort, search, seller),
                AuctionLang.component(titleKey, "page", shownPage + 1, "pages", pages, "player", seller)));
    }

    private void rebuild() {
        listings = service.browse(viewer.getUUID(), category, sort, search, seller);
        int pages = Math.max(1, (listings.size() + PAGE_SIZE - 1) / PAGE_SIZE); page = Math.min(page, pages - 1);
        display.clearContent(); visibleIds.clear(); unavailableSlots.clear(); int from = page * PAGE_SIZE;
        for (int i = from; i < Math.min(listings.size(), from + PAGE_SIZE); i++) {
            display.setItem(i - from, listingIcon(listings.get(i)));
            visibleIds.put(i - from, listings.get(i).id());
        }
        ItemStack pane = MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACKGROUND_ITEM, Items.GRAY_STAINED_GLASS_PANE), Component.literal(" "));
        for (int i = 45; i < 54; i++) display.setItem(i, pane.copy());
        display.setItem(45, MenuSupport.icon(MenuSupport.configured(AuctionConfig.MY_LISTINGS_ITEM, Items.CHEST), AuctionLang.component("tm2.nav.my"),
                MenuSupport.lines(AuctionLang.text("tm2.nav.my_lore", "count", service.myListings(viewer.getUUID()).size()))));
        // OXIDIZED_COPPER_CHEST does not exist in 1.20.1; barrel is the compatibility icon.
        display.setItem(46, MenuSupport.icon(MenuSupport.configured(AuctionConfig.ARCHIVE_ITEM, Items.BARREL), AuctionLang.component("tm2.nav.archive"),
                MenuSupport.lines(AuctionLang.text("tm2.nav.archive_lore", "count", service.archive(viewer.getUUID()).size()))));
        display.setItem(47, MenuSupport.icon(MenuSupport.configured(AuctionConfig.REFRESH_ITEM, Items.EMERALD), AuctionLang.component("tm2.nav.refresh")));
        if (page > 0) display.setItem(48, MenuSupport.icon(MenuSupport.configured(AuctionConfig.PREVIOUS_ITEM, Items.GRAY_DYE), AuctionLang.component("tm2.nav.prev")));
        display.setItem(49, MenuSupport.icon(MenuSupport.configured(AuctionConfig.INFO_ITEM, Items.NETHER_STAR), AuctionLang.component("tm2.nav.help"),
                MenuSupport.lines(AuctionLang.text("tm2.nav.help_lore", "max", AuctionConfig.MAX_LISTINGS_PER_PLAYER.get()))));
        if (page + 1 < pages) display.setItem(50, MenuSupport.icon(MenuSupport.configured(AuctionConfig.NEXT_ITEM, Items.YELLOW_DYE), AuctionLang.component("tm2.nav.next")));
        display.setItem(51, MenuSupport.icon(MenuSupport.configured(AuctionConfig.RESET_ITEM, Items.NAME_TAG), AuctionLang.component("tm2.nav.reset")));
        display.setItem(52, MenuSupport.icon(MenuSupport.configured(AuctionConfig.SORT_ITEM, Items.HOPPER), AuctionLang.component("tm2.nav.sort"), sortLore()));
        display.setItem(53, MenuSupport.icon(MenuSupport.configured(AuctionConfig.FILTER_ITEM, Items.CHEST_MINECART), AuctionLang.component("tm2.nav.category"), categoryLore()));
        SAVED_FILTERS.put(viewer.getUUID(), currentState());
        broadcastChanges();
    }

    private ItemStack listingIcon(AuctionListing l) {
        ItemStack icon = l.item();
        List<Component> lore = new ArrayList<>(); lore.add(Component.literal(" "));
        lore.addAll(MenuSupport.categoryLines(l.item()));
        lore.add(AuctionLang.component("tm2.lot.seller", "seller", l.sellerName()));
        lore.add(AuctionLang.component("tm2.lot.time", "time", remaining(l)));
        lore.add(AuctionLang.component("tm2.lot.price", "price", service.formatGui(l.price()))); lore.add(Component.literal(" "));
        lore.add(AuctionLang.component(l.sellerId().equals(viewer.getUUID()) ? "tm2.lot.cancel"
                : service.hasContents(l) ? "tm2.lot.preview" : "tm2.lot.buy"));
        MenuSupport.lore(icon, lore.toArray(Component[]::new));
        return icon;
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        long clickNow = System.currentTimeMillis();
        if (clickNow - lastClickAt < AuctionConfig.MENU_CLICK_COOLDOWN_MS.get()) return;
        lastClickAt = clickNow;
        if (slot < 0 || slot >= 54) { super.clicked(slot, button, type, player); return; }
        if (slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index < listings.size()) {
                if (unavailableSlots.contains(slot)) return;
                AuctionListing listing = listings.get(index);
                AuctionListing fresh = service.findActive(listing.id(), listing.createdAt());
                if (fresh == null) { showUnavailable(slot, listing.item().getCount()); return; }
                listing = fresh;
                if (!listing.sellerId().equals(viewer.getUUID()) && !service.hasFunds(viewer.getUUID(), listing.price())) {
                    long now = System.currentTimeMillis();
                    if (noMoneyUntil.getOrDefault(slot, 0L) > now) return;
                    noMoneyUntil.put(slot, now + 1500L);
                    ItemStack barrier = MenuSupport.icon(MenuSupport.configured(AuctionConfig.NO_MONEY_ITEM, Items.BARRIER), AuctionLang.component("tm2.barrier.no_money"));
                    barrier.setCount(Math.max(1, Math.min(64, listing.item().getCount())));
                    display.setItem(slot, barrier); broadcastChanges();
                    viewer.playNotifySound(SoundEvents.ANVIL_PLACE, SoundSource.MASTER, 0.3F, 0.8F);
                    java.util.concurrent.CompletableFuture.delayedExecutor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> viewer.server.execute(() -> {
                                noMoneyUntil.remove(slot);
                                if (viewer.containerMenu == this) rebuild();
                            }));
                    return;
                }
                if (!listing.sellerId().equals(viewer.getUUID()) && service.hasContents(listing)) {
                    service.playInterfaceSound(viewer);
                    ContainerPreviewMenu.open(viewer, listing, service, currentState()); return;
                }
                service.playInterfaceSound(viewer);
                ConfirmMenu.openListing(viewer, service, listing, listing.sellerId().equals(viewer.getUUID()),
                        () -> AuctionMenu.open(viewer, service, currentState())); return;
            }
        } else switch (slot) {
            case 45 -> { service.playInterfaceSound(viewer); UserAuctionsMenu.open(viewer, service, UserAuctionsMenu.Mode.ACTIVE, 0); return; }
            case 46 -> { service.playInterfaceSound(viewer); UserAuctionsMenu.open(viewer, service, UserAuctionsMenu.Mode.ARCHIVE, 0); return; }
            case 48 -> { service.playInterfaceSound(viewer); open(viewer, service, currentState().withPage(page - 1)); return; }
            case 49 -> viewer.sendSystemMessage(AuctionLang.component("chat.gui_help"));
            case 50 -> { service.playInterfaceSound(viewer); open(viewer, service, currentState().withPage(page + 1)); return; }
            case 51 -> { service.playInterfaceSound(viewer); SAVED_FILTERS.remove(viewer.getUUID()); open(viewer, service, AuctionViewState.initial()); return; }
            case 52 -> { sort = sort.next(button == 1 ? -1 : 1); page = 0; }
            case 53 -> { category = category.next(button == 1 ? -1 : 1); page = 0; }
        }
        service.playInterfaceSound(viewer); rebuild();
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player == viewer && viewer.isAlive(); }
    @Override public void removed(Player player) { OPEN_MENUS.remove(this); super.removed(player); }

    public static void notifyUnavailable(UUID listingId) {
        for (AuctionMenu menu : List.copyOf(OPEN_MENUS)) {
            if (menu.viewer.containerMenu != menu) { OPEN_MENUS.remove(menu); continue; }
            for (Map.Entry<Integer, UUID> entry : menu.visibleIds.entrySet()) if (entry.getValue().equals(listingId)) {
                int count = entry.getKey() < menu.display.getContainerSize() ? menu.display.getItem(entry.getKey()).getCount() : 1;
                menu.showUnavailable(entry.getKey(), count); break;
            }
        }
    }

    public static void clearSavedState(UUID playerId) {
        SAVED_FILTERS.remove(playerId);
    }

    private void showUnavailable(int slot, int count) {
        unavailableSlots.add(slot);
        ItemStack barrier = MenuSupport.icon(MenuSupport.configured(AuctionConfig.SOLD_ITEM, Items.BARRIER), AuctionLang.component("tm2.barrier.sold"));
        barrier.setCount(Math.max(1, Math.min(64, count))); display.setItem(slot, barrier); broadcastChanges();
    }

    private AuctionViewState currentState() { return new AuctionViewState(page, category, sort, search, seller); }
    private static String remaining(AuctionListing l) {
        return duration(Math.max(0, l.expiresAt() - System.currentTimeMillis()), true);
    }

    static String duration(long millis, boolean secondsIncluded) {
        long total = Math.max(0, millis / 1000), days = total / 86400, hours = total % 86400 / 3600;
        long minutes = total % 3600 / 60, seconds = total % 60;
        if (days > 0) return days + "д. " + hours + "ч. " + minutes + "м." + (secondsIncluded ? " " + seconds + "с." : "");
        if (hours > 0) return hours + "ч. " + minutes + "м." + (secondsIncluded ? " " + seconds + "с." : "");
        return Math.max(1, minutes) + "м." + (secondsIncluded ? " " + seconds + "с." : "");
    }

    private Component[] sortLore() {
        List<Component> lines = new java.util.ArrayList<>();
        for (AuctionSort value : AuctionSort.values()) lines.add(AuctionLang.component(value == sort ? "tm2.nav.selected" : "tm2.nav.unselected", "name", value.title()));
        return lines.toArray(Component[]::new);
    }
    private Component[] categoryLore() {
        List<Component> lines = new java.util.ArrayList<>();
        for (AuctionCategory value : AuctionCategory.values()) lines.add(AuctionLang.component(value == category ? "tm2.nav.selected" : "tm2.nav.unselected", "name", value.displayName()));
        return lines.toArray(Component[]::new);
    }
}
