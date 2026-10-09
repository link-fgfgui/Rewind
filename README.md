# Rewind

基于 Stonecutter 的多版本 Minecraft 模组（`common + versions/<minecraft-version>-<loader>`），由
[Stonecutter](https://stonecutter.kikugie.dev/) 管理四个版本节点。

包名与 Gradle group 为 `cc.sighs`，mod id 为 `rewind`，作者 MerrySnow。

项目骨架来自 [SighsTemple](https://github.com/Tower-of-Sighs/SighsTemple) 框架；该框架的迁移工作流为：先将当前 mc 版本的所有源码及资源迁移至 common 部分，再将经查询源码证实存在明显版本差异的内容转移到 `versions/<mc-version>-<loader>` 中的对应版本节点下，然后搭建 spi 模式，将版本节点中的内容抽象化，只保留具体实现差异、共通逻辑迁移回 common 部分，最后将当前版本节点中的主版本内容移植到全部所有版本。

## IDEA

直接打开仓库根目录。IDEA 会导入 Stonecutter 多版本工程（含 `common` 与四个
`versions/<minecraft-version>-<loader>/` 节点），只下载当前活动版本
（`stonecutter.gradle.kts` 中的 `active`，默认 `1.21.1-neoforge`）的加载器和
Minecraft 依赖。建议装 Stonecutter IDEA 插件切换活动版本；**Gradle JVM 选 21+**
（`Settings → Build Tools → Gradle`），否则 settings 插件解析失败。

## Target

| Version 节点 | 工具链 | 构建命令 |
| --- | --- | --- |
| `1.20.1-fabric` | JDK 17 | `./gradlew :1.20.1-fabric:build` |
| `1.20.1-forge` | JDK 17 | `./gradlew :1.20.1-forge:build` |
| `1.21.1-neoforge` | JDK 21 | `./gradlew :1.21.1-neoforge:build` |
| `26.1-neoforge` | JDK 25 | `./gradlew :26.1-neoforge:build` |

一次构建全部版本（所需 JDK 由工具链自动提供）：

```powershell
.\gradlew.bat build
```

手动跑客户端 / 专用服务器：

```powershell
.\gradlew.bat :1.21.1-neoforge:runClient
.\gradlew.bat :1.21.1-neoforge:runServer
```

## 结构

- `common/`: 不依赖 Minecraft 或任意 loader 的共享 Java 代码。
- `versions/<minecraft-version>-<loader>/`: 每个支持组合的完整源码（loader 和版本专属入口、metadata、资源及 API 适配），由根目录的 Stonecutter 配置（`settings.gradle.kts`、`stonecutter.gradle.kts`、`stonecutter.properties.toml`、`build.<loader>.gradle.kts`）统一管理构建。

## 共享资源

将所有加载器和版本共用的资源放在 `common/src/main/resources/`。构建任意版本节点时，该目录会与版本自己的 `src/main/resources/` 合并并写入最终 jar。

加载器 metadata 仍必须保留在版本中：Fabric 使用 `fabric.mod.json`，Forge 使用 `META-INF/mods.toml`，NeoForge 使用 `META-INF/neoforge.mods.toml`。

## 本地依赖

每个版本节点都会自动将自身 `libs/` 目录中的 `*.jar` 作为 `implementation` 依赖。将 jar 放入对应目录后不需要在构建脚本中逐条声明；`*-sources.jar` 和 `*-javadoc.jar` 会被忽略。

```text
versions/1.20.1-fabric/libs/
versions/1.20.1-forge/libs/
versions/1.21.1-neoforge/libs/
versions/26.1-neoforge/libs/
```

本地 jar 的传递依赖无法自动推导。若某个 jar 还依赖其他库，需要将这些库也放入同一个 `libs/` 目录，或按常规方式声明依赖。

## 发布

每个版本节点都提供 `publishMods`，可手动发布其自身的产物至 CurseForge 与 Modrinth。两个平台分别发布用 `publishCurseforge` / `publishModrinth`（`publishMods` 要求两个平台的凭据都在，只发一个平台时别用它）。更新日志优先取 `PUBLISH_CHANGELOG` 环境变量，没设就读仓库根的 `CHANGELOG.md`；版本号在 `stonecutter.properties.toml` 的 `mod.version`，改完要重新构建对应节点。两个平台的项目 ID 是所有版本共用的非敏感信息，在根 `gradle.properties` 中取消注释并填写：

```properties
publish_curseforge_project_id=你的CurseForge项目ID
publish_modrinth_project_id=你的Modrinth项目ID
```

token 只从环境变量读取，不要写入仓库。PowerShell 示例：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
$env:PUBLISH_CHANGELOG = '本次版本的更新说明' # 可选

.\gradlew.bat :1.20.1-forge:publishMods
```

将节点路径替换为其他版本即可单独发布对应加载器和 Minecraft 版本。Fabric 会上传重映射后的 jar；Forge 与 NeoForge 上传各自的最终 jar。

## 版本参考

- [Minecraft 1.20.1、1.21.1、26.1 完整迁移差异参考](docs/version-differences/README.md)
- [多版本日常维护工作流](docs/MAINTENANCE_WORKFLOW.md)

## 许可证

Copyright (C) 2026 Tower of Sighs

本项目以 **GNU General Public License v3.0**（`GPL-3.0`）发布：你可以再分发和/或修改它，
但必须遵守该协议；本程序不提供任何担保。完整协议文本见根目录的 [LICENSE](LICENSE)。
