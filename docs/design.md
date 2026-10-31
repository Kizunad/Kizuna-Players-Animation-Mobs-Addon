# 实体动画口子设计

## 消息与职责

PA 的 `src/main/proto/kizuna/playeranimation/v1/entity_animation.proto` 是唯一协议源，本仓经组合构建（`-Pkizuna.dir.players-animation` 可改指目录）直接读取并生成 v1 类型，不保存协议副本。`RegisterGeometry` 注册几何；`Play` 提交显式动作；`Stop` 停止显式动作；`Clear` 清理单个实体的两层状态；`Automatic` 提交客户端选择的移动底层和原版动作替代片段。每次请求都由 `EntityAnimationResponse` 返回明确错误码。

PA 公开的 Fabric 入口 `kizuna_player_animation` 只有 `exchange(byte[])`。PA 在入口内部校验协议、保存注册几何、驱动实体播放状态，并在自己的 `LivingEntityRenderer` 接线里使用当前实体姿态、注册几何和原版渲染器提供的纹理缓冲绘制蒙皮网格。新仓不 import `PlayerAnimationClient`、`PlayerMeshRenderer`、`SkinnedMesh` 或其他 PA 内部类。

服务端保存每个实体的活动 `Play` 快照，通过自身 S2C 通道按追踪关系发送；新追踪者收到相同请求身份和起点。客户端在主线程把显式请求交给 PA，同时从肢体速度、姿态、挥手、喝药及施法状态选择 `Automatic`，自动层不发网络包。交易客户不属于原版同步字段，因此仅在交易开始和结束时发送显式交互动作。

自动层从中立骨姿态取样，彻底替代原版交叉臂、举斧等姿势；显式动作最后混合，结束后露出持续推进的自动层。移动片段只在状态变化时启动，动作按进度 scrub，两者共用 PA 淡入淡出。人体功法映射归 Human 的公开内容 API，Threatening 经本附属的 `playFrom/stop` 同步，不在本附属复制功法目录。

渲染选择由 PA 完成，复用玩家相同的 `Skeleton`、`SkinnedMesh`、弯折带和动画采样逻辑。本体使用原版 renderer 选出的纹理缓冲；服饰和覆盖层另行拦截 `RenderLayer.renderColoredCutoutModel`，替换为同一帧的蒙皮网格。仅替换本体不会让 feature 层自动跟随，所以这两条路径分别接线。没有生物播放状态时，两条路径均保留原版绘制。

## 几何来源

`VanillaModelExport` 直接调用原版模型工厂，导出盒尺寸、枢轴、逐面顶点与 UV、dilation、子部件静态变换。26.2 把类人模型的帽子、外套、袖子和裤腿挂到对应主部件之下，导出时把这些覆盖层路径还原为根级部件名，生成脚本的来源编号不变。26.2 导出与 1.20.1 导出逐盒一致；猪灵身体、左臂两根骨内本体与覆盖层方块的先后次序随遍历顺序互换，几何资源沿用 1.20.1 生成结果。`scripts/generate_mob_geometry.py` 按 PA classic 的关节切口裁切原版体积，以原版面的仿射 UV 插值生成 `geo/mob_skeletons.geo.json`；classic 只提供骨层级、切口和挂点参考，不复制玩家皮肤几何。切口内部不额外造面，指头只切分原版手末端已有体积。

资源含 11 份几何，保留完整的 24 可见骨定义，加 root 与两个手持挂点。僵尸使用原版 Biped 工厂的 64×64 布局；骷髅系使用 64×32 纹理和 2×12×2 四肢；猪灵保留宽头、鼻部和静态旋转双耳；村民保留头鼻、身体、袍子和帽沿；灾厄村民保留独立双臂；僵尸村民、溺尸、女巫使用各自模型工厂。女巫鼻痣的负 dilation 烘焙为实际尺寸，帽子静态旋转和 UV 保留。外层只保留原版确有的盒与厚度。

村民职业、等级和群系由原版 `VillagerProfessionLayer` 依次选择纹理和帽子显隐；PA 拦截其 `RenderLayer.renderColoredCutoutModel` 顶点提交，使用本体同一次采样的蒙皮矩阵。溺尸、流浪者改用原版 dilation 为 0.25 像素的专用外层网格。村民服饰本身包含袍子和帽子膨胀方块，保留原版厚度。盔甲沿用原版材质、染色、纹饰和附魔缓冲，按原版绑定几何烘焙后复用身体骨骼矩阵。手持物使用手骨挂点。

村民和流浪商人的原版贴图在 Illager 分臂区域 `(40,46)..(56,64)` 的 288 个像素全部透明；因此把原版交叉臂上臂和横梁沿中线切成两臂，UV 从原版四边形插值，不制造指头体积。女巫沿用同样的分臂处理。已注册生物进入自动层后持续使用 PA 独立关节；凋灵骷髅继续使用原版 renderer 的 1.2 倍缩放，几何不重复放大。

PA 为非 Biped 渲染器补齐原版盔甲材质入口；手持物始终使用同帧手骨，不受原版举斧状态的显隐开关限制。头饰、头颅和头戴旗帜复用原版头部 feature，并在其前应用 PA 头骨变换。被替代的装备 feature 跳过，职业服饰等材质层继续绘制。

## 运行与验证

服务端调试命令是 `/kizuna_anim_mob play <targets> <animation> [loop]` 和 `/kizuna_anim_mob stop <targets>`，权限为 2；`<animation>` 的补全取 PA `ServerPlayerAnimations.animationIds()`（PA 内置动画索引），不另维护列表。几何边界、协议编解码、服务端追踪补发和实体移除清理分别由单元测试与 GameTest 覆盖；客户端截图脚本把结果放在 `build/mob-screens/`。

服务端快照保留到显式停止或实体卸载，不自动按客户端动画时长到期。后来追踪时补发已经结束的一次性动作，PA 会按原起点跳过过期播放。客户端错误只记录在本地日志，不向服务端回传结果。玩家仍使用 PA 的玩家事件与装备语义，生物不发布 `PlayerAnimations.EVENT`。
