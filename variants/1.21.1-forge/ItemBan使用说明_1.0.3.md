# ItemBan 模组使用说明

**版本**：1.0.3  
**适用游戏版本**：Minecraft 1.20.1（Forge 47.3.0+）  
**模组类型**：纯服务端模组（客户端无需安装）  
**许可证**：Apache License 2.0  

---

## 1. 安装方法

1. 将 `ItemBan-1.0.3.jar` 放入服务器（或单人世界）的 `mods` 文件夹。
2. 重启游戏 / 服务器。
3. 首次运行会自动生成配置目录：`config/ItemBan/`。

> 客户端玩家无需安装本模组。

---

## 2. 配置文件

| 文件 | 用途 |
|------|------|
| `config/ItemBan/blacklist.json` | 物品黑名单 |
| `config/ItemBan/block_blacklist.json` | 方块黑名单（独立） |
| `config/ItemBan/config.json` | 功能开关与审计排除列表 |

### 物品 / 方块黑名单格式示例

```json
[
  "minecraft:diamond_sword",
  {
    "id": "minecraft:diamond_sword",
    "nbtString": "{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}"
  }
]
```

- 简单字符串：封禁该 ID 的全部物品 / 方块  
- 带 NBT：只匹配包含指定 NBT 的物品 / 方块（部分匹配）  
- 默认不封禁任何内容；修改后可用 `/itemban reload` 热重载  

### config.json 主要字段

```json
{
  "publicAnnounce": true,
  "autoBanOnViolation": false,
  "detectDroppedItems": true,
  "detectWorldBlocks": true,
  "excludeFromLog": []
}
```

---

## 3. 指令一览（需要 OP 权限等级 ≥ 2）

| 指令 | 说明 |
|------|------|
| `/itemban add <物品ID>{nbt}` | 加入物品黑名单（支持 NBT） |
| `/itemban remove <物品ID>{nbt}` | 移除物品黑名单规则 |
| `/itemban list` | 查看物品黑名单 |
| `/itemban reload` | 重新加载配置 |
| `/itemban announce on/off` | 聊天公示开关 |
| `/itemban autoban on/off` | 自动封禁开关 |
| `/itemban dropdetect on/off` | 掉落物检测模式开关 |
| `/itemban blockscan on/off` | 世界方块检测开关 |
| `/itemban block add <方块ID>{nbt}` | 加入方块黑名单 |
| `/itemban block remove <方块ID>{nbt}` | 移除方块黑名单规则 |
| `/itemban block list` | 查看方块黑名单 |
| `/itemban logexclude add/remove/list` | 管理审计排除列表 |

所有指令修改会自动保存，重启后仍生效。

---

## 4. 核心功能

### 物品清理
- 玩家背包（主背包、快捷栏、副手）：约每 0.5 秒扫描一次  
- 打开容器时清理（含多数模组容器：AE2、Create、精妙背包、抽屉等）  
- 创造模式玩家与 OP（权限 ≥ 2）完全豁免  

### 掉落物检测（两种模式互斥）
| 开关 | 行为 |
|------|------|
| `dropdetect on`（默认） | **范围扫描**：每 2 ticks 扫描玩家周围约 2 chunk 内的掉落物并删除 |
| `dropdetect off` | **即时拦截**：仅在掉落物生成时拦截（1.0.2 风格） |

检测到后会删除掉落物，并按配置触发公示 / 封禁 / 审计。

### 环境扫描（展示框 + 可选方块）
- 约每 2 秒扫描玩家周围 2 chunk 半径（约 80×80）  
- 清空展示框中的违禁物品  
- `blockscan on` 时：检测世界中的违禁方块（独立方块黑名单），替换为空气；高度约玩家上下 16 格  

### 公示与自动封禁
1. 先删除违禁物品 / 方块  
2. 若开启公示：在聊天栏广播玩家与物品 / 方块信息（可含 NBT，过长截断）  
3. 若开启自动封禁且物品不在审计排除列表：执行 `/ban` 并踢出  

### 审计排除
- 排除列表中的物品仍会删除、仍可公示  
- **不写审计日志、不触发自动封禁**  

---

## 5. 日志

- 路径：`logs/ItemBan/yyyy-MM-dd.log`  
- 记录玩家获取违禁物品、破坏违禁方块等事件  

---

## 6. 豁免与注意

- 创造模式、OP（≥2）不受物品删除影响  
- 方块 / 展示框 / 掉落物扫描同样豁免 OP 与创造  
- 建议仅装服务端；客户端无需安装  
- 方块与物品使用两套独立黑名单，互不影响  

---

## 7. 常见问题

**Q：合成表里还能看到违禁物品？**  
确认 ID 正确后执行 `/itemban reload`。

**Q：AE2 终端里还有违禁物？**  
打开终端界面时会清理；未打开的深层网络需玩家打开界面触发。

**Q：如何临时放行某玩家？**  
给 OP 或切创造模式。

**Q：关掉 dropdetect 后掉落物还会不会删？**  
会。`off` 时改为即时拦截模式，玩家丢出违禁物仍会被立刻拦截删除。

---

**开发者**：lnsanes / ItemBan Team  
**构建版本**：1.0.3  
