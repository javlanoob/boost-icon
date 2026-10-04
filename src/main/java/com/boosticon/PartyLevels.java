package com.boosticon;

import java.util.Arrays;

/**
 * What a party member last told the party about themselves. Levels arrive a stat at a time, as and when
 * each one changes, so every stat is kept until something newer arrives for it.
 */
class PartyLevels
{
	/**
	 * Nothing heard about a stat yet. Below zero, since an empty special attack bar is a real nought.
	 */
	static final int UNKNOWN = -1;

	private static final int SIZE = CombatStat.ALL.length;

	private final int[] boosted = new int[SIZE];
	private final int[] real = new int[SIZE];

	/**
	 * The name of the player the levels belong to, as the member sharing them gave it, or nothing while
	 * they have not said. It is what ties the levels to somebody on screen.
	 */
	private String name;

	PartyLevels()
	{
		Arrays.fill(boosted, UNKNOWN);
		Arrays.fill(real, UNKNOWN);
	}

	void setBoosted(CombatStat stat, int boostedLevel)
	{
		boosted[stat.ordinal()] = boostedLevel;
	}

	void setReal(CombatStat stat, int realLevel)
	{
		real[stat.ordinal()] = realLevel;
	}

	int[] getBoosted()
	{
		return boosted;
	}

	int[] getReal()
	{
		return real;
	}

	void setName(String name)
	{
		this.name = name;
	}

	String getName()
	{
		return name;
	}
}
