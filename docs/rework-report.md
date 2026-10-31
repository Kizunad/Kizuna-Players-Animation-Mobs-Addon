# 生物动画返工报告

## 设计说明

PA 通过 Fabric entrypoint `kizuna_player_animation` 暴露 `PlayerAnimationHost.exchange(byte[])`，只交换 protobuf。协议包含 `RegisterGeometry`、`Play`、`Stop`、`Clear` 和明确错误码响应；唯一 schema 位于 PA 的 `entity_animation.proto`，附属通过 `-PkpaDir` 直接生成，不保留副本。PA 负责几何解析、动画采样、24 骨蒙皮、本体及标准特征层绘制；附属负责原版生物几何、客户端注册、服务端快照、追踪同步和权限 2 调试命令。同步使用附属自身通道，避免扩展 PA 既有玩家同步的职责。客户端在主线程调用字节入口，生物使用独立播放器，不向玩家事件发送实体 UUID。

本次完成审阅六项返工，视觉效果已由用户于 2026-10-28 实际验收，反馈为“还可以”。此后仅收尾代码、测试和文档，没有再调整几何、姿态、机位或视觉参数。两个仓库均保留为未提交工作区，没有提交或推送；PA 的 `.bbmodel`、动画 JSON 和索引没有改动。当前使用本地 `0.1.0-SNAPSHOT` 源码构建，尚未发布或锁定到新版本。

## 审阅处理

| 审阅项 | 最终实现与证据 |
| --- | --- |
| 几何及 UV | 从 1.20.1 原版模型工厂导出逐盒顶点、UV、枢轴、dilation、静态变换，再裁切到 PA 关节；逐盒 UV 拼回、体积、骨集合和细节测试覆盖 10 份几何。 |
| 特征层 | `MobFeatureRendererMixin` 替换标准 `RenderLayer.renderColoredCutoutModel` 的网格提交；职业、群系、等级、溺尸和流浪者外层复用本体同帧骨矩阵。原版盔甲及手持物另行接线。 |
| 协议副本 | 附属 `sourceSets.main.proto` 直接使用 PA 源码目录，只选择实体协议；生成代码不纳入版本控制。 |
| 代码形状 | 移除行内全限定类名；`entityPlayback` 合并生物 play/scrub 的资源与实体校验；拆分几何解析、网格烘焙与事件收集；补充关键约束的中文注释。 |
| 玩家事件 | 生物仅推进内部时间线，不调用 `PlayerAnimations.EVENT`；客户端回归验证事件隔离、卸载清理和 ID 复用。 |
| 村民双臂 | 确认村民、流浪商人分臂贴图区透明，改用交叉臂原版盒及其 UV 分开映射；没有动画时完全保留原版交叉臂绘制。 |

代码收尾还修复了实体类型绑定的命名空间问题：`zombie` 能通过原版标识符校验，但旧实现用原字符串保存绑定，查询 `minecraft:zombie` 时无法命中。注册与查询现在统一使用 `ResourceLocation`，所有实体类型解析、校验完毕后才替换映射。新增字节入口回归先在旧实现上复现 `INVALID_REQUEST`，修复后返回 `OK`；协议文档同步说明默认命名空间。玩家事件测试增加正向对照，先确认玩家播放确实发出事件，再验证生物隔离与卸载清理，避免空通过。

重复求值整理限于实体入口的进度读取、播放器 tick、scrub 接缝偏移、持物参数和服务端线程校验；scrub 游标更新提取为播放器内部方法，插值规则与数值保持不变。命令权限级别使用具名常量，移除未使用的日志器和 import。规范化只做一次，播放入口共享校验，保持职责边界并减少重复逻辑。

## 文件与结构

PA 新增或修改的范围如下，均为入口、播放或渲染所需接线：

