package com.sportday.mobile.data.api

import com.sportday.mobile.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface SportDayApi {

    // Auth
    @POST("api/auth/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    // Events
    @GET("api/events")
    suspend fun getEvents(@Query("onlyEnabled") onlyEnabled: Boolean = false): Response<List<EventDTO>>

    @GET("api/events/{id}")
    suspend fun getEvent(@Path("id") id: Long): Response<EventDTO>

    @POST("api/events")
    suspend fun createEvent(@Body event: EventDTO): Response<EventDTO>

    @PUT("api/events/{id}")
    suspend fun updateEvent(@Path("id") id: Long, @Body event: EventDTO): Response<EventDTO>

    @PATCH("api/events/{id}/enable")
    suspend fun setEventEnabled(@Path("id") id: Long, @Query("enabled") enabled: Boolean): Response<EventDTO>

    @DELETE("api/events/{id}")
    suspend fun deleteEvent(@Path("id") id: Long): Response<Unit>

    // Enrollments
    @POST("api/enrollments/{eventId}")
    suspend fun enroll(@Path("eventId") eventId: Long): Response<Enrollment>

    @DELETE("api/enrollments/{eventId}")
    suspend fun cancelEnrollment(@Path("eventId") eventId: Long): Response<Unit>

    @GET("api/enrollments/my")
    suspend fun getMyEnrollments(): Response<List<Any>>

    @GET("api/enrollments/event/{eventId}")
    suspend fun getEventEnrollments(@Path("eventId") eventId: Long): Response<List<Enrollment>>

    @GET("api/enrollments/check/{eventId}")
    suspend fun checkEnrollment(@Path("eventId") eventId: Long): Response<Boolean>

    // Results
    @GET("api/results/event/{eventId}")
    suspend fun getResultsByEvent(@Path("eventId") eventId: Long): Response<List<EventResultDTO>>

    @GET("api/results/user/{userId}")
    suspend fun getResultsByUser(@Path("userId") userId: Long): Response<List<EventResultDTO>>

    @POST("api/results")
    suspend fun recordResult(
        @Query("userId") userId: Long,
        @Query("eventId") eventId: Long,
        @Query("mark") mark: String,
        @Query("unit") unit: String?,
        @Query("notes") notes: String?
    ): Response<EventResultDTO>

    @DELETE("api/results/{id}")
    suspend fun deleteResult(@Path("id") id: Long): Response<Unit>

    // Users
    @GET("api/users/me")
    suspend fun getCurrentUser(): Response<UserDTO>

    @PUT("api/users/me")
    suspend fun updateCurrentUser(@Body user: UserDTO): Response<UserDTO>

    @GET("api/users")
    suspend fun getAllUsers(): Response<List<UserDTO>>

    @GET("api/users/{id}")
    suspend fun getUser(@Path("id") id: Long): Response<UserDTO>

    @PUT("api/users/{id}")
    suspend fun updateUser(@Path("id") id: Long, @Body user: UserDTO): Response<UserDTO>

    @PATCH("api/users/{id}/enable")
    suspend fun setUserEnabled(@Path("id") id: Long, @Query("enabled") enabled: Boolean): Response<Unit>

    @DELETE("api/users/{id}")
    suspend fun deleteUser(@Path("id") id: Long): Response<Unit>

    // Admin
    @POST("api/admin/managers")
    suspend fun createManager(@Body request: RegisterRequest): Response<UserDTO>
}
