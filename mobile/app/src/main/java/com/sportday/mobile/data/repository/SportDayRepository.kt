package com.sportday.mobile.data.repository

import com.sportday.mobile.data.api.ApiClient
import com.sportday.mobile.data.api.SportDayApi
import com.sportday.mobile.data.model.*

class SportDayRepository {

    private val api: SportDayApi get() = ApiClient.getApi()

    // Auth
    suspend fun login(username: String, password: String) = api.login(AuthRequest(username, password))
    suspend fun register(request: RegisterRequest) = api.register(request)

    // Events
    suspend fun getEvents(onlyEnabled: Boolean = false) = api.getEvents(onlyEnabled)
    suspend fun getEvent(id: Long) = api.getEvent(id)
    suspend fun createEvent(event: EventDTO) = api.createEvent(event)
    suspend fun updateEvent(id: Long, event: EventDTO) = api.updateEvent(id, event)
    suspend fun setEventEnabled(id: Long, enabled: Boolean) = api.setEventEnabled(id, enabled)
    suspend fun deleteEvent(id: Long) = api.deleteEvent(id)

    // Enrollments
    suspend fun enroll(eventId: Long) = api.enroll(eventId)
    suspend fun cancelEnrollment(eventId: Long) = api.cancelEnrollment(eventId)
    suspend fun getMyEnrollments() = api.getMyEnrollments()
    suspend fun getEventEnrollments(eventId: Long) = api.getEventEnrollments(eventId)
    suspend fun checkEnrollment(eventId: Long) = api.checkEnrollment(eventId)

    // Results
    suspend fun getResultsByEvent(eventId: Long) = api.getResultsByEvent(eventId)
    suspend fun getResultsByUser(userId: Long) = api.getResultsByUser(userId)
    suspend fun recordResult(userId: Long, eventId: Long, mark: String, unit: String?, notes: String?) =
        api.recordResult(userId, eventId, mark, unit, notes)
    suspend fun deleteResult(id: Long) = api.deleteResult(id)

    // Users
    suspend fun getCurrentUser() = api.getCurrentUser()
    suspend fun updateCurrentUser(user: UserDTO) = api.updateCurrentUser(user)
    suspend fun getAllUsers() = api.getAllUsers()
    suspend fun getUser(id: Long) = api.getUser(id)
    suspend fun updateUser(id: Long, user: UserDTO) = api.updateUser(id, user)
    suspend fun setUserEnabled(id: Long, enabled: Boolean) = api.setUserEnabled(id, enabled)
    suspend fun deleteUser(id: Long) = api.deleteUser(id)

    // Admin
    suspend fun createManager(request: RegisterRequest) = api.createManager(request)
}
