package com.suteny0r.mangledbabyducks.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suteny0r.mangledbabyducks.AppContainer
import com.suteny0r.mangledbabyducks.R
import com.suteny0r.mangledbabyducks.RememberedRadio
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MapNode
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.PositionEntity
import com.suteny0r.mangledbabyducks.db.RoutePoint
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.knownRadios
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.ThreadTarget
import com.suteny0r.mangledbabyducks.ui.absoluteTime
import com.suteny0r.mangledbabyducks.ui.relativeTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.suteny0r.mangledbabyducks.ui.bearingDegrees
import com.suteny0r.mangledbabyducks.ui.nodeOrder
import com.suteny0r.mangledbabyducks.ui.haversineMeters

/*
 * Head-unit screens for Android Auto. These are template descriptions, not views: the
 * host draws them, and the driver-distraction rules decide what each template may hold
 * (row counts come from ConstraintManager, text is truncated by the host, free typing is
 * not offered while driving). Each screen mirrors one phone tab, reads the same Room
 * flows the phone ViewModels read, and calls invalidate() when they change.
 *
 * Template-quota gotcha: the host allows about five template changes per task before
 * it forces the app back to the root; a refresh (same template type, same row titles)
 * is free. Row titles are therefore kept stable (node names, sender names) and the
 * volatile parts (last heard, distance, battery) live in the secondary text lines.
 */

/** Sends and reconnects outlive the screen that started them (a pop cancels lifecycleScope). */
private val carWorkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Row caps that keep a template well under the binder transaction ceiling (see contentLimit). */
private const val MAP_ROW_CAP = 24
private const val LIST_ROW_CAP = 50

/** Temporary: see MeshCarScreen.trace. `adb logcat -s CarTrace:D`. */
private const val TRACE_TAG = "CarTrace"

private val QUICK_REPLIES = listOf(
    "OK",
    "Yes",
    "No",
    "On my way",
    "Running late",
    "Arrived",
    "Where are you?",
    "Call me when you can",
    "Copy that",
    "Heading home",
)

/** Common plumbing: container access, flow observation, host content limits, icons. */
abstract class MeshCarScreen(carContext: CarContext) : Screen(carContext) {

    protected val container: AppContainer get() = carContext.container

    /**
     * Temporary instrumentation for the head unit's slow first paint: a screen renders its
     * first template before any flow has emitted, so where the delay sits (lifecycle,
     * first Room emission, or the host's template refresh) is not otherwise observable.
     * Filter with `adb logcat -s CarTrace:D`.
     */
    private val traceName: String = javaClass.simpleName
    private val traceStart: Long = SystemClock.elapsedRealtime()
    protected fun trace(message: String) {
        Log.d(TRACE_TAG, "$traceName +${SystemClock.elapsedRealtime() - traceStart}ms $message")
    }

    init {
        trace("constructed")
    }

    /**
     * Collect while the screen is at least started; every distinct value redraws the
     * template.
     *
     * A StateFlow is also read synchronously here, at construction, so the screen's fields
     * are already populated when the host asks for the first template. Without that seed a
     * freshly pushed screen always renders its empty state first and only fills in after
     * the first emission plus a host template round trip, which is what made the car's node
     * list sit on "No nodes yet" while the phone showed the same list instantly. No
     * invalidate() for the seed: there is no template yet to invalidate.
     */
    protected fun <T> observe(flow: Flow<T>, onValue: (T) -> Unit) {
        val label = "observe#${observeSeq++}"
        if (flow is StateFlow<T>) {
            onValue(flow.value)
            trace("$label seeded from StateFlow")
        } else {
            // A cold flow cannot be read synchronously, so this screen genuinely does not
            // know its contents yet. Counting it keeps [isLoading] honest, so a template
            // can say so instead of asserting the list is empty. Claiming "No messages
            // yet" with a radio connected and messages in the store is the same fault the
            // node list had; the answer there was a hot flow, and that is still preferred
            // where the query is not parameterised.
            pendingFirstEmissions++
        }
        lifecycleScope.launch {
            trace("$label launched")
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                trace("$label STARTED, collecting")
                var emissions = 0
                flow.distinctUntilChanged().collect { value ->
                    emissions++
                    if (emissions == 1 && flow !is StateFlow<T>) pendingFirstEmissions--
                    if (emissions <= 2) trace("$label emit #$emissions")
                    onValue(value)
                    invalidate()
                    if (emissions <= 2) trace("$label invalidate() returned")
                }
            }
        }
    }

    private var observeSeq = 0

    /**
     * True until every cold flow this screen observes has emitted once. Only meaningful
     * for the empty-state caption: an empty list and an unknown list look identical in a
     * template, and telling the driver the wrong one is worse than saying nothing yet.
     */
    private var pendingFirstEmissions = 0
    protected val isLoading: Boolean get() = pendingFirstEmissions > 0

    /**
     * How many rows this template may hold: the host's ConstraintManager limit, capped by
     * [cap]. The cap is load-bearing, not cosmetic: Android Auto 17.7 reports a limit of
     * 1000 for scrolling lists, and a template is sent over one binder transaction with a
     * hard ceiling near 1 MB. With ~9 KB per place row, 197 positioned nodes produced a
     * 1.8 MB parcel, a TransactionTooLargeException, and an "isn't responding" card.
     */
    protected fun contentLimit(type: Int, cap: Int): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(type)
    }.getOrDefault(cap).coerceIn(1, cap)

    protected fun icon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    /**
     * The link status as a row, the way the Map list has always carried it. Every list
     * screen shows it, because a driver reading a stale node list has no other way to tell
     * that the radio dropped: the rows themselves are Room contents and look exactly the
     * same connected or not.
     *
     * The title stays "Radio" and the state goes in the secondary line. That is the rule
     * the whole car UI follows: a row whose title changes counts as a template step against
     * the host's quota, while the same titles with new text is a free refresh.
     */
    protected fun radioRow(state: RadioState, deviceName: String?): Row =
        Row.Builder()
            .setTitle("Radio")
            .addText(state.describe(deviceName))
            .setImage(icon(R.drawable.ic_car_radio))
            // PlaceListMapTemplate rejects a non-browsable row without a distance span.
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(CarRadioScreen(carContext)) }
            .build()

    protected fun toast(text: String) {
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()
    }

    protected fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The connected radio's position once it has one, else the phone's fix. Same rule as
     * NodesViewModel.myLocation: radios waiting for a GPS lock fall back to the phone and
     * take over when their first position lands.
     */
    protected fun carLocation(myNum: Long, positions: List<MapNode>): Pair<Double, Double>? {
        positions.firstOrNull { it.nodeNum == myNum }?.let { return it.latitude to it.longitude }
        return container.locationSharer.lastFix.value?.let { it.latitudeI / 1e7 to it.longitudeI / 1e7 }
    }
}

