# ItemBan - English Mod Description

## Short Summary (for mod lists, max ~250 characters)

Server-side item & block blacklist (NBT). Strips recipes and auto-removes banned content from inventories, containers, frames, drops and the world. Web admin on port 25580. Permission `itemban.ban`. Clients not required.

## Full Description

**ItemBan** is a lightweight **server-side only** Minecraft mod. It gives server owners precise control over which items and blocks are allowed. Clients do **not** need to install it.

### Key Features

- **Recipe removal**: recipes whose result is a blacklisted item are stripped from the server recipe table (crafting, smelting, stonecutting, smithing, and other types in that table). Unbanning restores them from a datapack snapshot.
- **Automatic deletion** from player inventories, opened containers, item frames, dropped items, and (optionally) world blocks. Compatible with AE2, Create, Sophisticated Backpacks, Storage Drawers, and other standard containers.
- **NBT / SNBT rules**: ban a whole ID, or only stacks / blocks that match part of an NBT compound.
- **Web admin** (default `http://<server-ip>:25580`): item/block blacklist, audit exclusions, feature toggles, Chinese names, logs, and account management. Passwords are stored as PBKDF2 hashes. First login uses a one-time password from the log or `/itemban web`.
- **Chat announcements** and optional auto-ban (native ban list, with LnsanesBan cascade when that mod is present).
- **Audit logs** in `logs/ItemBan/` (IDs can be excluded from the log while still being removed).
- **Permission node** `itemban.ban` (granted to OP level 2 by default; console always allowed). Creative mode or holders of that node are exempt from scans.

### Commands

All in-game commands require **`itemban.ban`**:

| Command | Description |
|---------|-------------|
| `/itemban add/remove/list` | Item blacklist (optional `{nbt}`) |
| `/itemban reload` | Reload configs and rebuild recipes |
| `/itemban announce on/off` | Chat announcements |
| `/itemban autoban on/off` | Auto-ban on violation |
| `/itemban dropdetect on/off` | Area scan vs instant drop intercept |
| `/itemban blockscan on/off` | World block scanning |
| `/itemban block add/remove/list` | Separate block blacklist |
| `/itemban logexclude add/remove/list` | Audit exclusion list |
| `/itemban web` | Show the web panel URL (and one-time password if needed) |
| `/itemban web password <password>` | Set the admin password (hashed) |

### Supported versions

| Minecraft | Loader |
|-----------|--------|
| 1.16.5 | Forge |
| 1.18.2 | Forge |
| 1.19.2 | Forge |
| 1.20.1 | Forge |
| 1.21.1 | Forge / NeoForge |
| 1.21.11 | Forge / NeoForge |
| 26.1.2 | Forge / NeoForge |

Download the jar that matches your server game version and loader from [GitHub Releases](https://github.com/lnsanes/itemban/releases).

### Configuration

Created under `config/ItemBan/`:

- `blacklist.json` — item blacklist
- `block_blacklist.json` — block blacklist
- `config.json` — feature toggles, web port, hashed web accounts

### License / author

Apache License 2.0 · **lnsanes** · [github.com/lnsanes/itemban](https://github.com/lnsanes/itemban)

**Version**: 2.1.0
