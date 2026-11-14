package com.v2ray.ang.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private enum class MainPage { Home, Configs, Settings }

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    uiState.amneziaCaptchaImage?.let { image ->
        AmneziaCaptchaDialog(image, onAction)
    }
    val groups = uiState.groups
    val dataLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isLoading = dataLoading || uiState.isInspectingImport
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(MainPage.Home) }
    BackHandler(enabled = page != MainPage.Home && drawerState.isClosed) { page = MainPage.Home }
    val searchQuery = uiState.searchQuery
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showManualImport by rememberSaveable { mutableStateOf(false) }
    val switchGuid = uiState.restartPromptGuid
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId, page) {
        if (groups.isEmpty() || page != MainPage.Configs) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)

    LaunchedEffect(pagerState, page) {
        if (page != MainPage.Configs) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            // Initial page is presentation state; the persisted group wins until layout synchronizes it.
            .drop(1)
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    uiState.importReview?.let { review ->
        val data = review.inspection
        androidx.compose.material3.AlertDialog(onDismissRequest = { onAction(MainAction.DismissImportReview) },
            title = { Text(stringResource(R.string.forouz_import_review)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.forouz_import_target, uiState.groups.firstOrNull { it.id == review.groupId }?.remarks
                    ?: stringResource(R.string.filter_config_all)))
                Text(stringResource(R.string.forouz_import_counts, data.profiles, data.subscriptions, data.advanced, data.malformed + data.unsupported, data.repeated))
                Text(stringResource(if (data.tooLarge) R.string.forouz_import_too_large else if (!data.canImport)
                    R.string.forouz_import_unrecognized else R.string.forouz_import_preview_note))
            } }, confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onAction(MainAction.ConfirmImport(review.id)) }, enabled = data.canImport) {
                    Text(stringResource(R.string.forouz_import))
                }
            }, dismissButton = { androidx.compose.material3.TextButton(onClick = { onAction(MainAction.DismissImportReview) }) {
                Text(stringResource(R.string.action_cancel)) } })
    }
    if (showImport) MainImportChooser(onAction, { onNavigate(MainDestination.Subscriptions) },
        { showManualImport = true }, { showImport = false })
    if (showManualImport) androidx.compose.material3.AlertDialog(onDismissRequest = { showManualImport = false },
        title = { Text(stringResource(R.string.forouz_manual)) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                ImportMenuContent(includeSources = false) { command -> showManualImport = false; onAction(command) }
            }
        }, confirmButton = {}, dismissButton = {
            androidx.compose.material3.TextButton(onClick = { showManualImport = false }) { Text(stringResource(R.string.action_cancel)) }
        })
    switchGuid?.let { requested ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { onAction(MainAction.DismissRestartConfirmation) },
            title = { Text(stringResource(R.string.forouz_switch_profile)) },
            text = { Text(stringResource(R.string.forouz_switch_note)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                onAction(MainAction.DismissRestartConfirmation)
                if (uiState.selectedGuid == requested && uiState.isRunning) onAction(MainAction.RestartService)
            }) { Text(stringResource(R.string.forouz_switch_profile)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { onAction(MainAction.DismissRestartConfirmation) }) { Text(stringResource(R.string.action_cancel)) } })
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (page != MainPage.Configs) {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(painterResource(R.drawable.ic_menu_24dp), stringResource(R.string.acc_open_menu))
                            }
                            Spacer(Modifier.width(12.dp))
                            Icon(painterResource(R.drawable.ic_forouz_mark), null,
                                modifier = Modifier.size(36.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { onAction(MainAction.ImportQRcode) }) {
                                Icon(painterResource(R.drawable.ic_scan_24dp), stringResource(R.string.menu_item_import_config_qrcode))
                            }
                            IconButton(onClick = { showImport = true }) {
                                Icon(painterResource(R.drawable.ic_add_24dp), stringResource(R.string.forouz_import))
                            }
                        }
                        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else {
                MainTopBar(
                    isLoading = isLoading,
                    onSearchFocus = { searchFocus.requestFocus(); keyboard?.show() },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onImport = { showImport = true },
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> if (uiState.isRunning && uiState.selectedGuid != uiState.activeTestProfileGuid())
                                onAction(MainAction.RequestRestartConfirmation) else onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                        }
                    }
                )
                }
            },
            bottomBar = {
                Column(Modifier.imePadding()) {
                if (page == MainPage.Home) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 660.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OverviewConnectionAction(uiState, uiState.overviewPhase(), !isLoading &&
                            (uiState.homeProfile != null || uiState.overviewPhase() == OverviewPhase.EMPTY), { showImport = true }, onAction)
                    }
                }
                if (page == MainPage.Configs) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { onAction(if (uiState.isTesting) MainAction.CancelTesting else MainAction.TestRealAllServers) },
                        enabled = !isLoading, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(if (uiState.isTesting) R.string.action_cancel else R.string.forouz_test_profiles))
                    }
                    androidx.compose.material3.Button(onClick = { showImport = true }, enabled = !isLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.forouz_import)) }
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = page == MainPage.Home, onClick = { page = MainPage.Home },
                        icon = { Icon(painterResource(R.drawable.ic_power_24dp), null) },
                        label = { Text(stringResource(R.string.forouz_overview)) })
                    NavigationBarItem(selected = page == MainPage.Configs, onClick = { page = MainPage.Configs },
                        icon = { Icon(painterResource(R.drawable.ic_description_24dp), null) },
                        label = { Text(stringResource(R.string.forouz_profiles)) })
                    NavigationBarItem(selected = page == MainPage.Settings, onClick = { page = MainPage.Settings },
                        icon = { Icon(painterResource(R.drawable.ic_settings_24dp), null) },
                        label = { Text(stringResource(R.string.title_settings)) })
                }
                }
            },
            floatingActionButton = {},
        ) { innerPadding ->

            if (page == MainPage.Home) {
                MainHome(state = uiState, isLoading = isLoading, displayText = displayText,
                    onAction = onAction, onOpenConfigs = { page = MainPage.Configs; onAction(MainAction.LocateActiveProfile) },
                    onNavigate = onNavigate,
                    onSwitchProfile = { onAction(MainAction.RequestRestartConfirmation) },
                    modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding))
            } else if (page == MainPage.Settings) {
                MainSettingsHub(onNavigate, Modifier.padding(innerPadding).consumeWindowInsets(innerPadding))
            } else if (groups.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .imePadding()
                ) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        androidx.compose.material3.OutlinedTextField(value = searchQuery, onValueChange = { onAction(MainAction.Search(it)) },
                            label = { Text(stringResource(R.string.forouz_search_profiles)) }, singleLine = true,
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search_24dp), null) },
                            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { onAction(MainAction.Search("")) }) {
                                Icon(painterResource(R.drawable.ic_forouz_close), stringResource(R.string.forouz_clear_search)) } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(searchFocus))
                    }
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                        GroupPagerPage(
                            groupId = group.id,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            activeGuid = uiState.activeTestProfileGuid(),
                            locateTarget = uiState.locateTarget,
                            doubleColumnDisplay = doubleColumnDisplay,
                            searchQuery = searchQuery,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            usageInfo = group.usageInfo,
                            groupTitle = group.remarks,
                            onOpenLink = { url -> onAction(MainAction.OpenSubscriptionLink(url)) },
                            contentPadding = PaddingValues(
                                start = 0.dp,
                                top = 0.dp,
                                end = 0.dp,
                                bottom = 80.dp
                            )
                        )
                    }
                }
            }
        }
    }
}
