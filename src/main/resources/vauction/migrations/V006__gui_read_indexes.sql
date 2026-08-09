-- Bounded read-side indexes for the server-only vanilla inventory UI.
CREATE INDEX idx_orders_market_activity
    ON auction_orders (status, processing_state, updated_at, market_key);

CREATE INDEX idx_orders_owner_page
    ON auction_orders (owner_uuid, created_at DESC, order_id);

CREATE INDEX idx_orders_search_active
    ON auction_orders (status, processing_state, item_search_name, market_key);

CREATE INDEX idx_trades_settled_market
    ON auction_trades (state, settled_at DESC, market_key, trade_id);

CREATE INDEX idx_trades_market_last
    ON auction_trades (market_key, state, settled_at DESC, trade_id);

CREATE INDEX idx_deliveries_player_state_page
    ON auction_deliveries (player_uuid, state, created_at DESC, delivery_id);
