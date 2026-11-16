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

	//-Dspd.agent.script="use 3;equip 4;throw 5": actions played first, one per turn, to exercise the executors
	private final java.util.ArrayDeque<String> script = new java.util.ArrayDeque<>();
	{
		for (String step : System.getProperty( "spd.agent.script", "" ).split( ";" )) {
			if (!step.trim().isEmpty()) script.add( step.trim().replace( '_', ' ' ) );
		}
	}

	@Override
	public void decide( String observation, Agent.Reply reply ) {
		String action = "wait";
		int allowed = observation.lastIndexOf( "Allowed now: " );
		//what a small model was measured to do: repeat the last offered "-> action"
		java.util.regex.Matcher offered = java.util.regex.Pattern.compile( "-> ([a-z]+)\\n" ).matcher( observation );
		String last = null;
		while (offered.find()) last = offered.group( 1 );
		if (!script.isEmpty() && !observation.contains( "window open" )) {
			action = script.poll();
		} else if (allowed != -1) {
			//model mode has no arrows: take the first allowed action
			String list = observation.substring( allowed + 13, observation.indexOf( '\n', allowed ) );
			action = list.contains( "talent N" ) ? "talent 1" : list.split( ", " )[0].replace( " N", " 1" );
		} else if (last != null) {
			action = last;
		}
		System.out.println( "=== AGENT TURN ===\n" + observation + " " + action );
		reply.accept( action );
	}
}
