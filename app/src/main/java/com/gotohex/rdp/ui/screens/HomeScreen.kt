package com.gotohex.rdp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gotohex.rdp.R
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.session.SessionTab
import com.gotohex.rdp.session.SessionTabManager
import com.gotohex.rdp.ui.MainViewModel
import com.gotohex.rdp.ui.components.*
import com.gotohex.rdp.ui.screens.RdpSessionActivity
import com.gotohex.rdp.ui.theme.*
import kotlin.math.sin
import kotlin.math.PI

// ── Protocol filter tabs ────────────────────────────────────────────────────
private enum class ProtocolFilter(val protocol: ProtocolType?) {
    ALL(null),
    RDP(ProtocolType.RDP),
    VNC(ProtocolType.VNC),
    SSH(ProtocolType.SSH),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState  by viewModel.uiState.collectAsState()
    val context            = LocalContext.current
    val haptics            = LocalHapticFeedback.current
    val sound              = LocalSoundManager.current
    val scope              = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Feature-05: live session tabs
    val sessionTabs  by viewModel.sessionTabs.collectAsState()

    var showAddDialog    by remember { mutableStateOf(false) }
    var editingProfile   by remember { mutableStateOf<RdpProfile?>(null) }
    var deletingProfile  by remember { mutableStateOf<RdpProfile?>(null) }
    var activeFilter     by remember { mutableStateOf(ProtocolFilter.ALL) }
    var showQuickConnect by remember { mutableStateOf(false) }
    // UX-04: text search — hidden by default, revealed by pull-down gesture
    var searchQuery      by remember { mutableStateOf("") }
    var searchVisible    by remember { mutableStateOf(false) }

