package io.github.bubbajuice.cellinfo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.room.*
import io.github.bubbajuice.cellinfo.ui.theme.MyApplicationTheme
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.burnoutcrew.reorderable.*
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Entity(tableName = "logged_cells")
data class LoggedCell(
    @PrimaryKey val cellId: String,
    val type: String,
    var timestamp: Long,
    val enbId: String?,
    val earfcn: String?,
    val pci: String?,
    val cellSector: String?,
    val bandNumber: String?,
    val tac: String?,
    val mcc: String?,
    val mnc: String?,
    val operator: String?,
    var rsrp: Int?,
    var latitude: Double?,
    var longitude: Double?,
    var bestRsrp: Int?,
    var bestLatitude: Double?,
    var bestLongitude: Double?
)

class CellLoggingService : Service() {
    private lateinit var cellDatabase: CellDatabase
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var locationManager: LocationManager
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var loggingJob: Job? = null
    private val notifiedCells = mutableSetOf<String>()

    // Don't delete save for feature to clear database
    private fun clearDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            cellDatabase.clearAllTables()
        }
    }

    override fun onCreate() {
        super.onCreate()
        cellDatabase = CellDatabase.getInstance(applicationContext)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        settingsViewModel = SettingsViewModel(application)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startLogging()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLogging()
    }

    private fun startLogging() {
        loggingJob = serviceScope.launch {
            while (isActive) {
                logCells()
                delay(1000) // Log every second
            }
        }
    }

    private fun stopLogging() {
        loggingJob?.cancel()
        serviceScope.cancel()
    }

    private suspend fun logCells() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val cellInfoList = withContext(Dispatchers.Default) {
                telephonyManager.allCellInfo
            }
            val location = getLastKnownLocation()
            cellInfoList.forEach { cellInfo ->
                when (cellInfo) {
                    is CellInfoNr -> logNrCell(cellInfo, location)
                    is CellInfoLte -> logLteCell(cellInfo, location)
                }
            }
        }
    }

    private fun logNrCell(cellInfo: CellInfoNr, location: Location?) {
        val cellIdentity = cellInfo.cellIdentity as? CellIdentityNr ?: return
        val cellSignalStrength = cellInfo.cellSignalStrength as? CellSignalStrengthNr
        val cellId = cellIdentity.nci.toString()
        logCell(
            "NR",
            cellId,
            null, // eNB ID not applicable for NR
            cellIdentity.nrarfcn.toString(),
            cellIdentity.pci.toString(),
            null, // Cell sector not applicable for NR
            getNrBandFromArfcn(cellIdentity.nrarfcn).toString(),
            cellIdentity.tac.toString(),
            cellIdentity.mccString,
            cellIdentity.mncString,
            cellIdentity.operatorAlphaLong?.toString(),
            cellSignalStrength?.ssRsrp,
            location?.latitude,
            location?.longitude
        )
    }

    private fun logLteCell(cellInfo: CellInfoLte, location: Location?) {
        val cellIdentity = cellInfo.cellIdentity
        val cellSignalStrength = cellInfo.cellSignalStrength
        val cellId = cellIdentity.ci.toString()
        logCell(
            "LTE",
            cellId,
            calculateENBId(cellId),
            cellIdentity.earfcn.toString(),
            cellIdentity.pci.toString(),
            calculateCellSectorId(cellId),
            getLTEBandFromEArfcn(cellIdentity.earfcn).toString(),
            cellIdentity.tac.toString(),
            cellIdentity.mccString,
            cellIdentity.mncString,
            cellIdentity.operatorAlphaLong?.toString(),
            cellSignalStrength.rsrp,
            location?.latitude,
            location?.longitude
        )
    }

    private fun logCell(
        type: String,
        cellId: String,
        enbId: String?,
        earfcn: String?,
        pci: String?,
        cellSector: String?,
        bandNumber: String?,
        tac: String?,
        mcc: String?,
        mnc: String?,
        operator: String?,
        rsrp: Int?,
        latitude: Double?,
        longitude: Double?
    ) {
        serviceScope.launch(Dispatchers.IO) {
            // Check if the cell already exists in the database
            val existingCell = cellDatabase.cellDao().getCellById(cellId)

            val loggedCell = if (existingCell == null) {
                // If the cell doesn't exist, create a new LoggedCell with current values as best values
                LoggedCell(
                    cellId = cellId,
                    type = type,
                    timestamp = System.currentTimeMillis(),
                    enbId = enbId,
                    earfcn = earfcn,
                    pci = pci,
                    cellSector = cellSector,
                    bandNumber = bandNumber,
                    tac = tac,
                    mcc = mcc,
                    mnc = mnc,
                    operator = operator,
                    rsrp = rsrp,
                    latitude = latitude,
                    longitude = longitude,
                    bestRsrp = rsrp,
                    bestLatitude = latitude,
                    bestLongitude = longitude
                ).also { cellDatabase.cellDao().insert(it) }
            } else {
                // If the cell exists, update its current values
                existingCell.timestamp = System.currentTimeMillis()
                existingCell.rsrp = rsrp
                existingCell.latitude = latitude
                existingCell.longitude = longitude

                // Update best RSRP if the current RSRP is better (or if best RSRP is null)
                if (rsrp != null && (existingCell.bestRsrp == null || rsrp > existingCell.bestRsrp!!)) {
                    existingCell.bestRsrp = rsrp
                }

                // Update best location if there was no previous best location
                if (existingCell.bestLatitude == null && existingCell.bestLongitude == null && latitude != null && longitude != null) {
                    existingCell.bestLatitude = latitude
                    existingCell.bestLongitude = longitude
                }

                cellDatabase.cellDao().update(existingCell)
                existingCell
            }

            checkForNewCell(loggedCell)
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val location = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                bestLocation = location
            }
        }
        return bestLocation
    }

    private fun checkForNewCell(loggedCell: LoggedCell) {
        serviceScope.launch(Dispatchers.IO) {
            val isNewCell = cellDatabase.cellDao().getCellCount(loggedCell.cellId) == 1
            if (isNewCell && !notifiedCells.contains(loggedCell.cellId)) {
                withContext(Dispatchers.Main) {
                    showNewCellNotification(loggedCell)
                }
                notifiedCells.add(loggedCell.cellId)
            }
        }
    }

    private fun showNewCellNotification(loggedCell: LoggedCell) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("New Cell Detected")
            .setContentText("${loggedCell.type} cell with ID ${loggedCell.cellId}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NEW_CELL_NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cell Logging Service")
            .setContentText("Logging cells in the background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "CellLoggingChannel"
        private const val NOTIFICATION_ID = 1
        private const val NEW_CELL_NOTIFICATION_ID = 2
    }
}