/** Display name for a node the way the phone lists show it. */
private fun displayName(user: UserEntity?, num: Long): String =
    user?.longName?.takeIf { it.isNotBlank() }
        ?: user?.shortName?.takeIf { it.isNotBlank() }
        ?: nodeNumString(num)

private fun displayName(node: MapNode): String =
    node.longName?.takeIf { it.isNotBlank() }
        ?: node.shortName?.takeIf { it.isNotBlank() }
        ?: nodeNumString(node.nodeNum)

private fun displayName(point: RoutePoint): String =
    point.longName?.takeIf { it.isNotBlank() }
        ?: point.shortName?.takeIf { it.isNotBlank() }
        ?: nodeNumString(point.nodeNum)

/** Channel label with the phone UI's fallback for unnamed channels. */
private fun channelName(channel: ChannelEntity?, index: Int): String =
    channel?.name?.takeIf { it.isNotBlank() }
        ?: if (index == 0) "Primary Channel" else "Channel $index"

/**
 * Marker labels are capped at three characters by the host; emoji do not render there.
 * An emoji-only short name falls back to the long name's initials.
 */
private fun markerLabel(shortName: String?, longName: String? = null): String {
    val letters = shortName.orEmpty().filter { it.isLetterOrDigit() }.take(3)
    if (letters.isNotEmpty()) return letters
    val initials = longName.orEmpty().split(' ').mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(3)
    return initials.joinToString("").ifEmpty { "?" }
}

private val imperial: Boolean
    get() = Locale.getDefault().country in setOf("US", "LR", "MM")

/** A host-formatted distance span, in the locale's customary units. */
private fun distanceSpan(meters: Double): CharSequence {
    val distance = if (imperial) {
        val miles = meters / 1609.344
        if (miles < 0.2) Distance.create(meters * 3.28084, Distance.UNIT_FEET)
        else Distance.create(miles, Distance.UNIT_MILES)
    } else {
        if (meters < 1000) Distance.create(meters, Distance.UNIT_METERS)
        else Distance.create(meters / 1000, Distance.UNIT_KILOMETERS)
    }
    return SpannableString(" ").apply {
        setSpan(DistanceSpan.create(distance), 0, 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
}

internal fun RadioState.describe(deviceName: String?): String {
    val radio = deviceName ?: "radio"
    return when (this) {
        RadioState.Idle -> "Not connected"
        is RadioState.Searching -> "Searching for $radio ($attempt/$of)"
        is RadioState.Connecting -> "Connecting to $radio ($attempt/$of)"
        RadioState.Communicating -> "Retrieving configuration"
        is RadioState.RetrievingDatabase -> "Loading nodes ($nodeCount)"
        RadioState.Subscribed -> "Connected to $radio"
        is RadioState.Reconnecting -> "Reconnecting to $radio (attempt $attempt)"
        is RadioState.Failed -> reason
    }
}

/**
 * Home menu: one row per phone tab. The first car test showed that action-strip icons
 * and a single Radio row inside the map list were not discoverable on a real head unit,
 * so navigation is explicit, titled rows with chevrons; the map is one tap away.
 */
class CarHomeScreen(carContext: CarContext) : MeshCarScreen(carContext) {

    private var state: RadioState = RadioState.Idle
    private var deviceName: String? = null
    private var unread = 0
    private var nodeCount = 0
    private var positioned = 0

    init {
        observe(container.radioManager.state) { state = it }
        observe(container.radioManager.deviceName) { deviceName = it }
        observe(container.database.messageDao().unreadCount()) { unread = it }
        // Counted off the shared hot lists rather than their own cold queries, so these
        // two seed synchronously like the rest (a .map would drop the StateFlow type).
        observe(container.nodesWithUsers) { nodeCount = it.size }
        observe(container.mapNodes) { positioned = it.size }
    }

    private fun menuRow(title: String, text: String, iconRes: Int, open: () -> Screen): Row =
        Row.Builder()
            .setTitle(title)
            .addText(text)
            .setImage(icon(iconRes))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(open()) }
            .build()

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                menuRow("Map", "$positioned nodes with a position", R.drawable.ic_car_map) {
                    CarMapScreen(carContext)
                }
            )
            .addItem(
                menuRow(
                    "Messages",
                    if (unread > 0) "$unread unread" else "Channels and direct messages",
                    R.drawable.ic_car_message,
                ) { CarMessagesScreen(carContext) }
            )
            .addItem(
                menuRow("Nodes", "$nodeCount nodes heard", R.drawable.ic_car_nodes) {
                    CarNodesScreen(carContext)
                }
            )
            .addItem(
                menuRow("Radio", state.describe(deviceName), R.drawable.ic_car_radio) {
                    CarRadioScreen(carContext)
                }
            )
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}

