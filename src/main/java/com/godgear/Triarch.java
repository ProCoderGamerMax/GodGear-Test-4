package com.godgear;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.ShulkerBox;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Triarch Singularity: the guardian behind the Convergence system.
 *
 * WHAT IT ACTUALLY IS: a Wither with its health/damage/knockback-resistance overridden via the
 * Attribute API, a boss bar, a permanent name, and a "brain" (a repeating task) layered on top.
 * It keeps its normal Wither AI (it flies, melees and shoots wither skulls at players on its own),
 * and every attack-interval it ALSO fires one scripted ability - stage 3 fires all six every
 * interval - so this is not a passive mob with one occasional pulse: it is actively, continuously
 * attacking whoever is near it, in six different ways, for as long as the fight lasts.
 *
 * LOOK: Java resource packs can only retexture a mob's existing model, not fuse three different
 * mobs' skeletons into a new one (that needs a modelling plugin, e.g. ModelEngine, which this
 * project does not depend on). The Wither's three-headed flying silhouette is the closest vanilla
 * shape to a "fusion boss", and its constant particles/sounds are deliberately layered from all
 * three requested mobs: Warden (sonic boom pulses, sculk-dark ambience, deep growls), Wither (its
 * own native particles/skulls), and Ender Dragon (dragon-breath clouds, a screech on big attacks).
 *
 * SIX ABILITIES (two per realm), one fired per attack-interval at stage 1/2 (cycling in order),
 * all six fired every interval at stage 3:
 *   Overworld: Root Snare (poison/slow pulse on a target), Toxic Spores (a lingering poison cloud)
 *   Nether:    Inferno Wave (a fire ray at a target), Magma Burst (telegraphed eruption at their feet)
 *   End:       Void Pull (drags a target in, levitates them), Starfall (telegraphed sky-strikes on 1-3 players)
 *
 * A player may safely hold as many distinct god items as their Convergence level allows (0 -> 0,
 * level 1 -> 1, level 2 -> 4, level 3 -> 8 = all of them). Carrying more is fatal over time: a
 * "Divine Overload" bypasses armour every 5 seconds AND spawns this boss bound to them at the stage
 * matching the level they're missing. Defeating it drops a Convergence item (must be right-clicked)
 * plus a vanilla loot bundle that gets dramatically better each stage, guaranteeing an Unbreaking
 * 255 Elytra and three Shulker Boxes of max-power rockets at stage 3.
 *
 * Difficulty: stage 1 hard, stage 2 very hard, stage 3 (Awakened) extremely hard - tune config.yml.
 * Limitation: the scripted brain does not survive a restart mid-fight; on startup it reattaches to
 * any tagged Wither still alive in a loaded chunk (binding to a specific player is not restored, so
 * a reattached boss rewards whoever lands the final blow instead). `/god boss remove [player]`
 * removes tracked and orphaned Triarch entities cleanly - see that command if one gets stuck.
 */
public class Triarch implements Listener {

    private final GodGear plugin;
    private final Random rng = new Random();

    private final NamespacedKey stageKey;
    private final NamespacedKey boundKey;   // persists which player a boss is bound to (survives restarts)
    private final NamespacedKey lockedKey;  // persists the "overload is now locked on" state

    private static class BossState {
        int stage;
        UUID boundPlayer; // nullable - who this fight is "for"; reward falls back to the killer if null
        int ability = 0;  // 0-5, which of the six abilities fires next (stage 1/2 only)
        int seconds = 0;
        BossBar bar;
        BukkitTask task;
        final List<UUID> minions = new ArrayList<>();
        long lastHit = System.currentTimeMillis(); // last time the boss damaged its bound player
        boolean locked = false; // Divine Overload locked on until the boss dies or the player dies
        final Set<Integer> warned = new HashSet<>(); // countdown warnings already sent (seconds-left marks)
        boolean fledWarned = false;
    }

    private record Hit(UUID boss, long time) { }
    /** victimId -> the last Triarch hit (or lethal overload tick) on them, used to detect "killed by the Triarch". */
    private final Map<UUID, Hit> lastTriarchHit = new ConcurrentHashMap<>();

    private final Map<UUID, BossState> active = new ConcurrentHashMap<>();       // bossId -> state
    private final Map<UUID, UUID> activeForPlayer = new ConcurrentHashMap<>();   // playerId -> bossId (auto-triggered only)
    private final java.util.Set<UUID> pendingSpawn = ConcurrentHashMap.newKeySet();

    private static final String[] ABILITY_NAMES = {"Root Snare", "Toxic Spores", "Inferno Wave", "Magma Burst", "Void Pull", "Starfall"};

