package dev.kizuna.playeranimation.mobs;

/** 未单独声明时限的用例沿用 1.20.1 注解默认的 100 tick；26.2 的 Fabric 注解默认只有 20 tick。 */
final class MobGameTestTicks {
    static final int DEFAULT_TIMEOUT = 100;

    private MobGameTestTicks() {
        // 只放时限常量。
    }
}
