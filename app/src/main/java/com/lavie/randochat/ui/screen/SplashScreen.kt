package com.lavie.randochat.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.lavie.randochat.R
import com.lavie.randochat.ui.component.CustomSpacer
import com.lavie.randochat.ui.theme.Dimens
import com.lavie.randochat.utils.Constants
import com.lavie.randochat.utils.SplashType
import com.lavie.randochat.viewmodel.AuthViewModel
import com.lavie.randochat.viewmodel.MatchViewModel
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lavie.randochat.utils.singleClickHandler
import timber.log.Timber

@Composable
fun SplashScreen(
    navController: NavController,
    splashType: SplashType,
    messageId: Int,
    authViewModel: AuthViewModel? = null,
    matchViewModel: MatchViewModel? = null,
) {
    val message = stringResource(id = messageId)

    when (splashType) {
        SplashType.LOGIN_CHECK -> {
            requireNotNull(authViewModel)
            LoginCheckSplash(
                authViewModel = authViewModel,
                navController = navController,
                defaultMessage = message
            )
        }

        SplashType.MATCHING -> {
            requireNotNull(matchViewModel)
            MatchingSplash(
                matchViewModel = matchViewModel,
                navController = navController,
                message = message
            )
        }
    }
}

@Composable
private fun LoginCheckSplash(
    authViewModel: AuthViewModel,
    navController: NavController,
    defaultMessage: String
) {
    LaunchedEffect(Unit) {
        authViewModel.navigationEvent.collect { event ->
            when (event) {
                is AuthViewModel.NavigationEvent.NavigateToChat -> {
                    navController.navigate("${Constants.CHAT_SCREEN}/${event.roomId}") {
                        popUpTo(Constants.SPLASH_SCREEN) { inclusive = true }
                    }
                }
                is AuthViewModel.NavigationEvent.NavigateToStartChat -> {
                    navController.navigate(Constants.START_CHAT_SCREEN) {
                        popUpTo(Constants.SPLASH_SCREEN) { inclusive = true }
                    }
                }
            }
        }
    }

    val loginState by authViewModel.loginState.collectAsState()
    LaunchedEffect(loginState) {
        if (loginState == null && !authViewModel.hasCachedUser()) {
            navController.navigate(Constants.WELCOME_SCREEN) {
                popUpTo(Constants.SPLASH_SCREEN) { inclusive = true }
            }
        }
    }

    SplashContent(message = defaultMessage)
}

@Composable
private fun MatchingSplash(
    matchViewModel: MatchViewModel,
    navController: NavController,
    message: String
) {
    val matchState by matchViewModel.matchState.collectAsState()

    LaunchedEffect(matchState) {
        when (matchState) {
            is MatchViewModel.MatchState.Matched -> {
                navController.navigate("${Constants.CHAT_SCREEN}/${(matchState as MatchViewModel.MatchState.Matched).roomId}") {
                    popUpTo(Constants.START_CHAT_SCREEN) { inclusive = false }
                }
            }

            is MatchViewModel.MatchState.Error -> {
                navController.popBackStack()
            }

            else -> {
            }
        }
    }

    SplashContent(
        message = message,
        cancelButtonText = stringResource(R.string.stop_matching),
        onCancelClick = {
            matchViewModel.cancelWaiting()
            navController.popBackStack()
        }
    )
}

@Composable
private fun SplashContent(
    message: String,
    cancelButtonText: String? = null,
    onCancelClick: (() -> Unit)? = null
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.processing_cat))
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = LottieConstants.IterateForever
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier.size(Dimens.processingAnimationHeight)
            )
        }

        Text(
            text = message,
            fontSize = Dimens.baseTextSize,
            color = Color.Black
        )

        CustomSpacer(height = Dimens.baseMarginDouble)

        if (cancelButtonText != null && onCancelClick != null) {
            Button(
                onClick = singleClickHandler { onCancelClick.invoke()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(
                    Dimens.smallBorderStrokeWidth,
                    MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(Dimens.baseMarginDouble)
            ) {
                Text(
                    text = cancelButtonText, fontSize = Dimens.baseTextSize
                )
            }
        }
    }
}