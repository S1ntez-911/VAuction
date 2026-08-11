package com.valorcraft.vauction.gui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.valorcraft.vauction.config.VAuctionConfigPaths;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * Файлы {@code config/VMods/VAuction/ui/*.json} правятся без пересборки и применяются
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

    /** Vanilla chest screen: 1..6 rows, custom title with contextual placeholders. */
    record ScreenCfg(int rows, String title) {}

    /** Non-clickable configurable item rendered only into currently empty slots. */
    record DecorationCfg(boolean enabled, boolean fillEmpty, List<Integer> slots, Item iconItem,
                         int count, String name, List<String> lore,
                         String colorKey, String loreColorKey) {
        DecorationCfg {
            slots = slots == null ? List.of() : List.copyOf(slots);
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

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

    private static volatile Path directory;
    private static volatile Path legacyFile;

    private static final LinkedHashMap<String, List<String>> UI_FILES = new LinkedHashMap<>();

    private static final LinkedHashMap<String, String> TEXTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> LORE = new LinkedHashMap<>();
    private static final LinkedHashMap<String, ButtonCfg> BUTTONS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> LAYOUTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, ScreenCfg> SCREENS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> PLACEHOLDER_HELP = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> SCREEN_PLACEHOLDERS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, LinkedHashMap<String, DecorationCfg>> DECORATIONS = new LinkedHashMap<>();

    private static volatile LinkedHashMap<String, String> texts = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, List<String>> lore = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, ButtonCfg> buttons = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> layouts = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, ScreenCfg> screens = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, LinkedHashMap<String, DecorationCfg>> decorations = new LinkedHashMap<>();

    static {
        UI_FILES.put("screens.json", List.of("placeholderHelp", "screens", "layouts"));
        UI_FILES.put("buttons.json", List.of("buttons"));
        UI_FILES.put("decorations.json", List.of("decorations"));
        UI_FILES.put("texts.json", List.of("texts"));
        UI_FILES.put("cards.json", List.of("lore"));
        UI_FILES.put("colors.json", List.of("colors"));

        PLACEHOLDER_HELP.put("player", "Имя игрока");
        PLACEHOLDER_HELP.put("screen", "Ключ текущего экрана");
        PLACEHOLDER_HELP.put("item", "Название выбранного предмета");
        PLACEHOLDER_HELP.put("side", "Покупка или продажа");
        PLACEHOLDER_HELP.put("quantity", "Выбранное количество");
        PLACEHOLDER_HELP.put("price", "Цена за одну штуку");
        PLACEHOLDER_HELP.put("total", "Итоговая сумма");
        PLACEHOLDER_HELP.put("available", "Доступное количество предмета");
        PLACEHOLDER_HELP.put("buy_price", "Лучшая цена продажи на рынке");
        PLACEHOLDER_HELP.put("sell_price", "Лучшая цена покупки на рынке");
        PLACEHOLDER_HELP.put("last_price", "Цена последней сделки");
        PLACEHOLDER_HELP.put("requested", "Запрошенное количество");
        PLACEHOLDER_HELP.put("fillable", "Количество, доступное для мгновенной сделки");
        PLACEHOLDER_HELP.put("worst_price", "Предельная цена мгновенной сделки");
        PLACEHOLDER_HELP.put("market_price", "Ориентир текущего рынка");
        PLACEHOLDER_HELP.put("category", "Текущий раздел каталога");
        PLACEHOLDER_HELP.put("search", "Текущий поисковый запрос");
        PLACEHOLDER_HELP.put("page", "Текущая страница, начиная с 1");
        PLACEHOLDER_HELP.put("pages", "Общее количество страниц");
        PLACEHOLDER_HELP.put("results", "Количество найденных записей на странице");

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
        screen("catalogue", 6, "Биржа: каталог");
        screen("search", 6, "Биржа: поиск «{search}»");
        screen("categories", 6, "Биржа: разделы");
        screen("product", 6, "Биржа: {item}");
        screen("immediate", 6, "Биржа: {side}");
        screen("limit", 6, "Заявка: {side}");
        screen("my", 6, "Биржа: моё");
        screen("manage", 6, "Биржа: заявка");

        screenPlaceholders("catalogue", "category", "search", "page", "pages", "results");
        screenPlaceholders("search", "category", "search", "page", "pages", "results");
        screenPlaceholders("categories", "category");
        screenPlaceholders("product", "item", "available", "buy_price", "sell_price", "last_price");
        screenPlaceholders("immediate", "item", "side", "quantity", "price", "total", "available",
                "requested", "fillable", "worst_price");
        screenPlaceholders("limit", "item", "side", "quantity", "price", "total", "available");
        screenPlaceholders("my", "page", "pages", "results");
        screenPlaceholders("manage", "item", "side", "quantity", "price", "total");

        for (String screen : SCREENS.keySet()) {
            LinkedHashMap<String, DecorationCfg> values = new LinkedHashMap<>();
            values.put("background", new DecorationCfg(false, true, List.of(), Items.GRAY_STAINED_GLASS_PANE,
                    1, " ", List.of(), "muted", "muted"));
            DECORATIONS.put(screen, values);
        }

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

    /** Старт на загрузке сервера: задаём каталог и применяем файлы (или создаём дефолтные). */
    public static void start(Path configDir) {
        try {
            Path root = VAuctionConfigPaths.directory(configDir);
            directory = root.resolve("ui");
            legacyFile = VAuctionConfigPaths.file(configDir, "vauction-ui.json");
            reload();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot prepare config/VMods/VAuction", e);
        }
    }

    /** Применяет файл конфига. Возвращает null при успехе или текст ошибки. */
    static String reload() {
        if (directory == null) return "ui config: directory is not initialised";
        try {
            JsonObject defaults = JsonParser.parseString(defaultJson()).getAsJsonObject();
            Files.createDirectories(directory);
            migrateMonolithicConfig(defaults);
            JsonObject root = readSplitConfig(defaults);
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
            LinkedHashMap<String, ScreenCfg> sc = new LinkedHashMap<>(SCREENS);
            JsonObject jScreens = obj(root, "screens");
            if (jScreens != null) applyScreens(sc, jScreens);
            validateScreens(sc);
            LinkedHashMap<String, LinkedHashMap<String, List<Integer>>> ly = copyLayouts(LAYOUTS);
            JsonObject jLayouts = obj(root, "layouts");
            if (jLayouts != null) applyLayouts(ly, jLayouts);
            validateLayouts(ly, sc);
            LinkedHashMap<String, LinkedHashMap<String, DecorationCfg>> dc = copyDecorations(DECORATIONS);
            JsonObject jDecorations = obj(root, "decorations");
            if (jDecorations != null) applyDecorations(dc, jDecorations);
            validateDecorations(dc, sc);
            LinkedHashMap<String, String> colors = stringsOf(obj(root, "colors"));
            validateColors(colors);
            validateTemplates(t, b, sc, dc);

            // Publish one complete immutable-enough snapshot only after every section
            // has parsed and validated. A bad reload leaves the previous UI active.
            texts = t;
            lore = l;
            buttons = b;
            screens = sc;
            layouts = ly;
            decorations = dc;
            MarketPalette.replace(colors);
            Files.writeString(directory.resolve("README.txt"), readme(), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            LOGGER.error("Cannot reload {}: {}", directory, e.getMessage());
            return e.getMessage();
        }
    }

    private static void migrateMonolithicConfig(JsonObject defaults) throws java.io.IOException {
        boolean hasSplitFile = UI_FILES.keySet().stream().anyMatch(name -> Files.exists(directory.resolve(name)));
        if (hasSplitFile || legacyFile == null || !Files.isRegularFile(legacyFile)) return;

        JsonObject legacy = JsonParser.parseString(Files.readString(legacyFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        mergeMissing(legacy, defaults);
        legacy.addProperty("format", 5);
        legacy.add("placeholderHelp", defaults.get("placeholderHelp").deepCopy());
        writeSplitConfig(legacy);

        Path backup = legacyFile.resolveSibling("vauction-ui.legacy.json");
        if (Files.exists(backup)) {
            backup = legacyFile.resolveSibling("vauction-ui.legacy-" + System.currentTimeMillis() + ".json");
        }
        Files.move(legacyFile, backup);
    }

    private static JsonObject readSplitConfig(JsonObject defaults) throws java.io.IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 5);
        for (Map.Entry<String, List<String>> entry : UI_FILES.entrySet()) {
            Path path = directory.resolve(entry.getKey());
            JsonObject defaultDocument = splitDocument(defaults, entry.getKey(), entry.getValue());
            JsonObject document;
            boolean upgraded = false;
            if (!Files.exists(path)) {
                document = defaultDocument;
                upgraded = true;
            } else {
                JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) throw new IllegalArgumentException(entry.getKey() + " must contain an object");
                document = parsed.getAsJsonObject();
                upgraded = mergeMissing(document, defaultDocument);
            }
            if (!document.has("format") || !document.get("format").isJsonPrimitive()
                    || !document.getAsJsonPrimitive("format").isNumber()
                    || document.get("format").getAsInt() < 1) {
                document.addProperty("format", 1);
                upgraded = true;
            }
            for (String section : entry.getValue()) {
                if (!document.has(section) || !document.get(section).isJsonObject()) {
                    throw new IllegalArgumentException(entry.getKey() + ": section " + section + " must be an object");
                }
                root.add(section, document.get(section).deepCopy());
            }
            if (upgraded) writeJson(path, document);
        }
        return root;
    }

    private static void writeSplitConfig(JsonObject root) throws java.io.IOException {
        Files.createDirectories(directory);
        for (Map.Entry<String, List<String>> entry : UI_FILES.entrySet()) {
            writeJson(directory.resolve(entry.getKey()), splitDocument(root, entry.getKey(), entry.getValue()));
        }
    }

    private static JsonObject splitDocument(JsonObject root, String fileName, List<String> sections) {
        JsonObject document = new JsonObject();
        document.addProperty("format", 1);
        document.addProperty("help", switch (fileName) {
            case "screens.json" -> "Размеры экранов и расположение элементов. Слоты идут слева направо, сверху вниз, начиная с 0.";
            case "buttons.json" -> "Внешний вид функциональных кнопок. Их действия этим файлом не меняются.";
            case "decorations.json" -> "Некликабельный фон и украшения. Можно добавлять любые именованные элементы.";
            case "texts.json" -> "Фразы интерфейса. Ключи удалять не обязательно: отсутствующие дополняются автоматически.";
            case "cards.json" -> "Порядок строк в подсказках карточек предметов и заявок.";
            case "colors.json" -> "Именованные RGB-цвета интерфейса в формате #RRGGBB.";
            default -> "VAuction UI configuration.";
        });
        for (String section : sections) {
            JsonElement value = root.get(section);
            if (value == null || !value.isJsonObject()) {
                throw new IllegalArgumentException("Missing UI section " + section + " for " + fileName);
            }
            document.add(section, value.deepCopy());
        }
        return document;
    }

    private static void writeJson(Path path, JsonObject value) throws java.io.IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(value), StandardCharsets.UTF_8);
    }

    private static String readme() {
        return """
                VAuction GUI
                =============

                После изменений выполните: /ah admin reloadui
                При ошибке новые настройки не применятся, предыдущий интерфейс продолжит работать.

                ФАЙЛЫ
                screens.json      размеры экранов, заголовки и расположение функциональных элементов
                buttons.json      иконки, названия, описания и цвета кнопок
                decorations.json  фон, рамки и другие некликабельные элементы
                texts.json        все фразы интерфейса
                cards.json        состав и порядок строк в карточках предметов и заявок
                colors.json       палитра цветов

                КАРТА СЛОТОВ
                 0  1  2  3  4  5  6  7  8
                 9 10 11 12 13 14 15 16 17
                18 19 20 21 22 23 24 25 26
                27 28 29 30 31 32 33 34 35
                36 37 38 39 40 41 42 43 44
                45 46 47 48 49 50 51 52 53

                screens.rows: от 1 до 6. Ширина всегда 9.
                В layouts укажите номер слота. null скрывает одиночный элемент.
                content — список товарных слотов; его длина задаёт число товаров на странице.

                ПРИМЕР ЭКРАНА НА 3 РЯДА (screens.json)
                "product": { "rows": 3, "title": "Биржа: {item}" }
                "product": { "item": 13, "back": 18, "buy": 21, "sell": 23 }

                ПРИМЕР ФОНА (decorations.json)
                "background": {
                  "enabled": true,
                  "fillEmpty": true,
                  "slots": [],
                  "icon": "minecraft:gray_stained_glass_pane",
                  "count": 1,
                  "name": " ",
                  "lore": [],
                  "color": "muted",
                  "loreColor": "muted"
                }

                fillEmpty=true заполняет только реально пустые ячейки и не перекрывает товары и кнопки.
                Для отдельных ячеек используйте fillEmpty=false и, например, "slots": [0, 1, 7, 8].
                В decorations можно создавать сколько угодно элементов с произвольными именами.

                Доступные плейсхолдеры перечислены отдельно для каждого экрана
                в placeholderHelp внутри screens.json.
                Неизвестный или недоступный для заголовка/декорации плейсхолдер остановит reload
                и покажет точный путь ошибки.
                """;
    }

    private static String defaultJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", 5);
        root.addProperty("help", "Ширина всегда 9. screens.rows задаёт 1..6 рядов. layouts: число ставит элемент в слот, null скрывает. decorations добавляет некликабельные элементы и заполнители. Действия кнопок неизменны.");
        JsonObject placeholderHelp = new JsonObject();
        JsonObject commonPlaceholders = new JsonObject();
        commonPlaceholders.addProperty("player", PLACEHOLDER_HELP.get("player"));
        commonPlaceholders.addProperty("screen", PLACEHOLDER_HELP.get("screen"));
        placeholderHelp.add("common", commonPlaceholders);
        JsonObject screenPlaceholderHelp = new JsonObject();
        SCREEN_PLACEHOLDERS.forEach((screen, names) -> {
            JsonObject available = new JsonObject();
            available.addProperty("player", PLACEHOLDER_HELP.get("player"));
            available.addProperty("screen", PLACEHOLDER_HELP.get("screen"));
            names.forEach(name -> available.addProperty(name, PLACEHOLDER_HELP.get(name)));
            screenPlaceholderHelp.add(screen, available);
        });
        placeholderHelp.add("screens", screenPlaceholderHelp);
        placeholderHelp.addProperty("rule", "Неизвестный плейсхолдер запрещает reload; известный, но недоступный на текущем экране, выводится пустым.");
        root.add("placeholderHelp", placeholderHelp);
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
        JsonObject screens = new JsonObject();
        SCREENS.forEach((key, cfg) -> {
            JsonObject value = new JsonObject();
            value.addProperty("rows", cfg.rows());
            value.addProperty("title", cfg.title());
            screens.add(key, value);
        });
        root.add("screens", screens);
        root.add("layouts", layoutsJson(LAYOUTS));
        root.add("decorations", decorationsJson(DECORATIONS));
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

    /** Replaces available placeholders; unavailable known values render as empty text. */
    static String format(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) return template;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^{}]+)}")
                .matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = placeholders == null ? null : placeholders.get(matcher.group(1));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
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

    static int rows(String screen) {
        ScreenCfg cfg = screens.get(screen);
        if (cfg == null) cfg = SCREENS.get(screen);
        return cfg == null ? 6 : cfg.rows();
    }

    static String title(String screen, Map<String, String> placeholders) {
        ScreenCfg cfg = screens.get(screen);
        if (cfg == null) cfg = SCREENS.get(screen);
        return format(cfg == null ? text("window.title") : cfg.title(), placeholders);
    }

    static void decorate(String screen, SimpleContainer box, Map<String, String> placeholders) {
        LinkedHashMap<String, DecorationCfg> configured = decorations.get(screen);
        if (configured == null) configured = DECORATIONS.get(screen);
        if (configured == null) return;
        for (DecorationCfg cfg : configured.values()) {
            if (!cfg.enabled()) continue;
            ItemStack item = new ItemStack(cfg.iconItem());
            item.setCount(cfg.count());
            List<Component> lore = cfg.lore().stream()
                    .map(line -> MarketText.colored(format(line, placeholders),
                            MarketPalette.byKey(cfg.loreColorKey())))
                    .toList();
            item = GuiItems.namedButton(item,
                    MarketText.colored(format(cfg.name(), placeholders), MarketPalette.byKey(cfg.colorKey())), lore);
            for (int slot : cfg.slots()) putDecoration(box, slot, item);
            if (cfg.fillEmpty()) {
                for (int slot = 0; slot < box.getContainerSize(); slot++) putDecoration(box, slot, item);
            }
        }
    }

    private static void putDecoration(SimpleContainer box, int slot, ItemStack item) {
        if (slot >= 0 && slot < box.getContainerSize() && box.getItem(slot).isEmpty()) {
            box.setItem(slot, item.copy());
        }
    }

    static int slot(String screen, String key) {
        List<Integer> values = layoutValues(screen, key);
        if (values.isEmpty()) return -1;
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
        return lines(block, values, Map.of());
    }

    static List<Component> lines(String block, LinkedHashMap<String, LineValue> values,
                                 Map<String, String> placeholders) {
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        if (placeholders != null) resolved.putAll(placeholders);
        for (Map.Entry<String, LineValue> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().text() == null) continue;
            resolved.putIfAbsent(entry.getKey(), entry.getValue().text());
            int dot = entry.getKey().lastIndexOf('.');
            if (dot >= 0 && dot + 1 < entry.getKey().length()) {
                resolved.putIfAbsent(entry.getKey().substring(dot + 1), entry.getValue().text());
            }
        }
        List<Component> out = new ArrayList<>();
        for (String token : loreTokens(block)) {
            if (token.equals("empty")) {
                out.add(Component.empty());
            } else if (token.equals("divider")) {
                out.add(MarketText.divider());
            } else if (token.startsWith("title:")) {
                LineValue v = values.get(token.substring(6));
                if (v != null && v.text() != null && !v.text().isEmpty()) {
                    out.add(MarketText.action(format(v.text(), resolved), MarketPalette.byKey(v.colorKey())));
                }
            } else if (token.startsWith("value:")) {
                LineValue v = values.get(token.substring(6));
                if (v != null && v.text() != null && !v.text().isEmpty()) {
                    out.add(v.labelKey() == null
                            ? MarketText.colored(format(v.text(), resolved), MarketPalette.byKey(v.colorKey()))
                            : MarketText.labelValue(format(text(v.labelKey()), resolved),
                            format(v.text(), resolved), MarketPalette.byKey(v.colorKey())));
                }
            } else if (token.startsWith("text:")) {
                String[] parts = token.substring(5).split("@", 2);
                out.add(MarketText.colored(format(text(parts[0]), resolved),
                        MarketPalette.byKey(parts.length > 1 ? parts[1] : "text")));
            } else if (token.startsWith("muted:")) {
                out.add(MarketText.muted(format(text(token.substring(6)), resolved)));
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

    private static void screen(String key, int rows, String title) {
        SCREENS.put(key, new ScreenCfg(rows, title));
    }

    private static void screenPlaceholders(String screen, String... names) {
        ArrayList<String> available = new ArrayList<>();
        available.add("player");
        available.add("screen");
        available.addAll(List.of(names));
        SCREEN_PLACEHOLDERS.put(screen, List.copyOf(available));
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

    private static LinkedHashMap<String, LinkedHashMap<String, DecorationCfg>> copyDecorations(
            Map<String, ? extends Map<String, DecorationCfg>> source) {
        LinkedHashMap<String, LinkedHashMap<String, DecorationCfg>> result = new LinkedHashMap<>();
        source.forEach((screen, values) -> result.put(screen, new LinkedHashMap<>(values)));
        return result;
    }

    private static void applyDecorations(
            LinkedHashMap<String, LinkedHashMap<String, DecorationCfg>> target, JsonObject configured) {
        for (String screen : configured.keySet()) {
            if (!target.containsKey(screen)) {
                throw new IllegalArgumentException("Unknown UI screen in decorations: " + screen);
            }
            if (!configured.get(screen).isJsonObject()) {
                throw new IllegalArgumentException("decorations." + screen + " must be an object");
            }
            JsonObject entries = configured.getAsJsonObject(screen);
            for (String name : entries.keySet()) {
                if (!entries.get(name).isJsonObject()) {
                    throw new IllegalArgumentException("Decoration must be an object: " + screen + "." + name);
                }
                JsonObject value = entries.getAsJsonObject(name);
                DecorationCfg base = target.get(screen).get(name);
                boolean enabled = boolOr(value, "enabled", base != null && base.enabled());
                boolean fillEmpty = boolOr(value, "fillEmpty", base != null && base.fillEmpty());
                List<Integer> slots = value.has("slots") ? integerArray(value, "slots")
                        : base == null ? List.of() : base.slots();
                Item icon = value.has("icon") ? itemOf(value.get("icon").getAsString())
                        : base == null ? Items.GRAY_STAINED_GLASS_PANE : base.iconItem();
                int count = value.has("count") ? value.get("count").getAsInt() : base == null ? 1 : base.count();
                String directName = strOrNull(value, "name");
                String itemName = directName == null ? base == null ? " " : base.name() : directName;
                List<String> itemLore = value.has("lore") ? stringArrayOrEmpty(value, "lore")
                        : base == null ? List.of() : base.lore();
                String color = strOrNull(value, "color");
                String loreColor = strOrNull(value, "loreColor");
                target.get(screen).put(name, new DecorationCfg(enabled, fillEmpty, slots, icon, count,
                        itemName, itemLore, color == null ? base == null ? "muted" : base.colorKey() : color,
                        loreColor == null ? base == null ? "muted" : base.loreColorKey() : loreColor));
            }
        }
    }

    private static List<Integer> integerArray(JsonObject object, String key) {
        if (!object.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array of slots");
        }
        ArrayList<Integer> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(key + " must contain only slot numbers");
            }
            values.add(element.getAsInt());
        }
        return List.copyOf(values);
    }

    private static boolean boolOr(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return value.getAsBoolean();
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
                if (element.isJsonNull()) {
                    // null hides a scalar UI element without changing its action or implementation.
                } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
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

    private static void applyScreens(LinkedHashMap<String, ScreenCfg> target, JsonObject configured) {
        for (String key : configured.keySet()) {
            if (!target.containsKey(key)) throw new IllegalArgumentException("Unknown UI screen: " + key);
            if (!configured.get(key).isJsonObject()) {
                throw new IllegalArgumentException("screens." + key + " must be an object");
            }
            JsonObject value = configured.getAsJsonObject(key);
            ScreenCfg base = target.get(key);
            int rows = value.has("rows") ? value.get("rows").getAsInt() : base.rows();
            String title = strOrNull(value, "title");
            target.put(key, new ScreenCfg(rows, title == null ? base.title() : title));
        }
    }

    private static void validateScreens(Map<String, ScreenCfg> configured) {
        for (Map.Entry<String, ScreenCfg> entry : configured.entrySet()) {
            if (entry.getValue().rows() < 1 || entry.getValue().rows() > 6) {
                throw new IllegalArgumentException("screens." + entry.getKey() + ".rows must be between 1 and 6");
            }
            if (entry.getValue().title() == null || entry.getValue().title().isBlank()) {
                throw new IllegalArgumentException("screens." + entry.getKey() + ".title must not be empty");
            }
        }
    }

    private static void validateLayouts(Map<String, ? extends Map<String, List<Integer>>> configured,
                                        Map<String, ScreenCfg> screenSettings) {
        for (Map.Entry<String, ? extends Map<String, List<Integer>>> screen : configured.entrySet()) {
            int capacity = screenSettings.get(screen.getKey()).rows() * 9;
            Set<Integer> content = new java.util.HashSet<>();
            Set<Integer> controls = new java.util.HashSet<>();
            Integer emptySlot = null;
            for (Map.Entry<String, List<Integer>> entry : screen.getValue().entrySet()) {
                List<Integer> values = entry.getValue();
                boolean list = "content".equals(entry.getKey());
                if ((!list && values.size() > 1) || (list && (values.isEmpty() || values.size() > capacity))) {
                    throw new IllegalArgumentException("Invalid UI slot count: " + screen.getKey() + "." + entry.getKey());
                }
                Set<Integer> local = new java.util.HashSet<>();
                for (int slot : values) {
                    if (slot < 0 || slot >= capacity) {
                        throw new IllegalArgumentException("UI slot outside 0.." + (capacity - 1) + ": "
                                + screen.getKey() + "." + entry.getKey());
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

    private static void validateDecorations(
            Map<String, ? extends Map<String, DecorationCfg>> configured,
            Map<String, ScreenCfg> screenSettings) {
        for (Map.Entry<String, ? extends Map<String, DecorationCfg>> screen : configured.entrySet()) {
            int capacity = screenSettings.get(screen.getKey()).rows() * 9;
            for (Map.Entry<String, DecorationCfg> entry : screen.getValue().entrySet()) {
                DecorationCfg cfg = entry.getValue();
                String path = "decorations." + screen.getKey() + "." + entry.getKey();
                if (cfg.count() < 1 || cfg.count() > 64) {
                    throw new IllegalArgumentException(path + ".count must be between 1 and 64");
                }
                if (cfg.enabled() && !cfg.fillEmpty() && cfg.slots().isEmpty()) {
                    throw new IllegalArgumentException(path + " needs fillEmpty=true or at least one slot");
                }
                if (!MarketPalette.DEFAULT_COLORS.containsKey(cfg.colorKey())
                        || !MarketPalette.DEFAULT_COLORS.containsKey(cfg.loreColorKey())) {
                    throw new IllegalArgumentException(path + " uses an unknown color");
                }
                Set<Integer> unique = new java.util.HashSet<>();
                for (int slot : cfg.slots()) {
                    if (slot < 0 || slot >= capacity) {
                        throw new IllegalArgumentException(path + " slot outside 0.." + (capacity - 1));
                    }
                    if (!unique.add(slot)) throw new IllegalArgumentException(path + " repeats slot " + slot);
                }
            }
        }
    }

    private static void validateTemplates(Map<String, String> configuredTexts,
                                          Map<String, ButtonCfg> configuredButtons,
                                          Map<String, ScreenCfg> configuredScreens,
                                          Map<String, ? extends Map<String, DecorationCfg>> configuredDecorations) {
        Set<String> all = PLACEHOLDER_HELP.keySet();
        for (Map.Entry<String, ScreenCfg> entry : configuredScreens.entrySet()) {
            validateTemplate("screens." + entry.getKey() + ".title", entry.getValue().title(),
                    new java.util.HashSet<>(SCREEN_PLACEHOLDERS.get(entry.getKey())));
        }
        for (Map.Entry<String, ButtonCfg> entry : configuredButtons.entrySet()) {
            ButtonCfg cfg = entry.getValue();
            if (cfg.name() != null) validateTemplate("buttons." + entry.getKey() + ".name", cfg.name(), all);
            else if (cfg.nameKey() != null) validateTemplate("texts." + cfg.nameKey(),
                    configuredTexts.getOrDefault(cfg.nameKey(), ""), all);
            for (String line : cfg.lore()) validateTemplate("buttons." + entry.getKey() + ".lore", line, all);
            if (cfg.lore().isEmpty() && cfg.loreKey() != null) validateTemplate("texts." + cfg.loreKey(),
                    configuredTexts.getOrDefault(cfg.loreKey(), ""), all);
        }
        for (Map.Entry<String, ? extends Map<String, DecorationCfg>> screen : configuredDecorations.entrySet()) {
            Set<String> allowed = new java.util.HashSet<>(SCREEN_PLACEHOLDERS.get(screen.getKey()));
            for (Map.Entry<String, DecorationCfg> entry : screen.getValue().entrySet()) {
                String path = "decorations." + screen.getKey() + "." + entry.getKey();
                validateTemplate(path + ".name", entry.getValue().name(), allowed);
                for (String line : entry.getValue().lore()) validateTemplate(path + ".lore", line, allowed);
            }
        }
    }

    private static void validateTemplate(String path, String template, Set<String> allowed) {
        if (template == null) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^{}]+)}")
                .matcher(template);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw new IllegalArgumentException(path + " uses unavailable placeholder {" + matcher.group(1) + "}");
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
                } else if (slots.isEmpty() && !"content".equals(key)) {
                    object.add(key, com.google.gson.JsonNull.INSTANCE);
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

    private static JsonObject decorationsJson(
            Map<String, ? extends Map<String, DecorationCfg>> source) {
        JsonObject result = new JsonObject();
        source.forEach((screen, entries) -> {
            JsonObject screenJson = new JsonObject();
            entries.forEach((name, cfg) -> {
                JsonObject value = new JsonObject();
                value.addProperty("enabled", cfg.enabled());
                value.addProperty("fillEmpty", cfg.fillEmpty());
                com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
                cfg.slots().forEach(slots::add);
                value.add("slots", slots);
                value.addProperty("icon", BuiltInRegistries.ITEM.getKey(cfg.iconItem()).toString());
                value.addProperty("count", cfg.count());
                value.addProperty("name", cfg.name());
                com.google.gson.JsonArray lore = new com.google.gson.JsonArray();
                cfg.lore().forEach(lore::add);
                value.add("lore", lore);
                value.addProperty("color", cfg.colorKey());
                value.addProperty("loreColor", cfg.loreColorKey());
                screenJson.add(name, value);
            });
            result.add(screen, screenJson);
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
