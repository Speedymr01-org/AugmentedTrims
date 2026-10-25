package com.Speedymr01.AugmentedTrims.ability.impl;

import com.Speedymr01.AugmentedTrims.ability.Ability;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Copper - Thunderstrike.
 *
 * <p>Calls lightning down on the block the caster is looking at, up to the configured range.
 * The strike is cosmetic ({@link World#strikeLightningEffect}) so it never ignites the ground.
 * Damage is applied by us: the vanilla lightning damage type is <em>not</em> in
 * {@code minecraft:bypasses_armor}, so armour reduces it. {@code magic} is, which is what
 * {@code abilities.copper.ignore-armour} selects by default.
 */
public final class CopperThunderstrike implements Ability {

    private final JavaPlugin plugin;

    public CopperThunderstrike(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String materialKey() {
        return "copper";
    }

    @Override
    public int cooldownSeconds(int tier) {
        return plugin.getConfig().getInt("abilities.copper.tiers." + tier + ".cooldown", 55);
    }

    @Override
    public boolean cast(Player player, int tier) {
        var config = plugin.getConfig();
        double range = Math.max(1.0, config.getDouble("abilities.copper.range", 30.0));
        double radius = Math.max(0.0, config.getDouble("abilities.copper.radius", 3.0));
        double damage = Math.max(0.0, config.getDouble("abilities.copper.tiers." + tier + ".damage", 15.0));
        boolean damageCaster = config.getBoolean("abilities.copper.damage-caster", false);
        // magic bypasses armour; lightning_bolt is what vanilla uses and armour DOES reduce it.
        boolean ignoreArmour = config.getBoolean("abilities.copper.ignore-armour", true);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        // ignorePassableBlocks = true so grass, snow and flowers do not eat the ray.
        RayTraceResult hit = player.getWorld()
                .rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Block block = hit == null ? null : hit.getHitBlock();
        if (block == null) {
            return false;
        }

        Location impact = block.getLocation().add(0.5, 1.0, 0.5);

        // Cosmetic strike: no vanilla damage, no fire, no block ignition.
        player.getWorld().strikeLightningEffect(impact);

        // No causing entity: vanilla would attach the caster's held item (the custom-named star)
        // as the "weapon" and pick death.attack.<type>.item, which does not exist for these
        // damage types, so clients render the raw translation key. With only a direct entity it
        // uses the normal .player message. Kill credit is restored below.
        DamageSource source = DamageSource.builder(ignoreArmour ? DamageType.MAGIC : DamageType.LIGHTNING_BOLT)
                .withDirectEntity(player)
                .withDamageLocation(impact)
                .build();

        for (Entity entity : player.getWorld().getNearbyEntities(impact, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (!damageCaster && living.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            // A 55 second ability should not be swallowed by someone's recent hit immunity.
            living.setNoDamageTicks(0);
            living.damage(damage, source);
            // The source has no causing entity, so vanilla will not record the attacker for us.
            living.setKiller(player);
        }
        return true;
    }
}
