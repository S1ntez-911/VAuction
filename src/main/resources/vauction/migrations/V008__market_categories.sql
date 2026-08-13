CREATE TABLE auction_market_categories (
    market_key     VARCHAR(191) PRIMARY KEY,
    category       VARCHAR(32) NOT NULL,
    classified_at BIGINT NOT NULL
);

CREATE INDEX idx_market_categories_category
    ON auction_market_categories (category, market_key);
