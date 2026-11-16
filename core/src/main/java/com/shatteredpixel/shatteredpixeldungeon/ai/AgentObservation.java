/*
 * Shattered Pixel Dungeon AI agent bridge
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Observation rules (player-visible information only, snapshot taken on the
 * render thread while the hero waits for input) follow the approach of
 * meren314/shattered-pixel-dungeon-ai (GPL-3).
 */

package com.shatteredpixel.shatteredpixeldungeon.ai;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

//Text observation of one turn: targets come as ready commands ("rat, 2 steps northeast -> attack").
//With Agent.allowTap they also carry (dx,dy) relative to the hero, x grows right, y grows down.
public class AgentObservation {

	private static final int MAX_FRONTIERS = 3;
	private static final int MIN_FRONTIER_GAP = 4;
	//max heaps listed, nearest first
	private static final int MAX_ITEMS = 4;

	//true when the game waits for player input, so a snapshot is consistent and an action can be applied
	public static boolean inputReady() {
		Hero hero = Dungeon.hero;
		return Game.scene() instanceof GameScene
				&& !Game.switchingScene()
				&& Dungeon.level != null
				&& hero != null
				&& hero.isAlive()
				&& hero.ready
				&& hero.curAction == null;
	}

	//the action a hard rule chose in the last render(), null when the choice was left to the model
	static String ruleAction;

	//call on the render thread, only when inputReady(). stale is the action that just changed
	//nothing (or null), pacing is true when the hero walks in circles; both come from the Agent loop.
	public static String render( List<String> history, String stale, boolean pacing, List<String> log ) {
		Level level = Dungeon.level;
		Hero hero = Dungeon.hero;
		int[] steps = stepsFrom( level, hero.pos );
		StringBuilder sb = new StringBuilder();

		sb.append( "depth " ).append( Dungeon.depth )
				.append( " | HP " ).append( hero.HP ).append( '/' ).append( hero.HT )
				.append( " | level " ).append( hero.lvl )
				.append( " | str " ).append( hero.STR() )
				.append( " | gold " ).append( Dungeon.gold ).append( '\n' );

		List<String> status = new ArrayList<>();
		for (Buff buff : hero.buffs()) {
			if (buff.icon() != BuffIndicator.NONE) status.add( buff.name() );
		}
		if (!status.isEmpty()) sb.append( "status: " ).append( String.join( ", ", status ) ).append( '\n' );

		if (!history.isEmpty()) sb.append( "recent: " ).append( String.join( " / ", history ) ).append( '\n' );
		if (!log.isEmpty()) sb.append( "log: " ).append( String.join( " / ", log ) ).append( '\n' );

		//Lines go from least to most urgent: a small model tends to follow the last "-> action"
		//it reads (measured on Gemini Nano 4: 10/25 correct in the reverse order, 25/25 in this one).
		if (Agent.showMap) {
			int w = level.width(), hx = hero.pos % w, hy = hero.pos / w, r = Agent.mapRadius;
			char[] glyphs = glyphs( level, hero.pos );
			sb.append( "map:\n" );
			for (int y = hy - r; y <= hy + r; y++) {
				for (int x = hx - r; x <= hx + r; x++) {
					sb.append( x < 0 || y < 0 || x >= w || y >= level.height() ? '?' : glyphs[x + y * w] );
				}
				sb.append( '\n' );
			}
		}

		//Hard rules are settled here, not by the model: measured on Gemini Nano 4, a one-word reply
		//just repeats the last "-> action" in the text. A banned action loses its arrow and the
		//rule's own choice goes last.
		Mob nearest = nearestEnemy( level, hero.pos );
		boolean lowHp = hero.HP * 3 <= hero.HT;
		boolean avoidFight = lowHp && nearest != null && !level.adjacent( hero.pos, nearest.pos );
		Window window = GameScene.topWindow();

		List<Integer> frontiers = nearestFrontiers( level, hero.pos );
		if (frontiers.isEmpty()) {
			sb.append( "explore: nothing left\n" );
		} else {
			List<String> places = new ArrayList<>();
			for (int cell : frontiers) places.add( where( level, hero.pos, cell, steps ) );
			sb.append( "explore: unexplored at " ).append( String.join( "; ", places ) )
					.append( "explore".equals( stale ) ? "\n" : " -> explore\n" );
		}

		int exit = stairsDown( level );
		sb.append( "exits: " ).append( exit == -1 ? "stairs down not found yet"
				: "stairs down known, " + where( level, hero.pos, exit, steps )
				+ ("descend".equals( stale ) ? "" : " -> descend") ).append( '\n' );

		List<String> items = new ArrayList<>();
		List<Heap> heaps = loot( level, steps );
		for (Heap heap : heaps) {
			items.add( heap.title() + ", " + where( level, hero.pos, heap.pos, steps ) );
		}
		sb.append( "items: " ).append( items.isEmpty() ? "none" : String.join( "; ", items )
				+ ("loot".equals( stale ) ? "" : " -> loot") ).append( '\n' );

		boolean canAttack = nearest != null && !avoidFight && !"attack".equals( stale );
		List<String> enemies = new ArrayList<>(), others = new ArrayList<>();
		for (Mob mob : visibleMobs( level )) {
			String line = mob.name() + " hp " + mob.HP + "/" + mob.HT + ", " + where( level, hero.pos, mob.pos, steps );
			if (mob.alignment == Char.Alignment.ENEMY) enemies.add( line + (canAttack ? " -> attack" : "") );
			else others.add( line + (mob.alignment == Char.Alignment.ALLY ? " (ally)" : " (neutral)") );
		}
		if (!others.isEmpty()) sb.append( "others: " ).append( String.join( "; ", others ) ).append( '\n' );
		sb.append( "enemies: " ).append( enemies.isEmpty() ? "none" : String.join( "; ", enemies ) ).append( '\n' );

		if (window != null) {
			sb.append( "window open: " ).append( window.getClass().getSimpleName() ).append( " -> back\n" );
		}

		//triggered hints sit in the observation itself: small models tend to ignore static advice
		List<String> hints = new ArrayList<>();
		Hunger hunger = hero.buff( Hunger.class );
		if (hunger != null && hunger.isStarving()) hints.add( "starving, HP is dropping" );
		else if (hunger != null && hunger.hunger() >= Hunger.HUNGRY) hints.add( "hungry" );
		for (String hint : hints) sb.append( "hint: " ).append( hint ).append( '\n' );

		String reason = null;
		if (window != null) reason = null;
		else if (avoidFight) reason = "HP is low, stay away from the enemy";
		else if (stale != null) reason = stale + " changed nothing, do something else";
		else if (pacing) reason = "you keep moving back and forth, do something else";
		else if (frontiers.isEmpty() && exit == -1 && !canAttack && heaps.isEmpty()) reason = "nothing left to explore, a door may be hidden";
		else if (frontiers.isEmpty() && exit != -1 && !canAttack && heaps.isEmpty()) reason = "level explored";

		ruleAction = null;
		if (reason != null) {
			boolean adjacent = nearest != null && level.adjacent( hero.pos, nearest.pos );
			if (canAttack && adjacent) ruleAction = "attack";
			else if (lowHp && exit != -1 && !"descend".equals( stale )) ruleAction = "descend";
			else if (!heaps.isEmpty() && !"loot".equals( stale )) ruleAction = "loot";
			else if (!frontiers.isEmpty() && !"explore".equals( stale ) && !pacing) ruleAction = "explore";
			else if (exit != -1 && !"descend".equals( stale )) ruleAction = "descend";
			else if (!"search".equals( stale ) && !avoidFight) ruleAction = "search";
			else ruleAction = "wait";
			sb.append( "hint: " ).append( reason ).append( " -> " ).append( ruleAction ).append( '\n' );
		}

		sb.append( "\nYour action:" );
		return sb.toString();
	}

