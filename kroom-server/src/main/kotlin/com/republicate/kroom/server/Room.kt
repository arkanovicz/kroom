package com.republicate.kroom.server

import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Base Room class for SSE-based real-time communication with generic state.
 *
 * A Room tracks both Users (persistent identities) and Actors (individual connections).
 * A User can have multiple simultaneous connections (Actors) to the same room,
 * for example when opening multiple browser tabs.
 *
 * @param S The type of room state
 * @param id Unique room identifier
 */
abstract class Room<S : Any>(val id: String) {
    protected val logger = LoggerFactory.getLogger("kroom.room")

    /**
     * Room state - must be initialized by subclass
     */
    abstract var state: S

    // Users present in this room (userId -> User)
    protected val users = ConcurrentHashMap<String, User>()

    // Actors (individual connections) - keyed by connectionId for event processing
    protected val actors = ConcurrentHashMap<String, Actor>()

    // Spectators (observers) - keyed by connectionId
    protected val spectators = ConcurrentHashMap<String, Spectator>()

    // Event queue for async processing
    private val eventQueue = Channel<Event>(Channel.UNLIMITED)

    // Message ID counter
    private val messageId = AtomicLong(1)

    private var processingJob: Job? = null

    // Coroutine scope
    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Participant counts (user-centric, not connection-centric)
    private val _userCount = MutableStateFlow(0)
    val userCount: StateFlow<Int> = _userCount.asStateFlow()

    private val _spectatorCount = MutableStateFlow(0)
    val spectatorCount: StateFlow<Int> = _spectatorCount.asStateFlow()

    // Legacy: actorCount now reflects unique users, not connections
    @Deprecated("Use userCount instead for unique users, or connectionCount for raw connections")
    val actorCount: StateFlow<Int> = _userCount

    /**
     * Total number of active connections (actors + spectators).
     */
    val connectionCount: Int
        get() = actors.size + spectators.size

    companion object {
        const val DEFAULT_HISTORY_BUFFER_SIZE = 50
    }

    // History buffer for Last-Event-ID replay (only allocated if needsHistory() returns true)
    private val historyBuffer: ArrayDeque<ServerSentEvent>? by lazy {
        if (needsHistory()) ArrayDeque(historyBufferSize) else null
    }

    /**
     * Override to enable Last-Event-ID replay on reconnect.
     * Default is false - games typically don't need history since state is sent on join.
     * Enable for chat-like rooms where history complements the current state.
     */
    protected open fun needsHistory(): Boolean = false

    /**
     * Buffer size for event history. Only used when needsHistory() returns true.
     */
    protected open val historyBufferSize: Int = DEFAULT_HISTORY_BUFFER_SIZE

    /**
     * Event names that should be buffered for Last-Event-ID replay.
     * Configurable at runtime - add event names to enable replay for specific events.
     * Example: `historicizableEvents.add("chat")`
     */
    protected open val historicizableEvents: MutableSet<String> = mutableSetOf()

    init {
        start()
    }

    private fun start() {
        processingJob = scope.launch {
            logger.info("Room '$id' started")
            while (isActive) {
                try {
                    val event = eventQueue.receive()
                    processEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error in room '$id'", e)
                }
            }
            logger.info("Room '$id' stopped")
        }
    }

    /**
     * Stop room and cleanup
     */
    fun stop() {
        processingJob?.cancel()
        scope.cancel()
        actors.values.forEach { it.disconnect() }
        spectators.values.forEach { it.disconnect() }
        // Clean up user connections to this room
        users.values.forEach { user ->
            user.connectionsByRoom.remove(id)
        }
        actors.clear()
        spectators.clear()
        users.clear()
        logger.info("Room '$id' stopped")
    }

    /**
     * Per-connection buffer: the loop never waits on a consumer (see [sendSafe]), and a join fills it
     * with the state frame plus the replay before the route starts draining.
     */
    protected open val channelCapacity: Int
        get() = maxOf(64, historyBufferSize * 2)

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

    /**
     * Actor joins the room.
     * @param actor The actor joining
     * @param lastEventId Optional Last-Event-ID from SSE reconnect header
     */
    suspend fun join(actor: Actor, lastEventId: String? = null): Channel<ServerSentEvent> {
        val channel = Channel<ServerSentEvent>(channelCapacity)
        actor.channel = channel

        replace(actor)

        // Register user and track this connection
        actor.user?.let { user ->
            val isNewUser = !users.containsKey(user.userId)
            users[user.userId] = user
            user.addConnection(id, actor)
            if (isNewUser) {
                _userCount.value = users.size
            }
        }

        logger.debug("Actor '${actor.name}' joined room '$id' (${users.size} users, ${actors.size} connections)")

        // First complete the join (seat assignment in Table), then send state
        onActorJoined(actor)
        sendStateTo(actor)

        // Replay missed events if history is enabled and lastEventId is valid
        replayIfNeeded(actor, lastEventId)

        return channel
    }

