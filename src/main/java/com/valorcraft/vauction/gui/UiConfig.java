package com.valorcraft.vauction.gui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Поверхностный конфиг интерфейса биржи: цвета, тексты, строки лора, кнопки.
 * Файл {@code config/vauction-ui.json} правится без пересборки и применяется
 * командой {@code /ah admin reloadui} (старый {@code /ah ui reload} тоже работает).
 * Любая секция и любой ключ опциональны:
 * недостающие значения берутся из встроенных русских значений по умолчанию.
 * <p>
 * Строки лора задаются токенами:
 * <ul>
 *   <li>{@code "divider"} — строка-разделитель перед нативным tooltip;</li>
 *   <li>{@code "empty"} — пустая строка;</li>
 *   <li>{@code "title:key"} — жирный заголовок (текст из значений блока);</li>
 *   <li>{@code "value:key"} — строка «метка: значение» либо просто значение;</li>
 *   <li>{@code "text:key"} — строка из texts, цвет через {@code @имя};</li>
 *   <li>{@code "muted:key"} — приглушённая строка из texts.</li>
 * </ul>
 */
public final class UiConfig {
    private static final Logger LOGGER = LogManager.getLogger("VAuction/UI");

    private UiConfig() {}

    /** Одна строка лора: метка может отсутствовать (тогда строка без «: »). */
    record LineValue(String labelKey, String text, String colorKey) {}

    /** Кнопка: иконка + ключи имени и подписи (nameKey может отсутствовать, имя задаётся кодом). */
    record ButtonCfg(Item iconItem, String nameKey, String loreKey,
                     String name, List<String> lore, String colorKey, String loreColorKey) {
        ButtonCfg(Item iconItem, String nameKey, String loreKey) {
            this(iconItem, nameKey, loreKey, null, List.of(), "brand", "muted");
        }

        ButtonCfg {
            lore = lore == null ? List.of() : List.copyOf(lore);
            colorKey = colorKey == null || colorKey.isBlank() ? "brand" : colorKey;
            loreColorKey = loreColorKey == null || loreColorKey.isBlank() ? "muted" : loreColorKey;
        }
    }

    private static volatile Path file;

    private static final LinkedHashMap<String, String> TEXTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> LORE = new LinkedHashMap<>();
    private static final LinkedHashMap<String, ButtonCfg> BUTTONS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> LAYOUTS = new LinkedHashMap<>();

    private static volatile LinkedHashMap<String, String> texts = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, List<String>> lore = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, ButtonCfg> buttons = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> layouts = new LinkedHashMap<>();

