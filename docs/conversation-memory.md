# SignPicture 1.12.2 Modification Memory

## User Goal

Modify the Minecraft 1.12.2 version of SignPicture so online sign images can load/render from 128 blocks away, and provide a jar that can be placed directly into the `mods` folder.

## Distance Research

- SignPicture does not have its own configurable image loading distance.
- Image loading is triggered during sign tile entity rendering:
  - `CustomBlockSignRenderer.render(...)`
  - `SignEntryId.fromTile(tile).entry()`
  - `entry.getGui()`
  - `GuiImage.renderSignPicture(...)`
  - `entry.getContent()`
  - `ContentManager.get(...)`
- Minecraft 1.12.2 limits tile entity rendering through `TileEntity#getMaxRenderDistanceSquared()`.
- The vanilla default is `4096.0D`, which is `64 * 64`.
- `TileEntityRendererDispatcher` checks distance using a strict `< getMaxRenderDistanceSquared()` condition.
- SignPicture already patched `TileEntity#getRenderBoundingBox()` for signs to use `INFINITE_EXTENT_AABB`; that affects frustum culling, not distance.

## Source Changes

Changed files:

- `gradle.properties`
  - Changed version from `3.0.0` to `3.1.0`.
- `common/project.gradle`
  - Changed BnnWidget from a compile-only dependency to a shaded dependency for packaging work.
- `sources/universal/src/main/java/net/teamfruit/signpic/asm/ASMDeobfNames.java`
  - Added deobfuscated/obfuscated method name mapping for `TileEntity#getMaxRenderDistanceSquared()`.
- `sources/universal/src/main/java/net/teamfruit/signpic/asm/TileEntityVisitor.java`
  - Kept the existing render bounding box hook.
  - Added a hook for `getMaxRenderDistanceSquared()`.
  - For `TileEntitySign`, the hook returns `16384.0D`, which is `128 * 128`.

## Build Notes

- Use Java 8:
  - `/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`
- `./gradlew` is not executable in this checkout; use `bash gradlew`.
- Old Gradle/plugin dependencies may require proxy variables:
  - `http_proxy=http://127.0.0.1:7897`
  - `https_proxy=http://127.0.0.1:7897`
  - `HTTP_PROXY=http://127.0.0.1:7897`
  - `HTTPS_PROXY=http://127.0.0.1:7897`
- `http-builder:0.7.2` (a transitive dependency of the bintray/github-release plugins) is gone from every
  reachable repo: jcenter is shut down, Maven Central and the Gradle plugin portal both return 404.
  Workaround that works today: download `0.7.1` from Central, rename the jar and rewrite the pom version to
  `0.7.2`, and drop it into `~/.gradle/signpic-local-maven/org/codehaus/groovy/modules/http-builder/http-builder/0.7.2/`.
  Then feed that repo to Gradle with an init script (`gradlew -I init.gradle`) that adds it to
  `settings.pluginManagement.repositories` plus `allprojects { buildscript.repositories / repositories }`.
  Nothing in the mod jar depends on it — only the unused publish tasks do.
- Everything else (ForgeGradle, MCP mappings, MC assets) resolves from `~/.gradle/caches`.
- Temporary `settings.gradle` edits were used during builds to include only `common` and `1.12.2`; `settings.gradle` was restored afterward.

## Crash Fixes

### First Crash

Crash report:

- `/Volumes/Shared/crash-2026-06-20_13.34.07-client.txt`

Cause:

- `NoClassDefFoundError: net/teamfruit/bnnwidget/WFrame`
- The first jar did not include BnnWidget, and the user's `mods` folder did not contain a standalone BnnWidget jar.

Fix attempt:

- Embedded BnnWidget classes and resources into the final jar.

### Second Crash

Crash report:

- `/Volumes/Shared/crash-2026-06-20_13.43.34-client.txt`

Cause:

- `NoSuchMethodError: net.minecraft.client.Minecraft.getMinecraft()`
- The embedded BnnWidget was the dev/deobfuscated jar.
- The user's Cleanroom/Forge runtime needs obfuscated runtime names such as `Minecraft.func_71410_x()`.

Final fix:

- Rebuilt the final jar using SignPicture 3.1.0 output as the base.
- Replaced the embedded BnnWidget contents with the reobfuscated BnnWidget classes/resources extracted from:
  - `artifacts/3.0.0/3.0.0+001/SignPicture-1.12.2-3.0.0-cleanroom-fat.jar`
