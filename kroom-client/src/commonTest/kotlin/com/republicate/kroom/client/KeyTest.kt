package com.republicate.kroom.client

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.UserPresence
import com.republicate.kson.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyTest {
    @Test fun aViewByItsId() = assertEquals("a", key(UserPresence(Json.parse("""{"id":"a","name":"alice"}""") as Json.MutableObject), 3))

    @Test fun aLoggedEventByItsSeq() = assertEquals(7L, (key(Chat(Json.parse("""{"text":"hi","seq":7}""") as Json.MutableObject), 0) as Number).toLong())

    @Test fun aDeclaredKeyAbsentFallsBackToPosition() = assertEquals(2, key(UserPresence(), 2))

    @Test fun aPlainValueByItsPosition() = assertEquals(1, key("rock", 1))
}
