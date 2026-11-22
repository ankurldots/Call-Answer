package com.callanswer.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.callanswer.app.CallAnswerApp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Navigation graph. Routes are declared in [Routes] (Routes.kt); screens register their composable here.
 * [resumeTick] changes on every activity resume so grants made in system settings are re-read.
 */
@Composable
fun AppNavGraph(model: SetupModel, resumeTick: Int) {
    val navController = rememberNavController()
    val actions = rememberSetupActions(model)
    val auth = (LocalContext.current.applicationContext as CallAnswerApp).authRepository
    val authState by auth.state.collectAsState()
    LaunchedEffect(resumeTick, authState) { model.refresh() }

    NavHost(
        navController = navController,
        startDestination = if (model.onboardingDone) Routes.HOME else Routes.ONBOARDING,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(model, actions) {
                navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.HOME) {
            HomeScreen(
                model, actions,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenCallLog = { navController.navigate(Routes.CALL_LOG) },
                onOpenAccount = { navController.navigate(Routes.SETTINGS_ACCOUNT) },
            )
        }
        val back: () -> Unit = { navController.popBackStack() }
        composable(Routes.SETTINGS) { SettingsHubScreen(onOpen = { navController.navigate(it) }, onBack = back) }
        val openConflicts: () -> Unit = { navController.navigate(Routes.SETTINGS_CONFLICTS) }
        composable(Routes.SETTINGS_GENERAL) { GeneralSettingsScreen(rememberCloudSettings(), openConflicts, back) }
        composable(Routes.SETTINGS_GATES) { GatesSettingsScreen(rememberCloudSettings(), openConflicts, back) }
        composable(Routes.SETTINGS_CALL) { CallSettingsScreen(rememberCloudSettings(), openConflicts, back) }
        composable(Routes.SETTINGS_AGENT) {
            AgentSettingsScreen(
                (LocalContext.current.applicationContext as CallAnswerApp).companionApi, rememberCloudSettings(),
                onOpenConsent = { navController.navigate(Routes.CONSENT) }, onOpenConflicts = openConflicts, onBack = back,
            )
        }
        composable(Routes.SETTINGS_CONFLICTS) { SettingsConflictsScreen(rememberCloudSettings(), back) }
        composable(Routes.SETTINGS_ACCOUNT) {
            AccountScreen((LocalContext.current.applicationContext as CallAnswerApp).authRepository, back)
        }
        composable(Routes.SETTINGS_STEPS) { DeliveryStepsScreen(rememberCloudSettings(), openConflicts, back) }
        composable(Routes.CALL_LOG) {
            CallLogScreen(resumeTick, onOpenEntry = { navController.navigate(Routes.callDetail(it)) }, onBack = back)
        }
        composable(Routes.CALL_DETAIL) { entry ->
            val id = entry.arguments?.getString("entryId").orEmpty()
            CallDetailScreen(id, resumeTick, back)
        }
        composable(Routes.SETTINGS_SPAM) { SpamSettingsScreen(back) }
        composable(Routes.FORWARDING_SETUP) {
            ForwardingSetupScreen(
                (LocalContext.current.applicationContext as CallAnswerApp).companionApi, back,
                onOpenConsent = { navController.navigate(Routes.CONSENT) },
                onOpenTurnOff = { navController.navigate(Routes.FORWARDING_TURN_OFF) },
                onOpenTest = { navController.navigate(Routes.FORWARDING_TEST) },
            )
        }
        composable(Routes.FORWARDING_TURN_OFF) {
            ForwardingTurnOffScreen(
                (LocalContext.current.applicationContext as CallAnswerApp).companionApi, back,
                onOpenConsent = { navController.navigate(Routes.CONSENT) },
            )
        }
        composable(Routes.FORWARDING_TEST) {
            ForwardingTestScreen(
                (LocalContext.current.applicationContext as CallAnswerApp).companionApi, back,
                onOpenConsent = { navController.navigate(Routes.CONSENT) },
            )
        }
        composable(Routes.LIVE_CALL) { entry ->
            LiveCallScreen(entry.arguments?.getString("callId").orEmpty(), back)
        }
        composable(Routes.CONSENT) {
            ConsentScreen((LocalContext.current.applicationContext as CallAnswerApp).companionApi, back)
        }
    }
}
