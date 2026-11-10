# Server architecture

Status: **decided 2026-11-08: a Velocity network** (Option B), with Multiverse-Core inside each server where a game mode needs several worlds, such as the prison's mines and market. Covers the whole server, not one plugin: how a lobby, the prison ([TheBox](plugins/TheBox/design.md)) and minigames fit together.

## The problem
The plan was one Paper server with Multiverse: a lobby world plus a world per game mode, so players never leave the server. But plugins "bleed" across worlds, and some game modes need their own copy of a plugin they share with others (e.g. separate balances).

## Why plugins bleed
- Paper has no way to run a plugin in only some worlds. Every plugin loads for the whole server, its events fire in every world, and its commands work everywhere. A plugin is per-world only if its author built that in.
- Multiverse manages worlds: creating and loading them, and [per-world properties](https://mvplugins.org/core/fundamentals/world-properties/) like game mode, difficulty, PvP, hunger, mob spawning, respawn world and player limit. It can't change where other plugins keep their data.

Each plugin falls in one of three groups:
1. **Already per world:** WorldGuard regions and flags, Multiverse's own settings. Nothing to do.
2. **Global, but can be fenced off:**
   - Permission-checked commands and features: LuckPerms [contexts](https://luckperms.net/wiki/Context), e.g. `lp group default permission set essentials.pay true world=prison`. `world-rewrite` in LuckPerms' `config.yml` treats several worlds as one name. Ops skip all permission checks.
   - Inventory, health, XP, ender chest: [Multiverse-Inventories](https://mvplugins.org/inventories/fundamentals/basic-usage/) world groups and [shares](https://mvplugins.org/inventories/reference/shares-list/).
   - Some plugins have per-world options of their own; check each one's config.
3. **Global data that needs a separate copy per world:** the only real wall.
   - EssentialsX keeps one balance per player for the whole server ([EssentialsX #1011](https://github.com/EssentialsX/Essentials/issues/1011)), and a server has only one Vault economy.
   - Multiverse-Inventories' optional `economy` share swaps balances only when a player changes world group. Its docs say it can't stop `/pay` between groups and recommend leaving balances to the economy plugin.

Minigame plugins often run each arena as its own world on a shared server and isolate themselves (inventories, scoreboards), so they may not bleed at all.

## Option A: one server, several worlds
- Fits worlds that belong to one game mode (the prison's mine, market and cell worlds), or a setup where no plugin is in group 3.
- All worlds share one main thread: a laggy minigame lags the prison. A restart for one game kicks everyone.

## Option B: a Velocity network *(chosen 2026-11-08)*
- Players connect to one address, the [Velocity](https://docs.papermc.io/velocity/getting-started/) proxy. Moving between servers ([`/server`](https://docs.papermc.io/velocity/built-in-commands/), or a lobby compass or NPC) shows a loading screen, like going through a nether portal; the player doesn't disconnect.
- Each backend server runs its own copy of every plugin with its own data, so the prison and a minigame get separate EssentialsX balances with no extra setup.
- Small version for us: Velocity, a lobby server, a prison server running TheBox, and one minigames server.

Standard practice on networks:
- Separate servers by role: lobby servers, one per game mode, servers for minigames. Multiverse is still used *inside* a server (arena worlds; the prison's worlds).
- Data that follows the player (ranks, friends, cosmetics) lives in a shared database keyed by UUID. LuckPerms on MariaDB is the standard example; setup is in [SETUP.md, "Network (later)"](../../plugins/TheBox/SETUP.md#network-later).
- Each game mode usually has its own economy, on purpose. A prison economy is inflationary by design (mines create money; rank-ups and rent destroy it), so it shouldn't flow into other modes.
- Cross-server features (parties, friends, `/hub`) are proxy plugins.

### Hypixel, for reference
Not a template: nearly everything there is custom-built. Hypixel doesn't publish its architecture; this comes from players' forum threads ([1](https://hypixel.net/threads/how-does-hypixel-have-those-sub-servers-that-you-get-teleported-to-for-games.4830028/), [2](https://hypixel.net/threads/some-information-about-the-hypixel-network.3516511/)), several years old:
- A proxy based on BungeeCord in front of thousands of small server instances.
- Lobbies run on larger "mega" instances, each game on a small "mini" instance ("Sending you to mini39G"; `/whereami` shows the current one).
- An in-house system starts instances and assigns them to game modes; player data lives in a central database.
- Each game has its own coins: per-game-mode economies again.

## What this means for TheBox
- **Per-command permissions** (decided 2026-11-08): every player command gets a node, so admins can scope it with `world=` on one server or `server=` on a network. Milestone in TheBox's design.
- **No custom economy** (decided 2026-11-08): TheBox stays on Vault + EssentialsX. Reasoning in TheBox's design, section 3.
- Problems a shared server would have caused (kept as the reasoning behind the decision):
  - TheBox's listeners would run in every world, and permission nodes only cover commands. Rank-up and prestige announcements go to the whole server, new players are put on rank `a` wherever they join, and the planned chat format (`[P2] [C] Name`) would show in the lobby and minigames too.
  - Stall auto-renew charges whatever balance EssentialsX holds. With Multiverse-Inventories' economy share, that's the balance for the world group the player was last in, which could be a minigame's.
- TheBox needs no changes to run as the prison server on a network: ranks are global LuckPerms memberships, so they follow players across servers.

## If we ever need two currencies on one server
[VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlockedAPI) (already installed) adds multi-currency to the Vault API. It needs a multi-currency economy plugin behind it (EssentialsX has a single currency), and TheBox would have to call VaultUnlocked's newer API instead of the original Vault API. QuickShop-Hikari [supports multi-currency through VaultUnlocked economies](https://modrinth.com/plugin/quickshop-hikari). Check the exact API before planning this.

## Open questions
- ~~One server or a network?~~ Decided 2026-11-08: a Velocity network.
- Which minigames, and which plugins run them? Does the minigames server need money?
- Rollout: put the proxy and lobby in place for TheBox's first release, or launch the prison server alone and add Velocity once the lobby and minigames exist?
