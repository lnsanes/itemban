# ItemBan

[![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5--26.1.2-green)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-supported-orange)](https://files.minecraftforge.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-supported-red)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

服务端专用物品黑名单模组。封禁物品会从配方表移除产出、并从背包 / 容器 / 掉落物 / 世界中自动清除。客户端无需安装。

A lightweight **server-side only** Minecraft mod that blacklists items: banned-item recipes are stripped, and matching items are removed from inventories, containers, and the world.

[使用说明](USAGE.md) • [更新日志](CHANGELOG.md) • [English Description](ENGLISH_DESCRIPTION.md)

## 下载

请从 [GitHub Releases](https://github.com/lnsanes/itemban/releases) 下载对应游戏版本的 jar，放进服务端 `mods` 文件夹后重启。

当前发布：

- [v1.0.8](https://github.com/lnsanes/itemban/releases/tag/v1.0.8)（推荐）
- [v1.0.7](https://github.com/lnsanes/itemban/releases/tag/v1.0.7)

## 支持版本

| Minecraft | Loader | 构建文件名 |
|-----------|--------|------------|
| 1.16.5 | Forge | `ItemBan-x.x.x-1.16.5-Forge.jar` |
| 1.18.2 | Forge | `ItemBan-x.x.x-1.18.2-Forge.jar` |
| 1.19.2 | Forge | `ItemBan-x.x.x-1.19.2-Forge.jar` |
| 1.20.1 | Forge | `ItemBan-x.x.x-1.20.1-Forge.jar` |
| 1.21.1 | Forge / NeoForge | `ItemBan-x.x.x-1.21.1-Forge.jar` / `...-NeoForge.jar` |
| 1.21.11 | Forge / NeoForge | `ItemBan-x.x.x-1.21.11-Forge.jar` / `...-NeoForge.jar` |
| 26.1.2 | Forge / NeoForge | `ItemBan-x.x.x-26.1.2-Forge.jar` / `...-NeoForge.jar` |

仓库根目录是 **Minecraft 1.20.1 Forge** 的源码。其它游戏版本 / 加载器的 1.0.8 源码在 [`variants/`](variants/)。已编译的 jar 也在 [`dist/`](dist/)。

## 功能

- 从服务端配方表移除产出为黑名单物品的配方（合成 / 熔炼 / 切石 / 锻造等）
- 自动清除背包、容器、掉落物、展示框和世界方块中的违禁物品
- 兼容 AE2、Create、精妙背包、抽屉等常见存储模组
- 创造模式与 OP 玩家豁免
- `/itemban` 指令实时管理黑名单
- 日志写到 `logs/ItemBan/`
- 纯服务端模组，玩家客户端不用装
- 配置文件：`config/ItemBan/blacklist.json`

## 指令

需要 OP 等级 2：

| 指令 | 说明 |
|------|------|
| `/itemban add <item_id>` | 加入物品黑名单 |
| `/itemban remove <item_id>` | 移出物品黑名单 |
| `/itemban list` | 查看物品黑名单 |
| `/itemban reload` | 从磁盘重载黑名单并重建配方表 |

## 从源码构建（1.20.1 Forge）

需要 Java 17。

```bash
./gradlew build
```

产物在 `build/libs/`。

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
