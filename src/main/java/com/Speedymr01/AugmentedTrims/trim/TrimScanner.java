package com.Speedymr01.AugmentedTrims.trim;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

/**
 * Reads the trim materials currently worn on a player's four armour slots.
 *
 * <p>Materials are resolved to {@link NamespacedKey}s through the registry rather than
 * compile-time constants, which is what keeps this plugin working across Minecraft drops
 * that add new trim materials.
 */
public final class TrimScanner {

    private TrimScanner() {
    }

    /** @return material key -&gt; number of worn armour pieces carrying that trim, insertion ordered. */
    public static Map<NamespacedKey, Integer> countEquipped(Player player) {
        Map<NamespacedKey, Integer> counts = new LinkedHashMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            NamespacedKey key = materialKey(piece);
            if (key != null) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** @return the trim material key of a single item, or {@code null} if it has no trim. */
    public static NamespacedKey materialKey(ItemStack piece) {
        if (piece == null || piece.getType().isAir()) {
            return null;
        }
        if (!(piece.getItemMeta() instanceof ArmorMeta meta) || !meta.hasTrim()) {
            return null;
        }
        ArmorTrim trim = meta.getTrim();
        if (trim == null) {
            return null;
        }
        // Registry.TRIM_MATERIAL is deprecated since 1.20.6; RegistryAccess is the
        // supported path. Trim materials can exist without a key, hence the null check.
        Registry<TrimMaterial> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL);
        return registry.getKey(trim.getMaterial());
    }
}
