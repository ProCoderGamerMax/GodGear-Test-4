package com.godgear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityKnockbackByEntityEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerDeathEvent;
import org.bukkit.Color;
import org.bukkit.Particle.DustOptions;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Passive armour effects, weapon on-hit effects, [F] / [Shift+F] active abilities and kill counting.
 * Ability N of an item is unlocked when the item's tier >= N.
 */
public class GodListener implements Listener {

    private final GodGear plugin;
    private final Random rng = new Random();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Set<PotionEffectType>> granted = new HashMap<>();
    private final NamespacedKey volleyKey;
    private final NamespacedKey healthKey;

    /** True while the plugin itself is dealing damage, so ability damage doesn't trigger more abilities. */
    private boolean busy = false;
    /** True while Mirror Aegis reflects damage (reflection must not count as "hitting" someone). */
    private boolean reflecting = false;

    private final Map<UUID, Long> lastPvpHit = new HashMap<>();
    /** victimId -> time until which the Mace's Stunning Blow cannot proc on them again. */
    private final Map<UUID, Long> stunImmuneUntil = new HashMap<>();
    private final Set<UUID> setActive = new HashSet<>();

    /** Anti-farm: killer+victim pair -> last time that pair's kill counted towards a god item. */
    private final Map<String, Long> killPairCooldown = new HashMap<>();

    /** Who last landed a named-ability killing blow on whom, for custom death messages. */
    private record AbilityKill(String ability, String killerName, long time) {}
    private final Map<UUID, AbilityKill> lastAbilityHit = new HashMap<>();

    public GodListener(GodGear plugin) {
        this.plugin = plugin;
        this.volleyKey = new NamespacedKey(plugin, "volley_arrow");
        this.healthKey = new NamespacedKey(plugin, "titan_vitality");
    }

    // =====================================================================================
    //  helpers
    // =====================================================================================

    private void sound(Location l, String id, float vol, float pitch) {
        l.getWorld().playSound(l, id, vol, pitch);
    }

    private void bar(Player p, String msg, NamedTextColor color) {
        p.sendActionBar(Component.text(msg, color));
    }

    private boolean ready(Player p, String id, int defaultSeconds, boolean notify) {
        int secs = plugin.getConfig().getInt("cooldowns." + id, defaultSeconds);
        String k = p.getUniqueId() + ":" + id;
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(k);
        if (until != null && until > now) {
            if (notify) bar(p, "⏳ Cooldown: " + ((until - now + 999) / 1000) + "s", NamedTextColor.RED);
            return false;
        }
        cooldowns.put(k, now + secs * 1000L);
        return true;
    }

    /** Something an offensive god ability is allowed to hurt. */
    private boolean valid(Player p, Entity e) {
        if (!(e instanceof LivingEntity le) || e.equals(p) || e instanceof ArmorStand) return false;
        if (le.isDead() || le.isInvulnerable()) return false;
        if (e instanceof Tameable t && p.equals(t.getOwner())) return false;
        if (e instanceof Player o && (o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR)) return false;
        return e instanceof Enemy || e instanceof Player;
    }

    private void hurt(LivingEntity target, double dmg, Player source) {
        boolean old = busy;
        busy = true;
        try {
            target.damage(dmg, source);
        } finally {
            busy = old;
        }
    }

