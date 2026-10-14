# How TifoTV finds the channel for a game

_Last updated: 2026-10-14. The code: `SportsChannelFinder` (the searches) and `SportsMatching` (the
points), in `app/src/main/java/tv/own/owntv/tifo/sports/`._

> **Not built yet:** linking channels to a team in Settings (rule 2, and step 1 of each sport
> below), and the game screen described at the end (one row per channel and country, stream
> chips, FR/US/UK sections). Everything else on this page is how the app works today.

When you open a game, TifoTV looks at all of your provider's channels (about 55,000) and
gives each one **points**: the more sure it is that the channel shows this game, the more points.
The channel with the most points is first in the list, and it's the one **Watch** plays.

---

## The rules that apply to every sport

From the strongest to the weakest:

| # | Why the channel is chosen | Points |
|---|---|---|
| 1 | It's a **favorite channel (★)** and it really shows the game (rules 2–6 below) | +3000 |
| 2 | **You linked it to the team** in Settings (main channel, then backups). If the team is one of your favorite teams, its channel beats the other team's | 1000+ |
| 3 | Its **name contains both teams**, e.g. `US\| SOCCER PPV 41: Paris SG vs Lyon` (an "event" channel) | 600 |
| 4 | The **official TV list** for the game names this channel (FotMob for football, NBA.com for the NBA) | 500 |
| 5 | The provider's **TV guide** shows the game on this channel at that time (both teams named) | 500 |
| 6 | **Tennis only:** the TV guide names the tournament (e.g. "Shanghai Masters") at that time | 450 |
| 7 | **Ligue 1+** is announced → its numbered channels (`FR\| LIGUE 1+ 3`). Other streaming apps (Disney+, Prime Video…) are never matched: their games only show as event channels | 300 |
| 8 | **ESPN** says which US network has the game (CBS, NBC, ESPN…) → that network's US channel | 200 |
| 9 | **Last resort**, only if nothing above found anything: the channels that usually show the league | 100 |

Small extras:
- A **US** channel gets +50 and a **Canadian** one +49, so between two equal channels the US one comes first.

Always ignored:
- Empty event slots ("NO EVENT STREAMING") and list dividers.
- A channel whose TV guide shows **another game** at that time.
- A channel named after **another game**, or after the same teams on **another date**.
- Channels or categories for **another sport** (hockey, college football…).
- Rules 7–9 are guesses, so they only use channels from **your countries** (US, Canada, France) and
  never event slots. Rules 2–6 are about this exact game, so they can use channels from any country
  (non-US/CA/FR ones are in the **World** tab).

---

## ⚽ Football (LaLiga, Premier League, Ligue 1, Champions League)

**Where the info comes from:**
- **ESPN** gives the games, the time and the US networks.
- **FotMob** (fotmob.com TV guide) gives the TV channels per country. TifoTV uses:

| League | Countries used from FotMob |
|---|---|
| Ligue 1 | 🇫🇷 France |
| LaLiga | 🇫🇷 France + 🇪🇸 Spain |
| Premier League | 🇫🇷 France + 🇬🇧 UK |
| Champions League | 🇫🇷 France + 🇬🇧 UK |

**How it searches, in order:**
1. Your **linked channels** for the two clubs.
2. **Event channels** with both club names. Club nicknames work too ("PSG", "Barça", "Man City", "OM"…).
3. **FotMob channels**, only in the right country: `Canal+ Live 3` (France) → `FR| CANAL+LIVE 3 FHD`;
   `Sky Sports Main Event` (UK) → `UK| SKY SPORTS MAIN EVENT HD`. A `Canal+` listed in France never
   matches a `Canal 13` in Argentina.
4. **TV guide**: the game is in the provider's guide from 30 min before kick-off until 2h30 after.
5. **Ligue 1+**: if FotMob says the game is on Ligue 1+ without naming a channel, TifoTV uses the
   Ligue 1+ channels (`FR| LIGUE 1+ 3`, `FR| LIGUE1+ (Samedi Match 1)`).
6. **ESPN's US network** (e.g. Peacock, USA Network). "+" apps like **Paramount+** are never guessed:
   their games only appear as event channels (`US| PARAMOUNT+ PPV`).
7. **Last resort** (nothing else found), the league's usual channels:
   - LaLiga: ESPN Deportes, DAZN LaLiga
   - Premier League: Peacock, USA Network, NBC Sports, Canal+ Foot/Sport
   - Ligue 1: channels with "Ligue 1" in the name
   - Champions League: CBS Sports, Golazo, Canal+ Sport/Foot/Live

**Ignored:** hockey, basketball, NFL, rugby, handball, volleyball, futsal, college sports.

**Example:** Arsenal – Chelsea.
- `US| SOCCER PPV 12: Arsenal vs Chelsea` → event channel, 600 + 50 (US) = **650**
- FotMob lists Canal+ Foot in France → `FR| CANAL+ FOOT` = **500**
- FotMob lists Sky Sports Main Event in the UK → `UK-NOWTV| SKY SPORTS MAIN EVENTS` = **500**