    static {
        TEXTS.put("brand", "◆ Биржа ValorCraft");
        TEXTS.put("window.title", "Биржа ValorCraft");
        TEXTS.put("window.catalogue", "Биржа: каталог");
        TEXTS.put("window.search", "Биржа: поиск");
        TEXTS.put("window.categories", "Биржа: разделы");
        TEXTS.put("window.product", "Биржа: товар");
        TEXTS.put("window.buy", "Биржа: покупка");
        TEXTS.put("window.sell", "Биржа: продажа");
        TEXTS.put("window.buyOrder", "Заявка на покупку");
        TEXTS.put("window.sellOrder", "Заявка на продажу");
        TEXTS.put("window.priceWarning", "Биржа: проверьте цену");
        TEXTS.put("window.my", "Биржа: мои сделки");
        TEXTS.put("window.manage", "Биржа: заявка");
        TEXTS.put("window.cancel", "Биржа: отмена заявки");

        TEXTS.put("card.buy", "Купить");
        TEXTS.put("card.sell", "Продать");
        TEXTS.put("card.trade", "Сделка");
        TEXTS.put("card.unavailable", "нет предложений");
        TEXTS.put("card.dash", "—");
        TEXTS.put("catalog.buy", "Купить сейчас");
        TEXTS.put("catalog.sell", "Продать сейчас");
        TEXTS.put("catalog.open", "Нажмите, чтобы открыть товар");

        TEXTS.put("product.title", "Выберите действие");
        TEXTS.put("product.buy", "Цена покупки");
        TEXTS.put("product.sell", "Цена продажи");
        TEXTS.put("product.last", "Последняя сделка");
        TEXTS.put("product.available", "У вас");
        TEXTS.put("product.buyNow", "Купить сейчас");
        TEXTS.put("product.sellNow", "Продать сейчас");
        TEXTS.put("product.buyOrder", "Заявка на покупку");
        TEXTS.put("product.sellOrder", "Заявка на продажу");
        TEXTS.put("product.nowAt", "Текущая цена: {price} за штуку");
        TEXTS.put("product.orderLore", "Сейчас предложений нет. Укажите свою цену");
        TEXTS.put("product.sellDisabled", "Продажа недоступна");
        TEXTS.put("product.sellDisabledLore", "В инвентаре нет точно такого предмета");

        TEXTS.put("filter.all", "Все товары");
        TEXTS.put("filter.resources", "Ресурсы");
        TEXTS.put("filter.food", "Еда");
        TEXTS.put("filter.tools", "Инструменты");
        TEXTS.put("filter.machines", "Механизмы");
        TEXTS.put("filter.open", "Показать эту категорию");
        TEXTS.put("filter.active", "Категория выбрана");
        TEXTS.put("filter.reset", "Нажмите, чтобы показать всё");
        TEXTS.put("filter.title", "Разделы каталога");
        TEXTS.put("filter.titleLore", "Выберите, какие товары показать");

        TEXTS.put("instant.actionBuy", "ПОКУПКА");
        TEXTS.put("instant.actionSell", "ПРОДАЖА");
        TEXTS.put("instant.price", "Цена");
        TEXTS.put("instant.quantity", "Кол-во");
        TEXTS.put("instant.partial", "Частично");
        TEXTS.put("instant.totalBuy", "Итого");
        TEXTS.put("instant.totalSell", "Получите");
        TEXTS.put("instant.worstBuy", "До");
        TEXTS.put("instant.worstSell", "Не ниже");
        TEXTS.put("instant.offers", "Нет предложений");
        TEXTS.put("instant.buyNow", "✓ Купить сейчас");
        TEXTS.put("instant.sellNow", "✓ Продать сейчас");
        TEXTS.put("instant.buyNowLore", "Не дороже цены");
        TEXTS.put("instant.sellNowLore", "Не дешевле цены");
        TEXTS.put("instant.ownPrice", "Своя цена");
        TEXTS.put("instant.ownPriceLore", "Ждать по своей цене");

        TEXTS.put("editor.actionBuy", "ЗАЯВКА НА ПОКУПКУ");
        TEXTS.put("editor.actionSell", "ЗАЯВКА НА ПРОДАЖУ");
        TEXTS.put("editor.quantity", "Кол-во");
        TEXTS.put("editor.price", "Цена");
        TEXTS.put("editor.reserve", "Резерв");
        TEXTS.put("editor.sum", "Сумма");
        TEXTS.put("editor.available", "Доступно");
        TEXTS.put("editor.reserveNote", "Деньги резервируются");
        TEXTS.put("editor.changePrice", "Изменить цену");
        TEXTS.put("editor.changePriceLore", "Своя цена");
        TEXTS.put("editor.submit", "Выставить");
        TEXTS.put("editor.submitLore", "Заявка по указанной цене");
        TEXTS.put("editor.submitBuy", "Разместить заявку на покупку");
        TEXTS.put("editor.submitSell", "Разместить заявку на продажу");
        TEXTS.put("editor.submitSummary", "{quantity} шт. по {price}; всего {total}");
        TEXTS.put("editor.priceButton", "Цена: {price}");
        TEXTS.put("editor.priceButtonLore", "Нажмите, чтобы ввести другую цену");

        TEXTS.put("button.back", "Назад");
        TEXTS.put("button.backLore", "Каталог");
        TEXTS.put("button.my", "Моё");
        TEXTS.put("button.search", "Поиск");
        TEXTS.put("button.searchLore", "Искать по названию");
        TEXTS.put("button.categories", "Разделы");
        TEXTS.put("button.categoriesCurrent", "Сейчас: {category}");
        TEXTS.put("button.newSearch", "Новый поиск");
        TEXTS.put("button.newSearchLore", "Уточнить запрос");
        TEXTS.put("button.catalogue", "Каталог");
        TEXTS.put("button.catalogueLore", "Все товары");
        TEXTS.put("button.allGoods", "Все товары");
        TEXTS.put("button.allGoodsLore", "Вернуться к каталогу");
        TEXTS.put("button.prev", "← Предыдущая");
        TEXTS.put("button.next", "Следующая →");
        TEXTS.put("button.quantityAll", "Всё");
        TEXTS.put("button.quantityAllLore", "Всё что есть");
        TEXTS.put("button.quantityOther", "Другое");
        TEXTS.put("button.quantityOtherLore", "Своё количество");
        TEXTS.put("button.quantityPresetLore", "Установить количество");
        TEXTS.put("button.manageCancel", "Отменить заявку");
        TEXTS.put("button.manageCancelLore", "Остаток будет возвращён");
        TEXTS.put("button.manageBack", "Назад");
        TEXTS.put("button.manageBackLore", "Моё");
        TEXTS.put("button.cancelNo", "Назад");
        TEXTS.put("button.cancelNoLore", "Не отменять");
        TEXTS.put("button.cancelYes", "✕ Отменить заявку");
        TEXTS.put("button.cancelYesLore", "Остаток будет возвращён");
        TEXTS.put("button.warningChange", "Изменить");
        TEXTS.put("button.warningChangeLore", "Вернуться к параметрам");
        TEXTS.put("button.warningConfirm", "✓ Всё равно");
        TEXTS.put("button.warningConfirmLore", "Цена останется без изменений");

        TEXTS.put("nav.ordersHint", "Заявки, покупки и возвраты");
        TEXTS.put("nav.infoTitle", "Биржа");
        TEXTS.put("nav.myTitle", "Моё");
        TEXTS.put("nav.openHint", "Нажмите на товар, чтобы открыть");
        TEXTS.put("nav.page", "Страница");

        TEXTS.put("my.claimTitle", "Готово к получению");
        TEXTS.put("my.purchase", "Покупка");
        TEXTS.put("my.refund", "Возврат");
        TEXTS.put("my.claimHint", "ЛКМ: забрать");
        TEXTS.put("my.sell", "Продажа");
        TEXTS.put("my.manual", "Нужна проверка");
        TEXTS.put("my.partial", "Частично: ");
        TEXTS.put("my.waitBuy", "Ждёт покупателя");
        TEXTS.put("my.waitSell", "Ждёт продавца");
        TEXTS.put("my.manageHint", "ЛКМ: управление");
        TEXTS.put("my.awaiting", "Ожидает проверки");
        TEXTS.put("my.rowQuantity", "Кол-во");
        TEXTS.put("my.rowPrice", "Цена");
        TEXTS.put("my.rowLeft", "Осталось");
        TEXTS.put("my.emptyTitle", "Здесь пока пусто.");
        TEXTS.put("my.emptyLine1", "Активные заявки и покупки");
        TEXTS.put("my.emptyLine2", "появятся здесь.");

        TEXTS.put("manage.price", "Цена");
        TEXTS.put("manage.left", "Осталось");

        TEXTS.put("cancel.title", "Отменить заявку?");
        TEXTS.put("cancel.body", "Остаток вернётся в «Моё».");

        TEXTS.put("warning.title", "⚠ Проверьте цену");
        TEXTS.put("warning.market", "Рынок: ~");
        TEXTS.put("warning.mine", "Ваша цена: ");

        TEXTS.put("empty.catalogTitle", "На бирже пока нет товаров.");
        TEXTS.put("empty.createFirst", "Создайте первую заявку:");
        TEXTS.put("empty.sellBuy", "/ah buy или /ah sell");
        TEXTS.put("empty.searchTitle", "◆ Ничего не найдено");
        TEXTS.put("empty.searchBody", "Товар пока не торгуется.");
        TEXTS.put("empty.filterTitle", "В этой категории пока пусто");
        TEXTS.put("empty.filterBody", "Выберите другую категорию или откройте все товары.");

        TEXTS.put("bar.bought", "Куплено: {q} шт. за {a}");
        TEXTS.put("bar.sold", "Продано: {q} шт. за {a}");
        TEXTS.put("bar.orderPending", "Заявка обрабатывается");
        TEXTS.put("bar.orderFilled", "Заявка исполнена");
        TEXTS.put("bar.orderCreated", "Заявка создана");
        TEXTS.put("bar.orderPartialBuy", "Куплено: {q} · осталось {r}");
        TEXTS.put("bar.orderPartialSell", "Продано: {q} · осталось {r}");
        TEXTS.put("bar.offersGone", "Нет предложений");
        TEXTS.put("bar.noMoney", "Не хватает денег");
        TEXTS.put("bar.noItems", "Не хватает предметов");
        TEXTS.put("bar.failed", "Не получилось");
        TEXTS.put("bar.claim", "Получено из биржи");
        TEXTS.put("bar.cancelled", "Заявка отменена");

        TEXTS.put("chat.immediateTooBig", "Сумма слишком велика. Уменьшите количество.");
        TEXTS.put("chat.orderTooBig", "Сумма слишком велика. Уменьшите цену или количество.");
        TEXTS.put("chat.buyNeeds", "Нужно до: {need}, доступно: {have}. Уменьшите количество.");
        TEXTS.put("chat.fundsNeeded", "Нужно: {need}, доступно: {have}. Уменьшите цену или количество.");
        TEXTS.put("chat.itemsNeeded", "Нужно: {need}, у вас: {have}. Уменьшите количество.");
        TEXTS.put("chat.boughtPlace", "{item} — в «Моём».");
        TEXTS.put("chat.soldLeft", "Неисполненный остаток — в «Моём».");
        TEXTS.put("chat.noItemFull", "В инвентаре нет точно такого предмета. Возьмите его в руку или выберите другой рынок.");
        TEXTS.put("chat.noItem", "В инвентаре нет точно такого предмета.");
        TEXTS.put("chat.noItemDraft", "В инвентаре больше нет точно такого предмета.");
        TEXTS.put("chat.noMarket", "Этот предмет нельзя открыть на бирже.");
        TEXTS.put("chat.noMarketDraft", "Этот предмет больше нельзя открыть на бирже.");
        TEXTS.put("chat.guiDown", "Биржа временно недоступна. Попробуйте ещё раз.");
        TEXTS.put("chat.notReady", "Биржа ещё не готова или отключена.");
        TEXTS.put("chat.searchEmpty", "По запросу «{q}» ничего не найдено.");
        TEXTS.put("chat.accepted", "⏱ Заявка принята и безопасно завершается.");
        TEXTS.put("chat.orderLeftEmpty", "Заявка исполнена полностью.");
        TEXTS.put("chat.orderPartial", "Исполнено {q}, осталось {r}; заявка продолжает ждать.");
        TEXTS.put("chat.orderWaiting", "Создана и ждёт подходящего предложения.");
        TEXTS.put("chat.funds", "Недостаточно средств.");
        TEXTS.put("chat.items", "Недостаточно подходящих предметов.");
        TEXTS.put("chat.inventoryFull", "Освободите место в инвентаре.");
        TEXTS.put("chat.notYours", "Эта запись принадлежит другому игроку.");
        TEXTS.put("chat.orderChanged", "Запись уже изменилась. Список обновлён.");
        TEXTS.put("chat.badPrice", "Проверьте цену и количество.");
        TEXTS.put("chat.overLimit", "Достигнут лимит активных заявок.");
        TEXTS.put("chat.blacklisted", "Этот предмет запрещён на бирже.");
        TEXTS.put("chat.disabled", "Биржа сейчас отключена.");
        TEXTS.put("chat.genericFail", "Операцию не удалось завершить. Попробуйте позже.");
        TEXTS.put("chat.cancelReturn", "Возврат — в «Моём».");
        TEXTS.put("chat.routeChangedBuy", "Рынок изменился: по подтверждённой цене ничего не куплено. Средства освобождены.");
        TEXTS.put("chat.routeChangedSell", "Рынок изменился: по подтверждённой цене ничего не продано. Предметы — в «Моё».");

        TEXTS.put("draft.quantityMsg", "Введите количество:");
        TEXTS.put("draft.priceMsg", "Введите цену за штуку:");
        TEXTS.put("draft.command", "/ah set <число>");
        TEXTS.put("draft.quantityNote", "Сделка откроется заново.");
        TEXTS.put("draft.priceNote", "Заявка откроется заново.");

        TEXTS.put("onboarding.1", "Нажмите на товар, чтобы увидеть цены и доступные действия.");
        TEXTS.put("onboarding.2", "Покупка и продажа теперь выбираются отдельными кнопками.");
        TEXTS.put("onboarding.help", "[Помощь]");
        TEXTS.put("tutorial.1", "Откройте товар и выберите покупку или продажу.");
        TEXTS.put("tutorial.2", "«Своя цена» создаёт заявку, которая будет ждать подходящего предложения.");
        TEXTS.put("tutorial.commands", "[Подробные команды]");
        TEXTS.put("searchHelp.title", "Поиск по названию предмета:");
        TEXTS.put("searchHelp.command", "[/ah search ...]");

        TEXTS.put("ui.reloaded", "Интерфейс биржи обновлён. Откройте /ah заново.");
        TEXTS.put("ui.badJson", "Не удалось применить конфиг интерфейса: {error}.");
        TEXTS.put("quantity.all", "Всё");
        TEXTS.put("quantity.other", "Другое");
        TEXTS.put("quantity.one", "1 шт.");
    }