    private void knock(LivingEntity le, Location from, double horizontal, double vertical) {
        if (le instanceof Player pl && GodItems.armorTier(pl, GodType.LEGGINGS) >= 1) return; // Immovable
        Vector v = le.getLocation().toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() < 1.0E-4) v = new Vector(rng.nextDouble() - 0.5, 0, rng.nextDouble() - 0.5);
        v = v.normalize().multiply(horizontal).setY(vertical);
        le.setVelocity(v);
    }

    private double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    /** Deals ability damage AND remembers it, so a lethal blow can show a custom death message. */
    private void abilityHurt(LivingEntity target, double dmg, Player source, String abilityName) {
        if (target instanceof Player) {
            lastAbilityHit.put(target.getUniqueId(), new AbilityKill(abilityName, source.getName(), System.currentTimeMillis()));
        }
        hurt(target, dmg, source);
    }

    private void heal(Player p, double amount) {
        p.setHealth(Math.min(maxHealth(p), p.getHealth() + amount));
    }

    /** True for a few seconds after this player damaged another player. */
    private boolean inCombat(Player p) {
        Long t = lastPvpHit.get(p.getUniqueId());
        long window = plugin.getConfig().getInt("combat-tag-seconds", 5) * 1000L;
        return t != null && System.currentTimeMillis() - t < window;
    }

    private boolean wearsGodArmor(Player p) {
        for (GodType t : new GodType[]{GodType.HELMET, GodType.CHESTPLATE, GodType.LEGGINGS, GodType.BOOTS}) {
            if (GodItems.getType(GodItems.wornPiece(p, t)) == t) return true;
        }
        return false;
    }

    /** Full-set bonus + prestige bonuses of the worn god armour, as a 0..0.75 damage reduction. */
    private double armorReduction(Player p) {
        double red = 0;
        int worn = 0;
        for (GodType t : new GodType[]{GodType.HELMET, GodType.CHESTPLATE, GodType.LEGGINGS, GodType.BOOTS}) {
            ItemStack it = GodItems.wornPiece(p, t);
            if (GodItems.getType(it) == t) {
                worn++;
                red += GodItems.getPrestige(it) * GodItems.ARMOR_PRESTIGE_BONUS;
            }
        }
        if (worn == 4) red += plugin.getConfig().getDouble("set-bonus-reduction", 0.20);
        red = Math.min(red, 0.75);
        // Ambrosia and god armour only stack a little: armour's own reduction is scaled down while Ambrosia is active.
        if (worn > 0 && GodItems.ambrosiaActive(p)) red *= plugin.getConfig().getDouble("ambrosia-armour-factor", 0.25);
        return red;
    }

    // =====================================================================================
    //  passive armour effects (once a second)
    // =====================================================================================

    public void startPassives() {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
                    passives(p, t);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // migrate older god weapons off the removed near-zero hit cooldown
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    PlayerInventory inv = p.getInventory();
                    for (int i = 0; i < inv.getSize(); i++) {
                        ItemStack it = inv.getItem(i);
                        if (it == null) continue;
                        boolean changed = GodItems.stripZeroCooldown(it);
                        changed |= GodItems.ensureTrim(it);
                        if (changed) inv.setItem(i, it);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 40L);

        // cooldown HUD + weapon trails
        new BukkitRunnable() {
            @Override
            public void run() {
                hud();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    /** Remove things we added to players (called on disable). */
    public void shutdown() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            setTitanHealth(p, false);
            Set<PotionEffectType> prev = granted.remove(p.getUniqueId());
            if (prev != null) prev.forEach(p::removePotionEffect);
        }
    }

    private void eff(Player p, Set<PotionEffectType> now, PotionEffectType type, int dur, int amp) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && cur.getAmplifier() > amp) return; // never downgrade something stronger
        now.add(type);
        p.addPotionEffect(new PotionEffect(type, dur, amp, true, false, false));
    }

    private void setTitanHealth(Player p, boolean on) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        AttributeModifier existing = null;
        for (AttributeModifier m : a.getModifiers()) {
            if (healthKey.equals(m.getKey())) {
                existing = m;
                break;
            }
        }
        if (on && existing == null) {
            a.addTransientModifier(new AttributeModifier(healthKey, 20.0, AttributeModifier.Operation.ADD_NUMBER));
        } else if (!on && existing != null) {
            a.removeModifier(existing);
        }
    }

    private void passives(Player p, int t) {
        Set<PotionEffectType> now = new HashSet<>();
        int helm = GodItems.armorTier(p, GodType.HELMET);
        int chest = GodItems.armorTier(p, GodType.CHESTPLATE);
        int legs = GodItems.armorTier(p, GodType.LEGGINGS);
        int boots = GodItems.armorTier(p, GodType.BOOTS);

        // ---- helmet
        if (helm >= 0) {
            eff(p, now, PotionEffectType.NIGHT_VISION, 320, 0);
            eff(p, now, PotionEffectType.WATER_BREATHING, 60, 0);
        }
        if (helm >= 1) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.DARKNESS);
            p.removePotionEffect(PotionEffectType.NAUSEA);
        }
        if (helm >= 2 && t % 2 == 0) {
            for (LivingEntity le : p.getLocation().getNearbyLivingEntities(24)) {
                if (valid(p, le)) le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, true, false, false));
            }
        }
        if (helm >= 3) {
            if (p.getFoodLevel() < 20) p.setFoodLevel(20);
            p.setSaturation(20f);
        }

        // ---- chestplate
        boolean ambrosia = GodItems.ambrosiaActive(p); // Ambrosia already gives far more health/resistance: don't double up
        setTitanHealth(p, chest >= 0 && !ambrosia);
        if (chest >= 0 && !ambrosia) eff(p, now, PotionEffectType.RESISTANCE, 60, 0);
        if (chest >= 3 && p.getFireTicks() > 0) p.setFireTicks(0);

        // ---- leggings
        if (legs >= 0) {
            int amp = GodItems.scaledAmplifier(GodItems.getPrestige(p.getInventory().getLeggings()));
            eff(p, now, PotionEffectType.SPEED, 60, amp);
        }

        // ---- boots (Titan Stride: Strength II as their offensive perk)
        if (boots >= 0) {
            int amp = GodItems.scaledAmplifier(GodItems.getPrestige(p.getInventory().getBoots()));
            eff(p, now, PotionEffectType.STRENGTH, 60, amp);
        }
        if (boots >= 1) {
            eff(p, now, PotionEffectType.FIRE_RESISTANCE, 60, 0);
            eff(p, now, PotionEffectType.DOLPHINS_GRACE, 60, 0);
        }

        // full set bonus notice
        boolean fullSet = helm >= 0 && chest >= 0 && legs >= 0 && boots >= 0;
        if (fullSet) { // Godform aura
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 4, 0.4, 0.8, 0.4, 0.01);
        }
        if (fullSet) {
            if (setActive.add(p.getUniqueId())) {
                p.sendMessage(GodItems.c("&6&l✦ &eGodform &7- full set bonus active: &a+"
                        + Math.round(plugin.getConfig().getDouble("set-bonus-reduction", 0.20) * 100) + "% damage reduction"));
                sound(p.getLocation(), "entity.player.levelup", 0.6f, 1.4f);
            }
        } else {
            setActive.remove(p.getUniqueId());
        }

        // God Protection aura (Convergence level 1-3): distinct colour per level, always visible
        int convLevel = GodItems.convergenceLevel(p);
        if (convLevel >= 1) {
            Color cCol = convLevel == 3 ? Color.fromRGB(120, 0, 200) : convLevel == 2 ? Color.fromRGB(60, 120, 255) : Color.WHITE;
            Location cl = p.getLocation().add(0, 0.1, 0);
            p.getWorld().spawnParticle(Particle.DUST, cl, 10 + convLevel * 4, 0.6, 0.15, 0.6, 0, new DustOptions(cCol, 1.0f));
            if (t % 15 == 0) p.getWorld().playSound(cl, "block.beacon.ambient", 0.35f, 1.5f + convLevel * 0.2f);
        }

        // prestige aura (prestige 3+): visible from a distance, distinct colour/sound per level
        int maxPrestige = GodItems.maxEquippedPrestige(p);
        if (maxPrestige >= 3) {
            Color col = maxPrestige >= 5 ? Color.fromRGB(255, 0, 255)
                    : maxPrestige == 4 ? Color.fromRGB(80, 220, 255)
                    : Color.fromRGB(255, 200, 0);
            Location al = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.DUST, al, 18, 0.5, 0.9, 0.5, 0, new DustOptions(col, 1.3f));
            if (maxPrestige >= 5) p.getWorld().spawnParticle(Particle.DRAGON_BREATH, al, 4, 0.4, 0.6, 0.4, 0.005);
            if (t % 10 == 0) {
                String snd = maxPrestige >= 5 ? "entity.wither.ambient"
                        : maxPrestige == 4 ? "block.beacon.ambient" : "entity.experience_orb.pickup";
                p.getWorld().playSound(al, snd, 0.5f, maxPrestige >= 5 ? 0.6f : 1.2f);
            }
        }

        // drop effects from armour that's no longer worn
        Set<PotionEffectType> prev = granted.put(p.getUniqueId(), now);
        if (prev != null) {
            for (PotionEffectType type : prev) {
                if (!now.contains(type)) p.removePotionEffect(type);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        String idStr = id.toString();
        granted.remove(id);
        lastAbilityHit.remove(id);
        lastPvpHit.remove(id);
        stunImmuneUntil.remove(id);
        setActive.remove(id);
        cooldowns.keySet().removeIf(k -> k.startsWith(idStr + ":"));
        killPairCooldown.keySet().removeIf(k -> k.contains(idStr));
    }

    // =====================================================================================
    //  defensive armour abilities
    // =====================================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        // God volley arrows never hurt their caster
        if (e instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Projectile proj
                && proj.getPersistentDataContainer().has(volleyKey, PersistentDataType.BYTE)
                && proj.getShooter() instanceof Player shooter
                && shooter.equals(e.getEntity())) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getEntity() instanceof Player p)) return;

        DamageCause cause = e.getCause();
        int chest = GodItems.armorTier(p, GodType.CHESTPLATE);
        int legs = GodItems.armorTier(p, GodType.LEGGINGS);
        int boots = GodItems.armorTier(p, GodType.BOOTS);

        // Chestplate 4: Unyielding (fire/lava/lightning only - explosions still hurt)
        if (chest >= 3 && (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.LAVA
                || cause == DamageCause.HOT_FLOOR || cause == DamageCause.LIGHTNING)) {
            e.setCancelled(true);
            p.setFireTicks(0);
            return;
        }

        // Boots 1 (Featherfall), Boots 4 (Shockwave Landing), God Mace held (Titan Smash)
        if (cause == DamageCause.FALL) {
            boolean holdingMace = GodItems.getType(p.getInventory().getItemInMainHand()) == GodType.MACE;
            if (boots >= 0 || holdingMace) {
                if (boots >= 3 && !SpawnGuard.inSpawn(p) && e.getDamage() >= 3 && ready(p, "shockwave_landing", 6, false)) landingShockwave(p, e.getDamage());
                e.setCancelled(true);
                return;
            }
        }

        // Leggings 4: Phase Shift
        if (legs >= 3 && !SpawnGuard.inSpawn(p) && e instanceof EntityDamageByEntityEvent pe && pe.getDamager() instanceof Projectile
                && ready(p, "phase_shift", 3, false) && rng.nextDouble() < 0.6) {
            e.setCancelled(true);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.05);
            sound(p.getLocation(), "entity.enderman.teleport", 0.6f, 1.6f);
            bar(p, "Phase Shift!", NamedTextColor.LIGHT_PURPLE);
            return;
        }

        // Full set bonus + prestige: flat damage reduction
        double reduction = armorReduction(p);
        if (reduction > 0) e.setDamage(e.getDamage() * (1 - reduction));

        // Chestplate 2: Mirror Aegis
        if (chest >= 1 && !busy && !SpawnGuard.inSpawn(p) && cause == DamageCause.ENTITY_ATTACK
                && e instanceof EntityDamageByEntityEvent me
                && me.getDamager() instanceof LivingEntity attacker && !attacker.equals(p)
                && ready(p, "mirror_aegis", 3, false)) {
            double reflected = e.getFinalDamage() * 0.3;
            if (reflected > 0.5) {
                reflecting = true;
                try {
                    hurt(attacker, reflected, p);
                } finally {
                    reflecting = false;
                }
                attacker.getWorld().spawnParticle(Particle.ENCHANTED_HIT, attacker.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.1);
            }
        }
    }

    /** Chestplate 3: Second Life (runs after everything else has modified the damage). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (GodItems.armorTier(p, GodType.CHESTPLATE) < 2) return;
        if (inCombat(p)) return; // Second Life is disabled right after you hit another player
        if (SpawnGuard.inSpawn(p)) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        if (!ready(p, "cheat_death", 900, false)) return;

        e.setCancelled(true);
        p.setHealth(Math.max(1.0, maxHealth(p) * 0.5));
        p.setNoDamageTicks(40);
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 4));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 3));
        for (LivingEntity le : p.getLocation().getNearbyLivingEntities(6)) {
            if (valid(p, le)) knock(le, p.getLocation(), 1.4, 0.6);
        }
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 80, 0.5, 0.9, 0.5, 0.4);
        sound(l, "item.totem.use", 1f, 1f);
        p.sendMessage(GodItems.c("&6&l✦ &eSecond Life &7saved you from death!"));
    }

    /** Leggings 2: Immovable. */
    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackByEntityEvent e) {
        if (e.getEntity() instanceof Player p && GodItems.armorTier(p, GodType.LEGGINGS) >= 1) {
            e.setCancelled(true);
        }
    }

    /** Leggings 3: Wraith Dash (sprint + sneak). */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!e.isSneaking() || !p.isSprinting()) return;
        if (GodItems.armorTier(p, GodType.LEGGINGS) < 2) return;
        if (SpawnGuard.inSpawn(p)) return;
        if (inCombat(p)) {
            bar(p, "Wraith Dash is disabled in combat", NamedTextColor.RED);
            return;
        }
        if (!ready(p, "wraith_dash", 4, true)) return;
        Vector v = p.getLocation().getDirection().multiply(2.2);
        v.setY(Math.max(0.25, v.getY() * 0.4));
        p.setVelocity(v);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.5, 0), 25, 0.3, 0.3, 0.3, 0.05);
        sound(p.getLocation(), "entity.ender_dragon.flap", 1f, 1.5f);
    }

    /** Boots 3: Groundswell - starting to sprint launches you forward with a burst of speed. */
    @EventHandler(ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent e) {
        if (!e.isSprinting()) return;
        Player p = e.getPlayer();
        if (GodItems.armorTier(p, GodType.BOOTS) < 2) return;
        if (SpawnGuard.inSpawn(p)) return;
        if (!ready(p, "groundswell", 8, false)) return;
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) return;
        p.setVelocity(p.getVelocity().add(dir.normalize().multiply(0.6)));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 2, true, true, true));
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 15, 0.3, 0.1, 0.3, 0.03);
        sound(p.getLocation(), "entity.horse.gallop", 0.7f, 1.3f);
    }

    private void landingShockwave(Player p, double fallDamage) {
        Location l = p.getLocation();
        double dmg = Math.min(fallDamage * 1.5, 40);
        for (LivingEntity le : l.getNearbyLivingEntities(6)) {
            if (!valid(p, le)) continue;
            abilityHurt(le, dmg, p, "Shockwave Landing");
            knock(le, l, 1.2, 0.7);
        }
        p.getWorld().spawnParticle(Particle.EXPLOSION, l, 3, 1.5, 0.1, 1.5, 0);
        p.getWorld().spawnParticle(Particle.CLOUD, l, 40, 2.5, 0.1, 2.5, 0.1);
        sound(l, "entity.generic.explode", 1f, 0.8f);
    }

    // =====================================================================================
    //  weapon on-hit abilities
    // =====================================================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (busy || e.getCause() == DamageCause.THORNS) return;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)) return;

        ItemStack weapon = p.getInventory().getItemInMainHand();
        GodType type = GodItems.getType(weapon);
        if (type == null || type.isArmor()) return;
        int tier = GodItems.tier(weapon);

        int prestige = GodItems.getPrestige(weapon);
        if (prestige > 0) e.setDamage(e.getDamage() * (1 + GodItems.WEAPON_PRESTIGE_BONUS * prestige));

        if (tier >= 3 && victim instanceof Player vp && wearsGodArmor(vp)) {
            switch (type) {
                case SWORD, MACE -> godslay(p, vp, e, "Godslayer", 1.75, 6);
                case AXE, SPEAR -> godslay(p, vp, e, "Godbreaker", 1.5, 4);
                default -> { }
            }
        }

        switch (type) {
            case SWORD -> {
                e.setDamage(e.getDamage() * 1.5);                            // Godly Edge
                if (tier >= 1) {                                              // Vampiric
                    heal(p, e.getFinalDamage() * 0.25);
                    p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 2, 0.3, 0.2, 0.3, 0);
                }
            }
            case AXE -> {
                e.setDamage(e.getDamage() * 1.25);                            // Cleave
                double splash = e.getFinalDamage() * 0.5;
                for (LivingEntity other : victim.getLocation().getNearbyLivingEntities(3)) {
                    if (!other.equals(victim) && valid(p, other)) hurt(other, splash, p);
                }
                p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 3, 1, 0.3, 1, 0);
                if (tier >= 1) {                                              // Sunder
                    e.setDamage(e.getDamage() + 4);
                    if (victim instanceof Player vp && vp.isBlocking()) {
                        vp.setCooldown(Material.SHIELD, 100);
                        vp.clearActiveItem();
                    }
                    if (!victim.hasPotionEffect(PotionEffectType.WITHER)) { // no stacking / refreshing
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 200, 0)); // Wither: 10s
                    }
                }
            }
            case MACE -> {
                if (p.getFallDistance() > 1.5f) {                             // Titan Smash
                    e.setDamage(e.getDamage() * 1.75);
                    if (victim instanceof Player) {
                        lastAbilityHit.put(victim.getUniqueId(), new AbilityKill("Titan Smash", p.getName(), System.currentTimeMillis()));
                    }
                    Location l = victim.getLocation();
                    for (LivingEntity other : l.getNearbyLivingEntities(4)) {
                        if (!other.equals(victim) && valid(p, other)) {
                            hurt(other, 6, p);
                            knock(other, l, 0.8, 0.6);
                        }
                    }
                    p.getWorld().spawnParticle(Particle.EXPLOSION, l, 2, 1, 0.1, 1, 0);
                    sound(l, "entity.generic.explode", 0.8f, 1.2f);
                }
                if (tier >= 1) {                                              // Stunning Blow
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));  // Slowness III: 3s on every hit
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 1));
                    long nowMs = System.currentTimeMillis();
                    Long immune = stunImmuneUntil.get(victim.getUniqueId());
                    long stunCd = plugin.getConfig().getInt("cooldowns.mace_stun", 10) * 1000L; // per-target stun cooldown
                    if ((immune == null || immune <= nowMs) && rng.nextDouble() < 0.3) { // 30% roll, then on cooldown
                        stunImmuneUntil.put(victim.getUniqueId(), nowMs + stunCd);
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 300, 2)); // Slowness III: 15s
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 9)); // Slowness X: 5s
                        victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1.5, 0), 25, 0.3, 0.3, 0.3, 0.2);
                        sound(victim.getLocation(), "entity.warden.sonic_boom", 0.5f, 2f);
                    }
                }
            }
            case SPEAR -> {
                e.setDamage(e.getDamage() * 1.4);                             // Godly Point
                if (tier >= 1 && victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2) { // Armor Piercing
                    double h = victim.getHealth();
                    if (h > 4) victim.setHealth(h - 4);
                    victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 10, 0.2, 0.4, 0.2, 0.1);
                }
            }
            default -> { }
        }
        hitFx(type, victim);
    }

    // =====================================================================================
    //  active abilities: [F] = ability 3, [Shift+F] = ability 4
    // =====================================================================================

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        ItemStack weapon = p.getInventory().getItemInMainHand();
        GodType type = GodItems.getType(weapon);
        if (type == null || type.isArmor()) return;
        e.setCancelled(true); // the F key is our ability key while holding a god weapon

        if (SpawnGuard.inSpawn(p)) {
            bar(p, "Abilities are disabled here", NamedTextColor.RED);
            return;
        }

        boolean second = p.isSneaking();
        int needed = second ? 3 : 2;
        if (GodItems.tier(weapon) < needed) {
            bar(p, "🔒 Locked - needs " + (needed * GodItems.KILLS_PER_TIER) + " kills on this weapon", NamedTextColor.RED);
            return;
        }
        switch (type) {
            case SWORD -> { if (second) smitingStorm(p); else wrathWave(p); }
            case AXE -> { if (second) earthsplitter(p); else berserkerRage(p); }
            case MACE -> { if (second) gravityWell(p); else windRider(p); }
            case SPEAR -> { if (second) skyfallVolley(p); else piercingCharge(p); }
            default -> { }
        }
    }

    private void wrathWave(Player p) {
        if (!ready(p, "sword_wave", 6, true)) return;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        Set<UUID> hit = new HashSet<>();
        for (double d = 1; d <= 24; d += 0.75) {
            Location loc = eye.clone().add(dir.clone().multiply(d));
            if (loc.getBlock().getType().isSolid()) break;
            w.spawnParticle(Particle.SWEEP_ATTACK, loc, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.ELECTRIC_SPARK, loc, 6, 0.3, 0.3, 0.3, 0.02);
            for (LivingEntity le : loc.getNearbyLivingEntities(1.5)) {
                if (valid(p, le) && hit.add(le.getUniqueId())) abilityHurt(le, 14, p, "Wrath Wave");
            }
        }
        sound(p.getLocation(), "entity.warden.sonic_boom", 0.7f, 1.6f);
    }

    private void smitingStorm(Player p) {
        Location pl = p.getLocation();
        List<LivingEntity> targets = pl.getNearbyLivingEntities(14).stream()
                .filter(le -> valid(p, le))
                .sorted(Comparator.comparingDouble((LivingEntity le) -> le.getLocation().distanceSquared(pl)))
                .limit(6)
                .toList();
        if (targets.isEmpty()) {
            bar(p, "No targets nearby", NamedTextColor.GRAY);
            return;
        }
        if (!ready(p, "sword_storm", 25, true)) return;
        for (LivingEntity le : targets) {
            le.getWorld().strikeLightningEffect(le.getLocation());
            abilityHurt(le, 12, p, "Smiting Storm");
            le.setFireTicks(60);
        }
        sound(pl, "entity.lightning_bolt.thunder", 1f, 1f);
    }

    private void berserkerRage(Player p) {
        if (!ready(p, "axe_rage", 30, true)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 1));
        Location l = p.getLocation();
        for (LivingEntity le : l.getNearbyLivingEntities(8)) {
            if (!valid(p, le)) continue;
            knock(le, l, 1.0, 0.4);
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 1));
        }
        p.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, l.clone().add(0, 2, 0), 12, 0.6, 0.4, 0.6, 0);
        sound(l, "entity.ravager.roar", 1f, 0.8f);
    }

    private void earthsplitter(Player p) {
        if (!ready(p, "axe_slam", 14, true)) return;
        Location l = p.getLocation();
        for (LivingEntity le : l.getNearbyLivingEntities(7)) {
            if (!valid(p, le)) continue;
            abilityHurt(le, 14, p, "Earthsplitter");
            knock(le, l, 1.0, 0.9);
        }
        World w = p.getWorld();
        w.spawnParticle(Particle.BLOCK, l, 100, 3.5, 0.2, 3.5, Material.DIRT.createBlockData());
        w.spawnParticle(Particle.EXPLOSION, l, 4, 2.5, 0.1, 2.5, 0);
        sound(l, "entity.generic.explode", 1f, 0.6f);
    }

    private void windRider(Player p) {
        if (!ready(p, "mace_wind", 5, true)) return;
        Vector d = p.getLocation().getDirection();
        p.setVelocity(new Vector(d.getX() * 0.5, 1.8, d.getZ() * 0.5));
        p.setFallDistance(0f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 30, 0.4, 0.1, 0.4, 0.1);
        sound(p.getLocation(), "entity.wind_charge.wind_burst", 1f, 1f);
    }

    private void gravityWell(Player p) {
        Location l = p.getLocation();
        List<LivingEntity> targets = l.getNearbyLivingEntities(12).stream().filter(le -> valid(p, le)).toList();
        if (targets.isEmpty()) {
            bar(p, "No targets nearby", NamedTextColor.GRAY);
            return;
        }
        if (!ready(p, "mace_well", 18, true)) return;
        for (LivingEntity le : targets) {
            Vector pull = l.toVector().subtract(le.getLocation().toVector());
            if (pull.lengthSquared() > 0.01 && !(le instanceof Player lp && GodItems.armorTier(lp, GodType.LEGGINGS) >= 1)) {
                le.setVelocity(pull.normalize().multiply(1.6).setY(0.5));
            }
            abilityHurt(le, 6, p, "Gravity Well");
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        }
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, l.clone().add(0, 1, 0), 120, 5, 1, 5, 0.3);
        sound(l, "entity.enderman.teleport", 1f, 0.5f);
    }

    private void piercingCharge(Player p) {
        if (!ready(p, "spear_charge", 6, true)) return;
        Vector d = p.getLocation().getDirection();
        Vector v = new Vector(d.getX(), 0, d.getZ());
        if (v.lengthSquared() < 1.0E-4) v = new Vector(0, 0, 1);
        p.setVelocity(v.normalize().multiply(2.4).setY(0.15));
        sound(p.getLocation(), "entity.ender_dragon.flap", 1f, 1.8f);

        new BukkitRunnable() {
            int ticks = 0;
            final Set<UUID> hit = new HashSet<>();

            @Override
            public void run() {
                if (!p.isOnline() || ticks++ >= 10) {
                    cancel();
                    return;
                }
                Location l = p.getLocation().add(0, 1, 0);
                p.getWorld().spawnParticle(Particle.END_ROD, l, 6, 0.3, 0.3, 0.3, 0.02);
                for (LivingEntity le : l.getNearbyLivingEntities(2.2)) {
                    if (valid(p, le) && hit.add(le.getUniqueId())) {
                        abilityHurt(le, 12, p, "Piercing Charge");
                        knock(le, p.getLocation(), 0.5, 0.4);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void skyfallVolley(Player p) {
        Block b = p.getTargetBlockExact(40);
        Location target = b != null
                ? b.getLocation().add(0.5, 1, 0.5)
                : p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(25));
        if (!ready(p, "spear_volley", 20, true)) return;
        World w = target.getWorld();
        sound(p.getLocation(), "entity.arrow.shoot", 1f, 0.5f);

        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n++ >= 20 || !p.isOnline()) {
                    cancel();
                    return;
                }
                w.spawnParticle(Particle.END_ROD, target, 10, 3, 0.2, 3, 0.01);
                for (int i = 0; i < 2; i++) {
                    double rx = (rng.nextDouble() - 0.5) * 8, rz = (rng.nextDouble() - 0.5) * 8;
                    Location s = target.clone().add(rx, 16, rz);
                    if (!s.getBlock().isPassable()) s = target.clone().add(rx, 3, rz);
                    Arrow a = w.spawnArrow(s, new Vector(0, -1, 0), 3.0f, 0f);
                    a.setShooter(p);
                    a.setDamage(8);
                    a.setCritical(true);
                    a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    a.getPersistentDataContainer().set(volleyKey, PersistentDataType.BYTE, (byte) 1);
                    plugin.getServer().getScheduler().runTaskLater(plugin, a::remove, 100L);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // =====================================================================================
    //  PvP tag, hit effects, cooldown HUD and trails
    // =====================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpTag(EntityDamageByEntityEvent e) {
        if (reflecting || !(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player a ? a
                : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null);
        if (attacker == null || attacker.equals(victim)) return;
        lastPvpHit.put(attacker.getUniqueId(), System.currentTimeMillis());
    }

    /** Godslayer: the sword/mace's answer to god armour - bonus damage plus true damage. */
    private void godslay(Player attacker, Player victim, EntityDamageByEntityEvent e, String name, double mult, double trueDmg) {
        lastAbilityHit.put(victim.getUniqueId(), new AbilityKill(name, attacker.getName(), System.currentTimeMillis()));
        e.setDamage(e.getDamage() * mult);
        if (victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2 && victim.getHealth() > trueDmg) {
            victim.setHealth(victim.getHealth() - trueDmg); // true damage, ignores every armour bonus
        }
        Location l = victim.getLocation().add(0, 1, 0);
        victim.getWorld().spawnParticle(Particle.SONIC_BOOM, l, 1);
        victim.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l, 25, 0.4, 0.6, 0.4, 0.05);
        sound(l, "entity.warden.sonic_boom", 0.8f, 1.3f);
        bar(attacker, "☠ " + name + "!", NamedTextColor.GOLD);
    }

    private void hitFx(GodType type, LivingEntity victim) {
        Location l = victim.getLocation().add(0, 1, 0);
        World w = l.getWorld();
        switch (type) {
            case SWORD -> {
                w.spawnParticle(Particle.ELECTRIC_SPARK, l, 12, 0.3, 0.4, 0.3, 0.1);
                sound(l, "entity.player.attack.sweep", 0.7f, 1.4f);
            }
            case AXE -> {
                w.spawnParticle(Particle.FLAME, l, 10, 0.3, 0.4, 0.3, 0.03);
                sound(l, "item.shield.break", 0.5f, 1.5f);
            }
            case MACE -> {
                w.spawnParticle(Particle.CLOUD, l, 10, 0.3, 0.3, 0.3, 0.08);
                sound(l, "item.mace.smash_air", 0.7f, 1f);
            }
            case SPEAR -> {
                w.spawnParticle(Particle.END_ROD, l, 10, 0.2, 0.4, 0.2, 0.05);
                sound(l, "item.trident.hit", 0.8f, 1.3f);
            }
            default -> { }
        }
    }

    private String[] abilityIds(GodType t) {
        return switch (t) {
            case SWORD -> new String[]{"sword_wave", "sword_storm"};
            case AXE -> new String[]{"axe_rage", "axe_slam"};
            case MACE -> new String[]{"mace_wind", "mace_well"};
            case SPEAR -> new String[]{"spear_charge", "spear_volley"};
            default -> new String[]{"", ""};
        };
    }

    private String hudPart(String key, String name, String id, boolean unlocked, Player p, long now) {
        if (!unlocked) return "&8✘ " + name;
        Long until = cooldowns.get(p.getUniqueId() + ":" + id);
        if (until != null && until > now) {
            return "&e[" + key + "] &7" + name + " &c" + ((until - now + 999) / 1000) + "s";
        }
        return "&e[" + key + "] &f" + name + " &a✔";
    }

    /** Action-bar cooldown timers + a small particle trail for whoever is holding a god weapon. */
    private void hud() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.isDead()) continue;
            ItemStack w = p.getInventory().getItemInMainHand();
            GodType t = GodItems.getType(w);
            if (t == null || t.isArmor()) continue;
            int tier = GodItems.tier(w);
            String[] ids = abilityIds(t);
            String line = hudPart(GodItems.keyLabel(), t.ability(2)[0], ids[0], tier >= 2, p, now)
                    + "   " + hudPart("⇧" + GodItems.keyLabel(), t.ability(3)[0], ids[1], tier >= 3, p, now);
            p.sendActionBar(GodItems.c(line));

            Particle trail = switch (t) {
                case SWORD -> Particle.ELECTRIC_SPARK;
                case AXE -> Particle.FLAME;
                case MACE -> Particle.CLOUD;
                case SPEAR -> Particle.END_ROD;
                default -> null;
            };
            if (trail != null) {
                Location eye = p.getEyeLocation();
                Location hand = eye.clone().add(eye.getDirection().multiply(0.6)).subtract(0, 0.5, 0);
                p.getWorld().spawnParticle(trail, hand, 1 + tier, 0.1, 0.1, 0.1, 0.01);
            }
        }
    }

    // =====================================================================================
    //  kill counting -> upgrades
    // =====================================================================================

    /** Custom kill-feed line when the killing blow came from a named god ability, within the last few seconds. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeathMessage(PlayerDeathEvent e) {
        AbilityKill k = lastAbilityHit.remove(e.getEntity().getUniqueId());
        if (k == null || System.currentTimeMillis() - k.time() > 3000) return;
        e.deathMessage(GodItems.c("&7" + e.getEntity().getName() + " &7was struck down by &6" + k.killerName()
                + "&7's &e" + k.ability() + "&7!"));
    }

    /** Skyfall Volley arrows deal plain vanilla arrow damage, so tag the ability separately here. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityProjectileHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Projectile proj && proj.getPersistentDataContainer().has(volleyKey, PersistentDataType.BYTE)
                && proj.getShooter() instanceof Player shooter && e.getEntity() instanceof Player victim
                && !shooter.equals(victim)) {
            lastAbilityHit.put(victim.getUniqueId(), new AbilityKill("Skyfall Volley", shooter.getName(), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) return;

        if (!(dead instanceof Player)) return; // only player kills count towards upgrades
        Player victim = (Player) dead;

        // anti-farm: the same killer+victim pair only counts once per cooldown window
        String pairKey = killer.getUniqueId() + ":" + victim.getUniqueId();
        long cdMs = plugin.getConfig().getInt("kill-farm-cooldown-seconds", 120) * 1000L;
        long nowMs = System.currentTimeMillis();
        Long lastCounted = killPairCooldown.get(pairKey);
        if (lastCounted != null && nowMs - lastCounted < cdMs) {
            killer.sendMessage(GodItems.c("&7(That kill is on farm-cooldown - it won't count towards your god gear)"));
            return;
        }
        killPairCooldown.put(pairKey, nowMs);

        PlayerInventory inv = killer.getInventory();

        ItemStack main = inv.getItemInMainHand();
        GodType mt = GodItems.getType(main);
        if (mt != null && !mt.isArmor()) {
            boolean up = GodItems.addKill(main);
            inv.setItemInMainHand(main);
            if (up) announce(killer, main);
        }

        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            GodType at = GodItems.getType(armor[i]);
            if (at != null && at.isArmor()) {
                boolean up = GodItems.addKill(armor[i]);
                changed = true;
                if (up) announce(killer, armor[i]);
            }
        }
        if (changed) inv.setArmorContents(armor);
    }

    private String unlockedNames(GodType t, int tier) {
        if (tier >= 3 && t.abilityCount() > 4) return t.ability(3)[0] + " &7& &c" + t.ability(4)[0];
        return t.ability(Math.min(tier, 3))[0];
    }

    private void announce(Player p, ItemStack item) {
        GodType t = GodItems.getType(item);
        if (t == null) return;
        int tier = GodItems.tier(item);
        p.sendMessage(GodItems.c("&6&l✦ &eYour &6" + t.display() + " &eascended to &b" + GodItems.tierName(tier)
                + "&e! Unlocked: &a" + unlockedNames(t, tier)));
        p.playSound(p.getLocation(), "ui.toast.challenge_complete", 1f, 1f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.3);
    }
}
