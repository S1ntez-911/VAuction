-- Заявки на покупку (BuyOrder): покупатель замораживает средства под референс
-- экономики; продавец исполняет заявку частями, остаток перезамораживается под
-- следующий ref_epoch. Одна заявка = один escrow-референс за эпоху.
CREATE TABLE IF NOT EXISTS auction_buy_orders (
    buy_order_id      TEXT PRIMARY KEY,             -- UUID в виде строки
    buyer_uuid        TEXT NOT NULL,
    item_blob         BLOB NOT NULL,                -- сжатый NBT (наш кодек)
    item_codec_version TEXT NOT NULL,
    item_hash         TEXT NOT NULL,
    item_registry_id  TEXT NOT NULL,
    item_display_name TEXT NOT NULL DEFAULT '',
    item_search_name  TEXT NOT NULL DEFAULT '',
    quantity          INTEGER NOT NULL DEFAULT 1,
    price_per_unit    INTEGER NOT NULL CHECK (price_per_unit > 0),
    total_requested   INTEGER NOT NULL CHECK (total_requested > 0),
    fulfilled_amount  INTEGER NOT NULL DEFAULT 0 CHECK (fulfilled_amount >= 0 AND fulfilled_amount <= total_requested),
    active            INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    ref_epoch         INTEGER NOT NULL DEFAULT 0,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    version           INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_buy_orders_buyer ON auction_buy_orders (buyer_uuid, active);
CREATE INDEX IF NOT EXISTS idx_buy_orders_item  ON auction_buy_orders (item_registry_id, item_search_name, active);