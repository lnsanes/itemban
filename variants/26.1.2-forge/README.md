# ItemBan

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.3.0+-orange)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

A lightweight **server-side only** Forge mod for Minecraft that allows administrators to blacklist items. Blacklisted items have their recipes removed and are automatically deleted from inventories, containers, and the world.

[English Description](ENGLISH_DESCRIPTION.md) • [使用说明](USAGE.md) • [更新日志](CHANGELOG.md)

## Features

- Remove all recipes for blacklisted items
- Automatically delete blacklisted items from player inventories and containers
- Wide compatibility with AE2, Create, Sophisticated Backpacks, Storage Drawers, and most storage mods
- Dropped items are instantly removed
- Creative mode and OP players are exempt
- Live blacklist management via commands
- Daily logs saved to `logs/ItemBan/`
- Pure server-side mod — clients do not need to install anything
- Configuration file at `config/ItemBan/blacklist.json`

## Installation

1. Download the latest `ItemBan-x.x.x.jar` from the [Releases](https://github.com/YOUR_USERNAME/itemban/releases) page
2. Place it in your server's `mods` folder
3. Restart the server
4. Edit `config/ItemBan/blacklist.json` to add item IDs (e.g. `"minecraft:diamond_sword"`)

## Commands

All commands require OP level 2:

| Command | Description |
|---------|-------------|
| `/itemban add <item_id>` | Add an item to the blacklist |
| `/itemban remove <item_id>` | Remove an item from the blacklist |
| `/itemban list` | View the current blacklist |
| `/itemban reload` | Reload the blacklist from disk |

## Building from Source

### Requirements
- Java 17
- Gradle 8.5+ (or use the included wrapper)

### Build Steps

```bash
./gradlew build
```

The compiled jar will be located at:
```
build/libs/ItemBan-1.0.0.jar
```

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

## Contributing

Pull requests are welcome! Please open an issue first to discuss what you would like to change.

## Support

If you encounter any issues, please open an issue on GitHub.