package com.godgear;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Public (no permission needed, self-only - never reveals another player's gear):
 *   /godrecipes | /god [recipes]        - the recipe book
 *   /god book                           - re-give yourself "The Beginning"
 *   /god info | /god tune               - the current balance numbers (live from config.yml)
 *   /god status                         - your own Convergence level/cap, distinct god items held,
 *                                          and your held weapon's ability cooldowns
 *
 * Admin (permission godgear.admin):
 *   /god give <player> <item|template|table> [kills 0-12]
 *   /god inspect <player>                - their equipped god items, tiers, kills, prestige
 *   /god test                            - a chest of duplicate/test god items for you to try abilities with
 *   /god setconvergence <player> <0-3>   - set a player's Convergence level directly
 *   /god reset <player>                  - reset a player's Convergence level and lore-book flag
 *   /god boss <player> <1|2|3>           - force-spawn a Triarch stage, bound to that player
 *   /god boss remove [player]            - remove all tracked/orphaned Triarch entities, or just one player's
 *   /god boss list                       - list currently active Triarch fights
 *   /god reload                          - reload config.yml
 */
public class GodCommand implements CommandExecutor, TabCompleter {

    private final GodGear plugin;

    public GodCommand(GodGear plugin) {
        this.plugin = plugin;
    }

    /** Public balance sheet - reads the live config so it never goes stale. */
    private void info(CommandSender s) {
        var cfg = plugin.getConfig();
        List<String> l = new ArrayList<>();
        l.add("&6&l--- God Gear: balance sheet ---");
        l.add("&eProgression: &7" + GodItems.KILLS_PER_TIER + " player kills per upgrade, cap " + GodItems.MAX_KILLS
                + ". Prestige up to " + GodItems.MAX_PRESTIGE + " (+" + Math.round(GodItems.WEAPON_PRESTIGE_BONUS * 100)
                + "% weapon damage, +" + Math.round(GodItems.ARMOR_PRESTIGE_BONUS * 100) + "% damage reduction per armour piece).");
        l.add("&eAnti-farm: &7same killer+victim counts once per " + cfg.getInt("kill-farm-cooldown-seconds", 120) + "s.");
        l.add("&eWeapons: &7Sword x1.5 dmg + 25% lifesteal | Axe x1.25 + 50% splash | Spear x1.4 + 4 true dmg | Mace x1.75 on a falling smash.");
        l.add("&eSunder (Axe): &7Wither 10s, doesn't refresh while active.");
        l.add("&eStunning Blow (Mace): &7Slowness III 3s per hit; 30% roll for Slowness III 15s + Slowness X 5s, "
                + cfg.getInt("cooldowns.mace_stun", 10) + "s cooldown per target.");
        l.add("&eVs god armour (12 kills): &7Godslayer (Sword/Mace) x1.75 + 6 true | Godbreaker (Axe/Spear) x1.5 + 4 true.");
        l.add("&eSwing speed: &7vanilla cooldowns (no attack-speed boost).");
        l.add("&eArmour: &7full set = -" + Math.round(cfg.getDouble("set-bonus-reduction", 0.20) * 100) + "% damage (plus prestige, capped at 75%). "
                + "Hitting a player disables Second Life / Wraith Dash for " + cfg.getInt("combat-tag-seconds", 5) + "s.");
        l.add("&eAmbrosia + armour: &7while Ambrosia is active, armour's own reduction is x"
                + cfg.getDouble("ambrosia-armour-factor", 0.25) + ", its +10 hearts and passive Resistance are paused.");
        l.add("&eConvergence: &7safe distinct god items: level 0 -> 0, 1 -> 1, 2 -> 4, 3 -> 8. Carrying more triggers Divine Overload.");
        l.add("&eTriarch: &7no overload while you fight it; it applies if you go further than " + cfg.getInt("triarch.flee-distance", 64)
                + " blocks away, or locks on after " + cfg.getInt("triarch.idle-lock-seconds", 180) + "s without being hit by it. "
                + "If your own Triarch kills you, everything you carry is destroyed.");
        var cds = cfg.getConfigurationSection("cooldowns");
        if (cds != null) {
            StringBuilder sb = new StringBuilder("&eCooldowns (s): &7");
            for (String k : cds.getKeys(false)) sb.append(k.replace('_', ' ')).append(' ').append(cds.getInt(k)).append(", ");
            if (sb.length() > 2) sb.setLength(sb.length() - 2);
            l.add(sb.toString());
        }
        for (String line : l) s.sendMessage(GodItems.c(line));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        boolean recipes = cmd.getName().equalsIgnoreCase("godrecipes")
                || a.length == 0 || a[0].equalsIgnoreCase("recipes");
        if (recipes) {
            if (s instanceof Player p) {
                RecipeBook.openMain(p);
            } else {
                s.sendMessage("Only players can open the recipe book.");
            }
            return true;
        }

        // ---------------------------------------------------------------- public, self-only
        if (a[0].equalsIgnoreCase("book")) {
            if (s instanceof Player p) {
                plugin.giveBook(p);
                s.sendMessage(GodItems.c("&7Here's your copy of &8The Beginning&7."));
            } else {
                s.sendMessage("Only players can receive the book.");
            }
            return true;
        }
        if (a[0].equalsIgnoreCase("info") || a[0].equalsIgnoreCase("tune")) {
            info(s);
            return true;
        }
        if (a[0].equalsIgnoreCase("status")) {
            if (s instanceof Player p) {
                status(p);
            } else {
                s.sendMessage("Only players have a status.");
            }
            return true;
        }

        // ---------------------------------------------------------------- everything else is admin-only
        if (!s.hasPermission("godgear.admin")) {
            s.sendMessage(GodItems.c("&cYou don't have permission to do that. Try &e/godrecipes&c, &e/god book&c or &e/god status&c."));
            return true;
        }

        if (a[0].equalsIgnoreCase("test")) {
            if (s instanceof Player p) {
                openTestChest(p);
            } else {
                s.sendMessage("Only players can open the test chest.");
            }
            return true;
        }
        if (a[0].equalsIgnoreCase("boss")) {
            return handleBoss(s, a);
        }
        if (a[0].equalsIgnoreCase("inspect")) {
            if (a.length < 2) {
                s.sendMessage(GodItems.c("&cUsage: /god inspect <player>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                s.sendMessage(GodItems.c("&cPlayer not found."));
                return true;
            }
            inspect(s, target);
            return true;
        }
        if (a[0].equalsIgnoreCase("setconvergence")) {
            if (a.length < 3) {
                s.sendMessage(GodItems.c("&cUsage: /god setconvergence <player> <0-3>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                s.sendMessage(GodItems.c("&cPlayer not found."));
                return true;
            }
            int level;
            try {
                level = Integer.parseInt(a[2]);
            } catch (NumberFormatException ex) {
                s.sendMessage(GodItems.c("&cLevel must be a number 0-3."));
                return true;
            }
            GodItems.setConvergenceLevel(target, level);
            s.sendMessage(GodItems.c("&aSet " + target.getName() + "'s Convergence level to " + Math.max(0, Math.min(3, level)) + "."));
            return true;
        }
        if (a[0].equalsIgnoreCase("reset")) {
            if (a.length < 2) {
                s.sendMessage(GodItems.c("&cUsage: /god reset <player>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                s.sendMessage(GodItems.c("&cPlayer not found."));
                return true;
            }
            GodItems.setConvergenceLevel(target, 0);
            s.sendMessage(GodItems.c("&aReset " + target.getName() + "'s Convergence level to 0."));
            return true;
        }
        if (a[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            s.sendMessage(GodItems.c("&aGodGear config reloaded. &7(custom-models and ability-key-label need a restart)"));
            return true;
        }
        if (a.length < 3 || !a[0].equalsIgnoreCase("give")) {
            s.sendMessage(GodItems.c("&cUsage: /god give <player> <item|template|table> [kills]  |  /god inspect <player>  |  "
                    + "/god test  |  /god setconvergence <player> <0-3>  |  /god reset <player>  |  /god boss ...  |  /god reload"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) {
            s.sendMessage(GodItems.c("&cPlayer not found."));
            return true;
        }
        ItemStack item;
        String what = a[2].toLowerCase(Locale.ROOT);
        if (what.equals("template")) {
            item = GodItems.createTemplate();
        } else if (what.equals("table")) {
            item = GodItems.createTable();
        } else {
            GodType t = GodType.byName(what);
            if (t == null) {
                s.sendMessage(GodItems.c("&cUnknown item: " + what));
                return true;
            }
            int kills = 0;
            if (a.length >= 4) {
                try {
                    kills = Integer.parseInt(a[3]);
                } catch (NumberFormatException ex) {
                    s.sendMessage(GodItems.c("&cKills must be a number (0-12)."));
                    return true;
                }
            }
            item = GodItems.createGod(t, kills);
            if (item == null) {
                s.sendMessage(GodItems.c("&cThat item's base material doesn't exist on this server version."));
                return true;
            }
        }
        target.getInventory().addItem(item).values()
                .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        s.sendMessage(GodItems.c("&aGave " + what + " to " + target.getName() + "."));
        return true;
    }

    // ---------------------------------------------------------------- /god boss ...

    private boolean handleBoss(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("remove")) {
            Player only = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : null;
            if (a.length >= 3 && only == null) {
                s.sendMessage(GodItems.c("&cPlayer not found."));
                return true;
            }
            int n = plugin.triarch().removeAll(only);
            s.sendMessage(GodItems.c("&aRemoved " + n + " Triarch Singularity entit" + (n == 1 ? "y" : "ies") + "."));
            return true;
        }
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            List<String> lines = plugin.triarch().listActive();
            if (lines.isEmpty()) {
                s.sendMessage(GodItems.c("&7No Triarch Singularity is currently active."));
            } else {
                s.sendMessage(GodItems.c("&6&l--- Active Triarch fights ---"));
                for (String l : lines) s.sendMessage(GodItems.c("&7" + l));
            }
            return true;
        }
        if (a.length < 3) {
            s.sendMessage(GodItems.c("&cUsage: /god boss <player> <1|2|3>  |  /god boss remove [player]  |  /god boss list"));
            return true;
        }
        Player near = Bukkit.getPlayerExact(a[1]);
        if (near == null) {
            s.sendMessage(GodItems.c("&cPlayer not found."));
            return true;
        }
        int stage = switch (a[2]) {
            case "2" -> 2;
            case "3" -> 3;
            default -> 1;
        };
        plugin.triarch().forceSpawn(near, stage);
        s.sendMessage(GodItems.c("&aSpawning Triarch stage " + stage + " near " + near.getName() + "."));
        return true;
    }

    // ---------------------------------------------------------------- /god status (public, self only)

    private void status(Player p) {
        int level = GodItems.convergenceLevel(p);
        int cap = GodItems.convergenceCap(level);
        int distinct = GodItems.distinctGodTypes(p).size();
        int total = GodItems.totalGodItemCount(p);

        p.sendMessage(GodItems.c("&6&l--- Your GodGear status ---"));
        p.sendMessage(GodItems.c("&7Convergence level: &e" + level + "&7/3 &8(safe carry limit: " + cap + ")"));
        p.sendMessage(GodItems.c("&7Distinct god items held: &e" + distinct + " &8(" + total + " total, counting duplicates)"));
        if (distinct > cap) {
            p.sendMessage(GodItems.c("&c&lYou are over your Convergence limit! &7Defeat the guardian or drop items."));
        }

        GodType heldType = GodItems.getType(p.getInventory().getItemInMainHand());
        if (heldType != null && !heldType.isArmor()) {
            p.sendMessage(GodItems.c("&7Held weapon: &6" + heldType.display() + " &7- Tier " + GodItems.tier(p.getInventory().getItemInMainHand())
                    + "/" + GodItems.MAX_TIER));
        }
    }

    // ---------------------------------------------------------------- /god inspect (admin)

    private void inspect(CommandSender s, Player target) {
        PlayerInventory inv = target.getInventory();
        List<Component> lines = new ArrayList<>();
        lines.add(GodItems.c("&6&l--- " + target.getName() + "'s god gear ---"));

        String[] slotNames = {"Main hand", "Off hand", "Helmet", "Chestplate", "Leggings", "Boots"};
        ItemStack[] slots = {inv.getItemInMainHand(), inv.getItemInOffHand(),
                inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        boolean any = false;
        for (int i = 0; i < slots.length; i++) {
            GodType t = GodItems.getType(slots[i]);
            if (t == null) continue;
            any = true;
            int kills = GodItems.getKills(slots[i]);
            int tier = GodItems.tierOf(kills);
            int prestige = GodItems.getPrestige(slots[i]);
            lines.add(GodItems.c("&7" + slotNames[i] + ": &6" + t.display() + " &7- Tier " + tier + "/"
                    + GodItems.MAX_TIER + " &8(" + kills + "/" + GodItems.MAX_KILLS + " kills)"
                    + (prestige > 0 ? " &dPrestige " + prestige : "")));
        }
        if (!any) lines.add(GodItems.c("&7No god items equipped."));

        int distinct = GodItems.distinctGodTypes(target).size();
        int level = GodItems.convergenceLevel(target);
        lines.add(GodItems.c("&7Distinct god items carried: &e" + distinct + "&7/8 &8(Convergence " + level
                + ", safe limit " + GodItems.convergenceCap(level) + ")"));

        for (Component c : lines) s.sendMessage(c);
    }

    // ---------------------------------------------------------------- /god test (admin)

    private void openTestChest(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GodItems.c("&6GodGear Test Items"));
        int slot = 0;
        for (GodType t : GodType.values()) {
            ItemStack locked = GodItems.createGod(t, 0);
            ItemStack maxed = GodItems.createGod(t, GodItems.MAX_KILLS);
            ItemStack prestiged = GodItems.createGodWithPrestige(t, GodItems.MAX_KILLS, 2);
            if (locked != null) inv.setItem(slot++, locked);
            if (maxed != null) inv.setItem(slot++, maxed);
            if (prestiged != null) inv.setItem(slot++, prestiged);
        }
        for (int i = 0; i < 3; i++) inv.setItem(slot++, GodItems.createTemplate());
        inv.setItem(slot++, GodItems.createTable());
        for (int lvl = 1; lvl <= 3; lvl++) inv.setItem(slot++, GodItems.createConvergence(lvl));
        for (int stage = 1; stage <= 3; stage++) inv.setItem(slot++, GodItems.createTrophy(stage));
        inv.setItem(slot++, new ItemStack(Material.PLAYER_HEAD, 4));

        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("godrecipes")) return out;
        boolean admin = s.hasPermission("godgear.admin");

        if (a.length == 1) {
            out.addAll(List.of("recipes", "book", "status", "info"));
            if (admin) out.addAll(List.of("give", "inspect", "test", "setconvergence", "reset", "boss", "reload"));
        } else if (admin && a.length == 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("inspect")
                || a[0].equalsIgnoreCase("setconvergence") || a[0].equalsIgnoreCase("reset"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (admin && a.length == 2 && a[0].equalsIgnoreCase("boss")) {
            out.add("remove");
            out.add("list");
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (admin && a.length == 3 && a[0].equalsIgnoreCase("give")) {
            for (GodType t : GodType.values()) out.add(t.name().toLowerCase(Locale.ROOT));
            out.add("template");
            out.add("table");
        } else if (admin && a.length == 4 && a[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("0", "4", "8", "12"));
        } else if (admin && a.length == 3 && a[0].equalsIgnoreCase("boss") && !a[1].equalsIgnoreCase("remove") && !a[1].equalsIgnoreCase("list")) {
            out.addAll(List.of("1", "2", "3"));
        } else if (admin && a.length == 3 && a[0].equalsIgnoreCase("boss") && a[1].equalsIgnoreCase("remove")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (admin && a.length == 3 && a[0].equalsIgnoreCase("setconvergence")) {
            out.addAll(List.of("0", "1", "2", "3"));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
