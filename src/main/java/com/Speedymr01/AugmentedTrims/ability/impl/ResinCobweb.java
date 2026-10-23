package com.Speedymr01.AugmentedTrims.ability.impl;

import java.util.LinkedHashSet;
import java.util.Set;

import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.star.StarKeys;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Resin - Cobweb Shot.
 *
 * <p>Throws one cobweb along the caster's view direction and turns it into a real cobweb block
 * on the first block it touches. The projectile is a {@link BlockDisplay} moved by our own
 * physics rather than a vanilla falling block: vanilla's landing path refuses to convert a
 * falling cobweb (it ends up dropping the item) and sinks fast-moving blocks into the ground,
 * so placement is done here where it is deterministic. Solving the collision with a raycast
 * along each tick's movement also means it sticks to walls instead of sliding down them.
 */
public final class ResinCobweb implements Ability {

    private final JavaPlugin plugin;

    /** Displays currently in flight, so a disable/reload never leaves orphaned entities. */
    private final Set<BlockDisplay> inFlight = new LinkedHashSet<>();

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
    public boolean cast(Player player, int tier) {
        var config = plugin.getConfig();
        double speed = Math.max(0.1, config.getDouble("abilities.resin.speed", 1.6));
        double gravity = config.getDouble("abilities.resin.gravity", 0.04);
        int maxTicks = Math.max(20, config.getInt("abilities.resin.max-flight-ticks", 120));

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location spawn = muzzle(player, eye, direction);
        World world = player.getWorld();

        BlockDisplay web = (BlockDisplay) world.spawnEntity(spawn, EntityType.BLOCK_DISPLAY);
        web.setBlock(Material.COBWEB.createBlockData());
        web.setBillboard(Display.Billboard.FIXED);
        web.setTeleportDuration(1);
        web.setGravity(false);
        web.setPersistent(false);
        web.getPersistentDataContainer().set(StarKeys.RESIN_WEB, PersistentDataType.BYTE, (byte) 1);
        inFlight.add(web);

        world.playSound(spawn, Sound.BLOCK_COBWEB_PLACE, 1.0f, 1.0f);

        new BukkitRunnable() {
            private Vector velocity = direction.clone().multiply(speed);
            private int ticks;

            @Override
            public void run() {
                if (!web.isValid() || ++ticks > maxTicks) {
                    inFlight.remove(web);
                    if (web.isValid()) {
                        web.remove();
                    }
                    cancel();
                    return;
                }

                velocity = velocity.clone().setY(velocity.getY() - gravity);
                double length = velocity.length();
                if (length < 1.0e-5) {
                    inFlight.remove(web);
                    web.remove();
                    cancel();
                    return;
                }

                Location from = web.getLocation();
                RayTraceResult hit = world.rayTraceBlocks(from, velocity.clone().normalize(), length,
                        FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) {
                    place(world, hit);
                    inFlight.remove(web);
                    web.remove();
                    cancel();
                    return;
                }

                web.teleport(from.add(velocity));
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    private void place(World world, RayTraceResult hit) {
        Block hitBlock = hit.getHitBlock();
        if (hitBlock == null) {
            return;
        }
        BlockFace face = hit.getHitBlockFace();
        Block target = face == null ? hitBlock.getRelative(BlockFace.UP) : hitBlock.getRelative(face);
        if (!isReplaceable(target)) {
            target = target.getRelative(BlockFace.UP);
        }
        if (!isReplaceable(target)) {
            return;
        }

        target.setType(Material.COBWEB, false);
        Location centre = target.getLocation().add(0.5, 0.5, 0.5);
        world.playSound(centre, Sound.BLOCK_COBWEB_PLACE, 1.0f, 1.2f);
        world.spawnParticle(Particle.BLOCK, centre, 10, 0.25, 0.25, 0.25,
                Material.COBWEB.createBlockData());
    }

    private static boolean isReplaceable(Block block) {
        return block.isEmpty() || block.isLiquid();
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

    @Override
    public void shutdown() {
        for (BlockDisplay web : inFlight) {
            if (web.isValid()) {
                web.remove();
            }
        }
        inFlight.clear();
    }
}
