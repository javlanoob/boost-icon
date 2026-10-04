# Boosted Icon
Icons by your health bar for the combat stats that are boosted or drained.

When a combat stat is above or below its real level, the stat's own icon from the skills tab appears
next to your health bar with the change beside it: green while the stat is boosted, yellow once the
boost is nearly gone, red while it is drained. The icons stack while more than one stat is off its
level, and they disappear as each one comes back.

Based on [Frozen Icon](https://github.com/HamzehAdawi/FrozenIcon) by HamzehAdawi, which does the same
thing for freezes and binds.

## Settings
**Size**: how much bigger or smaller the icons are drawn. Each one keeps its own shape rather than
being squared off.

**Lower by**: how far below the health bar the column of icons sits. Below zero puts it above the bar.

**Anchor**: what the icons are measured from — the player's head, where the health bar is, their
middle, or the tile they are standing on. **Lower by** then moves them from there.

**Icon position**: which side of the health bar the icons appear on.

**Show**: what goes next to each icon — the change (`+5`, `-3`), the level the stat is at now, or
nothing. Hitpoints, prayer and special attack ignore this and always read as how much is left.

**Show buffs** and **Show debuffs**: whether stats above their level, below their level, or both get
an icon.

**Show unpotted**: whether a stat sitting at its real level gets one as well, which turns the icons
into a row of everything you have switched on rather than only what is off. Hitpoints, prayer and
special attack ignore this and always show, since a full bar is worth seeing too.

**Opacity**: how solid the icons and numbers are drawn, from 100 down to invisible.

**Buff colour**, **Debuff colour**, **Running out colour** and **Unpotted colour**: the colour of the
number. The first three are the ones the game's own Boosts plugin uses, so a stat reads the same in
both.

**Buff threshold**: how many levels a buff has left for it to count as running out and turn the colour
above. 0 never does.

**Low HP** and **Critical HP**: the share of your hitpoints or prayer left at which the number turns
the running out colour, and then the debuff colour. 60 and 35 to start with. Special attack is left out
of it, since a spec costs what it costs and there is no amount of it that counts as being in trouble;
its number takes the unpotted colour throughout.

**Show on yourself**: whether your own stats get icons. Off with **Party stats** on leaves only your
team mates'.

**Toggle**: a key that puts the icons away and brings them back. Unassigned to start with. Levels are
still kept up to date and still shared with the party while they are away.

**Party stats**: with this on, the same icons appear over the heads of the party members around you.

The five levelled stats come from party members running Boosted Icon with the setting on, and need
nothing else of either of you.

Hitpoints, prayer and special attack also arrive from anyone running the Party plugin that comes with the
client, whether or not they have this one, since that is what it already shares with the party.

What arrives either way is levels rather than the boosts worked out from them, so your own settings
decide what you see of theirs.

### Stats
Every combat stat can be turned on or off on its own, along with special attack, which is not one but
runs down and comes back the way they do. Attack, Strength, Defence, Ranged and Magic are on; Prayer,
Hitpoints and Special attack are off, since all three are already on the orbs.

Those three are read differently when you do turn them on. A level that is off where it should be is
news in itself, but hitpoints, prayer and special attack run down all fight, so what is left of them is
the news instead: the number is always what you have, as points or as per cent. Hitpoints and prayer
are coloured by how much of the full amount that is rather than by which way they are off it. Having
turned one on is asking to see it, so it stays up at a full bar as well.
