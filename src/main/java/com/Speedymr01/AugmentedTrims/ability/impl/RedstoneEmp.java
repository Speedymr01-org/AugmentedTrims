package com.Speedymr01.AugmentedTrims.ability.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.Speedymr01.AugmentedTrims.ability.Ability;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Redstone - EMP.
 *
 * <p>Fires a one tick pulse through every block in a 3D sphere around the caster: redstone wire
 * jumps to full power, levers/buttons/rails/repeaters/comparators read as powered, redstone
 * lamps and redstone torches light, doors/trapdoors/fence gates open, and TNT is primed. The
 * original block data is snapshotted and restored on the next tick, so nothing is permanently
 * rewired - it is a pulse, not a rebuild.
 *
 * <p>The sphere ignores walls and line of sight: anything inside the radius is affected. Blocks
 * are flipped directly rather than being fed a real redstone signal, which is what makes the
 * one tick pulse possible (a real signal would need a power source and would re-propagate).
 */
public final class RedstoneEmp implements Ability {

    private final JavaPlugin plugin;

    public RedstoneEmp(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String materialKey() {
        return "redstone";
    }

    /** Always 55 seconds, whatever the tier. */
    @Override
    public int cooldownSeconds(int tier) {
        return Math.max(1, plugin.getConfig().getInt("abilities.redstone.cooldown", 55));
    }

    @Override
    public boolean cast(Player player, int tier) {
        double radius = radius(tier);
        double radiusSquared = radius * radius;
        int span = (int) Math.ceil(radius);
        int fuse = Math.max(1, plugin.getConfig().getInt("abilities.redstone.tnt-fuse-ticks", 80));

        World world = player.getWorld();
        Location centre = player.getLocation().add(0, 0.9, 0);
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();

        Map<Block, BlockData> previous = new LinkedHashMap<>();
        List<Block> tnt = new ArrayList<>();

        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }
                    int y = cy + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.TNT) {
                        tnt.add(block);
                        continue;
                    }
                    // Cheap reject: a sphere is mostly air, and air has nothing to power.
                    if (block.isEmpty() || block.isLiquid()) {
                        continue;
                    }

                    BlockData original = block.getBlockData();
                    BlockData powered = power(original);
                    if (powered != null) {
                        previous.put(block, original);
                        // No physics: every affected block is flipped explicitly, and applying
                        // physics would make the redstone engine recalculate our 15-power wire
                        // straight back down to 0 before the pulse is over.
                        block.setBlockData(powered, false);
                    }
                }
            }
        }

        prime(world, player, tnt, fuse);
        restoreNextTick(previous);
        feedback(world, centre, radius);
        return true;
    }

    /** @return the powered variant of this block data, or {@code null} when it cannot be powered */
    private static BlockData power(BlockData data) {
        BlockData result = data.clone();
        if (result instanceof Powerable powerable && !powerable.isPowered()) {
            powerable.setPowered(true);
        }
        if (result instanceof AnaloguePowerable analogue && analogue.getPower() < analogue.getMaximumPower()) {
            analogue.setPower(analogue.getMaximumPower());
        }
        if (result instanceof Lightable lightable && !lightable.isLit() && isRedstoneLight(result.getMaterial())) {
            lightable.setLit(true);
        }
        if (result instanceof Openable openable && !openable.isOpen()) {
            openable.setOpen(true);
        }
        return result.equals(data) ? null : result;
    }

    /** Only redstone lights are lit; campfires, candles and furnaces are not part of the pulse. */
    private static boolean isRedstoneLight(Material material) {
        return material == Material.REDSTONE_LAMP
                || material == Material.REDSTONE_TORCH
                || material == Material.REDSTONE_WALL_TORCH;
    }

    private void prime(World world, Player player, List<Block> tnt, int fuse) {
        for (Block block : tnt) {
            block.setType(Material.AIR, false);
            TNTPrimed primed = (TNTPrimed) world.spawnEntity(
                    block.getLocation().add(0.5, 0.0, 0.5), EntityType.TNT);
            primed.setFuseTicks(fuse);
            primed.setSource(player);
        }
    }

    /** Puts every flipped block back exactly one tick later. */
    private void restoreNextTick(Map<Block, BlockData> previous) {
        if (previous.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Map.Entry<Block, BlockData> entry : previous.entrySet()) {
                Block block = entry.getKey();
                BlockData original = entry.getValue();
                // Skip anything that was destroyed or replaced during the pulse tick.
                if (block.getType() != original.getMaterial()) {
                    continue;
                }
                block.setBlockData(original, false);
            }
        });
    }

    private void feedback(World world, Location centre, double radius) {
        world.playSound(centre, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 0.7f);
        world.spawnParticle(Particle.ELECTRIC_SPARK, centre, 120,
                radius * 0.5, radius * 0.35, radius * 0.5, 0.1);
    }

    /** Tier radius: 4 blocks at tier 1, +2 per tier (config overrides per tier). */
    private double radius(int tier) {
        double fallback = 4.0 + 2.0 * (tier - 1);
        double configured = plugin.getConfig()
                .getDouble("abilities.redstone.radius." + tier, fallback);
        return Math.max(1.0, configured);
    }
}
