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