    /**
     * Spectator joins the room.
     * @param spectator The spectator joining
     * @param lastEventId Optional Last-Event-ID from SSE reconnect header
     */
    suspend fun joinAsSpectator(spectator: Spectator, lastEventId: String? = null): Channel<ServerSentEvent> {
        val channel = Channel<ServerSentEvent>(channelCapacity)
        spectator.channel = channel

        replace(spectator)
        _spectatorCount.value = spectators.size

        // Track spectator's user if authenticated
        spectator.user?.let { user ->
            users[user.userId] = user
            user.addConnection(id, spectator)
        }

        logger.debug("Spectator '${spectator.name}' joined room '$id' (${spectators.size} spectators)")

        // First complete the join, then send state
        onSpectatorJoined(spectator)
        sendStateTo(spectator)

        // Replay missed events if history is enabled and lastEventId is valid
        replayIfNeeded(spectator, lastEventId)

        return channel
    }

    /**
     * Actor or spectator leaves.
     */
    suspend fun leave(actor: Actor) {
        // a replaced connection (same tab back) leaves nothing: the newcomer owns the slot
        if (actors[actor.connectionId] !== actor && spectators[actor.connectionId] !== actor) return
        actor.disconnect()

        // Clean up user connection tracking
        val userFullyLeft = actor.user?.let { user ->
            val noMoreConnections = user.removeConnection(id, actor)
            if (noMoreConnections) {
                users.remove(user.userId)
                _userCount.value = users.size
            }
            noMoreConnections
        } ?: true  // Anonymous actors always "fully leave"

        if (actor is Spectator) {
            spectators.remove(actor.connectionId)
            _spectatorCount.value = spectators.size
            logger.debug("Spectator '${actor.name}' left room '$id'")
            onSpectatorLeft(actor)
        } else {
            actors.remove(actor.connectionId)
            logger.debug("Actor '${actor.name}' left room '$id' (userFullyLeft=$userFullyLeft)")
            onActorLeft(actor, userFullyLeft)
        }

        // Check if room should close
        if (shouldCloseWhenEmpty() && users.isEmpty() && actors.isEmpty()) {
            stop()
            Lobby.remove(id)
        }
    }

    /**
     * The room's reducer: called by [act] on the loop, never from a route — `state` needs no lock
     * here. Return the verdict, never data: a [ActionResult.Success] carrying an event is
     * broadcast, then `accepted` reaches the acting tab; an [ActionResult.Error] reaches it as
     * `rejected`. Side effects that need I/O are launched, not awaited: the verdict never waits.
     */
    abstract fun handleAction(actor: Actor, action: Json.Object): ActionResult

    /**
     * An actor's intent, queued on the loop. The returned promise is the verdict for whoever has
     * a reason to wait (a route answering an honest status, a test, a peer); the browser does not,
     * it reacts to the `accepted` / `rejected` frame like to any event.
     */
    fun act(actor: Actor, intent: Json.Object): Deferred<ActionResult> {
        val verdict = CompletableDeferred<ActionResult>()
        if (actor is Spectator) {
            verdict.complete(ActionResult.Error("spectators cannot act"))
        } else if (!eventQueue.trySend(Event.Act(actor, intent, verdict)).isSuccess) {
            verdict.complete(ActionResult.Error("room '$id' is closed"))
        }
        return verdict
    }

    /**
     * The route-side door: an intent arrives with a session and a tab id, not a connection. The
     * connection id is composed server-side ([Actor.connectionId]) so a tab id lands under its own
     * user, never under another's; a tab with no open stream still acts, as a detached actor, and
     * its feedback goes to the user's other connections if any.
     */
    suspend fun act(user: User, tabId: String?, intent: Json.Object): ActionResult {
        val connectionId = Actor.connectionId(user, tabId)
        val actor = getActor(connectionId) ?: Actor(connectionId, user)
        return act(actor, intent).await()
    }

    /**
     * Convert state to JSON for client.
     */
    abstract fun stateToJson(): Json.Object

    /**
     * Called when actor joins - override to customize.
     */
    protected open suspend fun onActorJoined(actor: Actor) {}

    /**
     * Called when actor leaves - override to customize.
     * @param actor The actor that left
     * @param userFullyLeft True if the user has no more connections to this room
     */
    protected open suspend fun onActorLeft(actor: Actor, userFullyLeft: Boolean) {
        // Default implementation for backward compatibility - subclasses can override
        @Suppress("DEPRECATION")
        onActorLeft(actor)
    }

