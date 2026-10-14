package com.example.gitpacker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.net.URL
import java.util.UUID
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : ComponentActivity() {

    private val saveFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let {
                val viewModel: MainViewModel = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
                val content = viewModel.resultText
                try {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        var initialUrl = ""
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                // Extract first github URL
                val regex = "(https?://(?:www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(?:/[\\w./-]+)?)".toRegex()
                val match = regex.find(sharedText)
                if (match != null) {
                    initialUrl = match.value
                }
            }
        }

        setContent {
            val viewModel: MainViewModel = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]

            // Collect state inside setContent for initial fast theme setup
            val isDarkTheme = when (viewModel.themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            val colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GitPackerApp(
                        initialUrl = initialUrl,
                        onSaveFile = { suggestedName ->
                            saveFileLauncher.launch(suggestedName)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "gitpacker") {
            val uri = intent.data
            val code = uri?.getQueryParameter("code")
            if (code != null) {
                val viewModel: MainViewModel = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
                viewModel.exchangeCodeForToken(code)
            } else {
                Toast.makeText(this, "OAuth authorization failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class MainViewModel : ViewModel() {
    var url by mutableStateOf("")
    var maxTokens by mutableStateOf("100000")
    var extensions by mutableStateOf("")

    var isLoading by mutableStateOf(false)
    var resultText by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)

    // Settings / Theme related
    var showAuthDialog by mutableStateOf(false)
    var themeMode by mutableStateOf("SYSTEM")
    private var preferencesManager: PreferencesManager? = null

    // Auth related
    var currentToken by mutableStateOf<String?>(null)
    private var codeVerifier: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun initToken(context: Context) {
        val tokenManager = TokenManager(context)
        currentToken = tokenManager.getToken()

        preferencesManager = PreferencesManager(context)
        themeMode = preferencesManager?.getThemeMode() ?: "SYSTEM"
    }

    fun updateThemeMode(mode: String) {
        themeMode = mode
        preferencesManager?.saveThemeMode(mode)
    }

    fun clearToken(context: Context) {
        val tokenManager = TokenManager(context)
        tokenManager.clearToken()
        currentToken = null
    }

    fun startOauthFlow(context: Context) {
        codeVerifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.generateCodeChallenge(codeVerifier!!)
        val state = UUID.randomUUID().toString()
        val clientId = "client_f09bf242631ea63061bf3f0f31ec325c" // App's registered Client ID
        val redirectUri = "gitpacker://callback"

        val url = Uri.parse("https://uithub.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "repo")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, url)
        showAuthDialog = false
    }

    fun exchangeCodeForToken(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val clientId = "client_f09bf242631ea63061bf3f0f31ec325c"
                val clientSecret = BuildConfig.CLIENT_SECRET
                val redirectUri = "gitpacker://callback"

                val formBody = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", redirectUri)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("code_verifier", codeVerifier ?: "")
                    .build()

                val request = Request.Builder()
                    .url("https://uithub.com/token")
                    .post(formBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val json = JSONObject(responseBody)
                            val token = json.optString("access_token")
                            if (token.isNotEmpty()) {
                                val context = com.example.gitpacker.GitPackerApplication.instance?.applicationContext
                                if (context != null) {
                                    val tm = TokenManager(context)
                                    tm.saveToken(token)
                                }
                                withContext(Dispatchers.Main) {
                                    currentToken = token
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore or handle
            }
        }
    }

    fun packRepository() {
        if (url.isBlank()) {
            errorMessage = "Please enter a valid GitHub URL"
            return
        }

        val githubUrl = try {
            URL(url)
        } catch (e: Exception) {
            errorMessage = "Invalid URL format"
            return
        }

        if (!githubUrl.host.equals("github.com", ignoreCase = true)) {
            errorMessage = "URL must be a github.com repository"
            return
        }

        isLoading = true
        errorMessage = null
        resultText = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Replace github.com with uithub.com
                var targetUrlStr = url.replaceFirst("github.com", "uithub.com")

                val urlBuilder = StringBuilder(targetUrlStr)
                if (targetUrlStr.contains("?")) {
                    urlBuilder.append("&accept=text/plain")
                } else {
                    urlBuilder.append("?accept=text/plain")
                }

                if (maxTokens.isNotBlank()) {
                    urlBuilder.append("&maxTokens=${maxTokens.trim()}")
                }

                if (extensions.isNotBlank()) {
                    urlBuilder.append("&ext=${extensions.trim()}")
                }

                val targetUrl = urlBuilder.toString()

                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("Accept", "text/plain")

                currentToken?.let { token ->
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val request = requestBuilder.build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            errorMessage = "Error: ${response.code} ${response.message}"
                            isLoading = false
                        }
                        return@launch
                    }

                    val responseBody = response.body?.string() ?: ""
                    withContext(Dispatchers.Main) {
                        resultText = responseBody
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Network error: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    fun getSuggestedFileName(): String {
        try {
            val githubUrl = URL(url)
            val pathSegments = githubUrl.path.split("/").filter { it.isNotBlank() }
            if (pathSegments.size >= 2) {
                val owner = pathSegments[0]
                val repo = pathSegments[1]
                return "${owner}_${repo}_context.txt"
            }
        } catch (e: Exception) {
            // ignore
        }
        return "repo_context.txt"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPackerApp(initialUrl: String, onSaveFile: (String) -> Unit) {
    val viewModel: MainViewModel = viewModel()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.initToken(context)
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && viewModel.url.isBlank()) {
            viewModel.url = initialUrl
        }
    }

    if (viewModel.showAuthDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showAuthDialog = false },
            title = { Text("Settings") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)

                    val themeOptions = listOf("SYSTEM" to "System default", "LIGHT" to "Light", "DARK" to "Dark")
                    themeOptions.forEach { (mode, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (viewModel.themeMode == mode),
                                    onClick = { viewModel.updateThemeMode(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (viewModel.themeMode == mode),
                                onClick = null // null recommended for accessibility with parent selectable
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("GitHub Authentication", style = MaterialTheme.typography.titleMedium)

                    Text("Login to access your private repositories.", style = MaterialTheme.typography.bodyMedium)
                    if (viewModel.currentToken != null) {
                        Text("Status: Logged In", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Status: Not Logged In", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = { viewModel.startOauthFlow(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Login with GitHub (OAuth)")
                    }

                    var manualToken by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it },
                        label = { Text("Or Paste Personal Access Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (manualToken.isNotBlank()) {
                                val tm = TokenManager(context)
                                tm.saveToken(manualToken)
                                viewModel.currentToken = manualToken
                                Toast.makeText(context, "Token saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Token")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.showAuthDialog = false }) {
                    Text("Close")
                }
            },
            dismissButton = {
                if (viewModel.currentToken != null) {
                    TextButton(onClick = {
                        viewModel.clearToken(context)
                        Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Logout")
                    }
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("GitPacker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                actions = {
                    IconButton(onClick = { viewModel.showAuthDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = viewModel.url,
                onValueChange = { viewModel.url = it },
                label = { Text("GitHub URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val pasteData = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!pasteData.isNullOrBlank()) {
                                viewModel.url = pasteData
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Paste")
                        }
                        IconButton(onClick = { viewModel.url = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = viewModel.maxTokens,
                    onValueChange = { viewModel.maxTokens = it },
                    label = { Text("Max Tokens") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = viewModel.extensions,
                    onValueChange = { viewModel.extensions = it },
                    label = { Text("Extensions (kt,java)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Button(
                onClick = { viewModel.packRepository() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isLoading
            ) {
                if (viewModel.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Packing...")
                } else {
                    Text("Pack Repository")
                }
            }

            viewModel.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (viewModel.resultText.isNotEmpty()) {
                val charCount = viewModel.resultText.length
                val lineCount = viewModel.resultText.count { it == '\n' } + 1
                val estTokens = charCount / 4

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Stats", style = MaterialTheme.typography.titleMedium)
                        Text("Characters: $charCount\nLines: $lineCount\nEst. Tokens: $estTokens")

                        HorizontalDivider()

                        SelectionContainer {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = viewModel.resultText.take(2000) + if (viewModel.resultText.length > 2000) "\n\n... (truncated for preview)" else "",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("GitPacker Result", viewModel.resultText)
                                clipboard.setPrimaryClip(clip)

                                val byteSize = viewModel.resultText.toByteArray().size
                                if (byteSize > 500_000) { // approx 500KB
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Text is very large (>500KB) and may be truncated by Android clipboard. Recommend saving to file."
                                        )
                                    }
                                } else {
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("Copy")
                            }

                            Button(onClick = {
                                onSaveFile(viewModel.getSuggestedFileName())
                            }) {
                                Text("Save")
                            }

                            Button(onClick = {
                                val cachePath = File(context.cacheDir, "shared_files")
                                cachePath.mkdirs()
                                val newFile = File(cachePath, viewModel.getSuggestedFileName())

                                try {
                                    FileOutputStream(newFile).use { fos ->
                                        fos.write(viewModel.resultText.toByteArray())
                                    }

                                    val contentUri: Uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        newFile
                                    )

                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, contentUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }

                                    context.startActivity(Intent.createChooser(shareIntent, "Share context file"))
                                } catch (e: Exception) {
                                    Log.e("GitPacker", "Error sharing file", e)
                                    Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("Share")
                            }
                        }
                    }
                }
            }
        }
    }
}
