package com.example.vehicletimer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.os.LocaleListCompat
import com.example.vehicletimer.ui.theme.VehicleTimerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

data class RunningJob(
    val customerName: String,
    val customerAddress: String = "",
    val startTime: Long,
    val isPaused: Boolean = false,
    val lastPauseTime: Long = 0,
    val accumulatedPausedDuration: Long = 0
)

sealed class Screen {
    object Splash : Screen()
    object Onboarding : Screen()
    object InitialSetup : Screen()
    object List : Screen()
    data class AddVehicle(val vehicle: VehicleEntity? = null) : Screen()
    object SettingsHub : Screen()
    object CompanyDetails : Screen()
    object Language : Screen()
    object History : Screen()
    object Totals : Screen()
    object BackupRestore : Screen()
    data class Timer(val vehicle: VehicleEntity) : Screen()
    data class Summary(
        val vehicle: VehicleEntity,
        val customerName: String,
        val customerAddress: String,
        val startTime: Long,
        val endTime: Long,
        val elapsedMillis: Long,
        val amount: Double
    ) : Screen()
}

enum class AppTheme {
    LIGHT, DARK, SYSTEM
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { getSharedPreferences("settings", Context.MODE_PRIVATE) }
            var themePref by remember { 
                mutableStateOf(AppTheme.valueOf(prefs.getString("theme", AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name)) 
            }

            val darkTheme = when (themePref) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            VehicleTimerTheme(darkTheme = darkTheme) {
                AppRoot(
                    currentTheme = themePref,
                    isDark = darkTheme,
                    onThemeChange = { newTheme ->
                        themePref = newTheme
                        prefs.edit { putString("theme", newTheme.name) }
                    }
                )
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
}

fun formatRate(rate: Double): String {
    return if (rate == rate.toInt().toDouble()) {
        "₹${rate.toInt()}"
    } else {
        "₹${String.format(Locale.getDefault(), "%.2f", rate)}"
    }
}

fun calculateAmount(elapsedMillis: Long, ratePerHour: Double, rule: String): Double {
    val elapsedSeconds = elapsedMillis / 1000.0
    val roundedSeconds = when (rule) {
        "HALF_HOUR" -> {
            val halfHourInSeconds = 30 * 60.0
            ceil(elapsedSeconds / halfHourInSeconds) * halfHourInSeconds
        }
        "FULL_HOUR" -> {
            val fullHourInSeconds = 60 * 60.0
            ceil(elapsedSeconds / fullHourInSeconds) * fullHourInSeconds
        }
        else -> elapsedSeconds // PER_MINUTE
    }
    val hours = roundedSeconds / 3600.0
    return hours * ratePerHour
}

fun generateReceiptBitmap(
    companySettings: CompanySettingsEntity?,
    title: String,
    details: List<Pair<String, String>>,
    totalLabel: String,
    totalValue: String
): Bitmap {
    val width = 600
    val height = 950
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    // 1. Header Band
    val headerHeight = 220f
    val headerPaint = Paint().apply {
        color = "#0F6E56".toColorInt() // TimeDrive Teal
        style = Paint.Style.FILL
    }
    canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, headerPaint)

    val whitePaintBold = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val whitePaintSmall = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    var currentY = 70f
    companySettings?.let {
        if (it.businessName.isNotBlank()) {
            canvas.drawText(it.businessName, width / 2f, currentY, whitePaintBold)
            currentY += 55f
        }
        if (it.phoneNumber.isNotBlank()) {
            canvas.drawText(it.phoneNumber, width / 2f, currentY, whitePaintSmall)
            currentY += 40f
        }
        if (it.address.isNotBlank()) {
            canvas.drawText(it.address, width / 2f, currentY, whitePaintSmall)
        }
    }

    // 2. Body
    canvas.drawColor(android.graphics.Color.WHITE, PorterDuff.Mode.DST_OVER)

    val blackPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    val blackPaintBold = Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 36f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val grayPaint = Paint().apply {
        color = android.graphics.Color.GRAY
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val margin = 40f
    currentY = headerHeight + 50f

    // Subtitle / Date
    canvas.drawText(title, width / 2f, currentY, grayPaint)
    currentY += 40f

    // Separator
    val dashPaint = Paint().apply {
        color = android.graphics.Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }
    canvas.drawLine(margin, currentY, width - margin, currentY, dashPaint)
    currentY += 60f

    fun drawRow(label: String, value: String, y: Float) {
        canvas.drawText(label, margin, y, blackPaint)
        val valueWidth = blackPaint.measureText(value)
        canvas.drawText(value, width - margin - valueWidth, y, blackPaint)
    }

    details.forEach { (label, value) ->
        drawRow(label, value, currentY)
        currentY += 55f
    }

    currentY += 10f
    // Separator
    canvas.drawLine(margin, currentY, width - margin, currentY, dashPaint)
    currentY += 80f

    // Total
    canvas.drawText(totalLabel, margin, currentY, blackPaintBold)
    val totalWidth = blackPaintBold.measureText(totalValue)
    canvas.drawText(totalValue, width - margin - totalWidth, currentY, blackPaintBold)

    // Footer
    canvas.drawText("Thank you for your business", width / 2f, height - 60f, grayPaint)

    return bitmap
}

fun shareReceiptImage(
    context: android.content.Context,
    companySettings: CompanySettingsEntity?,
    title: String,
    details: List<Pair<String, String>>,
    totalLabel: String,
    totalValue: String
) {
    try {
        val bitmap = generateReceiptBitmap(companySettings, title, details, totalLabel, totalValue)
        val imagesFolder = File(context.cacheDir, "shared_images")
        imagesFolder.mkdirs()
        val file = File(imagesFolder, "receipt.png")
        val stream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.flush()
        stream.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share receipt via"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun downloadReceiptImage(
    context: Context,
    companySettings: CompanySettingsEntity?,
    title: String,
    details: List<Pair<String, String>>,
    totalLabel: String,
    totalValue: String
) {
    try {
        val bitmap = generateReceiptBitmap(companySettings, title, details, totalLabel, totalValue)
        val filename = "receipt_${System.currentTimeMillis()}.png"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/TimeDrive"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }

            Toast.makeText(context, context.getString(R.string.download_success), Toast.LENGTH_SHORT)
                .show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun exportJobsToCsv(context: Context, jobs: List<JobEntity>) {
    try {
        val sharedFolder = File(context.cacheDir, "shared_images")
        sharedFolder.mkdirs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val file = File(sharedFolder, "timedrive_history_$timestamp.csv")
        
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val csvHeader = "\"Vehicle\",\"Customer\",\"Address\",\"Date\",\"Duration\",\"Amount\"\n"
        val csvData = jobs.joinToString("") { job ->
            val vName = job.vehicleName.replace("\"", "\"\"")
            val cName = job.customerName.replace("\"", "\"\"")
            val cAddress = job.customerAddress.replace("\"", "\"\"")
            val date = dateFormatter.format(Date(job.endTime))
            val duration = formatDuration(job.endTime - job.startTime)
            val amount = String.format(Locale.getDefault(), "%.2f", job.amount)
            
            "\"$vName\",\"$cName\",\"$cAddress\",\"$date\",\"$duration\",\"$amount\"\n"
        }
        
        file.writeText(csvHeader + csvData)

        if (jobs.isEmpty()) {
            Toast.makeText(context, "No jobs to export. Created header-only CSV.", Toast.LENGTH_SHORT).show()
        }

        if (file.exists()) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Data"))
        } else {
            Toast.makeText(context, "Failed to create export file", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AppRoot(
    currentTheme: AppTheme,
    isDark: Boolean,
    onThemeChange: (AppTheme) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.vehicleDao()
    val settingsDao = database.companySettingsDao()
    val jobDao = database.jobDao()
    val vehicles by dao.getAllVehicles().collectAsState(initial = emptyList())
    val companySettings by settingsDao.getSettings().collectAsState(initial = null)
    val jobs by jobDao.getAllJobs().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val runningJobs = remember { mutableStateMapOf<VehicleEntity, RunningJob>() }
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (val current = screen) {
            is Screen.Splash -> AnimatedSplashScreen(
                isDark = isDark,
                onTimeout = { 
                    val onboardingCompleted = prefs.getBoolean("onboarding_completed", false)
                    val initialSetupCompleted = prefs.getBoolean("initial_setup_completed", false)
                    screen = when {
                        !onboardingCompleted -> Screen.Onboarding
                        !initialSetupCompleted -> Screen.InitialSetup
                        else -> Screen.List
                    }
                }
            )
            is Screen.Onboarding -> OnboardingScreen(
                modifier = Modifier.padding(innerPadding),
                isDark = isDark,
                onFinish = {
                    prefs.edit { putBoolean("onboarding_completed", true) }
                    val initialSetupCompleted = prefs.getBoolean("initial_setup_completed", false)
                    screen = if (initialSetupCompleted) Screen.List else Screen.InitialSetup
                }
            )
            is Screen.InitialSetup -> InitialSetupScreen(
                modifier = Modifier.padding(innerPadding),
                initialSettings = companySettings ?: CompanySettingsEntity(businessName = "", phoneNumber = "", address = ""),
                isDark = isDark,
                onContinue = { settings ->
                    scope.launch {
                        settingsDao.upsert(settings)
                        prefs.edit { putBoolean("initial_setup_completed", true) }
                        screen = Screen.List
                    }
                },
                onSkip = {
                    prefs.edit { putBoolean("initial_setup_completed", true) }
                    screen = Screen.List
                }
            )
            is Screen.List -> VehicleListScreen(
                modifier = Modifier.padding(innerPadding),
                vehicles = vehicles,
                runningJobs = runningJobs,
                currentTheme = currentTheme,
                isDark = isDark,
                onThemeChange = onThemeChange,
                onAddClick = { screen = Screen.AddVehicle() },
                onEditClick = { vehicle -> screen = Screen.AddVehicle(vehicle) },
                onDeleteClick = { vehicle ->
                    scope.launch {
                        dao.delete(vehicle)
                    }
                },
                onSettingsClick = { screen = Screen.SettingsHub },
                onHistoryClick = { screen = Screen.History },
                onTotalsClick = { screen = Screen.Totals },
                onExportClick = {
                    scope.launch {
                        val allJobs = jobDao.getAllJobsOnce()
                        exportJobsToCsv(context, allJobs)
                    }
                },
                onVehicleClick = { vehicle -> screen = Screen.Timer(vehicle) }
            )
            is Screen.AddVehicle -> {
                BackHandler { screen = Screen.List }
                AddVehicleScreen(
                    modifier = Modifier.padding(innerPadding),
                    editingVehicle = current.vehicle,
                    isDark = isDark,
                    onSave = { name, rate, rule, icon ->
                        scope.launch {
                            if (current.vehicle != null) {
                                dao.update(current.vehicle.copy(name = name, ratePerHour = rate, roundingRule = rule, icon = icon))
                            } else {
                                dao.insert(VehicleEntity(name = name, ratePerHour = rate, roundingRule = rule, icon = icon))
                            }
                        }
                        screen = Screen.List
                    },
                    onCancel = { screen = Screen.List }
                )
            }
            is Screen.SettingsHub -> {
                BackHandler { screen = Screen.List }
                SettingsHubScreen(
                    modifier = Modifier.padding(innerPadding),
                    companySettings = companySettings ?: CompanySettingsEntity(),
                    isDark = isDark,
                    onCompanyDetailsClick = { screen = Screen.CompanyDetails },
                    onLanguageClick = { screen = Screen.Language },
                    onBackupRestoreClick = { screen = Screen.BackupRestore },
                    onBack = { screen = Screen.List }
                )
            }
            is Screen.BackupRestore -> {
                BackHandler { screen = Screen.SettingsHub }
                BackupRestoreScreen(
                    modifier = Modifier.padding(innerPadding),
                    isDark = isDark,
                    onBack = { screen = Screen.SettingsHub }
                )
            }
            is Screen.CompanyDetails -> {
                BackHandler { screen = Screen.SettingsHub }
                CompanyDetailsScreen(
                    modifier = Modifier.padding(innerPadding),
                    initialSettings = companySettings ?: CompanySettingsEntity(),
                    isDark = isDark,
                    onSave = { settings ->
                        scope.launch {
                            settingsDao.upsert(settings)
                        }
                        screen = Screen.SettingsHub
                    },
                    onCancel = { screen = Screen.SettingsHub }
                )
            }
            is Screen.Language -> {
                BackHandler { screen = Screen.SettingsHub }
                LanguageScreen(
                    modifier = Modifier.padding(innerPadding),
                    isDark = isDark,
                    onLanguageSelected = { 
                        screen = Screen.SettingsHub
                    },
                    onBack = { screen = Screen.SettingsHub }
                )
            }
            is Screen.History -> {
                BackHandler { screen = Screen.List }
                HistoryScreen(
                    modifier = Modifier.padding(innerPadding),
                    jobs = jobs,
                    companySettings = companySettings,
                    isDark = isDark,
                    onUpdateStatus = { id, status ->
                        scope.launch {
                            jobDao.updatePaymentStatus(id, status)
                        }
                    },
                    onBack = { screen = Screen.List }
                )
            }
            is Screen.Totals -> {
                BackHandler { screen = Screen.List }
                TotalsScreen(
                    modifier = Modifier.padding(innerPadding),
                    jobs = jobs,
                    companySettings = companySettings,
                    isDark = isDark,
                    onBack = { screen = Screen.List }
                )
            }
            is Screen.Timer -> {
                BackHandler { screen = Screen.List }
                TimerScreen(
                    modifier = Modifier.padding(innerPadding),
                    vehicle = current.vehicle,
                    runningJob = runningJobs[current.vehicle],
                    isDark = isDark,
                    onStart = { customerName, address ->
                        runningJobs[current.vehicle] = RunningJob(
                            customerName = customerName,
                            customerAddress = address,
                            startTime = System.currentTimeMillis()
                        )
                    },
                    onPauseToggle = {
                        val job = runningJobs[current.vehicle]
                        if (job != null) {
                            val now = System.currentTimeMillis()
                            if (job.isPaused) {
                                // Resume
                                val pausedDuration = now - job.lastPauseTime
                                runningJobs[current.vehicle] = job.copy(
                                    isPaused = false,
                                    accumulatedPausedDuration = job.accumulatedPausedDuration + pausedDuration
                                )
                            } else {
                                // Pause
                                runningJobs[current.vehicle] = job.copy(
                                    isPaused = true,
                                    lastPauseTime = now
                                )
                            }
                        }
                    },
                    onStop = {
                        val job = runningJobs[current.vehicle]
                        if (job != null) {
                            val endTime = System.currentTimeMillis()
                            val totalElapsed = endTime - job.startTime
                            val currentPause = if (job.isPaused) endTime - job.lastPauseTime else 0
                            val netElapsed = totalElapsed - (job.accumulatedPausedDuration + currentPause)
                            
                            val amount = calculateAmount(netElapsed, current.vehicle.ratePerHour, current.vehicle.roundingRule)
                            
                            runningJobs.remove(current.vehicle)
                            screen = Screen.Summary(
                                vehicle = current.vehicle,
                                customerName = job.customerName,
                                customerAddress = job.customerAddress,
                                startTime = job.startTime,
                                endTime = endTime,
                                elapsedMillis = netElapsed,
                                amount = amount
                            )
                        }
                    },
                    onBack = { screen = Screen.List }
                )
            }
            is Screen.Summary -> {
                SummaryScreen(
                    modifier = Modifier.padding(innerPadding),
                    vehicle = current.vehicle,
                    customerName = current.customerName,
                    customerAddress = current.customerAddress,
                    elapsedMillis = current.elapsedMillis,
                    amount = current.amount,
                    companySettings = companySettings,
                    isDark = isDark,
                    onDone = { status ->
                        scope.launch {
                            jobDao.insert(
                                JobEntity(
                                    vehicleName = current.vehicle.name,
                                    customerName = current.customerName,
                                    customerAddress = current.customerAddress,
                                    startTime = current.startTime,
                                    endTime = current.endTime,
                                    amount = current.amount,
                                    ratePerHour = current.vehicle.ratePerHour,
                                    paymentStatus = status
                                )
                            )
                        }
                        screen = Screen.List
                    }
                )
            }
        }
    }
}

@Composable
fun AnimatedSplashScreen(isDark: Boolean, onTimeout: () -> Unit) {
    val density = LocalDensity.current.density
    val pinOffset = remember { Animatable(-50f) }
    val tdAlpha = remember { Animatable(0f) }
    val tdScale = remember { Animatable(0.85f) }
    val handRotation = remember { Animatable(0f) }
    val sweepAngle = remember { Animatable(0f) }
    val beamAlpha = remember { Animatable(0f) }
    val trailsHeight = remember { Animatable(0f) }
    val ripplesAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 1. Clean Background (0.0s - 0.4s)
        delay(400)

        // 2. Location Pin Drops (0.4s - 0.8s)
        launch { beamAlpha.animateTo(0.4f, tween(200)) }
        launch {
            pinOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)
            )
            beamAlpha.animateTo(0f, tween(200))
        }
        delay(400)

        // 3. TD Appears (0.8s - 1.4s)
        launch { tdAlpha.animateTo(1f, tween(400)) }
        launch { tdScale.animateTo(1f, tween(400, easing = EaseOut)) }
        launch { trailsHeight.animateTo(1f, tween(500, easing = LinearOutSlowInEasing)) }
        launch { ripplesAlpha.animateTo(0.7f, tween(600)) }
        delay(600)

        // 4. Timer Activates (1.4s - 1.8s)
        launch { sweepAngle.animateTo(360f, tween(400, easing = FastOutSlowInEasing)) }
        launch { handRotation.animateTo(50f, tween(400, easing = LinearOutSlowInEasing)) }
        delay(400)

        // 5. Ready (1.8s)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F6E56)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Dimensions
            val pinWidthDp = 62f
            val pinHeightDp = 80f
            val spacingDp = 2f
            val tdWidthDp = 220f
            val tdHeightDp = 90f
            val strokeThicknessDp = 13f

            val scale = (canvasWidth * 0.85f / (tdWidthDp * density)).coerceAtMost(1f) * density
            val pinWidth = pinWidthDp * scale
            val pinHeight = pinHeightDp * scale
            val spacing = spacingDp * scale
            val tdWidth = tdWidthDp * scale
            val tdHeight = tdHeightDp * scale
            val totalH = pinHeight + spacing + tdHeight
            val tThickness = strokeThicknessDp * scale

            val centerX = canvasWidth / 2f
            val centerY = canvasHeight / 2f
            val logoTop = centerY - totalH / 2f

            // 0. Base Ripples (Cyan)
            if (ripplesAlpha.value > 0.01f) {
                val rippleY = logoTop + totalH - 4f * scale
                val color = Color(0xFF00E5FF).copy(alpha = ripplesAlpha.value)
                drawOval(color, Offset(centerX - tdWidth * 0.7f, rippleY - 12f * scale), Size(tdWidth * 1.4f, 24f * scale), style = Stroke(2f * scale))
                drawOval(color.copy(alpha = ripplesAlpha.value * 0.4f), Offset(centerX - tdWidth * 0.9f, rippleY - 20f * scale), Size(tdWidth * 1.8f, 40f * scale), style = Stroke(1.5f * scale))
            }

            // 1. Light Beam Effect (Above Pin)
            if (beamAlpha.value > 0.01f) {
                val beamWidth = pinWidth * 0.4f
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF00E5FF).copy(alpha = beamAlpha.value))),
                    topLeft = Offset(centerX - beamWidth / 2f, 0f),
                    size = Size(beamWidth, logoTop + pinHeight / 2f)
                )
            }

            // 2. Gold Pin
            val pinTopY = logoTop + (pinOffset.value * scale)
            val pinRadius = pinWidth / 2f
            val pinPointY = pinTopY + pinHeight
            val pinPath = Path().apply {
                arcTo(Rect(centerX - pinRadius, pinTopY, centerX + pinRadius, pinTopY + pinWidth), 180f, 180f, false)
                cubicTo(centerX + pinRadius, pinTopY + pinRadius * 1.5f, centerX + pinRadius * 0.2f, pinPointY, centerX, pinPointY)
                cubicTo(centerX - pinRadius * 0.2f, pinPointY, centerX - pinRadius, pinTopY + pinRadius * 1.5f, centerX - pinRadius, pinTopY + pinRadius)
                close()
            }
            drawPath(pinPath, Color(0xFFFAC775))
            drawCircle(Color(0xFF0F6E56), radius = pinWidth * 0.16f, center = Offset(centerX, pinTopY + pinRadius))

            // 3. Monogram (TD)
            withTransform({
                translate(left = centerX - tdWidth / 2f, top = logoTop + pinHeight + spacing)
                scale(tdScale.value, tdScale.value, Offset(tdWidth / 2f, tdHeight / 2f))
            }) {
                val alpha = tdAlpha.value
                val tW = tdWidth * 0.45f
                val dW = tdWidth * 0.52f
                val overlap = tdWidth * 0.05f

                // Landing Trails
                if (trailsHeight.value > 0.01f && trailsHeight.value < 0.99f) {
                    val trailBrush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent))
                    drawRect(trailBrush, Offset(0f, tdHeight * 0.5f), Size(tW, tdHeight * trailsHeight.value))
                    drawRect(trailBrush, Offset(tW - overlap, tdHeight * 0.5f), Size(dW, tdHeight * trailsHeight.value))
                }

