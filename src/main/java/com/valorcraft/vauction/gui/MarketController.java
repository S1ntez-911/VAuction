package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.application.Page;
import com.valorcraft.vauction.application.SimpleAuctionService;
import com.valorcraft.vauction.bootstrap.VAuctionCore;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.item.ItemCodecException;
import com.valorcraft.vauction.item.StoredContents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Catalogue, purchase confirmation and a fixed read-only storage preview. */
public final class MarketController {
    static final int CONFIRM_BACK = 45;
    static final int CONFIRM_PRIMARY = 49;
    private static final Logger LOGGER = LogManager.getLogger("VAuction/UI");
    private static final MarketController INSTANCE = new MarketController();
    private final Map<UUID, MarketSession> sessions = new ConcurrentHashMap<>();

    private MarketController() {}

    public static MarketController instance() { return INSTANCE; }

    public void open(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession s = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        s.screen = MarketScreen.BROWSE;
        s.cataloguePage = 0;
        s.search = "";
        s.mineOnly = false;
        s.filter = MarketFilter.ALL;
        renderCatalogue(player, s);
    }

    public void search(ServerPlayer player, String query, int page) {
        if (!ready(player)) return;
        MarketSession s = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        s.screen = MarketScreen.BROWSE;
        s.search = query == null ? "" : query.trim();
        s.cataloguePage = Math.max(0, page);
        renderCatalogue(player, s);
    }

    public void showMine(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession s = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        s.screen = MarketScreen.BROWSE;
        s.mineOnly = true;
        s.cataloguePage = 0;
        renderCatalogue(player, s);
    }

    public void clicked(Player raw, MarketSession s, int slotId, int button, ClickType type) {
        if (!(raw instanceof ServerPlayer player) || !player.getUUID().equals(s.playerId)
                || s.executing || type != ClickType.PICKUP || (button != 0 && button != 1)
                || s.contents == null || slotId < 0 || slotId >= s.contents.getContainerSize()) return;
        GuiAction action = s.actions.get(slotId);
        if (action == null) return;
        s.executing = true;
        try { handle(player, s, action); }
        catch (RuntimeException | LinkageError e) {
            LOGGER.error("Simple auction UI action failed player={} action={}", player.getUUID(), action.type(), e);
            tell(player, "Аукцион временно недоступен.", ChatFormatting.RED);
            MarketSounds.error(player);
        } finally { s.executing = false; }
    }

    private void handle(ServerPlayer player, MarketSession s, GuiAction action) {
        switch (action.type()) {
            case REFRESH -> renderCatalogue(player, s);
            case PAGE -> { s.cataloguePage = Math.max(0, s.cataloguePage + action.number()); renderCatalogue(player, s); }
            case TOGGLE_MINE -> { s.mineOnly = !s.mineOnly; s.cataloguePage = 0; renderCatalogue(player, s); }
            case NEXT_CATEGORY -> {
                MarketFilter[] filters = MarketFilter.values();
                s.filter = filters[(s.filter.ordinal() + 1) % filters.length];
                s.cataloguePage = 0;
                renderCatalogue(player, s);
            }
            case OPEN_LISTING -> openListing(player, s, action.listingId());
            case CONFIRM_PURCHASE -> purchase(player, s, action.listingId());
            case OPEN_CONTENTS -> {
                s.contentsPage = 0;
                openContents(player, s, action.listingId());
            }
            case CONTENTS_PAGE -> {
                s.contentsPage = Math.max(0, s.contentsPage + action.number());
                openContents(player, s, action.listingId());
            }
            case CLAIM_ALL -> { claimAll(player); renderCatalogue(player, s); }
            case BACK -> { s.screen = MarketScreen.BROWSE; renderCatalogue(player, s); }
            case BACK_TO_LISTING -> openListing(player, s, action.listingId());
        }
    }

