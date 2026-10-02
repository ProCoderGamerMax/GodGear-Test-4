package com.godgear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.attribute.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Everything about creating / reading / upgrading god items. */
public final class GodItems {

    public static final int KILLS_PER_TIER = 4;
    public static final int MAX_TIER = 3;
    public static final int MAX_KILLS = KILLS_PER_TIER * MAX_TIER; // 12
    public static final int MAX_PRESTIGE = 5;
    public static final double WEAPON_PRESTIGE_BONUS = 0.15;  // +15% damage per prestige level
    public static final double ARMOR_PRESTIGE_BONUS = 0.10;   // +10% damage reduction per prestige level, per piece
    private static final String[] PRESTIGE_COLORS = {"&6", "&b", "&d", "&a", "&c", "&5"};
    private static final String[] TIER_NAMES = {"Awakened", "Ascended", "Exalted", "Divine"};

    private static String keyLabel = "X";
    private static boolean customModels = true;

    private static NamespacedKey AMBROSIA_UNTIL;
    private static NamespacedKey TYPE, KILLS, TEMPLATE, TABLE, PRESTIGE, ATTACK_SPEED, CONVERGENCE_LEVEL, CONVERGED, AMBROSIA, AMBROSIA_USES;

    private GodItems() {}

    public static void init(Plugin plugin) {
        TYPE = new NamespacedKey(plugin, "god_type");
        KILLS = new NamespacedKey(plugin, "god_kills");
        TEMPLATE = new NamespacedKey(plugin, "god_template");
        TABLE = new NamespacedKey(plugin, "god_table");
        PRESTIGE = new NamespacedKey(plugin, "god_prestige");
        ATTACK_SPEED = new NamespacedKey(plugin, "god_attack_speed");
        AMBROSIA_UNTIL = new NamespacedKey(plugin, "ambrosia_until");
        CONVERGENCE_LEVEL = new NamespacedKey(plugin, "convergence_level");
        CONVERGED = new NamespacedKey(plugin, "converged_level");
        AMBROSIA = new NamespacedKey(plugin, "ambrosia");
        AMBROSIA_USES = new NamespacedKey(plugin, "ambrosia_uses");
        keyLabel = plugin.getConfig().getString("ability-key-label", "X");
        customModels = plugin.getConfig().getBoolean("custom-models", true);
    }

    /** "&6text" -> non-italic component. */
    public static Component c(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Label shown for the ability key (the player rebinds "Swap Item With Offhand" to it). */
    public static String keyLabel() {
        return keyLabel;
    }

    private static String desc(String d) {
        return d.replace("[Shift+F]", "[Shift+" + keyLabel + "]").replace("[F]", "[" + keyLabel + "]");
    }

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};

    /**
     * Potion amplifier (0-indexed) for this item's per-item prestige-scaled effects (Speed on
     * leggings, Strength on boots): level II by default, level III at prestige 3+, level IV at
     * max prestige (5).
     */
    public static int scaledAmplifier(int prestige) {
        return 1 + (prestige >= MAX_PRESTIGE ? 2 : prestige >= 3 ? 1 : 0);
    }

    private static String abilityDesc(GodType t, int i, int prestige) {
        String d = desc(t.ability(i)[1]);
        if (i == 0 && (t == GodType.LEGGINGS || t == GodType.BOOTS)) {
            d = d.replace("{LVL}", ROMAN[scaledAmplifier(prestige)]);
        }
        return d;
    }

    /** Resource-pack look: item model (+ worn armour model). Skipped when custom-models is false. */
    private static void applyLook(ItemMeta m, GodType t) {
        if (!customModels) return;
        m.setItemModel(NamespacedKey.fromString(t.modelId()));
        if (t.isArmor()) {
            try {
                EquippableComponent eq = m.getEquippable();
                eq.setSlot(switch (t) {
                    case HELMET -> EquipmentSlot.HEAD;
                    case CHESTPLATE -> EquipmentSlot.CHEST;
                    case LEGGINGS -> EquipmentSlot.LEGS;
                    default -> EquipmentSlot.FEET;
                });
                eq.setModel(NamespacedKey.fromString("godgear:god"));
                m.setEquippable(eq);
            } catch (Exception ignored) {
                // older/newer API without equippable models - item model alone still works
            }
        }
    }

