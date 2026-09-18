# ItemBan

[![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5--26.1.2-green)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-supported-orange)](https://files.minecraftforge.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-supported-red)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

服务端物品黑名单模组。封禁物品会从配方表移除产出、并从背包 / 容器 / 掉落物 / 世界中自动清除。可用网页面板（默认端口 25580）或游戏内图形界面管理。普通玩家客户端无需安装。

A Minecraft mod that blacklists items: banned-item recipes are stripped, matching items are removed from inventories, containers, and the world. Web admin (port 25580) and an in-game GUI (admin token mod + `itemban.ban`) are available.

[使用说明](USAGE.md) • [更新日志](CHANGELOG.md) • [English Description](ENGLISH_DESCRIPTION.md)

## 下载

请从 [GitHub Releases](https://github.com/lnsanes/itemban/releases) 下载对应游戏版本的 jar，放进服务端 `mods` 文件夹后重启。

当前发布：

- [v2.2.0](https://github.com/lnsanes/itemban/releases/tag/v2.2.0)（游戏内管理 + 按服管理模组）
- [v2.1.0](https://github.com/lnsanes/itemban/releases/tag/v2.1.0)（全平台）

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

仓库根目录是 **Minecraft 1.20.1 Forge** 的源码（2.2.0）。其它游戏版本 / 加载器在 [`variants/`](variants/)。已编译的 jar 在 [`dist/`](dist/)。

## 2.2.0 新功能

全平台新增游戏内图形管理界面。封禁清理仍可只装服务端；普通玩家客户端不必安装。

打开管理界面需要同时满足：

- 拥有权限节点 `itemban.ban`
- 客户端安装了与本服密钥匹配的管理模组
- 游戏内执行 `/itemban gui`

首次开服会生成本服专用密钥和管理模组：

- 密钥文件：`config/ItemBan/admin-key.json`（之后重启沿用同一把密钥）
- 管理模组：`config/ItemBan/admin-mods/ItemBan-Admin-*.jar`（复制到管理员客户端 `mods` 即可）
- 客户端可同时放多个服的管理模组，进哪台服就用哪把密钥
- 运行中执行 `/itemban adminmod` 可按当前密钥再生成；`/itemban adminmod regen` 会更换密钥，旧模组立即失效

网页管理默认只监听本机 `127.0.0.1:25580`。若要从其它电脑访问，在 `config/ItemBan/config.json` 中设置 `"webBind": "0.0.0.0"`。首次会生成一次性密码（日志或 `/itemban web` 可见），登录后必须设置正式密码。网页 `user` 账号不能修改自动踢出，也不能管理账号。

此版本同时将管理校验改为恒定时间比较，并在玩家断线后立即清除游戏内管理会话。

## 功能

- 从服务端配方表移除产出为黑名单物品的配方（合成 / 熔炼 / 切石 / 锻造等）
- 自动清除背包、容器、掉落物、展示框和世界方块中的违禁物品
- 兼容 AE2、Create、精妙背包、抽屉等常见存储模组
- 创造模式与拥有 `itemban.ban` 的玩家豁免
- `/itemban` 指令、网页可视化管理（默认只监听本机 25580 端口）与游戏内 `/itemban gui`
- 开服随机密钥 + 仅匹配该服的管理模组（可热重载 / 运行中再生成；重启不换密钥）
- 日志写到 `logs/ItemBan/`
- 封禁清理可不装客户端；游戏内管理需要本体 + 该服管理模组
- 配置文件：`config/ItemBan/blacklist.json`、`config.json`、`admin-key.json`

## 指令

需要权限节点 **`itemban.ban`**（默认 OP 等级 2 拥有；可用 LuckPerms 等插件单独授予）。控制台始终可用。

| 指令 | 说明 |
|------|------|
| `/itemban add <item_id>` | 加入物品黑名单 |
| `/itemban remove <item_id>` | 移出物品黑名单 |
| `/itemban list` | 查看物品黑名单 |
| `/itemban reload` | 从磁盘重载黑名单、管理密钥并重建配方表 |
| `/itemban gui` | 打开游戏内管理界面（需 `itemban.ban` + 匹配的管理模组） |
| `/itemban adminmod` | 按当前密钥生成管理模组 |
| `/itemban adminmod regen` | 热更换密钥并生成新管理模组 |
| `/itemban web` | 显示网页管理地址；未设置正式密码时显示一次性密码 |
| `/itemban web password <密码>` | 设置 admin 正式密码（哈希存储） |

## 从源码构建（1.20.1 Forge）

需要 Java 17。

```bash
./gradlew build
```

产物在 `build/libs/`。

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
