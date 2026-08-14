package com.valorcraft.vauction.bootstrap;

import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.application.AuctionReadService;
import com.valorcraft.vauction.application.DeliveryService;
import com.valorcraft.vauction.application.InventoryOps;
import com.valorcraft.vauction.application.ListingService;
import com.valorcraft.vauction.application.ServerInventoryOps;
import com.valorcraft.vauction.application.SimpleAuctionService;
import com.valorcraft.vauction.application.MarketNotificationService;
import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.economy.VEconomyGateway;
import com.valorcraft.vauction.item.ExactItemMarketKeyStrategy;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.item.MarketCategoryConfig;
import com.valorcraft.vauction.item.MarketCategoryClassifier;
import com.valorcraft.vauction.persistence.MarketCategoryRepository;
import com.valorcraft.vauction.persistence.AuctionHealthRepository;
import com.valorcraft.vauction.persistence.BuyOrderRepository;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.ListingRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import com.valorcraft.vauction.persistence.SaleRepository;
import com.valorcraft.vauction.persistence.TradeRepository;
import com.valorcraft.vauction.persistence.PlayerMarketStateRepository;
import com.valorcraft.vauction.recovery.RecoveryService;
import com.valorcraft.vauction.gui.UiConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import com.valorcraft.vauction.application.AuctionWorkLimits;
import com.valorcraft.vauction.application.WorkBudget;

/**
 * Compose root VAuction: конфиг → проверка VEconomy → БД + миграции → схема → сервисы.
 * <p>
 * При сбое миграции/схемы мод НЕ продолжает работать в «частичном» состоянии:
 * state = FAILED, функциональность отключена, в лог — явная ошибка.
 */
public final class VAuctionCore {

    public enum State {
        NEW, RUNNING, FAILED, DISABLED, STOPPED
    }

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private static volatile VAuctionCore instance;

    private State state = State.NEW;
    private AuctionSettings settings;
    private DatabaseManager database;
    private EconomyGateway economyGateway;
    private ItemStackCodec codec;
    private ListingRepository listings;
    private BuyOrderRepository buyOrders;
    private DeliveryRepository deliveries;
    private SaleRepository sales;
    private OperationRepository operations;
    private OrderRepository orders;
    private TradeRepository trades;
    private InventoryOps inventoryOps;
    private ListingService listingService;
    private DeliveryService deliveryService;
    private AuctionService auctionService;
    private AuctionReadService auctionReadService;
    private RecoveryService recoveryService;
    private SimpleAuctionService simpleAuctionService;
    private MarketNotificationService notificationService;

    private VAuctionCore() {}

    public static VAuctionCore instance() {
        if (instance == null) {
            instance = new VAuctionCore();
        }
        return instance;
    }

