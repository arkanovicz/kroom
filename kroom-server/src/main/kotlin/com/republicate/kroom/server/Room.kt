package com.republicate.kroom.server

import com.republicate.kroom.event.Audience
import com.republicate.kroom.event.Catalog
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Stage
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An event with no declaration: what [Room.broadcast] births, what the door rejects by name. */
class Generic(name: String, json: Json.MutableObject = Json.MutableObject(), audience: Audience = Audience.ROOM) :
    Event(name, audience, json = json)

/** Born by the room at a connect, to the room: who arrived. The joiner's own feedback is the `state` frame. */
class Join(json: Json.MutableObject = Json.MutableObject()) : Event("join", json = json) {
    var user by string()
    var displayName by string()
    var spectator by bool()
    var first by bool()      // the user's first connection to the room
}

/** Born by the room at a disconnect, to the room. */
class Leave(json: Json.MutableObject = Json.MutableObject()) : Event("leave", json = json) {
    var user by string()
    var displayName by string()
    var spectator by bool()
    var last by bool()       // the user's last connection to the room
}

/**
 * A room: state, membership (users; actors write, spectators read), one loop, and the events
 * it speaks. An [Event] arrives at the door ([act]), is applied on the loop by the handler its
 * declaration registered ([on]), and comes back to its audience with its stage — the emitter is
 * always in it, so the frame is the feedback. Every emitted frame carries the room's seq.
 *
 * A join is an event too: the room births [Join] at a connect, the handler runs, the joiner gets
 * its `state` snapshot ([stateToJson]) before anything queued behind, then the room hears `join`.
 */
abstract class Room<S : Any>(val id: String) {
    protected val logger = LoggerFactory.getLogger("kroom.room")

    abstract var state: S

    protected val users = ConcurrentHashMap<String, User>()
    protected val actors = ConcurrentHashMap<String, Actor>()
    protected val spectators = ConcurrentHashMap<String, Spectator>()

    private val queue = Channel<Op>(Channel.UNLIMITED)
    private val seq = AtomicLong(0)
    private var processingJob: Job? = null
    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _userCount = MutableStateFlow(0)
    val userCount: StateFlow<Int> = _userCount.asStateFlow()
    private val _spectatorCount = MutableStateFlow(0)
    val spectatorCount: StateFlow<Int> = _spectatorCount.asStateFlow()
    val connectionCount: Int get() = actors.size + spectators.size

    // ---- declarations

    /** The events a door may bring: what `GET /rooms/{room}/events` serves as data. */
    val catalog = Catalog()
    private val handlers = HashMap<String, (Actor, Event) -> Reason?>()
    private val system = setOf("join", "leave")

    /**
     * One declaration: the class (its factory, for the door) and its handler (for the loop).
     * The handler applies the event to the state and answers null, or a [Reason] to reject it;
     * a thrown [Event.Rejected] is the same, any other exception is `error`, logged. It runs on
     * the loop: no awaited I/O. What it [emit]s lands before the event's own frame.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <E : Event> on(factory: (Json.MutableObject) -> E, handler: (Actor, E) -> Reason?) {
        val name = factory(Json.MutableObject()).name
        if (name !in system) catalog.register(factory)
        handlers[name] = { actor, event -> handler(actor, event as E) }
    }

    /** Whether [user] holds [permission] here; the host's call (authoring's roles), everything by default. */
    protected open fun permits(user: User, permission: String): Boolean = true

    /** Every stage change of every event: the operator's line. */
    protected open fun trace(event: Event) {
        if (logger.isDebugEnabled) logger.debug("{} {} {}", id, event.name, event.meta.json)
    }

    /** An event just went on the wire: where a store persists it, off the loop. */
    protected open fun emitted(event: Event) {}

    init {
        on(::Join) { _, _ -> null }
        on(::Leave) { _, _ -> null }
        start()
    }

