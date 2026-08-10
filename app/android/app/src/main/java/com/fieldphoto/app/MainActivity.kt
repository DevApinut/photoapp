package com.fieldphoto.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.provider.MediaStore
import android.graphics.pdf.PdfRenderer
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.fieldphoto.app.data.*
import com.fieldphoto.app.sync.SyncClient
import com.fieldphoto.app.sync.SyncProgress
import com.fieldphoto.app.sync.CloudClient
import com.fieldphoto.app.sync.CloudPhoto
import com.fieldphoto.app.sync.CloudCatalog
import com.fieldphoto.app.sync.CloudDocument
import com.fieldphoto.app.media.RecentImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import kotlin.math.sign
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PhotoWorkApp((application as PhotoApp).repository) } }
    }
}

private sealed interface Page {
    data object Clients : Page
    data class Jobs(val client: ClientEntity) : Page
    data class Places(val job: JobEntity) : Page
    data class Photos(val place: LocationEntity, val title: String = place.name) : Page
    data class Viewer(val photos: List<PhotoEntity>, val initialIndex: Int) : Page
    data class CloudJob(val jobId: String, val clientName: String, val jobName: String) : Page
    data class CloudPdf(val document: CloudDocument) : Page
    data class CloudViewer(val photos: List<CloudPhoto>, val initialIndex: Int) : Page
    data class Camera(val place: LocationEntity, val title: String = place.name) : Page
    data object Settings : Page
}

private data class ExternalCapture(
    val uri: Uri,
    val relativePath: String,
    val filename: String,
    val capturedAt: OffsetDateTime,
    val withStamp: Boolean,
)

private data class FolderTemplate(val name: String, val folders: List<String>)
private data class MoveTarget(val job: JobEntity, val location: LocationEntity)
private data class CloudJobSummary(val jobId: String, val clientName: String, val jobName: String)
private enum class JobSortMode { LATEST_PHOTO, NAME, CREATED }
private enum class JobDateFilter { CREATED, LAST_PHOTO }

private fun templatePathPreview(raw: String): List<String> = raw.lines().flatMap { line ->
    val parts = line.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotBlank() }
    parts.indices.map { index -> parts.take(index + 1).joinToString("/") }
}.distinct()

private fun displayDateTime(value: String?): String = value?.let {
    runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")) }.getOrDefault(it)
} ?: "ยังไม่มีรูป"

private fun localDateOf(value: String?): LocalDate? = value?.let {
    runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
}

