package dev.kizuna.playeranimation.mobs;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ByteString;
import dev.kizuna.playeranimation.api.PlayerAnimationHost;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationResponse;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.RegisterGeometry;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 客户端只负责资源注册和把服务端请求交给 PA。 */
public final class MobAnimationClient implements ClientModInitializer {
    public static final String ID = MobAnimationPackets.MOD_ID;
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    private static PlayerAnimationHost host;

    @Override
    public void onInitializeClient() {
        host = findHost();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new MobGeometryAssets());
        ClientPlayNetworking.registerGlobalReceiver(MobAnimationPackets.PLAY,
                (client, handler, buffer, sender) -> receive(client, handler, buffer));
        ClientPlayNetworking.registerGlobalReceiver(MobAnimationPackets.STOP,
                (client, handler, buffer, sender) -> receive(client, handler, buffer));
        LOG.info("生物动画附属已连接 PA 字节入口");
    }

    static void registerGeometry(String geometryId, byte[] geometryJson, List<String> entityTypes, String overlayId) {
        RegisterGeometry geometry = RegisterGeometry.newBuilder()
                .setGeometryId(geometryId)
                .setGeometryJson(ByteString.copyFrom(geometryJson))
                .addAllEntityTypes(entityTypes)
                .setOverlayGeometryId(overlayId)
                .build();
        EntityAnimationRequest request = EntityAnimationRequest.newBuilder()
                .setRequestId("register:" + geometryId)
                .setRegisterGeometry(geometry)
                .build();
        exchange(request);
    }

    private static void receive(MinecraftClient client,
                                ClientPlayNetworkHandler handler,
                                PacketByteBuf buffer) {
        try {
            EntityAnimationRequest request = MobAnimationPackets.read(buffer, EntityAnimationRequest.parser());
            client.execute(() -> {
                if (client.getNetworkHandler() == handler) {
                    exchange(request);
                }
            });
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            LOG.error("生物动画包无法解码: {}", exception.getMessage());
        }
    }

    private static void exchange(EntityAnimationRequest request) {
        EntityAnimationResponse response;
        try {
            response = EntityAnimationResponse.parseFrom(host.exchange(request.toByteArray()));
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("PA 返回了无法解码的实体动画回执", exception);
        }
        if (response.getCode() != EntityAnimationResponse.Code.OK) {
            LOG.error("PA 实体动画请求 {} 失败: {} {}", request.getRequestId(), response.getCode(),
                    response.getDetail());
            if (request.hasRegisterGeometry()) {
                throw new IllegalStateException(response.getDetail());
            }
        }
    }

    private static PlayerAnimationHost findHost() {
        List<PlayerAnimationHost> hosts = FabricLoader.getInstance()
                .getEntrypointContainers(PlayerAnimationHost.ENTRYPOINT, PlayerAnimationHost.class)
                .stream()
                .map(container -> container.getEntrypoint())
                .toList();
        if (hosts.size() != 1) {
            throw new IllegalStateException("必须发现唯一的 PA 字节入口，实际数量: " + hosts.size());
        }
        return hosts.get(0);
    }

    static PlayerAnimationHost host() {
        return host;
    }
}
