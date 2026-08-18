# ItemBan 1.0.1 Release Notes

## 主要更新

- 完善了 NBT 封禁功能
- 现在支持通过指令直接添加和精确删除带**任意 NBT** 的封禁规则

## 新增功能

### 指令增强

- `/itemban add <物品ID>{nbt}`  
  示例：`/itemban add minecraft:diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:5}]}`

- `/itemban remove <物品ID>{nbt}`  
  支持精确删除指定 NBT 的规则（不带 NBT 则删除该物品所有规则）

- `/itemban list`  
  会清晰显示带 NBT 的封禁规则

### 配置支持

`blacklist.json` 现在支持两种格式：

```json
[
  "minecraft:diamond_sword",
  {
    "id": "minecraft:diamond_sword",
    "nbtString": "{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}"
  }
]
```

- 支持完整 SNBT 语法（与原版 `/give` 命令一致）
- 支持任意 NBT 结构（附魔、显示名称、属性修饰器等）
- 采用部分匹配逻辑（只要物品 NBT 包含配置中的字段即可触发封禁）

## 修复

- 修复了 NBT List 处理导致的编译错误
- 提升了 NBT 匹配的兼容性和稳定性

## 升级建议

直接替换 `mods` 文件夹中的旧 jar 即可，无需修改配置。旧版配置（纯字符串格式）完全兼容。

---

**下载**：`ItemBan-1.0.1.jar`