/**
 * Every node with a known position as a marker on the host's map, plus a Radio row
 * that says what the link is doing. The action strip also reaches Messages and Nodes.
 */
class CarMapScreen(carContext: CarContext) : MeshCarScreen(carContext) {

    private var nodes: List<MapNode> = emptyList()
    private var state: RadioState = RadioState.Idle
    private var deviceName: String? = null
    private var myNum = 0L

    init {
        observe(container.mapNodes) { list ->
            nodes = list.sortedByDescending { it.time }
        }
        observe(container.radioManager.state) { state = it }
        observe(container.radioManager.deviceName) { deviceName = it }
        observe(container.radioManager.myNodeNum) { myNum = it }
    }

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST, MAP_ROW_CAP)
        val here = carLocation(myNum, nodes)
        val list = ItemList.Builder().setNoItemsMessage("No nodes with a position yet")

        list.addItem(radioRow(state, deviceName))
        nodes.filter { it.nodeNum != myNum }.take(limit - 1).forEach { node ->
            val text = SpannableStringBuilder("heard ${relativeTime(node.time)}")
            here?.let { (lat, lon) ->
                text.append(" · ")
                text.append(distanceSpan(haversineMeters(lat, lon, node.latitude, node.longitude)))
            }
            val place = Place.Builder(CarLocation.create(node.latitude, node.longitude))
                .setMarker(
                    PlaceMarker.Builder()
                        .setLabel(markerLabel(node.shortName, node.longName))
                        .setColor(CarColor.BLUE)
                        .build()
                )
                .build()
            list.addItem(
                Row.Builder()
                    .setTitle(displayName(node))
                    .addText(text)
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    // Browsable: rows without a car fix have no distance span, which the
                    // host otherwise requires (validateAllNonBrowsableRowsHaveDistance).
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarNodeDetailScreen(carContext, node.nodeNum)) }
                    .build()
            )
        }

        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_message))
                    .setOnClickListener { screenManager.push(CarMessagesScreen(carContext)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_nodes))
                    .setOnClickListener { screenManager.push(CarNodesScreen(carContext)) }
                    .build()
            )
            .build()

        @Suppress("DEPRECATION")
        val builder = PlaceListMapTemplate.Builder()
            .setTitle("Map")
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .setActionStrip(strip)
            .setCurrentLocationEnabled(hasLocationPermission())
        here?.let { (lat, lon) ->
            builder.setAnchor(Place.Builder(CarLocation.create(lat, lon)).build())
        }
        return builder.build()
    }
}

/**
 * The Connect tab for the car: link status, a Disconnect action while a link is up, and
 * every saved radio as a row that connects on tap. Scanning for new radios stays on the
 * phone; the car can only pick radios the phone has already paired with.
 */
class CarRadioScreen(carContext: CarContext) : MeshCarScreen(carContext) {

    private var state: RadioState = RadioState.Idle
    private var deviceName: String? = null
    private var saved: List<RememberedRadio> = emptyList()

    init {
        observe(container.radioManager.state) { state = it }
        observe(container.radioManager.deviceName) { deviceName = it }
        observe(container.prefs.data.map { it.knownRadios() }) { saved = it }
    }

    private fun isLive(radio: RememberedRadio): Boolean =
        state is RadioState.Subscribed && deviceName == radio.label

    @Suppress("DEPRECATION") // setTitle/setHeaderAction/setActionStrip predate Header (API 7).
    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        val template = ListTemplate.Builder()
            .setTitle("Radio")
            .setHeaderAction(Action.BACK)

