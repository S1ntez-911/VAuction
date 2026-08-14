-- Marks only listings created by the new fixed-price auction. Legacy order-book
-- rows remain untouched and are never exposed by the new player interface.
CREATE TABLE auction_simple_listing_ids (
    listing_id   BIGINT PRIMARY KEY,
    category     VARCHAR(32) NOT NULL,
    created_at   BIGINT NOT NULL,
    state        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    CHECK (category IN ('resources','food','tools','machines','other')),
    CHECK (state IN ('PENDING','ACTIVE','CLOSED','MANUAL_REVIEW'))
);

CREATE INDEX idx_simple_listings_category
    ON auction_simple_listing_ids (category, listing_id);

CREATE INDEX idx_simple_listings_created
    ON auction_simple_listing_ids (created_at, listing_id);

CREATE INDEX idx_simple_listings_state
    ON auction_simple_listing_ids (state, listing_id);
