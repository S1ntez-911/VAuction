package com.valorcraft.vauction.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

final class MarketSounds {
    private MarketSounds() {}

    static void navigation(ServerPlayer player) { play(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.35f, 1.0f); }
    static void page(ServerPlayer player) { play(player, SoundEvents.BOOK_PAGE_TURN, 0.45f, 1.0f); }
    static void adjust(ServerPlayer player, boolean increase) { play(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, increase ? 1.2f : 0.8f); }
    static void preset(ServerPlayer player, boolean all) { play(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.28f, all ? 0.95f : 1.1f); }
    static void success(ServerPlayer player) { play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.45f, 1.15f); }
    static void claim(ServerPlayer player) { play(player, SoundEvents.ITEM_PICKUP, 0.5f, 1.0f); }
    static void error(ServerPlayer player) { play(player, SoundEvents.VILLAGER_NO, 0.35f, 1.0f); }

    private static void play(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }
}
