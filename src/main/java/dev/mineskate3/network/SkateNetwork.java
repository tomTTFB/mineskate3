package dev.mineskate3.network;

import dev.mineskate3.MineSkate3;
import dev.mineskate3.server.SkateServer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Skating is simulated on the skater's own client. The server only learns who
 * is skating (to waive fall damage and the flying kick) and relays each
 * skater's pose to the players who can see them.
 */
public final class SkateNetwork {
    /** Six model parts and the board, each a 3x4 matrix relative to the player's feet. */
    public static final int POSE_FLOATS = 7 * 12;

    private SkateNetwork() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, path);
    }

    private static float[] readPose(ByteBuf buf) {
        float[] data = new float[POSE_FLOATS];
        for (int i = 0; i < POSE_FLOATS; i++) {
            data[i] = buf.readFloat();
        }
        return data;
    }

    private static void writePose(ByteBuf buf, float[] data) {
        for (int i = 0; i < POSE_FLOATS; i++) {
            buf.writeFloat(i < data.length ? data[i] : 0f);
        }
    }

    /** Client to server: this player started or stopped skating. */
    public record State(boolean skating) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(id("state"));
        public static final StreamCodec<ByteBuf, State> CODEC = ByteBufCodecs.BOOL.map(State::new, State::skating);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    /** Client to server: this player's current pose. */
    public record Pose(float[] data) implements CustomPacketPayload {
        public static final Type<Pose> TYPE = new Type<>(id("pose"));
        public static final StreamCodec<ByteBuf, Pose> CODEC = StreamCodec.of(
                (buf, pose) -> writePose(buf, pose.data()),
                buf -> new Pose(readPose(buf)));

        @Override
        public Type<Pose> type() {
            return TYPE;
        }
    }

    /** Server to client: another player started or stopped skating. */
    public record RemoteState(int entityId, boolean skating) implements CustomPacketPayload {
        public static final Type<RemoteState> TYPE = new Type<>(id("remote_state"));
        public static final StreamCodec<ByteBuf, RemoteState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RemoteState::entityId,
                ByteBufCodecs.BOOL, RemoteState::skating,
                RemoteState::new);

        @Override
        public Type<RemoteState> type() {
            return TYPE;
        }
    }

    /** Server to client: another skater's pose. */
    public record RemotePose(int entityId, float[] data) implements CustomPacketPayload {
        public static final Type<RemotePose> TYPE = new Type<>(id("remote_pose"));
        public static final StreamCodec<ByteBuf, RemotePose> CODEC = StreamCodec.of(
                (buf, pose) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, pose.entityId());
                    writePose(buf, pose.data());
                },
                buf -> new RemotePose(ByteBufCodecs.VAR_INT.decode(buf), readPose(buf)));

        @Override
        public Type<RemotePose> type() {
            return TYPE;
        }
    }

    /** Where the client side of the relay lands; set by the client entry point. */
    public interface ClientHandler {
        void remoteState(RemoteState state);

        void remotePose(RemotePose pose);
    }

    private static volatile ClientHandler clientHandler;

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = handler;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(State.TYPE, State.CODEC,
                (payload, context) -> context.enqueueWork(() -> SkateServer.onState(context.player(), payload.skating())));
        registrar.playToServer(Pose.TYPE, Pose.CODEC,
                (payload, context) -> context.enqueueWork(() -> SkateServer.onPose(context.player(), payload.data())));
        registrar.playToClient(RemoteState.TYPE, RemoteState.CODEC, (payload, context) -> context.enqueueWork(() -> {
            ClientHandler handler = clientHandler;
            if (handler != null) {
                handler.remoteState(payload);
            }
        }));
        registrar.playToClient(RemotePose.TYPE, RemotePose.CODEC, (payload, context) -> context.enqueueWork(() -> {
            ClientHandler handler = clientHandler;
            if (handler != null) {
                handler.remotePose(payload);
            }
        }));
    }
}
