package com.republicate.kroom.server

import com.republicate.kson.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for room state persistence
 */
interface Persistence<S> {
    /**
     * Save room state (snapshot)
     */
    fun save(roomId: String, state: S)

    /**
     * Load room state
     */
    fun load(roomId: String): S?

    /**
     * Append an event (for event sourcing)
     */
    fun appendEvent(roomId: String, event: Json.Object)

    /**
     * Load all events for a room
     */
    fun loadEvents(roomId: String): List<Json.Object>

    /**
     * Delete room data
     */
    fun delete(roomId: String)

    /**
     * List all persisted room IDs
     */
    fun listRoomIds(): List<String>
}

/**
 * In-memory persistence (for testing or ephemeral rooms)
 */
class InMemoryPersistence<S> : Persistence<S> {
    private val states = ConcurrentHashMap<String, S>()
    private val events = ConcurrentHashMap<String, MutableList<Json.Object>>()

    override fun save(roomId: String, state: S) {
        states[roomId] = state
    }

    override fun load(roomId: String): S? = states[roomId]

    override fun appendEvent(roomId: String, event: Json.Object) {
        events.computeIfAbsent(roomId) { mutableListOf() }.add(event)
    }

    override fun loadEvents(roomId: String): List<Json.Object> =
        events[roomId]?.toList() ?: emptyList()

    override fun delete(roomId: String) {
        states.remove(roomId)
        events.remove(roomId)
    }

    override fun listRoomIds(): List<String> =
        (states.keys + events.keys).distinct()
}

/**
 * JSON file-based persistence
 * Stores state as .json and events as .events (JSONL format)
 */
class JsonFilePersistence<S>(
    private val directory: Path,
    private val stateSerializer: (S) -> Json.Object,
    private val stateDeserializer: (Json.Object) -> S
) : Persistence<S> {
    private val logger = LoggerFactory.getLogger("kroom.persistence")

    init {
        Files.createDirectories(directory)
    }

    private fun stateFile(roomId: String): File =
        directory.resolve("$roomId.json").toFile()

    private fun eventsFile(roomId: String): File =
        directory.resolve("$roomId.events").toFile()

    override fun save(roomId: String, state: S) {
        try {
            val json = stateSerializer(state)
            stateFile(roomId).writeText(json.toString())
            logger.debug("Saved state for room '$roomId'")
        } catch (e: Exception) {
            logger.error("Failed to save state for room '$roomId'", e)
        }
    }

    override fun load(roomId: String): S? {
        return try {
            val file = stateFile(roomId)
            if (!file.exists()) return null
            val json = Json.parse(file.readText()) as? Json.Object ?: return null
            stateDeserializer(json)
        } catch (e: Exception) {
            logger.error("Failed to load state for room '$roomId'", e)
            null
        }
    }

    override fun appendEvent(roomId: String, event: Json.Object) {
        try {
            val file = eventsFile(roomId)
            val line = event.toString() + "\n"
            Files.write(
                file.toPath(),
                line.toByteArray(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            logger.error("Failed to append event for room '$roomId'", e)
        }
    }

    override fun loadEvents(roomId: String): List<Json.Object> {
        return try {
            val file = eventsFile(roomId)
            if (!file.exists()) return emptyList()
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        Json.parse(line) as? Json.Object
                    } catch (e: Exception) {
                        logger.warn("Failed to parse event line: $line", e)
                        null
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to load events for room '$roomId'", e)
            emptyList()
        }
    }

    override fun delete(roomId: String) {
        try {
            stateFile(roomId).delete()
            eventsFile(roomId).delete()
            logger.debug("Deleted data for room '$roomId'")
        } catch (e: Exception) {
            logger.error("Failed to delete data for room '$roomId'", e)
        }
    }

    override fun listRoomIds(): List<String> {
        return try {
            directory.toFile().listFiles()
                ?.filter { it.extension == "json" || it.extension == "events" }
                ?.map { it.nameWithoutExtension }
                ?.distinct()
                ?: emptyList()
        } catch (e: Exception) {
            logger.error("Failed to list room IDs", e)
            emptyList()
        }
    }
}
