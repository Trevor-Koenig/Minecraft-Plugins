# TheBox — Design

Status: **draft**. A living document, updated as decisions are made.

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
- **Stalls:** protected regions that players **rent** (not buy), limited to **one stall per player per marketplace** (WorldGuard plus a stall plugin). Rent is an ongoing money sink, which prison economies need, and stalls of inactive players free up on their own when rent lapses. AreaShop can enforce the limit through its "limit groups."
- **Shops:** chest shops inside stalls that sell to or buy from players, paid through Vault.
- **`/market`:** teleports players to the market area. Access can be a rank unlock (`thebox.market`).
- TheBox's part is small: the `/market` command and its permission gate. Stalls and shops come from integrated plugins.

### Cell
A player's own personal space, a classic prison feature.
- Players can **buy upgrades** for their cell.
- One upgrade could be **selling things from the cell** (a shop in the cell).
- Upgrades fit the "unlocks = permissions" model: buying an upgrade grants a permission.
- Most details are still open (see section 7).

## 3. Integrations
| Plugin | Role | Dependency |
|---|---|---|
| WorldEdit | Region selection for mines; possibly fast fills for resets | `depend` (added) |
| Vault + an economy plugin (e.g. EssentialsX) | Balances: sell income, rank-up costs | `depend` (from M2: Selling) |
| LuckPerms | The rank ladder (a track), each player's rank and prestige, and the permissions ranks grant | `depend` (from M3: Ranks) |
| PlayerParticles | Cosmetic particle effects (wings, halo, …) unlocked by prestige, one permission per style | Runs alongside TheBox; no code dependency. Latest build lists up to 26.2, so check 26.3 support first |
| WorldGuard + a stall plugin (candidates: AreaShop, AdvancedRegionMarket) | Rentable market stalls | Runs alongside TheBox; probably no code dependency |
| A chest-shop plugin (candidates: QuickShop-Hikari, ChestShop) | Player shops inside stalls | Runs alongside TheBox; probably no code dependency |

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
- [ ] Watch reset performance on very large mines.
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
- *(Proposed)* Rank costs scale with prestige by one config value: `cost × (1 + prestige-multiplier × prestige)`.

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
- [ ] 2. LuckPerms API `compileOnly` dependency (`net.luckperms:api:5.5`), `depend: [WorldEdit, Vault, LuckPerms]`, look up the API through Bukkit's ServicesManager
- [x] 3. `ranks.yml` (display names, costs) plus `/thebox rank setname|setcost|list|reload` *(built 2026-11-08, untested; doesn't touch LuckPerms yet)*. The first start writes a–z with placeholder costs (b = 1000, c = 2000, …).
- [ ] 4. `/ranks`: the ladder, with the player's current rank and the next cost
- [ ] 5. `/rankup`: charge through Vault, then promote along the track
- [ ] 6. `/rankup max`
- [ ] 7. `/prestige`: at Z, promote on the `prestige` track and move back to `a` on the `ranks` track
- [ ] 8. Chat tags: `[P3] [C] Name: message` in chat
- [ ] 9. Prestige cosmetics: install PlayerParticles (if it supports 26.3), and decide which styles each prestige level unlocks
- Later: a `/rank setup` command that creates all 26 groups and the track, if creating them by hand gets tedious.

### ★ First playable release
After M1–M3 the core loop (mine → sell → rank up) works. Deploy TheBox to the real server and gather player feedback before building further.

### M4: Market
Comes after the first playable release, since shops need the economy. Set up the market area with integrated stall and shop plugins, then add `/market` plus the `thebox.market` permission in TheBox. To be planned in detail.

### M5: Cells
Personal cells with buyable upgrades, including the ability to sell from the cell. To be planned in detail.

## 7. Open questions
- ~~Rank-up costs~~: decided, set by hand per rank (see M3).
- ~~Rank names~~: decided, A–Z, each nameable (see M3). Mine names are still open.
- ~~What prestige grants~~: decided, a chat tag and cosmetics (see M3).
- ~~Player progress storage~~: decided, LuckPerms (see M3).
- Prestige: does it also reset the player's balance? (Many prison servers do.)
- Chat tags (M3 step 8): does TheBox format chat itself with [Paper's chat API](https://docs.papermc.io/paper/dev/chat-events/), or provide placeholders for a chat plugin? Owning chat is simplest to start; placeholders (PlaceholderAPI) are the standard choice once there's also a scoreboard or tab list.
- ~~Sell prices~~: decided, global for now (see M2).
- ~~Economy plugin~~: decided, **EssentialsX + VaultUnlocked** (see M2).
- ~~Items with custom data~~: decided, `/sell all` skips them (see M2).
- **Monetization:** read Mojang's Usage Guidelines (https://www.minecraft.net/en-us/usage-guidelines) before planning any store. Any store should grant permissions, which TheBox honors, rather than TheBox selling things directly.
- ~~Reset implementation~~: decided on a WorldEdit `EditSession` with a `RandomPattern`. Revisit only if huge mines cause lag.
- Market: which stall and shop plugins to use (check Paper 26.3 support first), rent pricing and duration, and whether there's one marketplace or several (several would make `/market` take a name).
- Cells: how players get one (a free starter cell, or bought?), which upgrades exist besides selling, whether cell shops compete with the market, and whether cells come from an integrated plugin or from TheBox.