                // T
                drawRect(Color.White.copy(alpha = alpha), Offset(0f, 0f), Size(tW, tThickness))
                drawRect(Color.White.copy(alpha = alpha), Offset(tW / 2f - tThickness / 2f, 0f), Size(tThickness, tdHeight))

                // D
                val dStartX = tW - overlap
                val dPath = Path().apply {
                    val r = tdHeight / 2f
                    fillType = PathFillType.EvenOdd
                    moveTo(dStartX, 0f); lineTo(dStartX + dW - r, 0f); arcTo(Rect(dStartX + dW - r * 2f, 0f, dStartX + dW, tdHeight), -90f, 180f, false); lineTo(dStartX, tdHeight); close()
                    val iR = r - tThickness; val iS = dStartX + tThickness; val iW = dW - tThickness * 2f
                    moveTo(iS, tThickness); lineTo(iS + iW - iR, tThickness); arcTo(Rect(iS + iW - iR * 2f, tThickness, iS + iW, tdHeight - tThickness), -90f, 180f, false); lineTo(iS, tdHeight - tThickness); close()
                }
                drawPath(dPath, Color.White.copy(alpha = alpha))

                // Timer Details
                if (alpha > 0.1f) {
                    val tmX = dStartX + dW * 0.48f; val tmY = tdHeight / 2f; val tmR = (tdHeight / 2f) - tThickness - (3f * scale)
                    // Sweep Effect
                    if (sweepAngle.value > 0f) {
                        drawArc(Color(0xFF00E5FF).copy(alpha = 0.4f), -90f, sweepAngle.value, false, Offset(tmX - tmR, tmY - tmR), Size(tmR * 2f, tmR * 2f), style = Stroke(width = 4f * scale))
                    }
                    // Ticks
                    val tickLen = 6f * scale; val tickS = 3.5f * scale
                    drawLine(Color.White, Offset(tmX, tmY - tmR), Offset(tmX, tmY - tmR + tickLen), tickS, StrokeCap.Round)
                    drawLine(Color.White, Offset(tmX + tmR, tmY), Offset(tmX + tmR - tickLen, tmY), tickS, StrokeCap.Round)
                    drawLine(Color.White, Offset(tmX, tmY + tmR), Offset(tmX, tmY + tmR - tickLen), tickS, StrokeCap.Round)
                    drawLine(Color.White, Offset(tmX - tmR, tmY), Offset(tmX - tmR + tickLen, tmY), tickS, StrokeCap.Round)
                    // Hand
                    rotate(handRotation.value, pivot = Offset(tmX, tmY)) {
                        drawLine(Color(0xFFFAC775), Offset(tmX, tmY), Offset(tmX, tmY - tmR + 4f * scale), 5f * scale, StrokeCap.Round)
                    }
                    drawCircle(Color(0xFFFAC775), radius = 5.5f * scale, center = Offset(tmX, tmY))
                }
            }
        }
    }
}

