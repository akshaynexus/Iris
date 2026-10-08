# Metal port research — 2026-10-09

## Sources and reproducibility

Read-only study checkouts (no changes, builds, or execution of their code):

- [fangbm/iris4vulkan](https://github.com/fangbm/iris4vulkan/tree/424e96796d21d0810aadbc609db698c3c3a8b342), `/tmp/iris-research/iris4vulkan`, commit `424e96796d21d0810aadbc609db698c3c3a8b342`.
- [aaaapai/Iris-Vulkan-Port](https://github.com/aaaapai/Iris-Vulkan-Port/tree/47babb4aac66b16084fb419e8d85c2e14e70d41e), `/tmp/iris-research/Iris-Vulkan-Port`, commit `47babb4aac66b16084fb419e8d85c2e14e70d41e`.
- [Iris 26.3](https://github.com/IrisShaders/Iris/tree/770155cc91d8c11272d93cb4457fac1625d37a4d). Remote branch enumeration and GitHub PR searches for Vulkan/Aperture found no implementation branch or PR. Search results mentioning those words in unrelated PR discussions are not implementations. [Aperture example pack](https://github.com/IrisShaders/Aperture-Example-Pack) exists, but is not a legacy Iris renderer implementation. This is a bounded search, not proof that no private development exists.

Both forks retain Iris's LGPL-3.0 license and identify glsl-transformer as AGPL-3.0 in README/LICENSE-DEPENDENCIES. No code from these forks is imported. Code moved from this Iris fork into mcopt retains LGPL-3.0 attribution; it must not be represented as original MIT-licensed mcopt code.

## What the ports actually do

### iris4vulkan (26.2)

The important sources are `vulkan/IrisNativeVulkan`, `IrisVulkanShaderResources`, `IrisVulkanUniformSnapshot`, `IrisVulkanGbufferTargets`, `IrisVulkanScreenPassExecutor`, `IrisVulkanScreenPassGraph`, `IrisVulkanRenderPassBindings`, `IrisVulkanCustomTextures`, and `mixin/vulkan/*` under `common/src/main/java/net/irisshaders/iris`.

It uses Mojang's GlslCompiler and RenderPipeline/RenderPass concepts, **but is not a backend-independent device API port**. `IrisNativeVulkan.compileCustomVulkanPipeline` and `compileVulkanPipeline` get the compiler through `VKOnly_VulkanDeviceAccessor`, then call `VulkanRenderPipeline.compile` directly. Pipeline and pass mixins name concrete Vulkan classes. 26.2 uses `com.mojang.blaze3d` API packages, unlike 26.3 renderpearl.

- ShaderResources gathers loose uniforms into a shared block, constructs binding layouts and assigns stage locations. UniformSnapshot supplies values. Unsupported resource kinds are diagnosed, including SSBOs, block arrays and unsupported opaque resources.
- GbufferTargets models logical targets and current/next views. ScreenPassGraph/Planner schedules target flips; Executor builds MRT descriptors, binds resources, and executes deferred/composite/final passes. It requests mip generation before the consumer, and maps viewport directives to render areas (including clamping, which is not identical to GL viewport semantics).
- NativeVulkan caches program replacements, using ShaderKey and transformed Iris sources. Its capability table explicitly leaves sky, weather, beacon, glint, hand, entities and shadows planned in several paths. Thus successful compilation does not establish complete gbuffer parity.
- RenderPassBindings explicitly supplies a fully-lit fallback for shadow samplers because real shadow targets are not wired. This is a concrete visual limitation, not evidence of functioning shadows.
- Shader preparation handles API interfaces; depth adaptation and real target binding must be considered together. Copying its pipeline creation alone would not resolve this fork's reversed-Z behavior.
- Color states belong to replacement pipelines, custom textures have their own backend path, and logical target history belongs to the screen graph. Neither the inherited generic README nor the absence of open issues is a visual acceptance test.

### Iris-Vulkan-Port (1.21.1, VulkanMod)

See `pipeline/terrain/IrisTerrainPipelineCompiler`, `IrisTerrainRenderHook`, `pipeline/transform/transformer/LayoutTransformer`, `gl/program/ProgramBuilder`, `pipeline/CompositeRenderer`, `FinalPassRenderer`, `shadows/ShadowRenderer`, and the bundled `custom_vulkanmod` tree.

This is a VulkanMod integration, not Mojang's 26.x device API. It translates GLSL to SPIR-V, uses explicit interface locations and UBO uploads, replaces terrain programs for VulkanMod's compressed vertex format, and maps framebuffer/pass operations through VulkanMod. Composite/deferred use the Iris pass ordering, target flips and mip directives. Blend state must be selected before pipeline binding because Vulkan bakes it into pipeline variants. Shadows use Vulkan render passes and depth attachments. Its coordinate adaptations cover depth range, Y and texture coordinates, but do not establish compatibility with 26.3's reverse-Z mixins. Custom texture and mip operations remain tied to the VulkanMod texture implementation.

The README's port-specific status says terrain, shadows and composite/deferred operate, and identifies unresolved volumetric-lighting and color-grading artifacts. The inherited general Iris compatibility claims are not evidence that this port matches GL.

## Local comparison and scope

Reviewed against `IrisRenderingPipeline`, `CompositeRenderer`, `FinalPassRenderer`, `ShaderCreator`, `ExtendedShader`, `IrisSamplers`, `ShadowRenderer`, `RenderTargets`, `MixinGlRenderPipeline`, `MixinGlCommandEncoder` and `UndoReverseZ*`. This is a source audit, not a claim of exhaustive image equivalence.

| Area | Existing Metal difference | Required treatment |
|---|---|---|
| Compilation/ownership | Iris copies GLSL rewrite, shaderc, SPIRV-Cross, slot mapping, library/PSO ownership and byte upload | Move to mcopt API; share SPIRV-Cross machinery with vanilla MetalPipeline |
| Viewport | Composite scale/offset ignored | Use GL integer truncation and actual Metal viewport, not scissor |
| Horizon | onBeginClear only sets phase | Reuse HorizonRenderer and GL dimension/sky-disc condition |
| Center depth | iris_centerDepthSmooth falls through to white | GPU sample/smooth before hand, same shader formula and half-life |
| Mips | A full mip allocation enables sampling from frame start on both ping-pong textures | Reset each frame, enable only generated physical texture, preserve through later writes |
| Final absent | Logs warning; leaves main target unpresented | Fullscreen colortex0 copy with format conversion |
| Shadow alias | shadow always selects shadowtex0 | Select shadowtex1 when watershadow is present |
| Shadow samplers | Compare filter hard-coded linear; raw depth nearest; ignores directives | Carry sampler settings into compiler/runtime |
| Gbuffer blend | Color channel mask ignored | Preserve game's RGBA write mask and per-target override semantics |
| Depth | World uses vanilla reversed-Z, zero-to-one transformer inverts pack depth reads; shadows keep reverse compare | Preserve matched transform/clear/test; do not enable UndoReverseZFour alone (would mix conventions). Offline compilation cannot prove depth image parity |
| Gbuffer fallback | Unknown key/source drops draws; GL has fallback shader creation | Requires equivalent fallback and attachment semantics; explicit remaining gap if not implemented |
| Targets | Union of all gbuffer outputs capped at 8, silently remaps dropped outputs to slot 7 | Reject unsupported union instead of corrupting another target; per-program pass splitting needs new redirect lifecycle |
| Target formats | Metal substitutes RGBA for RGB; packed low-bit formats approximated | Hardware format limitation; report |
| Custom/PBR textures | Native custom path supports subset; normals/specular defaults but no full resource-pack PBR tracking | Report unsupported dimensions/resource PBR as remaining |
| Extended inputs | Missing attributes are zero-filled | GL fallback attributes and extended terrain layout need in-game checks |
| Compute/shadowcomp | Not executed; SSBO/images unavailable | Out of baseline profile: Complementary default disables colored-light compute; do not claim support |
| Geometry/tessellation | No Metal stages | Hardware limitation; reject |
| DH | No Metal DH integration | Outside Complementary default baseline |

Scope is shared runtime plus concrete raster semantics above. Hardware/API extensions, new compute execution and DH are separate work. Any unfixed raster gap must remain visible in CODEX-REPORT-1.md rather than being called parity.

## Design decision

Choose **(b), mcopt public shader-program API with existing redirects**. Option (a) has the best long-term portability and would reuse Mojang's GLSL frontend. However, neither researched port supplies a complete backend-independent implementation to transplant. iris4vulkan still uses backend internals and lacks the real shadow path this fork already has. Replacing all draw routing now would combine an API migration with correctness work and lose working coverage.

Move GLSL adaptation, reflection, slots, Metal libraries/PSO caching and uniform storage/upload into `mcopt.metal`. Share the lower-level SPIRV-Cross context/options/binding/compile code with mcopt's existing device pipeline, so this is more than relocating an independent translator. Iris retains pack directives, uniform value producers, pass scheduling and binding semantic texture names. Its mcopt dependency stays compileOnly. This design is explicitly Metal-specific; it does not claim to run on Mojang Vulkan unchanged.

Verification uses Java 26, sequential builds followed immediately by daemon shutdown, then the 99-program native Metal pipeline harness. No Minecraft or CurseForge launch. Shader compilation is necessary but cannot certify pixel parity.

## Implementation follow-up

The implementation fixes the viewport, horizon, center-depth, physical mip-state, missing-final, shadow alias,
color-mask and fallback-routing rows. Missing known pack sources reuse ShaderSynthesizer. Unknown device programs
restore their original framebuffer and program, with mcopt replaying geometry state after the encoder restart.

A further source check corrected the initial shadow assumption: `ShadowRenderer.configureDepthSampler` sets the
texture's compare function to GEQUAL, but `IrisSamplers` binds `ShadowRenderTargets.getSamplerFor`, whose `GlSampler`
uses LEQUAL. Bound sampler state wins over texture state. The new mcopt comparison sampler therefore uses LEQUAL
and pack nearest/linear filtering. A GPU readback test verifies the compare operation. Shadow world depth testing
and clear values are separate from this texture sampling operation and were not blindly inverted.

The old game log (read only) reports the baseline gbuffer union `[0, 3, 4, 6, 13]`, within Metal's eight simultaneous
attachments. Logical colortex13 is supported: the limit applies to attachment count, not the highest logical index.
The pack's POTATO through HIGH profiles explicitly set COLORED_LIGHTING=0; VERYHIGH and ULTRA enable it. This task
has not enabled the compute-dependent profiles. Source and image parity outside the documented baseline remain
limitations, not successful verification claims.
