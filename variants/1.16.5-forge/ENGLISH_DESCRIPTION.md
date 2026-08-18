# ItemBan - English Mod Description

## Short Summary (for mod lists)

A lightweight server-side Forge mod for Minecraft 1.20.1 that lets administrators blacklist items. Blacklisted items have their recipes removed and are automatically deleted from player inventories, containers, and when dropped. Fully compatible with AE2, Create, Sophisticated Backpacks, Storage Drawers, and most other storage mods. Creative mode and OP players are exempt. Manage the blacklist with simple commands.

## Full Description

**ItemBan** is a powerful yet lightweight server-side only mod designed for Minecraft 1.20.1 (Forge). It gives server owners complete control over which items are allowed in the world.

### Key Features

- **Recipe Removal**: All crafting, smelting, and other recipes for blacklisted items are stripped at load time.
- **Automatic Deletion**: Blacklisted items are instantly removed from:
  - Player inventories (including hotbar and offhand)
  - Any container (chests, AE2 terminals, Create depots, Sophisticated Backpacks, drawers, etc.)
  - Item entities when dropped
- **Wide Compatibility**: Works seamlessly with popular storage and automation mods including Applied Energistics 2, Create, Sophisticated Backpacks, Storage Drawers, and any mod using standard containers.
- **Permission System**: Players in Creative mode or with OP level 2+ are completely exempt from item removal.
- **Easy Management**: Use four simple commands to add, remove, list, or reload the blacklist on the fly.
- **Configuration**: Blacklist is stored in a clean JSON file (`config/ItemBan/blacklist.json`) that supports any item ID in `modid:item` format.
- **Logging**: Daily log files are written to `logs/ItemBan/` for auditing.

### Commands

- `/itemban add <item_id>` — Add an item to the blacklist
- `/itemban remove <item_id>` — Remove an item from the blacklist
- `/itemban list` — Display the current blacklist
- `/itemban reload` — Reload the blacklist from disk

All commands require permission level 2 (OP).

### Use Cases

- Prevent players from obtaining overpowered or griefing items
- Disable specific Create contraptions or AE2 components on your server
- Create custom progression by locking certain items behind permissions
- Maintain a controlled economy by banning high-value items

### Technical Details

- Pure server-side mod — clients do not need to install anything
- Extremely lightweight with inventory scanning throttled to every 10 ticks
- Safe for use on large public servers

---

**Version**: 1.0.0  
**Minecraft**: 1.20.1  
**Forge**: 47.3.0+  
**License**: All Rights Reserved

This mod is ideal for any server owner who wants fine-grained control over item availability without heavy performance impact.