    private fun start() {
        processingJob = scope.launch {
            logger.info("Room '$id' started")
            while (isActive) {
                try {
                    process(queue.receive())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error in room '$id'", e)
                }
            }
        }
    }

    fun stop() {
        queue.close()
        processingJob?.cancel()
        scope.cancel()
        actors.values.forEach { it.disconnect() }
        spectators.values.forEach { it.disconnect() }
        users.values.forEach { it.connectionsByRoom.remove(id) }
        actors.clear(); spectators.clear(); users.clear()
        logger.info("Room '$id' stopped")
    }

    // ---- the door

    /**
     * An event at the door: accepted here, stateless (the shape, the permission, not a spectator),
     * then queued for the handler. A refusal at the door is answered, never queued nor emitted:
     * the returned event is `REJECTED`. Otherwise the promise is the event in its final stage.
     */
    fun act(actor: Actor, event: Event): Deferred<Event> {
        val verdict = CompletableDeferred<Event>()
        val refused = when {
            actor is Spectator -> Reason("spectator")
            event.name in system || handlers[event.name] == null -> Reason("unknown")
            event.permission?.let { p -> actor.user?.let { u -> permits(u, p) } != true } == true -> Reason("forbidden")
            else -> null
        }
        if (refused == null && event.stage == Stage.BORN) {
            try {
                event.accept(actor.user?.userId ?: actor.connectionId, actor.tab)
            } catch (e: Event.Rejected) {
                return verdict.also { it.complete(event.reject(e.reason).also(::trace)) }
            }
        }
        if (refused != null) return verdict.also { it.complete(event.reject(refused).also(::trace)) }
        trace(event)
        if (!queue.trySend(Op.Act(actor, event, verdict)).isSuccess) verdict.complete(event.reject(Reason("closed")))
        return verdict
    }

    /**
     * The route's door: a session and a tab, not a connection. The name is looked up in the
     * [catalog]; the connection id is composed server-side so the tab lands under its own user.
     */
    suspend fun act(user: User, tab: String?, name: String, json: Json.MutableObject): Event {
        val connectionId = Actor.connectionId(user, tab)
        val actor = getActor(connectionId) ?: Actor(connectionId, user)
        val event = catalog.of(name, json) ?: return Generic(name, json).reject(Reason("unknown")).also(::trace)
        return act(actor, event).await()
    }

    // ---- emissions

    /**
     * A room-born event, or one the handler made for others: queued behind what is already
     * queued, numbered and framed on the loop. [to] names the users of a `USERS` audience; none
     * means the emitter. A `BORN` event applies here: it had no door.
     */
    fun emit(event: Event, to: Collection<User> = emptyList()) {
        if (event.stage == Stage.BORN) event.apply()
        queue.trySend(Op.Emit(event, to.map { it.userId }))
    }

    fun emit(event: Event, vararg to: User) = emit(event, to.toList())

    /** Convenience: a room-born [Generic] to the room. */
    fun broadcast(name: String, data: Json.Object) = emit(Generic(name, Json.MutableObject(data)))

    /** Convenience: a room-born [Generic] to one user, every tab of theirs. */
    fun sendToUser(user: User, name: String, data: Json.Object) = emit(Generic(name, Json.MutableObject(data), Audience.USERS), user)

    fun sendToUser(userId: String, name: String, data: Json.Object) { users[userId]?.let { sendToUser(it, name, data) } }

    // ---- connections

    /**
     * A connection joins. On the loop like everything else: the newcomer gets `state`, then the
     * frames queued behind the join — the join handler's emissions, then `join` itself to the
     * room. Nothing written elsewhere can slip in before the snapshot. A stopped room hands back
     * a closed channel: the stream ends, the browser retries and learns the room is gone.
     */
    suspend fun join(actor: Actor): Channel<ServerSentEvent> {
        val ready = CompletableDeferred<Channel<ServerSentEvent>>()
        if (!queue.trySend(Op.Join(actor, ready)).isSuccess) return closed()
        return ready.await()
    }

