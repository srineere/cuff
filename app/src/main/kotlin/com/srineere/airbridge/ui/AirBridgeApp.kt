package com.srineere.airbridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.srineere.airbridge.health.HealthEnabled
import com.srineere.airbridge.health.MorningMoment
import com.srineere.airbridge.health.isHealthRoute
import com.srineere.airbridge.health.isHiddenRoute
import com.srineere.airbridge.ui.theme.extras

/**
 * The tabs. Health is first in the bar, as it was always drawn, and the app
 * opens on Health when there is a Health tab at all: the relay runs without
 * being looked at, and the readings are the half of this app anyone opens
 * it to see.
 *
 * There are two tabs rather than three whenever the health half is
 * switched off, which it is by default — see
 * [com.srineere.airbridge.health.HealthEnabled]. Not three with one of
 * them empty: a tab that opens on "nothing here" is a worse answer than no
 * tab, and the switch that brings it back is one tab along, on Device.
 */
/**
 * The three tabs, each with the glyph for "you are not here" and the one
 * for "you are".
 *
 * Filled-when-selected is Material 3's own navigation pattern and this bar
 * did not have it: the only thing separating the current tab from the
 * other two was a colour, which on a dark theme at 24dp is not much.
 *
 * The glyphs changed with it. MonitorHeart draws a heart inside a monitor
 * frame, which at this size is a box with something in it; Vibration draws
 * a phone, on the one tab that is about what reaches your wrist instead of
 * your phone; and Watch drew a watch for a band with no face, which is the
 * same mistake the Device tab was making about the hardware itself.
 */
