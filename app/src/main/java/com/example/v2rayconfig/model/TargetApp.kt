package com.example.v2rayconfig.model

/** A filtered service/app to test server reachability against. */
data class TargetApp(
    val id: String,
    val displayName: String,
    val testUrl: String
)

object TargetAppCatalog {
    /** A reasonable default set; user can add custom ones from the UI. */
    val defaults = listOf(
        TargetApp("claude", "Claude / Anthropic", "https://claude.ai"),
        TargetApp("instagram", "Instagram", "https://www.instagram.com"),
        TargetApp("youtube", "YouTube", "https://www.youtube.com"),
        TargetApp("telegram", "Telegram Web", "https://web.telegram.org"),
        TargetApp("whatsapp", "WhatsApp Web", "https://web.whatsapp.com"),
        TargetApp("twitter", "X / Twitter", "https://x.com")
    )
}
