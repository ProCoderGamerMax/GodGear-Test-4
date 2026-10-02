# GodGear (Paper 26.1.2, Java 25)

New here? Read **BUILD-HELP.md** first - it explains how to get a working .jar with zero experience.

## Crafting
God Template (3x3):
    Netherite Sword | Nether Star   | Netherite Sword
    Diamond Block   | Mace          | Diamond Block
    Player Head     | Diamond Block | Player Head
Duplicate: 7 Netherite Scrap in a ring + Diamond Block (bottom middle) around a God Template -> 2 God Templates.
God Smithing Table: Smithing Table + God Template (shapeless).
Place the God Smithing Table and right-click it: the real smithing screen opens.
God Template (template slot) + netherite piece or Mace (base slot), leave the 3rd slot empty -> god item.
`/godrecipes` opens an in-game recipe book.

## Progression
Only PLAYER kills count. 4 kills = 1 upgrade, cap 12 kills (3 upgrades). Ability N unlocks at tier N.
Weapons earn kills when held; armour earns kills for every kill while worn.
Weapon actives: swap-hands key (rebind to X) = ability 3, Shift + it = ability 4. Cooldowns show on the action bar.

Prestige: reforge a 12-kill god item with a template (kills reset, +1 prestige, max 5): permanent stat bonus
(+15% damage per level on weapons, +10% damage reduction per level per armour piece) and a new colour on the name.

## Balance features
* Full set of 4 armour pieces = Godform: +20% damage reduction and an aura.
* Hitting a player disables Second Life and Wraith Dash for 5s.
* God Boots have permanent Strength II (their offensive perk).
* Weapon swings use vanilla cooldowns (no attack-speed boost; older weapons are migrated automatically); every active/proc ability
  (weapon [F]/[Shift+F] and armour procs like Mirror Aegis, Shockwave Landing, Phase Shift) has its
  own cooldown in config.yml.
* God armour pieces are forged with every enchantment that applies to them, at max level, including
  ones that normally conflict (Curse of Vanishing is skipped - it would delete the item).
* Godslayer (God Sword & God Mace at 12 kills): vs god-armoured players x1.75 damage and +6 true damage.
* Godbreaker (God Axe & God Spear at 12 kills): vs god-armoured players x1.5 damage and +4 true damage.
* God weapons are forged with every applicable enchantment at max level too (same as armour).
* Axe Sunder: Wither for 10s, doesn't refresh while active. Mace Stunning Blow: Slowness III 3s on hit; a 30% roll
  (then a per-target cooldown, `cooldowns.mace_stun`, default 10s) adds Slowness III 15s + Slowness X 5s.
* God items drop on death (even with keepInventory) and keep their tier. Dropped god items glow and never despawn.
* Heads drop when a player is killed by another player.

## Ambrosia
A craftable consumable ("food of the old gods"): shapeless recipe of a Nether Star, Totem of Undying,
Resin Clump, Heart of the Sea, Sniffer Egg, Enchanted Golden Apple, Trident, Piglin Head and Echo Shard
(any arrangement). Right-click to consume: +20 hearts (Health Boost X), Regeneration V, Absorption X,
Resistance IV, Strength II and Speed II, all for 4 minutes. Each Ambrosia holds 2 uses and stacks to 64;
a partly-used one no longer stacks with fresh ones (it's tracked per item). The God Template's Nether
Star is now an Ambrosia instead - if your server is missing Piglin Head or Resin Clump, Ambrosia's
recipe is disabled and the Template automatically falls back to a plain Nether Star.

## The Beginning (lore book)
New players get a written book, "The Beginning", covering the server's anarchic nature and how god
gear, forging, kills/prestige, abilities and the Convergence all work. `/god book` re-gives it anytime.

## Convergence & The Triarch Singularity
A player may safely hold as many **distinct** god items as their Convergence level allows: level 0 -> 0,
level 1 -> 1, level 2 -> 4, level 3 -> 8 (all of them). Carrying more is fatal over time - a "Divine
Overload" bypasses armour and chips away at their health every 5 seconds - and it spawns **The Triarch
Singularity** bound to them, at the stage matching the level they're missing.

The Triarch is a Wither with its stats overridden (so it keeps its own AI - it flies, melees, and
shoots wither skulls - on top of everything below), a permanent boss bar, and continuous ambience
layered from Warden (sonic/sculk particles, growls), Wither (native particles) and Ender Dragon
(dragon-breath, screeches). Every attack-interval it fires a scripted ability at a targeted player -
stage 3 fires all six every interval - from six abilities, two per realm:
- **Overworld:** Root Snare (poison/slow pulse), Toxic Spores (a lingering poison cloud)
- **Nether:** Inferno Wave (a fire ray), Magma Burst (a telegraphed eruption underfoot)
- **End:** Void Pull (drags the target in + levitates), Starfall (telegraphed sky-strikes on 1-3 players)

**Divine Overload rules:** it is suspended while you're actively fighting your guardian. It applies if you run
away (further than `triarch.flee-distance`), and if the guardian hasn't damaged you for 3 minutes
(`triarch.idle-lock-seconds`) it locks on until the guardian is defeated or you die. A chat countdown warns you at 120/60/30/15s before the lock. If the Triarch
(or its overload) kills the player it is BOUND to, everything they carry is destroyed and it despawns; other players
it kills in a group fight die normally. If the bound player dies to anything else, it also despawns. All three stages are beatable (stage 3 is extremely hard); an optional
`triarch.stage3.unbeatable: true` makes Awakened impossible.

Difficulty: stage 1 hard, stage 2 very hard, stage 3 (Awakened) extremely hard - tune `triarch.*` in
config.yml. Defeating it drops a Convergence item (must be right-clicked to take effect - it won't work
out of order; you need level 1 before you can use a level-2 item) plus a vanilla-only loot bundle that
gets dramatically better each stage: netherite blocks, beacons, gold/diamond/emerald blocks, notch
apples, zombie villager spawn eggs, fully-enchanted books, and armour trim templates, all scaling up,
with stage 3 guaranteeing an Unbreaking 255 Elytra and three Shulker Boxes of max-power rockets.

Real limitation: Java resource packs can only retexture a mob's existing model, not fuse three mobs'
skeletons into one - true visual fusion needs a modelling plugin (e.g. ModelEngine), which this project
doesn't depend on. The Wither's shape plus the layered particles/sounds above is the vanilla-only
approximation. Also, the scripted brain doesn't survive a restart mid-fight (it reattaches to a still-
loaded boss on startup, but loses its player-binding, so it falls back to rewarding the killer); use
`/god boss remove [player]` if one gets stuck.

