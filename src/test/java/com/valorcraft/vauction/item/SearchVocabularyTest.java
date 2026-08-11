package com.valorcraft.vauction.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchVocabularyTest {

    @Test
    void russianWordsExpandIntoEnglishAliasGroups() {
        List<List<String>> groups = SearchVocabulary.groups("медь слиток");
        assertTrue(groups.stream().anyMatch(g -> g.contains("медь") && g.contains("copper")),
                "«медь» must alias copper");
        assertTrue(groups.stream().anyMatch(g -> g.contains("слиток") && g.contains("ingot")),
                "«слиток» must alias ingot");
        assertEquals(2, groups.size());
    }

    @Test
    void latinOnlyQueryStaysASingleGroup() {
        List<List<String>> groups = SearchVocabulary.groups("copper");
        assertEquals(1, groups.size());
        assertEquals(List.of("copper"), groups.get(0));
    }

    @Test
    void compoundQueryAndsAllWordsTogether() {
        List<List<String>> groups = SearchVocabulary.groups("медь руда");
        assertEquals(2, groups.size());
        assertFalse(groups.get(0).isEmpty());
        assertFalse(groups.get(1).isEmpty());
    }

    @Test
    void phraseMapsToASingleGroupWithSynonyms() {
        List<List<String>> groups = SearchVocabulary.groups("красная пыль");
        assertTrue(groups.size() >= 1);
        List<String> first = groups.get(0);
        assertTrue(first.contains("красная пыль"));
        assertTrue(first.contains("redstone"));
    }

    @Test
    void blankQueryYieldsNoGroups() {
        assertTrue(SearchVocabulary.groups(null).isEmpty());
        assertTrue(SearchVocabulary.groups("   ").isEmpty());
    }

    @Test
    void expansionIsHardBounded() {
        String veryLong = "медь железо золото серебро платина свинец олово цинк никель алюминий";
        List<List<String>> groups = SearchVocabulary.groups(veryLong);
        assertTrue(groups.size() <= SearchVocabulary.MAX_GROUPS);
        assertTrue(groups.stream().allMatch(g -> g.size() <= SearchVocabulary.MAX_ALIASES));
    }

    @Test
    void mixedRuEnQueryKeepsEveryWord() {
        List<List<String>> groups = SearchVocabulary.groups("copper слиток");
        assertTrue(groups.stream().anyMatch(g -> g.contains("copper")));
        assertTrue(groups.stream().anyMatch(g -> g.contains("слиток")));
    }

    @Test
    void catalogueCategoriesExpandToUsefulBoundedAliases() {
        assertTrue(SearchVocabulary.groups("resources").get(0).contains("ore"));
        assertTrue(SearchVocabulary.groups("food").get(0).contains("meat"));
        assertTrue(SearchVocabulary.groups("tools").get(0).contains("hammer"));
        assertTrue(SearchVocabulary.groups("machines").get(0).contains("circuit"));
        assertTrue(SearchVocabulary.groups("machines").get(0).size() <= SearchVocabulary.MAX_ALIASES);
    }
}
