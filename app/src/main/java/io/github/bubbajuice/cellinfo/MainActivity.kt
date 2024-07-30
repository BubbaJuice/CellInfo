package io.github.bubbajuice.cellinfo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import io.github.bubbajuice.cellinfo.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlin.math.round

class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyApplicationTheme {
                var showPermissionDialog by remember { mutableStateOf(false) }

                if (!hasPermissions()) {
                    requestPermissions.launch(permissions)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CellInfoScreen(
                        context = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                if (showPermissionDialog) {
                    PermissionDeniedDialog(onDismiss = {
                        showPermissionDialog = false
                        requestPermissions.launch(permissions)
                    })
                }
            }
        }
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            if (!allPermissionsGranted) {
                showPermissionDeniedDialog()
            }
        }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionDeniedDialog() {
    }
}

@Composable
fun PermissionDeniedDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Permissions Required", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "This app requires permissions to function properly. Please grant the necessary permissions.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

@Composable
fun CellInfoScreen(context: Context, modifier: Modifier = Modifier) {
    var cellInfoList by remember { mutableStateOf<List<CellInfo>>(emptyList()) }
    val expandedCells = remember { mutableStateOf<Map<Any, Boolean>>(emptyMap()) }

    LaunchedEffect(Unit) {
        while (true) {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                cellInfoList = telephonyManager.allCellInfo
                    .sortedByDescending {
                        when (it) {
                            is CellInfoNr -> 1
                            is CellInfoLte -> 0
                            else -> -1
                        }
                    }
            }
            delay(1000)
        }
    }

    CellInfoList(cellInfoList, expandedCells.value, onCellExpandChange = { cellId, isExpanded ->
        expandedCells.value = expandedCells.value.toMutableMap().apply {
            this[cellId] = isExpanded
        }
    }, modifier = modifier)
}

