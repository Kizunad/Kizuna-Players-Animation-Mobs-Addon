package dev.kizuna.playeranimation.mobs.mixin;

import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import dev.kizuna.playeranimation.mobs.MobIdlePoses;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractVillager.class)
public abstract class MerchantInteractionMixin {
    @Inject(method = "setTradingPlayer", at = @At("HEAD"))
    private void kpa$tradeAnimation(Player customer, CallbackInfo callback) {
        // customer 不向旁观客户端同步；只在交易边界发送显式动作，结束后恢复自动层。
        AbstractVillager merchant = (AbstractVillager) (Object) this;
        if (merchant.level().isClientSide() || merchant.getTradingPlayer() == customer) {
            return;
        }
        if (MobIdlePoses.seated(merchant)) {
            // 坐在火边的不站起来递东西，把手收进袖里；交易结束也不用停递东西的动作。
            if (customer != null) {
                MobIdlePoses.talk(merchant);
            }
            return;
        }
        if (customer == null) {
            MobAnimationServer.stop(merchant, "use_item");
        } else {
            MobAnimationServer.repeat(merchant, "use_item");
        }
    }
}
