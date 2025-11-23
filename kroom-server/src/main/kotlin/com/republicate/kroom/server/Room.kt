package com.republicate.kroom.server

import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Base Room class for SSE-based real-time communication
 * Inspired by decoinche's Room architecture but adapted for Ktor and Kotlin coroutines
 */
open class Room(val name: String) {
    private val logger = LoggerFactory.getLogger("kroom.room")

    // Users currently in this room
    private val users = ConcurrentHashMap<String, User>()

    // User event channels - each user session gets its own channel to receive events
    private data class UserSession(val id: String, val channel: Channel<ServerSentEvent>)
    private val userSessions = ConcurrentHashMap<String, MutableSet<UserSession>>()

    // Message queue for async event processing
    private val eventQueue = Channel<Event>(Channel.UNLIMITED)

    // Message ID counter for event sequencing
    private val messageId = AtomicLong(1)

    // Last event time for keep-alive
    private var lastEventTime = System.currentTimeMillis()

    // Event processing job
    private var processingJob: Job? = null

    // Room scope for coroutines
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Chat history (circular buffer concept)
    private val chatHistory = mutableListOf<ChatMessage>()
    private val maxChatHistory = 50

    // Connected users count
    private val _userCount = MutableStateFlow(0)
    val userCount: StateFlow<Int> = _userCount.asStateFlow()

    companion object {
        private const val KEEPALIVE_DELAY = 2000L // 2 seconds
    }

    init {
        start()
    }

    /**
     * Start the event processing loop
     */
    private fun start() {
        processingJob = scope.launch {
            logger.info("Room '$name' event loop started")
            while (isActive) {
                try {
                    // Try to receive an event with timeout
                    val event = withTimeoutOrNull(KEEPALIVE_DELAY) {
                        eventQueue.receive()
                    }

                    if (event != null) {
                        processEvent(event)
                        lastEventTime = System.currentTimeMillis()
                    } else {
                        // Timeout - send keep-alive
                        val now = System.currentTimeMillis()
                        if (now - lastEventTime >= KEEPALIVE_DELAY) {
                            keepAlive()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error processing event in room '$name'", e)
                }
            }
            logger.info("Room '$name' event loop stopped")
        }
    }

    /**
     * Stop the room and cleanup resources
     */
    fun stop() {
        processingJob?.cancel()
        scope.cancel()
        userSessions.clear()
        users.clear()
        logger.info("Room '$name' stopped")
    }

    /**
     * User joins the room - returns a channel that will receive events for this session
     */
    suspend fun joinRoom(user: User): Channel<ServerSentEvent> {
        val login = user.login
        users.putIfAbsent(login, user)

        val sessionId = "${System.currentTimeMillis()}-${(0..999).random()}"
        val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
        val session = UserSession(sessionId, channel)

        userSessions.compute(login) { _, sessions ->
            val set = sessions ?: ConcurrentHashMap.newKeySet()
            set.add(session)
            set
        }

        _userCount.value = users.size

        logger.debug("User '$login' joined room '$name' (session count: ${userSessions[login]?.size})")

        // Send context to this user
        sendContextToChannel(user, channel)

        // Notify room if this is the first session for this user
        if (userSessions[login]?.size == 1) {
            onUserJoined(login)
        }

        return channel
    }

    /**
     * User leaves the room
     */
    suspend fun leaveRoom(user: User, sessionChannel: Channel<ServerSentEvent>) {
        val login = user.login

        userSessions.compute(login) { _, sessions ->
            sessions?.removeIf { it.channel == sessionChannel }
            sessionChannel.close()
            if (sessions.isNullOrEmpty()) null else sessions
        }

        // If no more sessions for this user, remove them from the room
        if (!userSessions.containsKey(login)) {
            users.remove(login)
            _userCount.value = users.size
            logger.debug("User '$login' left room '$name' completely")
            onUserLeft(login)

            // Check if room should be closed
            if (shouldCloseEmptyRoom() && users.isEmpty()) {
                stop()
            }
        } else {
            logger.debug("User '$login' session closed in room '$name' (${userSessions[login]?.size} remaining)")
        }
    }

    /**
     * Send initial context to a newly joined user
     */
    private suspend fun sendContextToChannel(user: User, channel: Channel<ServerSentEvent>) {
        // Send connected users
        val connectedUsers = users.keys.toList()
        channel.send(ServerSentEvent(data = """{"users":${connectedUsers.joinToString(",", "[", "]") { "\"$it\"" }}}""", event = "connected"))

        // Send chat history
        val historyCopy = synchronized(chatHistory) {
            chatHistory.toList()
        }
        historyCopy.forEach { msg ->
            channel.send(ServerSentEvent(data = msg.toJson(), event = "chat", id = msg.seq.toString()))
        }

        // Allow subclasses to send additional context
        sendPrivateContextToChannel(user, channel)
    }

    /**
     * Override in subclasses to send user-specific context
     */
    protected open suspend fun sendPrivateContextToChannel(user: User, channel: Channel<ServerSentEvent>) {
        // Base implementation does nothing
    }

    /**
     * Override in subclasses to handle user joined event
     */
    protected open suspend fun onUserJoined(login: String) {
        // Broadcast user joined
        post("connected", """{"users":${users.keys.joinToString(",", "[", "]") { "\"$it\"" }}}""")
    }

    /**
     * Override in subclasses to handle user left event
     */
    protected open suspend fun onUserLeft(login: String) {
        // Broadcast user left
        post("connected", """{"users":${users.keys.joinToString(",", "[", "]") { "\"$it\"" }}}""")
    }

    /**
     * Whether to close the room when empty
     */
    protected open fun shouldCloseEmptyRoom(): Boolean = false

    /**
     * Post an event to all users in the room
     */
    fun post(event: String, data: String) {
        eventQueue.trySend(Event.Broadcast(event, data))
    }

    /**
     * Post an event to a specific user
     */
    fun post(login: String, event: String, data: String) {
        eventQueue.trySend(Event.Targeted(login, event, data))
    }

    /**
     * Send chat message
     */
    fun chat(from: String?, text: String) {
        val msg = ChatMessage(
            seq = messageId.incrementAndGet(),
            from = from,
            text = text,
            timestamp = Instant.now().toString()
        )

        synchronized(chatHistory) {
            chatHistory.add(msg)
            if (chatHistory.size > maxChatHistory) {
                chatHistory.removeAt(0)
            }
        }

        post("chat", msg.toJson())
    }

    /**
     * Process an event from the queue
     */
    private suspend fun processEvent(event: Event) {
        val id = messageId.get().toString()
        when (event) {
            is Event.Broadcast -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = id)
                logger.trace("Broadcasting to room '$name': ${event.event}")
                broadcast(sse)
            }
            is Event.Targeted -> {
                val sse = ServerSentEvent(data = event.data, event = event.event, id = id)
                logger.trace("Sending to user '${event.login}' in room '$name': ${event.event}")
                sendToUser(event.login, sse)
            }
        }
    }