    /**
     * @deprecated Override onActorLeft(actor, userFullyLeft) instead
     */
    @Deprecated("Override onActorLeft(actor, userFullyLeft) instead")
    protected open suspend fun onActorLeft(actor: Actor) {}

    /**
     * Called when spectator joins - override to customize.
     */
    protected open suspend fun onSpectatorJoined(spectator: Spectator) {}

    /**
     * Called when spectator leaves - override to customize.
     */
    protected open suspend fun onSpectatorLeft(spectator: Spectator) {}

    /**
     * Whether room should close when all users leave.
     */
    protected open fun shouldCloseWhenEmpty(): Boolean = true

    /**
     * Send current state to an actor.
     */
    protected open suspend fun sendStateTo(actor: Actor) {
        actor.channel?.send(ServerSentEvent(
            data = stateToJson().toString(),
            event = "state"
        ))
    }

    /**
     * Replay missed events to an actor if history is enabled.
     * Called after sendStateTo() - history complements the current state.
     * If lastEventId is null (fresh connect), replays entire buffer.
     * If lastEventId is present, replays events after that ID.
     */
    private suspend fun replayIfNeeded(actor: Actor, lastEventId: String?) {
        if (!needsHistory()) return

        val buffer = historyBuffer ?: return

        val eventsToReplay = if (lastEventId == null) {
            // Fresh connect: replay entire buffer
            synchronized(buffer) { buffer.toList() }
        } else {
            val clientId = lastEventId.toLongOrNull()
            if (clientId == null) {
                logger.warn("Invalid Last-Event-ID format: $lastEventId")
                return
            }

            val serverId = messageId.get()
            if (clientId > serverId) {
                // Server restart detected: client's ID is ahead of ours
                logger.info("Server restart detected for actor '${actor.name}': client lastEventId=$clientId > server messageId=$serverId")
                return
            }

            synchronized(buffer) {
                buffer.filter { sse ->
                    val eventId = sse.id?.toLongOrNull() ?: return@filter false
                    eventId > clientId
                }
            }
        }

        if (eventsToReplay.isNotEmpty()) {
            logger.debug("Replaying ${eventsToReplay.size} events to '${actor.name}' (lastEventId=${lastEventId ?: "none"})")
            eventsToReplay.forEach { sse ->
                sendSafe(actor, sse)
            }
        }
    }

    /**
     * Broadcast event to all (actors + spectators).
     */
    fun broadcast(event: String, data: Json.Object) {
        eventQueue.trySend(Event.Broadcast(event, data.toString()))
    }

    /**
     * Broadcast event to actors only.
     */
    fun broadcastToActors(event: String, data: Json.Object) {
        eventQueue.trySend(Event.BroadcastToActors(event, data.toString()))
    }

    /**
     * Broadcast event to spectators only.
     */
    fun broadcastToSpectators(event: String, data: Json.Object) {
        eventQueue.trySend(Event.BroadcastToSpectators(event, data.toString()))
    }

    /**
     * Send event to specific actor by connectionId.
     */
    fun sendTo(actor: Actor, event: String, data: Json.Object) {
        eventQueue.trySend(Event.Targeted(actor.connectionId, event, data.toString()))
    }

    /**
     * Send event to all connections of a specific user.
     */
    fun sendToUser(user: User, event: String, data: Json.Object) {
        user.getConnectionsInRoom(id).forEach { actor ->
            eventQueue.trySend(Event.Targeted(actor.connectionId, event, data.toString()))
        }
    }

    /**
     * Send event to a user by userId.
     */
    fun sendToUser(userId: String, event: String, data: Json.Object) {
        users[userId]?.let { sendToUser(it, event, data) }
    }

    /**
     * Process queued event.
     */
    private suspend fun processEvent(event: Event) {
        fun frame(name: String, data: String) =
            ServerSentEvent(data = data, event = name, id = messageId.incrementAndGet().toString())
        when (event) {
            is Event.Broadcast -> {
                val sse = frame(event.event, event.data)
                bufferIfHistoricizable(event.event, sse)
                sendToAll(sse)
            }
            is Event.BroadcastToActors -> {
                val sse = frame(event.event, event.data)
                bufferIfHistoricizable(event.event, sse)
                sendToActors(sse)
            }
            is Event.BroadcastToSpectators -> {
                val sse = frame(event.event, event.data)
                bufferIfHistoricizable(event.event, sse)
                sendToSpectators(sse)
            }
            is Event.Targeted -> sendToActor(event.actorId, frame(event.event, event.data))
            is Event.Act -> {
                val result = try {
                    handleAction(event.actor, event.intent)
                } catch (e: Exception) {
                    logger.error("Intent failed in room '$id'", e)
                    ActionResult.Error(e.message ?: "internal error")
                }
                // the handler's own broadcasts are already queued: the feedback lands after them,
                // so the acting tab sees the new state before its trigger is freed
                val type = event.intent.getString("type")
                when (result) {
                    is ActionResult.Success -> {
                        result.event?.let { broadcast(it, result.data ?: Json.Object()) }
                        eventQueue.trySend(Event.Feedback(event.actor, "accepted", Json.Object("intent" to type).toString()))
                    }
                    is ActionResult.Error ->
                        eventQueue.trySend(Event.Feedback(event.actor, "rejected", Json.Object("intent" to type, "reason" to result.message).toString()))
                }
                event.verdict.complete(result)
            }
            is Event.Feedback -> {
                val sse = frame(event.event, event.data)
                // the acting tab when its stream is open, else every tab of the user (a detached
                // actor, a stream that closed meanwhile); only a tab with that intent in flight reacts
                val connected = actors[event.actor.connectionId]
                if (connected != null) sendSafe(connected, sse)
                else event.actor.user?.getConnectionsInRoom(id)?.forEach { sendSafe(it, sse) }
            }
        }
    }

