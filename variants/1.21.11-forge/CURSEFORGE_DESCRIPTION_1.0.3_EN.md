# ItemBan

**Server-side item & block blacklist control for Minecraft Forge**

ItemBan gives server admins precise control over which items and blocks are allowed on the server. Blacklisted content is removed automatically from inventories, containers, item frames, dropped items, and (optionally) the world — with live commands, NBT rules, chat announcements, optional auto-ban, and audit logs.

**Clients do not need to install this mod.**

---

## Features

### Item Blacklist
- Ban any item by ID (`modid:item`)
- Full **NBT / SNBT** support (enchantments, custom names, attributes, etc.)
- Automatic removal from player inventories, opened containers, and most modded storage
- Compatible with AE2, Create, Sophisticated Backpacks, Storage Drawers, and standard containers

### Separate Block Blacklist
- Independent list stored in `block_blacklist.json`
- Same NBT matching rules as items
- Optional world scanning removes banned blocks near players

### Dropped Item Detection (two modes)
| Mode | Command | Behavior |
|------|---------|----------|
| Area scan (default) | `/itemban dropdetect on` | Every **2 ticks**, scan ~2 chunks around the player and delete banned drops |
| Instant intercept | `/itemban dropdetect off` | Cancel banned item entities when they spawn (1.0.2-style) |

### Environmental Scanning
- Every ~2 seconds, scan a **2-chunk radius** around each player
- Clears banned items from **item frames**
- Optional **world block scan** (`blockscan`) within ±16 blocks vertically

### Announcements & Auto-Ban
- Optional chat announcements (shows item/block ID + truncated NBT)
- Optional permanent profile ban via `/ban`, then kick
- Order: **remove → announce → ban**
- Items on the audit exclusion list: still removed & announced, but **not logged and not banned**

### Audit Logging
- Daily logs under `logs/ItemBan/`
- Exclude specific IDs from logs with `/itemban logexclude`

### Permissions
- Creative mode and OP level 2+ are fully exempt

---

## Commands

All commands require **OP level 2**:

| Command | Description |
|---------|-------------|
| `/itemban add <id>{nbt}` | Add item blacklist rule |
| `/itemban remove <id>{nbt}` | Remove item rule(s) |
| `/itemban list` | List item blacklist |
| `/itemban reload` | Reload configs |
| `/itemban announce on/off` | Chat announcement toggle |
| `/itemban autoban on/off` | Auto-ban toggle |
| `/itemban dropdetect on/off` | Drop detection mode (area vs instant) |
| `/itemban blockscan on/off` | World block scanning toggle |
| `/itemban block add/remove/list` | Manage block blacklist |
| `/itemban logexclude add/remove/list` | Manage audit exclusion list |

---

## Configuration

Created automatically under `config/ItemBan/`:

- `blacklist.json` — item blacklist  
- `block_blacklist.json` — block blacklist  
- `config.json` — feature toggles & exclusions  

Example item rule:

```json
[
  "minecraft:nether_star",
  {
    "id": "minecraft:diamond_sword",
    "nbtString": "{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}"
  }
]
```

---

## Requirements

- **Minecraft**: 1.20.1  
- **Mod Loader**: Forge 47.3.0+  
- **Side**: Server (client optional / not required)  

---

## License

Apache License 2.0

---

## Author

lnsanes

---

*Lightweight, server-only control for banned items and blocks — manage everything in-game with commands.*
