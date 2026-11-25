package com.republicate.kroom.server

import com.republicate.kroom.event.Audience
import com.republicate.kroom.event.Client
import com.republicate.kroom.event.Enter
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Exit
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Presence
import com.republicate.kroom.event.Present
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.RoomEvents
import com.republicate.kroom.event.Roster
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.UserPresence
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An event with no declaration: what [Room.broadcast] births, what the door rejects by name. */
class Generic(name: String, json: Json.MutableObject = Json.MutableObject(), audience: Audience = Audience.ROOM) :
    Event(name, audience, json = json)

/** What a caller is to a room ([Room.role]): one who acts, or one who watches. */
enum class Role { ACTOR, SPECTATOR }

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
 *
 * [lobby] is the lobby of the room's group, null when it is in none. A private room names its lobby too:
 * whether it shows there is [show]'s answer.
 */
abstract class Room<S : Any>(val id: String, val events: RoomEvents = RoomEvents(), val lobby: Lobby<*, *>? = null) {
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

    private val handlers = HashMap<String, (Actor, Event) -> Reason?>()
    private val system = setOf("join", "leave")

    /**
     * One declaration: the class (its factory, for the door; its [events] have it as postable) and its handler (for the loop).
     * The handler applies the event to the state and answers null, or a [Reason] to reject it;
     * a thrown [Event.Rejected] is the same, any other exception is `error`, logged. It runs on
     * the loop: no awaited I/O. What it [emit]s lands before the event's own frame.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <E : Event> on(factory: (Json.MutableObject) -> E, handler: (Actor, E) -> Reason?) {
        val name = factory(Json.MutableObject()).name
        if (name !in system) events.register(factory)
        handlers[name] = { actor, event -> handler(actor, event as E) }
    }

    /** Whether [user] holds [permission] here; the host's call (authoring's roles), everything by default. */
    protected open fun permits(user: User, permission: String): Boolean = true

    /**
     * What [user] (null: anonymous) is here, asked of every call a route serves: the stream (an actor or a
     * spectator), each intent (an actor's), the scroll-back and the catalog (anyone admitted); null admits nobody,
     * 401 when anonymous, 403 otherwise. It runs on the loop, the room's critical section: it may read the state
     * (who sits where, who is a member) as the same step sees it, and must not wait. An anonymous caller is at most
     * a spectator. Signed-in callers act by default.
     */
    protected open fun role(user: User?): Role? = user?.let { Role.ACTOR }

    private fun roleOf(user: User?): Role? = try {
        role(user)?.let { if (user == null) Role.SPECTATOR else it }
    } catch (e: Exception) {
        logger.error("role of '${user?.userId}' failed in room '$id'", e)
        null
    }

    /** Every stage change of every event: the operator's line. */
    protected open fun trace(event: Event) {
        if (logger.isDebugEnabled) logger.debug("{} {} {}", id, event.name, event.meta.json)
    }

    /** An event just went on the wire, on the loop: what the room does at once (the store is [persist]'s). */
    protected open fun emitted(event: Event) {}

    /**
     * Every frame, in the room's order, off the loop: where a store writes, and where what the loop cannot decide
     * (a lookup in the store) runs, its answer coming back as a step ([emit]). It gets a copy taken on the loop;
     * the event goes `PERSISTED` once it returns. The loop never waits on it; a stopping room drains it.
     */
    protected open suspend fun persist(event: Event) {}

    // one writer, in seq order: persist() sees the frames as the room numbered them
    private val writer = Channel<Pair<Event, Event>>(Channel.UNLIMITED)

    /**
     * A frame the whole room gets, numbered, goes into the marking before anyone reads it: where a
     * [State] reduces. A frame some users get (`to`) is a message, not a transition of the shared
     * marking: folding it would put in the snapshot what the others never received.
     */
    protected open fun fold(frame: Event) {}

    /** An intent (or a join, a leave) is framed: what its firing produced follows, on the same step. */
    protected open fun follow(intent: Event) {}

    /** The alphabet as data: what `GET /rooms/{room}/events` serves. */
    open fun describe(): Json.Object = Json.Object("events" to events.describe())

