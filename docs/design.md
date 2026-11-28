# 实体动画口子设计

## 消息与职责

PA 的 `src/main/proto/kizuna/playeranimation/v1/entity_animation.proto` 是唯一协议源，本仓经组合构建（`-Pkizuna.dir.players-animation` 可改指目录）直接读取并生成 v1 类型，不保存协议副本。`RegisterGeometry` 注册几何；`Play` 提交显式动作；`Stop` 停止显式动作；`Clear` 清理单个实体的两层状态；`Automatic` 提交客户端选择的移动底层和原版动作替代片段。每次请求都由 `EntityAnimationResponse` 返回明确错误码。

PA 公开的 Fabric 入口 `kizuna_player_animation` 只有 `exchange(byte[])`。PA 在入口内部校验协议、保存注册几何、驱动实体播放状态，并在自己的 `LivingEntityRenderer` 接线里使用当前实体姿态、注册几何和原版渲染器提供的纹理缓冲绘制蒙皮网格。新仓不 import `PlayerAnimationClient`、`PlayerMeshRenderer`、`SkinnedMesh` 或其他 PA 内部类。

服务端保存每个实体的活动 `Play` 快照，通过自身 S2C 通道按追踪关系发送；新追踪者收到相同请求身份和起点。客户端在主线程把显式请求交给 PA，同时从肢体速度、姿态、挥手、喝药及施法状态选择 `Automatic`，自动层不发网络包。交易客户不属于原版同步字段，因此仅在交易开始和结束时发送显式交互动作。

自动层从中立骨姿态取样，彻底替代原版交叉臂、举斧等姿势；显式动作最后混合，结束后露出持续推进的自动层。移动片段只在状态变化时启动，动作按进度 scrub，两者共用 PA 淡入淡出。人体功法映射归 Human 的公开内容 API，Threatening 经本附属的 `playFrom/stop` 同步，不在本附属复制功法目录。

渲染选择由 PA 完成，复用玩家相同的 `Skeleton`、`SkinnedMesh`、弯折带和动画采样逻辑。本体使用原版 renderer 选出的纹理缓冲；服饰和覆盖层另行拦截 `RenderLayer.renderColoredCutoutModel`，替换为同一帧的蒙皮网格。仅替换本体不会让 feature 层自动跟随，所以这两条路径分别接线。没有生物播放状态时，两条路径均保留原版绘制。

## 日常姿态

`MobIdlePoses` 在服务端给闲着的生物摆日常姿态，片段是 PA 的 `idle_*`（进入、循环、退出）和 `campfire_*`（坐下、烤火、收手入袖、抱袖、起身、快版起身），经同一条 `play/stop` 同步，追踪者看到的一致，GameTest 也能直接查。

- **谁摆**：有 AI 的成年村民、流浪商人用通用池（叉腰、抱臂、蹲坐、背手），站定 3–8 秒开始摆，一种摆 20–60 秒，退出后歇一会儿换另一种。没有 AI 的生物归剧本管，只在有标签时摆。村民、流浪商人用村民骨架版（`_robed`），其余（例如玩家模型的角色）用玩家版。
- **标签**（宿主写，本附属读）：`kizuna_players_animation_mobs.idle.<姿态>` 只摆这几种，姿态名为 `akimbo`、`arms_crossed`、`squat`、`hands_behind`、`lean_counter`、`campfire`；只指定一种就一直摆着，`campfire` 是坐在火边，一直坐着。`...idle.sleeves` 叫坐着的把手收进袖里并一直抱袖。`...idle.talk` 是一次性的交谈打断，读到就移除。撤掉火边标签就起身：抱着袖播整段起身，烤着火播快版。
- **打断**：挨打在扣血的同一 tick 收手（`AFTER_DAMAGE`），站着的停下姿态露出自动层，坐着的播快版起身；有目标或在攻击时退出；在走、离地、骑乘、睡觉、入水时立刻停下；交易或交谈时站着的停下，坐着的收手入袖（坐着交易不播站着递东西的 `use_item`）。打断后至少歇 10 秒再摆。客户端自动层看到火边片段在播时不叠站着的受击动作，由快版起身接。
- 进入段、退出段和火边各段的 tick 数写在 `MobIdlePoses` 里，单测按 PA 打包的剪辑时长核对。

