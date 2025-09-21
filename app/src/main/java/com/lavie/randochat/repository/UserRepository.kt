package com.lavie.randochat.repository

import com.lavie.randochat.model.User
import com.google.firebase.auth.FirebaseAuth

interface UserRepository {
    suspend fun signInWithGoogle(idToken: String): UserResult?

    suspend fun saveUserToDb(user: User): UserResult?

    suspend fun checkUserValid(): UserResult?

    suspend fun registerWithEmail(email: String, password: String): UserResult?

    suspend fun loginWithEmail(email: String, password: String): UserResult?

    suspend fun addFcmToken(userId: String, token: String)

    suspend fun removeFcmToken(userId: String, token: String): Result<Unit>

    fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener)

    fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener)

    suspend fun getChatRoomStatus(roomId: String): Boolean?

    suspend fun getActiveOrLastRoom(userId: String): String?

    suspend fun getUserById(userId: String): User?

    suspend fun getActiveRoomId(userId: String): String?

    suspend fun getCitizenScore(userId: String): Int

    suspend fun getImageCredit(userId: String): Int

    suspend fun decreaseImageCredit(userId: String, delta: Int): Result<Unit>

    suspend fun updateUserLocation(userId: String, lat: Double, lng: Double)

    sealed class UserResult {
        data class Success(val user: User) : UserResult()
        data class Error(val messageId: Int?) : UserResult()
    }
}