    /**
     * Broadcast an SSE to all users
     */
    private suspend fun broadcast(sse: ServerSentEvent) {
        userSessions.values.forEach { sessions ->
            sessions.forEach { session ->
                try {
                    session.channel.send(sse)
                } catch (e: Exception) {
                    logger.warn("Failed to send to session", e)
                }
            }
        }
    }

    /**
     * Send an SSE to a specific user
     */
    private suspend fun sendToUser(login: String, sse: ServerSentEvent) {
        userSessions[login]?.forEach { session ->
            try {
                session.channel.send(sse)
            } catch (e: Exception) {
                logger.warn("Failed to send to user '$login'", e)
            }
        }
    }

    /**
     * Send keep-alive to all users
     */
    private suspend fun keepAlive() {
        userSessions.values.forEach { sessions ->
            sessions.forEach { session ->
                try {
                    // Send a comment as keep-alive
                    session.channel.send(ServerSentEvent(comments = "keepalive"))
                } catch (e: Exception) {
                    logger.warn("Failed to send keep-alive", e)
                }
            }
        }
    }

    fun getUsers(): List<String> = users.keys.toList()

    sealed class Event {
        data class Broadcast(val event: String, val data: String) : Event()
        data class Targeted(val login: String, val event: String, val data: String) : Event()
    }

    data class ChatMessage(
        val seq: Long,
        val from: String?,
        val text: String,
        val timestamp: String
    ) {
        fun toJson(): String {
            val fromField = from?.let { """"from":"$it",""" } ?: ""
            return """{"seq":$seq,$fromField"text":"$text","timestamp":"$timestamp"}"""
        }
    }
}

/**
 * Simple user representation
 */
data class User(val login: String)
