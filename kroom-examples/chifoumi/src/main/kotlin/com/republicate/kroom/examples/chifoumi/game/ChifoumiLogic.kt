package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Actor
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.done
import com.republicate.kroom.event.fired
import com.republicate.kroom.event.held
import com.republicate.kroom.server.ChatLogic
import com.republicate.kson.Json

/**
 * The rule of the arena. The marking is read, never written: who waits is `state.queue`, the fold
 * of the `match` deposits, cancels and leaves. What the wire must not carry is here: the live
 * matches, with the move of a round in progress.
 */
object ChifoumiLogic : LobbyLogic() {
    private val chat = ChatLogic()
    private val live = HashMap<String, Game>()          // by match id
    private val playing = HashMap<String, String>()    // user → match id

    override fun chat(state: LobbyState, actor: Actor, chat: Chat): Outcome<Nothing> { this.chat.fire(state, actor, chat); return done }

    override fun match(state: LobbyState, actor: Actor, match: Match): Outcome<Nothing> {
        if (playing.containsKey(actor.id)) return Reason("in_match")
        match.player = Player(Json.MutableObject("id" to actor.id, "name" to actor.name))
        val other = state.queue.firstOrNull { it.id != actor.id } ?: return held()
        val started = Game(other.id, actor.id, other.name ?: other.id, actor.name)
        live[started.id] = started
        playing[other.id] = started.id
        playing[actor.id] = started.id
        match.match = MatchView(started.toJson())
        return done.releasing(other.id)
    }

    override fun play(state: LobbyState, actor: Actor, play: Play): Outcome<Round> {
        val match = live[playing[actor.id]] ?: return Reason("not_in_match")
        if (match.hasPlayed(actor.id)) return Reason("already_played")
        play.player = actor.id
        val round = match.play(actor.id, play.move) ?: return held()
        if (match.isFinished) end(match)
        return fired(Round().also { it.match = MatchView(match.toJson()); it.round = round.toJson() }).releasing(match.opponentOf(actor.id))
    }

    override fun cancelPlay(state: LobbyState, actor: Actor, play: Play): Outcome<Round> {
        live[playing[actor.id]]?.withdraw(actor.id)
        return done
    }

    override fun leave(state: LobbyState, user: Actor): Outcome<Match> {
        val match = live[playing[user.id]] ?: return done
        match.forfeit(user.id)
        end(match)
        // the other may have played this round: that move is withdrawn with the match
        return fired(Match().also { it.match = MatchView(match.toJson()) }).releasing(match.opponentOf(user.id))
    }

    private fun end(match: Game) {
        live.remove(match.id)
        playing.remove(match.player1)
        playing.remove(match.player2)
    }

    /** A test's reset. */
    fun clear() { live.clear(); playing.clear() }
}