@Composable
fun RuralFieldsBackground(isDark: Boolean, showVehicles: Boolean = true, showHills: Boolean = true) {
    val infiniteTransition = rememberInfiniteTransition(label = "RuralBackground")

    // Sun glow pulsing (6 second cycle)
    val sunOpacity by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SunGlow"
    )

    // Cloud drift
    val cloudProgress1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(35000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CloudDrift1"
    )

    val cloudProgress2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(50000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CloudDrift2"
    )

    // Vehicle progress (12-14 seconds)
    val vehicleProgressA = if (showVehicles && showHills) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(13000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "VehicleA"
        ).value
    } else 0f

    val vehicleProgressB = if (showVehicles && showHills) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(13000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(6500) // Half duration offset
            ),
            label = "VehicleB"
        ).value
    } else 0f

    val skyTopColor = if (isDark) Color(0xFF0F1214) else Color(0xFFFFF9E3)
    val skyBottomColor = Color.White
    val sunColor = Color(0xFFFAC775)
    
    val hill1Color = if (isDark) Color(0xFF1B3D2F) else Color(0xFFC5E1A5).copy(alpha = 0.7f)
    val hill2Color = if (isDark) Color(0xFF143528) else Color(0xFFA5D6A7).copy(alpha = 0.8f)
    val hill3Color = Color(0xFF0F6E56)

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. Sky Background
            drawRect(color = skyTopColor)

            // 2. Large Sun Glow (Top Left)
            drawCircle(
                color = sunColor.copy(alpha = if (isDark) 0.15f else 0.25f),
                radius = 350f,
                center = Offset(width * 0.1f, height * 0.05f)
            )

            // 3. Clouds
            val drawCloud = { progress: Float, y: Float, scale: Float, opacity: Float ->
                val x = (width + 300f) * progress - 150f
                val cloudColor = Color.White.copy(alpha = opacity)
                drawOval(cloudColor, Offset(x, y), Size(100f * scale, 40f * scale))
                drawOval(cloudColor, Offset(x + 20f * scale, y - 10f * scale), Size(60f * scale, 40f * scale))
            }

            drawCloud(cloudProgress1, height * 0.15f, 1.2f, if (isDark) 0.1f else 0.6f)
            drawCloud(cloudProgress2, height * 0.22f, 0.9f, if (isDark) 0.08f else 0.5f)

            if (showHills) {
                // 4. Hills & Vehicles
                
                // Hill 1 (Back) - The one vehicles follow
                val path1 = Path().apply {
                    moveTo(0f, height * 0.85f)
                    quadraticTo(width * 0.25f, height * 0.75f, width * 0.5f, height * 0.85f)
                    quadraticTo(width * 0.75f, height * 0.95f, width, height * 0.85f)
                    lineTo(width, height)
                    lineTo(0f, height)
                    close()
                }
                drawPath(path1, hill1Color)

                if (showVehicles) {
                    // Vehicles on Hill 1 ridge
                    val vehicleOpacity = if (isDark) 0.4f else 0.58f
                    
                    // Vehicle A: Truck (Gold)
                    val vAx = width * vehicleProgressA
                    val vAy = getBackHillY(vAx, width, height)
                    drawTruck(sunColor, vehicleOpacity, vAx, vAy)

                    // Vehicle B: Tractor (Teal)
                    val vBx = width * vehicleProgressB
                    val vBy = getBackHillY(vBx, width, height)
                    drawTractor(hill3Color, vehicleOpacity, vBx, vBy)
                }

                // Hill 2 (Middle)
                val path2 = Path().apply {
                    moveTo(0f, height * 0.92f)
                    quadraticTo(width * 0.3f, height * 0.85f, width * 0.6f, height * 0.92f)
                    quadraticTo(width * 0.85f, height * 0.98f, width, height * 0.9f)
                    lineTo(width, height)
                    lineTo(0f, height)
                    close()
                }
                drawPath(path2, hill2Color)

                // Hill 3 (Front)
                val path3 = Path().apply {
                    moveTo(0f, height)
                    quadraticTo(width * 0.4f, height * 0.92f, width * 0.8f, height * 0.98f)
                    quadraticTo(width * 0.9f, height, width, height * 0.95f)
                    lineTo(width, height)
                    lineTo(0f, height)
                    close()
                }
                drawPath(path3, hill3Color)

                // 5. Houses and Trees (Matching Reference Image)
                // Tree Left
                drawTree(width * 0.12f, height * 0.82f, 1.8f)
                // Tree Left Smaller
                drawTree(width * 0.22f, height * 0.83f, 1.2f)
                // House Left
                drawHouse(width * 0.32f, height * 0.81f, 1.1f)
                // House Right
                drawHouse(width * 0.95f, height * 0.85f, 1.3f)
            }
        }
    }
}