- Verified `net.teamfruit.bnnwidget.compat.Compat#getMinecraft()` calls:
  - `net/minecraft/client/Minecraft.func_71410_x()`

### Third Crash

Crash report:

- `/Volumes/Shared/crash-2026-08-22_15.38.23-client.txt`

Cause:

- `NullPointerException` in `Compat$CompatTextComponent.getUnformattedText` while rendering the hotbar.
- The held item was a `minecraft:sign` whose `BlockEntityTag` held a foreign tile entity
  (`minecraft:mod.chiselsandbits.tileentitychiseled`), so it had no `Text1`..`Text4` keys.
- `ItemEntryId.fromItemStack` fed that tag into `TileEntitySign#readFromNBT`, and vanilla 1.12.2 parses
  each missing line with `ITextComponent.Serializer.jsonToComponent("")`, which returns `null`.
- `CompatTileEntitySign.getSignText` then wrapped those nulls in `CompatTextComponent`, and
  `SignEntryId.fromChats` called `getUnformattedText()` on them.
- It crashed every frame because the item sat in the hotbar.

Fix:

- `sources/universal/.../entry/EntryId.java`
  - Added `ItemEntryId.hasSignText(NBTTagCompound)`; the `BlockEntityTag` branch now runs only when the tag
    really contains sign text, otherwise the display-name branch is used.
  - Wrapped `tile.readFromNBT(tag)` in try/catch so a malformed tag returns `ItemEntryId.blank`.
- `Compat.java` (`common`, `1.8.9`, `1.9.4`, `1.10.2`, `1.11.2`, `1.12.2`)
  - `CompatTileEntitySign.getSignText` maps null sign lines to a blank component.
  - `CompatTextComponent.getUnformattedText` returns `""` when the wrapped component is null.
- `Compat.java` (`1.7.10`)
  - Same null guards adapted to the `String[] signText` layout.
- Note: these files use CRLF line endings; keep them when editing.

## Version 3.1.1

- `gradle.properties`: `version_patch` `0` -> `1`.
- `build.gradle`: removed `classifier = 'universal'` from the root `jar` task, so the shipping jar is now
  named `SignPicture-3.1.1.jar` instead of `SignPicture-3.1.0-universal.jar`.
- Still carrying a `+NNN` build-number suffix: the artifacts directory (`artifacts/3.1.1/3.1.1+001/`) and the
  `ModVersion` manifest attribute (`3.1.1+001`). The in-game version from `@Mod` is a clean `3.1.1`.

## Final Jar

The shipping jar is the **root** project's jar,
`artifacts/<version>/<version>+NNN/SignPicture-<version>.jar`.
It is a flat 1.12.2 fat jar containing the reobfuscated BnnWidget classes directly.

Latest build (2026-09-08, 1.12.2-only cleanup), `bash build.sh --offline` on zulu-8:

- `artifacts/3.1.1/3.1.1+008/SignPicture-3.1.1.jar`

SHA-256:

```text
32d3a8b4825e84f67d8b92208346c21463f78fd3a2bf4d889fd6051fc6f8c7f4
```

Verified in the built jar:

- `@Mod` version is `3.1.1`.
- `net/teamfruit/bnnwidget/compat/Compat.getMinecraft()` compiles to `Minecraft.func_71410_x()` (reobf OK;
  running `build` reobfuscates the shaded BnnWidget automatically, so no manual class swapping is needed).
- `EntryId$ItemEntryId.hasSignText` exists and `fromItemStack` calls it, with an exception table around
  `TileEntitySign.func_145839_a` (`readFromNBT`).
- `Compat$CompatTileEntitySign.lambda$getSignText$0` null-checks and falls back to `fromText("")`.
- `Compat$CompatTextComponent.getUnformattedText` null-checks before `func_150260_c()`.
- The ASM render-distance hook still carries `16384.0d`.
- `mcmod.info` carries no version field (by design — Forge reads it from `@Mod`); no duplicate zip entries.

## 1.12.2-only Cleanup (2026-09-08)

- Removed the `1.7.10`, `1.8.9`, `1.9.4`, `1.10.2`, and `1.11.2` subprojects.
- Removed `common` after replacing the multi-version wrapper with a flat 1.12.2 fat jar.
- Removed GitHub, Bintray, and CurseForge publishing plugins/tasks plus unused development artifacts.
- Vendored BnnWidget because the original TeamFruit Bintray repository is no longer available.
- The shipping build is now `bash build.sh`; pass `--offline` after dependencies have been cached.
