package com.republicate.kroom.examples.chifoumi.game

actual fun golden(): String = FoldTest::class.java.getResource("/frames.jsonl")!!.readText()
