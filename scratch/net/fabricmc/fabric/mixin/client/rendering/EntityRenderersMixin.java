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

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.impl.client.rendering.EntityRendererRegistryImpl;
import net.fabricmc.fabric.impl.client.rendering.RegistrationHelperImpl;
import net.minecraft.class_1299;
import net.minecraft.class_1309;
import net.minecraft.class_1657;
import net.minecraft.class_5617;
import net.minecraft.class_5619;
import net.minecraft.class_897;
import net.minecraft.class_922;

@Mixin(class_5619.class)
public abstract class EntityRenderersMixin {
	@Shadow()
	@Final
	private static Map<class_1299<?>, class_5617<?>> RENDERER_FACTORIES;

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "<clinit>*", at = @At(value = "RETURN"))
	private static void onRegisterRenderers(CallbackInfo info) {
		EntityRendererRegistryImpl.setup(((t, factory) -> RENDERER_FACTORIES.put(t, factory)));
	}

	// synthetic lambda in reloadEntityRenderers
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Redirect(method = "method_32174", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRendererFactory;create(Lnet/minecraft/client/render/entity/EntityRendererFactory$Context;)Lnet/minecraft/client/render/entity/EntityRenderer;"))
	private static class_897<?, ?> createEntityRenderer(class_5617<?> entityRendererFactory, class_5617.class_5618 context, ImmutableMap.Builder builder, class_5617.class_5618 context2, class_1299<?> entityType) {
		class_897<?, ?> entityRenderer = entityRendererFactory.create(context);

		if (entityRenderer instanceof class_922) { // Must be living for features
			LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) entityRenderer;
			LivingEntityFeatureRendererRegistrationCallback.EVENT.invoker().registerRenderers((class_1299<? extends class_1309>) entityType, (class_922) entityRenderer, new RegistrationHelperImpl(accessor::callAddFeature), context);
		}

		return entityRenderer;
	}

	// private static synthetic method_32175(Lcom/google/common/collect/ImmutableMap$Builder;Lnet/minecraft/class_5617$class_5618;Ljava/lang/String;Lnet/minecraft/class_5617;)V
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Redirect(method = "method_32175", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRendererFactory;create(Lnet/minecraft/client/render/entity/EntityRendererFactory$Context;)Lnet/minecraft/client/render/entity/EntityRenderer;"))
	private static class_897<? extends class_1657, ?> createPlayerEntityRenderer(class_5617 playerEntityRendererFactory, class_5617.class_5618 context) {
		class_897<? extends class_1657, ?> entityRenderer = playerEntityRendererFactory.create(context);

		LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) entityRenderer;
		LivingEntityFeatureRendererRegistrationCallback.EVENT.invoker().registerRenderers(class_1299.field_6097, (class_922) entityRenderer, new RegistrationHelperImpl(accessor::callAddFeature), context);

		return entityRenderer;
	}
}