    // ── Import .rdp file ──────────────────────────────────────────────────────
    val pendingImport by viewModel.pendingImportProfile.collectAsState()
    // BUG #1 FIX: collect importError and show it as a Snackbar
    val importError   by viewModel.importError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(importError) {
        val msg = importError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearPendingImport()
        }
    }
    // CRIT-4 FIX: Show profile save Keystore errors (CRIT-1) as a Snackbar.
    // addProfile() and updateProfile() emit to profileSaveError when CryptoHelper.encrypt()
    // throws SecurityException. Without this LaunchedEffect the error was silently dropped
    // and the user had no feedback that their profile was NOT saved.
    val profileSaveError by viewModel.profileSaveError.collectAsState()
    LaunchedEffect(profileSaveError) {
        val msg = profileSaveError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearProfileSaveError()
        }
    }
    // FIX L1 / FIX-i18n: Show Wake-on-LAN success / error as a localised Snackbar.
    // wolResult is now Boolean? (true=success, false=error) so we resolve the
    // correct string resource here rather than relying on a hardcoded English string
    // from the ViewModel.
    val wolResult by viewModel.wolResult.collectAsState()
    val wolSentText  = stringResource(R.string.wol_sent)
    val wolErrorText = stringResource(R.string.wol_error)
    LaunchedEffect(wolResult) {
        val success = wolResult
        if (success != null) {
            snackbarHostState.showSnackbar(if (success) wolSentText else wolErrorText)
            viewModel.clearWolResult()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.parseRdpUri(it, context.contentResolver) }
    }
    // UX-03: drag-to-reorder local list
    // PERF-FIX: reorderableList is now reordered LIVE as the finger crosses a
    // neighbour's midpoint (not just once on release). Combined with
    // Modifier.animateItem() on every non-dragged card, the cards that make
    // way now slide smoothly into their new slot instead of snapping when the
    // drag ends. Items are tracked by stable `id`, not by index, since the
    // index of the dragged item keeps changing while the list is live-sorted.
    var reorderableList  by remember { mutableStateOf<List<RdpProfile>>(emptyList()) }
    var isDragging       by remember { mutableStateOf(false) }
    var draggingId        by remember { mutableStateOf<String?>(null) }
    var dragOffsetY       by remember { mutableStateOf(0f) }
    // heights of each card, keyed by stable profile id (survives reordering,
    // unlike an index-keyed map which would go stale the moment items swap)
    val itemHeightsById   = remember { mutableStateMapOf<String, Float>() }

    // UX-05: Subscribe dialogs removed — they were opening Telegram on every
    // 3-day interval which felt like spam and harmed Store ratings.
    // First-launch state is still marked shown so old users aren't affected.
    LaunchedEffect(uiState.showFirstLaunchDialog) {
        if (uiState.showFirstLaunchDialog) viewModel.dismissFirstLaunchDialog()
    }

    // UI-POLISH: count home-screen visits once per entry so the full text swipe-hint
    // banner can fade away automatically after the user has seen it a few times,
    // instead of repeating the same instruction forever on every card.
    LaunchedEffect(Unit) {
        viewModel.markHomeScreenOpened(uiState.settings.homeScreenOpenCount)
    }

    if (showAddDialog) {
        ProfileFormDialog(
            // BUGFIX #4: open the form pre-selected on whichever protocol tab
            // (RDP/VNC/SSH) the user currently has active, instead of always RDP.
            initialProtocolType = activeFilter.protocol,
            onDismiss = { showAddDialog = false },
            onSave    = { profile -> viewModel.addProfile(profile); showAddDialog = false }
        )
    }

    // Import .rdp — shows ProfileFormDialog pre-filled with parsed data
    pendingImport?.let { importedProfile ->
        ProfileFormDialog(
            profile   = importedProfile,
            onDismiss = { viewModel.clearPendingImport() },
            onSave    = { profile -> viewModel.addProfile(profile); viewModel.clearPendingImport() }
        )
    }

    // UX-08: Quick connect — no profile saved, immediate connection
    if (showQuickConnect) {
        QuickConnectDialog(
            onDismiss = { showQuickConnect = false },
            onConnect = { host, port, username, password ->
                showQuickConnect = false
                // FIX: same MAX_TABS guard as the profile card onConnect.
                if (sessionTabs.size >= SessionTabManager.MAX_TABS) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.error_max_sessions, SessionTabManager.MAX_TABS)
                        )
                    }
                } else {
                    // FIX #1 (Security): Store credentials in memory-only cache and pass
                    // a one-time token instead of the plaintext password. Intent extras are
                    // visible via `adb shell dumpsys activity` and appear in bug reports.
                    val token = com.gotohex.rdp.security.QuickConnectCache.put(
                        host, port, username, password
                    )
                    val intent = android.content.Intent(context, RdpSessionActivity::class.java)
                        .putExtra("profile_id", "__quick__")
                        .putExtra("quick_token", token)
                    context.startActivity(intent)
                }
            }
        )
    }

    editingProfile?.let { profile ->
        ProfileFormDialog(
            profile   = profile,
            onDismiss = { editingProfile = null },
            onSave    = { updated -> viewModel.updateProfile(updated); editingProfile = null }
        )
    }

    deletingProfile?.let { profile ->
        DeleteConfirmDialog(
            profileName = profile.name,
            onConfirm   = { viewModel.deleteProfile(profile); deletingProfile = null },
            onDismiss   = { deletingProfile = null }
        )
    }

    // ── Filtered list (UX-04: includes text search) ──────────────────────────
    val filtered = remember(uiState.profiles, activeFilter, searchQuery) {
        uiState.profiles
            .let { list ->
                if (activeFilter.protocol == null) list
                else list.filter { it.protocolType == activeFilter.protocol }
            }
            .let { list ->
                if (searchQuery.isBlank()) list
                else list.filter { p ->
                    p.name.contains(searchQuery, ignoreCase = true) ||
                    p.host.contains(searchQuery, ignoreCase = true)
                }
            }
    }

    // UX-03: keep local reorderable list in sync with filtered
    LaunchedEffect(filtered) {
        if (!isDragging) reorderableList = filtered
    }

    StarfieldBackground(isDark = uiState.settings.isDarkMode, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            // ── No top bar — removed per requirements ──────────────────────
            // BUG #1 FIX: host the snackbar that shows import errors
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                SpaceBottomBar(
                    hapticEnabled      = uiState.settings.hapticFeedback,
                    activeSessionCount = sessionTabs.size,
                    onSettingsClick    = { navController.navigate("settings") },
                    onAddClick         = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.5f)
                        showAddDialog = true
                    },
                    onAddLongClick     = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.5f)
                        showQuickConnect = true
                    },
                    onImportClick      = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.5f)
                        importLauncher.launch(
                            // BUG-6 FIX: "*/*" accepted any file type, causing a confusing error
                            // when the user picked a PDF or image. Priority MIME types filter the
                            // system file picker to .rdp files; "*/*" is the fallback for pickers
                            // that don't support custom MIME types.
                            arrayOf("application/x-rdp", "application/octet-stream", "*/*")
                        )
                    },
                    onSessionsClick    = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.5f)
                        navController.navigate("sessions")
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // NOTE: the old floating "active sessions" tab strip that used to sit
                // here (top of Home) was removed — it rendered as a cramped, clipped
                // badge with no room for the session name and an unreliable × button.
                // Active sessions now live in their own dedicated screen, reachable
                // from the "Sessions" button in the bottom bar (with a live count badge).

                // ── Search Bar (UX-11) — pull-down to reveal ──────────────
                // Auto-hide on upward scroll handled by nestedScroll below.
                AnimatedVisibility(
                    visible = searchVisible,
                    enter   = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit    = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        SearchBar(
                            query         = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier      = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // ── Protocol Filter Tabs ───────────────────────────────────
                ProtocolFilterRow(
                    active   = activeFilter,
                    onSelect = { activeFilter = it }
                )
                Spacer(Modifier.height(4.dp))

                // ── Content ────────────────────────────────────────────────
                if (uiState.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SpaceLoadingIndicator()
                    }
                } else if (filtered.isEmpty()) {
                    EmptyState(
                        modifier      = Modifier.fillMaxSize(),
                        filter        = activeFilter,
                        onAddClick    = { showAddDialog = true }
                    )
                } else {
                    // ── Network warning banner ─────────────────────────────
                    AnimatedVisibility(
                        visible = uiState.networkQuality == com.gotohex.rdp.ui.NetworkQuality.POOR,
                        enter   = expandVertically() + fadeIn(),
                        exit    = shrinkVertically() + fadeOut()
                    ) {
                        NetworkBanner(modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    // UI-POLISH: one-time swipe-gesture hint, shown as a single banner
                    // (not repeated on every card) and only for the first 3 visits —
                    // replaces the old per-card duplicated hint text.
                    AnimatedVisibility(
                        visible = uiState.settings.homeScreenOpenCount < 3,
                        enter   = fadeIn() + expandVertically(),
                        exit    = fadeOut() + shrinkVertically()
                    ) {
                        SwipeHintBanner(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }

                    // UX-11: pull-down to reveal search bar
                    val nestedScrollConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                // Scrolling UP (negative y) → hide search if query empty
                                if (available.y < -8f && searchQuery.isEmpty()) {
                                    searchVisible = false
                                    keyboardController?.hide() // FIX 1: dismiss keyboard when bar collapses
                                }
                                return Offset.Zero
                            }
                            override fun onPostScroll(
                                consumed: Offset, available: Offset, source: NestedScrollSource
                            ): Offset {
                                // Pull-down at top of list (available.y > 0 after list consumed 0)
                                if (available.y > 8f) {
                                    searchVisible = true
                                }
                                return Offset.Zero
                            }
                        }
                    }

                    // UX-03: Drag-to-reorder list — full pointer-based implementation
                    // PERF-FIX (professional reorder): the list is now re-sorted the
                    // instant the dragged card's centre crosses a neighbour's midpoint
                    // (previously the reorder only happened once, on release, which is
                    // why every other card used to "teleport" — appearing to launch
                    // upward — the moment you touched it). Every non-dragged card now
                    // carries Modifier.animateItem(), so when its index changes it
                    // glides smoothly into its new slot instead of snapping. The
                    // dragged card itself is excluded from animateItem() and is
                    // positioned purely by translationY so it tracks the finger with
                    // zero animation lag.
                    val listState      = rememberLazyListState()
                    val density        = LocalDensity.current
                    val itemSpacingPx  = with(density) { 14.dp.toPx() }
                    val defaultItemHeightPx = with(density) { 90.dp.toPx() }

                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(
                            items = reorderableList,
                            key   = { _, p -> p.id }
                        ) { index, profile ->
                            val isBeingDragged = isDragging && profile.id == draggingId

                            AnimatedVisibility(
                                visible  = true,
                                enter    = fadeIn(tween(300)) + slideInVertically(
                                    tween(300, easing = FastOutSlowInEasing)
                                ) { it / 3 },
                                // Cards making way for the dragged one animate their
                                // placement; the dragged card is driven manually below
                                // so the two animation systems never fight each other.
                                modifier = if (isBeingDragged) Modifier else Modifier.animateItem(
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness    = Spring.StiffnessMediumLow
                                    )
                                )
                            ) {
                                ReorderableProfileCard(
                                    profile        = profile,
                                    isDragTarget   = isBeingDragged,
                                    isDropTarget   = false,
                                    dragOffsetY    = if (isBeingDragged) dragOffsetY else 0f,
                                    onConnect      = {
                                        // FIX: check MAX_TABS before launching Activity.
                                        // Previously the Activity was always launched; if
                                        // openTab() returned null inside the Activity the
                                        // session connected silently without a tab slot,
                                        // bypassing OOM protection.
                                        if (sessionTabs.size >= SessionTabManager.MAX_TABS) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(
                                                        R.string.error_max_sessions,
                                                        SessionTabManager.MAX_TABS
                                                    )
                                                )
                                            }
                                        } else {
                                            val intent = Intent(context, RdpSessionActivity::class.java)
                                                .putExtra("profile_id", profile.id)
                                            context.startActivity(intent)
                                        }
                                    },
                                    onEdit         = { editingProfile = profile },
                                    onDelete       = { deletingProfile = profile },
                                    // BUGFIX-UI-1: يبقى true طالما Dialog التعديل أو تأكيد
                                    // الحذف مفتوحاً لهذا البروفايل تحديداً؛ عند إغلاقه (إلغاء)
                                    // يعود false فيُعيد SwipeableProfileCard إظهار الكرت
                                    // بدلاً من أن يبقى مخفياً للأبد.
                                    actionPending  = editingProfile?.id == profile.id ||
                                                      deletingProfile?.id == profile.id,
                                    onWakeOnLan    = if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) ({
                                        viewModel.sendWakeOnLan(profile)
                                        // BUG #1 FIX: sound is SoundManager? (nullable) — use safe-call to prevent NPE
                                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TOGGLE)
                                    }) else null,
                                    onHeightMeasured = { h -> itemHeightsById[profile.id] = h },
                                    onDragStart    = {
                                        isDragging  = true
                                        draggingId  = profile.id
                                        dragOffsetY = 0f
                                        // FIX #1: فحص إعداد hapticFeedback قبل التنفيذ
                                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag         = { deltaY ->
                                        dragOffsetY += deltaY
                                        val id = draggingId
                                        val currentIndex = if (id != null)
                                            reorderableList.indexOfFirst { it.id == id } else -1

                                        if (id != null && currentIndex != -1) {
                                        // Top (y) of the dragged card's current slot
                                        var accumulated = 0f
                                        for (i in 0 until currentIndex) {
                                            accumulated += (itemHeightsById[reorderableList[i].id]
                                                ?: defaultItemHeightPx) + itemSpacingPx
                                        }
                                        val myHeight = itemHeightsById[id] ?: defaultItemHeightPx
                                        val myCenter = accumulated + myHeight / 2f + dragOffsetY

                                        // Which slot does that centre now fall into?
                                        var acc2 = 0f
                                        var newTarget = currentIndex
                                        for (j in reorderableList.indices) {
                                            val hj = (itemHeightsById[reorderableList[j].id]
                                                ?: defaultItemHeightPx) + itemSpacingPx
                                            if (myCenter < acc2 + hj / 2f) {
                                                newTarget = j
                                                break
                                            }
                                            acc2 += hj
                                            newTarget = j
                                        }
                                        newTarget = newTarget.coerceIn(0, reorderableList.lastIndex)

                                        if (newTarget != currentIndex) {
                                            // Live-move the item now, so the other cards
                                            // animate out of the way immediately instead
                                            // of jumping at drag end.
                                            val mutable = reorderableList.toMutableList()
                                            val item = mutable.removeAt(currentIndex)
                                            mutable.add(newTarget, item)
                                            reorderableList = mutable

                                            // Compensate dragOffsetY by exactly how much the
                                            // slot's natural top moved, so the card's visual
                                            // position (naturalTop + dragOffsetY) stays
                                            // perfectly continuous under the finger — this is
                                            // what eliminates the "teleport" on swap.
                                            var newAccumulated = 0f
                                            for (i in 0 until newTarget) {
                                                newAccumulated += (itemHeightsById[mutable[i].id]
                                                    ?: defaultItemHeightPx) + itemSpacingPx
                                            }
                                            dragOffsetY += (accumulated - newAccumulated)
                                        }
                                        }
                                    },
                                    onDragEnd      = {
                                        // The list is already in its final order (it was
                                        // sorted live during the drag) — just persist it.
                                        // FIX 2: When a filter / search is active, reorderableList
                                        // is only a subset of all profiles.  Passing it directly to
                                        // reorderProfiles() would discard the positions of every
                                        // profile not currently visible.
                                        // Solution: rebuild the full ordered list by keeping the
                                        // relative order of hidden profiles intact, inserting the
                                        // reordered visible ones at their original positions.
                                        val fullList   = uiState.profiles
                                        val visibleIds = reorderableList.map { it.id }.toSet()
                                        val merged     = mutableListOf<RdpProfile>()
                                        var visIdx     = 0
                                        for (p in fullList) {
                                            if (p.id in visibleIds) {
                                                // Replace with the reordered version
                                                merged.add(reorderableList[visIdx++])
                                            } else {
                                                merged.add(p)
                                            }
                                        }
                                        viewModel.reorderProfiles(merged)
                                        isDragging  = false
                                        draggingId  = null
                                        dragOffsetY = 0f
                                    },
                                    hapticEnabled  = uiState.settings.hapticFeedback,   // FIX #1
                                )
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