## Ambrosia vs armour
Ambrosia and god armour only stack a little. While Ambrosia is active (4 min), god armour's own damage reduction
(set bonus + prestige) is multiplied by `ambrosia-armour-factor` (default 0.25), the chestplate's +10 hearts is
suspended and its passive Resistance is skipped. Milk or death ends it early.

## Armour trims
God armour carries a trim (Silence / Ward / Spire / Vex on helmet / chestplate / leggings / boots). The trim colour
climbs with prestige: gold, emerald, diamond, amethyst, redstone, resin. Note: with the resource pack's custom armour
model enabled, the trim shows in the tooltip but may not render on the worn model.

## Anti-farm
The same killer+victim pair only counts once every `kill-farm-cooldown-seconds` (default 120s) - repeat
kills on the same alt/target don't fast-track god gear until the cooldown passes.

## Spawn protection
`spawn-zones` in config.yml defines spheres (world/x/y/z/radius) where god abilities don't work: weapon
[F]/[Shift+F], Second Life, Mirror Aegis, Phase Shift, Shockwave Landing, Wraith Dash. Always-on passive
buffs (Night Vision, Speed, etc.) still apply everywhere. Empty by default - add your own coordinates.

## Prestige aura, God Protection aura & ability death messages
Prestige 3+ gives a visible particle aura and an occasional sound cue (gold/cyan/magenta by level), and
Speed (leggings) / Strength (boots) scale from level II up to level IV at max prestige. Convergence
level 1-3 gives a separate "God Protection" aura (white/blue/violet). Kills from a named active ability
(Wrath Wave, Smiting Storm, Earthsplitter, Gravity Well, Piercing Charge, Skyfall Volley, Titan Smash,
Shockwave Landing, Godslayer) show a custom death message instead of the vanilla one.

## Commands
Public, self-only (never reveal another player's gear):
- `/godrecipes` or `/god recipes` - the recipe book
- `/god book` - re-give yourself "The Beginning"
- `/god info` (alias `/god tune`) - live balance sheet: kill/prestige numbers, weapon perks, cooldowns, Triarch rules
- `/god status` - your own Convergence level/cap, distinct god items held, held weapon's tier

Admin (`godgear.admin`):
- `/god give <player> <item|template|table> [kills]`
- `/god inspect <player>` - their equipped god items, tiers, kills, prestige
- `/god test` - a chest of duplicate/test god items, templates, Convergence items and trophies
- `/god setconvergence <player> <0-3>` / `/god reset <player>`
- `/god boss <player> <1|2|3>` - force-spawn a Triarch stage, bound to that player
- `/god boss remove [player]` - remove tracked/orphaned Triarch entities
- `/god boss list` - list active Triarch fights
- `/god reload` - reload config.yml (custom-models and ability-key-label need a restart)
