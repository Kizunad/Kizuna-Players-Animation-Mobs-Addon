package dev.kizuna.playeranimation.mobs;

import com.mojang.authlib.GameProfile;
import dev.kizuna.playeranimation.mobs.mixin.LivingUseFlagsAccess;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 矛通过真实实体持物、使用和挥手字段触发，不直接播放目标剪辑。 */
final class SpearClientCases {
    private static final float STAB_PROGRESS = 0.30f;
    private static final float WALK_SPEED = 0.30f;
    private static final int USING_ITEM_FLAG = 1;
    private static final int OFF_HAND_FLAG = 2;
    private static final List<String> CASES = List.of("spear_zombie_hold", "spear_husk_charge",
            "spear_zombified_piglin_charge_off", "spear_piglin_stab", "spear_player_charge",
            "spear_player_stab_off", "spear_player_left_charge", "spear_player_left_stab_off");

    private SpearClientCases() { }

    static List<String> names() {
        return CASES;
    }

    static boolean matches(String name) {
        return name.startsWith("spear_");
    }

    static LivingEntity create(Minecraft client, String name) {
        // 玩家沿 AvatarRenderer，四类生物分别沿各自的原版渲染器。
        if (name.startsWith("spear_player_")) {
            var profile = new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Spear_reference");
            RemotePlayer player = new RemotePlayer(client.level, profile);
            if (name.contains("_left_")) {
                player.setMainArm(HumanoidArm.LEFT);
            }
            return player;
        }
        String type = name.substring("spear_".length())
                .replace("_off", "")
                .replace("_charge", "")
                .replace("_stab", "")
                .replace("_hold", "");
        var entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(type)).orElseThrow();
        return (LivingEntity) entityType.create(client.level, MobAnimationClientTest.FIXTURE);
    }

    static void equip(LivingEntity entity, String name) {
        EquipmentSlot slot = EquipmentSlot.MAINHAND;
        if (name.endsWith("_off")) {
            slot = EquipmentSlot.OFFHAND;
        }
        entity.setItemSlot(slot, new ItemStack(Items.NETHERITE_SPEAR));
        if (name.contains("_charge")) {
            // 客户端 startUsingItem 不设置同步标志；模拟原版数据包更新以真正推进使用时间。
            var accessor = LivingUseFlagsAccess.useFlags();
            int flags = entity.getEntityData().get(accessor) | USING_ITEM_FLAG;
            if (hand(name) == InteractionHand.OFF_HAND) {
                flags |= OFF_HAND_FLAG;
            }
            entity.getEntityData().set(accessor, (byte) flags);
        }
    }

    static void verify(LivingEntity entity, String name) {
        if (name.contains("_charge") && (!entity.isUsingItem() || entity.getTicksUsingItem() <= 0
                || entity.getUsedItemHand() != hand(name))) {
            throw new IllegalStateException("未进入对应手的矛蓄力状态：" + name);
        }
    }

    static void update(LivingEntity entity, String name) {
        // 使用时间由客户端真实 tick 推进；挥手固定在出刺位置，供同帧截图核对握位。
        entity.walkAnimation.update(WALK_SPEED, 1, 1);
        if (name.contains("_stab")) {
            entity.swingingArm = hand(name);
            entity.swinging = true;
            entity.attackAnim = STAB_PROGRESS;
            entity.oAttackAnim = STAB_PROGRESS;
        }
    }

    private static InteractionHand hand(String name) {
        if (name.endsWith("_off")) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }
}
