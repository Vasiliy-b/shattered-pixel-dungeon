/*
 * Shattered Pixel Dungeon AI agent bridge
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.ai;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

//Desktop test brain: a local Ollama server (-Dspd.agent=ollama, -Dspd.agent.ollama.model=gemma4:e2b).
//Lets a real model of the target family play on the development machine.
public class OllamaBrain implements Agent.Brain {

	private final String url = System.getProperty( "spd.agent.ollama.url", "http://localhost:11434/api/chat" );
	private final String model = System.getProperty( "spd.agent.ollama.model", "gemma4:e2b" );
	//-1 leaves the answer length to the model
	private final int maxTokens = Integer.getInteger( "spd.agent.ollama.tokens", -1 );

	@Override
	public void decide( String observation, Agent.Reply reply ) {
		Thread call = new Thread( () -> {
			try {
				String body = "{\"model\":" + Agent.quote( model )
						+ ",\"stream\":false,\"think\":false"
						+ ",\"options\":{\"temperature\":0.2,\"num_predict\":" + maxTokens + "}"
						+ ",\"messages\":[{\"role\":\"system\",\"content\":" + Agent.quote( Agent.rules() ) + "}"
						+ ",{\"role\":\"user\",\"content\":" + Agent.quote( observation ) + "}]}";

				HttpURLConnection http = (HttpURLConnection) new URL( url ).openConnection();
				http.setRequestMethod( "POST" );
				http.setRequestProperty( "Content-Type", "application/json" );
				http.setConnectTimeout( 5000 );
				http.setReadTimeout( 120000 );
				http.setDoOutput( true );
				try (OutputStream out = http.getOutputStream()) {
					out.write( body.getBytes( StandardCharsets.UTF_8 ) );
				}
				if (http.getResponseCode() != 200) {
					reply.fail( "HTTP_" + http.getResponseCode() );
					return;
				}
				JsonValue json;
				try (InputStream in = http.getInputStream()) {
					json = new JsonReader().parse( in );
				}
				String text = json.get( "message" ).getString( "content", "" );
				System.out.println( "=== AGENT TURN ===\n" + observation + "\n--- reply ---\n" + text );
				reply.accept( text );
			} catch (Exception e) {
				reply.fail( e.getClass().getSimpleName() );
			}
		} );
		call.setDaemon( true );
		call.start();
	}
}
