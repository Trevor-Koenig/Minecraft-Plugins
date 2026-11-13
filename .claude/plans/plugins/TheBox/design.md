# TheBox — Design

Status: **draft**. A living document, updated as decisions are made.

The admin setup guide is [plugins/TheBox/README.md](../../../../plugins/TheBox/README.md). Update it whenever commands, permissions or setup steps change.

## 1. Goals
- Deliver a classic prison-server experience first: mine → sell → rank up → prestige.
- Then adjust based on what players actually want.
- Integrate existing plugins for infrastructure; build the prison-specific gameplay ourselves.
- Keep features in separate packages (`mines`, `ranks`, `sell`) so they could be split into separate plugins later.
- Start each feature with the simplest version that works; add abstractions only once the code grows.

## 2. Concepts

### Mine
A region that refills with a weighted mix of blocks.

| Property | Notes |
|---|---|
| `id` | Short, lowercase, no spaces (`quarry`, `iron_depths`). Used in commands and config. |
| `displayName` | What players see; supports colors (MiniMessage). Can be renamed freely. |
| `region` | A cuboid in one world, defined from the admin's WorldEdit selection. |
| `blocks` | Weighted block mix, e.g. stone 70, coal_ore 20, iron_ore 10. |
| `reset` | A timer and/or "when X% has been mined." |
| `teleport` | Where `/mine` sends players. |
| `permission` | `thebox.mine.<id>`, required to enter or teleport. Mines are **not** tied 1:1 to ranks. |
| `level` | A number that orders the mines; 0 means not set. `/thebox mine setlevel`. A *player's* level is the highest level among the mines they can enter. PvP uses it (see PvP). |

Only admins create and edit mines. Players just teleport to them.

### Rank ladder
- Ranks **A–Z**, in order. Each rank's id is a letter, but like mines it can have a `displayName` (MiniMessage), e.g. A = "<gray>Shulker Larva".
- Each rank has a `cost` (set by hand) and **unlocks** (permissions, through LuckPerms).
- Prestige: after Z, the player can prestige. That resets them to A and adds 1 to their prestige level, which earns a chat tag and cosmetic effects (see M3).
- *(planned 2026-11-08)* `/ranks` shows what each rank unlocks: a short text per rank in `ranks.yml`. Unlocks are LuckPerms permissions, which players can't see, so this turns each rank-up into a visible goal, and shows which ranks unlock nothing yet.

### Unlocks = permissions
A rank grants permissions (or a LuckPerms group), and every feature checks those permissions:
- Mine access → `thebox.mine.<id>`
- Markets / shops → the shop plugin's permissions
- Building areas → WorldGuard region flags or a plot plugin's permissions
- Teleports → EssentialsX warp permissions

TheBox doesn't need to know about markets or plots. This also lets external sources grant unlocks, such as staff or a store plugin.

### Market
A player-run market area where players buy or rent **stalls**, build their store, and run **shops** that sell or buy items.
- **Stalls:** protected regions that players **rent** (not buy), limited to **one stall per player per marketplace**. Renters can build in their stall (WorldGuard region membership). Rent is an ongoing money sink, which prison economies need, and stalls of inactive players free up on their own when rent lapses.
- **Shops:** chest shops inside stalls that sell resources and tools to players, or buy them, paid through Vault. **QuickShop-Hikari** supports 26.3 (checked 2026-11-08; ChestShop lists up to 26.2).
- **`/market`:** teleports players to a marketplace. **Several marketplaces** must be supported from the start, even though the server starts with one (decided 2026-11-08): with one, `/market` goes straight there; with several, it opens a chest menu to pick one. Each stall belongs to one marketplace.
- **Settings, decided 2026-11-08** (all configurable):
  - Admins create stalls from a WorldEdit selection and set each stall's daily rent.
  - Rent is per real-world day; players can prepay up to 7 days, and rent auto-renews from the balance.
  - Lapsed rent: 3-day grace period (stall frozen), then eviction to the impound; unclaimed impound is auto-sold after 30 days.
  - Anyone can visit with `/market`; renting requires `thebox.market.rent` (granted to a rank).
  - Renters can trust up to 2 helpers to build.
  - Leaving early (unrenting): contents go to the impound, no refund.
  - QuickShop shops are allowed only in a stall you rent (or a cell with the shop upgrade).
  - Players rent by standing in a free stall and running `/market rent`.
- **Decided (2026-11-08): TheBox custom-codes all market logic** (stalls, renting, eviction, `/market`); only the shop interfaces come from QuickShop-Hikari. The usual stall-rental plugins look unmaintained anyway (AreaShop's last release was 2019; AdvancedRegionMarket's was March 2025). Stalls are the same machinery as cells (a WorldGuard region a player rents or owns, plus payments), so one shared TheBox module serves both.
- **When rent lapses** *(decided 2026-11-08: the impound flow; grace period and impound expiry lengths still to pick)*: prepaid rent that auto-renews from the balance, warnings, then a grace period where the stall stays intact; after that, eviction moves everything (container contents, the blocks themselves as items, item frames and armor stands) into the player's impound (`/market claim`), and the stall resets to empty. Optionally, unclaimed impound is auto-sold at market value after a while (unpriced or customized items count as $0).
- Clearing a stall with WorldEdit doesn't fire block events, so TheBox must remove that stall's QuickShop shops through QuickShop's API, or QuickShop would keep "ghost" shops.
- ~~*(idea, 2026-11-08)* A better-located marketplace unlocked at a higher rank~~: dropped 2026-11-10. The server has one player market plus the server's booths (see `README.md`, "Server booths"); there are too few players to split them. `thebox.market.rent` stays a single permission. *2026-11-12:* four rank-gated store markets (server booths only) are planned for pickaxes; see M5 step 10.

### Cell
A player's own personal space, a classic prison feature. **Redesigned 2026-11-09**; what changed from the 2026-11-08 design is at the end of this section.
- **Cell blocks are built by hand, and each cell is defined like a stall.** The admin builds each tier's cell block as solid blocks. For each cell they stand in front of it and run `/thebox cell create <tier> <cell> here`: TheBox works out the **plot** (the solid space for the tier's largest room, plus the front wall) and the **front space** from where they stand, and carves the starting room. For unusual shapes they can select the plot (`create` without `here`) and then the front space (`setfront <cell>`) instead.
- **Front space:** an empty strip in front of the cell, touching one face of the plot; that face becomes the front wall. The owner can build in the front space and the front wall, e.g. an iron door with a button. Visitors arrive here.
- **Tier fronts** *(decided 2026-11-10)*: each tier has a default front (door, window, buttons), designed once on one cell by the admin and put back every time a cell is rebuilt. Owners can change it; only what they add goes to their impound.
- **Growth:** within a tier, an upgrade carves the next size **away from the front** (sideways, backwards and up), so the front wall and door never move. Solid space that isn't carved yet stays outside the cell's WorldGuard region, so nobody can dig themselves a free upgrade.
- **Sizes and prices** per tier in `cells.yml` (width × depth × height). Defaults:

  | Tier | Sizes (rank that unlocks them) | Height |
  |---|---|---|
  | 1 | 3×3 (A, $2,000) → 5×5 (D) → 7×7 (G) | 3 |
  | 2 | 11×11 (J, move) → 13×13 (M) → 15×15 (P) | 6 |
  | 3 | 19×19 (S, move) → 21×21 (V) → 23×23 (Y) | 8 |

  - Buying size *n* (2–9) needs `thebox.cell.size.<n>`, granted to its rank group like mine unlocks, so `/ranks` can list it.
  - Default prices: about the cost of the rank that unlocks the size *(2026-11-12: tier moves no longer cost twice that, which had made size 7 cost more than size 8)*. The first cell costs $2,000 *(decided 2026-11-10)*, a small first goal so players play a while before they get one; `/cell` shows the price and `/cell buy` pays. Cells add about 25 hours on top of the 100-hour ladder (see Economy and pacing). They're optional: players who invest in cells reach Z later.
