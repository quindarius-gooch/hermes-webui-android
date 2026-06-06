package com.example.hermeswebui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Setup : NavKey
@Serializable data class Web(val url: String) : NavKey
