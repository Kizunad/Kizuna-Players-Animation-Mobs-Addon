package dev.kizuna.playeranimation.mobs;

/** 纯状态到现有 PA 片段的映射；不读取世界，也不发送网络请求。 */
public final class MobAnimationSelection {
    public static final float MOVING_THRESHOLD = 0.01f;
    public static final float RUNNING_THRESHOLD = 0.65f;

    public enum Posture { STANDING, SNEAKING, SWIMMING, RIDING, SLEEPING, CLIMBING }
    public enum Activity {
        NONE, ATTACK, SPEAR, AXE_READY, CROSSBOW_CHARGE, CROSSBOW_HOLD, BOW, SPELL, DRINK, TRADE, HURT
    }
    public enum ActionHand { MAIN, OFF }
    public enum Gait { NATURAL, SPRINTING }
    public record Motion(Posture posture, float limbSpeed, Gait gait) { }
    public record Clips(String base, String action) { }

    public static Clips select(Motion motion, Activity activity, ActionHand hand) {
        // 动作与移动分别选择，施法、喝药或挥砍时仍能保留下身移动。
        String action = switch (activity) {
            // 矛由 PA 按本帧使用与挥手状态采样，自动层只继续提供下身姿态。
            case NONE, SPEAR -> "";
            case ATTACK -> "attack_main";
            case AXE_READY -> "trident_charge";
            case CROSSBOW_CHARGE -> "crossbow_charge";
            case CROSSBOW_HOLD -> "crossbow_hold";
            case BOW -> "bow_draw";
            case SPELL -> "technique_charge";
            case DRINK -> "drink";
            case TRADE -> "use_item";
            case HURT -> "block_one_hand_hit";
        };
        String handedAction = action;
        if (hand == ActionHand.OFF && activity == Activity.ATTACK) {
            handedAction = "attack_off";
        } else if (hand == ActionHand.OFF && (activity == Activity.DRINK
                || activity == Activity.CROSSBOW_CHARGE || activity == Activity.CROSSBOW_HOLD
                || activity == Activity.BOW)) {
            handedAction = action + "_off";
        }
        return new Clips(base(motion), handedAction);
    }

    private static String base(Motion motion) {
        // 骑乘、游泳等原版状态先于速度判断；普通待机借用已交付的架势呼吸循环。
        float speed = motion.limbSpeed();
        return switch (motion.posture()) {
            case RIDING -> "ride";
            case SWIMMING -> "swim";
            case SLEEPING -> "sleep";
            case CLIMBING -> "climb";
            case SNEAKING -> {
                if (speed > MOVING_THRESHOLD) {
                    yield "sneak_walk";
                }
                yield "sneak";
            }
            case STANDING -> {
                if (speed <= MOVING_THRESHOLD) {
                    yield "stance_idle";
                }
                if (motion.gait() == Gait.SPRINTING || speed >= RUNNING_THRESHOLD) {
                    yield "run";
                }
                yield "walk";
            }
        };
    }

    private MobAnimationSelection() {
        // 静态映射不保存实体状态。
    }
}
