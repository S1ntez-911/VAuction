package com.valorcraft.vauction.gui;

import com.valorcraft.veconomy.EconomyCore;

/** Read-only compatibility bridge to VEconomy's public formatter accessor. */
final class CurrencyText {
    private CurrencyText() {}

    static String format(long minor) {
        try {
            Object formatter = EconomyCore.class.getMethod("formatter").invoke(null);
            if (formatter != null) {
                return String.valueOf(formatter.getClass().getMethod("format", long.class)
                        .invoke(formatter, minor));
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Older API snapshots do not expose the formatter type; raw minor units remain exact.
        }
        return Long.toString(minor);
    }
}
