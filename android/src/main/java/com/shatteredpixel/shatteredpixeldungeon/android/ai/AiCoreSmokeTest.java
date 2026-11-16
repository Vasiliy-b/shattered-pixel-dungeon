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

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.GenerationConfig;
import com.google.mlkit.genai.prompt.ModelConfig;
import com.google.mlkit.genai.prompt.ModelPreference;
import com.google.mlkit.genai.prompt.ModelReleaseStage;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

//Probes on-device Gemini Nano (AICore) from inside the game process: is the model
// reachable from this build, what are its limits, and does the per-app quota survive
// a roguelike's worth of calls. Runs on its own thread while the game is on screen,
// as AICore only serves the top foreground app.
//
//Start with:  adb shell am start -n <package>/.AndroidLauncher --ez ai_smoke true
// optional:   --ei ai_smoke_n 50  --ei ai_smoke_pause_ms 1000
//             --es ai_pref fast|full  --es ai_stage stable|preview
//Results go to logcat (tag SPDAgent) and <filesDir>/spd-agent/smoke.jsonl
public class AiCoreSmokeTest implements Runnable {

	public static final String TAG = "SPDAgent";

	//roughly the shape and size of a real turn prompt, so latency numbers transfer
	private static final String RULES =
			"You are playing a roguelike dungeon crawler. Each turn you get the game state.\n" +
			"Reply with exactly one action on its own line and nothing else.\n" +
			"Actions: explore, attack, loot, descend, wait, search, back\n";

	private static final String OBSERVATION =
			"depth 1 | HP 20/20 | level 1 | str 10 | gold 0\n" +
			"status: none\n" +
			"recent: explore -> ok / explore -> ok / attack -> ok\n" +
			"log: You hit the marsupial rat.\n" +
			"enemies: marsupial rat hp 3/8, adjacent north -> attack\n" +
			"items: gold, 4 steps east -> loot\n" +
			"exits: stairs down known, 12 steps southwest -> descend\n" +
			"explore: unexplored at 5 steps east; 8 steps north -> explore\n";

	private final File out;
	private final int runs;
	private final int pauseMs;
	private final int preference;
	private final int stage;

	private AiCoreSmokeTest( File out, int runs, int pauseMs, int preference, int stage ) {
		this.out = out;
		this.runs = runs;
		this.pauseMs = pauseMs;
		this.preference = preference;
		this.stage = stage;
	}

	public static boolean requested( Intent intent ) {
		return intent != null && intent.getBooleanExtra("ai_smoke", false);
	}