        val status = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Status")
                    .addText(state.describe(deviceName))
                    .setImage(icon(R.drawable.ic_car_radio))
                    .build()
            )
            .build()
        template.addSectionedList(SectionedItemList.create(status, "Status"))

        if (saved.isEmpty()) {
            template.addSectionedList(
                SectionedItemList.create(
                    ItemList.Builder()
                        .addItem(
                            Row.Builder()
                                .setTitle("No saved radios")
                                .addText("Connect once from the phone; it will appear here")
                                .build()
                        )
                        .build(),
                    "Saved radios",
                )
            )
        } else {
            val radios = ItemList.Builder()
            saved.take(limit - 1).forEach { radio ->
                val live = isLive(radio)
                radios.addItem(
                    Row.Builder()
                        .setTitle(radio.label)
                        .addText(
                            when {
                                live -> "Connected · tap to disconnect"
                                radio.type == "tcp" -> "Wi-Fi ${radio.address} · tap to connect"
                                else -> "Bluetooth · tap to connect"
                            }
                        )
                        .setOnClickListener { if (live) disconnect() else connect(radio) }
                        .build()
                )
            }
            template.addSectionedList(SectionedItemList.create(radios.build(), "Saved radios"))
        }

        if (container.radioManager.isConnected || container.radioManager.isAttempting) {
            template.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Disconnect")
                            .setOnClickListener { disconnect() }
                            .build()
                    )
                    .build()
            )
        }
        return template.build()
    }

    private fun connect(target: RememberedRadio) {
        val radio = container.radioManager
        if (radio.isAttempting) {
            toast("Already connecting")
            return
        }
        if (target.type == "ble" && !container.hasBlePermission(carContext)) {
            toast("Grant Bluetooth permission on the phone first")
            return
        }
        toast("Connecting to ${target.label}…")
        // connectKnown supersedes any live session, so switching radios is one tap.
        carWorkScope.launch { container.connectKnown(carContext, target) }
    }

    /** Same meaning as the phone's Disconnect: the app stops auto-connecting on launch too. */
    private fun disconnect() {
        toast("Disconnecting…")
        carWorkScope.launch { container.disconnectRadio(carContext) }
    }
}

/** Nodes list in the phone's order: connected node, favorites, then most recently heard. */
class CarNodesScreen(carContext: CarContext) : MeshCarScreen(carContext) {

    private var nodes: List<NodeWithUser> = emptyList()
    private var positions: Map<Long, MapNode> = emptyMap()
    private var myNum = 0L
    /** Set on the phone's node list; there is no car-side toggle for it. */
    private var pinFavorites = true
    private var state: RadioState = RadioState.Idle
    private var deviceName: String? = null

    init {
        observe(container.nodesWithUsers) { nodes = it }
        observe(container.mapNodes) { list -> positions = list.associateBy { it.nodeNum } }
        observe(container.radioManager.myNodeNum) { myNum = it }
        observe(container.pinFavorites) { pinFavorites = it }
        // Without these the screen never hears that the link changed: node rows come from
        // Room and read the same whether the radio is attached or gone.
        observe(container.radioManager.state) { state = it }
        observe(container.radioManager.deviceName) { deviceName = it }
    }

