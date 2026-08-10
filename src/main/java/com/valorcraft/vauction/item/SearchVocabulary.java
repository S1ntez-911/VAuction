package com.valorcraft.vauction.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Expands a human search query into bounded alias groups for SQL LIKE matching.
 * <p>
 * One group = one query word (or recognized phrase) plus its Russian/English
 * synonyms. Groups are AND-ed together inside the read repository, aliases are
 * OR-ed: a Russian player typing «медь слиток» matches an English-named TFG item
 * through {@code [медь | copper] AND [слиток | ingot]}. Queries without Russian
 * words stay single-group and behave exactly as before.
 * <p>
 * Bounded on purpose: at most {@link #MAX_GROUPS} groups and
 * {@link #MAX_ALIASES} aliases per group, so the generated SQL never explodes.
 */
public final class SearchVocabulary {
    public static final int MAX_GROUPS = 4;
    public static final int MAX_ALIASES = 5;

    private static final Map<String, List<String>> PHRASES = Map.of(
            "красная пыль", List.of("redstone"));

    private static final Map<String, List<String>> WORDS = Map.ofEntries(
            Map.entry("медь", List.of("copper")),
            Map.entry("железо", List.of("iron")),
            Map.entry("золото", List.of("gold")),
            Map.entry("серебро", List.of("silver")),
            Map.entry("платина", List.of("platinum")),
            Map.entry("свинец", List.of("lead")),
            Map.entry("олово", List.of("tin")),
            Map.entry("цинк", List.of("zinc")),
            Map.entry("никель", List.of("nickel")),
            Map.entry("алюминий", List.of("aluminium", "aluminum")),
            Map.entry("кремний", List.of("silicon")),
            Map.entry("вольфрам", List.of("tungsten")),
            Map.entry("титан", List.of("titanium")),
            Map.entry("хром", List.of("chromium")),
            Map.entry("уран", List.of("uranium")),
            Map.entry("слиток", List.of("ingot")),
            Map.entry("руда", List.of("ore")),
            Map.entry("пыль", List.of("dust")),
            Map.entry("блок", List.of("block")),
            Map.entry("самородок", List.of("nugget")),
            Map.entry("уголь", List.of("coal")),
            Map.entry("камень", List.of("stone", "rock")),
            Map.entry("дерево", List.of("wood", "log")),
            Map.entry("доски", List.of("planks")),
            Map.entry("стекло", List.of("glass")),
            Map.entry("глина", List.of("clay")),
            Map.entry("кожа", List.of("leather")),
            Map.entry("шерсть", List.of("wool")),
            Map.entry("кремень", List.of("flint")),
            Map.entry("палка", List.of("stick")),
            Map.entry("стрела", List.of("arrow")),
            Map.entry("нить", List.of("string")),
            Map.entry("жемчуг", List.of("pearl")),
            Map.entry("пшеница", List.of("wheat")),
            Map.entry("морковь", List.of("carrot")),
            Map.entry("картофель", List.of("potato")),
            Map.entry("свёкла", List.of("beetroot")),
            Map.entry("яблоко", List.of("apple")),
            Map.entry("семена", List.of("seeds")),
            Map.entry("рыба", List.of("fish")));

    private SearchVocabulary() {}

    /**
     * {@code [медь, copper], [слиток, ingot]} for «медь слиток»; a single
     * {@code [copper]} group for Latin-only queries. Empty for blank input.
     */
    public static List<List<String>> groups(String rawQuery) {
        String query = (rawQuery == null ? "" : rawQuery).trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return List.of();
        List<List<String>> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> phrase : PHRASES.entrySet()) {
            if (query.contains(phrase.getKey())) {
                List<String> aliases = new ArrayList<>();
                aliases.add(phrase.getKey());
                aliases.addAll(phrase.getValue());
                groups.add(cappedAliases(aliases));
                query = query.replace(phrase.getKey(), " ");
            }
        }
        for (String token : query.split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty() || groups.size() >= MAX_GROUPS) continue;
            List<String> aliases = new ArrayList<>();
            aliases.add(token);
            List<String> mapped = WORDS.get(token);
            if (mapped != null) aliases.addAll(mapped);
            groups.add(cappedAliases(aliases));
        }
        return List.copyOf(groups);
    }

    private static List<String> cappedAliases(List<String> aliases) {
        Set<String> unique = new LinkedHashSet<>(aliases);
        return List.copyOf(unique).subList(0, Math.min(unique.size(), MAX_ALIASES));
    }
}