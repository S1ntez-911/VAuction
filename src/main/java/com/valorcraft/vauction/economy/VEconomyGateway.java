package com.valorcraft.vauction.economy;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowLookupResult;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Продовая реализация {@link EconomyGateway} поверх публичного API VEconomy
 * ({@code EconomyCore.api()}/{@code EconomyCore.escrow()}). Мод загружается
 * после economy_core (mods.toml), поэтому здесь безопасно обращаться к API.
 * <p>
 * ВСЕ вызовы — идемпотентные по {@code idempotencyKey}; каждая ошибка денежного
 * слоя пробрасывается в понятном виде ({@code FAILED}) — бизнес-слой решает,
 * что откатывать.
 */
public final class VEconomyGateway implements EconomyGateway {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    @Override
    public boolean isAvailable() {
        return EconomyCore.isStarted();
    }

    @Override
    public long getBalance(UUID playerId) {
        if (!isAvailable()) {
            return -1L;
        }
        return EconomyCore.api().getBalance(playerId);
    }

    @Override
    public boolean has(UUID playerId, long amount) {
        if (!isAvailable() || amount <= 0) {
            return false;
        }
        return EconomyCore.api().getBalance(playerId) >= amount;
    }

    @Override
    public boolean withdraw(UUID playerId, long amount, String reason, String idempotencyKey) {
        if (!isAvailable() || playerId == null || amount <= 0) {
            return false;
        }
        TransactionContext ctx = TransactionContext.of(
                TransactionType.PLUGIN_OPERATION, playerId, reason, idempotencyKey);
        var result = EconomyCore.api().withdraw(playerId, amount, ctx);
        if (!result.isSuccess()) {
            LOGGER.warn("VEconomy withdraw failed: player={}, amount={}, result={}",
                    playerId, amount, result.status());
            return false;
        }
        return true;
    }

    @Override
    public boolean deposit(UUID playerId, long amount, String reason, String idempotencyKey) {
        if (!isAvailable() || playerId == null || amount <= 0) {
            return false;
        }
        TransactionContext ctx = TransactionContext.of(
                TransactionType.PLUGIN_OPERATION, null, reason, idempotencyKey);
        var result = EconomyCore.api().deposit(playerId, amount, ctx);
        if (!result.isSuccess()) {
            LOGGER.warn("VEconomy deposit failed: player={}, amount={}, result={}",
                    playerId, amount, result.status());
            return false;
        }
        return true;
    }

    @Override
    public ReserveResult reserve(UUID ownerId, long amount, String referenceId,
                                 String reason, String idempotencyKey) {
        if (!isAvailable()) {
            return new ReserveResult(ReserveStatus.FAILED, amount, referenceId);
        }
        EscrowResult r = EconomyCore.escrow().reserveMoney(ownerId, amount, referenceId,
                ctx(TransactionType.ESCROW_RESERVE, reason, idempotencyKey));
        return switch (r.status()) {
            case SUCCESS -> new ReserveResult(ReserveStatus.SUCCESS, r.reservedAmount(), referenceId);
            case ALREADY_RESERVED -> new ReserveResult(ReserveStatus.ALREADY_RESERVED, r.reservedAmount(), referenceId);
            case INSUFFICIENT_FUNDS -> new ReserveResult(ReserveStatus.INSUFFICIENT_FUNDS, amount, referenceId);
            case CONFLICT, WRONG_STATE, DUPLICATE, INVALID_AMOUNT, LIMIT_EXCEEDED, ACCOUNT_DISABLED,
                    INVALID_CREDITS, NOT_FOUND, ALREADY_SETTLED, ALREADY_RELEASED, DATABASE_ERROR
                    -> new ReserveResult(ReserveStatus.CONFLICT, amount, referenceId);
        };
    }

