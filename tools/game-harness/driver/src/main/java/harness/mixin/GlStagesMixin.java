package harness.mixin;
import harness.GlProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap=false)
public class GlStagesMixin {
 @Inject(method="beginTranslucents",at=@At("HEAD"),remap=false)
 private void opaque(CallbackInfo ci){GlProbe.stage(this,"after-gbuffers-opaque");}
 @Inject(method="beginTranslucents",at=@At("TAIL"),remap=false)
 private void deferred(CallbackInfo ci){GlProbe.stage(this,"after-deferred");}
 @Inject(method="finalizeLevelRendering",at=@At("TAIL"),remap=false)
 private void composite(CallbackInfo ci){GlProbe.stage(this,"after-composite");}
}