    static {
        LORE.put("catalogCard", List.of(
                "value:catalog.buy", "value:catalog.sell", "empty", "value:catalog.open"));
        LORE.put("product", List.of(
                "title:product.title", "value:product.buy", "value:product.sell",
                "value:product.last", "value:product.available"));
        LORE.put("tradeNow", List.of(
                "title:instant.action", "value:instant.price", "value:instant.quantity",
                "value:instant.partial", "value:instant.total", "value:instant.worst",
                "value:instant.offers"));
        LORE.put("tradeLimit", List.of(
                "title:editor.action", "value:editor.quantity", "value:editor.price",
                "value:editor.reserve", "value:editor.available", "value:editor.reserveNote"));
        LORE.put("myClaim", List.of(
                "title:my.claimTitle", "value:my.rowQuantity", "value:my.type",
                "text:my.claimHint@success"));
        LORE.put("myOrder", List.of(
                "title:my.side", "value:my.rowPrice", "value:my.rowLeft",
                "value:my.status", "value:my.manageHint"));
        LORE.put("manage", List.of(
                "title:manage.side", "value:manage.price", "value:manage.left"));
        LORE.put("cancel", List.of("title:cancel.title", "text:cancel.body"));
        LORE.put("warning", List.of(
                "title:warning.title", "value:warning.market", "value:warning.mine"));
    }

