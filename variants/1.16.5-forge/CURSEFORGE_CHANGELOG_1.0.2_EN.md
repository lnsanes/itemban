# ItemBan 1.0.2

## New Features

### Audit Exclusion System
Added the ability to exclude specific blacklisted items from audit logging. Excluded items will still be removed and announced (if enabled), but will **not** be auto-banned and will **not** appear in `logs/ItemBan/` files.

**New Commands:**
- `/itemban logexclude add <itemID>` — Add item to audit exclusion list
- `/itemban logexclude remove <itemID>` — Remove item from audit exclusion list
- `/itemban logexclude list` — View current exclusion list

### Dynamic Feature Controls
All major features can now be toggled live via commands:

- `/itemban announce on/off` — Enable/disable chat announcements when players obtain blacklisted items
- `/itemban autoban on/off` — Enable/disable automatic kick when players obtain blacklisted items
- `/itemban dropdetect on/off` — Enable/disable dropped item (ItemEntity) detection
- `/itemban blockscan on/off` — Enable/disable world block detection
- `/itemban block add/remove/list` — Manage separate block blacklist (independent from item blacklist)
- `/itemban logexclude add/remove/list` — Manage audit exclusion list

### Announcement Enhancements
- Chat announcements now include NBT data of the blacklisted item (truncated after 60 characters)
- Announcements are triggered **after** item removal (fixed execution order)

## Technical Improvements
- All toggles are now command-controlled with automatic persistence
- Removed `announceAfterRemoval` config (order is now fixed: remove → announce → autoban)

## New Feature: Environmental Scanning
- Added automatic scanning of a 2-chunk radius around each player every 2 seconds
- Detects and removes blacklisted items inside Item Frames (and other block entities)
- **New: Detects blacklisted blocks in the world** with full BlockEntity NBT support — detection rules are identical to regular item detection
- Triggers the same announcement / autoban / audit logic as inventory/container detection
- Block scanning limited to ±16 blocks vertically around the player for performance
- Fully respects the audit exclusion list (excluded items/blocks will not trigger ban)

## Upgrade Notes
- Fully compatible with 1.0.1
- Existing configurations and blacklists are preserved

---

**Author**: lnsanes  
**License**: Apache License 2.0