// ── Protocol Filter Row ───────────────────────────────────────────────────────
@Composable
private fun ProtocolFilterRow(
    active: ProtocolFilter,
    onSelect: (ProtocolFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProtocolFilter.entries.forEach { filter ->
            FilterChip(
                filter   = filter,
                selected = filter == active,
                onClick  = { onSelect(filter) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FilterChip(
    filter: ProtocolFilter,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent    = PulsarCyan
    val secondary = QuantumBlue
    val surface   = NebulaSurface
    val sound     = LocalSoundManager.current

    val bgColor by animateColorAsState(
        targetValue   = if (selected) accent.copy(alpha = 0.18f) else surface,
        animationSpec = tween(220),
        label         = "chip_bg"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (selected) accent else HorizonGray.copy(alpha = 0.5f),
        animationSpec = tween(220),
        label         = "chip_border"
    )
    val textColor by animateColorAsState(
        targetValue   = if (selected) accent else CometTail,
        animationSpec = tween(220),
        label         = "chip_text"
    )
    val scale by animateFloatAsState(
        targetValue   = if (selected) 1.04f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label         = "chip_scale"
    )

    val icon = when (filter.protocol) {
        ProtocolType.RDP -> Icons.Outlined.DesktopWindows
        ProtocolType.VNC -> Icons.Outlined.Monitor
        ProtocolType.SSH -> Icons.Outlined.Terminal
        null             -> Icons.Outlined.GridView
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(48.dp) // FIX-TOUCH: was 42dp — below Material Design 48dp minimum touch target
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.3f)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(16.dp)) // BUG-12 FIX: was 14dp, below 16dp minimum legibility threshold
            Spacer(Modifier.width(4.dp))
            Text(
                text  = when (filter) {
                    ProtocolFilter.ALL -> stringResource(R.string.filter_all)
                    ProtocolFilter.RDP -> "RDP"
                    ProtocolFilter.VNC -> "VNC"
                    ProtocolFilter.SSH -> "SSH"
                },
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
        // Glow line below selected tab
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.5f)
                    .height(2.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, accent, Color.Transparent)
                        ),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

// ── Space Bottom Navigation Bar ───────────────────────────────────────────────
// REDESIGN v2: the previous version floated the Add-connection FAB directly on
// top of a flat pill edge — the button and the dock never actually connected,
// which read as disjointed. This version cuts a real circular notch into the
// pill itself (via Path.combine/Difference) so the FAB nests INTO the dock
// with a thin "moat" ring of visible background around it, plus a layered,
// multi-stop static glow and a glossy top highlight — a single cohesive piece
// instead of two separately-styled elements stacked on each other.
@Composable
private fun SpaceBottomBar(
    hapticEnabled:      Boolean = true,   // FIX #1: لتمرير الإعداد إلى AddFab
    activeSessionCount: Int = 0,
    onSettingsClick:    () -> Unit,
    onAddClick:         () -> Unit,
    onAddLongClick:     () -> Unit,
    onImportClick:      () -> Unit,
    onSessionsClick:    () -> Unit,
) {
    val glass     = NebulaSurface
    val accent    = PulsarCyan
    val secondary = QuantumBlue

    val pillHeight   = 68.dp
    val fabSize      = 62.dp
    val fabRaise     = 24.dp                  // how much of the FAB pokes above the dock
    val notchRadius  = fabSize / 2 + 7.dp      // slightly larger than the FAB → visible moat ring
    val cornerRadius = 30.dp

    val dockShape = remember(cornerRadius, notchRadius) {
        NotchedDockShape(cornerRadius = cornerRadius, notchRadius = notchRadius)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .height(pillHeight + fabRaise)
    ) {
        // ── Floating glass dock with a notch cut for the FAB ────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(pillHeight)
                .shadow(
                    elevation    = 18.dp,
                    shape        = dockShape,
                    ambientColor = accent.copy(alpha = 0.20f),
                    spotColor    = accent.copy(alpha = 0.28f)
                )
                .clip(dockShape)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(glass.copy(alpha = 0.90f), glass.copy(alpha = 0.99f))
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(accent.copy(0.08f), accent.copy(0.34f), accent.copy(0.08f))
                    ),
                    shape = dockShape
                )
        ) {
            // ── Two mirrored, evenly-spaced groups either side of the notch.
            Row(
                modifier              = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavBarButton(
                    icon    = Icons.Outlined.Settings,
                    label   = stringResource(R.string.settings),
                    onClick = onSettingsClick,
                    tint    = CometTail
                )
                NavBarButton(
                    icon    = Icons.Outlined.FolderOpen,
                    label   = stringResource(R.string.import_rdp_label),
                    onClick = onImportClick,
                    tint    = CometTail
                )

                // Reserve the center gap for the notch + FAB.
                Spacer(Modifier.width(notchRadius * 2))

                NavBarButton(
                    icon    = Icons.Outlined.Layers,
                    label   = stringResource(R.string.sessions),
                    onClick = onSessionsClick,
                    tint    = if (activeSessionCount > 0) accent else CometTail,
                    badgeCount = activeSessionCount
                )
            }
        }

        // ── Center: Add Connection FAB — nested in the dock's notch ─────────
        AddFab(
            onClick       = onAddClick,
            onLongClick   = onAddLongClick,
            hapticEnabled = hapticEnabled,
            size          = fabSize,
            accent        = accent,
            secondary     = secondary,
            modifier      = Modifier.align(Alignment.TopCenter)
        )
    }
}

// Rounded-rect dock shape with a circular bite taken out of the top-center
// edge, produced via a boolean path difference — this is what lets the FAB
// visually nest into the bar instead of merely sitting on top of it.
private class NotchedDockShape(
    private val cornerRadius: Dp,
    private val notchRadius:  Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = with(density) { cornerRadius.toPx() }
        val notch  = with(density) { notchRadius.toPx() }
        val base = Path().apply {
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner, corner)))
        }
        val cutout = Path().apply {
            addOval(Rect(center = Offset(size.width / 2f, 0f), radius = notch))
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, base, cutout))
    }
}

