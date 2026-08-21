## [2.0.0] - 2026-08-21

### Changed
- 方块黑名单为空时跳过世界体素扫描，只保留展示框检测
- 未列入方块黑名单的方块不再序列化方块实体 NBT
- 所有周期扫描间隔不超过 12 ticks：背包 10、环境 12、掉落物 5
- 同一玩家同时只运行一种周期扫描；匹配在后台线程，删除仍在主线程

# Changelog — ItemBan NeoForge

## 1.0.3-neoforge.1 (1.21.1)
- Initial NeoForge port
- Forge → NeoForge APIs (`net.neoforged.*`)
- Event migration: `PlayerTickEvent.Post`, `RegisterCommandsEvent`, `PlayerContainerEvent`, `EntityJoinLevelEvent`
- Metadata: `META-INF/neoforge.mods.toml`
- Registry IDs via `BuiltInRegistries`
- Item NBT → Data Components compatibility layer (ID-based match for legacy NBT rules)
- Block entity NBT matching retained (1.21 still uses NBT for BEs)
- Dual-mode drop detection (area scan vs instant intercept)
- Optional world block scanning with separate block blacklist

## Notes for 1.21
- Build with `-Pmc=1.21 -Pneo_version=21.0.167` (NeoForge 21.0.x)
- Behavior identical to 1.21.1 build; only loader/mc ranges differ
