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

| Plugin | Why |
|---|---|
| TheBox | This plugin |
| WorldEdit | Selecting mine regions, filling mines |
| WorldGuard | Protecting the world: where players can build and mine |
| VaultUnlocked | Connects TheBox to the economy (registers as "Vault") |
| EssentialsX | The economy (balances, `/pay`, `/balance`, `/eco`) |
| LuckPerms | Ranks, prestige and permissions |
| PvPManager | Combat tagging: no commands or logging out mid-fight |
| QuickShop-Hikari *(optional)* | Player chest shops in market stalls |
| PlayerParticles *(optional)* | Particle cosmetics for prestige rewards. Check that it supports your Minecraft version first. |
| Multiverse-Core *(optional)* | Several worlds (say, one for mines and one for the market) |

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

**`plugins/LuckPerms/config.yml`** *(optional, privacy)*
- To stop LuckPerms ever uploading data (`/lp editor`, `/lp tree`, `/lp verbose upload`, `/lp export --upload`), add these two keys. They aren't in the default file. Point them at an address where nothing is running:
  ```yaml
  bytebin-url: 'http://localhost:1/'
  bytesocks-url: 'http://localhost:1/'
  ```
- `auto-install-translations: false` stops it downloading language files.
- For a server network, see [Network](#network-later) below.

**`plugins/PvPManager/config.yml`**

The defaults already fit: a 15-second combat tag, commands blocked mid-fight (so `/mines` can't be used to escape), logging out mid-fight kills the player and drops everything, and its `/pvp` toggle can't switch PvP off inside regions with `pvp allow` (`WorldGuard Overrides: true`). Change or check:
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
That's `/balance` (or `/bal`, `/money`), `/baltop` and `/pay`. They take effect right away, with no restart. Add others the same way, for example `essentials.msg` for `/msg` and `/r`. To see why a player does or doesn't have a permission: `lp user <name> permission check essentials.balance`. TheBox's own player commands (`/mines`, `/sell`, `/ranks`, `/rankup`, `/prestige`, `/market`) need no permission. Mine access (section 9) and stall renting (section 10) are granted by rank.

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

The first start writes `plugins/TheBox/ranks.yml` with ranks `a` to `z` and **placeholder costs** (b = 1000, c = 2000, …). Replace them, either in-game:
```
/thebox rank setcost b 1500
/thebox rank setname b <gray>B
```
`/ranks` shows the ladder; for admins it also shows each rank's id.
Or edit `ranks.yml` by hand, then run `/thebox rank reload`. Reload right after editing: the next `/thebox rank` change saves the file and would overwrite unsaved hand edits.

- `cost` is what it costs to rank up **to** that rank. Rank `a` is where everyone starts, so its cost is never charged.
- Costs go up with prestige: each prestige level adds `prestige-multiplier` × the cost (default 0.5, so Prestige 2 pays 200%). Change it with `/thebox rank setprestigemultiplier 0.25`.
- Names use [MiniMessage](https://docs.advntr.dev/minimessage/format.html) colors, e.g. `<light_purple>Endermite`.
- Keep the ranks in `ranks.yml` in the same order as the `ranks` track. A rank that's on the track but missing from `ranks.yml` blocks `/rankup` until it's added.

## 7. Sell prices

Set a price per item (any item, not just blocks):
```
/thebox price set cobblestone 0.5
/thebox price set coal 2
/thebox price set iron_ingot 10
/thebox price list
```
`/thebox price remove <item>` makes an item unsellable again. Prices live in `plugins/TheBox/prices.yml`; after hand edits run `/thebox price reload`.

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
3. Each mine gets its own region in the next section.

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
4. Let players break blocks in it. With the **same selection** still active, make a WorldGuard region named after the mine:
   ```
   /rg define mine_quarry
   /rg flag mine_quarry block-break allow
   /rg flag mine_quarry mob-spawning deny
   /rg flag mine_quarry pvp allow
   ```
   Players can now break blocks in the mine, but not place any (placing stays protected). **PvP is on in every mine except the starter mine** (the one rank `a` unlocks), so new players are safe; for that one, skip the `pvp allow` line. Players who die drop their items as usual. If the mine sits inside another region that has its own flags, raise the mine's priority: `/rg setpriority mine_quarry 10`.
5. Set the block mix. Weights are relative, so these two make 70% stone and 30% coal ore:
   ```
   /thebox mine addblock quarry stone 70
   /thebox mine addblock quarry coal_ore 30
   ```
   `/thebox mine removeblock quarry <block>` takes a block out of the mix.
6. The name players see:
   ```
   /thebox mine setname quarry <gray>The Quarry
   ```
7. Where players teleport to. Stand **outside** the mine (by default it's the center of the top):
   ```
   /thebox mine settp quarry
   ```
8. Automatic resets, by time and/or when a percentage has been mined (0 turns either off):
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

Check everything with `/thebox mine list`.

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
   You can create more markets later; with several, `/market` shows a menu to pick one.
3. **Create the stalls.** For each one, select with `//wand` only the **empty space players may build in** (above the floor, inside the walls), then:
   ```
   /thebox market stall create main s1 100
   ```
   `100` is the rent per day. TheBox refuses a selection that has blocks in it or overlaps another stall, and creates the WorldGuard region `thebox_stall_main_s1`. The floor and walls stay outside the region, so renters can't break them.
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
- `/market` takes them there. Standing in a stall, `/market info` shows its rent, and `/market rent [days]` rents it (one stall per player per market).
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

**Admin tools:** `/thebox market stall evict main s1` clears a stall into its renter's impound right away. `stall setrent` changes the rent, `stall delete` removes a free stall.

## 11. Prestige rewards

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

## 12. Food

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

## 13. Check that it works

As a **non-op** player:
1. Join, then in the console run `lp user <name> info`. The player should be in groups `default` and `a`. `/balance`, `/baltop` and `/pay` work.
2. Breaking or placing blocks outside a mine is blocked (WorldGuard says so in chat).
3. `/mines` lists the unlocked mines; `/mines quarry` teleports there. Breaking blocks in the mine works; placing them doesn't. PvP works in every mine except the starter mine, and nowhere else. After hitting another player, `/mines` is blocked for 15 seconds, and logging out in that time kills you.
4. Mine some blocks, then run `/sell all` (or `/sell hand`, `/sell worth`).
5. `/ranks` shows the ladder with the player's rank marked and the next rank's cost. `/rankup` buys the next rank (`/rankup max` buys as many as you can afford). The whole server sees an announcement.
6. Walking into a locked mine is blocked with "You haven't unlocked … yet."
7. At the top rank, `/prestige` explains what prestiging does, and `/prestige confirm` does it: the player goes back to `a`, joins `p1`, and the server sees an announcement.
8. `/market`, stand in a free stall, `/market rent`. Building works in your stall and nowhere else in the market. With `day-length-minutes: 2` and auto-renew off, the stall freezes after 2 minutes; after the grace period its contents show up in `/market claim`.

## Network (later)

To share ranks and prestige across several servers ([LuckPerms network guide](https://luckperms.net/wiki/Network-Installation)):
- Every server's LuckPerms uses the same database: set `storage-method` to `mariadb` (or MySQL, PostgreSQL, MongoDB) and fill in the connection details.
- Leave `messaging-service: auto`; with a SQL database it syncs changes between servers on its own.
- Behind a Velocity proxy, set up [modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding/) so every server sees players' real UUIDs.
- Optionally set `server:` in each LuckPerms config (e.g. `prison`) to limit permissions to one server with `server=prison`.
- Move existing data with `lp export <file>` before switching and `lp import <file>` after. Avoid `--upload`, which sends the data to LuckPerms' web service.
- Balances don't transfer: EssentialsX keeps them per server.
