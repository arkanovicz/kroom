package com.republicate.kroom.server

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
    }

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
     */
    suspend fun join(actor: Actor): Channel<ServerSentEvent> {
        val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
        actor.channel = channel

        actors[actor.id] = actor
        _actorCount.value = actors.size

        logger.debug("Actor '${actor.name}' joined room '$id' (${actors.size} actors)")

        // Send initial state
        sendStateTo(actor)
        onActorJoined(actor)

        return channel
    }

    /**
     * Spectator joins the room
     */
    suspend fun joinAsSpectator(spectator: Spectator): Channel<ServerSentEvent> {
        val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
        spectator.channel = channel

        spectators[spectator.id] = spectator
        _spectatorCount.value = spectators.size

        logger.debug("Spectator '${spectator.name}' joined room '$id' (${spectators.size} spectators)")

        // Send initial state
        sendStateTo(spectator)
        onSpectatorJoined(spectator)

        return channel
    }

    /**
     * Actor or spectator leaves
     */
    suspend fun leave(actor: Actor) {
        actor.disconnect()

        if (actor is Spectator) {
            spectators.remove(actor.id)
            _spectatorCount.value = spectators.size
            logger.debug("Spectator '${actor.name}' left room '$id'")
            onSpectatorLeft(actor)
        } else {
            actors.remove(actor.id)
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
        eventQueue.trySend(Event.Targeted(actor.id, event, data.toString()))
    }

    /**
     * Process queued event
     */
    private suspend fun processEvent(event: Event) {
        val msgId = messageId.incrementAndGet().toString()
        when (event) {
            is Event.Broadcast -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                sendToAll(sse)
            }
            is Event.BroadcastToActors -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                sendToActors(sse)
            }
            is Event.BroadcastToSpectators -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                sendToSpectators(sse)
            }
            is Event.Targeted -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = msgId)
                sendToActor(event.actorId, sse)
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
        "actors" to Json.Array(actors.values.map { Json.Object("id" to it.id, "name" to it.name) })
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
