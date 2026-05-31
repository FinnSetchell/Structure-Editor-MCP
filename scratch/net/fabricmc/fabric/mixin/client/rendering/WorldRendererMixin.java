/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.mixin.client.rendering;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.impl.client.rendering.WorldRenderContextImpl;
import net.minecraft.class_2338;
import net.minecraft.class_243;
import net.minecraft.class_2680;
import net.minecraft.class_310;
import net.minecraft.class_4063;
import net.minecraft.class_4184;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_4599;
import net.minecraft.class_4604;
import net.minecraft.class_638;
import net.minecraft.class_757;
import net.minecraft.class_761;
import net.minecraft.class_9779;
import net.minecraft.class_9909;
import net.minecraft.class_9916;
import net.minecraft.class_9922;
import net.minecraft.class_9958;
import net.minecraft.class_9960;

@Mixin(class_761.class)
public abstract class WorldRendererMixin {
	@Final
	@Shadow
	private class_4599 bufferBuilders;
	@Shadow private class_638 world;
	@Final
	@Shadow
	private class_310 client;
	@Shadow
	@Final
	private class_9960 framebufferSet;
	@Unique private final WorldRenderContextImpl context = new WorldRenderContextImpl();

	@Inject(method = "render", at = @At("HEAD"))
	private void beforeRender(class_9922 objectAllocator, class_9779 tickCounter, boolean renderBlockOutline, class_4184 camera, class_757 gameRenderer, Matrix4f positionMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		context.prepare((class_761) (Object) this, tickCounter, renderBlockOutline, camera, gameRenderer, positionMatrix, projectionMatrix, bufferBuilders.method_23000(), class_310.method_29611(), world);
		WorldRenderEvents.START.invoker().onStart(context);
	}

	@Inject(method = "setupTerrain", at = @At("RETURN"))
	private void afterTerrainSetup(class_4184 camera, class_4604 frustum, boolean hasForcedFrustum, boolean spectator, CallbackInfo ci) {
		context.setFrustum(frustum);
	}

	@Inject(
			method = "method_62214",
			at = @At(
				value = "INVOKE_STRING",
				target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V",
				args = "ldc=terrain",
				shift = Shift.AFTER
			) // Points to after profiler.push("terrain");
	)
	private void beforeTerrainSolid(CallbackInfo ci) {
		WorldRenderEvents.AFTER_SETUP.invoker().afterSetup(context);
	}

	@Inject(
			method = "method_62214",
			at = @At(
				value = "INVOKE",
				target = "Lnet/minecraft/client/render/WorldRenderer;renderLayer(Lnet/minecraft/client/render/RenderLayer;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
				ordinal = 2,
				shift = Shift.AFTER
			)
	)
	private void afterTerrainSolid(CallbackInfo ci) {
		WorldRenderEvents.BEFORE_ENTITIES.invoker().beforeEntities(context);
	}

	@ModifyExpressionValue(method = "method_62214", at = @At(value = "NEW", target = "net/minecraft/client/util/math/MatrixStack"))
	private class_4587 setMatrixStack(class_4587 matrixStack) {
		context.setMatrixStack(matrixStack);
		return matrixStack;
	}

	@Inject(method = "method_62214", at = @At(value = "CONSTANT", args = "stringValue=blockentities", ordinal = 0))
	private void afterEntities(CallbackInfo ci) {
		WorldRenderEvents.AFTER_ENTITIES.invoker().afterEntities(context);
	}

	@Inject(method = "renderTargetBlockOutline", at = @At("HEAD"))
	private void beforeRenderOutline(class_4184 camera, class_4597.class_4598 vertexConsumers, class_4587 matrices, boolean translucent, CallbackInfo ci) {
		context.setTranslucentBlockOutline(translucent);
		context.renderBlockOutline = WorldRenderEvents.BEFORE_BLOCK_OUTLINE.invoker().beforeBlockOutline(context, client.field_1765);
	}

