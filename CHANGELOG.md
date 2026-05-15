# ItemBan Changelog

## [1.0.0] - 2026-05-16

### Added
- Initial release of ItemBan
- Blacklist system supporting any item ID in `modid:item` format
- Automatic removal of all recipes (crafting, smelting, etc.) for blacklisted items on world load
- Real-time item deletion from:
  - Player inventory (main, hotbar, offhand)
  - All standard containers and most modded containers
  - Applied Energistics 2 terminals and networks
  - Create depots, vaults, crafters, belts, and funnels
  - Sophisticated Backpacks (worn and placed)
  - Storage Drawers and other storage mods
  - Dropped item entities
- Four live management commands (require OP level 2):
  - `/itemban add <item_id>`
  - `/itemban remove <item_id>`
  - `/itemban list`
  - `/itemban reload`
- Creative mode and OP players are completely exempt from item removal
- Pure server-side mod — clients do not need to install anything
- Configuration file at `config/ItemBan/blacklist.json`
- Daily audit logs written to `logs/ItemBan/`
- Performance-optimized scanning (throttled to every 10 ticks)
- Custom mod logo

### Technical
- Licensed under Apache License 2.0
- Built for Minecraft 1.20.1 + Forge 47.3.0+
- Uses Forge event system for reliable container and inventory handling
- Supports greedy string parsing for item IDs containing colons

### Notes
- First public release
- No default items are blacklisted (empty blacklist on first run)
- Fluid containers are excluded from scanning for safety

---

**Full Description & Download:** See the main mod page.