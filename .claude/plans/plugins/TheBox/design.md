# TheBox — Design

Status: **draft**. A living document, updated as decisions are made.

The admin setup guide is [plugins/TheBox/SETUP.md](../../../../plugins/TheBox/SETUP.md). Update it whenever commands, permissions or setup steps change.

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
| `teleport` | Where `/mines` sends players. |
| `permission` | `thebox.mine.<id>`, required to enter or teleport. Mines are **not** tied 1:1 to ranks. |

Only admins create and edit mines. Players just teleport to them.

### Rank ladder
- Ranks **A–Z**, in order. Each rank's id is a letter, but like mines it can have a `displayName` (MiniMessage), e.g. A = "<gray>Shulker Larva".
- Each rank has a `cost` (set by hand) and **unlocks** (permissions, through LuckPerms).
- Prestige: after Z, the player can prestige. That resets them to A and adds 1 to their prestige level, which earns a chat tag and cosmetic effects (see M3).

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

### Cell
A player's own personal space, a classic prison feature.
- Players can **buy upgrades** for their cell.
- One upgrade could be **selling things from the cell** (a shop in the cell).
- Upgrades fit the "unlocks = permissions" model: buying an upgrade grants a permission.
- **Bigger cells: tiers, decided 2026-11-08.** Both approaches, combined:
  - **Within a tier, grow in place,** up to **3 size upgrades**. Each cell's plot reserves room for its tier's largest size, and an upgrade widens the buildable WorldGuard region. Nothing is copied.
  - **To go beyond that, move to a cell in the next tier** (e.g. a different, bigger "cell block"). The build comes along: copy the old cell with WorldEdit (`ForwardExtentCopy` with `setCopyingEntities(true)`; WorldEdit keeps block-entity data like chest contents and sign text, and entities like item frames and armor stands), paste it into the new cell, verify, and only then clear the old one. The order matters: a failure halfway must never lose or duplicate items.
