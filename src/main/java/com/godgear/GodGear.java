package com.godgear;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class GodGear extends JavaPlugin implements Listener {

    private final List<NamespacedKey> recipeKeys = new ArrayList<>();
    private GodTables tables;
    private GodListener godListener;
    private SmithingGui gui;
    private Triarch triarch;
    private NamespacedKey bookGiven;

    public SmithingGui gui() {
        return gui;
    }

    public Triarch triarch() {
        return triarch;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        GodItems.init(this);
        SpawnGuard.load(this);
        bookGiven = new NamespacedKey(this, "book_given");
        registerRecipes();

        tables = new GodTables(this);
        godListener = new GodListener(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(tables, this);
        gui = new SmithingGui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new RecipeBook(), this);
        triarch = new Triarch(this);
        getServer().getPluginManager().registerEvents(triarch, this);
        getServer().getPluginManager().registerEvents(godListener, this);
        godListener.startPassives();

        GodCommand cmd = new GodCommand(this);
        getCommand("god").setExecutor(cmd);
        getCommand("god").setTabCompleter(cmd);
        getCommand("godrecipes").setExecutor(cmd);

        if (GodType.SPEAR.material() == null) {
            getLogger().warning("This server has no NETHERITE_SPEAR material - the God Spear is disabled.");
        }
        getLogger().info("GodGear enabled.");
    }

    @Override
    public void onDisable() {
        if (godListener != null) godListener.shutdown();
        if (triarch != null) triarch.shutdown();
        if (tables != null) tables.save();
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SmithingGui.Holder) {
                p.closeInventory(); // gives the inputs back
            }
        }
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();
    }

    // ------------------------------------------------------------------ recipes

    private void addRecipe(Recipe r, NamespacedKey key) {
        Bukkit.addRecipe(r);
        recipeKeys.add(key);
    }

    private void registerRecipes() {
        ItemStack template = GodItems.createTemplate();

        // Ambrosia: 9 specific ingredients, shapeless (any arrangement in the 3x3 grid).
        Material piglinHead = Material.matchMaterial("PIGLIN_HEAD");
        Material resinClump = Material.matchMaterial("RESIN_CLUMP");
        boolean ambrosiaAvailable = piglinHead != null && resinClump != null;
        if (ambrosiaAvailable) {
            NamespacedKey k0 = new NamespacedKey(this, "ambrosia");
            ShapelessRecipe r0 = new ShapelessRecipe(k0, GodItems.createAmbrosia());
            r0.addIngredient(Material.NETHER_STAR);
            r0.addIngredient(Material.TOTEM_OF_UNDYING);
            r0.addIngredient(resinClump);
            r0.addIngredient(Material.HEART_OF_THE_SEA);
            r0.addIngredient(Material.SNIFFER_EGG);
            r0.addIngredient(Material.ENCHANTED_GOLDEN_APPLE);
            r0.addIngredient(Material.TRIDENT);
            r0.addIngredient(piglinHead);
            r0.addIngredient(Material.ECHO_SHARD);
            addRecipe(r0, k0);
        } else {
            getLogger().warning("This server is missing Piglin Head and/or Resin Clump - "
                    + "the Ambrosia recipe is disabled and the God Template falls back to a plain Nether Star.");
        }

        // God Template:  S A S
        //                D M D      S = netherite sword, A = Ambrosia (or Nether Star as a fallback), M = mace
        //                H D H      D = diamond block,    H = player head
        NamespacedKey k1 = new NamespacedKey(this, "god_template");
        ShapedRecipe r1 = new ShapedRecipe(k1, template.clone());
        r1.shape("SAS", "DMD", "HDH");
        r1.setIngredient('S', Material.NETHERITE_SWORD);
        r1.setIngredient('A', ambrosiaAvailable ? new RecipeChoice.ExactChoice(GodItems.createAmbrosia())
                : new RecipeChoice.MaterialChoice(Material.NETHER_STAR));
        r1.setIngredient('M', Material.MACE);
        r1.setIngredient('D', Material.DIAMOND_BLOCK);
        r1.setIngredient('H', Material.PLAYER_HEAD);
        addRecipe(r1, k1);

        // Duplication: 7 netherite scrap + diamond block + a god template -> 2 god templates
        NamespacedKey k2 = new NamespacedKey(this, "god_template_dupe");
        ItemStack two = template.clone();
        two.setAmount(2);
        ShapedRecipe r2 = new ShapedRecipe(k2, two);
        r2.shape("SSS", "STS", "SBS");
        r2.setIngredient('S', Material.NETHERITE_SCRAP);
        r2.setIngredient('T', new RecipeChoice.ExactChoice(template.clone()));
        r2.setIngredient('B', Material.DIAMOND_BLOCK);
        addRecipe(r2, k2);

        // God Smithing Table = smithing table + god template (shapeless, crafting menu)
        NamespacedKey k3 = new NamespacedKey(this, "god_smithing_table");
        ShapelessRecipe r3 = new ShapelessRecipe(k3, GodItems.createTable());
        r3.addIngredient(Material.SMITHING_TABLE);
        r3.addIngredient(new RecipeChoice.ExactChoice(template.clone()));
        addRecipe(r3, k3);

        // Forging: God Template + netherite piece / mace in the REAL smithing screen (addition slot empty).
        // This recipe is what makes the vanilla screen accept netherite gear as a base; the actual
        // result is computed in SmithingGui#onPrepare.
        NamespacedKey k4 = new NamespacedKey(this, "god_forge");
        List<Material> bases = new ArrayList<>();
        for (GodType t : GodType.values()) {
            Material m = t.material();
            if (m != null) bases.add(m);
        }
        SmithingTransformRecipe r4 = new SmithingTransformRecipe(k4, new ItemStack(Material.NETHERITE_SWORD),
                new RecipeChoice.ExactChoice(template.clone()), new RecipeChoice.MaterialChoice(bases),
                RecipeChoice.empty());
        addRecipe(r4, k4);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.discoverRecipes(recipeKeys);
        if (getConfig().getBoolean("resource-pack.enabled", false)) {
            getServer().getScheduler().runTaskLater(this, () -> sendPack(p), 20L);
        }
        if (!p.getPersistentDataContainer().has(bookGiven, PersistentDataType.BYTE)) {
            p.getPersistentDataContainer().set(bookGiven, PersistentDataType.BYTE, (byte) 1);
            getServer().getScheduler().runTaskLater(this, () -> giveBook(p), 30L);
        }
    }

    // ------------------------------------------------------------------ the lore book

    private static final String[] BOOK_PAGES = {
            "&8&l~ THE BEGINNING ~\n\n&7There are no rules here.\n&7No admins to save you.\n&7No borders, no mercy.\n\n&7Only power - and those\n&7willing to seize it.",
            "&8This is an anarchy\n&8server.\n\n&7Everything you own can\n&7be taken. Everyone you\n&7meet can betray you.\n&7There is no land claim,\n&7no safety net.\n\n&7Trust is a currency few\n&7can afford to spend.",
            "&6&lGod Items\n\n&7Eight relics exist: a\n&6helmet, chestplate,\n&6leggings, boots, sword,\n&6axe, mace &7and &6spear&7,\n&7forged from netherite\n&7and grown monstrous\n&7with blood.",
            "&6&lForging\n\n&71. Craft a &6God Template&7:\n&7 2 netherite swords, a\n&7 nether star, a mace,\n&7 3 diamond blocks and\n&7 2 player heads.\n\n&72. Craft it with a\n&7 smithing table.",
            "&6&lForging &7(cont.)\n\n&73. Place the &6God\n&6 Smithing Table&7, then\n&7 right-click it.\n\n&74. Combine the template\n&7 with any netherite\n&7 piece or a mace to\n&7 forge a god item.",
            "&7Player heads are the\n&7key ingredient - and the\n&7only way to get one is\n&7to kill another player.\n\n&7The template asks a\n&7simple question: what\n&7are you willing to do?",
            "&6&lGrowing Stronger\n\n&7God items are born weak.\n&7Every &ckill &7on another\n&7player feeds them.\n\n&74 kills = 1 upgrade.\n&712 kills = fully grown,\n&7unlocking a weapon's or\n&7armour's final ability.",
            "&d&lPrestige\n\n&7A fully-grown item can\n&7be reforged at a god\n&7table with another\n&7template. Its kills\n&7reset - but it keeps\n&7growing beyond what was\n&7ever meant to be\n&7possible. Five times.",
            "&e&lAbilities\n\n&7Each item hides four\n&7powers, unlocked as it\n&7grows. Weapons trigger\n&7theirs with the key\n&7bound to 'Swap Item\n&7With Offhand' - rebind\n&7it in your controls.\n\n&7Read an item's lore to\n&7see what it can do.",
            "&5&lThe Convergence\n\n&7A mortal body cannot\n&7hold this much power on\n&7its own. Carry more god\n&7items than you've\n&7earned the right to, and\n&7something ancient will\n&7notice - and you will\n&7not enjoy its attention.",
            "&5&lThe Triarch Singularity\n\n&7That ancient thing has a\n&7name. It guards each\n&7threshold of power. Beat\n&7it, and it yields both a\n&7Convergence and\n&7treasures beyond reason.\n&7Fail, and it will not be\n&7gentle.",
            "&8&lGood luck.\n\n&7Everything from here is\n&7yours to take, lose, or\n&7die for.\n\n&7- &f???"
    };

    /** Gives (or re-gives) "The Beginning". Safe to call anytime - drops it if the inventory is full. */
    public void giveBook(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(BookMeta.class, m -> {
            m.title(GodItems.c("&8The Beginning"));
            m.author(net.kyori.adventure.text.Component.text("???"));
            List<net.kyori.adventure.text.Component> pages = new ArrayList<>();
            for (String pg : BOOK_PAGES) pages.add(GodItems.c(pg));
            m.pages(pages);
            m.setGeneration(BookMeta.Generation.ORIGINAL);
        });
        java.util.Map<Integer, ItemStack> left = p.getInventory().addItem(book);
        left.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
        p.playSound(p.getLocation(), "item.book.page_turn", 1f, 0.8f);
    }

    /** Sends the GodGear resource pack (custom item models + armour look) using the URL/hash from config.yml. */
    private void sendPack(Player p) {
        if (!p.isOnline()) return;
        String url = getConfig().getString("resource-pack.url", "").trim();
        String sha1 = getConfig().getString("resource-pack.sha1", "").trim();
        boolean force = getConfig().getBoolean("resource-pack.force", false);
        if (url.isEmpty()) return;
        Component prompt = Component.text("GodGear textures make god items look unique.");
        if (sha1.length() == 40) {
            byte[] hash = new byte[20];
            try {
                for (int i = 0; i < 20; i++) {
                    hash[i] = (byte) Integer.parseInt(sha1.substring(i * 2, i * 2 + 2), 16);
                }
                p.setResourcePack(url, hash, prompt, force);
                return;
            } catch (NumberFormatException ex) {
                getLogger().warning("resource-pack.sha1 in config.yml is not valid hex - sending without a hash.");
            }
        }
        p.setResourcePack(url);
    }

    /** God templates / tables can only be used in our own recipes (not the vanilla template-dupe recipe etc). */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r instanceof Keyed k && recipeKeys.contains(k.getKey())) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (GodItems.isTemplate(it) || GodItems.isTable(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ heads

    // ------------------------------------------------------------------ god items on death

    /**
     * God items always drop when their owner dies - even with keepInventory on. They drop with their kills,
     * tier and prestige untouched (that data lives on the item itself).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGodItemsDrop(PlayerDeathEvent e) {
        if (!e.getKeepInventory()) return; // normally the vanilla drop already handles it
        if (triarch != null && triarch.willDestroyLoot(e.getEntity())) return; // the Triarch devours everything
        Player victim = e.getEntity();
        PlayerInventory inv = victim.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (GodItems.getType(it) != null) {
                victim.getWorld().dropItemNaturally(victim.getLocation(), it.clone());
                inv.setItem(i, null);
            }
        }
    }

    @EventHandler
    public void onGodItemSpawn(ItemSpawnEvent e) {
        if (GodItems.getType(e.getEntity().getItemStack()) != null) {
            e.getEntity().setGlowing(true);      // easy to find
            e.getEntity().setInvulnerable(true); // survives explosions / cactus / fire
        }
    }

    @EventHandler
    public void onGodItemDespawn(ItemDespawnEvent e) {
        if (GodItems.getType(e.getEntity().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("head-drops", true)) return;
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return; // heads only drop when another player makes the kill

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, m -> {
            m.setOwningPlayer(victim);
            m.displayName(GodItems.c("&c" + victim.getName() + "'s Head"));
            m.lore(List.of(
                    GodItems.c("&7Slain by &e" + killer.getName()),
                    GodItems.c("&8Used to forge God Templates")));
        });
        if (e.getKeepInventory()) {
            // e.getDrops() is ignored by the game when keepInventory is on, so drop it directly
            victim.getWorld().dropItemNaturally(victim.getLocation(), head);
        } else {
            e.getDrops().add(head);
        }
    }

    // ------------------------------------------------------------------ Convergence

    @EventHandler(ignoreCancelled = true)
    public void onConvergenceUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();

        if (GodItems.isAmbrosia(it)) {
            e.setCancelled(true);
            consumeAmbrosia(p, it);
            return;
        }

        int level = GodItems.convergenceItemLevel(it);
        if (level == 0) return;
        e.setCancelled(true);

        int current = GodItems.convergenceLevel(p);
        if (current >= level) {
            p.sendMessage(GodItems.c("&7You already possess Convergence " + current + " or higher."));
            return;
        }
        if (current < level - 1) {
            p.sendMessage(GodItems.c("&cYou must first attain Convergence " + (level - 1) + "."));
            return;
        }

        it.setAmount(it.getAmount() - 1);
        GodItems.setConvergenceLevel(p, level);
        p.sendMessage(GodItems.c("&5&l✦ &dThe Convergence flows through you. &7You may now safely hold up to &e"
                + GodItems.convergenceCap(level) + " &7distinct god items - permanently."));

        Color col = level == 3 ? Color.fromRGB(120, 0, 200) : level == 2 ? Color.fromRGB(60, 120, 255) : Color.WHITE;
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 70, 0.6, 1, 0.6, 0,
                new Particle.DustOptions(col, 1.6f));
        p.getWorld().playSound(p.getLocation(), "entity.wither.spawn", 0.6f, 1.6f);
    }

    /** Applies the Ambrosia buff bundle and consumes one of the item's two uses. */
    private void consumeAmbrosia(Player p, ItemStack it) {
        int uses = GodItems.ambrosiaUses(it);

        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 4800, 9, true, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 4800, 4, true, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 4800, 9, true, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 4800, 3, true, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 4800, 1, true, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 4800, 1, true, true, true));
        GodItems.markAmbrosia(p, 4800L * 50L); // 4 minutes; god armour's own defences are dampened while this is active
        p.setFoodLevel(20);
        p.setSaturation(20f);

        PlayerInventory inv = p.getInventory();
        ItemStack current = inv.getItemInMainHand();
        int remaining = uses - 1;
        if (current.getAmount() > 1) {
            current.setAmount(current.getAmount() - 1);
            if (remaining > 0) {
                java.util.Map<Integer, ItemStack> left = inv.addItem(GodItems.withAmbrosiaUses(remaining));
                left.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            }
        } else {
            inv.setItemInMainHand(remaining > 0 ? GodItems.withAmbrosiaUses(remaining) : null);
        }

        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 50, 0.5, 0.9, 0.5, 0.3);
        p.getWorld().playSound(p.getLocation(), "item.honey_bottle.drink", 1f, 0.8f);
        p.sendMessage(GodItems.c("&6&l✦ &eAmbrosia &7courses through you." + (remaining > 0 ? "" : " &8(that was its last use)")));
    }
}