- `api/PlayerAnimationHost.java`、`PlayerAnimationHostImpl.java`：Fabric 字节入口及客户端绑定。
- `src/main/proto/kizuna/playeranimation/v1/entity_animation.proto`：唯一实体协议。
- `client/EntityAnimationApi.java`、`MobAnimationRegistry.java`、`MobAnimationRenderer.java`：请求处理、实体类型几何绑定、同帧采样与绘制。
- `animation/AnimationPlayer.java`、`client/PlayerPlayback.java`：可选循环覆盖、同步起点、生物独立播放器、玩家事件隔离和卸载清理。
- `client/PlayerAnimationClient.java`：绑定入口及客户端生命周期清理。
- `mixin/LivingEntityRendererMixin.java`、`MobFeatureRendererMixin.java`：本体和覆盖层网格替换。
- `mixin/ArmorFeatureRendererMixin.java`、`LivingHeldItemFeatureRendererMixin.java`：同帧骨矩阵驱动装备。
- `model/Skeleton.java`、`SkinnedMesh.java`：实体纹理尺寸、完整盒膨胀边界、静态子部件旋转和显隐路径；保持玩家几何原有约束。
- `fabric.mod.json`、`kizuna_players_animation.mixins.json`：入口与 mixin 声明。
- `EntityProtocolContractTest`、`EntityPlaybackClientTest`、`EquipVisibilityClientRunner`：协议及客户端契约回归。
- `docs/asset-contract.md`：实体协议与几何扩展文档；`.gitignore`：排除 Python 缓存。

上述 Java 路径除测试外均相对于 PA 的 `src/main/java/dev/kizuna/playeranimation/`。

附属仓结构：

| 路径 | 职责 |
| --- | --- |
| `src/main/java/.../MobGeometryAssets.java` | 资源重载时注册 16 种实体类型的几何与外层绑定 |
| `MobAnimationClient.java` | 发现唯一入口，解码网络请求并在客户端主线程调用 PA |
| `MobAnimationServer.java`、`MobAnimationSyncState.java`、`MobAnimationPackets.java` | 快照、追踪队列、播放/停止通道、卸载清理与命令 |
| `src/main/resources/assets/kizuna_players_animation_mobs/geo/mob_skeletons.geo.json` | 10 份原版尺寸的完整骨架几何 |
| `src/test/.../VanillaModelExport.java` | 调用原版模型工厂并导出烘焙顶点 |
| `scripts/generate_mob_geometry.py`、`test_mob_geometry.py` | 可复现生成、UV 拼回和几何边界测试 |
| `src/test/.../MobAnimationSyncStateTest.java` | 同步快照与停止语义 |
| `src/gametest/.../MobAnimationGameTest.java` | 真实服务端播放登记、追踪队列与卸载清理 |
| `src/clientTest/.../MobAnimationClientTest.java` | 实际世界字节契约验证与近景截图 |
| `build.gradle` | 读取 PA 唯一 proto、本地 JAR，接通构建与游戏测试 |

附属源码只引用 PA 的公开 host 接口和生成协议，不引用 PA 内部渲染类。

## 几何来源与差异

`VanillaModelExport` 直接执行 Yarn 1.20.1 对应原版模型工厂，而非手填 UV。classic 提供骨层级、关节切口和挂点参考，盒与逐面 UV 来自原版导出。27 个骨定义包含 24 个可见骨、root 和两个物品挂点；指头分割已有手末端体积，不额外增加块，内部切口不造重复面。

| 几何 | 原版来源及保留差异 |
| --- | --- |
| zombie | `HumanoidModel.createMesh`，64×64，8×8×8 头、8×12×4 身体、4×12×4 四肢；绑定僵尸、尸壳。 |
| skeleton / wither_skeleton | `SkeletonModel.createBodyLayer`，64×32，2×12×2 四肢；凋灵骷髅保留原版 renderer 的 1.2 倍缩放，不在几何再乘一次。 |
| piglin | `PiglinModel.createMesh`，64×64，10×8×8 宽头、原版鼻部/獠牙、双耳及 ±30° 静态耳旋转；绑定猪灵、蛮兵和僵尸猪灵。 |
| villager | `VillagerModel.createBodyModel`，64×64，8×10×8 头、2×4×2 鼻、8×12×6 身体、袍子、帽子/帽沿；绑定村民、流浪商人。 |
| illager | `IllagerModel.createBodyLayer`，64×64，保留头、鼻、厚躯干/袍子、原版独立双臂；绑定卫道士、掠夺者、唤魔者、幻术师。 |
| zombie_villager | `ZombieVillagerModel.createBodyLayer`，保留其头鼻、袍子和帽沿，不复用普通僵尸头。 |
| drowned / drowned_overlay | `DrownedModel.createBodyLayer`，本体及 0.25 像素 dilation 外层分别导出。 |
| stray_overlay | 原版 Biped 外层工厂，64×32，4 像素宽四肢及 0.25 像素 dilation；本体仍为 2 像素宽骷髅。 |

