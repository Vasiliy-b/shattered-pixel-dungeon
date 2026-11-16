/*
 * Shattered Pixel Dungeon AI agent bridge
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.ai;

//Model-free baseline that plays from the observation text alone (-Dspd.agent=scripted).
//Proves the text carries enough to act on, and prints each turn for inspection.
public class ScriptedBrain implements Agent.Brain {

	@Override
	public void decide( String observation, Agent.Reply reply ) {
		String action = "wait";
		//what a small model was measured to do: repeat the last offered "-> action"
		int arrow = observation.lastIndexOf( "-> " );
		if (arrow != -1) action = observation.substring( arrow + 3, observation.indexOf( '\n', arrow ) );
		System.out.println( "=== AGENT TURN ===\n" + observation + " " + action );
		reply.accept( action );
	}
}
