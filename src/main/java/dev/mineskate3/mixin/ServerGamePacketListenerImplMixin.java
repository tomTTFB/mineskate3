package dev.mineskate3.mixin;

import dev.mineskate3.server.SkateGuard;
import dev.mineskate3.server.SkateServer;
import java.util.Set;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Big airs and long grinds are not flying: never kick a skater for them
 * (SkateGuard checks their airtime instead). Teleports reset its tracking.
 */
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

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void mineskate3$teleported(double x, double y, double z, float yRot, float xRot,
            Set<RelativeMovement> relative, CallbackInfo ci) {
        SkateGuard.teleported(this.player);
    }
}
