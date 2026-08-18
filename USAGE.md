# ItemBan 模组使用说明

**版本**：1.0.3  
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
  {
    "id": "minecraft:diamond_sword",
    "nbt": {
      "Enchantments": [
        { "id": "minecraft:sharpness", "lvl": 5 }
      ]
    }
  },
  {
    "id": "minecraft:netherite_pickaxe",
    "nbt": {
      "display": {
        "Name": "{\"text\":\"§c禁忌之镐\"}"
      }
    }
  }
]
```

- 支持两种格式：
  - **简单字符串**：封禁该 ID 的所有物品（兼容旧版）
  - **对象格式**：可指定 `id` + `nbt`，只封禁带有特定 NBT 数据的物品
- NBT 支持部分匹配（只要物品的 NBT 包含配置中的字段即可）
- 支持 `display.Name`、`Enchantments`、`AttributeModifiers` 等常见 NBT
- **默认不禁止任何物品**，首次运行会生成空的 `blacklist.json`
- 修改后使用指令 `/itemban reload` 立即生效，无需重启服务器

### 功能开关指令

这些功能现在可以通过指令实时开启或关闭（会自动保存配置）：

- `/itemban announce on` → 开启聊天栏公示
- `/itemban announce off` → 关闭聊天栏公示
- `/itemban autoban on` → 开启自动踢出功能
- `/itemban autoban off` → 关闭自动踢出功能

初始默认值可在 `config/ItemBan/config.json` 中设置：

```json
{
  "publicAnnounce": true,
  "autoBanOnViolation": false
}
```

**公示消息示例**：

> [ItemBan] 玩家 Steve 因持有违禁物品 minecraft:diamond_sword {Enchantments:[{id:"minecraft:sharpness",lvl:5}]} 已被系统清除！

- 如果物品带有 NBT，消息中会显示 NBT 内容（超过 60 个字符会截断并显示 `...`）

---

## 3. 指令系统

所有指令需要 **权限等级 2**（即 OP 权限）才能执行。

**注意**：在单人游戏中，您需要先使用 `/op <你的玩家名>` 给自己 OP 权限，或者在 `ops.json` 中添加自己。

如果您是创造模式玩家但没有 OP，指令仍会因为权限不足而失败。

### 可用指令

| 指令 | 说明 | 示例 |
|------|------|------|
| `/itemban add <物品ID>` | 将物品添加到黑名单 | `/itemban add minecraft:diamond_sword` |
| `/itemban add <物品ID>{nbt}` | 添加带特定 NBT 的物品 | `/itemban add minecraft:diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:5}]}` |
| `/itemban remove <物品ID>` | 移除该物品的所有封禁规则 | `/itemban remove ae2:fluix_crystal` |
| `/itemban remove <物品ID>{nbt}` | 精确移除指定 NBT 的规则 | `/itemban remove minecraft:diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:5}]}` |
| `/itemban list` | 查看当前黑名单列表 | `/itemban list` |
| `/itemban reload` | 重新加载黑名单配置 | `/itemban reload` |
| `/itemban announce on/off` | 开启/关闭聊天栏公示功能 | `/itemban announce on` |
| `/itemban autoban on/off` | 开启/关闭自动踢出功能 | `/itemban autoban off` |
| `/itemban dropdetect on/off` | 开启/关闭掉落物检测（ItemEntity） | `/itemban dropdetect off` |
| `/itemban blockscan on/off` | 开启/关闭世界方块检测 | `/itemban blockscan off` |
| `/itemban block add <方块ID>{nbt}` | 将方块加入独立方块黑名单 | `/itemban block add minecraft:chest{Items:[]}` |
| `/itemban block remove <方块ID>{nbt}` | 从方块黑名单移除 | `/itemban block remove minecraft:chest` |
| `/itemban block list` | 查看方块黑名单 | `/itemban block list` |
| `/itemban logexclude add <物品ID>` | 将物品加入审计排除列表（不记录日志） | `/itemban logexclude add minecraft:diamond` |
| `/itemban logexclude remove <物品ID>` | 从审计排除列表移除物品 | `/itemban logexclude remove minecraft:diamond` |
| `/itemban logexclude list` | 查看当前审计排除列表 | `/itemban logexclude list` |

### 环境扫描功能（自动触发）

模组会**每 2 秒**自动扫描以玩家为中心的 **2 个区块（chunk）半径**范围内的黑名单物品和方块，包括：

- 物品展示框（Item Frame）中展示的物品
- 世界中的违禁方块（支持方块实体 NBT 匹配，与常规检测规则完全一致）
- 其他方块实体中的物品（基础支持）

**检测到违禁物品/方块后**：
- 自动清除（清空展示框 / 将方块替换为空气）
- 触发聊天公示（如果开启 `announce`，方块检测消息为“破坏违禁方块”）
- 触发自动封禁（如果开启 `autoban`）
- 记录审计日志（除非该物品/方块在排除列表中）

**性能说明**：
- 扫描节流至每 40 ticks（2 秒）一次
- 仅扫描玩家周围 5×5 chunk 区域（约 80×80 方块）
- 方块扫描仅检查玩家上下 16 格高度范围，避免全高度扫描导致性能问题
- 创造模式和 OP 玩家完全豁免

**掉落物检测开关**：
- 默认开启（`on`）：**仅使用范围扫描**，每 **2 ticks** 扫描玩家周围 2 chunk 内的掉落物（不使用即时拦截）
- 关闭（`off`）：**仅使用即时拦截**，通过 `EntityJoinLevelEvent` 在玩家丢出物品时立即检测并拦截（不使用范围扫描）
- 两种模式互斥，由开关控制
- 可通过 `/itemban dropdetect on/off` 控制

**世界方块检测开关**：
- 默认开启，自动检测玩家周围世界中的违禁方块（支持 NBT 匹配）
- 可通过 `/itemban blockscan on/off` 控制
- 关闭后，仍会扫描物品展示框，但不会检测世界方块

**独立方块黑名单**：
- 方块黑名单存储在独立的 `config/ItemBan/block_blacklist.json` 文件中，与物品黑名单分开
- 使用 `/itemban block add/remove/list` 管理方块黑名单
- 方块黑名单的匹配规则（含 NBT）与物品黑名单完全一致
- 审计排除列表对物品和方块均生效

**审计排除功能说明**：
- 被加入审计排除列表的物品，仍然会被正常删除、公示、踢出（如果开启相应功能）
- 但不会被记录到 `logs/ItemBan/` 目录下的日志文件中
- 适合用于不想留下记录的特殊违禁物品

**注意**：以上所有指令修改后会自动保存配置，下次重启服务器依然生效。

> **提示**：
> - `<物品ID>` 支持 `modid:item` 格式
> - 支持在物品 ID 后直接附加 SNBT（如 `{Enchantments:[...]}`），可封禁任意 NBT 组合
> - `remove` 命令支持精确删除带 NBT 的规则（不带 NBT 则删除该物品所有规则）
> - `list` 命令会显示带 NBT 的规则
> - 指令参数使用 `greedyString`，可正确解析冒号和花括号

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