	static List<Mob> visibleMobs( Level level ) {
		List<Mob> mobs = new ArrayList<>();
		for (Mob mob : level.mobs) {
			if (level.heroFOV[mob.pos] && mob.invisible <= 0) mobs.add( mob );
		}
		return mobs;
	}

	//nearest visible hostile mob, or null
	static Mob nearestEnemy( Level level, int from ) {
		Mob best = null;
		for (Mob mob : visibleMobs( level )) {
			if (mob.alignment != Char.Alignment.ENEMY) continue;
			if (best == null || level.distance( from, mob.pos ) < level.distance( from, best.pos )) best = mob;
		}
		return best;
	}

	//seen heaps the hero can walk to and take or open, nearest first, at most MAX_ITEMS
	static List<Heap> loot( Level level, int[] steps ) {
		List<Heap> heaps = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			if (heap.seen && steps[heap.pos] != Integer.MAX_VALUE
					&& (heap.type == Heap.Type.HEAP || heap.type == Heap.Type.CHEST)) heaps.add( heap );
		}
		heaps.sort( (a, b) -> steps[a.pos] - steps[b.pos] );
		return heaps.subList( 0, Math.min( MAX_ITEMS, heaps.size() ) );
	}

	//cell of the known regular stairs down, or -1
	static int stairsDown( Level level ) {
		for (LevelTransition t : level.transitions) {
			if (t.type == LevelTransition.Type.REGULAR_EXIT && known( level, t.cell() )) return t.cell();
		}
		return -1;
	}

	//"adjacent" or "N steps <direction>", plus (dx,dy) when taps are allowed
	private static String where( Level level, int from, int to, int[] steps ) {
		int w = level.width();
		int dx = to % w - from % w, dy = to / w - from / w;
		String out;
		if (to == from) {
			out = "here";
		} else if (level.adjacent( from, to )) {
			out = "adjacent " + direction( dx, dy );
		} else {
			//path length when a known path exists, straight-line distance otherwise
			int n = steps[to] != Integer.MAX_VALUE ? steps[to] : level.distance( from, to );
			out = n + " steps " + direction( dx, dy );
		}
		return Agent.allowTap ? out + String.format( Locale.ROOT, " (%+d,%+d)", dx, dy ) : out;
	}

	//8-way compass word; y grows down, so negative dy is north
	static String direction( int dx, int dy ) {
		boolean ns = Math.abs( dy ) * 2 > Math.abs( dx ), ew = Math.abs( dx ) * 2 > Math.abs( dy );
		return (ns ? (dy < 0 ? "north" : "south") : "") + (ew ? (dx > 0 ? "east" : "west") : "");
	}

	private static boolean known( Level level, int cell ) {
		return level.visited[cell] || level.mapped[cell] || level.heroFOV[cell];
	}

	private static boolean[] walkable( Level level, int from ) {
		boolean[] walkable = new boolean[level.length()];
		for (int i = 0; i < walkable.length; i++) walkable[i] = known( level, i ) && level.passable[i];
		walkable[from] = true;
		return walkable;
	}

	//walking distance over known passable cells, Integer.MAX_VALUE when unreachable
	static int[] stepsFrom( Level level, int from ) {
		PathFinder.buildDistanceMap( from, walkable( level, from ) );
		return PathFinder.distance.clone();
	}

	//up to MAX_FRONTIERS reachable known cells that border unknown territory, nearest first,
	//kept apart from each other so they are real alternatives when one path turns out blocked
	static List<Integer> nearestFrontiers( Level level, int from ) {
		boolean[] walkable = walkable( level, from );
		final int[] distance = stepsFrom( level, from );

		List<Integer> candidates = new ArrayList<>();
		for (int i = 0; i < walkable.length; i++) {
			if (!walkable[i] || i == from || distance[i] == Integer.MAX_VALUE) continue;
			for (int n : PathFinder.NEIGHBOURS4) {
				int c = i + n;
				if (c >= 0 && c < walkable.length && !known( level, c )) {
					candidates.add( i );
					break;
				}
			}
		}
		candidates.sort( (a, b) -> distance[a] - distance[b] );

		List<Integer> picked = new ArrayList<>();
		for (int cell : candidates) {
			boolean apart = true;
			for (int other : picked) apart &= level.distance( cell, other ) >= MIN_FRONTIER_GAP;
			if (apart) picked.add( cell );
			if (picked.size() == MAX_FRONTIERS) break;
		}
		return picked;
	}

	//one char per cell: ? unknown, # blocked, . floor, + door, / open door, L locked door,
	//> stairs down, < stairs up, ~ water, " grass, ^ trap, _ chasm, * item, E enemy, N other creature, @ hero
	private static char[] glyphs( Level level, int heroPos ) {
		char[] out = new char[level.length()];
		for (int i = 0; i < out.length; i++) {
			if (!known( level, i )) {
				out[i] = '?';
				continue;
			}
			switch (level.map[i]) {
				case Terrain.DOOR:              out[i] = '+'; break;
				case Terrain.OPEN_DOOR:         out[i] = '/'; break;
				case Terrain.LOCKED_DOOR:
				case Terrain.CRYSTAL_DOOR:
				case Terrain.HERO_LKD_DR:
				case Terrain.LOCKED_EXIT:       out[i] = 'L'; break;
				case Terrain.EXIT:
				case Terrain.UNLOCKED_EXIT:     out[i] = '>'; break;
				case Terrain.ENTRANCE:
				case Terrain.ENTRANCE_SP:       out[i] = '<'; break;
				case Terrain.WATER:             out[i] = '~'; break;
				case Terrain.GRASS:
				case Terrain.HIGH_GRASS:
				case Terrain.FURROWED_GRASS:    out[i] = '"'; break;
				case Terrain.TRAP:              out[i] = '^'; break;
				case Terrain.CHASM:             out[i] = '_'; break;
				//secret doors and traps look like wall and floor until found
				case Terrain.SECRET_DOOR:       out[i] = '#'; break;
				case Terrain.SECRET_TRAP:       out[i] = '.'; break;
				default:                        out[i] = level.passable[i] ? '.' : '#';
			}
		}
		for (Heap heap : level.heaps.valueList()) if (heap.seen) out[heap.pos] = '*';
		for (Mob mob : visibleMobs( level )) out[mob.pos] = mob.alignment == Char.Alignment.ENEMY ? 'E' : 'N';
		out[heroPos] = '@';
		return out;
	}
}
