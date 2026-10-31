package dev.kizuna.playeranimation.mobs;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 生物附属网络载荷只携带 PA v1 protobuf 字节。 */
public final class MobAnimationPackets {
    public static final String MOD_ID = "kizuna_players_animation_mobs";
    public static final CustomPacketPayload.Type<Payload> PLAY = type("play");
    public static final CustomPacketPayload.Type<Payload> STOP = type("stop");
    public static final int MAX_BYTES = 32 * 1024;

    private MobAnimationPackets() { }

    /** 一份未解码的生物动画包；载荷只是剩余全部字节，不加长度前缀。 */
    public record Payload(CustomPacketPayload.Type<Payload> type, byte[] bytes) implements CustomPacketPayload { }

    static void register() {
        // 两条通道都由服务端发往客户端，通用入口登记一次，两端共用。
        PayloadTypeRegistry.clientboundPlay().register(PLAY, codec(PLAY));
        PayloadTypeRegistry.clientboundPlay().register(STOP, codec(STOP));
    }

    public static <T extends MessageLite> T read(Payload payload, Parser<T> parser)
            throws InvalidProtocolBufferException {
        // 在解码前检查协议边界，避免把任意大小的自定义载荷交给解码器。
        if (payload.bytes().length > MAX_BYTES) {
            throw new IllegalArgumentException("生物动画包超过 32 KiB");
        }
        return parser.parseFrom(payload.bytes());
    }

    public static Payload write(CustomPacketPayload.Type<Payload> channel, MessageLite message) {
        if (message.getSerializedSize() > MAX_BYTES) {
            throw new IllegalArgumentException("生物动画包超过 32 KiB");
        }
        return new Payload(channel, message.toByteArray());
    }

    private static CustomPacketPayload.Type<Payload> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MOD_ID, path));
    }

    private static StreamCodec<FriendlyByteBuf, Payload> codec(CustomPacketPayload.Type<Payload> channel) {
        // 读出剩余全部字节，限长留给收包方检查并记日志，与旧版只丢弃坏包、不断开连接一致。
        return StreamCodec.of((buffer, payload) -> buffer.writeBytes(payload.bytes()), buffer -> {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new Payload(channel, bytes);
        });
    }
}