    override fun onGetTemplate(): Template {
        val limitStart = SystemClock.elapsedRealtime()
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        trace("onGetTemplate: nodes=${nodes.size} positions=${positions.size} myNum=$myNum " +
            "contentLimit took ${SystemClock.elapsedRealtime() - limitStart}ms")
        val here = carLocation(myNum, positions.values.toList())
        val list = ItemList.Builder()
        // The status row costs one row of the host's budget, as it does on the Map list.
        // It also means the list is never empty, so the host's no-items message can no
        // longer appear and the empty case has to say so in a row of its own.
        list.addItem(radioRow(state, deviceName))
        val visible = nodes.filter { !it.node.ignored }
        if (visible.isEmpty()) {
            list.addItem(Row.Builder().setTitle("No nodes yet").build())
        }
        visible.asSequence()
            .sortedWith(nodeOrder(myNum, pinFavorites))
            .take(maxOf(limit - 1, 1))
            .forEach { entry ->
                val num = entry.node.num
                val detail = SpannableStringBuilder(
                    buildList {
                        entry.user?.shortName?.takeIf { it.isNotBlank() }?.let { add(it) }
                        if (num == myNum) add("connected") else entry.node.lastHeard?.let { add(relativeTime(it)) }
                        if (entry.node.hopsAway > 0) add("${entry.node.hopsAway} hops")
                    }.joinToString(" · ").ifEmpty { nodeNumString(num) }
                )
                // NodeListItem's distance and bearing, in the car's units via the host span.
                val there = positions[num]
                if (here != null && there != null && num != myNum) {
                    detail.append(" · ")
                    detail.append(distanceSpan(haversineMeters(here.first, here.second, there.latitude, there.longitude)))
                    detail.append(" · ${bearingDegrees(here.first, here.second, there.latitude, there.longitude).toInt()}°")
                }
                list.addItem(
                    Row.Builder()
                        .setTitle(displayName(entry.user, num))
                        .addText(detail)
                        .setBrowsable(true)
                        .setOnClickListener { screenManager.push(CarNodeDetailScreen(carContext, num)) }
                        .build()
                )
            }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("Nodes")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** One node: the few facts a driver can use, plus Message and Traceroute. */
class CarNodeDetailScreen(carContext: CarContext, private val num: Long) : MeshCarScreen(carContext) {

    private var entry: NodeWithUser? = null
    private var position: PositionEntity? = null
    private var metrics: TelemetryEntity? = null
    private var mapNodes: List<MapNode> = emptyList()
    private var myNum = 0L

    init {
        observe(container.database.nodeDao().nodeWithUserFlow(num)) { entry = it }
        observe(container.database.positionDao().latestFlow(num)) { position = it }
        observe(container.database.telemetryDao().latestDeviceMetrics(num)) { metrics = it }
        observe(container.mapNodes) { mapNodes = it }
        observe(container.radioManager.myNodeNum) { myNum = it }
    }

    /**
     * Details on top, then the phone's node-detail actions (NodeDetail.swift's list) as rows.
     * A pane allows two buttons, which is why this is a sectioned list instead. Favorite,
     * mute and ignore are toggle rows: their titles never change, so flipping one is a
     * refresh rather than a template step against the host's quota.
     */
    @Suppress("DEPRECATION") // setTitle/setHeaderAction predate Header (API 7).
    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        val node = entry?.node
        val user = entry?.user
        val details = mutableListOf<Row>()
        details += Row.Builder()
            .setTitle("Node")
            .addText(listOfNotNull(user?.shortName, nodeNumString(num)).joinToString(" · "))
            .build()
        node?.lastHeard?.let {
            details += Row.Builder().setTitle("Last heard").addText(relativeTime(it)).build()
        }
        if (node != null && (node.snr != 0f || node.rssi != 0 || node.hopsAway >= 0)) {
            val signal = buildList {
                if (node.hopsAway == 0) add("direct") else if (node.hopsAway > 0) add("${node.hopsAway} hops")
                if (node.snr != 0f) add("SNR %.1f dB".format(node.snr))
                if (node.rssi != 0) add("RSSI ${node.rssi}")
            }.joinToString(" · ")
            if (signal.isNotEmpty()) details += Row.Builder().setTitle("Signal").addText(signal).build()
        }
        metrics?.batteryLevel?.let { level ->
            // Firmware reports >100 for a node running on external power.
            details += Row.Builder().setTitle("Battery").addText(if (level > 100) "Plugged in" else "$level%").build()
        }
        position?.let { pos ->
            val text = SpannableStringBuilder("%.5f, %.5f".format(pos.latitude, pos.longitude))
            carLocation(myNum, mapNodes)?.let { (lat, lon) ->
                text.append(" · ")
                text.append(distanceSpan(haversineMeters(lat, lon, pos.latitude, pos.longitude)))
            }
            details += Row.Builder().setTitle("Position").addText(text).build()
        }

        val isSelf = num == myNum
        val name = displayName(user, num)
        val actions = mutableListOf<Row>()
        if (!isSelf && user?.unmessagable != true) {
            actions += Row.Builder()
                .setTitle("Message")
                .setImage(icon(R.drawable.ic_car_message), Row.IMAGE_TYPE_ICON)
                .setOnClickListener {
                    screenManager.push(CarQuickReplyScreen(carContext, ThreadTarget.Direct(num, name)))
                }
                .build()
        }
        if (!isSelf) {
            actions += actionRow("Trace Route") { container.radioManager.sendTraceroute(num) }
            // TraceRouteLog: the only place a traceroute result is readable, so it sits next
            // to the send. Browsable: it opens a screen rather than firing a request.
            actions += Row.Builder()
                .setTitle("Traceroute Log")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CarTraceroutesScreen(carContext, num, name)) }
                .build()
            actions += actionRow("Exchange Positions") {
                val fix = container.locationSharer.lastFix.value ?: return@actionRow false
                container.radioManager.sendDestPosition(
                    toNum = num,
                    latitudeI = fix.latitudeI,
                    longitudeI = fix.longitudeI,
                    altitude = fix.altitude,
                    channel = 0,
                )
            }
            actions += actionRow("Exchange User Info") { container.radioManager.exchangeUserInfo(num) }
            actions += actionRow("Request Local Stats") {
                container.radioManager.sendLocalStatsRequest(num, user?.publicKey)
            }
        }
        actions += toggleRow("Favorite", node?.favorite == true) { on ->
            carWorkScope.launch {
                container.radioManager.setFavorite(num, on)
                container.database.nodeDao().setFavorite(num, on)
            }
        }
        actions += toggleRow("Mute notifications", user?.mute == true) { on ->
            carWorkScope.launch { container.database.userDao().setMute(num, on) }
        }
        if (!isSelf) {
            actions += toggleRow("Ignore node", node?.ignored == true) { on ->
                carWorkScope.launch {
                    container.radioManager.setIgnored(num, on)
                    container.database.nodeDao().setIgnored(num, on)
                }
            }
        }

        val detailList = ItemList.Builder()
        details.take(limit).forEach { detailList.addItem(it) }
        val actionList = ItemList.Builder()
        actions.take((limit - details.size).coerceAtLeast(0)).forEach { actionList.addItem(it) }

