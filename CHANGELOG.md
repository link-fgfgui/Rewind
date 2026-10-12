# 更新日志

## 1.0.3

### 新增：专用服务器与局域网的多人在线回滚

- **Dedicated Server 全流程可用**：`/rewind snapshot|restore|status|info|delete|rename|autosave` 现在注册在服务端入口，OP 等级 2（26.1 是 `COMMANDS_GAMEMASTER`）可用；控制台天然全权限，单人存档的主机不受影响。
- **回滚不再阻塞服务端线程**：多人路径走「跨 tick 的回滚切片」（每 tick 12ms 预算、整段 120 秒上限，超时立即中止并按失败收尾）。回滚期间服务端照常处理连接与心跳，客户端不会因为 keep-alive 超时被踢；同时世界刻被冻住、玩家操作包被丢弃，回滚期间的世界状态与单人原地回滚一致。
- **回滚完成后客户端原生重载**：用原版 `ClientboundRespawnPacket`（flags = 0，丢弃旧状态）+ `sendLevelInfo` + 区块跟踪重置，让全体在线客户端平滑重载回滚后的世界与地形；局域网主机自己也走这条（`CheckpointController` 新增 `MULTIPLAYER_ROLLBACK` 作业）。
- **局域网不再被拒绝**：开局域网时 F7 / F8、时间树、命令都可以用——主机与远端玩家一起被切片回滚 + 原生重载覆盖。
- **服务端忙锁**：`RewindApi.isBusy()` 现在也认服务端自己的忙标记（建点 / 回滚 / 删除 / 改名 / 切片回滚进行中），专用服务器上两个 OP 并发操作不会再撞进同一个引擎。

### 新增：回溯生命周期事件

- `RewindPlatform.beforeRollback` / `afterRollback` 两个 SPI 钩子：开始可取消（取消时返回 `RollbackCanceledException`，客户端不会退回「关世界 → 覆盖 → 重开」），结束不可取消，成功与失败都派发。
- Forge / NeoForge 侧对应 `cc.sighs.rewind.event.RollbackStartEvent`（可取消，`setCancelReason`）与 `RollbackEndEvent`（带 `RewindResult`）；Fabric 用 SPI 默认实现（放行、不通知）。
- 开始事件在回滚窗口打开**之前**派发；结束事件统一排在窗口关闭之后。

### 新增：世界列表上的「读取存档点」改成完整回溯页面

- 那颗按钮现在打开**时间树的离线模式**：世界还没进也能看槽位、封面、时间线，可以**读取**（先确认，再把磁盘上的世界文件覆盖回存档点）、删除、改名、**整理存档点**；建点按钮禁用。
- 本仓库特有的「整理存档点 / GC 重烘焙」并进时间树 HUD（离线才显示），带可释放空间估算。

### 改进

- **单个文件动不了不再让整次建点 / 回溯失败**：被别的进程锁着、正好被删掉、写不进去、删不掉的路径会被跳过，并在清单里留一条「没动它」的记录（回溯方向不会回拷它、也不会把它当多余文件删掉），计数进镜像摘要（`untouched=` / `undeleted=`）。
- **跨视距 / LOD 模组的运行时数据库不再进快照**：`voxy` 这类嵌在世界目录里的键值库（RocksDB 的 `LOCK` 在 Windows 上是强制锁）被排除，建点不会再因为它整段失败。
- `/rewind status` 现在显示时间线的头、切片回滚状态与客户端状态机的阶段。

### 修复

- **专用服务器上原地回滚在灌装阶段崩溃**：账本键函数原先放在 `cc.sighs.mixin.DataJournalMixins` /
  `StorageJournalMixins` 这两个 Mixin 容器类里，回滚引擎（普通代码）直接引用它们会被 Mixin 以
  `IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly`
  拒绝加载（客户端因为类已被 Mixin 加载过、侥幸不报）。现在键函数搬进普通类
  `cc.sighs.rewind.common.JournalKeys`，键格式与行为不变。

### 内部

- `/rewind` 命令层挪到 `cc.sighs.rewind.server.RewindCommands` 并由四个节点的**服务端入口**注册；`ui` / `status` 的客户端部分经 common 的中立钩子 `RewindClientHook` 转交，专用服务器上如实报告「没有客户端」。
- 四个节点的 `InPlaceRollback` 各多一个 `Job`（`RollbackJob` 的有状态机实现：卸载按轮、灌装 / 读回 / 标脏直写按区块），并为切片抽出「单区块读回 / 标脏 / 直写」等小步；同步路径的行为完全不变。
- `ClientLevelReload`（四份）用原版 Respawn 流程重载客户端；`ChunkMap.updatePlayerStatus` 经 `RollbackAccessMixins` 的 `@Invoker` 调用。
- 共享 Mixin `MultiplayerRollbackMixins`（世界冻结 + 操作包丢弃）注册在四端；`RewindScreens` 增加原生重载豁免。


## 1.0.2

### 新增：进世界之前的「读取存档点」

- 世界选择界面第一排多了一颗「读取存档点」（英文 **Rewind**）：**进世界之前**，用这个存档自己的存档点把磁盘上的世界文件覆盖回去。世界坏到打不开、游戏里按不出 F8 时，这是唯一一条恢复路径。
- 目标存档点自动挑：优先**时间线的头**（世界当前站着的那个存档点），取不到时退回保存时间最新的那个完整存档点。
- 点下去先出确认界面（写明要还原到哪个存档点、以及「这会覆盖磁盘上的世界文件」），确认后在后台线程回拷，界面显示进行到哪一步与结果。没有可用存档点、或者存档被别的实例锁着时，按钮保持不可用。
- 第一排现在是**三颗等宽按钮**（进入选中的世界 / 读取存档点 / 创建新的世界），整排宽度与原版一致。
- 新增查询接口 `RewindApi.repairSlot(worldRoot)`，整合包作者可以自己接。

