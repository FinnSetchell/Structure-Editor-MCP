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

import org.jetbrains.annotations.Nullable;
import net.fabricmc.fabric.impl.client.rendering.DimensionRenderingRegistryImpl;
import net.minecraft.class_1937;
import net.minecraft.class_2960;
import net.minecraft.class_5294;
import net.minecraft.class_5321;

/**
 * Dimensional renderers render world specific visuals of a world.
 * They may be used to render the sky, weather, or clouds.
 * The {@link class_5294} is the vanilla dimensional renderer.
 */
public interface DimensionRenderingRegistry {
	/**
	 * Registers the custom sky renderer for a {@link class_1937}.
	 *
	 * <p>This overrides Vanilla's sky rendering.
	 * @param key A {@link class_5321} for your {@link class_1937}
	 * @param renderer A {@link SkyRenderer} implementation
	 * @throws IllegalArgumentException if key is already registered.
	 */
	static void registerSkyRenderer(class_5321<class_1937> key, SkyRenderer renderer) {
		DimensionRenderingRegistryImpl.registerSkyRenderer(key, renderer);
	}

	/**
	 * Registers a custom weather renderer for a {@link class_1937}.
	 *
	 * <p>This overrides Vanilla's weather rendering.
	 * @param key A RegistryKey for your {@link class_1937}
	 * @param renderer A {@link WeatherRenderer} implementation
	 * @throws IllegalArgumentException if key is already registered.
	 */
	static void registerWeatherRenderer(class_5321<class_1937> key, WeatherRenderer renderer) {
		DimensionRenderingRegistryImpl.registerWeatherRenderer(key, renderer);
	}

	/**
	 * Registers dimension effects for an {@link net.minecraft.class_2960}.
	 *
	 * <p>This registers a new option for the "effects" entry of the dimension type json.
	 *
	 * @param key     The {@link net.minecraft.class_2960} for the new option entry.
	 * @param effects The {@link class_5294} option.
	 * @throws IllegalArgumentException if key is already registered.
	 */
	static void registerDimensionEffects(class_2960 key, class_5294 effects) {
		DimensionRenderingRegistryImpl.registerDimensionEffects(key, effects);
	}

	/**
	 * Registers a custom cloud renderer for a {@link class_1937}.
	 *
	 * <p>This overrides Vanilla's cloud rendering.
	 *
	 * @param key      A {@link class_5321} for your {@link class_1937}
	 * @param renderer A {@link CloudRenderer} implementation
	 * @throws IllegalArgumentException if key is already registered.
	 */
	static void registerCloudRenderer(class_5321<class_1937> key, CloudRenderer renderer) {
		DimensionRenderingRegistryImpl.registerCloudRenderer(key, renderer);
	}

	/**
	 * Gets the custom sky renderer for the given {@link class_1937}.
	 *
	 * @param key A {@link class_5321} for your {@link class_1937}
	 * @return {@code null} if no custom sky renderer is registered for the dimension.
	 */
	@Nullable
	static SkyRenderer getSkyRenderer(class_5321<class_1937> key) {
		return DimensionRenderingRegistryImpl.getSkyRenderer(key);
	}

	/**
	 * Gets the custom cloud renderer for the given {@link class_1937}.
	 *
	 * @param key A {@link class_5321} for your {@link class_1937}
	 * @return {@code null} if no custom cloud renderer is registered for the dimension.
	 */
	@Nullable
	static CloudRenderer getCloudRenderer(class_5321<class_1937> key) {
		return DimensionRenderingRegistryImpl.getCloudRenderer(key);
	}

	/**
	 * Gets the custom weather effect renderer for the given {@link class_1937}.
	 *
	 * @return {@code null} if no custom weather effect renderer is registered for the dimension.
	 */
	@Nullable
	static WeatherRenderer getWeatherRenderer(class_5321<class_1937> key) {
		return DimensionRenderingRegistryImpl.getWeatherRenderer(key);
	}

	/**
	 * Gets the dimension effects registered for an id.
	 * @param key A {@link class_5321} for your {@link class_1937}.
	 * @return overworld effect if no dimension effects is registered for the key.
	 */
	@Nullable
	static class_5294 getDimensionEffects(class_2960 key) {
		return DimensionRenderingRegistryImpl.getDimensionEffects(key);
	}

	@FunctionalInterface
	interface SkyRenderer {
		void render(WorldRenderContext context);
	}

	@FunctionalInterface
	interface WeatherRenderer {
		void render(WorldRenderContext context);
	}

	@FunctionalInterface
	interface CloudRenderer {
		void render(WorldRenderContext context);
	}
}
