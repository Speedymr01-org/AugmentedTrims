package com.Speedymr01.AugmentedTrims.ability.impl;

import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.star.StarKeys;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Resin - Cobweb Shot.
 *
 * <p>Launches a single cobweb as a vanilla falling block along the caster's view direction.
 * Vanilla lands it as a real cobweb block; {@code StarListener} pins the landing block type and
 * guarantees nothing drops as an item. It deals no damage ({@code hurtEntities} is off).
 */
public final class ResinCobweb implements Ability {

    private final JavaPlugin plugin;

    public ResinCobweb(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String materialKey() {
        return "resin";
    }

    @Override
    public int cooldownSeconds(int tier) {
        int fallback = tier >= 4 ? 10 : 30;
        return plugin.getConfig().getInt("abilities.resin.tiers." + tier + ".cooldown", fallback);
    }

    @Override
    @SuppressWarnings("deprecation") // Only way to spawn a FallingBlock with explicit block data.
    public boolean cast(Player player, int tier) {
        var config = plugin.getConfig();
        double speed = Math.max(0.1, config.getDouble("abilities.resin.speed", 1.6));

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location spawn = muzzle(player, eye, direction);

        FallingBlock web = player.getWorld().spawnFallingBlock(spawn, Material.COBWEB.createBlockData());
        web.setDropItem(false);
        web.setCancelDrop(true);
        web.setHurtEntities(false);
        web.setGravity(true);
        web.setVelocity(direction.multiply(speed));
        web.getPersistentDataContainer().set(StarKeys.RESIN_WEB, PersistentDataType.BYTE, (byte) 1);

        player.getWorld().playSound(spawn, Sound.BLOCK_COBWEB_PLACE, 1.0f, 1.0f);
        return true;
    }

    /** One block in front of the eyes, clamped to just short of any block in the way. */
    private Location muzzle(Player player, Location eye, Vector direction) {
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction, 1.0, FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            Vector point = hit.getHitPosition();
            return new Location(player.getWorld(),
                    point.getX() - direction.getX() * 0.25,
                    point.getY() - direction.getY() * 0.25,
                    point.getZ() - direction.getZ() * 0.25);
        }
        return eye.clone().add(direction.clone().multiply(1.0));
    }
}
