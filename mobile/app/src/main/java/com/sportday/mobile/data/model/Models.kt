package com.sportday.mobile.data.model

import com.google.gson.annotations.SerializedName

data class AuthRequest(
    val username: String,
    val password: String
)

data class AuthResponse(
    val token: String,
    val username: String,
    val role: String,
    val fullName: String,
    val userId: Long
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String,
    val fullName: String? = null,
    val age: Int? = null,
    val gender: String? = null
)

data class EventDTO(
    val id: Long,
    val name: String,
    val description: String?,
    val type: String,
    val eventDate: String,
    val location: String?,
    val maxParticipants: Int,
    val enabled: Boolean,
    val createdAt: String?,
    val enrolledCount: Int? = null
)

data class EventResultDTO(
    val id: Long,
    val userId: Long,
    val username: String,
    val fullName: String?,
    val eventId: Long,
    val eventName: String,
    val mark: String,
    val unit: String?,
    val notes: String?,
    val recordedAt: String?
)

data class UserDTO(
    val id: Long,
    val username: String,
    val email: String,
    val fullName: String?,
    val age: Int?,
    val gender: String?,
    val role: String,
    val enabled: Boolean,
    val createdAt: String?
)

data class Enrollment(
    val id: Long,
    val user: UserDTO?,
    val event: EventDTO?,
    val enrolledAt: String?
)

data class RecordResultRequest(
    val userId: Long,
    val eventId: Long,
    val mark: String,
    val unit: String?,
    val notes: String?
)