    init {
        on(::Join) { _, _ -> null }
        on(::Leave) { _, _ -> null }
        start()
        // its own scope: a stopping room closes the channel, the writer drains it, then ends
        CoroutineScope(Dispatchers.IO).launch {
            for ((event, copy) in writer) {
                try { persist(copy) } catch (e: Exception) { logger.error("persisting '${event.name}' failed in room '$id'", e) }
                trace(event.persist())
            }
        }
    }

    /** Built by the [Rooms] on first use: such a room closes once idle (see [shouldCloseWhenEmpty]). */
    internal var onDemand = false

    /** False once stopped: a stopped room answers nothing (a join gets a closed channel, an act `closed`). */
    val running: Boolean get() = !queue.isClosedForSend

    // each time the room empties, a timer of its own: one that finds the room busied since does nothing
    private val idleEpoch = AtomicLong(0)

    private val empty: Boolean get() = users.isEmpty() && actors.isEmpty() && spectators.isEmpty()

    /** The room just emptied, or was just built: it closes after [idleTimeout] if nobody came meanwhile. */
    internal fun idle() {
        val epoch = idleEpoch.incrementAndGet()
        scope.launch {
            delay(idleTimeout)
            step {
                if (idleEpoch.get() == epoch && empty && shouldCloseWhenEmpty()) {
                    logger.info("Room '$id' idle for {} ms: closing", idleTimeout)
                    stop()
                    Rooms.forget(this@Room)
                }
            }
        }
    }

