package dev.streamrewards.mixin;

import dev.streamrewards.ControlsEffects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Блокирует поворот камеры мышью, пока действует награда «Заблокировать мышь».
 * Касается только локального игрока.
 */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
    private void streamrewards$blockLook(CallbackInfo ci) {
        if (ControlsEffects.mouseLookBlocked() && (Object) this instanceof LocalPlayer) {
            ci.cancel();
        }
    }
}
