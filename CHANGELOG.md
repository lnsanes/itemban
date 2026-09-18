# ItemBan Changelog

## [2.2.0] - 2026-09-18

### Added
- 游戏内图形管理：拥有权限节点 `itemban.ban`，并且客户端安装了与本服匹配的管理模组后，执行 `/itemban gui` 即可打开
- 首次开服会写入 `admin-key.json`（之后重启沿用同一把密钥），并在 `config/ItemBan/admin-mods/` 生成 `ItemBan-Admin-<keyId>.jar`
- 运行中可用 `/itemban adminmod` 按当前密钥再生成管理模组；`/itemban adminmod regen` 会更换密钥并使旧模组立即失效
- `/itemban reload` 会热重载 `admin-key.json` 中的密钥与哈希
- 客户端可同时安装多个管理模组，进不同服时自动匹配对应密钥

### Changed
- 游戏内管理界面需要客户端也安装 ItemBan 本体 + 该服管理模组；封禁清理逻辑仍可只装服务端
- 网页管理默认只监听 `127.0.0.1`；远程访问需在 `config.json` 设置 `"webBind": "0.0.0.0"`

### Fixed
- 管理模组 HMAC 核对改为恒定时间比较；玩家断线后立即清除游戏内管理会话
- 网页 `user` 不能再改自动踢出；NBT/ID 长度设上限；POSIX 上收紧 `admin-key.json` 权限

## [2.1.0] - 2026-09-10

### Added
- 网页可视化管理：默认 `http://<服务器IP>:25580`，覆盖物品/方块黑名单、审计排除、功能开关、中文图鉴与日志
- `/itemban web` 显示访问地址；首次为一次性密码
- 网页管理改为账号密码登录，密码以 PBKDF2 哈希写入 `config.json`（不含明文）
- 网页账号：admin / owner 可注册或删除账号；一次性初始密码用后失效并须设置正式密码
- 物品/方块名称在网页中显示中文（原版语言文件 + 已加载模组的 `zh_cn`）

### Changed
- 所有管理功能改为权限节点 `itemban.ban`（默认仍授予 OP 等级 2；控制台始终可用）
- 创造模式或拥有 `itemban.ban` 的玩家豁免扫描清理

## [2.0.0] - 2026-08-21

### Changed
- 方块黑名单为空时跳过世界体素扫描，只保留展示框检测
- 未列入方块黑名单的方块不再序列化方块实体 NBT
- 所有周期扫描间隔不超过 12 ticks：背包 10、环境 12、掉落物 5
- 同一玩家同时只运行一种周期扫描；匹配在后台线程，删除仍在主线程
- 1.16.5 构建仍面向 Java 8

## [1.0.8] - 2026-08-18

### Added
- 真正从服务端配方表移除产出为黑名单物品的配方（合成/熔炼/切石/锻造等）
- `/itemban add`、`remove`、`reload` 以及 `/reload` 都会重建配方表
- 取消封禁后从 datapack 快照恢复对应配方，并同步给在线玩家

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