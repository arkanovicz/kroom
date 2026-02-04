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
 * Base Room class for SSE-based real-time communication with generic state
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

    // Actors (active participants)
    protected val actors = ConcurrentHashMap<String, Actor>()

    // Spectators (observers)
    protected val spectators = ConcurrentHashMap<String, Spectator>()

    // Event queue for async processing
    private val eventQueue = Channel<Event>(Channel.UNLIMITED)

    // Message ID counter
    private val messageId = AtomicLong(1)

    // Keep-alive tracking
    private var lastEventTime = System.currentTimeMillis()
    private var processingJob: Job? = null

    // Coroutine scope
    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Participant counts
    private val _actorCount = MutableStateFlow(0)
    val actorCount: StateFlow<Int> = _actorCount.asStateFlow()

    private val _spectatorCount = MutableStateFlow(0)
    val spectatorCount: StateFlow<Int> = _spectatorCount.asStateFlow()

    companion object {
        const val KEEPALIVE_DELAY = 15_000L  // 15 seconds
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
     * Override to specify which events are historicizable (e.g., "chat").
     */
    protected open val historicizableEvents: Set<String> = emptySet()

    init {
        start()
    }

    private fun start() {
        processingJob = scope.launch {
            logger.info("Room '$id' started")
            while (isActive) {
                try {
                    val event = withTimeoutOrNull(KEEPALIVE_DELAY) {
                        eventQueue.receive()
                    }
                    if (event != null) {
                        processEvent(event)
                        lastEventTime = System.currentTimeMillis()
                    } else {
                        keepAlive()
                    }
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
        actors.clear()
        spectators.clear()
        logger.info("Room '$id' stopped")
    }

    /**
     * Actor joins the room
     * @param actor The actor joining
     * @param lastEventId Optional Last-Event-ID from SSE reconnect header
     */
    suspend fun join(actor: Actor, lastEventId: String? = null): Channel<ServerSentEvent> {
        val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
        actor.channel = channel

        actors[actor.connectionId] = actor
        _actorCount.value = actors.size

        logger.debug("Actor '${actor.name}' joined room '$id' (${actors.size} actors)")

        // First complete the join (seat assignment in Table), then send state
        onActorJoined(actor)
        sendStateTo(actor)

        // Replay missed events if history is enabled and lastEventId is valid
        replayIfNeeded(actor, lastEventId)

        return channel
    }

    /**
     * Spectator joins the room
     * @param spectator The spectator joining
     * @param lastEventId Optional Last-Event-ID from SSE reconnect header
     */
    suspend fun joinAsSpectator(spectator: Spectator, lastEventId: String? = null): Channel<ServerSentEvent> {
        val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
        spectator.channel = channel

        spectators[spectator.connectionId] = spectator
        _spectatorCount.value = spectators.size

        logger.debug("Spectator '${spectator.name}' joined room '$id' (${spectators.size} spectators)")

        // First complete the join, then send state
        onSpectatorJoined(spectator)
        sendStateTo(spectator)

        // Replay missed events if history is enabled and lastEventId is valid
        replayIfNeeded(spectator, lastEventId)

        return channel
    }

    /**
     * Actor or spectator leaves
     */
    suspend fun leave(actor: Actor) {
        actor.disconnect()

        if (actor is Spectator) {
            spectators.remove(actor.connectionId)
            _spectatorCount.value = spectators.size
            logger.debug("Spectator '${actor.name}' left room '$id'")
            onSpectatorLeft(actor)
        } else {
            actors.remove(actor.connectionId)
            _actorCount.value = actors.size
            logger.debug("Actor '${actor.name}' left room '$id'")
            onActorLeft(actor)
        }

        // Check if room should close
        if (shouldCloseWhenEmpty() && actors.isEmpty()) {
            stop()
            Lobby.remove(id)
        }
    }

    /**
     * Handle an action from an actor
     * Returns the result to broadcast or send
     */
    abstract fun handleAction(actor: Actor, action: Json.Object): ActionResult

    /**
     * Convert state to JSON for client
     */
    abstract fun stateToJson(): Json.Object

    /**
     * Called when actor joins - override to customize
     */
    protected open suspend fun onActorJoined(actor: Actor) {}

    /**
     * Called when actor leaves - override to customize
     */
    protected open suspend fun onActorLeft(actor: Actor) {}

    /**
     * Called when spectator joins - override to customize
     */
    protected open suspend fun onSpectatorJoined(spectator: Spectator) {}

    /**
     * Called when spectator leaves - override to customize
     */
    protected open suspend fun onSpectatorLeft(spectator: Spectator) {}

    /**
     * Whether room should close when all actors leave
     */
    protected open fun shouldCloseWhenEmpty(): Boolean = true

    /**
     * Send current state to an actor
     */
    protected open suspend fun sendStateTo(actor: Actor) {
        actor.channel?.send(ServerSentEvent(
            data = stateToJson().toString(),
            event = "state"
        ))
    }

    /**
     * Replay missed events to an actor if history is enabled and lastEventId is valid.
     * Called after sendStateTo() - history complements the current state.
     */
    private suspend fun replayIfNeeded(actor: Actor, lastEventId: String?) {
        if (!needsHistory() || lastEventId == null) return

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

        val buffer = historyBuffer ?: return
        val eventsToReplay = synchronized(buffer) {
            buffer.filter { sse ->
                val eventId = sse.id?.toLongOrNull() ?: return@filter false
                eventId > clientId
            }
        }

        if (eventsToReplay.isNotEmpty()) {
            logger.debug("Replaying ${eventsToReplay.size} events to '${actor.name}' since ID $clientId")
            eventsToReplay.forEach { sse ->
                sendSafe(actor, sse)
            }
        }
    }

    /**
     * Broadcast event to all (actors + spectators)
     */
    fun broadcast(event: String, data: Json.Object) {
        eventQueue.trySend(Event.Broadcast(event, data.toString()))
    }

    /**
     * Broadcast event to actors only
     */
    fun broadcastToActors(event: String, data: Json.Object) {
        eventQueue.trySend(Event.BroadcastToActors(event, data.toString()))
    }

    /**
     * Broadcast event to spectators only
     */
    fun broadcastToSpectators(event: String, data: Json.Object) {
        eventQueue.trySend(Event.BroadcastToSpectators(event, data.toString()))
    }

    /**
     * Send event to specific actor
     */
    fun sendTo(actor: Actor, event: String, data: Json.Object) {
        eventQueue.trySend(Event.Targeted(actor.connectionId, event, data.toString()))
    }

    /**
     * Process queued event
     */
    private suspend fun processEvent(event: Event) {
        val msgId = messageId.incrementAndGet().toString()
        when (event) {
            is Event.Broadcast -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                bufferIfHistoricizable(event.event, sse)
                sendToAll(sse)
            }
            is Event.BroadcastToActors -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                bufferIfHistoricizable(event.event, sse)
                sendToActors(sse)
            }
            is Event.BroadcastToSpectators -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                bufferIfHistoricizable(event.event, sse)
                sendToSpectators(sse)
            }
            is Event.Targeted -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                sendToActor(event.actorId, sse)
            }
        }
    }

    /**
     * Buffer event for Last-Event-ID replay if event name is in historicizableEvents
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

    private suspend fun sendSafe(actor: Actor, sse: ServerSentEvent) {
        try {
            actor.channel?.send(sse)
        } catch (e: Exception) {
            logger.warn("Failed to send to ${actor.name}", e)
        }
    }

    private suspend fun keepAlive() {
        val sse = ServerSentEvent(comments = "keepalive")
        actors.values.forEach { sendSafe(it, sse) }
        spectators.values.forEach { sendSafe(it, sse) }
    }

    /**
     * Get list of actor infos
     */
    fun getActors(): List<Actor> = actors.values.toList()

    /**
     * Get list of spectator infos
     */
    fun getSpectators(): List<Spectator> = spectators.values.toList()

    /**
     * Get an actor by ID
     */
    fun getActor(id: String): Actor? = actors[id]

    /**
     * Room info for lobby
     */
    open fun toJson(): Json.Object = Json.Object(
        "id" to id,
        "actorCount" to actors.size,
        "spectatorCount" to spectators.size,
        "actors" to Json.Array(actors.values.map { Json.Object("id" to it.connectionId, "userId" to it.userId, "name" to it.name) })
    )

    // Event types for queue
    sealed class Event {
        data class Broadcast(val event: String, val data: String) : Event()
        data class BroadcastToActors(val event: String, val data: String) : Event()
        data class BroadcastToSpectators(val event: String, val data: String) : Event()
        data class Targeted(val actorId: String, val event: String, val data: String) : Event()
    }
}

/**
 * Result of handling an action
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
