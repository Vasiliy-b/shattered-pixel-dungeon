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

import com.google.mlkit.genai.common.FunctionCall;
import com.google.mlkit.genai.schema.tools.FunctionDeclaration;
import com.google.mlkit.genai.schema.tools.FunctionSchema;
import com.google.mlkit.genai.schema.tools.FunctionType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

//Runs prompt experiments against on-device Gemini Nano without rebuilding the game:
// reads cases from <filesDir>/spd-agent/lab.json, writes every reply to lab_out.jsonl.
//
//Start with:  adb shell am start -n <package>/.AndroidLauncher --ez ai_lab true
//
//lab.json: {"pref":"fast"|"full", "pause_ms":300, "cases":[
//   {"id":"...", "system":"...", "prompt":"...", "n":10,
//    "temperature":0.2, "top_k":10, "max_tokens":16, "thinking":false}]}
// "system" is optional and is sent as the system instruction.
public class AiCoreLab implements Runnable {

	private final File in;
	private final File out;

	private AiCoreLab( File in, File out ) {
		this.in = in;
		this.out = out;
	}

	public static boolean requested( Intent intent ) {
		return intent != null && intent.getBooleanExtra("ai_lab", false);
	}

	public static void start( Context context ) {
		File dir = new File(context.getFilesDir(), "spd-agent");
		dir.mkdirs();
		Thread t = new Thread(new AiCoreLab(
				new File(dir, "lab.json"), new File(dir, "lab_out.jsonl")), "AiCoreLab");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public void run() {
		try {
			experiment();
		} catch (Throwable t) {
			JSONObject o = new JSONObject();
			AiCoreSmokeTest.put(o, "event", "crash");
			AiCoreSmokeTest.describe(o, t);
			emit(o);
		}
	}

	private void experiment() throws Exception {
		JSONObject lab = new JSONObject(read(in));
		long pauseMs = lab.optLong("pause_ms", 300);

		ModelConfig.Builder modelConfig = new ModelConfig.Builder();
		modelConfig.setPreference("full".equals(lab.optString("pref"))
				? ModelPreference.FULL : ModelPreference.FAST);
		GenerationConfig.Builder config = new GenerationConfig.Builder();
		config.setModelConfig(modelConfig.build());
		GenerativeModelFutures model = GenerativeModelFutures.from(
				Generation.INSTANCE.getClient(config.build()));

		JSONObject start = new JSONObject();
		AiCoreSmokeTest.put(start, "event", "start");
		AiCoreSmokeTest.put(start, "model", model.getBaseModelName().get());
		emit(start);

		JSONArray cases = lab.getJSONArray("cases");
		for (int c = 0; c < cases.length(); c++) {
			JSONObject spec = cases.getJSONObject(c);
			String id = spec.optString("id", "case" + c);
			int n = spec.optInt("n", 10);

			for (int i = 1; i <= n; i++) {
				JSONObject o = new JSONObject();
				AiCoreSmokeTest.put(o, "event", "reply");
				AiCoreSmokeTest.put(o, "case", id);
				AiCoreSmokeTest.put(o, "i", i);
				long waitMs = pauseMs;
				long t0 = SystemClock.elapsedRealtime();
				try {
					GenerateContentResponse response = model.generateContent(request(spec)).get();
					AiCoreSmokeTest.put(o, "latency_ms", SystemClock.elapsedRealtime() - t0);
					AiCoreSmokeTest.put(o, "text", response.getCandidates().isEmpty()
							? "" : response.getCandidates().get(0).getText());
				} catch (ExecutionException e) {
					AiCoreSmokeTest.put(o, "latency_ms", SystemClock.elapsedRealtime() - t0);
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					AiCoreSmokeTest.describe(o, cause);
					waitMs = 5000;
					if (cause instanceof GenAiException && ((GenAiException) cause).getRetryDelay() != null) {
						waitMs = Math.max(waitMs, ((GenAiException) cause).getRetryDelay().toMillis());
					}
				}
				emit(o);
				Thread.sleep(waitMs);
			}
		}

		JSONObject done = new JSONObject();
		AiCoreSmokeTest.put(done, "event", "done");
		emit(done);
	}

	private static GenerateContentRequest request( JSONObject spec ) {
		TextPart prompt = new TextPart(spec.optString("prompt"));
		String system = spec.optString("system", "");
		GenerateContentRequest.Builder b = system.isEmpty()
				? new GenerateContentRequest.Builder(prompt)
				: new GenerateContentRequest.Builder(new SystemInstruction(system), prompt);
		b.setTemperature((float) spec.optDouble("temperature", 0.2));
		b.setTopK(spec.optInt("top_k", 10));
		b.setMaxOutputTokens(spec.optInt("max_tokens", 16));
		b.setEnableThinking(spec.optBoolean("thinking", false));
		//"tool_actions": ["explore", ...] offers one function, act(reason, action, item, target),
		// whose action argument is limited to these values
		JSONArray actions = spec.optJSONArray("tool_actions");
		if (actions != null) {
			List<String> values = new ArrayList<>();
			for (int i = 0; i < actions.length(); i++) values.add(actions.optString(i));
			Map<String, FunctionSchema> properties = new LinkedHashMap<>();
			properties.put("reason", new FunctionSchema(FunctionType.STRING, "one short sentence: why this action"));
			properties.put("action", new FunctionSchema(FunctionType.STRING, "the action to take",
					new LinkedHashMap<String, FunctionSchema>(), new ArrayList<String>(), values));
			properties.put("item", new FunctionSchema(FunctionType.INTEGER,
					"inventory or floor item number, for use, equip, throw and loot"));
			properties.put("target", new FunctionSchema(FunctionType.INTEGER,
					"inventory item number a scroll of upgrade is read on"));
			List<String> required = new ArrayList<>();
			required.add("reason");
			required.add("action");
			List<FunctionDeclaration> tools = new ArrayList<>();
			tools.add(new FunctionDeclaration("act", "Take one action in the game.",
					new FunctionSchema(FunctionType.OBJECT, "the action", properties, required)));
			b.setTools(tools);
		}
		return b.build();
	}

	private static String read( File file ) throws IOException {
		byte[] bytes = new byte[(int) file.length()];
		try (FileInputStream s = new FileInputStream(file)) {
			int read = 0;
			while (read < bytes.length) {
				int r = s.read(bytes, read, bytes.length - read);
				if (r < 0) break;
				read += r;
			}
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private synchronized void emit( JSONObject o ) {
		AiCoreSmokeTest.put(o, "ts", System.currentTimeMillis());
		String line = o.toString();
		Log.i(AiCoreSmokeTest.TAG, line);
		try (FileWriter w = new FileWriter(out, true)) {
			w.write(line);
			w.write('\n');
		} catch (IOException e) {
			Log.w(AiCoreSmokeTest.TAG, "could not write " + out, e);
		}
	}

}
