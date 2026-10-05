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

### The gap to close first

When a player leaves, `Game.leave` removes the row (`internal/domain/Game.java`). There's no
`left_at`, so the single most trust-relevant act in the app — dropping out the night before —
leaves no trace at all. A late withdrawal is indistinguishable from never having joined.

This is worth fixing before anything else here, because history that was never recorded can't be
recovered later. Every week without it is gone. Soft-delete the spot, keep `left_at`, and "left three
hours before kick-off" becomes a timestamped fact that needs nobody's judgment.

### Then: who actually played

The host already marks who paid. Asking the same question about who turned up is the same gesture at
the same moment, and it gives us attendance without asking anyone to rate anyone.

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

1. **Record `left_at`**, with how long before kick-off. Cheap, and unrecoverable if we skip it.
2. **Host confirms the squad** at kick-off, reusing the paid-marking UI.
3. **Plain history on player profiles** — games played, how long they've been here. No score.
4. **Turn on paying up front** when the rails are ready.
5. Only then consider anything score-shaped, and if we do, make it a threshold ("new", "regular"),
   never a grade.

## One rule for whatever we show

People have emergencies, and a record that forgets this will be both unkind and wrong. "Left three
hours before kick-off" is a fact with its context attached. "Unreliable" is a verdict. We show the
first and never the second: it's the difference between a record and an accusation.