@Composable
private fun NavBarButton(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    label:   String,
    onClick: () -> Unit,
    tint:    Color,
    modifier:   Modifier = Modifier,
    badgeCount: Int = 0,
) {
    val sound = LocalSoundManager.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .pressScale(onClick = { sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.3f); onClick() })
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Box {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-4).dp)
                        .size(14.dp)
                        .background(PulsarCyan, CircleShape)
                        .border(1.5.dp, NebulaSurface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = if (badgeCount > 9) "9+" else badgeCount.toString(),
                        color = DeepSpace,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        maxLines = 1
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint, // BUG-9 FIX: was tint.copy(alpha=0.7f) — double-dimming CometTail fails WCAG contrast
            maxLines = 1
        )
    }
}

// Fully redesigned Add-connection button: sits inside the dock's notch with a
// visible "moat" ring around it, a soft multi-layer bloom instead of a single
// flat glow ring, a diagonal two-tone gradient fill, and a glossy top
// highlight for a rounder, more tactile look. Still a single settling press
// spring — no idle animation, so it costs nothing while sitting still.
@Composable
private fun AddFab(
    onClick:       () -> Unit,
    onLongClick:   () -> Unit = {},
    hapticEnabled: Boolean = true,   // FIX #1
    size:          Dp = 62.dp,
    accent:        Color,
    secondary:     Color,
    modifier:      Modifier = Modifier,
) {
    val interSrc = remember { MutableInteractionSource() }
    val sound    = LocalSoundManager.current
    val haptics  = LocalHapticFeedback.current

    val pressed by interSrc.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue   = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "fab_press_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .drawBehind {
                // Layered static bloom — three soft, fading rings read as a
                // smooth diffuse halo instead of one hard-edged glow disc.
                val r = size.minDimension / 2
                drawCircle(color = accent.copy(alpha = 0.10f), radius = r + 22.dp.toPx())
                drawCircle(color = accent.copy(alpha = 0.16f), radius = r + 13.dp.toPx())
                drawCircle(color = accent.copy(alpha = 0.22f), radius = r + 6.dp.toPx())
            }
            .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = accent, spotColor = secondary)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(secondary, accent),
                    start  = Offset(0f, 0f),
                    end    = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            // Thin bright sweep-gradient rim instead of a flat single-alpha
            // border — catches the eye as a subtle ring of light.
            .border(
                width = 1.5.dp,
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.9f), secondary.copy(alpha = 0.9f), Color.White.copy(alpha = 0.5f), accent.copy(alpha = 0.9f))
                ),
                shape = CircleShape
            )
            .combinedClickable(
                interactionSource = interSrc,
                indication        = null,
                onClick           = {
                    sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.5f)
                    onClick()
                },
                onLongClick       = {
                    // FIX #1: فحص إعداد hapticFeedback قبل التنفيذ
                    if (hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.7f)
                    onLongClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Glossy highlight — a soft light streak near the top, like light
        // catching a rounded glass/metal surface. Purely cosmetic, static.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = size * 0.16f)
                .size(width = size * 0.46f, height = size * 0.20f)
                .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
        )
        Icon(
            Icons.Outlined.Add,
            contentDescription = stringResource(R.string.add_connection),
            tint     = Color.White,
            modifier = Modifier.size(size * 0.42f)
        )
    }
}