        val template = ListTemplate.Builder()
            .setTitle(name)
            .setHeaderAction(Action.BACK)
            .addSectionedList(SectionedItemList.create(detailList.build(), "Details"))
        if (actions.isNotEmpty()) {
            template.addSectionedList(SectionedItemList.create(actionList.build(), "Actions"))
        }
        return template.build()
    }

    /** A one-shot mesh request: confirm the tap at once, report a failed send afterwards. */
    private fun actionRow(title: String, action: suspend () -> Boolean): Row =
        Row.Builder()
            .setTitle(title)
            .setOnClickListener {
                toast("$title sent")
                carWorkScope.launch {
                    val ok = runCatching { action() }.getOrDefault(false)
                    if (!ok) withContext(Dispatchers.Main) { toast("$title failed: radio not connected") }
                }
            }
            .build()

    private fun toggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): Row =
        Row.Builder()
            .setTitle(title)
            .setToggle(Toggle.Builder { on -> onChange(on) }.setChecked(checked).build())
            .build()
}

/**
 * TraceRouteLog.swift for the car: one row per traceroute sent to this node, newest first,
 * with the resolved path ("A (x dB) → B") as the phone shows it. Rows with a reply open the
 * route on the host map; pending or dead ones are plain text. Titles are the absolute time
 * so they stay stable across refreshes.
 */
class CarTraceroutesScreen(
    carContext: CarContext,
    private val num: Long,
    private val name: String,
) : MeshCarScreen(carContext) {

    private var routes: List<Pair<TracerouteEntity, String>> = emptyList()

    init {
        observe(
            container.database.tracerouteDao().forNode(num).map { list ->
                list.map { route -> route to describe(route) }
            }
        ) { routes = it }
    }

    private suspend fun describe(route: TracerouteEntity): String {
        if (!route.response) {
            // No schema for timeouts; anything unanswered after 2 minutes is dead.
            return if (System.currentTimeMillis() - route.time > 120_000) "no reply" else "pending"
        }
        return buildString {
            append("→ ")
            append(routeText(route.routeTowards, route.snrTowards))
            if (route.routeBack.isNotEmpty() || route.snrBack.isNotEmpty()) {
                append("\n← ")
                append(routeText(route.routeBack, route.snrBack))
            }
        }
    }

    /** NodeDetailScreen.routeText: "num,num" + "snr,snr" (scaled by 4) into names and dB. */
    private suspend fun routeText(routeCsv: String, snrCsv: String): String {
        val hops = routeCsv.split(",").filter { it.isNotBlank() }.map { it.toLong() }
        val snrs = snrCsv.split(",").filter { it.isNotBlank() }.map { it.toInt() / 4f }
        if (hops.isEmpty()) {
            return if (snrs.isNotEmpty()) "direct (%.1f dB)".format(snrs.last()) else "direct"
        }
        val names = hops.map { n -> displayName(container.database.userDao().get(n), n) }
        return buildString {
            names.forEachIndexed { i, hop ->
                append(hop)
                snrs.getOrNull(i)?.let { append(" (%.1f dB)".format(it)) }
                if (i < names.lastIndex) append(" → ")
            }
            if (snrs.size > names.size) append(" → dest (%.1f dB)".format(snrs.last()))
        }
    }

    @Suppress("DEPRECATION") // setTitle/setHeaderAction predate Header (API 7).
    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        val list = ItemList.Builder().setNoItemsMessage("No traceroutes yet")
        routes.take(limit).forEach { (route, text) ->
            val row = Row.Builder()
                .setTitle(absoluteTime(route.time))
                .addText(text)
            if (route.response) {
                row.setBrowsable(true)
                row.setOnClickListener { screenManager.push(CarRouteMapScreen(carContext, route)) }
            }
            list.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setTitle("Traceroutes to $name")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/**
 * One traceroute on the host map (the phone's Map tab with a selected route). The host
 * draws markers only, no lines, so the forward path is listed in hop order with numbered
 * rows: our radio, each hop, the destination. Hops without a stored position are dropped,
 * as MapViewModel.route does. Markers: green origin, red destination, blue hops.
 */
class CarRouteMapScreen(carContext: CarContext, private val route: TracerouteEntity) : MeshCarScreen(carContext) {

    private var path: List<RoutePoint> = emptyList()
    private var myNum = 0L

    init {
        val forwardCsv = route.routeTowards.split(",").mapNotNull { it.trim().toLongOrNull() }
        observe(
            container.database.myInfoDao().myInfo().flatMapLatest { myInfo ->
                val mine = myInfo?.myNodeNum ?: 0L
                myNum = mine
                val nums = (listOfNotNull(mine.takeIf { it != 0L }) + forwardCsv + route.toNum).distinct()
                container.database.positionDao().latestByNums(nums).map { points ->
                    val byNum = points.associateBy { it.nodeNum }
                    buildList {
                        if (mine != 0L) byNum[mine]?.let { add(it) }
                        forwardCsv.forEach { n -> byNum[n]?.let { add(it) } }
                        byNum[route.toNum]?.let { add(it) }
                    }.distinctBy { it.nodeNum }
                }
            }
        ) { path = it }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST, MAP_ROW_CAP)
        val list = ItemList.Builder().setNoItemsMessage("No hop on this route has a known position")
        path.take(limit).forEachIndexed { i, point ->
            val color = when (point.nodeNum) {
                myNum -> CarColor.GREEN
                route.toNum -> CarColor.RED
                else -> CarColor.BLUE
            }
            val place = Place.Builder(CarLocation.create(point.latitude, point.longitude))
                // Numbered like the rows beside the map, so hop order is readable without names.
                .setMarker(PlaceMarker.Builder().setLabel("${i + 1}").setColor(color).build())
                .build()
            val role = when (point.nodeNum) {
                myNum -> "origin"
                route.toNum -> "destination"
                else -> "hop"
            }
            list.addItem(
                Row.Builder()
                    .setTitle("${i + 1}. ${displayName(point)}")
                    .addText(role)
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarNodeDetailScreen(carContext, point.nodeNum)) }
                    .build()
            )
        }
        val builder = PlaceListMapTemplate.Builder()
            .setTitle("Traceroute " + absoluteTime(route.time))
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .setCurrentLocationEnabled(hasLocationPermission())
        path.firstOrNull()?.let { first ->
            builder.setAnchor(Place.Builder(CarLocation.create(first.latitude, first.longitude)).build())
        }
        return builder.build()
    }
}

