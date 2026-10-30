package dev.kizuna.playeranimation.mobs;

import dev.kizuna.playeranimation.mobs.MobAnimationSelection.ActionHand;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Activity;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Motion;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Gait;
import dev.kizuna.playeranimation.mobs.MobAnimationSelection.Posture;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MobAnimationSelectionTest {
    private static final float WALK_SPEED = 0.3f;
    private static final float RUN_SPEED = 0.8f;

    @Test
    void movementAndPostureSelectDeliveredClips() {
        // 速度边界与特殊姿态共同决定底层，骑乘等状态必须压过奔跑。
        assertBase("stance_idle", new Motion(Posture.STANDING, 0, Gait.NATURAL));
        assertBase("walk", new Motion(Posture.STANDING, WALK_SPEED, Gait.NATURAL));
        assertBase("run", new Motion(Posture.STANDING, RUN_SPEED, Gait.NATURAL));
        assertBase("run", new Motion(Posture.STANDING, WALK_SPEED, Gait.SPRINTING));
        assertBase("sneak", new Motion(Posture.SNEAKING, 0, Gait.NATURAL));
        assertBase("sneak_walk", new Motion(Posture.SNEAKING, WALK_SPEED, Gait.NATURAL));
        var special = Map.of(Posture.SWIMMING, "swim", Posture.RIDING, "ride",
                Posture.SLEEPING, "sleep", Posture.CLIMBING, "climb");
        special.forEach((posture, clip) -> assertBase(clip, new Motion(posture, RUN_SPEED, Gait.SPRINTING)));
    }

    @Test
    void vanillaActionsUseExistingPaClipsAndPreserveMovement() {
        // 所有七种类人的专用姿势都有明确替代，副手挥砍不能误用主手模板。
        var actions = Map.of(Activity.ATTACK, "attack_main", Activity.AXE_READY, "trident_charge",
                Activity.CROSSBOW_CHARGE, "crossbow_charge", Activity.CROSSBOW_HOLD, "crossbow_hold",
                Activity.BOW, "bow_draw", Activity.SPELL, "technique_charge", Activity.DRINK, "drink",
                Activity.TRADE, "use_item", Activity.HURT, "block_one_hand_hit", Activity.NONE, "");
        Motion motion = new Motion(Posture.STANDING, WALK_SPEED, Gait.NATURAL);
        actions.forEach((activity, clip) -> {
            var selection = MobAnimationSelection.select(motion, activity, ActionHand.MAIN);
            assertEquals(clip, selection.action());
            assertEquals("walk", selection.base());
        });
        assertEquals("attack_off", MobAnimationSelection.select(motion, Activity.ATTACK, ActionHand.OFF).action());
        assertEquals("drink_off", MobAnimationSelection.select(motion, Activity.DRINK, ActionHand.OFF).action());
    }

    private static void assertBase(String clip, Motion motion) {
        // 只验证公开选择结果，不依赖 Minecraft 客户端或内部播放器。
        assertEquals(clip, MobAnimationSelection.select(motion, Activity.NONE, ActionHand.MAIN).base());
    }
}