    private fun start() {
        processingJob = scope.launch {
            logger.info("Room '$id' started")
            while (isActive) {
                val op = queue.receiveCatching().getOrNull() ?: break   // stopped: the queue is closed
                try {
                    process(op)
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
        // what was queued behind the stop gets its answer: closed
        while (true) queue.tryReceive().getOrNull()?.abort() ?: break
        processingJob?.cancel()
        scope.cancel()
        actors.values.forEach { it.disconnect() }
        spectators.values.forEach { it.disconnect() }
        users.values.forEach { it.connectionsByRoom.remove(id) }
        actors.clear(); spectators.clear(); users.clear(); lost.clear(); drops.clear()
        writer.close()
        lobby?.release(this)
        logger.info("Room '$id' stopped")
    }

    // ---- the door

    /**
     * Validation, stateless (the declared shape and nothing else, the permission, not a spectator),
     * then the loop. A refusal here or there is answered, never framed: the returned event is
     * `REJECTED` with its reason. The promise is the event in its final stage.
     */
    fun act(actor: Actor, event: Event): Deferred<Event> = CompletableDeferred<Event>().also { act(actor, event, it, gated = false) }

    private fun act(actor: Actor, event: Event, verdict: CompletableDeferred<Event>, gated: Boolean) {
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
                verdict.complete(event.reject(e.reason).also(::trace))
                return
            }
        }
        if (refused != null) { verdict.complete(event.reject(refused).also(::trace)); return }
        trace(event)
        if (!queue.trySend(Op.Act(actor, event, verdict, gated)).isSuccess) verdict.complete(event.reject(Reason("closed")))
    }

    /**
     * The route's entry: a session and a client id, not a connection. The name is looked up in the
     * [catalog]; the connection id is composed server-side so the client lands under its own user. [role] decides
     * on the loop, in the step that applies it: `spectator`, or `forbidden` when the user is nobody here.
     */
    suspend fun act(user: User, tab: String?, name: String, json: Json.MutableObject): Event {
        val connectionId = Actor.connectionId(user, tab)
        // the role, not how the tab joined, says who acts: a spectator seated since acts from its next intent
        val actor = getActor(connectionId)?.takeIf { it !is Spectator } ?: Actor(connectionId, user)
        val event = events.of(name, json) ?: return Generic(name, json).reject(Reason("unknown")).also(::trace)
        return CompletableDeferred<Event>().also { act(actor, event, it, gated = true) }.await()
    }

    // ---- emissions

    /**
     * A room-born event, or one the handler made for others. From inside a step (a handler) it is
     * numbered and framed at once, before the step returns: a snapshot taken later holds it and
     * never sees its frame. From elsewhere (a bridge, a clock) it is a step of its own, queued.
     * [to] names the users of a `USERS` audience; none means the emitter. A `BORN` event applies
     * here: it had no door.
     */
    open fun emit(event: Event, to: Collection<User> = emptyList()) {
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

    /**
     * The route's join: [role] decides on the loop, in the step that joins — an [Actor], a [Spectator] (an
     * anonymous one under a fresh id: a tab id is no credential), or null, nobody. Throws
     * [ClosedSendChannelException] when the room is stopped.
     */
    internal suspend fun connect(user: User?, tab: String?): Actor? = ask {
        when (roleOf(user)) {
            null -> null
            Role.ACTOR -> Actor(Actor.connectionId(user!!, tab), user)
            Role.SPECTATOR -> user?.let { Spectator(Actor.connectionId(it, tab), it) } ?: Spectator(java.util.UUID.randomUUID().toString())
        }?.also(::processJoin)
    }

    /** [role], asked on the loop; throws [ClosedSendChannelException] when the room is stopped. */
    internal suspend fun admit(user: User?): Role? = ask { roleOf(user) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> ask(block: () -> T): T {
        val answer = CompletableDeferred<Any?>()
        if (!queue.trySend(Op.Ask(block, answer)).isSuccess) throw ClosedSendChannelException("room '$id' is closed")
        return answer.await() as T
    }

    /** A connection leaves; returns once the room has forgotten it, a cancelled caller (a stream the client closed) too. */
    suspend fun leave(actor: Actor) {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Op.Leave(actor, done)).isSuccess) withContext(NonCancellable) { done.await() }
    }

    private fun closed() = Channel<ServerSentEvent>(0).also { it.close() }

    /** Per-connection buffer: the loop never waits on a consumer (see [sendSafe]). */
    protected open val channelCapacity: Int = 64

    /**
     * How long (ms) a user's last connection may be gone before the room says anything: a reload, a blip. Past it,
     * `leave`, or LOST first when the room has a [lostGrace]; 0 with a [lostGrace] says LOST at the drop itself.
     * A connection gone beside another of its user says nothing on the wire; its handler hears the leave (`last`
     * false) once both graces are over, and not at all when the same tab is back within them.
     */
    protected open val leaveGrace: Long = 1000

    /**
     * How long (ms) a user may stay LOST (past [leaveGrace], no client left, a `status` said so) before the room
     * says `leave`; 0 goes straight to `leave` after [leaveGrace], with no LOST frame.
     */
    protected open val lostGrace: Long = 0

    /**
     * The room's own position, for the `leave`'s `read` (read on the loop at the drop): a numbering of its own that a
     * store marks a user read up to — a chat's last line. Null when the room has none; the frame position is `seen`.
     */
    protected open val read: Long? get() = null

    // loop-confined: who is LOST, and each user's last drop (room-wide numbering: a delayed step of an older drop is a no-op)
    private val lost = HashSet<String>()
    private val drops = HashMap<String, Long>()
    private var dropCount = 0L
    // and each connection's, when the user keeps another: its leave waits the same graces, unless the tab is back
    private val closed = HashMap<String, Long>()

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
        closed.remove(actor.connectionId)
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
        val join = Join().also { it.user = actor.user?.let(::presence); it.first = first; it.count = users.size }
        join.meta.from = actor.user?.userId
        handle(actor, join)
        // the handler hears every connection, the wire hears a user's first; what the handler emits follows either way
        if (join.stage == Stage.APPLIED) { if (first) emit(join); follow(join) }
        // back from LOST: never left, so no join, a status
        actor.user?.let { user -> if (lost.remove(user.userId)) status(actor, user) }
    }

    /** A room-born `status`: the user's composed presence, [actor] the client that changed. */
    private fun status(actor: Actor, user: User) {
        val status = Status().also {
            it.user = user.userId
            it.client = Client().also { c -> c.id = actor.tab; c.kind = actor.kind; c.status = actor.status }
            it.status = presence(user).status!!
        }
        status.meta.from = user.userId
        handle(actor, status)
        if (status.stage == Stage.APPLIED) { emit(status); follow(status) }
    }

