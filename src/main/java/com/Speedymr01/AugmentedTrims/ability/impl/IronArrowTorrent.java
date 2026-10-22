package com.Speedymr01.AugmentedTrims.ability.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.star.StarKeys;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Iron - Arrow Torrent.
 *
 * <p>Fires a stream of arrows along the caster's eye direction: one volley every
 * {@code 20 / arrowsPerSecond} ticks for {@code duration-seconds}, tracking the crosshair
 * at the moment each volley leaves. Arrows spawn one block in front of the caster (clamped by
 * a one-block block raycast) so they never get caught in the shooter's own hitbox or a wall.
 */
public final class IronArrowTorrent implements Ability {

    private static final Random RANDOM = new Random();

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    public IronArrowTorrent(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String materialKey() {
        return "iron";
    }

    @Override
    public int cooldownSeconds(int tier) {
        return plugin.getConfig().getInt("abilities.iron.tiers." + tier + ".cooldown", 40);
    }

    @Override
    public boolean cast(Player player, int tier) {
        cancel(player);

        var config = plugin.getConfig();
        int arrowsPerSecond = Math.max(1, config.getInt("abilities.iron.tiers." + tier + ".arrows-per-second", 4));
        int durationSeconds = Math.max(1, config.getInt("abilities.iron.tiers." + tier + ".duration-seconds", 5));

        double interval = 20.0 / arrowsPerSecond;
        int totalTicks = durationSeconds * 20;

        UUID id = player.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (!player.isOnline() || elapsed >= totalTicks) {
                    tasks.remove(id);
                    cancel();
                    return;
                }
                // Fire every volley that falls due this tick (handles non-integer intervals).
                if (isDue(elapsed, interval)) {
                    volley(player);
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        tasks.put(id, task);
        return true;
    }

    /** @return whether a volley is due at this tick index for the given interval. */
    private static boolean isDue(int elapsed, double interval) {
        int previous = (int) Math.floor((elapsed - 1) / interval);
        int current = (int) Math.floor(elapsed / interval);
        return current > previous;
    }

    private void volley(Player player) {
        var config = plugin.getConfig();
        double speed = config.getDouble("abilities.iron.arrow.speed", 3.0);
        double spreadDegrees = config.getDouble("abilities.iron.arrow.spread-degrees", 1.5);
        double damage = config.getDouble("abilities.iron.arrow.damage", 4.0);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        Location origin = muzzle(player, eye, direction);
        if (origin == null) {
            return;
        }

        Vector launch = direction.clone();
        double spreadRad = Math.toRadians(Math.max(0.0, spreadDegrees));
        if (spreadRad > 0.0) {
            double tan = Math.tan(spreadRad);
            launch.add(new Vector(
                    (RANDOM.nextDouble() - 0.5) * 2 * tan,
                    (RANDOM.nextDouble() - 0.5) * 2 * tan,
                    (RANDOM.nextDouble() - 0.5) * 2 * tan)).normalize();
        }

        Arrow arrow = player.getWorld().spawnArrow(origin, launch, (float) speed, 0.0f, Arrow.class);
        arrow.setShooter(player);
        arrow.setDamage(damage);
        arrow.setCritical(true);
        arrow.setPierceLevel(0);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setFireTicks(0);
        arrow.getPersistentDataContainer().set(StarKeys.ABILITY_ARROW, PersistentDataType.STRING, materialKey());

        player.getWorld().playSound(origin, Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.1f);
    }

    /**
     * One block in front of the eyes, clamped to just short of any block in the way.
     *
     * @return {@code null} when the caster is flush against a block and the volley should be skipped
     */
    private Location muzzle(Player player, Location eye, Vector direction) {
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction, 1.0, FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            Vector point = hit.getHitPosition();
            Location clamped = new Location(player.getWorld(),
                    point.getX() - direction.getX() * 0.1,
                    point.getY() - direction.getY() * 0.1,
                    point.getZ() - direction.getZ() * 0.1);
            return clamped.distanceSquared(eye) < 0.09 ? null : clamped;
        }
        return eye.clone().add(direction.clone().multiply(1.0));
    }

    @Override
    public void cancel(Player player) {
        BukkitTask task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public void shutdown() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
