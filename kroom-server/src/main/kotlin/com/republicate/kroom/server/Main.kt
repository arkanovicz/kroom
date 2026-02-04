package com.republicate.kroom.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kroom.main")

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(SSE)
        configureRouting()

        // Shutdown hook
        environment.monitor.subscribe(ApplicationStopping) {
            RoomManager.shutdown()
        }
    }.start(wait = true)
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Kroom Server is running\n\nVisit /playground for the test interface", ContentType.Text.Plain)
        }

        // SSE endpoint for room connections
        sse("/events/{roomName}") {
            val roomName = call.parameters["roomName"] ?: run {
                send(ServerSentEvent(data = """{"error":"Room name is required"}""", event = "error"))
                return@sse
            }

            // Get login from query parameter (in production, use proper authentication)
            val login = call.request.queryParameters["login"] ?: run {
                send(ServerSentEvent(data = """{"error":"Login is required"}""", event = "error"))
                return@sse
            }

            val room = RoomManager.getOrCreateRoom(roomName)
            val connectionId = "conn-${System.currentTimeMillis()}"
            val actor = Actor(connectionId = connectionId, userId = login, name = login)

            // Read Last-Event-ID header for reconnection handling
            // EventSource sends this automatically on reconnect
            val lastEventId = call.request.header("Last-Event-ID")

            logger.info("User '$login' connecting to room '$roomName'" +
                (lastEventId?.let { " (reconnect from event $it)" } ?: ""))

            val channel = room.join(actor, lastEventId)

            try {
                // Read events from the channel and send them to the client
                for (event in channel) {
                    send(event)
                }
            } catch (e: Exception) {
                logger.debug("SSE connection ended for user '$login' in room '$roomName'", e)
            } finally {
                room.leave(actor)
            }
        }

        // HTTP API endpoints
        route("/api") {
            // List all rooms
            get("/rooms") {
                val rooms = RoomManager.getAllRoomInfo()
                call.respondText(
                    """{"rooms":[${rooms.joinToString(",") { """{"name":"${it.name}","userCount":${it.userCount},"users":${it.users.joinToString(",", "[", "]") { u -> "\"$u\"" }}}""" }}]}""",
                    ContentType.Application.Json
                )
            }

            // Get room info
            get("/rooms/{name}") {
                val roomName = call.parameters["name"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, """{"error":"Room name is required"}""")
                    return@get
                }

                val info = RoomManager.getRoomInfo(roomName)
                if (info != null) {
                    call.respondText(
                        """{"name":"${info.name}","userCount":${info.userCount},"users":${info.users.joinToString(",", "[", "]") { "\"$it\"" }}}""",
                        ContentType.Application.Json
                    )
                } else {
                    call.respond(HttpStatusCode.NotFound, """{"error":"Room not found"}""")
                }
            }

            // Create a room
            post("/rooms/{name}") {
                val roomName = call.parameters["name"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, """{"error":"Room name is required"}""")
                    return@post
                }

                RoomManager.getOrCreateRoom(roomName)
                call.respondText(
                    """{"name":"$roomName","created":true}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Created
                )
            }

            // Send chat message to a room
            post("/rooms/{name}/chat") {
                val roomName = call.parameters["name"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, """{"error":"Room name is required"}""")
                    return@post
                }

                val body = call.receiveText()
                val fromRegex = """"from"\s*:\s*"([^"]+)"""".toRegex()
                val textRegex = """"text"\s*:\s*"([^"]+)"""".toRegex()

                val from = fromRegex.find(body)?.groupValues?.get(1)
                val text = textRegex.find(body)?.groupValues?.get(1) ?: run {
                    call.respond(HttpStatusCode.BadRequest, """{"error":"Text is required"}""")
                    return@post
                }

                val room = RoomManager.getRoom(roomName) ?: run {
                    call.respond(HttpStatusCode.NotFound, """{"error":"Room not found"}""")
                    return@post
                }

                room.sendChat(from, text)
                call.respondText("""{"success":true}""", ContentType.Application.Json)
            }
        }

        // Serve the playground HTML
        get("/playground") {
            call.respondText(playgroundHtml, ContentType.Text.Html)
        }
    }
}

private val playgroundHtml = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Kroom SSE Playground</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            background: #f5f5f5;
        }
        h1 {
            color: #333;
        }
        .container {
            display: grid;
            grid-template-columns: 300px 1fr;
            gap: 20px;
        }
        .panel {
            background: white;
            border-radius: 8px;
            padding: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .rooms-panel {
            max-height: 600px;
            overflow-y: auto;
        }
        .room-list {
            list-style: none;
            padding: 0;
            margin: 0;
        }
        .room-item {
            padding: 10px;
            margin: 5px 0;
            background: #f8f9fa;
            border-radius: 4px;
            cursor: pointer;
            transition: background 0.2s;
        }
        .room-item:hover {
            background: #e9ecef;
        }
        .room-item.active {
            background: #007bff;
            color: white;
        }
        .input-group {
            margin-bottom: 15px;
        }
        label {
            display: block;
            margin-bottom: 5px;
            font-weight: 500;
            color: #555;
        }
        input[type="text"] {
            width: 100%;
            padding: 8px;
            border: 1px solid #ddd;
            border-radius: 4px;
            box-sizing: border-box;
        }
        button {
            background: #007bff;
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
            transition: background 0.2s;
        }
        button:hover {
            background: #0056b3;
        }
        button:disabled {
            background: #ccc;
            cursor: not-allowed;
        }
        .chat-container {
            height: 400px;
            border: 1px solid #ddd;
            border-radius: 4px;
            overflow-y: auto;
            padding: 10px;
            background: #fafafa;
            margin-bottom: 15px;
        }
        .message {
            margin-bottom: 10px;
            padding: 8px;
            background: white;
            border-radius: 4px;
        }
        .message-system {
            background: #e3f2fd;
            font-style: italic;
        }
        .message-from {
            font-weight: bold;
            color: #007bff;
            margin-right: 5px;
        }
        .status {
            padding: 10px;
            border-radius: 4px;
            margin-bottom: 15px;
        }
        .status-connected {
            background: #d4edda;
            color: #155724;
        }
        .status-disconnected {
            background: #f8d7da;
            color: #721c24;
        }
        .users-list {
            padding: 10px;
            background: #f8f9fa;
            border-radius: 4px;
            margin-bottom: 15px;
        }
    </style>
</head>
<body>
    <h1>Kroom SSE Playground</h1>

    <div class="container">
        <div class="panel rooms-panel">
            <h2>Rooms</h2>
            <div class="input-group">
                <label>Your Username</label>
                <input type="text" id="username" placeholder="Enter username" value="user_${"$"}{Math.random().toString(36).substr(2, 6)}">
            </div>
            <div class="input-group">
                <label>Create Room</label>
                <input type="text" id="newRoomName" placeholder="Room name">
                <button onclick="createRoom()" style="margin-top: 5px; width: 100%;">Create & Join</button>
            </div>
            <h3>Available Rooms</h3>
            <ul class="room-list" id="roomList"></ul>
            <button onclick="refreshRooms()" style="margin-top: 10px; width: 100%;">Refresh List</button>
        </div>

        <div class="panel">
            <h2>Chat Room: <span id="currentRoom">None</span></h2>
            <div id="status" class="status status-disconnected">Not connected</div>
            <div class="users-list">
                <strong>Users in room:</strong>
                <span id="usersList">-</span>
            </div>
            <div class="chat-container" id="chatContainer"></div>
            <div class="input-group">
                <input type="text" id="messageInput" placeholder="Type a message..." onkeypress="handleKeyPress(event)" disabled>
                <button onclick="sendMessage()" id="sendButton" disabled style="margin-top: 5px;">Send Message</button>
            </div>
            <button onclick="disconnect()" id="disconnectButton" disabled style="background: #dc3545;">Disconnect</button>
        </div>
    </div>

    <script>
        let eventSource = null;
        let currentRoom = null;
        let username = null;

        function addMessage(from, text, isSystem = false) {
            const container = document.getElementById('chatContainer');
            const msg = document.createElement('div');
            msg.className = isSystem ? 'message message-system' : 'message';

            if (isSystem) {
                msg.textContent = text;
            } else {
                const fromSpan = document.createElement('span');
                fromSpan.className = 'message-from';
                fromSpan.textContent = from + ':';
                msg.appendChild(fromSpan);
                msg.appendChild(document.createTextNode(' ' + text));
            }

            container.appendChild(msg);
            container.scrollTop = container.scrollHeight;
        }

        function updateStatus(connected, room = null) {
            const status = document.getElementById('status');
            const messageInput = document.getElementById('messageInput');
            const sendButton = document.getElementById('sendButton');
            const disconnectButton = document.getElementById('disconnectButton');
            const currentRoomSpan = document.getElementById('currentRoom');

            if (connected) {
                status.textContent = 'Connected to ' + room;
                status.className = 'status status-connected';
                messageInput.disabled = false;
                sendButton.disabled = false;
                disconnectButton.disabled = false;
                currentRoomSpan.textContent = room;
                currentRoom = room;
            } else {
                status.textContent = 'Not connected';
                status.className = 'status status-disconnected';
                messageInput.disabled = true;
                sendButton.disabled = true;
                disconnectButton.disabled = true;
                currentRoomSpan.textContent = 'None';
                currentRoom = null;
            }
        }

        function updateUsersList(users) {
            document.getElementById('usersList').textContent = users.join(', ') || '-';
        }

        function connect(room) {
            disconnect();

            username = document.getElementById('username').value.trim();
            if (!username) {
                alert('Please enter a username');
                return;
            }

            document.getElementById('chatContainer').innerHTML = '';
            addMessage(null, 'Connecting to room: ' + room, true);

            eventSource = new EventSource(`/events/${"$"}{room}?login=${"$"}{encodeURIComponent(username)}`);

            eventSource.addEventListener('state', (e) => {
                const data = JSON.parse(e.data);
                if (data.actors) {
                    updateUsersList(data.actors.map(a => a.name));
                }
                if (data.history) {
                    data.history.forEach(msg => {
                        addMessage(msg.from || 'System', msg.text);
                    });
                }
            });

            eventSource.addEventListener('actors', (e) => {
                const data = JSON.parse(e.data);
                updateUsersList(data.actors.map(a => a.name));
            });

            eventSource.addEventListener('chat', (e) => {
                const data = JSON.parse(e.data);
                addMessage(data.from || 'System', data.text);
            });

            eventSource.addEventListener('error', (e) => {
                try {
                    const data = JSON.parse(e.data);
                    addMessage(null, 'Error: ' + data.error, true);
                } catch (_) {
                    // Not JSON error event
                }
            });

            eventSource.onopen = () => {
                updateStatus(true, room);
                addMessage(null, 'Connected!', true);
            };

            eventSource.onerror = () => {
                addMessage(null, 'Connection error', true);
                disconnect();
            };
        }

        function disconnect() {
            if (eventSource) {
                eventSource.close();
                eventSource = null;
                updateStatus(false);
                addMessage(null, 'Disconnected', true);
            }
        }

        async function sendMessage() {
            const input = document.getElementById('messageInput');
            const text = input.value.trim();
            if (!text || !currentRoom) return;

            try {
                await fetch(`/api/rooms/${"$"}{currentRoom}/chat`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ from: username, text: text })
                });
                input.value = '';
            } catch (err) {
                alert('Failed to send message: ' + err.message);
            }
        }

        function handleKeyPress(event) {
            if (event.key === 'Enter') {
                sendMessage();
            }
        }

        async function refreshRooms() {
            try {
                const response = await fetch('/api/rooms');
                const data = await response.json();
                const list = document.getElementById('roomList');
                list.innerHTML = '';

                data.rooms.forEach(room => {
                    const li = document.createElement('li');
                    li.className = 'room-item';
                    if (room.name === currentRoom) {
                        li.className += ' active';
                    }
                    li.innerHTML = `
                        <strong>${"$"}{room.name}</strong><br>
                        <small>${"$"}{room.userCount} users: ${"$"}{room.users.join(", ") || "none"}</small>
                    `;
                    li.onclick = () => connect(room.name);
                    list.appendChild(li);
                });
            } catch (err) {
                alert('Failed to refresh rooms: ' + err.message);
            }
        }

        async function createRoom() {
            const roomName = document.getElementById('newRoomName').value.trim();
            if (!roomName) {
                alert('Please enter a room name');
                return;
            }

            try {
                await fetch(`/api/rooms/${"$"}{roomName}`, { method: 'POST' });
                document.getElementById('newRoomName').value = '';
                await refreshRooms();
                connect(roomName);
            } catch (err) {
                alert('Failed to create room: ' + err.message);
            }
        }

        // Initial refresh
        refreshRooms();
        setInterval(refreshRooms, 5000); // Refresh every 5 seconds
    </script>
</body>
</html>
""".trimIndent()
