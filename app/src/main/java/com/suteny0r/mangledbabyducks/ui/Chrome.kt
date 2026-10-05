package com.suteny0r.mangledbabyducks.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.suteny0r.mangledbabyducks.R
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosRed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.math.pow

/*
 * Shared chrome, ported from the iOS toolbar pieces every tab shares:
 * MeshtasticLogo.swift (top-left logo button), ConnectedDevice.swift (top-right link +
 * node pill), CircleText.swift (node avatar), BatteryCompact.swift, IconAndText, and the
 * TabView in ContentView.swift. The logo is this app's own icon: the Meshtastic mark is a
 * registered trademark and never appears here.
 */

// ---------------------------------------------------------------------------------------
// Header

/** The circular app icon at the top-left of every tab; taps open About, like iOS. */
@Composable
fun AppLogo(size: Dp = 44.dp, onClick: (() -> Unit)? = null) {
    val router = LocalContext.current.container.router
    Image(
        painter = painterResource(R.mipmap.ic_launcher_background),
        contentDescription = "About",
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable { onClick?.invoke() ?: router.openAbout() },
    )
}

/**
 * ConnectedDevice.swift: green link with the connected node's short name, or a red
 * broken link. Reads the live radio state directly so every tab shows the same pill.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ConnectedDevicePill(modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val state by container.radioManager.state.collectAsState()
    val myUser by remember {
        container.radioManager.myNodeNum.flatMapLatest { container.database.userDao().userFlow(it) }
    }.collectAsState(initial = null)
    val connected = state is RadioState.Subscribed

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            RxTxIndicator(connected)
            Box(
                Modifier
                    .size(30.dp)
                    .background(
                        (if (connected) IosGreen else IosRed).copy(alpha = 0.22f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (connected) Icons.Filled.Link else Icons.Filled.LinkOff,
                    contentDescription = if (connected) "Connected" else "Not connected",
                    tint = if (connected) IosGreen else IosRed,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (connected) {
                val short = myUser?.shortName?.takeIf { it.isNotBlank() } ?: "?"
                Text(
                    short,
                    style = if (short.isEmojiOnly()) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.bodyMedium,
                    color = if (short.isEmojiOnly()) Color.Unspecified
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * RXTXIndicatorWidget.swift: two tiny LEDs, green for packets to the radio (TX) and red
 * for packets from it (RX), each flashing when its counter moves. Tapping it sends a
 * heartbeat (so the lights blink on demand) and opens the packet-count popup.
 */
@Composable
private fun RxTxIndicator(connected: Boolean) {
    val radio = LocalContext.current.container.radioManager
    val sent by radio.packetsSent.collectAsState()
    val received by radio.packetsReceived.collectAsState()
    val scope = rememberCoroutineScope()
    var popup by remember { mutableStateOf(false) }
    val arrow = MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    if (connected) scope.launch { runCatching { radio.sendHeartbeat() } }
                    popup = !popup
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Sent", tint = arrow, modifier = Modifier.size(9.dp))
                LedIndicator(flash = sent, color = IosGreen)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Received", tint = arrow, modifier = Modifier.size(9.dp))
                LedIndicator(flash = received, color = IosRed)
            }
        }
        if (popup) {
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 80), onDismissRequest = { popup = false }) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.clickable { popup = false },
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Packet Count", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            LedIndicator(flash = sent, color = IosGreen)
                            Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = arrow, modifier = Modifier.size(9.dp))
                            Text("To Radio (TX): $sent", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            LedIndicator(flash = received, color = IosRed)
                            Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = arrow, modifier = Modifier.size(9.dp))
                            Text("From Radio (RX): $received", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** LEDIndicator: lights fully on every change of [flash], then eases out over 300 ms. */
@Composable
private fun LedIndicator(flash: Int, color: Color) {
    val brightness = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        brightness.snapTo(1f)
        brightness.animateTo(0f, tween(300, easing = FastOutLinearInEasing))
    }
    val ring = if (isSystemInDarkTheme()) Color.White else Color.Black
    Box(
        Modifier
            .size(9.dp)
            .background(color.copy(alpha = brightness.value), CircleShape)
            .border(0.5.dp, ring, CircleShape),
    )
}

/**
 * Tab header. [large] is the iOS large-title layout (logo row, then the bold title on
 * its own line); otherwise the title sits inline next to the logo (Nodes, Messages).
 */