// ── One-time swipe gesture hint (replaces old per-card duplicated text) ───────
@Composable
private fun SwipeHintBanner(modifier: Modifier = Modifier) {
    Surface(
        color    = NebulaSurface.copy(alpha = 0.6f),
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.SwipeLeft, null, tint = PulsarCyan.copy(0.8f), modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.swipe_hint_combined),
                style    = MaterialTheme.typography.labelSmall,
                color    = CometTail.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Network Banner ────────────────────────────────────────────────────────────
@Composable
private fun NetworkBanner(modifier: Modifier = Modifier) {
    Surface(
        color    = SolarFlare.copy(alpha = 0.12f),
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.WifiOff, null, tint = SolarFlare, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.poor_network_warning),
                style = MaterialTheme.typography.bodySmall,
                color = SolarFlare
            )
        }
    }
}

// ── Loading indicator ─────────────────────────────────────────────────────────
@Composable
private fun SpaceLoadingIndicator() {
    val accent     = PulsarCyan
    // BUGFIX-UI-10: نفس إصلاح BUG-13 المطبَّق في StarfieldBackground — إيقاف
    // الأنيميشن اللانهائي عند انتقال التطبيق للخلفية لتوفير البطارية.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // BUGFIX: infiniteRepeatable(snap()) has a 0-duration and crashes with
    // "Animation to be infinitely repeated cannot have a 0-duration" as soon as
    // isResumed flips to false (e.g. Activity paused when Settings or a session
    // card is opened). Replaced with Animatable-driven loops that simply don't
    // (re)start while paused — no crash, and the animation truly pauses.
    val rotationAnim = remember { Animatable(0f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                rotationAnim.animateTo(360f, tween(1200, easing = LinearEasing))
                rotationAnim.snapTo(0f)
            }
        }
    }
    val rotation = rotationAnim.value

    val pulseAnim = remember { Animatable(0.6f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                pulseAnim.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
                pulseAnim.animateTo(0.6f, tween(800, easing = FastOutSlowInEasing))
            }
        }
    }
    val pulse = pulseAnim.value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(56.dp)
                .rotate(rotation)
        ) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color      = accent,
                startAngle = 0f, sweepAngle = 270f,
                useCenter  = false, style = stroke
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.initializing), // BUG-3 FIX: was hardcoded "INITIALIZING..." — not translatable
            style = MaterialTheme.typography.labelMedium,
            color = accent.copy(alpha = pulse)
        )
    }
}

