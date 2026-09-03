package com.valorcraft.vauction.model;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class AuctionListing {
    public enum State { ACTIVE, PENDING_PAYMENT, SOLD, CANCELLED, EXPIRED, CLAIMED }

    private final UUID id;
    private final UUID sellerId;
    private final String sellerName;
    private ItemStack item;
    private final long price;
    private final long createdAt;
    private final long expiresAt;
    private State state;
    private UUID buyerId;
    private String buyerName;
    private long soldAt;
    private String escrowReference;

    public AuctionListing(UUID id, UUID sellerId, String sellerName, ItemStack item, long price,
                          long createdAt, long expiresAt, State state, UUID buyerId, String escrowReference) {
        this(id, sellerId, sellerName, item, price, createdAt, expiresAt, state, buyerId, null, 0L,
                escrowReference);
    }

    public AuctionListing(UUID id, UUID sellerId, String sellerName, ItemStack item, long price,
                          long createdAt, long expiresAt, State state, UUID buyerId, String buyerName,
                          long soldAt, String escrowReference) {
        this.id = id;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.item = item.copy();
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.state = state;
        this.buyerId = buyerId;
        this.buyerName = buyerName;
        this.soldAt = soldAt;
        this.escrowReference = escrowReference;
    }

    public UUID id() { return id; }
    public UUID sellerId() { return sellerId; }
    public String sellerName() { return sellerName; }
    public ItemStack item() { return item.copy(); }
    public long price() { return price; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public State state() { return state; }
    public UUID buyerId() { return buyerId; }
    public String buyerName() { return buyerName; }
    public long soldAt() { return soldAt; }
    public String escrowReference() { return escrowReference; }
    public void state(State value) { state = value; }
    public void buyerId(UUID value) { buyerId = value; }
    public void buyerName(String value) { buyerName = value; }
    public void soldAt(long value) { soldAt = value; }
    public void item(ItemStack value) { item = value.copy(); }
    public void escrowReference(String value) { escrowReference = value; }
}
