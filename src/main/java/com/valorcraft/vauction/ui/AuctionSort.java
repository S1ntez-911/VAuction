package com.valorcraft.vauction.ui;

public enum AuctionSort {
    NEWEST("Сначала новые"), OLDEST("Сначала старые"), ONLY_MINE("Только мои"),
    CHEAPEST("Сначала дешёвые"), EXPENSIVE("Сначала дорогие");
    private final String title;
    AuctionSort(String title) { this.title = title; }
    public String title() { return title; }
    public AuctionSort next(int direction) {
        AuctionSort[] values = values();
        return values[Math.floorMod(ordinal() + direction, values.length)];
    }
}
