# ItemBan 模组使用说明

**版本**：1.0.0  
**适用游戏版本**：Minecraft 1.20.1 (Forge 47.3.0+)  
**模组类型**：服务端模组（客户端无需安装）

---

## 1. 安装方法

1. 将编译后的 `ItemBan-1.0.0.jar` 文件放入服务器或单人游戏的 `mods` 文件夹。
2. 重启游戏/服务器。
3. 配置文件会自动生成于 `config/ItemBan/blacklist.json`。

> **提示**：本模组为纯服务端模组，客户端玩家无需安装即可正常游戏。

---

## 2. 配置文件

配置文件路径：`config/ItemBan/blacklist.json`

**格式示例**：

```json
[
  "minecraft:diamond_sword",
  "minecraft:netherite_pickaxe",
  "create:creative_motor",
  "ae2:fluix_crystal"
]
```

- 每行一个物品 ID（格式为 `模组ID:物品ID`）。
- 支持所有模组的物品（包括 AE2、Create、Sophisticated Backpacks 等）。
- **默认不禁止任何物品**，首次运行会生成空的 `blacklist.json`。
- 修改后使用指令 `/itemban reload` 立即生效，无需重启服务器。

---

## 3. 指令系统

所有指令需要 **权限等级 2**（即 OP 权限）才能执行。

**注意**：在单人游戏中，您需要先使用 `/op <你的玩家名>` 给自己 OP 权限，或者在 `ops.json` 中添加自己。

如果您是创造模式玩家但没有 OP，指令仍会因为权限不足而失败。

### 可用指令

| 指令 | 说明 | 示例 |
|------|------|------|
| `/itemban add <物品ID>` | 将物品添加到黑名单 | `/itemban add create:creative_fluid_tank` |
| `/itemban remove <物品ID>` | 从黑名单移除物品 | `/itemban remove ae2:fluix_crystal` |
| `/itemban list` | 查看当前黑名单列表 | `/itemban list` |
| `/itemban reload` | 重新加载黑名单配置 | `/itemban reload` |

> **提示**：`<物品ID>` 支持完整格式 `模组ID:物品ID`（如 `minecraft:diamond`、`create:creative_motor`），指令参数已修复可正确解析冒号。

### 指令权限

- 默认需要 OP（权限等级 2）
- 创造模式玩家不受黑名单限制（物品不会被删除）
- 普通玩家进入黑名单物品后，物品会被立即删除

---

## 4. 功能说明

### 核心功能

- **合成配方移除**：黑名单物品的所有合成配方在游戏启动时被移除。
- **实时清理**：
  - 玩家背包每 tick 扫描并删除黑名单物品。
  - 打开任何容器（包括箱子、AE2 终端、Create 仓库、精妙背包等）时自动清理。
  - 物品掉落时立即删除。
- **兼容性**：支持大多数使用标准 `Container` 的模组，包括：
  - Applied Energistics 2 (AE2)
  - Create（机械动力）
  - Sophisticated Backpacks（精妙背包）
  - Storage Drawers（抽屉）
  - 及其他主流存储模组

### 豁免机制

以下情况的玩家**不会**被删除黑名单物品：

- 创造模式玩家
- 拥有 OP 权限（权限等级 ≥ 2）的玩家

---

## 5. 日志系统

- 日志文件位置：`logs/ItemBan/`
- 每天生成一个独立的日志文件（如 `2026-05-16.log`）
- 记录内容包括：
  - 黑名单物品被删除的事件
  - 玩家获取黑名单物品的记录
  - 配置加载/重载信息

---

## 6. 注意事项

1. **子 ID 支持**：可配置 `modid:item` 格式的物品 ID。
2. **服务端部署**：强烈建议仅在服务端安装，客户端无需安装。
3. **性能优化**：背包扫描已进行节流处理（每 10 ticks 一次），对服务器性能影响极小。
4. **流体容器**：Create 的流体容器（如水槽、管道）不会被扫描，避免误删流体。
5. **热重载**：修改配置文件后使用 `/itemban reload` 即可生效，无需重启。

---

## 7. 常见问题

**Q: 为什么黑名单物品还在合成表里？**  
A: 请确认物品 ID 正确，并使用 `/itemban reload` 重载配置。

**Q: AE2 终端里的物品没被删除？**  
A: AE2 深层存储网络需要玩家打开终端界面，打开时会自动清理。

**Q: 如何临时允许某玩家使用黑名单物品？**  
A: 给予该玩家 OP 权限或切换至创造模式即可。

---

**构建日期**：2026-05-16  
**开发者**：ItemBan Team

如有问题，请提交 Issue 或联系服务器管理员。