    /** Старт серверного ядра (вызывается на ServerStartedEvent). */
    public static synchronized void start(Path databasePath, MinecraftServer server) {
        VAuctionCore core = instance();
        if (core.state == State.RUNNING || core.state == State.STOPPED) {
            return;
        }
        try {
            // 1. конфиг
            AuctionSettings settings = AuctionConfig.snapshot();
            core.settings = settings;
            if (!settings.enabled()) {
                core.state = State.DISABLED;
                LOGGER.warn("VAuction отключён конфигом (enabled=false), функциональность не активна");
                return;
            }
            // 1a. конфиг интерфейса (тексты, цвета, лор, кнопки) — при ошибке остаются дефолты
            UiConfig.start(FMLPaths.CONFIGDIR.get());
            MarketCategoryConfig.start(FMLPaths.CONFIGDIR.get());
            // 2. проверка VEconomy (мод обязателен в mods.toml; проверяем фактическую готовность API)
            core.economyGateway = new VEconomyGateway();
            if (!core.economyGateway.isAvailable()) {
                throw new IllegalStateException("VEconomy недоступна (мод economy_core не запущен?)");
            }
            LOGGER.info("Экономика VEconomy готова");

            // 3-4-5. БД + миграции + проверка схемы (при сбое — FAILED и останов)
            core.database = DatabaseManager.openSqlite(databasePath);
            core.database.initialize();
            core.codec = new ItemStackCodec(settings.maxCompressedItemBytes(), settings.maxUncompressedItemBytes());

            // 6. репозитории и сервисы
            core.listings = new ListingRepository();
            core.buyOrders = new BuyOrderRepository();
            core.deliveries = new DeliveryRepository();
            core.sales = new SaleRepository();
            core.operations = new OperationRepository();
            core.orders = new OrderRepository();
            core.trades = new TradeRepository();
            core.inventoryOps = new ServerInventoryOps(() -> server);
            core.listingService = new ListingService(core.database, core.listings, core.operations, core.codec);
            core.deliveryService = new DeliveryService(core.database, core.deliveries);
            core.simpleAuctionService = new SimpleAuctionService(core.database, core.listings,
                    core.sales, core.deliveries, core.operations, core.codec,
                    core.economyGateway, core.inventoryOps, core.settings);
            ExactItemMarketKeyStrategy marketKeys = new ExactItemMarketKeyStrategy(core.codec);
            core.auctionService = new AuctionService(core.database, core.orders, core.trades,
                    core.operations, core.deliveries, core.codec,
                    marketKeys,
                    core.economyGateway, core.inventoryOps, core.settings);
            core.auctionReadService = new AuctionReadService(core.database, core.orders,
                    core.deliveries, core.codec, marketKeys, core.settings.allowSelfPurchase(),
                    core.settings.catalogueHistoryDays());
            backfillMarketCategories(core.database, core.codec);
            core.recoveryService = new RecoveryService(core.database, core.orders, core.trades,
                    core.deliveries, core.economyGateway, core.auctionService);
            PlayerMarketStateRepository playerStates = new PlayerMarketStateRepository();
            core.notificationService = new MarketNotificationService(core.database, core.orders,
                    core.trades, core.deliveries, playerStates, server);
            core.auctionService.setSettledTradeListener(core.notificationService::onSettled);

            // 6а. автоматическое восстановление после краха (идемпотентно)
            RecoveryService.ScanReport recovery = core.recoveryService.startupScan();
            int simpleQuarantined = core.simpleAuctionService.quarantinePendingCreations(128);
            int simpleRecovered = core.simpleAuctionService.recoverReserved(128);
            int retiredOrders = retirePlayerOrderBook(core.database, core.orders, core.auctionService);
            if (recovery.total() > 0) {
                LOGGER.info("VAuction recovery: fills={}, escrows={}, claims={}, review={}",
                        recovery.fillsFinished(), recovery.escrowsRestored(),
                        recovery.claimsQuarantined(), recovery.ordersInManualReview());
            }
            if (simpleRecovered > 0) {
                LOGGER.info("VAuction simple listings recovered: {}", simpleRecovered);
            }
            if (simpleQuarantined > 0) {
                LOGGER.warn("VAuction simple listings require manual review: {}", simpleQuarantined);
            }
            if (retiredOrders > 0) {
                LOGGER.info("VAuction retired old player orders with safe refunds: {}", retiredOrders);
            }

            core.state = State.RUNNING;
            instance = core;
            // 7. итоговый лог запуска
            LOGGER.info("VAuction запущен: БД={}, схема v{}, политика предметов={}, комиссия={} bps, "
                            + "стакан={}", databasePath, core.database.schemaVersion(),
                    settings.itemPolicyMode(), settings.commissionBps(),
                    "orders+trades");
        } catch (Throwable t) {
            core.state = State.FAILED;
            if (core.database != null) {
                try {
                    core.database.close();
                } catch (Throwable ignored) {
                }
                core.database = null;
            }
            LOGGER.error("VAuction НЕ запущен: функциональность отключена ({}). Проблема может быть "
                    + "в конфигурации, БД или отсутствии VEconomy.", t.getMessage(), t);
        }
    }

    public static synchronized void shutdown() {
        VAuctionCore core = instance;
        if (core == null || core.database == null) {
            return;
        }
        try {
            core.database.close();
        } catch (Throwable t) {
            LOGGER.warn("Ошибка при закрытии БД: {}", t.getMessage());
        } finally {
            core.database = null;
            core.state = State.STOPPED;
        }
        LOGGER.info("VAuction остановлен");
    }

