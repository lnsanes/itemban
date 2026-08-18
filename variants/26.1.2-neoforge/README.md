# ItemBan — NeoForge 1.21 / 1.21.1

纯服务端物品/方块黑名单模组（Data Components 时代）。

## 与旧版（Forge 1.20.1）的关系
- 本项目位于 `itemban-neoforge/`，**不修改** 旧目录 `itemban/`
- 1.20.1 Forge 版继续维护旧逻辑（NBT）
- 1.21+ 物品数据迁移到 **Data Components**；当前实现对带 NBT 的旧规则做“按 ID 匹配”降级处理，方块实体仍支持 NBT 包含匹配（1.21 中方块实体 NBT 仍存在）

## 构建要求
- JDK 21
- 可联网下载 NeoGradle/NeoForge（首次构建）

## 构建命令
在 `itemban-neoforge/` 目录下：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"

# 1.21.1 (默认)
.\gradlew.bat build

# 1.21 (显式切换；PowerShell 中参数必须加引号，防止 21.0.167 被拆分)
.\gradlew.bat build "-Pmc=1.21" "-Pminecraft_version=1.21" "-Pneo_version=21.0.167"
```

> 已验证构建通过（Gradle 8.14 + NeoGradle 7.1.38 + NeoForge 21.1.93 / 21.0.167）。
> 本机 SSL 环境特殊：`gradle.properties` 已加 `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT`，让 Java 信任 Windows 证书库以正常下载 Mojang/NeoForge 资源；wrapper 指向本机 Gradle 8.14 离线包。

> 说明：1.21 对应 NeoForge 主版本 21.0.x；1.21.1 对应 21.1.x。通过 `-Pmc` 与 `-Pneo_version` 切换即可。产物命名会包含 `-Pmc` 值（如 `ItemBan-NeoForge-1.21.1-1.0.3.jar`）。

## 首次准备 Gradle Wrapper
如果当前目录没有 `gradlew.bat`，请在旧项目里执行一次（或从旧目录复制）：

```powershell
cd "d:\桌面\新建文件夹\itemban"
.\gradlew.bat wrapper --gradle-version 8.8
# 然后把 gradlew* 与 gradle/ 复制到 itemban-neoforge
```

## 配置与指令
- 配置目录：`config/ItemBan/`
- 物品黑名单：`blacklist.json`
- 方块黑名单：`block_blacklist.json`
- 指令：`/itemban ...`（add/remove/list/reload/announce/autoban/dropdetect/blockscan/block/logexclude）

## 功能
- 背包/容器/展示框/掉落物/世界方块检测
- 公示、自动封禁、审计排除
- 掉落物双模式：`dropdetect on`（每 2 ticks 范围扫描）/ `off`（生成时即时拦截）

## 注意（1.21 迁移差异）
- 物品 `getTag/hasTag` 已移除：当前物品匹配以 ID 为主；带 NBT 的物品规则在 1.21 中先按 ID 匹配（后续可做 Data Components 精确匹配）
- 方块实体 NBT 仍可用（1.21 保留 NBT），方块黑名单的 NBT 包含匹配继续有效

## License
Apache License 2.0
