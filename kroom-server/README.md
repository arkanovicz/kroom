# Kroom Server - SSE Playground

A Kotlin/Ktor implementation of the SSE (Server-Sent Events) room functionality extracted from the [decoinche](../decoinche) project.

## Overview

This server provides a real-time chat and room management system using Server-Sent Events (SSE). It's designed as both a testing playground and a foundation for migrating advanced SSE features from the decoinche Java/Servlet implementation to Kotlin/Ktor.

## Features Implemented

### Core Architecture

- **Room Class**: Base class for SSE-based real-time communication
  - Asynchronous event processing with coroutines
  - Multiple concurrent sessions per user (multi-tab support)
  - Keep-alive mechanism (2-second interval)
  - Message sequencing and event queue
  - Chat history (circular buffer, 50 messages)
  - User presence tracking

- **RoomManager**: Singleton for room lifecycle management
  - Create/retrieve/remove rooms
  - Global lobby room
  - Room info retrieval

### API Endpoints

#### SSE Endpoint
- `GET /events/{roomName}?login={username}` - Connect to a room via SSE

#### HTTP REST API
- `GET /api/rooms` - List all rooms
- `GET /api/rooms/{name}` - Get room info
- `POST /api/rooms/{name}` - Create a room
- `POST /api/rooms/{name}/chat` - Send chat message

### Web UI
- `GET /playground` - Interactive test interface for creating rooms and chatting

## Running the Server

```bash
./gradlew :kroom-server:run
```

The server will start on http://localhost:8080

## Testing the Playground

1. Open http://localhost:8080/playground in your browser
2. Enter a username
3. Create a room or join an existing one
4. Open another browser tab/window with a different username
5. Chat in real-time!

## Architecture Comparison with Decoinche

### Similarities
- Room-based architecture
- Asynchronous event processing
- User session management
- Chat history
- Keep-alive mechanism
- Context delivery on connection

### Differences (Technology Shift)

| Decoinche (Java/Servlet) | Kroom (Kotlin/Ktor) |
|--------------------------|---------------------|
| jeasse-servlet3 library | Ktor SSE plugin |
| `EventBroadcast` / `EventTarget` | Kotlin Channels |
| Thread-based event loop | Coroutine-based event loop |
| `ServletEventTarget` | Channel-based session management |
| Java synchronization primitives | Kotlin coroutine primitives |
| `BufferUtils.synchronizedBuffer` | Kotlin `synchronized` + `toList()` |

## Features To Migrate

From the decoinche SSE implementation, the following features are candidates for migration:

1. **Connection Status Detection**
   - Graceful disconnect handling (1-second timeout)
   - Robot/bot user detection
   - Connection recovery

2. **History Recovery**
   - Last-Event-ID handling
   - Server restart detection
   - Event replay from specific message ID

3. **Private Contexts**
   - User-specific state delivery
   - Per-user event filtering

4. **Event Recording**
   - Persistent event logs for replay
   - Game/session recording

5. **Advanced Room Types**
   - Game rooms (extending base Room)
   - Custom state management
   - Room-specific rules

6. **User Management**
   - Authentication integration
   - User-Agent tracking
   - IP address tracking
   - Session management

## Current Limitations

- No authentication (login is just a query parameter)
- No persistent storage
- No event recording
- Basic chat only (no rich message types)
- No private messaging
- No user status (online/away/offline)

## Next Steps

1. Add proper authentication
2. Implement Last-Event-ID support for reconnection
3. Add user presence tracking (online/offline/away)
4. Implement event recording for replay
5. Add private context support
6. Create specialized room types (e.g., GameRoom)
7. Add automated tests

## Directory Structure

```
kroom-server/
├── src/main/kotlin/com/republicate/kroom/server/
│   ├── Main.kt          # Server setup, routing, HTML playground
│   ├── Room.kt          # Base room class with SSE functionality
│   ├── RoomManager.kt   # Room lifecycle management
│   └── User.kt          # User data class (in Room.kt)
└── build.gradle.kts     # Build configuration
```

## Development Notes

- The Room class uses Kotlin Channels to distribute events to SSE sessions
- Each user can have multiple sessions (multi-tab support)
- Events are processed asynchronously via a coroutine-based queue
- The HTML playground is embedded in Main.kt for simplicity
- All SSE events are JSON-formatted for easy client-side parsing

## License

Apache 2.0 (same as decoinche project)
