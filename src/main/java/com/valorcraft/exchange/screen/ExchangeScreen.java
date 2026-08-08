package com.valorcraft.exchange.screen;

import com.valorcraft.exchange.network.ExchangeActionPacket;
import com.valorcraft.exchange.network.ModNetworking;
import com.valorcraft.exchange.network.SyncMarketPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Экран биржи: три вкладки (Лоты / Заявки / Почта) и кнопки действий.
 * Данные приходят пакетом SyncMarketPacket, действия уходят ExchangeActionPacket.
 */
public class ExchangeScreen extends AbstractContainerScreen<ExchangeContainerMenu> {

    private enum Tab {SELLS, BUYS, MAIL}

    private Tab tab = Tab.SELLS;
    private Button tabSells;
    private Button tabBuys;
    private Button tabMail;
    private Button cmdBuy;
    private Button cmdFulfill;
    private Button cmdClaim;

    public ExchangeScreen(ExchangeContainerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 260;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();
        tabSells = addRenderableWidget(Button.builder(Component.literal("Лоты"),
                b -> setTab(Tab.SELLS)).bounds(leftPos + 8, topPos + 10, 70, 18).build());
        tabBuys = addRenderableWidget(Button.builder(Component.literal("Заявки"),
                b -> setTab(Tab.BUYS)).bounds(leftPos + 82, topPos + 10, 70, 18).build());
        tabMail = addRenderableWidget(Button.builder(Component.literal("Моя почта"),
                b -> setTab(Tab.MAIL)).bounds(leftPos + 156, topPos + 10, 70, 18).build());
        addActionButtons();
        requestMarket();
    }

    private void addActionButtons() {
        cmdBuy = addRenderableWidget(Button.builder(Component.literal("Купить x1"),
                b -> buyFromFirstSell()).bounds(leftPos + 180, topPos + 176, 72, 18).build());
        cmdFulfill = addRenderableWidget(Button.builder(Component.literal("Продать x1"),
                b -> fulfillFirstBuy()).bounds(leftPos + 180, topPos + 176, 72, 18).build());
        cmdClaim = addRenderableWidget(Button.builder(Component.literal("Забрать почту"),
                b -> claimMail()).bounds(leftPos + 180, topPos + 176, 72, 18).build());
        refreshButtons();
    }

    private void setTab(Tab newTab) {
        tab = newTab;
        refreshButtons();
        requestMarket();
    }

    private void refreshButtons() {
        cmdBuy.visible = tab == Tab.SELLS;
        cmdFulfill.visible = tab == Tab.BUYS;
        cmdClaim.visible = tab == Tab.MAIL;
    }

    private void requestMarket() {
        ModNetworking.channel().sendToServer(
                new ExchangeActionPacket(ExchangeActionPacket.Action.REQUEST_MARKET, 0, 0, 0, null, ItemStack.EMPTY));
    }

    private void sendAction(ExchangeActionPacket.Action action, UUID orderId, int quantity) {
        ModNetworking.channel().sendToServer(
                new ExchangeActionPacket(action, 0, 0, quantity, orderId, ItemStack.EMPTY));
        requestMarket();
    }

    private void buyFromFirstSell() {
        SyncMarketPacket market = ExchangeScreenClient.current();
        if (market != null && !market.sellOrders().isEmpty()) {
            sendAction(ExchangeActionPacket.Action.BUY_FROM_SELL, market.sellOrders().get(0).id, 1);
        }
    }

    private void fulfillFirstBuy() {
        SyncMarketPacket market = ExchangeScreenClient.current();
        if (market != null && !market.buyOrders().isEmpty()) {
            sendAction(ExchangeActionPacket.Action.FULFILL_BUY, market.buyOrders().get(0).id, 1);
        }
    }

    private void claimMail() {
        sendAction(ExchangeActionPacket.Action.CLAIM_MAILBOX, null, 0);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1B1B2F);
        graphics.fill(leftPos + 4, topPos + 34, leftPos + imageWidth - 4, topPos + imageHeight - 30, 0xFF26263F);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        SyncMarketPacket market = ExchangeScreenClient.current();
        if (market != null) {
            drawMarket(graphics, market);
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    private void drawMarket(GuiGraphics graphics, SyncMarketPacket market) {
        int y0 = topPos + 44;
        switch (tab) {
            case SELLS -> {
                List<SyncMarketPacket.SellEntry> sells = market.sellOrders();
                for (int i = 0; i < Math.min(sells.size(), 6); i++) {
                    SyncMarketPacket.SellEntry e = sells.get(i);
                    int y = y0 + i * 24;
                    graphics.renderItem(e.item, leftPos + 12, y);
                    graphics.drawString(font, e.item.getHoverName().getString() + "  x" + e.remaining,
                            leftPos + 34, y + 1, 0xFFFFFFFF);
                    graphics.drawString(font, "Цена: " + e.price + " за шт", leftPos + 34, y + 11, 0xFFCCCCCC);
                }
            }
            case BUYS -> {
                List<SyncMarketPacket.BuyEntry> buys = market.buyOrders();
                for (int i = 0; i < Math.min(buys.size(), 6); i++) {
                    SyncMarketPacket.BuyEntry e = buys.get(i);
                    int y = y0 + i * 24;
                    graphics.renderItem(e.item, leftPos + 12, y);
                    graphics.drawString(font, e.item.getHoverName().getString() + "  x" + e.total,
                            leftPos + 34, y + 1, 0xFFFFFFFF);
                    graphics.drawString(font, "Исполнено " + e.fulfilled + "; цена " + e.price,
                            leftPos + 34, y + 11, 0xFFCCCCCC);
                }
            }
            case MAIL -> {
                List<ItemStack> mail = market.mailbox();
                graphics.drawString(font,
                        mail.isEmpty() ? "Почта пуста." : "Почта: " + mail.size() + " стаков",
                        leftPos + 12, y0, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}