    /** A user as presence sees it now: its status composed from its clients' in this room. */
    protected fun presence(user: User): UserPresence = UserPresence().also { p ->
        p.id = user.userId
        p.name = user.displayName
        p.avatar = user.avatar
        val clients = user.getConnectionsInRoom(id)
        clients.forEach { c -> p.clients += Client().also { it.id = c.tab; it.kind = c.kind; it.status = c.status } }
        p.status = clients.minOfOrNull { it.status } ?: Presence.LOST
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
        val seen = seq.get()   // the last frame this connection may have received; the graces' frames come after
        val read = read        // and the room's own position then, when it has one
        logger.debug("${if (spectator) "Spectator" else "Actor"} '${actor.name}' left room '$id' (last=$none)")
        if (user != null && none && (leaveGrace > 0 || lostGrace > 0)) {
            // the user's last connection: a reload or a blip is back within the graces, a departure is not
            val drop = ++dropCount
            drops[user.userId] = drop
            fun away() = drops[user.userId] == drop && users.containsKey(user.userId) && !user.isConnectedTo(id)
            fun lose() {
                actor.status = Presence.LOST
                status(actor, user)
                lost += user.userId
                scope.launch {
                    delay(lostGrace)
                    step { if (away() && user.userId in lost) gone(actor, spectator, true, seen, read) }
                }
            }
            if (leaveGrace == 0L) lose()   // LOST at the drop itself
            else scope.launch {
                delay(leaveGrace)
                step {
                    if (!away()) return@step
                    if (lostGrace == 0L) gone(actor, spectator, true, seen, read) else lose()
                }
            }
        } else if (user != null && !none && leaveGrace + lostGrace > 0) {
            // the user stays, by another connection: nothing for the wire, and the handler hears this one once the
            // graces a last one gets are over — a tab reloaded beside another never left either
            val connection = actor.connectionId
            val drop = ++dropCount
            closed[connection] = drop
            scope.launch {
                delay(leaveGrace + lostGrace)
                step { if (closed[connection] == drop) { closed.remove(connection); gone(actor, spectator, false, seen, read) } }
            }
        } else gone(actor, spectator, none, seen, read)
    }

