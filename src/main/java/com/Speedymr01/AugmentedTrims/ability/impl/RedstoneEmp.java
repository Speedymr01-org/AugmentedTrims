package com.Speedymr01.AugmentedTrims.ability.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.Speedymr01.AugmentedTrims.ability.Ability;

import org.bukkit.Bukkit;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.TechnicalPiston;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Redstone - EMP.
 *
 * <p>Fires a pulse through every block in a 3D sphere around the caster: redstone wire jumps to
 * full power, levers/buttons/rails/repeaters/comparators read as powered, redstone lamps and
 * redstone torches light, doors/trapdoors/fence gates open, pistons extend and TNT is primed.
 *
 * <p>Flipping a block state is not enough on its own: the vanilla redstone engine never hears
 * about it, so a wire would look bright without powering the lamp it feeds. Instead of faking a
 * signal, the plugin works the network out itself:
 *
 * <ul>
 *   <li>every wire inside the sphere counts as a source with power 15,</li>
 *   <li>the power travels along the wires, losing one per wire, exactly like vanilla,</li>
 *   <li>everything next to a live wire is powered: lamps light, doors open, pistons extend,</li>
 *   <li>and the blocks that act on power act: dispensers dispense, droppers drop, note blocks
 *       play - through the vanilla API, so the world ends up in a correct state.</li>
 * </ul>
 *
 * <p>Nothing is ever placed in the world for this, except the head of a piston that extends: a
 * piston without its head is a broken block, so it gets one and it is taken away again when the
 * pulse ends.
 *
 * <p>As an alternative the pulse can also be made "real" by placing temporary blocks of redstone,
 * which lets the vanilla engine do the work (see {@code abilities.redstone.real-sources}).
 *
 * <p>The sphere ignores walls and line of sight: anything inside the radius is affected.
 *
 * <p>{@link #sweep} does the work for both the ability and {@code /trims empdebug}, so the
 * debug output describes exactly what the ability does.
 */
public final class RedstoneEmp implements Ability {

    /** The strongest signal a wire can carry. */
    private static final int MAX_POWER = 15;

    /** Blocks that react to a signal: they light up, open, extend or fire. */
    private static final Set<Material> CONSUMERS = Set.of(
            Material.REDSTONE_LAMP,
            Material.COPPER_BULB,
            Material.DISPENSER,
            Material.DROPPER,
            Material.PISTON,
            Material.STICKY_PISTON,
            Material.NOTE_BLOCK,
            Material.OBSERVER,
            Material.SCULK_SENSOR,
            Material.CALIBRATED_SCULK_SENSOR,
            Material.CRAFTER,
            Material.DAYLIGHT_DETECTOR,
            Material.REPEATER,
            Material.COMPARATOR);

    /** Blocks that do something when they are powered. */
    private static final Set<Material> ACTIONS = Set.of(
            Material.DISPENSER,
            Material.DROPPER,
            Material.NOTE_BLOCK);

    /** Where a temporary power source may go, in order of preference. */
    private static final int[][] SOURCE_OFFSETS = {
            {0, 1, 0},
            {1, 0, 0},
            {-1, 0, 0},
            {0, 0, 1},
            {0, 0, -1},
            {0, -1, 0}
    };

    /** One block the pulse touched (or, in a dry run, would have touched). */
    public record Change(int x, int y, int z, String material, String kind,
                         String before, String after, boolean applied, boolean stuck) {
    }

    /** Everything one sweep saw. */
    public record SweepResult(int scanned, int air, int liquid,
                              List<Change> changes, Map<String, Integer> skipped,
                              List<Block> tnt, List<Block> actions, List<Block> wires,
                              List<Block> heads,
                              Map<Block, BlockData> previous, Map<Block, BlockData> applied) {

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
    }

    /** The powered variant of a block, plus which interface matched. */
    private record Powered(BlockData data, String kind, boolean changed) {
    }

    /** A block that the simulated network reached. */
    public record Reach(Block block, int power, String kind) {
    }

    /** A pulse that is still running, so it can be cleaned up when the plugin stops. */
    private record Pulse(Map<Block, BlockData> previous, List<Block> heads, List<Block> sources) {
    }

    private final JavaPlugin plugin;
    private final List<Pulse> running = new ArrayList<>();

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

        // Optional: let the vanilla engine do the work with temporary blocks of redstone.
        List<Block> sources = List.of();
        boolean engine = realSources();
        if (engine) {
            sources = placeSources(world, new ArrayList<>(result.applied().keySet()), maxSources());
            hideSources(world, sources);
        }

        // The blocks that act on power act, even without a signal. In engine mode the vanilla
        // engine does this itself, so doing it here as well would fire them twice.
        if (!engine) {
            for (Block action : result.actions()) {
                fire(world, action);
            }
        }

        // The pulse travels along the wires and powers what they feed.
        List<Reach> reached = propagate(world, result, true);
        if (!engine) {
            for (Reach reach : reached) {
                if (!result.actions().contains(reach.block())) {
                    fire(world, reach.block());
                }
            }
        }

        prime(world, player, result.tnt(), fuseTicks());
        // Push the new states to the clients by hand: a short pulse is otherwise invisible, and
        // the server does not always send an update for a block that was flipped directly.
        notifyClients(world, result.applied());
        restoreLater(result, sources, pulseTicks());
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
        Map<Block, BlockData> applied = new LinkedHashMap<>();
        List<Block> tnt = new ArrayList<>();
        List<Block> actions = new ArrayList<>();
        List<Block> wires = new ArrayList<>();
        List<Block> heads = new ArrayList<>();
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
                    if (ACTIONS.contains(original.getMaterial())) {
                        // Nothing to flip, but it reacts to power.
                        actions.add(block);
                    }
                    if (original.getMaterial() == Material.REDSTONE_WIRE) {
                        // Wires carry the pulse further than the sphere.
                        wires.add(block);
                    }

                    boolean piston = isPiston(original);
                    Powered powered = power(original);
                    if (powered.kind() == null && !piston) {
                        // Not a block that carries a power state at all.
                        skipped.merge(original.getMaterial().name(), 1, Integer::sum);
                        continue;
                    }
                    if (!powered.changed() && !piston) {
                        // Supports a power state but is already in it.
                        skipped.merge(original.getMaterial().name() + " (already)", 1, Integer::sum);
                        continue;
                    }

                    boolean stuck = false;
                    if (apply) {
                        flip(block, previous, applied, heads, true);
                        if (!piston) {
                            // Read back: if the state did not stick, the client will never see it.
                            stuck = !block.getBlockData().equals(powered.data());
                        }
                    }

                    changes.add(new Change(x, y, z, original.getMaterial().name(),
                            piston ? "Piston" : powered.kind(),
                            describe(original), describe(block.getBlockData()), apply, stuck));
                }
            }
        }

        return new SweepResult(scanned, air, liquid, List.copyOf(changes), skipped,
                List.copyOf(tnt), List.copyOf(actions), List.copyOf(wires), List.copyOf(heads),
                previous, applied);
    }

    /**
     * Works out what the pulse reaches, without placing a single block.
     *
     * <p>Every wire inside the sphere is a source with power 15. From there the power travels
     * along the wires, losing one per wire, and everything next to a live wire is powered -
     * including the blocks outside the sphere that the wires feed.
     *
     * @param apply when false nothing in the world is changed, the result only says what the
     *              network would reach
     */
    public List<Reach> propagate(World world, SweepResult result, boolean apply) {
        Map<Block, Integer> live = new LinkedHashMap<>();
        Deque<Block> queue = new ArrayDeque<>();
        for (Block block : result.wires()) {
            live.put(block, MAX_POWER);
            queue.add(block);
        }
        Set<Block> visited = new HashSet<>(live.keySet());
        List<Reach> reached = new ArrayList<>();
        Set<Block> fired = new HashSet<>();

        while (!queue.isEmpty()) {
            Block source = queue.poll();
            int power = live.get(source);
            for (Block neighbour : neighbours(world, source)) {
                if (neighbour.getType() == Material.REDSTONE_WIRE) {
                    if (visited.contains(neighbour)) {
                        continue;
                    }
                    // A wire next to a wire gets one less than its neighbour.
                    int next = power - 1;
                    if (next <= 0) {
                        continue;
                    }
                    visited.add(neighbour);
                    live.put(neighbour, next);
                    queue.add(neighbour);
                    // The wire outside the sphere lights up as well, and is restored afterwards.
                    flip(neighbour, result.previous(), result.applied(), result.heads(), apply);
                } else if (isConsumer(neighbour) && fired.add(neighbour)) {
                    // Powered: light it up, open it, extend it.
                    flip(neighbour, result.previous(), result.applied(), result.heads(), apply);
                    reached.add(new Reach(neighbour, power, "powered"));
                }
            }
        }
        return reached;
    }

    /** The six neighbours of a block. */
    private static List<Block> neighbours(World world, Block block) {
        List<Block> list = new ArrayList<>(6);
        list.add(block.getRelative(0, 1, 0));
        list.add(block.getRelative(0, -1, 0));
        list.add(block.getRelative(1, 0, 0));
        list.add(block.getRelative(-1, 0, 0));
        list.add(block.getRelative(0, 0, 1));
        list.add(block.getRelative(0, 0, -1));
        return list;
    }

    /**
     * Puts a block into its powered state and remembers the old one for the restore.
     */
    private void flip(Block block, Map<Block, BlockData> previous, Map<Block, BlockData> applied,
                      List<Block> heads, boolean apply) {
        BlockData original = block.getBlockData();
        if (isPiston(original)) {
            extendPiston(block, original, previous, applied, heads, apply);
            return;
        }
        BlockData powered = power(original).data();
        if (original.equals(powered)) {
            return;
        }
        if (!apply) {
            return;
        }
        previous.put(block, original);
        block.setBlockData(powered, false);
        applied.put(block, powered);
    }

    private static boolean isPiston(BlockData data) {
        return data instanceof Piston;
    }

    /**
     * Extends a piston - with its head.
     *
     * <p>A piston that is extended without a head is a broken block: the server and the client
     * disagree about it and it can stay that way. So the head is placed as well and taken away
     * again when the pulse ends. A piston that has no room for its head is left alone.
     */
    private void extendPiston(Block block, BlockData original, Map<Block, BlockData> previous,
                              Map<Block, BlockData> applied, List<Block> heads, boolean apply) {
        Piston piston = (Piston) original;
        if (piston.isExtended()) {
            // Already out.
            return;
        }
        Block head = block.getRelative(0, 1, 0);
        if (!head.isEmpty()) {
            // No room for the head: extending it would break it.
            return;
        }
        if (!apply) {
            return;
        }
        // The head goes in first: a piston that claims to be extended while its head is missing
        // is a state the server does not accept.
        head.setType(Material.PISTON_HEAD, false);
        if (head.getBlockData() instanceof TechnicalPiston headData) {
            headData.setFacing(piston.getFacing());
            headData.setType(block.getType() == Material.STICKY_PISTON
                    ? TechnicalPiston.Type.STICKY : TechnicalPiston.Type.NORMAL);
            head.setBlockData(headData, false);
        }
        heads.add(head);

        Piston extended = (Piston) original.clone();
        extended.setExtended(true);
        previous.put(block, original);
        block.setBlockData(extended, false);
        applied.put(block, extended);
    }

    /** Makes a block do the thing it does when it is powered. */
    private void fire(World world, Block block) {
        BlockState state = block.getState();
        if (state instanceof Dispenser dispenser) {
            dispenser.dispense();
        } else if (state instanceof Dropper dropper) {
            dropper.drop();
        } else if (block.getType() == Material.NOTE_BLOCK) {
            playNote(world, block);
        }
    }

    /** Plays the note of a note block (there is no Bukkit API for it any more). */
    private void playNote(World world, Block block) {
        if (!(block.getBlockData() instanceof NoteBlock data)) {
            return;
        }
        float pitch = data.getNote().getPitch();
        Sound sound = noteSound(data.getInstrument());
        world.playSound(block.getLocation(), sound, 1.0f, pitch);
    }

    /** Maps a note block instrument to its sound. */
    private static Sound noteSound(Instrument instrument) {
        if (instrument == null) {
            return Sound.BLOCK_NOTE_BLOCK_HARP;
        }
        return switch (instrument) {
            case PIANO -> Sound.BLOCK_NOTE_BLOCK_HARP;
            case BASS_DRUM -> Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
            case SNARE_DRUM -> Sound.BLOCK_NOTE_BLOCK_SNARE;
            case STICKS -> Sound.BLOCK_NOTE_BLOCK_HAT;
            case BASS_GUITAR -> Sound.BLOCK_NOTE_BLOCK_BASS;
            case FLUTE -> Sound.BLOCK_NOTE_BLOCK_FLUTE;
            case BELL -> Sound.BLOCK_NOTE_BLOCK_BELL;
            case GUITAR -> Sound.BLOCK_NOTE_BLOCK_GUITAR;
            case CHIME -> Sound.BLOCK_NOTE_BLOCK_CHIME;
            case XYLOPHONE -> Sound.BLOCK_NOTE_BLOCK_XYLOPHONE;
            case IRON_XYLOPHONE -> Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE;
            case COW_BELL -> Sound.BLOCK_NOTE_BLOCK_COW_BELL;
            case DIDGERIDOO -> Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
            case BIT -> Sound.BLOCK_NOTE_BLOCK_BIT;
            case BANJO -> Sound.BLOCK_NOTE_BLOCK_BANJO;
            case PLING -> Sound.BLOCK_NOTE_BLOCK_PLING;
            default -> Sound.BLOCK_NOTE_BLOCK_PLING;
        };
    }

    /**
     * Places a temporary block of redstone next to every block that consumes a signal, so the
     * vanilla redstone engine really powers it. Only used when {@code real-sources} is on.
     *
     * @return the temporary blocks that were placed (they have to be removed again)
     */
    public List<Block> placeSources(World world, List<Block> targets, int cap) {
        List<Block> placed = new ArrayList<>();
        if (cap <= 0) {
            return placed;
        }
        Set<Long> used = new HashSet<>();
        for (Block target : targets) {
            if (placed.size() >= cap) {
                break;
            }
            if (!isConsumer(target)) {
                continue;
            }
            Block cell = findSourceCell(world, target, used);
            if (cell == null) {
                continue;
            }
            used.add(cellKey(cell));
            // Physics on: the engine has to be told that something changed, otherwise the wire
            // never recalculates and stays unpowered.
            cell.setType(Material.REDSTONE_BLOCK, true);
            placed.add(cell);
        }
        return placed;
    }

    /**
     * True when the block reacts to a signal. Doors, trapdoors and fence gates are not in the
     * list above because they exist in too many variants - they are recognised by their data.
     */
    private static boolean isConsumer(Block block) {
        return CONSUMERS.contains(block.getType())
                || block.getBlockData() instanceof Openable;
    }

    /** Finds a free cell next to {@code target} for a temporary power source. */
    private Block findSourceCell(World world, Block target, Set<Long> used) {
        // First pass: only cells that cannot be seen from above, so the source stays out of
        // sight. Second pass: any free cell, still hidden from the clients.
        for (int pass = 0; pass < 2; pass++) {
            for (int[] offset : SOURCE_OFFSETS) {
                int x = target.getX() + offset[0];
                int y = target.getY() + offset[1];
                int z = target.getZ() + offset[2];
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block cell = world.getBlockAt(x, y, z);
                // Only air: a source must never destroy what is already there.
                if (!cell.isEmpty()) {
                    continue;
                }
                if (used.contains(cellKey(cell))) {
                    continue;
                }
                if (pass == 0 && !concealed(world, x, y, z)) {
                    continue;
                }
                if (cellOccupied(world, x, y, z)) {
                    continue;
                }
                return cell;
            }
        }
        return null;
    }

    /** True when the cell is covered from above, so nothing of it is visible in the world. */
    private boolean concealed(World world, int x, int y, int z) {
        if (y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block above = world.getBlockAt(x, y + 1, z);
        return above.getType().isSolid() || above.isLiquid();
    }

    /** True when a player or mob is standing in that cell (a source would push them). */
    private boolean cellOccupied(World world, int x, int y, int z) {
        Location centre = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        for (Entity entity : world.getNearbyEntities(centre, 0.9, 0.9, 0.9)) {
            Location location = entity.getLocation();
            if (location.getX() > x - 0.4 && location.getX() < x + 1.4
                    && location.getY() > y - 0.4 && location.getY() < y + 1.4
                    && location.getZ() > z - 0.4 && location.getZ() < z + 1.4) {
                return true;
            }
        }
        return false;
    }

    private static long cellKey(Block block) {
        return ((long) block.getX() << 38) ^ ((long) block.getY() << 19) ^ block.getZ();
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
        if (result instanceof Lightable lightable && lightsUp(result.getMaterial())) {
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

    /** Only blocks that really light up when they are powered. */
    private static boolean lightsUp(Material material) {
        return material == Material.REDSTONE_LAMP
                || material == Material.REDSTONE_TORCH
                || material == Material.REDSTONE_WALL_TORCH
                || material == Material.COPPER_BULB;
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
        if (data instanceof Piston piston) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append("extended=").append(piston.isExtended());
        }
        if (builder.length() == 0) {
            builder.append(data.getMaterial().name());
        }
        return builder.toString();
    }

    /** How long the pulse holds before the blocks are restored. 20 ticks = one second. */
    public int pulseTicks() {
        return Math.max(1, plugin.getConfig().getInt("abilities.redstone.pulse-ticks", 20));
    }

    /** Whether temporary power sources are placed so the vanilla engine really reacts. */
    public boolean realSources() {
        return plugin.getConfig().getBoolean("abilities.redstone.real-sources", false);
    }

    /** Safety cap on how many temporary blocks one pulse may place. */
    public int maxSources() {
        return Math.max(0, plugin.getConfig().getInt("abilities.redstone.max-sources", 64));
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

    /**
     * Removes the temporary power sources and puts every flipped block back exactly
     * {@code ticks} ticks later.
     */
    public void restoreLater(SweepResult result, List<Block> sources, int ticks) {
        Pulse pulse = new Pulse(result.previous(), result.heads(), sources);
        running.add(pulse);
        long delay = Math.max(1L, ticks);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            restore(pulse);
            running.remove(pulse);
        }, delay);
    }

    /** Puts one pulse back the way it was. */
    private void restore(Pulse pulse) {
        World world = null;
        // The heads of the pistons go first: a head without its piston is just as broken.
        for (Block head : pulse.heads()) {
            world = head.getWorld();
            if (head.getType() == Material.PISTON_HEAD) {
                head.setType(Material.AIR, false);
            }
        }
        // Remove the sources: with them gone the engine recalculates and the pulse really ends.
        for (Block source : pulse.sources()) {
            world = source.getWorld();
            if (source.getType() == Material.REDSTONE_BLOCK) {
                source.setType(Material.AIR, true);
            }
        }
        for (Map.Entry<Block, BlockData> entry : pulse.previous().entrySet()) {
            Block block = entry.getKey();
            BlockData original = entry.getValue();
            // Skip anything that was destroyed or replaced during the pulse.
            if (block.getType() != original.getMaterial()) {
                continue;
            }
            block.setBlockData(original, false);
        }
        // Tell the clients as well, so the pulse visibly ends.
        if (world != null) {
            notifyClients(world, pulse.previous());
        }
    }

    /** Puts every running pulse back, used when the plugin stops. */
    public void restoreAll() {
        for (Pulse pulse : new ArrayList<>(running)) {
            restore(pulse);
        }
        running.clear();
    }

    /**
     * Sends the given block states to every player nearby, so a flipped block is rendered even
     * when the server itself did not send an update for it.
     */
    public void notifyClients(World world, Map<Block, BlockData> states) {
        if (states.isEmpty()) {
            return;
        }
        double range = 64.0 * 64.0;
        for (Player viewer : world.getPlayers()) {
            Location eye = viewer.getLocation();
            for (Map.Entry<Block, BlockData> entry : states.entrySet()) {
                Block block = entry.getKey();
                if (eye.distanceSquared(block.getLocation().toCenterLocation()) > range) {
                    continue;
                }
                viewer.sendBlockChange(block.getLocation(), entry.getValue());
            }
        }
    }

    /** Hides the temporary power sources from the clients: they are an implementation detail. */
    public void hideSources(World world, List<Block> sources) {
        if (sources.isEmpty()) {
            return;
        }
        double range = 64.0 * 64.0;
        BlockData air = Material.AIR.createBlockData();
        for (Player viewer : world.getPlayers()) {
            Location eye = viewer.getLocation();
            for (Block source : sources) {
                if (eye.distanceSquared(source.getLocation().toCenterLocation()) > range) {
                    continue;
                }
                viewer.sendBlockChange(source.getLocation(), air);
            }
        }
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
