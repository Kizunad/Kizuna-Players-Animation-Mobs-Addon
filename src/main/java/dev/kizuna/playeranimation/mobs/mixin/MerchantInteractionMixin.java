package dev.kizuna.playeranimation.mobs.mixin;

import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantEntity.class)
public abstract class MerchantInteractionMixin {
    @Inject(method = "setCustomer", at = @At("HEAD"))
    private void kpa$tradeAnimation(PlayerEntity customer, CallbackInfo callback) {
        // customer 不向旁观客户端同步；只在交易边界发送显式动作，结束后恢复自动层。
        MerchantEntity merchant = (MerchantEntity) (Object) this;
        if (merchant.getWorld().isClient() || merchant.getCustomer() == customer) {
            return;
        }
        if (customer == null) {
            MobAnimationServer.stop(merchant, "use_item");
        } else {
            MobAnimationServer.repeat(merchant, "use_item");
        }
    }
}
