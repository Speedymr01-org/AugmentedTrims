package com.Speedymr01.AugmentedTrims.listener;

import java.util.Optional;

import com.Speedymr01.AugmentedTrims.AugmentedTrims;
import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.ability.AbilityManager;
import com.Speedymr01.AugmentedTrims.ability.impl.QuartzRefract;
import com.Speedymr01.AugmentedTrims.craft.StarRecipes;
import com.Speedymr01.AugmentedTrims.star.AbilityStarFactory;
import com.Speedymr01.AugmentedTrims.star.StarCooldownService;
import com.Speedymr01.AugmentedTrims.star.StarKeys;
import com.Speedymr01.AugmentedTrims.trim.TrimScanner;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Ability Star interaction, crafting guards, smithing attunement and projectile reflection. */
public final class StarListener implements Listener {
    private final AugmentedTrims plugin;
    private final AbilityStarFactory starFactory;
    private final AbilityManager abilityManager;
    private final StarCooldownService cooldowns;
    private final StarRecipes recipes;
    private final QuartzRefract quartz;

    public StarListener(AugmentedTrims plugin, AbilityStarFactory starFactory, AbilityManager abilityManager,
                        StarCooldownService cooldowns, StarRecipes recipes, QuartzRefract quartz) {
        this.plugin = plugin;
        this.starFactory = starFactory;
        this.abilityManager = abilityManager;
        this.cooldowns = cooldowns;
        this.recipes = recipes;
        this.quartz = quartz;
    }

    // ---------------------------------------------------------------- casting

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation") // No replacement exists: BlockType#isInteractable is deprecated too.
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!starFactory.isStar(item)) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().asBlockType().isInteractable()) {
            // Let chests, doors, buttons etc. behave normally while holding a star.
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        Optional<String> materialKey = starFactory.abilityOf(item);
        if (materialKey.isEmpty()) {
            message(player, "messages.ability.not-attuned");
            return;
        }
        String material = materialKey.get();
        Optional<Ability> ability = abilityManager.ability(material);
        if (ability.isEmpty()) {
            message(player, "messages.ability.not-attuned");
            return;
        }

        int pieces = TrimScanner.countEquipped(player).getOrDefault(NamespacedKey.minecraft(material), 0);
        if (pieces <= 0 || cooldowns.isOnCooldown(player, material)) {
            message(player, "messages.ability.on-cooldown");
            return;
        }

        int tier = Math.min(4, pieces);
        if (!ability.get().cast(player, tier)) {
            message(player, "messages.ability.no-target");
            return;
        }
        cooldowns.start(player, material, ability.get().cooldownSeconds(tier));
        cooldowns.reconcile(player, material, true);
        String path = ability.get().successMessagePath(player, tier);
        message(player, path == null ? "messages.ability.activated" : path);
    }

    // ------------------------------------------------------------- crafting

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() != Material.BEACON) {
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (starFactory.isStar(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        var inventory = event.getInventory();
        ItemStack template = inventory.getInputTemplate();
        ItemStack base = inventory.getInputEquipment();

        if (template == null || template.getType() != Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE) {
            return;
        }
        if (base == null || base.getType() != Material.NETHER_STAR) {
            return;
        }
        // A plain nether star must not become a star just by matching our registered recipe.
        if (!starFactory.isStar(base)) {
            event.setResult(null);
            return;
        }

        ItemStack addition = inventory.getInputMineral();
        event.setResult(recipes.smithingResult(base, template, addition).orElse(null));
    }

    // ----------------------------------------------------------- projectiles

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile instanceof ThrownPotion || projectile instanceof FishHook) {
            return;
        }

        int antiLoop = plugin.getConfig()
                .getInt("abilities.quartz.projectile.reflection-min-interval-ticks", 10);

        if (!QuartzRefract.isRecentlyReflected(projectile, antiLoop)
                && event.getHitEntity() instanceof Player reflector
                && quartz.canReflect(reflector)) {
            event.setCancelled(true);
            quartz.tryReflect(projectile, reflector);
            return;
        }

        removeOurArrowOnBlockHit(projectile, event);
    }

    /** Backup for damage that vanilla applies outside the hit event (e.g. arrow melee damage). */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (projectile instanceof ThrownPotion || projectile instanceof FishHook) {
            return;
        }

        int antiLoop = plugin.getConfig()
                .getInt("abilities.quartz.projectile.reflection-min-interval-ticks", 10);
        if (QuartzRefract.isRecentlyReflected(projectile, antiLoop) || !quartz.canReflect(victim)) {
            return;
        }

        event.setCancelled(true);
        quartz.tryReflect(projectile, victim);
    }

    private void removeOurArrowOnBlockHit(Projectile projectile, ProjectileHitEvent event) {
        if (event.getHitBlock() == null) {
            return;
        }
        if (projectile.getPersistentDataContainer().has(StarKeys.ABILITY_ARROW, PersistentDataType.STRING)) {
            projectile.remove();
        }
    }

    // ------------------------------------------------------------- lifecycle
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {        abilityManager.cancelAll(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abilityManager.cancelAll(event.getPlayer());
        cooldowns.forget(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        cooldowns.resetVisuals(event.getPlayer());
    }

    private void message(Player player, String path) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String text = plugin.getConfig().getString(path);
        if (text == null) {
            // Config written by an older version: fall back to the generic success message.
            text = plugin.getConfig().getString("messages.ability.activated", path);
        }
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + text));
    }
}
