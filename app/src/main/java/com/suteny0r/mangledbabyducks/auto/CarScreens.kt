package com.suteny0r.mangledbabyducks.auto

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
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
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.knownRadios
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.ThreadTarget
import com.suteny0r.mangledbabyducks.ui.relativeTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
private const val PANE_ROW_CAP = 6

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

    /** Collect while the screen is at least started; every distinct value redraws the template. */
    protected fun <T> observe(flow: Flow<T>, onValue: (T) -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.distinctUntilChanged().collect { value ->
                    onValue(value)
                    invalidate()
                }
            }
        }
    }

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

    protected fun toast(text: String) {
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()
    }

    protected fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** The car's own fix (phone GPS), else the connected node's last position. */
    protected fun carLocation(myNum: Long, positions: List<MapNode>): Pair<Double, Double>? {
        container.locationSharer.lastFix.value?.let { return it.latitudeI / 1e7 to it.longitudeI / 1e7 }
        return positions.firstOrNull { it.nodeNum == myNum }?.let { it.latitude to it.longitude }
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

/** Channel label with the phone UI's fallback for unnamed channels. */
private fun channelName(channel: ChannelEntity?, index: Int): String =
    channel?.name?.takeIf { it.isNotBlank() }
        ?: if (index == 0) "Primary Channel" else "Channel $index"

/** Marker labels are capped at three characters by the host; emoji do not render there. */
private fun markerLabel(shortName: String?): String {
    val letters = shortName.orEmpty().filter { it.isLetterOrDigit() }.take(3)
    return letters.ifEmpty { "?" }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
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
        observe(container.database.nodeDao().count()) { nodeCount = it }
        observe(container.database.positionDao().mapNodes().map { it.size }) { positioned = it }
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
        observe(container.database.positionDao().mapNodes()) { list ->
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

        list.addItem(
            Row.Builder()
                .setTitle("Radio")
                .addText(state.describe(deviceName))
                .setImage(icon(R.drawable.ic_car_radio))
                // PlaceListMapTemplate rejects a non-browsable row without a distance span.
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CarRadioScreen(carContext)) }
                .build()
        )
        nodes.filter { it.nodeNum != myNum }.take(limit - 1).forEach { node ->
            val text = SpannableStringBuilder("heard ${relativeTime(node.time)}")
            here?.let { (lat, lon) ->
                text.append(" · ")
                text.append(distanceSpan(haversineMeters(lat, lon, node.latitude, node.longitude)))
            }
            val place = Place.Builder(CarLocation.create(node.latitude, node.longitude))
                .setMarker(
                    PlaceMarker.Builder()
                        .setLabel(markerLabel(node.shortName))
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
    private var myNum = 0L

    init {
        observe(container.database.nodeDao().nodesWithUsers()) { nodes = it }
        observe(container.radioManager.myNodeNum) { myNum = it }
    }

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        val list = ItemList.Builder().setNoItemsMessage("No nodes yet")
        nodes.asSequence()
            .filter { !it.node.ignored }
            .sortedWith(
                compareByDescending<NodeWithUser> { it.node.num == myNum }
                    .thenByDescending { it.node.favorite }
                    .thenByDescending { it.node.lastHeard ?: 0L }
            )
            .take(limit)
            .forEach { entry ->
                val num = entry.node.num
                val detail = buildList {
                    entry.user?.shortName?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (num == myNum) add("connected") else entry.node.lastHeard?.let { add(relativeTime(it)) }
                    if (entry.node.hopsAway > 0) add("${entry.node.hopsAway} hops")
                }.joinToString(" · ")
                list.addItem(
                    Row.Builder()
                        .setTitle(displayName(entry.user, num))
                        .addText(detail.ifEmpty { nodeNumString(num) })
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
        observe(container.database.positionDao().mapNodes()) { mapNodes = it }
        observe(container.radioManager.myNodeNum) { myNum = it }
    }

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE, PANE_ROW_CAP)
        val node = entry?.node
        val user = entry?.user
        val rows = mutableListOf<Row>()
        rows += Row.Builder()
            .setTitle("Node")
            .addText(listOfNotNull(user?.shortName, nodeNumString(num)).joinToString(" · "))
            .build()
        node?.lastHeard?.let {
            rows += Row.Builder().setTitle("Last heard").addText(relativeTime(it)).build()
        }
        if (node != null && (node.snr != 0f || node.rssi != 0 || node.hopsAway >= 0)) {
            val signal = buildList {
                if (node.hopsAway == 0) add("direct") else if (node.hopsAway > 0) add("${node.hopsAway} hops")
                if (node.snr != 0f) add("SNR %.1f dB".format(node.snr))
                if (node.rssi != 0) add("RSSI ${node.rssi}")
            }.joinToString(" · ")
            if (signal.isNotEmpty()) rows += Row.Builder().setTitle("Signal").addText(signal).build()
        }
        metrics?.batteryLevel?.let { level ->
            // Firmware reports >100 for a node running on external power.
            rows += Row.Builder().setTitle("Battery").addText(if (level > 100) "Plugged in" else "$level%").build()
        }
        position?.let { pos ->
            val text = SpannableStringBuilder("%.5f, %.5f".format(pos.latitude, pos.longitude))
            carLocation(myNum, mapNodes)?.let { (lat, lon) ->
                text.append(" · ")
                text.append(distanceSpan(haversineMeters(lat, lon, pos.latitude, pos.longitude)))
            }
            rows += Row.Builder().setTitle("Position").addText(text).build()
        }

        val pane = Pane.Builder()
        rows.take(limit).forEach { pane.addRow(it) }
        if (num != myNum) {
            val name = displayName(user, num)
            pane.addAction(
                Action.Builder()
                    .setTitle("Message")
                    .setIcon(icon(R.drawable.ic_car_message))
                    .setOnClickListener {
                        screenManager.push(CarQuickReplyScreen(carContext, ThreadTarget.Direct(num, name)))
                    }
                    .build()
            )
            pane.addAction(
                Action.Builder()
                    .setTitle("Traceroute")
                    .setOnClickListener { traceroute() }
                    .build()
            )
        }
        @Suppress("DEPRECATION")
        return PaneTemplate.Builder(pane.build())
            .setTitle(displayName(user, num))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun traceroute() {
        toast("Traceroute sent")
        carWorkScope.launch {
            val ok = container.radioManager.sendTraceroute(num)
            if (!ok) withContext(Dispatchers.Main) { toast("Traceroute failed: radio not connected") }
        }
    }
}

/** Channels and direct-message contacts, the car twin of the Messages tab's thread list. */
class CarMessagesScreen(carContext: CarContext) : MeshCarScreen(carContext) {

    private var channels: List<ChannelEntity> = emptyList()
    private var contacts: List<UserEntity> = emptyList()

    init {
        observe(container.database.channelDao().activeChannels()) { channels = it }
        observe(container.database.userDao().dmContacts()) { contacts = it }
    }

    override fun onGetTemplate(): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, LIST_ROW_CAP)
        @Suppress("DEPRECATION")
        val template = ListTemplate.Builder()
            .setTitle("Messages")
            .setHeaderAction(Action.BACK)

        val channelRows = channels.take(limit).map { channel ->
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
        val contactRows = contacts.take((limit - channelRows.size).coerceAtLeast(0)).map { user ->
            val name = displayName(user, user.num)
            Row.Builder()
                .setTitle(name)
                .addText(user.lastMessage?.let { "last message ${relativeTime(it)}" } ?: nodeNumString(user.num))
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(CarThreadScreen(carContext, ThreadTarget.Direct(user.num, name)))
                }
                .build()
        }

        if (channelRows.isEmpty() && contactRows.isEmpty()) {
            template.setSingleList(
                ItemList.Builder().setNoItemsMessage("No channels yet; connect a radio").build()
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
    private var myNum = 0L

    init {
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
        observe(container.database.nodeDao().nodesWithUsers()) { list ->
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
        val list = ItemList.Builder().setNoItemsMessage("No messages yet")
        messages.take(limit).forEach { message ->
            val mine = message.fromNum == myNum
            val sender = if (mine) "Me" else names[message.fromNum] ?: nodeNumString(message.fromNum)
            val status = buildString {
                append(relativeTime(message.timestamp))
                if (mine) append(if (message.receivedAck) " · delivered" else " · sending")
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