/** Channels and direct-message contacts, the car twin of the Messages tab's thread list. */
class CarMessagesScreen(carContext: CarContext) : MeshCarScreen(carContext) {

    private var channels: List<ChannelEntity> = emptyList()
    private var contacts: List<UserEntity> = emptyList()
    private var positions: Map<Long, MapNode> = emptyMap()
    private var myNum = 0L
    private var state: RadioState = RadioState.Idle
    private var deviceName: String? = null

    init {
        // The hot copies in AppContainer, not the DAO flows. A cold flow gets no
        // synchronous seed in observe(), so this screen drew its empty state first and
        // only filled in after the first emission plus a host template round trip.
        observe(container.activeChannels) { channels = it }
        observe(container.dmContacts) { contacts = it }
        // For the distance badge on DM rows, as the node list carries.
        observe(container.mapNodes) { list -> positions = list.associateBy { it.nodeNum } }
        observe(container.radioManager.myNodeNum) { myNum = it }
        // Without these the screen never hears that the link changed. Channels and
        // contacts are Room contents and survive a disconnect, so the list sat there
        // looking exactly as it had while connected, with nothing saying otherwise.
        observe(container.radioManager.state) { state = it }
        observe(container.radioManager.deviceName) { deviceName = it }
    }

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        @Suppress("DEPRECATION")
        val template = ListTemplate.Builder()
            .setTitle("Messages")
            .setHeaderAction(Action.BACK)

        // Split the budget. The host's list limit is commonly 6, and a radio with six
        // channels left `take(limit - 6)` = take(0) for contacts, so Direct Messages
        // vanished entirely — not empty, absent, because the section is only added when it
        // has rows. Each side is guaranteed its half and may grow into whatever the other
        // does not use. Contacts are ordered lastMessage-first, so the half that survives
        // is the half worth showing.
        // One row of the budget goes to the status section, as on the Map and Nodes lists.
        val budget = maxOf(limit - 1, 1)
        val half = budget / 2
        val channelTake = minOf(channels.size, maxOf(budget - contacts.size, half))
        val contactTake = minOf(contacts.size, budget - channelTake)

        val channelRows = channels.take(channelTake).map { channel ->
            val name = channelName(channel, channel.index)
            Row.Builder()
                .setTitle(name)
                .addText(if (channel.index == 0) "Broadcast" else "Channel ${channel.index}")
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(CarThreadScreen(carContext, ThreadTarget.Channel(channel.index, name)))
                }
                .build()
        }
        // Only the DM rows get a distance: a channel is a group, so there is no one node to
        // measure to. Same span the node list and the thread view use.
        val here = carLocation(myNum, positions.values.toList())
        val contactRows = contacts.take(contactTake).map { user ->
            val name = displayName(user, user.num)
            val detail = SpannableStringBuilder(
                user.lastMessage?.let { "last message ${relativeTime(it)}" } ?: nodeNumString(user.num)
            )
            val there = positions[user.num]
            if (here != null && there != null && user.num != myNum) {
                detail.append(" · ")
                detail.append(distanceSpan(haversineMeters(here.first, here.second, there.latitude, there.longitude)))
            }
            Row.Builder()
                .setTitle(name)
                .addText(detail)
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(CarThreadScreen(carContext, ThreadTarget.Direct(user.num, name)))
                }
                .build()
        }

        // The status goes first and is always present. A ListTemplate is either one list
        // or sections, never both, so the empty case became a section too rather than the
        // host's no-items message.
        template.addSectionedList(
            SectionedItemList.create(
                ItemList.Builder().addItem(radioRow(state, deviceName)).build(),
                "Status",
            )
        )
        if (channelRows.isEmpty() && contactRows.isEmpty()) {
            template.addSectionedList(
                SectionedItemList.create(
                    ItemList.Builder()
                        .addItem(
                            Row.Builder()
                                .setTitle(if (isLoading) "Loading…" else "No channels yet")
                                .build()
                        )
                        .build(),
                    "Conversations",
                )
            )
            return template.build()
        }
        if (channelRows.isNotEmpty()) {
            template.addSectionedList(
                SectionedItemList.create(
                    ItemList.Builder().apply { channelRows.forEach(::addItem) }.build(),
                    "Channels",
                )
            )
        }
        if (contactRows.isNotEmpty()) {
            template.addSectionedList(
                SectionedItemList.create(
                    ItemList.Builder().apply { contactRows.forEach(::addItem) }.build(),
                    "Direct Messages",
                )
            )
        }
        return template.build()
    }
}

