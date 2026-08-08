package com.valorcraft.vauction.application;

/**
 * Hard operation budget plus a soft deadline. The deadline is checked only before an operation;
 * once SQL/economy work starts it is allowed to complete atomically.
 */
public final class WorkBudget {
    private int remaining;
    private final long deadlineNanos;

    private WorkBudget(int operations, long deadlineNanos) {
        this.remaining = Math.max(0, operations);
        this.deadlineNanos = deadlineNanos;
    }

    public static WorkBudget timed(int operations, long maxNanos) {
        long now = System.nanoTime();
        long deadline = maxNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + Math.max(0L, maxNanos);
        return new WorkBudget(operations, deadline);
    }

    public static WorkBudget operations(int operations) {
        return new WorkBudget(operations, Long.MAX_VALUE);
    }

    public boolean tryAcquire() {
        if (remaining <= 0 || System.nanoTime() >= deadlineNanos) {
            return false;
        }
        remaining--;
        return true;
    }

    public boolean exhausted() {
        return remaining <= 0 || System.nanoTime() >= deadlineNanos;
    }

    public int remaining() {
        return remaining;
    }
}