    private void openListing(ServerPlayer player, MarketSession s, long listingId) {
        AuctionListing listing = service().find(listingId);
        if (listing == null || listing.status() != com.valorcraft.vauction.domain.listing.ListingStatus.ACTIVE) {
            tell(player, "Этот лот уже недоступен.", ChatFormatting.RED);
            renderCatalogue(player, s);
            return;
        }
        if (listing.sellerUuid().equals(player.getUUID())) {
            cancel(player, s, listingId);
            return;
        }
        s.screen = MarketScreen.CONFIRM_PURCHASE;
        s.resetActions();
        SimpleContainer box = new SimpleContainer(54);
        ItemStack exact = decode(listing);
        StoredContents.Inspection contents = StoredContents.inspect(exact);
        box.setItem(22, listingCard(player, listing, exact, true));
        put(box, s, CONFIRM_BACK, GuiItems.namedButton(new ItemStack(Items.ARROW),
                "Назад", ChatFormatting.GRAY, "Вернуться к каталогу"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, CONFIRM_PRIMARY, GuiItems.namedButton(new ItemStack(Items.LIME_CONCRETE),
                "Купить за " + CurrencyText.format(listing.priceMinor()), ChatFormatting.GREEN,
                "Вы получите весь показанный лот"),
                GuiAction.listing(GuiAction.Type.CONFIRM_PURCHASE, listingId));
        if (contents.readable() && contents.hasItems()) {
            put(box, s, 47, GuiItems.namedButton(new ItemStack(Items.CHEST),
                    "Посмотреть содержимое", ChatFormatting.AQUA,
                    "Ячеек занято: " + contents.occupiedItemSlots(),
                    "Просмотр без возможности забрать предметы"),
                    GuiAction.listing(GuiAction.Type.OPEN_CONTENTS, listingId));
        }
        openBox(player, s, box, "Купить лот?");
    }

    private void openContents(ServerPlayer player, MarketSession s, long listingId) {
        AuctionListing listing = service().find(listingId);
        if (listing == null || listing.status() != com.valorcraft.vauction.domain.listing.ListingStatus.ACTIVE) {
            tell(player, "Этот лот уже недоступен.", ChatFormatting.RED);
            renderCatalogue(player, s);
            return;
        }
        StoredContents.Inspection contents = StoredContents.inspect(decode(listing));
        if (!contents.readable() || !contents.hasItems()) {
            tell(player, "Содержимое этого хранилища недоступно для просмотра.", ChatFormatting.RED);
            openListing(player, s, listingId);
            return;
        }
        final int pageSize = 45;
        int pages = Math.max(1, (contents.slots().size() + pageSize - 1) / pageSize);
        s.contentsPage = Math.min(s.contentsPage, pages - 1);
        int start = s.contentsPage * pageSize;
        int end = Math.min(contents.slots().size(), start + pageSize);
        s.screen = MarketScreen.CONTENTS_PREVIEW;
        s.resetActions();
        SimpleContainer box = new SimpleContainer(54);
        for (int sourceSlot = start; sourceSlot < end; sourceSlot++) {
            ItemStack contained = contents.slots().get(sourceSlot);
            if (!contained.isEmpty()) box.setItem(sourceSlot - start, contained.copy());
        }
        boolean previous = s.contentsPage > 0;
        boolean next = s.contentsPage + 1 < pages;
        put(box, s, 45, previewArrow(false, previous), previous
                ? GuiAction.listingPage(GuiAction.Type.CONTENTS_PAGE, listingId, -1) : null);
        put(box, s, 49, GuiItems.namedButton(new ItemStack(Items.ARROW),
                "Назад к покупке", ChatFormatting.YELLOW, "Вернуться к карточке лота"),
                GuiAction.listing(GuiAction.Type.BACK_TO_LISTING, listingId));
        put(box, s, 53, previewArrow(true, next), next
                ? GuiAction.listingPage(GuiAction.Type.CONTENTS_PAGE, listingId, 1) : null);
        openBox(player, s, box, "Содержимое • " + (s.contentsPage + 1) + "/" + pages);
    }

    private void purchase(ServerPlayer player, MarketSession s, long listingId) {
        SimpleAuctionService.Outcome outcome = service().purchase(player.getUUID(), listingId);
        if (outcome.success()) {
            if (outcome.deliveryId() != null) {
                AuctionService.Outcome claim = VAuctionCore.instance().auctionService()
                        .claimDelivery(player.getUUID(), outcome.deliveryId());
                if (claim.isSuccess()) tell(player, "Покупка завершена. Предмет добавлен в инвентарь.", ChatFormatting.GREEN);
                else tell(player, "Покупка завершена. Освободите место и нажмите «Забрать».", ChatFormatting.YELLOW);
            } else tell(player, outcome.message(), ChatFormatting.YELLOW);
        } else tell(player, outcome.message(), ChatFormatting.RED);
        s.screen = MarketScreen.BROWSE;
        renderCatalogue(player, s);
    }

    private void cancel(ServerPlayer player, MarketSession s, long listingId) {
        SimpleAuctionService.Outcome outcome = service().cancel(player.getUUID(), listingId);
        if (outcome.success()) {
            boolean returned = false;
            if (outcome.deliveryId() != null) {
                returned = VAuctionCore.instance().auctionService()
                        .claimDelivery(player.getUUID(), outcome.deliveryId()).isSuccess();
            }
            tell(player, returned ? "Лот снят. Предмет возвращён." : "Лот снят. Предмет ждёт в «Забрать».",
                    returned ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        } else tell(player, outcome.message(), ChatFormatting.RED);
        s.screen = MarketScreen.BROWSE;
        renderCatalogue(player, s);
    }

    private void renderCatalogue(ServerPlayer player, MarketSession s) {
        s.screen = MarketScreen.BROWSE;
        int[] content = UiConfig.slots("catalogue", "content");
        Page<AuctionListing> page = service().catalogue(player.getUUID(), s.mineOnly,
                s.filter.category, s.search, s.cataloguePage, Math.max(1, content.length));
        if (s.cataloguePage > 0 && page.items().isEmpty()) {
            s.cataloguePage = Math.max(0, page.totalPages() - 1);
            page = service().catalogue(player.getUUID(), s.mineOnly, s.filter.category,
                    s.search, s.cataloguePage, Math.max(1, content.length));
        }
        context(player, s, page);
        SimpleContainer box = new SimpleContainer(UiConfig.rows("catalogue") * 9);
        s.resetActions();
        for (int i = 0; i < page.items().size() && i < content.length; i++) {
            AuctionListing listing = page.items().get(i);
            ItemStack exact = decode(listing);
            put(box, s, content[i], listingCard(player, listing, exact, false),
                    GuiAction.listing(GuiAction.Type.OPEN_LISTING, listing.listingId()));
        }
        if (page.items().isEmpty()) {
            int empty = UiConfig.slot("catalogue", "empty");
            String name = s.mineOnly ? "У вас нет активных лотов" : "Подходящих лотов нет";
            put(box, s, empty, configuredButton(s, "empty", name, null), null);
        }
        navigation(player, box, s, page);
        UiConfig.decorate("catalogue", box, s.placeholders);
        openBox(player, s, box, UiConfig.title("catalogue", s.placeholders));
    }

    private void navigation(ServerPlayer player, SimpleContainer box, MarketSession s, Page<?> page) {
        int previous = UiConfig.slot("catalogue", "previous");
        int next = UiConfig.slot("catalogue", "next");
        put(box, s, previous, arrow(s, false, page.hasPrevious()),
                page.hasPrevious() ? GuiAction.number(GuiAction.Type.PAGE, -1) : null);
        put(box, s, next, arrow(s, true, page.hasNext()),
                page.hasNext() ? GuiAction.number(GuiAction.Type.PAGE, 1) : null);

        String category = UiConfig.text(s.filter.textKey);
        put(box, s, UiConfig.slot("catalogue", "categories"),
                configuredButton(s, "categories", "Раздел: " + category,
                        "Нажмите, чтобы переключить"), GuiAction.simple(GuiAction.Type.NEXT_CATEGORY));
        put(box, s, UiConfig.slot("catalogue", "refresh"), configuredButton(s, "refresh", null, null),
                GuiAction.simple(GuiAction.Type.REFRESH));
        put(box, s, UiConfig.slot("catalogue", "my"), configuredButton(s, "my",
                        s.mineOnly ? "Показаны мои лоты" : "Мои лоты",
                        s.mineOnly ? "Нажмите, чтобы показать все" : "Нажмите, чтобы показать только свои"),
                GuiAction.simple(GuiAction.Type.TOGGLE_MINE));

        int claimSlot = firstFreeControl(s, box, "help", "search", "info");
        List<AuctionDelivery> claims = VAuctionCore.instance().auctionReadService()
                .deliveries(player.getUUID(), 0).items();
        s.placeholders.put("claims", Integer.toString(claims.size()));
        if (!claims.isEmpty() && claimSlot >= 0) {
            put(box, s, claimSlot, configuredButton(s, "claim",
                    "Забрать предметы (" + claims.size() + ")", null), GuiAction.simple(GuiAction.Type.CLAIM_ALL));
        } else if (claimSlot >= 0) {
            put(box, s, claimSlot, configuredButton(s, "info",
                    "Страница " + (page.page() + 1) + " / " + page.totalPages(),
                    s.mineOnly ? "Показаны только ваши лоты" : "ЛКМ по товару: купить"), null);
        }
    }

    private void claimAll(ServerPlayer player) {
        int claimed = 0;
        while (claimed < 100) {
            List<AuctionDelivery> page = VAuctionCore.instance().auctionReadService()
                    .deliveries(player.getUUID(), 0).items();
            if (page.isEmpty()) break;
            boolean progress = false;
            for (AuctionDelivery delivery : page) {
                AuctionService.Outcome outcome = VAuctionCore.instance().auctionService()
                        .claimDelivery(player.getUUID(), delivery.deliveryId());
                if (!outcome.isSuccess()) {
                    tell(player, "Освободите место в инвентаре.", ChatFormatting.YELLOW);
                    return;
                }
                claimed++;
                progress = true;
            }
            if (!progress) break;
        }
        if (claimed > 0) tell(player, "Получено предметов: " + claimed + ".", ChatFormatting.GREEN);
    }

    private ItemStack listingCard(ServerPlayer viewer, AuctionListing listing, ItemStack exact, boolean confirm) {
        LinkedHashMap<String, UiConfig.LineValue> values = new LinkedHashMap<>();
        values.put("listing.price", new UiConfig.LineValue("listing.priceLabel",
                CurrencyText.format(listing.priceMinor()), "money"));
        values.put("listing.quantity", new UiConfig.LineValue("listing.quantityLabel",
                Integer.toString(listing.item().quantity()), "text"));
        values.put("listing.seller", new UiConfig.LineValue("listing.sellerLabel",
                sellerName(viewer, listing.sellerUuid()), "muted"));
        boolean own = listing.sellerUuid().equals(viewer.getUUID());
        values.put("listing.action", new UiConfig.LineValue(null,
                confirm ? "Нажмите зелёную кнопку для покупки"
                        : own ? "ЛКМ: снять лот" : "ЛКМ: купить весь лот",
                own ? "warning" : "success"));
        List<Component> lines = UiConfig.lines("listingCard", values);
        appendStoredContents(lines, StoredContents.inspect(exact));
        // Catalogue cards stay compact; the confirmation deliberately shows the
        // exact native NBT tooltip so modded variants can be distinguished before paying.
        return confirm ? GuiItems.decorateMarketItem(exact, lines) : GuiItems.marketDisplay(exact, lines);
    }

    private static void appendStoredContents(List<Component> lines, StoredContents.Inspection contents) {
        if (contents == null || !contents.readable()) return;
        if (contents.hasItems()) {
            lines.add(Component.literal("Содержимое: " + contents.occupiedItemSlots()
                    + " яч., " + contents.itemCount() + " предметов").withStyle(ChatFormatting.AQUA));
        }
        int shown = 0;
        int total = 0;
        for (StoredContents.FluidEntry entry : contents.fluids()) {
            if (entry.fluid().isEmpty() || entry.fluid().getAmount() <= 0) continue;
            total++;
            if (shown++ >= 5) continue;
            Component line = Component.literal("Жидкость: ").withStyle(ChatFormatting.AQUA)
                    .append(entry.fluid().getDisplayName().copy().withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" • " + entry.fluid().getAmount() + " / "
                            + entry.capacity() + " mB").withStyle(ChatFormatting.GRAY));
            lines.add(line);
        }
        if (total > 5) lines.add(Component.literal("Ещё резервуаров: " + (total - 5))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static ItemStack previewArrow(boolean forward, boolean enabled) {
        return GuiItems.namedButton(new ItemStack(Items.ARROW),
                forward ? "Следующая страница" : "Предыдущая страница",
                enabled ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY,
                enabled ? "Нажмите для перехода" : "Других страниц нет");
    }

    private static String sellerName(ServerPlayer viewer, UUID seller) {
        return viewer.getServer().getProfileCache().get(seller)
                .map(profile -> profile.getName()).orElse(seller.toString().substring(0, 8));
    }

    private ItemStack decode(AuctionListing listing) {
        try { return VAuctionCore.instance().codec().decode(listing.item()); }
        catch (ItemCodecException e) { return new ItemStack(Items.BARRIER); }
    }

    private static ItemStack arrow(MarketSession s, boolean forward, boolean enabled) {
        String key = forward ? "next" : "previous";
        return configuredButton(s, key, null,
                enabled ? "Нажмите для перехода" : "Других страниц нет");
    }

    private static ItemStack configuredButton(MarketSession s, String key, String name, String lore) {
        UiConfig.ButtonCfg cfg = UiConfig.button(key);
        String resolvedName = UiConfig.format(name != null ? name : cfg.name(), s.placeholders);
        List<String> resolvedLore = lore != null ? List.of(lore) : cfg.lore();
        List<Component> components = resolvedLore.stream().filter(line -> line != null && !line.isBlank())
                .map(line -> (Component) Component.literal(UiConfig.format(line, s.placeholders)).withStyle(style ->
                        style.withColor(MarketPalette.byKey(cfg.loreColorKey())))).toList();
        return GuiItems.namedButton(new ItemStack(cfg.iconItem()),
                Component.literal(resolvedName).withStyle(style -> style.withColor(MarketPalette.byKey(cfg.colorKey()))),
                components);
    }

    private static int firstFreeControl(MarketSession s, SimpleContainer box, String... keys) {
        for (String key : keys) {
            int slot = UiConfig.slot("catalogue", key);
            if (slot >= 0 && box.getItem(slot).isEmpty() && !s.actions.containsKey(slot)) return slot;
        }
        return -1;
    }

    private static void context(ServerPlayer player, MarketSession s, Page<?> page) {
        s.placeholders.clear();
        s.placeholders.put("player", player.getGameProfile().getName());
        s.placeholders.put("screen", "catalogue");
        s.placeholders.put("category", UiConfig.text(s.filter.textKey));
        s.placeholders.put("search", s.search);
        s.placeholders.put("page", Integer.toString(page.page() + 1));
        s.placeholders.put("pages", Integer.toString(page.totalPages()));
        s.placeholders.put("results", Long.toString(page.totalItems()));
        s.placeholders.put("mode", s.mineOnly ? "Мои лоты" : "Все лоты");
    }

    private void openBox(ServerPlayer player, MarketSession s, SimpleContainer box, String title) {
        int rows = box.getContainerSize() / 9;
        if (s.menu != null && player.containerMenu == s.menu && s.openScreen == s.screen && s.openRows == rows) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) s.contents.setItem(slot, box.getItem(slot));
            fullSync(player, s.menu);
            return;
        }
        s.transitioning = true;
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
                s.containerId = id;
                s.contents = box;
                s.openScreen = s.screen;
                s.openRows = rows;
                s.menu = new ServerChestMenu(id, inventory, box, rows, this, s);
                return s.menu;
            }, Component.literal(title).withStyle(ChatFormatting.GOLD)));
        } finally { s.transitioning = false; }
        if (s.menu != null) fullSync(player, s.menu);
    }

    private static void fullSync(ServerPlayer player, ServerChestMenu menu) {
        player.connection.send(new ClientboundContainerSetContentPacket(menu.containerId,
                menu.incrementStateId(), menu.getItems(), menu.getCarried()));
    }

    private static void put(SimpleContainer box, MarketSession s, int slot, ItemStack item, GuiAction action) {
        if (slot < 0 || slot >= box.getContainerSize()) return;
        box.setItem(slot, item.copy());
        if (action != null) s.actions.put(slot, action);
    }

    private static SimpleAuctionService service() { return VAuctionCore.instance().simpleAuctionService(); }

    private static boolean ready(ServerPlayer player) {
        if (VAuctionCore.instance().isRunning() && service() != null) return true;
        tell(player, "Аукцион ещё не готов или отключён.", ChatFormatting.RED);
        return false;
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    public void closed(UUID playerId, int containerId) {
        MarketSession s = sessions.get(playerId);
        if (s != null && !s.transitioning && s.containerId == containerId) sessions.remove(playerId, s);
    }
    public void logout(UUID playerId) { sessions.remove(playerId); }
    public void clear() { sessions.clear(); }
    public void closeAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ServerChestMenu) player.closeContainer();
        }
        clear();
    }
}
