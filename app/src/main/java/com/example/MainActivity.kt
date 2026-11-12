package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.LoanUiState
import com.example.ui.LoanViewModel
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.util.LoanCalculators
import com.example.util.SchedulePdfGenerator

class MainActivity : ComponentActivity() {

  private val viewModel: LoanViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleLeadIntent(intent)

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
      val isDark = when (uiState.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemDark
      }
      MyApplicationTheme(darkTheme = isDark) {
        if (uiState.authUserId == null) {
          SignInScreen(
            isLoading = uiState.isAuthenticating,
            errorMessage = uiState.authError,
            onSignInClick = { viewModel.signInWithGoogle(this@MainActivity) },
            onContinueOffline = { viewModel.continueInOfflineMode() }
          )
        } else {
          LoanAppContent(
            viewModel = viewModel,
            uiState = uiState,
            onShareWhatsApp = { phone, text -> shareWhatsApp(phone, text) },
            onShareSms = { phone, text -> shareSms(phone, text) },
            onCopyProposal = { text -> copyToClipboard(text) },
            onCallCustomer = { phone -> callCustomer(phone) },
            onExportCsv = { filename, csv -> exportCsv(filename, csv) },
            onExportPdf = { _ -> exportSchedulePdf() }
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.onAppResumed()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleLeadIntent(intent)
  }

  private fun handleLeadIntent(intent: Intent?) {
    val leadId = intent?.getStringExtra("open_lead_id")
    if (!leadId.isNullOrBlank()) {
      viewModel.setTab(1)
    }
  }

  private fun shareWhatsApp(phone: String, text: String) {
    try {
      val digits = phone.filter { it.isDigit() }
      val uri = if (digits.isNotBlank()) {
        Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}")
      } else {
        Uri.parse("https://wa.me/?text=${Uri.encode(text)}")
      }
      val intent = Intent(Intent.ACTION_VIEW, uri)
      startActivity(intent)
    } catch (e: Exception) {
      sharePlainText(text, "Share Proposal via WhatsApp")
    }
  }

  private fun shareSms(phone: String, text: String) {
    try {
      val clean = phone.replace(" ", "").replace("-", "")
      val uri = Uri.parse("sms:$clean")
      val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        putExtra("sms_body", text)
      }
      startActivity(intent)
    } catch (e: Exception) {
      sharePlainText(text, "Send SMS Proposal")
    }
  }

  private fun copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("KB Loan Proposal", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(this, "Quotation copied to clipboard!", Toast.LENGTH_SHORT).show()
  }

  private fun callCustomer(phone: String) {
    try {
      val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
      startActivity(intent)
    } catch (e: Exception) {
      Toast.makeText(this, "Cannot dial number: $phone", Toast.LENGTH_SHORT).show()
    }
  }

