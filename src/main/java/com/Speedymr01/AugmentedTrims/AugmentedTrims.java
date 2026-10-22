package com.Speedymr01.AugmentedTrims;

import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.ability.AbilityManager;
import com.Speedymr01.AugmentedTrims.ability.impl.CopperThunderstrike;
import com.Speedymr01.AugmentedTrims.ability.impl.IronArrowTorrent;
import com.Speedymr01.AugmentedTrims.ability.impl.QuartzRefract;
import com.Speedymr01.AugmentedTrims.command.TrimsCommand;
import com.Speedymr01.AugmentedTrims.config.TrimPowerConfig;
import com.Speedymr01.AugmentedTrims.craft.StarRecipes;
import com.Speedymr01.AugmentedTrims.listener.StarListener;
import com.Speedymr01.AugmentedTrims.listener.TrimListener;
import com.Speedymr01.AugmentedTrims.power.TrimPowerApplier;
import com.Speedymr01.AugmentedTrims.star.AbilityStarFactory;
import com.Speedymr01.AugmentedTrims.star.StarCooldownService;
import com.Speedymr01.AugmentedTrims.star.StarKeys;
import com.Speedymr01.AugmentedTrims.trim.TrimScanner;

import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * AugmentedTrims - armour trim materials grant tiered passive powers and, via attuned
 * Ability Stars, active abilities.
 *
 * <p>Everything is data driven: {@code trims.yml} holds passive powers, {@code config.yml}
 * holds ability tuning. Materials are resolved through the registry by key, so drops that add
 * trim materials need no plugin update.
 */
public final class AugmentedTrims extends JavaPlugin {

    private TrimPowerConfig trimConfig;
    private TrimPowerApplier applier;
    private AbilityStarFactory starFactory;
    private StarCooldownService cooldowns;
    private AbilityManager abilityManager;
    private StarRecipes recipes;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        StarKeys.init(this);

        this.trimConfig = TrimPowerConfig.load(this);
        this.applier = new TrimPowerApplier(this, trimConfig);
        this.starFactory = new AbilityStarFactory(this, trimConfig);
        this.cooldowns = new StarCooldownService(this);

        QuartzRefract quartz = new QuartzRefract(this);
        this.abilityManager = new AbilityManager();
        abilityManager.register(new IronArrowTorrent(this));
        abilityManager.register(quartz);
        abilityManager.register(new CopperThunderstrike(this));

        this.recipes = new StarRecipes(this, starFactory, abilityManager);
        recipes.register();

        getServer().getPluginManager().registerEvents(new TrimListener(this, applier), this);
        getServer().getPluginManager().registerEvents(
                new StarListener(this, starFactory, abilityManager, cooldowns, recipes, quartz), this);

        PluginCommand command = getCommand("trims");
        if (command != null) {
            TrimsCommand executor = new TrimsCommand(this, trimConfig, applier);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'trims' is missing from plugin.yml - /trims will not work.");
        }

        startRefreshTask();
        getLogger().info("Enabled - " + abilityManager.all().size() + " abilities, refresh every "
                + applier.intervalTicks() + " tick(s).");
    }

    @Override
    public void onDisable() {
        stopRefreshTask();
        if (abilityManager != null) {
            abilityManager.shutdown();
        }
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
        refreshTask = getServer().getScheduler().runTaskTimer(this, this::tick, interval, interval);
    }

    private void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /** One second of upkeep: passive trim powers plus the star cooldown overlays. */
    private void tick() {
        applier.refreshAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<NamespacedKey, Integer> counts = TrimScanner.countEquipped(player);
            for (Ability ability : abilityManager.all()) {
                String material = ability.materialKey();
                boolean hasArmour = counts.getOrDefault(NamespacedKey.minecraft(material), 0) > 0;
                cooldowns.reconcile(player, material, hasArmour);
            }
        }
    }

    public TrimPowerConfig trimConfig() {
        return trimConfig;
    }

    public TrimPowerApplier applier() {
        return applier;
    }

    public AbilityStarFactory starFactory() {
        return starFactory;
    }

    public StarCooldownService cooldowns() {
        return cooldowns;
    }

    public AbilityManager abilityManager() {
        return abilityManager;
    }

    public StarRecipes recipes() {
        return recipes;
    }
}
