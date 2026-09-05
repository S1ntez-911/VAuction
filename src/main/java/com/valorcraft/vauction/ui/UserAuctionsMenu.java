package com.valorcraft.vauction.ui;

import com.valorcraft.vauction.model.AuctionListing;
import com.valorcraft.vauction.service.AuctionService;
import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.lang.AuctionLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UserAuctionsMenu extends AbstractContainerMenu implements ReloadableMenu {
    private static final Set<UserAuctionsMenu> OPEN_MENUS = ConcurrentHashMap.newKeySet();
    public enum Mode { ACTIVE, ARCHIVE, HISTORY, PLAYER }
    private final SimpleContainer display = new SimpleContainer(54);
    private final ServerPlayer viewer; private final AuctionService service; private final Mode mode;
    private final Mode returnMode;
    private final String targetName;
    private final Map<Integer, Long> noMoneyUntil = new java.util.HashMap<>();
    private long lastClickAt;
    private int page; private List<AuctionListing> entries = List.of();
    private UserAuctionsMenu(int id, Inventory inv, AuctionService service, Mode mode, int page, Mode returnMode, String targetName) {
        super(MenuType.GENERIC_9x6, id); viewer = (ServerPlayer) inv.player; this.service = service; this.mode = mode; this.page = page; this.returnMode = returnMode; this.targetName = targetName == null ? "" : targetName;
        MenuSupport.slots(this::addSlot, display, inv, 6); OPEN_MENUS.add(this); rebuild();
    }
    public static void open(ServerPlayer p, AuctionService s, Mode mode, int page) {
        open(p, s, mode, page, null, "");
    }
    public static void openPlayer(ServerPlayer p, AuctionService s, String targetName, int page) {
        if (p.getGameProfile().getName().equalsIgnoreCase(targetName)) {
            open(p, s, Mode.ACTIVE, page);
            return;
        }
        open(p, s, Mode.PLAYER, page, null, targetName);
    }
    private static void open(ServerPlayer p, AuctionService s, Mode mode, int page, Mode returnMode, String targetName) {
        int count = switch (mode) {
            case ACTIVE -> s.myListings(p.getUUID()).size(); case ARCHIVE -> s.archive(p.getUUID()).size();
            case HISTORY -> s.salesHistory(p.getUUID()).size();
            case PLAYER -> s.browse(p.getUUID(), AuctionCategory.ALL, AuctionSort.NEWEST, "", targetName).size();
        };
        int pages = Math.max(1, (count + 44) / 45), shown = Math.max(0, Math.min(page, pages - 1));
        String title = switch (mode) { case ACTIVE -> "tm2.my.title"; case ARCHIVE -> "tm2.archive.title";
            case HISTORY -> "tm2.history.title"; case PLAYER -> "tm2.browse.title_player"; };
        p.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new UserAuctionsMenu(id, inv, s, mode, page, returnMode, targetName),
                AuctionLang.component(title, "page", shown + 1, "pages", pages, "player", targetName)));
    }
    @Override public void refreshConfig() { rebuild(); broadcastChanges(); }

    private void rebuild() {
        entries = switch (mode) { case ACTIVE -> service.myListings(viewer.getUUID()); case ARCHIVE -> service.archive(viewer.getUUID());
            case HISTORY -> service.salesHistory(viewer.getUUID());
            case PLAYER -> service.browse(viewer.getUUID(), AuctionCategory.ALL, AuctionSort.NEWEST, "", targetName); };
        int pages = Math.max(1, (entries.size() + 44) / 45); page = Math.max(0, Math.min(page, pages - 1)); display.clearContent();
        int from = page * 45;
        for (int i = from; i < Math.min(entries.size(), from + 45); i++) {
            AuctionListing l = entries.get(i); ItemStack icon = l.item();
            java.util.ArrayList<Component> lore = new java.util.ArrayList<>(); lore.add(Component.literal(" "));
            lore.addAll(MenuSupport.categoryLines(l.item()));
            if (mode == Mode.ACTIVE || mode == Mode.PLAYER) {
                if (mode == Mode.PLAYER) lore.add(AuctionLang.component("tm2.lot.seller", "seller", l.sellerName()));
                lore.add(AuctionLang.component("tm2.lot.time", "time", AuctionMenu.duration(l.expiresAt() - System.currentTimeMillis(), true)));
                lore.add(AuctionLang.component("tm2.lot.price", "price", service.formatGui(l.price()))); lore.add(Component.literal(" "));
                lore.add(AuctionLang.component(mode == Mode.ACTIVE ? "tm2.lot.cancel"
                        : service.hasContents(l) ? "tm2.lot.preview" : "tm2.lot.buy"));
            } else if (mode == Mode.ARCHIVE) {
                lore.add(AuctionLang.component("tm2.lot.stored"));
                lore.add(Component.literal(" ")); lore.add(AuctionLang.component("tm2.lot.take"));
            } else {
                lore.add(AuctionLang.component("tm2.history.buyer", "buyer", l.buyerName() == null ? "?" : l.buyerName()));
                lore.add(AuctionLang.component("tm2.lot.price", "price", service.formatGui(l.price())));
                lore.add(AuctionLang.component("tm2.history.when", "ago", AuctionMenu.duration(System.currentTimeMillis() - l.soldAt(), false)));
            }
            MenuSupport.lore(icon, lore.toArray(Component[]::new));
            display.setItem(i - from, icon);
        }
        ItemStack pane = MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACKGROUND_ITEM, Items.GRAY_STAINED_GLASS_PANE), Component.literal(" "));
        for (int i = 45; i < 54; i++) display.setItem(i, pane.copy());
        if (mode == Mode.ACTIVE) {
            display.setItem(45, MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACK_ITEM, Items.ARROW), AuctionLang.component("tm2.nav.back")));
            display.setItem(46, MenuSupport.icon(MenuSupport.configured(AuctionConfig.ARCHIVE_ITEM, Items.BARREL), AuctionLang.component("tm2.nav.archive"),
                    MenuSupport.lines(AuctionLang.text("tm2.nav.archive_lore", "count", service.archive(viewer.getUUID()).size()))));
            display.setItem(53, historyButton());
        } else if (mode == Mode.ARCHIVE) {
            display.setItem(45, MenuSupport.icon(MenuSupport.configured(AuctionConfig.MY_LISTINGS_ITEM, Items.CHEST), AuctionLang.component("tm2.nav.my"),
                    MenuSupport.lines(AuctionLang.text("tm2.nav.my_lore", "count", service.myListings(viewer.getUUID()).size()))));
            display.setItem(46, MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACK_ITEM, Items.ARROW), AuctionLang.component("tm2.nav.back")));
            display.setItem(47, MenuSupport.icon(MenuSupport.configured(AuctionConfig.CLAIM_ALL_ITEM, Items.HOPPER), AuctionLang.component("tm2.archive.take_all"),
                    MenuSupport.lines(AuctionLang.text("tm2.archive.take_all_lore"))));
            display.setItem(53, historyButton());
        } else if (mode == Mode.HISTORY) display.setItem(53, MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACK_ITEM, Items.ARROW), AuctionLang.component("tm2.nav.back")));
        else if (mode == Mode.PLAYER) display.setItem(45, MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACK_ITEM, Items.ARROW), AuctionLang.component("tm2.nav.back")));
        if (page > 0) display.setItem(48, MenuSupport.icon(MenuSupport.configured(AuctionConfig.PREVIOUS_ITEM, Items.GRAY_DYE), AuctionLang.component("tm2.nav.prev")));
        String helpLore = mode == Mode.ACTIVE ? "tm2.nav.help_my" : mode == Mode.ARCHIVE ? "tm2.nav.help_archive"
                : mode == Mode.HISTORY ? "tm2.nav.help_history_v2" : "tm2.nav.help_lore";
        display.setItem(49, MenuSupport.icon(MenuSupport.configured(AuctionConfig.INFO_ITEM, Items.NETHER_STAR), AuctionLang.component("tm2.nav.help"),
                MenuSupport.lines(AuctionLang.text(helpLore, "days", AuctionConfig.HISTORY_RETENTION_DAYS.get()))));
        if (page + 1 < pages) display.setItem(50, MenuSupport.icon(MenuSupport.configured(AuctionConfig.NEXT_ITEM, Items.YELLOW_DYE), AuctionLang.component("tm2.nav.next")));
        broadcastChanges();
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        long clickNow = System.currentTimeMillis();
        if (clickNow - lastClickAt < AuctionConfig.MENU_CLICK_COOLDOWN_MS.get()) return;
        lastClickAt = clickNow;
        if (slot < 0 || slot >= 54) { super.clicked(slot, button, type, player); return; }
        if (slot < 45) { int i = page * 45 + slot; if (i < entries.size()) {
            AuctionListing l = entries.get(i);
            if (mode == Mode.ACTIVE) ConfirmMenu.openListing(viewer, service, l, true, () -> open(viewer, service, mode, page));
            else if (mode == Mode.ARCHIVE) { service.claimOne(viewer, l.id()); rebuild(); }
            else if (mode == Mode.PLAYER) {
                AuctionListing fresh = service.findActive(l.id(), l.createdAt());
                if (fresh == null) { display.setItem(slot, MenuSupport.icon(MenuSupport.configured(AuctionConfig.SOLD_ITEM, Items.BARRIER), AuctionLang.component("tm2.barrier.sold"))); broadcastChanges(); return; }
                if (!service.hasFunds(viewer.getUUID(), fresh.price())) {
                    long now = System.currentTimeMillis();
                    if (noMoneyUntil.getOrDefault(slot, 0L) > now) return;
                    noMoneyUntil.put(slot, now + 1500L);
                    ItemStack barrier = MenuSupport.icon(MenuSupport.configured(AuctionConfig.NO_MONEY_ITEM, Items.BARRIER), AuctionLang.component("tm2.barrier.no_money"));
                    barrier.setCount(Math.max(1, Math.min(64, fresh.item().getCount())));
                    display.setItem(slot, barrier); broadcastChanges();
                    java.util.concurrent.CompletableFuture.delayedExecutor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> viewer.server.execute(() -> {
                                noMoneyUntil.remove(slot);
                                if (viewer.containerMenu == this) rebuild();
                            }));
                    return;
                }
                Runnable back = () -> openPlayer(viewer, service, targetName, page);
                if (service.hasContents(fresh)) ContainerPreviewMenu.open(viewer, fresh, service, back);
                else ConfirmMenu.openListing(viewer, service, fresh, false, back);
            }
            return;
        }}
        switch (slot) {
            case 45 -> { if (mode == Mode.ARCHIVE) open(viewer, service, Mode.ACTIVE, 0); else if (mode == Mode.ACTIVE || mode == Mode.PLAYER) AuctionMenu.open(viewer, service, 0); return; }
            case 46 -> { if (mode == Mode.ACTIVE) open(viewer, service, Mode.ARCHIVE, 0); else if (mode == Mode.ARCHIVE) AuctionMenu.open(viewer, service, 0); return; }
            case 47 -> { if (mode == Mode.ARCHIVE) service.claim(viewer); }
            case 48 -> { reopen(page - 1); return; }
            case 50 -> { reopen(page + 1); return; }
            case 53 -> {
                if (mode == Mode.HISTORY) {
                    if (returnMode == null) AuctionMenu.open(viewer, service, 0); else open(viewer, service, returnMode, 0);
                } else if (mode != Mode.PLAYER) open(viewer, service, Mode.HISTORY, 0, mode, "");
                return;
            }
        } rebuild();
    }
    private void reopen(int targetPage) {
        open(viewer, service, mode, targetPage, returnMode, targetName);
    }
    private ItemStack historyButton() {
        return MenuSupport.icon(MenuSupport.configured(AuctionConfig.HISTORY_ITEM, Items.BOOK), AuctionLang.component("tm2.nav.history"),
                MenuSupport.lines(AuctionLang.text("tm2.nav.history_lore_v2", "days", AuctionConfig.HISTORY_RETENTION_DAYS.get())));
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player == viewer && viewer.isAlive(); }
    @Override public void removed(Player player) { OPEN_MENUS.remove(this); super.removed(player); }

    public static void notifyChanged(UUID listingId) {
        for (UserAuctionsMenu menu : List.copyOf(OPEN_MENUS)) {
            if (menu.viewer.containerMenu != menu) { OPEN_MENUS.remove(menu); continue; }
            if (menu.entries.stream().anyMatch(it -> it.id().equals(listingId))) menu.rebuild();
        }
    }
}