// ── Empty State ───────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(
    modifier:    Modifier = Modifier,
    filter:      ProtocolFilter,
    onAddClick:  () -> Unit
) {
    val accent = PulsarCyan
    // BUGFIX-UI-10: نفس إصلاح BUG-13 — إيقاف الأنيميشن اللانهائي عند الخلفية.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // BUGFIX: same infiniteRepeatable(snap()) 0-duration crash as SpaceLoadingIndicator
    // above — fixed the same way, with Animatable loops gated on isResumed.
    val floatAnim = remember { Animatable(-10f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                floatAnim.animateTo(10f, tween(2200, easing = FastOutSlowInEasing))
                floatAnim.animateTo(-10f, tween(2200, easing = FastOutSlowInEasing))
            }
        }
    }
    val floatY = floatAnim.value

    val glowAnim = remember { Animatable(0.3f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                glowAnim.animateTo(0.7f, tween(2000, easing = FastOutSlowInEasing))
                glowAnim.animateTo(0.3f, tween(2000, easing = FastOutSlowInEasing))
            }
        }
    }
    val glowAlpha = glowAnim.value

    val (icon, title, subtitle) = when (filter) {
        ProtocolFilter.ALL -> Triple(Icons.Outlined.RocketLaunch, stringResource(R.string.no_connections), stringResource(R.string.add_first_connection))
        ProtocolFilter.RDP -> Triple(Icons.Outlined.DesktopWindows, stringResource(R.string.no_rdp_connections), stringResource(R.string.no_rdp_connections_desc))
        ProtocolFilter.VNC -> Triple(Icons.Outlined.Monitor, stringResource(R.string.no_vnc_connections), stringResource(R.string.no_vnc_connections_desc))
        ProtocolFilter.SSH -> Triple(Icons.Outlined.Terminal, stringResource(R.string.no_ssh_connections), stringResource(R.string.no_ssh_connections_desc))
    }

    Column(
        modifier              = modifier,
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .drawBehind {
                    drawCircle(accent.copy(alpha = glowAlpha * 0.15f), radius = size.minDimension / 2 * 1.6f)
                    drawCircle(accent.copy(alpha = glowAlpha * 0.08f), radius = size.minDimension / 2 * 2.2f)
                }
                .offset(y = floatY.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent.copy(alpha = 0.7f), modifier = Modifier.size(80.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = StarDust, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CometTail,
            modifier = Modifier.padding(horizontal = 48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))
        SpaceButton(
            text     = stringResource(R.string.add_connection),
            onClick  = onAddClick,
            modifier = Modifier.width(220.dp)
        )
    }
}