每个切段面用原版四边形进行仿射 UV 插值，保留镜像与面朝向。`inflate_origin` / `inflate_size` 记录完整原盒边界，保证外层分段不重复膨胀。鼻、耳、帽沿按原版静态子部件变换烘焙后进入同一蒙皮路径。

村民和流浪商人贴图在 Illager 分臂区域 `(40,46)..(56,64)` 的 288 个像素全部透明。因此将原版交叉臂的两个上臂盒及横梁沿中线拆开，映射为左右独立上臂、前臂与手部，继续使用交叉臂原始 UV。播放时从双肘弯曲的底层姿态混合到动画；未播放时直接使用原版模型。

## 贴图层与装备

本体材质、变体纹理、亮度和透明度继续由原版 renderer 选择。村民及僵尸村民服饰层保留原版的群系、职业、等级选择与帽子显隐顺序，只替换标准 feature 的顶点提交。村民服饰使用包含原版袍子和帽子厚度的本体网格；溺尸、流浪者使用单独的原版外层网格，全部共享本体同一帧采样矩阵。

原版盔甲复用 PA 已有 `ArmorMesh`，从原版装备模型烘焙网格并以身体同一帧骨矩阵变形；染色、覆盖层、纹饰、附魔仍使用原版缓冲。手持物使用 `r_item` / `l_item` 挂点和原版 display 变换。生物不会触发玩家穿戴事件显隐。没有生物播放状态时，本体、feature、盔甲和持物均保留原版路径。

## 截图与验收

截图位于 [`build/mob-screens/`](../build/mob-screens/)，均来自加载测试世界后的实际 renderer。动画为 `dodge_forward`，固定进度 `0.45`；1280×960，FOV 48，相机约 2.6 格、正面偏 30°。

| 必需对照 | 补充覆盖 |
| --- | --- |
| `zombie.png`、`skeleton.png`、`piglin.png`、`villager.png`、`vindicator.png`、`wither_skeleton.png`、`player.png` | `drowned.png`、`stray.png`、`zombie_villager.png`、`zombie_armor.png`、`villager_vanilla.png` |

用户已于 2026-10-28 实际验收视觉并接受当前效果。视觉结论以用户验收为准，收尾没有重新看图或进行视觉迭代；客户端测试按既有参数生成截图，并核对请求结果和播放/停止/清理契约，不把截图数量视为逐像素视觉证明。截图、世界和构建产物均不纳入版本控制。

## 实际验证

以下命令已在 2026-10-28 收尾代码上实际运行，均通过；随后仅更新本报告。

| 仓库 | 命令 | 结果 |
| --- | --- | --- |
| PA | `./gradlew test build` | `BUILD SUCCESSFUL`；93 项 Java 单测，0 失败、0 跳过；77 段动画导出一致性与数值门禁通过。 |
| PA | `./gradlew runClientTest` | `BUILD SUCCESSFUL`；18 项客户端测试全部通过，`clienttest-results.txt` 为 `success=true`，包含默认命名空间绑定与玩家事件正向对照。 |
| 附属 | `./gradlew test build runClientTest` | `BUILD SUCCESSFUL`；2 项 Java 单测、3 项 Python 几何测试及 1 项服务端 GameTest 通过；客户端报告 `success=true`、`captures=12`、`error=null`。 |
| 附属 | `python3 "scripts/generate_mob_geometry.py" --check` | 退出码 0，生成几何与已保存资源一致。 |
| 两仓 | `git diff --check` | 通过。附属当前文件均未跟踪，另用只读脚本扫描 Git 未忽略文本，补足普通 diff 的覆盖范围。 |

