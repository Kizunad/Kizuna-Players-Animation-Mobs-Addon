package dev.kizuna.playeranimation.mobs.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 本地截图实体通过原版同步字段进入使用状态；该入口只存在于测试包。 */
@Mixin(LivingEntity.class)
public interface LivingUseFlagsAccess {
    @Accessor("DATA_LIVING_ENTITY_FLAGS")
    static EntityDataAccessor<Byte> useFlags() {
        throw new AssertionError("Mixin accessor not applied");
    }
}