// ── UX-04: Search Bar ─────────────────────────────────────────────────────────
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent   = PulsarCyan
    val surface  = NebulaSurface
    var focused  by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue   = if (focused) accent else HorizonGray.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label         = "search_border"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(surface, RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint     = if (focused) accent else CometTail,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value         = query,
            onValueChange = onQueryChange,
            modifier      = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused },
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodyMedium.copy(color = StarDust),
            cursorBrush   = SolidColor(accent),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CometTail.copy(alpha = 0.5f)
                        )
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_clear_search),
                tint     = CometTail,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onQueryChange("") }
                    )
            )
        }
    }
}

// ── UX-03: Reorderable Profile Card wrapper — full drag & drop ────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableProfileCard(
    profile:          RdpProfile,
    isDragTarget:     Boolean,
    isDropTarget:     Boolean,
    dragOffsetY:      Float,
    onConnect:        () -> Unit,
    onEdit:           () -> Unit,
    onDelete:         () -> Unit,
    onWakeOnLan:      (() -> Unit)? = null,
    onHeightMeasured: (Float) -> Unit,
    onDragStart:      () -> Unit,
    onDrag:           (Float) -> Unit,
    onDragEnd:        () -> Unit,
    hapticEnabled:    Boolean = true,   // FIX #1: تمرير إعداد hapticFeedback للـ RdpProfileCard
    actionPending:    Boolean = false,  // BUGFIX-UI-1: هل هناك Dialog حذف/تعديل مفتوح لهذا البروفايل؟
) {
    // BUGFIX-UI-9: نتتبع هنا ما إذا كانت قائمة الخيارات (MoreVert) مفتوحة لهذا
    // الكرت، لتعطيل كاشف السحب الطويل أثناء تفاعل المستخدم مع القائمة.
    var isMenuOpen by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        targetValue   = if (isDragTarget) 16.dp else 0.dp,
        animationSpec = tween(150),
        label         = "card_elev"
    )
    val scale by animateFloatAsState(
        targetValue   = if (isDragTarget) 1.04f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "card_scale"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (isDragTarget) 0.82f else 1f,
        animationSpec = tween(150),
        label         = "card_alpha"
    )

    // Drop target highlight colour
    val dropHighlight by animateFloatAsState(
        targetValue   = if (isDropTarget) 1f else 0f,
        animationSpec = tween(120),
        label         = "drop_highlight"
    )

    Box(
        modifier = Modifier
            .onGloballyPositioned { coords -> onHeightMeasured(coords.size.height.toFloat()) }
            .zIndex(if (isDragTarget) 1f else 0f)
            .graphicsLayer {
                scaleX       = scale
                scaleY       = scale
                this.alpha   = alpha
                shadowElevation = elevation.toPx()
                translationY = if (isDragTarget) dragOffsetY else 0f
            }
            // Drop-target accent border
            .then(
                if (isDropTarget)
                    Modifier.border(
                        width = 2.dp,
                        color = CometTail.copy(alpha = dropHighlight),
                        shape = RoundedCornerShape(16.dp)
                    )
                else Modifier
            )
            // Long-press initiates drag; subsequent drag events move the card.
            // BUGFIX-UI-9: مُعطّل بالكامل أثناء فتح قائمة MoreVert (isMenuOpen)
            // بدل ترك كاشف السحب الطويل نشطاً فوق منطقة قائمة مفتوحة.
            .then(
                if (!isMenuOpen)
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag      = { _, dragAmount -> onDrag(dragAmount.y) },
                            onDragEnd   = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        )
                    }
                else Modifier
            )
    ) {
        RdpProfileCard(
            profile        = profile,
            onConnect      = onConnect,
            onEdit         = onEdit,
            onDelete       = onDelete,
            onWakeOnLan    = onWakeOnLan,
            hapticEnabled  = hapticEnabled,   // FIX #1
            actionPending  = actionPending,   // BUGFIX-UI-1
            onMenuExpandedChange = { isMenuOpen = it },  // BUGFIX-UI-9
        )
        // UI FIX: removed the standalone DragHandle "≡" mark that sat on top of
        // the card — it was a redundant visual mark since long-pressing anywhere
        // on the card already starts the reorder drag (see pointerInput above).
    }
}