    /** The leave itself: the handler hears every connection, the wire hears a user's last; [seen], [read] as of the drop. */
    private fun gone(actor: Actor, spectator: Boolean, last: Boolean, seen: Long, read: Long?) {
        val user = actor.user
        if (last && user != null) { users.remove(user.userId); lost.remove(user.userId); drops.remove(user.userId); _userCount.value = users.size }
        val leave = Leave().also { it.user = user?.let(::presence); it.last = last; it.count = users.size; it.seen = seen; it.read = read }
        leave.meta.from = user?.userId
        handle(actor, leave)
        if (leave.stage == Stage.APPLIED) { if (last) emit(leave); follow(leave) }

        if (empty) idle()
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
                    val refused = if (!op.gated) null else when (roleOf(op.actor.user)) {
                        Role.ACTOR -> null
                        Role.SPECTATOR -> Reason("spectator")
                        null -> Reason("forbidden")
                    }
                    if (refused != null) trace(op.event.reject(refused)) else handle(op.actor, op.event)
                    // the handler's own emissions are already framed: the event's frame lands after them, its firing follows
                    if (op.event.stage == Stage.APPLIED) { frame(op.event, emptyList()); follow(op.event) }
                    op.verdict.complete(op.event)
                }
                is Op.Emit -> frame(op.event, op.to)
                is Op.Run -> op.block()
                is Op.Join -> try { processJoin(op.actor) } finally { op.ready.complete(op.actor.channel ?: closed()) }
                is Op.Leave -> try { processLeave(op.actor) } finally { op.done.complete(Unit) }
                is Op.Ask -> try { op.answer.complete(op.block()) } catch (e: Exception) { op.answer.completeExceptionally(e); throw e }
            }
        } finally {
            loopThread = null
        }
    }

    /**
     * Numbers the event at the channel's next position, folds it, and writes it: to the users it
     * names (`to`), else to the room — or, a `USERS` event of the `on()` path, to its emitter. Each
     * connection gets its user's view: one frame for all, unless a field is selective.
     */
    private fun frame(event: Event, to: List<String>) {
        val emitted = event.sequence(seq.incrementAndGet())
        trace(emitted)
        val frame = views(emitted)
        val targets = to.ifEmpty { emitted.to }
        when {
            targets.isNotEmpty() -> toUsers(targets, frame)
            emitted.audience == Audience.USERS -> toUsers(listOfNotNull(emitted.meta.from), frame)
            else -> { fold(emitted); toAll(frame); relay(emitted) }
        }
        emitted(emitted)
        // copied on the loop, before anything may touch what the State kept of it; the metadata too (seq, from)
        val json = emitted.json.copy()
        val copy = events.frame(emitted.name, json) ?: Generic(emitted.name, json, emitted.audience)
        copy.meta.json.putAll(emitted.meta.json.copy())
        writer.trySend(emitted to copy)
    }

    /** The frame a connection gets, serialized once per user (or once, when the event is the same for everyone). */
    private fun views(event: Event): (Actor) -> ServerSentEvent {
        fun of(viewer: String?) = ServerSentEvent(data = event.view(viewer).toString(), event = event.name, id = event.meta.seq.toString())
        if (!event.selective) return of(null).let { all -> { all } }
        val byUser = HashMap<String?, ServerSentEvent>()
        return { actor -> actor.user?.userId.let { byUser.getOrPut(it) { of(it) } } }
    }

    private fun toAll(frame: (Actor) -> ServerSentEvent) {
        actors.values.forEach { sendSafe(it, frame(it)) }
        spectators.values.forEach { sendSafe(it, frame(it)) }
    }

    private fun toUsers(userIds: Collection<String>, frame: (Actor) -> ServerSentEvent) {
        userIds.forEach { userId -> users[userId]?.getConnectionsInRoom(id)?.forEach { sendSafe(it, frame(it)) } }
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

    // ---- the lobby

    /**
     * Does the room show in its [lobby]? Asked on the loop. [roster] arrives with the room's id and users, of the
     * lobby's own class: the room adds its fields. False (a private room): nothing of the room reaches the lobby's
     * State or wire.
     */
    protected open fun show(roster: Roster): Boolean = true

    /**
     * Asks [show] again and tells the lobby: the roster whole, or that it is gone. For a room whose access changed.
     * The room calls it itself at its first user.
     */
    fun announce() = step {
        lobby?.let { lobby ->
            announced = true
            val roster = lobby.roster(id).also { r -> presences().forEach { r.users.add(it) } }
            lobby.shown(this, roster.takeIf { show(it) })
        }
    }

    // loop-confined: the lobby was told once, so a join is an `enter` from then on
    private var announced = false

    /** Who is in, as a roster lists them. */
    internal open fun presences(): List<UserPresence> = users.values.map(::presence)

    /** What the room's own wire says of presence, said to its lobby. */
    private fun relay(event: Event) {
        val lobby = lobby ?: return
        when (event) {
            is Join -> if (!announced) announce() else event.user?.let { user ->
                lobby.emit(Enter().also { it.room = id; it.user = UserPresence(user.json.copy()) })
            }
            is Status -> event.user?.let { user -> lobby.emit(Present().also { it.room = id; it.user = user; it.status = event.status }) }
            is Leave -> event.user?.let { user -> lobby.emit(Exit().also { it.room = id; it.user = user.id }) }
            else -> {}
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

    /** Whether the room closes once idle: a room built on demand does, one the host built lives with the app. */
    protected open fun shouldCloseWhenEmpty(): Boolean = onDemand

    /** How long (ms) an empty room waits before closing: whatever it still had to write is written long before. */
    protected open val idleTimeout: Long = 30_000

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
        "events" to events.describe()
    )

    private fun Op.abort() {
        when (this) {
            is Op.Act -> verdict.complete(event.reject(Reason("closed")))
            is Op.Join -> ready.complete(closed())
            is Op.Leave -> done.complete(Unit)
            is Op.Ask -> answer.completeExceptionally(ClosedSendChannelException("room '$id' is closed"))
            is Op.Emit, is Op.Run -> {}
        }
    }

    private sealed class Op {
        class Act(val actor: Actor, val event: Event, val verdict: CompletableDeferred<Event>, val gated: Boolean) : Op()
        class Emit(val event: Event, val to: List<String>) : Op()
        class Run(val block: () -> Unit) : Op()
        class Join(val actor: Actor, val ready: CompletableDeferred<Channel<ServerSentEvent>>) : Op()
        class Leave(val actor: Actor, val done: CompletableDeferred<Unit>) : Op()
        class Ask(val block: () -> Any?, val answer: CompletableDeferred<Any?>) : Op()
    }
}