private fun constrainedImageOffset(offset: Offset, scale: Float, viewport: IntSize, content: IntSize): Offset {
    if (scale <= 1f || viewport.width <= 0 || viewport.height <= 0 || content.width <= 0 || content.height <= 0) return Offset.Zero
    val imageRatio = content.width.toFloat() / content.height
    val viewportRatio = viewport.width.toFloat() / viewport.height
    val fittedWidth = if (imageRatio > viewportRatio) viewport.width.toFloat() else viewport.height * imageRatio
    val fittedHeight = if (imageRatio > viewportRatio) viewport.width / imageRatio else viewport.height.toFloat()
    val maxX = ((fittedWidth * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fittedHeight * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private fun loadFolderTemplates(context: Context): List<FolderTemplate> = runCatching {
    val raw = context.getSharedPreferences("folder_templates", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    val array = JSONArray(raw)
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val folders = item.getJSONArray("folders")
        FolderTemplate(item.getString("name"), (0 until folders.length()).map { folders.getString(it) })
    }
}.getOrDefault(emptyList())

private fun saveFolderTemplates(context: Context, templates: List<FolderTemplate>) {
    val array = JSONArray()
    templates.forEach { template ->
        array.put(JSONObject().apply {
            put("name", template.name)
            put("folders", JSONArray(template.folders))
        })
    }
    context.getSharedPreferences("folder_templates", Context.MODE_PRIVATE).edit().putString("items", array.toString()).apply()
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhotoWorkApp(repo: PhotoRepository) {
    var page by remember { mutableStateOf<Page>(Page.Clients) }
    val history = remember { mutableStateListOf<Page>() }
    fun navigate(target: Page) { history.add(page); page = target }
    fun back() { if (history.isNotEmpty()) page = history.removeAt(history.lastIndex) else page = Page.Clients }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "http://192.168.1.53:8080")!!) }
    var showTimestamp by remember { mutableStateOf(prefs.getBoolean("show_timestamp", true)) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var syncModeDialog by remember { mutableStateOf(false) }
    var recoveryNavigationChecked by remember { mutableStateOf(false) }

    BackHandler(enabled = page !is Page.Clients) { back() }

    LaunchedEffect(Unit) {
        if (!recoveryNavigationChecked) {
            recoveryNavigationChecked = true
            val locationId = prefs.getString("oppo_recovery_location", null)
            if (locationId != null) {
                runCatching {
                    val location = repo.dao.location(locationId)
                    val job = repo.dao.job(location.jobId)
                    navigate(Page.Photos(location, job.name))
                }.onFailure {
                    prefs.edit().remove("oppo_recovery_location").remove("oppo_recovery_started")
                        .remove("oppo_recovery_stamp").apply()
                }
            }
        }
    }

    fun sync(statuses: List<UploadStatus>) {
        if (syncing) return
        syncing = true
        syncProgress = SyncProgress(0, 0, "กำลังเชื่อมต่อ Server", 0, 0, 0, 0)
        scope.launch {
            val result = runCatching {
                SyncClient(context, repo.dao).sync(serverUrl, statuses) { progress ->
                    scope.launch { syncProgress = progress }
                }
            }
            message = result.fold(
                {
                    buildString {
                        append("Sync เสร็จ: รูปใหม่ ${it.uploaded}, มีแล้ว ${it.skipped}, รูปผิดพลาด ${it.failed}, โฟลเดอร์ ${it.folders}")
                        if (it.folderFailed > 0) append(", โฟลเดอร์ผิดพลาด ${it.folderFailed}")
                        if (it.errors.isNotEmpty()) append("\n").append(it.errors.take(5).joinToString("\n"))
                    }
                },
                { "Sync ไม่สำเร็จ: ${it.message}" }
            )
            syncing = false
            syncProgress = null
        }
    }

    Scaffold(
        topBar = {
            if (page !is Page.Viewer && page !is Page.CloudViewer) TopAppBar(
                title = { Text(when (val p = page) {
                    Page.Clients -> "งานทั้งหมด"; is Page.Jobs -> p.client.name; is Page.Places -> p.job.name
                    is Page.Photos -> p.title; is Page.Camera -> "ถ่ายรูป — ${p.title}"; Page.Settings -> "ตั้งค่า"
                    is Page.Viewer -> "รูปภาพ"; is Page.CloudJob -> "${p.jobName} — Server"; is Page.CloudPdf -> p.document.filename; is Page.CloudViewer -> "รูปบน Server"
                }) },
                navigationIcon = { if (page !is Page.Clients) IconButton(onClick = { back() }) { Icon(Icons.Default.ArrowBack, "กลับ") } },
                actions = {
                    if (page !is Page.Camera) {
                        IconButton(onClick = { syncModeDialog = true }, enabled = !syncing) { Icon(Icons.Default.Sync, "Backup") }
                        IconButton(onClick = { navigate(Page.Settings) }) { Icon(Icons.Default.Settings, "ตั้งค่า") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val p = page) {
                Page.Clients -> QuickJobsPage(repo, serverUrl,
                    openJob = { place, title -> navigate(Page.Photos(place, title)) },
                    openCloud = { jobId, client, job -> navigate(Page.CloudJob(jobId, client, job)) })
                is Page.Jobs -> JobsPage(repo, p.client) { navigate(Page.Places(it)) }
                is Page.Places -> PlacesPage(repo, p.job) { navigate(Page.Photos(it)) }
                is Page.Photos -> PhotosPage(repo, p.place, p.title,
                    openPhoto = { photos, index -> navigate(Page.Viewer(photos, index)) },
                    openFolder = { folder ->
                        navigate(Page.Photos(folder, "${p.title} › ${folder.name.substringAfterLast('/')}") )
                    })
                is Page.Camera -> CameraPage(repo, p.place) { back() }
                is Page.Viewer -> PhotoViewer(repo, p.photos, p.initialIndex) { back() }
                is Page.CloudJob -> CloudPhotosPage(repo, serverUrl, p.jobId, p.clientName, p.jobName,
                    openPdf = { navigate(Page.CloudPdf(it)) },
                    openPhoto = { photos, index -> navigate(Page.CloudViewer(photos, index)) })
                is Page.CloudPdf -> CloudPdfPage(serverUrl, p.document)
                is Page.CloudViewer -> CloudPhotoViewer(repo, serverUrl, p.photos, p.initialIndex) { back() }
                Page.Settings -> SettingsPage(serverUrl, showTimestamp,
                    saveUrl = { serverUrl = it; prefs.edit().putString("server_url", it).apply() },
                    saveTimestamp = { showTimestamp = it; prefs.edit().putBoolean("show_timestamp", it).apply() })
            }
            if (syncing) {
                val progress = syncProgress
                ElevatedCard(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("กำลัง Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (progress != null && progress.total > 0) Text("${progress.current}/${progress.total}")
                        }
                        if (progress != null && progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.current.toFloat() / progress.total.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress?.label ?: "กำลังเตรียมข้อมูล", maxLines = 1)
                        Text(
                            "ส่งใหม่ ${progress?.uploaded ?: 0} • มีแล้ว ${progress?.skipped ?: 0} • ผิดพลาด ${progress?.failed ?: 0} • โฟลเดอร์ ${progress?.folders ?: 0}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            message?.let { text ->
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton(onClick = { message = null }) { Text("ปิด") } }) { Text(text) }
            }
            if (syncModeDialog) AlertDialog(
                onDismissRequest = { syncModeDialog = false },
                title = { Text("เลือกภาพที่จะ Backup") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { syncModeDialog = false; sync(listOf(UploadStatus.WAITING)) }, Modifier.fillMaxWidth()) { Text("ยังไม่ Backup") }
                        OutlinedButton(onClick = { syncModeDialog = false; sync(listOf(UploadStatus.ERROR)) }, Modifier.fillMaxWidth()) { Text("Backup ผิดพลาด") }
                        OutlinedButton(onClick = { syncModeDialog = false; sync(listOf(UploadStatus.UPLOADED)) }, Modifier.fillMaxWidth()) { Text("สำเร็จแล้ว — ตรวจไฟล์บนคอมอีกครั้ง") }
                        OutlinedButton(onClick = { syncModeDialog = false; sync(UploadStatus.entries.toList()) }, Modifier.fillMaxWidth()) { Text("ทั้งหมด") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { syncModeDialog = false }) { Text("ยกเลิก") } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable private fun QuickJobsPage(
    repo: PhotoRepository, serverUrl: String,
    openJob: (LocationEntity, String) -> Unit, openCloud: (String, String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val jobPreferences = remember { context.getSharedPreferences("job_list", Context.MODE_PRIVATE) }
    var jobSortMode by remember {
        mutableStateOf(runCatching { JobSortMode.valueOf(jobPreferences.getString("sort", JobSortMode.LATEST_PHOTO.name)!!) }
            .getOrDefault(JobSortMode.LATEST_PHOTO))
    }
    var jobSearch by remember { mutableStateOf("") }
    val jobFlow = remember(jobSortMode, jobSearch) {
        val query = jobSearch.trim()
        if (query.isNotEmpty()) repo.dao.searchQuickJobs(query)
        else when (jobSortMode) {
            JobSortMode.LATEST_PHOTO -> repo.dao.quickJobsByLatestPhoto()
            JobSortMode.NAME -> repo.dao.quickJobsByName()
            JobSortMode.CREATED -> repo.dao.quickJobs()
        }
    }
    val rows by jobFlow.collectAsStateWithLifecycle(emptyList())
    var cloudCatalog by remember { mutableStateOf(CloudCatalog()) }
    var cloudLoading by remember { mutableStateOf(false) }
    LaunchedEffect(serverUrl, rows) {
        cloudLoading = true
        cloudCatalog = runCatching { CloudClient().catalog(serverUrl) }.getOrDefault(CloudCatalog())
        cloudLoading = false
    }
    val activityRows by repo.dao.jobActivity().collectAsStateWithLifecycle(emptyList())
    val activityByJob = activityRows.associateBy { it.jobId }
    var dateFilterEnabled by remember { mutableStateOf(false) }
    var dateFilterType by remember { mutableStateOf(JobDateFilter.LAST_PHOTO) }
    var filterStart by remember { mutableStateOf<LocalDate?>(null) }
    var filterEnd by remember { mutableStateOf<LocalDate?>(null) }
    var pickStartDate by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var jobInfo by remember { mutableStateOf<JobEntity?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var quickDialog by remember { mutableStateOf(false) }
    var bulkDialog by remember { mutableStateOf(false) }
    var templateDialog by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<FolderTemplate?>(null) }
    var templates by remember { mutableStateOf(loadFolderTemplates(context)) }
    var bulkNames by remember { mutableStateOf("") }
    var templateName by remember { mutableStateOf("") }
    var templateFolders by remember { mutableStateOf("") }
    var editingTemplateName by remember { mutableStateOf<String?>(null) }
    var jobToRename by remember { mutableStateOf<JobEntity?>(null) }
    val visibleRows = rows.filter { job ->
        if (!dateFilterEnabled) true else {
            val value = when (dateFilterType) {
                JobDateFilter.CREATED -> localDateOf(job.createdAt)
                JobDateFilter.LAST_PHOTO -> localDateOf(activityByJob[job.id]?.lastPhotoAt)
            }
            value != null &&
                (filterStart?.let { !value.isBefore(it) } ?: true) &&
                (filterEnd?.let { !value.isAfter(it) } ?: true)
        }
    }
    val cloudJobs = (cloudCatalog.folders.map { CloudJobSummary(it.jobId, it.clientName, it.jobName) } +
        cloudCatalog.photos.map { CloudJobSummary(it.jobId, it.clientName, it.jobName) } +
        cloudCatalog.documents.map { CloudJobSummary(it.jobId, it.clientName, it.jobName) } +
        cloudCatalog.notes.map { CloudJobSummary(it.jobId, it.clientName, it.jobName) })
        .filter { jobSearch.isBlank() || it.jobName.contains(jobSearch.trim(), true) }
        .distinctBy { if (it.jobId.isNotBlank()) it.jobId else "${it.clientName}/${it.jobName}" }
    var selectedJobIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedJobs = rows.filter { it.id in selectedJobIds }
    val allVisibleJobsSelected = visibleRows.isNotEmpty() && visibleRows.all { it.id in selectedJobIds }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var batchDeleteSummary by remember { mutableStateOf<BackupSummary?>(null) }
    var jobsAwaitingDeleteApproval by remember { mutableStateOf<List<JobEntity>>(emptyList()) }
    LaunchedEffect(rows) { selectedJobIds = selectedJobIds.intersect(rows.mapTo(mutableSetOf()) { it.id }) }
    var jobToDelete by remember { mutableStateOf<JobEntity?>(null) }
    var deleteBackupSummary by remember { mutableStateOf<BackupSummary?>(null) }
    var jobAwaitingDeleteApproval by remember { mutableStateOf<JobEntity?>(null) }
    val jobDeleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val job = jobAwaitingDeleteApproval
        jobAwaitingDeleteApproval = null
        if (result.resultCode == Activity.RESULT_OK && job != null) {
            scope.launch {
                repo.forgetJob(job.id)
                Toast.makeText(context, "ลบงาน ${job.name} แล้ว", Toast.LENGTH_LONG).show()
            }
        }
    }
    val jobsDeleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val jobs = jobsAwaitingDeleteApproval
        jobsAwaitingDeleteApproval = emptyList()
        if (result.resultCode == Activity.RESULT_OK && jobs.isNotEmpty()) {
            scope.launch {
                repo.forgetJobs(jobs.map { it.id })
                selectedJobIds = emptySet()
                Toast.makeText(context, "ลบ ${jobs.size} งานแล้ว", Toast.LENGTH_LONG).show()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selectedJobIds.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedJobIds = emptySet() }) { Icon(Icons.Default.Close, "ยกเลิก") }
                        Text("เลือก ${selectedJobs.size} งาน", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            selectedJobIds = if (allVisibleJobsSelected) selectedJobIds - visibleRows.map { it.id }.toSet()
                            else selectedJobIds + visibleRows.map { it.id }
                        }) { Text(if (allVisibleJobsSelected) "ยกเลิกทั้งหมด" else "เลือกทั้งหมด") }
                        if (selectedJobs.size == 1) IconButton(onClick = { jobToRename = selectedJobs.first() }) {
                            Icon(Icons.Default.Edit, "เปลี่ยนชื่องาน")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val summaries = selectedJobs.map { repo.jobBackupSummary(it.id) }
                                batchDeleteSummary = BackupSummary(
                                    summaries.sumOf { it.waiting }, summaries.sumOf { it.failed }, summaries.sumOf { it.uploaded }
                                )
                                showBatchDeleteConfirm = true
                            }
                        }) { Icon(Icons.Default.Delete, "ลบงานที่เลือก", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            OutlinedTextField(
                value = jobSearch, onValueChange = { jobSearch = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (jobSearch.isNotEmpty()) IconButton(onClick = { jobSearch = "" }) { Icon(Icons.Default.Close, "ล้างการค้นหา") } },
                placeholder = { Text("ค้นหาชื่องานหรือข้อความในโน้ต") }, singleLine = true
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { sortMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Sort, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(when (jobSortMode) {
                                JobSortMode.LATEST_PHOTO -> "รูปล่าสุด"
                                JobSortMode.NAME -> "ชื่องาน"
                                JobSortMode.CREATED -> "สร้างล่าสุด"
                            }, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            listOf(
                                JobSortMode.LATEST_PHOTO to "รูปล่าสุด",
                                JobSortMode.NAME to "ชื่องาน A–Z",
                                JobSortMode.CREATED to "สร้างล่าสุด"
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { if (jobSortMode == mode) Icon(Icons.Default.Check, null) },
                                    onClick = {
                                        jobSortMode = mode
                                        jobPreferences.edit().putString("sort", mode.name).apply()
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    FilledTonalButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.FilterList, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (dateFilterEnabled) "กรองอยู่" else "ตัวกรอง")
                    }
                }
            }
            if (dateFilterEnabled) Text(
                "${if (dateFilterType == JobDateFilter.LAST_PHOTO) "วันที่เพิ่มรูป" else "วันที่สร้างงาน"}: " +
                    "${filterStart?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "เริ่มแรก"} – " +
                    "${filterEnd?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "ปัจจุบัน"}",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
            if (visibleRows.isEmpty() && cloudJobs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (rows.isEmpty() && !cloudLoading) "กด + งานใหม่ เพื่อเริ่มถ่ายรูป" else if (cloudLoading) "กำลังตรวจสอบ Server…" else "ไม่พบงานที่ค้นหา")
            } else LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(cloudJobs, key = { "cloud-${it.jobId}-${it.clientName}-${it.jobName}" }) { cloudJob ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { openCloud(cloudJob.jobId, cloudJob.clientName, cloudJob.jobName) },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 0.dp,
                        shadowElevation = 1.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, "อยู่บน Server", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cloudJob.jobName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("อยู่บน Server • แตะเพื่อเปิดดู", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(visibleRows) { row ->
                    val selected = row.id in selectedJobIds
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                if (selectedJobIds.isEmpty()) scope.launch { openJob(repo.rootForJob(row.id), row.name) }
                                else selectedJobIds = if (selected) selectedJobIds - row.id else selectedJobIds + row.id
                            },
                            onLongClick = { selectedJobIds = if (selected) selectedJobIds - row.id else selectedJobIds + row.id }
                        ),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).background(
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(14.dp)
                                ), contentAlignment = Alignment.Center
                            ) {
                                Icon(if (selected) Icons.Default.Check else Icons.Default.Folder, null,
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("แตะเพื่อเปิด • กดค้างเพื่อเลือก", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selectedJobIds.isEmpty()) IconButton(onClick = { jobInfo = row }) {
                                Icon(Icons.Default.Info, "ข้อมูลงาน")
                            }
                            if (selectedJobIds.isEmpty()) IconButton(onClick = { jobToRename = row }) { Icon(Icons.Default.Edit, "เปลี่ยนชื่องาน") }
                            if (selectedJobIds.isEmpty()) IconButton(onClick = {
                                scope.launch {
                                    deleteBackupSummary = repo.jobBackupSummary(row.id)
                                    jobToDelete = row
                                }
                            }) { Icon(Icons.Default.Delete, "ลบงาน") }
                        }
                    }
                }
            }
        }
        if (selectedJobIds.isEmpty()) ExtendedFloatingActionButton(
            onClick = { createMenu = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            shape = RoundedCornerShape(18.dp),
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("งานใหม่", fontWeight = FontWeight.Bold) }
        )
    }
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            icon = { Icon(Icons.Default.FilterList, null) },
            title = { Text("กรองรายการงาน") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("กรองจาก", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = dateFilterType == JobDateFilter.LAST_PHOTO,
                            onClick = { dateFilterType = JobDateFilter.LAST_PHOTO },
                            leadingIcon = { Icon(Icons.Default.Photo, null, Modifier.size(17.dp)) },
                            label = { Text("วันที่เพิ่มรูป") }
                        )
                        FilterChip(
                            selected = dateFilterType == JobDateFilter.CREATED,
                            onClick = { dateFilterType = JobDateFilter.CREATED },
                            leadingIcon = { Icon(Icons.Default.Work, null, Modifier.size(17.dp)) },
                            label = { Text("วันที่สร้างงาน") }
                        )
                    }
                    HorizontalDivider()
                    Text("ช่วงวันที่", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { pickStartDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DateRange, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ตั้งแต่  ${filterStart?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "วันแรก"}")
                    }
                    OutlinedButton(onClick = { pickEndDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Event, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ถึง  ${filterEnd?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "ปัจจุบัน"}")
                    }
                    if (filterStart != null && filterEnd != null && filterStart!!.isAfter(filterEnd)) {
                        Text("วันเริ่มต้องไม่อยู่หลังวันสิ้นสุด", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = filterStart == null || filterEnd == null || !filterStart!!.isAfter(filterEnd),
                    onClick = { dateFilterEnabled = true; showFilterDialog = false }
                ) { Text("ใช้ตัวกรอง") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dateFilterEnabled = false
                    filterStart = null
                    filterEnd = null
                    showFilterDialog = false
                }) { Text("ล้างตัวกรอง") }
            }
        )
    }
    if (pickStartDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = filterStart?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickStartDate = false },
            confirmButton = { TextButton(onClick = {
                filterStart = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }
                pickStartDate = false
            }) { Text("ตกลง") } },
            dismissButton = { TextButton(onClick = { pickStartDate = false }) { Text("ยกเลิก") } }
        ) { DatePicker(picker) }
    }
    if (pickEndDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = filterEnd?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickEndDate = false },
            confirmButton = { TextButton(onClick = {
                filterEnd = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }
                pickEndDate = false
            }) { Text("ตกลง") } },
            dismissButton = { TextButton(onClick = { pickEndDate = false }) { Text("ยกเลิก") } }
        ) { DatePicker(picker) }
    }
    jobInfo?.let { job ->
        AlertDialog(
            onDismissRequest = { jobInfo = null },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text(job.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("สร้างงาน\n${displayDateTime(job.createdAt)}")
                    Text("เพิ่มรูปล่าสุด\n${displayDateTime(activityByJob[job.id]?.lastPhotoAt)}")
                }
            },
            confirmButton = { TextButton(onClick = { jobInfo = null }) { Text("ปิด") } }
        )
    }
    if (createMenu) AlertDialog(
        onDismissRequest = { createMenu = false },
        title = { Text("เพิ่มงาน") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selectedTemplate = null; createMenu = false; quickDialog = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.NoteAdd, null); Text(" สร้างงานใหม่")
                }
                templates.forEach { template ->
                    FilledTonalButton(onClick = {
                        createMenu = false
                        selectedTemplate = template
                        quickDialog = true
                    }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderCopy, null); Text(" ใช้ Template: ${template.name}")
                    }
                }
                OutlinedButton(onClick = { createMenu = false; bulkDialog = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlaylistAdd, null); Text(" เพิ่มหลายงานพร้อมกัน")
                }
                TextButton(onClick = { createMenu = false; templateDialog = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Settings, null); Text(" จัดการ Template โฟลเดอร์")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { createMenu = false }) { Text("ยกเลิก") } }
    )
    if (quickDialog) NameDialog(
        if (selectedTemplate == null) "กรอกชื่องาน" else "ชื่องานใหม่ — ${selectedTemplate!!.name}",
        initialValue = selectedTemplate?.name.orEmpty(),
        confirmLabel = "สร้างงาน",
        onDismiss = { quickDialog = false }
    ) { value ->
        val template = selectedTemplate
        scope.launch {
            runCatching {
                if (template == null) repo.createQuickJob(value) to value
                else repo.createQuickJobFromTemplate(value.ifBlank { template.name }, template.folders)
            }.onSuccess { (root, actualName) -> openJob(root, actualName) }
                .onFailure { Toast.makeText(context, "สร้างงานไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        quickDialog = false
    }
    if (bulkDialog) AlertDialog(
        onDismissRequest = { bulkDialog = false },
        title = { Text("เพิ่มหลายงาน") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ใส่ชื่องานหนึ่งงานต่อหนึ่งบรรทัด")
                OutlinedTextField(
                    value = bulkNames, onValueChange = { bulkNames = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("LHAY69002508\nLHAY69002655\nงาน 2026-08-15") }
                )
                Text("โครงสร้าง: ${selectedTemplate?.name ?: "ไม่มี Template"}", fontWeight = FontWeight.Bold)
                FilterChip(selected = selectedTemplate == null, onClick = { selectedTemplate = null }, label = { Text("ไม่ใช้ Template") })
                templates.forEach { template ->
                    FilterChip(
                        selected = selectedTemplate?.name == template.name,
                        onClick = { selectedTemplate = template },
                        label = { Text(template.name) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val names = bulkNames.lines().map { it.trim() }.filter { it.isNotBlank() }
                val folders = selectedTemplate?.folders.orEmpty()
                bulkDialog = false
                scope.launch {
                    val count = repo.createQuickJobs(names, folders)
                    Toast.makeText(context, "สร้างแล้ว $count งาน", Toast.LENGTH_LONG).show()
                    bulkNames = ""
                }
            }, enabled = bulkNames.isNotBlank()) { Text("สร้างทั้งหมด") }
        },
        dismissButton = { TextButton(onClick = { bulkDialog = false }) { Text("ยกเลิก") } }
    )
    if (templateDialog) AlertDialog(
        onDismissRequest = { templateDialog = false },
        title = { Text("Template โฟลเดอร์") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { template ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(template.name, fontWeight = FontWeight.Bold)
                            Text(template.folders.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            editingTemplateName = template.name
                            templateName = template.name
                            templateFolders = template.folders.joinToString("\n")
                        }) { Icon(Icons.Default.Edit, "แก้ไข Template") }
                        IconButton(onClick = {
                            templates = templates.filterNot { it.name == template.name }
                            saveFolderTemplates(context, templates)
                        }) { Icon(Icons.Default.Delete, "ลบ Template") }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(templateName, { templateName = it }, label = { Text("ชื่อ Template") }, singleLine = true)
                OutlinedTextField(
                    templateFolders, { templateFolders = it },
                    label = { Text("โครงสร้างโฟลเดอร์ — หนึ่ง path ต่อบรรทัด") },
                    placeholder = { Text("จุดที่ 1/ก่อนทำ/ตู้ไฟ 1\nจุดที่ 1/ก่อนทำ/ตู้ไฟ 2\nจุดที่ 1/ก่อนทำ/ตู้ไฟ 3") },
                    modifier = Modifier.heightIn(min = 120.dp)
                )
                Text("ใช้เครื่องหมาย / คั่นแต่ละระดับ ไม่ต้องพิมพ์โฟลเดอร์แม่ซ้ำ ระบบสร้างให้เอง", style = MaterialTheme.typography.bodySmall)
                Text("ถ้ามีหลายโฟลเดอร์ในแม่เดียวกัน ให้เขียน path เต็มแยกคนละบรรทัด เช่น ก่อนทำ/ตู้ไฟ 1 และ ก่อนทำ/ตู้ไฟ 2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                val preview = templatePathPreview(templateFolders)
                if (preview.isNotEmpty()) Surface(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ตัวอย่างโครงสร้างที่จะสร้าง", fontWeight = FontWeight.Bold)
                        preview.forEach { path ->
                            val depth = path.count { it == '/' }
                            Row(Modifier.padding(start = (depth * 18).dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp)); Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val newTemplate = FolderTemplate(templateName.trim(), templateFolders.lines().map { it.trim().trim('/') }.filter { it.isNotBlank() })
                templates = templates.filterNot { it.name == editingTemplateName || it.name == newTemplate.name } + newTemplate
                saveFolderTemplates(context, templates)
                editingTemplateName = null; templateName = ""; templateFolders = ""
            }, enabled = templateName.isNotBlank() && templateFolders.isNotBlank()) {
                Text(if (editingTemplateName == null) "บันทึกใหม่" else "บันทึกการแก้ไข")
            }
        },
        dismissButton = { TextButton(onClick = { templateDialog = false; editingTemplateName = null; templateName = ""; templateFolders = "" }) { Text("ปิด") } }
    )
    if (showBatchDeleteConfirm) AlertDialog(
        onDismissRequest = { showBatchDeleteConfirm = false },
        title = { Text("ลบ ${selectedJobs.size} งานที่เลือก?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val backup = batchDeleteSummary
                if (backup != null && backup.needsAttention > 0) {
                    Text("คำเตือน: ยัง Backup ไม่ครบ", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    if (backup.waiting > 0) Text("• ยังไม่ Backup ${backup.waiting} รูป")
                    if (backup.failed > 0) Text("• Backup ผิดพลาด ${backup.failed} รูป")
                }
                Text("งานและโฟลเดอร์ที่เลือกจะถูกลบจาก DN รูปที่ DN สร้างจะถูกลบจาก Gallery ด้วย")
                Text("รูปต้นฉบับที่เคยนำเข้าจาก Gallery จะยังอยู่ใน Gallery ตามเดิม")
                Text("การดำเนินการนี้ย้อนกลับไม่ได้", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = {
                val jobs = selectedJobs
                showBatchDeleteConfirm = false
                scope.launch {
                    runCatching { repo.jobsDeleteApproval(jobs) }
                        .onSuccess { sender ->
                            if (sender == null) {
                                repo.forgetJobs(jobs.map { it.id })
                                selectedJobIds = emptySet()
                                Toast.makeText(context, "ลบ ${jobs.size} งานแล้ว", Toast.LENGTH_LONG).show()
                            } else {
                                jobsAwaitingDeleteApproval = jobs
                                jobsDeleteApproval.launch(IntentSenderRequest.Builder(sender).build())
                            }
                        }
                        .onFailure { Toast.makeText(context, "ลบงานไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("ยืนยันลบทั้งหมด") }
        },
        dismissButton = { TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("ยกเลิก") } }
    )
    jobToRename?.let { job ->
        RenameDialog("เปลี่ยนชื่องาน", job.name, onDismiss = { jobToRename = null }) { value ->
            jobToRename = null
            scope.launch {
                runCatching { repo.renameJob(job, value) }
                    .onSuccess { Toast.makeText(context, "เปลี่ยนชื่อเป็น $it แล้ว", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "เปลี่ยนชื่อไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    jobToDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { jobToDelete = null },
            title = { Text("ลบงาน ${job.name}?") },
            text = {
                val backup = deleteBackupSummary
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (backup != null && backup.needsAttention > 0) {
                        Text("คำเตือน: งานนี้ยัง Backup ไม่ครบ", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        if (backup.waiting > 0) Text("• ยังไม่ Backup ${backup.waiting} รูป")
                        if (backup.failed > 0) Text("• Backup ผิดพลาด ${backup.failed} รูป")
                    }
                    Text("งานและรูปที่ DN สร้างจะถูกลบ รูปต้นฉบับที่นำเข้าจะถูกเอาออกจาก DN แต่ยังเก็บอยู่ใน Gallery")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        jobToDelete = null
                        scope.launch {
                            runCatching { repo.jobDeleteApproval(job) }
                                .onSuccess { sender ->
                                    if (sender == null) {
                                        repo.forgetJob(job.id)
                                        Toast.makeText(context, "ลบงาน ${job.name} แล้ว", Toast.LENGTH_LONG).show()
                                    } else {
                                        jobAwaitingDeleteApproval = job
                                        jobDeleteApproval.launch(IntentSenderRequest.Builder(sender).build())
                                    }
                                }
                                .onFailure { Toast.makeText(context, "ลบงานไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("ลบงานทั้งหมด") }
            },
            dismissButton = { TextButton(onClick = { jobToDelete = null }) { Text("ยกเลิก") } }
        )
    }
}

@Composable private fun JobsPage(repo: PhotoRepository, client: ClientEntity, open: (JobEntity) -> Unit) {
    val rows by repo.dao.jobs(client.id).collectAsStateWithLifecycle(emptyList())
    EntityList(rows, { it.name }, open, "ยังไม่มีงาน", "เพิ่มงาน") { name -> repo.addJob(client.id, name) }
}

@Composable private fun PlacesPage(repo: PhotoRepository, job: JobEntity, open: (LocationEntity) -> Unit) {
    val rows by repo.dao.locations(job.id).collectAsStateWithLifecycle(emptyList())
    EntityList(rows, { it.name }, open, "ยังไม่มีสถานที่", "เพิ่มสถานที่") { name -> repo.addLocation(job.id, name) }
}

@Composable
private fun <T> EntityList(rows: List<T>, label: (T) -> String, open: (T) -> Unit, empty: String, addLabel: String, add: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope(); var dialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) Text(empty, Modifier.align(Alignment.Center))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows) { row ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { open(row) }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null); Spacer(Modifier.width(12.dp)); Text(label(row), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { dialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), icon = { Icon(Icons.Default.Add, null) }, text = { Text(addLabel) })
    }
    if (dialog) NameDialog(addLabel, onDismiss = { dialog = false }) { value -> scope.launch { add(value) }; dialog = false }
}

@Composable private fun NameDialog(title: String, initialValue: String = "", confirmLabel: String = "บันทึก", onDismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(
            onClick = { if (value.isNotBlank() || initialValue.isNotBlank()) save(value.trim().ifBlank { initialValue }) },
            enabled = value.isNotBlank() || initialValue.isNotBlank()
        ) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } })
}

@Composable private fun RenameDialog(title: String, initialValue: String, onDismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { save(value.trim()) }, enabled = value.isNotBlank()) { Text("บันทึก") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PhotosPage(
    repo: PhotoRepository, place: LocationEntity, pageTitle: String,
    openPhoto: (List<PhotoEntity>, Int) -> Unit, openFolder: (LocationEntity) -> Unit
) {
    val rows by repo.dao.photos(place.id).collectAsStateWithLifecycle(emptyList())
    val documents by repo.dao.documents(place.id).collectAsStateWithLifecycle(emptyList())
    val notes by repo.dao.notes(place.id).collectAsStateWithLifecycle(emptyList())
    val allFolders by repo.dao.locations(place.jobId).collectAsStateWithLifecycle(emptyList())
    val prefix = if (place.name.isBlank()) "" else "${place.name}/"
    val childFolders = allFolders.filter { candidate ->
        candidate.id != place.id && candidate.name.startsWith(prefix) && !candidate.name.removePrefix(prefix).contains('/')
    }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val displayPreferences = remember { context.getSharedPreferences("photo_display", Context.MODE_PRIVATE) }
    val recoveryPreferences = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var gridColumns by remember { mutableIntStateOf(displayPreferences.getInt("columns", 2).coerceIn(1, 5)) }
    var selectedPhotoIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var documentToDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var photosAwaitingDeleteApproval by remember { mutableStateOf<List<PhotoEntity>>(emptyList()) }
    var photoDeleteOptions by remember { mutableStateOf<PhotoEntity?>(null) }
    var showSelectedDeleteOptions by remember { mutableStateOf(false) }
    var recoveryCandidates by remember { mutableStateOf<List<RecentImage>>(emptyList()) }
    var selectedRecoveryUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryWithStamp by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var noteInfo by remember { mutableStateOf<NoteEntity?>(null) }
    var noteTitle by remember { mutableStateOf("") }
    var noteContent by remember { mutableStateOf("") }
    var movePhotoIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var moveFolderSource by remember { mutableStateOf<LocationEntity?>(null) }
    var moveTargets by remember { mutableStateOf<List<MoveTarget>>(emptyList()) }
    var moveBrowseJobId by remember { mutableStateOf<String?>(null) }
    var moveBrowsePath by remember { mutableStateOf("") }
    var showMoveDialog by remember { mutableStateOf(false) }
    val selectedPhotos = rows.filter { it.id in selectedPhotoIds }
    LaunchedEffect(rows) {
        selectedPhotoIds = selectedPhotoIds.intersect(rows.mapTo(mutableSetOf()) { it.id })
    }
    LaunchedEffect(documents) {
        selectedDocumentIds = selectedDocumentIds.intersect(documents.mapTo(mutableSetOf()) { it.id })
    }
    val batchDeleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val photos = photosAwaitingDeleteApproval
        photosAwaitingDeleteApproval = emptyList()
        if (result.resultCode == Activity.RESULT_OK && photos.isNotEmpty()) {
            scope.launch {
                repo.forgetPhotos(photos.map { it.id })
                selectedPhotoIds = emptySet()
            }
        }
    }

    fun togglePhoto(photoId: String) {
        selectedPhotoIds = if (photoId in selectedPhotoIds) selectedPhotoIds - photoId else selectedPhotoIds + photoId
    }

    fun prepareMove(photos: List<String> = emptyList(), folder: LocationEntity? = null) {
        scope.launch {
            val jobs = repo.dao.allJobsNow().associateBy { it.id }
            moveTargets = repo.dao.allLocationsNow().mapNotNull { location -> jobs[location.jobId]?.let { MoveTarget(it, location) } }
            movePhotoIds = photos
            moveFolderSource = folder
            moveBrowseJobId = null
            moveBrowsePath = ""
            showMoveDialog = true
        }
    }

    fun shareSelectedPhotos() {
        val uris = ArrayList(selectedPhotos.map { Uri.parse(it.contentUri) })
        if (uris.isEmpty()) return
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "DN photos", uris.first()).also { clips ->
                uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
            }
        }
        context.startActivity(Intent.createChooser(share, "แชร์ ${uris.size} รูป"))
    }

    fun shareSelectedDocuments() {
        val selected = documents.filter { it.id in selectedDocumentIds }
        val uris = ArrayList(selected.map { Uri.parse(it.contentUri) })
        if (uris.isEmpty()) return
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(context.contentResolver, selected.first().filename, uris.first()).also { clips ->
                uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(share, "แชร์ PDF ${uris.size} ไฟล์")) }
            .onFailure { Toast.makeText(context, "ไม่พบแอปที่รองรับการแชร์ PDF หลายไฟล์", Toast.LENGTH_LONG).show() }
    }

    fun deleteSelectedPhotosFromDevice() {
        val photos = selectedPhotos
        if (photos.isEmpty()) return
        scope.launch {
            runCatching { repo.photosDeleteApproval(photos) }
                .onSuccess { sender ->
                    if (sender == null) {
                        repo.forgetPhotos(photos.map { it.id })
                        selectedPhotoIds = emptySet()
                    } else {
                        photosAwaitingDeleteApproval = photos
                        batchDeleteApproval.launch(IntentSenderRequest.Builder(sender).build())
                    }
                }
                .onFailure { Toast.makeText(context, "ลบรูปไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            delay(1000)
            repo.reconcileMissingPhotos(place.id)
        }
    }
    var pendingExternal by remember { mutableStateOf<ExternalCapture?>(null) }
    var pendingOppoStamp by remember { mutableStateOf<Boolean?>(null) }
    var pendingOppoStartedAt by remember { mutableLongStateOf(0L) }
    var externalModeDialog by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<LocationEntity?>(null) }
    var folderAwaitingDeleteApproval by remember { mutableStateOf<LocationEntity?>(null) }
    val folderDeleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val folder = folderAwaitingDeleteApproval
        folderAwaitingDeleteApproval = null
        if (result.resultCode == Activity.RESULT_OK && folder != null) {
            scope.launch {
                repo.forgetFolder(folder)
                Toast.makeText(context, "ลบโฟลเดอร์แล้ว", Toast.LENGTH_LONG).show()
            }
        }
    }
    var pendingPhotoDelete by remember { mutableStateOf<PhotoEntity?>(null) }
    val mediaDeleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val photo = pendingPhotoDelete
        pendingPhotoDelete = null
        if (result.resultCode == Activity.RESULT_OK && photo != null) {
            scope.launch { repo.forgetPhoto(photo.id) }
        }
    }
    val documentScanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
    }
    val documentScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            result?.pdf?.let { pdf ->
                scope.launch {
                    runCatching { repo.saveScannedPdf(place.id, pdf.uri, pdf.pageCount) }
                        .onSuccess { Toast.makeText(context, "บันทึก PDF ${pdf.pageCount} หน้าแล้ว", Toast.LENGTH_LONG).show() }
                        .onFailure { Toast.makeText(context, "บันทึก PDF ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
    val pdfImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                runCatching { repo.importPdf(place.id, it) }
                    .onSuccess { pages -> Toast.makeText(context, "นำเข้า PDF $pages หน้าแล้ว", Toast.LENGTH_LONG).show() }
                    .onFailure { error -> Toast.makeText(context, "นำเข้า PDF ไม่สำเร็จ: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    fun startDocumentScanner() {
        val activity = context as? Activity ?: return
        documentScanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender -> documentScannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { Toast.makeText(context, "เปิดสแกนเอกสารไม่ได้: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    fun importGalleryUris(uris: List<Uri>) {
        scope.launch {
            var added = 0
            var protected = 0
            uris.forEach { uri ->
                if (!repo.media.canRequestDelete(uri)) {
                    protected++
                    return@forEach
                }
                runCatching {
                    val exif = repo.media.readExif(uri)
                    repo.attachGalleryPhoto(
                        place.id, uri, exif.capturedAt ?: OffsetDateTime.now(),
                        exif.latitude, exif.longitude, null
                    )
                }.onSuccess { added++ }
            }
            if (uris.isNotEmpty()) Toast.makeText(
                context,
                if (protected == 0) "เพิ่ม $added รูปโดยใช้ไฟล์เดิม ไม่สร้างสำเนา"
                else "เพิ่ม $added รูป • ข้าม $protected รูปที่ Android ไม่ให้สิทธิ์ลบ",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun galleryPickIntent() = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data
        val uris = buildList {
            data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
            }
            if (isEmpty()) data?.data?.let { add(it) }
        }.distinct()
        importGalleryUris(uris)
    }
    val galleryReadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) galleryPicker.launch(galleryPickIntent())
        else Toast.makeText(context, "ต้องอนุญาตการเข้าถึงรูป เพื่อให้ DN ลบรูปพร้อมงานได้", Toast.LENGTH_LONG).show()
    }
    fun openGalleryImporter() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            galleryPicker.launch(galleryPickIntent())
        } else galleryReadPermission.launch(permission)
    }
    fun finishExternalCapture(capture: ExternalCapture) {
        pendingExternal = null
        fun save(latitude: Double?, longitude: Double?, accuracy: Float?) {
            scope.launch {
                runCatching {
                    repo.registerCaptured(place.id, capture.uri, capture.relativePath, capture.filename,
                        capture.capturedAt, latitude, longitude, accuracy, capture.withStamp)
                }.onFailure { Toast.makeText(context, "บันทึกรูปไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnCompleteListener { task ->
                    val current = task.result?.takeIf {
                        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
                            !(it.latitude == 0.0 && it.longitude == 0.0)
                    }
                    current?.let { save(it.latitude, it.longitude, it.accuracy) } ?: save(null, null, null)
                }
        } else save(null, null, null)
    }

    val oppoPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        val capture = pendingExternal
        if (capture == null) return@rememberLauncherForActivityResult
        if (selected == null) {
            repo.media.cancel(capture.uri)
            pendingExternal = null
            return@rememberLauncherForActivityResult
        }
        runCatching { repo.media.replaceContent(selected, capture.uri) }
            .onSuccess { finishExternalCapture(capture) }
            .onFailure {
                repo.media.cancel(capture.uri)
                pendingExternal = null
                Toast.makeText(context, "นำรูปจาก OPPO Gallery ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun importOppoPhotos(selected: List<Pair<Uri, Long>>, withStamp: Boolean, useCurrentLocation: Boolean = true) {
        if (selected.isEmpty()) return
        fun importAll(latitude: Double?, longitude: Double?, accuracy: Float?) {
            scope.launch {
                var imported = 0
                selected.forEach { (uri, takenAt) ->
                    val capturedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(takenAt), ZoneId.systemDefault())
                    runCatching {
                        if (withStamp) {
                            val info = repo.media.galleryInfo(uri)
                            val exif = repo.media.readExif(uri)
                            val actualTime = exif.capturedAt ?: capturedAt
                            val actualLat = exif.latitude ?: latitude
                            val actualLon = exif.longitude ?: longitude
                            repo.attachGalleryPhoto(place.id, uri, actualTime, actualLat, actualLon, accuracy)
                            repo.createTimestampFromSource(place.id, uri, info.filename, actualTime, actualLat, actualLon, accuracy)
                        } else repo.attachGalleryPhoto(place.id, uri, capturedAt, latitude, longitude, accuracy)
                    }
                        .onSuccess { imported++ }
                }
                recoveryPreferences.edit().remove("oppo_recovery_location").remove("oppo_recovery_started")
                    .remove("oppo_recovery_stamp").apply()
                Toast.makeText(context, "นำเข้ารูปจาก OPPO $imported รูปแล้ว", Toast.LENGTH_LONG).show()
            }
        }
        if (useCurrentLocation && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnCompleteListener { task ->
                    val current = task.result?.takeIf {
                        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
                            !(it.latitude == 0.0 && it.longitude == 0.0)
                    }
                    current?.let { importAll(it.latitude, it.longitude, it.accuracy) } ?: importAll(null, null, null)
                }
        } else importAll(null, null, null)
    }

    LaunchedEffect(place.id) {
        val recoveryLocation = recoveryPreferences.getString("oppo_recovery_location", null)
        val recoveryStarted = recoveryPreferences.getLong("oppo_recovery_started", 0L)
        if (recoveryLocation == place.id && recoveryStarted > 0L) {
            val mediaPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
                delay(700)
                recoveryCandidates = runCatching { repo.media.imagesAddedSince(recoveryStarted) }.getOrDefault(emptyList()).takeLast(100)
                recoveryWithStamp = recoveryPreferences.getBoolean("oppo_recovery_stamp", false)
                selectedRecoveryUris = emptySet()
                showRecoveryDialog = recoveryCandidates.isNotEmpty()
            }
        }
    }

    val oppoBatchPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { selected ->
        val withStamp = pendingOppoStamp ?: false
        pendingOppoStamp = null
        importOppoPhotos(selected.map { it to System.currentTimeMillis() }, withStamp)
    }

    val externalCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingOppoStamp != null) {
            val withStamp = pendingOppoStamp ?: false
            scope.launch {
                delay(2500)
                val mediaPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                val canReadGallery = ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
                val recent = if (canReadGallery) runCatching { repo.media.imagesAddedSince(pendingOppoStartedAt) }.getOrDefault(emptyList()) else emptyList()
                if (recent.isNotEmpty()) {
                    pendingOppoStamp = null
                    importOppoPhotos(recent.map { it.uri to it.capturedAtMillis }, withStamp)
                } else {
                    Toast.makeText(context, "หาไฟล์ใหม่อัตโนมัติไม่พบ กรุณาเลือกภาพที่เพิ่งถ่าย", Toast.LENGTH_LONG).show()
                    oppoBatchPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }

    fun openSystemCamera(withStamp: Boolean) {
        runCatching {
            pendingOppoStamp = withStamp
            pendingOppoStartedAt = System.currentTimeMillis()
            recoveryPreferences.edit()
                .putString("oppo_recovery_location", place.id)
                .putLong("oppo_recovery_started", pendingOppoStartedAt)
                .putBoolean("oppo_recovery_stamp", withStamp)
                .apply()
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            if (intent.resolveActivity(context.packageManager) == null) kotlin.error("ไม่พบแอปกล้องในเครื่อง")
            Toast.makeText(context, "ถ่ายต่อเนื่องได้เลย เสร็จแล้วใช้ปุ่มหรือท่าทางย้อนกลับของ Android", Toast.LENGTH_LONG).show()
            externalCamera.launch(intent)
        }.onFailure {
            pendingOppoStamp = null
            Toast.makeText(context, it.message ?: "เปิดกล้องไม่ได้", Toast.LENGTH_LONG).show()
        }
    }
    fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "ไม่พบแอป Gallery", Toast.LENGTH_SHORT).show() }
    }
    val externalCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) externalModeDialog = true
        else Toast.makeText(context, "ต้องอนุญาตกล้องก่อน", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize()) {
        if (selectedDocumentIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedDocumentIds = emptySet() }) { Icon(Icons.Default.Close, "ยกเลิก") }
                Text("เลือกแล้ว ${selectedDocumentIds.size} PDF", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = ::shareSelectedDocuments) { Icon(Icons.Default.Share, "แชร์ PDF ที่เลือก") }
            }
        } else if (selectedPhotoIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedPhotoIds = emptySet() }) { Icon(Icons.Default.Close, "ยกเลิก") }
                Text("เลือกแล้ว ${selectedPhotos.size} รูป", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = { prepareMove(selectedPhotos.map { it.id }) }) { Icon(Icons.Default.DriveFileMove, "ย้ายรูป") }
                IconButton(onClick = ::shareSelectedPhotos) { Icon(Icons.Default.Share, "แชร์รูปที่เลือก") }
                IconButton(onClick = { showSelectedDeleteOptions = true }) {
                    Icon(Icons.Default.Delete, "ลบรูปที่เลือก", tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text("รูปต่อแถว")
                IconButton(onClick = {
                    gridColumns = (gridColumns - 1).coerceAtLeast(1)
                    displayPreferences.edit().putInt("columns", gridColumns).apply()
                }, enabled = gridColumns > 1) { Icon(Icons.Default.Remove, "ลดจำนวนรูปต่อแถว") }
                Text("$gridColumns", fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    gridColumns = (gridColumns + 1).coerceAtMost(5)
                    displayPreferences.edit().putInt("columns", gridColumns).apply()
                }, enabled = gridColumns < 5) { Icon(Icons.Default.Add, "เพิ่มจำนวนรูปต่อแถว") }
            }
        }
        if (rows.isEmpty() && childFolders.isEmpty() && documents.isEmpty() && notes.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("ยังไม่มีข้อมูลใน $pageTitle") }
        else LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            gridItems(childFolders, key = { "folder-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { openFolder(folder) }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null)
                        Spacer(Modifier.width(12.dp))
                        Text(folder.name.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { prepareMove(folder = folder) }) { Icon(Icons.Default.DriveFileMove, "ย้ายโฟลเดอร์") }
                        IconButton(onClick = { folderToDelete = folder }) { Icon(Icons.Default.Delete, "ลบโฟลเดอร์") }
                    }
                }
            }
            gridItems(documents, key = { "document-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { document ->
                val isSelected = document.id in selectedDocumentIds
                ElevatedCard(Modifier.fillMaxWidth().combinedClickable(
                    onClick = {
                        if (selectedDocumentIds.isNotEmpty()) {
                            selectedDocumentIds = if (isSelected) selectedDocumentIds - document.id else selectedDocumentIds + document.id
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(document.contentUri), "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure { Toast.makeText(context, "ไม่พบแอปเปิด PDF", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onLongClick = {
                        selectedPhotoIds = emptySet()
                        selectedDocumentIds = selectedDocumentIds + document.id
                    }
                )) {
                    Row(
                        Modifier.fillMaxWidth().background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, "เลือกแล้ว", tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.filename, fontWeight = FontWeight.Bold)
                            Text("PDF ${document.pageCount} หน้า • ${document.status}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (selectedDocumentIds.isEmpty()) IconButton(onClick = {
                            val uri = Uri.parse(document.contentUri)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, document.filename, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(share, "แชร์ ${document.filename}")) }
                                .onFailure { Toast.makeText(context, "ไม่พบแอปสำหรับแชร์ PDF", Toast.LENGTH_SHORT).show() }
                        }) { Icon(Icons.Default.Share, "แชร์ PDF") }
                        if (selectedDocumentIds.isEmpty()) IconButton(onClick = { documentToDelete = document }) { Icon(Icons.Default.Delete, "ลบ PDF") }
                    }
                }
            }
            gridItems(notes, key = { "note-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { note ->
                ElevatedCard(Modifier.fillMaxWidth().clickable {
                    editingNote = note
                    noteTitle = note.title
                    noteContent = note.content
                    noteDialog = true
                }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.StickyNote2, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(note.title, fontWeight = FontWeight.Bold)
                            Text(note.content, maxLines = 3)
                            Text(note.status.name, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { noteInfo = note }) { Icon(Icons.Default.Info, "ข้อมูลโน้ต") }
                        IconButton(onClick = {
                            editingNote = note
                            noteTitle = note.title
                            noteContent = note.content
                            noteDialog = true
                        }) { Icon(Icons.Default.Edit, "แก้ไขโน้ต") }
                        IconButton(onClick = { scope.launch { repo.dao.deleteNote(note.id) } }) { Icon(Icons.Default.Delete, "ลบโน้ต") }
                    }
                }
            }
            gridItemsIndexed(rows, key = { _, photo -> photo.id }) { index, photo ->
                val selected = photo.id in selectedPhotoIds
                ElevatedCard(
                    Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(
                        onClick = { if (selectedPhotoIds.isEmpty()) openPhoto(rows, index) else togglePhoto(photo.id) },
                        onLongClick = { togglePhoto(photo.id) }
                    )
                ) { Box(Modifier.fillMaxSize()) {
                    AsyncImage(Uri.parse(photo.contentUri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (selected) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)))
                        Icon(
                            Icons.Default.CheckCircle, "เลือกแล้ว", tint = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                        )
                    }
                    if (selectedPhotoIds.isEmpty()) Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(30.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .clickable { photoDeleteOptions = photo },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Delete, "ลบ", tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                } }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp
        ) {
          Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = {
                        val mediaPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                        externalCameraPermission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, mediaPermission))
                    }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.CameraAlt, null, Modifier.size(22.dp)) }
                    Text("กล้อง OPPO", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = ::openGalleryImporter,
                        modifier = Modifier.size(44.dp)
                    ) { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(22.dp)) }
                    Text("นำเข้ารูป", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = ::openGallery, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(22.dp))
                    }
                    Text("เปิด Gallery", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = { folderDialog = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.CreateNewFolder, null, Modifier.size(22.dp))
                    }
                    Text("โฟลเดอร์", style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = ::startDocumentScanner, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.DocumentScanner, null, Modifier.size(22.dp))
                    }
                    Text("สแกน PDF", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = { pdfImportLauncher.launch(arrayOf("application/pdf")) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.UploadFile, null, Modifier.size(22.dp))
                    }
                    Text("นำเข้า PDF", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = {
                        editingNote = null
                        noteTitle = ""
                        noteContent = ""
                        noteDialog = true
                    }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.NoteAdd, null, Modifier.size(22.dp))
                    }
                    Text("เพิ่มโน้ต", style = MaterialTheme.typography.labelSmall)
                }
            }
          }
        }
    }
    documentToDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("ลบ PDF นี้?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(document.filename, fontWeight = FontWeight.Bold)
                    Text("PDF ${document.pageCount} หน้า")
                    if (document.status != UploadStatus.UPLOADED) {
                        Text("ไฟล์นี้ยัง Backup ไม่สำเร็จ", color = MaterialTheme.colorScheme.error)
                    }
                    Text("ไฟล์จะถูกลบออกจาก DN และพื้นที่เก็บเอกสารในมือถือ")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        documentToDelete = null
                        scope.launch {
                            runCatching { repo.removeDocument(document) }
                                .onFailure { Toast.makeText(context, "ลบ PDF ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("ลบ PDF") }
            },
            dismissButton = { TextButton(onClick = { documentToDelete = null }) { Text("ยกเลิก") } }
        )
    }
    fun movePickerBack() {
        if (moveBrowseJobId == null) showMoveDialog = false
        else if (moveBrowsePath.isBlank()) moveBrowseJobId = null
        else moveBrowsePath = moveBrowsePath.substringBeforeLast('/', "")
    }
    BackHandler(enabled = showMoveDialog) { movePickerBack() }
    if (showMoveDialog) {
        val browseJob = moveBrowseJobId?.let { id -> moveTargets.firstOrNull { it.job.id == id }?.job }
        val currentTarget = moveBrowseJobId?.let { jobId ->
            moveTargets.firstOrNull { it.job.id == jobId && it.location.name == moveBrowsePath }
        }
        val jobs = moveTargets.map { it.job }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        val children = moveBrowseJobId?.let { jobId ->
            val prefix = if (moveBrowsePath.isBlank()) "" else "$moveBrowsePath/"
            moveTargets.filter { target ->
                target.job.id == jobId && target.location.name.startsWith(prefix) &&
                    target.location.name != moveBrowsePath &&
                    !target.location.name.removePrefix(prefix).contains('/')
            }.sortedBy { it.location.name.lowercase() }
        }.orEmpty()
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            icon = { Icon(Icons.Default.DriveFileMove, null) },
            title = { Text(if (moveFolderSource == null) "ย้าย ${movePhotoIds.size} รูป" else "ย้ายโฟลเดอร์ ${moveFolderSource?.name?.substringAfterLast('/')}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::movePickerBack) { Icon(Icons.Default.ArrowBack, "ย้อนกลับ") }
                        Column {
                            Text(browseJob?.name ?: "เลือกงานปลายทาง", fontWeight = FontWeight.Bold)
                            if (browseJob != null) Text(
                                moveBrowsePath.ifBlank { "โฟลเดอร์หลัก" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (moveBrowseJobId == null) items(jobs, key = { it.id }) { job ->
                            ElevatedCard(Modifier.fillMaxWidth().clickable {
                                moveBrowseJobId = job.id; moveBrowsePath = ""
                            }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Work, null); Spacer(Modifier.width(10.dp))
                                    Text(job.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        } else items(children, key = { it.location.id }) { target ->
                            ElevatedCard(Modifier.fillMaxWidth().clickable { moveBrowsePath = target.location.name }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, null); Spacer(Modifier.width(10.dp))
                                    Text(target.location.name.substringAfterLast('/'), Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                        if (moveBrowseJobId != null && children.isEmpty()) item {
                            Text("ไม่มีโฟลเดอร์ย่อย", Modifier.fillMaxWidth().padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("เข้าไปยังตำแหน่งที่ต้องการ แล้วกด “ย้ายมาที่นี่”", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = currentTarget ?: return@Button
                    scope.launch {
                        runCatching {
                            moveFolderSource?.let { repo.moveFolder(it, target.location) }
                                ?: repo.movePhotos(movePhotoIds, target.location.id)
                        }.onSuccess {
                            selectedPhotoIds = emptySet(); showMoveDialog = false
                            Toast.makeText(context, "ย้ายไป ${target.job.name} / ${target.location.name.ifBlank { "โฟลเดอร์หลัก" }} แล้ว", Toast.LENGTH_LONG).show()
                        }.onFailure { Toast.makeText(context, "ย้ายไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }, enabled = currentTarget != null) { Text("ย้ายมาที่นี่") }
            },
            dismissButton = { TextButton(onClick = { showMoveDialog = false }) { Text("ยกเลิก") } }
        )
    }
    if (noteDialog) AlertDialog(
        onDismissRequest = { noteDialog = false; editingNote = null },
        title = { Text(if (editingNote == null) "เพิ่มโน้ต" else "แก้ไขโน้ต") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(noteTitle, { noteTitle = it }, label = { Text("หัวข้อ") }, singleLine = true)
                OutlinedTextField(noteContent, { noteContent = it }, label = { Text("รายละเอียด") }, modifier = Modifier.heightIn(min = 150.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                val title = noteTitle; val content = noteContent
                val noteToUpdate = editingNote
                noteDialog = false; noteTitle = ""; noteContent = ""
                editingNote = null
                scope.launch {
                    if (noteToUpdate == null) repo.addNote(place.id, title, content)
                    else repo.updateNote(noteToUpdate.id, title, content)
                }
            }, enabled = noteContent.isNotBlank()) {
                Text(if (editingNote == null) "บันทึกโน้ต" else "บันทึกการแก้ไข")
            }
        },
        dismissButton = { TextButton(onClick = { noteDialog = false; editingNote = null }) { Text("ยกเลิก") } }
    )
    noteInfo?.let { note ->
        AlertDialog(
            onDismissRequest = { noteInfo = null },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text(note.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("สร้าง/แก้ไขล่าสุด\n${displayDateTime(note.updatedAt)}")
                    Text("สถานะ Backup: ${note.status.name}")
                    note.lastError?.takeIf { it.isNotBlank() }?.let { Text("ข้อผิดพลาด: $it", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { noteInfo = null }) { Text("ปิด") } }
        )
    }
    photoDeleteOptions?.let { photo ->
        val photoUri = Uri.parse(photo.contentUri)
        val canDeleteFromDn = repo.media.canRequestDelete(photoUri)
        AlertDialog(
            onDismissRequest = { photoDeleteOptions = null },
            title = { Text("จัดการรูปนี้") },
            text = {
                Text(
                    if (canDeleteFromDn) "เลือกว่าจะเอารูปออกจาก DN อย่างเดียว หรือจะลบไฟล์ออกจาก Gallery และเครื่องด้วย"
                    else "รูปนี้นำเข้าผ่าน Android Photo Picker หากต้องการลบไฟล์ต้นฉบับ ให้เปิดรูปใน Gallery แล้วลบจาก Gallery"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        photoDeleteOptions = null
                        if (!canDeleteFromDn) {
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(photoUri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(viewIntent) }
                                .onFailure { Toast.makeText(context, "เปิดรูปใน Gallery ไม่สำเร็จ", Toast.LENGTH_LONG).show() }
                        } else scope.launch {
                            runCatching { repo.deletePhoto(photo.id) }.onFailure { error ->
                                if (error is MediaDeleteApproval) {
                                    pendingPhotoDelete = photo
                                    mediaDeleteApproval.launch(IntentSenderRequest.Builder(error.sender).build())
                                } else Toast.makeText(context, "Android ไม่อนุญาตให้ลบไฟล์นี้: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = if (canDeleteFromDn) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors()
                ) { Text(if (canDeleteFromDn) "ลบจากเครื่อง" else "เปิดใน Gallery เพื่อลบ") }
            },
            dismissButton = {
                TextButton(onClick = {
                    photoDeleteOptions = null
                    scope.launch { repo.forgetPhoto(photo.id) }
                }) { Text("เอาออกจาก DN แต่เก็บใน Gallery") }
            }
        )
    }
    if (showSelectedDeleteOptions) AlertDialog(
        onDismissRequest = { showSelectedDeleteOptions = false },
        title = { Text("จัดการ ${selectedPhotos.size} รูป") },
        text = { Text("เลือกว่าจะเอารูปออกจาก DN อย่างเดียว หรือจะลบออกจากเครื่องด้วย") },
        confirmButton = {
            Button(onClick = { showSelectedDeleteOptions = false; deleteSelectedPhotosFromDevice() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("ลบจากเครื่อง") }
        },
        dismissButton = {
            TextButton(onClick = {
                showSelectedDeleteOptions = false
                scope.launch { repo.forgetPhotos(selectedPhotos.map { it.id }); selectedPhotoIds = emptySet() }
            }) { Text("เอาออกจาก DN แต่เก็บใน Gallery") }
        }
    )
    if (folderDialog) NameDialog("ชื่อโฟลเดอร์", onDismiss = { folderDialog = false }) { name ->
        scope.launch { runCatching { repo.addFolder(place, name) } }
        folderDialog = false
    }
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("ลบโฟลเดอร์ ${folder.name.substringAfterLast('/')}?") },
            text = { Text("โฟลเดอร์ย่อยและรูปทั้งหมดข้างในจะถูกลบออกจากแอปและ Gallery การดำเนินการนี้ย้อนกลับไม่ได้") },
            confirmButton = {
                Button(
                    onClick = {
                        folderToDelete = null
                        scope.launch {
                            runCatching { repo.folderDeleteApproval(folder) }
                                .onSuccess { sender ->
                                    if (sender == null) {
                                        repo.forgetFolder(folder)
                                        Toast.makeText(context, "ลบโฟลเดอร์แล้ว", Toast.LENGTH_LONG).show()
                                    } else {
                                        folderAwaitingDeleteApproval = folder
                                        folderDeleteApproval.launch(IntentSenderRequest.Builder(sender).build())
                                    }
                                }
                                .onFailure { Toast.makeText(context, "ลบโฟลเดอร์ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("ลบทั้งหมด") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("ยกเลิก") } }
        )
    }
    if (showRecoveryDialog) AlertDialog(
        onDismissRequest = { showRecoveryDialog = false },
        title = { Text("พบรูปที่ยังไม่ได้นำเข้างาน") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("เลือกรูปที่เป็นของงานนี้เท่านั้น รูปที่ไม่เลือกจะไม่ถูกนำเข้า")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = recoveryWithStamp, onCheckedChange = { recoveryWithStamp = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("สร้างรูป Timestamp", fontWeight = FontWeight.Bold)
                        Text("ใช้เวลาถ่ายและ GPS จากข้อมูลในรูป", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    gridItems(recoveryCandidates, key = { it.uri.toString() }) { candidate ->
                        val key = candidate.uri.toString()
                        val selected = key in selectedRecoveryUris
                        Box(
                            Modifier.aspectRatio(1f).clickable {
                                selectedRecoveryUris = if (selected) selectedRecoveryUris - key else selectedRecoveryUris + key
                            }
                        ) {
                            AsyncImage(candidate.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (selected) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                                Icon(Icons.Default.CheckCircle, "เลือกแล้ว", tint = Color.White,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                            }
                        }
                    }
                }
                Text("เลือกแล้ว ${selectedRecoveryUris.size}/${recoveryCandidates.size} รูป", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = recoveryCandidates.filter { it.uri.toString() in selectedRecoveryUris }
                    showRecoveryDialog = false
                    importOppoPhotos(selected.map { it.uri to it.capturedAtMillis }, recoveryWithStamp, useCurrentLocation = false)
                },
                enabled = selectedRecoveryUris.isNotEmpty()
            ) { Text("นำเข้ารูปที่เลือก") }
        },
        dismissButton = {
            TextButton(onClick = {
                showRecoveryDialog = false
                recoveryPreferences.edit().remove("oppo_recovery_location").remove("oppo_recovery_started")
                    .remove("oppo_recovery_stamp").apply()
            }) { Text("ไม่ใช่รูปของงานนี้") }
        }
    )
    if (externalModeDialog) AlertDialog(
        onDismissRequest = { externalModeDialog = false },
        title = { Text("ถ่ายด้วยกล้อง OPPO") },
        text = { Text("ถ่ายต่อเนื่องกี่รูปก็ได้ เมื่อเสร็จให้ใช้ปุ่ม Back หรือปัดย้อนกลับจากขอบจอ แล้วเลือกภาพทั้งหมดจาก Gallery") },
        confirmButton = { Button(onClick = { externalModeDialog = false; openSystemCamera(true) }) { Text("Time stamp + GPS") } },
        dismissButton = { OutlinedButton(onClick = { externalModeDialog = false; openSystemCamera(false) }) { Text("ต้นฉบับ") } }
    )
}

@Composable private fun PhotoViewer(repo: PhotoRepository, photos: List<PhotoEntity>, initialIndex: Int, onBack: () -> Unit) {
    if (photos.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, photos.lastIndex), pageCount = { photos.size })
    val photo = photos[pagerState.currentPage]
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffset by remember { mutableStateOf(Offset.Zero) }
    var imageViewport by remember { mutableStateOf(IntSize.Zero) }
    var imageContent by remember { mutableStateOf(IntSize.Zero) }
    var edgePreview by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { imageScale = 1f; imageOffset = Offset.Zero; edgePreview = 0f; showInfo = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            delay(1000)
            if (repo.removeIfMissing(photo)) onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, userScrollEnabled = imageScale == 1f, modifier = Modifier.fillMaxSize()) { page ->
            val pagePhoto = photos[page]
            val originalRequest = remember(pagePhoto.contentUri) {
                ImageRequest.Builder(context)
                    .data(Uri.parse(pagePhoto.contentUri))
                    .size(Size.ORIGINAL)
                    .precision(Precision.EXACT)
                    .crossfade(false)
                    .build()
            }
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = edgePreview }) {
            AsyncImage(
                originalRequest, pagePhoto.filename,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    imageContent = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                },
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { imageViewport = it }
                    .pointerInput(pagePhoto.id) {
                        detectTapGestures(onDoubleTap = {
                            imageScale = if (imageScale > 1f) 1f else 2.5f
                            imageOffset = Offset.Zero
                        })
                    }
                    .pointerInput(pagePhoto.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                val pan = event.calculatePan()
                                val isTransforming = pressedCount >= 2 || (imageScale > 1f && pan.getDistance() > 0.5f)
                                if (isTransforming) {
                                    val oldScale = imageScale
                                    val nextScale = (oldScale * event.calculateZoom()).coerceIn(1f, 8f)
                                    val appliedZoom = nextScale / oldScale
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val center = Offset(imageViewport.width / 2f, imageViewport.height / 2f)
                                    imageScale = nextScale
                                    val proposed = imageOffset * appliedZoom + (centroid - center) * (1f - appliedZoom) + pan
                                    val constrained = constrainedImageOffset(proposed, nextScale, imageViewport, imageContent)
                                    if (pressedCount == 1 && edgePreview != 0f) {
                                        val updated = edgePreview + pan.x
                                        edgePreview = if (updated.sign != edgePreview.sign) 0f else updated.coerceIn(-imageViewport.width * 0.65f, imageViewport.width * 0.65f)
                                    } else if (pressedCount == 1 && proposed.x != constrained.x) {
                                        val canReveal = (pan.x < 0 && pagerState.currentPage < photos.lastIndex) || (pan.x > 0 && pagerState.currentPage > 0)
                                        if (canReveal) edgePreview = (edgePreview + pan.x * 0.65f).coerceIn(-imageViewport.width * 0.65f, imageViewport.width * 0.65f)
                                    } else imageOffset = constrained
                                    event.changes.forEach { it.consume() }
                                }
                                if (event.changes.none { it.pressed }) {
                                    val confirmDistance = imageViewport.width * 0.50f
                                    val targetPage = when {
                                        edgePreview < -confirmDistance && pagerState.currentPage < photos.lastIndex -> pagerState.currentPage + 1
                                        edgePreview > confirmDistance && pagerState.currentPage > 0 -> pagerState.currentPage - 1
                                        else -> null
                                    }
                                    scope.launch {
                                        if (targetPage == null) {
                                            animate(edgePreview, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> edgePreview = value }
                                        } else {
                                            val destination = if (edgePreview < 0f) -imageViewport.width.toFloat() else imageViewport.width.toFloat()
                                            animate(edgePreview, destination, animationSpec = tween(180)) { value, _ -> edgePreview = value }
                                            imageScale = 1f
                                            imageOffset = Offset.Zero
                                            pagerState.scrollToPage(targetPage)
                                            edgePreview = 0f
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = imageScale; scaleY = imageScale
                        translationX = imageOffset.x; translationY = imageOffset.y
                    },
                contentScale = ContentScale.Fit
            )
            }
        }

        val previewIndex = when {
            edgePreview < 0f && pagerState.currentPage < photos.lastIndex -> pagerState.currentPage + 1
            edgePreview > 0f && pagerState.currentPage > 0 -> pagerState.currentPage - 1
            else -> null
        }
        previewIndex?.let { index ->
            val previewPhoto = photos[index]
            val previewRequest = remember(previewPhoto.contentUri) {
                ImageRequest.Builder(context).data(Uri.parse(previewPhoto.contentUri))
                    .size(Size.ORIGINAL).precision(Precision.EXACT).crossfade(false).build()
            }
            AsyncImage(
                model = previewRequest,
                contentDescription = previewPhoto.filename,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = if (edgePreview < 0f) imageViewport.width + edgePreview
                    else -imageViewport.width + edgePreview
                },
                contentScale = ContentScale.Fit
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
        ) { Icon(Icons.Default.ArrowBack, "กลับ", tint = Color.White) }

        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
        ) { Icon(Icons.Default.Info, "ข้อมูลรูป", tint = Color.White) }

        Text(
            "${pagerState.currentPage + 1} / ${photos.size}",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp)
        )

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            color = Color.Black.copy(alpha = 0.58f), shape = RoundedCornerShape(28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    imageScale = (imageScale - 0.5f).coerceAtLeast(1f)
                    if (imageScale == 1f) imageOffset = Offset.Zero
                }) { Text("−", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                Text("%.1f×".format(imageScale), color = Color.White, modifier = Modifier.width(52.dp))
                IconButton(onClick = { imageScale = (imageScale + 0.5f).coerceAtMost(8f) }) { Text("+", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { imageScale = 1f; imageOffset = Offset.Zero }) { Text("รีเซ็ต", color = Color.White) }
            }
        }
    }

    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text("ข้อมูลรูป") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ชื่อไฟล์: ${photo.filename}")
                Text("ถ่ายเมื่อ: ${photo.capturedAt}")
                Text("สถานะ: ${photo.status}")
                if (photo.latitude != null) Text("GPS: ${photo.latitude}, ${photo.longitude}\nความแม่นยำ: ±${photo.accuracy ?: 0f} เมตร")
                else Text("GPS: ไม่มีข้อมูล")
            }
        },
        confirmButton = {
            Row {
                if (!photo.filename.contains("_STAMP", ignoreCase = true)) TextButton(onClick = {
                    showInfo = false
                    scope.launch {
                        runCatching { repo.createTimestampCopy(photo.id) }
                            .onSuccess { Toast.makeText(context, "สร้างรูป Time stamp แล้ว", Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(context, "สร้าง Time stamp ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }) { Text("สร้าง Time stamp") }
                if (photo.latitude != null) TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${photo.latitude},${photo.longitude}?q=${photo.latitude},${photo.longitude}")))
                }) { Text("แผนที่") }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, Uri.parse(photo.contentUri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "แชร์รูป"))
                }) { Text("แชร์") }
                TextButton(onClick = { showInfo = false }) { Text("ปิด") }
            }
        }
    )
}

@Composable private fun CloudPhotoViewer(
    repo: PhotoRepository, serverUrl: String, photos: List<CloudPhoto>, initialIndex: Int, onBack: () -> Unit,
) {
    if (photos.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, photos.lastIndex), pageCount = { photos.size })
    val photo = photos[pagerState.currentPage]
    var scale by remember { mutableFloatStateOf(1f) }; var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var edgePreview by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }; var downloading by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { scale = 1f; offset = Offset.Zero; edgePreview = 0f; showInfo = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, userScrollEnabled = scale == 1f, modifier = Modifier.fillMaxSize()) { page ->
            val item = photos[page]
            val originalRequest = remember(item.hash, serverUrl) {
                ImageRequest.Builder(context)
                    .data(CloudClient().photoUrl(serverUrl, item.hash))
                    .size(Size.ORIGINAL)
                    .precision(Precision.EXACT)
                    .crossfade(false)
                    .build()
            }
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = edgePreview }) {
            AsyncImage(
                originalRequest, item.filename,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    contentSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                },
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .pointerInput(item.hash) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(item.hash) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            val isTransforming = pressedCount >= 2 || (scale > 1f && pan.getDistance() > 0.5f)
                            if (isTransforming) {
                                val oldScale = scale
                                val next = (oldScale * event.calculateZoom()).coerceIn(1f, 8f)
                                val appliedZoom = next / oldScale
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                val proposed = offset * appliedZoom + (centroid - center) * (1f - appliedZoom) + pan
                                val constrained = constrainedImageOffset(proposed, next, viewport, contentSize)
                                if (pressedCount == 1 && edgePreview != 0f) {
                                    val updated = edgePreview + pan.x
                                    edgePreview = if (updated.sign != edgePreview.sign) 0f else updated.coerceIn(-viewport.width * 0.65f, viewport.width * 0.65f)
                                } else if (pressedCount == 1 && proposed.x != constrained.x) {
                                    val canReveal = (pan.x < 0 && pagerState.currentPage < photos.lastIndex) || (pan.x > 0 && pagerState.currentPage > 0)
                                    if (canReveal) edgePreview = (edgePreview + pan.x * 0.65f).coerceIn(-viewport.width * 0.65f, viewport.width * 0.65f)
                                } else offset = constrained
                                scale = next
                                event.changes.forEach { it.consume() }
                            }
                            if (event.changes.none { it.pressed }) {
                                val confirmDistance = viewport.width * 0.50f
                                val targetPage = when {
                                    edgePreview < -confirmDistance && pagerState.currentPage < photos.lastIndex -> pagerState.currentPage + 1
                                    edgePreview > confirmDistance && pagerState.currentPage > 0 -> pagerState.currentPage - 1
                                    else -> null
                                }
                                scope.launch {
                                    if (targetPage == null) {
                                        animate(edgePreview, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> edgePreview = value }
                                    } else {
                                        val destination = if (edgePreview < 0f) -viewport.width.toFloat() else viewport.width.toFloat()
                                        animate(edgePreview, destination, animationSpec = tween(180)) { value, _ -> edgePreview = value }
                                        scale = 1f
                                        offset = Offset.Zero
                                        pagerState.scrollToPage(targetPage)
                                        edgePreview = 0f
                                    }
                                }
                                break
                            }
                        }
                    }
                }.graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }, contentScale = ContentScale.Fit
            )
            }
        }
        val previewIndex = when {
            edgePreview < 0f && pagerState.currentPage < photos.lastIndex -> pagerState.currentPage + 1
            edgePreview > 0f && pagerState.currentPage > 0 -> pagerState.currentPage - 1
            else -> null
        }
        previewIndex?.let { index ->
            val previewPhoto = photos[index]
            val previewRequest = remember(previewPhoto.hash, serverUrl) {
                ImageRequest.Builder(context).data(CloudClient().photoUrl(serverUrl, previewPhoto.hash))
                    .size(Size.ORIGINAL).precision(Precision.EXACT).crossfade(false).build()
            }
            AsyncImage(
                model = previewRequest,
                contentDescription = previewPhoto.filename,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = if (edgePreview < 0f) viewport.width + edgePreview else -viewport.width + edgePreview
                },
                contentScale = ContentScale.Fit
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))) {
            Icon(Icons.Default.ArrowBack, "กลับ", tint = Color.White)
        }
        Text("${pagerState.currentPage + 1} / ${photos.size}", color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
        IconButton(onClick = { showInfo = true }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))) {
            Icon(Icons.Default.Info, "ข้อมูลรูป", tint = Color.White)
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.Black.copy(alpha = 0.62f), shape = RoundedCornerShape(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scale = (scale - 0.5f).coerceAtLeast(1f); if (scale == 1f) offset = Offset.Zero }) {
                    Text("−", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                Text("%.1f×".format(scale), color = Color.White, modifier = Modifier.width(52.dp))
                IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(8f) }) { Text("+", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("รีเซ็ต", color = Color.White) }
            }
        }
    }
    if (showInfo) AlertDialog(
        onDismissRequest = { if (!downloading) showInfo = false }, title = { Text("ข้อมูลรูปบน Server") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ชื่อไฟล์: ${photo.filename}"); Text("โฟลเดอร์: ${photo.locationName.ifBlank { "โฟลเดอร์หลัก" }}")
            Text("ถ่ายเมื่อ: ${displayDateTime(photo.capturedAt)}")
            if (photo.latitude != null) Text("GPS: ${photo.latitude}, ${photo.longitude}\nความแม่นยำ: ±${photo.accuracy ?: 0f} เมตร") else Text("GPS: ไม่มีข้อมูล")
        } },
        confirmButton = { Button(onClick = {
            downloading = true; scope.launch {
                runCatching { repo.restoreCloudPhoto(serverUrl, photo) }
                    .onSuccess { Toast.makeText(context, "ดาวน์โหลดกลับเข้า DN แล้ว", Toast.LENGTH_LONG).show(); showInfo = false }
                    .onFailure { Toast.makeText(context, "ดาวน์โหลดไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                downloading = false
            }
        }, enabled = !downloading) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text(if (downloading) "กำลังดาวน์โหลด…" else "ดาวน์โหลดกลับ") } },
        dismissButton = { TextButton(onClick = { showInfo = false }, enabled = !downloading) { Text("ปิด") } }
    )
}

@Composable private fun CloudPhotosPage(
    repo: PhotoRepository, serverUrl: String, jobId: String, clientName: String, jobName: String,
    openPdf: (CloudDocument) -> Unit, openPhoto: (List<CloudPhoto>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(CloudCatalog()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CloudPhoto?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentFolder by remember { mutableStateOf("") }
    var selectedNote by remember { mutableStateOf<com.fieldphoto.app.sync.CloudNote?>(null) }
    var confirmRestoreGroup by remember { mutableStateOf(false) }
    var restoringGroup by remember { mutableStateOf(false) }
    LaunchedEffect(serverUrl, clientName, jobName) {
        loading = true
        runCatching { CloudClient().catalog(serverUrl) }
            .onSuccess { full ->
                catalog = CloudCatalog(
                    full.folders.filter { if (jobId.isNotBlank()) it.jobId == jobId else it.clientName == clientName && it.jobName == jobName },
                    full.photos.filter { if (jobId.isNotBlank()) it.jobId == jobId else it.clientName == clientName && it.jobName == jobName },
                    full.documents.filter { if (jobId.isNotBlank()) it.jobId == jobId else it.clientName == clientName && it.jobName == jobName },
                    full.notes.filter { if (jobId.isNotBlank()) it.jobId == jobId else it.clientName == clientName && it.jobName == jobName },
                ); error = null
            }
            .onFailure { error = it.message ?: "เชื่อมต่อ Server ไม่ได้" }
        loading = false
    }
    val prefix = if (currentFolder.isBlank()) "" else "$currentFolder/"
    val childFolders = catalog.folders.filter { folder ->
        folder.locationName.startsWith(prefix) && folder.locationName != currentFolder &&
            !folder.locationName.removePrefix(prefix).contains('/')
    }.distinctBy { it.locationName }
    val visiblePhotos = catalog.photos.filter { it.locationName == currentFolder }
    val visibleDocuments = catalog.documents.filter { it.locationName == currentFolder }
    val visibleNotes = catalog.notes.filter { it.locationName == currentFolder }
    BackHandler(enabled = currentFolder.isNotBlank()) { currentFolder = currentFolder.substringBeforeLast('/', "") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (currentFolder.isNotBlank()) IconButton(onClick = { currentFolder = currentFolder.substringBeforeLast('/', "") }) {
                Icon(Icons.Default.ArrowBack, "โฟลเดอร์ก่อนหน้า")
            }
            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(currentFolder.ifBlank { "โฟลเดอร์หลักบน Server" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { confirmRestoreGroup = true }) {
                Icon(Icons.Default.CloudDownload, if (currentFolder.isBlank()) "ดาวน์โหลดทั้งงาน" else "ดาวน์โหลดทั้งโฟลเดอร์")
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            catalog.folders.isEmpty() && catalog.photos.isEmpty() && catalog.documents.isEmpty() && catalog.notes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("ไม่พบข้อมูลบน Server") }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2), contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                gridItems(childFolders, key = { "folder-${it.locationName}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                    Surface(Modifier.fillMaxWidth().clickable { currentFolder = folder.locationName }, shape = RoundedCornerShape(14.dp), color = Color.LightGray.copy(alpha = 0.48f)) {
                        Row(Modifier.padding(14.dp).graphicsLayer(alpha = 0.76f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudQueue, null); Spacer(Modifier.width(10.dp))
                            Text(folder.locationName.ifBlank { "โฟลเดอร์หลัก" }, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                gridItems(visibleNotes, key = { "note-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { note ->
                    Surface(Modifier.fillMaxWidth().clickable { selectedNote = note }, shape = RoundedCornerShape(14.dp), color = Color.LightGray.copy(alpha = 0.48f)) {
                        Row(Modifier.padding(14.dp).graphicsLayer(alpha = 0.76f), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Cloud, null); Spacer(Modifier.width(10.dp)); Column {
                                Text(note.title, fontWeight = FontWeight.Bold); Text(note.content, maxLines = 5)
                                Text(displayDateTime(note.updatedAt), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                gridItems(visibleDocuments, key = { "document-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { document ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { openPdf(document) }, shape = RoundedCornerShape(14.dp), color = Color.LightGray.copy(alpha = 0.48f)
                    ) {
                        Row(Modifier.padding(14.dp).graphicsLayer(alpha = 0.76f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(10.dp)); Column {
                                Text(document.filename, fontWeight = FontWeight.Bold)
                                Text("PDF ${document.pageCount} หน้า • เปิดใน DN", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                gridItemsIndexed(visiblePhotos, key = { _, item -> item.hash }) { index, cloud ->
                    ElevatedCard(
                        Modifier.aspectRatio(1f).clickable { openPhoto(visiblePhotos, index) },
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.LightGray.copy(alpha = 0.58f))
                    ) {
                        Box(Modifier.fillMaxSize().graphicsLayer(alpha = 0.76f)) {
                            AsyncImage(
                                CloudClient().photoUrl(serverUrl, cloud.hash), cloud.filename,
                                Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                            )
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(7.dp).size(32.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (cloud.hash in downloaded) Icons.Default.CloudDone else Icons.Default.Cloud,
                                    "รูปบน Server", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmRestoreGroup) AlertDialog(
        onDismissRequest = { if (!restoringGroup) confirmRestoreGroup = false },
        icon = { Icon(Icons.Default.CloudDownload, null) },
        title = { Text(if (currentFolder.isBlank()) "ดาวน์โหลดทั้งงาน" else "ดาวน์โหลดทั้งโฟลเดอร์") },
        text = { Text("ระบบจะนำโครงสร้างโฟลเดอร์ รูป PDF และโน้ตกลับเข้า DN หากชื่องานซ้ำจะสร้างชื่อแบบวงเล็บแยกให้อัตโนมัติ") },
        confirmButton = { Button(onClick = {
            restoringGroup = true
            scope.launch {
                runCatching { repo.restoreCloudGroup(serverUrl, catalog, currentFolder.takeIf { it.isNotBlank() }) }
                    .onSuccess { count -> Toast.makeText(context, "ดาวน์โหลดกลับ $count รายการแล้ว", Toast.LENGTH_LONG).show(); confirmRestoreGroup = false }
                    .onFailure { Toast.makeText(context, "ดาวน์โหลดไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                restoringGroup = false
            }
        }, enabled = !restoringGroup) { Text(if (restoringGroup) "กำลังดาวน์โหลด…" else "ดาวน์โหลด") } },
        dismissButton = { TextButton(onClick = { confirmRestoreGroup = false }, enabled = !restoringGroup) { Text("ยกเลิก") } }
    )
    selectedNote?.let { note ->
        AlertDialog(
            onDismissRequest = { selectedNote = null }, icon = { Icon(Icons.Default.Cloud, null) },
            title = { Text(note.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(note.content); HorizontalDivider(); Text("แก้ไขล่าสุด ${displayDateTime(note.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { selectedNote = null }) { Text("ปิด") } }
        )
    }
    selected?.let { cloud ->
        AlertDialog(
            onDismissRequest = { if (downloading == null) selected = null },
            title = { Text(cloud.filename) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(CloudClient().photoUrl(serverUrl, cloud.hash), cloud.filename,
                        Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer(alpha = 0.9f), contentScale = ContentScale.Fit)
                    Text("โฟลเดอร์: ${cloud.locationName.ifBlank { "โฟลเดอร์หลัก" }}")
                    Text("ถ่ายเมื่อ: ${displayDateTime(cloud.capturedAt)}")
                    Text("ไฟล์นี้อยู่บน Server${if (cloud.hash in downloaded) " และดาวน์โหลดลงมือถือแล้ว" else " เท่านั้น"}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    downloading = cloud.hash
                    scope.launch {
                        runCatching { repo.restoreCloudPhoto(serverUrl, cloud) }
                            .onSuccess {
                                downloaded = downloaded + cloud.hash
                                Toast.makeText(context, "ดาวน์โหลดกลับเข้า DN แล้ว", Toast.LENGTH_LONG).show()
                                selected = null
                            }
                            .onFailure { Toast.makeText(context, "ดาวน์โหลดไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                        downloading = null
                    }
                }, enabled = downloading == null && cloud.hash !in downloaded) {
                    if (downloading == cloud.hash) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudDownload, null)
                    Spacer(Modifier.width(6.dp)); Text("ดาวน์โหลดกลับ")
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }, enabled = downloading == null) { Text("ปิด") } }
        )
    }
}

@Composable private fun CloudPdfPage(serverUrl: String, document: CloudDocument) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverUrl, document.id) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "cloud-${document.id}.pdf")
                CloudClient().openDocument(serverUrl, document.id).use { response ->
                    if (!response.isSuccessful) error("ดาวน์โหลด PDF ไม่สำเร็จ: Server ${response.code}")
                    file.outputStream().use { output -> response.body?.byteStream().use { input -> requireNotNull(input); input.copyTo(output) } }
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        (0 until renderer.pageCount).map { index ->
                            renderer.openPage(index).use { page ->
                                val width = page.width.coerceAtMost(1600)
                                val height = (page.height * (width.toFloat() / page.width)).toInt()
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
            }
        }.onSuccess { pages = it; error = null }.onFailure { error = it.message ?: "เปิด PDF ไม่สำเร็จ" }
        loading = false
    }
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
        else -> LazyColumn(
            Modifier.fillMaxSize().background(Color(0xFFE7E7E7)), contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(pages) { index, bitmap ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("หน้า ${index + 1}/${pages.size}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Image(bitmap.asImageBitmap(), "PDF หน้า ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}

@Composable private fun TimestampToggle(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = change)
    }
}

@Composable private fun SettingsPage(current: String, timestampEnabled: Boolean, saveUrl: (String) -> Unit, saveTimestamp: (Boolean) -> Unit) {
    val context = LocalContext.current
    val stampPreferences = remember { context.getSharedPreferences("timestamp_settings", Context.MODE_PRIVATE) }
    var value by remember(current) { mutableStateOf(current) }
    var showWork by remember { mutableStateOf(stampPreferences.getBoolean("show_work", true)) }
    var showDate by remember { mutableStateOf(stampPreferences.getBoolean("show_date", true)) }
    var showTime by remember { mutableStateOf(stampPreferences.getBoolean("show_time", true)) }
    var showCoordinates by remember { mutableStateOf(stampPreferences.getBoolean("show_coordinates", true)) }
    var showAccuracy by remember { mutableStateOf(stampPreferences.getBoolean("show_accuracy", true)) }
    var showAddress by remember { mutableStateOf(stampPreferences.getBoolean("show_address", false)) }
    var fontPercent by remember { mutableFloatStateOf(stampPreferences.getInt("font_percent", 100).toFloat()) }
    fun saveBoolean(key: String, setting: Boolean) { stampPreferences.edit().putBoolean(key, setting).apply() }
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ที่อยู่ Computer Server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value, { value = it }, label = { Text("เช่น http://192.168.1.53:8080") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { saveUrl(value.trim()) }, enabled = value.startsWith("http://")) { Text("บันทึก") }
        Text("Version 1 ใช้ HTTP ภายใน Wi-Fi เดียวกันเท่านั้น", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("แสดง Time stamp", style = MaterialTheme.typography.titleMedium); Text("แสดงวันที่และเวลาทับบนรูปในแอป โดยไม่แก้ไฟล์ต้นฉบับ", style = MaterialTheme.typography.bodySmall) }
            Switch(checked = timestampEnabled, onCheckedChange = saveTimestamp)
        }
        HorizontalDivider()
        Text("ตั้งค่าข้อความบนไฟล์ Timestamp", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("มีผลกับโหมด Time stamp + GPS และปุ่มสร้าง Time stamp ภายหลัง", style = MaterialTheme.typography.bodySmall)
        TimestampToggle("ชื่องานและโฟลเดอร์", "แสดงลูกค้า / งาน / โฟลเดอร์", showWork) { showWork = it; saveBoolean("show_work", it) }
        TimestampToggle("วันที่", "แสดงวันที่ถ่ายจริง", showDate) { showDate = it; saveBoolean("show_date", it) }
        TimestampToggle("เวลา", "แสดงเวลาถ่ายจริงและเขตเวลา", showTime) { showTime = it; saveBoolean("show_time", it) }
        TimestampToggle("พิกัด GPS", "แสดง Latitude และ Longitude", showCoordinates) { showCoordinates = it; saveBoolean("show_coordinates", it) }
        TimestampToggle("ความแม่นยำ GPS", "แสดงค่า Accuracy เป็นเมตร", showAccuracy) { showAccuracy = it; saveBoolean("show_accuracy", it) }
        TimestampToggle("ถนน / ซอย / ที่อยู่", "ค้นชื่อสถานที่จาก GPS ของรูปหรือ GPS ปัจจุบัน (มีผลกับไฟล์ Timestamp ที่สร้างใหม่)", showAddress) { showAddress = it; saveBoolean("show_address", it) }
        if (showAddress) Text(
            "ถ้าขึ้นว่าไม่มีพิกัด ให้เปิด Location ของมือถือและเปิดบันทึกตำแหน่งในกล้อง OPPO ก่อนถ่าย",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("ขนาดตัวอักษร ${fontPercent.toInt()}%", fontWeight = FontWeight.Medium)
        Slider(
            value = fontPercent, onValueChange = { fontPercent = it }, valueRange = 60f..180f, steps = 11,
            onValueChangeFinished = { stampPreferences.edit().putInt("font_percent", fontPercent.toInt()).apply() }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("เล็ก 60%", style = MaterialTheme.typography.labelSmall); Text("ปกติ 100%", style = MaterialTheme.typography.labelSmall); Text("ใหญ่ 180%", style = MaterialTheme.typography.labelSmall)
        }
    }
}
