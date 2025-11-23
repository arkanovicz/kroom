package com.republicate.kroom

// actual typealias BitSet = kotlin.native.BitSet

@OptIn(ObsoleteNativeApi::class)
actual class BitSet actual constructor(size: Int) {
    private var bits = kotlin.native.BitSet(size)
    actual operator fun get(index: Int) = bits.get(index)
    actual fun set(index: Int) = set(index, true)
    actual fun set(index: Int, value: Boolean) = bits.set(index, value)
    actual fun clear(index: Int) = bits.clear(index)
    actual fun or(another: BitSet) = bits.or(another.bits)
    actual fun clear() = bits.clear()
    actual fun nextSetBit() = nextSetBit(0)
    actual fun nextSetBit(startIndex: Int) = bits.nextSetBit(startIndex)
}

