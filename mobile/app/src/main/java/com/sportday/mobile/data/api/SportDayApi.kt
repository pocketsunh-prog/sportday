package com.sportday.mobile.data.api

import com.sportday.mobile.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface SportDayApi {

    // Auth
    @POST("api/auth/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    /**
     * There is deliberately no `register` on the way in: public self-registration
     * was removed and `POST /api/auth/register` now answers 404. Accounts are made
     * by an administrator (`createManager` below). Kept declared so the app's own
     * Register screen gets the server's 404 sentence rather than a crash.
     */
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    // Events
    @GET("api/events")
    suspend fun getEvents(
        @Query("onlyEnabled") onlyEnabled: Boolean = false,
        @Query("category") category: String? = null
    ): Response<List<EventDTO>>

    /** Events already held — today or earlier, most recent first. */
    @GET("api/events/past")
    suspend fun getPastEvents(): Response<List<EventDTO>>

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
    suspend fun enroll(@Path("eventId") eventId: Long): Response<EnrollmentDTO>

    @DELETE("api/enrollments/{eventId}")
    suspend fun cancelEnrollment(@Path("eventId") eventId: Long): Response<Unit>

    /** Re-enters an event the student withdrew from; the row is revived. */
    @POST("api/enrollments/{eventId}/re-enroll")
    suspend fun reEnroll(@Path("eventId") eventId: Long): Response<EnrollmentDTO>

    /** The signed-in student's confirmed entries, flattened. */
    @GET("api/enrollments/my")
    suspend fun getMyEnrollments(): Response<List<EnrollmentDTO>>

    /** Every entry including withdrawn ones. */
    @GET("api/enrollments/my/all")
    suspend fun getMyEnrollmentHistory(): Response<List<EnrollmentDTO>>

    /** Track/field allowance used and remaining — the server's own numbers. */
    @GET("api/enrollments/my/quota")
    suspend fun getMyQuota(): Response<QuotaDTO>

    @GET("api/enrollments/event/{eventId}")
    suspend fun getEventEnrollments(@Path("eventId") eventId: Long): Response<List<EnrollmentDTO>>

    @GET("api/enrollments/check/{eventId}")
    suspend fun checkEnrollment(@Path("eventId") eventId: Long): Response<Boolean>

    // Results
    @GET("api/results/event/{eventId}")
    suspend fun getResultsByEvent(@Path("eventId") eventId: Long): Response<List<EventResultDTO>>

    @GET("api/results/user/{userId}")
    suspend fun getResultsByUser(@Path("userId") userId: Long): Response<List<EventResultDTO>>

    /**
     * Who finished where in one event, with the points each place is worth and
     * the school-record flag. This is where a *placing* comes from.
     */
    @GET("api/events/{eventId}/standings")
    suspend fun getStandings(@Path("eventId") eventId: Long): Response<EventStandingsDTO>

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

    // Results PDFs — both endpoints are authenticated, so the bearer token must
    // travel with the request; a plain link would be refused.
    /** `GET /api/events/{eventId}/results.pdf` — one event's results sheet. */
    @Streaming
    @GET("api/events/{eventId}/results.pdf")
    suspend fun downloadEventResultsPdf(@Path("eventId") eventId: Long): Response<ResponseBody>

    /** `GET /api/results.pdf` — the whole programme's results in one file. */
    @Streaming
    @GET("api/results.pdf")
    suspend fun downloadProgrammeResultsPdf(): Response<ResponseBody>

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
