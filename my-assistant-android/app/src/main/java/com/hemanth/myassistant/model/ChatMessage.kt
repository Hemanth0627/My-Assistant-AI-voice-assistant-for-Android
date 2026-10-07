package com.hemanth.myassistant.model

enum class Sender { USER, ASSISTANT }

/** A web page the assistant used for an answer (shown under the reply, never spoken). */
data class WebSource(val title: String, val url: String)

data class ChatMessage(
    val id: Long,
    val text: String,
    val sender: Sender,
    val includeInHistory: Boolean = true, // false = shown on screen, never sent to the AI
    val sources: List<WebSource> = emptyList()
)