private fun DrawScope.drawHouse(x: Float, y: Float, scale: Float) {
    val houseWidth = 24f * scale
    val houseHeight = 18f * scale
    val roofHeight = 12f * scale

    // Walls
    drawRect(
        color = Color(0xFFFFF9E3).copy(alpha = 0.95f),
        topLeft = Offset(x - houseWidth / 2, y - houseHeight),
        size = Size(houseWidth, houseHeight)
    )
    // Door
    drawRect(
        color = Color(0xFF5D4037).copy(alpha = 0.7f),
        topLeft = Offset(x - 2f * scale, y - 8f * scale),
        size = Size(4f * scale, 8f * scale)
    )
    // Roof
    val roofPath = Path().apply {
        moveTo(x - houseWidth / 2 - 4f * scale, y - houseHeight)
        lineTo(x + houseWidth / 2 + 4f * scale, y - houseHeight)
        lineTo(x, y - houseHeight - roofHeight)
        close()
    }
    drawPath(roofPath, Color(0xFFD84315)) // Deep Orange/Brown roof
}

private fun DrawScope.drawTree(x: Float, y: Float, scale: Float) {
    val trunkWidth = 4f * scale
    val trunkHeight = 20f * scale
    val foliageRadius = 14f * scale

    // Trunk
    drawRect(
        color = Color(0xFF5D4037).copy(alpha = 0.8f),
        topLeft = Offset(x - trunkWidth / 2, y - trunkHeight),
        size = Size(trunkWidth, trunkHeight)
    )
    // Foliage
    drawCircle(
        color = Color(0xFF2E7D32).copy(alpha = 0.75f),
        radius = foliageRadius,
        center = Offset(x, y - trunkHeight)
    )
}

private fun getBackHillY(x: Float, width: Float, height: Float): Float {
    return if (x < width * 0.5f) {
        val t = x / (width * 0.5f)
        height * (0.85f - 0.2f * t + 0.2f * t * t)
    } else {
        val t = (x - width * 0.5f) / (width * 0.5f)
        height * (0.85f + 0.2f * t - 0.2f * t * t)
    }
}

private fun DrawScope.drawTruck(color: Color, opacity: Float, x: Float, y: Float) {
    val truckWidth = 36f
    val truckHeight = 14f
    val cabWidth = 10f
    val cabHeight = 9f
    val wheelRadius = 2.5f

    // Cargo box
    drawRect(
        color = color.copy(alpha = opacity),
        topLeft = Offset(x - truckWidth, y - truckHeight - 4f),
        size = Size(truckWidth - cabWidth, truckHeight)
    )
    // Cab
    drawRect(
        color = color.copy(alpha = opacity),
        topLeft = Offset(x - cabWidth, y - cabHeight - 4f),
        size = Size(cabWidth, cabHeight)
    )
    // Wheels
    drawCircle(color = color.copy(alpha = opacity), radius = wheelRadius, center = Offset(x - truckWidth + 8f, y - 4f))
    drawCircle(color = color.copy(alpha = opacity), radius = wheelRadius, center = Offset(x - 8f, y - 4f))
}

private fun DrawScope.drawTractor(color: Color, opacity: Float, x: Float, y: Float) {
    val cabWidth = 12f
    val cabHeight = 16f
    val hoodWidth = 18f
    val hoodHeight = 8f
    val rearWheelRadius = 5.5f
    val frontWheelRadius = 3f

    // Cab (at the back)
    drawRect(
        color = color.copy(alpha = opacity),
        topLeft = Offset(x - cabWidth - hoodWidth, y - cabHeight - 4f),
        size = Size(cabWidth, cabHeight)
    )
    // Hood
    drawRect(
        color = color.copy(alpha = opacity),
        topLeft = Offset(x - hoodWidth, y - hoodHeight - 4f),
        size = Size(hoodWidth, hoodHeight)
    )
    // Rear Wheel
    drawCircle(color = color.copy(alpha = opacity), radius = rearWheelRadius, center = Offset(x - hoodWidth - cabWidth + rearWheelRadius + 2f, y - 4f))
    // Front Wheel
    drawCircle(color = color.copy(alpha = opacity), radius = frontWheelRadius, center = Offset(x - frontWheelRadius - 2f, y - 4f))
}

@Composable
fun BrandedTitle(isDark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Pin + TD + Text
        Box(contentAlignment = Alignment.TopStart) {
            // Location Pin (Gold) - Floating above
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .offset(x = 12.dp, y = (-14).dp),
                tint = Color(0xFFFAC775)
            )
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "T"
                Text(
                    text = "T",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold, 
                        fontSize = 34.sp
                    ),
                    color = Color(0xFF0F6E56)
                )
                
                // "D" with clock face
                Canvas(modifier = Modifier.size(28.dp).offset(x = (-2).dp)) {
                    val scale = size.width / 24f
                    
                    // D-shaped body (Teal)
                    val dPath = Path().apply {
                        moveTo(2f * scale, 3f * scale)
                        lineTo(14f * scale, 3f * scale)
                        cubicTo(21f * scale, 3f * scale, 23f * scale, 7f * scale, 23f * scale, 12f * scale)
                        cubicTo(23f * scale, 17f * scale, 21f * scale, 21f * scale, 14f * scale, 21f * scale)
                        lineTo(2f * scale, 21f * scale)
                        close()
                    }
                    drawPath(path = dPath, color = Color(0xFF0F6E56))
                    
                    // White clock face
                    val clockCenter = Offset(13f * scale, 12f * scale)
                    val clockRadius = 7.5f * scale
                    drawCircle(
                        color = Color.White,
                        radius = clockRadius,
                        center = clockCenter
                    )
                    
                    // Clock hands (Teal)
                    drawLine(
                        color = Color(0xFF0F6E56),
                        start = clockCenter,
                        end = Offset(clockCenter.x, clockCenter.y - 5f * scale),
                        strokeWidth = 2f * scale,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF0F6E56),
                        start = clockCenter,
                        end = Offset(clockCenter.x + 4f * scale, clockCenter.y),
                        strokeWidth = 2f * scale,
                        cap = StrokeCap.Round
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // "TimeDrive"
                Text(
                    text = "Time",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
                    color = Color(0xFF0F6E56)
                )
                val goldTextColor = if (isDark) Color(0xFFFAC775) else Color(0xFFB8860B)
                Text(
                    text = "Drive",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
                    color = goldTextColor
                )
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, isBoldValue: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = if (isBoldValue) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (isBoldValue) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun BrandedCard(
    modifier: Modifier = Modifier,
    headerColor: Color = Color(0xFF0F6E56),
    headerContent: @Composable ColumnScope.() -> Unit,
    bodyContent: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    headerContent()
                }
            }
            Column(modifier = Modifier.padding(16.dp)) {
                bodyContent()
            }
        }
    }
}

@Composable
fun StaticGradientBackground(isDark: Boolean) {
    val topColor = if (isDark) Color(0xFF1E1B16) else Color(0xFFFFF3DC)
    val bottomColor = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFDFBF6)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    0f to topColor,
                    1f to bottomColor
                )
            )
    )
}

