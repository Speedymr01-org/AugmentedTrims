package com.Speedymr01.AugmentedTrims;

import com.Speedymr01.AugmentedTrims.command.TrimsCommand;
import com.Speedymr01.AugmentedTrims.config.TrimPowerConfig;
import com.Speedymr01.AugmentedTrims.listener.TrimListener;
import com.Speedymr01.AugmentedTrims.power.TrimPowerApplier;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * AugmentedTrims - armour trim materials grant tiered powers.
 *
 * <p>The power table is fully data driven ({@code trims.yml}) and materials are resolved
 * through the registry by key, so Minecraft drops that add new trim materials need no
 * plugin update. One jar covers the whole 26.x line: {@code api-version} is a minimum
 * marker, pinned here to 26.1.2.
 */
public final class AugmentedTrims extends JavaPlugin {

    private TrimPowerConfig trimConfig;
    private TrimPowerApplier applier;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.trimConfig = TrimPowerConfig.load(this);
        this.applier = new TrimPowerApplier(this, trimConfig);

        getServer().getPluginManager().registerEvents(new TrimListener(this, applier), this);

        PluginCommand command = getCommand("trims");
        if (command != null) {
            TrimsCommand executor = new TrimsCommand(this, trimConfig, applier);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'trims' is missing from plugin.yml - /trims will not work.");
        }

        startRefreshTask();
        getLogger().info("Enabled - trim powers refresh every " + applier.intervalTicks() + " tick(s).");
    }

    @Override
    public void onDisable() {
        stopRefreshTask();
    }

    /** Reloads config.yml and trims.yml, then restarts the refresh task with the new interval. */
    public void reloadTrimPlugin() {
        reloadConfig();
        trimConfig.reload();
        applier.reloadSettings();
        startRefreshTask();
        getLogger().info("Reloaded config.yml and " + TrimPowerConfig.FILE_NAME);
    }

    private void startRefreshTask() {
        stopRefreshTask();
        int interval = Math.max(5, applier.intervalTicks());
        refreshTask = getServer().getScheduler().runTaskTimer(this, applier::refreshAll, interval, interval);
    }

    private void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    public TrimPowerConfig trimConfig() {
        return trimConfig;
    }

    public TrimPowerApplier applier() {
        return applier;
    }
}
