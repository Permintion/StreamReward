package dev.streamrewards.mixin;

import dev.streamrewards.CameraEffects;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Добавляет крен (поворот вокруг оси взгляда) к камере для награды «Вверх ногами».
 * Счётчик вложенности нужен на случай, если одна версия setRotation вызывает другую:
 * крен должен применяться ровно один раз.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    @Final
    private Quaternionf rotation;

    @Unique
    private int streamrewards$depth = 0;

    @Inject(method = "setRotation", at = @At("HEAD"), require = 0)
    private void streamrewards$enter(CallbackInfo ci) {
        streamrewards$depth++;
    }

    @Inject(method = "setRotation", at = @At("TAIL"), require = 0)
    private void streamrewards$exit(CallbackInfo ci) {
        if (--streamrewards$depth > 0) return;
        streamrewards$depth = 0;
        float roll = CameraEffects.currentRoll();
        if (roll != 0.0F) {
            this.rotation.rotateZ((float) Math.toRadians(roll));
        }
    }
}
