package com.valorcraft.vauction.application;

/** Central production work limits for code executed on the Minecraft server thread. */
public final class AuctionWorkLimits {
    public static final int MAX_MATCH_FILLS_PER_PUMP = 8;
    public static final int MAX_MATCH_OPERATIONS_PER_PUMP = 16;
    /** Individual maker fills considered by both an immediate quote and its targeted execution. */
    public static final int MAX_IMMEDIATE_MATCH_FILLS = 32;
    public static final int MAX_RUNTIME_RECOVERY_OPERATIONS = 8;
    public static final int MAX_EXPIRY_OPERATIONS = 8;
    public static final int MAX_SERVER_TICK_OPERATIONS = 24;
    public static final int STARTUP_PAGE_SIZE = 250;
    public static final int TARGETED_QUERY_BATCH = 32;
    public static final long MAX_MAINTENANCE_NANOS = 2_000_000L;
    public static final int EXPIRY_INTERVAL_TICKS = 20 * 60;
    public static final int RECOVERY_BASE_TICKS = 20 * 30;
    public static final int RECOVERY_MAX_TICKS = 20 * 300;

    private AuctionWorkLimits() {}
}
