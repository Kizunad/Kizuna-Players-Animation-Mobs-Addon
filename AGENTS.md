# 生物动画附属仓约定

- 目标环境为 Minecraft 26.2、Fabric、Java 25。
- 沟通、文档和说明性注释使用简体中文，公开标识符使用清晰英文。
- 通过 PA 的 `kizuna_player_animation` Fabric 入口交换 protobuf 字节；新仓不引用 PA 的内部类。
- 服务端只保存实体动画状态并按追踪关系发送，客户端只在主线程调用 PA 入口。
- 几何资源必须保留每个关节的 24 骨定义和原版尺寸、枢轴、UV；不得退回六个 ModelPart 的姿态压缩。
- 不提交、不推送；构建目录、运行目录和截图生成物不纳入版本控制。
- 修改前先读相邻代码，测试只覆盖公开契约、同步清理和几何边界。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