只读扫描覆盖 PA 的 239 个、附属的 27 个未忽略文本文件，没有行末空白。静态检查确认附属源码只 import PA 的公开 host 和生成协议、`src` 下没有 `.proto` 副本，PA 的 `.bbmodel`、动画 JSON 与索引无改动。12 张 PNG 截图均存在。

日志和结构化结果保留在本地构建目录：

- PA：`build/mobs-closeout-build.log`、`build/mobs-closeout-clienttest.log`、`build/clienttest-results.txt`、`build/test-results/test/`。命名空间缺陷的修复前复现日志为 `build/mobs-contract-repro.log`（预期失败：17 通过、1 失败）。
- 附属：[`build/mobs-closeout-verification.log`](../build/mobs-closeout-verification.log)、[`build/clienttest-results.txt`](../build/clienttest-results.txt)、[`build/gametest-results.xml`](../build/gametest-results.xml)、`build/test-results/test/`。

两仓测试运行环境均为 Java 17。构建仍提示现有 Gradle 弃用 API 与 Gradle 9 不兼容；本次使用仓库的 Gradle 8.10.2，没有升级工具链。

## 已知行为与未测范围

- 服务端快照保留到显式停止或实体卸载，没有按动画时长自动移除一次性动作；追踪补发保留原请求 ID 和起点，客户端跳过已过期播放。
- PA 字节响应由附属记入客户端日志，不向服务端回执；命令成功表示服务端请求已登记并发送，不代表所有客户端渲染成功。
- 未执行独立远端服务端加两个真实客户端的网络追踪验收；GameTest 验证播放登记、追踪队列和卸载清理，不能替代实际双客户端补发链路。
- 未穷举幼年体型、全部职业/群系/等级、装备及附魔纹饰组合；截图覆盖指定成年实体与部分服饰/盔甲。
- 未专门验证幻术师复制影像、唤魔者特殊效果、自定义 feature、自定义盔甲模型、其他模组渲染兼容。
- 未测大量实体性能、公网延迟、跨维度网络重入组合；共享玩家动作并不保证大头生物在所有动作与所有进度都没有穿插。
- 依赖仍为本地 PA `0.1.0-SNAPSHOT` 源码及构建产物，尚未锁定已发布版本；跨仓交付需要匹配这次协议与入口实现。

## 代码形状自查

已修正：第 1、2 条，附属请求构造先命名内层消息、builder 每行一个 setter；第 3 条，`Skeleton.parseCube/parseFace`、`AnimationPlayer` 的循环时间分支；第 4、5 条，`Skeleton.parseFaces` 缓存纹理尺寸、`SkinnedMesh.collectSkinBounds` 区分原盒与合并边界、渲染静态变换缓存局部值；第 6 条，播放模式使用枚举/record，第三方布尔字面量标注含义；第 7 条，纹理尺寸、膨胀量、交叉臂角度与四分之一转角使用常量；第 9 条，`AnimationPlayer.advance`、`Skeleton.parse`、`SkinnedMesh` 构造及顶点烘焙拆分职责，持物/播放参数由 record 承载，mixin 描述符换行；第 10、11 条，相关注释改为约束与运行行为，使用中文并去除行内全限定类名。Mixin 回调参数保持第三方签名。

收尾补充修正：第 3 条，`ArmorFeatureRendererMixin.kpa$drawArmor` 的帧读取改为明确分支；第 4 条，`MobAnimationRegistry.register` 复用已解析类型，`EntityAnimationApi.play`、`PlayerPlayback.tick/tickEntities`、`AnimationPlayer.Playback.updateScrub`、`MobAnimationRenderer.item`、`MobAnimationServer.requireServerThread` 缓存重复表达式；第 6 条，`EntityProtocolContractTest` 与 `MobAnimationServer` 的 protobuf 布尔字面量注明含义；第 7 条，`MobAnimationServer.COMMAND_PERMISSION_LEVEL` 与 `AnimationPlayer.SCRUB_REWIND_THRESHOLD` 命名既有数值；第 9 条，`AnimationPlayer.scrub` 提取连续游标更新。新增及修改 Java 行的 120 列检查通过。
