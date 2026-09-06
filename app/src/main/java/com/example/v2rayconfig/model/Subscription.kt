package com.example.v2rayconfig.model

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
)