    @Override
    public SettleResult settle(String referenceId, List<Credit> credits,
                               String reason, String idempotencyKey) {
        if (!isAvailable()) {
            return new SettleResult(SettleStatus.FAILED, 0L, referenceId);
        }
        List<EscrowCredit> veCredits = new ArrayList<>(credits.size());
        for (Credit c : credits) {
            veCredits.add(new EscrowCredit(c.recipientId(), c.amount(), c.role()));
        }
        EscrowResult r = EconomyCore.escrow().settleMoney(referenceId, veCredits,
                ctx(TransactionType.ESCROW_CAPTURE, reason, idempotencyKey));
        return switch (r.status()) {
            case SUCCESS -> new SettleResult(SettleStatus.SUCCESS, r.reservedAmount(), referenceId);
            case ALREADY_SETTLED -> new SettleResult(SettleStatus.ALREADY_SETTLED, r.reservedAmount(), referenceId);
            case NOT_FOUND -> new SettleResult(SettleStatus.NOT_FOUND, r.reservedAmount(), referenceId);
            case CONFLICT, WRONG_STATE, INVALID_CREDITS, INVALID_AMOUNT, DUPLICATE, LIMIT_EXCEEDED,
                    ACCOUNT_DISABLED, INSUFFICIENT_FUNDS, ALREADY_RESERVED, ALREADY_RELEASED, DATABASE_ERROR
                    -> new SettleResult(SettleStatus.CONFLICT, r.reservedAmount(), referenceId);
        };
    }

    @Override
    public ReleaseResult release(String referenceId, String reason, String idempotencyKey) {
        if (!isAvailable()) {
            return new ReleaseResult(ReleaseStatus.FAILED, referenceId);
        }
        EscrowResult r = EconomyCore.escrow().releaseMoney(referenceId,
                ctx(TransactionType.ESCROW_RELEASE, reason, idempotencyKey));
        return switch (r.status()) {
            case SUCCESS -> new ReleaseResult(ReleaseStatus.SUCCESS, referenceId);
            case ALREADY_RELEASED -> new ReleaseResult(ReleaseStatus.ALREADY_RELEASED, referenceId);
            case NOT_FOUND -> new ReleaseResult(ReleaseStatus.NOT_FOUND, referenceId);
            case CONFLICT, WRONG_STATE, INVALID_AMOUNT, INVALID_CREDITS, DUPLICATE, LIMIT_EXCEEDED,
                    ACCOUNT_DISABLED, INSUFFICIENT_FUNDS, ALREADY_RESERVED, ALREADY_SETTLED, DATABASE_ERROR
                    -> new ReleaseResult(ReleaseStatus.CONFLICT, referenceId);
        };
    }

    @Override
    public LookupResult find(String referenceId) {
        if (!isAvailable()) {
            return LookupResult.failed();
        }
        EscrowLookupResult r = EconomyCore.escrow().findEscrow(referenceId);
        if (r.status() == EscrowLookupResult.Status.DATABASE_ERROR) {
            return LookupResult.failed();
        }
        if (r.status() == EscrowLookupResult.Status.NOT_FOUND || r.snapshotOrNull() == null) {
            return LookupResult.notFound();
        }
        var s = r.snapshot();
        HoldingState state = switch (s.state()) {
            case RESERVED -> HoldingState.RESERVED;
            case CAPTURED -> HoldingState.CAPTURED;
            case RELEASED -> HoldingState.RELEASED;
        };
        List<Credit> credits = new ArrayList<>();
        for (EscrowCredit c : s.settlement()) {
            credits.add(new Credit(c.recipientId(), c.amount(), c.role()));
        }
        return new LookupResult(LookupStatus.FOUND,
                new Holding(s.ownerId(), s.amount(), state, credits));
    }

    @Override
    public UUID treasury() {
        return EconomyCore.escrow().treasuryUuid();
    }

    private static TransactionContext ctx(TransactionType type, String reason, String idempotencyKey) {
        return TransactionContext.of(type, null, reason, idempotencyKey);
    }
}