@Composable
fun VehicleListScreen(
    modifier: Modifier = Modifier,
    vehicles: List<VehicleEntity>,
    runningJobs: Map<VehicleEntity, RunningJob>,
    currentTheme: AppTheme,
    isDark: Boolean,
    onThemeChange: (AppTheme) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (VehicleEntity) -> Unit,
    onDeleteClick: (VehicleEntity) -> Unit,
    onSettingsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onTotalsClick: () -> Unit,
    onExportClick: () -> Unit,
    onVehicleClick: (VehicleEntity) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var vehicleToDelete by remember { mutableStateOf<VehicleEntity?>(null) }
    var showDeleteError by remember { mutableStateOf<String?>(null) }
    val errorStopJobFirstStr = stringResource(R.string.error_stop_job_first)

    if (vehicleToDelete != null) {
        AlertDialog(
            onDismissRequest = { vehicleToDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title, vehicleToDelete?.name ?: "")) },
            text = { Text(stringResource(R.string.delete_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    val vehicle = vehicleToDelete!!
                    if (runningJobs.containsKey(vehicle)) {
                        showDeleteError = errorStopJobFirstStr
                    } else {
                        onDeleteClick(vehicle)
                    }
                    vehicleToDelete = null
                }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vehicleToDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showDeleteError != null) {
        AlertDialog(
            onDismissRequest = { showDeleteError = null },
            title = { Text(stringResource(R.string.error_cannot_delete)) },
            text = { Text(showDeleteError!!) },
            confirmButton = {
                TextButton(onClick = { showDeleteError = null }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        RuralFieldsBackground(isDark = isDark)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
            BrandedTitle(isDark = isDark)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val newTheme = if (isDark) AppTheme.LIGHT else AppTheme.DARK
                    onThemeChange(newTheme)
                }) {
                    Icon(
                        if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = "Toggle Theme",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.widthIn(min = 180.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_history)) },
                            onClick = { showMenu = false; onHistoryClick() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_totals)) },
                            onClick = { showMenu = false; onTotalsClick() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export)) },
                            onClick = { showMenu = false; onExportClick() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_settings)) },
                            onClick = { showMenu = false; onSettingsClick() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (vehicles.isEmpty()) {
            Text(stringResource(R.string.no_vehicles_yet))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(vehicles) { vehicle ->
                    val isRunning = runningJobs.containsKey(vehicle)
                    var showCardMenu by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val iconBgColor = remember(vehicle.icon) {
                                when {
                                    vehicle.icon.contains("🚜") || vehicle.icon.contains("🌾") -> Color(0xFFE8F5E9)
                                    vehicle.icon.contains("🏗️") || vehicle.icon.contains("🏗") -> Color(0xFFFFF3E0)
                                    vehicle.icon.contains("🚛") || vehicle.icon.contains("🚚") || vehicle.icon.contains("🚙") -> Color(0xFFE3F2FD)
                                    else -> Color(0xFFF5F5F5)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(iconBgColor.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(vehicle.icon, style = MaterialTheme.typography.displaySmall)
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    vehicle.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.per_hour, formatRate(vehicle.ratePerHour)),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF0F6E56),
                                    fontWeight = FontWeight.Bold
                                )
                                
                                val runningJob = runningJobs[vehicle]
                                if (runningJob == null) {
                                    Row(
                                        modifier = Modifier.padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF0F6E56))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.status_available),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF0F6E56),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val runningJob = runningJobs[vehicle]
                                val statusText = when {
                                    runningJob == null -> stringResource(R.string.status_start)
                                    runningJob.isPaused -> stringResource(R.string.status_paused)
                                    else -> stringResource(R.string.status_running)
                                }
                                val statusColor = when {
                                    runningJob == null -> Color(0xFF0F6E56)
                                    runningJob.isPaused -> Color(0xFFC62828)
                                    else -> Color(0xFFE65100)
                                }

                                Button(
                                    onClick = { onVehicleClick(vehicle) },
                                    colors = ButtonDefaults.buttonColors(containerColor = statusColor),
                                    shape = RoundedCornerShape(24.dp),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                                ) {
                                    Text(statusText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                
                                Box {
                                    IconButton(onClick = { showCardMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu_settings))
                                    }
                                    DropdownMenu(expanded = showCardMenu, onDismissRequest = { showCardMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_edit)) },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { showCardMenu = false; onEditClick(vehicle) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_delete)) },
                                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                                            onClick = { showCardMenu = false; vehicleToDelete = vehicle }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF0F6E56),
            shadowElevation = 4.dp,
            border = BorderStroke(2.dp, Color(0xFFFAC775))
        ) {
            Button(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.btn_add_vehicle),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
}

@Composable
fun AddVehicleScreen(
    modifier: Modifier = Modifier,
    editingVehicle: VehicleEntity? = null,
    isDark: Boolean,
    onSave: (String, Double, String, String) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        var name by remember { mutableStateOf(editingVehicle?.name ?: "") }
        var rate by remember { mutableStateOf(editingVehicle?.ratePerHour?.toString() ?: "") }
        var roundingRule by remember { mutableStateOf(editingVehicle?.roundingRule ?: "PER_MINUTE") }
        var selectedIcon by remember { mutableStateOf(editingVehicle?.icon ?: "🚜") }
        var userHasSelectedIcon by remember { mutableStateOf(editingVehicle != null) }

        val emojiSuggestionsMap = mapOf(
            "tractor" to listOf("🚜", "🌾", "🧑‍🌾", "🌱", "🚜"),
            "jcb" to listOf("🏗️", "🚜", "⚙️", "🛠️", "🚧"),
            "truck" to listOf("🚛", "🚚", "🛻", "📦", "🚚"),
            "water" to listOf("🚛", "💧", "🚰", "🛢️", "🚚"),
            "tanker" to listOf("🚛", "💧", "🚰", "🛢️", "🚚"),
            "harvester" to listOf("🌾", "🚜", "🌱", "🚜", "🌾")
        )

        val suggestions = remember(name) {
            val lowerName = name.lowercase()
            val matched = emojiSuggestionsMap.entries.find { lowerName.contains(it.key) }?.value
            matched ?: listOf("🚜", "🏗️", "🚛", "🚐", "🚙", "🛻", "🚚", "🚜")
        }

        LaunchedEffect(suggestions) {
            if (!userHasSelectedIcon && suggestions.isNotEmpty()) {
                selectedIcon = suggestions.first()
            }
        }

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
        ) {
            Text(
                if (editingVehicle != null) stringResource(R.string.edit_vehicle) else stringResource(R.string.add_vehicle),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_vehicle_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = rate,
                onValueChange = { input ->
                    if (input.isEmpty() || input.all { it.isDigit() || it == '.' }) {
                        if (input.count { it == '.' } <= 1) {
                            rate = input
                        }
                    }
                },
                label = { Text(stringResource(R.string.label_rate_per_hour)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(stringResource(R.string.label_vehicle_icon), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.choose_an_icon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                suggestions.take(5).forEach { emoji ->
                    val isSelected = selectedIcon == emoji
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f), CircleShape)
                            .clickable { 
                                selectedIcon = emoji
                                userHasSelectedIcon = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "${selectedIcon}   " + stringResource(R.string.selected_icon_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(stringResource(R.string.rounding_rule), style = MaterialTheme.typography.titleMedium)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = roundingRule == "PER_MINUTE", onClick = { roundingRule = "PER_MINUTE" })
                    Text(stringResource(R.string.rule_per_minute))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = roundingRule == "HALF_HOUR", onClick = { roundingRule = "HALF_HOUR" })
                    Text(stringResource(R.string.rule_half_hour))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = roundingRule == "FULL_HOUR", onClick = { roundingRule = "FULL_HOUR" })
                    Text(stringResource(R.string.rule_full_hour))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val rateValue = rate.toDoubleOrNull()
                        if (name.isNotBlank() && rateValue != null) {
                            onSave(name, rateValue, roundingRule, selectedIcon)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (editingVehicle != null) stringResource(R.string.btn_update_vehicle) else stringResource(R.string.btn_save_vehicle))
                }
            }
        }
    }
}

