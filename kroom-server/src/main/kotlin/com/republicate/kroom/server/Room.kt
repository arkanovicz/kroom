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

/** Born by the room at a connect: the handler hears every one, the room's wire a user's first. */
class Join(json: Json.MutableObject = Json.MutableObject()) : Event("join", json = json) {
    var user by string()
    var displayName by string()
    var spectator by bool()
    var first by bool()      // the user's first connection to the room
}

/**
 * Born by the room at a disconnect: the handler hears a connection that was not the user's last at
 * once, the last after the grace, and not at all when the user is back within it; the room's wire
 * hears the last only.
 */
class Leave(json: Json.MutableObject = Json.MutableObject()) : Event("leave", json = json) {
    var user by string()
    var displayName by string()
    var spectator by bool()
    var last by bool()       // the user's last connection to the room
}

/**
 * A room: state, membership (users; actors write, spectators read), one loop, and the events
 * it speaks. An [Event] is validated ([act]), applied on the loop by the handler its declaration
 * registered ([on]), and framed to its audience, the emitter among them: the stream carries the
 * facts, the verdict is the reply. Every frame's id is its position on the room's channel (seq);
 * a step of the loop numbers and writes everything it produces before it returns, so a snapshot
 * taken at a position holds exactly the transitions up to it.
 *
 * A join is an event too: the room births [Join] at a connect; the joiner gets its `state`
 * snapshot ([stateToJson]) first, then its own `join` like everyone, the handler's emissions before it.
 */
abstract class Room<S : Any>(val id: String) {
    protected val logger = LoggerFactory.getLogger("kroom.room")
    // the thread inside a step: what a handler emits is framed at once, nothing a step produces is re-enqueued
    @Volatile private var loopThread: Thread? = null
    private val onLoop: Boolean get() = Thread.currentThread() === loopThread

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
     * Validation, stateless (the declared shape and nothing else, the permission, not a spectator),
     * then the loop. A refusal here or there is answered, never framed: the returned event is
     * `REJECTED` with its reason. The promise is the event in its final stage.
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
     * The route's entry: a session and a client id, not a connection. The name is looked up in the
     * [catalog]; the connection id is composed server-side so the client lands under its own user.
     */
    suspend fun act(user: User, tab: String?, name: String, json: Json.MutableObject): Event {
        val connectionId = Actor.connectionId(user, tab)
        val actor = getActor(connectionId) ?: Actor(connectionId, user)
        val event = catalog.of(name, json) ?: return Generic(name, json).reject(Reason("unknown")).also(::trace)
        return act(actor, event).await()
    }

    // ---- emissions

    /**
     * A room-born event, or one the handler made for others. From inside a step (a handler) it is
     * numbered and framed at once, before the step returns: a snapshot taken later holds it and
     * never sees its frame. From elsewhere (a bridge, a clock) it is a step of its own, queued.
     * [to] names the users of a `USERS` audience; none means the emitter. A `BORN` event applies
     * here: it had no door.
     */
    fun emit(event: Event, to: Collection<User> = emptyList()) {
        if (event.stage == Stage.BORN) event.apply()
        val userIds = to.map { it.userId }
        if (onLoop) frame(event, userIds) else queue.trySend(Op.Emit(event, userIds))
    }

    /** Runs [block] as a step of the loop: what it changes and emits is one instant. */
    protected fun step(block: () -> Unit) {
        if (onLoop) block() else queue.trySend(Op.Run(block))
    }

    fun emit(event: Event, vararg to: User) = emit(event, to.toList())

    /** Convenience: a room-born [Generic] to the room. */
    fun broadcast(name: String, data: Json.Object) = emit(Generic(name, Json.MutableObject(data)))

    /** Convenience: a room-born [Generic] to one user, every tab of theirs. */
    fun sendToUser(user: User, name: String, data: Json.Object) = emit(Generic(name, Json.MutableObject(data), Audience.USERS), user)

    fun sendToUser(userId: String, name: String, data: Json.Object) { users[userId]?.let { sendToUser(it, name, data) } }

    // ---- connections

