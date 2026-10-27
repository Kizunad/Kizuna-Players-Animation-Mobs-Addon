package dev.kizuna.playeranimation.mobs;

import static com.google.common.base.Preconditions.checkState;

import com.google.protobuf.ByteString;
import com.mojang.authlib.GameProfile;
import dev.kizuna.playeranimation.api.PlayerAnimationHost;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Clear;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationResponse;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Play;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.RegisterGeometry;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Stop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

/** 在实际世界与原版 renderer 中验收字节入口，并保存同帧近景。 */
public final class MobAnimationClientTest implements ClientModInitializer {
    private static final int WAIT_WORLD_TICKS = 60;
    private static final int SETTLE_TICKS = 12;
    private static final int TARGET_ID = 700001;
    private static final int CAMERA_ID = 700002;
    private static final float PROGRESS = 0.45f;
    private static final float CAMERA_YAW = 150;
    private static final float CAMERA_PITCH = 8;
    private static final double CAMERA_X = 1.3;
    private static final double CAMERA_Z = 2.25;
    private static final double CAMERA_EYE_Y = 1.45;
    private static final int FOV = 48;
    private static final int VILLAGER_LEVEL = 4;
    private static final Path SCREENS = Path.of("../mob-screens");
    private static final List<String> CASES = List.of("zombie", "skeleton", "piglin", "villager", "vindicator",
            "wither_skeleton", "player", "drowned", "stray", "zombie_villager", "zombie_armor", "villager_vanilla");
    private int worldTicks;
    private int caseIndex;
    private int settleTicks;
    private boolean capture;
    private boolean finished;
    private LivingEntity target;
    private ArmorStandEntity camera;
    private Placement targetPlacement;
    private Placement cameraPlacement;
    private PlayerAnimationHost host;

