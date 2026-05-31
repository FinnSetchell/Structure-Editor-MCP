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

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.class_1297;
import net.minecraft.class_1921;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_4184;
import net.minecraft.class_4587;
import net.minecraft.class_4588;
import net.minecraft.class_4597;
import net.minecraft.class_4604;
import net.minecraft.class_638;
import net.minecraft.class_757;
import net.minecraft.class_761;
import net.minecraft.class_9779;

public final class WorldRenderContextImpl implements WorldRenderContext.BlockOutlineContext, WorldRenderContext {
	private class_761 worldRenderer;
	private class_9779 tickCounter;
	private boolean blockOutlines;
	private class_4184 camera;
	private class_757 gameRenderer;
	private Matrix4f positionMatrix;
	private Matrix4f projectionMatrix;
	private class_4597 consumers;
	private boolean advancedTranslucency;
	private class_638 world;

	@Nullable
	private class_4604 frustum;
	@Nullable
	private class_4587 matrixStack;
	private boolean translucentBlockOutline;

	private class_1297 entity;
	private double cameraX;
	private double cameraY;
	private double cameraZ;
	private class_2338 blockPos;
	private class_2680 blockState;

	public boolean renderBlockOutline = true;

	public void prepare(
			class_761 worldRenderer,
			class_9779 tickCounter,
			boolean blockOutlines,
			class_4184 camera,
			class_757 gameRenderer,
			Matrix4f positionMatrix,
			Matrix4f projectionMatrix,
			class_4597 consumers,
			boolean advancedTranslucency,
			class_638 world
	) {
		this.worldRenderer = worldRenderer;
		this.tickCounter = tickCounter;
		this.blockOutlines = blockOutlines;
		this.camera = camera;
		this.gameRenderer = gameRenderer;
		this.positionMatrix = positionMatrix;
		this.projectionMatrix = projectionMatrix;
		this.consumers = consumers;
		this.advancedTranslucency = advancedTranslucency;
		this.world = world;

		frustum = null;
		matrixStack = null;
	}

	public void setFrustum(class_4604 frustum) {
		this.frustum = frustum;
	}

	public void setMatrixStack(class_4587 matrixStack) {
		this.matrixStack = matrixStack;
	}

	public void setTranslucentBlockOutline(boolean translucentBlockOutline) {
		this.translucentBlockOutline = translucentBlockOutline;
	}

	public void prepareBlockOutline(
			class_1297 entity,
			double cameraX,
			double cameraY,
			double cameraZ,
			class_2338 blockPos,
			class_2680 blockState
	) {
		this.entity = entity;
		this.cameraX = cameraX;
		this.cameraY = cameraY;
		this.cameraZ = cameraZ;
		this.blockPos = blockPos;
		this.blockState = blockState;
	}

	@Override
	public class_761 worldRenderer() {
		return worldRenderer;
	}

	@Override
	public class_9779 tickCounter() {
		return this.tickCounter;
	}

	@Override
	public boolean blockOutlines() {
		return blockOutlines;
	}

	@Override
	public class_4184 camera() {
		return camera;
	}

	@Override
	public class_757 gameRenderer() {
		return gameRenderer;
	}

	@Override
	public Matrix4f positionMatrix() {
		return positionMatrix;
	}

	@Override
	public Matrix4f projectionMatrix() {
		return projectionMatrix;
	}

	@Override
	public class_638 world() {
		return world;
	}

	@Override
	public boolean advancedTranslucency() {
		return advancedTranslucency;
	}

	@Override
	public class_4597 consumers() {
		return consumers;
	}

	@Override
	@Nullable
	public class_4604 frustum() {
		return frustum;
	}

	@Override
	@Nullable
	public class_4587 matrixStack() {
		return matrixStack;
	}

	@Override
	public boolean translucentBlockOutline() {
		return translucentBlockOutline;
	}

	@Override
	public class_1297 entity() {
		return entity;
	}

	@Override
	public double cameraX() {
		return cameraX;
	}

	@Override
	public double cameraY() {
		return cameraY;
	}

	@Override
	public double cameraZ() {
		return cameraZ;
	}

	@Override
	public class_2338 blockPos() {
		return blockPos;
	}

	@Override
	public class_2680 blockState() {
		return blockState;
	}

	@Deprecated
	@Override
	public class_4588 vertexConsumer() {
		return consumers.getBuffer(class_1921.method_23594());
	}
}
