# ItemBan 1.0.3 — Changelog

## What's New

### Environmental & Block Control
- Scan a **2-chunk radius** around players every ~2 seconds
- Clear blacklisted items from **item frames**
- **Separate block blacklist** (`block_blacklist.json`) with NBT support
- Optional world block removal (`/itemban blockscan on/off`)
- Manage blocks via `/itemban block add|remove|list`

### Dropped Item Detection Modes
- **`dropdetect on`**: area scan every **2 ticks** around the player (delete banned drops)
- **`dropdetect off`**: instant intercept on spawn only (classic 1.0.2 behavior)
- Modes are mutually exclusive

### Announcements, Auto-Ban & Audit
- Chat announcements with optional NBT display
- Auto-ban (permanent profile ban + kick) after removal & announce
- Audit exclusion: excluded items are still removed/announced, but **not logged and not banned**
- Commands: `/itemban announce`, `/itemban autoban`, `/itemban logexclude`

### Other
- Live command toggles with automatic config save
- OP / creative exemption across inventory, drops, frames, and block scans
- Compatible with AE2, Create, Sophisticated Backpacks, Storage Drawers, and most container mods

## Upgrade Notes
- Safe upgrade from 1.0.1 / 1.0.2
- Existing `blacklist.json` is preserved
- New files may appear: `block_blacklist.json`, updated `config.json` keys

## Requirements
- Minecraft **1.20.1**
- Forge **47.3.0+**
- Server-side only (clients do not need the mod)

**License**: Apache License 2.0  
**Author**: lnsanes
