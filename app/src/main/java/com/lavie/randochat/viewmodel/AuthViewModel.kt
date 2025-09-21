package com.lavie.randochat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.lavie.randochat.R
import com.lavie.randochat.model.User
import com.lavie.randochat.repository.UserRepository
import com.lavie.randochat.service.PreferencesService
import com.lavie.randochat.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class AuthViewModel(
    private val userRepository: UserRepository,
    private val firebaseMessaging: FirebaseMessaging,
    private val prefs: PreferencesService
) : ViewModel() {

    private val hasCheckedInitialState = AtomicBoolean(false)

    private val _loginState = MutableStateFlow<User?>(null)
    val loginState: StateFlow<User?> = _loginState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessageId = MutableStateFlow<Int?>(null)
    val errorMessageId: StateFlow<Int?> = _errorMessageId

    private val _signInRequest = MutableSharedFlow<Unit>()
    val signInRequest = _signInRequest.asSharedFlow()

    private val _progressMessageId = MutableStateFlow<Int?>(null)
    val progressMessageId: StateFlow<Int?> = _progressMessageId

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        if (user != null && _loginState.value == null &&  hasCheckedInitialState.compareAndSet(false, true)) {
            viewModelScope.launch {
                checkInitialUserState()
                Timber.d("🧪 AuthStateListener fired: loginState=${_loginState.value}, hasChecked=${hasCheckedInitialState.get()}")
            }
        }
    }

    init {
        userRepository.addAuthStateListener(authStateListener)
    }

    override fun onCleared() {
        super.onCleared()
        userRepository.removeAuthStateListener(authStateListener)
    }

    fun onGoogleLoginClick() {
        viewModelScope.launch {
            _signInRequest.emit(Unit)
        }
    }

    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessageId.value = null
            _progressMessageId.value = R.string.signing_in

            try {
                when (val result = userRepository.signInWithGoogle(idToken)) {
                    is UserRepository.UserResult.Success -> handleSuccessfulSignIn(result.user)
                    is UserRepository.UserResult.Error -> result.messageId?.let { handleLoginError(it) }
                    else -> handleLoginError(R.string.login_error)
                }
            } catch (e: Exception) {
                Timber.e(e, "Login failed")
                handleLoginError(R.string.login_error)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun checkInitialUserState() {
        viewModelScope.launch {
            val cachedUser = getCachedUser()
            val cachedRoom = getCachedRoomId()

            when (val result = userRepository.checkUserValid()) {
                is UserRepository.UserResult.Success -> {
                    val user = result.user
                    _loginState.value = user
                    cacheUser(user)

                    val roomId = userRepository.getActiveOrLastRoom(user.id)
                    if (roomId != null) {
                        _activeRoomId.value = roomId
                        cacheRoomId(roomId)
                        _navigationEvent.emit(NavigationEvent.NavigateToChat(roomId))
                    } else {
                        _navigationEvent.emit(NavigationEvent.NavigateToStartChat)
                    }
                }

                is UserRepository.UserResult.Error -> {
                    if (cachedUser != null) {
                        _loginState.value = cachedUser

                        if (cachedRoom != null) {
                            _activeRoomId.value = cachedRoom
                            _navigationEvent.emit(NavigationEvent.NavigateToChat(cachedRoom))
                        } else {
                            _navigationEvent.emit(NavigationEvent.NavigateToStartChat)
                            Timber.d("➡️ LoginCheckSplash Failed")
                        }
                    } else {
                        _errorMessageId.value = result.messageId ?: R.string.login_error
                    }
                }

                else -> {
                    _loginState.value = null
                    _errorMessageId.value = R.string.login_error
                }
            }
        }
    }

    private suspend fun handleSuccessfulSignIn(user: User) {
        _progressMessageId.value = R.string.saving_user_data

        when (val saveResult = userRepository.saveUserToDb(user)) {
            is UserRepository.UserResult.Success -> {
                _loginState.value = saveResult.user

                if (!hasCachedUser()) {
                    cacheUser(saveResult.user)
                }

                _progressMessageId.value = null
                _errorMessageId.value = null

                val activeRoom = userRepository.getActiveOrLastRoom(saveResult.user.id)
                _activeRoomId.value = activeRoom
                cacheRoomId(activeRoom)

                if (activeRoom != null) {
                    _navigationEvent.emit(NavigationEvent.NavigateToChat(activeRoom))
                } else {
                    _navigationEvent.emit(NavigationEvent.NavigateToStartChat)
                }
            }

            is UserRepository.UserResult.Error -> {
                saveResult.messageId?.let { handleLoginError(it) }
            }

            else -> handleLoginError(R.string.login_error)
        }

        updateFcmTokenForCurrentUser()
    }

    private fun handleLoginError(messageId: Int) {
        _loginState.value = null
        _errorMessageId.value = messageId
        _progressMessageId.value = null
    }

    fun registerWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessageId.value = null
            _progressMessageId.value = R.string.signing_in

            when (val result = userRepository.registerWithEmail(email, password)) {
                is UserRepository.UserResult.Success -> handleSuccessfulSignIn(result.user)
                is UserRepository.UserResult.Error -> result.messageId?.let { handleLoginError(it) }
                else -> handleLoginError(R.string.login_error)
            }

            _isLoading.value = false
        }
    }

    fun loginWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessageId.value = null
            _progressMessageId.value = R.string.signing_in

            when (val result = userRepository.loginWithEmail(email, password)) {
                is UserRepository.UserResult.Success -> handleSuccessfulSignIn(result.user)
                is UserRepository.UserResult.Error -> result.messageId?.let { handleLoginError(it) }
                else -> handleLoginError(R.string.login_error)
            }

            _isLoading.value = false
        }
    }

    private fun updateFcmTokenForCurrentUser() {
        val userId = loginState.value?.id ?: return
        firebaseMessaging.token.addOnSuccessListener { token ->
            viewModelScope.launch {
                userRepository.addFcmToken(userId, token)
            }
        }
    }

    private fun cacheUser(user: User?) {
        if (user != null) {
            prefs.putString(Constants.CACHED_USER_ID, user.id)
            prefs.putString(Constants.CACHED_USER_EMAIL, user.email)
            prefs.putString(Constants.CACHED_USER_NICKNAME, user.nickname)

            prefs.putBoolean(Constants.CACHED_USER_IS_DISABLED, user.isDisabled)
            prefs.putInt(Constants.CACHED_USER_CITIZEN_SCORE, user.citizenScore)
            prefs.putInt(Constants.CACHED_USER_IMAGE_CREDIT, user.imageCredit)

            val fcmTokensJson = JSONArray(user.fcmTokens).toString()
            prefs.putString(Constants.CACHED_USER_FCM_TOKENS, fcmTokensJson)
        } else {
            prefs.remove(Constants.CACHED_USER_ID)
            prefs.remove(Constants.CACHED_USER_EMAIL)
            prefs.remove(Constants.CACHED_USER_NICKNAME)

            prefs.remove(Constants.CACHED_USER_IS_DISABLED)
            prefs.remove(Constants.CACHED_USER_CITIZEN_SCORE)
            prefs.remove(Constants.CACHED_USER_IMAGE_CREDIT)
            prefs.remove(Constants.CACHED_USER_FCM_TOKENS)
        }
    }

    suspend fun restoreCachedUser(): Boolean = withContext(Dispatchers.IO) {
        Timber.d("🔁 [restoreCachedUser] Called")

        val firebaseUser = FirebaseAuth.getInstance().currentUser ?: return@withContext false
        val userId = firebaseUser.uid
        val user = userRepository.getUserById(userId) ?: return@withContext false
        val roomId = userRepository.getActiveOrLastRoom(userId) ?: return@withContext false

        Timber.d("📦 [restoreCachedUser] roomId = $roomId")

        withContext(Dispatchers.Main) {
            _loginState.value = user
            _activeRoomId.value = roomId
            _navigationEvent.emit(NavigationEvent.NavigateToChat(roomId))
            hasCheckedInitialState.set(true)        }

        return@withContext true
    }


    fun hasCachedUser(): Boolean {
        return getCachedUser() != null
    }

    private fun getCachedUser(): User? {
        val id = prefs.getString(Constants.CACHED_USER_ID, null) ?: return null
        val email = prefs.getString(Constants.CACHED_USER_EMAIL, "") ?: ""
        val nickname = prefs.getString(Constants.CACHED_USER_NICKNAME, "") ?: ""
        val isDisabled = prefs.getBoolean(Constants.CACHED_USER_IS_DISABLED, false)
        val citizenScore = prefs.getInt(Constants.CACHED_USER_CITIZEN_SCORE, 0)
        val imageCredit = prefs.getInt(Constants.CACHED_USER_IMAGE_CREDIT, 0)

        val fcmTokensJson = prefs.getString(Constants.CACHED_USER_FCM_TOKENS, "[]") ?: "[]"
        val fcmTokens = try {
            val arr = JSONArray(fcmTokensJson)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }

        return User(
            id = id,
            email = email,
            nickname = nickname,
            isOnline = false,
            isDisabled = isDisabled,
            citizenScore = citizenScore,
            imageCredit = imageCredit,
            fcmTokens = fcmTokens
        )
    }

    private fun cacheRoomId(roomId: String?) {
        if (roomId != null) {
            prefs.putString(Constants.CACHED_ROOM_ID, roomId)
        } else {
            prefs.remove(Constants.CACHED_ROOM_ID)
        }
    }

    fun clearActiveRoom() {
        _activeRoomId.value = null
        cacheRoomId(null)
    }

    fun clearCachedUser() {
        _loginState.value = null
        cacheUser(null)
    }

    private fun getCachedRoomId(): String? {
        return prefs.getString(Constants.CACHED_ROOM_ID, null)
    }

    suspend fun getActiveRoomIdOnly(): String? {
        val userId = loginState.value?.id ?: return null
        return userRepository.getActiveRoomId(userId)
    }

    fun logout() {
        val uid = loginState.value?.id ?: return
        firebaseMessaging.token.addOnSuccessListener { token ->
            viewModelScope.launch {
                userRepository.removeFcmToken(uid, token)
                FirebaseAuth.getInstance().signOut()
                clearCachedUser()
                clearActiveRoom()
                hasCheckedInitialState.set(false)
            }
        }
    }

    suspend fun getCurrentImageCredit(userId: String): Int {
        return userRepository.getImageCredit(userId)
    }

    fun updateLocation(lat: Double, lng: Double) {
        val userId = loginState.value?.id ?: return
        viewModelScope.launch {
            userRepository.updateUserLocation(userId, lat, lng)
        }
    }

    sealed class NavigationEvent {
        data object NavigateToStartChat : NavigationEvent()
        data class NavigateToChat(val roomId: String) : NavigationEvent()
    }
}
