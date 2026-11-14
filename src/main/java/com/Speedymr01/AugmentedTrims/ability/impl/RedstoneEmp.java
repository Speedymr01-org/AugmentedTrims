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
 *
 * <p>{@link #sweep} does the work for both the ability and {@code /trims empdebug}, so the
 * debug output describes exactly what the ability does.
 */
public final class RedstoneEmp implements Ability {

    /** One block the pulse touched (or, in a dry run, would have touched). */
    public record Change(int x, int y, int z, String material, String kind,
                         String before, String after, boolean applied, boolean stuck) {
    }

    /** Everything one sweep saw. */
    public record SweepResult(int scanned, int air, int liquid,
                              List<Change> changes, Map<String, Integer> skipped,
                              List<Block> tnt, Map<Block, BlockData> previous) {

        /** Blocks that were written but did not keep the new state. */
        public int stuck() {
            int count = 0;
            for (Change change : changes) {
                if (change.stuck()) {
                    count++;
                }
            }
            return count;
        }

        /** Blocks that support one of the power states but were already in it. */
        public int alreadyPowered() {
            int count = 0;
            for (Map.Entry<String, Integer> entry : skipped.entrySet()) {
                if (entry.getKey().endsWith("(already)")) {
                    count += entry.getValue();
                }
            }
            return count;
        }
    }

    /** The powered variant of a block, plus which interface matched. */
    private record Powered(BlockData data, String kind, boolean changed) {
    }

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
        World world = player.getWorld();
        Location centre = player.getLocation().add(0, 0.9, 0);

        SweepResult result = sweep(world, centre, radius, true);
        prime(world, player, result.tnt(), fuseTicks());
        restoreLater(result.previous(), 1);
        feedback(world, centre, radius);
        return true;
    }

    /**
     * Walks the sphere around {@code centre} and reports every block the pulse touches.
     *
     * @param apply when true the blocks are really flipped; when false nothing in the world is
     *              touched and the result only says what would happen
     */
    public SweepResult sweep(World world, Location centre, double radius, boolean apply) {
        double radiusSquared = radius * radius;
        int span = (int) Math.ceil(radius);
        int centreX = centre.getBlockX();
        int centreY = centre.getBlockY();
        int centreZ = centre.getBlockZ();

        List<Change> changes = new ArrayList<>();
        Map<Block, BlockData> previous = new LinkedHashMap<>();
        List<Block> tnt = new ArrayList<>();
        Map<String, Integer> skipped = new LinkedHashMap<>();
        int scanned = 0;
        int air = 0;
        int liquid = 0;

        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }
                    int y = centreY + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    int x = centreX + dx;
                    int z = centreZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }

                    scanned++;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.TNT) {
                        tnt.add(block);
                        continue;
                    }
                    // Cheap reject: a sphere is mostly air, and air has nothing to power.
                    if (block.isEmpty()) {
                        air++;
                        continue;
                    }
                    if (block.isLiquid()) {
                        liquid++;
                        continue;
                    }

                    BlockData original = block.getBlockData();
                    Powered powered = power(original);
                    if (powered.kind() == null) {
                        // Not a block that carries a power state at all.
                        skipped.merge(original.getMaterial().name(), 1, Integer::sum);
                        continue;
                    }
                    if (!powered.changed()) {
                        // Supports a power state but is already in it.
                        skipped.merge(original.getMaterial().name() + " (already)", 1, Integer::sum);
                        continue;
                    }

                    boolean stuck = false;
                    if (apply) {
                        previous.put(block, original);
                        // No physics: every affected block is flipped explicitly, and applying
                        // physics would make the redstone engine recalculate our 15-power wire
                        // straight back down to 0 before the pulse is over.
                        block.setBlockData(powered.data(), false);
                        // Read back: if the state did not stick, the client will never see it.
                        stuck = !block.getBlockData().equals(powered.data());
                    }

                    changes.add(new Change(x, y, z, original.getMaterial().name(), powered.kind(),
                            describe(original), describe(powered.data()), apply, stuck));
                }
            }
        }

        return new SweepResult(scanned, air, liquid, List.copyOf(changes), skipped,
                List.copyOf(tnt), previous);
    }

    /**
     * @return the powered variant of this block data; {@code kind} is null when the block carries
     *         no power state, {@code changed} is false when it is already in the wanted state
     */
    private static Powered power(BlockData data) {
        BlockData result = data.clone();
        List<String> kinds = new ArrayList<>(2);
        boolean changed = false;

        if (result instanceof Powerable powerable) {
            kinds.add("Powerable");
            if (!powerable.isPowered()) {
                powerable.setPowered(true);
                changed = true;
            }
        }
        if (result instanceof AnaloguePowerable analogue) {
            kinds.add("AnaloguePowerable");
            if (analogue.getPower() < analogue.getMaximumPower()) {
                analogue.setPower(analogue.getMaximumPower());
                changed = true;
            }
        }
        if (result instanceof Lightable lightable && isRedstoneLight(result.getMaterial())) {
            kinds.add("Lightable");
            if (!lightable.isLit()) {
                lightable.setLit(true);
                changed = true;
            }
        }
        if (result instanceof Openable openable) {
            kinds.add("Openable");
            if (!openable.isOpen()) {
                openable.setOpen(true);
                changed = true;
            }
        }
        return new Powered(result, kinds.isEmpty() ? null : String.join("+", kinds), changed);
    }

    /** Only redstone lights are lit; campfires, candles and furnaces are not part of the pulse. */
    private static boolean isRedstoneLight(Material material) {
        return material == Material.REDSTONE_LAMP
                || material == Material.REDSTONE_TORCH
                || material == Material.REDSTONE_WALL_TORCH;
    }

    /** Short "before -> after" description of the states this plugin cares about. */
    private static String describe(BlockData data) {
        StringBuilder builder = new StringBuilder();
        if (data instanceof Powerable powerable) {
            builder.append("powered=").append(powerable.isPowered());
        }
        if (data instanceof AnaloguePowerable analogue) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append("power=").append(analogue.getPower())
                    .append('/').append(analogue.getMaximumPower());
        }
        if (data instanceof Lightable lightable) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append("lit=").append(lightable.isLit());
        }
        if (data instanceof Openable openable) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append("open=").append(openable.isOpen());
        }
        if (builder.length() == 0) {
            builder.append(data.getMaterial().name());
        }
        return builder.toString();
    }

    private int fuseTicks() {
        return Math.max(1, plugin.getConfig().getInt("abilities.redstone.tnt-fuse-ticks", 80));
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

    /** Puts every flipped block back exactly {@code ticks} ticks later. */
    public void restoreLater(Map<Block, BlockData> previous, int ticks) {
        if (previous.isEmpty()) {
            return;
        }
        long delay = Math.max(1L, ticks);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<Block, BlockData> entry : previous.entrySet()) {
                Block block = entry.getKey();
                BlockData original = entry.getValue();
                // Skip anything that was destroyed or replaced during the pulse.
                if (block.getType() != original.getMaterial()) {
                    continue;
                }
                block.setBlockData(original, false);
            }
        }, delay);
    }

    private void feedback(World world, Location centre, double radius) {
        world.playSound(centre, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 0.7f);
        world.spawnParticle(Particle.ELECTRIC_SPARK, centre, 120,
                radius * 0.5, radius * 0.35, radius * 0.5, 0.1);
    }

    /** Tier radius: 4 blocks at tier 1, +2 per tier (config overrides per tier). */
    public double radius(int tier) {
        double fallback = 4.0 + 2.0 * (tier - 1);
        double configured = plugin.getConfig()
                .getDouble("abilities.redstone.radius." + tier, fallback);
        return Math.max(1.0, configured);
    }
}