    public Triarch(GodGear plugin) {
        this.plugin = plugin;
        this.stageKey = new NamespacedKey(plugin, "triarch_stage");
        this.boundKey = new NamespacedKey(plugin, "triarch_bound");
        this.lockedKey = new NamespacedKey(plugin, "triarch_locked");
        Bukkit.getScheduler().runTaskLater(plugin, this::reattachExisting, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::monitorOvercapacity, 100L, 100L); // every 5s
    }

    private void reattachExisting() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity ent : w.getEntities()) {
                if (ent instanceof Wither r
                        && r.getPersistentDataContainer().has(stageKey, PersistentDataType.INTEGER)
                        && !active.containsKey(r.getUniqueId())) {
                    int stage = r.getPersistentDataContainer().get(stageKey, PersistentDataType.INTEGER);
                    BossState st = new BossState();
                    st.stage = stage;
                    String boundStr = r.getPersistentDataContainer().get(boundKey, PersistentDataType.STRING);
                    if (boundStr != null) {
                        try {
                            st.boundPlayer = UUID.fromString(boundStr);
                            activeForPlayer.put(st.boundPlayer, r.getUniqueId());
                        } catch (IllegalArgumentException ignored) { }
                    }
                    st.locked = r.getPersistentDataContainer().has(lockedKey, PersistentDataType.BYTE);
                    bindBar(r, st);
                    active.put(r.getUniqueId(), st);
                    st.task = brain(r, st);
                }
            }
        }
    }

    public void shutdown() {
        for (BossState st : active.values()) {
            if (st.task != null) st.task.cancel();
            if (st.bar != null) st.bar.removeAll();
        }
        active.clear();
        activeForPlayer.clear();
    }

    // ============================================================================================
    //  the Convergence overcapacity check - runs every 5 seconds for every online, non-admin player
    // ============================================================================================

    private void monitorOvercapacity() {
        long now = System.currentTimeMillis();
        long idleMs = plugin.getConfig().getLong("triarch.idle-lock-seconds", 180) * 1000L;
        double flee = plugin.getConfig().getDouble("triarch.flee-distance", 64);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("godgear.admin") || p.isDead()) continue;
            UUID bossId = activeForPlayer.get(p.getUniqueId());
            BossState st = bossId != null ? active.get(bossId) : null;

            // A LOCKED overload ignores what the player carries: it only ends when the Triarch dies or the player does.
            boolean lockedOn = st != null && st.locked;
            int distinct = GodItems.distinctGodTypes(p).size();
            int cap = GodItems.carryLimit(p);
            if (distinct <= cap && !lockedOn) continue;

            int stage = GodItems.convergenceLevel(p) + 1;
            if (stage > 3 && !lockedOn) continue; // shouldn't happen - level 3's cap (8) is the max possible distinct count

            if (st == null) {
                // No guardian yet: summon one. No overload during the 3s build-up or while it is queued.
                ensureGuardian(p, stage);
                continue;
            }

            if (!st.locked) {
                Entity boss = Bukkit.getEntity(bossId);
                boolean fled = boss == null
                        || !boss.getWorld().equals(p.getWorld())
                        || boss.getLocation().distanceSquared(p.getLocation()) > flee * flee;

                if (fled) {
                    if (!st.fledWarned) {
                        st.fledWarned = true;
                        p.sendMessage(GodItems.c("&4&l⚠ &cYou fled your guardian - &4Divine Overload&c is eating you. "
                                + "Get within &e" + (int) flee + " blocks&c of it to pause the damage."));
                    }
                    // fall through and apply overload
                } else {
                    st.fledWarned = false;
                    long remaining = idleMs - (now - st.lastHit);
                    if (remaining > 0) {
                        warnCountdown(p, st, remaining);
                        continue; // actively fighting its guardian: no overload
                    }
                    // The Triarch hasn't touched them for the whole window: overload locks on.
                    st.locked = true;
                    if (boss != null) boss.getPersistentDataContainer().set(lockedKey, PersistentDataType.BYTE, (byte) 1);
                    p.sendMessage(GodItems.c("&4&l⚠ &cYou avoided the guardian for too long. Divine Overload is now &4LOCKED ON&c - "
                            + "it ends only when the Triarch Singularity falls, or you die."));
                    p.playSound(p.getLocation(), "entity.wither.spawn", 0.7f, 0.5f);
                }
            }
            overload(p, st.stage, bossId);
        }
    }

    /** Chat countdown before the idle lock: sent once per mark (120s / 60s / 30s / 15s left). */
    private void warnCountdown(Player p, BossState st, long remainingMs) {
        int secs = (int) Math.ceil(remainingMs / 1000.0);
        int fire = -1;
        for (int mark : new int[]{120, 60, 30, 15}) { // descending: ends on the smallest newly-crossed mark
            if (secs <= mark && st.warned.add(mark)) fire = mark;
        }
        if (fire < 0) return;
        p.sendMessage(GodItems.c("&e&l⚠ &6The Triarch hasn't struck you in a while. In about &c" + secs
                + "s &6Divine Overload will &4lock on&6 until it dies or you do. &7Engage it to reset the timer."));
        p.playSound(p.getLocation(), "block.note_block.bell", 0.8f, secs <= 30 ? 0.6f : 1.0f);
    }

    private void ensureGuardian(Player p, int stage) {
        UUID existing = activeForPlayer.get(p.getUniqueId());
        if (existing != null && active.containsKey(existing)) return; // already fighting their guardian
        if (!pendingSpawn.add(p.getUniqueId())) return; // a spawn is already queued (buildup delay in progress)
        announceAndSummon(p, stage, true);
    }

    private void overload(Player p, int stage, UUID bossId) {
        double dmg = switch (stage) {
            case 1 -> 2.0;
            case 2 -> 4.0;
            default -> 7.0;
        };
        double newHealth = Math.max(0, p.getHealth() - dmg);
        if (newHealth <= 0) lastTriarchHit.put(p.getUniqueId(), new Hit(bossId, System.currentTimeMillis())); // counts as a Triarch kill
        p.setHealth(newHealth); // bypasses armour/resistance entirely - this is meant to be unavoidable
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.SOUL, l, 20, 0.4, 0.6, 0.4, 0.05);
        p.playSound(l, "entity.wither.hurt", 0.6f, 0.6f);
        p.sendActionBar(GodItems.c("&4&l⚠ &cDivine Overload &7- carrying more than Convergence "
                + GodItems.convergenceLevel(p) + " allows. Fight the guardian, or drop the excess."));
    }

    // ============================================================================================
    //  spawn
    // ============================================================================================

    private void announceAndSummon(Player p, int stage, boolean bind) {
        Bukkit.broadcast(GodItems.c("&5&l⚠ &dReality strains near &f" + p.getName() + "&d... something is coming.&5&l ⚠"));
        p.getWorld().playSound(p.getLocation(), "entity.wither.spawn", 1f, stage == 1 ? 1.3f : stage == 2 ? 0.9f : 0.6f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(p, stage, bind), 60L); // 3s buildup
    }

    /** Admin/testing entry point - does not gate on or interfere with the automatic overcapacity system. */
    public void forceSpawn(Player near, int stage) {
        spawn(near, stage, false);
    }

    private Location findGround(Location origin) {
        Location l = origin.clone().add((rng.nextDouble() - 0.5) * 6, 0, (rng.nextDouble() - 0.5) * 6);
        World w = l.getWorld();
        l.setY(w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1);
        return l;
    }

    private void spawn(Player p, int stage, boolean bind) {
        pendingSpawn.remove(p.getUniqueId());
        Location loc = findGround(p.getLocation());
        Wither r = loc.getWorld().spawn(loc, Wither.class);
        configure(r, stage);

        BossState st = new BossState();
        st.stage = stage;
        if (bind) {
            st.boundPlayer = p.getUniqueId();
            r.getPersistentDataContainer().set(boundKey, PersistentDataType.STRING, p.getUniqueId().toString());
        }
        bindBar(r, st);
        active.put(r.getUniqueId(), st);
        if (bind) activeForPlayer.put(p.getUniqueId(), r.getUniqueId());
        st.task = brain(r, st);

        loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2);
        loc.getWorld().playSound(loc, "entity.ender_dragon.growl", 1.5f, stage == 1 ? 1f : stage == 2 ? 0.8f : 0.6f);
        Bukkit.broadcast(GodItems.c("&5&l☠ &dThe Triarch Singularity" + stageSuffix(stage) + " &dhas emerged!&5&l ☠"));
    }

    private String stageSuffix(int stage) {
        return switch (stage) {
            case 2 -> " &c(Resurgent)";
            case 3 -> " &4(Awakened)";
            default -> "";
        };
    }

    private void configure(Wither r, int stage) {
        double health = plugin.getConfig().getDouble("triarch.stage" + stage + ".health", switch (stage) {
            case 2 -> 900;
            case 3 -> 1000;
            default -> 350;
        });
        double dmg = plugin.getConfig().getDouble("triarch.stage" + stage + ".damage", switch (stage) {
            case 2 -> 16;
            case 3 -> 22;
            default -> 10;
        });

        r.customName(GodItems.c("&5&lThe Triarch Singularity" + stageSuffix(stage)));
        r.setCustomNameVisible(true);
        r.setPersistent(true);
        r.setRemoveWhenFarAway(false);
        r.setInvulnerabilityTicks(0); // skip the vanilla "shielded on spawn" animation - we already telegraph the spawn ourselves

        AttributeInstance hp = r.getAttribute(Attribute.MAX_HEALTH);
        double applied = health;
        if (hp != null) {
            try {
                hp.setBaseValue(health);
            } catch (IllegalArgumentException ex) { // above the server's max-health ceiling
                hp.setBaseValue(1024);
            }
            applied = hp.getValue();
        }
        r.setHealth(Math.min(health, applied));
        AttributeInstance dm = r.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dm != null) dm.setBaseValue(dmg);
        AttributeInstance kb = r.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(stage == 3 ? 1.0 : 0.8);

        r.getPersistentDataContainer().set(stageKey, PersistentDataType.INTEGER, stage);
    }

    private void bindBar(Wither r, BossState st) {
        BarColor color = st.stage == 3 ? BarColor.RED : st.stage == 2 ? BarColor.PURPLE : BarColor.GREEN;
        BossBar bar = Bukkit.createBossBar(barTitle(st), color, BarStyle.SEGMENTED_10);
        bar.setProgress(1.0);
        for (Player near : r.getWorld().getPlayers()) {
            if (near.getLocation().distanceSquared(r.getLocation()) < 96 * 96) bar.addPlayer(near);
        }
        st.bar = bar;
    }

    // ============================================================================================
    //  the brain: continuous Warden/Wither/Dragon ambience + one ability per attack-interval
    // ============================================================================================

    private BukkitTask brain(Wither r, BossState st) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (!r.isValid() || r.isDead()) {
                    cleanup(r.getUniqueId());
                    cancel();
                    return;
                }
                st.seconds++;
                int intervalSeconds = Math.max(3, plugin.getConfig().getInt(
                        "triarch.stage" + st.stage + ".attack-interval-seconds", st.stage == 3 ? 4 : st.stage == 2 ? 5 : 7));

                // keep the boss bar's viewer list current instead of only ever growing
                List<Player> near = new ArrayList<>();
                for (Player pl : r.getWorld().getPlayers()) {
                    if (pl.getLocation().distanceSquared(r.getLocation()) < 96 * 96) near.add(pl);
                }
                for (Player viewer : new ArrayList<>(st.bar.getPlayers())) {
                    if (!near.contains(viewer)) st.bar.removePlayer(viewer);
                }
                for (Player pl : near) st.bar.addPlayer(pl);

                AttributeInstance maxHp = r.getAttribute(Attribute.MAX_HEALTH);
                double max = maxHp != null ? maxHp.getValue() : 1;
                st.bar.setProgress(Math.max(0, Math.min(1, r.getHealth() / max)));

                ambience(r);

                // Awakened: out-heals anything players can deal (see onBossDamageCap)
                if (st.stage == 3 && plugin.getConfig().getBoolean("triarch.stage3.unbeatable", false)) {
                    double regen = plugin.getConfig().getDouble("triarch.stage3.regen-per-second", 40);
                    r.setHealth(Math.min(max, r.getHealth() + regen));
                }

                if (r.getLocation().getY() < -48) r.teleport(findGround(r.getLocation())); // void safety net

                if (st.seconds % intervalSeconds == 0) {
                    fireAbilities(r, st);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private String barTitle(BossState st) {
        String name = "&5&lThe Triarch Singularity" + stageSuffix(st.stage);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .serialize(GodItems.c(name));
    }

    /** Constant layered theming: Warden (sonic/sculk-dark), Wither (native), Ender Dragon (breath/screech). */
    private void ambience(Wither r) {
        Location l = r.getLocation().add(0, 1, 0);
        World w = r.getWorld();
        w.spawnParticle(Particle.SCULK_SOUL, l, 4, 0.6, 0.6, 0.6, 0.01);       // Warden
        w.spawnParticle(Particle.DRAGON_BREATH, l, 3, 0.7, 0.5, 0.7, 0.005);   // Ender Dragon
        w.spawnParticle(Particle.SMOKE, l, 6, 0.6, 0.6, 0.6, 0.01);            // Wither decay
        if (rng.nextInt(4) == 0) w.playSound(l, "entity.warden.ambient", 0.5f, 0.7f);
    }

    // ============================================================================================
    //  targeting
    // ============================================================================================

    private List<Player> nearby(Location l, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getLocation().distanceSquared(l) <= radius * radius) out.add(p);
        }
        return out;
    }

    /** A random valid player within a generous range, so the boss keeps attacking even if people spread out. */
    private Player pickTarget(Location l) {
        List<Player> pool = nearby(l, 40);
        return pool.isEmpty() ? null : pool.get(rng.nextInt(pool.size()));
    }

    private void purgeMinions(BossState st) {
        st.minions.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid() || e.isDead();
        });
    }

    private void summonMinion(Wither r, BossState st, int realm) {
        purgeMinions(st);
        int cap = plugin.getConfig().getInt("triarch.stage" + st.stage + ".minion-cap", st.stage == 3 ? 7 : st.stage == 2 ? 5 : 3);
        if (st.minions.size() >= cap) return;
        Location at = r.getLocation().add((rng.nextDouble() - 0.5) * 4, 0, (rng.nextDouble() - 0.5) * 4);
        LivingEntity minion = switch (realm) {
            case 0 -> at.getWorld().spawn(at, Silverfish.class);
            case 1 -> at.getWorld().spawn(at, Blaze.class);
            default -> at.getWorld().spawn(at, Endermite.class);
        };
        minion.setRemoveWhenFarAway(true);
        st.minions.add(minion.getUniqueId());
    }

    // ============================================================================================
    //  six abilities, two per realm - fired at a targeted player, not just centred on the boss
    // ============================================================================================

    private void fireAbilities(Wither r, BossState st) {
        double dmg = plugin.getConfig().getDouble("triarch.stage" + st.stage + ".pulse-damage",
                st.stage == 3 ? 8 : st.stage == 2 ? 6 : 4);
        double radius = st.stage == 3 ? 8 : 6;

        if (st.stage == 3) {
            for (int i = 0; i < 6; i++) useAbility(r, st, i, dmg, radius);
            return;
        }
        useAbility(r, st, st.ability, dmg, radius);
        st.ability = (st.ability + 1) % 6;
    }

    private void useAbility(Wither r, BossState st, int ability, double dmg, double radius) {
        Player target = pickTarget(r.getLocation());
        if (target == null) return; // nobody in range - skip this ability this cycle
        switch (ability) {
            case 0 -> rootSnare(r, target, dmg);
            case 1 -> toxicSpores(r, target, dmg);
            case 2 -> infernoWave(r, target, dmg);
            case 3 -> magmaBurst(r, target, dmg);
            case 4 -> voidPull(r, target, dmg);
            default -> starfall(r, target, dmg, st.stage);
        }
        summonMinion(r, st, ability / 2); // 0/1 -> overworld, 2/3 -> nether, 4/5 -> end
    }

    private String name(int ability) {
        return ABILITY_NAMES[ability];
    }

    /** Overworld 1: poison/slow pulse centred on the target. */
    private void rootSnare(Wither r, Player target, double dmg) {
        Location l = target.getLocation();
        l.getWorld().spawnParticle(Particle.COMPOSTER, l.clone().add(0, 0.2, 0), 50, 3, 0.3, 3, 0);
        r.getWorld().playSound(l, "block.grass.break", 1.5f, 0.6f);
        for (Player t : nearby(l, 4)) {
            dealt(t, dmg, r);
            t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
        }
    }

    /** Overworld 2: a lingering poison cloud that pulses a few times where the target was standing. */
    private void toxicSpores(Wither r, Player target, double dmg) {
        Location center = target.getLocation();
        World w = r.getWorld();
        w.playSound(center, "entity.spider.ambient", 1.2f, 0.6f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t++ >= 4) {
                    cancel();
                    return;
                }
                w.spawnParticle(Particle.ITEM_SLIME, center.clone().add(0, 0.3, 0), 30, 1.5, 0.3, 1.5, 0);
                for (Player near : nearby(center, 3)) {
                    dealt(near, dmg * 0.5, r);
                    near.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40, 0));
                }
            }
        }.runTaskTimer(plugin, 0L, 15L);
    }

    /** Nether 1: a piercing fire ray fired straight at the target. */
    private void infernoWave(Wither r, Player target, double dmg) {
        Location eye = r.getEyeLocation();
        Vector dir = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector()).normalize();
        World w = r.getWorld();
        w.playSound(eye, "entity.blaze.shoot", 1.5f, 0.7f);
        java.util.Set<UUID> hit = new java.util.HashSet<>();
        for (double d = 1; d <= 30; d += 1.0) {
            Location loc = eye.clone().add(dir.clone().multiply(d));
            w.spawnParticle(Particle.FLAME, loc, 6, 0.25, 0.25, 0.25, 0.01);
            for (Player t : nearby(loc, 1.5)) {
                if (hit.add(t.getUniqueId())) {
                    dealt(t, dmg, r);
                    t.setFireTicks(80);
                }
            }
        }
    }

    /** Nether 2: telegraphed eruption at the target's feet - a moment to dodge, then it hits hard. */
    private void magmaBurst(Wither r, Player target, double dmg) {
        Location at = target.getLocation();
        World w = r.getWorld();
        w.spawnParticle(Particle.LAVA, at.clone().add(0, 0.1, 0), 20, 0.6, 0.1, 0.6, 0);
        w.playSound(at, "block.lava.pop", 1f, 0.6f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!r.isValid()) return;
            w.spawnParticle(Particle.EXPLOSION, at, 2, 0.6, 0.2, 0.6, 0);
            w.spawnParticle(Particle.FLAME, at, 60, 1.2, 0.4, 1.2, 0.05);
            w.playSound(at, "entity.blaze.hurt", 1.2f, 0.7f);
            for (Player t : nearby(at, 3)) {
                dealt(t, dmg * 1.5, r);
                t.setFireTicks(100);
            }
        }, 20L); // 1s telegraph
    }

    /** End 1: pulls the target in and levitates them. */
    private void voidPull(Wither r, Player target, double dmg) {
        World w = r.getWorld();
        w.spawnParticle(Particle.REVERSE_PORTAL, target.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.3);
        w.playSound(target.getLocation(), "entity.enderman.teleport", 1.5f, 0.6f);
        Vector pull = r.getLocation().toVector().subtract(target.getLocation().toVector());
        if (pull.lengthSquared() > 0.01 && !(GodItems.armorTier(target, GodType.LEGGINGS) >= 1)) {
            target.setVelocity(pull.normalize().multiply(1.3).setY(0.3));
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 30, 0));
        target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0));
        dealt(target, dmg, r);
    }

    /** End 2: telegraphed strikes from the sky on up to three nearby players. */
    private void starfall(Wither r, Player primary, double dmg, int stage) {
        List<Player> pool = nearby(r.getLocation(), 20);
        int count = Math.min(pool.size(), stage == 3 ? 3 : 2);
        List<Player> targets = new ArrayList<>();
        targets.add(primary);
        while (targets.size() < count) {
            Player extra = pool.get(rng.nextInt(pool.size()));
            if (!targets.contains(extra)) targets.add(extra);
        }
        World w = r.getWorld();
        w.playSound(r.getLocation(), "entity.ender_dragon.growl", 1.2f, 1.3f);
        for (Player t : targets) {
            Location mark = t.getLocation();
            w.spawnParticle(Particle.END_ROD, mark.clone().add(0, 6, 0), 10, 0.3, 0.3, 0.3, 0.02);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!r.isValid()) return;
                w.spawnParticle(Particle.FLASH, mark.clone().add(0, 1, 0), 1);
                w.spawnParticle(Particle.PORTAL, mark.clone().add(0, 1, 0), 50, 0.6, 1, 0.6, 0.4);
                w.playSound(mark, "entity.lightning_bolt.impact", 1f, 1.2f);
                for (Player near : nearby(mark, 2.5)) dealt(near, dmg, r);
            }, 30L); // 1.5s telegraph
        }
    }

    private void dealt(Player t, double dmg, Wither source) {
        t.damage(dmg, source);
    }

    // ============================================================================================
    //  death: reward + cleanup
    // ============================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Wither r)) return;
        if (!r.getPersistentDataContainer().has(stageKey, PersistentDataType.INTEGER)) return;
        int stage = r.getPersistentDataContainer().get(stageKey, PersistentDataType.INTEGER);

        BossState st = active.get(r.getUniqueId());
        UUID bound = st != null ? st.boundPlayer : null;
        cleanup(r.getUniqueId());

        Player rewardTo = (bound != null && Bukkit.getPlayer(bound) != null) ? Bukkit.getPlayer(bound) : r.getKiller();
        if (rewardTo != null) {
            reward(rewardTo, stage);
        } else {
            Bukkit.broadcast(GodItems.c("&5&l☠ &dThe Triarch Singularity" + stageSuffix(stage) + " has fallen.&5&l ☠"));
        }
    }

    private void reward(Player p, int stage) {
        Location at = p.getLocation();
        World w = p.getWorld();

        w.dropItemNaturally(at, GodItems.createConvergence(stage));
        for (ItemStack it : lootFor(stage)) {
            w.dropItemNaturally(at, it);
        }
        if (stage == 3) {
            w.dropItemNaturally(at, elytra255());
            for (int i = 0; i < 3; i++) w.dropItemNaturally(at, shulkerOfRockets());
        }

        w.spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 1, 0), 80, 0.6, 1, 0.6, 0.5);
        Bukkit.broadcast(GodItems.c("&5&l☠ &d" + p.getName() + " has overcome The Triarch Singularity"
                + stageSuffix(stage) + "&d!&5&l ☠"));
    }

    // ---------------------------------------------------------------- loot

    private static final String[] TRIM_NAMES = {"SENTRY", "VEX", "WILD", "COAST", "DUNE", "WAYFINDER", "RAISER",
            "SHAPER", "HOST", "WARD", "SILENCE", "TIDE", "SNOUT", "RIB", "EYE", "SPIRE", "FLOW", "BOLT"};

    private List<Material> availableTrims() {
        List<Material> list = new ArrayList<>();
        for (String n : TRIM_NAMES) {
            Material m = Material.matchMaterial(n + "_ARMOR_TRIM_SMITHING_TEMPLATE");
            if (m != null) list.add(m);
        }
        return list;
    }

    private ItemStack opEnchantedBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class, m -> {
            for (Enchantment ench : org.bukkit.Registry.ENCHANTMENT) {
                if (ench.equals(Enchantment.VANISHING_CURSE)) continue;
                m.addStoredEnchant(ench, ench.getMaxLevel(), true);
            }
            m.displayName(GodItems.c("&5&lTome of the Triarch"));
            m.setEnchantmentGlintOverride(true);
        });
        return book;
    }

    private ItemStack elytra255() {
        ItemStack e = new ItemStack(Material.ELYTRA);
        e.editMeta(m -> {
            m.addEnchant(Enchantment.UNBREAKING, 255, true);
            m.setUnbreakable(true);
            m.displayName(GodItems.c("&5&l✦ Wings of the Triarch ✦"));
            m.setEnchantmentGlintOverride(true);
        });
        return e;
    }

    private ItemStack shulkerOfRockets() {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        box.editMeta(BlockStateMeta.class, bsm -> {
            if (bsm.getBlockState() instanceof ShulkerBox sb) {
                ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET, 64);
                rocket.editMeta(FireworkMeta.class, fm -> fm.setPower(3));
                for (int i = 0; i < sb.getInventory().getSize(); i++) sb.getInventory().setItem(i, rocket.clone());
                bsm.setBlockState(sb);
            }
            bsm.displayName(GodItems.c("&5&lShulker of Flight"));
        });
        return box;
    }

    private List<ItemStack> lootFor(int stage) {
        int idx = Math.max(0, Math.min(2, stage - 1));
        int[] netheriteBlocks = {2, 4, 7};
        int[] beacons = {1, 2, 3};
        int[] goldBlocks = {12, 20, 64};
        int[] diamondBlocks = {6, 12, 64};
        int[] emeraldBlocks = {6, 12, 64};
        int[] apples = {1, 2, 5};
        int[] zombieEggs = {0, 1, 2};
        int[] books = {1, 2, 4};
        int[] trims = {1, 2, 4};

        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Material.NETHERITE_BLOCK, netheriteBlocks[idx]));
        loot.add(new ItemStack(Material.BEACON, beacons[idx]));
        loot.add(new ItemStack(Material.GOLD_BLOCK, goldBlocks[idx]));
        loot.add(new ItemStack(Material.DIAMOND_BLOCK, diamondBlocks[idx]));
        loot.add(new ItemStack(Material.EMERALD_BLOCK, emeraldBlocks[idx]));
        loot.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, apples[idx]));
        if (zombieEggs[idx] > 0) {
            Material egg = Material.matchMaterial("ZOMBIE_VILLAGER_SPAWN_EGG");
            if (egg != null) loot.add(new ItemStack(egg, zombieEggs[idx]));
        }
        for (int i = 0; i < books[idx]; i++) loot.add(opEnchantedBook());
        List<Material> trimPool = availableTrims();
        for (int i = 0; i < trims[idx] && !trimPool.isEmpty(); i++) {
            loot.add(new ItemStack(trimPool.get(rng.nextInt(trimPool.size()))));
            loot.add(new ItemStack(Material.NETHERITE_INGOT, 4));
        }
        return loot;
    }

    // ============================================================================================
    //  admin cleanup / monitoring
    // ============================================================================================

    private void cleanup(UUID bossId) {
        BossState st = active.remove(bossId);
        if (st == null) return;
        if (st.task != null) st.task.cancel();
        if (st.bar != null) st.bar.removeAll();
        if (st.boundPlayer != null) activeForPlayer.remove(st.boundPlayer, bossId);
        for (UUID id : st.minions) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) e.remove();
        }
    }

    /** Removes tracked Triarch entities (and sweeps for orphaned tagged ones). If player is null, removes all. */
    public int removeAll(Player onlyFor) {
        int count = 0;
        if (onlyFor != null) {
            UUID bossId = activeForPlayer.get(onlyFor.getUniqueId());
            if (bossId != null) {
                Entity e = Bukkit.getEntity(bossId);
                if (e != null) e.remove();
                cleanup(bossId);
                count++;
            }
            return count;
        }
        for (UUID bossId : new ArrayList<>(active.keySet())) {
            Entity e = Bukkit.getEntity(bossId);
            if (e != null) e.remove();
            cleanup(bossId);
            count++;
        }
        // sweep for any tagged Triarch not currently tracked (e.g. brain never reattached)
        for (World w : Bukkit.getWorlds()) {
            for (Entity ent : w.getEntities()) {
                if (ent instanceof Wither r && r.getPersistentDataContainer().has(stageKey, PersistentDataType.INTEGER)) {
                    r.remove();
                    count++;
                }
            }
        }
        return count;
    }

    public List<String> listActive() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, BossState> entry : active.entrySet()) {
            Entity e = Bukkit.getEntity(entry.getKey());
            BossState st = entry.getValue();
            String boundName = st.boundPlayer != null && Bukkit.getPlayer(st.boundPlayer) != null
                    ? Bukkit.getPlayer(st.boundPlayer).getName() : "unbound/offline";
            String hp = e instanceof LivingEntity le ? (int) le.getHealth() + " hp" : "unknown";
            lines.add("Stage " + st.stage + " - bound to " + boundName + " - " + hp + " - next: " + name(st.ability));
        }
        return lines;
    }

    // ============================================================================================
    //  the boss ignores environmental fire/lava/drowning damage (it "combines" the three realms)
    // ============================================================================================

    /** Awakened Triarch: every hit is capped, and it regenerates faster than that (see brain). Stages 1-2 are untouched. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamageCap(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Wither r)) return;
        BossState st = active.get(r.getUniqueId());
        if (st == null || st.stage != 3) return;
        if (!plugin.getConfig().getBoolean("triarch.stage3.unbeatable", false)) return;
        double cap = plugin.getConfig().getDouble("triarch.stage3.max-damage-per-hit", 1.0);
        if (e.getDamage() > cap) e.setDamage(cap);
    }

    /** Tracks when the Triarch (or its skulls / scripted abilities) last hurt a player. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTriarchHitsPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) src = shooter;
        if (!(src instanceof Wither w) || !active.containsKey(w.getUniqueId())) return;
        long now = System.currentTimeMillis();
        lastTriarchHit.put(victim.getUniqueId(), new Hit(w.getUniqueId(), now));
        BossState st = active.get(w.getUniqueId());
        if (victim.getUniqueId().equals(st.boundPlayer)) {
            st.lastHit = now;
            st.warned.clear(); // the timer restarted, so warn again next time
        }
    }

    /**
     * True if this player was just killed by THEIR OWN bound Triarch (or its Divine Overload). Other players it
     * happens to kill in a group fight die normally - only the player it is bound to forfeits their loot.
     */
    private boolean killedByTriarch(Player p) {
        Hit h = lastTriarchHit.get(p.getUniqueId());
        return h != null && System.currentTimeMillis() - h.time() <= 8000 && active.containsKey(h.boss())
                && h.boss().equals(activeForPlayer.get(p.getUniqueId()));
    }

    /** Used by GodGear so its "god items always drop" rule doesn't rescue items the Triarch is about to destroy (own boss only). */
    public boolean willDestroyLoot(Player p) {
        return plugin.getConfig().getBoolean("triarch.destroy-loot-on-kill", true) && killedByTriarch(p);
    }

    /**
     * Player death: if the Triarch killed them, ALL their loot (god items included) is destroyed and the Triarch despawns.
     * If the dead player was the one a Triarch was bound to (any cause), that Triarch also despawns and the overload ends.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        UUID id = victim.getUniqueId();
        boolean killed = killedByTriarch(victim);
        Hit hit = lastTriarchHit.remove(id);
        UUID boundBoss = activeForPlayer.get(id);

        if (killed && plugin.getConfig().getBoolean("triarch.destroy-loot-on-kill", true)) {
            e.setKeepInventory(false);
            e.setKeepLevel(false);
            e.getDrops().clear();
            e.setDroppedExp(0);
            victim.getInventory().clear();
            Bukkit.broadcast(GodItems.c("&5&l☠ &d" + victim.getName()
                    + "&d was consumed by The Triarch Singularity - everything they carried is destroyed.&5&l ☠"));
        }

        // the bound player died (to the Triarch or to anything else): their Triarch despawns, no reward.
        if (boundBoss != null) despawnBoss(boundBoss);
    }

    private void despawnBoss(UUID bossId) {
        Entity ent = Bukkit.getEntity(bossId);
        cleanup(bossId);
        if (ent != null && ent.isValid()) {
            ent.getWorld().spawnParticle(Particle.SMOKE, ent.getLocation().add(0, 1, 0), 60, 1, 1, 1, 0.05);
            ent.remove(); // despawn: no EntityDeathEvent, so no reward
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossEnvDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Wither r) || !active.containsKey(r.getUniqueId())) return;
        DamageCause c = e.getCause();
        if (c == DamageCause.FIRE || c == DamageCause.FIRE_TICK || c == DamageCause.LAVA || c == DamageCause.DROWNING) {
            e.setCancelled(true);
        }
    }
}