    static {
        BUTTONS.put("back", new ButtonCfg(Items.ARROW, "button.back", "button.backLore"));
        BUTTONS.put("my", new ButtonCfg(Items.ENDER_CHEST, "button.my", "nav.ordersHint"));
        BUTTONS.put("search", new ButtonCfg(Items.COMPASS, "button.search", "button.searchLore"));
        BUTTONS.put("categories", new ButtonCfg(Items.BOOK, "button.categories", null));
        BUTTONS.put("newSearch", new ButtonCfg(Items.COMPASS, "button.newSearch", "button.newSearchLore"));
        BUTTONS.put("catalogue", new ButtonCfg(Items.CHEST, "button.catalogue", "button.catalogueLore"));
        BUTTONS.put("allGoods", new ButtonCfg(Items.CHEST, "button.allGoods", "button.allGoodsLore"));
        BUTTONS.put("prev", new ButtonCfg(Items.ARROW, "button.prev", null));
        BUTTONS.put("next", new ButtonCfg(Items.ARROW, "button.next", null));
        BUTTONS.put("infoBook", new ButtonCfg(Items.BOOK, null, null));
        BUTTONS.put("ownPrice", new ButtonCfg(Items.CLOCK, null, "instant.ownPriceLore"));
        BUTTONS.put("buyNow", new ButtonCfg(Items.EMERALD, null, "instant.buyNowLore"));
        BUTTONS.put("sellNow", new ButtonCfg(Items.HOPPER, null, "instant.sellNowLore"));
        BUTTONS.put("productBuy", new ButtonCfg(Items.EMERALD, null, null));
        BUTTONS.put("productSell", new ButtonCfg(Items.HOPPER, null, null));
        BUTTONS.put("productSellDisabled", new ButtonCfg(Items.BARRIER,
                "product.sellDisabled", "product.sellDisabledLore"));
        BUTTONS.put("disabledOffers", new ButtonCfg(Items.BARRIER, "instant.offers", null));
        BUTTONS.put("priceInfo", new ButtonCfg(Items.COMPARATOR, "editor.changePrice", "editor.changePriceLore"));
        BUTTONS.put("submitLimit", new ButtonCfg(Items.WRITABLE_BOOK, "editor.submit", "editor.submitLore"));
        BUTTONS.put("quantityAll", new ButtonCfg(Items.BARREL, null, "button.quantityAllLore"));
        BUTTONS.put("quantityOther", new ButtonCfg(Items.WRITABLE_BOOK, null, "button.quantityOtherLore"));
        BUTTONS.put("quantityPreset", new ButtonCfg(Items.PAPER, null, "button.quantityPresetLore"));
        BUTTONS.put("manageBack", new ButtonCfg(Items.ARROW, "button.manageBack", "button.manageBackLore"));
        BUTTONS.put("manageCancel", new ButtonCfg(Items.RED_CONCRETE, "button.manageCancel", "button.manageCancelLore"));
        BUTTONS.put("cancelNo", new ButtonCfg(Items.ARROW, "button.cancelNo", "button.cancelNoLore"));
        BUTTONS.put("cancelYes", new ButtonCfg(Items.RED_CONCRETE, null, "button.cancelYesLore"));
        BUTTONS.put("warningChange", new ButtonCfg(Items.ARROW, "button.warningChange", "button.warningChangeLore"));
        BUTTONS.put("warningConfirm", new ButtonCfg(Items.YELLOW_CONCRETE, null, "button.warningConfirmLore"));
        BUTTONS.put("categoriesHeader", new ButtonCfg(Items.BOOK, "filter.title", "filter.titleLore"));
        BUTTONS.put("filterAll", new ButtonCfg(Items.CHEST, "filter.all", "filter.open"));
        BUTTONS.put("filterResources", new ButtonCfg(Items.BARREL, "filter.resources", "filter.open"));
        BUTTONS.put("filterFood", new ButtonCfg(Items.BOWL, "filter.food", "filter.open"));
        BUTTONS.put("filterTools", new ButtonCfg(Items.ANVIL, "filter.tools", "filter.open"));
        BUTTONS.put("filterMachines", new ButtonCfg(Items.PISTON, "filter.machines", "filter.open"));
        BUTTONS.put("emptyCatalogue", new ButtonCfg(Items.PAPER, "empty.catalogTitle", "empty.createFirst"));
        BUTTONS.put("emptySearch", new ButtonCfg(Items.COMPASS, "empty.searchTitle", "empty.searchBody"));
        BUTTONS.put("emptyFilter", new ButtonCfg(Items.COMPASS, "empty.filterTitle", "empty.filterBody"));
        BUTTONS.put("emptyMy", new ButtonCfg(Items.ENDER_CHEST, "my.emptyTitle", "my.emptyLine1"));
    }

