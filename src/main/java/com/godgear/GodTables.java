package com.godgear;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which placed Smithing Table blocks are God Smithing Tables
 * (placed from a tagged item) and opens the god GUI when they're used.
 */
public class GodTables implements Listener {

    private final GodGear plugin;
    private final File file;
    private final Set<String> tables = new HashSet<>();

    public GodTables(GodGear plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tables.yml");
        load();
    }

    private static String key(Block b) {
        return b.getWorld().getUID() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private boolean tracked(Block b) {
        return tables.contains(key(b));
    }

    private void load() {
        if (!file.exists()) return;
        List<String> list = YamlConfiguration.loadConfiguration(file).getStringList("tables");
        tables.addAll(list);
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("tables", List.copyOf(tables));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save tables.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ place / break

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (GodItems.isTable(e.getItemInHand())) {
            tables.add(key(e.getBlockPlaced()));
            save();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!tracked(b)) return;
        tables.remove(key(b));
        save();
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), GodItems.createTable());
        }
    }

    // God tables are blast- and piston-proof so they can't be silently lost.
    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::tracked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::tracked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::tracked)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::tracked)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ use

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !tracked(b)) return;

        if (b.getType() != Material.SMITHING_TABLE) { // stale entry (removed by some other means)
            tables.remove(key(b));
            save();
            return;
        }
        Player p = e.getPlayer();
        // Sneak + item in hand = let people build against it as usual
        if (p.isSneaking() && !p.getInventory().getItemInMainHand().getType().isAir()) return;

        boolean vanilla = !"custom".equalsIgnoreCase(plugin.getConfig().getString("smithing-gui", "vanilla"));
        if (vanilla) {
            // let the real smithing screen open, but remember this viewer is at a *god* table
            if (e.getHand() == EquipmentSlot.HAND) plugin.gui().markPending(p);
            return;
        }
        e.setCancelled(true); // fallback: custom chest GUI
        if (e.getHand() == EquipmentSlot.HAND) {
            SmithingGui.open(p);
        }
    }
}
