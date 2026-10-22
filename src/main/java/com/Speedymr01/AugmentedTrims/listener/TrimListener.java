package com.Speedymr01.AugmentedTrims.listener;

import com.Speedymr01.AugmentedTrims.AugmentedTrims;
import com.Speedymr01.AugmentedTrims.power.TrimPowerApplier;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Keeps trim powers in sync with what players are actually wearing.
 *
 * <p>Armour changes are refreshed on the next tick: the inventory is updated immediately
 * after {@link PlayerArmorChangeEvent} fires, so reading it in the same tick sees the old state.
 */
public final class TrimListener implements Listener {

    private final AugmentedTrims plugin;
    private final TrimPowerApplier applier;

    public TrimListener(AugmentedTrims plugin, TrimPowerApplier applier) {
        this.plugin = plugin;
        this.applier = applier;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                applier.refresh(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applier.refresh(event.getPlayer()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applier.refresh(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applier.forget(event.getPlayer());
    }
}