    suspend fun joinAsSpectator(spectator: Spectator): Channel<ServerSentEvent> = join(spectator)

    /** A connection leaves; returns once the room has forgotten it. */
    suspend fun leave(actor: Actor) {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Op.Leave(actor, done)).isSuccess) done.await()
    }

    private fun closed() = Channel<ServerSentEvent>(0).also { it.close() }

    /** Per-connection buffer: the loop never waits on a consumer (see [sendSafe]). */
    protected open val channelCapacity: Int = 64

    /**
     * A connection id already present is the same tab back (reconnect, or a duplicated tab): the
     * newcomer takes the slot, the stale connection is closed and its later [leave] is a no-op.
     */
    private fun replace(actor: Actor) {
        val evicted = if (actor is Spectator) spectators.put(actor.connectionId, actor) else actors.put(actor.connectionId, actor)
        val crossed = if (actor is Spectator) actors.remove(actor.connectionId) else spectators.remove(actor.connectionId)
        val stale = (evicted ?: crossed)?.takeIf { it !== actor } ?: return
        stale.disconnect()
        stale.user?.removeConnection(id, stale)
        logger.debug("Connection '${actor.connectionId}' replaced in room '$id'")
    }

    private fun processJoin(actor: Actor) {
        val channel = Channel<ServerSentEvent>(channelCapacity)
        actor.channel = channel
        replace(actor)
        val spectator = actor is Spectator
        if (spectator) _spectatorCount.value = spectators.size
        var first = true
        actor.user?.let { user ->
            first = !users.containsKey(user.userId)
            users[user.userId] = user
            user.addConnection(id, actor)
            if (first && !spectator) _userCount.value = users.size
        }
        logger.debug("${if (spectator) "Spectator" else "Actor"} '${actor.name}' joined room '$id' (${users.size} users, ${actors.size} connections, ${spectators.size} spectators)")

        val join = Join().also { it.user = actor.user?.userId; it.displayName = actor.name; it.spectator = spectator; it.first = first }
        join.meta.from = actor.user?.userId
        handle(actor, join)
        // the snapshot, straight on the channel: this op owns the loop, nothing interleaves
        val snapshot = Json.MutableObject(stateToJson(actor))
        snapshot[Event.META] = Json.Object("stage" to Stage.APPLIED.name, "seq" to seq.get())
        sendSafe(actor, ServerSentEvent(data = snapshot.toString(), event = "state", id = seq.get().toString()))
        if (join.stage == Stage.APPLIED) emit(join)
    }

    private fun processLeave(actor: Actor) {
        // a replaced connection (same tab back) leaves nothing: the newcomer owns the slot
        if (actors[actor.connectionId] !== actor && spectators[actor.connectionId] !== actor) return
        actor.disconnect()
        val spectator = actor is Spectator
        val last = actor.user?.let { user ->
            val none = user.removeConnection(id, actor)
            if (none) { users.remove(user.userId); _userCount.value = users.size }
            none
        } ?: true
        if (spectator) { spectators.remove(actor.connectionId); _spectatorCount.value = spectators.size }
        else actors.remove(actor.connectionId)
        logger.debug("${if (spectator) "Spectator" else "Actor"} '${actor.name}' left room '$id' (last=$last)")

        val leave = Leave().also { it.user = actor.user?.userId; it.displayName = actor.name; it.spectator = spectator; it.last = last }
        leave.meta.from = actor.user?.userId
        handle(actor, leave)
        if (leave.stage == Stage.APPLIED) emit(leave)

        if (shouldCloseWhenEmpty() && users.isEmpty() && actors.isEmpty()) {
            stop()
            Lobby.remove(id)
        }
    }

    /** The handler, then the stage it earned. */
    private fun handle(actor: Actor, event: Event) {
        val reason = try {
            handlers[event.name]?.invoke(actor, event)
        } catch (e: Event.Rejected) {
            e.reason
        } catch (e: Exception) {
            logger.error("'${event.name}' failed in room '$id'", e)
            Reason("error")
        }
        if (reason == null) event.apply() else event.reject(reason)
        trace(event)
    }

    // ---- the loop

    private fun process(op: Op) {
        when (op) {
            is Op.Act -> {
                handle(op.actor, op.event)
                // the handler's own emissions are already queued: the event's frame lands after them,
                // so the acting tab sees the new state before its trigger is freed
                queue.trySend(Op.Emit(op.event, emptyList()))
                op.verdict.complete(op.event)
            }
            is Op.Emit -> {
                val event = op.event.emit(seq.incrementAndGet())
                trace(event)
                val frame = ServerSentEvent(data = event.json.toString(), event = event.name, id = event.meta.seq.toString())
                when {
                    event.stage == Stage.REJECTED -> toUsers(listOfNotNull(event.meta.from), frame)
                    event.audience == Audience.GLOBAL -> Lobby.listRooms().forEach { it.toAll(frame) }
                    event.audience == Audience.ROOM -> toAll(frame)
                    else -> toUsers(op.to.ifEmpty { listOfNotNull(event.meta.from) }, frame)
                }
                emitted(event)
            }
            is Op.Join -> try { processJoin(op.actor) } finally { op.ready.complete(op.actor.channel ?: closed()) }
            is Op.Leave -> try { processLeave(op.actor) } finally { op.done.complete(Unit) }
        }
    }

    private fun toAll(frame: ServerSentEvent) {
        actors.values.forEach { sendSafe(it, frame) }
        spectators.values.forEach { sendSafe(it, frame) }
    }

    private fun toUsers(userIds: Collection<String>, frame: ServerSentEvent) {
        userIds.forEach { userId -> users[userId]?.getConnectionsInRoom(id)?.forEach { sendSafe(it, frame) } }
    }

    /**
     * The loop never waits on a consumer: a full buffer means a tab that stopped draining (frozen
     * in the background, slow link), and it is dropped — its stream ends, the browser reconnects
     * and joins again. Nobody else pays.
     */
    private fun sendSafe(actor: Actor, sse: ServerSentEvent) {
        val channel = actor.channel ?: return
        if (channel.trySend(sse).isFailure) {
            logger.warn("Dropping '${actor.name}' from room '$id': not draining")
            actor.disconnect()
        }
    }

    // ---- state

    abstract fun stateToJson(): Json.Object

    /**
     * The snapshot a joining connection gets as its `state` frame. Override when a viewer sees the
     * room differently (a seat, a hand, its own match): catch-up is this frame's job alone, and an
     * event only ever means a transition.
     */
    open fun stateToJson(actor: Actor): Json.Object = stateToJson()

    protected open fun shouldCloseWhenEmpty(): Boolean = true

    fun getActors(): List<Actor> = actors.values.toList()
    fun getUsers(): List<User> = users.values.toList()
    fun getSpectators(): List<Spectator> = spectators.values.toList()
    fun getActor(connectionId: String): Actor? = actors[connectionId] ?: spectators[connectionId]
    fun getUser(userId: String): User? = users[userId]

    /** Room info for lobby. */
    open fun toJson(): Json.Object = Json.Object(
        "id" to id,
        "userCount" to users.size,
        "connectionCount" to actors.size,
        "spectatorCount" to spectators.size,
        "users" to Json.Array(users.values.map { Json.Object("userId" to it.userId, "name" to it.displayName) }),
        "events" to catalog.describe()
    )

    private sealed class Op {
        class Act(val actor: Actor, val event: Event, val verdict: CompletableDeferred<Event>) : Op()
        class Emit(val event: Event, val to: List<String>) : Op()
        class Join(val actor: Actor, val ready: CompletableDeferred<Channel<ServerSentEvent>>) : Op()
        class Leave(val actor: Actor, val done: CompletableDeferred<Unit>) : Op()
    }
}