/** One conversation, newest first. Opening it marks it read; a row or the strip icon replies. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarThreadScreen(carContext: CarContext, private val target: ThreadTarget) : MeshCarScreen(carContext) {

    private var messages: List<MessageEntity> = emptyList()
    private var names: Map<Long, String> = emptyMap()
    private var positions: Map<Long, MapNode> = emptyMap()
    private var myNum = 0L

    init {
        observe(container.mapNodes) { list -> positions = list.associateBy { it.nodeNum } }
        val dao = container.database.messageDao()
        val flow = when (target) {
            is ThreadTarget.Channel -> dao.channelMessages(target.index)
            is ThreadTarget.Direct -> container.radioManager.myNodeNum.flatMapLatest { my ->
                dao.directMessages(my, target.peerNum)
            }
        }
        observe(flow) { list ->
            messages = list.sortedByDescending { it.timestamp }
            markRead()
        }
        observe(container.nodesWithUsers) { list ->
            names = list.associate { it.node.num to displayName(it.user, it.node.num) }
        }
        observe(container.radioManager.myNodeNum) { myNum = it }
    }

    private fun markRead() {
        val dao = container.database.messageDao()
        carWorkScope.launch {
            when (target) {
                is ThreadTarget.Channel -> dao.markChannelRead(target.index)
                is ThreadTarget.Direct -> dao.markDmRead(container.radioManager.myNodeNum.value, target.peerNum)
            }
            container.messageNotifier.dismiss(target)
        }
    }

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        // This thread's query is parameterised, so it cannot be one of the hot flows in
        // AppContainer and the first template is drawn before it has emitted.
        val list = ItemList.Builder()
            .setNoItemsMessage(if (isLoading) "Loading messages…" else "No messages yet")
        val here = carLocation(myNum, positions.values.toList())
        messages.take(limit).forEach { message ->
            val mine = message.fromNum == myNum
            val sender = if (mine) "Me" else names[message.fromNum] ?: nodeNumString(message.fromNum)
            val status = SpannableStringBuilder(relativeTime(message.timestamp))
            if (mine) status.append(if (message.receivedAck) " · delivered" else " · sending")
            // Distance to the sender, as the phone thread shows under the avatar.
            val there = positions[message.fromNum]
            if (!mine && here != null && there != null) {
                status.append(" · ")
                status.append(distanceSpan(haversineMeters(here.first, here.second, there.latitude, there.longitude)))
            }
            list.addItem(
                Row.Builder()
                    .setTitle(sender)
                    .addText(message.payload?.takeIf { it.isNotBlank() } ?: "(empty)")
                    .addText(status)
                    .setOnClickListener {
                        screenManager.push(CarQuickReplyScreen(carContext, target, replyId = message.messageId))
                    }
                    .build()
            )
        }
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_reply))
                    .setOnClickListener { screenManager.push(CarQuickReplyScreen(carContext, target)) }
                    .build()
            )
            .build()
        val title = when (target) {
            is ThreadTarget.Channel -> target.name
            is ThreadTarget.Direct -> target.name
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip)
            .setSingleList(list.build())
            .build()
    }
}

/**
 * Canned replies: the head unit offers no keyboard while driving, so this is how a
 * message gets sent from the car UI. (Free-form voice replies go through the
 * MessagingStyle notification instead; see MessageNotifier.)
 */
class CarQuickReplyScreen(
    carContext: CarContext,
    private val target: ThreadTarget,
    private val replyId: Long = 0,
) : MeshCarScreen(carContext) {

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        val list = ItemList.Builder()
        QUICK_REPLIES.take(limit).forEach { text ->
            list.addItem(
                Row.Builder()
                    .setTitle(text)
                    .setOnClickListener { send(text) }
                    .build()
            )
        }
        val to = when (target) {
            is ThreadTarget.Channel -> target.name
            is ThreadTarget.Direct -> target.name
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("Send to $to")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun send(text: String) {
        screenManager.pop()
        carWorkScope.launch {
            val radio = container.radioManager
            val ok = when (target) {
                is ThreadTarget.Channel -> radio.sendTextMessage(text, channel = target.index, replyId = replyId)
                is ThreadTarget.Direct -> radio.sendTextMessage(text, toNum = target.peerNum, replyId = replyId)
            }
            withContext(Dispatchers.Main) {
                toast(if (ok) "Sent: $text" else "Not sent: radio not connected")
            }
        }
    }
}
