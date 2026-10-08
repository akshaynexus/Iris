package net.irisshaders.iris.mixin;

import net.irisshaders.iris.platform.IrisPlatformHelpers;

/**
 * Detects mcopt's Metal backend (mod id mcopt-metal) before any mixin applies. mcopt decides Metal or OpenGL from
 * -Dmcopt.metal and config/mcopt.properties in mcopt.metal.Profile.apply(), which is idempotent; it is called here by
 * reflection so this works whichever of the two mods' mixin plugins loads first. This fork declares "mcopt:metal" in
 * fabric.mod.json, so mcopt keeps Metal on with it instead of falling back to OpenGL as it does for upstream Iris.
 */
public final class MetalSupport {
	private MetalSupport() {
	}

	public static boolean installed() {
		return IrisPlatformHelpers.getInstance().isModLoaded("mcopt-metal");
	}

	/** Called after device initialization; configuration alone does not prove Metal won backend selection. */
	public static boolean shaderPacksBlocked() {
		if (!installed()) return false;
		if (!IrisMixinPlugin.usingMetal) return true;
		return !(com.mojang.blaze3d.systems.RenderSystem.getDevice() instanceof GpuDeviceAccessor device)
			|| !device.getBackend().getClass().getName().equals("mcopt.metal.MetalDevice");
	}

	static boolean metalActive() {
		try {
			if (!IrisPlatformHelpers.getInstance().isModLoaded("mcopt-metal")) return false;
			Class.forName("mcopt.metal.Profile").getMethod("apply").invoke(null);
		} catch (Throwable t) {
			return false;
		}
		return Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));
	}
}
