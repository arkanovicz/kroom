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
import java.util.UUID

/**
 * The rule of the arena. It reads the marking and never writes it: who waits is `state.queue`, the fold of the
 * `match` deposits, cancels and leaves; a round's moves are fields of its match each player alone sees, put there by the fold of
 * `play`. Nothing lives here.
 */
object ChifoumiLogic : LobbyLogic() {
    private val chat = ChatLogic()

    override fun chat(state: LobbyState, actor: Actor, chat: Chat): Outcome<Nothing> { this.chat.fire(state, actor, chat); return done }

    override fun match(state: LobbyState, actor: Actor, match: Match): Outcome<Nothing> {
        if (state.myMatch(actor.id) != null) return Reason("in_match")
        val me = Player(Json.MutableObject("id" to actor.id, "name" to actor.name))
        match.player = me
        val other = state.queue.firstOrNull { it.id != actor.id } ?: return held()
        match.match = MatchView.start(UUID.randomUUID().toString().take(8), other, me)
        return done.releasing(other.id)
    }

    override fun play(state: LobbyState, actor: Actor, play: Play): Outcome<Round> {
        val match = state.myMatch(actor.id) ?: return Reason("not_in_match")
        if (actor.id in match.played) return Reason("already_played")
        play.player = actor.id
        val (next, round) = match.resolve(actor.id, play.move!!) ?: return held()
        return fired(Round().also { it.match = next; it.round = round }).releasing(match.opponentOf(actor.id))
    }

    override fun leave(state: LobbyState, user: Actor): Outcome<Match> {
        val match = state.myMatch(user.id) ?: return done
        // the other may have played this round: that move is withdrawn with the match
        return fired(Match().also { it.match = match.forfeit(user.id) }).releasing(match.opponentOf(user.id))
    }
}