## 几何来源

`VanillaModelExport` 直接调用原版模型工厂，导出盒尺寸、枢轴、逐面顶点与 UV、dilation、子部件静态变换。26.2 把类人模型的帽子、外套、袖子和裤腿挂到对应主部件之下，导出时把这些覆盖层路径还原为根级部件名，生成脚本的来源编号不变。26.2 导出与 1.20.1 导出逐盒一致；猪灵身体、左臂两根骨内本体与覆盖层方块的先后次序随遍历顺序互换，几何资源沿用 1.20.1 生成结果。`scripts/generate_mob_geometry.py` 按 PA classic 的关节切口裁切原版体积，以原版面的仿射 UV 插值生成 `geo/mob_skeletons.geo.json`；classic 只提供骨层级、切口和挂点参考，不复制玩家皮肤几何。切口内部不额外造面，指头只切分原版手末端已有体积。

资源含 11 份几何，保留完整的 24 可见骨定义，加 root 与两个手持挂点。僵尸使用原版 Biped 工厂的 64×64 布局；骷髅系使用 64×32 纹理和 2×12×2 四肢；猪灵保留宽头、鼻部和静态旋转双耳；村民保留头鼻、身体、袍子和帽沿；灾厄村民保留独立双臂；僵尸村民、溺尸、女巫使用各自模型工厂。女巫鼻痣的负 dilation 烘焙为实际尺寸，帽子静态旋转和 UV 保留。外层只保留原版确有的盒与厚度。

村民职业、等级和群系由原版 `VillagerProfessionLayer` 依次选择纹理和帽子显隐；PA 拦截其 `RenderLayer.renderColoredCutoutModel` 顶点提交，使用本体同一次采样的蒙皮矩阵。溺尸、流浪者改用原版 dilation 为 0.25 像素的专用外层网格。村民服饰本身包含袍子和帽子膨胀方块，保留原版厚度。盔甲沿用原版材质、染色、纹饰和附魔缓冲，按原版绑定几何烘焙后复用身体骨骼矩阵。手持物使用手骨挂点。

村民和流浪商人的原版贴图在 Illager 分臂区域 `(40,46)..(56,64)` 的 288 个像素全部透明；因此把原版交叉臂上臂和横梁沿中线切成两臂，UV 从原版四边形插值，不制造指头体积。女巫沿用同样的分臂处理。已注册生物进入自动层后持续使用 PA 独立关节；凋灵骷髅继续使用原版 renderer 的 1.2 倍缩放，几何不重复放大。

PA 为非 Biped 渲染器补齐原版盔甲材质入口；手持物始终使用同帧手骨，不受原版举斧状态的显隐开关限制。头饰、头颅和头戴旗帜复用原版头部 feature，并在其前应用 PA 头骨变换。被替代的装备 feature 跳过，职业服饰等材质层继续绘制。

## 运行与验证

服务端调试命令是 `/kizuna_anim_mob play <targets> <animation> [loop]` 和 `/kizuna_anim_mob stop <targets>`，权限为 2；`<animation>` 的补全取 PA `ServerPlayerAnimations.animationIds()`（PA 内置动画索引），不另维护列表。几何边界、协议编解码、服务端追踪补发和实体移除清理分别由单元测试与 GameTest 覆盖；客户端截图脚本把结果放在 `build/mob-screens/`。

服务端快照保留到显式停止或实体卸载，不自动按客户端动画时长到期。后来追踪时补发已经结束的一次性动作，PA 会按原起点跳过过期播放。客户端错误只记录在本地日志，不向服务端回传结果。玩家仍使用 PA 的玩家事件与装备语义，生物不发布 `PlayerAnimations.EVENT`。
