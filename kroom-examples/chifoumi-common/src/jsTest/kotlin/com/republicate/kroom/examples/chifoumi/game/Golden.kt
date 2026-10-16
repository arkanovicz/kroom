package com.republicate.kroom.examples.chifoumi.game

// node runs the tests from the package's directory, where the test resources land under kotlin/
actual fun golden(): String = js("require('fs').readFileSync('kotlin/frames.jsonl', 'utf8')") as String
