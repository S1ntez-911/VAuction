package com.valorcraft.vauction.lang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.valorcraft.vauction.VAuctionMod;
import com.valorcraft.vauction.config.AuctionConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Server-side, hot-reloadable localization used by inventory GUIs and chat. */
public final class AuctionLang {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("VMods/VAuction/lang/ru_ru.json");
    private static volatile Map<String, String> values = defaults();

    private AuctionLang() {}

    public static synchronized boolean load() {
        try {
            Files.createDirectories(FILE.getParent());
            LinkedHashMap<String, String> merged = defaults();
            if (Files.exists(FILE)) {
                Map<String, String> custom = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), MAP_TYPE);
                if (custom != null) merged.putAll(custom);
            }
            values = Map.copyOf(merged);
            Files.writeString(FILE, GSON.toJson(merged), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            VAuctionMod.LOGGER.info("Загружена локализация VAuction: {}", FILE);
            return true;
        } catch (IOException | RuntimeException e) {
            VAuctionMod.LOGGER.error("Не удалось загрузить локализацию {}; оставлена предыдущая версия", FILE, e);
            return false;
        }
    }

    public static String text(String key, Object... replacements) {
        String result = values.getOrDefault(key, "<" + key + ">");
        for (int i = 0; i + 1 < replacements.length; i += 2)
            result = result.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        return applyTheme(result);
    }

    private static String applyTheme(String value) {
        return replaceCode(replaceCode(replaceCode(replaceCode(replaceCode(replaceCode(replaceCode(replaceCode(value,
                        'b', AuctionConfig.THEME_PRIMARY.get()), 'd', AuctionConfig.THEME_PRIMARY.get()),
                        '6', AuctionConfig.THEME_SECONDARY.get()), 'e', AuctionConfig.THEME_SECONDARY.get()),
                        'a', AuctionConfig.THEME_SUCCESS.get()), 'c', AuctionConfig.THEME_DANGER.get()),
                        '7', AuctionConfig.THEME_MUTED.get()), 'f', AuctionConfig.THEME_TEXT.get());
    }

    private static String replaceCode(String value, char code, String configured) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.startsWith("#")) raw = raw.substring(1);
        if (!raw.matches("(?i)[0-9a-f]{6}")) return value;
        String replacement = "&#" + raw.toUpperCase(Locale.ROOT);
        return value.replace("&" + code, replacement).replace("&" + Character.toUpperCase(code), replacement);
    }

    public static Component component(String key, Object... replacements) { return legacy(text(key, replacements)); }
    public static Component legacy(String input) {
        input = applyTheme(input);
        MutableComponent root = Component.empty();
        StringBuilder part = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                if (part.length() > 0) { root.append(Component.literal(part.toString()).setStyle(style)); part.setLength(0); }
                if (input.charAt(i + 1) == '#' && i + 7 < input.length()) {
                    try {
                        int rgb = Integer.parseInt(input.substring(i + 2, i + 8), 16);
                        style = style.withColor(TextColor.fromRgb(rgb)); i += 7; continue;
                    } catch (NumberFormatException ignored) {}
                }
                char code = Character.toLowerCase(input.charAt(++i));
                ChatFormatting format = ChatFormatting.getByCode(code);
                if (format != null) style = format == ChatFormatting.RESET ? Style.EMPTY : style.applyFormat(format);
                else { part.append('&').append(code); }
            } else part.append(c);
        }
        if (part.length() > 0) root.append(Component.literal(part.toString()).setStyle(style));
        return root;
    }

    private static LinkedHashMap<String, String> defaults() {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        d.put("gui.auction.title", "&0Аукцион");
        d.put("gui.auction.my_lots.name", "&6Мои товары");
        d.put("gui.auction.my_lots.lore", "&7Ваши активные объявления");
        d.put("gui.auction.archive.name", "&6Архив");
        d.put("gui.auction.archive.lore", "&7Возвраты и невыданные покупки");
        d.put("gui.auction.refresh", "&aОбновить");
        d.put("gui.auction.previous", "&eПредыдущая страница");
        d.put("gui.auction.next", "&eСледующая страница");
        d.put("gui.auction.help.name", "&bПомощь");
        d.put("gui.auction.help.lore", "&7ЛКМ — действие\n&7Shift+ЛКМ или ПКМ — содержимое\n&7Страница &f{page}&7/&f{pages}");
        d.put("gui.auction.reset", "&cСбросить фильтры");
        d.put("gui.auction.sort.name", "&6Сортировка: &f{sort}");
        d.put("gui.auction.category.name", "&6Категория: &f{category}");
        d.put("gui.auction.cycle.lore", "&7ЛКМ — следующая\n&7ПКМ — предыдущая");
        d.put("gui.lot.lore", " \n&6Цена: &f{price}\n&7Продавец: &f{seller}\n&7Осталось: &f{remaining}\n&a{action}{preview}");
        d.put("gui.lot.buy", "ЛКМ — купить"); d.put("gui.lot.cancel", "ЛКМ — снять товар");
        d.put("gui.lot.preview", "\n&bShift+ЛКМ/ПКМ — посмотреть содержимое");
        d.put("gui.confirm.yes", "&aПодтвердить"); d.put("gui.confirm.no", "&cОтмена");
        d.put("gui.confirm.buy.action", "&7Купить за &f{price}"); d.put("gui.confirm.cancel.action", "&7Снять лот с продажи");
        d.put("gui.confirm.sell.action", "&7Выставить за &f{price}");
        d.put("gui.confirm.buy.title", "&0Подтверждение покупки"); d.put("gui.confirm.cancel.title", "&0Подтверждение снятия");
        d.put("gui.confirm.sell.title", "&0Подтверждение продажи");
        d.put("gui.user.active.title", "&0Мои товары"); d.put("gui.user.archive.title", "&0Архив аукциона");
        d.put("gui.user.history.title", "&0История продаж"); d.put("gui.user.back", "&cНазад на аукцион");
        d.put("gui.user.claim_all", "&aЗабрать всё"); d.put("gui.user.page", "&bСтраница {page}/{pages}");
        d.put("gui.user.history", "&6История продаж");
        d.put("gui.user.lot.price", "&6Цена: &f{price}"); d.put("gui.user.lot.buyer", "&7Покупатель: &f{buyer}");
        d.put("gui.user.lot.cancel", "&7ЛКМ — снять с продажи"); d.put("gui.user.lot.claim", "&7ЛКМ — забрать");
        d.put("gui.preview.title", "&0Содержимое: {item}"); d.put("gui.preview.back", "&cВернуться к аукциону");
        d.put("time.remaining", "{hours}ч {minutes}м");
        d.put("chat.gui_help", "&b/ah sell <цена>, /ah search <текст>, /ah player <ник>, /ah history, /ah claim");
        d.put("chat.help", "&6VAuction — помощь\n&e/ah &7— открыть аукцион\n&e/ah sell <цена> [количество] &7— выставить предмет из основной руки\n&e/ah search <текст> &7— поиск товара\n&e/ah player <ник> &7— товары продавца\n&e/ah history &7— история ваших продаж\n&e/ah claim &7— забрать предметы из архива");
        d.put("chat.decimals.enabled", "&7Дробные цены разрешены: до {decimals} знаков после точки или запятой.");
        d.put("chat.decimals.disabled", "&7VEconomy сейчас использует только целые цены.");
        d.put("error.storage_unavailable", "&cАукцион временно недоступен: хранилище не запущено. Сообщите администрации.");
        d.put("chat.list.empty", "&eУ вас нет активных лотов."); d.put("chat.list.title", "&6Ваши активные лоты:");
        d.put("chat.list.entry", "&7{id} • {item} x{count} • {price}");
        d.put("chat.config_reload", "&aКонфигурация и локализация VAuction перезагружены. Иконки открытых меню обновлены.");
        d.put("chat.config_reload_failed", "&cНе удалось загрузить VAuction.toml: проверьте конфиг и журнал сервера.");
        d.put("chat.config_reload_partial", "&eКонфигурация VAuction обновлена, но локализация не загружена: проверьте JSON и журнал сервера.");
        d.put("chat.reload", "&aЛокализация VAuction перезагружена.");
        d.put("chat.reload_failed", "&cЛокализация не загружена: проверьте JSON и журнал сервера.");
        d.put("chat.archive_waiting", "&eВ архиве VAuction ожидают предметы: {count}. /ah claim");
        d.put("chat.inventory_full", "&eОсвободите место: остаток сохранён в архиве.");
        d.put("chat.delivery_pending", "&eПредмет уже выдан, но подтверждение хранилища отложено. Повторите /ah claim позже — второй предмет выдан не будет.");
        d.put("chat.sale_success", "&aЛот выставлен: {id} за {price}");
        d.put("chat.sales_recovered", "&aВосстановлено отложенных лотов: {count}.");
        d.put("chat.buy_success", "&aПокупка завершена: {item} за {price}");
        d.put("chat.cancel_success", "&eЛот снят и помещён в архив аукциона.");
        d.put("chat.claim_none", "&cНет предметов для получения."); d.put("chat.claim_success", "&aПолучено предметов: {count}");
        d.put("error.empty_hand", "Возьмите продаваемый предмет в основную руку.");
        d.put("error.forbidden_item", "Этот предмет запрещено продавать на аукционе.");
        d.put("error.invalid_amount", "Количество должно быть положительным.");
        d.put("error.invalid_price", "Некорректная цена: {reason}");
        d.put("error.price_range", "Цена выходит за разрешённые пределы.");
        d.put("error.listing_limit", "Достигнут лимит активных лотов.");
        d.put("error.save", "Ошибка сохранения. Предмет возвращён.");
        d.put("error.sale_pending", "Выставление сохранено для безопасного восстановления. Предмет не потерян; перезайдите или попросите администратора выполнить /ah recover.");
        d.put("error.not_found", "Лот не найден."); d.put("error.own_lot", "Нельзя купить собственный лот.");
        d.put("error.being_bought", "Этот лот уже покупают."); d.put("error.unavailable", "Лот уже недоступен.");
        d.put("error.retry", "Предыдущая попытка покупки была отменена. Повторите клик.");
        d.put("error.cancel_not_found", "Ваш активный лот с таким ID не найден.");
        d.put("error.cannot_cancel", "Этот лот уже нельзя отменить."); d.put("error.economy_offline", "VEconomy ещё не запущена.");
        d.put("error.insufficient_funds", "Недостаточно средств."); d.put("error.account_disabled", "Ваш экономический аккаунт заморожен.");
        d.put("error.limit_exceeded", "Операция превышает лимит VEconomy."); d.put("error.purchase", "Покупка не выполнена: {status}");
        d.put("error.purchase_pending", "Расчёт требует безопасного восстановления. Лот заблокирован; деньги и предмет не будут списаны повторно.");
        d.put("error.storage_pending", "Хранилище временно не подтвердило операцию. Лот безопасно заблокирован до восстановления.");
        // TM2-compatible visual vocabulary. Kept under new keys so upgrades cannot retain old simplified strings.
        d.put("tm2.browse.title", "&0Рынок ({page}/{pages})"); d.put("tm2.browse.title_search", "&0Результаты поиска ({page}/{pages})");
        d.put("tm2.browse.title_player", "&0Лоты {player} ({page}/{pages})");
        d.put("chat.perf", "&6[VAuction] &7{stats}");
        d.put("chat.recover", "&aСверка незавершённых escrow-операций VAuction выполнена.");
        d.put("tm2.nav.prev", "&7◀ Предыдущая страница"); d.put("tm2.nav.next", "&bСледующая страница ▶");
        d.put("tm2.nav.back", "&7◀ Назад"); d.put("tm2.nav.my", "&b⬢ Активные лоты"); d.put("tm2.nav.archive", "&b⏳ Архив лотов");
        d.put("tm2.nav.refresh", "&b🔄 &7Обновить рынок"); d.put("tm2.nav.reset", "&b🔀 &7Сбросить");
        d.put("tm2.nav.help", "&b❖ Помощь по системе рынка"); d.put("tm2.nav.sort", "&b⇵ Сортировка");
        d.put("tm2.nav.category", "&b☰ Категории предметов"); d.put("tm2.nav.history", "&b⌚ История продаж");
        d.put("tm2.nav.selected", "&6◆ {name}"); d.put("tm2.nav.unselected", "&b◇ &7{name}");
        d.put("tm2.nav.my_lore", "&b┃ &7Здесь отображаются\n&b┃ &7все активные товары\n&b┃ &7на рынке.\n&b \n&6 Активных лотов: {count}\n&b \n&b▶ &7ЛКМ: Открыть раздел");
        d.put("tm2.nav.archive_lore", "&b┃ &7Здесь отображаются\n&b┃ &7товары, срок которых\n&b┃ &7уже истёк.\n&b┃\n&6 Неактивных лотов: {count}\n&b┃\n&b▶ &7ЛКМ: Открыть раздел");
        d.put("tm2.nav.help_lore", "&b┃ &7Чтобы выставить предмет на продажу,\n&b┃ &7используйте команду:\n&b┃ &b/ah sell <цена>\n&b┃\n&b┃ &7Чтобы найти нужный предмет,\n&b┃ &7используйте команду:\n&b┃ &b/ah search <название>\n&b┃\n&b┃ &7Чтобы посмотреть товары игрока,\n&b┃ &7используйте команду:\n&b┃ &b/ah player <ник>\n&b┃\n&b┃ &7Чтобы посмотреть историю продаж,\n&b┃ &7используйте команду:\n&b┃ &b/ah history\n&b\n&6Доступные активные лоты: {max}\n&b\n&7Для переключения категорий\n&7используйте &bЛКМ &7и &bПКМ");
        d.put("tm2.nav.help_my", "&b┃ &7Здесь отображаются\n&b┃ &7размещённые лоты рынка.\n&b┃\n&b┃ &7Нажмите на лот, чтобы\n&b┃ &7снять его с продажи.\n&b┃\n&b┃ &7Истёкшие и снятые лоты\n&b┃ &7перемещаются в архив.\n&b┃\n&b┃ &7История продаж доступна\n&b┃ &7по кнопке &b⌚ История продаж&7.");
        d.put("tm2.nav.help_archive", "&b┃ &7Здесь отображаются\n&b┃ &7завершённые лоты рынка.\n&b┃\n&b┃ &7В архив попадают\n&b┃ &7снятые и истёкшие лоты.\n&b┃\n&b┃ &7Нажмите на лот, чтобы\n&b┃ &7забрать предмет обратно.\n&b┃\n&b┃ &7Все предметы можно получить\n&b┃ &7по кнопке &b➦ Забрать всё&7.\n&b┃\n&b┃ &7История продаж доступна\n&b┃ &7по кнопке &b⌚ История продаж&7.");
        d.put("tm2.nav.help_history_v2", "&b┃ &7Здесь отображается история\n&b┃ &7ваших проданных предметов\n&b┃ &7за последние {days} дней.\n&b┃\n&b┃ &7Каждый предмет показан\n&b┃ &7с информацией о покупателе,\n&b┃ &7цене и времени продажи.");
        d.put("tm2.nav.history_lore_v2", "&b┃ &7Здесь отображается история\n&b┃ &7ваших проданных предметов\n&b┃ &7за последние {days} дней.\n&b┃\n&b┃ &7Каждый предмет показан\n&b┃ &7с информацией о покупателе,\n&b┃ &7цене и времени продажи.\n&b\n&b▶ &7ЛКМ: Открыть раздел");
        d.put("tm2.lot.category", "&b┃ &7Категория: &b{category}"); d.put("tm2.lot.seller", "&b┃ &7Продавец: &b{seller}");
        d.put("tm2.lot.category_cont", "&b┃  &b{categories}");
        d.put("tm2.lot.time", "&b┃ &7Истекает через: &b{time}"); d.put("tm2.lot.price", "&b┃ &7Стоимость: &6{price}");
        d.put("tm2.lot.buy", "&b▶ &7ЛКМ: купить лот"); d.put("tm2.lot.preview", "&b▶ &7ЛКМ: посмотреть содержимое");
        d.put("tm2.lot.cancel", "&b▶ &7ЛКМ: снять лот"); d.put("tm2.lot.take", "&b▶ &7ЛКМ: забрать предмет");
        d.put("tm2.lot.stored", "&b┃ &7Хранится до получения"); d.put("tm2.history.buyer", "&b┃ &7Покупатель: &b{buyer}");
        d.put("tm2.preview.page", "&bСтраница {page}/{pages}");
        d.put("tm2.history.when", "&b┃ &7Продано: &b{ago}");
        d.put("tm2.my.title", "&0Размещённые лоты ({page}/{pages})"); d.put("tm2.archive.title", "&0Возврат ({page}/{pages})");
        d.put("tm2.history.title", "&0История продаж ({page}/{pages})"); d.put("tm2.archive.take_all", "&b➦ Забрать всё");
        d.put("tm2.archive.take_all_lore", "&b┃ &7Забрать все\n&b┃ &7просроченные товары");
        d.put("tm2.confirm.buy.title", "&0Подтверждение покупки"); d.put("tm2.confirm.sell.title", "&0Подтверждение продажи");
        d.put("tm2.confirm.cancel.title", "&0Снять с продажи?"); d.put("tm2.confirm.buy", "&aПодтвердить");
        d.put("tm2.confirm.sell", "&aВыставить"); d.put("tm2.confirm.remove", "&aСнять"); d.put("tm2.confirm.remove_container", "&aСнять шалкер");
        d.put("tm2.confirm.cancel", "&cОтмена"); d.put("tm2.confirm.back", "&cНазад");
        d.put("tm2.confirm.price", "&7Цена: &6{price}"); d.put("tm2.confirm.seller", "&7Продавец: &b{seller}");
        d.put("tm2.preview.title", "&0Содержимое шалкера"); d.put("tm2.preview.buy", "&fКупить лот");
        d.put("tm2.barrier.no_money", "&c✕ Недостаточно средств"); d.put("tm2.barrier.sold", "&c✕ Этот предмет уже продан");
        return d;
    }
}
