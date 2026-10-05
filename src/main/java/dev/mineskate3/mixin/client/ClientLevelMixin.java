package dev.mineskate3.mixin.client;

import dev.mineskate3.client.SkateSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every client block change passes here: the skate collision rebuilds when one lands near the skater. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void mineskate3$blockChanged(BlockPos pos, BlockState before, BlockState after, int flags,
            CallbackInfo ci) {
        SkateSession.get().blockChanged((ClientLevel) (Object) this, pos, before, after);
    }
}