- **Prestige:** cells **survive** prestige (they're a separate, money-based progression), while bigger tiers *require* a rank or prestige (`thebox.cell.tier.<n>`, granted as usual). Decided 2026-11-08.
- **Decided 2026-11-08:**
  - **Cell blocks are built by hand** and each cell is **registered** from WorldEdit selections, like stalls. For in-place growth, an admin registers a cell's base size plus each larger size.
  - **Growth is invisible:** no glass rings or markers. Decided 2026-11-08: the cell **visibly gets bigger, as if its walls were shaved back.** The admin builds the cell at its largest size with solid wall between each size; an upgrade sets everything between the old interior and the new one to air, so the old wall disappears and the room is larger.
  - **Every player gets a free first cell when they first join.** Hand-built cells can run out, so if none is free the player gets one as soon as one is registered, and admins are warned.
  - One cell per player; 3 tiers to start; admins set the price of each upgrade and tier move; v1 upgrades besides size: a cell shop and more trusted builders; others can visit unless the owner locks the cell; no hostile mob spawning; cells of inactive players are kept for now; a tier move pastes the old build centered in the new cell.

### PvP
- PvP is allowed **in mines** only. That's WorldGuard config: `pvp deny` everywhere else (`__global__` and spawn), `pvp allow` in mine regions.
- **Combat logging** (quitting or teleporting out mid-fight) needs a plugin. **PvPManager** supports 26.3 (checked 2026-11-08); its combat tag can block commands like `/mines` during a fight.
- **Decided 2026-11-08:** PvP in **every mine except the starter mine** (the one rank `a` unlocks), so new players are safe. Dying drops items as usual (vanilla; `keepInventory` stays off). Mine resets during fights need no special handling.

### Gang *(paused, 2026-11-08)*
Paused by the owner; not part of the first release. Notes kept for later. A group of players who **pool resources** (a shared gang bank through Vault) and form **alliances** with other gangs. Likely also: no friendly fire between members and allies, gang chat, a gang leaderboard. SimpleClans, the established plugin for this, last released in 2023, so gangs probably belong in TheBox.

### Black market *(pinned, 2026-11-08)*
An idea to come back to later; nothing designed yet.

### Menus
Decided 2026-11-08: players use **chest menus** (clickable inventory GUIs) where they fit: choosing a marketplace, the impound, cell upgrades. Actions also have commands. One small, reusable menu helper serves every feature.

### Chat
Decided 2026-11-08: **TheBox formats chat itself** with Paper's chat API: `[P2] [C] Name: message` (prestige, rank; a gang tag if gangs return).

### Food
Decided 2026-11-08: no TheBox code. For launch, EssentialsX covers it (setup in `SETUP.md`, "Food"):
- A free food kit (`/kit food`) with a cooldown, granted to rank `a`.
- `/feed` as a mid-ladder rank unlock (`essentials.feed`).

Later, food can become part of the economy (a money sink in the market, farms in cells). If mines ever need it, WorldGuard's `feed-*` region flags can feed players automatically inside a region.

## 3. Integrations
| Plugin | Role | Dependency |
|---|---|---|
| WorldEdit | Region selection for mines; possibly fast fills for resets | `depend` (added) |
| Vault + an economy plugin (e.g. EssentialsX) | Balances: sell income, rank-up costs | `depend` (from M2: Selling) |
| LuckPerms | The rank ladder (a track), each player's rank and prestige, and the permissions ranks grant | `depend` (from M3: Ranks) |
| PlayerParticles | Cosmetic particle effects (wings, halo, …) unlocked by prestige, one permission per style | Runs alongside TheBox; no code dependency. Latest build lists up to 26.2, so check 26.3 support first |
| WorldGuard | From launch: protects the world (`__global__` passthrough deny) and lets players break blocks in mines (a `mine_<id>` region per mine with `block-break allow`). Who may *enter* a mine stays TheBox's job (rank permissions). Setup in `SETUP.md`. | Runs alongside TheBox; no code dependency yet |
| WorldGuard API | Rentable market stalls and cells: TheBox creates regions and adds renters/owners as members (stall plugins look unmaintained, see Market) | Code dependency (from M5) |
| QuickShop-Hikari | Player chest shops in stalls (and maybe cells) | Runs alongside TheBox; probably no code dependency |
| PvPManager | Combat tagging, combat-log handling | Runs alongside TheBox (from M4) |
| Multiverse-Core *(optional)* | Several worlds. Loads before TheBox (`softdepend`) so mine and market worlds exist when TheBox loads its files. Keep its per-world PvP on in worlds with PvP mines. | `softdepend` |

Shops (2026-11-08): QuickShop-Hikari over EssentialsX's sign shops. EssentialsX's `[Trade]` signs keep their stock in the sign rather than in a chest, its `[Buy]`/`[Sell]` signs are server shops, and neither has an API for closing or removing shops when rent lapses.

## 4. Commands and permissions
Compare against existing prison servers before finalizing.

**Players**
- `/mines`: list or menu of mines → teleport
- `/rankup`, `/rankup max`, `/ranks`, `/prestige`
- `/sell all`, `/sell hand`, `/autosell`
- `/market`: teleport to the player market (permission-gated)
- Balance, pay and baltop come from the economy plugin, not TheBox.

**Admins**: all under one root, `/thebox` (decided 2026-11-08). Player commands stay short and top-level, like on other prison servers. Grouping admin commands gives one autocomplete entry point, avoids the `/mine` vs `/mines` mix-up, and keeps `/sell` player-only.
- `/thebox mine create|delete|list|reset|addblock|removeblock|setresettime|setresetpercent|setname|settp|reload`
- `/thebox price set|remove|list|reload`
- `/thebox rank list|setname|setcost|reload` (edits `ranks.yml` in-game)
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
| `thebox.market` | `/market` | granted by ranks |
| *(later)* cell upgrade nodes | One per upgrade | granted on purchase |

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
- [x] Display names: `/mine setname <mine> <name>` (MiniMessage, admin-only), shown in `/mines` and reset messages *(built 2026-11-07, untested)*
- [x] Reset when X% has been mined: `/mine setresetpercent <mine> <percent>`, counting block breaks inside the mine *(built, untested)*
- [x] Players without `thebox.mine.<id>` can't walk or teleport into a mine (`PlayerMoveEvent`) *(built, untested)*
- [x] Announce resets to players in or within 32 blocks of the mine *(built, untested)*
- [x] Reset changes requested 2026-11-08 *(built, untested)*:
  - Players inside are lifted **straight up** onto the refilled mine (same x/z and facing), not sent to the teleport point. Falls back to the teleport point if the space above the mine is blocked.
  - **Warnings in chat** to players in or near the mine: 1 minute, 10 seconds, then 3, 2, 1, sent by the once-a-second reset timer.
  - **Percentage resets** start a 10-second countdown (10-second warning, then 3, 2, 1) instead of resetting on the next tick.
  - `/thebox mine reset` (admin) still resets immediately.
- [ ] Watch reset performance on very large mines.
- [ ] *(idea)* `/thebox mine create` also creates the mine's WorldGuard region (`mine_<id>`, `block-break allow`) through the WorldGuard API, so the two can't drift apart. Today it's a manual step in `SETUP.md`.
- Note: the % counter only counts player block breaks, not explosions, and it resets on server restart.

### M2: Selling
Comes before ranks so players can earn the money to rank up.

Decisions (2026-11-07):
- **Global prices:** one price per item type, everywhere. Per-mine prices or rank multipliers can come later.
- **Any item can have a price,** not just mined blocks. That leaves room for crafting interplay later (craft something worth more than its parts).
- **v1 commands:** `/sell hand` and `/sell all`. `/autosell` comes later.
- **`/sell all` skips items with custom data** (renamed, enchanted, custom items), so nobody sells their gear by accident. Those items stay free for crafting interplay later.
- **Economy: EssentialsX + VaultUnlocked.** It's a new server, so we chose for popularity and long-term support. EssentialsX's last release (2.22.0) lists only 26.1.2, but 26.3 support is merged and available in dev builds (2.22.1-dev). VaultUnlocked is the maintained Vault fork; it registers as `Vault`, so `depend: [Vault]` works.

Steps:
- [x] 1. Test server: add VaultUnlocked and an economy plugin via `downloadPlugins`
- [x] 2. Hook into the economy through Vault's service lookup; `depend: [WorldEdit, Vault]`
- [x] 3. `prices.yml` (item → price) plus `/sell price set|remove|list|reload` (admin only)
- [x] 4. `/sell hand`
- [x] 5. `/sell all`

M2 is built and compiles, but hasn't been tested in-game yet. Implementation notes:
- Uses the original Vault API (`VaultAPI:1.7`, not transitive), which EssentialsX registers with. VaultUnlocked's newer API only works if the economy plugin supports it.
- The economy is looked up on each sell, not once at startup, because the economy plugin may enable after TheBox. A startup check runs on the first tick and logs which economy was found.
- "Customized" means anything that isn't identical to a fresh item of its type (`isSimilar`). That includes renamed, enchanted *and damaged* items. `/sell hand` refuses them too, not just `/sell all`.
- Selling pays first and removes items second, so a refused payment never costs the player their items.
- Every `/sell hand` and `/sell all` reply, successes and errors, is also written to the server log through Paper's `ComponentLogger` (so translated item names render properly).
- Unsellable and customized items are named in the error, using the custom name if renamed.
- `/sell worth` (players): shows what the held item sells for *(built, untested)*.

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
- [x] 3. `ranks.yml` (display names, costs) plus `/thebox rank setname|setcost|list|reload` *(built 2026-11-08, untested; doesn't touch LuckPerms yet)*. The first start writes a–z with placeholder costs (b = 1000, c = 2000, …).
- [ ] 4. `/ranks`: the ladder, with the player's current rank and the next cost
- [x] 5. `/rankup`: charge through Vault, then promote along the track *(built 2026-11-08, untested)*
- [x] 6. `/rankup max` *(built 2026-11-08, untested)*

Steps 2, 5 and 6, implementation notes:
- **New players start at rank `a`.** LuckPerms puts new players in its `default` group and [advises against replacing it](https://luckperms.net/wiki/Default-Groups). So TheBox adds the track's first group when a player joins, if they aren't on the track yet. Without this, LuckPerms' `promote` would put an off-track player into the *first* group (`ADDED_TO_FIRST_GROUP`), charging B's price for A.
- LuckPerms code lives in `RankLadder`; `RankManager` only handles `ranks.yml`. The track name is fixed: `ranks`.
- Charge first, then promote. If the promotion fails, the player is refunded.
- Only global (context-free) group memberships count, and promotions are global, so ranks follow players across a network.
- A rank on the track but missing from `ranks.yml` blocks rank-up (so it's never free by accident).
- Rank-ups are announced to the whole server and written to the server log.
- `/rankup max` ranks up until the next one would fail, then announces once.
- [x] 7. `/prestige`: at Z, promote on the `prestige` track and move back to `a` on the `ranks` track *(built 2026-11-08, untested)*
  - `/prestige` explains what will happen (or what the player still needs); `/prestige confirm` does it, because losing your rank by accident would hurt.
  - Free: reaching the top rank is the cost. The balance is kept (open question below).
  - A first prestige uses LuckPerms' `ADDED_TO_FIRST_GROUP` result (adds `p1`), which the API counts as a success.
  - Rank costs scale with prestige: `cost × (1 + prestige-multiplier × prestige)`, default 0.5. Set with `/thebox rank setprestigemultiplier` or in `ranks.yml`.
  - Prestige-ups are announced to the whole server and written to the server log.
- [ ] 8. Chat tags: `[P3] [C] Name: message` in chat
- [ ] 9. Prestige cosmetics: install PlayerParticles (if it supports 26.3), and decide which styles each prestige level unlocks
- Later: a `/rank setup` command that creates all 26 groups and the track, if creating them by hand gets tedious.

**Change of plan (2026-11-08):** the owner wants markets, cells and PvP in place before testing with real users, so the first release moves to after M6. (Gangs were also on that list, then paused.)

### M4: PvP in mines
Mostly configuration: WorldGuard `pvp` flags plus PvPManager for combat tagging.
- [x] WorldGuard PvP flags documented in `SETUP.md` (`__global__ pvp deny`, `pvp allow` in each mine region except the starter mine)
- [x] PvPManager (4.0.30, Modrinth `eKgXGdO2`) chosen and documented in `SETUP.md`. Its defaults fit; verified in its config and source (2026-11-08): commands blocked in combat, kill on logout, `/pvp` toggle overridden in `pvp allow` regions. Must keep `Unsafe Teleports: false`, or it cancels TheBox's reset lift (a `PLUGIN` teleport) mid-fight. Turn off its auto-update.
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
- [x] 9. `SETUP.md`: market setup

All built 2026-11-08; **untested in-game**. Implementation notes:
- **A stall is the empty space players build in.** `stall create` refuses a selection with blocks in it, so everything in a stall belongs to its renter; the floor and walls stay outside the region and stay protected. Stall regions get priority 10, so they win inside a surrounding market region.
- **TheBox owns each stall's region** (`thebox_stall_<market>_<stall>`) and keeps its members in sync: renter and helpers, nobody while free or frozen. Loading `markets.yml` re-syncs every region (recreating deleted ones).
- **Rent:** auto-renew charges one day at a time and catches up after downtime. Extending continues from the paid-until time, so overdue days are paid too. If no economy plugin is found, the rent timer pauses instead of evicting everyone.
- **Clearing** (`StallClearer`): entities first, then every block is collected before any is removed (QuickShop shop deleted, container emptied, Silk Touch drops), then blocks are set to air without physics. Follows loot tables, so doors and beds drop once; containers are emptied first, so shulker boxes can't duplicate their contents.
- **Impound:** items stored with `ItemStack#serializeAsBytes` (Base64 in `impound.yml`), so every item keeps all its data. Menu buttons hold their own entry, and items are removed before they're given, so double clicks can't duplicate.
- **QuickShop-Hikari** is a `softdepend`. Its classes are only touched through `QuickShopHook.register`, so TheBox runs without it. QuickShop's own protection check (a simulated `BlockBreakEvent`) already restricts shops to stalls the player can build in.
- `day-length-minutes` in `markets.yml` shortens rent "days" for testing.
- Worlds are stored by name (`SavedLocation`): a Bukkit `Location` in YAML fails to load if its world isn't loaded yet, which would wipe the file on the next save. Mines got the same protection: they now save their teleport point as plain values, and mines in unloaded worlds are kept in `mines.yml`. `softdepend: Multiverse-Core` makes Multiverse load its worlds first.

### M6: Cells
Personal cells with buyable upgrades and bigger cells (see Cell above). Hand-built and registered like stalls, so it reuses the market's region module. To be planned in detail.

### Gangs *(paused 2026-11-08)*
Gang bank, alliances, friendly-fire rules, gang chat. See Gang above.

### ★ First playable release
After M1–M6, plus M3's remaining steps (`/ranks`, chat tags). Deploy TheBox to the real server and gather player feedback before building further.

## 7. Open questions
- ~~Rank-up costs~~: decided, set by hand per rank (see M3).
- ~~Rank names~~: decided, A–Z, each nameable (see M3). Mine names are still open.
- ~~What prestige grants~~: decided, a chat tag and cosmetics (see M3).
- ~~Player progress storage~~: decided, LuckPerms (see M3).
- ~~Prestige resets the balance?~~ Decided: no, since rank costs already rise with prestige.
- ~~Chat tags~~: decided, TheBox formats chat itself with [Paper's chat API](https://docs.papermc.io/paper/dev/chat-events/) (see Chat). PlaceholderAPI can be added later if a scoreboard or tab list needs ranks too.
- ~~Sell prices~~: decided, global for now (see M2).
- ~~Economy plugin~~: decided, **EssentialsX + VaultUnlocked** (see M2).
- ~~Items with custom data~~: decided, `/sell all` skips them (see M2).
- **Monetization:** read Mojang's Usage Guidelines (https://www.minecraft.net/en-us/usage-guidelines) before planning any store. Any store should grant permissions, which TheBox honors, rather than TheBox selling things directly.
- ~~Reset implementation~~: decided on a WorldEdit `EditSession` with a `RandomPattern`. Revisit only if huge mines cause lag.
- Market: ~~build or integrate~~ decided, TheBox builds it (shops from QuickShop-Hikari). ~~What happens when rent lapses~~: decided, the impound flow (see Market). ~~Settings~~: decided (see Market): several marketplaces supported, 7-day prepay, 3-day grace, 30-day impound. Still open: actual rent prices (set per stall in-game).
- Cells: ~~how players get one~~ (free on first join), ~~tiers and prestige~~ (3 tiers, cells survive prestige), ~~layout~~ (hand-built, registered). ~~How an upgrade removes the old boundary~~: decided, the walls are shaved back (see Cell). Still open: whether cell shops compete with the market.
- ~~PvP~~: decided, every mine except the starter mine; normal drops; no special reset handling (see PvP).
- Gangs: paused. Defaults agreed before pausing: up to 8 members, leader/officers/members, creating costs money, 2–5 letter tag in chat, anyone deposits but only leader and officers withdraw, up to 3 mutual allies, no friendly fire, `/gc` chat.
- Black market: pinned, come back later.
