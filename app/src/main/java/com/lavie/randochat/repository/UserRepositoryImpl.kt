package com.lavie.randochat.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.lavie.randochat.R
import com.lavie.randochat.model.ChatRoom
import com.lavie.randochat.model.User
import com.lavie.randochat.repository.UserRepository.UserResult
import com.lavie.randochat.utils.CommonUtils.isNetworkError
import com.lavie.randochat.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class UserRepositoryImpl(
    private val firebaseAuth: FirebaseAuth,
    private val database: DatabaseReference
) : UserRepository {

    override suspend fun signInWithGoogle(idToken: String): UserResult? {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()

            Timber.d("Sign in successful: ${result.user?.uid}")

            result.user?.let { firebaseUser ->
                UserResult.Success(
                    User(
                        id = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        nickname = firebaseUser.displayName ?: "",
                        isOnline = true
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Sign in failed")

            if (isNetworkError(e)) {
                UserResult.Error(R.string.network_error)
            } else {
                UserResult.Error(R.string.login_error)
            }
        }
    }

    override suspend fun saveUserToDb(user: User): UserResult {
        var retryCount = 0
        val maxRetries = 3
        var delayTime = 1000L

        while (retryCount < maxRetries) {
            try {
                FirebaseDatabase.getInstance().goOnline()

                val userSnapshot = withTimeout(Constants.SAVE_USER_TIMEOUT) {
                    database.child(Constants.USERS).child(user.id).get().await()
                }

                val userMap = userSnapshot.value as? Map<*, *>
                val isDisabled = userMap?.get(Constants.IS_DISABLED) as? Boolean ?: user.isDisabled

                if (isDisabled) {
                    firebaseAuth.signOut()
                    return UserResult.Error(R.string.account_locked_full)
                }

                saveUserMapToFirebase(user, isDisabled)

                return UserResult.Success(user.copy(isDisabled = isDisabled))

            } catch (e: TimeoutCancellationException) {
                retryCount++
                if (retryCount >= maxRetries) {
                    return UserResult.Error(R.string.save_user_data_failed)
                }
                delay(delayTime)
                delayTime *= 2

            } catch (e: Exception) {
                retryCount++

                when {
                    isNetworkError(e) && retryCount < maxRetries -> {
                        delay(delayTime)
                        delayTime *= 2
                    }

                    retryCount >= maxRetries -> {
                        return UserResult.Error(R.string.please_check_connection)
                    }

                    else -> {
                        Timber.e(e)
                    }
                }
            }
        }

        return UserResult.Error(R.string.save_user_data_failed)
    }

    override suspend fun checkUserValid(): UserResult {
        val user = firebaseAuth.currentUser
        if (user != null) {
            try {
                withTimeout(Constants.RELOAD_USER_TIMEOUT) { user.reload().await() }

                val userSnapshot = withTimeout(Constants.RELOAD_USER_TIMEOUT) {
                    database.child(Constants.USERS).child(user.uid).get().await()
                }

                if (userSnapshot.exists()) {
                    val userMap = userSnapshot.value as? Map<*, *>
                    val isDisabled = userMap?.get(Constants.IS_DISABLED) as? Boolean ?: false
                    val citizenScore = (userMap?.get(Constants.CITIZEN_SCORE) as? Long)?.toInt() ?: 100
                    val imageCredit = (userMap?.get(Constants.IMAGE_CREDIT) as? Long)?.toInt() ?: 5
                    Timber.d("Citizen Score: $citizenScore")
                    Timber.d("imageCredit: $imageCredit")
                    if (isDisabled) {
                        firebaseAuth.signOut()
                        return UserResult.Error(R.string.account_locked_full)
                    }

                    return UserResult.Success(
                        User(
                            id = user.uid,
                            email = userMap?.get(Constants.EMAIL) as? String ?: "",
                            nickname = userMap?.get(Constants.NICKNAME) as? String ?: "",
                            isOnline = userMap?.get(Constants.IS_ONLINE) as? Boolean ?: true,
                            isDisabled = isDisabled,
                            citizenScore = citizenScore,
                            imageCredit = imageCredit
                        )
                    )
                } else {
                    firebaseAuth.signOut()

                    return UserResult.Error(R.string.account_not_exist)
                }
            } catch (e: Exception) {
                Timber.e(e, "User validation failed")
                if (!isNetworkError(e)) {
                    firebaseAuth.signOut()

                    return UserResult.Error(R.string.login_error)
                }

                return UserResult.Error(R.string.network_error)
            }
        }

        return UserResult.Error(R.string.login_error)
    }

    override suspend fun registerWithEmail(email: String, password: String): UserResult {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = result.user

            if (firebaseUser != null) {
                Timber.d("Register success: ${firebaseUser.uid}")

                val user = User(
                    id = firebaseUser.uid,
                    email = email,
                    nickname = "",
                    isOnline = true
                )

                saveUserMapToFirebase(user, isDisabled = false)

                UserResult.Success(user)
            } else {
                UserResult.Error(R.string.account_not_exist)
            }
        } catch (e: FirebaseAuthUserCollisionException) {
            Timber.e(e, "Email already registered")
            UserResult.Error(R.string.account_already_registered)
        } catch (e: Exception) {
            Timber.e(e, "Register failed")
            UserResult.Error(R.string.login_error)
        }
    }

    override suspend fun loginWithEmail(email: String, password: String): UserResult? {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = result.user

            if (firebaseUser != null) {
                val user = User(
                    id = firebaseUser.uid,
                    email = firebaseUser.email ?: "",
                    nickname = firebaseUser.displayName ?: "",
                    isOnline = true
                )
                UserResult.Success(user)
            } else {
                UserResult.Error(R.string.account_not_exist)
            }
        } catch (e: Exception) {
            Timber.e(e, "Login with email failed")
            if (isNetworkError(e)) {
                UserResult.Error(R.string.network_error)
            } else {
                UserResult.Error(R.string.invalid_credentials)
            }
        }
    }

    private suspend fun saveUserMapToFirebase(user: User, isDisabled: Boolean = false) {
        val userMapToSave = mapOf(
            Constants.ID to user.id,
            Constants.EMAIL to user.email,
            Constants.NICKNAME to user.nickname,
            Constants.IS_ONLINE to user.isOnline,
            Constants.LAST_UPDATED to System.currentTimeMillis(),
            Constants.IS_DISABLED to isDisabled,
            Constants.CITIZEN_SCORE to user.citizenScore,
            Constants.IMAGE_CREDIT to user.imageCredit
        )

        withTimeout(Constants.SAVE_USER_TIMEOUT) {
            database.child(Constants.USERS).child(user.id).updateChildren(userMapToSave).await()
        }
    }

    override suspend fun addFcmToken(userId: String, token: String) {
        database.child(Constants.USERS)
            .child(userId)
            .child(Constants.FCM_TOKENS)
            .child(token)
            .setValue(true)
            .await()
    }

    override fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
        firebaseAuth.addAuthStateListener(listener)
    }

    override fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
        firebaseAuth.removeAuthStateListener(listener)
    }

    override suspend fun getChatRoomStatus(roomId: String): Boolean? {
        return try {
            val roomSnapshot = database.child(Constants.CHAT_ROOMS).child(roomId).get().await()
            roomSnapshot.child(Constants.ACTIVE).getValue(Boolean::class.java) ?: true
        } catch (e: Exception) {
            Timber.e(e, "Failed to get room status for $roomId")
            null
        }
    }

    override suspend fun getActiveOrLastRoom(userId: String): String? {
        val snapshot = database.child(Constants.USERS).child(userId).get().await()

        val activeRoomId = snapshot.child(Constants.ACTIVE_ROOM_ID).getValue(String::class.java)
        if (!activeRoomId.isNullOrEmpty()) return activeRoomId

        val lastRoomId = snapshot.child(Constants.LAST_ROOM_ID).getValue(String::class.java)
        return lastRoomId
    }

    override suspend fun getActiveRoomId(userId: String): String? {
        val snapshot = database.child(Constants.USERS).child(userId).get().await()
        return snapshot.child(Constants.ACTIVE_ROOM_ID).getValue(String::class.java)
    }

    override suspend fun getUserById(userId: String): User? = withContext(Dispatchers.IO) {
        try {
            val snapshot = database
                .child(Constants.USERS)
                .child(userId)
                .get()
                .await()

            val user = snapshot.getValue(User::class.java)
            Timber.d("[getUserById] User loaded: $user")
            return@withContext user
        } catch (e: Exception) {
            Timber.e(e, "getUserById] Failed to load user: $userId")
            null
        }
    }

    override suspend fun removeFcmToken(userId: String, token: String) = try {
        database.child(Constants.USERS)
            .child(userId)
            .child(Constants.FCM_TOKENS)
            .child(token)
            .removeValue()
            .await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun getCitizenScore(userId: String): Int =
        try {
            val ref = database.child(Constants.USERS)
                .child(userId)
                .child(Constants.CITIZEN_SCORE)

            val snapshot = ref.get().await()
            snapshot.getValue(Int::class.java) ?: 0
        } catch (e: Exception) {
            Timber.e(e, "Failed to get citizen score for user $userId")
            0
        }

    override suspend fun getImageCredit(userId: String): Int = try {
        val ref = database.child(Constants.USERS).child(userId).child(Constants.IMAGE_CREDIT)
        val snapshot = ref.get().await()
        snapshot.getValue(Int::class.java) ?: 0
    } catch (_: Exception) {
        0
    }

    override suspend fun decreaseImageCredit(userId: String, delta: Int): Result<Unit> {
        return try {
            val ref = database.child(Constants.USERS).child(userId).child(Constants.IMAGE_CREDIT)
            val snapshot = ref.get().await()
            val current = snapshot.getValue(Int::class.java) ?: 0
            if (current < delta) return Result.failure(IllegalStateException("Insufficient credit"))
            ref.setValue(current - delta).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateUserLocation(userId: String, lat: Double, lng: Double) {
        val updates = mapOf(
            "${Constants.USERS}/$userId/${Constants.LOCATION}/${Constants.LAT}" to lat,
            "${Constants.USERS}/$userId/${Constants.LOCATION}/${Constants.LNG}" to lng
        )

        database.updateChildren(updates).await()
    }

}