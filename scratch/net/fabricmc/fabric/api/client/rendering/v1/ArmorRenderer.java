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

package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.impl.client.rendering.ArmorRendererRegistryImpl;
import net.minecraft.class_10034;
import net.minecraft.class_1304;
import net.minecraft.class_1799;
import net.minecraft.class_1921;
import net.minecraft.class_1935;
import net.minecraft.class_2960;
import net.minecraft.class_3879;
import net.minecraft.class_3887;
import net.minecraft.class_4587;
import net.minecraft.class_4588;
import net.minecraft.class_4597;
import net.minecraft.class_4608;
import net.minecraft.class_572;
import net.minecraft.class_918;

/**
 * Armor renderers render worn armor items with custom code.
 * They may be used to render armor with special models or effects.
 *
 * <p>The renderers are registered with {@link net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer#register(ArmorRenderer, class_1935...)}.
 */
@FunctionalInterface
public interface ArmorRenderer {
	/**
	 * Registers the armor renderer for the specified items.
	 * @param renderer	the renderer
	 * @param items		the items
	 * @throws IllegalArgumentException if an item already has a registered armor renderer
	 * @throws NullPointerException if either an item or the renderer is null
	 */
	static void register(ArmorRenderer renderer, class_1935... items) {
		ArmorRendererRegistryImpl.register(renderer, items);
	}

	/**
	 * Helper method for rendering a specific armor model, comes after setting visibility.
	 *
	 * <p>This primarily handles applying glint and the correct {@link class_1921}
	 * @param matrices			the matrix stack
	 * @param vertexConsumers	the vertex consumer provider
	 * @param light				packed lightmap coordinates
	 * @param stack				the item stack of the armor item
	 * @param model				the model to be rendered
	 * @param texture			the texture to be applied
	 */
	static void renderPart(class_4587 matrices, class_4597 vertexConsumers, int light, class_1799 stack, class_3879 model, class_2960 texture) {
		class_4588 vertexConsumer = class_918.method_27952(vertexConsumers, class_1921.method_25448(texture), stack.method_7958());
		model.method_62100(matrices, vertexConsumer, light, class_4608.field_21444, 0xFFFFFFFF);
	}

	/**
	 * Renders an armor part.
	 *
	 * @param matrices			     the matrix stack
	 * @param vertexConsumers	     the vertex consumer provider
	 * @param stack				     the item stack of the armor item
	 * @param bipedEntityRenderState the render state of the entity
	 * @param slot				     the equipment slot in which the armor stack is worn
	 * @param light				     packed lightmap coordinates
	 * @param contextModel		     the model provided by {@link class_3887#method_17165()}
	 */
	void render(class_4587 matrices, class_4597 vertexConsumers, class_1799 stack, class_10034 bipedEntityRenderState, class_1304 slot, int light, class_572<class_10034> contextModel);
}
