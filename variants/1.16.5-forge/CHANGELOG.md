# ItemBan Changelog

## [2.0.0] - 2026-08-21

### Changed
- 方块黑名单为空时跳过世界体素扫描，只保留展示框检测
- 未列入方块黑名单的方块不再序列化方块实体 NBT
- 所有周期扫描间隔不超过 12 ticks：背包 10、环境 12、掉落物 5
- 同一玩家同时只运行一种周期扫描；匹配在后台线程，删除仍在主线程
- 1.16.5 构建仍面向 Java 8


## [1.0.5] - 2026-08-03

### Added
- 违禁事件现在会**公示并记录坐标**（维度 + x/y/z）
- 日志与聊天公示同时标注来源：背包 / 容器 / 掉落物 / 展示框 / 世界方块
- 展示框、掉落物、违禁方块使用实际发现位置；背包/容器使用玩家当前位置

## [1.0.4] - 2026-08-03

### Fixed
- 与 LnsanesBan 同服使用时自动封禁失败：不再依赖被覆盖的 `/ban` 命令字符串
- 新增 `BanHelper`：优先反射调用 `LnsanesBanApi` / `BanService.banCascade`，失败则写入原版 `UserBanList`，最后才用服务器侧 `/ban` 回退

## [1.0.3] - 2026-05-31

### Added
- 新增审计排除功能：可将特定违禁物品加入审计排除列表，不记录到 `logs/ItemBan/` 日志
- 新增指令管理审计排除列表：
  - `/itemban logexclude add <物品ID>`：添加物品到审计排除列表
  - `/itemban logexclude remove <物品ID>`：从审计排除列表移除物品
  - `/itemban logexclude list`：查看当前审计排除列表

## [1.0.2] - 2026-05-31

### Added
- 新增聊天栏公示功能：当玩家获取违禁物品时，可在聊天栏公示该玩家及物品信息（含 NBT）
- 新增自动踢出功能：获取违禁物品后可自动踢出玩家（封禁原因为“如有问题请联系管理员”）
- 新增指令控制功能开关：
  - `/itemban announce on/off`：开启/关闭聊天栏公示功能
  - `/itemban autoban on/off`：开启/关闭自动踢出功能
- 公示消息支持显示 NBT 信息（超过 60 字符自动截断）

### Changed
- 功能开关从配置文件改为通过指令实时控制（修改后自动保存）
- 审计排除的物品不再触发自动封禁（仅删除 + 公示）

### Added
- 新增环境扫描功能：每 2 秒自动扫描玩家周围 2 个 chunk 范围内的物品展示框（Item Frame）和方块实体
- **新增世界方块检测**：环境扫描现在也会检测世界中的违禁方块（支持方块实体 NBT 匹配，与常规检测规则完全一致）
- 检测到黑名单物品/方块后自动清除，并触发公示/封禁逻辑（若开启）
- 改进掉落物检测逻辑（两种模式互斥）：
  - `/itemban dropdetect on`：**仅启用范围扫描**，每 2 秒主动扫描玩家周围的掉落物，不执行即时拦截
  - `/itemban dropdetect off`：**仅启用即时拦截**，通过 `EntityJoinLevelEvent` 在玩家丢出物品时立即检测并拦截，不执行范围扫描
- 新增世界方块检测开关：`/itemban blockscan on/off`，可独立控制是否检测世界中的违禁方块（关闭后仍保留展示框扫描）
- 新增独立方块黑名单：存储于 `block_blacklist.json`，通过 `/itemban block add/remove/list` 管理，规则与物品黑名单完全一致但存储分离

## [1.0.1] - 2026-05-16

### Changed
- 完善了 NBT 封禁功能，现在支持通过指令直接添加和精确删除带任意 NBT 的封禁规则

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