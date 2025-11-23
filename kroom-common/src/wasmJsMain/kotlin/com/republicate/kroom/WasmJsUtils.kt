package com.republicate.kroom

/*
@JsFun("eval(code)")
external fun eval(code: String): dynamic
*/


fun getProperty(obj: JsAny, prop: String): JsAny? = js("obj[prop]")
