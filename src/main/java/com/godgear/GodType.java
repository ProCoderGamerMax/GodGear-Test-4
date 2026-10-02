package com.godgear;

import org.bukkit.Material;

/**
 * The 8 god items. Abilities unlock by tier (ability N unlocks at tier N, capped at tier 3);
 * tier goes up by one every 4 kills (max 12 kills = tier 3). The sword and mace have a 5th
 * ability, Godslayer, which unlocks together with the 4th (at 12 kills).
 * "[F]" / "[Shift+F]" in descriptions are replaced by the configured ability key label.
 */
public enum GodType {
    HELMET("God Helmet", "NETHERITE_HELMET", true, new String[][]{
            {"Eyes of the Gods", "Permanent Night Vision & Water Breathing"},
            {"Clear Mind", "Immune to Blindness, Darkness & Nausea"},
            {"Third Eye", "Nearby enemies are revealed with Glowing"},
            {"Divine Nourishment", "Hunger never drains"}
    }),
    CHESTPLATE("God Chestplate", "NETHERITE_CHESTPLATE", true, new String[][]{
            {"Titan's Vitality", "+10 hearts & Resistance"},
            {"Mirror Aegis", "Reflects 30% of melee damage back"},
            {"Second Life", "Cheat death (15 minute cooldown)"},
            {"Unyielding", "Immune to fire & lava (not explosions)"}
    }),
    LEGGINGS("God Leggings", "NETHERITE_LEGGINGS", true, new String[][]{
            {"Godspeed", "Speed {LVL}"},
            {"Immovable", "Immune to knockback"},
            {"Wraith Dash", "Sprint + Sneak to dash forward"},
            {"Phase Shift", "60% chance to dodge projectiles"}
    }),
    BOOTS("God Boots", "NETHERITE_BOOTS", true, new String[][]{
            {"Titan Stride", "No fall damage & permanent Strength {LVL}"},
            {"Tidewalker", "Fire Resistance & Dolphin's Grace"},
            {"Groundswell", "Sprinting launches you forward"},
            {"Shockwave Landing", "Landings blast nearby enemies"}
    }),
    SPEAR("God Spear", "NETHERITE_SPEAR", false, new String[][]{
            {"Godly Point", "+40% damage"},
            {"Armor Piercing", "4 true damage on every hit"},
            {"Piercing Charge", "[F] Dash through enemies"},
            {"Skyfall Volley", "[Shift+F] Rain arrows on your target"},
            {"Godbreaker", "Vs god armour: x1.5 damage & +4 true damage"}
    }),
    MACE("God Mace", "MACE", false, new String[][]{
            {"Titan Smash", "Smash x1.75 + shockwave, no fall damage"},
            {"Stunning Blow", "Slows & weakens, 30% chance to stun"},
            {"Wind Rider", "[F] Launch yourself skyward"},
            {"Gravity Well", "[Shift+F] Pull enemies to you"},
            {"Godslayer", "Vs god armour: x1.75 damage & +6 true damage"}
    }),
    SWORD("God Sword", "NETHERITE_SWORD", false, new String[][]{
            {"Godly Edge", "+50% damage"},
            {"Vampiric", "Heal 25% of damage dealt"},
            {"Wrath Wave", "[F] Fire a piercing energy wave"},
            {"Smiting Storm", "[Shift+F] Lightning on nearby enemies"},
            {"Godslayer", "Vs god armour: x1.75 damage & +6 true damage"}
    }),
    AXE("God Axe", "NETHERITE_AXE", false, new String[][]{
            {"Cleave", "+25% damage, hits enemies around the target"},
            {"Sunder", "Disables shields, +4 damage, bleeding"},
            {"Berserker Rage", "[F] Strength III, Speed II & Resistance"},
            {"Earthsplitter", "[Shift+F] Ground slam"},
            {"Godbreaker", "Vs god armour: x1.5 damage & +4 true damage"}
    });

    private final String display;
    private final String materialName;
    private final boolean armor;
    private final String[][] abilities;

    GodType(String display, String materialName, boolean armor, String[][] abilities) {
        this.display = display;
        this.materialName = materialName;
        this.armor = armor;
        this.abilities = abilities;
    }

    public String display() {
        return display;
    }

    public boolean isArmor() {
        return armor;
    }

    /** Base material (netherite piece / mace). Null if this server has no such material (e.g. no spears). */
    public Material material() {
        return Material.matchMaterial(materialName);
    }

    public int abilityCount() {
        return abilities.length;
    }

    /** {name, description} of ability i. */
    public String[] ability(int i) {
        return abilities[i];
    }

    /** Item model id used by the resource pack, e.g. godgear:god_sword */
    public String modelId() {
        return "godgear:god_" + name().toLowerCase();
    }

    public static GodType byName(String s) {
        for (GodType t : values()) {
            if (t.name().equalsIgnoreCase(s)) return t;
        }
        return null;
    }
}