@Dao
interface CellDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cell: LoggedCell)

    @Update
    fun update(cell: LoggedCell)

    @Query("SELECT * FROM logged_cells WHERE cellId = :cellId")
    fun getCellById(cellId: String): LoggedCell?

    @Query("SELECT COUNT(*) FROM logged_cells WHERE cellId = :cellId")
    fun getCellCount(cellId: String): Int

    @Query("SELECT * FROM logged_cells ORDER BY timestamp DESC")
    fun getAllCells(): Flow<List<LoggedCell>>
}

@Database(entities = [LoggedCell::class], version = 2, exportSchema = false)
abstract class CellDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao

    companion object {
        private var instance: CellDatabase? = null

        fun getInstance(context: Context): CellDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): CellDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CellDatabase::class.java,
                "cell_database"
            )
                .fallbackToDestructiveMigration() // This will recreate the database if the version changes
                .build()
        }
    }
}

@Composable
fun LogPage(cellDatabase: CellDatabase, innerPadding: PaddingValues) {
    val cells by cellDatabase.cellDao().getAllCells().collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    val cellsToShow by remember {
        derivedStateOf { cells.filter { it.cellId != "268435455" && it.cellId != "2147483647" } }    }

    LazyColumn(
        state = listState,
        contentPadding = innerPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        items(cellsToShow, key = { it.cellId }) { cell ->
            LoggedCellItem(cell)
        }
    }
}

