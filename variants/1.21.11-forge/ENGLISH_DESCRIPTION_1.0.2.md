# ItemBan - Detailed Mod Description

**ItemBan** is a lightweight yet powerful **server-side only** Forge mod that gives server administrators complete control over which items are allowed in the game world.

With ItemBan, you can blacklist any item from any mod. Once blacklisted, the item’s recipes are removed, and any existing instances of the item are automatically and instantly deleted from player inventories, containers, and the world. The mod offers fine-grained control through live commands and supports advanced features such as NBT-based blacklisting, chat announcements, automatic player kicks, and audit logging with exclusion options.

## Key Features

**Recipe Removal**  
All crafting, smelting, stonecutting, and other recipes involving blacklisted items are stripped during world load.

**Real-time Item Deletion**  
Blacklisted items are automatically removed from player inventories, all standard and most modded containers (including AE2, Create, Sophisticated Backpacks, Storage Drawers), and dropped item entities.

**NBT-Based Blacklisting**  
Supports advanced blacklist rules using full SNBT syntax. You can blacklist items with specific enchantments, custom names, attribute modifiers, or any other NBT data.

**Dynamic Feature Controls via Commands**  
All major features can be enabled or disabled live:
- `/itemban announce on/off` — Toggle chat announcements
- `/itemban autoban on/off` — Toggle automatic player kick
- `/itemban logexclude add/remove/list` — Manage audit exclusion list

**Chat Announcements with NBT Display**  
When enabled, the server broadcasts a message whenever a player obtains a blacklisted item, including the item ID and NBT data (truncated if too long).

**Automatic Player Kick**  
When enabled, players who obtain blacklisted items are immediately kicked with the message “如有问题请联系管理员”.

**Audit Logging with Exclusion**  
All violation events are logged daily in `logs/ItemBan/`. Administrators can exclude specific items from being logged using the audit exclusion system.

**Extensive Mod Compatibility**  
Works with AE2, Create, Sophisticated Backpacks, Storage Drawers, and virtually any mod using standard containers. Fluid containers are safely excluded.

**Permission System**  
Creative mode and OP level 2+ players are exempt from item removal.

## Commands

All commands require OP level 2:

- `/itemban add <item_id>{nbt}` — Add blacklist rule (supports NBT)
- `/itemban remove <item_id>{nbt}` — Remove blacklist rule
- `/itemban list` — View blacklist
- `/itemban reload` — Reload configuration
- `/itemban announce on/off` — Toggle chat announcements
- `/itemban autoban on/off` — Toggle automatic kick
- `/itemban dropdetect on/off` — Toggle dropped item (ItemEntity) detection
- `/itemban blockscan on/off` — Toggle world block detection
- `/itemban block add/remove/list` — Manage separate block blacklist (stored in block_blacklist.json)
- `/itemban logexclude add/remove/list` — Manage audit exclusion list

**Environmental Scanning**
- Automatically scans a 2-chunk radius around each player every 2 seconds
- Detects blacklisted items inside Item Frames and other block entities
- Detects blacklisted **blocks** in the world (with full BlockEntity NBT support, consistent with regular detection rules)
- Instantly removes detected items/blocks and triggers announcement/ban if enabled
- Block scanning is limited to ±16 blocks vertically around the player for performance

## Use Cases

- Prevent overpowered or griefing items
- Enforce economy rules by banning dupable items
- Create custom progression with NBT-locked items
- Maintain audit trails while excluding sensitive items from logs

## Technical Details

- Purely server-side
- No client requirements
- Extremely lightweight (inventory scan every 10 ticks, chunk scan every 40 ticks)
- Scans 5×5 chunk area (80×80 blocks) centered on player for Item Frames and block entities

---

**Version**: 1.0.2  
**License**: Apache License 2.0  
**Author**: lnsanes