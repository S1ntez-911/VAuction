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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Поверхностный конфиг интерфейса биржи: цвета, тексты, строки лора, кнопки.
 * Файл {@code config/vauction-ui.json} правится без пересборки и применяется
 * командой {@code /ah ui reload}. Любая секция и любой ключ опциональны:
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
    private UiConfig() {}

    /** Одна строка лора: метка может отсутствовать (тогда строка без «: »). */
    record LineValue(String labelKey, String text, String colorKey) {}

    /** Кнопка: иконка + ключи имени и подписи (nameKey может отсутствовать, имя задаётся кодом). */
    record ButtonCfg(Item iconItem, String nameKey, String loreKey) {}

    private static volatile Path file;

    private static final LinkedHashMap<String, String> TEXTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> LORE = new LinkedHashMap<>();
    private static final LinkedHashMap<String, ButtonCfg> BUTTONS = new LinkedHashMap<>();

    private static volatile LinkedHashMap<String, String> texts = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, List<String>> lore = new LinkedHashMap<>();
    private static volatile LinkedHashMap<String, ButtonCfg> buttons = new LinkedHashMap<>();

    static {
        TEXTS.put("brand", "◆ Биржа ValorCraft");
        TEXTS.put("window.title", "Биржа ValorCraft");

        TEXTS.put("card.buy", "Купить");
        TEXTS.put("card.sell", "Продать");
        TEXTS.put("card.trade", "Сделка");
        TEXTS.put("card.hintBuy", "ЛКМ: купить");
        TEXTS.put("card.hintSell", "ПКМ: продать");
        TEXTS.put("card.unavailable", "нет предложений");
        TEXTS.put("card.dash", "—");

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
        TEXTS.put("editor.nowBuy", "Купить сейчас");
        TEXTS.put("editor.nowSell", "Продать сейчас");
        TEXTS.put("editor.nowLore", "По рынку");
        TEXTS.put("editor.submit", "Выставить");
        TEXTS.put("editor.submitLore", "Заявка по указанной цене");

        TEXTS.put("button.back", "Назад");
        TEXTS.put("button.backLore", "Каталог");
        TEXTS.put("button.my", "Моё");
        TEXTS.put("button.search", "Поиск");
        TEXTS.put("button.searchLore", "Искать по названию");
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
        TEXTS.put("nav.infoHintsMulti", "ЛКМ: купить · ПКМ: продать");
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

        TEXTS.put("onboarding.1", "ЛКМ по товару — купить.");
        TEXTS.put("onboarding.2", "ПКМ по товару — продать.");
        TEXTS.put("onboarding.help", "[Помощь]");
        TEXTS.put("tutorial.1", "«Купить/Продать сейчас» — по рынку.");
        TEXTS.put("tutorial.2", "«Своя цена» — заявка-ожидание.");
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
        LORE.put("card", List.of(
                "value:card.buy", "value:card.sell", "value:card.trade",
                "empty", "text:card.hintBuy@success", "text:card.hintSell@sell"));
        LORE.put("instant", List.of(
                "title:instant.action", "value:instant.price", "value:instant.quantity",
                "value:instant.partial", "value:instant.total", "value:instant.worst",
                "value:instant.offers"));
        LORE.put("editor", List.of(
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
        BUTTONS.put("newSearch", new ButtonCfg(Items.COMPASS, "button.newSearch", "button.newSearchLore"));
        BUTTONS.put("catalogue", new ButtonCfg(Items.CHEST, "button.catalogue", "button.catalogueLore"));
        BUTTONS.put("allGoods", new ButtonCfg(Items.CHEST, "button.allGoods", "button.allGoodsLore"));
        BUTTONS.put("prev", new ButtonCfg(Items.ARROW, "button.prev", null));
        BUTTONS.put("next", new ButtonCfg(Items.ARROW, "button.next", null));
        BUTTONS.put("infoBook", new ButtonCfg(Items.BOOK, null, null));
        BUTTONS.put("ownPrice", new ButtonCfg(Items.CLOCK, null, "instant.ownPriceLore"));
        BUTTONS.put("buyNow", new ButtonCfg(Items.EMERALD, null, "instant.buyNowLore"));
        BUTTONS.put("sellNow", new ButtonCfg(Items.HOPPER, null, "instant.sellNowLore"));
        BUTTONS.put("disabledOffers", new ButtonCfg(Items.BARRIER, "instant.offers", null));
        BUTTONS.put("priceInfo", new ButtonCfg(Items.COMPARATOR, "editor.changePrice", "editor.changePriceLore"));
        BUTTONS.put("modeNow", new ButtonCfg(Items.CLOCK, null, "editor.nowLore"));
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
            LinkedHashMap<String, String> t = new LinkedHashMap<>(TEXTS);
            JsonObject jt = obj(root, "texts");
            if (jt != null) t.putAll(stringsOf(jt));
            texts = t;
            LinkedHashMap<String, List<String>> l = new LinkedHashMap<>(LORE);
            JsonObject jl = obj(root, "lore");
            if (jl != null) l.putAll(loreOf(jl));
            lore = l;
            LinkedHashMap<String, ButtonCfg> b = new LinkedHashMap<>(BUTTONS);
            JsonObject jb = obj(root, "buttons");
            if (jb != null) b.putAll(buttonsOf(jb));
            buttons = b;
            MarketPalette.apply(stringsOf(obj(root, "colors")));
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String defaultJson() {
        JsonObject root = new JsonObject();
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
            if (e.getValue().nameKey() != null) b.addProperty("nameKey", e.getValue().nameKey());
            if (e.getValue().loreKey() != null) b.addProperty("loreKey", e.getValue().loreKey());
            buttons.add(e.getKey(), b);
        }
        root.add("buttons", buttons);
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

    private static Item itemOf(String id) {
        try {
            ResourceLocation loc = ResourceLocation.tryParse(id);
            if (loc != null) {
                return BuiltInRegistries.ITEM.getOptional(loc).orElse(Items.BARRIER);
            }
        } catch (RuntimeException ignored) {
        }
        return Items.BARRIER;
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
        for (String k : o.keySet()) {
            JsonElement e = o.get(k);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) m.put(k, e.getAsString());
        }
        return m;
    }

    private static LinkedHashMap<String, List<String>> loreOf(JsonObject o) {
        LinkedHashMap<String, List<String>> m = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            if (!o.get(k).isJsonArray()) continue;
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
            if (!o.get(k).isJsonObject()) continue;
            JsonObject b = o.getAsJsonObject(k);
            ButtonCfg base = BUTTONS.getOrDefault(k, new ButtonCfg(Items.BARRIER, null, null));
            Item icon = b.has("icon") && b.get("icon").isJsonPrimitive()
                    && b.get("icon").getAsJsonPrimitive().isString()
                    ? itemOf(b.get("icon").getAsString()) : base.iconItem();
            String nameKey = strOrNull(b, "nameKey");
            String loreKey = strOrNull(b, "loreKey");
            m.put(k, new ButtonCfg(icon,
                    nameKey == null ? base.nameKey() : nameKey,
                    loreKey == null ? base.loreKey() : loreKey));
        }
        return m;
    }

    private static String strOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString();
        return null;
    }
}