# ModernWarfare Guard

面向 **Mianbao's NewModernWarfare 2.5.x** 的纯服务端附属模组（NeoForge 1.21.1 / Java 21）。
玩家客户端**不需要**安装，原版客户端可直接进服。

它只做三件事：把原 mod 里每实体每 tick 的全量实体检索换成有界缓存、把 APT 的刷怪 DEBUG 日志静音、
在服务器真的扛不住时提供一套可配置的护栏和 `/mwdiag` 诊断。

---

## 1. 它修的是什么

对 `mianbaos_modernwarfare-2.5.3-neoforge.jar` 反编译（`javap -p -c`）确认：

- `net.mcreator.myfirstmod.procedures.JamedSTARTProcedure` 带 `@EventBusSubscriber`，
  **对每个维度的每个实体每 tick** 无条件调用 `execute(...)`；
- 该方法唯一的热点在字节码 offset 38：

```text
invokeinterface net/minecraft/world/level/LevelAccessor.getEntitiesOfClass:
  (Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;
```

- 查询盒是 `AABB.ofSize(pos, 60, 60, 60)`（**半边长 30**），查询类是
  `SmokelauncherammoentityEntity`，谓词是 `e -> true`；
- 调用方**只用了 `List.isEmpty()`**，随后丢弃整个列表。

因此每次调用的分配量随“盒内命中的弹药实体数”线性增长，而不是随扫描量增长。这是 550MB/s 的来源。

---

## 2. 三个模块

### M1 实体检索补丁（`com.mwguard.cache`）

`@Redirect` 精确匹配上面的指令（owner 是 `LevelAccessor`，不是 `Level`），改走 `TargetCache`：

- key = `(维度, 网格坐标, 半径)`，网格边长 `cacheCellBlocks`（默认 32）；
  用实体自身坐标做精确 key 对移动实体永远不命中，所以必须量化。
- **查询路径永不扫描**：命中返回缓存；未命中/已过期返回共享空表，并把该格子登记到刷新队列。
- 刷新在 `ServerTickEvent.Post`，每 tick 最多 `refreshBudgetPerTick` 个格子；
  每个格子的扫描盒 = 网格外扩查询半边长，**绝不是全维度扫描**。
- 扫描用 `Level.getEntities(EntityTypeTest, AABB, Predicate, List, int limit)`，
  上限 `cacheSampleCap`，命中上限时该格子标记为“饱和”，后续直接判非空。
- 半径超过 `maxRadius` 的查询直接返回空表并打一次 WARN，绝不放大扫描范围。
- 每个格子 TTL = `cacheTtlTicks`（默认 10），即稳定状态下 9/10 次命中。

`TargetpointerworkProcedure`（`PlayerTickEvent`，每玩家每 tick）用同一套实现，默认关闭。

### M2 日志降噪（`com.mwguard.log.AptLogMute`）

把 logger `Adaptive Performance Tweaks.spawn` 从 `DEBUG` 提到 `INFO`。
这个名字来自实际 `debug.log`（带空格，父 logger 是 `Adaptive Performance Tweaks`），
只影响这一条 logger，APT 的 WARN/ERROR 不受影响。配置加载时和 `ServerStartedEvent` 各设一次，
防止别的模组或配置重载把级别刷回去。`muteAptSpawnLog=false` 时恢复 DEBUG。

### M3 服务端护栏（`com.mwguard.guard` + `/mwdiag`）

- 每 `guardSampleIntervalTicks`（默认 20）采样一次：MSPT、每维度实体数、投射物数。
- 超过 `msptWarnThreshold` / `maxProjectilesPerLevel` / `maxEntitiesPerLevel` 时按 `guardrailAction` 执行：
  - `WARN`（默认）：只打限频日志；
  - `DISCARD_OLDEST_PROJECTILE`：丢弃最老的投射物直到回到上限，单次最多 `maxDiscardPerPass` 个；
  - `CANCEL_EXPLOSION`：MSPT 高于 `msptCriticalThreshold` 时，本 tick 的 `Explosion.explode()` 全部取消。
- `/mwdiag`（权限等级由 `diagPermissionLevel` 控制，默认 3）输出：
  TPS、MSPT、堆使用、各维度实体数、Top5 实体类型、两个缓存的命中率/扫描数/拒绝数、注入状态。

---

## 3. 实测结果（开发服，NeoForge 21.1.248 + 目标 mod 2.5.3）

```text
=== ModernWarfare Guard ===
TPS 20.00 | MSPT 0.61 ms | tick 1131
Heap 263 / 470 MB | entities 5 | projectiles 0
  minecraft:overworld: 5
Top entities: minecraft:pig=4 minecraft:chest_minecart=1
Cache[jamed_start]: hits=269 misses=30 rate=90.0% scans=30 refused=0 fallbacks=0
Cache[target_pointer]: hits=0 misses=0 rate=n/a scans=0 refused=0 fallbacks=0
Mixin: jamed[seen=true,redirect=true] targetPointer[seen=false,redirect=false] explosion[seen=false,cancelled=false] calls=299
```

- `hits=269 misses=30` 正好是 9:1，和 TTL=10 的设计一致；
- 三个注入都用 `-Dmixin.debug.export=true` 导出转换后的类并反编译确认：
  `JamedSTARTProcedure` 里出现 `redirect$...$cachedLookup`，
  `Explosion.explode()` 开头出现 `handler$...$guardExplosion` 调用；