The US event channel is first. But if `FR| CANAL+ FOOT` is one of your ★ favorites, it gets
500 + 3000 = **3500** and becomes first.

A ★ only counts when the channel really shows the game (rules 2–6). A ★ channel that's only
a guess (rules 7–9) gets no bonus.

---

## 🏈 NFL

**Where the info comes from:** ESPN (games, time, US networks). No FotMob.

**How it searches, in order:**
1. Your **linked channels** for the two teams.
2. **Event channels** with both team names (`NFL | Bills vs Rams`).
3. **TV guide** with both teams, from 30 min before kick-off until 2h30 after.
4. **ESPN's network** for the game, on US channels: CBS, FOX, NBC, ESPN, ABC, Prime Video, NFL Network.
5. **Last resort**, only if ESPN gives no network at all: NFL Network, RedZone, CBS, FOX, NBC,
   ESPN, ABC, Prime Video, TSN, DAZN.

**Ignored:** any channel mentioning another sport, **including soccer** ("Premier League",
"LaLiga", "MLS"…), plus college/high school, CFL, UFL. That avoids mistakes like
"Lodge **Patriots** vs Old Road **Jets**" (a Caribbean soccer game) for Patriots – Jets.

---

## 🏀 NBA

**Where the info comes from:**
- **ESPN** gives the games and time.
- **NBA.com** gives, for each game, the **national** channel and each team's **local** channel.
  - National: ESPN, ABC, NBC, NBA TV, Prime Video, Peacock…
  - Local: regional sports networks (NBC Sports Boston, MSG, YES Network, Altitude, Chicago Sports
    Network, Space City Home Network, Spectrum SportsNet…) or local stations by call sign
    (`KARE` → `US| NBC 11 MINNEAPOLIS MN (KARE)`).
  - Team apps (Kings+, "… App", DAZN, PCK) are not channels and are skipped.
  - This list replaces ESPN's on the 🇺🇸 line of the game.

**How it searches, in order:**
1. Your **linked channels** for the two teams (your favorite team's first).
2. **NBA.com channels**, US channels only, in **your order**:
   1. **National TV** (e.g. ESPN) if there is one;
   2. otherwise **your favorite team's** local channel (Knicks away → MSG first);
   3. otherwise the **home** team's local channel;
   4. then the **away** team's local channel.

   These come **before event channels**, so Watch plays the official channel.
3. **Event channels** with both team names (`NBA | Celtics vs Knicks`).
4. **TV guide** with both teams, from 30 min before tip-off until 2h30 after.
5. **ESPN's network**, on US channels.
6. **Last resort**, only if ESPN gives no network: NBA TV, League Pass, ESPN, ABC, NBC, Peacock,
   Prime Video, TNT, TSN, Sportsnet, beIN Sports.

**Ignored:** other sports **including soccer**, WNBA, college, EuroLeague, ACB.

**Known limit:** in the regular season, NBA.com lists no local channel for about 18 teams
(e.g. Clippers). For those games you only get the national channel, event channels and the guide.

---

## 🎾 Tennis (ATP)

**Where the info comes from:** ESPN gives the matches. It gives **no** TV channel for tennis,
and the start time is only an estimate (order of play).

**How it searches, in order:**
1. **Event channels** with both players. A player matches by full name or surname
   (`US| TENNIS PPV: Zverev, Alexander vs Bublik, Alexander`).
2. **TV guide** naming both players. The time window is wider: from 1h30 before to 5h after the
   estimated start.
3. **TV guide naming the tournament** (450) at that time: TifoTV keeps the tournament's own words
   and the city, and drops words like Rolex, Masters, Open, ATP ("Rolex Shanghai Masters" →
   "Shanghai"). Highlights, replays and weekly magazines don't count. Shown in **World** when it's
   not a US/CA/FR channel.
4. **Last resort** (nothing else found): Tennis Channel, Tennis TV, channels with "Tennis" or "ATP"
   in the name.

**Favorites:** a ★ channel also gets the +3000 when its guide only names the tournament.
Example: Paul – Bergs → `FR| EUROSPORT 1` (★, guide "Shanghai Masters") 3450, before the
`US| SOCCER PPV 41` event channel (650).

**Ignored:** WTA / women's, padel, table tennis, and other sports.

---

## On the game screen

- Each channel is **one row per channel and country**. Its streams (FHD 1, FHD 2, HD 1…) are chips
  you can scroll; tap one to play. ★ marks a favorite.
- Event channels and guesses stay one row per provider category (`US| SOCCER PPV`).
- Sections: 🇫🇷 FR, 🇺🇸 US, 🇬🇧 UK first, then the others.
- Event channels are created by the provider near game day, so games far away show fewer channels.
