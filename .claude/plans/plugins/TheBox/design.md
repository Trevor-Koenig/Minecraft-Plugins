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
- A configurable, ordered list of ranks. Not hard-coded A–Z.
- Each rank has an `id`, a `displayName`, a `cost`, and **unlocks**.
- Prestige: after the last rank, the player can prestige, which resets their rank and gives a prestige level. What prestige grants is still open (see section 7).

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
| LuckPerms | Permissions and groups granted by ranks; possibly the rank ladder itself (tracks) | `depend` (from M3: Ranks) |
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

**Admins**
- `/mine create <id>`, `delete <id>`, `list`
- `/mine setblocks` / `addblock <id> <block> <weight>`
- `/mine settp <id>`, `setresettime <id> <time>`, `reset <id>`
- `/mine reload`
- `/rank …` commands for managing the ladder (to be designed)
- All admin commands require `thebox.admin`. Brigadier's `.requires(...)` also hides them from players without it.

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
- **Player progress** (each player's rank and prestige level) is separate from config. Where it's stored is an open question (see section 7). The leading idea is that the rank ladder *is* a [LuckPerms track](https://luckperms.net/wiki/Tracks), so LuckPerms stores each player's rank and applies its permissions.

## 6. Milestones

### M1: Mines
- [x] 1. `/mine` command (hello)
- [ ] 2. `/mine create <id>` prints the WorldEdit selection
  - [x] 2.1 WorldEdit `compileOnly` dependency
  - [x] 2.2 `create <name>` branch in the command tree
  - [ ] 2.3 Require a player sender
  - [ ] 2.4 Read the selection through the WorldEdit API
  - [ ] 2.5 Restrict admin subcommands to `thebox.admin` (Brigadier `.requires(...)`)
- [ ] 3. Mines kept in memory; `/mine reset <id>` fills the region with stone
- [ ] 4. Weighted block mix
- [ ] 5. Save and load `mines.yml`; `/mine reload`
- [ ] 6. Automatic resets (scheduler)
- [ ] 7. Player safety: move players out of the region before a reset
- [ ] 8. `thebox.mine.<id>` permission, plus `/mines` teleport

### M2: Selling
`/sell`, prices per block (per mine or per rank?), autosell. Comes before ranks so players can earn the money to rank up. To be planned in detail.

### M3: Ranks and prestige
Configurable ladder, `/rankup`, `/ranks`, unlocks via LuckPerms, prestige. To be planned in detail.

### ★ First playable release
After M1–M3 the core loop (mine → sell → rank up) works. Deploy TheBox to the real server and gather player feedback before building further.

### M4: Market
Comes after the first playable release, since shops need the economy. Set up the market area with integrated stall and shop plugins, then add `/market` plus the `thebox.market` permission in TheBox. To be planned in detail.

### M5: Cells
Personal cells with buyable upgrades, including the ability to sell from the cell. To be planned in detail.

## 7. Open questions
- Rank-up costs and the shape of the cost curve.
- The naming theme for mines and ranks (Shulker / End themed?).
- What prestige grants.
- Player progress storage (M3): ranks as a LuckPerms track? And where prestige levels live (LuckPerms metadata, the player's persistent data, or TheBox's own file or database)?
- Sell prices: global, per mine, or with per-rank multipliers?
- **Monetization:** read Mojang's Usage Guidelines (https://www.minecraft.net/en-us/usage-guidelines) before planning any store. Any store should grant permissions, which TheBox honors, rather than TheBox selling things directly.
- Reset implementation: WorldEdit `EditSession` vs. spreading block changes across ticks.
- Market: which stall and shop plugins to use (check Paper 26.3 support first), rent pricing and duration, and whether there's one marketplace or several (several would make `/market` take a name).
- Cells: how players get one (a free starter cell, or bought?), which upgrades exist besides selling, whether cell shops compete with the market, and whether cells come from an integrated plugin or from TheBox.
