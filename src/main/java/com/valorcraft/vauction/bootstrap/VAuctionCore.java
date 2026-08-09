package com.valorcraft.vauction.bootstrap;

import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.application.AuctionReadService;
import com.valorcraft.vauction.application.DeliveryService;
import com.valorcraft.vauction.application.InventoryOps;
import com.valorcraft.vauction.application.ListingService;
import com.valorcraft.vauction.application.ServerInventoryOps;
import com.valorcraft.vauction.application.MarketNotificationService;
import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.economy.VEconomyGateway;
import com.valorcraft.vauction.item.ExactItemMarketKeyStrategy;
import com.valorcraft.vauction.item.ItemStackCodec;
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
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

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
            ExactItemMarketKeyStrategy marketKeys = new ExactItemMarketKeyStrategy(core.codec);
            core.auctionService = new AuctionService(core.database, core.orders, core.trades,
                    core.operations, core.deliveries, core.codec,
                    marketKeys,
                    core.economyGateway, core.inventoryOps, core.settings);
            core.auctionReadService = new AuctionReadService(core.database, core.orders,
                    core.deliveries, core.codec, marketKeys);
            core.recoveryService = new RecoveryService(core.database, core.orders, core.trades,
                    core.deliveries, core.economyGateway, core.auctionService);
            PlayerMarketStateRepository playerStates = new PlayerMarketStateRepository();
            core.notificationService = new MarketNotificationService(core.database, core.orders,
                    core.trades, core.deliveries, playerStates, server);
            core.auctionService.setSettledTradeListener(core.notificationService::onSettled);

            // 6а. автоматическое восстановление после краха (идемпотентно)
            RecoveryService.ScanReport recovery = core.recoveryService.startupScan();
            if (recovery.total() > 0) {
                LOGGER.info("VAuction recovery: fills={}, escrows={}, claims={}, review={}",
                        recovery.fillsFinished(), recovery.escrowsRestored(),
                        recovery.claimsQuarantined(), recovery.ordersInManualReview());
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