- 关服日志无 `ModConfigEvent$Unloading` 异常，缓存统计正常输出。

---

## 4. 安装

1. 把 `build/libs/mwguard-neoforge-1.21.1-1.0.0.jar` 放进**服务端** `mods/`；
2. 确认服务端已有 `mianbaos_modernwarfare` 2.5.x（jar 内部版本号是 `2.5.0-neoforge`，2.5.3 文件也满足 `[2.5.0,)`）；
3. 首次启动生成 `config/mwguard-server.toml`，默认值即可直接跑；
4. 客户端不需要装，`displayTest = "IGNORE_ALL_VERSION"` 已设置。

## 5. 验证（前后对比）

1. **基线**：不装本模组，在 `dannys-aot:paradis` 压力最大的时段记录
   `/neoforge tps` 的 MSPT/TPS，以及 spark 里的 GC 次数与暂停。
2. **装后**：重启，等 2 分钟让缓存预热，再跑 `/neoforge tps` 和 `/mwdiag`。
3. 判据：
   - `/mwdiag` 里 `jamed[seen=true,redirect=true]`；
   - `Cache[jamed_start]` 命中率 > 80%（稳定后应接近 90%）；
   - `scans` 应约为 `hits+misses` 的 1/10；
   - MSPT 与 GC 暂停明显下降，`paradis` 的 tick 时间回落。
4. 若 `seen=true` 但 `redirect=false`：目标 mod 的方法描述符变了，M1 已安全降级为原版调用，
   日志会打一次 WARN —— 此时把新的 `javap` 输出发我，更新 `@Redirect` 即可。

## 6. 回滚

1. 停服，删除 `mods/mwguard-*.jar`；
2. 可选：删除 `config/mwguard-server.toml`；
3. 重启。

本模组不写存档、不改 NBT、不发网络包；唯一的世界侧动作是 `DISCARD_OLDEST_PROJECTILE` /
`CANCEL_EXPLOSION` 两个**非默认**动作，回滚后不会留下任何持久化痕迹。

## 7. 能力边界（请先读）

- 缓存只对**这两个** procedure 生效，不是通用优化；`javap` 扫描显示 162 个类调用
  `getEntitiesOfClass`，但只有 `JamedSTARTProcedure` 和 `TargetpointerworkProcedure`
  挂在每 tick 事件上。
- 严格按“未命中返回空表”实现：新网格/过期格子最多有 `cacheTtlTicks` 的空窗期。
  `Jamed` 本身是 60 tick 的效果且每 tick 刷新，所以表现为最多晚 0.5 秒生效。
- 网格按 32 格量化，格子边缘的判定与该格最后一次扫描的位置相关，最多一个格子 + 半边长
  （默认 32+30 格）的偏差；需要更精确就调小 `cacheCellBlocks`（扫描次数随之上升）。
- 采样上限 `cacheSampleCap=8`：格子内命中超过 8 个时标记为“饱和”，查询直接判非空；
  这是为了在弹幕维度里避免缓存几千个实体引用。
- `CANCEL_EXPLOSION` 会取消**所有**爆炸（包括床和重生锚），只应在紧急降载时使用。
- APT 的 logger 名是硬编码的；若 APT 改版换了名字，`muteAptSpawnLog` 会失效但不报错，
  启动日志里能看到 `Could not change the level of logger ...`。
- M2 在 server config 加载时生效（`SERVER` 类型配置在服务端启动阶段才可读），
  早于任何刷怪尝试，但晚于 mod 构造阶段。
- 服务端护栏的采样间隔默认 20 tick，开启 `guardSampleIntervalTicks=1` 可逐 tick 统计，
  但会多一次全实体遍历。

## 8. 构建

```bat
gradlew.bat build
```

- 默认从 `maven.neoforged.net` 解析依赖；本机把该域名黑洞了，所以仓库里用的是一个**未提交**的
  `local-repo/`（从 Gradle 缓存整理的离线镜像）：

```bat
gradlew.bat build --offline --console=plain
```

- 编译基线 `neoforge 21.1.248`，运行时声明 `[21.1.230,)`，21.1.251 服务端可直接加载；
- 开发服冒烟测试：`gradlew.bat runServer --offline`（`run-server/mods/` 下需要放目标 mod）。

## 9. 目录

```text
mwguard/
├─ build.gradle / settings.gradle / gradle.properties
├─ local-repo/                      离线 Maven 仓库（NeoForge 21.1.248）
├─ default-config/mwguard-server.toml   首次启动生成的配置等价文件
├─ tools/rcon.py                    冒烟测试用的最小 RCON 客户端
└─ src/main/
   ├─ java/com/mwguard/
   │  ├─ MwGuard.java               入口、事件接线
   │  ├─ cache/TargetCache.java     网格缓存 + 刷新队列
   │  ├─ cache/CacheRegistry.java   两个缓存实例
   │  ├─ config/MwConfig.java       服务端配置 + 热路径快照
   │  ├─ guard/Guardrails.java      M3 动作
   │  ├─ guard/ServerStats.java     采样
   │  ├─ log/AptLogMute.java        M2
   │  ├─ status/MixinStatus.java    注入探针（必须在 mixin 包外）
   │  ├─ command/MwDiagCommand.java /mwdiag
   │  └─ mixin/                     三个 Mixin
   └─ resources/
      ├─ META-INF/neoforge.mods.toml
      └─ mwguard.mixins.json
```

## 10. 许可

MIT。
