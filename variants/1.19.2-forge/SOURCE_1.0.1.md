# ItemBan 1.0.1 源代码说明

## 源代码获取方式

本次更新（v1.0.1）的源代码**已通过 Git 推送到仓库**，无需单独上传源代码文件。

GitHub 在创建 Release 时会自动生成以下两个源代码压缩包供用户下载：

- `Source code (zip)`
- `Source code (tar.gz)`

---

## 本次更新修改的源代码文件

| 文件路径 | 修改内容 |
|----------|----------|
| `src/main/java/com/itemban/ConfigHandler.java` | 完善 NBT 封禁逻辑，支持 `nbtString` 格式、精确匹配删除、任意 NBT 支持 |
| `src/main/java/com/itemban/CommandHandler.java` | `add` 和 `remove` 命令支持解析 `{nbt}` 格式参数 |
| `gradle.properties` | 版本号更新为 1.0.1 |
| `CHANGELOG.md` | 添加 1.0.1 版本记录 |
| `USAGE.md` | 更新指令说明，增加 NBT 用法示例 |

---

## 如果需要手动打包源代码

你可以将以下干净的源代码文件夹打包成 zip 上传：

**文件夹路径**：`D:\itemban-source`

该文件夹包含：
- 完整源代码（`src/`）
- 构建脚本（`gradlew`、`gradlew.bat`、`build.gradle` 等）
- 文档（`README.md`、`USAGE.md`、`CHANGELOG.md` 等）
- 许可证（`LICENSE`）

**打包命令示例**（PowerShell）：

```powershell
Compress-Archive -Path "D:\itemban-source\*" -DestinationPath "D:\itemban-source-1.0.1.zip"
```

---

## 推荐做法

1. 直接在 GitHub Release 页面创建 `v1.0.1`
2. GitHub 会自动附加源代码压缩包
3. 只需额外上传 `ItemBan-1.0.1.jar` 即可

无需手动上传源代码。