	public static void start( Context context, Intent intent ) {
		File dir = new File(context.getFilesDir(), "spd-agent");
		dir.mkdirs();
		int preference = "full".equals(intent.getStringExtra("ai_pref"))
				? ModelPreference.FULL : ModelPreference.FAST;
		int stage = "preview".equals(intent.getStringExtra("ai_stage"))
				? ModelReleaseStage.PREVIEW : ModelReleaseStage.STABLE;
		Thread t = new Thread(new AiCoreSmokeTest(
				new File(dir, "smoke.jsonl"),
				intent.getIntExtra("ai_smoke_n", 50),
				intent.getIntExtra("ai_smoke_pause_ms", 1000),
				preference, stage), "AiCoreSmokeTest");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public void run() {
		try {
			probe();
		} catch (Throwable t) {
			JSONObject o = new JSONObject();
			put(o, "event", "crash");
			describe(o, t);
			emit(o);
		}
	}

	private void probe() throws InterruptedException {
		JSONObject start = new JSONObject();
		put(start, "event", "start");
		put(start, "runs", runs);
		put(start, "pause_ms", pauseMs);
		put(start, "preference", preference == ModelPreference.FULL ? "full" : "fast");
		put(start, "stage", stage == ModelReleaseStage.PREVIEW ? "preview" : "stable");
		emit(start);

		ModelConfig.Builder modelConfig = new ModelConfig.Builder();
		modelConfig.setPreference(preference);
		modelConfig.setReleaseStage(stage);
		GenerationConfig.Builder config = new GenerationConfig.Builder();
		config.setModelConfig(modelConfig.build());
		GenerativeModelFutures model = GenerativeModelFutures.from(
				Generation.INSTANCE.getClient(config.build()));

		Integer status = call("checkStatus", model.checkStatus());
		if (status == null) return;

		if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
			call("download", model.download(new DownloadCallback() {
				@Override
				public void onDownloadStarted( long bytesToDownload ) {
					JSONObject o = new JSONObject();
					put(o, "event", "download_started");
					put(o, "bytes", bytesToDownload);
					emit(o);
				}
				@Override
				public void onDownloadFailed( GenAiException e ) {
					JSONObject o = new JSONObject();
					put(o, "event", "download_failed");
					describe(o, e);
					emit(o);
				}
				@Override
				public void onDownloadProgress( long totalBytesDownloaded ) {}
				@Override
				public void onDownloadCompleted() {
					JSONObject o = new JSONObject();
					put(o, "event", "download_completed");
					emit(o);
				}
			}));
			status = call("checkStatus", model.checkStatus());
		}
		if (status == null || status != FeatureStatus.AVAILABLE) {
			JSONObject o = new JSONObject();
			put(o, "event", "abort");
			put(o, "reason", "model not available, status " + status);
			emit(o);
			return;
		}

		call("getBaseModelName", model.getBaseModelName());
		call("getTokenLimit", model.getTokenLimit());
		call("isSystemPromptAvailable", model.isSystemPromptAvailable());
		call("isThinkingModeAvailable", model.isThinkingModeAvailable());
		call("countTokens", model.countTokens(request()));
		call("warmup", model.warmup());

		int ok = 0, failed = 0;
		long backoffMs = pauseMs;
		for (int i = 1; i <= runs; i++) {
			JSONObject o = new JSONObject();
			put(o, "event", "generate");
			put(o, "i", i);
			long t0 = SystemClock.elapsedRealtime();
			long waitMs = pauseMs;
			try {
				GenerateContentResponse response = model.generateContent(request()).get();
				put(o, "latency_ms", SystemClock.elapsedRealtime() - t0);
				String text = response.getCandidates().isEmpty()
						? "" : response.getCandidates().get(0).getText();
				put(o, "text", text);
				if (!response.getCandidates().isEmpty()) {
					put(o, "finish", String.valueOf(response.getCandidates().get(0).getFinishReason()));
				}
				ok++;
				backoffMs = pauseMs;
			} catch (ExecutionException e) {
				put(o, "latency_ms", SystemClock.elapsedRealtime() - t0);
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				describe(o, cause);
				failed++;
				//honor the delay AICore asks for, otherwise back off exponentially
				long asked = 0;
				if (cause instanceof GenAiException && ((GenAiException) cause).getRetryDelay() != null) {
					asked = ((GenAiException) cause).getRetryDelay().toMillis();
				}
				backoffMs = Math.min(60_000, Math.max(backoffMs * 2, 2000));
				waitMs = Math.max(asked, backoffMs);
				put(o, "wait_ms", waitMs);
			}
			emit(o);
			Thread.sleep(waitMs);
		}

		JSONObject done = new JSONObject();
		put(done, "event", "done");
		put(done, "ok", ok);
		put(done, "failed", failed);
		emit(done);
	}

	private GenerateContentRequest request() {
		GenerateContentRequest.Builder b = new GenerateContentRequest.Builder(
				new TextPart(RULES + "\n" + OBSERVATION));
		b.setTemperature(0.2f);
		b.setTopK(10);
		b.setMaxOutputTokens(16);
		return b.build();
	}

	//runs one blocking call, logs its value or error and latency; null when it failed
	private <T> T call( String name, Future<T> future ) throws InterruptedException {
		JSONObject o = new JSONObject();
		put(o, "event", name);
		long t0 = SystemClock.elapsedRealtime();
		T value = null;
		try {
			value = future.get();
			put(o, "value", String.valueOf(value));
		} catch (ExecutionException e) {
			describe(o, e.getCause() != null ? e.getCause() : e);
		}
		put(o, "latency_ms", SystemClock.elapsedRealtime() - t0);
		emit(o);
		return value;
	}

	static void describe( JSONObject o, Throwable t ) {
		put(o, "error", t.getClass().getSimpleName() + ": " + t.getMessage());
		if (t instanceof GenAiException) {
			GenAiException g = (GenAiException) t;
			put(o, "error_code", g.getErrorCode());
			put(o, "aicore_error_code", g.getAiCoreErrorCode());
			put(o, "aicore_error_type", g.getAiCoreErrorType());
			if (g.getRetryDelay() != null) put(o, "retry_delay_ms", g.getRetryDelay().toMillis());
		}
	}

	static void put( JSONObject o, String key, Object value ) {
		try {
			o.put(key, value);
		} catch (JSONException e) {
			//only thrown for non-finite numbers, which are never passed here
		}
	}

	private synchronized void emit( JSONObject o ) {
		put(o, "ts", System.currentTimeMillis());
		String line = o.toString();
		Log.i(TAG, line);
		try (FileWriter w = new FileWriter(out, true)) {
			w.write(line);
			w.write('\n');
		} catch (IOException e) {
			Log.w(TAG, "could not write " + out, e);
		}
	}

}
