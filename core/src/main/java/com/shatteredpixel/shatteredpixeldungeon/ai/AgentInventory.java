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
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.InventoryScroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;

import java.util.ArrayList;
import java.util.List;

//The hero's items as a numbered list, and the actions that take those numbers.
//Numbers are positions in items(): the same call numbers the text and resolves the action.
public class AgentInventory {

	//equipped items first, then the backpack; containers themselves are skipped
	static List<Item> items( Hero hero ) {
		List<Item> items = new ArrayList<>();
		for (Item item : hero.belongings) {
			if (!(item instanceof Bag)) items.add( item );
		}
		return items;
	}

	//"1 worn shortsword (equipped); 2 cloth armor (equipped); 3 food ration", names as the player sees them
	static String render( Hero hero ) {
		List<String> out = new ArrayList<>();
		List<Item> items = items( hero );
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get( i );
			out.add( (i + 1) + ": " + item.title() + (item.isEquipped( hero ) ? " (equipped)" : "") );
		}
		return String.join( "; ", out );
	}

	//talents a point can be spent on right now, in tier order; empty when there is no point to spend
	static List<Talent> talents( Hero hero ) {
		List<Talent> open = new ArrayList<>();
		for (int tier = 1; tier <= hero.talents.size(); tier++) {
			if (hero.talentPointsAvailable( tier ) <= 0) continue;
			for (Talent talent : hero.talents.get( tier - 1 ).keySet()) {
				if (hero.pointsInTalent( talent ) < talent.maxPoints()) open.add( talent );
			}
		}
		return open;
	}

	//"1 Hearty Meal 0/2: <what it does>; 2 ...", or null when there is no point to spend
	static String renderTalents( Hero hero ) {
		List<Talent> open = talents( hero );
		if (open.isEmpty()) return null;
		List<String> out = new ArrayList<>();
		for (int i = 0; i < open.size(); i++) {
			Talent talent = open.get( i );
			String desc = talent.desc().replace( "_", "" ).replaceAll( "\\s+", " " ).trim();
			if (desc.length() > 200) desc = desc.substring( 0, 200 ) + "...";
			//the short form leaves what each talent does to the guide: a long line can bury the prompt to act
			out.add( (i + 1) + ": " + talent.title() + " " + hero.pointsInTalent( talent ) + "/" + talent.maxPoints()
					+ (Agent.talentDetails ? ": " + desc : "") );
		}
		return String.join( "; ", out );
	}

	//spend a talent point on talent N of the list shown in the observation
	static String talent( int number ) {
		Hero hero = Dungeon.hero;
		List<Talent> open = talents( hero );
		if (open.isEmpty()) return "error: no talent point to spend";
		if (number < 1 || number > open.size()) return "error: no talent " + number;
		hero.upgradeTalent( open.get( number - 1 ) );
		//the game does the same when a point is spent by hand
		Statistics.qualifiedForRandomVictoryBadge = false;
		return "ok";
	}

	private static Item pick( Hero hero, int number ) {
		List<Item> items = items( hero );
		return number >= 1 && number <= items.size() ? items.get( number - 1 ) : null;
	}

	//eat, drink or read item N
	static String use( int number ) {
		Hero hero = Dungeon.hero;
		Item item = pick( hero, number );
		if (item == null) return "error: no item " + number;

		if (item instanceof Food) {
			item.execute( hero, Food.AC_EAT );
		} else if (item instanceof Potion) {
			item.execute( hero, Potion.AC_DRINK );
			//a potion known to be harmful asks for confirmation instead of being drunk
			if (GameScene.topWindow() != null) {
				GameScene.topWindow().hide();
				return "error: this potion is harmful to drink, throw it instead";
			}
		} else if (item instanceof ScrollOfUpgrade && ((Scroll) item).isKnown()) {
			return "error: say which item to upgrade: use " + number + " on M";
		} else if (item instanceof Scroll) {
			item.execute( hero, Scroll.AC_READ );
			//a scroll that works on an item asks which one: take the first it accepts
			Window window = GameScene.topWindow();
			if (window instanceof WndBag && item instanceof InventoryScroll) {
				WndBag.ItemSelector selector = ((WndBag) window).getSelector();
				Item target = null;
				for (Item candidate : items( hero )) {
					if (selector.itemSelectable( candidate )) {
						target = candidate;
						break;
					}
				}
				window.hide();
				selector.onSelect( target );
			}
		} else {
			return "error: " + item.name() + " cannot be used this way";
		}
		return "ok";
	}

	//read upgrade scroll N on item M
	static String useOn( int number, int targetNumber ) {
		Hero hero = Dungeon.hero;
		Item item = pick( hero, number ), target = pick( hero, targetNumber );
		if (item == null) return "error: no item " + number;
		if (target == null) return "error: no item " + targetNumber;
		if (!(item instanceof ScrollOfUpgrade) || !((Scroll) item).isKnown()) {
			return "error: only a scroll of upgrade is used on another item";
		}
		if (!target.isUpgradable()) return "error: " + target.name() + " cannot be upgraded";

		//the same steps as the confirm button of the upgrade window
		ScrollOfUpgrade scroll = (ScrollOfUpgrade) item;
		scroll.execute( hero, "" );
		scroll.readAnimation();
		scroll.upgradeItem( target );
		scroll.detach( hero.belongings.backpack );
		return "ok";
	}

	static String equip( int number ) {
		Hero hero = Dungeon.hero;
		Item item = pick( hero, number );
		if (item == null) return "error: no item " + number;
		if (!(item instanceof EquipableItem)) return "error: " + item.name() + " cannot be equipped";
		if (item.isEquipped( hero )) return "error: already equipped";
		item.execute( hero, EquipableItem.AC_EQUIP );
		return "ok";
	}

	//throw item N at the nearest enemy in sight
	static String throwAt( int number ) {
		Hero hero = Dungeon.hero;
		Item item = pick( hero, number );
		if (item == null) return "error: no item " + number;
		if (item.isEquipped( hero )) return "error: unequipping to throw is not supported";
		Mob target = null;
		for (Mob mob : AgentObservation.visibleMobs( Dungeon.level )) {
			if (mob.alignment != com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY) continue;
			if (target == null || Dungeon.level.distance( hero.pos, mob.pos ) < Dungeon.level.distance( hero.pos, target.pos )) {
				target = mob;
			}
		}
		if (target == null) return "error: no enemy in sight to throw at";
		item.execute( hero, "" );
		item.cast( hero, target.pos );
		return "ok";
	}
}
