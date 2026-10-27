# 实体动画口子设计

## 消息与职责

PA 的 `src/main/proto/kizuna/playeranimation/v1/entity_animation.proto` 是唯一协议源，本仓通过 `-PkpaDir` 直接读取并生成 v1 类型，不保存协议副本。消息分成四类：`RegisterGeometry` 携带几何 JSON、实体类型绑定和可选外层几何 ID；`Play` 携带实体运行时 ID、动画 ID、起点、scrub 进度和可选循环覆盖；`Stop` 携带实体 ID 与动作范围；`Clear` 清理单个客户端实体。每次请求都由 `EntityAnimationResponse` 返回明确错误码。

PA 公开的 Fabric 入口 `kizuna_player_animation` 只有 `exchange(byte[])`。PA 在入口内部校验协议、保存注册几何、驱动实体播放状态，并在自己的 `LivingEntityRenderer` 接线里使用当前实体姿态、注册几何和原版渲染器提供的纹理缓冲绘制蒙皮网格。新仓不 import `PlayerAnimationClient`、`PlayerMeshRenderer`、`SkinnedMesh` 或其他 PA 内部类。

新仓服务端保存每个实体的活动 `Play` 快照，通过自己的 Fabric S2C 通道按追踪关系发送；新加入追踪范围的客户端在下一 tick 收到同一份起点请求。新仓客户端只解码网络包，在客户端主线程把 protobuf 原样交给 PA 入口。这样服务端同步和 PA 客户端播放职责分开，PA 不需要把现有玩家同步状态扩大成通用玩法 API。

渲染选择由 PA 完成，复用玩家相同的 `Skeleton`、`SkinnedMesh`、弯折带和动画采样逻辑。本体使用原版 renderer 选出的纹理缓冲；服饰和覆盖层另行拦截 `FeatureRenderer.renderModel`，替换为同一帧的蒙皮网格。仅替换本体不会让 feature 层自动跟随，所以这两条路径分别接线。没有生物播放状态时，两条路径均保留原版绘制。

## 几何来源

`VanillaModelExport` 直接调用 Minecraft 1.20.1 原版模型工厂，导出盒尺寸、枢轴、逐面顶点与 UV、dilation、子部件静态变换。`scripts/generate_mob_geometry.py` 按 PA classic 的关节切口裁切原版体积，以原版面的仿射 UV 插值生成 `geo/mob_skeletons.geo.json`；classic 只提供骨层级、切口和挂点参考，不复制玩家皮肤几何。切口内部不额外造面，指头只切分原版手末端已有体积。

资源含 10 份几何，保留完整的 24 可见骨定义，加 root 与两个手持挂点。僵尸使用原版 Biped 工厂的 64×64 布局；骷髅系使用 64×32 纹理和 2×12×2 四肢；猪灵保留原版宽头、鼻部和带静态旋转的双耳；村民保留 8×10×8 头、鼻子、8×12×6 身体、袍子和帽沿；灾厄村民保留其原版独立双臂；僵尸村民、溺尸使用各自模型工厂。外层只保留原版确有的盒与厚度，不为每个可见骨强加玩家外层。

村民职业、等级和群系由原版 `VillagerClothingFeatureRenderer` 依次选择纹理和帽子显隐；PA 拦截其 `FeatureRenderer.renderModel` 顶点提交，使用本体同一次采样的蒙皮矩阵。溺尸、流浪者改用原版 dilation 为 0.25 像素的专用外层网格。村民服饰本身包含袍子和帽子膨胀方块，保留原版厚度。盔甲沿用原版材质、染色、纹饰和附魔缓冲，按原版绑定几何烘焙后复用身体骨骼矩阵。手持物使用手骨挂点。

村民和流浪商人的原版贴图在 Illager 分臂区域 `(40,46)..(56,64)` 的 288 个像素全部透明；因此把原版交叉臂上臂和横梁沿中线切成两臂，UV 从原版四边形插值，不制造指头体积。未播放时直接走原版交叉臂模型；播放时展开到独立上臂、前臂、手骨。凋灵骷髅继续使用原版 renderer 的 1.2 倍缩放，几何不重复放大。

## 运行与验证

服务端调试命令是 `/kizuna_anim_mob play <targets> <animation> [loop]` 和 `/kizuna_anim_mob stop <targets>`，权限为 2。几何边界、协议编解码、服务端追踪补发和实体移除清理分别由单元测试与 GameTest 覆盖；客户端截图脚本把结果放在 `build/mob-screens/`。

服务端快照保留到显式停止或实体卸载，不自动按客户端动画时长到期。后来追踪时补发已经结束的一次性动作，PA 会按原起点跳过过期播放。客户端错误只记录在本地日志，不向服务端回传结果。玩家仍使用 PA 的玩家事件与装备语义，生物不发布 `PlayerAnimations.EVENT`。
