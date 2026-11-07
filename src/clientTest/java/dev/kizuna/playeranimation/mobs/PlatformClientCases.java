package dev.kizuna.playeranimation.mobs;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Items;

/** 新坐骑、长柄握位和五脏诀在双体型上的实际画面。 */
final class PlatformClientCases {
    private static final int MOUNT_ID = 700003;
    private static final int DEFAULT_SKIN_COUNT = 18;
    private static final float SAMPLE_PROGRESS = 0.35f;
    private static final float REFINE_PROGRESS = 0.10f;
    private static final List<String> EVOKER_ACTIONS = List.of("water_frost", "metal_edge", "earth_quake",
            "fire_burn", "wood_thunder", "fire_heart", "metal_arms", "earth_legs", "water_return",
            "wood_sinew", "refine_lung", "refine_spleen", "refine_liver", "refine_heart", "refine_kidney",
            "grip", "grip_off");
    private static final List<String> MOUNTS = List.of("happy_ghast", "nautilus", "zombie_nautilus", "camel_husk");

    private PlatformClientCases() { }

    static List<String> names() {
        var names = new ArrayList<String>();
        for (String model : List.of("classic", "slim")) {
            for (String action : EVOKER_ACTIONS) {
                names.add("platform_" + model + "_evoker_" + action);
            }
            names.add("platform_" + model + "_mace");
            names.add("platform_" + model + "_bundle");
            names.add("platform_" + model + "_bow");
            names.add("platform_" + model + "_crossbow");
        }
        for (String mount : MOUNTS) {
            names.add("platform_classic_ride_" + mount);
        }
        return List.copyOf(names);
    }

    static boolean matches(String name) {
        return name.startsWith("platform_");
    }

    static LivingEntity create(Minecraft client, String name) {
        PlayerModelType model = PlayerModelType.WIDE;
        if (name.startsWith("platform_slim_")) {
            model = PlayerModelType.SLIM;
        }
        PlayerSkin skin = skin(model);
        var profile = new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000014"), "PA_platform");
        return new RemotePlayer(client.level, profile) {
            @Override
            public PlayerSkin getSkin() {
                return skin;
            }
        };
    }

    private static PlayerSkin skin(PlayerModelType model) {
        // 选用匹配体型的正式默认皮肤，不把宽臂贴图强行贴到细臂上。
        for (int index = 0; index < DEFAULT_SKIN_COUNT; index++) {
            PlayerSkin skin = DefaultPlayerSkin.get(new UUID(0, index));
            if (skin.model() == model) {
                return skin;
            }
        }
        throw new IllegalStateException("没有找到默认皮肤：" + model);
    }

    static void equip(LivingEntity actor, String name) {
        if (name.endsWith("_mace")) {
            actor.setItemSlot(EquipmentSlot.MAINHAND, Items.MACE.getDefaultInstance());
        } else if (name.endsWith("_bundle")) {
            actor.setItemSlot(EquipmentSlot.MAINHAND, Items.BUNDLE.getDefaultInstance());
        } else if (name.endsWith("_crossbow")) {
            actor.setItemSlot(EquipmentSlot.MAINHAND, Items.CROSSBOW.getDefaultInstance());
        } else if (name.endsWith("_bow")) {
            actor.setItemSlot(EquipmentSlot.MAINHAND, Items.BOW.getDefaultInstance());
        }
    }

    static void mount(Minecraft client, LivingEntity actor, String name) {
        int marker = name.indexOf("_ride_");
        if (marker < 0) {
            return;
        }
        String type = name.substring(marker + "_ride_".length());
        var entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(type)).orElseThrow();
        Mob mount = (Mob) entityType.create(client.level, MobAnimationClientTest.FIXTURE);
        mount.setNoAi(true /* aiDisabled */);
        mount.setNoGravity(true /* noGravity */);
        mount.snapTo(actor.getX(), actor.getY(), actor.getZ(), 0, 0);
        mount.setId(MOUNT_ID);
        if (type.equals("happy_ghast")) {
            mount.setItemSlot(EquipmentSlot.BODY, Items.HARNESS.blue().getDefaultInstance());
        } else {
            mount.setItemSlot(EquipmentSlot.SADDLE, Items.SADDLE.getDefaultInstance());
        }
        client.level.addEntity(mount);
        if (!actor.startRiding(mount, true /* force */, false /* sendEvent */)) {
            throw new IllegalStateException("无法骑乘：" + type);
        }
        mount.positionRider(actor);
    }

    static String animation(String name) {
        int evoker = name.indexOf("evoker_");
        if (evoker >= 0) {
            return name.substring(evoker);
        }
        if (name.contains("_ride_")) {
            return "ride";
        }
        if (name.endsWith("_bundle")) {
            return "use_item";
        }
        if (name.endsWith("_crossbow")) {
            return "crossbow_charge";
        }
        if (name.endsWith("_bow")) {
            return "bow_draw";
        }
        return "attack_main";
    }

    static float progress(String name) {
        if (name.contains("_refine_")) {
            return REFINE_PROGRESS;
        }
        return SAMPLE_PROGRESS;
    }
}
