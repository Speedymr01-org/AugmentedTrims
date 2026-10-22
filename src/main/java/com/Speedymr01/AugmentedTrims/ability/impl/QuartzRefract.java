package com.Speedymr01.AugmentedTrims.ability.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.star.StarKeys;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Quartz - Refract.
 *
 * <p>While active, an incoming projectile that would strike the caster is stopped at the moment
 * of impact (no explosion, no damage in the caster's face) and re-launched along a solved
 * ballistic arc back at whoever fired it. Tiers 1-3 solve the arc once; tier 4 re-solves every
 * tick so it homes in on a moving shooter.
 */
public final class QuartzRefract implements Ability {

    public record Drag(double gravity, double drag) {
    }

    private static final class Window {
        long expiresAt;
        int reflectionsLeft;
        boolean homing;
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Window> windows = new HashMap<>();
    private final Map<UUID, BukkitTask> homingTasks = new HashMap<>();

    public QuartzRefract(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String materialKey() {
        return "quartz";
    }

    @Override
    public int cooldownSeconds(int tier) {
        return plugin.getConfig().getInt("abilities.quartz.tiers." + tier + ".cooldown", 60);
    }

    @Override
    public void cast(Player player, int tier) {
        var config = plugin.getConfig();
        double duration = config.getDouble("abilities.quartz.tiers." + tier + ".duration-seconds", 4.0);

        Window window = new Window();
        window.expiresAt = Bukkit.getCurrentTick() + (long) (duration * 20);
        window.reflectionsLeft = config.getInt("abilities.quartz.tiers." + tier + ".max-reflections", 5);
        window.homing = config.getBoolean("abilities.quartz.tiers." + tier + ".homing", true);
        windows.put(player.getUniqueId(), window);
    }

    public boolean canReflect(Player player) {
        Window window = windows.get(player.getUniqueId());
        if (window == null) {
            return false;
        }
        if (Bukkit.getCurrentTick() > window.expiresAt || window.reflectionsLeft <= 0) {
            windows.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** @return whether the projectile was consumed and reflected. */
    public boolean tryReflect(Projectile projectile, Player reflector) {
        Window window = windows.get(reflector.getUniqueId());
        if (window == null) {
            return false;
        }

        var config = plugin.getConfig();
        double speedMultiplier = config.getDouble("abilities.quartz.projectile.speed-multiplier", 1.2);
        int minTicks = config.getInt("abilities.quartz.projectile.arc.min-flight-ticks", 8);
        int maxTicks = config.getInt("abilities.quartz.projectile.arc.max-flight-ticks", 40);

        Location from = projectile.getLocation().clone();
        ProjectileSource shooter = projectile.getShooter();
        Location target = targetPoint(shooter);
        Drag physics = ReflectionArc.dragFor(projectile);

        projectile.setShooter(reflector);
        Vector returnVelocity;
        if (target == null) {
            returnVelocity = projectile.getVelocity().multiply(-1).multiply(speedMultiplier);
        } else {
            int flightTicks = ReflectionArc.flightTicks(from.distance(target), minTicks, maxTicks);
            returnVelocity = ReflectionArc
                    .solve(from.toVector(), target.toVector(), flightTicks, physics.gravity(), physics.drag())
                    .multiply(speedMultiplier);
        }
        projectile.setVelocity(returnVelocity);
        // The projectile struck the caster, so it is inside their hitbox; without a nudge it would
        // register a second hit next tick and detonate on the reflector anyway.
        escapeHitbox(projectile, from, returnVelocity);

        if (target != null && window.homing) {
            startHoming(projectile, shooter, speedMultiplier, physics.gravity(), maxTicks);
        }

        markReflected(projectile);
        feedback(from);

        if (--window.reflectionsLeft <= 0) {
            windows.remove(reflector.getUniqueId());
        }
        return true;
    }

    /** @return whether this projectile was reflected within the last {@code windowTicks} ticks. */
    public static boolean isRecentlyReflected(Projectile projectile, int windowTicks) {
        Integer stamp = projectile.getPersistentDataContainer()
                .get(StarKeys.REFLECTED, PersistentDataType.INTEGER);
        return stamp != null && Bukkit.getCurrentTick() - stamp < windowTicks;
    }

    private static void markReflected(Projectile projectile) {
        projectile.getPersistentDataContainer().set(StarKeys.REFLECTED,
                PersistentDataType.INTEGER, Bukkit.getCurrentTick());
    }

    /**
     * Moves the freshly reflected projectile clear of the caster's hitbox along its new heading.
     * Falls back to shorter steps when the first block is solid.
     */
    private static void escapeHitbox(Projectile projectile, Location from, Vector velocity) {
        Vector direction = velocity.clone();
        if (direction.lengthSquared() < 1.0e-4) {
            return;
        }
        direction.normalize();
        for (double step : new double[] { 1.0, 0.5, 0.25 }) {
            Location candidate = from.clone().add(direction.clone().multiply(step));
            if (candidate.getBlock().isPassable()) {
                projectile.teleport(candidate);
                return;
            }
        }
    }

    private static Location targetPoint(ProjectileSource source) {
        if (source instanceof Entity entity) {
            Location base = entity.getLocation();
            if (entity instanceof LivingEntity living) {
                base.add(0, living.getHeight() * 0.5, 0);
            }
            return base;
        }
        if (source instanceof BlockProjectileSource blockSource) {
            return blockSource.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        return null;
    }

    private void startHoming(Projectile projectile, ProjectileSource shooter, double speed,
                             double gravity, int maxTicks) {
        UUID id = projectile.getUniqueId();
        BukkitTask existing = homingTasks.remove(id);
        if (existing != null) {
            existing.cancel();
        }

        BukkitTask task = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (++ticks > maxTicks || !projectile.isValid()) {
                    homingTasks.remove(id);
                    cancel();
                    return;
                }
                Location target = targetPoint(shooter);
                if (target == null) {
                    homingTasks.remove(id);
                    cancel();
                    return;
                }
                Vector delta = target.toVector().subtract(projectile.getLocation().toVector());
                if (delta.lengthSquared() < 0.25) {
                    homingTasks.remove(id);
                    cancel();
                    return;
                }
                projectile.setVelocity(delta.normalize().multiply(speed)
                        .add(new Vector(0, gravity * 0.5, 0)));
            }
        }.runTaskTimer(plugin, 0L, 1L);

        homingTasks.put(id, task);
    }

    private void feedback(Location location) {
        location.getWorld().playSound(location, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.4f);
        location.getWorld().spawnParticle(Particle.END_ROD, location, 12, 0.2, 0.2, 0.2, 0.02);
    }

    @Override
    public void cancel(Player player) {
        windows.remove(player.getUniqueId());
    }

    @Override
    public void shutdown() {
        windows.clear();
        homingTasks.values().forEach(BukkitTask::cancel);
        homingTasks.clear();
    }
}
