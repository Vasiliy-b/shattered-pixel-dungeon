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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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

	private static final int PIRANHA_RANGE = 6;
	private static final int MAX_LOOT_WALK = 12;

	//explore targets that the walk could not make progress towards, as depth * 100000 + cell
	static final Set<Integer> deadEnds = new HashSet<>();
	static int lastFrontier = -1;

	//the action a hard rule chose in the last render(), null when the choice was left to the model
	static String ruleAction;

	//call on the render thread, only when inputReady(). banned holds the actions the Agent loop has
	//ruled out for this turn, why is its reason when the ban is new (or null).
	public static String render( List<String> history, Set<String> banned, String why, List<String> log ) {
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

		//repeats are folded: a word said three times in a row gets echoed by a small model
		List<String> recent = new ArrayList<>();
		int repeats = 1;
		for (int i = 0; i < history.size(); i++) {
			if (i + 1 < history.size() && history.get( i ).equals( history.get( i + 1 ) )) {
				repeats++;
			} else {
				recent.add( history.get( i ) + (repeats > 1 ? " x" + repeats : "") );
				repeats = 1;
			}
		}
		if (!recent.isEmpty()) sb.append( "recent: " ).append( String.join( " / ", recent ) ).append( '\n' );
		if (!log.isEmpty()) sb.append( "log: " ).append( String.join( " / ", log ) ).append( '\n' );

		//Lines go from least to most urgent (leave, explore, loot, fight): a small model tends to follow the last "-> action"
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

		int exit = stairsDown( level );
		sb.append( "exits: " ).append( exit == -1 ? "stairs down not found yet"
				: "stairs down known, " + where( level, hero.pos, exit, steps )
				+ (banned.contains( "descend" ) ? "" : " -> descend") ).append( '\n' );

		List<Integer> frontiers = nearestFrontiers( level, hero.pos );
		if (frontiers.isEmpty()) {
			//no action word in a line that offers no action (the model was seen answering "explore" to it)
			sb.append( "unexplored: none\n" );
		} else {
			List<String> places = new ArrayList<>();
			for (int cell : frontiers) places.add( where( level, hero.pos, cell, steps ) );
			sb.append( "unexplored: " ).append( String.join( "; ", places ) )
					.append( banned.contains( "explore" ) ? "\n" : " -> explore\n" );
		}

		List<String> items = new ArrayList<>();
		List<Heap> heaps = loot( level, steps );
		for (Heap heap : heaps) {
			items.add( heap.title() + ", " + where( level, hero.pos, heap.pos, steps ) );
		}
		sb.append( "items: " ).append( items.isEmpty() ? "none" : String.join( "; ", items )
				+ (banned.contains( "loot" ) ? "" : " -> loot") ).append( '\n' );

		boolean adjacent = nearest != null && level.adjacent( hero.pos, nearest.pos );
		//a ban never stops the hero from hitting back
		boolean canAttack = nearest != null && !avoidFight && (adjacent || !banned.contains( "attack" ));
		boolean here = !heaps.isEmpty() && heaps.get( 0 ).pos == hero.pos;
		List<String> enemies = new ArrayList<>(), others = new ArrayList<>();
		for (Mob mob : visibleMobs( level )) {
			String line = mob.name() + " hp " + mob.HP + "/" + mob.HT + ", " + where( level, hero.pos, mob.pos, steps );
			if (mob.alignment == Char.Alignment.ENEMY) {
				enemies.add( line + (!threat( level, hero.pos, mob ) ? " (not hunting you)" : canAttack ? " -> attack" : "") );
			}
			else others.add( line + (mob.alignment == Char.Alignment.ALLY ? " (ally)" : " (neutral)") );
		}
		int visibleEnemies = enemies.size();
		if (!others.isEmpty()) sb.append( "others: " ).append( String.join( "; ", others ) ).append( '\n' );
		sb.append( "enemies: " ).append( enemies.isEmpty() ? "none" : String.join( "; ", enemies ) ).append( '\n' );

		if (window != null) {
			sb.append( "window open: " ).append( window.getClass().getSimpleName() ).append( " -> back\n" );
		}

		//triggered hints sit in the observation itself: small models tend to ignore static advice
		List<String> hints = new ArrayList<>();
		Hunger hunger = hero.buff( Hunger.class );
		//resting heals slowly and costs food, so only while not hungry
		boolean rest = hero.HP * 2 < hero.HT && visibleEnemies == 0
				&& (hunger == null || hunger.hunger() < Hunger.HUNGRY) && !banned.contains( "wait" );
		boolean hungry = hunger != null && hunger.hunger() >= Hunger.HUNGRY;
		boolean canEat = hungry && hero.belongings.getItem( Food.class ) != null && !banned.contains( "eat" );
		if (hunger != null && hunger.isStarving()) hints.add( "starving, HP is dropping" );
		else if (hungry) hints.add( "hungry" );
		for (String hint : hints) sb.append( "hint: " ).append( hint ).append( '\n' );

		boolean canHeal = lowHp && hero.belongings.getItem( PotionOfHealing.class ) != null
				&& healing( hero.belongings.getItem( PotionOfHealing.class ) ) && !banned.contains( "heal" );
		//a potion lying within two steps is worth a hit on the way
		boolean grabPotion = lowHp && !canHeal && !heaps.isEmpty() && !banned.contains( "loot" )
				&& heaps.get( 0 ).type == Heap.Type.HEAP && healing( heaps.get( 0 ).peek() ) && steps[heaps.get( 0 ).pos] <= 2;

		String reason = null;
		if (window != null) reason = null;
		else if (canHeal) reason = "HP is low and you have a healing potion";
		else if (grabPotion) reason = "HP is low and a healing potion is close";
		else if (avoidFight) reason = "HP is low, stay away from the enemy";
		else if (canEat && visibleEnemies == 0) reason = "you are hungry and have food";
		else if (rest) reason = "HP is low and no enemy is in sight, rest";
		else if (here && !adjacent && !banned.contains( "loot" )) reason = "an item is under your feet";
		else if (why != null) reason = why;
		else if (frontiers.isEmpty() && exit == -1 && !canAttack && heaps.isEmpty()) reason = "nothing left to explore, a door may be hidden";
		else if (frontiers.isEmpty() && exit != -1 && !canAttack && heaps.isEmpty()) reason = "level explored";

		ruleAction = null;
		if (reason != null) {
			if (canHeal) ruleAction = "heal";
			else if (grabPotion) ruleAction = "loot";
			else if (here && !adjacent && !banned.contains( "loot" )) ruleAction = "loot";
			else if (canEat && visibleEnemies == 0) ruleAction = "eat";
			else if (rest) ruleAction = "wait";
			else if (canAttack && adjacent) ruleAction = "attack";
			else if (lowHp && exit != -1 && !banned.contains( "descend" )) ruleAction = "descend";
			else if (!heaps.isEmpty() && !banned.contains( "loot" )) ruleAction = "loot";
			else if (!frontiers.isEmpty() && !banned.contains( "explore" )) ruleAction = "explore";
			else if (exit != -1 && !banned.contains( "descend" )) ruleAction = "descend";
			else if (!banned.contains( "search" ) && !avoidFight) ruleAction = "search";
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

	//worth a fight now: next to the hero, or hunting it. Walking up to a sleeping or wandering mob
	//loses sight of it and the walk back and forth never ends. Piranhas cannot leave the water.
	static boolean threat( Level level, int from, Mob mob ) {
		if (level.adjacent( from, mob.pos )) return true;
		return mob.state == mob.HUNTING && !(mob instanceof Piranha);
	}

	//nearest visible hostile mob that is a threat, or null
	static Mob nearestEnemy( Level level, int from ) {
		Mob best = null;
		for (Mob mob : visibleMobs( level )) {
			if (mob.alignment != Char.Alignment.ENEMY || !threat( level, from, mob )) continue;
			if (best == null || level.distance( from, mob.pos ) < level.distance( from, best.pos )) best = mob;
		}
		return best;
	}

	//seen heaps the hero can walk to and take or open, nearest first, at most MAX_ITEMS
	static List<Heap> loot( Level level, int[] steps ) {
		List<Heap> heaps = new ArrayList<>();
		for (Heap heap : level.heaps.valueList()) {
			//a long walk for an ordinary item costs more food than it is worth
			if (heap.seen && steps[heap.pos] <= (rank( heap, steps ) == 2 ? MAX_LOOT_WALK : Integer.MAX_VALUE - 1)
					&& (heap.type == Heap.Type.HEAP || heap.type == Heap.Type.CHEST)) heaps.add( heap );
		}
		//what the hero stands on, then what keeps it alive, then the rest; nearest first within each
		heaps.sort( (a, b) -> rank( a, steps ) != rank( b, steps ) ? rank( a, steps ) - rank( b, steps )
				: steps[a.pos] - steps[b.pos] );
		return heaps.subList( 0, Math.min( MAX_ITEMS, heaps.size() ) );
	}

	private static int rank( Heap heap, int[] steps ) {
		if (steps[heap.pos] == 0) return 0;
		Item top = heap.type == Heap.Type.HEAP ? heap.peek() : null;
		return top instanceof Food || healing( top ) ? 1 : 2;
	}

	//a healing potion the hero would recognise
	static boolean healing( Item item ) {
		return item instanceof PotionOfHealing && ((PotionOfHealing) item).isKnown();
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
		//keep out of water a piranha can reach, whatever lies in it
		for (Mob mob : level.mobs) {
			if (!(mob instanceof Piranha)) continue;
			for (int i = 0; i < walkable.length; i++) {
				if (level.water[i] && level.distance( i, mob.pos ) <= PIRANHA_RANGE) walkable[i] = false;
			}
		}
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
			if (deadEnds.contains( Dungeon.depth * 100000 + i )) continue;
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