    /**
     * Buffer event for Last-Event-ID replay if event name is in historicizableEvents.
     */
    private fun bufferIfHistoricizable(eventName: String, sse: ServerSentEvent) {
        if (eventName !in historicizableEvents) return
        historyBuffer?.let { buffer ->
            synchronized(buffer) {
                buffer.addLast(sse)
                while (buffer.size > historyBufferSize) {
                    buffer.removeFirst()
                }
            }
        }
    }

    private suspend fun sendToAll(sse: ServerSentEvent) {
        actors.values.forEach { sendSafe(it, sse) }
        spectators.values.forEach { sendSafe(it, sse) }
    }

    private suspend fun sendToActors(sse: ServerSentEvent) {
        actors.values.forEach { sendSafe(it, sse) }
    }

    private suspend fun sendToSpectators(sse: ServerSentEvent) {
        spectators.values.forEach { sendSafe(it, sse) }
    }

    private suspend fun sendToActor(actorId: String, sse: ServerSentEvent) {
        actors[actorId]?.let { sendSafe(it, sse) }
            ?: spectators[actorId]?.let { sendSafe(it, sse) }
    }

    /**
     * The loop never waits on a consumer: a full buffer means a tab that stopped draining (frozen
     * in the background, slow link), and it is dropped — its stream ends, the browser reconnects
     * on its own with Last-Event-ID and gets state + replay. Nobody else pays.
     */
    private fun sendSafe(actor: Actor, sse: ServerSentEvent) {
        val channel = actor.channel ?: return
        if (channel.trySend(sse).isFailure) {
            logger.warn("Dropping '${actor.name}' from room '$id': not draining")
            actor.disconnect()
        }
    }

    /**
     * Get list of all actors (connections).
     */
    fun getActors(): List<Actor> = actors.values.toList()

    /**
     * Get list of all users (unique identities).
     */
    fun getUsers(): List<User> = users.values.toList()

    /**
     * Get list of spectators.
     */
    fun getSpectators(): List<Spectator> = spectators.values.toList()

    /**
     * Get an actor by connectionId.
     */
    fun getActor(connectionId: String): Actor? = actors[connectionId] ?: spectators[connectionId]

    /**
     * Get a user by userId.
     */
    fun getUser(userId: String): User? = users[userId]

    /**
     * Room info for lobby.
     */
    open fun toJson(): Json.Object = Json.Object(
        "id" to id,
        "userCount" to users.size,
        "connectionCount" to actors.size,
        "spectatorCount" to spectators.size,
        "users" to Json.Array(users.values.map { Json.Object("userId" to it.userId, "name" to it.displayName) }),
        // Legacy field for backward compatibility
        "actorCount" to users.size,
        "actors" to Json.Array(actors.values.map { Json.Object("id" to it.connectionId, "userId" to it.user?.userId, "name" to it.name) })
    )

    // Event types for queue
    sealed class Event {
        data class Broadcast(val event: String, val data: String) : Event()
        data class BroadcastToActors(val event: String, val data: String) : Event()
        data class BroadcastToSpectators(val event: String, val data: String) : Event()
        data class Targeted(val actorId: String, val event: String, val data: String) : Event()
        class Act(val actor: Actor, val intent: Json.Object, val verdict: CompletableDeferred<ActionResult>) : Event()
        class Feedback(val actor: Actor, val event: String, val data: String) : Event()
    }
}

/**
 * Result of handling an action.
 */
sealed class ActionResult {
    /** Action succeeded, optionally broadcast an event */
    data class Success(
        val event: String? = null,
        val data: Json.Object? = null
    ) : ActionResult()

    /** Action failed with error message */
    data class Error(val message: String) : ActionResult()
}
