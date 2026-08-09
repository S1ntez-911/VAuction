-- Durable immediate-or-cancel cleanup markers.
CREATE TABLE auction_ioc_orders (
    order_id VARCHAR(36) PRIMARY KEY,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES auction_orders(order_id) ON DELETE CASCADE
);

CREATE INDEX idx_ioc_orders_created ON auction_ioc_orders(created_at, order_id);

-- Minimal persistent player cursor; the trading ledger remains the source of truth.
CREATE TABLE auction_player_market_state (
    player_uuid VARCHAR(36) PRIMARY KEY,
    last_seen_trade_at BIGINT NOT NULL DEFAULT 0,
    last_seen_trade_id VARCHAR(36) NOT NULL DEFAULT '',
    last_seen_delivery_id BIGINT NOT NULL DEFAULT 0,
    onboarding_shown INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_trades_buyer_notifications
    ON auction_trades(buyer_uuid, state, settled_at, trade_id);

CREATE INDEX idx_trades_seller_notifications
    ON auction_trades(seller_uuid, state, settled_at, trade_id);