    static {
        layout("catalogue",
                "content", range(0, 45), "empty", 22, "previous", 45,
                "categories", 46, "search", 48, "info", 49, "my", 50, "next", 53);
        layout("search",
                "content", range(0, 45), "empty", 22, "previous", 45,
                "newSearch", 48, "info", 49, "catalogue", 50, "next", 53);
        layout("categories",
                "header", 13, "all", 20, "resources", 21, "food", 22,
                "tools", 23, "machines", 24, "back", 45);
        layout("product", "item", 22, "back", 45, "buy", 48, "sell", 50);
        layout("immediate",
                "item", 13, "quantityOne", 21, "quantityBulk", 22, "quantityOther", 23,
                "back", 45, "ownPrice", 47, "confirm", 49);
        layout("limit",
                "item", 13, "quantityOne", 21, "quantityBulk", 22, "quantityOther", 23,
                "price", 31, "back", 45, "submit", 49);
        layout("my",
                "content", range(0, 45), "empty", 22, "previous", 45,
                "info", 49, "catalogue", 50, "next", 53);
        layout("manage", "item", 22, "back", 45, "cancel", 49);
    }

    /** Старт на загрузке сервера: задаём путь и применяем файл (или создаём дефолтный). */
    public static void start(Path configDir) {
        file = configDir.resolve("vauction-ui.json");
        reload();
    }

