-- =====================================================================
-- VAuction V003: единый рынок (order-book) поверх старой «лот + заявка».
-- Старые таблицы НЕ удаляются (данные сохраняются) — создаются новые:
--   auction_orders (обе стороны: BUY/SELL, частичное исполнение)
--   auction_trades (каждый fill как отдельная запись с аудитом)
-- auction_operation_log пересоздаётся с CHECK, допускающим ВСЕ значения
-- Java-enum (старый CHECK расходился с кодом — INSERT падал бы в живой БД).
-- =====================================================================

-- ---------------------------------------------------------------
-- auction_orders: единый стакан. price_per_unit — long, minor.
-- Индексы на (market_key, side, status, price_per_unit, created_at)
-- ц. приоритет: SQL ORDER BY + LIMIT — без полного прохода в Java.
-- ---------------------------------------------------------------
CREATE TABLE auction_orders (
    order_id           TEXT PRIMARY KEY,              -- UUID как строка
    owner_uuid         TEXT NOT NULL,
    side               VARCHAR(4)  NOT NULL CHECK (side IN ('BUY','SELL')),
    status             VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','FILLED','CANCELLED','EXPIRED','MANUAL_REVIEW','LEGACY_LOCKED')),
    market_key         VARCHAR(191) NOT NULL,
    item_blob          BLOB NOT NULL,
    item_codec_version VARCHAR(32) NOT NULL,
    item_hash          VARCHAR(64) NOT NULL,
    item_registry_id   VARCHAR(255) NOT NULL,
    item_display_name  VARCHAR(512) NOT NULL,
    item_search_name   VARCHAR(512) NOT NULL,
    item_snapshot_qty  INTEGER NOT NULL DEFAULT 1 CHECK (item_snapshot_qty > 0),
    price_per_unit     BIGINT NOT NULL CHECK (price_per_unit > 0),
    original_quantity  INTEGER NOT NULL CHECK (original_quantity > 0),
    remaining_quantity INTEGER NOT NULL CHECK (remaining_quantity >= 0 AND remaining_quantity <= original_quantity),
    filled_quantity    INTEGER NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0 AND filled_quantity <= original_quantity),
    escrow_reference   VARCHAR(191),
    ref_epoch          INTEGER NOT NULL DEFAULT 0 CHECK (ref_epoch >= 0),
    created_at         BIGINT NOT NULL,
    updated_at         BIGINT NOT NULL,
    version            INTEGER NOT NULL DEFAULT 0,
    CHECK (remaining_quantity + filled_quantity = original_quantity)
);

CREATE INDEX idx_orders_buyer   ON auction_orders (market_key, side, status, price_per_unit, created_at);
CREATE INDEX idx_orders_seller  ON auction_orders (side, status, created_at);
CREATE INDEX idx_orders_item    ON auction_orders (item_search_name);

-- ---------------------------------------------------------------
-- auction_trades: каждый исполненный fill (частичный или полный).
-- seller_net = gross - commission в той же строке; CHECK дублирует инвариант.
-- ---------------------------------------------------------------
CREATE TABLE auction_trades (
    trade_id         TEXT PRIMARY KEY,
    market_key       VARCHAR(191) NOT NULL,
    buy_order_id     TEXT NOT NULL,
    sell_order_id    TEXT NOT NULL,
    maker_side       VARCHAR(4) NOT NULL CHECK (maker_side IN ('BUY','SELL')),
    execution_price  BIGINT NOT NULL CHECK (execution_price > 0),
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    gross_minor      BIGINT NOT NULL CHECK (gross_minor > 0),
    commission_minor BIGINT NOT NULL CHECK (commission_minor >= 0),
    seller_net_minor BIGINT NOT NULL,
    buyer_uuid       TEXT NOT NULL,
    seller_uuid      TEXT NOT NULL,
    escrow_reference TEXT,
    state            VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SETTLED','FAILED','MANUAL_REVIEW')),
    created_at       BIGINT NOT NULL,
    settled_at       BIGINT,
    version          INTEGER NOT NULL DEFAULT 0,
    CHECK (seller_net_minor >= 0),
    CHECK (gross_minor = commission_minor + seller_net_minor),
    FOREIGN KEY (buy_order_id) REFERENCES auction_orders (order_id),
    FOREIGN KEY (sell_order_id) REFERENCES auction_orders (order_id)
);

