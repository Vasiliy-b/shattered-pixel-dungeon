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
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
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
					+ "|(use)\\s+(\\d{1,2})(?:\\s+on\\s+(\\d{1,2}))?"
					+ "|(equip|throw|talent)\\s+(\\d{1,2})"
					+ "|(loot)(?:\\s+(\\d{1,2}))?"
					+ "|(explore|descend|attack|eat|heal|rest|wait|search|back))\\.?$" );

	//returns the action as words ({"tap", dx, dy}, {"use", n}, {"use", n, "on", m}, {"explore"}), or null when the reply has no valid action line
	static String[] parse( String reply ) {
		String[] found = null;
		if (reply == null) return null;
		for (String line : reply.split( "\n" )) {
			//models like to decorate the answer line: **use 4**, `explore`
			Matcher m = LINE.matcher( line.replaceAll( "[*`]", "" ).trim().toLowerCase() );
			if (!m.matches()) continue;
			//the last action line wins, so "reasoning first, action last" replies work
			if (m.group( 1 ) != null) found = new String[]{ "tap", m.group( 2 ), m.group( 3 ) };
			else if (m.group( 4 ) != null && m.group( 6 ) != null) found = new String[]{ "use", m.group( 5 ), "on", m.group( 6 ) };
			else if (m.group( 4 ) != null) found = new String[]{ "use", m.group( 5 ) };
			else if (m.group( 7 ) != null) found = new String[]{ m.group( 7 ), m.group( 8 ) };
			else if (m.group( 9 ) != null && m.group( 10 ) != null) found = new String[]{ "loot", m.group( 10 ) };
			else if (m.group( 9 ) != null) found = new String[]{ "loot" };
			else found = new String[]{ m.group( 11 ) };
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
			case "eat":
				Food food = Dungeon.hero.belongings.getItem( Food.class );
				if (food == null) return "error: no food";
				food.execute( Dungeon.hero, Food.AC_EAT );
				return "ok";
			case "heal":
				//only a potion the hero knows to be healing: unidentified ones are not drunk
				PotionOfHealing potion = Dungeon.hero.belongings.getItem( PotionOfHealing.class );
				if (!AgentObservation.healing( potion )) return "error: no healing potion";
				potion.execute( Dungeon.hero, Potion.AC_DRINK );
				return "ok";
			case "rest":
				Dungeon.hero.rest( true );
				return "ok";
			case "search":
				//searching covers only the cells next to the hero, so it has to move along the walls
				int spot = AgentObservation.searchSpot( level, pos );
				if (spot == -1) return "error: every wall in reach has been searched";
				if (spot == pos) {
					AgentObservation.markSearched( level, pos );
					Dungeon.hero.search( true );
				} else {
					GameScene.handleCell( spot );
				}
				return "ok";
			case "explore":
				//the walk stops by itself when the hero notices a new enemy, like any tap-to-move
				List<Integer> frontiers = AgentObservation.nearestFrontiers( level, pos );
				if (frontiers.isEmpty()) return "error: nothing left to explore";
				AgentObservation.lastFrontier = frontiers.get( 0 );
				GameScene.handleCell( frontiers.get( 0 ) );
				return "ok";
			case "attack":
				Mob enemy = AgentObservation.nearestEnemy( level, pos );
				if (enemy == null) return "error: no enemy to fight";
				GameScene.handleCell( enemy.pos );
				return "ok";
			case "loot":
				List<Heap> loot = AgentObservation.loot( level, AgentObservation.stepsFrom( level, pos ) );
				if (loot.isEmpty()) return "error: no item in reach";
				//loot N takes the Nth item of the list the observation showed, plain loot the first
				int n = action.length > 1 ? Integer.parseInt( action[1] ) : 1;
				if (n < 1 || n > loot.size()) return "error: no item " + n + " on the floor";
				GameScene.handleCell( loot.get( n - 1 ).pos );
				return "ok";
			case "descend":
				int exit = AgentObservation.stairsDown( level );
				if (exit == -1) return "error: stairs down not found yet";
				GameScene.handleCell( exit );
				return "ok";
			case "use":
				return action.length == 4
						? AgentInventory.useOn( Integer.parseInt( action[1] ), Integer.parseInt( action[3] ) )
						: AgentInventory.use( Integer.parseInt( action[1] ) );
			case "equip":
				return AgentInventory.equip( Integer.parseInt( action[1] ) );
			case "talent":
				return AgentInventory.talent( Integer.parseInt( action[1] ) );
			case "throw":
				return AgentInventory.throwAt( Integer.parseInt( action[1] ) );
			default:
				int x = pos % level.width() + Integer.parseInt( action[1] );
				int y = pos / level.width() + Integer.parseInt( action[2] );
				if (x < 0 || y < 0 || x >= level.width() || y >= level.height()) return "error: target is outside the map";
				GameScene.handleCell( x + y * level.width() );
				return "ok";
		}
	}
}