    /** Применяет файл конфига. Возвращает null при успехе или текст ошибки. */
    static String reload() {
        if (file == null) return "ui config: file is not initialised";
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, defaultJson(), StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject defaults = JsonParser.parseString(defaultJson()).getAsJsonObject();
            boolean upgraded = mergeMissing(root, defaults);
            LinkedHashMap<String, String> t = new LinkedHashMap<>(TEXTS);
            JsonObject jt = obj(root, "texts");
            if (jt != null) t.putAll(stringsOf(jt));
            LinkedHashMap<String, List<String>> l = new LinkedHashMap<>(LORE);
            JsonObject jl = obj(root, "lore");
            if (jl != null) l.putAll(loreOf(jl));
            LinkedHashMap<String, ButtonCfg> b = new LinkedHashMap<>(BUTTONS);
            JsonObject jb = obj(root, "buttons");
            if (jb != null) b.putAll(buttonsOf(jb));
            validateButtons(b);
            LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> ly = copyLayouts(LAYOUTS);
            JsonObject jLayouts = obj(root, "layouts");
            if (jLayouts != null) applyLayouts(ly, jLayouts);
            validateLayouts(ly);
            LinkedHashMap<String, String> colors = stringsOf(obj(root, "colors"));
            validateColors(colors);

            // Publish one complete immutable-enough snapshot only after every section
            // has parsed and validated. A bad reload leaves the previous UI active.
            texts = t;
            lore = l;
            buttons = b;
            layouts = ly;
            MarketPalette.replace(colors);
            if (upgraded) {
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                        .create().toJson(root), StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Cannot reload {}: {}", file, e.getMessage());
            return e.getMessage();
        }
    }

