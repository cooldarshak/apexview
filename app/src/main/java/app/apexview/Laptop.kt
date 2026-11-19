package app.apexview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

private val ROW_HEIGHT = 34.dp
internal val TYRE_COLORS = mapOf(
    "SOFT" to Color(0xFFFF3B30),
    "MEDIUM" to Color(0xFFFFD60A),
    "HARD" to Color(0xFFF2F2F2),
    "INTERMEDIATE" to Color(0xFF34C759),
    "WET" to Color(0xFF0A84FF),
)
private val cell = TextStyle(color = Color.White, fontSize = 13.sp, fontFeatureSettings = "tnum")

/**
 * The half of the screen below the hinge in laptop mode: the timing tower on the left. The track
 * map on the right is its own window, movable when the layout is unlocked. Both follow the
 * picture's clock, like the map and cards everywhere else.
 */
@Composable
fun LaptopPanel(app: AppState, timing: SessionTiming?, modifier: Modifier) {
    // The tower takes the left; the map is a window of its own over the right (see Viewer).
    Row(modifier.background(Ink.background)) {
        Column(Modifier.weight(AppState.LAPTOP_TOWER_SHARE).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // An onboard is up top: one tap back to the broadcast it replaced.
                app.laptopReturnChannel?.let { channel ->
                    Row(
                        Modifier.pressable { app.returnToBroadcast() }.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Rounded.ArrowBackIosNew, null,
                            tint = Ink.primary, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Back to ${broadcastName(channel)}", style = Type.headline)
                    }
                }
                Spacer(Modifier.weight(1f))
                timing?.let { SessionCountdown(app, it, Type.title2, Modifier.padding(top = 8.dp)) }
            }
            if (timing != null && timing.laps.isNotEmpty()) TowerTextScale(app) { TimingTower(app, timing) }
            else Text(app.mapStatus ?: "No timing for this session.", style = Type.footnote, modifier = Modifier.padding(20.dp))
        }
        Spacer(Modifier.weight(1f - AppState.LAPTOP_TOWER_SHARE))
    }
}

/**
 * The time left in a session that runs on time, as "Q2 12:34", following the picture's clock. Dims
 * while the clock is stopped, and goes at the chequered flag. Races have laps, not a countdown.
 */
@Composable
fun SessionCountdown(app: AppState, timing: SessionTiming, style: TextStyle, modifier: Modifier = Modifier) {
    if (timing.isRace) return
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = app.mapTimeMs() ?: 0L
            delay(250)
        }
    }
    if (now == 0L) return
    val reading = timing.clock.at(now) ?: return
    val flag = timing.finishedUtc
    if (reading.remainingMs == 0L && flag != null && now >= flag) return
    val seconds = (reading.remainingMs + 999) / 1000
    val time = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
    val part = reading.part?.takeIf { timing.showsPushLaps }?.let { (if (timing.name.contains("Sprint", ignoreCase = true)) "SQ" else "Q") + it + " " }.orEmpty()
    Text(
        part + time,
        style = style.copy(color = if (reading.running) Ink.primary else Ink.secondary, fontFeatureSettings = "tnum"),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The timing tower over the feeds, for when the phone isn't in laptop mode: the same table, in a
 * panel that moves and resizes like the radio's.
 */
@Composable
fun TowerPanel(app: AppState, timing: SessionTiming, width: Float, height: Float, feedScale: Float) {
    val density = LocalDensity.current
    val plan = app.combinedOverlayPlan
    val bounds = plan?.workspace
    val widthRange = app.towerWidthRange(width)
    val maxTowerWidth = with(density) { (if (app.tvLandscapeLayout) bounds?.w ?: width else widthRange.endInclusive).toDp() }
    val minTowerWidth = with(density) { widthRange.start.toDp().coerceAtMost(maxTowerWidth) }
    val maxTowerHeight = bounds?.let { with(density) { it.h.toDp() } } ?: with(density) {
        minOf(TOWER_HEIGHT, height.toDp())
    }
    TowerTextScale(app) {
        FloatingPanel(
            app, app.towerSpot, "", "Hide timing tower", app::toggleTower, width, height, feedScale,
            maxWidth = maxTowerWidth, maxHeight = maxTowerHeight,
            minWidth = minTowerWidth,
            minHeight = minOf(AppState.TOWER_MIN_HEIGHT_DP.dp, maxTowerHeight,
                plan?.takeUnless { it.floating }?.let { with(density) { it.tower.h.toDp() } } ?: maxTowerHeight),
            bounds = bounds,
            tower = true,
            opacity = app.towerOpacity,
            locked = app.towerLocked,
            onResizeFinished = app::finishTowerResize,
        ) {
            TimingTower(app, timing)
        }
    }
}

@Composable
private fun TowerTextScale(app: AppState, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * app.towerFontScale),
        content = content,
    )
}