- **Tier moves are by hand** *(decided 2026-11-09)*. Buying size 4 or 7 gives the player a free cell in the next tier's block. They keep the old cell for 7 days (`/cell old`) to carry things over; shulker boxes are the moving boxes. On `/cell release`, or when the time is up, whatever is left goes to their **impound** through the stall clearer (Silk Touch drops, containers emptied, item frames and armor stands), and the old cell is reset to its starting room and freed. Nothing is copied, so nothing can duplicate.
- **Doors need no code:** WorldGuard stops non-members using doors, buttons, pressure plates and levers inside a region ([What's protected](https://worldguard.enginehub.org/en/latest/regions/scope/)), so only the owner and trusted helpers can open a cell door. **Never set `use allow` on `__global__`:** its flags also apply inside regions that don't set them, so it would open every cell door.
- **Commands:** `/cell` (home: inside the room, just behind the front wall; the same goes for `/cell buy`, `/cell old` and tier moves. From 2026-11-10 to 2026-11-11 it went to the front instead, in case the door couldn't be opened from inside. That was reverted because the tier front can include a button inside the room), `/cell visit <player>` (to a free spot in their front space, e.g. straight from a mine. If the owner has built over the middle, `Cell#standingSpot` finds another spot, and the visit is refused if there's no room), `/cell upgrade` (also a chest menu), `/cell trust|untrust`, `/cell old`, `/cell release`. PvPManager blocks commands during combat, so `/cell` can't be used to escape a fight (check in testing).
- **Prestige:** cells survive prestige. Seasons wipe them (see Seasons).
- **Still true from 2026-11-08:** one cell per player; *(changed 2026-11-10: players buy their first cell with `/cell buy` instead of getting one free on join; if none is free, admins are warned)*; no hostile mob spawning (TheBox sets `mob-spawning deny` on cell regions); cells of inactive players are kept for now; later upgrades besides size: a cell shop and more trusted builders.
- **What changed on 2026-11-09:** tier moves are by hand instead of a WorldEdit copy, so the riskiest code is gone; sizes come from `cells.yml` instead of one selection per size; cells grow away from a front space instead of in every direction, so they can have real doors; the visit lock is gone, because visitors arrive outside the door. The "proposed simplification" (no tier moves) wasn't adopted.

### PvP
- PvP is allowed **in mines** only. That's WorldGuard config: `pvp deny` everywhere else (`__global__` and spawn), `pvp allow` in mine regions.
- **Combat logging** (quitting or teleporting out mid-fight) needs a plugin. **PvPManager** supports 26.3 (checked 2026-11-08); its combat tag can block commands like `/mine` during a fight.
- **Decided 2026-11-08:** PvP in **every mine except the starter mine** (the one rank `a` unlocks), so new players are safe. Dying drops items as usual (vanilla; `keepInventory` stays off). Mine resets during fights need no special handling.
- **Only in your own mine** *(planned 2026-11-08; testers worried that higher-rank players would come down to lower mines to kill weaker ones)*. Inside a mine, PvP only happens between players whose level equals the mine's level (see Mine `level`). Decided 2026-11-08:
  - **Rules use levels, not single mines,** so several mines can share a level with no special case.
  - **A mine with level 0 (not set) has no PvP,** because it's nobody's level. Forgetting a level makes a mine peaceful, not a place for ganking. After deploying, PvP is off in every mine until its level is set.
  - **WorldGuard decides where PvP can happen; TheBox only removes it.** WorldGuard checks the `pvp` flag at *both* the victim's and the attacker's location (verified in its `RegionProtectionListener`), so a hit only lands when both stand in a `pvp allow` region. TheBox finds the mine from its own cuboid at the victim's location. The two match because README.md creates each `mine_<id>` region from the same selection. **If a mine's WorldGuard region is ever bigger than the mine, the extra strip has PvP without the level rule.** For now, keep them identical and warn about it in `README.md`; the lasting fix is the M1 follow-up idea of `/thebox mine create` making the region itself.
  - Considered and not chosen: comparing the two players' mine access (PvP only between players who can enter exactly the same mines), which needs no levels or configuration. Also checked, and ruled out: doing it without code. PvPManager's `pvpmanager.nopvp` was removed in v3.16, and WorldGuard region groups would need two regions per mine, kept in step with the ladder by hand.

  It works both ways: a higher-rank visitor can neither hit nor be hit there, so low-rank players can't harass someone who can't fight back. WorldGuard's `pvp` flag can't do this because it doesn't know ranks, so it's a TheBox listener.

### Gang *(paused, 2026-11-08)*
Paused by the owner; not part of the first release. Notes kept for later. A group of players who **pool resources** (a shared gang bank through Vault) and form **alliances** with other gangs. Likely also: no friendly fire between members and allies, gang chat, a gang leaderboard. SimpleClans, the established plugin for this, last released in 2023, so gangs probably belong in TheBox.

### Black market *(pinned, 2026-11-08)*
An idea to come back to later; nothing designed yet.

### Menus
Decided 2026-11-08: players use **chest menus** (clickable inventory GUIs) where they fit: choosing a marketplace, the impound, cell upgrades. Actions also have commands. One small, reusable menu helper serves every feature.

### Chat
Decided 2026-11-08: **TheBox formats chat itself** with Paper's chat API: `[P2] [C] Name: message` (prestige, rank; a gang tag if gangs return). *Built 2026-11-13 (M3 step 8):* the rank part is the rank's display name (owner), so renamed ranks show their name and colors.

### Food
Decided 2026-11-08: no TheBox code. For launch, EssentialsX covers it (setup in `README.md`, "Food"):
- A free food kit (`/kit food`) with a cooldown, granted to rank `a`.
- `/feed` as a mid-ladder rank unlock (`essentials.feed`).

Later, food can become part of the economy (a money sink in the market, farms in cells). If mines ever need it, WorldGuard's `feed-*` region flags can feed players automatically inside a region.

### Starter gear store
Planned 2026-11-08 (from the first player test): a store at spawn where players buy a default set of gear at fixed prices. **No TheBox code:**
- A hall at spawn, protected by WorldGuard like the rest of spawn. Not a TheBox marketplace, so there are no stalls to rent.
- QuickShop-Hikari admin shops: create a shop as usual, then run `/quickshop unlimited` while looking at it, for endless stock paid to the server ([Create an Admin Shop](https://quickshop-community.github.io/QuickShop-Hikari-Documents/docs/guides/getting-started/create-admin-shop)). The `unlimited-shop-owner-change` settings in QuickShop's `config.yml` can move them to a server account. A shop sells the exact item it was created with, so enchanted gear works.
- **Pricing rules:** every item must cost more than `/sell` pays for it in `prices.yml`, or buying and selling in a loop makes free money. Only *selling* admin shops; a buying one would be a second `/sell` with its own prices.
- **Prices** *(first draft 2026-11-10, at the money scale in Economy and pacing)*:
  - Pickaxes lose 1 durability per block, so they're consumables. Each costs about $0.32 per block of durability, which is 8% of what a block earns. So every pickaxe costs the same per block, and better ones pay for themselves by mining faster. Unbreaking II makes a tool last 3× as long, III 4×.
  - Players can craft plain gear from mined materials, so plain items cost about 2× their materials' sell value. Enchantments are what the store adds. What a PvP death costs is set by material prices, not by the store.
  - Players start with $0 (EssentialsX `starting-balance`), so EssentialsX Spawn's first-join kit (`newbies.kit`) gives a stone pickaxe (see Seasons).

  | Item | Price |
  |---|---|
  | Stone pickaxe | 40 |
  | Iron pickaxe, Unbreaking II | 240 |
  | Diamond pickaxe, Efficiency II, Unbreaking III | 2,000 |
  | Iron sword / shield | 120 / 60 |
  | Iron helmet / chestplate / leggings / boots | 240 / 400 / 360 / 200 |
  | Torch / chest / crafting table / furnace | 4 / 80 / 40 / 40 |
  | Bread / cooked salmon / steak | 8 / 12 / 16 |
  | Golden apple | 800 |

  - **Food:** mining barely uses any: 0.005 exhaustion per block, about 4–5 food points an hour. The free kit covers that, so store food is for fights, where healing costs 1.5 food points per HP. Ordinary food costs about $0.80 per point of hunger plus saturation. Beetroot isn't sold, because the test server's kit gives it. The store's prices cap what players can charge for food in stalls: if cell farms come, raise store food or stop selling steak and salmon.
  - **Golden apples** (8 gold ingots and an apple, about 2× material) are the PvP cost. A fixed price gets relatively cheaper as incomes rise, so revisit it with PvP balance.
  - Iron and gold gear smelts into nuggets, so keep nuggets unpriced.
  - Left out until rank costs follow the formula: diamond armor, netherite, Efficiency IV–V, Protection, Fortune. *Superseded 2026-11-12:* vanilla pickaxes for mines A–F, then god-tier pickaxes from G/H, sold in rank-gated store markets and given in rank-up kits (see Economy and pacing).
- Chosen over a GUI: no code to maintain, players learn the shop interface they'll use in the market, and the world gets a landmark. An item that opens a GUI was ruled out because items drop on death in PvP mines. Revisit with a TheBox chest menu only if gear needs to be locked by rank.

### Seasons
Agreed 2026-11-08: a new season resets all player progress. **A checklist in `README.md` first, not a command:** most steps already have a tool, and a one-shot command that can't be undone is the riskiest thing to build. Automate the annoying parts after a season or two.

**Written 2026-11-10 as `README.md` section 16.** A season makes everyone a new player: rank, prestige, balance, cell, inventory and newbie protection all reset. Setup stays: mines, cells and their prices, sell prices, groups, staff permissions and the server booths.
- **Where player data lives.** Paper can't edit offline players, so most of it is deleted with the server stopped. An in-game version would have to reset each player at their next join.
  - Vanilla: `world/players/data`, `advancements` and `stats`. Minecraft 26.1 moved them there ([26.1 changelog](https://minecraft.wiki/w/Java_Edition_26.1)).
  - EssentialsX: `userdata/` (balances, used kits, homes). EssentialsX detects first joins from its own data, so deleting it gives everyone the starter kit again.
  - PvPManager: `database.db`. Newbie protection only goes to players with default PvPManager data who also haven't played before (`hasPlayedBefore()`). `/newbie add` only takes online players.
- **Ranks and prestige:** `lp bulkupdate users delete "permission ~~ group._"`, and the same with `group.p_` ([Bulk editing](https://luckperms.net/wiki/Bulk-Editing); `~~` is SQL `LIKE`, where `_` is one character). Use `users`, not `all`, because the rank groups inherit each other. TheBox puts players back in `a` at their next join.
- **Decided 2026-11-10: delete EssentialsX `userdata/`**, for a real first join, at the cost of homes, nicknames, mail and mutes. The alternative is `eco reset ** 0` (`**` includes offline players; it resets to `starting-balance`). An [old bug report](https://github.com/EssentialsX/Essentials/issues/5305) says `**` failed on some builds.
- **Decided 2026-11-10: prestige resets with the season**, as part of the full reset (replaces the earlier lean toward keeping it).
- **Stalls:** `/thebox market wipe` *(built 2026-11-10, untested)* clears every rented stall without impounding, frees it, then empties the whole impound, which also holds evicted cells' leftovers. Rented stalls in unloaded worlds are skipped and reported. The store's admin shops aren't in stalls, so they survive. Because this runs in-game, the checklist needs only one restart: delete files while stopped, then run the wipes.
- Cells: `/thebox cell wipe` resets every cell to its starting room, with no impound (M6). Mines: reset each one.
- **Starter kit and first spawn** *(decided 2026-11-11)*:
  - The kit is EssentialsX Spawn's `newbies.kit`, the only way EssentialsX hands out a kit by itself. Its spawning is switched off (`respawn-listener-priority: none`, `newbies.spawnpoint: none`), so it doesn't overlap with Multiverse.
  - The kit is "given regardless of cost and permissions", so nobody gets `essentials.kits.starter`; otherwise it could be claimed twice.
  - Where new players arrive is Multiverse's `spawn.first-spawn-override`. Without it, vanilla puts first-time players on the highest block above the world spawn, which is the spawn building's roof.
  - Fallback if Multiverse's first-join detection fails (see below): EssentialsX Spawn's `newbies.spawnpoint` with `/setspawn newbies`. EssentialsX detects first joins from its own data.
- To test: PvPManager, and Multiverse's first-spawn-override, rely on `hasPlayedBefore()`, which an EssentialsX source comment calls unreliable on current server software. If brand-new players land on the roof or don't get newbie protection, that's the cause. The fixes would be Multiverse's `join-destination` (everyone goes to spawn on every login) and TheBox running `/newbie add` on first join.
- On the network this only touches the prison server. LuckPerms is shared, but the bulk updates only match the prison's one-letter rank groups and `p` groups.

### Economy and pacing
Decided 2026-11-09, for a small server (3–10 players):
- **A first run from A to Z takes about 100 hours of mining; A–C is a tutorial.** Time per rank-up: B 15 min, C 30 min, D 1 h, then 18 minutes more for each rank, up to 7.6 h for Z. Players reach M after about 24 hours and R after about 47. Cells add about 25 hours on top (the 100 hours exclude them; longer is preferred to shorter, owner 2026-11-12).
- **Rank cost = hours for that rank × income per hour of the mine before it.** Measure each mine's income: mine for 5 minutes with realistic gear, `/sell all`, ×12. Aim for each mine to pay about 1.15× the one before. *Defaults since 2026-11-12:* `ranks.yml` is first written with the formula's costs: mines A–F with vanilla pickaxes from $14,400 an hour, then god-tier pickaxes from G (b $3,600, h $360k, z $46M, $213M in all; the first formula ended at $3.1M, the old placeholders were +4000 per rank). Cell sizes default to about the cost of their unlocking rank. Both are listed in `README.md`, sections 6 and 11, with `setcost` commands for older servers.
- **Money scale** *(decided 2026-11-10)*: the starter mine pays about $4 a block, about $240 a minute. That's 4× the first draft, so every price is a whole number, and the first run ends in the millions (under the formula, Z costs about 7.6 h of the Y mine's income, about $3M). Scaling everything by the same factor changes nothing in play, so it was settled before players have balances.
- **Sell prices** *(first draft 2026-11-10; suggested values in `README.md`, section 7)*: set once, then tune each mine through its block mix. Changing a price changes every mine that drops the item.
  - Filler: cobblestone and stone $2, cobbled deepslate and deepslate $3. Stone matches cobblestone so Silk Touch and smelting add nothing. Deepslate pays 1.5× because it takes about 1.5× as long to mine: hardness 3 against stone's 1.5, plus a 6-tick pause after every block ([Breaking](https://minecraft.wiki/w/Breaking)).
  - Ores: coal $12, raw iron $20 (ingot $24), redstone $6 a dust (4–5 per ore), raw gold $40 (ingot $48), diamond $200, emerald $320. Smelting adds 20%: worth doing, not a business.
  - The top mine has to reach about 29× the starter mine (1.15^24). A late mine of half deepslate, 30% deepslate diamond ore and 20% deepslate emerald ore averages about $125 a block, about 33× the starter mine's $3.75. Deepslate ores take about twice as long to mine, though.
  - Unpriced: logs and planks (tree farms in cells), all food (the free kit would become money), nuggets and storage blocks, and ore blocks (so Silk Touch on ores is worthless).
- **Income levers to keep under control:**
  - Fortune III averages 2.2× drops for coal, iron, gold, diamond and emerald, and 1.33× for redstone ([Fortune](https://minecraft.wiki/w/Fortune)), so it roughly doubles income in ore-heavy mines. Measure incomes without it and keep it out of the store. Decide later whether it becomes a late, expensive upgrade.
  - No lava for players. It allows cobblestone generators in cells (weak: about half the starter mine's income), and obsidian for enchanting tables.
  - No villagers (iron golem farms, librarians selling Fortune books) and no fishing rods (fishing loot includes enchanted books).
- **Mines (planned):** one per rank (26), a wood mine, a lootbox mine, and event mines (setup in `README.md`, section 15).
  - PvP bands with mine levels: A–C no PvP; D–H level 1, I–M 2, N–R 3, S–W 4, X–Z 5.
  - Wood mine: logs, level 0, unlocked at `a`. Logs unpriced or very cheap, so it doesn't compete with the rank mines.
  - Lootbox mine: special-item drops are planned (an admin-set item list). Drops should check the player still has access when they break the block, since a player on top of the mine can break blocks below.
- **Few players means thin player trading:** `/sell`, the store and rank-ups carry the economy. Suggested: keep stall rent low and the grace period long (`grace-days: 7`, `max-prepay-days: 14`).
- **Decided 2026-11-12 (owner), for the second draft of prices and mixes** (draft in progress, not yet in `README.md`):
  - **Ores overlap:** each mine mixes several ores, and older ores (iron, lapis, gold) stay in later mines for variety. Diamonds and emeralds pay more and are rarer.
  - **Pace:** the owner reached B and bought a cell in about an hour at the first draft's prices, slower than the model assumes. Longer runs are preferred to shorter, so the model isn't slowed down to match.
  - **Potions become a mechanic** (brewing and crafting chains); not designed yet. Vanilla can't brew Haste, but Paper can add brewing recipes (`PotionBrewer#addPotionMix`), so Haste is a choice. It matters: Haste II with Efficiency V breaks stone instantly.
  - **Gear is gated by store markets:** several store markets unlocked by rank, so players can buy a little ahead. Needs per-market permissions. Breakable picks come first, unbreakable ones in later markets. The best picks come rarely, from rank-up and prestige kits, which needs rank-up rewards in TheBox. Picks above vanilla levels change the speed model: Efficiency VI breaks stone instantly.
  - **Cells are workshops:** crafted items (ingots, armor, pistons…) sell for somewhat less than 2× their raw materials, so cells, cell upgrades and autocrafters pay off. Selling raw must stay a fully viable route. Markups decided the same day: raw materials × 1.25 smelted, × 1.5 crafted from one material, × 1.8 components from several materials; recipes that can be undone stay exact; wood counts as free (`README.md` section 7). At 1.8× on everything a crafter would reach Z in about 72 hours with every cell bought, against 100 selling raw; with the tiers, about 85–95.
  - **Prices and all 26 mines written to `README.md`** (sections 7 and 9), with the pickaxe track below.
  - **God-tier pickaxes from G/H** (owner): past vanilla levels, so most blocks break instantly. Players feel the progress, block choice is free (hardness stops mattering), and obsidian can go in mines so players can enchant, since vanilla levels are below the god picks. Modeled consequences:
    - Efficiency IX breaks hardness 3 instantly (stone, deepslate, stone ores, end stone), XII hardness 5 (deepslate ores, storage blocks). Obsidian (50) never does: 20 ticks with Efficiency X.
    - Speed then caps at how fast players aim, about 210 blocks a minute modeled (5 a second while mining, 70% of the time); measure it once a god pick exists. Mines G onward would pay 3–4× their targets, and 4–7× with Fortune III.
    - Past instant, more Efficiency does nothing, so later god picks need another axis: Fortune levels (III ×2.2, V ×3.1, X ×5.6 ore drops), unbreakable, or custom area-breaking enchants (TheBox code).
    - Enchanting tables give everyone Fortune III and PvP enchants. Books need leather, which has no source (no animals), so the store would sell books or leather, and bookshelves (45 books for level 30) become a lever. A high `repair_cost` item component on god picks stops anvils adding to them.
    - **Decided (owner):** rank costs absorb the speed, by the formula, so mines stay full; Fortune is the god picks' upgrade path; every rank from G adds something, with Efficiency X not early.
    - **Pickaxe track** (one gain per rank, in `README.md` section 9): G Efficiency VI + Fortune III + Unbreaking III; Fortune IV–VI at H–J; K Unbreakable; Fortune VII–VIII at L–M; N Efficiency IX; Fortune IX–XV at O–U; V Efficiency XII; Fortune XVI–XIX at W–Z. Only VI, IX and XII change anything in stone and deepslate mines with ordinary ores; VII, VIII and X matter only with concrete, with cobblestone and bricks, and with cobbled deepslate and deepslate bricks, so they're kept for themed mines.
    - **Mines G–Z** grow income at least 1.2× a rank and value per block at least 4% a rank. Ore stays at 13–25%, because Fortune makes each ore worth more. G–M stone with obsidian (0.5%), N–U deepslate with ordinary ores (instant at IX), V–Z deepslate ores (instant at XII) with storage blocks as 0.5% jackpots. 1.25× growth would push late mines to 43% ore.
    - **Costs** (`RankManager.DEFAULT_COSTS`, cells follow): H $360k (6.5× G, the god-pick jump), then 1.2–1.5× a rank, Z $46M, ladder $213M. Cells about 25 hours.
    - **Decided (owner), later the same day:** picks are sold in store markets, grouped a little, each priced from its rank's cost so it's mainly worth buying at that rank. Buying early stays possible: freedom over gating. No `/autosell` for now (`/sell all` is enough); keep the idea in case playtesting asks for it. God picks survive prestige, because prestige keeps inventories and cells.
    - **Modeled consequences** (a player who spends to finish soonest, 210 blocks a minute):
      - The first god pick earns 5–6× vanilla gear in mines A–F, so its price alone can't hold it back: at its rank's cost it's still bought at C. It needs a hard gate at G (a market that opens at G, or the G kit).
      - A breakable god pick lasts 6,244 blocks (1,561 × 4 with Unbreaking III), about 30 minutes. At a rank's cost, G–J picks would cost more an hour than they earn. Price them by wear (8% of what a block earns: about $9k–$18k), or raise their durability with the `max_damage` item component.
      - Inside a market, buying the best pick you can afford straight away beats buying each tier at its rank, so the number of markets is the number of pick upgrades players feel. Even with a market per rank, one Fortune level (5–15% more income) isn't worth 25–100% of a rank's cost, so tiers get skipped.
      - Ladder time with picks bought: four markets (opening at G, K, N and V) at a quarter of the rank's cost take about 100 hours; at the full rank cost, about 130.
      - The kept pick shortens prestige 1 from 150 to 90 hours at `prestige-multiplier` 0.5 (B–G take 20 minutes); 0.7 gives about 100. The kept balance matters more: a player can bank prestige 1's $320M at mine Z ($7.4M an hour, about 43 hours), then prestige and buy every rank at once, which beats climbing.
    - **Decided (owner), third round:** four store markets of server booths, opening at G, K, N and V (which also gates the first god pick), with each pick at its rank's full cost; four markets leave room to tune prices per market. Prestige resets the balance (see M3 step 10).
    - **Modeled with full-cost picks:** H–J breakable picks don't pay for themselves: they use up 48–64% of what they earn with `max_damage` 8,000 and 24–32% at 16,000. They only become worth buying at about 32,000, when they last about 14 hours, longer than players use them before K. The G pick works: at $55k with `max_damage` about 8,000 it lasts about 3.5 hours at mine G and costs about 9% of what it earns (the store's 8% rule). A player who buys well buys about five picks (G at G, L at K, N and S at N, V at V) and reaches Z in about 140 hours. W–Z are never worth their price, which suits rare kits.
    - **Still open:** what market 1 sells (just the G pick, or H–J too, with more durability); which tiers kits give; prestige-kit picks only matter if they beat the Z pick (Fortune XX…). The 210 blocks a minute needs measuring.
  - **Themed mines, eventually,** with fillers other than stone (sandstone, netherrack, end stone…). A filler's hardness sets how much digging there is per ore, and its price sets how much of the income comes from digging. Soft fillers break instantly once the pick is fast enough (netherrack from diamond Efficiency II, sandstone from Efficiency IV).
- **Open:** `prestige-multiplier` (with the pick kept, 0.5 makes a second run about 90 hours and 0.7 about 100; see the god-pick notes), and season length, which should be long enough for regulars to reach Z.

## 3. Integrations
| Plugin | Role | Dependency |
|---|---|---|
| WorldEdit | Region selection for mines; possibly fast fills for resets | `depend` (added) |
| Vault + an economy plugin (e.g. EssentialsX) | Balances: sell income, rank-up costs | `depend` (from M2: Selling) |
| EssentialsX Spawn | Only the starter kit on a player's first join (`newbies.kit`). `respawn-listener-priority: none` and `newbies.spawnpoint: none` keep it out of spawning *(decided 2026-11-11)*. It must be the same build as EssentialsX. | Runs alongside TheBox; no code dependency |
| LuckPerms | The rank ladder (a track), each player's rank and prestige, and the permissions ranks grant | `depend` (from M3: Ranks) |
| PlayerParticles | Cosmetic particle effects (wings, halo, …) unlocked by prestige, one permission per style | Runs alongside TheBox; no code dependency. Latest build lists up to 26.2, so check 26.3 support first |
| WorldGuard | From launch: protects the world (`__global__` passthrough deny) and lets players break blocks in mines (a `mine_<id>` region per mine with `block-break allow`). Who may *enter* a mine stays TheBox's job (rank permissions). Setup in `README.md`. | Runs alongside TheBox; no code dependency yet |
| WorldGuard API | Rentable market stalls and cells: TheBox creates regions and adds renters/owners as members (stall plugins look unmaintained, see Market) | Code dependency (from M5) |
| QuickShop-Hikari | Player chest shops in stalls (and maybe cells) | Runs alongside TheBox; probably no code dependency |
| PacketEvents | QuickShop-Hikari's floating item displays (or ProtocolLib); without either, shops work but show no display | Optional, no code dependency. 2.14.0 adds 26.3 |
| PlaceholderAPI | TheBox registers `%thebox_earned%` (lifetime `/sell` earnings) for leaderboard and hologram plugins; rank and prestige placeholders for a sidebar would go in the same expansion | `softdepend` (from 2026-11-13); `TheBoxExpansion` is only created when it's installed |
| ajLeaderboards + DecentHolograms | The top sellers hologram at spawn, from `%thebox_earned%` (setup in `README.md`, section 7) | Optional, no code dependency. Both list 26.3 (ajLeaderboards 2.11.0, DecentHolograms 2.10.2; checked 2026-11-13) |
| PvPManager | Combat tagging, combat-log handling | Runs alongside TheBox (from M4) |
| Multiverse-Core *(optional)* | Several worlds on the prison server (mines, market, …). Loads before TheBox (`softdepend`) so mine and market worlds exist when TheBox loads its files. Keep its per-world PvP on in worlds with PvP mines. Also handles spawning: respawns, and first joins (`spawn.first-spawn-override`). | `softdepend` |

Shops (2026-11-08): QuickShop-Hikari over EssentialsX's sign shops. EssentialsX's `[Trade]` signs keep their stock in the sign rather than in a chest, its `[Buy]`/`[Sell]` signs are server shops, and neither has an API for closing or removing shops when rent lapses.

Economy: **decided 2026-11-08, no custom economy.** We considered giving TheBox its own economy, to keep prison balances separate from other game modes. Every plugin that touches money (QuickShop-Hikari, EssentialsX's `/pay`, `/bal`, `/baltop`) finds the economy through Vault, and a server has one Vault economy. A private TheBox economy would cut QuickShop off from prison money; registering it with Vault would replace EssentialsX's for every plugin, so nothing would be separate. Separate balances come from running the prison as its own server in a Velocity network (decided; see [server architecture](../../server-architecture.md)).

## 4. Commands and permissions
Compare against existing prison servers before finalizing.

**Players**
- `/mine`: menu of your mines (straight there with only one), `/mine <id>`, `/mine help` (renamed from `/mines` 2026-11-12)
- `/rankup` (buys the next rank), `/rankup max`, `/rankup list` (the ladder; `/ranks` until 2026-11-12), `/rankup help`, `/prestige`
- `/sell all`, `/sell hand`, `/sell worth`, `/sell top` (lifetime earnings board); `/autosell` only if playtesting asks
- `/market`: menu of unlocked markets (straight there with one), `/market <id>`, and the stall subcommands
- Balance, pay and baltop come from the economy plugin, not TheBox.

Decided 2026-11-08: **every player command gets its own permission node**, `default: true`, so it works out of the box and admins can limit it with LuckPerms contexts (`world=…` on one server, `server=…` on a network) instead of TheBox hard-coding world names. See "Per-command permissions" in the milestones and [server architecture](../../server-architecture.md).

**Admins**: all under one root, `/thebox` (decided 2026-11-08). Player commands stay short and top-level, like on other prison servers. Grouping admin commands gives one autocomplete entry point, frees the short names for players (`/mine` is the player command since 2026-11-12), and keeps `/sell` player-only.
- `/thebox mine create|delete|list|reset|addblock|removeblock|setresettime|setresetpercent|setlevel|setname|settp|reload`
- `/thebox price set|remove|list|reload`
- `/thebox rank setname|setcost|setprestigemultiplier|reload` (edits `ranks.yml` in-game). The list moved to the player command `/ranks` (2026-11-08).
- `/thebox` requires `thebox.admin`. Brigadier's `.requires(...)` on the root guards, and hides, every admin command at once. Each feature's command class builds its own subtree, which is attached under the root with `.then(node)`.
- Every command is also reachable as `/thebox:<label>` (Paper adds the plugin-name prefix automatically).
- `/tb` is an alias for `/thebox`. Paper skips an alias another plugin already uses; `/thebox` always works.
- Possible later: a `/thebox reload` that reloads everything.

**Permission nodes**
Declare them in `plugin.yml` with defaults.

| Node | Grants | Default |
|---|---|---|
| `thebox.admin` | All admin commands | op |
| `thebox.mine.<id>` | Entering and teleporting to mine `<id>` | granted by ranks |
| `thebox.market.rent` | Renting a stall (`/market rent`). Visiting with `/market` needs no permission, by design (see Market). | granted by rank `c` |
| `thebox.command.<command>` *(planned)* | A player command (`sell`, `mines`, `rankup`, …) | true |
| `thebox.cell.size.<n>` *(planned)* | Buying cell size *n* (2–9) | granted by ranks `d`, `g`, `j`, `m`, `p`, `s`, `v`, `y` |

Admin access lives under `thebox.admin`, not under `thebox.mine.*`, so a mine named "admin" can't collide with it.

## 5. Data
- Editable in-game. Admin commands change data and save to YAML (`mines.yml`, later `ranks.yml`).
- Hand-editing is supported: edit the file, then run `/mine reload`.
- Known caveat: if an in-game save happens before a reload, it overwrites unsaved hand edits. Document this for admins.
- **Player progress** (each player's rank and prestige level) is separate from config, and lives in LuckPerms. The rank ladder *is* a [LuckPerms track](https://luckperms.net/wiki/Tracks), so LuckPerms stores each player's rank and applies its permissions (see M3).

## 6. Milestones

### M1: Mines
- [x] 1. `/mine` command (hello)
- [x] 2. `/mine create <id>` creates a mine from the WorldEdit selection
  - [x] 2.1 WorldEdit `compileOnly` dependency
  - [x] 2.2 `create <id>` branch in the command tree
  - [x] 2.3 Require a player sender (`getPlayerOrThrow()`)
  - [x] 2.4 Read the selection through the WorldEdit API (uses the bounding box of any selection shape)
  - [x] 2.5 Restrict `/mine` to `thebox.admin` (Brigadier `.requires(...)`)
- [x] 3. Mines kept in memory; `/mine reset <mine>` refills the region (WorldEdit `EditSession`)
- [x] 4. Weighted block mix: `/mine addblock`, `/mine removeblock`
- [x] 5. Save and load `mines.yml`; `/mine reload`
- [x] 6. Automatic resets: `/mine setresettime`, one timer checking every second
- [x] 7. Player safety: players inside are teleported to the mine's teleport point before a reset
- [x] 8. `thebox.mine.<id>` permission, `/mines` list and teleport, `/mine settp`

All of M1 passed in-game testing on 2026-11-07.

**M1 follow-ups:**
- [x] Display names: `/mine setname <mine> <name>` (MiniMessage, admin-only), shown in `/mines` and reset messages *(tested in the first player test, 2026-11-08)*
- [x] `/thebox mine rename <mine> <id>` *(built 2026-11-10, untested)*: re-keys the mine and saves. It refuses an id that's taken, including by an inactive mine. LuckPerms grants still name the old `thebox.mine.<id>`, so it shows (click to copy) `lp bulkupdate all update permission thebox.mine.<new> "permission == thebox.mine.<old>"`, LuckPerms' documented way to rename a permission everywhere, temporary grants included. It's console-only, so TheBox can't run it itself.
- [x] Reset when X% has been mined: `/mine setresetpercent <mine> <percent>`, counting block breaks inside the mine *(tested in the first player test, 2026-11-08)*
- [x] Players without `thebox.mine.<id>` can't walk or teleport into a mine (`PlayerMoveEvent`). Walking tested in the first player test, 2026-11-08; teleports got their own handler afterwards (`PlayerTeleportEvent` has its own handler list), *untested*
- [x] Announce resets to players in or within 32 blocks of the mine *(tested in the first player test, 2026-11-08)*
- [x] Surface block *(built 2026-11-11, untested)*: `/thebox mine setsurface <mine> <block>|auto|none`, saved as `surface` in `mines.yml` (missing means auto; `none` means off). The idea comes from MineResetLite's `surface` flag ("hides surface ores that are freebies"), and the owner saw ores being picked off the surface in testing. Details:
  - On by default, the same day: `auto` uses the mix's highest-weighted block (the first listed, on a tie), so most mines need no setting. Existing mines get it at their next reset.
  - At each reset, every edge block whose neighbour just outside the mine is see-through (`BlockData#isOccluding` is false) gets the surface block instead of the mix. That covers the top of a walled pit, and glass or open sides too, with no per-face setting.
  - It's a WorldEdit pattern wrapping the mix. WorldEdit 7.4's `Pattern` has no abstract method, so it's an anonymous class overriding `applyBlock`, not a lambda.
  - *Decided the same day:* mines keep the classic speckled look. Each block is random by percentage, as in MineResetLite. Clumped ore veins (per-ore sizes, Eden-growth blobs) were considered and dropped.
- [x] Night Vision in and around mines *(built 2026-11-11, untested)*, instead of letting players place torches. Mines stay place-protected, which rules out pillaring, boxing in during PvP, and buckets. Details:
  - TheBox checks on every block-to-block move and adds an infinite, particle-free Night Vision when the player is near a mine, so it also comes back after milk or a death. It's checked on join too, since effects are saved with the player.
  - It's on within 8 blocks (`NIGHT_VISION_MARGIN`) of any mine, sideways or above, and off beyond that. An edge at the mine box itself flashed it on and off when players walked along the rim or hopped in and out of holes, because removing Night Vision has no fade. Briefly (2026-11-11) it switched on at the box and off at 8 blocks. The owner preferred 8 for both, which also lights the way in. If players ever flicker at the 8-block line, switching off a couple of blocks farther out fixes it.
  - It removes only infinite Night Vision, which is how it recognises its own: potions always have an end, so a potion's effect keeps running.
  - Possible later: a per-mine switch, or allowing a placeable list such as torches or TNT (a WorldGuard `block-place allow` plus a TheBox filter, with buckets and lighters blocked, and reset counting limited to the mine's own blocks).
- [x] Reset changes requested 2026-11-08 *(tested in the first player test, 2026-11-08)*:
  - Players inside are lifted **straight up** onto the refilled mine (same x/z and facing), not sent to the teleport point. Falls back to the teleport point if the space above the mine is blocked.
  - **Warnings in chat** to players in or near the mine: 1 minute, 10 seconds, then 3, 2, 1, sent by the once-a-second reset timer.
  - **Percentage resets** start a 10-second countdown (10-second warning, then 3, 2, 1) instead of resetting on the next tick.
  - `/thebox mine reset` (admin) still resets immediately.
- [ ] Watch reset performance on very large mines.
- [ ] *(idea)* `/thebox mine create` also creates the mine's WorldGuard region (`mine_<id>`, `block-break allow`) through the WorldGuard API, so the two can't drift apart. Today it's a manual step in `README.md`.
- Note: the % counter only counts player block breaks, not explosions, and it resets on server restart.

### M2: Selling
Comes before ranks so players can earn the money to rank up.

Decisions (2026-11-07):
- **Global prices:** one price per item type, everywhere. Per-mine prices or rank multipliers can come later.
- **Any item can have a price,** not just mined blocks. That leaves room for crafting interplay later (craft something worth more than its parts).
- **v1 commands:** `/sell hand` and `/sell all`. `/autosell` only if playtesting asks for it (owner, 2026-11-12).
- **`/sell all` skips items with custom data** (renamed, enchanted, custom items), so nobody sells their gear by accident. Those items stay free for crafting interplay later.
- **Economy: EssentialsX + VaultUnlocked.** It's a new server, so we chose for popularity and long-term support. EssentialsX's last release (2.22.0) lists only 26.1.2, but 26.3 support is merged and available in dev builds (2.22.1-dev). VaultUnlocked is the maintained Vault fork; it registers as `Vault`, so `depend: [Vault]` works.

Steps:
- [x] 1. Test server: add VaultUnlocked and an economy plugin via `downloadPlugins`
- [x] 2. Hook into the economy through Vault's service lookup; `depend: [WorldEdit, Vault]`
- [x] 3. `prices.yml` (item → price) plus `/sell price set|remove|list|reload` (admin only)
- [x] 4. `/sell hand`
- [x] 5. `/sell all`

M2 was tested in the first player test (2026-11-08). Implementation notes:
- Uses the original Vault API (`VaultAPI:1.7`, not transitive), which EssentialsX registers with. VaultUnlocked's newer API only works if the economy plugin supports it.
- The economy is looked up on each sell, not once at startup, because the economy plugin may enable after TheBox. A startup check runs on the first tick and logs which economy was found.
- "Customized" means anything that isn't identical to a fresh item of its type (`isSimilar`). That includes renamed, enchanted *and damaged* items. `/sell hand` refuses them too, not just `/sell all`.
- Selling pays first and removes items second, so a refused payment never costs the player their items.
- Every `/sell hand` and `/sell all` reply, successes and errors, is also written to the server log through Paper's `ComponentLogger` (so translated item names render properly).
- Unsellable and customized items are named in the error, using the custom name if renamed.
- `/sell worth` (players): shows what the held item sells for *(tested in the first player test, 2026-11-08)*.
- **Lifetime earnings and `/sell top`** *(built 2026-11-13, untested; players asked for it in the first test)*. `Earnings` adds every `/sell hand` and `/sell all` payout to the player's total in `earnings.yml` (UUID → last-known name and total, saved after each sale). Only `/sell` counts: it's the only money the server creates, and `/pay` or shop sales could be passed back and forth between friends to climb the board. The impound's auto-sell doesn't count either. `/sell top` lists the top 10 and the player's own place. Prestige keeps the totals; a season deletes `earnings.yml` (`README.md` section 16).
  - The hologram (owner's choice over chat only): `%thebox_earned%` (PlaceholderAPI, whole dollars, works for offline players), ranked by ajLeaderboards and shown by DecentHolograms. The map is a `ConcurrentHashMap` because placeholder plugins read it from their own threads.

### M3: Ranks and prestige
Configurable ladder, `/rankup`, `/ranks`, unlocks via LuckPerms, prestige.

Decisions (2026-11-08):
- **LuckPerms is the source of truth.** The ladder is a LuckPerms track (`ranks`), and each rank is a LuckPerms group named after its letter (`a` … `z`). Each group inherits the one before it, so rank C keeps rank A's and B's unlocks. Unlocks are managed with LuckPerms' own commands, e.g. `/lp group c permission set thebox.mine.iron_depths true`. LuckPerms also stores each player's rank and prestige: their group on each track.
- **Prestige is a second LuckPerms track** (`prestige`, groups `p1`, `p2`, …, each inheriting the one before). The prestige number is the group's position on the track. Prestige rewards (particles, tags) are set up exactly like rank unlocks: `/lp group p3 permission set playerparticles.style.wings true`. The highest prestige is however many groups exist, and more can be added any time. Prestige groups could get display names later, like ranks.
- **Costs are set by hand** in `ranks.yml`, which can also be edited in-game with `/rank` admin commands (`thebox.admin`), like `/mine`.
- **Ranks are A–Z** and nameable like mines: `/rank setname c <gold>Endermite`.
- **Prestige grants a chat tag** (e.g. `[P3]`) **and cosmetic effects** (particle wings and the like). Cosmetics come from PlayerParticles: each prestige group grants `playerparticles.effect.<effect>` and `playerparticles.style.<style>` permissions (a particle needs both), which keeps "unlocks = permissions".
- Rank costs scale with prestige by one config value: `cost × (1 + prestige-multiplier × prestige)` (built with step 7).

`ranks.yml` sketch:
```yaml
prestige-multiplier: 0.5
ranks:
  a: { display-name: "<gray>A", cost: 0 }
  b: { display-name: "<gray>B", cost: 1000 }
  # … through z
```

Steps:
- [ ] 1. Test server: add LuckPerms via `downloadPlugins`. By hand, create groups `a`, `b`, `c` on a `ranks` track, and `p1`, `p2` on a `prestige` track (just a few groups while developing). This is a chance to learn LuckPerms itself.
- [x] 2. LuckPerms API `compileOnly` dependency (`net.luckperms:api:5.5`), `depend: [WorldEdit, Vault, LuckPerms]`, look up the API through Bukkit's ServicesManager *(built 2026-11-08)*
- [x] 3. `ranks.yml` (display names, costs) plus `/thebox rank setname|setcost|list|reload` *(tested in the first player test, 2026-11-08; doesn't touch LuckPerms)*. The first start writes a–z with placeholder costs (b = 4000, c = 8000, …; ×4 since 2026-11-10).
- [x] 4. `/ranks`: the ladder, with the player's current rank and the next cost *(built 2026-11-08 after the first player test, untested)*. Costs shown at the player's prestige; the console sees base costs; admins also see rank ids. Replaces `/thebox rank list`. *Moved to `/rankup list` 2026-11-12 (M5 step 11).*
- [x] 5. `/rankup`: charge through Vault, then promote along the track *(tested in the first player test, 2026-11-08)*
- [x] 6. `/rankup max` *(tested in the first player test, 2026-11-08)*

Steps 2, 5 and 6, implementation notes:
- **New players start at rank `a`.** LuckPerms puts new players in its `default` group and [advises against replacing it](https://luckperms.net/wiki/Default-Groups). So TheBox adds the track's first group when a player joins, if they aren't on the track yet. Without this, LuckPerms' `promote` would put an off-track player into the *first* group (`ADDED_TO_FIRST_GROUP`), charging B's price for A.
- LuckPerms code lives in `RankLadder`; `RankManager` only handles `ranks.yml`. The track name is fixed: `ranks`.
- Charge first, then promote. If the promotion fails, the player is refunded.
- Only global (context-free) group memberships count, and promotions are global, so ranks follow players across a network.
- A rank on the track but missing from `ranks.yml` blocks rank-up (so it's never free by accident).
- Rank-ups are announced to the whole server and written to the server log.
- `/rankup max` ranks up until the next one would fail, then announces once.
- [x] 7. `/prestige`: at Z, promote on the `prestige` track and move back to `a` on the `ranks` track *(tested in the first player test, 2026-11-08)*
  - `/prestige` explains what will happen (or what the player still needs); `/prestige confirm` does it, because losing your rank by accident would hurt.
  - Free: reaching the top rank is the cost. Step 10 resets the balance.
  - A first prestige uses LuckPerms' `ADDED_TO_FIRST_GROUP` result (adds `p1`), which the API counts as a success.
  - Rank costs scale with prestige: `cost × (1 + prestige-multiplier × prestige)`, default 0.5. Set with `/thebox rank setprestigemultiplier` or in `ranks.yml`.
  - Prestige-ups are announced to the whole server and written to the server log.
- [x] 8. Chat tags: `[P3] [C] Name: message` in chat *(built 2026-11-13, untested)*. `ranks/ChatTags` listens to `AsyncChatEvent` and sets a `ChatRenderer.viewerUnaware` renderer (rendered once per message, not per viewer). The prestige tag only shows from P1; the rank part is the rank's display name; the name is the player's display name, so EssentialsX nicknames show. The event runs off the main thread: it reads LuckPerms (thread-safe) and `ranks.yml`'s names, never the Bukkit API. Cancelled messages (mutes) are skipped.
- [ ] 9. Prestige cosmetics: install PlayerParticles (if it supports 26.3), and decide which styles each prestige level unlocks
- [x] 10. Prestige resets the balance (decided 2026-11-12; *built 2026-11-12, untested*): `/prestige confirm` withdraws the whole balance, then promotes, and refunds if the promotion fails, like `/rankup`. `/prestige` says so first. Inventories and cells stay. Known gap: `/pay` lets a friend hold money across a prestige.
- Later: a `/rank setup` command that creates all 26 groups and the track, if creating them by hand gets tedious.

**Change of plan (2026-11-08):** the owner wants markets, cells and PvP in place before testing with real users, so the first release moves to after M6. (Gangs were also on that list, then paused.)

### M4: PvP in mines
Mostly configuration: WorldGuard `pvp` flags plus PvPManager for combat tagging.
- [x] WorldGuard PvP flags documented in `README.md` (`__global__ pvp deny`, `pvp allow` in each mine region except the starter mine)
- [x] PvPManager (4.0.30, Modrinth `eKgXGdO2`) chosen and documented in `README.md`. Its defaults fit; verified in its config and source (2026-11-08): commands blocked in combat, kill on logout, `/pvp` toggle overridden in `pvp allow` regions. Must keep `Unsafe Teleports: false`, or it cancels TheBox's reset lift (a `PLUGIN` teleport) mid-fight. Turn off its auto-update.
- [ ] Add it to the test server (`downloadPlugins`) and check the action-bar placeholders without PlaceholderAPI.
- [ ] Test: PvP in a mine, none at spawn or in the starter mine, items drop on death.

### M5: Market
Rentable stalls as a TheBox module (decided: all market logic is custom; see Market above), QuickShop-Hikari shops inside them, and `/market`.

Steps (drafted 2026-11-08):
- [x] 1. WorldGuard API `compileOnly` dependency, `depend: [..., WorldGuard]`; QuickShop-Hikari on the test server
- [x] 2. Data: marketplaces (id, display name, teleport point) and stalls (id, marketplace, region, daily rent, renter, paid-until, helpers, state), saved in `markets.yml`
- [x] 3. Admin commands: `/thebox market create|setname|settp|delete|list|reload`, and `/thebox market stall create <market> <stall> <rent>` from a WorldEdit selection, which also creates the stall's WorldGuard region
- [x] 4. Chest menu helper (reusable), then `/market`: straight to the only marketplace, or a menu to pick one
- [x] 5. Renting: `/market rent [days]` standing in a free stall (makes the renter a region member), `/market extend`, `/market info`, `/market trust|untrust`, `/market autorenew`; one stall per player per marketplace; `thebox.market.rent`
- [x] 6. Rent timer: auto-renew from the balance, warnings, grace period (frozen stall), eviction
- [x] 7. Impound: eviction and unrenting move container contents, blocks (as items) and item frames / armor stands into the player's impound (full item data kept), clear the stall, and `/market claim` opens it as a chest menu; auto-sell after 30 days
- [x] 8. QuickShop-Hikari: shops only in your own stall, closed during the grace period, removed on eviction (through its API)
- [x] 9. `README.md`: market setup
- [x] 10. Store markets by rank (decided 2026-11-12; *built 2026-11-12, untested*): four marketplaces of server booths, opening at G, K, N and V.
  - Every marketplace is locked by default (owner) and needs `thebox.marketplace.<id>`, granted by rank like mines. The node can't clash with `thebox.market.rent`. Upgrade note: the player market's node must be granted to `a`.
  - `/market` opens a menu of unlocked markets (straight there if only one), and `/market <id>` goes straight to one. Locked markets stay hidden.
  - `/thebox market create` refuses ids that match a `/market` subcommand, because Brigadier always picks a matching literal over an argument (owner chose this over a separate `/markets`).
  - Walking in isn't blocked, since TheBox only knows a market's teleport point, so store markets are built sealed.
  - Built: `Marketplace#getPermission`; `/market` filters by it (menus re-check on click); store markets' menu entries and welcome skip the stall text; `MarketCommand.SUBCOMMANDS` is the reserved list, which must match `createNode`; `create` prints the `lp` command that unlocks the new market.
- [x] 11. Consistent player commands (owner, 2026-11-12; *built 2026-11-12, untested*). The rule: a feature's own command takes you there (a menu when there's a choice), `<name>` goes straight to one, `help` lists the rest.
  - `/mine` replaces `/mines` outright (no alias: nobody has habits yet), with a menu (each mine's surface block as its icon), `/mine <id>` and `/mine help`. `/thebox mine create` and `rename` refuse the id `help`.
  - `/rankup` still buys the next rank, with `/rankup max`, `/rankup list` (the old `/ranks`) and `/rankup help`. The owner first suggested a bare `/rankup` showing help with `/rankup now` to buy; kept as the action because it's the most-typed prison command and what players expect from other servers.
  - `utils/Teleports` checks `Player#teleport`'s result, so `/mine`, `/market` and `/cell` never report a teleport that was cancelled.
  - Classes match the market and cell pattern: `MineCommand` (player) and `MineAdminCommand`, `RankAdminCommand`; `RanksCommand` merged into `RankupCommand`.
  - A TheBox `/warp` was considered and dropped: EssentialsX already has one, and players still need each feature's own command. `/sell` alone still shows help, since selling everything by accident is costly.

All built 2026-11-08 and tested in the first player test, 2026-11-08. Implementation notes:
- **A stall is the empty space players build in.** `stall create` refuses a selection with blocks in it, so everything in a stall belongs to its renter; the floor and walls stay outside the region and stay protected. Stall regions get priority 10, so they win inside a surrounding market region.
- **TheBox owns each stall's region** (`thebox_stall_<market>_<stall>`) and keeps its members in sync: renter and helpers, nobody while free or frozen. Loading `markets.yml` re-syncs every region (recreating deleted ones).
- **Rent:** auto-renew charges one day at a time and catches up after downtime. Extending continues from the paid-until time, so overdue days are paid too. If no economy plugin is found, the rent timer pauses instead of evicting everyone.
- **Clearing** (`StallClearer`, since M6 `regions/RegionClearer`, shared with cells): entities first, then every block is collected before any is removed (QuickShop shop deleted, container emptied, Silk Touch drops), then blocks are set to air without physics. Follows loot tables, so doors and beds drop once; containers are emptied first, so shulker boxes can't duplicate their contents.
- **Impound:** items stored with `ItemStack#serializeAsBytes` (Base64 in `impound.yml`), so every item keeps all its data. Menu buttons hold their own entry, and items are removed before they're given, so double clicks can't duplicate.
- **QuickShop-Hikari** is a `softdepend`. Its classes are only touched through `QuickShopHook.register`, so TheBox runs without it. QuickShop's own protection check (a simulated `BlockBreakEvent`) already restricts shops to stalls the player can build in.
- `day-length-minutes` in `markets.yml` shortens rent "days" for testing.
- Worlds are stored by name (`SavedLocation`): a Bukkit `Location` in YAML fails to load if its world isn't loaded yet, which would wipe the file on the next save. Mines got the same protection: they now save their teleport point as plain values, and mines in unloaded worlds are kept in `mines.yml`. `softdepend: Multiverse-Core` makes Multiverse load its worlds first.

### M6: Cells
Designed 2026-11-09 (see Cell above). Reuses the market's region code. Steps:
- [x] 1. Shared region module: `StallClearer` and the stall-region sync work on any region, so stalls and cells share them. New package `regions`: `Box` (a block box), `Regions` (WorldGuard sync, moved out of MarketManager), `RegionClearer` (was `StallClearer`)
- [x] 2. Data: `cells.yml`, with tiers (sizes, prices, wall block) and cells (tier, plot, front space, owner, current size, helpers, old cell and its release time)
- [x] 3. Admin: `/thebox cell create <tier> <cell>` (the plot from the selection: solid, big enough for the tier's largest room) and `/thebox cell setfront <cell>` (an empty selection touching one face of the plot). TheBox carves the starting room and creates the region `thebox_cell_<cell>` (owner and helpers as members, `mob-spawning deny`). Also `list`, `evict`, `delete`, `reload`
- [x] 4. A free tier-1 cell on first join; if none is free, queue the player and warn admins. Built as: any player without a cell gets one when they join (so after a season wipe too), and online players without one get it as soon as a cell is set up or freed; no stored queue. **Changed 2026-11-10:** cells are only handed out by `/cell`, when a player without one asks; joining just tells them to. Automatic hand-out gave the admin creating cells a cell of their own, and would let every one-time visitor keep a cell forever (nothing takes a cell back from an inactive player)
- [x] 5. `/cell`, `/cell visit <player>`, `/cell trust|untrust`, `/cell info`, `/cell claim` (the market's impound menu)
- [x] 6. Upgrades: `/cell upgrade` and its chest menu. Requires `thebox.cell.size.<n>`, charges through Vault, carves the next size away from the front, widens the region
- [x] 7. Tier moves: a free cell in the next tier and 7 days to move (`/cell old`); then `/cell release` or expiry clears the old cell into the impound and resets it (refill with the tier's wall block, carve the starting room)
- [x] 8. `/thebox cell wipe` for new seasons: resets every cell, no impound
- [x] 9. `README.md`: building cell blocks, defining cells, granting size unlocks, the `use allow` warning *(section 11; test in section 14)*

All built 2026-11-09; **untested in-game**. Implementation notes:
- **Package `cells`:** `Cell` (geometry: room, front region, home and visit points), `CellTier`, `CellManager` (storage, carving, regions, moves, timer, join), `CellCommand` and `CellMenu` (players), `CellAdminCommand`.
- **Two WorldGuard regions per cell**, `thebox_cell_<id>` (the room at its current size) and `thebox_cell_<id>_front` (front space plus the front wall), both priority 10 with `mob-spawning deny`. A region's corners can't change, so an upgrade replaces the room region with a bigger one that copies the old one's settings (`ProtectedRegion#copyFrom`).
- **The plot must be completely solid** at `create`; its most common block becomes the cell's wall block (`wall-block` in `cells.yml`), used to rebuild it. The front space must be empty, against one side of the plot, within its width and height; `setfront` also checks the plot fits the tier's largest room from that side.
- **`create <tier> <cell> here`** *(added 2026-11-10, untested)*: the shortcut for defining a cell without selections. The admin stands on the floor in front of the wall, facing it (`Entity#getFacing()`, the nearest compass direction), at most 3 blocks away. The plot starts at the first block ahead, is centered on them, starts at their feet, and is the tier's largest room plus the front wall. The front space is the air from the wall back to where they stand, as wide and tall as the plot (full height, so the owner can redo the whole front wall). All checks run before anything changes.
- **A block of wall between cells** *(added 2026-11-10)*: a plot may not touch another cell's plot or front space, and a front space may not touch another cell's plot, because a room at its largest fills its plot and would open into the neighbor. Front spaces may touch each other.
- **`delete` fills the plot back in** *(changed 2026-11-10)* with the wall block and empties the front space, after sending anyone inside out to the front space, so the plot is solid again: an undo for a misplaced cell, and it can be created again.
- **Carving** only removes blocks in the new room that weren't in the old one, without physics, so nothing the owner built is touched. Things hung on an old wall (torches, signs) are left floating until a neighboring block changes, then drop as items.
- **Rebuilding** (release, evict, wipe): players in the room are first teleported to the front space, the room and front region are cleared (into the impound, except for a wipe), then the whole plot is filled with the wall block, the front space emptied, the starting room carved and the tier's front put on.
- **Tier fronts** *(added 2026-11-10, untested)*: `/thebox cell savefront <cell>` saves a free cell's front as its tier's front (`tiers.<n>.front` in `cells.yml`). That's every block that differs from a plain cell in the front wall (from the wall block), the front space and the starting room's first layer (from air), e.g. an iron door, an iron-bars window and buttons on both sides. It's put on every free cell of the tier at once, and on every cell at each rebuild.
  - Each block is stored as `BlockData#getAsString` text, relative to the starting room and turned as if the front faced north. `BlockData#rotate(StructureRotation)` turns it to each cell's direction: the four directions are the same layout turned, never mirrored, so door hinges stay right.
  - Save checks: doors, windows and gaps (anything not occluding) in the front wall must be in front of the starting room; blocks with a tile entity (signs, banners, heads, containers) are refused, because only the block is saved, not its contents.
  - When a cell is cleared, the parts of its front still as TheBox put them (matched by block type, so an open door still counts) are removed first, so they don't go to the impound. That also stopped the front wall's plain wall blocks being impounded.
  - Accepted leak: an owner who breaks the default door, button or window keeps it, and the next rebuild puts a new one in. At most one front's worth per cell they own. Could be closed later by making the front's own blocks drop nothing.
- **Sizes are numbered across tiers** (`thebox.cell.size.<n>`, n = 2–9 by default). Sizes that shrink in `cells.yml` are raised to match the size before. Default prices are placeholders (about n × 1000; tier moves double).
- **QuickShop shops are possible in cells** (QuickShop allows them wherever the player can build), and are removed when a cell is cleared. Whether cell shops should exist is still the open question below.
- Later: the cell shop and more-helpers upgrades. Shops in cells? QuickShop allows a shop wherever the player can build, so once cells exist, shops could appear in cells too. If shops should stay in the market, TheBox's QuickShop hook can cancel `ShopCreateEvent` outside stalls.

### Second pass *(after the first player test, 2026-11-08)*
Testers found it rough around the edges, mostly because the world wasn't built yet and was still being configured during play. Suggested order:
- [ ] 1. Build the world properly, including the starter gear store (configuration only; see Starter gear store). `README.md`: a store section.
- [ ] 2. PvP only in your own mine (see PvP):
  - [x] 2.1 Mine `level`, set with `/thebox mine setlevel <mine> <level>` and saved in `mines.yml`; shown in `/thebox mine list` *(built 2026-11-08, untested)*
  - [x] 2.2 Damage listener: get the attacker with `event.getDamageSource().getCausingEntity()` ([DamageSource](https://jd.papermc.io/paper/org/bukkit/damage/DamageSource.html)), so arrows and tridents count as their shooter. `MineManager.onPlayerDamage` with helpers `getMineAt` and `levelOf`; action-bar message to the attacker *(built 2026-11-08, untested)*
  - [x] 2.3 Potions that only apply effects (poison, weakness) never fire a damage event: handle `PotionSplashEvent` and `AreaEffectCloudApplyEvent`. Only potions with a `HARMFUL` effect (`PotionEffectType#getEffectCategory()`; `getCategory()` is deprecated since 26.3) are blocked, so healing a friend still works. One shared check, `blockingMine`, serves hits and both potion events. No action-bar message for potions *(built 2026-11-08, untested)*
  - [ ] 2.4 Cancel at a low (early) priority; test that PvPManager doesn't combat-tag blocked hits. **Owner, in-game:** the PvP-levels check in `README.md` section 14, step 3
  - [x] 2.5 `README.md`: setting mine levels; PvP stays off in a mine until its level is set; a mine's WorldGuard region must match the mine exactly *(section 9, steps 4 and 11; test in section 14)*
- [x] 3. Seasons: the checklist in `README.md` (section 16) and `/thebox market wipe` *(both 2026-11-10, untested; see Seasons)*
- [ ] 4. `/ranks` shows each rank's unlocks (see Rank ladder), then M6 (designed 2026-11-09, see M6)

### Per-command permissions *(added 2026-11-08)*
So admins can limit TheBox's player commands per world or per server (see Commands and permissions).
- [ ] 1. Declare a node per player command in `plugin.yml` (`thebox.command.<command>`, `default: true`)
- [ ] 2. Check it in each command's Brigadier `.requires(...)`, like `thebox.admin` on `/thebox`
- [ ] 3. `README.md`: limiting a command to a world or server with LuckPerms contexts
- [ ] 4. Test with a `world=` context: does the client's command list update after a world change? If not, look at `Player#updateCommands()`.

### Gangs *(paused 2026-11-08)*
Gang bank, alliances, friendly-fire rules, gang chat. See Gang above.

### ★ First playable release
After M1–M6, plus M3's remaining step (chat tags). Deploy TheBox to the real server and gather player feedback before building further. *2026-11-13:* chat tags are built, so every code step on this list exists; much of it is still untested in-game.

## 7. Open questions
- ~~Rank-up costs~~: decided, set by hand per rank (see M3).
- ~~Rank names~~: decided, A–Z, each nameable (see M3). Mine names are still open.
- ~~What prestige grants~~: decided, a chat tag and cosmetics (see M3).
- ~~Player progress storage~~: decided, LuckPerms (see M3).
- ~~Prestige resets the balance?~~ Decided: no, since rank costs already rise with prestige. *Changed 2026-11-12 (owner): yes*, because with god picks, banking at mine Z beats climbing (see Economy and pacing, and M3 step 10).
- ~~Chat tags~~: decided, TheBox formats chat itself with [Paper's chat API](https://docs.papermc.io/paper/dev/chat-events/) (see Chat). PlaceholderAPI can be added later if a scoreboard or tab list needs ranks too.
- **On-screen balance** *(asked for by players, 2026-11-08)*: a sidebar showing balance, rank and prestige. Either TheBox draws it with per-player scoreboards, which hides PvPManager's combat nametags because those are scoreboard teams, or TAB + PlaceholderAPI draws it from `%thebox_…%` placeholders that TheBox provides. *2026-11-13:* TheBox's PlaceholderAPI expansion exists now (`%thebox_earned%`), so the TAB route only needs rank and prestige placeholders added to it.
- ~~**Lifetime earnings**~~ *(asked for by players, 2026-11-08)*: decided and built 2026-11-13. Only `/sell` counts, shown by `/sell top` and a hologram (see M2).
- ~~Server layout~~: decided 2026-11-08, a Velocity network (lobby, prison, minigames), with Multiverse-Core inside the prison server for mine and market worlds. See [server architecture](../../server-architecture.md).
- ~~Prestige across seasons~~: decided 2026-11-10, a new season resets prestige too (see Seasons).
- **Something to work toward** *(asked for by players, 2026-11-08)*: ask testers what they'd want. Candidates: rank unlocks shown in `/ranks`, the cut-down cells (see Cell), a marketplace unlocked at a higher rank (see Market).
- ~~Sell prices~~: decided, global for now (see M2).
- ~~Economy plugin~~: decided, **EssentialsX + VaultUnlocked** (see M2).
- ~~Items with custom data~~: decided, `/sell all` skips them (see M2).
- **Monetization:** read Mojang's Usage Guidelines (https://www.minecraft.net/en-us/usage-guidelines) before planning any store. Any store should grant permissions, which TheBox honors, rather than TheBox selling things directly.
- ~~Reset implementation~~: decided on a WorldEdit `EditSession` with a `RandomPattern`. Revisit only if huge mines cause lag.
- Market: ~~build or integrate~~ decided, TheBox builds it (shops from QuickShop-Hikari). ~~What happens when rent lapses~~: decided, the impound flow (see Market). ~~Settings~~: decided (see Market): several marketplaces supported, 7-day prepay, 3-day grace, 30-day impound. Still open: actual rent prices (set per stall in-game).
- Cells: ~~how players get one~~ (bought with `/cell buy`, $2,000 by default), ~~tiers and prestige~~ (3 tiers, cells survive prestige), ~~layout~~ (hand-built, registered). ~~How an upgrade removes the old boundary~~: decided, the walls are shaved back (see Cell). ~~Sizes, moves, doors~~: redesigned 2026-11-09 (see Cell). Still open: whether cell shops compete with the market.
- ~~PvP~~: decided, every mine except the starter mine; normal drops; no special reset handling (see PvP).
- Gangs: paused. Defaults agreed before pausing: up to 8 members, leader/officers/members, creating costs money, 2–5 letter tag in chat, anyone deposits but only leader and officers withdraw, up to 3 mutual allies, no friendly fire, `/gc` chat.
- Black market: pinned, come back later.