@Composable
fun CellInfoList(
    cellInfoList: List<CellInfo>,
    expandedCells: Map<Any, Boolean>,
    onCellExpandChange: (Any, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedCells = cellInfoList.groupBy {
        when (it) {
            is CellInfoNr -> "5G"
            is CellInfoLte -> "LTE"
            else -> "Other"
        }
    }

    LazyColumn(modifier = modifier.padding(16.dp)) {
        groupedCells.forEach { (type, cells) ->
            item {
                Text(
                    text = type,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            val firstCell = cells.firstOrNull()
            if (firstCell != null) {
                item {
                    val cellId = getCellId(firstCell)
                    Column {
                        CellInfoItem(
                            cellInfo = firstCell,
                            isExpanded = expandedCells[cellId] ?: false,
                            onExpandChange = { isExpanded -> onCellExpandChange(cellId, isExpanded) }
                        )

                        if (cells.size > 1) {
                            val groupExpanded = expandedCells[type] ?: false
                            ExpandableSection(
                                expandedTitle = "Hide ${cells.size - 1} ${type} cells",
                                collapsedTitle = "Show ${cells.size - 1} more ${type} cells",
                                expanded = groupExpanded,
                                onExpandChange = { isExpanded -> onCellExpandChange(type, isExpanded) }
                            ) {
                                cells.drop(1).forEach { cellInfo ->
                                    val additionalCellId = getCellId(cellInfo)
                                    CellInfoItem(
                                        cellInfo = cellInfo,
                                        isExpanded = expandedCells[additionalCellId] ?: false,
                                        onExpandChange = { isExpanded ->
                                            onCellExpandChange(additionalCellId, isExpanded)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpandableSection(
    expandedTitle: String,
    collapsedTitle: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandChange(!expanded) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
            Text(
                text = if (expanded) expandedTitle else collapsedTitle,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (expanded) {
            content()
        }
    }
}

fun getCellId(cellInfo: CellInfo): Any {
    return when (cellInfo) {
        is CellInfoNr -> cellInfo.toString()
        is CellInfoLte -> cellInfo.cellIdentity.ci
        else -> cellInfo.hashCode()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CellInfoItem(
    cellInfo: CellInfo,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit
) {
    var showDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    val cellInfoRows = if (isExpanded) {
        when (cellInfo) {
            is CellInfoNr -> formatCellInfoNr(cellInfo)
            is CellInfoLte -> formatCellInfoLte(cellInfo)
            else -> emptyList()
        }
    } else {
        when (cellInfo) {
            // 5G
            is CellInfoNr -> {
                val cellIdentity = cellInfo.cellIdentity as CellIdentityNr
                val cellSignalStrength = cellInfo.cellSignalStrength
                val band = getNrBandFromArfcn(cellIdentity.nrarfcn)
                listOf(
                    "eNB ID" to cellIdentity.nci.toString(),
                    "ssRSRP" to cellSignalStrength.dbm.toString() + " dBm",
                    "ARFCN" to cellIdentity.nrarfcn.toString(),
                    "Band Number" to "n$band"
                )
            }
            // 4G
            is CellInfoLte -> {
                val cellIdentity = cellInfo.cellIdentity
                val cellSignalStrength = cellInfo.cellSignalStrength

                val band = getLTEBandFromEArfcn(cellIdentity.earfcn)
                val cellSectorId = calculateCellSectorId(formatCellID(cellIdentity.ci))
                listOf(
                    "eNB ID" to calculateENBId(formatCellID(cellIdentity.ci)),
                    "Band Number" to band.toString(),
                    "Cell Sector ID" to cellSectorId,
                    "RSRP" to cellSignalStrength.rsrp.toString() + " dBm",
                    "RSRQ" to cellSignalStrength.rsrq.toString() + " dB"
                )
            }
            else -> emptyList()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onExpandChange(!isExpanded) },
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            cellInfoRows.forEach { (label, value) ->
                TableRow(
                    label = label,
                    value = value,
                    onClick = { onExpandChange(!isExpanded) },
                    onLongClick = { showDialog = label to getExplanation(label) }
                )
            }
        }
    }

    showDialog?.let { (title, message) ->
        InfoDialog(title = title, message = message) {
            showDialog = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableRow(label: String, value: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
fun InfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    }
}

fun getExplanation(label: String): String {
    return when (label) {
        "PCI" -> "Physical Cell Identity is a unique identifier for a cell in a LTE network."
        "eNB ID" -> "The eNodeB Identifier is the identifier of the hardware that communicates directly between mobile handsets (your phone) and a baseband unit."
        "Cell Sector ID" -> "Cell Sector ID is the identifier for which sector of the cell site the cell falls under."
        "Cell ID" -> "Cell ID is the raw identifier for the cell from which the eNB Id and Cell Sector ID is derived."
        "Band Number" -> "Bands are frequency bands or channels at which carries use."
        "Band Name" -> "The commonly used name of a band number."
        "TAC" -> "The Tracking Area Code is a code given based on where a cell site is located."
        "EARFCN" -> "The E-UTRA Absolute Radio Frequency Channel Number is a number that provides the frequency, bandwidth, and duplex mode of the connected channel."
        "Bandwidth" -> "The bandwidth is how wide a frequency band is."
        "MCC" -> "The Mobile County Code is a code that each county has that identifies which county a cell site is located in."
        "MNC" -> "The Mobile Network Code is a code that identifies which operator is broadcasting from a cell."
        "Operator" -> "The broadcasted operator of the cell."
        "Operator Abbreviation" -> "The broadcasted operator abbreviation of the cell."
        "RSSI" -> "Received Signal Strength Indicator is the total received power within the connected band."
        "RSRP" -> "Reference Signal Received Power is the power of the reference signal broadcasted in a cell."
        "RSRQ" -> "Reference Signal Received Quality is a measure of signal quality and is the ratio of the RSRP over the RSSI."
        "RSSNR" -> "Reference Signal Signal-to-Noise Ratio is the ratio of signal over background noise."
        "CQI" -> "Channel Quality Indicator is the metric reported to the network from a mobile device that indicated the downlink channel quality."
        "Timing Advance" -> "Timing Advance is a parameter that represents the amount of time by which a mobile device must advance its signal transmission to ensure it reaches the base station in the correct time slot. The TA is calculated by determining the round trip time of the signal."
        "DbM" -> "Decibel milliwatts that is used for measuring the power of signals."
        "Data" -> "Raw data given from the phone."
        "ssRSRP" -> "Synchronization Signal Reference Signal Received Power is the power of the synchronization reference signal broadcasted in a cell. The 5G equivalent of LTE RSRP."
        // Add more explanations for other labels
        else -> "No information available."
    }
}

fun formatCellInfoNr(cellInfoNr: CellInfoNr): List<Pair<String, String>> {
    val cellIdentity = cellInfoNr.cellIdentity as CellIdentityNr
    val cellSignalStrength = cellInfoNr.cellSignalStrength

    val band = getNrBandFromArfcn(cellIdentity.nrarfcn)

    return listOf(
        "ssRSRP" to cellSignalStrength.dbm.toString() + " dBm",
        "ARFCN" to cellIdentity.nrarfcn.toString(),
        "Band" to "n$band",
        "Data" to cellSignalStrength.toString() + cellIdentity.toString()
    )
}

fun getNrBandFromArfcn(arfcn: Int): Int {
    return when (arfcn) {
        in 422000..434000 -> 1
        in 386000..398000 -> 2
        in 361000..376000 -> 3
        in 173800..178800 -> 5
        in 524000..538000 -> 7
        in 185000..192000 -> 8
        in 145800..149200 -> 12
        in 149200..151200 -> 13
        in 151600..153600 -> 14
        in 172000..175000 -> 18
        in 158200..164200 -> 20
        in 305000..311800 -> 24
        in 386000..399000 -> 25
        in 171800..178800 -> 26
        in 151600..160600 -> 28
        in 143400..145600 -> 29
        in 470000..472000 -> 30
        in 92500..93500 -> 31
        in 402000..405000 -> 34
        in 514000..524000 -> 38
        in 376000..384000 -> 39
        in 460000..480000 -> 40
        in 499200..537999 -> 41
        in 743334..795000 -> 46
        in 790334..795000 -> 47
        in 636667..646666 -> 48
        in 286400..303400 -> 50
        in 285400..286400 -> 51
        in 496700..499000 -> 53
        in 334000..335000 -> 54
        in 422000..440000 -> 65
        in 422000..440000 -> 66
        in 147600..151600 -> 67
        in 399000..404000 -> 70
        in 123400..130400 -> 71
        in 92200..93200 -> 72
        in 295000..303600 -> 74
        in 286400..303400 -> 75
        in 285400..286400 -> 76
        in 620000..680000 -> 77
        in 620000..653333 -> 78
        in 693334..733333 -> 79
        in 342000..357000 -> 80
        in 176000..183000 -> 81
        in 166400..172400 -> 82
        in 140600..149600 -> 83
        in 384000..396000 -> 84
        in 145600..149200 -> 85
        in 342000..356000 -> 86
        in 164800..169800 -> 89
        in 499200..538000 -> 90
        in 285400..286400 -> 91
        in 286400..303400 -> 92
        in 285400..286400 -> 93
        in 286400..303400 -> 94
        in 402000..405000 -> 95
        in 795000..875000 -> 96
        in 460000..480000 -> 97
        in 376000..384000 -> 98
        in 325300..332100 -> 99
        in 183880..185000 -> 100
        in 380000..382000 -> 101
        in 795000..828333 -> 102
        in 828334..875000 -> 104
        in 122400..130400 -> 105
        in 187000..188000 -> 106
        in 286400..303400 -> 109
        in 434000..440000 -> 256
        in 305000..311800 -> 255
        in 496700..500000 -> 254
        in 434000..440000 -> 256
        in 2054166..2104165 -> 257
        in 2016667..2070832 -> 258
        in 2270833..2337499 -> 259
        in 2229166..2279165 -> 260
        in 2070833..2084999 -> 261
        in 2399166..2415832 -> 262
        in 2564083..2794243 -> 262
        else -> -1 // Unknown or unsupported band
    }
}

fun formatCellInfoLte(cellInfoLte: CellInfoLte): List<Pair<String, String>> {
    val cellIdentity = cellInfoLte.cellIdentity
    val cellSignalStrength = cellInfoLte.cellSignalStrength

    val eNodeBId = calculateENBId(formatCellID(cellIdentity.ci))
    val cellSectorId = calculateCellSectorId(formatCellID(cellIdentity.ci))
    val timingAdvance = formatTimingAdvance(cellSignalStrength.timingAdvance)
    val cqi = formatCQI(cellSignalStrength.cqi)
    val rssnr = formatRSSNR(cellSignalStrength.rssnr)
    val cellid = formatCellID(cellIdentity.ci)
    val band = getLTEBandFromEArfcn(cellIdentity.earfcn)

    return listOf(
        "eNB ID" to eNodeBId,
        "Cell Sector ID" to cellSectorId,
        "Band Number" to band.toString(),
        "RSRP" to cellSignalStrength.rsrp.toString() + " dBm",
        "RSRQ" to cellSignalStrength.rsrq.toString() + " dB",
        "Timing Advance" to timingAdvance,
        "Cell ID" to cellid,
        "PCI" to cellIdentity.pci.toString(),
        "EARFCN" to cellIdentity.earfcn.toString(),
        "Bandwidth" to cellIdentity.bandwidth.toString(),
        "TAC" to cellIdentity.tac.toString(),
        "MCC" to (cellIdentity.mccString ?: ""),
        "MNC" to (cellIdentity.mncString ?: ""),
        "RSSI" to cellSignalStrength.rssi.toString() + " dBm",
        "RSSNR" to "$rssnr dB",
        "CQI" to "$cqi dB",
        "Operator" to (cellIdentity.operatorAlphaLong?.toString() ?: ""),
        "Operator Abbreviation" to (cellIdentity.operatorAlphaShort?.toString() ?: ""),
        "Data" to cellSignalStrength.toString() + cellIdentity.toString(),
    )
}

fun getLTEBandFromEArfcn(earfcn: Int): Int {
    return when (earfcn) {
        in 0..599 -> 1
        in 600..1199 -> 2
        in 1200..1949 -> 3
        in 1950..2399 -> 4
        in 2400..2649 -> 5
        in 2650..2749 -> 6
        in 2750..3449 -> 7
        in 3450..3799 -> 8
        in 3800..4149 -> 9
        in 4150..4749 -> 10
        in 4750..4949 -> 11
        in 5010..5179 -> 12
        in 5180..5279 -> 13
        in 5280..5379 -> 14
        in 5730..5849 -> 17
        in 5850..5999 -> 18
        in 6000..6149 -> 19
        in 6150..6449 -> 20
        in 6450..6599 -> 21
        in 6600..7399 -> 22
        in 7500..7699 -> 23
        in 7700..8039 -> 24
        in 8040..8689 -> 25
        in 8690..9039 -> 26
        in 9040..9209 -> 27
        in 9210..9659 -> 28
        in 9660..9769 -> 29
        in 9770..9869 -> 30
        in 9870..9919 -> 31
        in 9920..10359 -> 32
        in 36000..36199 -> 33
        in 36200..36349 -> 34
        in 36350..36949 -> 35
        in 36950..37549 -> 36
        in 37550..37749 -> 37
        in 37750..38249 -> 38
        in 38250..38649 -> 39
        in 38650..39649 -> 40
        in 39650..41589 -> 41
        in 41590..43589 -> 42
        in 43590..45589 -> 43
        in 45590..46589 -> 44
        in 46590..46789 -> 45
        in 46790..54539 -> 46
        in 54540..55239 -> 47
        in 55240..56739 -> 48
        in 56740..58239 -> 49
        in 58240..59089 -> 50
        in 59090..59139 -> 51
        in 59140..60139 -> 52
        in 60140..60254 -> 53
        in 60255..60304 -> 54
        in 65536..66435 -> 65
        in 66436..67335 -> 66
        in 67336..67535 -> 67
        in 67536..67835 -> 68
        in 67836..68335 -> 69
        in 68336..68585 -> 70
        in 68586..68935 -> 71
        in 68936..68985 -> 72
        in 68986..69035 -> 73
        in 69036..69465 -> 74
        in 69466..70315 -> 75
        in 70316..70365 -> 76
        in 70366..70545 -> 85
        in 70546..70595 -> 87
        in 70596..70645 -> 88
        in 70646..70655 -> 103
        in 70656..70705 -> 106
        in 70656..71055 -> 107
        in 71056..73335 -> 108
        else -> -1 // Unknown or unsupported band
    }
}

fun formatCQI(cqi: Int): String{
    return if (cqi == 2147483647) {
        "n/a"
    } else {
        "$cqi"
    }
}

fun formatRSSNR(rssnr: Int): String{
    return if (rssnr == 0) {
        "n/a"
    } else {
        "$rssnr"
    }
}

fun formatTimingAdvance(timingAdvance: Int): String {
    return if (timingAdvance == 1282) {
        "n/a"
    } else {
        val distanceMeters = round((4800000000 * 1/30720000 * timingAdvance) / 2.0).toInt()
        val distanceFeet = round(distanceMeters * 3.28084).toInt()
        "~$distanceMeters m ($distanceFeet ft)"
    }
}

fun formatCellID(cellid: Int): String {
    return if (cellid == 268435455) {
        "n/a"
    } else {
        "$cellid"
    }
}

fun calculateCellSectorId(eci: String): String {
    return if (eci == "n/a") {
        "n/a"
    } else {
        val sectorId = (eci.toInt() % 256).toString()
        return sectorId
    }
}

fun calculateENBId(eci: String): String {
    return if (eci == "n/a") {
        "n/a"
    } else {
        val eNodeBId = (eci.toInt() / 256).toString()
        return eNodeBId
    }
}

@Preview(showBackground = true)
@Composable
fun CellInfoScreenPreview() {
    MyApplicationTheme {
        CellInfoScreen(context = LocalContext.current)
    }
}