    private record Placement(double x, double y, double z, float yaw, float pitch) { }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END.register(context -> screenshot());
    }

    private void tick(MinecraftClient client) {
        if (finished || client.world == null || client.player == null) {
            return;
        }
        try {
            if (++worldTicks < WAIT_WORLD_TICKS) {
                return;
            }
            if (host == null) {
                initialize(client);
                checkErrors();
            }
            if (target == null) {
                prepareCase(client);
            }
            pinEntity(target, targetPlacement);
            pinEntity(camera, cameraPlacement);
            if (++settleTicks >= SETTLE_TICKS) {
                capture = true;
            }
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void initialize(MinecraftClient client) throws Exception {
        host = MobAnimationClient.host();
        Files.createDirectories(SCREENS);
        double originX = Math.floor(client.player.getX()) + 0.5;
        double originY = client.player.getY();
        double originZ = Math.floor(client.player.getZ()) + 0.5;
        targetPlacement = new Placement(originX, originY, originZ, 0, 0);
        client.options.hudHidden = true;
        client.options.setPerspective(Perspective.FIRST_PERSON);
        client.options.getFov().setValue(FOV);
        camera = new ArmorStandEntity(EntityType.ARMOR_STAND, client.world);
        camera.setInvisible(true /* invisible */);
        camera.setNoGravity(true /* noGravity */);
        camera.setId(CAMERA_ID);
        client.world.addEntity(CAMERA_ID, camera);
        client.setCameraEntity(camera);
        cameraPlacement = new Placement(originX + CAMERA_X,
                originY + CAMERA_EYE_Y - camera.getStandingEyeHeight(), originZ + CAMERA_Z, CAMERA_YAW, CAMERA_PITCH);
    }

    private void prepareCase(MinecraftClient client) throws Exception {
        String name = CASES.get(caseIndex);
        target = createTarget(client, name);
        configureTarget(name);
        target.setId(TARGET_ID);
        client.world.addEntity(TARGET_ID, target);
        pinEntity(target, targetPlacement);
        checkTargetContract();
        if (!name.endsWith("vanilla")) {
            exchange(play("dodge_forward", PROGRESS), EntityAnimationResponse.Code.OK);
        }
        settleTicks = 0;
    }

    private static LivingEntity createTarget(MinecraftClient client, String name) {
        if (name.equals("player")) {
            return new OtherClientPlayerEntity(client.world,
                    new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "PA_reference"));
        }
        String type = switch (name) {
            case "zombie_armor" -> "zombie";
            case "villager_vanilla" -> "villager";
            default -> name;
        };
        return (LivingEntity) EntityType.get(type).orElseThrow().create(client.world);
    }

    private void configureTarget(String name) {
        // 阻止本地 AI 和重力改变截图的固定机位与姿态。
        target.setNoGravity(true /* noGravity */);
        if (target instanceof MobEntity mob) {
            mob.setAiDisabled(true /* aiDisabled */);
        }
        VillagerData data = new VillagerData(VillagerType.SNOW, VillagerProfession.LIBRARIAN, VILLAGER_LEVEL);
        if (target instanceof VillagerEntity villager) {
            villager.setVillagerData(data);
        } else if (target instanceof ZombieVillagerEntity villager) {
            villager.setVillagerData(data);
        }
        if (name.equals("zombie_armor")) {
            target.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            target.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            target.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            target.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        }
    }

    private void checkTargetContract() throws Exception {
        exchange(play("missing_animation", PROGRESS), EntityAnimationResponse.Code.ANIMATION_NOT_FOUND);
        exchange(play("dodge_forward", Float.NaN), EntityAnimationResponse.Code.INVALID_REQUEST);
        exchange(play("dodge_forward", PROGRESS), EntityAnimationResponse.Code.OK);
        Stop stop = Stop.newBuilder()
                .setEntityId(TARGET_ID)
                .setAll(true /* all */)
                .build();
        exchange(EntityAnimationRequest.newBuilder().setStop(stop).build(), EntityAnimationResponse.Code.OK);
        Clear clear = Clear.newBuilder().setEntityId(TARGET_ID).build();
        exchange(EntityAnimationRequest.newBuilder().setClear(clear).build(), EntityAnimationResponse.Code.OK);
    }

    private void checkErrors() throws Exception {
        exchange(EntityAnimationRequest.getDefaultInstance(), EntityAnimationResponse.Code.INVALID_REQUEST);
        EntityAnimationResponse malformed = EntityAnimationResponse.parseFrom(host.exchange(new byte[]{(byte) 0x80}));
        checkState(malformed.getCode() == EntityAnimationResponse.Code.INVALID_REQUEST, "非法 protobuf 必须返回错误码");
        RegisterGeometry invalid = RegisterGeometry.newBuilder()
                .setGeometryId("invalid")
                .setGeometryJson(ByteString.copyFromUtf8("{}"))
                .build();
        exchange(EntityAnimationRequest.newBuilder().setRegisterGeometry(invalid).build(),
                EntityAnimationResponse.Code.GEOMETRY_INVALID);
        exchange(play("dodge_forward", PROGRESS), EntityAnimationResponse.Code.ENTITY_NOT_LOADED);
    }

    private static EntityAnimationRequest play(String animation, float progress) {
        Play operation = Play.newBuilder()
                .setEntityId(TARGET_ID)
                .setAnimationId(animation)
                .setNormalizedProgress(progress)
                .build();
        return EntityAnimationRequest.newBuilder()
                .setRequestId("client-test")
                .setPlay(operation)
                .build();
    }

    private void exchange(EntityAnimationRequest request, EntityAnimationResponse.Code expected) throws Exception {
        EntityAnimationResponse response = EntityAnimationResponse.parseFrom(host.exchange(request.toByteArray()));
        checkState(response.getCode() == expected, expected + " != " + response);
    }

    private static void pinEntity(LivingEntity entity, Placement placement) {
        double x = placement.x();
        double y = placement.y();
        double z = placement.z();
        float yaw = placement.yaw();
        float pitch = placement.pitch();
        entity.refreshPositionAndAngles(x, y, z, yaw, pitch);
        entity.prevX = x;
        entity.prevY = y;
        entity.prevZ = z;
        entity.bodyYaw = yaw;
        entity.prevBodyYaw = yaw;
        entity.headYaw = yaw;
        entity.prevHeadYaw = yaw;
        entity.prevYaw = yaw;
        entity.prevPitch = pitch;
    }

    private void screenshot() {
        if (!capture || finished) {
            return;
        }
        capture = false;
        MinecraftClient client = MinecraftClient.getInstance();
        try (var image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            image.writeTo(SCREENS.resolve(CASES.get(caseIndex) + ".png"));
            client.world.removeEntity(TARGET_ID, Entity.RemovalReason.DISCARDED);
            target = null;
            if (++caseIndex == CASES.size()) {
                finish(null);
            }
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void finish(String failure) {
        finished = true;
        try {
            Path report = Path.of(System.getProperty("kpa.mobs.clientTest.report"));
            Files.writeString(report, "success=" + (failure == null) + "\ncaptures=" + caseIndex + "\n"
                    + "animation=dodge_forward\nprogress=" + PROGRESS + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        MinecraftClient.getInstance().scheduleStop();
    }

}