private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val chosen: ImageVector,
) {
    Health("health", "Health", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    // One glyph both ways. GraphicEq is a row of bars that reads as
    // something pulsing, which is nearer this tab's job than a bell: what
    // is configured here is how the band moves, not whether a phone makes
    // a sound. It has no outlined-versus-filled pair, so the indicator
    // pill and the colour carry the selected state on their own.
    Alerts("alerts", "Alerts", Icons.Outlined.GraphicEq, Icons.Outlined.GraphicEq),
    // One glyph both ways: it is drawn here rather than taken from a set,
    // and a second weight of it would be a second drawing to keep right.
    Device("device", "Device", BandIcon, BandIcon),
}

@Composable
fun AirBridgeApp(openRoute: String? = null, openCount: Int = 0) {
    val context = LocalContext.current
    WarmInstalledApps(context)
    val state = rememberRelayState(context)
    val health = rememberHealthState()
    val healthOn by HealthEnabled.enabled.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination

    // Which tabs there are. Two of them whenever the health half is off.
    val tabs = remember(healthOn) { Tab.entries.filter { healthOn || it != Tab.Health } }

    // The bottom of the back stack.
    //
    // Read once and held, rather than following the switch. A NavHost
    // rebuilds its graph when its start destination changes and
    // rebuilding empties the back stack -- so letting this follow would
    // throw whoever just flipped the switch off the Device tab they
    // flipped it on, which is the one screen they are certainly looking
    // at. [root] is what keeps it honest instead: the moment the health
    // half goes off, Alerts becomes the bottom and the stack is rebased
    // onto it.
    val startRoute = remember {
        if (HealthEnabled.isOn(context)) Tab.Health.route else Tab.Alerts.route
    }
    var root by remember { mutableStateOf(startRoute) }

    // Where a widget sent us.
    //
    // Navigated once per tap rather than once per composition: the same
    // widget tapped twice should land on its screen twice, and a route that
    // stayed in a remembered flag would only work the first time. [openCount]
    // is what makes the second tap a new key -- keyed on the route alone,
    // the identical String recorded no state change and the second tap did
    // nothing at all. Anything this build does not know is ignored, because
    // a widget placed by an older version can name a screen that has since
    // been renamed, and crashing on it is a worse answer than opening the
    // Health tab.
    // Going to a tab, from the bar or from a widget.
    //
    // One function for both, because a tab reached two ways that built two
    // different back stacks is a tab that behaves differently depending on
    // how you got there, and that is not something anybody can hold in
    // their head.
    // No saveState, and no restoreState. They are the reason the Health tab
    // could stop working, and the reason is worth keeping written down.
    //
    // The Material recipe for a bottom bar is
    // `popUpTo(startDestination) { saveState = true }` with
    // `restoreState = true`, and it assumes each tab is a *nested graph* with
    // a back stack of its own. This graph is flat: `apps`, `healthsleep`,
    // `people` and the rest are all siblings of the three tabs, not children
    // of them. So "save whatever sits above the start destination, and put it
    // back when you return" does not mean "restore this tab" here -- it means
    // "restore the last pushed screen anybody was looking at", whichever tab
    // they tapped.
    //
    // Which is exactly what it did. Open Apps, go to Device, tap Health, and
    // the saved stack came back: Apps again, under a Health tab that now
    // looked selected and would not move however many times it was tapped,
    // because every tap restored the same screen. It is also why it was
    // intermittent -- it needs a pushed screen to have been saved first, so
    // it never happened on a fresh start.
    //
    // Without them a tab goes to its own root, which is what a tab is for.
    // The cost is a scroll position; the thing bought is a bar that answers.
    // [root] rather than the graph's own start destination, which is the
    // same thing right up until the health half is switched off under a
    // session that opened on Health. After that the graph still names
    // Health as its start -- that is metadata and does not move -- while
    // the stack's bottom is Alerts, and popping to a route that is no
    // longer on the stack pops nothing at all, so the bar would quietly
    // start stacking tabs on each other.
    val goToTab: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(root)
            launchSingleTop = true
        }
    }

    // The Health tab going away under a stack that was built on it.
    //
    // Only a session that opened on Health can be in this state, and only
    // once. Alerts becomes the bottom, inclusive, so that nothing below
    // is left pointing at a tab the bar no longer has: without it, Back
    // from either remaining tab would land on a Health tab that is not in
    // the bar and cannot be left the way it was reached.
    //
    // Then straight back to the tab that was open, because the only way
    // to get here is to have just used the switch on the Device tab, and
    // an app that moves you off the screen you are touching for having
    // touched it is an app arguing. Both navigations run inside this one
    // effect, so the stack is [Alerts, Device] by the next frame and
    // nothing is drawn in between. Only a tab is restored -- a pushed
    // route's pattern is not a route you can navigate to, and there is
    // no way to be on one of those at this moment anyway.
    LaunchedEffect(healthOn) {
        if (healthOn || root != Tab.Health.route) return@LaunchedEffect
        root = Tab.Alerts.route
        val here = current?.route?.takeIf { route -> tabs.any { it.route == route } }
        runCatching {
            nav.navigate(Tab.Alerts.route) {
                popUpTo(Tab.Health.route) { inclusive = true }
                launchSingleTop = true
            }
            if (here != null && here != Tab.Alerts.route) {
                nav.navigate(here) { launchSingleTop = true }
            }
        }
    }

    // A health screen reached while the half is off.
    //
    // Every one of those routes is still registered: taking them out of
    // the graph would mean two graphs to keep right and a navigate() that
    // throws rather than one that goes somewhere. So the refusal happens
    // here, where it can say where to go instead -- Device, because the
    // switch that brings the screen back is on it, and "this is gone" is
    // half an answer without "and here is why".
    LaunchedEffect(healthOn, current?.route) {
        if (healthOn) return@LaunchedEffect
        if (!isHealthRoute(current?.route)) return@LaunchedEffect
        runCatching { goToTab(Tab.Device.route) }
    }

    androidx.compose.runtime.LaunchedEffect(openRoute, openCount) {
        val route = openRoute ?: return@LaunchedEffect
        runCatching {
            when {
                // The journal is hidden in this build and a notification
                // posted by one that still offered it can be in the shade
                // for days. Bringing the app forward is the whole of the
                // answer; there is no screen behind it any more.
                isHiddenRoute(route) -> Unit
                // A widget placed while the health half was on, tapped
                // after it was switched off. Refused here rather than two
                // frames later by the guard above, so that the screen it
                // cannot draw is never drawn at all.
                !healthOn && isHealthRoute(route) -> goToTab(Tab.Device.route)
                // A tab asked for by a widget goes through the bar's own
                // route rather than being pushed on top of wherever the app
                // happened to be left. Health used to be skipped entirely
                // here -- the effect returned without navigating -- so a
                // Health widget tapped while the app sat on Alerts opened
                // the app on Alerts and looked like a widget that did
                // nothing.
                tabs.any { it.route == route } -> goToTab(route)
                // Everything else is a screen, pushed. singleTop so that a
                // widget tapped twice in a row does not stack the same
                // screen twice and ask for two presses of Back to leave.
                else -> nav.navigate(route) { launchSingleTop = true }
            }
        }
    }

    // The very first launch lands on Permissions.
    //
    // A fresh install now opens on Alerts, because the health half is off
    // by default -- so the relay is the whole app for that person, and it
    // is sitting off and silent with nothing granted. Nothing on that
    // screen tells somebody there is a Permissions page in another tab,
    // so the app reads as broken rather than as unconfigured, which is
    // exactly the reason this landing existed when the first tab was
    // Health instead.
    //
    // What that screen must not do is ask for Health Connect. The half is
    // off and nothing is being read, so asking at the one moment somebody
    // has no idea what the app is would make "off by default" a thing the
    // app says and not a thing it does. PermissionsScreen leaves the
    // section out while the half is off and says so instead.
    //
    // Skipped when a widget asked for a particular screen: that is a
    // deliberate destination and it outranks an introduction.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (openRoute != null) return@LaunchedEffect
        if (!FirstRun.needsIntroducing(context)) return@LaunchedEffect
        // Marked on arrival, not on completion. Somebody who opens it and
        // backs out has been shown it, and showing it again is arguing.
        FirstRun.introduced(context)
        runCatching { nav.navigate("permissions") }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            // Only shown on the tabs themselves; a pushed screen takes the
            // whole height and comes back with its own arrow.
            val onTab = tabs.any { tab ->
                current?.hierarchy?.any { it.route == tab.route } == true
            }
            if (onTab) {
                NavigationBar(containerColor = MaterialTheme.extras.navSurface) {
                    tabs.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // Tapping the tab you are already on returns
                                // to the top of it, rather than doing
                                // nothing at all.
                                //
                                // `selected` is read off the destination
                                // hierarchy, so anything the navigator
                                // counts as inside this tab reads as
                                // selected -- and the old early return made
                                // that tap a no-op, which is identical to a
                                // dead button from the other side of the
                                // glass. Popping back to the tab's own route
                                // is what the bar is for and is what every
                                // other app does; where the tab is already
                                // the top of the stack, popBackStack finds
                                // nothing to pop and the tap costs nothing.
                                if (selected) {
                                    nav.popBackStack(tab.route, inclusive = false)
                                } else {
                                    goToTab(tab.route)
                                }
                            },
                            icon = {
                                // The indicator pill is Material's own and
                                // already animates; the icon inside it did
                                // not move at all. A small bounce on the
                                // one you just chose is the cheapest way
                                // for the bar to answer a tap.
                                val lift by animateFloatAsState(
                                    if (selected) 1f else 0.88f,
                                    Motion.enter,
                                    label = "tabIcon",
                                )
                                Icon(
                                    if (selected) tab.chosen else tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = lift
                                        scaleY = lift
                                    },
                                )
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { insets ->
        CompositionLocalProvider(
            LocalRelayState provides state,
            LocalHealthState provides health,
        ) {
            NavHost(
                navController = nav,
                startDestination = startRoute,
                modifier = Modifier.fillMaxSize().padding(insets),
                // Tabs cross-fade; pushed screens slide, set per route below.
                enterTransition = {
                    fadeIn(tween(170)) + scaleIn(tween(170), initialScale = 0.98f)
                },
                exitTransition = {
                    fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 1.02f)
                },
            ) {
                composable(Tab.Health.route) { HealthScreen(onOpen = nav::navigate) }
                composable(Tab.Alerts.route) { AlertsScreen(onOpen = nav::navigate) }
                composable(Tab.Device.route) { DeviceScreen(onOpen = nav::navigate) }

                pushed("apps") { AppsScreen(onBack = nav::popBackStack, onOpen = nav::navigate) }
                pushed("calls") { CallsScreen(onBack = nav::popBackStack, onOpen = nav::navigate) }
                pushed("people") { PeopleScreen(onBack = nav::popBackStack, onOpen = nav::navigate) }
                pushed("activity") { ActivityScreen(onBack = nav::popBackStack) }
                pushed("schedule") { ScheduleScreen(onBack = nav::popBackStack) }
                pushed(
                    route = "person/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    PersonScreen(
                        id = entry.arguments?.getString("id").orEmpty(),
                        onBack = nav::popBackStack,
                        onOpen = nav::navigate,
                    )
                }
                pushed("contacts") { ContactsScreen(onBack = nav::popBackStack, onOpen = nav::navigate) }
                pushed(
                    route = "contacts/{source}",
                    arguments = listOf(navArgument("source") { type = NavType.StringType }),
                ) { entry ->
                    ContactsScreen(
                        onBack = nav::popBackStack,
                        onOpen = nav::navigate,
                        initialSource = entry.arguments?.getString("source"),
                    )
                }
                pushed(
                    route = "callers/{group}",
                    arguments = listOf(navArgument("group") { type = NavType.StringType }),
                ) { entry ->
                    CallerListScreen(
                        group = CallerGroup.of(entry.arguments?.getString("group")),
                        onBack = nav::popBackStack,
                        onOpen = nav::navigate,
                    )
                }
                pushed(
                    route = "callers/{group}/{scope}",
                    arguments = listOf(
                        navArgument("group") { type = NavType.StringType },
                        navArgument("scope") { type = NavType.StringType },
                    ),
                ) { entry ->
                    CallerListScreen(
                        group = CallerGroup.of(entry.arguments?.getString("group")),
                        onBack = nav::popBackStack,
                        onOpen = nav::navigate,
                        packageName = entry.arguments?.getString("scope"),
                    )
                }
                pushed("healthheart") { HealthHeartScreen(onBack = nav::popBackStack) }
                pushed("healthenergy") {
                    HealthEnergyScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("healthsleep") {
                    HealthSleepScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("export") { HealthExportScreen(onBack = nav::popBackStack) }
                // Registered, and unreachable. The journal is hidden
                // rather than removed -- see JournalReminder.hidden --
                // so nothing links to this any more and `isHiddenRoute`
                // turns away the one intent that still could. It stays
                // in the graph because a route that is merely absent
                // makes navigate() throw instead of going somewhere, and
                // because the screen behind it is being kept.
                pushed("journal") { JournalScreen(onBack = nav::popBackStack) }
                pushed("readiness") { ReadinessScreen(onBack = nav::popBackStack) }
                pushed("fitnessage") {
                    FitnessAgeScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed(MorningMoment.ROUTE) {
                    MorningScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("healthsleepbank") {
                    HealthSleepBankScreen(onBack = nav::popBackStack)
                }
                pushed("healthsteps") {
                    HealthStepsScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("healthvitals") {
                    HealthVitalsScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("healthactivity") {
                    HealthActivityScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("healthsessions") {
                    HealthSessionsScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed(
                    route = "healthsession/{start}",
                    arguments = listOf(navArgument("start") { type = NavType.LongType }),
                ) { entry ->
                    HealthSessionScreen(
                        startMillis = entry.arguments?.getLong("start") ?: 0L,
                        onBack = nav::popBackStack,
                    )
                }
                pushed("healthtiles") { HealthTilesScreen(onBack = nav::popBackStack) }
                pushed("healthgoals") { HealthGoalsScreen(onBack = nav::popBackStack) }
                pushed("healthhrv") { HealthHrvScreen(onBack = nav::popBackStack) }
                pushed("healthnutrition") {
                    HealthNutritionScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("appearance") { AppearanceScreen(onBack = nav::popBackStack) }
                pushed("zeppsignin") { ZeppSignInScreen(onBack = nav::popBackStack) }
                pushed("healthsavedfoods") {
                    HealthSavedFoodsScreen(onBack = nav::popBackStack)
                }
                pushed("healthfoodlog") {
                    HealthFoodLogScreen(onBack = nav::popBackStack, onOpen = nav::navigate)
                }
                pushed("healthlogfood") { HealthLogFoodScreen(onBack = nav::popBackStack, onOpen = nav::navigate) }
                pushed("healthbalance") { HealthBalanceDayScreen(onBack = nav::popBackStack) }
                pushed("healthtrend") {
                    HealthTrendScreen(
                        metric = TrendMetric.Resting,
                        onBack = nav::popBackStack,
                        onOpen = nav::navigate,
                    )
                }
                pushed("healthweight") {
                    HealthTrendScreen(TrendMetric.Weight, onBack = nav::popBackStack)
                }
                pushed("healthbreathing") {
                    HealthTrendScreen(TrendMetric.Breathing, onBack = nav::popBackStack)
                }
                pushed("healthskin") {
                    HealthTrendScreen(TrendMetric.Skin, onBack = nav::popBackStack)
                }
                pushed("widgets") { WidgetsScreen(onBack = nav::popBackStack) }
                pushed("permissions") { PermissionsScreen(onBack = nav::popBackStack) }
                pushed("about") { AboutScreen(onBack = nav::popBackStack) }
                pushed(
                    route = "appcallers/{packageName}",
                    arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
                ) { entry ->
                    AppCallersScreen(
                        packageName = entry.arguments?.getString("packageName").orEmpty(),
                        onBack = nav::popBackStack,
                        onOpen = nav::navigate,
                    )
                }
                pushed(
                    route = "vibration/{target}",
                    arguments = listOf(navArgument("target") { type = NavType.StringType }),
                ) { entry ->
                    VibrationScreen(
                        target = VibrationTarget.decode(entry.arguments?.getString("target").orEmpty()),
                        onBack = nav::popBackStack,
                    )
                }
            }
        }
    }
}

/**
 * Reads the installed apps off the main thread, and again when something is
 * installed or removed.
 *
 * Without this the first screen that wants an app's name or icon pays for the
 * whole list on the frame it is drawn, which on a phone with a hundred apps is
 * several dropped frames before anything appears.
 */
@Composable
private fun WarmInstalledApps(context: Context) {
    val installed by InstalledApps.current.collectAsState()
    LaunchedEffect(installed) {
        if (installed == null) withContext(Dispatchers.IO) { InstalledApps.load(context) }
    }
    DisposableEffect(context) {
        // Only while we are on screen. Installing something is the one thing
        // that makes the list wrong, and it cannot happen while this is up
        // without us being told.
        val changes = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                InstalledApps.invalidate()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(changes, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(changes) } }
    }
}

/** A screen pushed over a tab: slides in from the trailing edge, back reverses it. */
private fun NavGraphBuilder.pushed(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, spring(stiffness = 420f))
    },
    exitTransition = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, spring(stiffness = 420f))
    },
    popEnterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, spring(stiffness = 420f))
    },
    popExitTransition = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, spring(stiffness = 420f))
    },
) { entry ->
    // The tile's colour is provided to the route that tile opened, not to
    // whichever route is current. Keying it on the current back stack entry
    // meant that on the way back the entry flipped to the tab while this
    // screen was still sliding out, so it spent its last frames repainting
    // itself in the default colour.
    CompositionLocalProvider(
        LocalTileColour provides LocalHealthState.current.openedWith
            ?.takeIf { it.first == route }
            ?.second,
    ) {
        Box(Modifier.fillMaxSize()) { content(entry) }
    }
}
