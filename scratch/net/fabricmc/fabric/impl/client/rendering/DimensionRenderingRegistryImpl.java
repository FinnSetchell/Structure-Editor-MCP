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

package net.fabricmc.fabric.impl.client.rendering;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry.CloudRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry.SkyRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry.WeatherRenderer;
import net.fabricmc.fabric.mixin.client.rendering.DimensionEffectsAccessor;
import net.minecraft.class_1937;
import net.minecraft.class_2960;
import net.minecraft.class_5294;
import net.minecraft.class_5321;

public final class DimensionRenderingRegistryImpl {
	private static final Map<class_5321<class_1937>, SkyRenderer> SKY_RENDERERS = new IdentityHashMap<>();
	private static final Map<class_5321<class_1937>, CloudRenderer> CLOUD_RENDERERS = new IdentityHashMap<>();
	private static final Map<class_5321<class_1937>, WeatherRenderer> WEATHER_RENDERERS = new IdentityHashMap<>();

	public static void registerSkyRenderer(class_5321<class_1937> key, DimensionRenderingRegistry.SkyRenderer renderer) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(renderer);

		SKY_RENDERERS.putIfAbsent(key, renderer);
	}

	public static void registerWeatherRenderer(class_5321<class_1937> key, WeatherRenderer renderer) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(renderer);

		WEATHER_RENDERERS.putIfAbsent(key, renderer);
	}

	public static void registerDimensionEffects(class_2960 key, class_5294 effects) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(effects);
		//The map containing all dimension effects returns a default if null so a null check doesn't work.

		DimensionEffectsAccessor.getIdentifierMap().putIfAbsent(key, effects);
	}

	public static void registerCloudRenderer(class_5321<class_1937> key, CloudRenderer renderer) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(renderer);

		CLOUD_RENDERERS.putIfAbsent(key, renderer);
	}

	@Nullable
	public static SkyRenderer getSkyRenderer(class_5321<class_1937> key) {
		return SKY_RENDERERS.get(key);
	}

	@Nullable
	public static CloudRenderer getCloudRenderer(class_5321<class_1937> key) {
		return CLOUD_RENDERERS.get(key);
	}

	@Nullable
	public static WeatherRenderer getWeatherRenderer(class_5321<class_1937> key) {
		return WEATHER_RENDERERS.get(key);
	}

	@Nullable
	public static class_5294 getDimensionEffects(class_2960 key) {
		return DimensionEffectsAccessor.getIdentifierMap().get(key);
	}
}
