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
import java.util.stream.Stream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;
import net.minecraft.util.math.Vec3d;

/** 在实际世界与原版 renderer 中验收字节入口，并保存同帧近景。 */
public final class MobAnimationClientTest implements ClientModInitializer {
    private static final int WAIT_WORLD_TICKS = 60;
    private static final int SETTLE_TICKS = 12;
    private static final int TARGET_ID = 700001;
    private static final int CAMERA_ID = 700002;
    private static final float PROGRESS = 0.45f;
    private static final float CAMERA_YAW = 150;
    private static final float CAMERA_PITCH = 4;
    private static final double CAMERA_X = 2;
    private static final double CAMERA_Z = 3.5;
    private static final double CAMERA_EYE_Y = 1.6;
    private static final int FOV = 48;
    private static final int VILLAGER_LEVEL = 4;
    private static final Path SCREENS = Path.of("../mob-screens");
    private static final List<String> CASES = Stream.concat(List.of(
            "zombie", "skeleton", "piglin", "villager", "vindicator",
            "wither_skeleton", "player", "drowned", "stray", "zombie_villager", "zombie_armor", "villager_idle",
            "vindicator_walk", "vindicator_run", "vindicator_axe_ready", "vindicator_axe_swing",
            "witch_drink", "pillager_charge", "pillager_hold", "evoker_idle", "illusioner_idle",
            "wandering_trader_trade", "vindicator_banner").stream(), DoorBreakerClientCases.names().stream()).toList();
    private static final float WALK_SPEED = 0.3f;
    private static final float RUN_SPEED = 0.8f;
    private static final float SWING_PROGRESS = 0.45f;
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
    private MobTechniqueClientScene techniqueScene;
    private MobRecordingHost recording;
    private CompletableFuture<Void> cleaned;

    private record Placement(double x, double y, double z, float yaw, float pitch) { }

    @Override
    public void onInitializeClient() {
        // 在世界 tick 完成后写入截图状态，正式 END_CLIENT_TICK 观察器随后读取。
        ClientTickEvents.END_WORLD_TICK.register(world -> {
            if (target != null && caseIndex < CASES.size()) {
                automaticState(MinecraftClient.getInstance());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END.register(context -> screenshot());
    }

    private void tick(MinecraftClient client) {
        // 等待实际世界，再逐个驱动状态并交给同帧截图回调验收。
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
            if (!cleaned.isDone()) {
                return;
            }
            cleaned.join();
            if (caseIndex == CASES.size()) {
                tickTechnique(client);
                return;
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
        // 装饰公开字节入口，验证截图中自动层和真实网络动作的确进入 PA。
        recording = MobRecordingHost.install();
        host = recording;
        cleaned = client.getServer().submit(() -> MobTechniqueClientScene.cleanWorld(client.getServer()));
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
        // 错误契约先验证，自动状态用例由正式 tick 驱动，不手工启动目标片段。
        String name = CASES.get(caseIndex);
        target = createTarget(client, name);
        configureTarget(name);
        target.setId(TARGET_ID);
        client.world.addEntity(TARGET_ID, target);
        pinEntity(target, targetPlacement);
        checkTargetContract();
        if (DoorBreakerClientCases.matches(name)) {
            exchange(play(DoorBreakerClientCases.animation(name), DoorBreakerClientCases.progress(name)),
                    EntityAnimationResponse.Code.OK);
        } else if (!automaticCase(name)) {
            exchange(play("dodge_forward", PROGRESS), EntityAnimationResponse.Code.OK);
        }
        settleTicks = 0;
    }

    private static LivingEntity createTarget(MinecraftClient client, String name) {
        // 同一实体分别覆盖移动、原版动作和装备状态，不手工播放自动层。
        if (DoorBreakerClientCases.matches(name)) {
            return DoorBreakerClientCases.create(client, name);
        }
        if (name.equals("player")) {
            return new OtherClientPlayerEntity(client.world,
                    new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "PA_reference"));
        }
        String type = switch (name) {
            case "zombie_armor" -> "zombie";
            case "villager_idle" -> "villager";
            case "vindicator_walk", "vindicator_run", "vindicator_axe_ready", "vindicator_axe_swing",
                    "vindicator_banner" -> "vindicator";
            case "witch_drink" -> "witch";
            case "pillager_charge", "pillager_hold" -> "pillager";
            case "evoker_idle" -> "evoker";
            case "illusioner_idle" -> "illusioner";
            case "wandering_trader_trade" -> "wandering_trader";
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
        configureEquipment(name);
    }

    private void configureEquipment(String name) {
        // 专属招式单持斧头，其余用例分别检查盔甲、盾牌和头饰遮挡。
        if (name.equals("zombie_armor")) {
            target.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            target.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            target.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            target.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        }
        if (name.startsWith("vindicator_")) {
            target.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            target.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            target.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            target.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            target.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            target.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        }
        if (name.equals("vindicator_banner")) {
            target.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.WHITE_BANNER));
        }
        if (DoorBreakerClientCases.matches(name)) {
            DoorBreakerClientCases.equip(target, name);
        }
    }

    private static boolean automaticCase(String name) {
        // 历史几何用例保持显式 dodge，其余只由客户端实体状态触发 PA。
        if (DoorBreakerClientCases.matches(name)) {
            return false;
        }
        return name.startsWith("vindicator_") || name.equals("villager_idle") || name.endsWith("_drink")
                || name.startsWith("pillager_") || name.endsWith("_idle") || name.endsWith("_trade");
    }

    private void automaticState(MinecraftClient client) {
        // 固定镜头下推进实际原版字段，确认自动选择器确实观察这些字段。
        String name = CASES.get(caseIndex);
        if (name.equals("vindicator_walk")) {
            target.limbAnimator.updateLimbs(WALK_SPEED, 1);
        } else if (name.equals("vindicator_run")) {
            target.limbAnimator.updateLimbs(RUN_SPEED, 1);
        } else if (name.equals("vindicator_axe_ready")) {
            ((MobEntity) target).setAttacking(true /* attacking */);
        } else if (name.equals("vindicator_axe_swing")) {
            target.handSwinging = true;
            target.handSwingProgress = SWING_PROGRESS;
            target.lastHandSwingProgress = SWING_PROGRESS;
        } else if (target instanceof WitchEntity witch) {
            witch.setDrinking(true /* drinking */);
            witch.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.POTION));
        } else if (target instanceof PillagerEntity pillager) {
            pillager.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            pillager.setCharging(name.equals("pillager_charge"));
            pillager.setAttacking(true /* attacking */);
        } else if (target instanceof MerchantEntity merchant && name.endsWith("_trade")) {
            merchant.setCustomer(client.player);
        }
    }