// ── UX-08: Quick Connect Dialog ───────────────────────────────────────────────
@Composable
private fun QuickConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int, username: String, password: String) -> Unit,
) {
    var hostPort      by remember { mutableStateOf("") }
    var username      by remember { mutableStateOf("") }
    var password      by remember { mutableStateOf("") }
    var showPass      by remember { mutableStateOf(false) }
    // FIX-VALID: track whether Connect was attempted so we only show
    // validation errors after the first press (not on first render).
    var connectTried  by remember { mutableStateOf(false) }
    val accent        = PulsarCyan

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.FlashOn, null, tint = accent, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.quick_connect_title), color = StarDust,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.quick_connect_dialog_desc),
                    color = CometTail, style = MaterialTheme.typography.bodySmall)

                // Host:Port field
                OutlinedTextField(
                    value         = hostPort,
                    onValueChange = { hostPort = it },
                    label         = { Text(stringResource(R.string.field_host_port), color = CometTail) },
                    placeholder   = { Text(stringResource(R.string.quick_connect_placeholder), color = CometTail.copy(alpha = 0.4f)) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accent,
                        unfocusedBorderColor = HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // FIX-VALID: show error border + supporting text when username is blank
                // after the user has pressed Connect at least once.
                val usernameIsEmpty = connectTried && username.isBlank()
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text(stringResource(R.string.username), color = if (usernameIsEmpty) SolarFlare else CometTail) },
                    isError       = usernameIsEmpty,
                    supportingText = if (usernameIsEmpty) ({
                        Text(stringResource(R.string.error_username_required), color = SolarFlare,
                            style = MaterialTheme.typography.labelSmall)
                    }) else null,
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = if (usernameIsEmpty) SolarFlare else accent,
                        unfocusedBorderColor = if (usernameIsEmpty) SolarFlare else HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                        errorBorderColor     = SolarFlare,
                        errorLabelColor      = SolarFlare,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = { Text(stringResource(R.string.password), color = CometTail) },
                    singleLine    = true,
                    visualTransformation = if (showPass)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null, tint = CometTail,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accent,
                        unfocusedBorderColor = HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // FIX B4: دعم عناوين IPv6 في QuickConnect.
            // الكود القديم كان يستخدم split(":") مما يكسر العناوين مثل [2001:db8::1]:3389
            // الآن نستخدم نفس منطق RdpFileParser لمعالجة الحالات الثلاث:
            //   1. [IPv6]:port   مثل "[::1]:3389"
            //   2. host:port     مثل "192.168.1.1:3389"
            //   3. host فقط     مثل "myserver.local"
            val raw = hostPort.trim()
            val host: String
            val port: Int
            when {
                raw.startsWith("[") -> {
                    // IPv6 بصيغة أقواس: "[2001:db8::1]:3390" أو "[::1]"
                    val closing = raw.indexOf(']')
                    if (closing >= 0) {
                        host = raw.substring(0, closing + 1)
                        port = raw.getOrNull(closing + 1)
                            ?.takeIf { it == ':' }
                            ?.let { raw.substring(closing + 2).toIntOrNull() }
                            ?: 3389
                    } else {
                        host = raw
                        port = 3389
                    }
                }
                raw.contains(":") && !raw.startsWith("[") -> {
                    // IPv4 أو اسم مضيف مع port: "192.168.1.1:3389"
                    val lastColon = raw.lastIndexOf(':')
                    val possiblePort = raw.substring(lastColon + 1).toIntOrNull()
                    if (possiblePort != null) {
                        host = raw.substring(0, lastColon)
                        port = possiblePort
                    } else {
                        host = raw
                        port = 3389
                    }
                }
                else -> {
                    host = raw
                    port = 3389
                }
            }
            // FIX-VALID: valid only when both host AND username are non-blank.
            val valid = host.isNotBlank() && username.isNotBlank()
            SpaceButton(
                text     = stringResource(R.string.connect),
                onClick  = {
                    connectTried = true   // FIX-VALID: reveal validation errors after first press
                    if (valid) onConnect(host, port, username, password)
                },
                modifier = Modifier.fillMaxWidth(),
                variant  = if (valid) ButtonVariant.PRIMARY else ButtonVariant.GHOST
            )
        },
        dismissButton = {
            SpaceButton(stringResource(R.string.cancel), onDismiss, variant = ButtonVariant.GHOST, modifier = Modifier.fillMaxWidth())
        }
    )
}