    /* --------------------------------- accessors -------------------------------- */

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public AuctionSettings settings() {
        return settings;
    }

    public DatabaseManager database() {
        return database;
    }

    public EconomyGateway economyGateway() {
        return economyGateway;
    }

    public ItemStackCodec codec() {
        return codec;
    }

    public ListingRepository listings() {
        return listings;
    }

    public BuyOrderRepository buyOrders() {
        return buyOrders;
    }

    public DeliveryRepository deliveries() {
        return deliveries;
    }

    public SaleRepository sales() {
        return sales;
    }

    public OperationRepository operations() {
        return operations;
    }

    public ListingService listingService() {
        return listingService;
    }

    public AuctionService auctionService() {
        return auctionService;
    }

    public SimpleAuctionService simpleAuctionService() {
        return simpleAuctionService;
    }

    private static void backfillMarketCategories(DatabaseManager database, ItemStackCodec codec) {
        MarketCategoryRepository categories = new MarketCategoryRepository();
        int classified = 0;
        String cursor = "";
        while (true) {
            String after = cursor;
            var pending = database.query(c -> categories.marketsAfter(c, after, 128));
            if (pending.isEmpty()) break;
            for (var market : pending) {
                try {
                    var category = MarketCategoryClassifier.classify(codec.decode(market.item()));
                    database.inTransaction(c -> {
                        categories.upsert(c, market.marketKey(), category, System.currentTimeMillis());
                        return null;
                    });
                    classified++;
                } catch (Exception e) {
                    database.inTransaction(c -> {
                        categories.upsert(c, market.marketKey(), com.valorcraft.vauction.item.MarketCategory.OTHER,
                                System.currentTimeMillis());
                        return null;
                    });
                    classified++;
                }
            }
            cursor = pending.get(pending.size() - 1).marketKey();
        }
        if (classified > 0) LOGGER.info("VAuction categories classified: {} markets", classified);
    }

    /** The old order book is no longer public; safely return every ordinary active order. */
    private static int retirePlayerOrderBook(DatabaseManager database, OrderRepository orders,
                                             AuctionService auction) {
        int retired = 0;
        while (true) {
            var ids = database.query(c -> orders.activeUuidOrderIds(c, 128));
            if (ids.isEmpty()) return retired;
            int progress = 0;
            for (var id : ids) {
                var order = database.query(c -> orders.findById(c, id).orElse(null));
                if (order != null && auction.cancel(order.ownerUuid(), id, "simple-market migration").isSuccess()) {
                    retired++;
                    progress++;
                }
            }
            if (progress == 0) {
                LOGGER.warn("VAuction could not retire {} old orders; recovery will keep their assets safe", ids.size());
                return retired;
            }
        }
    }

    public String reloadMarketCategories() {
        if (!isRunning() || database == null || codec == null) return "Биржа сейчас недоступна.";
        String error = MarketCategoryConfig.reload();
        if (error != null) return error;
        backfillMarketCategories(database, codec);
        return null;
    }

    public AuctionHealthRepository.Snapshot health() {
        if (!isRunning() || database == null) throw new IllegalStateException("Биржа сейчас недоступна.");
        return database.query(c -> new AuctionHealthRepository().read(c));
    }

    /** Runs only the same bounded recovery slice used by normal server maintenance. */
    public RecoveryService.ScanReport runRecoverySlice() {
        if (!isRunning() || recoveryService == null) throw new IllegalStateException("Биржа сейчас недоступна.");
        return recoveryService.runtimeSlice(WorkBudget.timed(
                AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS,
                AuctionWorkLimits.MAX_MAINTENANCE_NANOS));
    }

    public AuctionReadService auctionReadService() {
        return auctionReadService;
    }

    public DeliveryService deliveryService() {
        return deliveryService;
    }

    public RecoveryService recoveryService() {
        return recoveryService;
    }

    public MarketNotificationService notificationService() {
        return notificationService;
    }
}
