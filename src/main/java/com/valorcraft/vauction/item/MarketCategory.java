package com.valorcraft.vauction.item;

public enum MarketCategory {
    RESOURCES("resources"),
    FOOD("food"),
    TOOLS("tools"),
    MACHINES("machines"),
    OTHER("other");

    private final String id;

    MarketCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static MarketCategory fromId(String value) {
        if (value != null) for (MarketCategory category : values()) {
            if (category.id.equalsIgnoreCase(value)) return category;
        }
        return OTHER;
    }
}
