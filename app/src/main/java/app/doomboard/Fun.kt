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

    // Home-screen greeting by time of day.
    fun greeting(hour: Int, nick: String?): String {
        val name = nick?.let { ", $it" } ?: ""
        return when (hour) {
            in 0..4 -> "It's ${if (hour == 0) 12 else hour} AM$name. Go to sleep 😭"
            in 5..11 -> "Good morning$name ☀️"
            in 12..16 -> "Good afternoon$name 👋"
            in 17..21 -> "Good evening$name 🌆"
            else -> "Late-night scroll$name? 🌙"
        }
    }

    // One-line roast under the greeting. Being over the limit wins over everything else.
    fun roast(reels: Int, limit: Int): String = when {
        limit in 1..reels -> if (reels == limit) "That's your limit. Put it down, bestie 🛑" else "${reels - limit} over your limit. We need to talk 🛑"
        reels == 0 -> "Clean slate. Stay strong 💪"
        reels < 10 -> "Barely touched it. Proud of you 🌱"
        reels < 50 -> "Casual scrolling. For now 👀"
        reels < 100 -> "The rabbit hole is calling 🐇"
        reels < 200 -> "Bestie… put the phone down 🫠"
        else -> "You are the algorithm now 🧟"
    }
}