    private static void applyModel(ItemMeta m, String modelId) {
        if (customModels) m.setItemModel(NamespacedKey.fromString(modelId));
    }

    // ---------------------------------------------------------------- Convergence

    private static final int[] CONVERGENCE_CAPS = {0, 1, 4, 8};
    private static final String[] CONVERGENCE_NAMES = {"", "Convergence I", "Convergence II", "Convergence III"};
    private static final String[] CONVERGENCE_COLORS = {"", "&f", "&b", "&5"};

    /** How many distinct god items a player can safely hold at this Convergence level (0-3). */
    public static int convergenceCap(int level) {
        return CONVERGENCE_CAPS[Math.max(0, Math.min(3, level))];
    }

    /** The player's current Convergence level (0 = none). Stored permanently on the player. */
    public static int convergenceLevel(Player p) {
        Integer l = p.getPersistentDataContainer().get(CONVERGED, PersistentDataType.INTEGER);
        return l == null ? 0 : l;
    }

    public static void setConvergenceLevel(Player p, int level) {
        p.getPersistentDataContainer().set(CONVERGED, PersistentDataType.INTEGER, Math.max(0, Math.min(3, level)));
    }

    /** How many distinct god items this player may currently hold without divine backlash. */
    public static int carryLimit(Player p) {
        return convergenceCap(convergenceLevel(p));
    }

    public static ItemStack createConvergence(int level) {
        int lvl = Math.max(1, Math.min(3, level));
        int cap = convergenceCap(lvl);
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        it.editMeta(m -> {
            m.displayName(c(CONVERGENCE_COLORS[lvl] + "&l✦ The " + CONVERGENCE_NAMES[lvl] + " ✦"));
            m.lore(List.of(
                    c("&7Right-click to consume."),
                    c("&7Permanently raises how many god items you"),
                    c("&7can hold at once, without cost, to &e" + cap + "&7."),
                    Component.empty(),
                    c("&7Grants &dGod Protection&7 - a lasting divine aura.")));
            m.setEnchantmentGlintOverride(true);
            applyModel(m, "godgear:convergence_" + lvl);
            m.getPersistentDataContainer().set(CONVERGENCE_LEVEL, PersistentDataType.INTEGER, lvl);
        });
        return it;
    }

    /** 0 if this isn't a Convergence item, else the level (1-3) it grants. */
    public static int convergenceItemLevel(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return 0;
        Integer l = it.getItemMeta().getPersistentDataContainer().get(CONVERGENCE_LEVEL, PersistentDataType.INTEGER);
        return l == null ? 0 : l;
    }

    // ---------------------------------------------------------------- Triarch trophy (loot)

    private static final String[] TROPHY_NAMES = {"", "Fragment of the Triarch",
            "Fragment of the Triarch (Resurgent)", "Fragment of the Triarch (Awakened)"};

    public static ItemStack createTrophy(int stage) {
        int s = Math.max(1, Math.min(3, stage));
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        it.editMeta(m -> {
            m.displayName(c("&5&l" + TROPHY_NAMES[s]));
            m.lore(List.of(c("&7Proof you overcame The Triarch Singularity.")));
            m.setEnchantmentGlintOverride(true);
            applyModel(m, "godgear:trophy_" + s);
        });
        return it;
    }

    // ---------------------------------------------------------------- Ambrosia

    public static final int AMBROSIA_MAX_USES = 2;

    private static List<Component> ambrosiaLore(int uses) {
        return List.of(
                c("&7Food of the old gods."),
                c("&7Right-click to consume."),
                c("&7&o\"Ambrosia, and nectar pure,"),
                c("&7&o did the deathless gods sustain.\""),
                Component.empty(),
                c("&7For 4 minutes: &c+20 hearts&7, &dRegeneration V&7,"),
                c("&7&fAbsorption X&7, &9Resistance IV&7, &6Strength II&7 & &bSpeed II&7."),
                Component.empty(),
                c("&e" + uses + " &7use" + (uses == 1 ? "" : "s") + " remaining"));
    }

