/*
 * Shattered Pixel Dungeon AI agent bridge
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.ai;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Parses one model reply into an action and applies it the same way a player's input would be.
public class AgentAction {

	//the action must sit on its own line; anything else in the reply (reasoning) is ignored
	private static final Pattern LINE = Pattern.compile(
			"^(?:action:\\s*)?(?:(tap)\\s*\\(?\\s*([+-]?\\d{1,3})\\s*[,\\s]\\s*([+-]?\\d{1,3})\\s*\\)?"
					+ "|(explore|descend|attack|loot|wait|search|back))\\.?$" );

	//returns {"tap", dx, dy} or {name of a macro action}, or null when the reply has no valid action line
	static String[] parse( String reply ) {
		String[] found = null;
		if (reply == null) return null;
		for (String line : reply.split( "\n" )) {
			Matcher m = LINE.matcher( line.trim().toLowerCase() );
			if (!m.matches()) continue;
			//the last action line wins, so "reasoning first, action last" replies work
			found = m.group( 1 ) != null
					? new String[]{ "tap", m.group( 2 ), m.group( 3 ) }
					: new String[]{ m.group( 4 ) };
		}
		return found;
	}

	//normalized action text ("tap +2 -1", "explore"), or null when the reply is not a usable action
	static String normalize( String reply ) {
		String[] action = parse( reply );
		if (action == null || (action[0].equals( "tap" ) && !Agent.allowTap)) return null;
		return String.join( " ", action );
	}

	//what to do when the brain gave nothing usable; always applicable in the current state
	static String fallback() {
		Level level = Dungeon.level;
		int pos = Dungeon.hero.pos;
		Mob enemy = AgentObservation.nearestEnemy( level, pos );
		if (GameScene.topWindow() != null) return "back";
		if (enemy != null && level.adjacent( pos, enemy.pos )) return "attack";
		if (!AgentObservation.nearestFrontiers( level, pos ).isEmpty()) return "explore";
		if (AgentObservation.stairsDown( level ) != -1) return "descend";
		return "wait";
	}

	//call on the render thread, only when AgentObservation.inputReady(), with a normalize()d action.
	//Every game action goes through the same path as a screen tap. Returns "ok" or "error: ...".
	public static String apply( String normalized ) {
		String[] action = normalized.split( " " );
		Level level = Dungeon.level;
		int pos = Dungeon.hero.pos;

		Window window = GameScene.topWindow();
		if (action[0].equals( "back" )) {
			if (window != null) window.onBackPressed();
			else if (!GameScene.cancel()) return "error: nothing to close";
			return "ok";
		}
		if (window != null) return "error: a window is open, send back first";

		switch (action[0]) {
			case "wait":
				Dungeon.hero.rest( false );
				return "ok";
			case "search":
				Dungeon.hero.search( true );
				return "ok";
			case "explore":
				//the walk stops by itself when the hero notices a new enemy, like any tap-to-move
				List<Integer> frontiers = AgentObservation.nearestFrontiers( level, pos );
				if (frontiers.isEmpty()) return "error: nothing left to explore";
				GameScene.handleCell( frontiers.get( 0 ) );
				return "ok";
			case "attack":
				Mob enemy = AgentObservation.nearestEnemy( level, pos );
				if (enemy == null) return "error: no enemy in sight";
				GameScene.handleCell( enemy.pos );
				return "ok";
			case "loot":
				List<Heap> loot = AgentObservation.loot( level, AgentObservation.stepsFrom( level, pos ) );
				if (loot.isEmpty()) return "error: no item in reach";
				GameScene.handleCell( loot.get( 0 ).pos );
				return "ok";
			case "descend":
				int exit = AgentObservation.stairsDown( level );
				if (exit == -1) return "error: stairs down not found yet";
				GameScene.handleCell( exit );
				return "ok";
			default:
				int x = pos % level.width() + Integer.parseInt( action[1] );
				int y = pos / level.width() + Integer.parseInt( action[2] );
				if (x < 0 || y < 0 || x >= level.width() || y >= level.height()) return "error: target is outside the map";
				GameScene.handleCell( x + y * level.width() );
				return "ok";
		}
	}
}
