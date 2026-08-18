# ItemBan 1.0.2

## New Features

### Audit Exclusion System
Added the ability to exclude specific blacklisted items from audit logging. Excluded items will still be removed, announced, and auto-banned (if enabled), but will **not** appear in `logs/ItemBan/` files.

**New Commands:**
- `/itemban logexclude add <itemID>` — Add item to audit exclusion list
- `/itemban logexclude remove <itemID>` — Remove item from audit exclusion list
- `/itemban logexclude list` — View current exclusion list

### Dynamic Feature Controls
All major features can now be toggled live via commands:

- `/itemban announce on/off` — Enable/disable chat announcements when players obtain blacklisted items
- `/itemban autoban on/off` — Enable/disable automatic kick when players obtain blacklisted items
- `/itemban logexclude add/remove/list` — Manage audit exclusion list

### Announcement Enhancements
- Chat announcements now include NBT data of the blacklisted item (truncated after 60 characters)
- Announcements are triggered **after** item removal (fixed execution order)

## Technical Improvements
- All toggles are now command-controlled with automatic persistence
- Removed `announceAfterRemoval` config (order is now fixed: remove → announce → autoban)

## Upgrade Notes
- Fully compatible with 1.0.1
- Existing configurations and blacklists are preserved
- New config options will be added automatically on first load

---

**Version**: 1.0.2  
**License**: Apache License 2.0  
**Author**: lnsanes