    public static ItemStack createAmbrosia() {
        return withAmbrosiaUses(AMBROSIA_MAX_USES);
    }

    /** A single-unit Ambrosia stack carrying a specific remaining-uses count. */
    public static ItemStack withAmbrosiaUses(int uses) {
        ItemStack it = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        int u = Math.max(0, uses);
        it.editMeta(m -> {
            m.displayName(c("&6&l✦ Ambrosia ✦"));
            m.lore(ambrosiaLore(u));
            m.setEnchantmentGlintOverride(true);
            applyModel(m, "godgear:ambrosia");
            m.getPersistentDataContainer().set(AMBROSIA, PersistentDataType.BYTE, (byte) 1);
            m.getPersistentDataContainer().set(AMBROSIA_USES, PersistentDataType.INTEGER, u);
        });
        return it;
    }

    public static boolean isAmbrosia(ItemStack it) {
        return hasTag(it, AMBROSIA);
    }

    public static int ambrosiaUses(ItemStack it) {
        if (!isAmbrosia(it)) return 0;
        Integer u = it.getItemMeta().getPersistentDataContainer().get(AMBROSIA_USES, PersistentDataType.INTEGER);
        return u == null ? AMBROSIA_MAX_USES : u;
    }

    public static String tierName(int tier) {
        return TIER_NAMES[Math.max(0, Math.min(MAX_TIER, tier))];
    }

    // ---------------------------------------------------------------- template / table

