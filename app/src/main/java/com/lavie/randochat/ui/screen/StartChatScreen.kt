package com.lavie.randochat.ui.screen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.lavie.randochat.R
import com.lavie.randochat.ui.component.CustomSpacer
import com.lavie.randochat.ui.component.customToast
import com.lavie.randochat.ui.theme.Dimens
import com.lavie.randochat.utils.ChatType
import com.lavie.randochat.utils.CommonUtils.getCurrentLocation
import com.lavie.randochat.utils.Constants
import com.lavie.randochat.utils.singleClickHandler
import com.lavie.randochat.viewmodel.AuthViewModel
import com.lavie.randochat.viewmodel.ChatViewModel
import com.lavie.randochat.viewmodel.MatchViewModel
import kotlinx.coroutines.launch

@Composable
fun StartChatScreen(
    navController: NavController,
    matchViewModel: MatchViewModel,
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel
) {
    val myUser by authViewModel.loginState.collectAsState()
    val myUserId = myUser?.id
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val chatOptions = listOf(ChatType.RANDOM, ChatType.LOCATION, ChatType.AGE)
    var selectedChatType by remember { mutableStateOf(ChatType.RANDOM) }

    val activity = context as? Activity

    BackHandler { activity?.finish() }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            customToast(context, R.string.location_disabled)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(Dimens.mailIcon),
                tint = Color(0xFFD8D8D8)
            )
            CustomSpacer(height = Dimens.baseSpacerHeight)
            Text(
                fontSize = Dimens.welcomeTextSize,
                fontWeight = FontWeight.Medium,
                color = Color.Black,
                text = stringResource(R.string.lets_start_chatting)
            )
            CustomSpacer(height = Dimens.baseSpacerHeight)
        }

        items(chatOptions) { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clickable { selectedChatType = option }
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = selectedChatType == option,
                    onClick = { selectedChatType = option }
                )
                Text(
                    text = stringResource(getChatTypeLabel(option)),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        item {
            CustomSpacer(height = Dimens.baseSpacerHeight)

            TextButton(
                onClick = singleClickHandler {
                    scope.launch {
                        val roomId = authViewModel.getActiveRoomIdOnly()
                        if (!roomId.isNullOrEmpty()) {
                            customToast(context, R.string.already_in_chat)
                            navController.navigate("${Constants.CHAT_SCREEN}/$roomId")
                            return@launch
                        }

                        if (myUserId == null) return@launch

                        if (selectedChatType == ChatType.LOCATION) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!granted) {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                return@launch
                            }

                            val location = getCurrentLocation(context)
                            if (location == null) {
                                customToast(context, R.string.cannot_get_location)
                                return@launch
                            }

                            authViewModel.updateLocation(location.first, location.second)
                        }

                        navController.navigate("${Constants.SPLASH_SCREEN}/${Constants.SPLASH_MODE_MATCHING}/${R.string.matching}")
                        matchViewModel.startMatching(myUserId, selectedChatType)
                    }
                }
            ) {
                Text(
                    text = stringResource(R.string.start_a_chat),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF2979FF)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        chatViewModel.resetChatState()
    }
}

@Composable
fun getChatTypeLabel(chatType: ChatType): Int = when (chatType) {
    ChatType.RANDOM -> R.string.random_chat
    ChatType.LOCATION -> R.string.chat_by_location
    ChatType.AGE -> R.string.chat_by_age
}