  private fun exportCsv(filename: String, csvContent: String) {
    try {
      // 1. Write to internal cache directory for rock-solid FileProvider access
      val cacheFile = File(cacheDir, filename)
      cacheFile.writeText(csvContent, Charsets.UTF_8)

      val fileUri: Uri = androidx.core.content.FileProvider.getUriForFile(
        this,
        "${packageName}.fileprovider",
        cacheFile
      )

      // 2. Also save to Downloads folder via MediaStore so the file is stored permanently on device
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
          }
          val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { os ->
              os.write(csvContent.toByteArray(Charsets.UTF_8))
            }
          }
        } else {
          val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
          if (!downloadsDir.exists()) downloadsDir.mkdirs()
          val permFile = File(downloadsDir, filename)
          permFile.writeText(csvContent, Charsets.UTF_8)
        }
      } catch (storageEx: Exception) {
        Log.w("MainActivity", "Downloads directory save skipped: ${storageEx.message}")
      }

      Toast.makeText(this, "💾 $filename ready! Opening spreadsheet...", Toast.LENGTH_SHORT).show()

      // 3. Share as pure file attachment (EXTRA_STREAM only, NO EXTRA_TEXT)
      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, filename)
        putExtra(Intent.EXTRA_STREAM, fileUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(Intent.createChooser(shareIntent, "Open with MS Excel / Google Sheets"))
    } catch (e: Exception) {
      Log.e("MainActivity", "Error exporting CSV file", e)
      Toast.makeText(this, "CSV Export error: ${e.localizedMessage ?: e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  private fun exportSchedulePdf() {
    try {
      val uiState = viewModel.uiState.value
      val product = viewModel.getSelectedProduct()
      val tenure = uiState.selectedTenure
      val principal = viewModel.getPrincipalForTenure(tenure)
      val rate = LoanCalculators.getApplicableRate(product, principal)
      val schedulePair = LoanCalculators.generateInteractiveSchedule(
        principal = principal,
        annualRate = rate,
        tenureMonths = tenure,
        rateType = product.rateType,
        extraPayments = uiState.extraPayments,
        mode = uiState.prepaymentMode
      )
      SchedulePdfGenerator.generateAndShareSchedulePdf(
        context = this,
        uiState = uiState,
        rows = schedulePair.first,
        summary = schedulePair.second,
        actualPrincipal = principal
      )
    } catch (e: Exception) {
      Toast.makeText(this, "PDF Export error: ${e.localizedMessage ?: e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  private fun sharePlainText(text: String, title: String) {
    try {
      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
      }
      startActivity(Intent.createChooser(shareIntent, title))
    } catch (e: Exception) {
      copyToClipboard(text)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanAppContent(
  viewModel: LoanViewModel,
  uiState: LoanUiState,
  onShareWhatsApp: (String, String) -> Unit,
  onShareSms: (String, String) -> Unit,
  onCopyProposal: (String) -> Unit,
  onCallCustomer: (String) -> Unit,
  onExportCsv: (String, String) -> Unit,
  onExportPdf: (String) -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  var showSignOutConfirm by remember { mutableStateOf(false) }

  LaunchedEffect(uiState.toastMessage) {
    uiState.toastMessage?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.clearToast()
    }
  }

  // Dedicated Full-Screen Amortization Schedule (Takes over view with TopAppBar back arrow)
  if (uiState.showAmortizationScreen) {
    AmortizationScreen(
      viewModel = viewModel,
      uiState = uiState,
      onDismiss = { viewModel.closeAmortizationScreen() },
      onShareWhatsApp = onShareWhatsApp,
      onShareSms = onShareSms,
      onExportPdf = onExportPdf
    )
    return
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Color(0xFF0F172A),
          titleContentColor = Color.White
        ),
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Box(
              modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF1E3A8A), CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Text("🏛️", fontSize = 16.sp)
            }
            Column {
              Text("KB Loan Calculator", fontWeight = FontWeight.Black, fontSize = 16.sp)
              Text(
                text = "${uiState.profile.branchName.ifBlank { "Branch" }} • ${uiState.profile.officerName.ifBlank { "Officer" }} (${uiState.profile.staffId.ifBlank { "Staff ID" }})",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
              )
            }
          }
        },
        actions = {
          // Profile Settings Button in Top Header
          IconButton(
            onClick = { viewModel.openProfileDialog() },
            modifier = Modifier.press3d(0.90f)
          ) {
            Icon(Icons.Default.AccountCircle, contentDescription = "Officer Profile", tint = Color(0xFFFBBF24))
          }

          // Sign Out Icon Button
          IconButton(
            onClick = { showSignOutConfirm = true },
            modifier = Modifier.press3d(0.90f)
          ) {
            Icon(Icons.Default.ExitToApp, contentDescription = "Sign Out", tint = Color(0xFFF87171))
          }

          // Status and Sync button with 3D press
          OutlinedButton(
            onClick = { viewModel.syncNow() },
            colors = ButtonDefaults.outlinedButtonColors(
              containerColor = if (uiState.isOnline) Color(0xFF064E3B).copy(alpha = 0.7f) else Color(0xFF78350F).copy(alpha = 0.7f),
              contentColor = if (uiState.isOnline) Color(0xFF6EE7B7) else Color(0xFFFCD34D)
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier
              .height(32.dp)
              .press3d(0.94f)
          ) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .background(if (uiState.isOnline) Color(0xFF34D399) else Color(0xFFFBBF24), CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (uiState.isOnline) "Online" else "Offline", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (uiState.syncQueue.isNotEmpty()) {
              Spacer(modifier = Modifier.width(4.dp))
              Badge {
                Text("${uiState.syncQueue.size}", fontSize = 9.sp)
              }
            }
          }
          Spacer(modifier = Modifier.width(6.dp))
        }
      )
    },
    bottomBar = {
      Surface(
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
      ) {
        NavigationBar(
          containerColor = Color.Transparent,
          contentColor = Color.White
        ) {
          NavigationBarItem(
            selected = uiState.activeTab == 0,
            onClick = { viewModel.setTab(0) },
            icon = { Icon(Icons.Default.Calculate, contentDescription = "Calculator") },
            label = { Text("Calculator", fontWeight = if (uiState.activeTab == 0) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.press3d(0.95f),
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = Color(0xFF0F172A),
              selectedTextColor = Color(0xFFFBBF24),
              indicatorColor = Color(0xFFFBBF24),
              unselectedIconColor = Color(0xFF94A3B8),
              unselectedTextColor = Color(0xFF94A3B8)
            )
          )

          NavigationBarItem(
            selected = uiState.activeTab == 1,
            onClick = { viewModel.setTab(1) },
            icon = {
              val activeUserLeadsCount = remember(
                uiState.leads,
                uiState.registeredUsers,
                uiState.profile.staffId,
                uiState.profile.officerName,
                uiState.authUserId
              ) {
                uiState.leads.count { lead ->
                  val isMine = uiState.isLeadOwnedByCurrentUser(lead)
                  val st = lead.status.trim()
                  isMine && !st.equals("Lost", ignoreCase = true) && !st.equals("Converted", ignoreCase = true)
                }
              }
              BadgedBox(
                badge = {
                  if (activeUserLeadsCount > 0) {
                    Badge { Text("$activeUserLeadsCount") }
                  }
                }
              ) {
                Icon(Icons.Default.FormatListBulleted, contentDescription = "Leads")
              }
            },
            label = { Text("Leads", fontWeight = if (uiState.activeTab == 1) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.press3d(0.95f),
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = Color(0xFF0F172A),
              selectedTextColor = Color(0xFFFBBF24),
              indicatorColor = Color(0xFFFBBF24),
              unselectedIconColor = Color(0xFF94A3B8),
              unselectedTextColor = Color(0xFF94A3B8)
            )
          )

          NavigationBarItem(
            selected = uiState.activeTab == 2,
            onClick = { viewModel.setTab(2) },
            icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = "Admin") },
            label = { Text("Admin & Slabs", fontWeight = if (uiState.activeTab == 2) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.press3d(0.95f),
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = Color(0xFF0F172A),
              selectedTextColor = Color(0xFFFBBF24),
              indicatorColor = Color(0xFFFBBF24),
              unselectedIconColor = Color(0xFF94A3B8),
              unselectedTextColor = Color(0xFF94A3B8)
            )
          )
        }
      }
    }
  ) { innerPadding ->
    GlassMeshBackground(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      if (uiState.isAppUpdateRequired && !uiState.isAdminUnlocked && uiState.activeTab != 2) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Card(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Icon(
                Icons.Default.SystemUpdate,
                contentDescription = "Update Required",
                tint = Color(0xFF1D4ED8),
                modifier = Modifier.size(48.dp)
              )
              Text(
                text = "Mandatory App Update Required",
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                color = Color(0xFF1E3A8A)
              )
              Text(
                text = uiState.appUpdateMessage.ifBlank {
                  "A new version of KB Loan Calculator has been released by Admin. All app features are locked until you download and install the latest update."
                },
                fontSize = 12.sp,
                color = Color(0xFF1E40AF),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
              Button(
                onClick = {
                  viewModel.markAppUpdateDownloadStarted()
                  try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.appUpdateUrl))
                    context.startActivity(intent)
                  } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open update link: ${uiState.appUpdateUrl}", Toast.LENGTH_LONG).show()
                  }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                shape = RoundedCornerShape(10.dp)
              ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("⬇️ Download & Install Update Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
              if (uiState.hasClickedAppUpdateDownload) {
                Button(
                  onClick = { viewModel.verifyAppUpdateInstallation() },
                  modifier = Modifier.fillMaxWidth(),
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D)),
                  shape = RoundedCornerShape(10.dp)
                ) {
                  Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("🔄 Verify Installation & Unlock App", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      } else if (uiState.isCurrentUserBlocked && uiState.activeTab != 2) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Card(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Icon(
                Icons.Default.Block,
                contentDescription = "Blocked",
                tint = Color(0xFFDC2626),
                modifier = Modifier.size(48.dp)
              )
              Text(
                text = "Access Restricted by Admin",
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                color = Color(0xFF991B1B)
              )
              Text(
                text = "Your officer account (${uiState.profile.officerName.ifBlank { uiState.authUserEmail ?: "User" }} • ${uiState.profile.staffId.ifBlank { "ID" }}) has been temporarily blocked/disabled by the Administrator. Please contact your Admin to restore access.",
                fontSize = 12.sp,
                color = Color(0xFF7F1D1D),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
              Button(
                onClick = { viewModel.syncNow() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("🔄 Refresh / Check Access Status", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                OutlinedButton(
                  onClick = { viewModel.setTab(2) },
                  modifier = Modifier.weight(1f),
                  shape = RoundedCornerShape(8.dp)
                ) {
                  Text("Admin Unlock", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                  onClick = { viewModel.signOut() },
                  modifier = Modifier.weight(1f),
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                  shape = RoundedCornerShape(8.dp)
                ) {
                  Text("Sign Out", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      } else {
        when (uiState.activeTab) {
          0 -> CalculatorScreen(
            viewModel = viewModel,
            uiState = uiState,
            onShareWhatsApp = onShareWhatsApp,
            onShareSms = onShareSms,
            onCopyProposal = onCopyProposal,
            onExportPdf = onExportPdf
          )
          1 -> LeadsScreen(
            viewModel = viewModel,
            uiState = uiState,
            onCallCustomer = onCallCustomer,
            onWhatsAppCustomer = onShareWhatsApp,
            onSmsCustomer = onShareSms,
            onExportCsv = onExportCsv
          )
          2 -> AdminScreen(
            viewModel = viewModel,
            uiState = uiState,
            onExportCsv = onExportCsv
          )
        }
      }
    }
  }

  // Mandatory App Update Pop-Up Modal (Blocks access until user downloads & installs update)
  if (uiState.isAppUpdateRequired && !uiState.isAdminUnlocked && !uiState.showAdminPinDialog) {
    val context = androidx.compose.ui.platform.LocalContext.current
    MandatoryAppUpdateDialog(
      updateUrl = uiState.appUpdateUrl,
      message = uiState.appUpdateMessage,
      hasClickedDownload = uiState.hasClickedAppUpdateDownload,
      onDownloadClick = {
        viewModel.markAppUpdateDownloadStarted()
        try {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.appUpdateUrl))
          context.startActivity(intent)
        } catch (e: Exception) {
          Toast.makeText(context, "Cannot open update link: ${uiState.appUpdateUrl}", Toast.LENGTH_LONG).show()
        }
      },
      onVerifyInstallClick = {
        viewModel.verifyAppUpdateInstallation()
      },
      onAdminUnlockClick = {
        viewModel.setTab(2)
      }
    )
  }

  // 1. Mandatory First-Time Onboarding Modal
  if (uiState.showOnboardingDialog) {
    OnboardingOfficerDialog(
      onComplete = { name, staffId, branch, restore ->
        viewModel.completeOnboarding(name, staffId, branch, restore)
      }
    )
  }

  // 2. Profile Settings Dialog (Editable anytime from top bar)
  if (uiState.showProfileDialog) {
    ProfileSettingsDialog(
      currentName = uiState.profile.officerName,
      currentStaffId = uiState.profile.staffId,
      currentBranch = uiState.profile.branchName,
      currentThemeMode = uiState.themeMode,
      onThemeChange = { viewModel.setThemeMode(it) },
      onDismiss = { viewModel.dismissProfileDialog() },
      onSave = { name, staffId, branch ->
        viewModel.updateProfile(name, staffId, branch)
      }
    )
  }

  // Sign Out Confirmation Dialog
  if (showSignOutConfirm) {
    AlertDialog(
      onDismissRequest = { showSignOutConfirm = false },
      title = { Text("Sign Out of Google Account?", fontWeight = FontWeight.Bold) },
      text = { Text("Currently connected as ${uiState.authUserEmail ?: "Google Account"}. Signing out will pause real-time cloud sync on this device.") },
      confirmButton = {
        Button(
          onClick = {
            showSignOutConfirm = false
            viewModel.signOut()
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          Text("Sign Out")
        }
      },
      dismissButton = {
        TextButton(onClick = { showSignOutConfirm = false }) {
          Text("Cancel")
        }
      }
    )
  }

  // 4. Admin Security Password Dialog
  if (uiState.showAdminPinDialog) {
    AdminPinDialog(
      onDismiss = { viewModel.dismissAdminPinDialog() },
      onVerify = { viewModel.verifyAdminPin(it) }
    )
  }

  // 5. Change Admin Password Dialog
  if (uiState.showAdminChangePasswordDialog) {
    ChangeAdminPasswordDialog(
      onDismiss = { viewModel.dismissChangePasswordDialog() },
      onChange = { oldP, newP -> viewModel.changeAdminPassword(oldP, newP) }
    )
  }

  // 6. Product Edit & Slabs Dialog
  if (uiState.showProductEditDialog) {
    ProductEditDialog(
      product = uiState.editingProduct,
      onDismiss = { viewModel.dismissProductEdit() },
      onSave = { viewModel.saveProduct(it) }
    )
  }

  // 7. Mandatory Admin Broadcast Notice Modal (Shown to all branch users until they read & click I Agree & Save)
  if (uiState.showAdminBroadcastDialog && !uiState.showOnboardingDialog && uiState.adminBroadcastMessage.isNotBlank()) {
    AdminBroadcastNoticeDialog(
      message = uiState.adminBroadcastMessage,
      onAgreeAndSave = { viewModel.acknowledgeAdminBroadcast() }
    )
  }
}

@Composable
fun AdminBroadcastNoticeDialog(
  message: String,
  onAgreeAndSave: () -> Unit
) {
  var isChecked by remember(message) { mutableStateOf(false) }

  Dialog(
    onDismissRequest = { /* Mandatory acknowledgment required */ },
    properties = androidx.compose.ui.window.DialogProperties(
      dismissOnBackPress = false,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false
    )
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .padding(16.dp),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .background(Color(0xFF1E3A8A), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Text("📢", fontSize = 18.sp)
          }
          Column {
            Text(
              text = "Official Admin Message / Notice",
              fontWeight = FontWeight.Black,
              fontSize = 15.sp,
              color = Color(0xFF0F172A)
            )
            Text(
              text = "Please read carefully before proceeding to Calculator",
              fontSize = 11.sp,
              color = Color(0xFF64748B)
            )
          }
        }

        Surface(
          color = Color(0xFFEFF6FF),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "📌 Message from Admin (SOC & Product Update):",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF1E3A8A)
            )
            Text(
              text = message,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Medium,
              color = Color(0xFF0F172A),
              lineHeight = 18.sp
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          Checkbox(
            checked = isChecked,
            onCheckedChange = { isChecked = it }
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "I have carefully read and understood the Admin notice.",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF334155)
          )
        }

        Button(
          onClick = onAgreeAndSave,
          enabled = isChecked,
          modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857)),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("I Agree & Save (Open Calculator)", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        }
      }
    }
  }
}

