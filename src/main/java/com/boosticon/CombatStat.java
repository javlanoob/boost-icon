package com.boosticon;

import java.util.function.Predicate;
import net.runelite.api.Skill;
import net.runelite.api.gameval.SpriteID;

/**
 * The seven skills that make up your combat level, in the order they sit in the stats tab, each with
 * the stats tab icon the game draws for it, and special attack, which is not a skill but runs down and
 * comes back the way the points among them do.
 */
enum CombatStat
{
	ATTACK(Skill.ATTACK, SpriteID.Staticons.ATTACK, BoostIconConfig::showAttack),
	STRENGTH(Skill.STRENGTH, SpriteID.Staticons.STRENGTH, BoostIconConfig::showStrength),
	DEFENCE(Skill.DEFENCE, SpriteID.Staticons.DEFENCE, BoostIconConfig::showDefence),
	RANGED(Skill.RANGED, SpriteID.Staticons.RANGED, BoostIconConfig::showRanged),
	PRAYER(Skill.PRAYER, SpriteID.Staticons.PRAYER, BoostIconConfig::showPrayer),
	MAGIC(Skill.MAGIC, SpriteID.Staticons.MAGIC, BoostIconConfig::showMagic),
	HITPOINTS(Skill.HITPOINTS, SpriteID.Staticons.HITPOINTS, BoostIconConfig::showHitpoints),
	SPECIAL(null, SpriteID.OrbIcon.SPECIAL, BoostIconConfig::showSpecial);

	/**
	 * A full special attack bar. It is kept as a share of one rather than as a level, so this is the
	 * most there can be of it.
	 */
	static final int FULL_PERCENT = 100;

	/**
	 * Every stat, in order. Kept rather than asked for each time, since asking hands out a fresh copy of
	 * the list and this is walked for every player, every tick.
	 */
	static final CombatStat[] ALL = values();

	private final Skill skill;
	private final int spriteId;
	private final Predicate<BoostIconConfig> enabled;

	CombatStat(Skill skill, int spriteId, Predicate<BoostIconConfig> enabled)
	{
		this.skill = skill;
		this.spriteId = spriteId;
		this.enabled = enabled;
	}

	/**
	 * The skill this stat is, or nothing where it is not a skill at all, as special attack is not.
	 */
	Skill getSkill()
	{
		return skill;
	}

	int getSpriteId()
	{
		return spriteId;
	}

	boolean isEnabled(BoostIconConfig config)
	{
		return enabled.test(config);
	}

	/**
	 * Whether the stat is points that run down and get topped up again rather than a level that sits
	 * where it is until something acts on it. How much of these is left matters more than how far they
	 * are from the level, so they are read and coloured that way.
	 */
	boolean isPoints()
	{
		return this == HITPOINTS || this == PRAYER || this == SPECIAL;
	}

	/**
	 * Whether the stat is a share of a full bar rather than a level, which special attack alone is.
	 */
	boolean isPercent()
	{
		return this == SPECIAL;
	}

	/**
	 * Whether a number is one this stat could be sitting at, so that nothing is made of a stat arriving
	 * from elsewhere as something it cannot be. Nought is where a spec leaves the bar and where a prayer
	 * runs out, so it is somewhere a stat really is rather than a number to throw away.
	 */
	boolean isPossibleNow(int level)
	{
		return level >= 0 && (!isPercent() || level <= FULL_PERCENT);
	}

	/**
	 * Whether a number is one this stat could be at with nothing acting on it. That is a level, and a
	 * level is at least one, which is the whole of the difference from the amount above.
	 */
	boolean isPossibleReal(int level)
	{
		return level >= 1 && (!isPercent() || level <= FULL_PERCENT);
	}
}
