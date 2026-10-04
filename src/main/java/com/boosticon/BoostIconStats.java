package com.boosticon;

import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Value;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Combat stats for a party that has nobody sharing levels already, as levels rather than as the boosts
 * and drains worked out from them, so everyone's own settings decide what they see of them. Named after
 * the plugin because every message on the party connection has to be named differently to every other
 * plugin's.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class BoostIconStats extends PartyMemberMessage
{
	/**
	 * The name of the player the levels belong to. The party knows its members by an id and nothing else,
	 * and the only thing that ever puts a name to one is the party plugin that comes with the client, so
	 * the name travels with the levels rather than there being no way to place them without that plugin.
	 */
	@SerializedName("n")
	String name;

	/**
	 * The level each combat stat is at now, in {@link CombatStat} order.
	 */
	@SerializedName("b")
	int[] boosted;

	/**
	 * The level each one would be at with nothing acting on it, in the same order.
	 */
	@SerializedName("r")
	int[] real;
}
