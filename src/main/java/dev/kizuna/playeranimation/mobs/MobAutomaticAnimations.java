package dev.kizuna.playeranimation.mobs;

import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Activity;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.ActionHand;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Motion;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Gait;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Posture;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Automatic;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Clear;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.SpellcastingIllagerEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.util.Hand;

/** 观察客户端已有实体字段，只向本地 PA 字节入口提交自动层。 */
final class MobAutomaticAnimations {
    private static final float SWING_END = 0.6f;
    private static final float RAISED_ARM_PROGRESS = 0.3f;
    private static final float HELD_ITEM_PROGRESS = 1;
    private static final int HURT_TICKS = 10;
    private static final int SPELL_TICKS = 40;
    private static final int DRINK_TICKS = 32;
    private static final int CHARGE_TICKS = 25;
    private static final int BOW_TICKS = 38;
    private static final int INTERACTION_TICKS = 8;
    private final Map<Integer, ActionClock> clocks = new HashMap<>();
    private ClientWorld world;

    private record ActionClock(Activity activity, int startedAt) { }

    void tick(MinecraftClient client) {
        // 世界切换后实体 ID 可复用，自动状态不能跨世界保存。
        if (world != client.world) {
            clocks.clear();
            world = client.world;
        }
        if (world == null || client.isPaused()) {
            return;
        }
        clocks.keySet().removeIf(id -> world.getEntityById(id) == null);
        for (var entity : world.getEntities()) {
            if (entity instanceof LivingEntity living && MobGeometryAssets.supports(living.getType())) {
                update(living);
            }
        }
    }

    void reset() {
        // 资源重载会清理 PA 播放器，下个 tick 从现存实体重新建立自动层。
        clocks.clear();
    }

    private void update(LivingEntity entity) {
        // 死亡交由原版整体倒地变换，移除自动与显式动作，避免死亡后残留挥砍。
        int entityId = entity.getId();
        if (!entity.isAlive()) {
            if (clocks.remove(entityId) != null) {
                Clear clear = Clear.newBuilder().setEntityId(entityId).build();
                MobAnimationClient.exchange(EntityAnimationRequest.newBuilder().setClear(clear).build());
            }
            return;
        }
        Activity activity = activity(entity);
        ActionClock clock = clocks.get(entityId);
        if (clock == null || clock.activity() != activity) {
            clock = new ActionClock(activity, entity.age);
            clocks.put(entityId, clock);
        }
        Gait gait = entity.isSprinting() ? Gait.SPRINTING : Gait.NATURAL;
        Motion motion = new Motion(posture(entity), entity.limbAnimator.getSpeed(), gait);
        ActionHand hand = ActionHand.MAIN;
        if (entity.getActiveHand() == Hand.OFF_HAND
                || activity == Activity.ATTACK && entity.preferredHand == Hand.OFF_HAND) {
            hand = ActionHand.OFF;
        }
        var clips = MobAnimationSelection.select(motion, activity, hand);
        Automatic automatic = Automatic.newBuilder()
                .setEntityId(entityId)
                .setBaseAnimationId(clips.base())
                .setActionAnimationId(clips.action())
                .setActionProgress(progress(entity, clock))
                .build();
        MobAnimationClient.exchange(EntityAnimationRequest.newBuilder().setAutomatic(automatic).build());
    }

    static Activity activity(LivingEntity entity) {
        // 真实挥手优先于持斧待机；服务端显式功法在 PA 内部独立叠在自动层之上。
        if (entity.hurtTime > 0) {
            return Activity.HURT;
        }
        if (entity.handSwinging) {
            return Activity.ATTACK;
        }
        if (entity instanceof WitchEntity witch && witch.isDrinking()) {
            return Activity.DRINK;
        }
        if (entity instanceof SpellcastingIllagerEntity caster && caster.isSpellcasting()) {
            return Activity.SPELL;
        }
        if (entity instanceof IllagerEntity illager) {
            return switch (illager.getState()) {
                case ATTACKING -> Activity.AXE_READY;
                case CROSSBOW_CHARGE -> Activity.CROSSBOW_CHARGE;
                case CROSSBOW_HOLD -> Activity.CROSSBOW_HOLD;
                case BOW_AND_ARROW -> Activity.BOW;
                case SPELLCASTING -> Activity.SPELL;
                default -> Activity.NONE;
            };
        }
        if (entity instanceof MerchantEntity merchant && merchant.getCustomer() != null) {
            return Activity.TRADE;
        }
        return Activity.NONE;
    }

    private static Posture posture(LivingEntity entity) {
        // 原版同步状态决定支撑方式，移动速度只用于站立或潜行下的步态。
        if (entity.hasVehicle()) {
            return Posture.RIDING;
        }
        if (entity.isSleeping()) {
            return Posture.SLEEPING;
        }
        if (entity.isInSwimmingPose() && entity.isTouchingWater()) {
            return Posture.SWIMMING;
        }
        if (entity.isClimbing() && !entity.isOnGround()) {
            return Posture.CLIMBING;
        }
        if (entity.isInSneakingPose()) {
            return Posture.SNEAKING;
        }
        return Posture.STANDING;
    }

    private static float progress(LivingEntity entity, ActionClock clock) {
        // 保持动作取预备段，持续交互重复现有片段；挥砍直接跟随原版挥手游标。
        int elapsed = entity.age - clock.startedAt();
        return switch (clock.activity()) {
            case ATTACK -> entity.getHandSwingProgress(1) * SWING_END;
            case AXE_READY -> RAISED_ARM_PROGRESS;
            case CROSSBOW_HOLD -> HELD_ITEM_PROGRESS;
            case CROSSBOW_CHARGE -> Math.min(1, (float) elapsed / CHARGE_TICKS);
            case BOW -> Math.min(SWING_END, (float) elapsed / BOW_TICKS);
            case SPELL -> (float) (elapsed % SPELL_TICKS) / SPELL_TICKS;
            case DRINK -> Math.min(1, (float) elapsed / DRINK_TICKS);
            case TRADE -> (float) (elapsed % INTERACTION_TICKS) / INTERACTION_TICKS;
            case HURT -> 1 - (float) entity.hurtTime / HURT_TICKS;
            case NONE -> 0;
        };
    }
}
