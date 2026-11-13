# TheBox server setup

A one-time checklist for setting up a prison server with TheBox. Work through it top to bottom.

- Commands written as `lp …` or `thebox …` (no slash) are for the **server console**, where you can paste a whole block at once.
- Commands written with a slash (`/thebox …`) are for **in-game**, as an op or a player with `thebox.admin`.
- Anything under `/thebox` also works as `/tb`.
- In-game help: `/thebox mine`, `/thebox rank`.

## 1. Install the plugins

The server needs [Paper](https://docs.papermc.io/paper/getting-started/) 26.3, on Java 25 or newer.

**Build TheBox** on your computer, from this repository:
```
cd plugins/TheBox
./gradlew build
```
The plugin is `build/libs/TheBox-<version>.jar`. It doesn't bundle its dependencies; those are the other plugins below.

With the server **stopped**, put these jars directly in the server's `plugins/` folder (Paper ignores subfolders). The exact versions tested with TheBox are listed in the `downloadPlugins` block of [build.gradle.kts](build.gradle.kts).

**Required.** TheBox won't start without the four marked *hard dependency*: they're its `depend` list in `plugin.yml`. The others are needed for the setup in this guide.

| Plugin | Why |
|---|---|
| TheBox | This plugin |
| WorldEdit *(hard dependency)* | Selecting mine regions, filling mines |
| WorldGuard *(hard dependency)* | Protecting the world: where players can build and mine. TheBox creates the regions for stalls and cells. |
| VaultUnlocked *(hard dependency)* | Connects TheBox to the economy (registers as "Vault") |
| LuckPerms *(hard dependency)* | Ranks, prestige and permissions |
| EssentialsX | The economy behind Vault (balances, `/pay`, `/balance`, `/eco`), and kits |
| EssentialsX Spawn | Gives new players the starter kit on their first join (section 13). It must be the same build as EssentialsX, or it warns "Version mismatch!", so download both from the same EssentialsX release or CI build. |
| PvPManager | Combat tagging: no commands or logging out mid-fight |

**Optional.**

| Plugin | Why |
|---|---|
| Multiverse-Core | Several worlds (say, one for mines and one for the market), and where new players first arrive (section 2) |
| QuickShop-Hikari | Player chest shops in market stalls, and the server booths |
| PacketEvents | Shows the item floating above each QuickShop shop. Without it or ProtocolLib, QuickShop warns "Invalid display provider!" at startup; shops still work, just with no display. Use the `packetevents-spigot` jar, 2.14.0 or newer for 26.3. QuickShop's `shop.display-protocol` should be `packetevents`, its default. |
| PlayerParticles | Particle cosmetics for prestige rewards. Check that it supports your Minecraft version first. |
| PlaceholderAPI | Lets other plugins show TheBox's numbers: `%thebox_earned%` is a player's lifetime `/sell` earnings (section 7). With it, PvPManager's combat action bar also shows its values instead of raw `%pvpmanager_…%` text. |
| ajLeaderboards | Ranks players by `%thebox_earned%` for the top sellers hologram (section 7). Needs PlaceholderAPI; 2.11.0 or newer for 26.3. |
| DecentHolograms | Shows the top sellers board as floating text at spawn (section 7). Needs PlaceholderAPI. |

Start the server once so every plugin writes its config files, then stop it.

**With Multiverse-Core:** TheBox waits for it to load its worlds before starting (it's a `softdepend`), so mines and stalls in Multiverse worlds are found. If a world is ever missing, its mines and stalls are kept in TheBox's files and simply sit idle until it's back. Two things to keep in mind:
- WorldGuard flags are per world: run the `__global__` commands in section 8 in *each* world.
- In a world with PvP mines, leave Multiverse's PvP setting for that world on. If a world has PvP turned off, no region can turn it back on.

**Updating a plugin later** (including a new TheBox build): put the new jar in `plugins/update/` and restart. Paper swaps it in on startup, so you never end up with two versions in `plugins/` ([Paper's update guide](https://docs.papermc.io/paper/updating/)). Always restart rather than using `/reload`, which Paper doesn't support.

## 2. Config files (server stopped)

**`plugins/Essentials/config.yml`**
- `starting-balance: 0`: money new players start with.
- `currency-symbol: '$'`.
- `disabled-commands:`: add `worth`. EssentialsX's `/worth` shows prices from its own `worth.yml`, which TheBox doesn't use, so players would see wrong prices. TheBox's version is `/sell worth`.
- EssentialsX also has a `/sell`. Its config says other plugins' commands get priority automatically, so TheBox's should win. If `/sell` ever shows EssentialsX's usage message instead, add `sell` to `disabled-commands` too.
- **EssentialsX Spawn, limited to the starter kit,** so it doesn't touch where players spawn. That stays with Multiverse (below) or vanilla:
  ```yaml
  respawn-listener-priority: none   # EssentialsX stays out of respawns
  newbies:
    spawnpoint: none                # no teleport on first join
    kit: starter                    # the kit from section 13
  ```
  - Leave `spawn-join-listener-priority` as it is (`high`). That listener is the one that gives the kit, so `none` would turn the kit off too.
  - `newbies.announce-format` broadcasts "Welcome <player> to the server!" on a first join. Set it to `''` to turn that off.
  - Both listener settings only take effect after a restart.

**`plugins/Multiverse-Core/config.yml`** *(with Multiverse-Core)*: where new players first arrive. Without this, vanilla puts first-time players on the highest block above the world spawn, such as the roof of a spawn building.
```yaml
spawn:
  first-spawn-override: true
  first-spawn-location: <your spawn world>
```
Then, in-game, stand where players should arrive and run `/mv setspawn`. Respawns use the same spot.
- **If brand-new players still land on the roof:** Multiverse detects first joins with `hasPlayedBefore()`, which EssentialsX's developers say no longer works on current server software ([EssentialsX #6466](https://github.com/EssentialsX/Essentials/issues/6466)). Let EssentialsX Spawn place new players instead:
  1. In EssentialsX's config, set `newbies.spawnpoint: newbies`.
  2. In-game, stand where players should arrive and run `/setspawn newbies`.
  3. Set `first-spawn-override: false` here.

  Multiverse still handles respawns.

**`plugins/LuckPerms/config.yml`** *(optional, privacy)*
- To stop LuckPerms ever uploading data (`/lp editor`, `/lp tree`, `/lp verbose upload`, `/lp export --upload`), add these two keys. They aren't in the default file. Point them at an address where nothing is running:
  ```yaml
  bytebin-url: 'http://localhost:1/'
  bytesocks-url: 'http://localhost:1/'
  ```
- `auto-install-translations: false` stops it downloading language files.
- For a server network, see [Network](#network-later) below.

**`plugins/PvPManager/config.yml`**

The defaults already fit: a 15-second combat tag, commands blocked mid-fight (so `/mine` can't be used to escape), logging out mid-fight kills the player and drops everything, and its `/pvp` toggle can't switch PvP off inside regions with `pvp allow` (`WorldGuard Overrides: true`). Change or check:
- **Keep `Unsafe Teleports: false`.** Turning it on blocks *plugin* teleports during combat, including TheBox lifting players out of a resetting mine, which would bury them.
- `Update Check` → `Auto Update: false`, so it never replaces itself with an untested version.
- `Newbie Protection` (10 minutes without PvP for new players): optional, since new players only have the PvP-free starter mine anyway.
- The combat action bar uses PlaceholderAPI placeholders (`%pvpmanager_…%`). Without PlaceholderAPI they may show up as raw text; if so, simplify `Combat Tag` → `Display` → `Action Bar` → `Message`.

**`plugins/bStats/config.yml`** *(optional, privacy)*: many plugins, PvPManager included, send anonymous usage statistics to bStats. `enabled: false` turns it off for all of them.

Start the server again.

## 3. LuckPerms: the rank ladder (console)

Ranks are LuckPerms groups `a` to `z` on a track called `ranks`. Each group inherits the one before it, so higher ranks keep lower ranks' unlocks. Paste each block into the console.

Create the groups:
```
lp creategroup a
lp creategroup b
lp creategroup c
lp creategroup d
lp creategroup e
lp creategroup f
lp creategroup g
lp creategroup h
lp creategroup i
lp creategroup j
lp creategroup k
lp creategroup l
lp creategroup m
lp creategroup n
lp creategroup o
lp creategroup p
lp creategroup q
lp creategroup r
lp creategroup s
lp creategroup t
lp creategroup u
lp creategroup v
lp creategroup w
lp creategroup x
lp creategroup y
lp creategroup z
```

Make each rank inherit the one before it:
```
lp group b parent add a
lp group c parent add b
lp group d parent add c
lp group e parent add d
lp group f parent add e
lp group g parent add f
lp group h parent add g
lp group i parent add h
lp group j parent add i
lp group k parent add j
lp group l parent add k
lp group m parent add l
lp group n parent add m
lp group o parent add n
lp group p parent add o
lp group q parent add p
lp group r parent add q
lp group s parent add r
lp group t parent add s
lp group u parent add t
lp group v parent add u
lp group w parent add v
lp group x parent add w
lp group y parent add x
lp group z parent add y
```

Create the track, in order:
```
lp createtrack ranks
lp track ranks append a
lp track ranks append b
lp track ranks append c
lp track ranks append d
lp track ranks append e
lp track ranks append f
lp track ranks append g
lp track ranks append h
lp track ranks append i
lp track ranks append j
lp track ranks append k
lp track ranks append l
lp track ranks append m
lp track ranks append n
lp track ranks append o
lp track ranks append p
lp track ranks append q
lp track ranks append r
lp track ranks append s
lp track ranks append t
lp track ranks append u
lp track ranks append v
lp track ranks append w
lp track ranks append x
lp track ranks append y
lp track ranks append z
```

Check it: `lp track ranks info` should list `a` through `z` in order.

### New players start in `a`

There's no LuckPerms setting for this. TheBox does it itself: whenever a player joins and isn't on the `ranks` track yet, TheBox adds them to `a`. That includes players who joined before the track existed, at their next join.

LuckPerms also puts every player in its built-in `default` group, and they stay there. Leave it alone. LuckPerms doesn't let you swap it for another group and [recommends keeping it](https://luckperms.net/wiki/Default-Groups). So `lp user <name> info` lists both `default` and `a`, which is expected.

### Commands every player gets

Players who aren't op have no permissions apart from Paper's basics. EssentialsX's `player-commands` list in its `config.yml` does nothing once LuckPerms is installed, so grant everyday commands to LuckPerms' `default` group. Every player is in `default` whatever their rank, so these work even if a player isn't on the ladder yet. Rank perks go on `a` to `z`. EssentialsX's permission for a command is `essentials.<command>`:
```
lp group default permission set essentials.balance true
lp group default permission set essentials.balancetop true
lp group default permission set essentials.pay true
```
That's `/balance` (or `/bal`, `/money`), `/baltop` and `/pay`. They take effect right away, with no restart. Add others the same way, for example `essentials.msg` for `/msg` and `/r`. To see why a player does or doesn't have a permission: `lp user <name> permission check essentials.balance`. TheBox's own player commands (`/mine`, `/sell`, `/rankup`, `/prestige`, `/market`, `/cell`) need no permission. Mine access (section 9), market access and stall renting (section 10) and cell sizes (section 11) are granted by rank.

## 4. LuckPerms: the prestige track (console)

Prestige levels are groups `p1`, `p2`, … on a track called `prestige`, each inheriting the one before it. This creates five; add more any time the same way. The number of groups is the highest prestige players can reach.
```
lp creategroup p1
lp creategroup p2
lp creategroup p3
lp creategroup p4
lp creategroup p5
lp group p2 parent add p1
lp group p3 parent add p2
lp group p4 parent add p3
lp group p5 parent add p4
lp createtrack prestige
lp track prestige append p1
lp track prestige append p2
lp track prestige append p3
lp track prestige append p4
lp track prestige append p5
```

## 5. Staff (console)

Ops can already do everything. To give staff TheBox's admin commands without op:
```
lp creategroup admin
lp group admin permission set thebox.admin true
lp group admin permission set worldedit.* true
lp group admin permission set worldguard.* true
lp user <name> parent add admin
```
WorldEdit is needed for selecting mine regions. `worldguard.*` is full WorldGuard access, including bypassing protection (as ops can).

## 6. Rank names and costs

The first start writes `plugins/TheBox/ranks.yml` with ranks `a` to `z` and the **default costs** below. Change them, either in-game:
```
/thebox rank setcost b 3600
/thebox rank setname b <gray>B
```
`/rankup list` shows the ladder; for admins it also shows each rank's id.
Or edit `ranks.yml` by hand, then run `/thebox rank reload`. Reload right after editing: the next `/thebox rank` change saves the file and would overwrite unsaved hand edits.

**Default costs.** A rank costs the hours planned for it × an hour's income in the mine before it:
- **Hours:** B 15 minutes, C 30 minutes, D 1 hour, then 18 minutes more for each rank, up to 7.6 hours for Z. A first run from A to Z takes about 100 hours.
- **Income:** mines A–F, mined with ordinary pickaxes, pay from about $14,400 an hour, each about 1.15× the one before. From G, players have god-tier pickaxes (section 9) that mine about 4× faster, and each mine pays at least 1.2× the one before.

H costs 6.5× G, because the god-tier pickaxe arrives at G; after that, costs rise 1.2–1.5× a rank. Z costs $46M, and the whole ladder about $213M. A `ranks.yml` written by an older TheBox has other costs: flat placeholders (b = 4000, c = 8000, …) or the first formula's, which ended at $3.1M. To switch to these defaults without losing your rank names, paste into the console:
```
thebox rank setcost b 3600
thebox rank setcost c 8300
thebox rank setcost d 19000
thebox rank setcost e 28000
thebox rank setcost f 40000
thebox rank setcost g 55000
thebox rank setcost h 360000
thebox rank setcost i 530000
thebox rank setcost j 730000
thebox rank setcost k 990000
thebox rank setcost l 1300000
thebox rank setcost m 1800000
thebox rank setcost n 2300000
thebox rank setcost o 3500000
thebox rank setcost p 4300000
thebox rank setcost q 5500000
thebox rank setcost r 7000000
thebox rank setcost s 8500000
thebox rank setcost t 11000000
thebox rank setcost u 14000000
thebox rank setcost v 18000000
thebox rank setcost w 23000000
thebox rank setcost x 28000000
thebox rank setcost y 36000000
thebox rank setcost z 46000000
```
- To check a mine, mine it for 5 minutes with the gear players would have there, `/sell all`, and multiply by 12 for its hourly income. If it's far off, change its block mix rather than the cost.
- For a steeper or flatter ladder, make each mine more or less rich than the one before (1.15× for A–F, at least 1.2× from G), and work the costs out again.
- Cell prices follow these costs (section 11).

- `cost` is what it costs to rank up **to** that rank. Rank `a` is where everyone starts, so its cost is never charged.
- Costs go up with prestige: each prestige level adds `prestige-multiplier` × the cost (default 0.5, so Prestige 2 pays 200%). Change it with `/thebox rank setprestigemultiplier 0.25`.
- Prestiging resets the player's balance to 0, so nobody can bank money at Z and buy the whole ladder back at once. Players keep their items and cell, best pickaxe included, so a second run is quicker than the first: about 90 hours at 0.5. 0.7 makes it about as long as the first.
- Names use [MiniMessage](https://docs.papermc.io/adventure/minimessage/format/) colors, e.g. `<light_purple>Endermite`.
- **Chat shows each player's prestige and rank,** e.g. `[P2] [C] Name: message`, with no `[P…]` before the first prestige. The rank part is the rank's name from here, colors included, so keep names short. TheBox formats chat itself, so don't add another chat formatter such as EssentialsX Chat: whichever formats last wins.
- Keep the ranks in `ranks.yml` in the same order as the `ranks` track. A rank that's on the track but missing from `ranks.yml` blocks `/rankup` until it's added.

## 7. Sell prices

Set a price per item (any item, not just blocks):
```
/thebox price set cobblestone 2
/thebox price list
```
`/thebox price remove <item>` makes an item unsellable again. Prices live in `plugins/TheBox/prices.yml`; after hand edits run `/thebox price reload`.

**Suggested prices.** These go with the default rank costs (section 6): the starter mine pays about $5.60 a block, about $14,400 an hour. Set them once, then tune each mine through its block mix (section 9): changing a price changes every mine that drops the item.

| Item | Price |
|---|---|
| `cobblestone`, `stone` | 3 |
| `coal` | 15 |
| `raw_copper` / `copper_ingot` | 6 / 7.50 |
| `raw_iron` / `iron_ingot` | 32 / 40 |
| `lapis_lazuli` | 7 |
| `redstone` | 12 |
| `raw_gold` / `gold_ingot` | 80 / 100 |
| `diamond` | 200 |
| `emerald` | 300 |
| `cobbled_deepslate`, `deepslate` | 4 |
| `iron_block` | 360 |
| `gold_block` | 900 |
| `diamond_block` | 1800 |
| `emerald_block` | 2700 |
| `obsidian` | 25 |

- Copper, lapis and redstone ores drop several items (2–5, 4–9 and 4–5), so one ore is worth about $21, $45 and $54.
- Diamonds and emeralds are worth a lot, so mines hold few of them; older ores stay in later mines for variety.
- Storage blocks are exactly 9× their ingot or gem, so crafting or breaking them up neither makes nor loses money.

**Crafted items.** Cells are workshops: crafted items sell for more than their raw materials, so cells, cell upgrades and autocrafters pay off. The markup grows with the work, and stays below 2× so that selling raw stays a fair way to play:

| Kind | Price | Examples |
|---|---|---|
| Smelted | raw materials × 1.25 | `iron_ingot` 40, `gold_ingot` 100, `copper_ingot` 7.50 |
| Crafted from one material | raw materials × 1.5 | `iron_chestplate` 384, `stone_bricks` 4.50 |
| Components from several materials | raw materials × 1.8 | `piston` 101, `hopper` 288, `crafter` 391 |

- **Raw materials** means what was mined. An iron chestplate is 8 raw iron ($256) × 1.5. A piston is 4 cobblestone, 1 raw iron and 1 redstone ($56) × 1.8; its planks count as free.
- **Why not 2×:** nearly everything mined can be crafted into something, so the markup applies to a crafter's whole income. At 1.8× on everything, a crafter would reach Z in about 72 hours even after buying every cell, against 100 for selling raw. With these tiers a dedicated crafter lands at roughly 85–95 hours with every cell bought.
- **Recipes that can be undone stay exact:** storage blocks at 9× their ingot or gem, and nuggets (if you price them) at 1/9 of an ingot. A markup there would be money for nothing.
- **Wood counts as free,** so a trip to the wood mine is what a component costs.
- **The store must charge more than `/sell` pays** for anything it sells, or buying and selling makes free money. Now that armor can be sold, check the store's armor prices.

**Leave unpriced:**
- logs and planks, because tree farms in cells would be endless money;
- all food, above all whatever the free food kit gives;
- nuggets, raw ore blocks (raw iron block, …) and storage blocks no mine contains (coal, copper, lapis, redstone);
- ore blocks, so Silk Touch on ores is worthless.

**Top sellers.** TheBox adds up what each player earns from `/sell`, and only from `/sell`: `/pay` and shop sales just move money between players, so counting them would let two friends pass the same money back and forth. `/sell top` shows the 10 players who've earned the most, plus the player's own place. The totals are kept in `plugins/TheBox/earnings.yml`. Prestige doesn't reset them; a new season does (section 16).

To show the board as a hologram at spawn, install PlaceholderAPI, ajLeaderboards and DecentHolograms (section 1), restart, then in-game:
1. Register the board: `/ajlb add %thebox_earned%`. Its name is `thebox_earned`.
2. Add everyone who sold before the board existed: `/ajlb updatealloffline thebox_earned`. Otherwise players only show up once they next join.
3. Stand where the hologram should be and create it, with a title line:
   ```
   /dh h create topsellers &6&lTop sellers
   ```
4. Add a line for each place, 1 to 10. The `1` after `topsellers` is the hologram's page, not the place:
   ```
   /dh lines add topsellers 1 &e1. &f%ajlb_lb_thebox_earned_1_alltime_name% &7- &a$%ajlb_lb_thebox_earned_1_alltime_value%
   /dh lines add topsellers 1 &e2. &f%ajlb_lb_thebox_earned_2_alltime_name% &7- &a$%ajlb_lb_thebox_earned_2_alltime_value%
   ```
   …and so on up to 10.

ajLeaderboards updates about once a minute, so a sale can take a minute to show on the hologram. `/sell top` is always current.

## 8. World protection (WorldGuard, in-game)

Out of the box, anyone can break and place anything. Prison servers protect the whole world, then open up specific areas with [WorldGuard regions](https://worldguard.enginehub.org/en/latest/regions/global-region/).

1. Protect the world by default. `__global__` is the built-in region covering the world you're standing in, so run this in every world players can reach:
   ```
   /rg flag __global__ passthrough deny
   /rg flag __global__ pvp deny
   ```
   Now only region members (and ops) can build anywhere, and PvP is off everywhere except where a region turns it on (the mines). Don't use `build deny` on `__global__`: WorldGuard warns that it stops every piston in the world.
2. Spawn: select it with `//wand`, then:
   ```
   /rg define spawn
   /rg flag spawn pvp deny
   /rg addmember spawn <builder>
   ```
   Members can build in a region; everyone else can't.
3. Mines need nothing here: TheBox creates their regions itself (section 9, step 4).

Good to know:
- **Ops bypass all protection.** To see what players see, toggle it off with `/rg bypass`, or test as a non-op.
- **Overlapping regions:** the higher priority wins. The default is 0; change it with `/rg setpriority <region> <number>` ([priorities](https://worldguard.enginehub.org/en/latest/regions/priorities/)).
- `/rg flags <region>` lists a region's flags; in-game, the list is clickable.

## 9. Mines (in-game)

Mines are created from a WorldEdit selection, so this part needs a player. Repeat for each mine (the examples use a mine called `quarry`).

1. Build the mine area.
2. `//wand` gives you a wooden axe: left-click one corner, right-click the opposite corner.
3. Create the mine (it starts as 100% stone):
   ```
   /thebox mine create quarry
   ```
   TheBox does the rest of the setup itself: its WorldGuard regions (step 4), its resets (step 8) and where players arrive (step 7). What's left is the mix (step 5), the rank that unlocks it (step 10) and its PvP level (step 11). A selection that overlaps another mine is refused.
4. **Protection and PvP are automatic.** TheBox creates two WorldGuard regions for each mine and keeps them matching it:
   - `mine_quarry`, exactly the mine: players can break blocks but not place any (so no torches either), and no mobs spawn.
   - `mine_quarry_pvp`, the mine plus 8 blocks on every side: PvP, once the mine has a level (step 11). It doesn't change who can build there (it has `passthrough allow`).

   Instead of torches, everyone in that same area gets Night Vision, and loses it again outside. The edge is out in the open, not at the mine itself, so walking around the rim or hopping in and out of holes doesn't flash it on and off. A Night Vision potion a player drank keeps working. Players who die drop their items as usual.

   Don't edit these regions' size, flags or priority by hand: at every start TheBox puts them back, and it renames and deletes the regions with their mine. Other flags you add are kept, except through a rename.
   - **Keep spawn, the market and cells at least 8 blocks from any mine.** A PvP area's priority (10) beats theirs, so players at the mine's level could fight there. TheBox warns about overlaps when you set a level, and in the console at startup.
   - **Updating from an older TheBox:** at its first start, TheBox takes over the `mine_<id>` regions you made by hand. It fits each one to its mine, sets the flags above, removes their `pvp` flag (the PvP area handles PvP now) and adds the PvP areas. Check the console for overlap warnings.
5. Set the block mix. Weights are relative, so these two make 70% stone and 30% coal ore:
   ```
   /thebox mine addblock quarry stone 70
   /thebox mine addblock quarry coal_ore 30
   ```
   `/thebox mine removeblock quarry <block>` takes a block out of the mix.

   The outside of the mine hides its ores; otherwise any ore on the surface goes to whoever arrives first.
   - At every reset, the faces players can see get one block instead of the mix. That's any edge block whose neighbour just outside the mine is see-through: the top of a walled pit, plus the sides or bottom if they're glass or open.
   - By default that block is the mix's most common one (the first listed, on a tie), so there's nothing to set for most mines. `/thebox mine list` shows each mine's surface.
   - To pick the block yourself: `/thebox mine setsurface quarry stone`. `setsurface quarry auto` goes back to the most common block, and `setsurface quarry none` turns the surface off, for example for an event mine that should show its ores.
   - The surface takes the place of a little of the mix: about one layer, so 5% of a 20-tall mine.
6. The name players see:
   ```
   /thebox mine setname quarry <gray>The Quarry
   ```
   The id (`quarry`) is separate from the name. To change the id later, use `/thebox mine rename quarry <new id>`. The permission changes with it (`thebox.mine.<new id>`), so TheBox shows a LuckPerms command to run in the console, which moves the existing grants to the new permission.
7. Where players teleport to. Stand **outside** the mine (by default it's the center of the top):
   ```
   /thebox mine settp quarry
   ```
8. Automatic resets. New mines reset every hour. To change that, or to also reset once a percentage has been mined (0 turns either off):
   ```
   /thebox mine setresettime quarry 600
   /thebox mine setresetpercent quarry 80
   ```
   Players in or near the mine are warned in chat 1 minute, 10 seconds, and 3-2-1 seconds before a timed reset. A percentage reset starts a 10-second countdown. At the reset, anyone inside is lifted straight up onto the refilled mine (or sent to the mine's teleport point if something is built above it).
9. Fill it now:
   ```
   /thebox mine reset quarry
   ```
10. Unlock it for a rank. Give it to the **lowest** rank that should have it; higher ranks inherit it.
    ```
    lp group a permission set thebox.mine.quarry true
    ```
11. Set its PvP level. Number the mines 1, 2, 3, … in the order players unlock them; mines unlocked by the same rank share a level:
    ```
    /thebox mine setlevel quarry 2
    ```
    A player's level is the highest level among the mines they've unlocked. In a mine and within 8 blocks of it, players only fight others whose level matches the mine's, so higher ranks can't come back down to pick off newer players (and can't be hit there either). **A mine without a level has no PvP**, so set one on every PvP mine, and leave the starter mine (the one rank `a` unlocks) at 0 so new players are safe. Where two mines' PvP areas overlap, players outside both mines can only fight if both mines allow it. Ops have every mine, so test this with non-op players.

Check everything with `/thebox mine list`; it shows each mine's level.

**Suggested mines A to F.** One mine per rank, each about 1.15× richer than the one before, so that the default rank costs (section 6) take the planned time. Weights are out of 200, so 2 is 1%. Each mix assumes the pickaxe players have by then: copper, then iron, smelted and crafted in their cell from the mines' own ore (furnaces and crafting tables can only be placed in cells), then the store's Efficiency I.

| Mine | Pickaxe | Mix (weights) | Pays about |
|---|---|---|---|
| A | copper | `stone` 164, `coal_ore` 22, `copper_ore` 14 | $14k an hour |
| B | iron | `stone` 165, `coal_ore` 16, `copper_ore` 13, `iron_ore` 6 | $17k |
| C | iron | `stone` 161, `coal_ore` 13, `copper_ore` 13, `iron_ore` 13 | $19k |
| D | iron | `stone` 160, `coal_ore` 9, `copper_ore` 9, `iron_ore` 16, `lapis_ore` 6 | $22k |
| E | iron, Efficiency I | `stone` 164, `coal_ore` 5, `copper_ore` 8, `iron_ore` 13, `lapis_ore` 10 | $25k |
| F | iron, Efficiency I | `stone` 161, `coal_ore` 5, `copper_ore` 5, `iron_ore` 13, `lapis_ore` 11, `redstone_ore` 5 | $29k |

- Check each mine with the 5-minute test (mine with that pickaxe, `/sell all`, × 12). If one pays well off, change its mix rather than the prices.
- Players slower than the model take longer; at the pace of the first test, the ladder takes about 150 hours instead of 100.

**Suggested mines G to Z, with god-tier pickaxes.** From G, players' pickaxes go past vanilla levels (sold in four store markets, section 10, or given in kits), and every rank adds one thing to them. The mixes assume players have each rank's pickaxe. Most players will skip some, which makes the ladder longer (section 10):

| Rank | The pickaxe gains | Mix (weights out of 200) | Pays about |
|---|---|---|---|
| G | Efficiency VI (stone breaks instantly), Fortune III, Unbreaking III | `stone` 155, `obsidian` 1, `coal_ore` 3, `copper_ore` 5, `iron_ore` 14, `lapis_ore` 11, `redstone_ore` 11 | $163k an hour |
| H | Fortune IV | `stone` 160, `obsidian` 1, `coal_ore` 2, `iron_ore` 12, `lapis_ore` 10, `redstone_ore` 10, `gold_ore` 5 | $212k |
| I | Fortune V | `stone` 160, `obsidian` 1, `iron_ore` 12, `lapis_ore` 10, `redstone_ore` 10, `gold_ore` 7 | $260k |
| J | Fortune VI | `stone` 159, `obsidian` 1, `iron_ore` 10, `lapis_ore` 10, `redstone_ore` 10, `gold_ore` 10 | $320k |
| K | Unbreakable | `stone` 157, `obsidian` 1, `iron_ore` 10, `lapis_ore` 8, `redstone_ore` 10, `gold_ore` 13, `diamond_ore` 1 | $372k |
| L | Fortune VII | `stone` 157, `obsidian` 1, `iron_ore` 10, `lapis_ore` 8, `redstone_ore` 8, `gold_ore` 13, `diamond_ore` 3 | $481k |
| M | Fortune VIII | `stone` 157, `obsidian` 1, `iron_ore` 8, `lapis_ore` 8, `redstone_ore` 8, `gold_ore` 14, `diamond_ore` 4 | $578k |
| N | Efficiency IX (ores and deepslate break instantly) | `deepslate` 164, `iron_ore` 7, `lapis_ore` 5, `redstone_ore` 7, `gold_ore` 12, `diamond_ore` 4, `emerald_ore` 1 | $814k |
| O | Fortune IX | `deepslate` 167, `iron_ore` 6, `lapis_ore` 4, `redstone_ore` 6, `gold_ore` 11, `diamond_ore` 4, `emerald_ore` 2 | $934k |
| P | Fortune X | `deepslate` 170, `iron_ore` 4, `lapis_ore` 4, `redstone_ore` 4, `gold_ore` 10, `diamond_ore` 5, `emerald_ore` 3 | $1.1M |
| Q | Fortune XI | `deepslate` 170, `iron_ore` 4, `lapis_ore` 4, `redstone_ore` 4, `gold_ore` 8, `diamond_ore` 6, `emerald_ore` 4 | $1.4M |
| R | Fortune XII | `deepslate` 171, `iron_ore` 4, `lapis_ore` 2, `redstone_ore` 4, `gold_ore` 8, `diamond_ore` 6, `emerald_ore` 5 | $1.5M |
| S | Fortune XIII | `deepslate` 171, `iron_ore` 2, `lapis_ore` 2, `redstone_ore` 4, `gold_ore` 8, `diamond_ore` 7, `emerald_ore` 6 | $1.9M |
| T | Fortune XIV | `deepslate` 173, `iron_ore` 2, `redstone_ore` 2, `gold_ore` 7, `diamond_ore` 8, `emerald_ore` 8 | $2.3M |
| U | Fortune XV | `deepslate` 172, `redstone_ore` 2, `gold_ore` 7, `diamond_ore` 9, `emerald_ore` 10 | $2.8M |
| V | Efficiency XII (deepslate ores and storage blocks break instantly) | `deepslate` 161, `iron_block` 1, `gold_block` 1, `deepslate_iron_ore` 3, `deepslate_redstone_ore` 3, `deepslate_gold_ore` 9, `deepslate_diamond_ore` 10, `deepslate_emerald_ore` 12 | $3.4M |
| W | Fortune XVI | `deepslate` 164, `gold_block` 1, `diamond_block` 1, `deepslate_gold_ore` 9, `deepslate_diamond_ore` 11, `deepslate_emerald_ore` 14 | $4.0M |
| X | Fortune XVII | `deepslate` 161, `diamond_block` 1, `emerald_block` 1, `deepslate_gold_ore` 8, `deepslate_diamond_ore` 12, `deepslate_emerald_ore` 17 | $5.0M |
| Y | Fortune XVIII | `deepslate` 157, `diamond_block` 1, `emerald_block` 1, `deepslate_gold_ore` 7, `deepslate_diamond_ore` 13, `deepslate_emerald_ore` 21 | $6.0M |
| Z | Fortune XIX | `deepslate` 150, `diamond_block` 1, `emerald_block` 1, `deepslate_gold_ore` 9, `deepslate_diamond_ore` 13, `deepslate_emerald_ore` 26 | $7.4M |

- **Efficiency only matters where blocks start breaking instantly.** With a diamond pickaxe:
  - VI breaks hardness 1.5 instantly (stone, blackstone, tuff, terracotta);
  - IX breaks hardness 3 (stone ores, deepslate, end stone);
  - XII breaks hardness 5 (deepslate ores, iron, diamond and emerald blocks).
  - VII, VIII and X only matter for concrete, for cobblestone and bricks, and for cobbled deepslate and deepslate bricks, so the track skips them. Use them if you build themed mines from those blocks.
- **Fortune multiplies ore drops** (III ×2.2, X ×5.6, XIX ×10), but not fillers, obsidian or storage blocks. That's why these mines lean on ores: each Fortune level makes every ore worth more, so mines get richer without more ore in them. They're 13–25% ore.
- **Any filler works** if that rank's pickaxe breaks it instantly: hardness 1.5 or less from G, 3 from N, 5 from V. Price it near stone's $3, or deepslate's $4.
- **Obsidian** (G–M) is there so players can build enchanting tables. It never breaks instantly: about 2 seconds a block with Efficiency VI.
- **Making a god pick:** give the item with its enchantments and a high `repair_cost`, so anvils can't add more. For example, the G pick:
  ```
  /give @p diamond_pickaxe[enchantments={"minecraft:efficiency":6,"minecraft:fortune":3,"minecraft:unbreaking":3},repair_cost=1000,max_damage=8000]
  ```
  `max_damage` is its durability ([data components](https://minecraft.wiki/w/Data_component_format)): 8,000 instead of diamond's 1,561, so with Unbreaking III it lasts about 3.5 hours of mining instead of 40 minutes. From K, swap Unbreaking and `max_damage` for `unbreakable={}`.
- **These mixes assume about 210 blocks a minute** once everything breaks instantly. That's the least certain number here, so run the 5-minute test once you have a god pick.
- **High Fortune fills an inventory in a few minutes,** about 600 items a minute at Z, so players will lean on `/sell all`.

Two separate locks, on purpose:
- **Who can enter** a mine is TheBox's job (`thebox.mine.<id>`, granted by rank). WorldGuard's own `entry` flag only knows region members, not ranks.
- **What can be broken** is WorldGuard's job (the `mine_<id>` region). Mine resets aren't affected: TheBox fills mines through WorldEdit, which WorldGuard doesn't block.

Ops can enter every mine whether or not they've unlocked it. Test locks as a non-op player.

## 10. Market (in-game)

Players rent stalls in a market, build in them, and (with QuickShop-Hikari) run chest shops there. TheBox manages each stall's WorldGuard region, so there's no `/rg` work per stall.

1. **Build the market:** stalls with floors and walls, and roofs if you like. Optionally protect the whole market area with a region of its own; stall regions have priority 10, so they win inside it.
2. **Create the market**, standing where players should arrive:
   ```
   /thebox market create main
   /thebox market setname main <gold>The Bazaar
   ```
   You can create more markets later. A market can't be named after a `/market` subcommand (`rent`, `info`, `claim`…).

   **Unlock it for a rank.** Every market is locked until a rank unlocks it, like mines (higher ranks inherit it):
   ```
   lp group a permission set thebox.marketplace.main true
   ```

   **Updating from an older TheBox:** markets used to be open to everyone, so run this for each market you already have, or `/market` won't show it to anyone. Test as a non-op player: ops have every permission.
3. **Create the stalls.** For each one, select with `//wand` only the **empty space players may build in** (above the floor, inside the walls), then:
   ```
   /thebox market stall create main s1 400
   ```
   `400` is the rent per day. TheBox refuses a selection that has blocks in it or overlaps another stall, and creates the WorldGuard region `thebox_stall_main_s1`. The floor and walls stay outside the region, so renters can't break them.
4. **Let a rank rent stalls** (higher ranks inherit it):
   ```
   lp group c permission set thebox.market.rent true
   ```
5. Check with `/thebox market list` and `/thebox market stall list main`.

**Settings** are in `plugins/TheBox/markets.yml` (run `/thebox market reload` after editing):

| Setting | Default | Meaning |
|---|---|---|
| `max-prepay-days` | 7 | How many days of rent players can pay ahead |
| `grace-days` | 3 | How long an unpaid stall stays frozen before it's cleared |
| `impound-expiry-days` | 30 | How long items wait in the impound before they're sold |
| `max-helpers` | 2 | How many friends a renter can trust to build |
| `day-length-minutes` | 1440 | How long a rent "day" is. **For testing**, set it to e.g. 2 to watch auto-renewal, grace and eviction happen in minutes. Set it back to 1440 for real use. |

**How it works for players:**
- `/market` takes them to a market they've unlocked, with a menu if there are several; `/market <id>` goes straight to one. Standing in a stall, `/market info` shows its rent, and `/market rent [days]` rents it (one stall per player per market).
- Rent renews itself each day from their balance (`/market autorenew off` to stop). `/market extend <days>` pays ahead.
- If rent can't be paid, the stall is **frozen**: nobody can build, and its shops are closed. Paying (`/market extend`) unfreezes it.
- After the grace period, everything in the stall (chest contents, the blocks themselves, item frames, armor stands) goes to the renter's **impound**, and the stall is free again. `/market claim` opens the impound as a chest menu. Unclaimed items are sold at `prices.yml` value after `impound-expiry-days` (unpriced or customized items are worth nothing).
- `/market trust <player>` lets a friend build; `/market unrent` gives the stall up (contents go to the impound, no refund).

**Shops (QuickShop-Hikari):** creating a shop is op-only by default. Buying from shops already works for every player (`quickshop.use`). Give creating to the same rank that can rent:
```
lp group c permission set quickshop.player true
```
`quickshop.player` is QuickShop's bundle of everything a normal player needs.
- Keep `protection-checking: true` in `plugins/QuickShop-Hikari/config.yml` (the default). That's what limits shops to blocks the player could break, and WorldGuard only lets them break blocks in their own stall.
- QuickShop's optional `Compat-WorldGuard` add-on isn't needed.
- Shops in a frozen stall don't sell. When the stall is cleared, its shops and their signs are deleted.

How players make a shop ([QuickShop's guide](https://quickshop-community.github.io/QuickShop-Hikari-Documents/docs/modules/shops/shop-basic)), worth putting on a sign at the market:
1. Rent a stall and place a chest or barrel in it.
2. Hold the item to sell and left-click the chest, without sneaking. Type the price in chat. QuickShop puts the shop sign on the side that was clicked, so click from where shoppers will stand.
3. Put stock in the chest. Right-click the sign for the control panel: change the price, or switch the shop to **buying** (it buys that item from other players instead).
4. Shoppers left-click the chest or sign and type how many they want in chat.

Breaking the chest removes the shop. Trusted friends can make shops in the stall too; those shops, and their earnings, are theirs.

**Server booths** (no rent, no restocking): build a booth that looks like a stall, but **don't** register it with `stall create`. The market-wide region from step 1 protects it.
1. Optional, and do it first: to show a server name as the shops' owner instead of you, set these in QuickShop's `config.yml`, then `/quickshop reload`. It only applies when a shop is switched to unlimited.
   ```yaml
   unlimited-shop-owner-change: true
   unlimited-shop-owner-change-account: "[The Warden]"
   ```
   The brackets make it a made-up account. Without them, QuickShop looks the name up as a real Minecraft player. The quotes are needed because YAML reads `[...]` as a list.
2. Make each shop as above, then look at it and run `/quickshop unlimited` for endless stock ([QuickShop's admin shop guide](https://quickshop-community.github.io/QuickShop-Hikari-Documents/docs/guides/getting-started/create-admin-shop)).
3. Test with a non-admin account: the buyer pays, and no one receives it. With QuickShop's default `shop.pay-unlimited-shop-owners: false`, money spent at unlimited shops just disappears, so the booths are a money sink.
- Never put an admin shop inside a registered stall: clearing a stall (unpaid rent, `stall evict`, `/market unrent`) deletes every shop in it.
- Price every item above what `/sell` pays in `prices.yml`, or buying and selling in a loop makes endless money. Only make selling shops; a buying one is a second `/sell`.

**Pickaxe store markets** (the plan; the pickaxes are in section 9). Four markets of server booths sell god pickaxes. Each opens at a rank, and players can buy anything in a market they've unlocked. Every pickaxe costs the same as its rank (section 6), and each market's prices can be tuned on their own.

| Market | Opens at | Sells | Prices |
|---|---|---|---|
| 1 | G | The G pick (breakable, `max_damage=8000`) | $55,000 |
| 2 | K | The K–M picks (unbreakable) | $990,000 to $1,800,000 |
| 3 | N | The N–U picks | $2,300,000 to $14,000,000 |
| 4 | V | The V–Z picks | $18,000,000 to $46,000,000 |

- Market 1 has only the G pick. At their ranks' prices, breakable H–J picks would use up half or more of what they earn, so nobody should buy them. Give those Fortune levels in kits instead, or skip them.
- Buying the best pickaxe a market has right away beats buying each rank's in turn. So a player who buys well ends up with about five (G, L, N, S, V) and reaches Z in about 140 hours instead of 100. W–Z are never worth their price, so they make good rare kit rewards.
- Unlock each market for its rank, for example `lp group g permission set thebox.marketplace.picks_g true`. TheBox locks `/market`, not walking in, so build the store markets sealed off, with no way in but `/market`.

**Admin tools:** `/thebox market stall evict main s1` clears a stall into its renter's impound right away. `stall setrent` changes the rent, `stall delete` removes a free stall. `/thebox market wipe` is for a new season (section 16): it clears every stall and empties every impound.

## 11. Cells (in-game)

Each player can own one cell, a room of their own. They get it with `/cell buy` (the first cell costs $2,000 by default, see the prices below). Cells come in tiers: a player grows their cell in place, then moves to a bigger cell in the next tier's cell block. You build solid blocks; TheBox carves the rooms.

**Sizes and prices** are in `plugins/TheBox/cells.yml` (run `/thebox cell reload` after editing). The defaults:

| Tier | Sizes (room floor, width × depth) | Height |
|---|---|---|
| 1 | 3×3 → 5×5 → 7×7 | 3 |
| 2 | 11×11 (a move) → 13×13 → 15×15 | 6 |
| 3 | 19×19 (a move) → 21×21 → 23×23 | 8 |

A player's first cell (tier 1's first size) costs $2,000 by default, so new players play a while before they get one: `/cell` tells them the price and `/cell buy` pays for it. Set its price to 0 to make it free. Settle the sizes before you build, because plots are built for a tier's largest room.

**Default prices**, to go with section 6's rank costs. Each size costs about what the rank that unlocks it costs (step 6), so every size costs more than the one before. A move to the next tier follows the same rule: the bigger room in a new cell block is its reward. Together they add about 25 hours to the ladder. A `cells.yml` written by an older TheBox has lower placeholder prices: set each size's `price` under `tiers` in `cells.yml`, then run `/thebox cell reload`.

| Size | Tier | Floor | Unlocked at | Price |
|---|---|---|---|---|
| 1 | 1 | 3×3 | (start) | $2,000 |
| 2 | 1 | 5×5 | D | $19,000 |
| 3 | 1 | 7×7 | G | $55,000 |
| 4 | 2 | 11×11 (a move) | J | $730,000 |
| 5 | 2 | 13×13 | M | $1,800,000 |
| 6 | 2 | 15×15 | P | $4,300,000 |
| 7 | 3 | 19×19 (a move) | S | $8,500,000 |
| 8 | 3 | 21×21 | V | $18,000,000 |
| 9 | 3 | 23×23 | Y | $36,000,000 |

1. **Build a cell block for each tier:** a mass of solid blocks (e.g. stone bricks; nothing that falls, like sand or gravel) on a floor, with a corridor along the front for players to walk in. Each cell needs a **plot** inside it: a solid box big enough for the tier's largest room plus the front wall. With the default sizes:
   - tier 1: 7 wide, 8 deep (7 plus the front wall), 3 tall
   - tier 2: 15 wide, 16 deep, 6 tall
   - tier 3: 23 wide, 24 deep, 8 tall

   Keep at least one block of solid wall around every plot (sides, back and top) and a floor under it: that's what stays between neighbors.
2. **Register each cell.** Stand on the corridor floor in front of the cell, lined up with its middle, facing the wall (at most 3 blocks from it), then:
   ```
   /thebox cell create 1 c1 here
   ```
   TheBox works out the rest from where you stand:
   - **The plot** starts at the first block in front of you, is centered on you, starts at your feet, and is the tier's size from step 1. TheBox remembers its most common block as the wall block, to rebuild the cell with later.
   - **The front space** (the strip of corridor the owner may decorate) is the air between the wall and you, as wide and tall as the plot. Stand right against the wall for a front space 1 deep, or a step or two back for 2 or 3. It's as tall as the plot so owners can redo their whole front wall: they can only build in the part of it behind their front space.

   It checks everything before changing anything: the plot must be completely solid, the front space completely empty, and there must be at least one block of wall between this cell and any other. Then it carves the starting room behind the front wall and creates two WorldGuard regions: `thebox_cell_c1` (the room) and `thebox_cell_c1_front` (the front space plus the front wall). The message says which way the front faces: if the cell is in the wrong spot, `/thebox cell delete c1` fills the plot back in, so you can try again.

   **Don't build doors, windows or patterns into a plot:** TheBox fills the whole plot with the wall block when it creates the cell, and again every time the cell is freed. Doors and windows go in the tier's front (step 4).
3. **Or use selections,** for a plot that isn't the tier's size or a front space smaller than the plot's front. Select exactly the plot with `//wand` and run `/thebox cell create 1 c1`. Then select the front space (1 to 3 blocks deep, within the plot's width and height, starting at the plot's bottom layer so the entrance is level with the room's floor) and run `/thebox cell setfront c1`. It makes the same checks.
4. **Give each tier a front.** Decorate one free cell of the tier the way every cell should start: say, an iron door in the front wall, an iron-bars window next to it, a button on the outside, and a button on the inside so owners can get out. Then:
   ```
   /thebox cell savefront c1
   ```
   That saves the cell's front wall, its front space and the inside of the front wall as the tier's front, and puts it on every free cell in the tier right away, turned to match cells that face other ways. Cells with an owner get it the next time they're freed, and every cell gets it back each time it's rebuilt. You can save a front before or after creating the rest of the tier.
   - **It must fit the smallest size:** doors, windows and gaps go in the part of the front wall in front of the starting room (the middle 3 blocks in tier 1, 11 in tier 2, 19 in tier 3). Solid blocks, like a frame around the door, can go anywhere.
   - **Only plain blocks:** signs, banners, heads and containers are refused, because only the block is saved, not what's in it (a sign would come back blank). Item frames and armor stands aren't saved.
   - Cells with a shallower front space get the parts that fit. To go back to a plain front, save a plain cell.
5. Repeat for every cell. Build a few more tier 1 cells than you have players: each player gets one the first time they type `/cell`, and keeps it. `/thebox cell list` shows them all.
6. **Unlock the sizes by rank.** Size 1 only costs money; each later size needs `thebox.cell.size.<n>`, counted across all tiers. On the default schedule, a size every third rank:
   ```
   lp group d permission set thebox.cell.size.2 true
   lp group g permission set thebox.cell.size.3 true
   lp group j permission set thebox.cell.size.4 true
   lp group m permission set thebox.cell.size.5 true
   lp group p permission set thebox.cell.size.6 true
   lp group s permission set thebox.cell.size.7 true
   lp group v permission set thebox.cell.size.8 true
   lp group y permission set thebox.cell.size.9 true
   ```

**Doors:** owners (and their helpers) can build in the front space and the front wall, e.g. an iron door with a button. WorldGuard stops everyone else using doors, buttons, levers and pressure plates in a region, so only they can open it. Owners can change the tier's front however they like. When the cell is freed, only what they added goes to their impound, and the tier's front is put back for the next owner. (An owner who breaks the default door keeps it: at most one door's worth per cell they own.) **Never set `use allow` on `__global__`:** its flags also apply inside regions that don't set them, so it would open every cell door. If spawn needs doors anyone can use, set `use allow` on the spawn region only.

**How it works for players:**
- `/cell` takes them inside their cell, just behind the front wall. Make sure each tier's front opens from inside too, for example with a button inside the room as well as outside (section 11, step 4: `savefront` saves the room's first layer). Otherwise players can't get out. The first time, it tells them what a cell costs, and `/cell buy` pays for it (or, if the price is 0, `/cell` gives them one right away). Cells aren't handed out automatically, so players who join once and leave (and admins) don't use one up. If none is free, online admins are warned. `/cell visit <player>` goes to the front of someone else's (they have to be let in).
- `/cell upgrade` opens a menu with the next size and its price. Within a tier, the room grows in place: the walls are carved back, sideways and away from the front, so the front wall and its door never move.
- Moving up a tier gives them a new cell in the next tier's block. They keep the old one for 7 days to carry their things over (`/cell old`); shulker boxes keep their contents when broken. On `/cell release`, or when time's up, whatever's left goes to their impound (`/cell claim`), and the old cell is rebuilt as a starting room, with the tier's front, for the next player.
- `/cell trust <player>` lets a friend build and open the door.
- Hostile mobs don't spawn in cells. Players can make QuickShop shops in their cell, like in a stall.

**Settings** in `cells.yml`: `move-days` (7), `max-helpers` (2), and `day-length-minutes` (1440). **For testing**, set `day-length-minutes` to e.g. 2, so a move's 7 "days" pass in 14 minutes. Set it back to 1440 for real use.

**Admin tools:** `/thebox cell evict c1` clears a cell into its owner's impound and frees it. `delete` forgets a free cell and fills its plot back in, so it's solid again, as it was before `create`. `/thebox cell wipe` clears every cell for a new season: nothing goes to the impound.

## 12. Prestige rewards

> Needs PlayerParticles. The version TheBox is tested with (8.13) lists Minecraft up to 26.2, so check it enables on 26.3 (`/plugins` shows it green).

Rewards are permissions on prestige groups, like mine unlocks on rank groups. Every player can open `/pp`, but they start with nothing in it: every effect and style is locked except the `normal` style, until a group grants it. A particle needs both an **effect** (which particle) and a **style** (the shape). Names are listed in the [PlayerParticles particle details](https://wiki.rosewooddev.io/PlayerParticles/particle-details/).
```
lp group p1 permission set playerparticles.effect.end_rod true
lp group p1 permission set playerparticles.style.halo true
lp group p3 permission set playerparticles.style.wings true
```
- Each prestige group inherits the one before it, so P3 players keep P1's particles.
- Rank groups work the same way, if a rank should unlock something too: `lp group m permission set playerparticles.effect.flame true`.
- `playerparticles.effect.*` and `playerparticles.style.*` unlock everything (e.g. for staff).
- How many particles a player can wear at once is `max-particles` in `plugins/PlayerParticles/config.yml` (default 3). To raise it for a group, grant `playerparticles.particles.max.<number>`, e.g. `lp group p5 permission set playerparticles.particles.max.5 true`.

Players pick their particles with `/pp`.

## 13. Starter kit and food

**A starter kit on the first join.** New players start with $0, so EssentialsX Spawn gives them a stone pickaxe to start mining. It's the `newbies.kit` set in section 2. Define the kit in `plugins/Essentials/kits.yml`, under `kits:`, then run `/essentials reload`:
```yaml
  starter:
    delay: -1
    items:
      - stone_pickaxe 1
```
- **Grant no permission for it.** The first-join kit is "given regardless of cost and permissions". If players also had `essentials.kits.starter`, they could claim it a second time with `/kit starter`.
- **Only players EssentialsX has never seen get it.** It decides that from its own user data, so a season reset gives everyone the kit again, because it deletes `plugins/Essentials/userdata/` (section 16).
- **To test,** use an account that has never joined. Or, with the server stopped, delete that account's file in `plugins/Essentials/userdata/`.

Mining all day uses up hunger fast. Two EssentialsX features cover it, with no TheBox setup needed:

**A free food kit for everyone, with a cooldown.** Add it to `plugins/Essentials/kits.yml` (`delay` is in seconds):
```yaml
kits:
  food:
    delay: 600
    items:
      - bread 16
```
Then grant it to the first rank (every higher rank inherits it), and run `/essentials reload` or restart:
```
lp group a permission set essentials.kit true
lp group a permission set essentials.kits.food true
```
Players claim it with `/kit food`.

**`/feed` as a rank unlock.** Pick the rank that earns it (here `m`):
```
lp group m permission set essentials.feed true
```
Its cooldown is `heal-cooldown` in `plugins/Essentials/config.yml` (default 60 seconds, shared with `/heal`).

## 14. Check that it works

As a **non-op** player:
1. Join, then in the console run `lp user <name> info`. The player should be in groups `default` and `a`. `/balance`, `/baltop` and `/pay` work. Chat shows the rank before the name: `[A] Name: message` (or rank A's name from section 6). With an account that has never joined: you arrive at the spawn point, not on a roof, and get the starter kit.
2. Breaking or placing blocks outside a mine is blocked (WorldGuard says so in chat).
3. `/mine` opens a menu of the unlocked mines (with only one, it goes straight there); `/mine quarry` teleports there. Breaking blocks in the mine works; placing them doesn't. Within 8 blocks of the mine you have Night Vision (an ∞ icon at the top right). It stays while you walk around the rim, and goes once you're farther away. PvP works in every mine except the starter mine, and within 8 blocks of them, but nowhere else. `/rg info` inside a mine lists `mine_quarry` and `mine_quarry_pvp`. After hitting another player, `/mine` is blocked for 15 seconds, and logging out in that time kills you.
   - PvP levels need two non-op players a mine apart. In the lower player's mine, neither can hit the other (melee, arrows, or a splash or lingering potion of poison); the attacker sees "Only players at this mine's level can fight here." The same goes a few blocks outside the mine. Afterwards `/mine` still works for both: a blocked hit must not combat-tag anyone.
4. Mine some blocks, then run `/sell all` (or `/sell hand`, `/sell worth`). `/sell top` lists you with what you've earned; after another `/sell all` your total goes up. With the hologram (section 7), you're on it within a minute.
5. `/rankup list` shows the ladder with the player's rank marked and the next rank's cost; `/rankup help` lists the rank commands. `/rankup` buys the next rank (`/rankup max` buys as many as you can afford). The whole server sees an announcement.
6. Walking into a locked mine is blocked with "You haven't unlocked … yet."
7. At the top rank, `/prestige` explains what prestiging does, and `/prestige confirm` does it: the player goes back to `a`, joins `p1`, their balance goes to 0 (items and cell stay), and the server sees an announcement. Chat now shows `[P1] [A]` before their name, and `/sell top` still has their total.
8. A non-op player without `thebox.marketplace.main` gets "You haven't unlocked any markets yet." from `/market`, and `/market main` says "You haven't unlocked that market." With it: `/market`, stand in a free stall, `/market rent`. Building works in your stall and nowhere else in the market. With `day-length-minutes: 2` and auto-renew off, the stall freezes after 2 minutes; after the grace period its contents show up in `/market claim`.
9. A new player is told to type `/cell` when they join; `/cell` shows the price, `/cell buy` without enough money is refused, and with enough it takes the money, gives them a cell and takes them into it. The door opens from inside, with the inside button, and from outside. An admin who creates cells doesn't get one. Building works in the room and the front space, not in the walls. With `thebox.cell.size.2`, `/cell upgrade` carves the room bigger and leaves what's in it alone. With the tier's front saved (section 11, step 4): the cell has its door, and another non-op player can't press its button. Add a block to the front and `/thebox cell evict` the cell: only the added block goes to the impound, and the door comes back. Check a cell facing a different way too: its door and buttons should face the corridor. As an admin, `create ... here` is refused when standing one block off center would make the plot touch its neighbor's, and `/thebox cell delete` on a free cell makes its plot solid again.

## 15. Special events

Mines that are only open for a while. They're ordinary mines (section 9), except that their permission is **temporary**: LuckPerms' `settemp` grants a permission that removes itself when it runs out ([permission commands](https://luckperms.net/wiki/Permission-Commands)).

Build them like any other mine (section 9), with three differences:
- Skip step 10: don't give the mine to a rank. The commands below grant access.
- Step 11: leave the level at 0 for no PvP, or give it a band's level if you want PvP there.
- Give it a short reset time, e.g. `/thebox mine setresettime halloween 300`. When time runs out, players already inside aren't removed. A reset lifts everyone onto the top of the mine, though, and players whose time is up can't go back in.

### Event mines: open for a date range

Open it for everyone. Every player is in `default`, including anyone who joins during the event:
```
lp group default permission settemp thebox.mine.halloween true 14d
```
`14d` means two weeks from now; durations combine, like `3d12h`. To close it at a fixed time instead, give a Unix timestamp. On Linux, `date -d '2026-11-30 23:59' +%s` prints one:
```
lp group default permission settemp thebox.mine.halloween true <timestamp>
```
- LuckPerms has no start date: run the command when the event begins.
- While it's open, the mine shows in `/mine`; afterwards it disappears from the menu.
- To end it early: `lp group default permission unsettemp thebox.mine.halloween`.

### Lootbox mine: short visits

Give one player a visit, e.g. as a reward:
```
lp user <name> permission settemp thebox.mine.loot true 10m
```
- To add time to a visit that's still running, put `accumulate` after the duration: `… true 10m accumulate`.
- To open it for everyone for a short event, use `lp group default …` as above.
- Special items aren't built yet: for now the lootbox mine drops its blocks like any other mine.

Test as a non-op player: ops can enter every mine.

## 16. New season (full reset)

A new season makes everyone a new player again: rank A, no prestige, the starting balance, no cell, an empty inventory, and PvPManager's newbie protection.

What you've set up stays:
- mines, cell locations and prices, sell prices and rank costs;
- stalls and the server booths;
- LuckPerms groups and their permissions, and staff permissions.

Player data is spread over several plugins, and some of it can only be deleted with the server stopped, so this takes one restart. Run the commands in the **console** (without `/`).

1. **Close the server:** `whitelist on`, then `stop`. Until step 5, only whitelisted players and ops can join.
2. **Back up** the whole server folder. Nothing below can be undone.
3. **Delete player data** (server still stopped):

   | Delete | What it resets |
   |---|---|
   | `world/players/data/`, `world/players/advancements/`, `world/players/stats/` | Inventories, ender chests, XP, spawn points, recipes, advancements, statistics |
   | `plugins/Essentials/userdata/` | Balances (back to `starting-balance`), used kits, homes, nicknames, mail, mutes. Everyone counts as new again, so they get the starter kit. |
   | `plugins/PvPManager/database.db` | PvP toggles and newbie protection (PvPManager's default storage) |
   | `plugins/TheBox/earnings.yml` | Lifetime `/sell` earnings (`/sell top` and the top sellers hologram) |
4. **Start the server and wipe the in-game parts:**
   ```
   thebox cell wipe confirm
   thebox market wipe confirm
   lp listgroups
   lp bulkupdate users delete "permission ~~ group._"
   lp bulkupdate users delete "permission ~~ group.p_"
   ```
   - **The market wipe** clears and frees every rented stall and empties every impound, including what evicted cells left there. Nothing goes to the impound. Server booths aren't stalls, so they stay.
   - **With the top sellers hologram** (section 7), also run `ajlb reset thebox_earned`. ajLeaderboards keeps its own copy of the totals, which deleting `earnings.yml` doesn't touch.
   - **The bulk updates** remove every player's rank and prestige. Each one prints a confirmation command; run it.
   - `~~` uses SQL `LIKE` patterns, where `_` matches exactly one character ([Bulk editing](https://luckperms.net/wiki/Bulk-Editing)). So the first command removes the rank groups `a`–`z`, and the second removes `p1`–`p9`. If you have `p10` or higher, also run it with `"permission ~~ group.p__"`.
   - Check the `lp listgroups` output first: no other group may match. For example, a group named `pvp` would match `group.p__`.
   - Use `users`, not `all`. The rank groups inherit each other, and `all` would break the ladder.
   - Players are put back in `a` when they next join.

   Finally, reset every mine with `thebox mine reset <mine>`.
5. **Check, then open:** join with a non-op account. Check that you arrive inside the spawn building, you're rank A with the starting balance, `/cell` shows the price, you got the starter kit, and you have newbie protection. Then run `whitelist off`.

**Notes**
- **To keep homes, nicknames, mail and mutes,** skip `userdata/` and run `eco reset ** 0` in step 4 instead. That resets everyone to `starting-balance`; check `/baltop` afterwards. Players then don't get the starter kit again, because EssentialsX still knows them, and kits they've used stay on cooldown.
- **Where new players arrive** is Multiverse's `first-spawn-override` (section 2). Multiverse decides who's new from the vanilla player files that step 3 deletes.
- **Newbie protection** only goes to players the server has never seen, so it depends on deleting the player files. Test it with a brand-new account at least once. An EssentialsX source comment says `hasPlayedBefore()`, which PvPManager uses, is unreliable on current server software.

## Network (later)

To share ranks and prestige across several servers ([LuckPerms network guide](https://luckperms.net/wiki/Network-Installation)):
- Every server's LuckPerms uses the same database: set `storage-method` to `mariadb` (or MySQL, PostgreSQL, MongoDB) and fill in the connection details.
- Leave `messaging-service: auto`; with a SQL database it syncs changes between servers on its own.
- Behind a Velocity proxy, set up [modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding/) so every server sees players' real UUIDs.
- Optionally set `server:` in each LuckPerms config (e.g. `prison`) to limit permissions to one server with `server=prison`.
- Move existing data with `lp export <file>` before switching and `lp import <file>` after. Avoid `--upload`, which sends the data to LuckPerms' web service.
- Balances don't transfer: EssentialsX keeps them per server.