@Composable
fun TotalsScreen(
    modifier: Modifier = Modifier,
    jobs: List<JobEntity>,
    companySettings: CompanySettingsEntity?,
    isDark: Boolean,
    onBack: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        val context = LocalContext.current
        val vehicleFilterAll = stringResource(R.string.all_vehicles)
        var vehicleFilter by remember { mutableStateOf(vehicleFilterAll) }
        
        val timeRangeToday = stringResource(R.string.today)
        var timeRangeFilter by remember { mutableStateOf(timeRangeToday) }

        val earningTotalsStr = stringResource(R.string.earning_totals)
        val todayStr = stringResource(R.string.today)
        val receiptVehicleStr = stringResource(R.string.receipt_vehicle)
        val menuHistoryStr = stringResource(R.string.menu_history)
        val receiptTotalStr = stringResource(R.string.receipt_total)

        val vehicleNames = remember(jobs, vehicleFilterAll) {
            listOf(vehicleFilterAll) + jobs.map { it.vehicleName }.distinct().sorted()
        }

        val timeRangeOptions = listOf(
            stringResource(R.string.today),
            stringResource(R.string.this_week),
            stringResource(R.string.this_month),
            stringResource(R.string.this_year)
        )

        val startOfTimeRange = remember(timeRangeFilter, timeRangeOptions) {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            when (timeRangeFilter) {
                timeRangeOptions[1] -> calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                timeRangeOptions[2] -> calendar.set(Calendar.DAY_OF_MONTH, 1)
                timeRangeOptions[3] -> calendar.set(Calendar.DAY_OF_YEAR, 1)
            }
            calendar.timeInMillis
        }

        val filteredJobs = remember(jobs, vehicleFilter, startOfTimeRange, vehicleFilterAll) {
            jobs.filter { job ->
                val matchesVehicle = vehicleFilter == vehicleFilterAll || job.vehicleName == vehicleFilter
                val matchesTime = job.endTime >= startOfTimeRange
                matchesVehicle && matchesTime
            }
        }

        val totalAmount = filteredJobs.sumOf { it.amount }
        val pendingJobs = filteredJobs.filter { job -> job.paymentStatus == "PENDING" }
        val pendingAmount = pendingJobs.sumOf { it.amount }
        val pendingCount = pendingJobs.size

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                }
                Text(stringResource(R.string.earning_totals), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                var vehicleMenuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { vehicleMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(vehicleFilter)
                    }
                    DropdownMenu(expanded = vehicleMenuExpanded, onDismissRequest = { vehicleMenuExpanded = false }) {
                        vehicleNames.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { vehicleFilter = name; vehicleMenuExpanded = false })
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                var timeMenuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { timeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(timeRangeFilter)
                    }
                    DropdownMenu(expanded = timeMenuExpanded, onDismissRequest = { timeMenuExpanded = false }) {
                        timeRangeOptions.forEach { range ->
                            DropdownMenuItem(text = { Text(range) }, onClick = { timeRangeFilter = range; timeMenuExpanded = false })
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            BrandedCard(
                modifier = Modifier.fillMaxWidth(),
                headerColor = Color(0xFF0F6E56),
                headerContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.total_for_period, timeRangeFilter).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val details = mutableListOf<Pair<String, String>>()
                                details.add(todayStr to timeRangeFilter) // Period
                                if (vehicleFilter != vehicleFilterAll) {
                                    details.add(receiptVehicleStr to vehicleFilter)
                                }
                                details.add(menuHistoryStr to "${filteredJobs.size}")

                                downloadReceiptImage(
                                    context = context,
                                    companySettings = companySettings,
                                    title = earningTotalsStr,
                                    details = details,
                                    totalLabel = receiptTotalStr,
                                    totalValue = "₹${String.format(Locale.getDefault(), "%.2f", totalAmount)}"
                                )
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = stringResource(R.string.btn_download),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = {
                                val details = mutableListOf<Pair<String, String>>()
                                details.add(todayStr to timeRangeFilter) // Period
                                if (vehicleFilter != vehicleFilterAll) {
                                    details.add(receiptVehicleStr to vehicleFilter)
                                }
                                details.add(menuHistoryStr to "${filteredJobs.size}")

                                shareReceiptImage(
                                    context = context,
                                    companySettings = companySettings,
                                    title = earningTotalsStr,
                                    details = details,
                                    totalLabel = receiptTotalStr,
                                    totalValue = "₹${String.format(Locale.getDefault(), "%.2f", totalAmount)}"
                                )
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.btn_share_bill),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                bodyContent = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "₹${String.format(Locale.getDefault(), "%.2f", totalAmount)}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.jobs_count, filteredJobs.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            BrandedCard(
                modifier = Modifier.fillMaxWidth(),
                headerColor = if (isDark) Color(0xFFB8860B) else Color(0xFFA07600),
                headerContent = {
                    Text(
                        text = stringResource(R.string.pending_collection).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp
                    )
                },
                bodyContent = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val goldColor = if (isDark) Color(0xFFFAC775) else Color(0xFF8B6508)
                        Text(
                            "₹${String.format(Locale.getDefault(), "%.2f", pendingAmount)}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = goldColor
                        )
                        Text(
                            stringResource(R.string.pending_jobs_count, pendingCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = goldColor.copy(alpha = 0.8f)
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    jobs: List<JobEntity>,
    companySettings: CompanySettingsEntity?,
    isDark: Boolean,
    onUpdateStatus: (Long, String) -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        val context = LocalContext.current
        val vehicleFilterAll = stringResource(R.string.all_vehicles)
        var vehicleFilter by remember { mutableStateOf(vehicleFilterAll) }
        
        val timeRangeToday = stringResource(R.string.today)
        var timeRangeFilter by remember { mutableStateOf(timeRangeToday) }
        
        var searchQuery by remember { mutableStateOf("") }
        var showPendingOnly by remember { mutableStateOf(false) }

        val receiptTitleStr = stringResource(R.string.receipt_title)
        val receiptVehicleStr = stringResource(R.string.receipt_vehicle)
        val receiptCustomerStr = stringResource(R.string.receipt_customer)
        val receiptDateStr = stringResource(R.string.receipt_date)
        val receiptDurationStr = stringResource(R.string.receipt_duration)
        val receiptRateStr = stringResource(R.string.receipt_rate)
        val receiptTotalStr = stringResource(R.string.receipt_total)

        val vehicleNames = remember(jobs, vehicleFilterAll) {
            listOf(vehicleFilterAll) + jobs.map { it.vehicleName }.distinct().sorted()
        }

        val timeRangeOptions = listOf(
            stringResource(R.string.today),
            stringResource(R.string.this_week),
            stringResource(R.string.this_month),
            stringResource(R.string.this_year)
        )

        val startOfTimeRange = remember(timeRangeFilter, timeRangeOptions) {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            when (timeRangeFilter) {
                timeRangeOptions[1] -> calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                timeRangeOptions[2] -> calendar.set(Calendar.DAY_OF_MONTH, 1)
                timeRangeOptions[3] -> calendar.set(Calendar.DAY_OF_YEAR, 1)
            }
            calendar.timeInMillis
        }

        val filteredJobs = remember(jobs, vehicleFilter, startOfTimeRange, searchQuery, showPendingOnly, vehicleFilterAll) {
            jobs.filter { job ->
                val matchesVehicle = vehicleFilter == vehicleFilterAll || job.vehicleName == vehicleFilter
                val matchesTime = job.endTime >= startOfTimeRange
                val matchesSearch = job.customerName.contains(searchQuery, ignoreCase = true)
                val matchesPending = !showPendingOnly || job.paymentStatus == "PENDING"
                matchesVehicle && matchesTime && matchesSearch && matchesPending
            }
        }

        val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                    Text(
                        stringResource(R.string.job_history),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_customer)) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    var vehicleMenuExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { vehicleMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(vehicleFilter)
                        }
                        DropdownMenu(
                            expanded = vehicleMenuExpanded,
                            onDismissRequest = { vehicleMenuExpanded = false }) {
                            vehicleNames.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { vehicleFilter = name; vehicleMenuExpanded = false })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    var timeMenuExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { timeMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(timeRangeFilter)
                        }
                        DropdownMenu(
                            expanded = timeMenuExpanded,
                            onDismissRequest = { timeMenuExpanded = false }) {
                            timeRangeOptions.forEach { range ->
                                DropdownMenuItem(
                                    text = { Text(range) },
                                    onClick = { timeRangeFilter = range; timeMenuExpanded = false })
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPendingOnly, onCheckedChange = { showPendingOnly = it })
                    Text(
                        stringResource(R.string.show_pending_only),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (filteredJobs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.no_jobs_found))
                    }
                }
            } else {
                items(filteredJobs) { job ->
                    BrandedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        headerColor = Color(0xFF0F6E56),
                        headerContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${job.vehicleName} · ${job.customerName}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            downloadReceiptImage(
                                                context = context,
                                                companySettings = companySettings,
                                                title = receiptTitleStr,
                                                details = listOf(
                                                    receiptVehicleStr to job.vehicleName,
                                                    receiptCustomerStr to job.customerName,
                                                    receiptDateStr to dateFormatter.format(
                                                        Date(job.endTime)
                                                    ),
                                                    receiptDurationStr to formatDuration(
                                                        job.endTime - job.startTime
                                                    ),
                                                    receiptRateStr to "${
                                                        formatRate(
                                                            job.ratePerHour
                                                        )
                                                    }/hr"
                                                ),
                                                totalLabel = receiptTotalStr,
                                                totalValue = "₹${String.format(Locale.getDefault(), "%.2f", job.amount)}"
                                            )
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = stringResource(R.string.btn_download),
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            shareReceiptImage(
                                                context = context,
                                                companySettings = companySettings,
                                                title = receiptTitleStr,
                                                details = listOf(
                                                    receiptVehicleStr to job.vehicleName,
                                                    receiptCustomerStr to job.customerName,
                                                    receiptDateStr to dateFormatter.format(
                                                        Date(job.endTime)
                                                    ),
                                                    receiptDurationStr to formatDuration(
                                                        job.endTime - job.startTime
                                                    ),
                                                    receiptRateStr to "${
                                                        formatRate(
                                                            job.ratePerHour
                                                        )
                                                    }/hr"
                                                ),
                                                totalLabel = receiptTotalStr,
                                                totalValue = "₹${String.format(Locale.getDefault(), "%.2f", job.amount)}"
                                            )
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = stringResource(R.string.btn_share_bill),
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        },
                        bodyContent = {
                            DetailRow(
                                stringResource(R.string.receipt_duration).removeSuffix(":"),
                                formatDuration(job.endTime - job.startTime),
                                isBoldValue = true
                            )
                            DetailRow(
                                stringResource(R.string.receipt_date).removeSuffix(":"),
                                dateFormatter.format(Date(job.endTime)),
                                isBoldValue = true
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            DashedDivider()
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Badge
                                val statusColor =
                                    if (job.paymentStatus == "PAID") Color(0xFF0F6E56) else Color(
                                        0xFFFFB300
                                    )
                                val statusText =
                                    if (job.paymentStatus == "PAID") stringResource(R.string.status_paid) else stringResource(
                                        R.string.status_pending
                                    )
                                Surface(
                                    color = statusColor,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            val newStatus =
                                                if (job.paymentStatus == "PAID") "PENDING" else "PAID"
                                            onUpdateStatus(job.id, newStatus)
                                        }
                                ) {
                                    Text(
                                        text = statusText,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 4.dp
                                        )
                                    )
                                }

                                Text(
                                    "₹${String.format(Locale.getDefault(), "%.2f", job.amount)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsHubScreen(
    modifier: Modifier = Modifier,
    companySettings: CompanySettingsEntity,
    isDark: Boolean,
    onCompanyDetailsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onBackupRestoreClick: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        RuralFieldsBackground(isDark = isDark)
        val currentLocaleCode = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en"
        val languages = listOf(
            Triple("en", "English", "English"),
            Triple("hi", "हिंदी", "Hindi"),
            Triple("ta", "தமிழ்", "Tamil"),
            Triple("te", "తెలుగు", "Telugu"),
            Triple("mr", "मరాठी", "Marathi"),
            Triple("ml", "മലയാളം", "Malayalam")
        )
        val currentLanguageName = languages.find { it.first == currentLocaleCode }?.second ?: "English"

        val lastBackupText = if (companySettings.lastBackupTime == 0L) {
            stringResource(R.string.never_backed_up)
        } else {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            stringResource(R.string.last_backup, sdf.format(Date(companySettings.lastBackupTime)))
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }
                Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Company Details Row
            SettingsMenuRow(
                icon = Icons.Default.Business,
                title = stringResource(R.string.company_settings),
                subtitle = companySettings.businessName.ifBlank { "Not set" },
                onClick = onCompanyDetailsClick
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Language Row
            SettingsMenuRow(
                icon = Icons.Default.Language,
                title = stringResource(R.string.language),
                subtitle = currentLanguageName,
                onClick = onLanguageClick
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Backup & Restore Row
            SettingsMenuRow(
                icon = Icons.Default.Backup,
                title = stringResource(R.string.backup_restore),
                subtitle = lastBackupText,
                onClick = onBackupRestoreClick
            )
        }
    }
}

@Composable
fun BackupRestoreScreen(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    onBack: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val database = remember { AppDatabase.getDatabase(context) }
        val vehicleDao = database.vehicleDao()
        val jobDao = database.jobDao()
        val settingsDao = database.companySettingsDao()

        var restoreData by remember { mutableStateOf<JSONObject?>(null) }
        var showConfirmDialog by remember { mutableStateOf(false) }

        val backupSuccessStr = stringResource(R.string.backup_success)
        val errorInvalidBackupStr = stringResource(R.string.error_invalid_backup)
        val errorRestoreFailedFormat = stringResource(R.string.error_restore_failed)
        val restoreSuccessStr = stringResource(R.string.restore_success)
        val createBackupStr = stringResource(R.string.create_backup)

        suspend fun generateBackupJson(): String {
            val vehicles = vehicleDao.getAllVehiclesOnce()
            val jobs = jobDao.getAllJobsOnce()
            val settings = settingsDao.getSettingsOnce() ?: CompanySettingsEntity()

            val root = JSONObject()
            root.put("backupTime", System.currentTimeMillis())
            root.put("version", 8)

            val vArr = JSONArray()
            vehicles.forEach {
                val obj = JSONObject()
                obj.put("name", it.name)
                obj.put("ratePerHour", it.ratePerHour)
                obj.put("roundingRule", it.roundingRule)
                obj.put("icon", it.icon)
                vArr.put(obj)
            }
            root.put("vehicles", vArr)

            val jArr = JSONArray()
            jobs.forEach {
                val obj = JSONObject()
                obj.put("vehicleName", it.vehicleName)
                obj.put("customerName", it.customerName)
                obj.put("customerAddress", it.customerAddress)
                obj.put("startTime", it.startTime)
                obj.put("endTime", it.endTime)
                obj.put("amount", it.amount)
                obj.put("ratePerHour", it.ratePerHour)
                obj.put("paymentStatus", it.paymentStatus)
                jArr.put(obj)
            }
            root.put("jobs", jArr)

            val sObj = JSONObject()
            sObj.put("businessName", settings.businessName)
            sObj.put("phoneNumber", settings.phoneNumber)
            sObj.put("address", settings.address)
            root.put("settings", sObj)

            return root.toString(2)
        }

        val saveLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
            onResult = { uri ->
                uri?.let {
                    scope.launch {
                        try {
                            val jsonString = generateBackupJson()
                            context.contentResolver.openOutputStream(it)?.use { os ->
                                os.write(jsonString.toByteArray())
                            }
                            val settings = settingsDao.getSettingsOnce() ?: CompanySettingsEntity()
                            settingsDao.upsert(settings.copy(lastBackupTime = System.currentTimeMillis()))
                            Toast.makeText(context, backupSuccessStr, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )

        val restoreLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri ->
                uri?.let {
                    try {
                        val jsonString = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                        if (jsonString != null) {
                            val json = JSONObject(jsonString)
                            if (json.has("vehicles") && json.has("jobs")) {
                                restoreData = json
                                showConfirmDialog = true
                            } else {
                                Toast.makeText(context, errorInvalidBackupStr, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, String.format(errorRestoreFailedFormat, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )

        if (showConfirmDialog && restoreData != null) {
            val vehiclesArr = restoreData!!.getJSONArray("vehicles")
            val jobsArr = restoreData!!.getJSONArray("jobs")
            val backupDateLong = restoreData!!.optLong("backupTime", 0L)
            val backupDate = if (backupDateLong != 0L) {
                SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(backupDateLong))
            } else "Unknown"

            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(stringResource(R.string.restore_confirm_title)) },
                text = { 
                    Text(stringResource(R.string.restore_confirm_msg, "backup.json", vehiclesArr.length(), jobsArr.length(), backupDate))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showConfirmDialog = false
                        scope.launch {
                            try {
                                // 1. Vehicles
                                val vehicles = mutableListOf<VehicleEntity>()
                                for (i in 0 until vehiclesArr.length()) {
                                    val obj = vehiclesArr.getJSONObject(i)
                                    vehicles.add(VehicleEntity(
                                        name = obj.getString("name"),
                                        ratePerHour = obj.getDouble("ratePerHour"),
                                        roundingRule = obj.optString("roundingRule", "PER_MINUTE"),
                                        icon = obj.optString("icon", "🚜")
                                    ))
                                }
                                
                                // 2. Jobs
                                val jobs = mutableListOf<JobEntity>()
                                for (i in 0 until jobsArr.length()) {
                                    val obj = jobsArr.getJSONObject(i)
                                    jobs.add(JobEntity(
                                        vehicleName = obj.getString("vehicleName"),
                                        customerName = obj.getString("customerName"),
                                        customerAddress = obj.optString("customerAddress", ""),
                                        startTime = obj.getLong("startTime"),
                                        endTime = obj.getLong("endTime"),
                                        amount = obj.getDouble("amount"),
                                        ratePerHour = obj.optDouble("ratePerHour", 0.0),
                                        paymentStatus = obj.optString("paymentStatus", "PAID")
                                    ))
                                }

                                // 3. Settings
                                val settingsObj = restoreData!!.optJSONObject("settings")
                                val settings = if (settingsObj != null) {
                                    CompanySettingsEntity(
                                        businessName = settingsObj.optString("businessName", ""),
                                        phoneNumber = settingsObj.optString("phoneNumber", ""),
                                        address = settingsObj.optString("address", ""),
                                        lastBackupTime = System.currentTimeMillis()
                                    )
                                } else {
                                    (settingsDao.getSettingsOnce() ?: CompanySettingsEntity()).copy(lastBackupTime = System.currentTimeMillis())
                                }

                                // Perform restoration
                                database.withTransaction {
                                    vehicleDao.deleteAllVehicles()
                                    jobDao.deleteAllJobs()
                                    settingsDao.deleteSettings()

                                    vehicleDao.insertAll(vehicles)
                                    jobDao.insertAll(jobs)
                                    settingsDao.upsert(settings)
                                }

                                Toast.makeText(context, restoreSuccessStr, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, String.format(errorRestoreFailedFormat, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.btn_restore), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Create Backup Section
            BrandedCard(
                headerContent = {
                    Text(stringResource(R.string.create_backup).uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                },
                bodyContent = {
                    Text(stringResource(R.string.create_backup_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val jsonString = generateBackupJson()
                                        val sharedFolder = File(context.cacheDir, "shared_images")
                                        sharedFolder.mkdirs()
                                        val file = File(sharedFolder, "timedrive_backup.json")
                                        file.writeText(jsonString)

                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, createBackupStr))
                                        
                                        val settings = settingsDao.getSettingsOnce() ?: CompanySettingsEntity()
                                        settingsDao.upsert(settings.copy(lastBackupTime = System.currentTimeMillis()))
                                        Toast.makeText(context, backupSuccessStr, Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_share_backup))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                saveLauncher.launch("timedrive_backup_$date.json")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_save_to_device))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Restore Section
            BrandedCard(
                headerColor = Color(0xFFB8860B),
                headerContent = {
                    Text(stringResource(R.string.restore_backup).uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                },
                bodyContent = {
                    Text(stringResource(R.string.restore_backup_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            restoreLauncher.launch(arrayOf("application/json"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.restore_backup))
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CompanyDetailsScreen(
    modifier: Modifier = Modifier,
    initialSettings: CompanySettingsEntity,
    isDark: Boolean,
    onSave: (CompanySettingsEntity) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        var businessName by remember { mutableStateOf(initialSettings.businessName) }
        var phoneNumber by remember { mutableStateOf(initialSettings.phoneNumber) }
        var address by remember { mutableStateOf(initialSettings.address) }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }
                Text(stringResource(R.string.company_settings), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                label = { Text(stringResource(R.string.label_business_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { input ->
                    if (input.all { it.isDigit() }) {
                        phoneNumber = input
                    }
                },
                label = { Text(stringResource(R.string.label_phone_number)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.label_address)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            Row {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        onSave(initialSettings.copy(
                            businessName = businessName,
                            phoneNumber = phoneNumber,
                            address = address
                        ))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            }
        }
    }
}

@Composable
fun LanguageScreen(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    onLanguageSelected: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        val currentLocaleCode = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en"
        
        val languages = listOf(
            Triple("en", "English", "English"),
            Triple("hi", "हिंदी", "Hindi"),
            Triple("ta", "தமிழ்", "Tamil"),
            Triple("te", "తెలుగు", "Telugu"),
            Triple("mr", "मరాठी", "Marathi"),
            Triple("ml", "മലയാളം", "Malayalam")
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }
                Text(stringResource(R.string.language), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(16.dp))

            languages.forEach { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(lang.first)
                            AppCompatDelegate.setApplicationLocales(appLocale)
                            onLanguageSelected()
                        }
                        .padding(vertical = 16.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = lang.second, style = MaterialTheme.typography.bodyLarge)
                    if (currentLocaleCode == lang.first) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun TimerScreen(
    modifier: Modifier = Modifier,
    vehicle: VehicleEntity,
    runningJob: RunningJob?,
    isDark: Boolean,
    onStart: (String, String) -> Unit,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }
                Text(
                    if (runningJob == null) stringResource(R.string.start_job) else stringResource(R.string.timer),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (runningJob == null) {
                // Not started yet - ask for customer info
                var customerName by remember { mutableStateOf("") }
                var customerAddress by remember { mutableStateOf("") }
                Column {
                    Text(vehicle.name, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.per_hour, formatRate(vehicle.ratePerHour)))
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = customerName,
                        onValueChange = { customerName = it },
                        label = { Text(stringResource(R.string.label_customer_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customerAddress,
                        onValueChange = { customerAddress = it },
                        label = { Text(stringResource(R.string.label_customer_address)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { if (customerName.isNotBlank()) onStart(customerName, customerAddress) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_start_job))
                    }
                }
            } else {
                // Job running - live updating timer
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(runningJob.isPaused) {
                    while (!runningJob.isPaused) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                    now = System.currentTimeMillis()
                }
                
                val totalElapsedSinceStart = now - runningJob.startTime
                val currentPauseDuration = if (runningJob.isPaused) now - runningJob.lastPauseTime else 0
                val netElapsed = totalElapsedSinceStart - (runningJob.accumulatedPausedDuration + currentPauseDuration)
                
                val amount = calculateAmount(netElapsed, vehicle.ratePerHour, vehicle.roundingRule)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("${vehicle.name} — ${formatRate(vehicle.ratePerHour)}/" + stringResource(R.string.this_week).substringBefore(":")) // Short hour label hack
                    Text(stringResource(R.string.customer_label, runningJob.customerName))
                    if (runningJob.customerAddress.isNotBlank()) {
                        Text(runningJob.customerAddress, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(formatDuration(netElapsed), style = MaterialTheme.typography.displayMedium)
                    if (runningJob.isPaused) {
                        Text(stringResource(R.string.status_paused).uppercase(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.amount_so_far))
                            Text(
                                "₹${String.format(Locale.getDefault(), "%.2f", amount)}",
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onPauseToggle, modifier = Modifier.weight(1f)) {
                            Text(if (runningJob.isPaused) stringResource(R.string.btn_resume) else stringResource(R.string.btn_pause))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.btn_stop_job))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(
    modifier: Modifier = Modifier,
    vehicle: VehicleEntity,
    customerName: String,
    customerAddress: String,
    elapsedMillis: Long,
    amount: Double,
    companySettings: CompanySettingsEntity?,
    isDark: Boolean,
    onDone: (String) -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        StaticGradientBackground(isDark = isDark)
        val context = LocalContext.current
        var status by remember { mutableStateOf("PAID") }
        BackHandler { onDone(status) }

        val receiptTitleStr = stringResource(R.string.receipt_title)
        val receiptVehicleStr = stringResource(R.string.receipt_vehicle)
        val receiptCustomerStr = stringResource(R.string.receipt_customer)
        val receiptDateStr = stringResource(R.string.receipt_date)
        val receiptDurationStr = stringResource(R.string.receipt_duration)
        val receiptRateStr = stringResource(R.string.receipt_rate)
        val receiptTotalStr = stringResource(R.string.receipt_total)

        val hasCompanyDetails = companySettings != null && (
                companySettings.businessName.isNotBlank() ||
                        companySettings.phoneNumber.isNotBlank() ||
                        companySettings.address.isNotBlank()
                )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.job_finished), style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(20.dp))

            BrandedCard(
                modifier = Modifier.fillMaxWidth(),
                headerColor = if (hasCompanyDetails) Color(0xFF0F6E56) else Color.Transparent,
                headerContent = {
                    companySettings?.let { settings ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (settings.businessName.isNotBlank()) {
                                Text(
                                    settings.businessName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (settings.phoneNumber.isNotBlank()) {
                                Text(
                                    settings.phoneNumber,
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (settings.address.isNotBlank()) {
                                Text(
                                    settings.address,
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                },
                bodyContent = {
                    Text(
                        text = stringResource(R.string.receipt_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    DetailRow(stringResource(R.string.receipt_vehicle), vehicle.name)
                    DetailRow(
                        stringResource(R.string.receipt_customer).removeSuffix(":"),
                        customerName
                    )
                    if (customerAddress.isNotBlank()) {
                        DetailRow(stringResource(R.string.label_address), customerAddress)
                    }
                    DetailRow(
                        stringResource(R.string.receipt_duration).removeSuffix(":"),
                        formatDuration(elapsedMillis)
                    )
                    DetailRow(
                        stringResource(R.string.receipt_rate).removeSuffix(":"),
                        formatRate(vehicle.ratePerHour)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    val totalContainerColor =
                        if (isDark) Color(0xFF2E7D32).copy(alpha = 0.2f) else Color(0xFFE8F5E9)
                    val totalContentColor = if (isDark) Color(0xFF81C784) else Color(0xFF1B5E20)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(totalContainerColor, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.receipt_total),
                                color = totalContentColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "₹${String.format(Locale.getDefault(), "%.2f", amount)}",
                                color = totalContentColor,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )


            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.payment_status), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            val selectedColor = Color(0xFF0F6E56)

            OutlinedButton(
                onClick = { status = "PAID" },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (status == "PAID") selectedColor else Color.Transparent,
                    contentColor = if (status == "PAID") Color.White else selectedColor
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, selectedColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.status_paid))
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { status = "PENDING" },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (status == "PENDING") selectedColor else Color.Transparent,
                    contentColor = if (status == "PENDING") Color.White else selectedColor
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, selectedColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.status_pending))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        downloadReceiptImage(
                            context = context,
                            companySettings = companySettings,
                            title = receiptTitleStr,
                            details = listOf(
                                receiptVehicleStr to vehicle.name,
                                receiptCustomerStr to customerName,
                                receiptDateStr to SimpleDateFormat(
                                    "dd MMM yyyy",
                                    Locale.getDefault()
                                ).format(Date()),
                                receiptDurationStr to formatDuration(
                                    elapsedMillis
                                ),
                                receiptRateStr to "${
                                    formatRate(
                                        vehicle.ratePerHour
                                    )
                                }/hr"
                            ),
                            totalLabel = receiptTotalStr,
                            totalValue = "₹${String.format(Locale.getDefault(), "%.2f", amount)}"
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_download))
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = {
                        shareReceiptImage(
                            context = context,
                            companySettings = companySettings,
                            title = receiptTitleStr,
                            details = listOf(
                                receiptVehicleStr to vehicle.name,
                                receiptCustomerStr to customerName,
                                receiptDateStr to SimpleDateFormat(
                                    "dd MMM yyyy",
                                    Locale.getDefault()
                                ).format(Date()),
                                receiptDurationStr to formatDuration(
                                    elapsedMillis
                                ),
                                receiptRateStr to "${
                                    formatRate(
                                        vehicle.ratePerHour
                                    )
                                }/hr"
                            ),
                            totalLabel = receiptTotalStr,
                            totalValue = "₹${String.format(Locale.getDefault(), "%.2f", amount)}"
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Share, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_share_bill))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onDone(status) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.btn_done))
            }
        }
    }
}

data class OnboardingPage(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val headline: String,
    val subtext: String,
    val color: Color
)

@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    onFinish: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        RuralFieldsBackground(isDark = isDark)
        var currentPage by remember { mutableIntStateOf(0) }
        val pages = listOf(
            OnboardingPage(
                icon = Icons.Default.Agriculture,
                headline = stringResource(R.string.onboarding_1_title),
                subtext = stringResource(R.string.onboarding_1_sub),
                color = Color(0xFF0F6E56)
            ),
            OnboardingPage(
                icon = Icons.Default.Timer,
                headline = stringResource(R.string.onboarding_2_title),
                subtext = stringResource(R.string.onboarding_2_sub),
                color = Color(0xFFE65100)
            ),
            OnboardingPage(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                headline = stringResource(R.string.onboarding_3_title),
                subtext = stringResource(R.string.onboarding_3_sub),
                color = Color(0xFF0F6E56)
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip link
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.btn_skip), style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            val page = pages[currentPage]

            // Icon in circle
            Surface(
                modifier = Modifier.size(160.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                tonalElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = page.color
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = page.headline,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = page.subtext,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Dot indicators
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == currentPage) 10.dp else 8.dp)
                            .background(
                                color = if (index == currentPage) page.color else MaterialTheme.colorScheme.outlineVariant,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Next / Get Started button
            Surface(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = page.color,
                shadowElevation = 6.dp,
                tonalElevation = 6.dp
            ) {
                Button(
                    onClick = {
                        if (currentPage < pages.size - 1) {
                            currentPage++
                        } else {
                            onFinish()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(28.dp),
                    elevation = null
                ) {
                    Text(
                        if (currentPage == pages.size - 1) stringResource(R.string.btn_get_started) else stringResource(R.string.btn_next),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun InitialSetupScreen(
    modifier: Modifier = Modifier,
    initialSettings: CompanySettingsEntity,
    isDark: Boolean,
    onContinue: (CompanySettingsEntity) -> Unit,
    onSkip: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        RuralFieldsBackground(isDark = isDark)
        var businessName by remember { mutableStateOf(initialSettings.businessName) }
        var phoneNumber by remember { mutableStateOf(initialSettings.phoneNumber) }
        var address by remember { mutableStateOf(initialSettings.address) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Icon
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                tonalElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Business,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                        tint = Color(0xFF0F6E56)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.initial_setup_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.initial_setup_sub),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Form Fields
            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                label = { Text(stringResource(R.string.label_business_name)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { input ->
                    if (input.all { it.isDigit() }) {
                        phoneNumber = input
                    }
                },
                label = { Text(stringResource(R.string.label_phone_number)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.label_address)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = stringResource(R.string.initial_setup_reassurance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Buttons
            Surface(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF0F6E56),
                shadowElevation = 4.dp
            ) {
                Button(
                    onClick = {
                        onContinue(initialSettings.copy(
                            businessName = businessName,
                            phoneNumber = phoneNumber,
                            address = address
                        ))
                    },
                    modifier = Modifier.fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(stringResource(R.string.btn_continue), style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    stringResource(R.string.btn_skip_for_now),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