### 内部

- `.mca` 头部解析（`RegionHeader`）与存档数据回滚骨架（`SavedDataRollback`）从各 target 抽到 common——四个 target 逐字节相同，少一份重复。

## 1.0.1（自 1.0.0 起）

### 时间树

- F9 / `/rewind ui` 打开「时间树」，一页管理所有槽位（自动、快速 + 8 个手动槽）：卡片显示截图封面、背包快照、生物群系、坐标、游戏时间、游玩时长与占用大小。
- 两套布局：**档案布局**（槽位卡片，支持搜索 / 排序 / 重命名 / 删除 / 读取 / 覆盖）与**节点树布局**（把存档点按派生关系画成流程图，可拖拽平移、滚轮缩放、整体旋转 90°）。
- 节点树上有一个「在此存档」节点，挂在时间线的头下面；点它就直接存进序号最小的空手动槽位。
- 页面顶部还有 HUD：当前世界的群系、坐标、游戏内时间与已游玩时长。
- 存档点之间有了时间线关系：每个存档点记住自己是站在哪个存档点上建出来的，覆盖 / 删除槽位时会如实断掉这条边。

### 回溯

- **原地回溯**：F8 默认不再关世界——在活着的集成服务器里把世界倒回存档点（卸载受影响区块 → 回拷文件 → 重建 → 玩家 / 时间 / 天气 / 存档数据回滚），失败会自动退回「关世界 → 覆盖 → 重开」那条老路。
- 受影响区块按「真正改了多少区块」判定，与视距无关；实体存储没有脏标记，用「内存里还有会被保存的实体 / 快照里本来有实体」两条补判。
- 收尾会作废维度（26.1 还有服务器级）存档数据缓存里没被强引用的条目，让模组写在 `data/*.dat` 里的数据跟着回滚；袭击、记分板、等级附件这些被长生命周期对象强引用的会显式重建。
- 读档冷却与「死亡后自动回溯」两个可调项（都默认关闭；开启死亡回溯后，时间线的头会标红提醒）。
- 死亡回溯会先把重生点临时挪到玩家脚下、并把朝向接回来，避免「先瞬移回出生点、再被拉走」那一下。

### 存档点存储

- 快照按 **4 KiB 扇区**共享存储（内容寻址的块目录 + 每个槽位一份块映射）：换一个槽位建点只为真正变了的扇区付字节。同一份 30 MB 测试世界上，整文件级只能省 8.3%，按扇区切块省 **79.9%**。
- 回溯是**反向增量**：按建点时记录的清单只回拷真正被改写过的文件，回拷后把 mtime 拨回建点时的值。
- 槽位改名（只改显示名，不重拷）、删除、以及块存储的标记-清除垃圾回收。

### 观感

- F7 / F8 全程不出现任何界面、也不往聊天框发提示：过渡改成正片后处理（存档 = 高饱和、读档 = 高斯模糊），时长、饱和度、模糊半径、settle tick 全部进客户端配置，界面上还有滚动条可调。
- 死亡回溯有一套专门的红边过渡效果。
- 热键在世界内任何地方都生效，包括界面开着的时候。

### 自动存档

- 可以跟着原版自动保存建点（写 `auto` 槽位），也可以直接改原版的自动保存间隔（等比缩放，保留原版在冲刺时缩短间隔的行为）。

### 其他

- `/rewind status|ui|snapshot|restore` 命令；公开 `RewindApi`，供整合包与其它模组调用。
- 可选兼容：精妙核心（Sophisticated Core）——回溯后清掉它自己缓存的解码结果。
- 四个 target 功能对齐：1.20.1 Forge / 1.20.1 Fabric / 1.21.1 NeoForge / 26.1 NeoForge（26.1 的存档数据入口、维度路径与加载流程差异都已消化）。
- 界面文案中英双语；时间树整页字体改用本机字体（原来的远程 webfont 一直加载不到）。

---

## English

### 1.0.2

- New **Rewind** button on the world-selection screen: restore a world's files from one of its own checkpoints **before opening it** — the only recovery path when a world no longer loads. It picks the timeline head (falling back to the newest complete checkpoint), asks for confirmation before overwriting anything, then copies on a background thread and reports progress and result.
- The first button row is now three equal-width buttons (Play Selected World / Rewind / Create New World), the same total width as vanilla.
- New query API `RewindApi.repairSlot(worldRoot)`.

### 1.0.1 (since 1.0.0)

- **Time tree GUI** (F9 / `/rewind ui`): manage every slot on one page with screenshots, inventory snapshots, biome, position, in-game time, playtime and sizes; a slot view and a node-tree timeline view (drag, zoom, rotate); a "save here" node that writes into the first free manual slot; search, sort, rename, delete, restore and overwrite.
- **In-place rollback**: F8 rewinds the world inside the running integrated server (unload affected chunks, copy files back, rebuild, roll back player/time/weather/saved data), with automatic fallback to the close-world-then-reopen path.
- **4 KiB block-shared snapshots**: about 79.9% smaller than whole-file copies on the same 30 MB test world, plus reverse-incremental restores.
- **No-UI transitions**: post-process saturation (save) and gaussian blur (restore) with every parameter exposed in the client config; a dedicated red-edge effect for death-triggered rollback; hotkeys work everywhere, including in GUIs.
- **Autosave checkpoints** and an adjustable vanilla autosave interval; rollback cooldown; death-triggered rollback.
- `/rewind` commands and the public `RewindApi`; optional Sophisticated Core cache invalidation; the same feature set on Forge 1.20.1, Fabric 1.20.1, NeoForge 1.21.1 and NeoForge 26.1.
