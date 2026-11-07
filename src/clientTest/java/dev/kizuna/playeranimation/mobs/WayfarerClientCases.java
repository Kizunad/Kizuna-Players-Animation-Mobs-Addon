package dev.kizuna.playeranimation.mobs;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 行矢十式的实际关节与持物截图，覆盖主副手、惯用手及掠夺者。 */
final class WayfarerClientCases {
    private record Timing(float sampleSeconds, float durationSeconds) { }
    private static final Map<String, Timing> TIMINGS = Map.of(
            "full_draw", new Timing(3.2f, 6), "dry_draw", new Timing(3.2f, 32),
            "pearls", new Timing(2.0f, 5), "bow_strike", new Timing(1.35f, 5),
            "repeater", new Timing(1.58f, 5), "gale", new Timing(2.18f, 6),
            "kneeling", new Timing(2.9f, 7), "parthian", new Timing(1.95f, 5),
            "arrow_rain", new Timing(2.7f, 7), "convergence", new Timing(4.65f, 9));
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    static List<String> names() {
        // 固定排序让截图名称和执行顺序在不同 JVM 上保持一致。
        var names = new ArrayList<String>();
        for (String actor : List.of("player", "left_player", "pillager")) {
            for (String move : TIMINGS.keySet().stream().sorted().toList()) {
                for (String suffix : List.of("", "_off")) {
                    names.add(actor + "_wayfarer_" + move + suffix);
                }
            }
            names.add(actor + "_rocket_wayfarer_repeater");
            names.add(actor + "_rocket_wayfarer_repeater_off");
        }
        return List.copyOf(names);
    }

    static boolean matches(String name) {
        return name.contains("_wayfarer_");
    }

    static String animation(String name) {
        return name.substring(name.indexOf("wayfarer_"));
    }

    static float progress(String name) {
        // 固定满弓、上弦或打击时刻，截图不驱动实际射弹。
        String move = animation(name).substring("wayfarer_".length()).replace("_off", "");
        Timing timing = TIMINGS.get(move);
        return timing.sampleSeconds() / timing.durationSeconds();
    }

    static LivingEntity create(Minecraft client, String name) {
        // 生物通过正式几何注册和 renderer 绘制，玩家读取真实惯用手契约。
        if (name.startsWith("pillager_")) {
            return EntityTypes.PILLAGER.create(client.level, MobAnimationClientTest.FIXTURE);
        }
        HumanoidArm arm = HumanoidArm.RIGHT;
        if (name.startsWith("left_player_")) {
            arm = HumanoidArm.LEFT;
        }
        HumanoidArm mainArm = arm;
        return new RemotePlayer(client.level, new GameProfile(PLAYER_ID, "PA_wayfarer")) {
            @Override
            public HumanoidArm getMainArm() {
                return mainArm;
            }
        };
    }

    static void equip(LivingEntity actor, String name) {
        // 空弓和弩都保持真实未使用、未上弦状态，模型由动画物品轨道接管。
        actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        actor.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        EquipmentSlot slot = EquipmentSlot.MAINHAND;
        if (name.endsWith("_off")) {
            slot = EquipmentSlot.OFFHAND;
        }
        if (name.contains("repeater") || name.contains("gale") || name.contains("kneeling")) {
            actor.setItemSlot(slot, Items.CROSSBOW.getDefaultInstance());
        } else {
            actor.setItemSlot(slot, Items.BOW.getDefaultInstance());
        }
        if (name.contains("_rocket_")) {
            EquipmentSlot ammunition = EquipmentSlot.OFFHAND;
            if (slot == EquipmentSlot.OFFHAND) {
                ammunition = EquipmentSlot.MAINHAND;
            }
            actor.setItemSlot(ammunition, Items.FIREWORK_ROCKET.getDefaultInstance());
        }
    }

    private WayfarerClientCases() { }
}
