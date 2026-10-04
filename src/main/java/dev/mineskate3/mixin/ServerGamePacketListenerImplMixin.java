package dev.mineskate3.mixin;

import dev.mineskate3.server.SkateServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Big airs and long grinds are not flying: never kick a skater for them. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private int aboveGroundTickCount;

    @Inject(method = "tick", at = @At("HEAD"))
    private void mineskate3$skatersAreNotFlying(CallbackInfo ci) {
        if (SkateServer.isSkating(this.player)) {
            this.aboveGroundTickCount = 0;
        }
    }
}
