package harness.mixin;
import harness.Driver;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public class CameraMixin {
 @Inject(method="extract", at=@At("HEAD"))
 private void lockPose(DeltaTracker delta, boolean tick, CallbackInfo ci) { Driver.lockPose(); }
}
