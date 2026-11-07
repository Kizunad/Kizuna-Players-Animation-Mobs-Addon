# Kizuna Players Animation Mobs Addon

这个附属把原版类人生物的几何和贴图注册到 Kizuna Players Animation（PA），让 PA 的 24 骨蒙皮、关节弯折带和动画播放路径直接服务于生物。

设计说明见 [`docs/design.md`](docs/design.md)。PA 只公开 `kizuna_player_animation` Fabric 入口，入口只接收和返回 protobuf 字节；本仓不 import PA 的客户端渲染内部类。服务端保存实体播放状态，按实体追踪关系发送同一份请求，客户端收到后在主线程交给 PA。

返工内容、验证结果和未测范围见 [`docs/rework-report.md`](docs/rework-report.md)。环境为 Minecraft 26.2、Fabric、Java 25；运行时必须安装 PA。

已注册生物自动使用 PA 移动与动作层。村民、流浪商人、卫道士、掠夺者、唤魔者、幻术师、女巫均已接入；
待机、行走、奔跑、潜行、游泳、骑乘、睡眠、攀爬直接观察客户端已有状态，不增加移动网络包。
自动层从中立骨姿态取样，替代原版举斧与交叉臂。显式服务端动作位于自动层之上，结束后回到移动层，
两层均复用 PA 资源的淡入淡出。主副手、盔甲、头饰和头戴旗帜使用同帧骨骼绘制。
26.2 的幼年村民具有独立模型和 UV，目前保留原版渲染，成年后再使用已注册的 PA 村民几何。
沼骸和焦骸按 26.2 原版模型工厂导出几何；沼骸蘑菇保留零厚度双面、旋转和剪除显隐，
衣层按原版 0.2 像素外扩。焦骸使用原版双身体层模型，不复用普通骷髅的几何文件。

当前替代片段：待机用 `stance_idle`，举斧预备用 `trident_charge` 预备段，近战用 `attack_main/off`，
拉弩和持弩用 `crossbow_charge/hold`，幻术师拉弓用 `bow_draw`，施法用 `technique_charge`，
女巫饮药用 `drink`，交易用 `use_item`，受击用 `block_one_hand_hit`。这些都是已有片段，未新制动画。
矛由 PA 共用的客户端采样器读取本帧使用与挥手状态，播放 `spear_charge(_off)`、`spear_stab(_off)`；
本附属识别矛戳刺，避免再叠普通挥砍。持矛手覆盖移动层，其余身体继续步态与骑乘动作。
该路径不增加动画网络包，左右惯用手、主副手与第一人称处理和玩家保持一致。
交易客户不在原版客户端字段中同步，因此交易开始、结束通过服务端显式请求同步给旁观者。

Meridian 宿主经本附属播放 PA 功法片段，主副手选择由宿主映射到对应动作。
客户端只在起播时按原始起点映射本地时钟，之后连续播放；伤害时刻与结算由服务端负责。
客户端验收包含破门斩主副手的玩家、左惯用手玩家及卫道士画面，以及真实 AI 施放链路。

先在相邻 `Kizuna-Players-Animation` 仓执行 `./gradlew test build`，再在本仓执行：

```bash
./gradlew test build
./gradlew runClientTest
```

PA 以组合构建接入：默认从相邻 PA 源码读取唯一 proto 并随本仓一起构建，无需事先单独打包。相邻目录由 Framework 的装配清单给出，其他位置使用 `./gradlew -Pkizuna.dir.players-animation="/path/to/Kizuna-Players-Animation" test build`；该 PA 源码须包含实体字节入口。版本号取 Framework 的 `scripts/libs.versions.toml`。本次依赖本地源码，尚未锁定到已发布版本。

`runClientTest` 使用相邻 Meridian 完整开发装配，先执行服务端 GameTest，再加载测试世界。
启动前把 `build/clienttest/options.txt` 的 `onboardAccessibility` 写成 `false`（文件不存在时新建），跳过 26.2 首启无障碍引导页。
截图保存在 `build/mob-screens/`，包含七种生人、幼年村民、卫道士走跑、举斧、挥砍、双手装备、盔甲、旗帜，
以及真炁卫道士经真实 AI 和功法宿主下发 PA 的施放场景。正式模组只依赖 PA，开发整合验收需要先构建相邻整套产物。
2026-11-07 验收完成 167 张整帧采集，覆盖沼骸剪蘑菇、焦骸、持矛生物、玩家双手与左惯用手、
五脏诀双体型、烟花连弩、收纳袋倒出和新坐骑乘客挂点。截图在第一人称手与 HUD 绘制完成后捕获。
新坐骑场景验证静态挂点，未覆盖鹦鹉螺水下游动；截图按上述项目抽查，不代表逐帧完整动作审查。
几何复现命令：

```bash
./gradlew exportVanillaModels
python3 "scripts/generate_mob_geometry.py" --check
python3 "scripts/test_mob_geometry.py"
```

需要重新生成几何时去掉 `--check`；PA 不在相邻目录时，生成脚本同样支持 `--kpa-dir "/path/to/PA"`。

调试命令：

```text
/kizuna_anim_mob play <targets> <animation> [loop]
/kizuna_anim_mob stop <targets>
```

命令要求权限 2，`<animation>` 按 PA 内置动画索引补全。省略 `loop` 时沿用动画资源定义；`true` 强制循环，`false` 强制一次播放。构建、运行目录和截图不纳入版本控制。

服务端公开 API：`MobAnimationServer.play`、`playFrom`、`repeat`、`stop`、`stopAll`。
`playFrom` 接收原始世界起播 tick，范围为 `[0, 当前 tick]`；追踪补发保留请求身份与起点。
`snapshot` 返回不可变同步请求供宿主诊断。所有服务端 API 必须在服务端主线程调用。
