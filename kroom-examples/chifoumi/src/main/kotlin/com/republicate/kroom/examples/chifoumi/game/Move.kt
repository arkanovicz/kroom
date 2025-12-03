package com.republicate.kroom.examples.chifoumi.game

/**
 * Chifoumi moves with the "well" variant.
 *
 *     Rock ────► Scissors
 *      │ ╲           │
 *      │   ╲         │
 *      ▼     ╲       ▼
 *     Well ◄──── Paper
 *      │
 *      ▼
 *   Scissors
 *
 * Well beats: Rock (falls in), Scissors (fall in)
 * Well loses: Paper (covers it)
 */
enum class Move(val emoji: String, val nameEn: String, val nameFr: String) {
    ROCK("🪨", "Rock", "Pierre"),
    PAPER("📄", "Paper", "Feuille"),
    SCISSORS("✂️", "Scissors", "Ciseaux"),
    WELL("🕳️", "Well", "Puits");

    /**
     * Returns the moves this move beats.
     */
    fun beats(): Set<Move> = when (this) {
        ROCK -> setOf(SCISSORS)
        PAPER -> setOf(ROCK, WELL)
        SCISSORS -> setOf(PAPER)
        WELL -> setOf(ROCK, SCISSORS)
    }

    /**
     * Compare against another move.
     * @return 1 if this wins, -1 if other wins, 0 if draw
     */
    fun against(other: Move): Int = when {
        this == other -> 0
        other in this.beats() -> 1
        this in other.beats() -> -1
        else -> 0 // shouldn't happen with valid moves
    }

    companion object {
        fun fromString(s: String): Move? = entries.find {
            it.name.equals(s, ignoreCase = true)
        }
    }
}
