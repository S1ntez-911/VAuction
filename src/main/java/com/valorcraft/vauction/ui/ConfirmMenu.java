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

/** Exact TM2-style 3x9 confirmation: green left, preview center, red right. */
public final class ConfirmMenu extends AbstractContainerMenu {
    private static final int[] YES = {0,1,2,9,10,11,18,19,20};
    private static final int[] NO = {6,7,8,15,16,17,24,25,26};
    private final SimpleContainer display = new SimpleContainer(27);
    private final ServerPlayer viewer;
    private final Runnable accept;
    private final Runnable back;
    private long lastClickAt;

    private ConfirmMenu(int id, Inventory inv, ItemStack preview, Component yesName, Component noName, Runnable accept, Runnable back) {
        super(MenuType.GENERIC_9x3, id); viewer = (ServerPlayer) inv.player; this.accept = accept; this.back = back;
        MenuSupport.slots(this::addSlot, display, inv, 3);
        ItemStack yes = MenuSupport.icon(MenuSupport.configured(AuctionConfig.CONFIRM_YES_ITEM, Items.LIME_STAINED_GLASS_PANE), yesName);
        ItemStack no = MenuSupport.icon(MenuSupport.configured(AuctionConfig.CONFIRM_NO_ITEM, Items.RED_STAINED_GLASS_PANE), noName);
        ItemStack neutral = MenuSupport.icon(MenuSupport.configured(AuctionConfig.CONFIRM_BACKGROUND_ITEM, Items.LIGHT_GRAY_STAINED_GLASS_PANE), Component.literal(" "));
        for (int i = 0; i < 27; i++) display.setItem(i, neutral.copy());
        for (int i : YES) display.setItem(i, yes.copy()); for (int i : NO) display.setItem(i, no.copy());
        display.setItem(13, preview.copy());
    }

    public static void openListing(ServerPlayer p, AuctionService s, AuctionListing listing, boolean cancel, Runnable back) {
        ItemStack preview = listing.item();
        if (!cancel) MenuSupport.lore(preview, Component.literal(" "),
                AuctionLang.component("tm2.confirm.price", "price", s.formatGui(listing.price())),
                AuctionLang.component("tm2.confirm.seller", "seller", listing.sellerName()));
        Component yes = AuctionLang.component(cancel
                ? s.hasContents(listing) ? "tm2.confirm.remove_container" : "tm2.confirm.remove"
                : "tm2.confirm.buy");
        p.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new ConfirmMenu(id, inv, preview, yes,
                AuctionLang.component(cancel ? "tm2.confirm.back" : "tm2.confirm.cancel"),
                () -> { if (cancel) s.cancel(p, AuctionService.shortId(listing.id())); else s.buy(p, listing.id()); back.run(); }, back),
                AuctionLang.component(cancel ? "tm2.confirm.cancel.title" : "tm2.confirm.buy.title")));
    }

    public static void openSell(ServerPlayer p, AuctionService s, String price, int amount) {
        ItemStack preview = p.getMainHandItem().copy(); preview.setCount(Math.min(amount, preview.getCount()));
        long parsed;
        try { parsed = com.valorcraft.veconomy.util.CurrencyParser.parse(price, com.valorcraft.veconomy.EconomyCore.settings().decimalPlaces); }
        catch (RuntimeException ignored) { parsed = 0L; }
        MenuSupport.lore(preview, Component.literal(" "), AuctionLang.component("tm2.confirm.price", "price", s.formatGui(parsed)));
        p.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new ConfirmMenu(id, inv, preview,
                AuctionLang.component("tm2.confirm.sell"), AuctionLang.component("tm2.confirm.cancel"),
                () -> { s.sell(p, price, amount); AuctionMenu.open(p, s, 0); }, p::closeContainer),
                AuctionLang.component("tm2.confirm.sell.title")));
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        long clickNow = System.currentTimeMillis();
        if (clickNow - lastClickAt < AuctionConfig.MENU_CLICK_COOLDOWN_MS.get()) return;
        lastClickAt = clickNow;
        if (slot >= 0 && slot < 27) {
            for (int yes : YES) if (slot == yes) { accept.run(); return; }
            for (int no : NO) if (slot == no) { back.run(); return; }
            return;
        }
        // Inventory remains locked while the server is asking to confirm this exact action.
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player == viewer && viewer.isAlive(); }
}
