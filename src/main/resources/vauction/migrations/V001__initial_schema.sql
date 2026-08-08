-- =====================================================================
-- VAuction V001: initial schema (SQLite).
-- Собственные таблицы аукциона; ничего общего с таблицами VEconomy.
-- Завершённые лоты НЕ удаляются (ретеншн — внешние задачи).
-- =====================================================================

CREATE TABLE auction_listings (
    listing_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_uuid         VARCHAR(36)    NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    item_blob           BLOB           NOT NULL,
    item_codec_version  VARCHAR(32)    NOT NULL,
    item_hash           VARCHAR(64)    NOT NULL,
    item_registry_id    VARCHAR(255)   NOT NULL,
    item_display_name   VARCHAR(512)   NOT NULL,
    item_search_name    VARCHAR(512)   NOT NULL,
    quantity            INTEGER        NOT NULL,
    price_minor         BIGINT         NOT NULL,
    listing_fee_minor   BIGINT         NOT NULL,
    commission_bps      INTEGER        NOT NULL,
    created_at          BIGINT         NOT NULL,
    expires_at          BIGINT         NOT NULL,
    updated_at          BIGINT         NOT NULL,
    buyer_uuid          VARCHAR(36),
    reservation_id      VARCHAR(128),
    reserved_at         BIGINT,
    reserved_until      BIGINT,
    cancel_reason       VARCHAR(512),
    admin_actor_uuid    VARCHAR(36),
    version             INTEGER NOT NULL DEFAULT 0,
    CHECK (price_minor > 0),
    CHECK (quantity > 0),
    CHECK (expires_at > created_at),
    CHECK (listing_fee_minor >= 0),
    CHECK (commission_bps >= 0),
    CHECK (status IN ('ACTIVE','RESERVED','SOLD','CANCELLED','EXPIRED','FAILED'))
);

CREATE INDEX idx_listings_status_created ON auction_listings (status, created_at);
CREATE INDEX idx_listings_status_price   ON auction_listings (status, price_minor);
CREATE INDEX idx_listings_seller_status  ON auction_listings (seller_uuid, status);
CREATE INDEX idx_listings_buyer_status   ON auction_listings (buyer_uuid, status);
CREATE INDEX idx_listings_status_expires ON auction_listings (status, expires_at);
CREATE INDEX idx_listings_item_search    ON auction_listings (item_search_name);

CREATE TABLE auction_deliveries (
    delivery_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    dedupe_key          VARCHAR(191) UNIQUE NOT NULL,
    player_uuid         VARCHAR(36)    NOT NULL,
    listing_id          BIGINT         NOT NULL,
    operation_id        VARCHAR(64)    NOT NULL,
    delivery_type       VARCHAR(32)    NOT NULL,
    state               VARCHAR(16)    NOT NULL,
    item_blob           BLOB           NOT NULL,
    item_codec_version  VARCHAR(32)    NOT NULL,
    item_hash           VARCHAR(64)    NOT NULL,
    item_registry_id    VARCHAR(255)   NOT NULL,
    item_display_name   VARCHAR(512)   NOT NULL,
    item_search_name    VARCHAR(512)   NOT NULL,
    quantity            INTEGER        NOT NULL,
    created_at          BIGINT         NOT NULL,
    claimable_at        BIGINT,
    claim_started_at    BIGINT,
    claimed_at          BIGINT,
    claim_token         VARCHAR(64),
    last_error          TEXT,
    version             INTEGER NOT NULL DEFAULT 0,
    CHECK (quantity > 0),
    CHECK (delivery_type IN ('PURCHASED','CANCELLED_RETURN','EXPIRED_RETURN','ADMIN_RETURN','COMPENSATION')),
    CHECK (state IN ('PENDING','CLAIMABLE','CLAIMING','CLAIMED','FAILED'))
);

CREATE INDEX idx_deliveries_player_state ON auction_deliveries (player_uuid, state);
CREATE INDEX idx_deliveries_state_claimable ON auction_deliveries (state, claimable_at);

CREATE TABLE auction_sales (
    sale_id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id              BIGINT UNIQUE NOT NULL,
    purchase_operation_id   VARCHAR(64) UNIQUE NOT NULL,
    seller_uuid             VARCHAR(36)    NOT NULL,
    buyer_uuid              VARCHAR(36)    NOT NULL,
    gross_minor             BIGINT         NOT NULL,
    commission_minor        BIGINT         NOT NULL,
    seller_net_minor        BIGINT         NOT NULL,
    escrow_reference        VARCHAR(191) UNIQUE NOT NULL,
    item_hash               VARCHAR(64)    NOT NULL,
    sold_at                 BIGINT         NOT NULL,
    CHECK (gross_minor = commission_minor + seller_net_minor),
    CHECK (gross_minor > 0),
    CHECK (commission_minor >= 0),
    CHECK (seller_net_minor >= 0)
);

CREATE INDEX idx_sales_seller ON auction_sales (seller_uuid);
CREATE INDEX idx_sales_buyer  ON auction_sales (buyer_uuid);

CREATE TABLE auction_operation_log (
    operation_id    VARCHAR(64) PRIMARY KEY,
    listing_id      BIGINT,
    operation_type  VARCHAR(32) NOT NULL,
    phase           VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    actor_uuid      VARCHAR(36),
    target_uuid     VARCHAR(36),
    idempotency_key VARCHAR(191) UNIQUE NOT NULL,
    payload_json    TEXT,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_retry_at   BIGINT,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    CHECK (operation_type IN ('CREATE_LISTING','PURCHASE','CANCEL','EXPIRE','CLAIM','ADMIN_CANCEL','RECOVERY')),
    CHECK (status IN ('RUNNING','COMPLETED','COMPENSATING','FAILED','MANUAL_REVIEW'))
);

CREATE INDEX idx_operations_listing        ON auction_operation_log (listing_id);
CREATE INDEX idx_operations_status_retry  ON auction_operation_log (status, next_retry_at);