@Composable
fun LoggedCellItem(cell: LoggedCell) {
    // Skip cells with cellId 268435455 or 2147483647
    if (cell.cellId == "268435455" || cell.cellId == "2147483647") {
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("${cell.type} Cell", style = MaterialTheme.typography.titleMedium)
            Text("Cell ID: ${cell.cellId}", style = MaterialTheme.typography.bodyMedium)
            Text("eNB ID: ${cell.enbId ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("EARFCN: ${cell.earfcn ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("PCI: ${cell.pci ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("Cell Sector: ${cell.cellSector ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("Band Number: ${cell.bandNumber ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("TAC: ${cell.tac ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("MCC: ${cell.mcc ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("MNC: ${cell.mnc ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text("Operator: ${cell.operator ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Logged: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(cell.timestamp))}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Serializable
data class CellComponent(
    val id: String,
    val label: String,
    var enabled: Boolean = true,
    var order: Int
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("CellInfoSettings", Context.MODE_PRIVATE)

    private val _nrComponents = MutableStateFlow<List<CellComponent>>(emptyList())
    val nrComponents = _nrComponents.asStateFlow()

    private val _lteComponents = MutableStateFlow<List<CellComponent>>(emptyList())
    val lteComponents = _lteComponents.asStateFlow()

    private val _nrCompressedComponents = MutableStateFlow<List<CellComponent>>(emptyList())
    val nrCompressedComponents = _nrCompressedComponents.asStateFlow()

    private val _lteCompressedComponents = MutableStateFlow<List<CellComponent>>(emptyList())
    val lteCompressedComponents = _lteCompressedComponents.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _nrComponents.value = loadComponentList("NR")
            _lteComponents.value = loadComponentList("LTE")
            _nrCompressedComponents.value = loadComponentList("NR_Compressed")
            _lteCompressedComponents.value = loadComponentList("LTE_Compressed")
        }
    }

    private fun loadComponentList(key: String): List<CellComponent> {
        val json = sharedPreferences.getString(key, null)
        return if (json != null) {
            Json.decodeFromString(json)
        } else {
            getDefaultComponentList(key)
        }
    }

    fun resetToDefault(type: String) {
        viewModelScope.launch {
            val defaultComponents = getDefaultComponentList(type)
            when (type) {
                "NR" -> _nrComponents.value = defaultComponents
                "LTE" -> _lteComponents.value = defaultComponents
                "NR_Compressed" -> _nrCompressedComponents.value = defaultComponents
                "LTE_Compressed" -> _lteCompressedComponents.value = defaultComponents
            }
            saveComponentList(type, defaultComponents)
        }
    }

    private fun getDefaultComponentList(key: String): List<CellComponent> {
        return when (key) {
            "NR_Compressed" -> listOf(
                CellComponent("ssRSRP", "ssRSRP", true, 0),
                CellComponent("ARFCN", "ARFCN", true, 1),
                CellComponent("Band", "Band", true, 2),
                CellComponent("Data", "Data", false, 3)
                // Add more NR compressed components as needed
            )

            "NR" -> listOf(
                CellComponent("ssRSRP", "ssRSRP", true, 0),
                CellComponent("ARFCN", "ARFCN", true, 1),
                CellComponent("Band", "Band", true, 2),
                CellComponent("Data", "Data", false, 3)
                // Add more NR components as needed
            )

            "LTE_Compressed" -> listOf(
                CellComponent("eNB ID", "eNB ID", true, 0),
                CellComponent("Cell Sector ID", "Cell Sector ID", true, 1),
                CellComponent("Band Number", "Band Number", true, 2),
                CellComponent("RSRP", "RSRP", true, 3),
                CellComponent("RSRQ", "RSRQ", true, 4),
                CellComponent("Timing Advance", "Timing Advance", false, 5),
                CellComponent("Cell ID", "Cell ID", false, 6),
                CellComponent("PCI", "PCI", false, 7),
                CellComponent("EARFCN", "EARFCN", false, 8),
                CellComponent("Bandwidth", "Bandwidth", false, 9),
                CellComponent("TAC", "TAC", false, 10),
                CellComponent("MCC", "MCC", false, 11),
                CellComponent("MNC", "MNC", false, 12),
                CellComponent("RSSI", "RSSI", false, 13),
                CellComponent("RSSNR", "RSSNR", false, 14),
                CellComponent("CQI", "CQI", false, 15),
                CellComponent("Operator", "Operator", false, 16),
                CellComponent("Operator Abbreviation", "Operator Abbreviation", false, 17),
                CellComponent("Data", "Data", false, 18)
                // Add more LTE compressed components as needed
            )

            "LTE" -> listOf(
                CellComponent("eNB ID", "eNB ID", true, 0),
                CellComponent("Cell Sector ID", "Cell Sector ID", true, 1),
                CellComponent("Band Number", "Band Number", true, 2),
                CellComponent("RSRP", "RSRP", true, 3),
                CellComponent("RSRQ", "RSRQ", true, 4),
                CellComponent("Timing Advance", "Timing Advance", true, 5),
                CellComponent("Cell ID", "Cell ID", true, 6),
                CellComponent("PCI", "PCI", true, 7),
                CellComponent("EARFCN", "EARFCN", true, 8),
                CellComponent("Bandwidth", "Bandwidth", true, 9),
                CellComponent("TAC", "TAC", true, 10),
                CellComponent("MCC", "MCC", true, 11),
                CellComponent("MNC", "MNC", true, 12),
                CellComponent("RSSI", "RSSI", true, 13),
                CellComponent("RSSNR", "RSSNR", true, 14),
                CellComponent("CQI", "CQI", true, 15),
                CellComponent("Operator", "Operator", true, 16),
                CellComponent("Operator Abbreviation", "Operator Abbreviation", true, 17),
                CellComponent("Data", "Data", false, 18)
                // Add more LTE components as needed
            )
            else -> emptyList()
        }
    }

    private fun saveComponentList(key: String, components: List<CellComponent>) {
        val json = Json.encodeToString(components)
        sharedPreferences.edit().putString(key, json).apply()
    }

    fun updateComponentOrder(type: String, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val components = when (type) {
                "NR" -> _nrComponents
                "LTE" -> _lteComponents
                "NR_Compressed" -> _nrCompressedComponents
                "LTE_Compressed" -> _lteCompressedComponents
                else -> return@launch
            }

            val updatedList = components.value.toMutableList()
            val movedItem = updatedList.removeAt(fromIndex)
            updatedList.add(toIndex, movedItem)
            updatedList.forEachIndexed { index, component -> component.order = index }
            components.value = updatedList
            saveComponentList(type, updatedList)
        }
    }

    fun toggleComponentEnabled(type: String, componentId: String) {
        viewModelScope.launch {
            val components = when (type) {
                "NR" -> _nrComponents
                "LTE" -> _lteComponents
                "NR_Compressed" -> _nrCompressedComponents
                "LTE_Compressed" -> _lteCompressedComponents
                else -> return@launch
            }

            val updatedList = components.value.map { component ->
                if (component.id == componentId) {
                    component.copy(enabled = !component.enabled)
                } else {
                    component
                }
            }
            components.value = updatedList
            saveComponentList(type, updatedList)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("NR Compressed", "NR", "LTE Compressed", "LTE")

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ComponentListWithReset(
                    components = viewModel.nrCompressedComponents.collectAsState().value,
                    onOrderChanged = { from, to -> viewModel.updateComponentOrder("NR_Compressed", from, to) },
                    onToggleEnabled = { id -> viewModel.toggleComponentEnabled("NR_Compressed", id) },
                    onResetToDefault = { viewModel.resetToDefault("NR_Compressed") }
                )
                1 -> ComponentListWithReset(
                    components = viewModel.nrComponents.collectAsState().value,
                    onOrderChanged = { from, to -> viewModel.updateComponentOrder("NR", from, to) },
                    onToggleEnabled = { id -> viewModel.toggleComponentEnabled("NR", id) },
                    onResetToDefault = { viewModel.resetToDefault("NR") }
                )
                2 -> ComponentListWithReset(
                    components = viewModel.lteCompressedComponents.collectAsState().value,
                    onOrderChanged = { from, to -> viewModel.updateComponentOrder("LTE_Compressed", from, to) },
                    onToggleEnabled = { id -> viewModel.toggleComponentEnabled("LTE_Compressed", id) },
                    onResetToDefault = { viewModel.resetToDefault("LTE_Compressed") }
                )
                3 -> ComponentListWithReset(
                    components = viewModel.lteComponents.collectAsState().value,
                    onOrderChanged = { from, to -> viewModel.updateComponentOrder("LTE", from, to) },
                    onToggleEnabled = { id -> viewModel.toggleComponentEnabled("LTE", id) },
                    onResetToDefault = { viewModel.resetToDefault("LTE") }
                )
            }
        }
    }
}

@Composable
fun ComponentListWithReset(
    components: List<CellComponent>,
    onOrderChanged: (Int, Int) -> Unit,
    onToggleEnabled: (String) -> Unit,
    onResetToDefault: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = LocalConfiguration.current.screenHeightDp.dp * 0.1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp) // Space for the button
        ) {
            ComponentList(
                components = components,
                onOrderChanged = onOrderChanged,
                onToggleEnabled = onToggleEnabled
            )
        }

        Button(
            onClick = onResetToDefault,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("Reset to Default")
        }
    }
}

@Composable
fun ComponentList(
    components: List<CellComponent>,
    onOrderChanged: (Int, Int) -> Unit,
    onToggleEnabled: (String) -> Unit
) {
    val state = rememberReorderableLazyListState(onMove = { from, to ->
        onOrderChanged(from.index, to.index)
    })

    LazyColumn(
        state = state.listState,
        modifier = Modifier.reorderable(state)
    ) {
        itemsIndexed(components, { _, item -> item.id }) { index, item ->
            ReorderableItem(state, key = item.id) { isDragging ->
                val elevation = if (isDragging) 16.dp else 0.dp
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .then(if (index == components.lastIndex) Modifier.padding(bottom = 16.dp) else Modifier),
                    elevation = CardDefaults.cardElevation(elevation)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.enabled,
                            onCheckedChange = { onToggleEnabled(item.id) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(item.label, modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Drag handle",
                            modifier = Modifier.detectReorder(state)
                        )
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private lateinit var cellDatabase: CellDatabase
    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cellDatabase = CellDatabase.getInstance(applicationContext)

        createNotificationChannel()
        startCellLoggingService()

        setContent {
            MyApplicationTheme {
                var showPermissionDialog by remember { mutableStateOf(false) }
                var currentPage by remember { mutableStateOf("Live") }
                var selectedSettingsTab by remember { mutableIntStateOf(0) }

                if (!hasPermissions() || !hasNotificationPermission()) {
                    requestPermissions.launch(permissions)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        BottomNavBar(
                            currentPage = currentPage,
                            onPageChange = { page -> currentPage = page }
                        )
                    }
                ) { innerPadding ->
                    when (currentPage) {
                        "Map" -> {
                            // Placeholder for Map page
                            Text("Map page (not yet implemented)", modifier = Modifier.padding(innerPadding))
                        }
                        "Log" -> {
                            LogPage(cellDatabase, innerPadding)                        }
                        "Live" -> {
                            CellInfoScreen(
                                context = this,
                                viewModel = settingsViewModel,
                                cellDatabase,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        "Settings" -> {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                selectedTab = selectedSettingsTab,
                                onTabSelected = { selectedSettingsTab = it }
                            )
                        }
                    }
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

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission not required for Android versions below 13
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CellLoggingService.CHANNEL_ID,
            "Cell Logging Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun startCellLoggingService() {
        val serviceIntent = Intent(this, CellLoggingService::class.java)
        startForegroundService(serviceIntent)
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
        } && hasNotificationPermission()
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
fun BottomNavBar(
    currentPage: String,
    onPageChange: (String) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.LocationOn, contentDescription = "Map") },
            label = { Text("Map") },
            selected = currentPage == "Map",
            onClick = { onPageChange("Map") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Log") },
            label = { Text("Log") },
            selected = currentPage == "Log",
            onClick = { onPageChange("Log") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Call, contentDescription = "Live") },
            label = { Text("Live") },
            selected = currentPage == "Live",
            onClick = { onPageChange("Live") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentPage == "Settings",
            onClick = { onPageChange("Settings") }
        )
    }
}

@Composable
fun CellInfoScreen(context: Context, viewModel: SettingsViewModel, cellDatabase: CellDatabase, modifier: Modifier = Modifier) {
    var cellInfoList by remember { mutableStateOf<List<CellInfo>>(emptyList()) }
    val expandedCells = remember { mutableStateOf<Map<Any, Boolean>>(emptyMap()) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }

    val nrComponents by viewModel.nrComponents.collectAsState()
    val lteComponents by viewModel.lteComponents.collectAsState()
    val nrCompressedComponents by viewModel.nrCompressedComponents.collectAsState()
    val lteCompressedComponents by viewModel.lteCompressedComponents.collectAsState()

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
                currentLocation = getLastKnownLocation(context)
            }
            delay(1000)
        }
    }

    CellInfoList(
        cellInfoList = cellInfoList,
        currentLocation = currentLocation,
        cellDatabase = cellDatabase,
        expandedCells = expandedCells.value,
        onCellExpandChange = { cellId, isExpanded ->
            expandedCells.value = expandedCells.value.toMutableMap().apply {
                this[cellId] = isExpanded
            }
        },
        nrComponents = nrComponents,
        lteComponents = lteComponents,
        nrCompressedComponents = nrCompressedComponents,
        lteCompressedComponents = lteCompressedComponents,
        modifier = modifier
    )
}

fun getLastKnownLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }

    val providers = locationManager.getProviders(true)
    var bestLocation: Location? = null
    for (provider in providers) {
        val location = locationManager.getLastKnownLocation(provider) ?: continue
        if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
            bestLocation = location
        }
    }
    return bestLocation
}

suspend fun findMatchingLoggedCell(
    cellDatabase: CellDatabase,
    earfcn: Int,
    pci: Int,
    currentLocation: Location?
): LoggedCell? {
    val loggedCells = cellDatabase.cellDao().getAllCells().first()
    return loggedCells.find { loggedCell ->
        loggedCell.earfcn == earfcn.toString() &&
                loggedCell.pci == pci.toString() &&
                currentLocation != null &&
                loggedCell.bestLatitude != null &&
                loggedCell.bestLongitude != null &&
                calculateDistance(
                    currentLocation.latitude, currentLocation.longitude,
                    loggedCell.bestLatitude!!, loggedCell.bestLongitude!!
                ) <= 20 * 1609.34 // 20 miles in meters
    }
}

fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371e3 // Earth's radius in meters
    val w = lat1 * Math.PI / 180
    val x = lat2 * Math.PI / 180
    val y = (lat2 - lat1) * Math.PI / 180
    val z = (lon2 - lon1) * Math.PI / 180

    val a = sin(y / 2) * sin(y / 2) +
            cos(w) * cos(x) *
            sin(z / 2) * sin(z / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return r * c
}

@Composable
fun CellInfoList(
    cellInfoList: List<CellInfo>,
    currentLocation: Location?,
    cellDatabase: CellDatabase,
    expandedCells: Map<Any, Boolean>,
    onCellExpandChange: (Any, Boolean) -> Unit,
    nrComponents: List<CellComponent>,
    lteComponents: List<CellComponent>,
    nrCompressedComponents: List<CellComponent>,
    lteCompressedComponents: List<CellComponent>,
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
                            onExpandChange = { isExpanded -> onCellExpandChange(cellId, isExpanded) },
                            nrComponents = nrComponents,
                            lteComponents = lteComponents,
                            nrCompressedComponents = nrCompressedComponents,
                            lteCompressedComponents = lteCompressedComponents,
                            cellDatabase = cellDatabase,
                            currentLocation = currentLocation
                        )

                        if (cells.size > 1) {
                            val groupExpanded = expandedCells[type] ?: false
                            ExpandableSection(
                                expandedTitle = "Hide ${cells.size - 1} $type cells",
                                collapsedTitle = "Show ${cells.size - 1} more $type cells",
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
                                        },
                                        nrComponents = nrComponents,
                                        lteComponents = lteComponents,
                                        nrCompressedComponents = nrCompressedComponents,
                                        lteCompressedComponents = lteCompressedComponents,
                                        cellDatabase = cellDatabase,
                                        currentLocation = currentLocation
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
        is CellInfoLte -> "${cellInfo.cellIdentity.ci}-${cellInfo.cellIdentity.earfcn}-${cellInfo.cellIdentity.pci}"
        else -> cellInfo.hashCode()
    }
}

@Composable
fun CellInfoItem(
    cellInfo: CellInfo,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    nrComponents: List<CellComponent>,
    lteComponents: List<CellComponent>,
    nrCompressedComponents: List<CellComponent>,
    lteCompressedComponents: List<CellComponent>,
    cellDatabase: CellDatabase,
    currentLocation: Location?
) {
    var showDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    val cellInfoRows = when (cellInfo) {
        is CellInfoNr -> formatCellInfoNr(cellInfo, if (isExpanded) nrComponents else nrCompressedComponents)
        is CellInfoLte -> formatCellInfoLte(
            cellInfo,
            if (isExpanded) lteComponents else lteCompressedComponents,
            cellDatabase,
            currentLocation
        )
        else -> emptyList()
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

fun formatCellInfoNr(cellInfoNr: CellInfoNr, components: List<CellComponent>): List<Pair<String, String>> {
    val cellIdentity = cellInfoNr.cellIdentity as CellIdentityNr
    val cellSignalStrength = cellInfoNr.cellSignalStrength

    val band = getNrBandFromArfcn(cellIdentity.nrarfcn)

    val allComponents = listOf(
        "ssRSRP" to (cellSignalStrength.dbm.toString() + " dBm"),
        "ARFCN" to cellIdentity.nrarfcn.toString(),
        "Band Number" to "n$band",
        "Data" to (cellSignalStrength.toString() + cellIdentity.toString())
    )

    return components
        .filter { it.enabled }
        .sortedBy { it.order }
        .mapNotNull { component ->
            allComponents.find { it.first == component.id }?.let { it.first to it.second }
        }
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

fun formatCellInfoLte(
    cellInfoLte: CellInfoLte,
    components: List<CellComponent>,
    cellDatabase: CellDatabase,
    currentLocation: Location?
): List<Pair<String, String>> = runBlocking {
    val cellIdentity = cellInfoLte.cellIdentity
    val cellSignalStrength = cellInfoLte.cellSignalStrength

    val cellId = cellIdentity.ci
    val isInvalidCellId = cellId == 268435455 || cellId == 2147483647

    val matchingLoggedCell = if (isInvalidCellId) {
        findMatchingLoggedCell(cellDatabase, cellIdentity.earfcn, cellIdentity.pci, currentLocation)
    } else null

    val eNodeBId = calculateENBId(formatCellID(matchingLoggedCell?.cellId?.toInt() ?: cellId))
    val cellSectorId = calculateCellSectorId(formatCellID(matchingLoggedCell?.cellId?.toInt() ?: cellId))
    val timingAdvance = formatTimingAdvance(cellSignalStrength.timingAdvance)
    val cqi = formatCQI(cellSignalStrength.cqi)
    val rssnr = formatRSSNR(cellSignalStrength.rssnr)
    val cellid = if (isInvalidCellId) matchingLoggedCell?.cellId ?: "n/a" else formatCellID(cellId)
    val band = getLTEBandFromEArfcn(cellIdentity.earfcn)
    val bandwidth = formatBandwidth(cellIdentity.bandwidth)

    val allComponents = listOf(
        "eNB ID" to eNodeBId,
        "Cell Sector ID" to cellSectorId,
        "Band Number" to band.toString(),
        "RSRP" to (cellSignalStrength.rsrp.toString() + " dBm"),
        "RSRQ" to (cellSignalStrength.rsrq.toString() + " dB"),
        "Timing Advance" to timingAdvance,
        "Cell ID" to cellid,
        "PCI" to cellIdentity.pci.toString(),
        "EARFCN" to cellIdentity.earfcn.toString(),
        "Bandwidth" to bandwidth,
        "TAC" to (matchingLoggedCell?.tac ?: cellIdentity.tac.toString()),
        "MCC" to (matchingLoggedCell?.mcc ?: cellIdentity.mccString ?: ""),
        "MNC" to (matchingLoggedCell?.mnc ?: cellIdentity.mncString ?: ""),
        "RSSI" to (cellSignalStrength.rssi.toString() + " dBm"),
        "RSSNR" to "$rssnr dB",
        "CQI" to "$cqi dB",
        "Operator" to (matchingLoggedCell?.operator ?: cellIdentity.operatorAlphaLong?.toString() ?: ""),
        "Operator Abbreviation" to (cellIdentity.operatorAlphaShort?.toString() ?: ""),
        "Data" to (cellSignalStrength.toString() + cellIdentity.toString())
    )

    return@runBlocking components
        .filter { it.enabled }
        .sortedBy { it.order }
        .mapNotNull { component ->
            allComponents.find { it.first == component.id }?.let { it.first to it.second }
        }
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

fun formatBandwidth(bandwidth: Int): String{
    return if (bandwidth == 2147483647) {
        "n/a"
    } else {
        "$bandwidth"
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
    return if (cellid == 268435455 || cellid == 2147483647) {
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