	@SuppressWarnings("ConstantConditions")
	@Inject(method = "renderTargetBlockOutline", at = @At(value = "INVOKE", target = "net/minecraft/client/option/GameOptions.getHighContrastBlockOutline()Lnet/minecraft/client/option/SimpleOption;"), cancellable = true)
	private void onDrawBlockOutline(class_4184 camera, class_4597.class_4598 vertexConsumers, class_4587 matrices, boolean translucent, CallbackInfo ci, @Local class_2338 blockPos, @Local class_2680 blockState, @Local class_243 cameraPos) {
		if (!context.renderBlockOutline) {
			// Was cancelled before we got here, so do not
			// fire the BLOCK_OUTLINE event per contract of the API.
			ci.cancel();
			return;
		}

		context.prepareBlockOutline(camera.method_19331(), cameraPos.field_1352, cameraPos.field_1351, cameraPos.field_1350, blockPos, blockState);

		if (!WorldRenderEvents.BLOCK_OUTLINE.invoker().onBlockOutline(context, context)) {
			vertexConsumers.method_37104();
			ci.cancel();
		}
	}

	@Inject(
			method = "method_62214",
			at = @At(
				value = "INVOKE",
				target = "Lnet/minecraft/client/render/debug/DebugRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/Frustum;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;DDD)V",
				ordinal = 0
			)
	)
	private void beforeDebugRender(CallbackInfo ci) {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.invoker().beforeDebugRender(context);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/GameOptions;getCloudRenderModeValue()Lnet/minecraft/client/option/CloudRenderMode;"))
	private void beforeClouds(CallbackInfo ci, @Local class_9909 frameGraphBuilder) {
		class_9916 afterTranslucentPass = frameGraphBuilder.method_61911("afterTranslucent");
		framebufferSet.field_53091 = afterTranslucentPass.method_61933(framebufferSet.field_53091);

		afterTranslucentPass.method_61929(() -> WorldRenderEvents.AFTER_TRANSLUCENT.invoker().afterTranslucent(context));
	}

	@Inject(method = "method_62214", at = @At("RETURN"))
	private void onFinishWritingFramebuffer(CallbackInfo ci) {
		WorldRenderEvents.LAST.invoker().onLast(context);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void afterRender(CallbackInfo ci) {
		WorldRenderEvents.END.invoker().onEnd(context);
	}

	@Inject(method = "Lnet/minecraft/client/render/WorldRenderer;reload()V", at = @At("HEAD"))
	private void onReload(CallbackInfo ci) {
		InvalidateRenderStateCallback.EVENT.invoker().onInvalidate();
	}

	@Inject(at = @At("HEAD"), method = "renderWeather", cancellable = true)
	private void renderWeather(class_9909 frameGraphBuilder, class_243 vec3d, float f, class_9958 fog, CallbackInfo info) {
		if (this.client.field_1687 != null) {
			DimensionRenderingRegistry.WeatherRenderer renderer = DimensionRenderingRegistry.getWeatherRenderer(world.method_27983());

			if (renderer != null) {
				renderer.render(context);
				info.cancel();
			}
		}
	}

	@Inject(at = @At("HEAD"), method = "renderClouds", cancellable = true)
	private void renderCloud(class_9909 frameGraphBuilder, Matrix4f matrix4f, Matrix4f matrix4f2, class_4063 cloudRenderMode, class_243 vec3d, float f, int i, float g, CallbackInfo info) {
		if (this.client.field_1687 != null) {
			DimensionRenderingRegistry.CloudRenderer renderer = DimensionRenderingRegistry.getCloudRenderer(world.method_27983());

			if (renderer != null) {
				renderer.render(context);
				info.cancel();
			}
		}
	}

	@Inject(at = @At(value = "HEAD"), method = "renderSky", cancellable = true)
	private void renderSky(class_9909 frameGraphBuilder, class_4184 camera, float tickDelta, class_9958 fog, CallbackInfo info) {
		if (this.client.field_1687 != null) {
			DimensionRenderingRegistry.SkyRenderer renderer = DimensionRenderingRegistry.getSkyRenderer(world.method_27983());

			if (renderer != null) {
				renderer.render(context);
				info.cancel();
			}
		}
	}
}
