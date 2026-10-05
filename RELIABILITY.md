# Showing up

The hardest problem in an app for playing with people you don't know isn't finding a game. It's
trusting that the other nine will turn up. A host who gets burned twice stops hosting, and a player
who turns up to four people on a pitch booked for ten stops coming back. Everything else in the
product depends on this one thing working.

The obvious answer is to let players rate each other. We're not going to.

## Why not a rating

**It stops meaning anything.** Every peer-rating system drifts upward until everyone sits at 4.8 and
the number separates nobody. We'd carry the whole cost of ratings and learn nothing from them.

**It costs too much socially.** This isn't a marketplace of strangers. It's the same estate, the same
church, the same office. Giving a neighbour two stars is an act with a life outside the app, so most
people won't do it, and the few who do will be the ones with a grievance. That's a complaints box,
not a measurement.

**It shuts out the people we need most.** A new player has no rating, so hosts don't pick them, so
they never get one. Growth depends on newcomers getting into games with strangers, and a rating taxes
exactly them.

**Subjective scores carry bias.** The newcomer, the one from another part of town, the one who speaks
a different language first. In an app whose whole purpose is getting strangers playing together, that
is an exclusion mechanism pointed the wrong way.

And a number that judges a named person is the most sensitive kind of personal data we could hold.
"Why is mine 2.1?" is a question we'd owe an answer to.

## What we do instead: record what happened

We already hold nearly everything that matters, and none of it is an opinion. `game_participants`
records who joined, when, and whether they paid. Facts like these don't inflate, can't be retaliated
with, and can be explained to the person they describe. A host reading "played 14 games, been here
eight months" judges for themselves, which they do better than a score would.

### The gap to close first — done (V32)

A spot that was given up used to vanish: `game_participants` lost the row and nothing said it had
ever been there, so dropping out the night before was indistinguishable from never having joined.
`game_departures` now records it, written through the `Game` aggregate.

It is a separate table rather than a flag on `game_participants`, because that table answers "who is
in this game" and every count in the app reads it — spots left, who has paid, whether it is full.
Marking rows dead in place would mean every one of those had to remember to skip them, and the first
one that forgot would hold a spot for someone already gone. Nothing about running a game reads
`game_departures`.

Three things it gets right that are easy to get wrong:

- **`notice_minutes` is settled when they leave**, along with the kick-off it was measured against.
  Hosts move games, so working it out later from the game's current start time would turn a
  fortnight's notice into an hour's.
- **A removal is not a drop-out.** The host taking someone off is recorded as `reason = 'removed'`,
  so it never reads as the player letting anyone down. Guests record nothing: there is no account.
- **Closing an account clears the record.** The users row is only anonymised (V11), so nothing would
  remove these otherwise, and a closed account must not leave behind a behaviour record its owner
  can no longer reach or dispute. `giveUpSpotOnAccountClosed` writes nothing, and
  `GameAccountDeletion` deletes what is already there.

### Then: who actually played — done (V33)

The host already marks who paid, so asking who turned up is the same gesture at the same moment.
`attended` and `attended_at` sit on `game_participants`, written through `Game.attended` and offered
on the game page once it has been played ("Who turned up", host only).

Unlike departures this lives on the spot: a played game keeps its participants, so nothing vanishes
and nothing that counts a roster reads these columns.

- **`attended` is null until the host says.** Not marked and did not turn up are different things.
  Most games will never be marked at all, and the absence of a record must never read as a record of
  absence — which is why both answers are a tap and neither is filled in by default.
- **Only after kick-off**, and never on a game that was called off: nobody was expected.
- **Marking again corrects it.** Both answers stay on screen, so changing one is the same tap as
  giving it. Hosts misremember, and a record nobody can fix is worse than no record.

## Paying up front is the stronger lever

A record describes behaviour after the event. Money committed changes it beforehand, which is why
prepayment prevents more no-shows than any reputation system would.

The domain already assumes this. `Game.leave` refuses outright once a player has paid: *"You've
already paid. Ask the host to sort out a refund."* Paying is what turns a casual yes into a
commitment, and the rules around it are written.

Production currently runs with `PLAYCHALE_PAYMENTS_IN_APP=false`, so nobody pays when they join and
the deterrent is dormant. Switching it on does more for turnout than any feature in this note, and
introduces no new ideas.

## Trust runs both ways

Players are also taking a risk on the host: that the pitch is really booked, that the game will
happen, that money collected reaches the venue. Verified partner venues are the right instinct
already. "Ran nine games, none called off late" is the same mechanism pointed at hosts, and it
matters as much.

## Order of work

1. ~~**Record `left_at`**, with how long before kick-off.~~ Done: `game_departures`, V32.
2. ~~**Host confirms the squad** at kick-off, reusing the paid-marking UI.~~ Done: V33.
3. **Plain history on player profiles** — games played, how long they've been here. No score. Next:
   the two records above are what it reads.
4. **Turn on paying up front** when the rails are ready.
5. Only then consider anything score-shaped, and if we do, make it a threshold ("new", "regular"),
   never a grade.

## One rule for whatever we show

People have emergencies, and a record that forgets this will be both unkind and wrong. "Left three
hours before kick-off" is a fact with its context attached. "Unreliable" is a verdict. We show the
first and never the second: it's the difference between a record and an accusation.
