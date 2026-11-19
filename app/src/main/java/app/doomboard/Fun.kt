package app.doomboard

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Same titles / score / nicknames as the browser extension (titles.js).
object Fun {
    private val TITLES = listOf(
        Triple(200, "🧟", "Certified Zombie"),
        Triple(100, "🫠", "Brainrot Boss"),
        Triple(50, "🐇", "Rabbit Hole Olympian"),
        Triple(10, "🙂", "Casual Scroller"),
        Triple(0, "🌱", "Touched Grass"),
    )

    fun titleFor(reels: Int) = TITLES.first { reels >= it.first }

    // 0-100: more reels + faster skipping (shorter avg watch) = higher
    fun rotScore(reels: Int, secs: Int): Int {
        val avg = if (reels > 0) secs.toDouble() / reels else 20.0
        return min(100, (reels * 0.4 + max(0.0, 20 - avg) * 2).roundToInt())
    }

    private val ADJ = listOf("Sleepy", "Cursed", "Feral", "Chaotic", "Gigabrain", "Doomed", "Crispy", "Soggy", "Unhinged", "Sus", "Caffeinated", "Mid")
    private val ANIMAL = listOf("Otter", "Goblin", "Panda", "Raccoon", "Potato", "Gremlin", "Capybara", "Noodle", "Pigeon", "Llama", "Walrus", "Toast")

    fun randomNick() = ADJ.random() + ANIMAL.random() + (10..99).random()
}