private data class TowerSample(
    val rows: List<Pair<String, CardState>>,
    val header: TowerSessionHeaderData,
    val tyres: Map<String, Tyre?>,
    val speeds: Map<String, Pair<Int?, Int?>>,
)

/** Live running order. The screen follows the same delayed picture clock as the map. */
@Composable
private fun TimingTower(app: AppState, timing: SessionTiming) {
    LaunchedEffect(timing) { app.loadSpeeds(timing) }
    var sample by remember { mutableStateOf<TowerSample?>(null) }
    LaunchedEffect(app, timing) {
        while (true) {
            val now = app.mapTimeMs() ?: 0L
            val speeds = timing.speeds
            sample = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val rows = timing.laps.mapNotNull { (number, laps) ->
                    laps.at(now, includeRunningTime = false)?.let { number to it }
                }.sortedBy { it.second.position ?: 99 }
                TowerSample(rows, towerSessionHeader(timing, now), timing.tyres.mapValues { it.value.at(now) },
                    speeds.mapValues { it.value.kmhAt(now) to it.value.gearAt(now) })
            }
            delay(if (app.isTv) 250L else 500L)
        }
    }
    val visible = sample ?: return
    val onTop = app.tiles.firstOrNull { it.id == app.bigTileIdForUi }?.channel?.racingNumber?.toString()
    TimingTowerContents(timing, visible.rows, visible.header, visible.tyres, visible.speeds,
        onTop, app.settings.speedUnit, app.layoutMode == LayoutMode.TV, app::showOnboard)
}

