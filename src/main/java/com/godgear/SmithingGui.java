package com.godgear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Forging at a God Smithing Table.
 *
 * Default ("vanilla" mode): the real smithing screen opens. A registered smithing recipe (see GodGear) makes the
 * screen accept netherite gear / maces in the base slot with a God Template in the template slot; the result is
 * computed here via PrepareSmithingEvent. Leave the addition slot empty.
 *
 * Fallback ("custom" mode, config smithing-gui: custom): a 27-slot chest GUI. Slots: gear = 11, template = 13, result = 15.
 *
 * Both modes: a plain netherite piece becomes a god item; a 12-kill god item is reforged as +1 Prestige (kills reset).
 */
public class SmithingGui implements Listener {

    private static final int BASE = 11, TEMPLATE = 13, OUT = 15;

    public static class Holder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GodGear plugin;
    /** Players who just right-clicked a god table (cleared next tick). */
    private final Set<UUID> pending = new HashSet<>();
    /** Players currently looking at the vanilla smithing screen of a god table. */
    private final Set<UUID> godViewers = new HashSet<>();

    public SmithingGui(GodGear plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    //  vanilla smithing screen
    // =====================================================================================

    public void markPending(Player p) {
        pending.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> pending.remove(p.getUniqueId()));
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.SMITHING) return;
        UUID id = e.getPlayer().getUniqueId();
        if (pending.remove(id)) godViewers.add(id);
    }

    @EventHandler
    public void onCloseSmithing(InventoryCloseEvent e) {
        if (e.getInventory().getType() == InventoryType.SMITHING) {
            godViewers.remove(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
        godViewers.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPrepare(PrepareSmithingEvent e) {
        SmithingInventory inv = e.getInventory();
        ItemStack template = inv.getInputTemplate();
        if (!GodItems.isTemplate(template)) return;

        boolean god = e.getView().getPlayer() instanceof Player p && godViewers.contains(p.getUniqueId());
        if (!god) {
            e.setResult(null); // god templates do nothing at a normal smithing table
            return;
        }
        ItemStack addition = inv.getInputMineral();
        if (addition != null && !addition.getType().isAir()) {
            e.setResult(null); // keep the addition slot empty
            return;
        }
        e.setResult(GodItems.upgradeResult(inv.getInputEquipment(), template)); // null clears it
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmithTaken(SmithItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !godViewers.contains(p.getUniqueId())) return;
        ItemStack result = e.getCurrentItem();
        if (GodItems.getType(result) == null) return;
        boolean prestige = GodItems.getType(e.getInventory().getInputEquipment()) != null;
        announceForge(p, result, prestige);
    }

    // =====================================================================================
    //  shared announcement
    // =====================================================================================

    private static void announceForge(Player p, ItemStack result, boolean prestige) {
        Location l = p.getLocation();
        p.getWorld().playSound(l, "block.anvil.use", 1f, 1.2f);
        p.getWorld().playSound(l, "entity.player.levelup", 1f, 0.8f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.2);

        GodType t = GodItems.getType(result);
        String name = t != null ? t.display() : "God item";
        // Server-wide announcement - deliberately does not say who forged it
        if (prestige) {
            Bukkit.broadcast(GodItems.c("&6&l✦ &eA &6" + name + " &ehas reached &dPrestige "
                    + GodItems.getPrestige(result) + "&e!"));
        } else {
            Bukkit.broadcast(GodItems.c("&6&l✦ &eA &6" + name + " &ehas been forged!"));
        }
    }

    // =====================================================================================
    //  custom chest GUI (fallback)
    // =====================================================================================

    public static void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("God Smithing Table", NamedTextColor.GOLD));
        h.inv = inv;

        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        pane.editMeta(m -> {
            m.displayName(Component.text(" "));
            m.setHideTooltip(true);
        });
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);

        inv.setItem(BASE, null);
        inv.setItem(TEMPLATE, null);
        inv.setItem(OUT, null);
        inv.setItem(2, label("&bNetherite gear / Mace &7↓"));
        inv.setItem(4, label("&6God Template &7↓"));
        inv.setItem(6, label("&aResult &7↓"));

        p.openInventory(inv);
    }

    private static ItemStack label(String text) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.displayName(GodItems.c(text)));
        return it;
    }

    private void updateResult(Inventory inv) {
        inv.setItem(OUT, GodItems.upgradeResult(inv.getItem(BASE), inv.getItem(TEMPLATE)));
    }

    private void scheduleUpdate(Inventory inv) {
        Bukkit.getScheduler().runTask(plugin, () -> updateResult(inv));
    }

    private static boolean empty(ItemStack it) {
        return it == null || it.getType().isAir();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0) return; // clicked outside the window

        if (raw >= top.getSize()) { // bottom (player) inventory
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (empty(it)) return;
                int target = GodItems.isTemplate(it) ? TEMPLATE : GodItems.isUpgradableBase(it) ? BASE : -1;
                if (target < 0 || !empty(top.getItem(target))) return;
                top.setItem(target, it.clone());
                e.setCurrentItem(null);
                scheduleUpdate(top);
            }
            return;
        }

        if (raw == OUT) {
            e.setCancelled(true);
            takeResult(p, top);
            return;
        }
        if (raw != BASE && raw != TEMPLATE) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY || e.getClick() == ClickType.SWAP_OFFHAND) {
            e.setCancelled(true);
            return;
        }
        ItemStack cursor = e.getCursor();
        if (!empty(cursor)) {
            boolean ok = raw == BASE ? GodItems.isUpgradableBase(cursor) : GodItems.isTemplate(cursor);
            if (!ok) {
                e.setCancelled(true);
                return;
            }
        }
        scheduleUpdate(top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private void takeResult(Player p, Inventory inv) {
        ItemStack base = inv.getItem(BASE);
        ItemStack result = GodItems.upgradeResult(base, inv.getItem(TEMPLATE));
        if (result == null) {
            updateResult(inv);
            return;
        }
        boolean prestige = GodItems.getType(base) != null;
        consume(inv, BASE);
        consume(inv, TEMPLATE);
        give(p, result);
        updateResult(inv);
        announceForge(p, result, prestige);
    }

    private static void consume(Inventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        if (empty(it)) return;
        if (it.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            it.setAmount(it.getAmount() - 1);
            inv.setItem(slot, it);
        }
    }

    private static void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        for (int slot : new int[]{BASE, TEMPLATE}) {
            ItemStack it = inv.getItem(slot);
            if (empty(it)) continue;
            inv.setItem(slot, null);
            if (p.isDead()) {
                p.getWorld().dropItemNaturally(p.getLocation(), it);
            } else {
                give(p, it);
            }
        }
        inv.setItem(OUT, null);
    }
}
