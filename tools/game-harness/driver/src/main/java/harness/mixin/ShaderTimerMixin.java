package harness.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Identical animation phase on both backends; actual timing and frame counters remain live. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap=false)
public class ShaderTimerMixin {
 @Inject(method="getFrameTimeCounter", at=@At("HEAD"), cancellable=true, remap=false)
 private void animationTime(CallbackInfoReturnable<Float> cir) {
  if(Boolean.getBoolean("harness.enabled") && System.getProperty("harness.animationTime")!=null)
   cir.setReturnValue(Float.parseFloat(System.getProperty("harness.animationTime")));
 }
}
