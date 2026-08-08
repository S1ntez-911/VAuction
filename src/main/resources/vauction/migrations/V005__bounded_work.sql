CREATE TABLE auction_order_acceptance (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id TEXT NOT NULL UNIQUE,
    FOREIGN KEY (order_id) REFERENCES auction_orders (order_id)
);

INSERT OR IGNORE INTO auction_order_acceptance (order_id)
SELECT order_id FROM auction_orders ORDER BY created_at, rowid;

CREATE TABLE auction_match_queue (
    work_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id        TEXT NOT NULL UNIQUE,
    created_at      BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (order_id) REFERENCES auction_orders (order_id)
);

CREATE INDEX idx_match_queue_ready
    ON auction_match_queue (next_attempt_at, created_at, work_id);

CREATE INDEX idx_trades_pending
    ON auction_trades (state, created_at, trade_id);

CREATE INDEX idx_deliveries_state
    ON auction_deliveries (state, delivery_id);

CREATE INDEX idx_orders_expiry
    ON auction_orders (side, status, processing_state, created_at, order_id);

CREATE INDEX idx_orders_match
    ON auction_orders (market_key, side, status, processing_state, price_per_unit, created_at, order_id);

CREATE INDEX idx_orders_processing_cursor
    ON auction_orders (updated_at, order_id)
    WHERE processing_state <> 'NONE';

INSERT OR IGNORE INTO auction_match_queue (order_id, created_at, next_attempt_at, attempt_count)
SELECT order_id, created_at, created_at, 0
FROM auction_orders
WHERE status = 'ACTIVE' AND processing_state = 'NONE'
ORDER BY (SELECT sequence FROM auction_order_acceptance a
          WHERE a.order_id = auction_orders.order_id);
