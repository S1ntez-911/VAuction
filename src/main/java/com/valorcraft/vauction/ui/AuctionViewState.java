package com.valorcraft.vauction.ui;

/** Complete browse context carried through every child GUI. */
public record AuctionViewState(int page, AuctionCategory category, AuctionSort sort, String search, String seller) {
    public AuctionViewState {
        page = Math.max(0, page);
        category = category == null ? AuctionCategory.ALL : category;
        sort = sort == null ? AuctionSort.NEWEST : sort;
        search = search == null ? "" : search;
        seller = seller == null ? "" : seller;
    }
    public static AuctionViewState initial() { return new AuctionViewState(0, AuctionCategory.ALL, AuctionSort.NEWEST, "", ""); }
    public AuctionViewState withPage(int value) { return new AuctionViewState(value, category, sort, search, seller); }
}
