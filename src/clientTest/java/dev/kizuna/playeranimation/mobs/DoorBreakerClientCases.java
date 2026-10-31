package dev.kizuna.playeranimation.mobs;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 在真实 PA renderer 中固定主副手命中帧，并覆盖左撇子运行时镜像。 */
final class DoorBreakerClientCases {
    private record Timing(float hitSeconds, float durationSeconds) { }

    private static final Map<String, Timing> TIMINGS = Map.of(
            "knock", new Timing(1.7f, 3), "cleave", new Timing(3.2f, 6), "ram", new Timing(2.4f, 5));
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    static List<String> names() {
        // 玩家两种惯用手和卫道士，各自覆盖三式的主副手版。
        var names = new ArrayList<String>();
        for (String actor : List.of("player", "left_player", "vindicator")) {
            for (String move : List.of("knock", "cleave", "ram")) {
                for (String suffix : List.of("", "_off")) {
                    names.add(actor + "_door_breaker_" + move + suffix);
                }
            }
        }
        return List.copyOf(names);
    }

    static boolean matches(String name) {
        // 显式动画用例不进入原版自动动作夹具。
        return name.contains("_door_breaker_");
    }

    static String animation(String name) {
        // 文件名同时标记实体和片段，方便逐图追溯。
        return name.substring(name.indexOf("door_breaker_"));
    }

    static float progress(String name) {
        // 归一化游标固定在动画 hit 事件；这只用于画面验收，不驱动伤害。
        String move = animation(name).substring("door_breaker_".length()).replace("_off", "");
        Timing timing = TIMINGS.get(move);
        return timing.hitSeconds() / timing.durationSeconds();
    }

    static LivingEntity create(Minecraft client, String name) {
        // 两种玩家只改变原版惯用手读取，其余关节、持物和绘制沿用正式实现。
        if (name.startsWith("vindicator_")) {
            return EntityType.VINDICATOR.create(client.level);
        }
        HumanoidArm mainArm = HumanoidArm.RIGHT;
        if (name.startsWith("left_player_")) {
            mainArm = HumanoidArm.LEFT;
        }
        HumanoidArm selectedArm = mainArm;
        return new RemotePlayer(client.level, new GameProfile(PLAYER_ID, "PA_door_test")) {
            @Override
            public HumanoidArm getMainArm() {
                // 让 PA 的运行时镜像读取真实实体的惯用手契约。
                return selectedArm;
            }
        };
    }

    static void equip(LivingEntity actor, String name) {
        // 单独装备出招手，避免闲置盾牌或盔甲遮住叠握与关节。
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            actor.setItemSlot(slot, ItemStack.EMPTY);
        }
        EquipmentSlot slot = EquipmentSlot.MAINHAND;
        if (name.endsWith("_off")) {
            slot = EquipmentSlot.OFFHAND;
        }
        actor.setItemSlot(slot, new ItemStack(Items.IRON_AXE));
    }

    private DoorBreakerClientCases() {
        // 仅提供截图用例数据，不保存动画状态。
    }
}
