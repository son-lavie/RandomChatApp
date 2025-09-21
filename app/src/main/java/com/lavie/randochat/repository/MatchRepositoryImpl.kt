package com.lavie.randochat.repository

import com.google.firebase.database.*
import com.lavie.randochat.R
import com.lavie.randochat.model.ChatRoom
import com.lavie.randochat.utils.ChatType
import com.lavie.randochat.utils.CommonUtils.haversineKm
import com.lavie.randochat.utils.Constants
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MatchRepositoryImpl(
    private val database: DatabaseReference
) : MatchRepository {

    override suspend fun findUserForMatch(
        userId: String,
        chatType: ChatType
    ): MatchRepository.MatchResult {

        var myLat: Double? = null
        var myLng: Double? = null
        if (chatType == ChatType.LOCATION) {
            val userLocSnap = database
                .child(Constants.USERS).child(userId).child(Constants.LOCATION)
                .get().await()

            myLat = userLocSnap.child(Constants.LAT).getValue(Double::class.java)
            myLng = userLocSnap.child(Constants.LNG).getValue(Double::class.java)

            if (myLat == null || myLng == null) {
                return MatchRepository.MatchResult.Error(R.string.location_unavailable) // tạo stringId
            }
        }

        return suspendCancellableCoroutine { cont ->
            val waitingRef = database.child(Constants.WAITING_USERS)
            val chatRoomsRef = database.child(Constants.CHAT_ROOMS)
            val matchNotiRef = database.child(Constants.MATCH_NOTIFICATIONS)

            var matchedUserId: String? = null

            waitingRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(mutableData: MutableData): Transaction.Result {
                    val waitingList = mutableData.children.toList()

                    val availableUsers = when (chatType) {
                        ChatType.RANDOM -> waitingList.filter {
                            it.key != userId &&
                                    it.child(Constants.STATUS).value == Constants.STATUS_WAITING &&
                                    it.child(Constants.MATCHING).value != true &&
                                    it.child(Constants.CHAT_TYPE).value?.toString() == ChatType.RANDOM.name
                        }

                        ChatType.AGE -> emptyList() // TODO: implement later

                        ChatType.LOCATION -> {
                            val currentUserNode = mutableData.child(userId)
                            if (currentUserNode.value == null ||
                                currentUserNode.child(Constants.STATUS).value != Constants.STATUS_WAITING
                            ) {
                                currentUserNode.child(Constants.STATUS).value = Constants.STATUS_WAITING
                                currentUserNode.child(Constants.TIMESTAMP).value = System.currentTimeMillis()
                                currentUserNode.child(Constants.MATCHING).value = false
                                currentUserNode.child(Constants.CHAT_TYPE).value = ChatType.LOCATION.name
                                currentUserNode.child(Constants.LOCATION).child(Constants.LAT).value = myLat
                                currentUserNode.child(Constants.LOCATION).child(Constants.LNG).value = myLng
                            }

                            waitingList.filter { other ->
                                val okCommon =
                                    other.key != userId &&
                                            other.child(Constants.STATUS).value == Constants.STATUS_WAITING &&
                                            other.child(Constants.MATCHING).value != true &&
                                            other.child(Constants.CHAT_TYPE).value?.toString() == ChatType.LOCATION.name

                                if (!okCommon) return@filter false

                                val oLat = other.child(Constants.LOCATION).child(Constants.LAT).getValue(Double::class.java)
                                val oLng = other.child(Constants.LOCATION).child(Constants.LNG).getValue(Double::class.java)
                                if (oLat == null || oLng == null || myLat == null || myLng == null) return@filter false

                                val dist = haversineKm(myLat!!, myLng!!, oLat, oLng)
                                dist <= Constants.MAX_MATCH_DISTANCE_KM
                            }
                        }
                    }

                    if (availableUsers.isNotEmpty()) {
                        val otherUser = availableUsers.minByOrNull {
                            it.child(Constants.TIMESTAMP).getValue(Long::class.java) ?: 0L
                        }!!

                        val otherUserId = otherUser.key!!
                        matchedUserId = otherUserId

                        mutableData.child(userId).child(Constants.MATCHING).value = true
                        mutableData.child(otherUserId).child(Constants.MATCHING).value = true

                        return Transaction.success(mutableData)
                    } else {
                        val currentUser = mutableData.child(userId)
                        if (currentUser.value == null || currentUser.child(Constants.STATUS).value != Constants.STATUS_WAITING) {
                            currentUser.child(Constants.STATUS).value = Constants.STATUS_WAITING
                            currentUser.child(Constants.TIMESTAMP).value = System.currentTimeMillis()
                            currentUser.child(Constants.MATCHING).value = false
                            currentUser.child(Constants.CHAT_TYPE).value = chatType.name
                            if (chatType == ChatType.LOCATION) {
                                currentUser.child(Constants.LOCATION).child(Constants.LAT).value = myLat
                                currentUser.child(Constants.LOCATION).child(Constants.LNG).value = myLng
                            }
                        }
                        return Transaction.success(mutableData)
                    }
                }

                override fun onComplete(
                    error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?
                ) {
                    if (error != null || !committed) {
                        cont.resume(MatchRepository.MatchResult.Error(R.string.network_error))
                        return
                    }

                    matchedUserId?.let { otherUserId ->
                        createMatchAndCleanup(
                            userId,
                            otherUserId,
                            chatType,
                            chatRoomsRef,
                            matchNotiRef
                        ) { result -> cont.resume(result) }
                    } ?: cont.resume(MatchRepository.MatchResult.Waiting)
                }
            })
        }
    }

    private fun createMatchAndCleanup(
        userId: String,
        otherUserId: String,
        chatType: ChatType,
        chatRoomsRef: DatabaseReference,
        matchNotificationsRef: DatabaseReference,
        callback: (MatchRepository.MatchResult) -> Unit
    ) {
        val newRoomId = chatRoomsRef.push().key ?: run {
            callback(MatchRepository.MatchResult.Error(R.string.match_failed))
            return
        }

        val chatRoom = ChatRoom(
            id = newRoomId,
            participantIds = listOf(userId, otherUserId),
            createdAt = System.currentTimeMillis(),
            lastUpdated = System.currentTimeMillis(),
            isActive = true,
            chatType = chatType
        )

        val matchDataForUser = mapOf(
            Constants.ROOM_ID to newRoomId,
            Constants.PARTNER_ID to otherUserId,
            Constants.TIMESTAMP to System.currentTimeMillis(),
            Constants.STATUS to Constants.STATUS_MATCHED
        )
        val matchDataForOther = mapOf(
            Constants.ROOM_ID to newRoomId,
            Constants.PARTNER_ID to userId,
            Constants.TIMESTAMP to System.currentTimeMillis(),
            Constants.STATUS to Constants.STATUS_MATCHED
        )

        val updates = hashMapOf(
            "${Constants.CHAT_ROOMS}/$newRoomId" to chatRoom,

            "${Constants.MATCH_NOTIFICATIONS}/$userId" to matchDataForUser,
            "${Constants.MATCH_NOTIFICATIONS}/$otherUserId" to matchDataForOther,

            "${Constants.USERS}/$userId/${Constants.ACTIVE_ROOM_ID}" to newRoomId,
            "${Constants.USERS}/$otherUserId/${Constants.ACTIVE_ROOM_ID}" to newRoomId,
            "${Constants.USERS}/$userId/${Constants.LAST_ROOM_ID}" to newRoomId,
            "${Constants.USERS}/$otherUserId/${Constants.LAST_ROOM_ID}" to newRoomId,

            "${Constants.WAITING_USERS}/$userId" to null,
            "${Constants.WAITING_USERS}/$otherUserId" to null
        )

        database.updateChildren(updates)
            .addOnSuccessListener { callback(MatchRepository.MatchResult.Matched(newRoomId, otherUserId)) }
            .addOnFailureListener {
                matchNotificationsRef.child(userId).removeValue()
                matchNotificationsRef.child(otherUserId).removeValue()

                val rollback = hashMapOf<String, Any>(
                    "${Constants.WAITING_USERS}/$userId/${Constants.STATUS}" to Constants.STATUS_WAITING,
                    "${Constants.WAITING_USERS}/$userId/${Constants.TIMESTAMP}" to System.currentTimeMillis(),
                    "${Constants.WAITING_USERS}/$userId/${Constants.MATCHING}" to false,
                    "${Constants.WAITING_USERS}/$otherUserId/${Constants.STATUS}" to Constants.STATUS_WAITING,
                    "${Constants.WAITING_USERS}/$otherUserId/${Constants.TIMESTAMP}" to System.currentTimeMillis(),
                    "${Constants.WAITING_USERS}/$otherUserId/${Constants.MATCHING}" to false
                )
                database.updateChildren(rollback)
                callback(MatchRepository.MatchResult.Error(R.string.match_failed))
            }
    }

    override suspend fun cancelWaiting(userId: String) {
        database.child(Constants.WAITING_USERS).child(userId).removeValue().await()
    }

    override fun observeMatchedUser(
        userId: String,
        onMatched: (matched: Boolean, matchedWith: String?, roomId: String?) -> Unit
    ): ValueEventListener {
        val ref = database.child(Constants.MATCH_NOTIFICATIONS).child(userId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val status = snapshot.child(Constants.STATUS).getValue(String::class.java)
                    if (status == Constants.STATUS_MATCHED) {
                        val partnerId =
                            snapshot.child(Constants.PARTNER_ID).getValue(String::class.java)
                        val roomId = snapshot.child(Constants.ROOM_ID).getValue(String::class.java)
                        onMatched(true, partnerId, roomId)

                        snapshot.ref.removeValue()
                    }
                } else {
                    onMatched(false, null, null)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    override fun removeMatchedListener(userId: String, listener: ValueEventListener) {
        database.child(Constants.MATCH_NOTIFICATIONS).child(userId).removeEventListener(listener)
    }
}