    public static ItemStack createTemplate() {
        ItemStack it = new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        it.editMeta(m -> {
            m.displayName(c("&6&l✦ God Template ✦"));
            m.lore(List.of(
                    c("&7Combine with a &bNetherite piece &7(or a Mace)"),
                    c("&7at a &6God Smithing Table &7to forge a god item."),
                    Component.empty(),
                    c("&8Duplicate: 7 Netherite Scrap + Diamond Block")));
            m.setEnchantmentGlintOverride(true);
            if (customModels) m.setItemModel(NamespacedKey.fromString("godgear:god_template"));
            m.getPersistentDataContainer().set(TEMPLATE, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public static ItemStack createTable() {
        ItemStack it = new ItemStack(Material.SMITHING_TABLE);
        it.editMeta(m -> {
            m.displayName(c("&6&lGod Smithing Table"));
            m.lore(List.of(
                    c("&7Place it down, then right-click it"),
                    c("&7to forge god items using a God Template.")));
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(TABLE, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    private static boolean hasTag(ItemStack it, NamespacedKey key) {
        return it != null && !it.getType().isAir() && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public static boolean isTemplate(ItemStack it) {
        return hasTag(it, TEMPLATE);
    }

    public static boolean isTable(ItemStack it) {
        return hasTag(it, TABLE);
    }

    // ---------------------------------------------------------------- god items

    public static GodType getType(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(TYPE, PersistentDataType.STRING);
        return s == null ? null : GodType.byName(s);
    }

    public static int getKills(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer k = it.getItemMeta().getPersistentDataContainer().get(KILLS, PersistentDataType.INTEGER);
        return k == null ? 0 : k;
    }

    public static int tierOf(int kills) {
        return Math.min(kills, MAX_KILLS) / KILLS_PER_TIER;
    }

    public static int tier(ItemStack it) {
        return tierOf(getKills(it));
    }

    public static int getPrestige(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer p = it.getItemMeta().getPersistentDataContainer().get(PRESTIGE, PersistentDataType.INTEGER);
        return p == null ? 0 : p;
    }

    /** The armour item worn in that slot (may be null / not a god item). */
    public static ItemStack wornPiece(Player p, GodType type) {
        return switch (type) {
            case HELMET -> p.getInventory().getHelmet();
            case CHESTPLATE -> p.getInventory().getChestplate();
            case LEGGINGS -> p.getInventory().getLeggings();
            case BOOTS -> p.getInventory().getBoots();
            default -> null;
        };
    }

    /** Tier of the worn god armour piece of that type, or -1 if not wearing one. */
    public static int armorTier(Player p, GodType type) {
        ItemStack it = switch (type) {
            case HELMET -> p.getInventory().getHelmet();
            case CHESTPLATE -> p.getInventory().getChestplate();
            case LEGGINGS -> p.getInventory().getLeggings();
            case BOOTS -> p.getInventory().getBoots();
            default -> null;
        };
        return getType(it) == type ? tier(it) : -1;
    }

    /** Every distinct GodType currently equipped or carried by this player (armour + both hands + storage). */
    public static java.util.Set<GodType> distinctGodTypes(Player p) {
        java.util.Set<GodType> found = new java.util.HashSet<>();
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getArmorContents()) addType(found, it);
        addType(found, inv.getItemInMainHand());
        addType(found, inv.getItemInOffHand());
        for (ItemStack it : inv.getStorageContents()) addType(found, it);
        return found;
    }

    private static void addType(java.util.Set<GodType> set, ItemStack it) {
        GodType t = getType(it);
        if (t != null) set.add(t);
    }

    /** Every god item this player physically has, counting duplicates (armour + both hands + storage). */
    public static int totalGodItemCount(Player p) {
        int total = 0;
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getArmorContents()) if (getType(it) != null) total += it.getAmount();
        if (getType(inv.getItemInMainHand()) != null) total += inv.getItemInMainHand().getAmount();
        if (getType(inv.getItemInOffHand()) != null) total += inv.getItemInOffHand().getAmount();
        for (ItemStack it : inv.getStorageContents()) if (it != null && getType(it) != null) total += it.getAmount();
        return total;
    }

    /** Highest prestige among a player's worn armour + held weapon - drives the prestige aura. */
    public static int maxEquippedPrestige(Player p) {
        int max = 0;
        for (ItemStack it : p.getInventory().getArmorContents()) max = Math.max(max, getPrestige(it));
        max = Math.max(max, getPrestige(p.getInventory().getItemInMainHand()));
        return max;
    }

    /** A 12-kill god item that can still be prestiged. */
    public static boolean isPrestigeReady(ItemStack it) {
        return getType(it) != null && getKills(it) >= MAX_KILLS && getPrestige(it) < MAX_PRESTIGE;
    }

    /** A plain netherite piece / mace, or a maxed god item ready to prestige. */
    public static boolean isUpgradableBase(ItemStack it) {
        return baseTypeOf(it) != null || isPrestigeReady(it);
    }

    private static GodType baseTypeOf(ItemStack it) {
        if (it == null || it.getType().isAir() || getType(it) != null) return null;
        for (GodType t : GodType.values()) {
            Material m = t.material();
            if (m != null && m == it.getType()) return t;
        }
        return null;
    }

    /**
     * What the smithing table produces for (base, template), or null if invalid.
     * Plain netherite piece -> god item. 12-kill god item -> prestige +1 (kills reset to 0).
     */
    public static ItemStack upgradeResult(ItemStack base, ItemStack template) {
        if (!isTemplate(template) || base == null || base.getType().isAir()) return null;

        if (isPrestigeReady(base)) {
            int next = getPrestige(base) + 1;
            ItemStack r = base.clone();
            r.setAmount(1);
            r.editMeta(m -> {
                m.getPersistentDataContainer().set(KILLS, PersistentDataType.INTEGER, 0);
                m.getPersistentDataContainer().set(PRESTIGE, PersistentDataType.INTEGER, next);
            });
            refresh(r);
            return r;
        }

        GodType t = baseTypeOf(base);
        if (t == null) return null;
        ItemStack r = base.clone();
        r.setAmount(1);
        apply(r, t, 0);
        return r;
    }

    /** Turn a stack into a god item (keeps enchants, trims, damage...). */
    private static void apply(ItemStack it, GodType t, int kills) {
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(TYPE, PersistentDataType.STRING, t.name());
            m.getPersistentDataContainer().set(KILLS, PersistentDataType.INTEGER, kills);
            m.setUnbreakable(true);
            m.setEnchantmentGlintOverride(true);
            maxEnchant(m, it); // armour AND weapons: every applicable enchantment at max level
            // No attack-speed modifier: god weapons use vanilla swing cooldowns.
        });
        refresh(it);
    }

    /** Marks the player as Ambrosia-fuelled for `ms` milliseconds (persists across restarts). */
    public static void markAmbrosia(Player p, long ms) {
        p.getPersistentDataContainer().set(AMBROSIA_UNTIL, PersistentDataType.LONG, System.currentTimeMillis() + ms);
    }

    /** True while Ambrosia is genuinely active (timer not expired AND its Health Boost wasn't removed, e.g. by milk). */
    public static boolean ambrosiaActive(Player p) {
        Long until = p.getPersistentDataContainer().get(AMBROSIA_UNTIL, PersistentDataType.LONG);
        if (until == null || until <= System.currentTimeMillis()) return false;
        var hb = p.getPotionEffect(org.bukkit.potion.PotionEffectType.HEALTH_BOOST);
        return hb != null && hb.getAmplifier() >= 9;
    }

    // ---------------------------------------------------------------- armour trims
    private static final String[] TRIM_MATERIALS = {"gold", "emerald", "diamond", "amethyst", "redstone", "resin"}; // by prestige
    private static TrimPattern trimPattern(GodType t) {
        String key = switch (t) {
            case HELMET -> "silence";
            case CHESTPLATE -> "ward";
            case LEGGINGS -> "spire";
            default -> "vex"; // boots
        };
        return Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(key));
    }
    private static TrimMaterial trimMaterial(int prestige) {
        return Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(TRIM_MATERIALS[Math.max(0, Math.min(prestige, TRIM_MATERIALS.length - 1))]));
    }
    private static void applyTrim(ItemMeta m, GodType t, int prestige) {
        if (!t.isArmor() || !(m instanceof ArmorMeta am)) return;
        TrimPattern pat = trimPattern(t);
        TrimMaterial mat = trimMaterial(prestige);
        if (pat != null && mat != null) am.setTrim(new ArmorTrim(mat, pat));
    }

    /** Gives older god armour its trim. Returns true if the stack was changed. */
    public static boolean ensureTrim(ItemStack it) {
        GodType t = getType(it);
        if (t == null || !t.isArmor()) return false;
        ItemMeta m = it.getItemMeta();
        if (!(m instanceof ArmorMeta am) || am.getTrim() != null) return false;
        applyTrim(m, t, getPrestige(it));
        it.setItemMeta(m);
        return true;
    }

    /**
     * Older god weapons were forged with a huge Attack Speed bonus (near-zero hit cooldown). This removes it
     * so they use vanilla cooldowns. Returns true if the stack was changed.
     */
    public static boolean stripZeroCooldown(ItemStack it) {
        GodType t = getType(it);
        if (t == null || t.isArmor()) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        var mods = m.getAttributeModifiers(Attribute.ATTACK_SPEED);
        if (mods == null) return false;
        boolean found = false;
        for (AttributeModifier am : mods) {
            if (ATTACK_SPEED.equals(am.getKey())) { found = true; break; }
        }
        if (!found) return false;
        m.setAttributeModifiers(null); // clears the override -> the item's normal vanilla attributes apply again
        it.setItemMeta(m);
        return true;
    }

    /**
     * Adds every enchantment that applies to this item at its maximum level, including ones that
     * normally conflict with each other. Curse of Vanishing is deliberately skipped: it would erase
     * the item itself, which defeats the "god items always drop on death" behaviour.
     */
    private static void maxEnchant(ItemMeta m, ItemStack reference) {
        for (Enchantment ench : Registry.ENCHANTMENT) {
            if (ench.equals(Enchantment.VANISHING_CURSE)) continue;
            if (!ench.canEnchantItem(reference)) continue;
            m.addEnchant(ench, ench.getMaxLevel(), true);
        }
    }

    /** Fresh god item straight from a command (for admins/testing). Null if the base material doesn't exist. */
    public static ItemStack createGod(GodType t, int kills) {
        Material m = t.material();
        if (m == null) return null;
        ItemStack it = new ItemStack(m);
        apply(it, t, Math.max(0, Math.min(MAX_KILLS, kills)));
        return it;
    }

    /** Testing utility: a god item at a specific kills+prestige combo, bypassing normal forging. */
    public static ItemStack createGodWithPrestige(GodType t, int kills, int prestige) {
        ItemStack it = createGod(t, kills);
        if (it == null) return null;
        int clamped = Math.max(0, Math.min(MAX_PRESTIGE, prestige));
        it.editMeta(m -> m.getPersistentDataContainer().set(PRESTIGE, PersistentDataType.INTEGER, clamped));
        refresh(it);
        return it;
    }

    /** +1 kill. Returns true if this pushed the item up a tier. */
    public static boolean addKill(ItemStack it) {
        GodType t = getType(it);
        if (t == null) return false;
        int k = getKills(it);
        if (k >= MAX_KILLS) return false;
        int nk = k + 1;
        it.editMeta(m -> m.getPersistentDataContainer().set(KILLS, PersistentDataType.INTEGER, nk));
        refresh(it);
        return tierOf(nk) > tierOf(k);
    }

    /** Rebuild name + lore from the stored kills / prestige. */
    public static void refresh(ItemStack it) {
        GodType t = getType(it);
        if (t == null) return;
        int kills = getKills(it);
        int tier = tierOf(kills);
        int prestige = getPrestige(it);
        String color = PRESTIGE_COLORS[Math.min(prestige, PRESTIGE_COLORS.length - 1)];

        List<Component> lore = new ArrayList<>();
        lore.add(c("&8God-Forged " + (t.isArmor() ? "Armour" : "Weapon")));
        lore.add(Component.empty());
        lore.add(c("&7Rank: &e" + tierName(tier) + " &8(" + tier + "/" + MAX_TIER + ")"));
        lore.add(c("&7Kills: &c" + kills + "&7/&c" + MAX_KILLS));
        lore.add(c("&a" + "▌".repeat(kills) + "&8" + "▌".repeat(MAX_KILLS - kills)));
        if (prestige > 0) {
            lore.add(c("&7Prestige: &d" + "✦".repeat(prestige) + " &8(" + prestige + "/" + MAX_PRESTIGE + ")"));
            lore.add(c(t.isArmor()
                    ? "&7Permanent bonus: &a+" + Math.round(prestige * ARMOR_PRESTIGE_BONUS * 100) + "% damage reduction"
                    : "&7Permanent bonus: &a+" + Math.round(prestige * WEAPON_PRESTIGE_BONUS * 100) + "% damage"));
        }
        lore.add(Component.empty());
        lore.add(c("&6&lDivine Abilities"));
        for (int i = 0; i < t.abilityCount(); i++) {
            String[] a = t.ability(i);
            int needTier = Math.min(i, MAX_TIER); // the 5th ability unlocks together with the 4th
            if (tier >= needTier) {
                lore.add(c((i >= 4 ? "&c&l☠ &c" : "&a✔ &e") + a[0]));
                lore.add(c("   &7" + abilityDesc(t, i, prestige)));
            } else {
                lore.add(c("&8✘ " + a[0] + " &8(unlocks at " + (needTier * KILLS_PER_TIER) + " kills)"));
            }
        }
        lore.add(Component.empty());
        if (tier < MAX_TIER) {
            lore.add(c("&7Next unlock in &c" + (KILLS_PER_TIER - kills % KILLS_PER_TIER) + " &7kills"));
        } else if (prestige < MAX_PRESTIGE) {
            lore.add(c("&d&l✦ Ready to Prestige!"));
            lore.add(c("&7Reforge with a God Template at a"));
            lore.add(c("&7God Smithing Table (resets kills)."));
        } else {
            lore.add(c("&d&l✦ MAX POWER ✦"));
        }

        Component name = c(color + "&l" + t.display() + " &e" + "★".repeat(tier) + "&8" + "☆".repeat(MAX_TIER - tier)
                + (prestige > 0 ? " &d✦" + prestige : ""));
        it.editMeta(m -> {
            m.displayName(name);
            m.lore(lore);
            applyLook(m, t);
            applyTrim(m, t, prestige);
        });
    }
}