@Composable
fun AppHeader(
    title: String,
    large: Boolean = true,
    showStatus: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo()
            if (!large) {
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            trailing()
            if (showStatus) ConnectedDevicePill()
        }
        if (large) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Node avatar (CircleText.swift) and color (UIColor(hex: num))

/** iOS derives every node's color from its number: the low 24 bits are the RGB. */
fun nodeColor(num: Long): Color = Color(0xFF000000L or (num and 0xFFFFFFL))

/** WCAG relative luminance above 0.179 reads as light, so dark text goes on top. */
fun Color.isLight(): Boolean {
    fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * lin(red) + 0.7152f * lin(green) + 0.0722f * lin(blue) > 0.179f
}

@Composable
fun NodeAvatar(shortName: String?, num: Long, size: Dp, modifier: Modifier = Modifier) {
    val text = shortName?.takeIf { it.isNotBlank() } ?: "?"
    val color = nodeColor(num)
    val glyphs = text.codePointCount(0, text.length)
    // CircleText lets the font shrink to fit 90% of the circle; approximate that from
    // the glyph count, since Compose 1.7 has no auto-size text.
    val scale = when {
        text.isEmojiOnly() -> 0.55f
        glyphs <= 1 -> 0.5f
        glyphs == 2 -> 0.42f
        glyphs == 3 -> 0.32f
        else -> 0.26f
    }
    Box(
        modifier
            .size(size)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = (size.value * scale).sp,
            fontWeight = FontWeight.Medium,
            color = if (text.isEmojiOnly()) Color.Unspecified
            else if (color.isLight()) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** True when the string is nothing but emoji (and variation selectors / joiners). */
fun String.isEmojiOnly(): Boolean {
    if (isBlank()) return false
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF ||
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF || cp == 0x00A9 || cp == 0x00AE
        if (!emoji) return false
        i += Character.charCount(cp)
    }
    return true
}

// ---------------------------------------------------------------------------------------
// Small widgets

/** BatteryCompact.swift: icon by level, >100 means plugged in, 100 means charging. */
@Composable
fun BatteryCompact(level: Int?, modifier: Modifier = Modifier) {
    if (level == null) return
    val tint = MaterialTheme.colorScheme.primary
    val (icon, label) = when {
        level > 100 -> Icons.Filled.Power to "PWD"
        level == 100 -> Icons.Filled.BatteryChargingFull to "100%"
        level >= 85 -> Icons.Filled.BatteryFull to "$level%"
        level >= 60 -> Icons.Filled.Battery6Bar to "$level%"
        level >= 35 -> Icons.Filled.Battery4Bar to "$level%"
        level >= 10 -> Icons.Filled.Battery2Bar to "$level%"
        else -> Icons.Filled.Battery0Bar to "$level%"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = "Battery", tint = tint, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** IconAndText from the iOS helpers: a fixed-width glyph column, then the text. */
@Composable
fun IconAndText(
    icon: ImageVector,
    text: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke(this)
    }
}

// ---------------------------------------------------------------------------------------
// Grouped list pieces (iOS inset-grouped List)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** A white rounded card with no elevation, the iOS grouped section container. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(content = content) }
}

/** Divider inset past the leading icon column, as iOS insets its row separators. */
@Composable
fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 60.dp),
    )
}

/**
 * One row of a grouped list: accent-tinted leading glyph, title, optional subtitle,
 * trailing content (a chevron when the row navigates).
 */
@Composable
fun NavRow(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let {
            {
                Icon(
                    it,
                    contentDescription = null,
                    tint = if (enabled) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        },
        trailingContent = when {
            trailing != null -> trailing
            chevron && onClick != null -> {
                {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
    )
}

// ---------------------------------------------------------------------------------------
// Floating tab bar (ContentView.swift TabView, iOS 26 floating style)

data class TabSpec(val label: String, val icon: ImageVector)

@Composable
fun FloatingTabBar(
    tabs: List<TabSpec>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badgeIndex: Int = -1,
    badgeCount: Int = 0,
) {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selected
                    val tint = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                    ) {
                        val iconContent: @Composable () -> Unit = {
                            Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(26.dp))
                        }
                        if (index == badgeIndex && badgeCount > 0) {
                            BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { iconContent() }
                        } else {
                            iconContent()
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
