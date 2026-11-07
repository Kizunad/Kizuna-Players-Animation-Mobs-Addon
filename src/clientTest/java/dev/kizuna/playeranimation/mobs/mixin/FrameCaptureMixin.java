package dev.kizuna.playeranimation.mobs.mixin;

import dev.kizuna.playeranimation.mobs.MobAnimationClientTest;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 世界、第一人称物品与 HUD 都绘制完后再保存画面。 */
@Mixin(GameRenderer.class)
public abstract class FrameCaptureMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void captureFinishedFrame(DeltaTracker delta, boolean tick, CallbackInfo callback) {
        MobAnimationClientTest.captureFinishedFrame();
    }
}
