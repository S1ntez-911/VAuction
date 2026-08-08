ALTER TABLE auction_orders
    ADD COLUMN processing_state VARCHAR(16) NOT NULL DEFAULT 'NONE';

CREATE INDEX idx_orders_processing
    ON auction_orders (processing_state, updated_at);