/** The phone and TV receiver use this same table; receiver data is already sampled on the phone. */
@Composable
internal fun TimingTowerContents(
    timing: SessionTiming, rows: List<Pair<String, CardState>>, header: TowerSessionHeaderData,
    tyres: Map<String, Tyre?>, speeds: Map<String, Pair<Int?, Int?>>, onTop: String?,
    speedUnit: String, tvLayout: Boolean, onDriver: (String) -> Unit,
) {
    val context = LocalContext.current
    val television = remember(context) { isTvDevice(context) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The table is 361dp wide, plus its 18dp side padding. Scale its layout with the panel
        // so S1/S2/S3 and the tyre column remain inside the visible window when it is narrowed.
        // Let the 1/3 landscape sidebar shrink the complete table rather than clip its last columns.
        // The text-size setting remains available when the sidebar is especially narrow.
        val baseDensity = LocalDensity.current
        val contentScale = (maxWidth.value / 379f).coerceIn(0.25f, if (television) 1.75f else 1.25f)
        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density * contentScale, baseDensity.fontScale)
        ) {
            val listState = rememberLazyListState()
            val scrollHint by remember(listState, rows.size) {
                derivedStateOf {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val first = visible.firstOrNull()?.index?.plus(1) ?: 0
                    val last = visible.lastOrNull()?.index?.plus(1) ?: 0
                    when {
                        first == 0 -> "${rows.size} DRIVERS"
                        first == 1 && last >= rows.size -> "${rows.size} DRIVERS"
                        first > 1 && last < rows.size -> "↑↓ $first–$last / ${rows.size} · SCROLL"
                        first > 1 -> "↑ $first–$last / ${rows.size} · SCROLL"
                        last < rows.size -> "↓ $first–$last / ${rows.size} · SCROLL"
                        else -> "${rows.size} DRIVERS"
                    }
                }
            }
            val minimumRowHeight = if (tvLayout) 16f else 20f
            val rowHeight = if (rows.isEmpty()) ROW_HEIGHT else {
                // The TV preset uses a compact broadcast tower so more of the field is visible.
                // On other layouts preserve the existing minimum for touch readability.
                ((maxHeight.value / contentScale - 67f) / rows.size)
                    .coerceIn((minimumRowHeight / contentScale).coerceAtMost(ROW_HEIGHT.value), ROW_HEIGHT.value).dp
            }
            Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp)) {
                TowerSessionHeader(header)
                TowerHeader()
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(rows, key = { it.first }) { (number, state) ->
                        val kmh = speeds[number]?.first
                        val gear = speeds[number]?.second
                        if (television) {
                            TvTowerRow(timing.drivers[number], number, state, tyres[number], kmh, gear,
                                speedUnit, number == onTop, rowHeight) { onDriver(number) }
                        } else TowerRow(
                            timing = timing,
                            number = number,
                            state = state,
                            tyre = tyres[number],
                            kmh = kmh,
                            gear = gear,
                            speedUnit = speedUnit,
                            highlight = number == onTop,
                            rowHeight = rowHeight,
                        ) { onDriver(number) }
                    }
                }
                if (rows.isNotEmpty()) {
                    Text(
                        scrollHint,
                        style = cell.copy(fontSize = 6.sp, color = Ink.secondary),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/** Compact session/track strip, like the header on the F1 timing screen. */
internal fun towerSessionHeader(timing: SessionTiming, now: Long): TowerSessionHeaderData {
    val flag = timing.flags.at(now)
    val trackLabel = flag.caption(now) ?: "Track clear"
    val trackColor = when {
        flag.track == TrackCondition.RED -> Color(0xFFFF453A)
        flag.track.safetyCar || flag.track == TrackCondition.VSC || flag.track == TrackCondition.VSC_ENDING -> Color(0xFFFFD60A)
        else -> Color(0xFF30D158)
    }
    val currentLap = timing.currentLap ?: timing.laps.values.mapNotNull { it.lapAt(now) }.maxOrNull()
    val counter = if (timing.isRace && currentLap != null) {
        "L${currentLap}/${timing.totalLaps ?: "–"}"
    } else {
        timing.clock.at(now)?.let { reading ->
            val seconds = (reading.remainingMs + 999) / 1000
            val clock = "%02d:%02d".format(seconds / 60, seconds % 60)
            val part = reading.part?.takeIf { timing.showsPushLaps }
                ?.let { (if (timing.name.contains("Sprint", ignoreCase = true)) "SQ" else "Q") + it + " " }
                .orEmpty()
            part + clock
        }.orEmpty()
    }
    val weather = timing.weather.at(now)
    val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    val windDirection = weather?.windDirectionDegrees?.let {
        directions[((it / 22.5 + 0.5).toInt()).mod(16)]
    }.orEmpty()
    val wind = weather?.windSpeedMps?.let { "W ${"%.1f".format(it * 3.6)} ${windDirection}" } ?: "W –"
    val trackTemp = weather?.trackTempC?.let { "T ${"%.1f".format(it)}°" } ?: "T –"
    val airTemp = weather?.airTempC?.let { "A ${"%.1f".format(it)}°" } ?: "A –"
    val humidity = weather?.humidityPct?.let { "H ${"%.1f".format(it)}%" } ?: "H –"
    val pressure = weather?.pressureHpa?.let { "P ${"%.1f".format(it)}" } ?: "P –"
    val rain = when (weather?.raining) {
        true -> "RAIN"
        false -> "DRY"
        null -> "RAIN –"
    }
    return TowerSessionHeaderData(timing.name.uppercase(), trackLabel.uppercase(), trackColor,
        counter, listOf(wind, trackTemp, airTemp, humidity, pressure, rain))
}

internal data class TowerSessionHeaderData(val name: String, val track: String,
    val color: Color, val counter: String, val weather: List<String>)

@Composable
internal fun TowerSessionHeader(header: TowerSessionHeaderData) {
    Column(Modifier.fillMaxWidth().height(36.dp)) {
        Row(
            Modifier.fillMaxWidth().height(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                header.name,
                style = cell.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Ink.secondary),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                header.track,
                style = cell.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = header.color),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 5.dp),
            )
            Text(
                header.counter,
                style = cell.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth().height(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            header.weather.forEach { value ->
                Text(
                    value,
                    style = cell.copy(fontSize = 7.sp, color = Ink.secondary),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Narrow headings aligned to the broadcast-style row columns. */
@Composable
fun TowerHeader() {
    Row(
        Modifier.fillMaxWidth().height(19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Header("POS / DRV", 64.dp, TextAlign.Start)
        Header("GEAR", 30.dp)
        Header("LAST / BEST", 62.dp)
        Header("INT / LDR", 46.dp)
        Header("S1", 43.dp)
        Header("S2", 43.dp)
        Header("S3", 43.dp)
        Header("TYR", 30.dp)
    }
}

/** One dense live row: order, gear/speed, lap times, gaps, three sector strips and tyre age. */
@Composable
fun TowerRow(
    timing: SessionTiming,
    number: String,
    state: CardState,
    tyre: Tyre?,
    kmh: Int? = null,
    gear: Int? = null,
    speedUnit: String = Settings.KMH,
    highlight: Boolean,
    rowHeight: Dp = ROW_HEIGHT,
    onClick: () -> Unit,
) {
    val driver = timing.drivers[number]
    val teamColor = driver?.color?.let { Color(it) } ?: Color.White
    val last = state.lastLap
    val best = state.bestLap
    Row(
        Modifier
            .height(rowHeight)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    highlight -> Color.White.copy(alpha = 0.12f)
                    state.retired -> Color.White.copy(alpha = 0.025f)
                    else -> Color.Transparent
                }
            )
            .pressable(scaleTo = 0.99f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(18.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.position?.toString() ?: "–",
                    style = cell.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.End,
                )
                state.positionChange?.takeIf { it != 0 }?.let { change ->
                    val gained = change > 0
                    Text(
                        "${if (gained) "▲" else "▼"}${if (gained) change else -change}",
                        style = cell.copy(fontSize = 6.sp, color = if (gained) Color(0xFF30D158) else Color(0xFFFF453A)),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.width(42.dp).height(23.dp).clip(RoundedCornerShape(3.dp)).background(teamColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    driver?.tla ?: number,
                    style = cell.copy(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black),
                    maxLines = 1,
                )
            }
        }
        Column(
            Modifier.width(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(19.dp).border(1.5.dp, Color(0xFF28A8F8), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    gear?.let { if (it == 0) "N" else it.toString() } ?: "–",
                    style = cell.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                )
            }
            Text(
                kmh?.let { speedIn(it, speedUnit) }?.toString() ?: "–",
                style = cell.copy(fontSize = 8.sp, color = Ink.secondary),
                maxLines = 1,
            )
        }
        Column(
            Modifier.width(62.dp).padding(start = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LAST", style = cell.copy(fontSize = 6.sp, color = Ink.secondary), modifier = Modifier.width(19.dp))
                Text(
                    when { state.retired -> "OUT"; state.inPit -> "PIT"; else -> last?.time?.ifBlank { "–" } ?: "–" },
                    style = cell.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (state.inPit) Color(0xFFFF453A) else lapColor(last?.pace)),
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BEST", style = cell.copy(fontSize = 6.sp, color = Ink.secondary), modifier = Modifier.width(19.dp))
                Text(
                    best?.time?.ifBlank { "–" } ?: "–",
                    style = cell.copy(fontSize = 9.sp, color = lapColor(best?.pace)),
                    maxLines = 1,
                )
            }
        }
        Column(
            Modifier.width(46.dp).padding(start = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("INT", style = cell.copy(fontSize = 6.sp, color = Ink.secondary), modifier = Modifier.width(14.dp))
                Text(state.ahead ?: "–", style = cell.copy(fontSize = 8.sp), maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LDR", style = cell.copy(fontSize = 6.sp, color = Ink.secondary), modifier = Modifier.width(14.dp))
                Text(state.toLeader ?: "–", style = cell.copy(fontSize = 8.sp, color = Ink.secondary), maxLines = 1)
            }
        }
        repeat(3) { index ->
            val sector = state.towerSector(index)
            TowerSector(sector)
        }
        Column(
            Modifier.width(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (tyre != null) {
                TyreMark(tyre.compound)
                Text(tyre.laps.toString(), style = cell.copy(fontSize = 7.sp, color = Ink.secondary), maxLines = 1)
            } else {
                Text("–", style = cell.copy(fontSize = 9.sp, color = Ink.tertiary))
            }
        }
    }
}

/** A sector value with its official purple/green/yellow comparison and live micro-sector strip. */
@Composable
private fun TowerSector(sector: SectorCell?) {
    Column(
        Modifier.width(43.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val segments = sector?.segments.orEmpty()
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val count = segments.size.takeIf { it > 0 } ?: 5
            val gap = minOf(1.dp.toPx(), size.width / (2 * count))
            val width = (size.width - (count - 1) * gap) / count
            val height = if (segments.isEmpty()) 6.dp.toPx() else size.height
            val radius = CornerRadius(minOf(1.dp.toPx(), width / 2))
            repeat(count) { index ->
                drawRoundRect(
                    color = segmentColor(segments.getOrNull(index) ?: 0),
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = Size(width, height),
                    cornerRadius = radius,
                )
            }
        }
        Text(
            sector?.time?.ifBlank { "–" } ?: "–",
            style = cell.copy(fontSize = 8.sp, color = lapColor(sector?.pace)),
            maxLines = 1,
        )
    }
}

internal fun segmentColor(status: Int) = when (status) {
    2048, 2052 -> Color(0xFFFFD60A) // Yellow
    2049 -> Color(0xFF30D158) // Personal best
    2051 -> Color(0xFFBF5AF2) // Session best
    2064 -> Color(0xFF28A8F8) // Pit lane
    else -> Color.White.copy(alpha = 0.10f)
}

/** The tyre as on the broadcast: a ring in the compound's colour with its letter. */
@Composable
private fun TyreMark(compound: String) {
    val color = TYRE_COLORS[compound] ?: Ink.secondary
    Box(
        Modifier.size(16.dp).border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(compound.take(1), style = cell.copy(color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Header(text: String, width: Dp, align: TextAlign = TextAlign.End) {
    Text(text, style = Type.caption.copy(fontSize = 7.sp), textAlign = align, maxLines = 1, modifier = Modifier.width(width))
}

internal fun lapColor(pace: Pace?) = when (pace) {
    Pace.PURPLE -> Color(0xFFBF5AF2)
    Pace.GREEN -> Color(0xFF30D158)
    else -> Color.White
}

/** The one-time question when the phone is first held half-open like a laptop. */
@Composable
fun LaptopPrompt(app: AppState) {
    if (!app.laptopPromptOpen) return
    Dialog(onDismissRequest = { app.answerLaptopPrompt(false) }) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .padding(24.dp)
        ) {
            Text("Laptop mode?", style = Type.title2)
            Text(
                "With the phone half-open like this, the stream can sit on the top half and the timing tower and track map on the bottom half. You can turn this on or off any time in Layout.",
                style = Type.subhead,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton("No thanks") { app.answerLaptopPrompt(false) }
                PrimaryButton("Use laptop mode") { app.answerLaptopPrompt(true) }
            }
        }
    }
}

/** "F1 LIVE" as "F1 Live", "INTERNATIONAL" as "International". */
private fun broadcastName(channel: Channel) = channel.title.split(" ").joinToString(" ") { word ->
    if (word.length <= 2) word.uppercase() else word.lowercase().replaceFirstChar { it.uppercase() }
}

/** The floating tower's size until it's resized: every column, and about ten drivers. */
private val TOWER_HEIGHT = 720.dp
