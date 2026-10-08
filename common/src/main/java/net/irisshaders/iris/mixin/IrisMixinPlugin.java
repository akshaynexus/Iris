package net.irisshaders.iris.mixin;

import com.google.common.base.Splitter;
import com.google.common.io.Files;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class IrisMixinPlugin implements IMixinConfigPlugin {
    private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);

    public static boolean usingVulkan;
    /** mcopt's Metal backend draws the game: like Vulkan, there is no GL context, so the GL-only mixins stay off. */
    public static boolean usingMetal = MetalSupport.metalActive();

    static {
        BufferedReader reader = null;
        boolean check = true;
        try {
            reader = Files.newReader(IrisPlatformHelpers.getInstance().getGameDir().resolve("options.txt").toFile(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            usingVulkan = false;
            check = false;
        }

        if (check) {
            Map<String, String> options = new HashMap<>();

            try {
                reader.lines().forEach(line -> {
                    try {
                        Iterator<String> iterator = OPTION_SPLITTER.split(line).iterator();
                        options.put((String) iterator.next(), (String) iterator.next());
                    } catch (Exception var3) {
                    }
                });
            } catch (Throwable var6) {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                    }
                }

                throw var6;
            }

            if (options.get("preferredGraphicsBackend") != null) {
                usingVulkan = options.get("preferredGraphicsBackend").toLowerCase(Locale.ROOT).contains("vulkan");
            } else {
                usingVulkan = false;
            }
        }
    }
	@Override
	public void onLoad(String mixinPackage) {

	}

	@Override
	public String getRefMapperConfig() {
		return "iris.refmap.json";
	}

	/**
	 * Mixins that target Mojang's GL backend or call GL, by name relative to net.irisshaders.iris.mixin. On Metal every
	 * other mixin applies: the Metal pipeline (net.irisshaders.iris.metal) relies on Iris's world-render hooks.
	 */
	private static final Set<String> GL_ONLY = Set.of(
		"MixinGpuTexture", "MixinBooleanState", "MixinGlCommandEncoder", "MixinGlProgram", "MixinGlRenderPipeline",
		"MixinGlStateManager", "MixinGlStateManager_BlendOverride", "MixinGlStateManager_DepthColorOverride",
		"MixinGlStateManager_FramebufferBinding", "MixinShaderManager_Overrides", "MixinRenderPass", "MixinCompiledShaderProgram",
		"MixinTextureUtil", "MixinUniform", "MixinWindow", "UndoReverseZOne", "UndoReverseZTwo", "UndoReverseZThree",
		"UndoReverseZFour", "UndoReverseZFive", "statelisteners.BooleanStateAccessor", "statelisteners.MixinGlStateManager",
		"texture.MixinGlStateManager", "vertices.MixinVertexFormat", "fantastic.FeatureRenderDispatcherAccessor",
		"fantastic.MixinFireworkSparkParticle", "fantastic.MixinStationaryItemParticle", "fantastic.MixinTerrainParticle",
		"fantastic.MixinParticleFeatureRenderer", "fantastic.MixinParticlesRenderState", "fantastic.MixinLevelRenderer");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("MetalOnly")) return usingMetal;
        if (mixinClassName.contains("VKOnly")) return usingVulkan && !usingMetal; // Metal runs the full Iris, keybinds included
        if (usingMetal) {
            String prefix = "net.irisshaders.iris.mixin.";
            String name = mixinClassName.startsWith(prefix) ? mixinClassName.substring(prefix.length()) : mixinClassName;
            return !GL_ONLY.contains(name);
        }
		return !usingVulkan;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		//if (targetClassName.contains("LevelRenderer")) {
		//	targetClass.methods.forEach(m -> System.out.println(m.name + m.desc));
		//}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}
}