CREATE INDEX idx_trades_market ON auction_trades (market_key, created_at);
CREATE INDEX idx_trades_buyer  ON auction_trades (buyer_uuid, created_at);
CREATE INDEX idx_trades_seller ON auction_trades (seller_uuid, created_at);
CREATE INDEX idx_trades_buy    ON auction_trades (buy_order_id);
CREATE INDEX idx_trades_sell   ON auction_trades (sell_order_id);

-- ---------------------------------------------------------------
-- auction_operation_log: полный набор типов текущего и будущего кода.
-- ---------------------------------------------------------------
CREATE TABLE auction_operation_log_new (
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
    CHECK (operation_type IN ('CREATE_SELL_ORDER','CREATE_BUY_ORDER','EXECUTE_FILL','CANCEL_ORDER','CLAIM_MAIL','RECOVERY','ADMIN_CANCEL','EXPIRE','CREATE_LISTING','PURCHASE','CANCEL','CLAIM','MIGRATION')),
    CHECK (status IN ('RUNNING','COMPLETED','COMPENSATING','FAILED','MANUAL_REVIEW'))
);

INSERT INTO auction_operation_log_new
    (operation_id, listing_id, operation_type, phase, status, actor_uuid, target_uuid,
     idempotency_key, payload_json, attempt_count, last_error, next_retry_at,
     created_at, updated_at)
SELECT operation_id, listing_id, operation_type, phase, status, actor_uuid, target_uuid,
       idempotency_key, payload_json, attempt_count, last_error, next_retry_at,
       created_at, updated_at
FROM auction_operation_log;

DROP TABLE auction_operation_log;
ALTER TABLE auction_operation_log_new RENAME TO auction_operation_log;

CREATE INDEX idx_operations_listing       ON auction_operation_log (listing_id);
CREATE INDEX idx_operations_status_retry  ON auction_operation_log (status, next_retry_at);

-- ---------------------------------------------------------------
-- Перенос legacy-строк в новый стакан (данные сохраняются, ликвидность
-- прежних отбразится): стаканы/заявки по item_hash legacy под legacy-ключом
-- 'legacy:<hash>' (в новый формат blob не конвертируем).
-- Условия матчинга с новым code не совместимы, но существующие строки
-- не теряются и видны как «наследственные» — для серверов на старой схеме.
-- ---------------------------------------------------------------
INSERT INTO auction_orders (order_id, owner_uuid, side, status, market_key,
                            item_blob, item_codec_version, item_hash,
                            item_registry_id, item_display_name, item_search_name,
                            item_snapshot_qty,
                            price_per_unit, original_quantity, remaining_quantity,
                            filled_quantity, escrow_reference, ref_epoch,
                            created_at, updated_at, version)
SELECT 'legacy-sell-' || listing_id, seller_uuid, 'SELL', 'ACTIVE',
       'legacy:' || item_hash, item_blob, item_codec_version, item_hash,
       item_registry_id, item_display_name, item_search_name, quantity,
       MAX(price_minor / quantity, 1), quantity, quantity, 0, NULL, 0,
       created_at, updated_at, 0
FROM auction_listings
WHERE status = 'ACTIVE';

INSERT INTO auction_orders (order_id, owner_uuid, side, status, market_key,
                            item_blob, item_codec_version, item_hash,
                            item_registry_id, item_display_name, item_search_name,
                            item_snapshot_qty,
                            price_per_unit, original_quantity, remaining_quantity,
                            filled_quantity, escrow_reference, ref_epoch,
                            created_at, updated_at, version)
SELECT 'legacy-buy-' || buy_order_id, buyer_uuid, 'BUY', 'ACTIVE',
       'legacy:' || item_hash, item_blob, item_codec_version, item_hash,
       item_registry_id, item_display_name, item_search_name, 1,
       price_per_unit, total_requested, total_requested - fulfilled_amount,
       fulfilled_amount, 'vauction:buy:' || buy_order_id || ':' || ref_epoch,
       ref_epoch, created_at, updated_at, 0
FROM auction_buy_orders
WHERE active = 1;