    private void tickTechnique(MinecraftClient client) {
        // 真实整合服生成真炁卫道士，让 AI 经功法宿主下发 PA；镜头继续使用同一机位。
        if (techniqueScene == null) {
            techniqueScene = new MobTechniqueClientScene(client,
                    new Vec3d(targetPlacement.x(), targetPlacement.y(), targetPlacement.z()));
        }
        pinEntity(camera, cameraPlacement);
        capture = techniqueScene.ready(client, recording);
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
        // 只在世界完成绘制后取图，避免把加载遮罩或上一用例当作通过证据。
        if (!capture || finished) {
            return;
        }
        capture = false;
        MinecraftClient client = MinecraftClient.getInstance();
        try (var image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            if (caseIndex == CASES.size()) {
                image.writeTo(SCREENS.resolve("vindicator_technique.png"));
                finish(null);
                return;
            }
            verifyAutomatic();
            image.writeTo(SCREENS.resolve(CASES.get(caseIndex) + ".png"));
            client.world.removeEntity(TARGET_ID, Entity.RemovalReason.DISCARDED);
            target = null;
            caseIndex++;
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void verifyAutomatic() {
        // 走跑截图必须对应实际下发给 PA 的底层，不能只检查夹具设置过速度。
        String name = CASES.get(caseIndex);
        if (DoorBreakerClientCases.matches(name)) {
            var play = recording.explicit(TARGET_ID);
            checkState(play != null && play.getAnimationId().equals(DoorBreakerClientCases.animation(name)),
                    "击中截图未播放对应片段：" + name);
            checkState(play.hasNormalizedProgress()
                    && play.getNormalizedProgress() == DoorBreakerClientCases.progress(name),
                    "击中截图游标不符：" + name);
        }
        if (!automaticCase(name)) {
            return;
        }
        var request = recording.automatic(TARGET_ID);
        checkState(request != null, "未收到自动层请求：" + name);
        if (name.equals("vindicator_walk")) {
            checkState(request.getBaseAnimationId().equals("walk"), "走路未选择 walk");
        } else if (name.equals("vindicator_run")) {
            checkState(request.getBaseAnimationId().equals("run"), "奔跑未选择 run");
        } else if (name.equals("vindicator_axe_swing")) {
            checkState(request.getActionAnimationId().equals("attack_main"), "挥斧未选择 attack_main");
        }
    }

    private void finish(String failure) {
        // 结果写入独立报告，Gradle 会把客户端断言失败转换为任务失败。
        finished = true;
        try {
            Path report = Path.of(System.getProperty("kpa.mobs.clientTest.report"));
            Files.writeString(report, "success=" + (failure == null) + "\ncaptures=" + caseIndex + "\n"
                    + "technique=" + (techniqueScene != null && techniqueScene.observed())
                    + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        MinecraftClient.getInstance().scheduleStop();
    }

}
