package com.shatteredpixel.shatteredpixeldungeon.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

public class AgentActionTest {

	@After
	public void resetFlags() {
		Agent.allowTap = false;
	}

	@Test
	public void parsesTapVariants() {
		String[] tap = { "tap", "+2", "-1" };
		assertArrayEquals( tap, AgentAction.parse( "tap +2 -1" ) );
		assertArrayEquals( tap, AgentAction.parse( "Action: TAP (+2,-1)." ) );
		assertArrayEquals( tap, AgentAction.parse( "The rat is close, I should wait or fight.\ntap +2, -1\n" ) );
		assertArrayEquals( new String[]{ "tap", "0", "0" }, AgentAction.parse( "tap 0 0" ) );
	}

	@Test
	public void lastActionLineWins() {
		assertArrayEquals( new String[]{ "search" }, AgentAction.parse( "wait\nsearch" ) );
		assertArrayEquals( new String[]{ "back" }, AgentAction.parse( " back " ) );
		assertArrayEquals( new String[]{ "explore" }, AgentAction.parse( "Explore" ) );
		assertArrayEquals( new String[]{ "descend" }, AgentAction.parse( "action: descend." ) );
		assertArrayEquals( new String[]{ "attack" }, AgentAction.parse( "attack" ) );
		assertArrayEquals( new String[]{ "loot" }, AgentAction.parse( "loot" ) );
		assertArrayEquals( new String[]{ "eat" }, AgentAction.parse( "Action: eat." ) );
		assertArrayEquals( new String[]{ "heal" }, AgentAction.parse( "heal" ) );
		assertArrayEquals( new String[]{ "rest" }, AgentAction.parse( "rest" ) );
		assertArrayEquals( new String[]{ "loot", "2" }, AgentAction.parse( "**loot 2**" ) );
		assertArrayEquals( new String[]{ "use", "4", "on", "1" }, AgentAction.parse( "I upgrade.\nuse 4 on 1" ) );
		assertNull( AgentAction.parse( "use golden potion" ) );
		assertArrayEquals( new String[]{ "talent", "2" }, AgentAction.parse( "talent 2" ) );
	}

	@Test
	public void rejectsProseAndGarbage() {
		assertNull( AgentAction.parse( null ) );
		assertNull( AgentAction.parse( "" ) );
		assertNull( AgentAction.parse( "I will go back to the stairs" ) );
		assertNull( AgentAction.parse( "attack the rat" ) );
		assertNull( AgentAction.parse( "tap north" ) );
		assertNull( AgentAction.parse( "tap 1" ) );
		assertNull( AgentAction.parse( "tap 99999 1" ) );
	}

	@Test
	public void tapIsOffUnlessAllowed() {
		assertNull( AgentAction.normalize( "tap +2 -1" ) );
		assertEquals( "explore", AgentAction.normalize( "I think.\nexplore" ) );
		Agent.allowTap = true;
		assertEquals( "tap +2 -1", AgentAction.normalize( "tap (+2,-1)" ) );
	}

	@Test
	public void compassDirections() {
		assertEquals( "north", AgentObservation.direction( 0, -3 ) );
		assertEquals( "southeast", AgentObservation.direction( 2, 2 ) );
		assertEquals( "east", AgentObservation.direction( 5, -1 ) );
		assertEquals( "southwest", AgentObservation.direction( -3, 2 ) );
	}

	@Test
	public void journalStringsAreEscaped() {
		assertEquals( "null", Agent.quote( null ) );
		assertEquals( "\"a\\\"b\\\\c\\nd\\u0009\"", Agent.quote( "a\"b\\c\nd\t" ) );
	}
}
