/*
 * Shattered Pixel Dungeon AI agent bridge
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.ai;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

//Turn loop: when the game waits for input, hands a text observation to the brain and applies its reply.
//Off unless a brain is set. Everything here runs on the render thread except Brain's own work.
public class Agent {

	public interface Brain {
		//Called on the render thread, must not block: run inference elsewhere and
		//answer through reply exactly once, from any thread.
		void decide( String observation, Reply reply );
	}

	public interface Reply {
		//the model's text; the last line that is a valid action is used
		void accept( String text );
		//inference failed (for example "BUSY"); the loop logs the code and plays a safe action
		void fail( String errorCode );
	}

	public static Brain brain = "scripted".equals( System.getProperty( "spd.agent" ) ) ? new ScriptedBrain()
			: "ollama".equals( System.getProperty( "spd.agent" ) ) ? new OllamaBrain() : null;

	//A/B switches, off by default: macro actions only and no map work better for small models
	public static boolean allowTap = Boolean.getBoolean( "spd.agent.tap" );
	public static boolean showMap = Boolean.getBoolean( "spd.agent.map" );
	//half-size of the square map window around the hero: 5 gives 11x11
	public static int mapRadius = 5;

	private static final int MAX_LOG = 6;
	private static final int MAX_HISTORY = 3;
	private static final int POSITIONS = 10;
	//brain calls in a row that did not advance game time before the loop takes over
	private static final int MAX_IDLE = 10;

	private static final List<String> log = new ArrayList<>();
	private static final List<String> history = new ArrayList<>();
	private static final List<Integer> positions = new ArrayList<>();
	private static boolean waiting;
	private static boolean deathLogged;
	private static String lastClock;
	//the last action and the last one before it that was different
	private static String lastAction, prevAction;
	//action -> the turn its ban ends
	private static final Map<String, Integer> bans = new HashMap<>();
	private static final int PACING_BAN = 5;
	private static int missedFrontier = -1;
	//the last action was refused by the game ("error: ...") rather than played without effect
	private static boolean lastFailed;
	//the last reply was not an action and nothing was played (model mode)
	private static boolean lastInvalid;
	//replies in a row that were not actions or that the game refused; model mode stops the run at the limit
	private static int strikes;
	private static final int MAX_STRIKES = 20;
	private static boolean stopped;

	//model mode switches, both on by default:
	//nudge - the note that the last action did nothing or is not possible, or that the hero paces
	public static boolean nudge = !"false".equals( System.getProperty( "spd.agent.nudge" ) );
	//notes - derived facts such as "HP is low", "hungry", "you stand in a doorway"
	public static boolean notes = !"false".equals( System.getProperty( "spd.agent.notes" ) );
	private static int idle;
	private static int turn;

	//"rules": the harness settles hard cases itself and marks its choice with "-> action" (the baseline).
	//"model": the observation is facts only and every decision is the model's, reasoning included.
	public static String mode = System.getProperty( "spd.agent.mode", "rules" );
	static boolean modelMode() {
		return "model".equals( mode );
	}

	//play advice for model mode; kept to what the action set can carry out
	public static String guide =
			"- Put every scroll of upgrade into the same one or two items: armor first, then the weapon.\n" +
			"- Drink a healing potion before HP gets critical. Eat when hungry.\n" +
			"- Unknown potions and scrolls are identified by using them: do it with high HP and no enemy near.\n" +
			"- Fight in doorways and corridors so only one enemy reaches you.\n" +
			"- An enemy that has not noticed you or is still far: wait for it to come, then the first hit is yours. " +
			"Snakes dodge most hits unless surprised.\n" +
			"- Explore the whole floor and pick up items before going down. Rest when HP is low and no enemy is near.\n" +
			"- Equip better weapons and armor you find if your strength allows.\n";

	//static part of the prompt: keep it as an unchanging prefix so the model's KV cache is reused
	public static String rules() {
		if (modelMode()) {
			return "You play the roguelike Shattered Pixel Dungeon as a warrior. Goal: survive and go as deep as you can.\n" +
					"Each turn you get the game state. Think in a few short lines, then write one action alone on the last line.\n" +
					"Actions:\n" +
					"explore - walk to the nearest unexplored place\n" +
					"attack - fight the nearest enemy\n" +
					"loot N - walk to item number N on the floor and pick it up\n" +
					"descend - go to the stairs down and take them\n" +
					"use N - eat, drink or read inventory item number N\n" +
					"use N on M - read scroll of upgrade N on item M\n" +
					"equip N - equip inventory item number N\n" +
					"throw N - throw inventory item number N at the nearest enemy\n" +
					"talent N - spend a talent point on talent number N\n" +
					"rest - rest until HP recovers or something happens\n" +
					"wait - skip a turn\n" +
					"search - walk along the walls looking for hidden doors\n" +
					"back - close the open window\n" +
					"Advice:\n" + guide;
		}
		return "You play the roguelike Shattered Pixel Dungeon. Goal: survive and go as deep as you can.\n" +
				"Each turn you get the game state. Lines ending with \"-> action\" show what that action would do; a hint line overrides the others.\n" +
				"Reply with exactly one action and nothing else:\n" +
				"explore - walk to the nearest unexplored place\n" +
				"attack - fight the nearest enemy\n" +
				"loot - walk to the nearest item and pick it up\n" +
				"descend - go to the stairs down and take them\n" +
				"eat - eat food from the backpack\n" +
				"heal - drink a healing potion\n" +
				"rest - rest until HP recovers or something happens\n" +
				"wait - skip a turn\n" +
				"search - walk along the walls looking for hidden doors\n" +
				"back - close the open window\n" +
				(allowTap ? "tap DX DY - act on a cell like a screen tap; (DX,DY) is relative to you, " +
						"DX grows to the right, DY grows down\n" : "") +
				(showMap ? "Map: @ you, # wall, . floor, + door, / open door, L locked door, > stairs down, " +
						"< stairs up, ~ water, \" grass, ^ trap, _ chasm, * item, E enemy, N other creature, ? unknown.\n" : "");
	}

	//hooked into GLog.i(), collects what the player would read in the game log since the last observation
	public static synchronized void onLog( String text ) {
		if (brain == null || text.equals( GLog.NEW_LINE )) return;
		log.add( text.replaceFirst( "^(\\+\\+|--|\\*\\*|@@) ", "" ) );
		if (log.size() > MAX_LOG) log.remove( 0 );
	}

	//hooked into GameScene.update()
	public static void tick() {
		if (brain == null || waiting || stopped) return;
		if (Dungeon.hero != null && Dungeon.hero.resting && AgentObservation.restedEnough( Dungeon.hero )) {
			Dungeon.hero.resting = false;
		}

		if (Dungeon.hero != null && !Dungeon.hero.isAlive()) {
			if (!deathLogged) journal( "\"event\":\"dead\"" );
			deathLogged = true;
			//the next run has new levels
			AgentObservation.deadEnds.clear();
			AgentObservation.searched.clear();
			return;
		}
		if (!AgentObservation.inputReady()) return;
		deathLogged = false;

		//harness-side guards: the model is not trusted to notice it is stuck
		String clock = Dungeon.depth + ":" + Actor.now();
		idle = clock.equals( lastClock ) ? idle + 1 : 0;
		lastClock = clock;
		//an explore target that got the hero nowhere is dropped, so explore tries the next one
		String why = null;
		if (idle > 0 && "explore".equals( lastAction ) && !lastFailed) {
			//one miss can be a hiccup (seen right after a kill), two in a row to the same place is a dead end
			if (AgentObservation.lastFrontier == missedFrontier) {
				AgentObservation.deadEnds.add( Dungeon.depth * 100000 + missedFrontier );
			}
			missedFrontier = AgentObservation.lastFrontier;
		} else if (idle > 0 && lastAction != null && !lastInvalid) {
			if (!modelMode()) bans.put( lastAction, turn + 1 );
			why = lastAction + (lastFailed ? " is not possible now" : " changed nothing") + ", do something else";
		}

		//only moves are recorded: resting or searching in one place is not pacing
		Integer place = Dungeon.depth * 100000 + Dungeon.hero.pos;
		if (positions.isEmpty() || !place.equals( positions.get( positions.size() - 1 ) )) positions.add( place );
		if (positions.size() > POSITIONS) positions.remove( 0 );

		int places = new HashSet<>( positions ).size();
		boolean pacing = idle == 0 && positions.size() == POSITIONS && places <= 3
				&& AgentObservation.nearestEnemy( Dungeon.level, Dungeon.hero.pos ) == null;
		if (pacing) {
			//both halves of the loop are off the table for a while, then the walk gets another ten moves
			if (!modelMode()) {
				bans.put( lastAction, turn + PACING_BAN );
				if (prevAction != null) bans.put( prevAction, turn + PACING_BAN );
			}
			why = "you keep moving back and forth, do something else";
			positions.clear();
		}
		final int now = turn;
		bans.values().removeIf( until -> until <= now );

		final String observation;
		synchronized (Agent.class) {
			observation = AgentObservation.render( history, bans.keySet(), why, log );
			log.clear();
		}
		turn++;

		if (idle >= MAX_IDLE && !modelMode()) {
			//the brain keeps choosing actions that do nothing: stop paying for calls and force time forward
			finish( observation, null, "idle_limit", 0, true );
			return;
		}

		waiting = true;
		final long started = System.nanoTime();
		final AtomicBoolean answered = new AtomicBoolean();
		brain.decide( observation, new Reply() {
			@Override
			public void accept( String text ) {
				answer( text == null ? "" : text, null );
			}

			@Override
			public void fail( String errorCode ) {
				answer( null, errorCode );
			}

			private void answer( String text, String error ) {
				if (!answered.compareAndSet( false, true )) return;
				long ms = (System.nanoTime() - started) / 1000000;
				Game.runOnRenderThread( () -> {
					waiting = false;
					finish( observation, text, error, ms, false );
				} );
			}
		} );
	}

	private static void finish( String observation, String reply, String error, long latencyMs, boolean forced ) {
		String action = null, result;
		boolean valid = false;
		if (!AgentObservation.inputReady()) {
			//the game moved on while the brain was thinking
			result = "error: game was not ready";
		} else {
			action = forced || reply == null ? null : AgentAction.normalize( reply );
			valid = action != null;
			if (forced) action = GameScene.topWindow() != null ? "back" : "wait";
			else if (!valid && !modelMode()) action = AgentAction.fallback();

			lastInvalid = action == null;
			if (lastInvalid) {
				//model mode plays nothing for the model: a bad reply costs no game time and is reported back
				result = "error: not an action";
				String said = reply == null ? "" : reply.trim();
				said = said.substring( said.lastIndexOf( '\n' ) + 1 );
				if (said.length() > 60) said = said.substring( 0, 60 );
				history.add( reply == null ? "no reply (" + error + ")" : "your reply \"" + said + "\" is not an action" );
			} else {
				result = AgentAction.apply( action );
				lastFailed = result.startsWith( "error" );
				//spending a talent point takes no game time and is still progress
				if (action.startsWith( "talent" ) && !lastFailed) lastClock = null;
				if (!action.equals( lastAction )) prevAction = lastAction;
				lastAction = action;
				history.add( (valid ? action : "invalid reply, played " + action) + " -> " + result );
			}
			if (history.size() > MAX_HISTORY) history.remove( 0 );
		}

		strikes = result.startsWith( "error" ) ? strikes + 1 : 0;

		//who chose: a harness rule (its hint named the action), the model, or the fallback for a bad reply
		String decider = forced || (valid && action.equals( AgentObservation.ruleAction )) ? "rule"
				: valid ? "model" : action == null ? "none" : "fallback";
		journal( "\"turn\":" + turn
				+ ",\"depth\":" + Dungeon.depth
				+ ",\"hp\":" + Dungeon.hero.HP + ",\"ht\":" + Dungeon.hero.HT
				+ ",\"obs_chars\":" + observation.length()
				+ ",\"reply\":" + quote( reply )
				+ ",\"action\":" + quote( action )
				+ ",\"valid\":" + valid
				+ ",\"forced\":" + forced
				+ ",\"decider\":" + quote( decider )
				+ ",\"mode\":" + quote( mode )
				+ ",\"echo\":" + echo( observation, action )
				+ ",\"result\":" + quote( result )
				+ ",\"latency_ms\":" + latencyMs
				+ ",\"error\":" + quote( error )
				+ ",\"obs\":" + quote( observation ) );

		if (modelMode() && strikes >= MAX_STRIKES) {
			//the run ends here: nothing is played on the model's behalf
			journal( "\"event\":\"stuck\",\"turn\":" + turn );
			stopped = true;
		}
	}

	//Directory for journal.jsonl, one JSON object per line. The platform launcher sets it
	//(on Android: context.getFilesDir()); null turns the journal off.
	public static String journalDir = System.getProperty( "spd.agent.journal.dir" );
	private static boolean journalBroken;
	private static void journal( String fields ) {
		if (journalBroken || journalDir == null) return;
		try {
			FileHandle file = Gdx.files.absolute( journalDir ).child( "journal.jsonl" );
			file.writeString( "{\"ts\":" + System.currentTimeMillis() + "," + fields + "}\n", true, "UTF-8" );
		} catch (RuntimeException e) {
			//the journal must never take the game down with it
			journalBroken = true;
			Game.reportException( e );
		}
	}

	//the action is the last one the observation offered with an arrow: an echo, not a decision
	private static boolean echo( String observation, String action ) {
		int arrow = observation.lastIndexOf( "-> " );
		return action != null && arrow != -1
				&& observation.startsWith( action + "\n", arrow + 3 );
	}

	static String quote( String s ) {
		if (s == null) return "null";
		StringBuilder sb = new StringBuilder( "\"" );
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') sb.append( '\\' ).append( c );
			else if (c == '\n') sb.append( "\\n" );
			else if (c < 0x20) sb.append( String.format( "\\u%04x", (int) c ) );
			else sb.append( c );
		}
		return sb.append( '"' ).toString();
	}

	//hooked into WelcomeScene.create() and TitleScene.create(): with -Dspd.agent.autostart=true starts a run without touching the UI
	private static boolean autostarted;
	public static void onTitle() {
		if (brain == null) return;
		//the new-player tutorial hides the doors of the first rooms until guide pages are read
		SPDSettings.intro( false );
		//the phone layout on every platform: item prompts are windows there, which the bridge handles
		SPDSettings.interfaceSize( 0 );
		Document.ADVENTURERS_GUIDE.findPage( Document.GUIDE_SEARCHING );
		if (autostarted || !Boolean.getBoolean( "spd.agent.autostart" )) return;
		autostarted = true;
		//not right away: a scene reset (the desktop window resizes on startup) during level
		//generation leaves the loading screen stuck, so let the first scene settle
		Thread delay = new Thread( () -> {
			try { Thread.sleep( 2000 ); } catch (InterruptedException e) { return; }
			Game.runOnRenderThread( Agent::startRun );
		} );
		delay.setDaemon( true );
		delay.start();
	}

	private static void startRun() {
		int slot = GamesInProgress.firstEmpty();
		if (slot == -1) {
			//interrupted agent runs fill every slot: the last one is given up for the new run
			slot = GamesInProgress.MAX_SLOTS;
			Dungeon.deleteGame( slot, true );
		}
		//same steps as the hero select screen takes for a new game
		GamesInProgress.curSlot = slot;
		GamesInProgress.selectedClass = HeroClass.WARRIOR;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.initSeed();
		Dungeon.hero = null;
		InterlevelScene.curTransition = null;
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		Game.switchScene( InterlevelScene.class );
	}
}
