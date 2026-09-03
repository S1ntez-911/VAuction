package com.valorcraft.vauction.ui;

import com.valorcraft.vauction.lang.AuctionLang;
import com.valorcraft.vauction.model.AuctionListing;
import com.valorcraft.vauction.service.AuctionService;
import com.valorcraft.vauction.config.AuctionConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.util.List;

/** TM2 shulker preview: 27 contents slots, navigation row, back at 29 and action at 31. */
public final class ContainerPreviewMenu extends AbstractContainerMenu {
    private final SimpleContainer display = new SimpleContainer(36);
    private final ServerPlayer viewer; private final AuctionListing listing; private final AuctionService service;
    private final Runnable back;
    private int page;
    private long lastClickAt;

    private ContainerPreviewMenu(int id, Inventory inv, AuctionListing listing, AuctionService service,
                                 Runnable back) {
        super(MenuType.GENERIC_9x4, id); viewer = (ServerPlayer) inv.player; this.listing = listing;
        this.service = service; this.back = back;
        MenuSupport.slots(this::addSlot, display, inv, 4); rebuild();
    }

    public static void open(ServerPlayer p, AuctionListing listing, AuctionService service, AuctionViewState returnState) {
        open(p, listing, service, () -> AuctionMenu.open(p, service, returnState));
    }
    public static void open(ServerPlayer p, AuctionListing listing, AuctionService service, Runnable back) {
        p.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new ContainerPreviewMenu(id, inv, listing, service, back),
                AuctionLang.component("tm2.preview.title")));
    }

    private void rebuild() {
        display.clearContent(); List<ItemStack> contents = ContainerInspector.entries(listing.item());
        int pages = Math.max(1, (contents.size() + 26) / 27);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * 27;
        for (int i = from; i < Math.min(from + 27, contents.size()); i++) {
            ItemStack icon = contents.get(i).copy(); String fluid = icon.getOrCreateTag().getString("VAuctionFluidInfo");
            icon.getOrCreateTag().remove("VAuctionFluidInfo");
            if (!fluid.isBlank()) MenuSupport.lore(icon, Component.literal(fluid).withStyle(ChatFormatting.AQUA));
            display.setItem(i - from, icon);
        }
        ItemStack pane = MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACKGROUND_ITEM, Items.GRAY_STAINED_GLASS_PANE), Component.literal(" "));
        for (int i = 27; i < 36; i++) display.setItem(i, pane.copy());
        display.setItem(29, MenuSupport.icon(MenuSupport.configured(AuctionConfig.BACK_ITEM, Items.ARROW), AuctionLang.component("tm2.nav.back")));
        if (page > 0) display.setItem(28, MenuSupport.icon(MenuSupport.configured(AuctionConfig.PREVIOUS_ITEM, Items.GRAY_DYE), AuctionLang.component("tm2.nav.prev")));
        display.setItem(30, MenuSupport.icon(MenuSupport.configured(AuctionConfig.INFO_ITEM, Items.PAPER), AuctionLang.component("tm2.preview.page",
                "page", page + 1, "pages", pages)));
        if (page + 1 < pages) display.setItem(32, MenuSupport.icon(MenuSupport.configured(AuctionConfig.NEXT_ITEM, Items.YELLOW_DYE), AuctionLang.component("tm2.nav.next")));
        ItemStack action = listing.item();
        action.setHoverName(AuctionLang.component(listing.sellerId().equals(viewer.getUUID())
                ? "tm2.confirm.remove_container" : "tm2.preview.buy").copy().withStyle(style -> style.withItalic(false)));
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>(); lore.add(Component.literal(" "));
        lore.addAll(MenuSupport.categoryLines(listing.item()));
        lore.add(AuctionLang.component("tm2.lot.seller", "seller", listing.sellerName()));
        lore.add(AuctionLang.component("tm2.lot.time", "time", AuctionMenu.duration(listing.expiresAt() - System.currentTimeMillis(), true)));
        lore.add(AuctionLang.component("tm2.lot.price", "price", service.formatGui(listing.price()))); lore.add(Component.literal(" "));
        lore.add(AuctionLang.component(listing.sellerId().equals(viewer.getUUID()) ? "tm2.lot.cancel" : "tm2.lot.buy"));
        MenuSupport.lore(action, lore.toArray(Component[]::new));
        display.setItem(31, action); broadcastChanges();
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        long clickNow = System.currentTimeMillis();
        if (clickNow - lastClickAt < AuctionConfig.MENU_CLICK_COOLDOWN_MS.get()) return;
        lastClickAt = clickNow;
        if (slot >= 0 && slot < 36) {
            if (slot == 28 && page > 0) { service.playInterfaceSound(viewer); page--; rebuild(); }
            else if (slot == 29) { service.playInterfaceSound(viewer); back.run(); }
            else if (slot == 32) { service.playInterfaceSound(viewer); page++; rebuild(); }
            else if (slot == 31) {
                AuctionListing fresh = service.findActive(listing.id(), listing.createdAt());
                if (fresh == null) { display.setItem(31, MenuSupport.icon(MenuSupport.configured(AuctionConfig.SOLD_ITEM, Items.BARRIER), AuctionLang.component("tm2.barrier.sold"))); broadcastChanges(); }
                else { service.playInterfaceSound(viewer); ConfirmMenu.openListing(viewer, service, fresh,
                        fresh.sellerId().equals(viewer.getUUID()), back); }
            }
            return;
        }
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player == viewer && viewer.isAlive(); }
}
