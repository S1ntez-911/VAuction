package com.valorcraft.vauction.gui;

record GuiAction(Type type, int number, long listingId) {
    enum Type {
        REFRESH, PAGE, TOGGLE_MINE, NEXT_CATEGORY, OPEN_LISTING,
        CONFIRM_PURCHASE, OPEN_CONTENTS, CONTENTS_PAGE, CLAIM_ALL, BACK, BACK_TO_LISTING
    }

    static GuiAction simple(Type type) {
        return new GuiAction(type, 0, 0);
    }

    static GuiAction number(Type type, int value) {
        return new GuiAction(type, value, 0);
    }

    static GuiAction listing(Type type, long listingId) {
        return new GuiAction(type, 0, listingId);
    }

    static GuiAction listingPage(Type type, long listingId, int delta) {
        return new GuiAction(type, delta, listingId);
    }
}
