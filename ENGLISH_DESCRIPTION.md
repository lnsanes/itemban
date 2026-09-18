# ItemBan - English Mod Description

**Version**: 2.2.0  
**Author**: lnsanes  
**License**: Apache License 2.0  
**Links**: [GitHub](https://github.com/lnsanes/itemban) · [Modrinth](https://modrinth.com/mod/lnsanes-itemban) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/itemban)

## Short Summary (for mod lists, max ~250 characters)

Server-side item and block blacklist with NBT matching. Strips banned recipes and removes matching items from inventories, containers, frames, drops, and the world. Includes a localhost web admin and an in-game GUI gated by a per-server admin mod.

## Full Description

ItemBan is a lightweight Minecraft mod for server owners. Ban cleanup can stay server-side only: regular players do not need the mod on their client.

The in-game admin GUI is optional. It requires the ItemBan jar plus a matching per-server admin mod on the administrator's client, and the `itemban.ban` permission.

### What's new in 2.2.0

- In-game graphical admin for every supported loader.
- Players with `itemban.ban` can run `/itemban gui` after installing the admin mod that matches this server's key.
- First launch writes `admin-key.json` and generates `ItemBan-Admin-*.jar` under `config/ItemBan/admin-mods/`. Later restarts keep the same key.
- `/itemban adminmod regen` rotates the key while the server is running; old admin mods stop working immediately.
- Web admin now listens on `127.0.0.1` only. Set `webBind` to `0.0.0.0` if you need remote access.
- Constant-time admin-mod checks, admin sessions cleared on disconnect, and web `user` accounts can no longer toggle auto-kick.

### Key features

- **Recipe removal**: recipes whose result is a blacklisted item are stripped from the server recipe table (crafting, smelting, stonecutting, smithing, and other types in that table). Unbanning restores them from a datapack snapshot.
- **Automatic deletion** from player inventories, opened containers, item frames, dropped items, and optional world blocks. Compatible with AE2, Create, Sophisticated Backpacks, Storage Drawers, and other standard containers.
- **NBT / SNBT rules**: ban a whole ID, or only stacks and blocks that match part of an NBT compound.
- **Web admin** at `http://127.0.0.1:25580` by default: item and block blacklists, audit exclusions, feature toggles, Chinese names, logs, and account management. Passwords are stored as PBKDF2 hashes, never as plaintext.
- **In-game GUI** via `/itemban gui`, using a per-server admin mod.
- **Chat announcements** and optional auto-kick / auto-ban (vanilla ban list, with LnsanesBan cascade when that mod is present).
- **Audit logs** in `logs/ItemBan/`. IDs can be excluded from the log while still being removed.
- **Permission node** `itemban.ban` (granted to OP level 2 by default; the console always has it). Creative mode and holders of that node are exempt from scans.

### Install

1. Download the jar that matches your Minecraft version and loader.
2. Put it in the server `mods` folder and restart.
3. Regular players need nothing on the client.
4. For the in-game GUI, copy both the ItemBan jar and the generated admin mod into the administrator's client `mods` folder. Several admin mods can be installed at once; the client picks the one that matches the server you join.

### In-game GUI

On first launch the server creates a random admin key and writes:

- Key file: `config/ItemBan/admin-key.json`
- Admin mod: `config/ItemBan/admin-mods/ItemBan-Admin-*.jar`

To open the GUI you need all of the following:

- Permission node `itemban.ban`
- The matching admin mod on the client
- Command `/itemban gui`

Use `/itemban adminmod` to regenerate the current admin mod. Use `/itemban adminmod regen` to rotate the key; old admin mods become invalid immediately.

### Web admin

The panel listens on localhost only: `http://127.0.0.1:25580`.

To reach it from another machine, set this in `config/ItemBan/config.json`:

```json
"webBind": "0.0.0.0"
```

First start creates a one-time password for the `admin` account (shown in the server log and by `/itemban web`). After that login you must set a real password. You can also set it directly with `/itemban web password <password>`.

Web roles: `admin` / `owner` can manage accounts. A `user` account can edit blacklists and toggles, but cannot change auto-kick and cannot manage accounts.

### Commands

All in-game commands require `itemban.ban`. The console can always run them.

- `/itemban add <item_id> [{nbt}]` — add to the item blacklist
- `/itemban remove <item_id>` — remove from the item blacklist
- `/itemban list` — list the item blacklist
- `/itemban reload` — reload configs, the admin key, and rebuild recipes
- `/itemban announce on/off` — chat announcements
- `/itemban autoban on/off` — auto-kick / auto-ban on violation
- `/itemban dropdetect on/off` — area scan vs instant drop intercept
- `/itemban blockscan on/off` — world block scanning
- `/itemban block add/remove/list` — separate block blacklist
- `/itemban logexclude add/remove/list` — audit exclusion list
- `/itemban gui` — open the in-game admin UI
- `/itemban adminmod` — generate the current server's admin mod
- `/itemban adminmod regen` — rotate the admin key and write a new admin mod
- `/itemban web` — show the web panel URL (and the one-time password if needed)
- `/itemban web password <password>` — set the admin password (hashed)

### Supported versions

Download the jar that matches your server:

- Minecraft 1.16.5 — Forge
- Minecraft 1.18.2 — Forge
- Minecraft 1.19.2 — Forge
- Minecraft 1.20.1 — Forge
- Minecraft 1.21.1 — Forge / NeoForge
- Minecraft 1.21.11 — Forge / NeoForge
- Minecraft 26.1.2 — Forge / NeoForge

### Configuration

Created under `config/ItemBan/`:

- `blacklist.json` — item blacklist
- `block_blacklist.json` — block blacklist
- `config.json` — feature toggles, web port, web bind address, hashed web accounts
- `admin-key.json` — in-game GUI key (kept across restarts)
- `admin-mods/` — generated per-server admin mods

Default `config.json` values include `"webBind": "127.0.0.1"` and `"webPort": 25580`.
