package com.godgear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** /god recipes - an in-game guide: every recipe, the forging steps and a preview of all god items. */
public class RecipeBook implements Listener {

    public static class Holder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] GRID = {3, 4, 5, 12, 13, 14, 21, 22, 23};
    private static final int RESULT = 16, INFO = 18, BACK = 26;

    // ------------------------------------------------------------------ helpers

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.displayName(GodItems.c(name));
            if (lore.length > 0) {
                List<Component> l = new ArrayList<>();
                for (String s : lore) l.add(GodItems.c(s));
                meta.lore(l);
            }
        });
        return it;
    }

    private static ItemStack plain(Material m, String... lore) {
        ItemStack it = new ItemStack(m);
        if (lore.length > 0) {
            it.editMeta(meta -> {
                List<Component> l = new ArrayList<>();
                for (String s : lore) l.add(GodItems.c(s));
                meta.lore(l);
            });
        }
        return it;
    }

    private static Inventory create(int size) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, size, Component.text("God Recipe Book", NamedTextColor.GOLD));
        h.inv = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        pane.editMeta(m -> {
            m.displayName(Component.text(" "));
            m.setHideTooltip(true);
        });
        for (int i = 0; i < size; i++) inv.setItem(i, pane);
        return inv;
    }

    // ------------------------------------------------------------------ main menu

    public static void openMain(Player p) {
        Inventory inv = create(36);
        inv.setItem(10, item(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "&6God Template", "&7Click to view the recipe"));
        inv.setItem(11, item(Material.NETHERITE_SCRAP, "&6Duplicate a God Template", "&7Click to view the recipe"));
        inv.setItem(12, item(Material.SMITHING_TABLE, "&6God Smithing Table", "&7Click to view the recipe"));
        inv.setItem(13, item(Material.NETHERITE_INGOT, "&6Forging & Prestige", "&7Click to see how to forge god items"));
        inv.setItem(14, item(Material.ENCHANTED_GOLDEN_APPLE, "&6Ambrosia", "&7Click to view the recipe"));
        inv.setItem(15, item(Material.BOOK, "&eHow god gear works",
                "&7Kill &cplayers &7to level items: every 4 kills",
                "&7is an upgrade (12 kills = 3 upgrades).",
                "&7Weapons need to be held; armour just worn.",
                "",
                "&7Weapon abilities: &e" + GodItems.keyLabel() + " &7and &eShift+" + GodItems.keyLabel(),
                "&8(rebind 'Swap Item With Offhand' to " + GodItems.keyLabel() + ")",
                "",
                "&7Wear all 4 armour pieces for the &6Godform &7bonus.",
                "&7God items drop when you die - tiers are kept.",
                "&7Player heads drop when you're killed by a player.",
                "&7Repeat kills on the same player have a cooldown",
                "&7before they count towards your gear again."));
        inv.setItem(16, item(Material.WITHER_SKELETON_SKULL, "&cCombat rules",
                "&7Hitting a player disables &eSecond Life",
                "&7and &eWraith Dash &7for a few seconds.",
                "",
                "&cGodslayer &7(sword & mace, 12 kills):",
                "&7hits on god-armoured players deal",
                "&7x1.75 damage plus 6 true damage.",
                "&cGodbreaker &7(axe & spear, 12 kills):",
                "&7x1.5 damage plus 4 true damage.",
                "",
                "&5The Convergence & The Triarch",
                "&7Without it, you can safely hold only",
                "&7as many god items as your Convergence",
                "&7level allows: 0 -> none, 1 -> 1, 2 -> 4,",
                "&73 -> all 8. Carry more and it hurts you",
                "&7and summons a guardian to fight for it.",
                "&7/god status &7shows your own level.",
                "&7/god book &7re-gives you 'The Beginning'."));

        int slot = 19;
        for (GodType t : GodType.values()) {
            ItemStack preview = GodItems.createGod(t, GodItems.MAX_KILLS);
            if (preview != null) inv.setItem(slot++, preview);
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ recipe pages

    private static void openRecipe(Player p, String view) {
        Inventory inv = create(27);
        ItemStack[] grid = new ItemStack[9];
        ItemStack result;
        String[] info;

        switch (view) {
            case "template" -> {
                ItemStack s = plain(Material.NETHERITE_SWORD), n = GodItems.createAmbrosia(),
                        d = plain(Material.DIAMOND_BLOCK), m = plain(Material.MACE),
                        h = plain(Material.PLAYER_HEAD, "&7Any player head");
                grid = new ItemStack[]{s, n, s, d, m, d, h, d, h};
                result = GodItems.createTemplate();
                info = new String[]{"&6God Template", "&7Craft in a crafting table.", "&7Player heads drop when a player",
                        "&7is killed by another player.", "&7(Ambrosia has its own recipe -", "&7see /god recipes for it too.)"};
            }
            case "ambrosia" -> {
                grid = new ItemStack[]{plain(Material.NETHER_STAR), plain(Material.TOTEM_OF_UNDYING),
                        plain(Material.matchMaterial("RESIN_CLUMP") != null ? Material.matchMaterial("RESIN_CLUMP") : Material.AMETHYST_SHARD),
                        plain(Material.HEART_OF_THE_SEA), plain(Material.SNIFFER_EGG), plain(Material.ENCHANTED_GOLDEN_APPLE),
                        plain(Material.TRIDENT),
                        plain(Material.matchMaterial("PIGLIN_HEAD") != null ? Material.matchMaterial("PIGLIN_HEAD") : Material.ZOMBIE_HEAD),
                        plain(Material.ECHO_SHARD)};
                result = GodItems.createAmbrosia();
                info = new String[]{"&6Ambrosia", "&7Shapeless - any arrangement works.", "&7Food of the old gods: a huge, temporary",
                        "&7buff bundle. 2 uses per item, stacks to 64."};
            }
            case "dupe" -> {
                ItemStack s = plain(Material.NETHERITE_SCRAP);
                grid = new ItemStack[]{s, s, s, s, GodItems.createTemplate(), s, s, plain(Material.DIAMOND_BLOCK), s};
                result = GodItems.createTemplate();
                result.setAmount(2);
                info = new String[]{"&6Duplicate a God Template", "&77 Netherite Scrap + Diamond Block", "&7around a God Template = 2 templates."};
            }
            case "table" -> {
                grid[3] = plain(Material.SMITHING_TABLE);
                grid[5] = GodItems.createTemplate();
                result = GodItems.createTable();
                info = new String[]{"&6God Smithing Table", "&7Shapeless: smithing table + God Template.", "&7Place it, then right-click it."};
            }
            default -> { // forge
                grid[3] = GodItems.createTemplate();
                grid[4] = plain(Material.NETHERITE_CHESTPLATE, "&7Any netherite armour, sword, axe,", "&7spear - or a Mace");
                result = GodItems.createGod(GodType.CHESTPLATE, 0);
                info = new String[]{"&6Forging", "&7Right-click a God Smithing Table.",
                        "&7Template + netherite piece (or Mace)", "&7= god item. Leave the 3rd slot empty.", "",
                        "&dPrestige: &7put a 12-kill god item", "&7in with a template to reforge it:", "&7kills reset, +1 Prestige (max 5),",
                        "&7permanent stat bonus & new look."};
            }
        }
        for (int i = 0; i < 9; i++) {
            inv.setItem(GRID[i], grid[i]);
        }
        inv.setItem(15, item(Material.ARROW, "&7→"));
        inv.setItem(RESULT, result);
        inv.setItem(INFO, item(Material.PAPER, info[0], java.util.Arrays.copyOfRange(info, 1, info.length)));
        inv.setItem(BACK, item(Material.BARRIER, "&cBack"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != top) return;

        int slot = e.getRawSlot();
        if (top.getSize() == 36) {
            switch (slot) {
                case 10 -> openRecipe(p, "template");
                case 11 -> openRecipe(p, "dupe");
                case 12 -> openRecipe(p, "table");
                case 13 -> openRecipe(p, "forge");
                case 14 -> openRecipe(p, "ambrosia");
                default -> { }
            }
        } else if (slot == BACK) {
            openMain(p);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
