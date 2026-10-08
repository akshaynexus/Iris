package harness.mixin;
import harness.Driver;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class FrameMixin {
 @Inject(method="renderFrame", at=@At("HEAD")) private void lockPose(boolean tick, CallbackInfo ci) { Driver.lockPose(); }
 @Inject(method="renderFrame", at=@At("RETURN")) private void frame(boolean tick, CallbackInfo ci) { Driver.frame(); }
}
