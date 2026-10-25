package dev.alore.argos.ui

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

/** `serverId == null` adds a new server. */
@Serializable
data class ServerFormRoute(val serverId: String? = null)

@Serializable
data class TerminalRoute(val serverId: String, val sessionName: String)

@Serializable
data object SettingsRoute