@Composable
fun OnboardingOfficerDialog(
  onComplete: (name: String, staffId: String, branch: String, restoreCloud: Boolean) -> Unit
) {
  var name by remember { mutableStateOf("") }
  var staffId by remember { mutableStateOf("") }
  var branch by remember { mutableStateOf("") }
  var restoreCloud by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = {}) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.95f)
        .padding(16.dp),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .background(Color(0xFF1D4ED8), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Text("🏛️", fontSize = 18.sp)
          }
          Column {
            Text("KB Loan Calculator", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("Officer Setup & Onboarding", fontSize = 12.sp, color = Color.Gray)
          }
        }

        Text(
          text = "Please enter your official bank credentials. These details are tied to your sales leads and calculations for supervisor audit trails.",
          fontSize = 11.sp,
          color = Color(0xFF475569)
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Officer Full Name *") },
          placeholder = { Text("e.g. Muhammad Usman", color = Color(0xFF94A3B8)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        OutlinedTextField(
          value = staffId,
          onValueChange = { staffId = it },
          label = { Text("Staff / Employee ID *") },
          placeholder = { Text("e.g. RO-8942", color = Color(0xFF94A3B8)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        OutlinedTextField(
          value = branch,
          onValueChange = { branch = it },
          label = { Text("Branch Name / Location *") },
          placeholder = { Text("e.g. Blue Area Branch, Islamabad", color = Color(0xFF94A3B8)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = restoreCloud,
            onCheckedChange = { restoreCloud = it }
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Restore previous leads for this Staff ID", fontSize = 12.sp)
        }

        Button(
          onClick = {
            if (name.isNotBlank() && staffId.isNotBlank()) {
              onComplete(name, staffId, branch.ifBlank { "Main Branch" }, restoreCloud)
            }
          },
          enabled = name.isNotBlank() && staffId.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
          Text("Save & Get Started", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
fun ProfileSettingsDialog(
  currentName: String,
  currentStaffId: String,
  currentBranch: String,
  currentThemeMode: String,
  onThemeChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onSave: (String, String, String) -> Unit
) {
  var name by remember { mutableStateOf(currentName) }
  var staffId by remember { mutableStateOf(currentStaffId) }
  var branch by remember { mutableStateOf(currentBranch) }
  var selectedTheme by remember { mutableStateOf(currentThemeMode) }
  val isDark = when (selectedTheme) {
    "dark" -> true
    "light" -> false
    else -> androidx.compose.foundation.isSystemInDarkTheme()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Officer Profile & Theme Settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Theme Selection Card
        Card(
          shape = RoundedCornerShape(10.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🎨 Appearance / Theme Mode", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Text("Select preferred visual mode for application:", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              FilterChip(
                selected = selectedTheme == "light",
                onClick = {
                  selectedTheme = "light"
                  onThemeChange("light")
                },
                label = { Text("☀️ Light", fontSize = 10.5.sp) },
                modifier = Modifier.weight(1f)
              )
              FilterChip(
                selected = selectedTheme == "dark",
                onClick = {
                  selectedTheme = "dark"
                  onThemeChange("dark")
                },
                label = { Text("🌙 Dark", fontSize = 10.5.sp) },
                modifier = Modifier.weight(1f)
              )
              FilterChip(
                selected = selectedTheme == "system",
                onClick = {
                  selectedTheme = "system"
                  onThemeChange("system")
                },
                label = { Text("📱 Auto", fontSize = 10.5.sp) },
                modifier = Modifier.weight(1f)
              )
            }
          }
        }

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Officer Name") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          colors = kbInputFieldColors(isDark)
        )
        OutlinedTextField(
          value = staffId,
          onValueChange = { staffId = it },
          label = { Text("Staff / Employee ID") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          colors = kbInputFieldColors(isDark)
        )
        OutlinedTextField(
          value = branch,
          onValueChange = { branch = it },
          label = { Text("Branch Location") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          colors = kbInputFieldColors(isDark)
        )
      }
    },
    confirmButton = {
      Button(onClick = { onSave(name, staffId, branch) }) {
        Text("Save Settings")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

@Composable
fun MandatoryAppUpdateDialog(
  updateUrl: String,
  message: String,
  hasClickedDownload: Boolean,
  onDownloadClick: () -> Unit,
  onVerifyInstallClick: () -> Unit,
  onAdminUnlockClick: () -> Unit
) {
  Dialog(
    onDismissRequest = { /* Mandatory update - cannot dismiss without updating */ },
    properties = androidx.compose.ui.window.DialogProperties(
      dismissOnBackPress = false,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false
    )
  ) {
    Card(
      modifier = Modifier
        .widthIn(max = 460.dp)
        .fillMaxWidth(0.94f)
        .padding(16.dp),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(54.dp)
            .background(Color(0xFFDBEAFE), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            Icons.Default.SystemUpdate,
            contentDescription = "App Update Available",
            tint = Color(0xFF1D4ED8),
            modifier = Modifier.size(30.dp)
          )
        }

        Text(
          text = "New App Update Available!",
          fontWeight = FontWeight.Black,
          fontSize = 17.sp,
          color = Color(0xFF1E3A8A)
        )

        Surface(
          color = Color(0xFFFEF2F2),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "🔒 App Access Locked Until Updated",
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            color = Color(0xFFDC2626),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(8.dp)
          )
        }

        Text(
          text = message.ifBlank {
            "A mandatory new version of KB Loan Calculator has been published by Admin. Please download and install the latest update using the button below to restore your app access and use the new features."
          },
          fontSize = 12.sp,
          color = Color(0xFF334155),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Button(
          onClick = onDownloadClick,
          modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("⬇️ Download Latest APK", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        }

        if (hasClickedDownload) {
          Button(
            onClick = onVerifyInstallClick,
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D)),
            shape = RoundedCornerShape(10.dp)
          ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("🔄 Verify Installation & Unlock App", fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
          Text(
            text = "⚠️ Note: After downloading the APK, please open your device's Downloads or Files app, tap the APK and finish installing it. Then tap 'Verify Installation & Unlock App' above.",
            fontSize = 10.sp,
            color = Color(0xFF64748B),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        } else {
          Text(
            text = "Tap 'Download Latest APK' above to download the update from cloud storage.",
            fontSize = 10.5.sp,
            color = Color(0xFF64748B),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }

        TextButton(
          onClick = onAdminUnlockClick,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
          Text("🔐 Admin Panel Unlock", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}
