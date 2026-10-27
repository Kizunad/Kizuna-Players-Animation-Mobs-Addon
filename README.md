# Kizuna Players Animation Mobs Addon

这个附属把原版类人生物的几何和贴图注册到 Kizuna Players Animation（PA），让 PA 的 24 骨蒙皮、关节弯折带和动画播放路径直接服务于生物。

设计说明见 [`docs/design.md`](docs/design.md)。PA 只公开 `kizuna_player_animation` Fabric 入口，入口只接收和返回 protobuf 字节；本仓不 import PA 的客户端渲染内部类。服务端保存实体播放状态，按实体追踪关系发送同一份请求，客户端收到后在主线程交给 PA。

返工内容、验证结果和未测范围见 [`docs/rework-report.md`](docs/rework-report.md)。环境为 Minecraft 1.20.1、Fabric、Java 17；运行时必须安装 PA。

先在相邻 `Kizuna-Players-Animation` 仓执行 `./gradlew test build`，再在本仓执行：

```bash
./gradlew test build
./gradlew runClientTest
```

默认从相邻 PA 源码读取唯一 proto，并使用其本地构建的 `0.1.0-SNAPSHOT` JAR。其他位置使用 `./gradlew -PkpaDir="/path/to/Kizuna-Players-Animation" test build`；该 PA 目录须已构建且包含实体字节入口。本次依赖本地源码，尚未锁定到已发布版本。

`runClientTest` 先执行服务端 GameTest，再加载测试世界，生成 12 张同帧截图至 `build/mob-screens/`。几何复现命令：

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

命令要求权限 2。省略 `loop` 时沿用动画资源定义；`true` 强制循环，`false` 强制一次播放。构建、运行目录和截图不纳入版本控制；本次不提交、不推送。