    private static String defaultJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        root.addProperty("help", "Слоты сундука: 0..53. layouts меняет только расположение; названия действий менять нельзя.");
        JsonObject colors = new JsonObject();
        for (Map.Entry<String, String> e : MarketPalette.DEFAULT_COLORS.entrySet()) {
            colors.addProperty(e.getKey(), e.getValue());
        }
        root.add("colors", colors);
        JsonObject texts = new JsonObject();
        for (Map.Entry<String, String> e : TEXTS.entrySet()) texts.addProperty(e.getKey(), e.getValue());
        root.add("texts", texts);
        JsonObject lore = new JsonObject();
        for (Map.Entry<String, List<String>> e : LORE.entrySet()) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (String token : e.getValue()) arr.add(token);
            lore.add(e.getKey(), arr);
        }
        root.add("lore", lore);
        JsonObject buttons = new JsonObject();
        for (Map.Entry<String, ButtonCfg> e : BUTTONS.entrySet()) {
            JsonObject b = new JsonObject();
            b.addProperty("icon", BuiltInRegistries.ITEM.getKey(e.getValue().iconItem()).toString());
            if (e.getValue().nameKey() != null) {
                b.addProperty("nameKey", e.getValue().nameKey());
                b.addProperty("name", TEXTS.getOrDefault(e.getValue().nameKey(), e.getValue().nameKey()));
            }
            if (e.getValue().loreKey() != null) {
                b.addProperty("loreKey", e.getValue().loreKey());
                com.google.gson.JsonArray directLore = new com.google.gson.JsonArray();
                directLore.add(TEXTS.getOrDefault(e.getValue().loreKey(), e.getValue().loreKey()));
                b.add("lore", directLore);
            }
            b.addProperty("color", e.getValue().colorKey());
            b.addProperty("loreColor", e.getValue().loreColorKey());
            buttons.add(e.getKey(), b);
        }
        root.add("buttons", buttons);
        root.add("layouts", layoutsJson(LAYOUTS));
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    /** Строка по ключу с сопоставлением {placeholder} → значение. */
    static String fmt(String key, Object... placeholders) {
        String v = text(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            v = v.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return v;
    }

    static String text(String key) {
        String v = texts.get(key);
        return v == null ? TEXTS.getOrDefault(key, key) : v;
    }

    static List<String> loreTokens(String block) {
        List<String> list = lore.get(block);
        return list == null ? LORE.getOrDefault(block, List.of()) : list;
    }

    static ButtonCfg button(String key) {
        ButtonCfg cfg = buttons.get(key);
        return cfg == null ? BUTTONS.getOrDefault(key, new ButtonCfg(Items.BARRIER, null, null)) : cfg;
    }

    static int slot(String screen, String key) {
        List<Integer> values = layoutValues(screen, key);
        if (values.size() != 1) throw new IllegalStateException("UI slot is not scalar: " + screen + "." + key);
        return values.get(0);
    }

    static int[] slots(String screen, String key) {
        return layoutValues(screen, key).stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<Integer> layoutValues(String screen, String key) {
        LinkedHashMap<String, List<Integer>> current = layouts.get(screen);
        if (current == null) current = LAYOUTS.get(screen);
        List<Integer> values = current == null ? null : current.get(key);
        if (values == null && LAYOUTS.containsKey(screen)) values = LAYOUTS.get(screen).get(key);
        if (values == null) throw new IllegalArgumentException("Unknown UI layout key: " + screen + "." + key);
        return values;
    }

    private static Item itemOf(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) throw new IllegalArgumentException("Invalid button item id: " + id);
        return BuiltInRegistries.ITEM.getOptional(loc)
                .orElseThrow(() -> new IllegalArgumentException("Unknown button item: " + id));
    }

    /**
     * Собирает строки лора блока по токенам шаблона. Значения, которых нет в
     * контексте, пропускаются — так шаблон описывает максимум, а код решает,
     * какие строки реально показать (частичное исполнение, доступность и т.п.).
     */
    static List<Component> lines(String block, LinkedHashMap<String, LineValue> values) {
        List<Component> out = new ArrayList<>();
        for (String token : loreTokens(block)) {
            if (token.equals("empty")) {
                out.add(Component.empty());
            } else if (token.equals("divider")) {
                out.add(MarketText.divider());
            } else if (token.startsWith("title:")) {
                LineValue v = values.get(token.substring(6));
                if (v != null && v.text() != null && !v.text().isEmpty()) {
                    out.add(MarketText.action(v.text(), MarketPalette.byKey(v.colorKey())));
                }
            } else if (token.startsWith("value:")) {
                LineValue v = values.get(token.substring(6));
                if (v != null && v.text() != null && !v.text().isEmpty()) {
                    out.add(v.labelKey() == null
                            ? MarketText.colored(v.text(), MarketPalette.byKey(v.colorKey()))
                            : MarketText.labelValue(text(v.labelKey()), v.text(), MarketPalette.byKey(v.colorKey())));
                }
            } else if (token.startsWith("text:")) {
                String[] parts = token.substring(5).split("@", 2);
                out.add(MarketText.colored(text(parts[0]), MarketPalette.byKey(parts.length > 1 ? parts[1] : "text")));
            } else if (token.startsWith("muted:")) {
                out.add(MarketText.muted(text(token.substring(6))));
            }
        }
        return out;
    }

    private static JsonObject obj(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null;
    }

    private static LinkedHashMap<String, String> stringsOf(JsonObject o) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (o == null) return m;
        for (String k : o.keySet()) {
            JsonElement e = o.get(k);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("UI value must be a string: " + k);
            }
            m.put(k, e.getAsString());
        }
        return m;
    }

    private static LinkedHashMap<String, List<String>> loreOf(JsonObject o) {
        LinkedHashMap<String, List<String>> m = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            if (!o.get(k).isJsonArray()) {
                throw new IllegalArgumentException("UI lore must be an array: " + k);
            }
            ArrayList<String> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray(k)) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) list.add(e.getAsString());
            }
            m.put(k, list);
        }
        return m;
    }

    private static LinkedHashMap<String, ButtonCfg> buttonsOf(JsonObject o) {
        LinkedHashMap<String, ButtonCfg> m = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            if (!o.get(k).isJsonObject()) {
                throw new IllegalArgumentException("UI button must be an object: " + k);
            }
            if (!BUTTONS.containsKey(k)) {
                throw new IllegalArgumentException("Unknown UI button: " + k);
            }
            JsonObject b = o.getAsJsonObject(k);
            ButtonCfg base = BUTTONS.getOrDefault(k, new ButtonCfg(Items.BARRIER, null, null));
            Item icon = b.has("icon") && b.get("icon").isJsonPrimitive()
                    && b.get("icon").getAsJsonPrimitive().isString()
                    ? itemOf(b.get("icon").getAsString()) : base.iconItem();
            String nameKey = strOrNull(b, "nameKey");
            String loreKey = strOrNull(b, "loreKey");
            String name = strOrNull(b, "name");
            boolean hasDirectLore = b.has("lore");
            List<String> directLore = stringArrayOrEmpty(b, "lore");
            String color = strOrNull(b, "color");
            String loreColor = strOrNull(b, "loreColor");
            m.put(k, new ButtonCfg(icon,
                    nameKey == null ? base.nameKey() : nameKey,
                    loreKey == null ? base.loreKey() : loreKey,
                    name == null ? base.name() : name,
                    hasDirectLore ? directLore : base.lore(),
                    color == null ? base.colorKey() : color,
                    loreColor == null ? base.loreColorKey() : loreColor));
        }
        return m;
    }

    private static List<String> stringArrayOrEmpty(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        ArrayList<String> result = new ArrayList<>();
        for (JsonElement element : o.getAsJsonArray(key)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("buttons." + key + " must contain strings");
            }
            result.add(element.getAsString());
        }
        return result;
    }

    private static void layout(String screen, Object... pairs) {
        LinkedHashMap<String, List<Integer>> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value instanceof Integer integer) values.put(key, List.of(integer));
            else if (value instanceof List<?> list) {
                ArrayList<Integer> slots = new ArrayList<>();
                for (Object entry : list) slots.add((Integer) entry);
                values.put(key, List.copyOf(slots));
            } else throw new IllegalArgumentException("Unsupported layout value " + value);
        }
        LAYOUTS.put(screen, values);
    }

    private static List<Integer> range(int startInclusive, int endExclusive) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int i = startInclusive; i < endExclusive; i++) result.add(i);
        return List.copyOf(result);
    }

    private static LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> copyLayouts(
            Map<String, ? extends Map<String, List<Integer>>> source) {
        LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> result = new LinkedHashMap<>();
        source.forEach((screen, values) -> {
            LinkedHashMap<String, List<Integer>> copy = new LinkedHashMap<>();
            values.forEach((key, slots) -> copy.put(key, List.copyOf(slots)));
            result.put(screen, copy);
        });
        return result;
    }

    private static void applyLayouts(LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> target,
                                     JsonObject configured) {
        for (String screen : configured.keySet()) {
            if (!target.containsKey(screen)) {
                throw new IllegalArgumentException("Unknown UI screen in layouts: " + screen);
            }
            if (!configured.get(screen).isJsonObject()) {
                throw new IllegalArgumentException("layouts." + screen + " must be an object");
            }
            JsonObject values = configured.getAsJsonObject(screen);
            for (String key : values.keySet()) {
                if (!target.get(screen).containsKey(key)) {
                    throw new IllegalArgumentException("Unknown UI slot: " + screen + "." + key);
                }
                JsonElement element = values.get(key);
                ArrayList<Integer> parsed = new ArrayList<>();
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    parsed.add(element.getAsInt());
                } else if (element.isJsonArray()) {
                    for (JsonElement slot : element.getAsJsonArray()) {
                        if (!slot.isJsonPrimitive() || !slot.getAsJsonPrimitive().isNumber()) {
                            throw new IllegalArgumentException("UI slot list must contain numbers: " + screen + "." + key);
                        }
                        parsed.add(slot.getAsInt());
                    }
                } else {
                    throw new IllegalArgumentException("UI slot must be a number or array: " + screen + "." + key);
                }
                target.get(screen).put(key, List.copyOf(parsed));
            }
        }
    }

    private static void validateLayouts(Map<String, ? extends Map<String, List<Integer>>> configured) {
        for (Map.Entry<String, ? extends Map<String, List<Integer>>> screen : configured.entrySet()) {
            Set<Integer> content = new java.util.HashSet<>();
            Set<Integer> controls = new java.util.HashSet<>();
            Integer emptySlot = null;
            for (Map.Entry<String, List<Integer>> entry : screen.getValue().entrySet()) {
                List<Integer> values = entry.getValue();
                boolean list = "content".equals(entry.getKey());
                if (values.isEmpty() || (!list && values.size() != 1) || (list && values.size() > 45)) {
                    throw new IllegalArgumentException("Invalid UI slot count: " + screen.getKey() + "." + entry.getKey());
                }
                Set<Integer> local = new java.util.HashSet<>();
                for (int slot : values) {
                    if (slot < 0 || slot >= 54) {
                        throw new IllegalArgumentException("UI slot outside 0..53: " + screen.getKey() + "." + entry.getKey());
                    }
                    if (!local.add(slot)) {
                        throw new IllegalArgumentException("Duplicate UI slot " + slot + " in " + screen.getKey() + "." + entry.getKey());
                    }
                    if (list) content.add(slot);
                    else if ("empty".equals(entry.getKey())) emptySlot = slot;
                    else if (!controls.add(slot)) throw new IllegalArgumentException(
                            "UI slot " + slot + " is used twice on screen " + screen.getKey());
                }
            }
            for (int control : controls) {
                if (content.contains(control)) throw new IllegalArgumentException(
                        "UI control slot " + control + " overlaps content on screen " + screen.getKey());
            }
            if (emptySlot != null && controls.contains(emptySlot)) throw new IllegalArgumentException(
                    "UI empty-state slot " + emptySlot + " overlaps a control on screen " + screen.getKey());
        }
    }

    private static void validateButtons(Map<String, ButtonCfg> configured) {
        for (Map.Entry<String, ButtonCfg> entry : configured.entrySet()) {
            ButtonCfg button = entry.getValue();
            if (!MarketPalette.DEFAULT_COLORS.containsKey(button.colorKey())) {
                throw new IllegalArgumentException("Unknown button color for " + entry.getKey() + ": " + button.colorKey());
            }
            if (!MarketPalette.DEFAULT_COLORS.containsKey(button.loreColorKey())) {
                throw new IllegalArgumentException("Unknown button loreColor for " + entry.getKey() + ": " + button.loreColorKey());
            }
        }
    }

    private static void validateColors(Map<String, String> colors) {
        for (Map.Entry<String, String> color : colors.entrySet()) {
            if (!MarketPalette.DEFAULT_COLORS.containsKey(color.getKey())) {
                throw new IllegalArgumentException("Unknown UI color: " + color.getKey());
            }
            if (!color.getValue().matches("#?[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("Invalid UI color " + color.getKey() + ": " + color.getValue());
            }
        }
    }

    private static JsonObject layoutsJson(Map<String, ? extends Map<String, List<Integer>>> source) {
        JsonObject result = new JsonObject();
        source.forEach((screen, values) -> {
            JsonObject object = new JsonObject();
            values.forEach((key, slots) -> {
                if (slots.size() == 1 && !"content".equals(key)) {
                    object.addProperty(key, slots.get(0));
                } else {
                    com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                    slots.forEach(array::add);
                    object.add(key, array);
                }
            });
            result.add(screen, object);
        });
        return result;
    }

    /** Recursively adds new default keys without overwriting administrator changes. */
    private static boolean mergeMissing(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (String key : defaults.keySet()) {
            if (!target.has(key)) {
                target.add(key, defaults.get(key).deepCopy());
                changed = true;
            } else if (target.get(key).isJsonObject() && defaults.get(key).isJsonObject()) {
                changed |= mergeMissing(target.getAsJsonObject(key), defaults.getAsJsonObject(key));
            }
        }
        return changed;
    }

    private static String strOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString();
        return null;
    }
}
