/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.android.ai;

import android.util.Log;

import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.GenerationConfig;
import com.google.mlkit.genai.prompt.ModelConfig;
import com.google.mlkit.genai.prompt.ModelPreference;
import com.google.mlkit.genai.prompt.SystemInstruction;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import com.shatteredpixel.shatteredpixeldungeon.ai.Agent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//Agent brain backed by on-device Gemini Nano (AICore). Every decision runs on one
// worker thread, so the render thread never blocks and requests never overlap.
// AICore only serves the top foreground app, so this works while the game is on screen.
public class AiCoreBrain implements Agent.Brain {

	//AICore asks for a pause after some errors (busy, quota); never wait longer than this
	private static final long MAX_RETRY_DELAY_MS = 10_000;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "AiCoreBrain");
		t.setDaemon(true);
		return t;
	});

	private final GenerativeModelFutures model;
	private final SystemInstruction rules = new SystemInstruction(Agent.rules());

	public AiCoreBrain( boolean fullModel ) {
		ModelConfig.Builder modelConfig = new ModelConfig.Builder();
		modelConfig.setPreference(fullModel ? ModelPreference.FULL : ModelPreference.FAST);
		GenerationConfig.Builder config = new GenerationConfig.Builder();
		config.setModelConfig(modelConfig.build());
		model = GenerativeModelFutures.from(Generation.INSTANCE.getClient(config.build()));
	}

	@Override
	public void decide( String observation, Agent.Reply reply ) {
		worker.execute(() -> {
			try {
				GenerateContentRequest.Builder request = new GenerateContentRequest.Builder(
						rules, new TextPart(observation));
				request.setTemperature(0.2f);
				request.setTopK(10);
				request.setMaxOutputTokens(16);
				GenerateContentResponse response = model.generateContent(request.build()).get();
				reply.accept(response.getCandidates().isEmpty()
						? "" : response.getCandidates().get(0).getText());
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				Log.w(AiCoreSmokeTest.TAG, "decide failed", cause);
				String code = cause.getClass().getSimpleName();
				if (cause instanceof GenAiException) {
					GenAiException g = (GenAiException) cause;
					code = "aicore_" + g.getErrorCode();
					if (g.getRetryDelay() != null) {
						sleep(Math.min(MAX_RETRY_DELAY_MS, g.getRetryDelay().toMillis()));
					}
				}
				reply.fail(code);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				reply.fail("interrupted");
			} catch (RuntimeException e) {
				Log.w(AiCoreSmokeTest.TAG, "decide failed", e);
				reply.fail(e.getClass().getSimpleName());
			}
		});
	}

	private static void sleep( long ms ) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