    /**
     * A connection joins. On the loop like everything else: the newcomer gets `state`, the room
     * as it is at that position, then every transition after it — its own `join` first, with
     * what the join handler emits. A stopped room hands back a closed channel: the stream ends,
     * the browser retries and learns the room is gone.
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

    /** How long (ms) a user's last connection may be gone before the room says `leave`: a reload, a blip. */
    protected open val leaveGrace: Long = 1000

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

        // the snapshot at this position, straight on the channel; the join is the transition after it
        sendSafe(actor, ServerSentEvent(data = stateToJson(actor).toString(), event = "state", id = seq.get().toString()))
        val join = Join().also { it.user = actor.user?.userId; it.displayName = actor.name; it.spectator = spectator; it.first = first }
        join.meta.from = actor.user?.userId
        handle(actor, join)
        // the handler hears every connection, the wire hears a user's first
        if (first && join.stage == Stage.APPLIED) emit(join)
    }

    private fun processLeave(actor: Actor) {
        // a replaced connection (same tab back) leaves nothing: the newcomer owns the slot
        if (actors[actor.connectionId] !== actor && spectators[actor.connectionId] !== actor) return
        actor.disconnect()
        val spectator = actor is Spectator
        if (spectator) { spectators.remove(actor.connectionId); _spectatorCount.value = spectators.size }
        else actors.remove(actor.connectionId)
        val user = actor.user
        val none = user?.removeConnection(id, actor) ?: true
        logger.debug("${if (spectator) "Spectator" else "Actor"} '${actor.name}' left room '$id' (last=$none)")
        if (user != null && none && leaveGrace > 0) {
            // the user's last connection: a reload or a blip is back within the grace, a departure is not
            scope.launch {
                delay(leaveGrace)
                step { if (users.containsKey(user.userId) && !user.isConnectedTo(id)) gone(actor, spectator, true) }
            }
        } else gone(actor, spectator, none)
    }

    /** The leave itself: the handler hears every connection, the wire hears a user's last. */
    private fun gone(actor: Actor, spectator: Boolean, last: Boolean) {
        val user = actor.user
        if (last && user != null) { users.remove(user.userId); _userCount.value = users.size }
        val leave = Leave().also { it.user = user?.userId; it.displayName = actor.name; it.spectator = spectator; it.last = last }
        leave.meta.from = user?.userId
        handle(actor, leave)
        if (last && leave.stage == Stage.APPLIED) emit(leave)

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

    /** One step: what it changes and what it frames are one instant, numbered before it returns. */
    private fun process(op: Op) {
        loopThread = Thread.currentThread()
        try {
            when (op) {
                is Op.Act -> {
                    handle(op.actor, op.event)
                    // the handler's own emissions are already framed: the event's frame lands after them
                    if (op.event.stage == Stage.APPLIED) frame(op.event, emptyList())
                    op.verdict.complete(op.event)
                }
                is Op.Emit -> frame(op.event, op.to)
                is Op.Run -> op.block()
                is Op.Join -> try { processJoin(op.actor) } finally { op.ready.complete(op.actor.channel ?: closed()) }
                is Op.Leave -> try { processLeave(op.actor) } finally { op.done.complete(Unit) }
            }
        } finally {
            loopThread = null
        }
    }

    /** Numbers the event at the channel's next position and writes it to its audience. */
    private fun frame(event: Event, to: List<String>) {
        val emitted = event.emit(seq.incrementAndGet())
        trace(emitted)
        val frame = ServerSentEvent(data = emitted.json.toString(), event = emitted.name, id = emitted.meta.seq.toString())
        if (emitted.audience == Audience.ROOM) toAll(frame)
        else toUsers(to.ifEmpty { listOfNotNull(emitted.meta.from) }, frame)
        emitted(emitted)
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
        class Run(val block: () -> Unit) : Op()
        class Join(val actor: Actor, val ready: CompletableDeferred<Channel<ServerSentEvent>>) : Op()
        class Leave(val actor: Actor, val done: CompletableDeferred<Unit>) : Op()
    }
}
