package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CustomerLead
import com.example.model.FeeSlab
import com.example.model.LoanProduct
import com.example.model.RateSlab
import com.example.model.RegisteredUser
import com.example.ui.LoanUiState
import com.example.ui.LoanViewModel
import com.example.ui.theme.*
import com.example.util.LoanCalculators

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
  viewModel: LoanViewModel,
  uiState: LoanUiState,
  onExportCsv: ((String, String) -> Unit)? = null
) {
  val isDark = when (uiState.themeMode) {
    "dark" -> true
    "light" -> false
    else -> androidx.compose.foundation.isSystemInDarkTheme()
  }
  var globalFedInput by remember(uiState.profile.fedRate) { mutableStateOf(uiState.profile.fedRate.toString()) }
  var globalFilerTaxInput by remember(uiState.profile.filerTaxRate) { mutableStateOf(uiState.profile.filerTaxRate.toString()) }
  var globalNonFilerTaxInput by remember(uiState.profile.nonFilerTaxRate) { mutableStateOf(uiState.profile.nonFilerTaxRate.toString()) }
  var adminNoticeInput by remember(uiState.adminBroadcastMessage) {
    mutableStateOf(uiState.adminBroadcastMessage)
  }
  var appUpdateUrlInput by remember(uiState.appUpdateUrl) {
    mutableStateOf(uiState.appUpdateUrl)
  }
  var appUpdateMsgInput by remember(uiState.appUpdateMessage) {
    mutableStateOf(
      uiState.appUpdateMessage.ifBlank {
        "A mandatory new update of KB Loan Calculator is available! Please download and install the latest version to restore app access."
      }
    )
  }

  LaunchedEffect(Unit) {
    viewModel.refreshRegisteredUsers()
  }

  // Bulk Product Import Dialog state
  var showBulkImportDialog by remember { mutableStateOf(false) }

  // Lead Deletion & Filtering states
  var leadToDelete by remember { mutableStateOf<CustomerLead?>(null) }
  var showClearAllLeadsDialog by remember { mutableStateOf(false) }
  var adminLeadFilterQuery by remember { mutableStateOf("") }
  val selectedLeadIds = remember { mutableStateListOf<String>() }
  var pendingBulkDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
  var pendingBulkDeleteLabel by remember { mutableStateOf("") }

  // User Access & Branch Rights Management states
  var editingUserAccess by remember { mutableStateOf<RegisteredUser?>(null) }
  var showAddUserAccessDialog by remember { mutableStateOf(false) }
  var userToDelete by remember { mutableStateOf<RegisteredUser?>(null) }
  var adminUserSearchQuery by remember { mutableStateOf("") }

  // Product Search state inside Loan Facilities & Slabs
  var adminProductSearchQuery by remember { mutableStateOf("") }
  var adminCategoryFilter by remember { mutableStateOf("all") } // "all", "asset", "liability"

  // Collapsible / Expandable states for all Admin sections (Folded by default so Admin can click to expand/collapse)
  var isSecuritySectionExpanded by remember { mutableStateOf(false) }
  var isGoogleSheetsSectionExpanded by remember { mutableStateOf(false) }
  var isFedSectionExpanded by remember { mutableStateOf(false) }
  var isBroadcastSectionExpanded by remember { mutableStateOf(false) }
  var isAppUpdateSectionExpanded by remember { mutableStateOf(false) }
  var isFacilitiesSectionExpanded by remember { mutableStateOf(false) }
  var isUsersSectionExpanded by remember { mutableStateOf(false) }
  var isLeadsSectionExpanded by remember { mutableStateOf(false) }

  var sheetsUrlInput by rememberSaveable(uiState.googleSheetsWebhookUrl) {
    mutableStateOf(uiState.googleSheetsWebhookUrl)
  }
  var showScriptGuideDialog by remember { mutableStateOf(false) }

  // Combine registeredUsers from Firestore with current profile and any officers found in leads (excluding deleted users)
  val allManagedUsers = remember(uiState.registeredUsers, uiState.leads, uiState.deletedUserKeys, uiState.profile, uiState.authUserId, uiState.authUserEmail) {
    val map = LinkedHashMap<String, RegisteredUser>()
    uiState.registeredUsers.forEach { u ->
      if (!viewModel.isUserKeyDeleted(u, uiState.deletedUserKeys)) {
        val key = u.uid.ifBlank { u.staffId.ifBlank { u.officerName } }
        if (key.isNotBlank()) map[key] = u
      }
    }
    if (uiState.profile.isOnboarded && (uiState.profile.staffId.isNotBlank() || uiState.profile.officerName.isNotBlank())) {
      val selfUid = uiState.authUserId?.takeIf { it.isNotBlank() }
        ?: "staff_${uiState.profile.staffId.trim().lowercase().ifBlank { "local_officer" }}"
      val selfCandidate = RegisteredUser(
        uid = selfUid,
        email = uiState.authUserEmail.orEmpty(),
        officerName = uiState.profile.officerName,
        staffId = uiState.profile.staffId,
        branchName = uiState.profile.branchName,
        allowedSupervisorBranch = uiState.profile.branchName,
        canUseSupervisorFilter = true,
        isBlocked = true,
        role = "Loan Officer"
      )
      val isDeleted = viewModel.isUserKeyDeleted(selfCandidate, uiState.deletedUserKeys)
      val alreadyExists = map.values.any { u ->
        u.uid == selfUid ||
          (uiState.profile.staffId.isNotBlank() && u.staffId.equals(uiState.profile.staffId.trim(), ignoreCase = true))
      }
      if (!isDeleted && !alreadyExists) {
        map[selfUid] = selfCandidate
      }
    }
    uiState.leads.forEach { lead ->
      val staffKey = lead.officerId.trim()
      val uidKey = lead.userId.trim()
      val syntheticUid = uidKey.ifBlank { "staff_${staffKey.ifBlank { lead.officerName.lowercase().replace(" ", "_") }}" }
      val candidateUser = RegisteredUser(
        uid = syntheticUid,
        officerName = lead.officerName.ifBlank { "Loan Officer" },
        staffId = staffKey,
        branchName = lead.branchName,
        allowedSupervisorBranch = lead.branchName,
        canUseSupervisorFilter = true,
        isBlocked = false,
        role = "Loan Officer"
      )
      val isDeleted = viewModel.isUserKeyDeleted(candidateUser, uiState.deletedUserKeys)
      val alreadyExists = map.values.any { u ->
        (uidKey.isNotEmpty() && u.uid == uidKey) ||
          (staffKey.isNotEmpty() && u.staffId.equals(staffKey, ignoreCase = true)) ||
          (lead.officerName.isNotBlank() && u.officerName.equals(lead.officerName.trim(), ignoreCase = true))
      }
      if (!isDeleted && !alreadyExists && (staffKey.isNotEmpty() || lead.officerName.isNotBlank())) {
        map[syntheticUid] = candidateUser
      }
    }
    map.values.toList()
  }

  // Filtered users inside User Access & Branch Rights
  val filteredManagedUsers = remember(allManagedUsers, adminUserSearchQuery) {
    val q = adminUserSearchQuery.trim()
    if (q.isEmpty()) {
      allManagedUsers
    } else {
      allManagedUsers.filter { u ->
        u.officerName.contains(q, ignoreCase = true) ||
          u.role.contains(q, ignoreCase = true) ||
          u.staffId.contains(q, ignoreCase = true) ||
          u.branchName.contains(q, ignoreCase = true) ||
          u.allowedSupervisorBranch.contains(q, ignoreCase = true) ||
          u.email.contains(q, ignoreCase = true) ||
          (if (u.isBlocked) "blocked" else "active").contains(q, ignoreCase = true)
      }
    }
  }

  // Filtered products inside Loan Facilities & Slabs
  val adminFilteredProducts = remember(uiState.products, adminProductSearchQuery, adminCategoryFilter) {
    val q = adminProductSearchQuery.trim()
    uiState.products.filter { p ->
      val matchesCat = when (adminCategoryFilter) {
        "asset" -> p.category != "liability"
        "liability" -> p.category == "liability"
        else -> true
      }
      val matchesSearch = q.isEmpty() ||
        p.name.contains(q, ignoreCase = true) ||
        p.id.contains(q, ignoreCase = true) ||
        p.rateType.contains(q, ignoreCase = true) ||
        p.defaultAnnualRate.toString().contains(q)
      matchesCat && matchesSearch
    }
  }

  // Filtered leads inside Admin Lead Database Management
  val adminFilteredLeads = remember(uiState.leads, adminLeadFilterQuery) {
    val q = adminLeadFilterQuery.trim()
    if (q.isEmpty()) {
      uiState.leads
    } else {
      uiState.leads.filter { lead ->
        lead.customerName.contains(q, ignoreCase = true) ||
          lead.mobile.contains(q, ignoreCase = true) ||
          lead.branchName.contains(q, ignoreCase = true) ||
          lead.officerName.contains(q, ignoreCase = true) ||
          lead.officerId.contains(q, ignoreCase = true) ||
          lead.productName.contains(q, ignoreCase = true) ||
          lead.status.contains(q, ignoreCase = true)
      }
    }
  }

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter
  ) {
    val isWideScreen = maxWidth >= 600.dp
    val horizontalPad = if (isWideScreen) 24.dp else 14.dp
    val contentMaxWidth = if (isWideScreen) 840.dp else maxWidth

    LazyColumn(
      modifier = Modifier
        .widthIn(max = contentMaxWidth)
        .fillMaxSize()
        .padding(horizontal = horizontalPad),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp)
    ) {

    // 0. App Theme & Appearance Setting Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("🎨 Theme & Display Appearance", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          Text("Switch application display mode between Light, Dark, or System Auto", fontSize = 11.sp, color = Color(0xFF64748B))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            FilterChip(
              selected = uiState.themeMode == "light",
              onClick = { viewModel.setThemeMode("light") },
              label = { Text("☀️ Light", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
              modifier = Modifier.weight(1f)
            )
            FilterChip(
              selected = uiState.themeMode == "dark",
              onClick = { viewModel.setThemeMode("dark") },
              label = { Text("🌙 Dark", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
              modifier = Modifier.weight(1f)
            )
            FilterChip(
              selected = uiState.themeMode == "system",
              onClick = { viewModel.setThemeMode("system") },
              label = { Text("📱 Auto", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }

    // 0B. Google Sheets Live Integration (Webhook URL)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          AdminCollapsibleHeader(
            title = "📊 Google Sheets Live Leads Integration",
            subtitle = if (sheetsUrlInput.isNotBlank()) "✅ Live sync active • Leads log directly to Google Sheet" else "⚠️ Disconnected • Set Web App URL to auto-log leads to Google Sheets",
            isExpanded = isGoogleSheetsSectionExpanded,
            onToggle = { isGoogleSheetsSectionExpanded = !isGoogleSheetsSectionExpanded }
          )

          if (isGoogleSheetsSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))

            Text(
              text = "Enter your Google Apps Script Web App URL below. When saved, all customer leads from all 400-500 officers will automatically log directly into your Google Spreadsheet in real-time, eliminating Firestore timeouts and database quota limits.",
              fontSize = 11.5.sp,
              color = Color(0xFF475569),
              lineHeight = 16.sp
            )

            OutlinedTextField(
              value = sheetsUrlInput,
              onValueChange = { sheetsUrlInput = it },
              label = { Text("Google Apps Script Web App URL", fontSize = 11.sp) },
              placeholder = { Text("https://script.google.com/macros/s/.../exec", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
              colors = kbInputFieldColors(isDark),
              leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp)) },
              trailingIcon = {
                if (sheetsUrlInput.isNotBlank()) {
                  IconButton(onClick = { sheetsUrlInput = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(15.dp))
                  }
                }
              },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth(),
              singleLine = true
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Button(
                onClick = { viewModel.setGoogleSheetsWebhookUrl(sheetsUrlInput) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save & Sync URL", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }

              OutlinedButton(
                onClick = { viewModel.testGoogleSheetsConnection(sheetsUrlInput) },
                enabled = !uiState.isTestingSheetsUrl && sheetsUrlInput.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                if (uiState.isTestingSheetsUrl) {
                  CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Testing...", fontSize = 11.sp)
                } else {
                  Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Test Connection", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
              }
            }

            TextButton(
              onClick = { showScriptGuideDialog = true },
              contentPadding = PaddingValues(0.dp)
            ) {
              Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("📋 Click here to view & copy ready-to-use Google Apps Script code", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }

    // 1. Password Change Section (Foldable / Expandable)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          AdminCollapsibleHeader(
            title = "🔐 Admin & SOC Slabs Configuration",
            subtitle = "Tiered rates, fee slabs, lead database, and security settings.",
            isExpanded = isSecuritySectionExpanded,
            onToggle = { isSecuritySectionExpanded = !isSecuritySectionExpanded }
          )

          if (isSecuritySectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Button(
                onClick = { viewModel.openChangePasswordDialog() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Change Admin Password", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
              }

              OutlinedButton(
                onClick = { viewModel.lockAdmin() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lock Admin Session", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
              }
            }
          }
        }
      }
    }

    // 2. Global FED % Control Card (Foldable / Expandable)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          AdminCollapsibleHeader(
            title = "🏛️ Global FED & Deposit Tax Rates (WHT)",
            subtitle = "FED: ${uiState.profile.fedRate}% • Filer WHT: ${uiState.profile.filerTaxRate}% • Non-Filer WHT: ${uiState.profile.nonFilerTaxRate}%",
            isExpanded = isFedSectionExpanded,
            onToggle = { isFedSectionExpanded = !isFedSectionExpanded }
          )

          if (isFedSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))
            Text(
              text = "Configure global tax deduction rates applicable across the entire bank. Deposits deduct 20% WHT for Filers and 40% WHT for Non-Filers from profit. Changes sync globally to all branch users instantly.",
              fontSize = 11.sp,
              color = Color(0xFF64748B)
            )

            OutlinedTextField(
              value = globalFedInput,
              onValueChange = { globalFedInput = it },
              label = { Text("Global FED % (Loan Processing Charges)") },
              placeholder = { Text("e.g. 16", color = Color(0xFF94A3B8)) },
              colors = kbInputFieldColors(isDark),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.fillMaxWidth(),
              singleLine = true
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedTextField(
                value = globalFilerTaxInput,
                onValueChange = { globalFilerTaxInput = it },
                label = { Text("Filer WHT % (فائلر ٹیکس)") },
                placeholder = { Text("20", color = Color(0xFF94A3B8)) },
                colors = kbInputFieldColors(isDark),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
              )
              OutlinedTextField(
                value = globalNonFilerTaxInput,
                onValueChange = { globalNonFilerTaxInput = it },
                label = { Text("Non-Filer WHT % (نان فائلر)") },
                placeholder = { Text("40", color = Color(0xFF94A3B8)) },
                colors = kbInputFieldColors(isDark),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
              )
            }

            Button(
              onClick = {
                val fed = globalFedInput.toDoubleOrNull() ?: 16.0
                val filerTax = globalFilerTaxInput.toDoubleOrNull() ?: 20.0
                val nonFilerTax = globalNonFilerTaxInput.toDoubleOrNull() ?: 40.0
                viewModel.updateGlobalTaxRates(fed, filerTax, nonFilerTax)
              },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              shape = RoundedCornerShape(8.dp)
            ) {
              Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Save Global FED & Deposit Tax Rates", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
          }
        }
      }
    }

    // 2B. Admin Broadcast Notice / SOC & Product Update Announcement Card (Foldable / Expandable)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          AdminCollapsibleHeader(
            title = "📢 Admin Broadcast Notice (All Branch Users)",
            subtitle = "Publish official SOC updates or policy announcements for all branch officers.",
            isExpanded = isBroadcastSectionExpanded,
            onToggle = { isBroadcastSectionExpanded = !isBroadcastSectionExpanded }
          )

          if (isBroadcastSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))
            Text(
              text = "Whenever a new product is configured or SOC slabs are updated, write an official admin note below and submit. Every branch user will see this message upon login/app open and must click 'I Agree & Save' before opening the Calculator.",
              fontSize = 11.sp,
              color = Color(0xFF64748B)
            )

            OutlinedTextField(
              value = adminNoticeInput,
              onValueChange = { adminNoticeInput = it },
              label = { Text("Official Admin Message / SOC Update Note", fontSize = 11.sp) },
              placeholder = { Text("Enter important SOC update, new product instructions, or policy announcement for all branch officers...", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
              colors = kbInputFieldColors(isDark),
              modifier = Modifier.fillMaxWidth(),
              minLines = 3,
              maxLines = 6,
              shape = RoundedCornerShape(10.dp)
            )

            Button(
              onClick = { viewModel.publishAdminBroadcast(adminNoticeInput) },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857)),
              shape = RoundedCornerShape(8.dp)
            ) {
              Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Submit & Broadcast Note to All Users", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            if (uiState.adminBroadcastMessage.isNotBlank() || adminNoticeInput.isNotBlank()) {
              OutlinedButton(
                onClick = {
                  adminNoticeInput = ""
                  viewModel.clearAdminBroadcast()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("✕ Clear / Remove Broadcast Notice", fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
              }
            }
          }
        }
      }
    }

    // 2C. Mandatory App Update Link & Force Upgrade Control Card (Foldable / Expandable)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val isUpdateActive = uiState.appUpdateUrl.isNotBlank() && uiState.appUpdateId.isNotBlank()
          AdminCollapsibleHeader(
            title = if (isUpdateActive) "📲 App Update & Force Upgrade (ACTIVE)" else "📲 App Update & Force Upgrade Link",
            subtitle = "Paste online APK download link to force all users to update before using the app.",
            isExpanded = isAppUpdateSectionExpanded,
            onToggle = { isAppUpdateSectionExpanded = !isAppUpdateSectionExpanded }
          )

          if (isAppUpdateSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))
            Text(
              text = "Paste your online APK storage link (Google Drive, Dropbox, OneDrive, or direct download URL) and click Submit. Every user will immediately see a mandatory pop-up and their app access will remain blocked until they download and install the update.",
              fontSize = 11.sp,
              color = Color(0xFF64748B)
            )

            if (isUpdateActive) {
              Surface(
                color = Color(0xFFFEF3C7),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  text = "⚡ Active Mandatory Update Link: ${uiState.appUpdateUrl}",
                  fontSize = 10.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF92400E),
                  modifier = Modifier.padding(8.dp)
                )
              }
            }

            OutlinedTextField(
              value = appUpdateUrlInput,
              onValueChange = { appUpdateUrlInput = it },
              label = { Text("App Update Download Link (URL) *", fontSize = 11.sp) },
              placeholder = { Text("https://drive.google.com/... or direct APK link", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
              colors = kbInputFieldColors(isDark),
              leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF1D4ED8)) },
              trailingIcon = {
                if (appUpdateUrlInput.isNotBlank()) {
                  IconButton(onClick = { appUpdateUrlInput = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear Link", modifier = Modifier.size(16.dp))
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              shape = RoundedCornerShape(10.dp)
            )

            OutlinedTextField(
              value = appUpdateMsgInput,
              onValueChange = { appUpdateMsgInput = it },
              label = { Text("Pop-Up Update Message for Users", fontSize = 11.sp) },
              placeholder = { Text("Please download and install the new app update to continue...", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp),
              colors = kbInputFieldColors(isDark),
              modifier = Modifier.fillMaxWidth(),
              minLines = 2,
              maxLines = 4,
              shape = RoundedCornerShape(10.dp)
            )

            Button(
              onClick = { viewModel.publishAppUpdateLink(appUpdateUrlInput, appUpdateMsgInput) },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
              shape = RoundedCornerShape(8.dp)
            ) {
              Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Submit & Force App Update on All Users", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            if (isUpdateActive) {
              OutlinedButton(
                onClick = {
                  appUpdateUrlInput = ""
                  viewModel.clearAppUpdateLink()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Disable / Clear Mandatory Update Lock", fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
              }
            }
          }
        }
      }
    }

    // 3. Loan Facilities, Slabs, and BULK CSV IMPORT / EXPORT Card (Foldable / Expandable + Product Search Box)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          AdminCollapsibleHeader(
            title = "💼 Loan Facilities & Slabs (${uiState.products.size})",
            subtitle = "Tiered amount-based rates, fee slabs, search, and bulk import/export.",
            isExpanded = isFacilitiesSectionExpanded,
            onToggle = { isFacilitiesSectionExpanded = !isFacilitiesSectionExpanded }
          )

          if (isFacilitiesSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))

            // Add New Product Buttons: Asset (Loan) & Liability (Deposit)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Button(
                onClick = { viewModel.openProductEdit(null) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("+ Asset Facility (لون)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
              }

              Button(
                onClick = {
                  viewModel.openProductEdit(
                    LoanProduct(
                      id = "",
                      name = "",
                      rateType = "flat",
                      defaultAnnualRate = 17.5,
                      minAmount = 25000L,
                      maxAmount = 25000000L,
                      minTenure = 1,
                      maxTenure = 60,
                      minAge = 18,
                      maxAgeAtMaturity = 90,
                      category = "liability"
                    )
                  )
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("+ Liability (ڈپازٹ)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
              }
            }

            // Category Filter Chips: All, Asset Facilities, Liability Products
            val assetCount = remember(uiState.products) { uiState.products.count { it.category != "liability" } }
            val liabilityCount = remember(uiState.products) { uiState.products.count { it.category == "liability" } }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              FilterChip(
                selected = adminCategoryFilter == "all",
                onClick = { adminCategoryFilter = "all" },
                label = { Text("All (${uiState.products.size})", fontSize = 10.5.sp, fontWeight = FontWeight.Medium) },
                modifier = Modifier.weight(1f)
              )
              FilterChip(
                selected = adminCategoryFilter == "asset",
                onClick = { adminCategoryFilter = "asset" },
                label = { Text("Asset ($assetCount)", fontSize = 10.5.sp, fontWeight = FontWeight.Medium) },
                modifier = Modifier.weight(1f)
              )
              FilterChip(
                selected = adminCategoryFilter == "liability",
                onClick = { adminCategoryFilter = "liability" },
                label = { Text("Liability ($liabilityCount)", fontSize = 10.5.sp, fontWeight = FontWeight.Medium) },
                modifier = Modifier.weight(1f)
              )
            }

            // Bulk Import & Export Buttons
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Button(
                onClick = { showBulkImportDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("📥 Import (CSV)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
              }

              OutlinedButton(
                onClick = {
                  val csv = viewModel.exportProductsCsv()
                  onExportCsv?.invoke("kb_loan_products.csv", csv)
                    ?: viewModel.showToast("CSV Export generated")
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
              ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("📤 Export (CSV)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
              }
            }

            OutlinedButton(
              onClick = { viewModel.resetDefaultProducts() },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("↺ Reset to Standard Bank SOC Facilities", fontSize = 11.sp)
            }

            // Search box right below Reset to Standard Bank SOC Facilities to search & edit/delete specific product
            OutlinedTextField(
              value = adminProductSearchQuery,
              onValueChange = { adminProductSearchQuery = it },
              label = { Text("Search Loan / Deposit Product", fontSize = 11.sp) },
              placeholder = { Text("Search product name, category, or rate...", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
              colors = kbInputFieldColors(isDark),
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF64748B)) },
              trailingIcon = {
                if (adminProductSearchQuery.isNotBlank()) {
                  IconButton(onClick = { adminProductSearchQuery = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear Search", modifier = Modifier.size(16.dp))
                  }
                }
              },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
              singleLine = true
            )

            if (adminFilteredProducts.isEmpty()) {
              Text(
                text = "No products match \"$adminProductSearchQuery\" in $adminCategoryFilter category.",
                fontSize = 11.5.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(vertical = 8.dp)
              )
            } else {
              adminFilteredProducts.forEach { p ->
                val isLiabilityProd = p.category == "liability"
                Card(
                  modifier = Modifier.fillMaxWidth(),
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                  shape = RoundedCornerShape(10.dp)
                ) {
                  Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                          shape = RoundedCornerShape(4.dp),
                          color = if (isLiabilityProd) Color(0xFFCCFBF1) else Color(0xFFDBEAFE)
                        ) {
                          Text(
                            text = if (isLiabilityProd) "LIABILITY / DEPOSIT" else "ASSET / LOAN",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLiabilityProd) Color(0xFF0F766E) else Color(0xFF1E40AF),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                          )
                        }
                        val slabRateLabel = if (isLiabilityProd && (p.liabilityPricingType == "amount" || (p.rateSlabs.size > 1 && p.tenureRates.isEmpty()))) {
                          val minR = p.rateSlabs.minOfOrNull { it.annualRate } ?: p.defaultAnnualRate
                          val maxR = p.rateSlabs.maxOfOrNull { it.annualRate } ?: p.defaultAnnualRate
                          if (minR == maxR) "$minR% (Balance Slabs)" else "$minR% - $maxR% (Balance Slabs)"
                        } else if (isLiabilityProd && p.tenureRates.isNotEmpty()) {
                          p.tenureRates.toSortedMap().entries.joinToString(", ") { "${it.key}M: ${it.value}%" }
                        } else if (p.rateSlabs.isNotEmpty()) {
                          val minR = p.rateSlabs.minOf { it.annualRate }
                          val maxR = p.rateSlabs.maxOf { it.annualRate }
                          if (minR == maxR) "$minR%" else "$minR% - $maxR%"
                        } else "${p.defaultAnnualRate}%"
                        val ratePrefix = if (isLiabilityProd) "Profit Rate" else "Slab Rate"
                        Text(
                          text = if (isLiabilityProd) "$ratePrefix: $slabRateLabel" else "$ratePrefix: $slabRateLabel (${p.rateType.uppercase()}) • Age: ${p.minAge}-${p.maxAgeAtMaturity} yrs",
                          fontSize = 11.sp,
                          color = Color(0xFF64748B)
                        )
                      }

                      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { viewModel.openProductEdit(p) }, modifier = Modifier.size(32.dp)) {
                          Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { viewModel.deleteProduct(p.id) }, modifier = Modifier.size(32.dp)) {
                          Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                        }
                      }
                    }

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      val amtLabel = if (isLiabilityProd) "Deposit" else "Loan"
                      Text(
                        text = "$amtLabel: ${LoanCalculators.formatCurrency(p.minAmount, uiState.profile.currency)} - ${LoanCalculators.formatCurrency(p.maxAmount, uiState.profile.currency)}",
                        fontSize = 10.5.sp,
                        color = Color(0xFF475569)
                      )
                      Text(
                        text = "Tenure: ${p.minTenure}-${p.maxTenure} Mo",
                        fontSize = 10.5.sp,
                        color = Color(0xFF475569)
                      )
                    }

                    if (isLiabilityProd && (p.liabilityPricingType == "amount" || (p.rateSlabs.size > 1 && p.tenureRates.isEmpty()))) {
                      Text("Amount Slabs: ${p.rateSlabs.size} balance-tiered slab(s) configured", fontSize = 10.sp, color = Color(0xFF0F766E), fontWeight = FontWeight.SemiBold)
                    } else if (isLiabilityProd && p.tenureRates.isNotEmpty()) {
                      Text("Tenure Slabs: ${p.tenureRates.toSortedMap().entries.joinToString(" | ") { "${it.key}M @ ${it.value}%" }}", fontSize = 10.sp, color = Color(0xFF0F766E), fontWeight = FontWeight.SemiBold)
                    } else if (p.rateSlabs.isNotEmpty()) {
                      Text("Rate Slabs: ${p.rateSlabs.size} tiered slab(s) configured", fontSize = 10.sp, color = Color(0xFF1D4ED8), fontWeight = FontWeight.SemiBold)
                    }
                    if (p.feeSlabs.isNotEmpty()) {
                      Text("Fee Slabs: ${p.feeSlabs.size} tiered fee slab(s) configured", fontSize = 10.sp, color = Color(0xFF047857), fontWeight = FontWeight.SemiBold)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // 3B. USER ACCESS & BRANCH RIGHTS MANAGEMENT SECTION (Foldable / Expandable + User Search Box)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          AdminCollapsibleHeader(
            title = "👥 User Access & Branch Rights (${allManagedUsers.size})",
            subtitle = "Allow/Block Loan Officers & RMs, delete users, and assign branch monitoring rights.",
            isExpanded = isUsersSectionExpanded,
            onToggle = { isUsersSectionExpanded = !isUsersSectionExpanded }
          )

          if (isUsersSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Button(
                onClick = { showAddUserAccessDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
              ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ Add / Configure Officer Branch Rights", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
              }

              OutlinedButton(
                onClick = { viewModel.refreshRegisteredUsers() },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
              ) {
                Icon(Icons.Default.Sync, contentDescription = "Sync Users", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }

            // Search box right below + Add / Configure Officer Branch Rights
            OutlinedTextField(
              value = adminUserSearchQuery,
              onValueChange = { adminUserSearchQuery = it },
              label = { Text("Search Officer / Designation / Staff Code / Branch", fontSize = 11.sp) },
              placeholder = { Text("Type name, role, staff code, branch, or email...", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
              colors = kbInputFieldColors(isDark),
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF64748B)) },
              trailingIcon = {
                if (adminUserSearchQuery.isNotBlank()) {
                  IconButton(onClick = { adminUserSearchQuery = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear User Search", modifier = Modifier.size(16.dp))
                  }
                }
              },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
              singleLine = true
            )

            if (filteredManagedUsers.isEmpty()) {
              Text(
                text = if (adminUserSearchQuery.isBlank()) {
                  "No registered officers yet. Users appear automatically (as Blocked by default) when they sign in, or you can add them above."
                } else {
                  "No users match \"$adminUserSearchQuery\"."
                },
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
              )
            } else {
              filteredManagedUsers.forEach { user ->
                val isBlocked = user.isBlocked
                Card(
                  modifier = Modifier.fillMaxWidth(),
                  colors = CardDefaults.cardColors(
                    containerColor = if (isBlocked) Color(0xFFFEF2F2) else Color(0xFFF8FAFC)
                  ),
                  shape = RoundedCornerShape(10.dp),
                  border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                      if (isBlocked) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                    )
                  )
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                          Text(
                            text = user.officerName.ifBlank { "Officer" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = if (isBlocked) Color(0xFF991B1B) else Color(0xFF0F172A)
                          )
                          Surface(
                            color = if (isBlocked) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(4.dp)
                          ) {
                            Text(
                              text = if (isBlocked) "BLOCKED" else "ACTIVE",
                              fontSize = 9.sp,
                              fontWeight = FontWeight.Bold,
                              color = if (isBlocked) Color(0xFFDC2626) else Color(0xFF15803D),
                              modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                          }
                        }
                        Text(
                          text = "${user.role} • Staff ID: ${user.staffId.ifBlank { "N/A" }} • Home Branch: ${user.branchName.ifBlank { "N/A" }}",
                          fontSize = 10.5.sp,
                          color = Color(0xFF475569)
                        )
                        if (user.email.isNotBlank()) {
                          Text(
                            text = "Email: ${user.email}",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                          )
                        }
                        val rightScope = when {
                          !user.canUseSupervisorFilter -> "Own Leads Only (Branch Filter Disabled)"
                          user.allowedSupervisorBranch.equals("ALL", ignoreCase = true) || user.allowedSupervisorBranch == "*" -> "All Branches (Supervisor Access)"
                          user.allowedSupervisorBranch.isNotBlank() -> "Branch Scope: ${user.allowedSupervisorBranch}"
                          else -> "Branch Scope: ${user.branchName.ifBlank { "Own Branch" }}"
                        }
                        Text(
                          text = "🔑 Access Rights: $rightScope",
                          fontSize = 10.5.sp,
                          fontWeight = FontWeight.SemiBold,
                          color = Color(0xFF1D4ED8)
                        )
                      }
                    }

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                      OutlinedButton(
                        onClick = { editingUserAccess = user },
                        modifier = Modifier
                          .weight(1.1f)
                          .height(32.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                      ) {
                        Icon(Icons.Default.ManageAccounts, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Branch Rights", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                      }

                      Button(
                        onClick = { viewModel.toggleUserBlocked(user) },
                        modifier = Modifier
                          .weight(1f)
                          .height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                          containerColor = if (isBlocked) Color(0xFF15803D) else Color(0xFFD97706)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                      ) {
                        Icon(
                          if (isBlocked) Icons.Default.CheckCircle else Icons.Default.Block,
                          contentDescription = null,
                          modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                          if (isBlocked) "Allow User" else "Block User",
                          fontSize = 10.sp,
                          fontWeight = FontWeight.Bold,
                          maxLines = 1
                        )
                      }

                      OutlinedButton(
                        onClick = { userToDelete = user },
                        modifier = Modifier
                          .weight(0.75f)
                          .height(32.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                      ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete User", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Delete", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // 4. LEAD DATABASE MANAGEMENT & DELETION SECTION (Foldable / Expandable)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          AdminCollapsibleHeader(
            title = "🗑️ Lead Database Management (${uiState.leads.size})",
            subtitle = "Filter leads by branch, officer, or customer and delete selected or filtered records.",
            isExpanded = isLeadsSectionExpanded,
            onToggle = { isLeadsSectionExpanded = !isLeadsSectionExpanded }
          )

          if (isLeadsSectionExpanded) {
            HorizontalDivider(color = Color(0xFFE2E8F0))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = {
                  if (uiState.leads.isEmpty()) {
                    viewModel.showToast("No leads available to export")
                  } else {
                    val csv = viewModel.exportAllLeadsCsv()
                    onExportCsv?.invoke("kb_leads_detailed_record.csv", csv)
                      ?: viewModel.showToast("Leads CSV exported")
                  }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF047857)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
              ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("📤 Export Detailed Leads (CSV)", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
              }

              if (uiState.leads.isNotEmpty()) {
                OutlinedButton(
                  onClick = { showClearAllLeadsDialog = true },
                  modifier = Modifier.weight(0.9f),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                  shape = RoundedCornerShape(8.dp),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                  Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Delete All (${uiState.leads.size})", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
              }
            }

            if (uiState.leads.isNotEmpty()) {
              // Search & Filter Bar for Admin Lead Deletion
              OutlinedTextField(
                value = adminLeadFilterQuery,
                onValueChange = { adminLeadFilterQuery = it },
                label = { Text("Search / Filter Leads to Delete", fontSize = 11.sp) },
                placeholder = { Text("Filter by Customer, Mobile, Branch, Staff ID, Status...", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                colors = kbInputFieldColors(isDark),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF64748B)) },
                trailingIcon = {
                  if (adminLeadFilterQuery.isNotBlank()) {
                    IconButton(onClick = { adminLeadFilterQuery = "" }, modifier = Modifier.size(28.dp)) {
                      Icon(Icons.Default.Clear, contentDescription = "Clear Filter", modifier = Modifier.size(16.dp))
                    }
                  }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp),
                singleLine = true
              )

              // Multi-select & Filtered Bulk Delete Controls
              val allFilteredSelected = adminFilteredLeads.isNotEmpty() && adminFilteredLeads.all { it.id in selectedLeadIds }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Checkbox(
                    checked = allFilteredSelected,
                    onCheckedChange = { checked ->
                      if (checked) {
                        adminFilteredLeads.forEach { l ->
                          if (l.id !in selectedLeadIds) selectedLeadIds.add(l.id)
                        }
                      } else {
                        val filteredSet = adminFilteredLeads.map { it.id }.toSet()
                        selectedLeadIds.removeAll(filteredSet)
                      }
                    },
                    modifier = Modifier.size(32.dp)
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = if (adminLeadFilterQuery.isBlank()) {
                      "Select All (${adminFilteredLeads.size}) • Selected: ${selectedLeadIds.size}"
                    } else {
                      "Filtered: ${adminFilteredLeads.size} lead(s) • Selected: ${selectedLeadIds.size}"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF334155)
                  )
                }

                if (selectedLeadIds.isNotEmpty()) {
                  TextButton(
                    onClick = { selectedLeadIds.clear() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                  ) {
                    Text("Clear Selection", fontSize = 10.5.sp, color = Color(0xFF64748B))
                  }
                }
              }

              // Action Buttons for Deleting Selected or Filtered Leads
              if (selectedLeadIds.isNotEmpty() || (adminLeadFilterQuery.isNotBlank() && adminFilteredLeads.isNotEmpty())) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  if (selectedLeadIds.isNotEmpty()) {
                    Button(
                      onClick = {
                        pendingBulkDeleteIds = selectedLeadIds.toSet()
                        pendingBulkDeleteLabel = "${selectedLeadIds.size} selected lead(s)"
                      },
                      modifier = Modifier.weight(1f),
                      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                      shape = RoundedCornerShape(8.dp),
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                      Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(15.dp))
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Delete Selected (${selectedLeadIds.size})", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                  }

                  if (adminLeadFilterQuery.isNotBlank() && adminFilteredLeads.isNotEmpty()) {
                    OutlinedButton(
                      onClick = {
                        pendingBulkDeleteIds = adminFilteredLeads.map { it.id }.toSet()
                        pendingBulkDeleteLabel = "all ${adminFilteredLeads.size} filtered lead(s) matching \"$adminLeadFilterQuery\""
                      },
                      modifier = Modifier.weight(1f),
                      colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB91C1C)),
                      shape = RoundedCornerShape(8.dp),
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                      Icon(Icons.Default.FilterAltOff, contentDescription = null, modifier = Modifier.size(15.dp))
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Delete Filtered (${adminFilteredLeads.size})", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                  }
                }
              }

              if (adminFilteredLeads.isEmpty()) {
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text("No leads match filter \"$adminLeadFilterQuery\".", fontSize = 11.5.sp, color = Color(0xFF94A3B8))
                }
              } else {
                adminFilteredLeads.forEach { lead ->
                  val isSelected = lead.id in selectedLeadIds
                  Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                      containerColor = if (isSelected) Color(0xFFFEF2F2) else Color(0xFFF8FAFC)
                    ),
                    shape = RoundedCornerShape(8.dp)
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                          if (checked) {
                            if (lead.id !in selectedLeadIds) selectedLeadIds.add(lead.id)
                          } else {
                            selectedLeadIds.remove(lead.id)
                          }
                        },
                        modifier = Modifier.size(32.dp)
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Column(modifier = Modifier.weight(1f)) {
                        Text(lead.customerName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                          "${lead.mobile} • ${lead.productName} • ${LoanCalculators.formatCurrency(lead.loanAmount, uiState.profile.currency)} (${lead.status})",
                          fontSize = 10.5.sp,
                          color = Color(0xFF64748B)
                        )
                        if (lead.branchName.isNotBlank() || lead.officerName.isNotBlank()) {
                          Text(
                            "📍 ${lead.branchName.ifBlank { "Branch" }} • 👤 ${lead.officerName.ifBlank { "Officer" }} (${lead.officerId.ifBlank { "ID" }})",
                            fontSize = 10.sp,
                            color = Color(0xFF475569)
                          )
                        }
                      }

                      IconButton(
                        onClick = { leadToDelete = lead },
                        modifier = Modifier.size(32.dp)
                      ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Lead", tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                      }
                    }
                  }
                }
              }
            } else {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                contentAlignment = Alignment.Center
              ) {
                Text("No leads currently stored in database.", fontSize = 12.sp, color = Color(0xFF94A3B8))
              }
            }
          }
        }
      }
    }
    }
  }

  // Delete Individual Lead Confirmation Dialog
  leadToDelete?.let { targetLead ->
    AlertDialog(
      onDismissRequest = { leadToDelete = null },
      title = { Text("Delete Customer Lead?", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
      text = {
        Text("Are you sure you want to permanently delete lead record for \"${targetLead.customerName}\" (${LoanCalculators.formatCurrency(targetLead.loanAmount, uiState.profile.currency)})? This action cannot be undone.")
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteLead(targetLead.id)
            leadToDelete = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          Text("Delete Lead")
        }
      },
      dismissButton = {
        TextButton(onClick = { leadToDelete = null }) {
          Text("Cancel")
        }
      }
    )
  }

  // Clear All Leads Confirmation Dialog
  if (showClearAllLeadsDialog) {
    AlertDialog(
      onDismissRequest = { showClearAllLeadsDialog = false },
      title = { Text("Delete ALL Customer Leads?", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFDC2626)) },
      text = {
        Text("Warning: This will permanently erase all ${uiState.leads.size} leads from the local pipeline database. Are you sure you want to proceed?")
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.clearAllLeads()
            showClearAllLeadsDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          Text("Yes, Delete All")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearAllLeadsDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  // Bulk Selected / Filtered Leads Delete Confirmation Dialog
  pendingBulkDeleteIds?.let { targetIds ->
    AlertDialog(
      onDismissRequest = { pendingBulkDeleteIds = null },
      title = { Text("Delete Filtered / Selected Leads?", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFDC2626)) },
      text = {
        Text("Are you sure you want to permanently delete $pendingBulkDeleteLabel from the database? This action cannot be undone.")
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteSelectedLeads(targetIds)
            selectedLeadIds.removeAll(targetIds)
            pendingBulkDeleteIds = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          Text("Yes, Delete (${targetIds.size})")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingBulkDeleteIds = null }) {
          Text("Cancel")
        }
      }
    )
  }

  // Edit or Add User Access & Branch Rights Dialog
  if (editingUserAccess != null || showAddUserAccessDialog) {
    UserAccessRightsDialog(
      initialUser = editingUserAccess,
      onDismiss = {
        editingUserAccess = null
        showAddUserAccessDialog = false
      },
      onSave = { updatedUser ->
        viewModel.saveUserAccessRights(updatedUser)
        editingUserAccess = null
        showAddUserAccessDialog = false
      }
    )
  }

  // Delete Registered User Confirmation Dialog
  userToDelete?.let { targetUser ->
    AlertDialog(
      onDismissRequest = { userToDelete = null },
      title = { Text("Remove User Access Record?", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
      text = {
        Text("Are you sure you want to permanently delete and hide user record for \"${targetUser.officerName.ifBlank { targetUser.staffId }}\" from User Management?")
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteUserRecord(targetUser)
            userToDelete = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          Text("Delete User")
        }
      },
      dismissButton = {
        TextButton(onClick = { userToDelete = null }) {
          Text("Cancel")
        }
      }
    )
  }

  // Bulk Product Import Dialog
  if (showBulkImportDialog) {
    BulkImportProductsDialog(
      onDismiss = { showBulkImportDialog = false },
      onImport = { csvContent ->
        val result = viewModel.importProductsCsv(csvContent)
        if (result.first > 0) {
          showBulkImportDialog = false
        }
      }
    )
  }

  // Google Apps Script Setup & Code Modal
  if (showScriptGuideDialog) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val scriptCode = """
function cleanDigits(val) {
  if (!val) return "";
  var s = String(val).replace(/\D/g, "");
  if (s.length > 10 && s.indexOf("92") === 0) s = s.substring(2);
  if (s.length > 10 && s.indexOf("0") === 0) s = s.substring(1);
  return s;
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.tryLock(30000);
  try {
    var rawData = e.postData.contents;
    var data = JSON.parse(rawData);
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName("Leads") || ss.getActiveSheet();

    if (sheet.getLastRow() === 0) {
      sheet.appendRow([
        "Lead ID", "Date Created", "Customer Name", "Mobile Number", "Category", 
        "Product Name", "Loan / Deposit Amount", "Tenure (M)", "Monthly EMI / Profit", 
        "Total Profit", "Processing Fee", "Status", "Follow-up Date", "Officer Name", 
        "Staff ID", "Branch Name", "Current Notes", "Approaches Count", "Location Address", "Audit Log"
      ]);
      sheet.getRange(1, 1, 1, 20).setFontWeight("bold").setBackground("#E2E8F0");
      sheet.setFrozenRows(1);
    }

    if (data.action === "ping") {
      return ContentService.createTextOutput(JSON.stringify({ 
        status: "success", 
        version: "2.2-dual-key-upsert", 
        message: "KB App Connected Successfully (Dual-Key In-Place Upsert Active)!" 
      })).setMimeType(ContentService.MimeType.JSON);
    }

    // Handle delete leads action from app by Lead ID or Mobile
    if (data.action === "delete_leads" || data.action === "delete_lead") {
      var deleteIds = data.leadIds || (data.leadId ? [data.leadId] : []);
      var deleteMobiles = data.mobiles || (data.mobile ? [data.mobile] : []);
      var cleanDeletePhones = [];
      for (var d = 0; d < deleteMobiles.length; d++) {
        var cp = cleanDigits(deleteMobiles[d]);
        if (cp) cleanDeletePhones.push(cp);
      }
      var lastRow = sheet.getLastRow();
      if (lastRow > 1 && (deleteIds.length > 0 || cleanDeletePhones.length > 0)) {
        var idAndPhoneValues = sheet.getRange(2, 1, lastRow - 1, 4).getValues();
        for (var r = idAndPhoneValues.length - 1; r >= 0; r--) {
          var rowId = String(idAndPhoneValues[r][0]).trim();
          var rowPhone = cleanDigits(idAndPhoneValues[r][3]);
          var matchId = rowId && deleteIds.indexOf(rowId) !== -1;
          var matchPhone = rowPhone && cleanDeletePhones.indexOf(rowPhone) !== -1;
          if (matchId || matchPhone) {
            sheet.deleteRow(r + 2);
          }
        }
      }
      return ContentService.createTextOutput(JSON.stringify({ status: "success", action: "deleted" }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // Handle Dual-Key In-Place Upsert (Update existing row or Append new row)
    // Matches existing customer by Lead ID (Col 1) OR Mobile Phone (Col 4)
    var leads = data.leads || [data];
    var lastRow = sheet.getLastRow();
    var idToRowMap = {};
    var phoneToRowMap = {};
    if (lastRow > 1) {
      var existingData = sheet.getRange(2, 1, lastRow - 1, 4).getValues();
      for (var r = 0; r < existingData.length; r++) {
        var existingId = String(existingData[r][0]).trim();
        var existingPhone = cleanDigits(existingData[r][3]);
        var rowIndex = r + 2;
        if (existingId) {
          idToRowMap[existingId] = rowIndex;
        }
        if (existingPhone) {
          phoneToRowMap[existingPhone] = rowIndex;
        }
      }
    }

    for (var i = 0; i < leads.length; i++) {
      var l = leads[i];
      var leadId = String(l.id || "").trim();
      var cleanPhone = cleanDigits(l.mobile);
      var rowData = [
        leadId,
        l.createdAtFormatted || new Date().toLocaleString(),
        l.customerName || "",
        l.mobile || "",
        l.category || "asset",
        l.productName || "",
        l.loanAmount || 0,
        l.tenureMonths || 0,
        l.monthlyEmi || 0,
        l.totalProfit || 0,
        l.processingFee || 0,
        l.status || "New",
        l.followUpDate || "",
        l.officerName || "",
        l.officerId || "",
        l.branchName || "",
        l.notes || "",
        l.approachCount || 1,
        l.locationAddress || "",
        l.activitiesAudit || ""
      ];

      var targetRow = null;
      if (leadId && idToRowMap[leadId]) {
        targetRow = idToRowMap[leadId];
      } else if (cleanPhone && phoneToRowMap[cleanPhone]) {
        targetRow = phoneToRowMap[cleanPhone];
      }

      if (targetRow) {
        // Rewrite existing row in-place (no duplicates!)
        sheet.getRange(targetRow, 1, 1, 20).setValues([rowData]);
      } else {
        // Append new customer lead row
        sheet.appendRow(rowData);
        var newRowIndex = sheet.getLastRow();
        if (leadId) idToRowMap[leadId] = newRowIndex;
        if (cleanPhone) phoneToRowMap[cleanPhone] = newRowIndex;
      }
    }

    // Enable text wrapping on Notes (Col 17) and Audit Log (Col 20)
    var updatedLastRow = sheet.getLastRow();
    if (updatedLastRow > 1) {
      sheet.getRange(2, 17, updatedLastRow - 1, 1).setWrap(true);
      sheet.getRange(2, 20, updatedLastRow - 1, 1).setWrap(true);
    }

    return ContentService.createTextOutput(JSON.stringify({ status: "success", count: leads.length }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ status: "error", message: err.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}

function doGet(e) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Leads") || ss.getActiveSheet();
  var lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    return ContentService.createTextOutput(JSON.stringify({ status: "success", leads: [] }))
      .setMimeType(ContentService.MimeType.JSON);
  }
  var data = sheet.getRange(2, 1, lastRow - 1, 20).getValues();
  var leads = [];
  for (var i = 0; i < data.length; i++) {
    var r = data[i];
    leads.push({
      id: String(r[0]),
      createdAtFormatted: String(r[1]),
      customerName: String(r[2]),
      mobile: String(r[3]),
      category: String(r[4]),
      productName: String(r[5]),
      loanAmount: Number(r[6]) || 0,
      tenureMonths: Number(r[7]) || 0,
      monthlyEmi: Number(r[8]) || 0,
      totalProfit: Number(r[9]) || 0,
      processingFee: Number(r[10]) || 0,
      status: String(r[11]),
      followUpDate: String(r[12]),
      officerName: String(r[13]),
      officerId: String(r[14]),
      branchName: String(r[15]),
      notes: String(r[16]),
      approachCount: Number(r[17]) || 1,
      locationAddress: String(r[18]),
      activitiesAudit: String(r[19])
    });
  }
  return ContentService.createTextOutput(JSON.stringify({ status: "success", leads: leads }))
    .setMimeType(ContentService.MimeType.JSON);
}
    """.trimIndent()

    AlertDialog(
      onDismissRequest = { showScriptGuideDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Google Apps Script Setup (v2.2 In-Place Upsert)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
      },
      text = {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            "1. Open your Google Sheet in browser.\n" +
            "2. Click Extensions > Apps Script.\n" +
            "3. Select all existing code, delete it, paste the updated snippet below, and click the floppy Save icon.\n" +
            "4. ⚠️ CRITICAL STEP FOR UPDATES:\n" +
            "   • Click Deploy > Manage deployments.\n" +
            "   • Click the Edit pencil ✏️ next to your active deployment.\n" +
            "   • In the 'Version' dropdown, select 'New version'.\n" +
            "   • Ensure 'Who has access' is set to 'Anyone'.\n" +
            "   • Click Deploy! (If you do not select 'New version', Google will continue serving the old code).\n" +
            "5. Copy the Web App URL and paste it into the field above, then click 'Test Connection'.",
            fontSize = 11.5.sp,
            color = Color(0xFF334155),
            lineHeight = 16.sp
          )
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0F172A)
          ) {
            Text(
              text = scriptCode,
              color = Color(0xFF38BDF8),
              fontSize = 9.5.sp,
              modifier = Modifier.padding(10.dp)
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(scriptCode))
            viewModel.showToast("📋 Script code copied to clipboard!")
            showScriptGuideDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Copy Code", fontSize = 12.sp)
        }
      },
      dismissButton = {
        TextButton(onClick = { showScriptGuideDialog = false }) {
          Text("Close", fontSize = 12.sp)
        }
      }
    )
  }
}

@Composable
fun AdminCollapsibleHeader(
  title: String,
  subtitle: String,
  isExpanded: Boolean,
  onToggle: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onToggle() }
      .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary
      )
      Text(
        text = subtitle,
        fontSize = 11.sp,
        color = Color(0xFF64748B)
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Surface(
      color = if (isExpanded) Color(0xFFDBEAFE) else Color(0xFFF1F5F9),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.clickable { onToggle() }
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
      ) {
        Text(
          text = if (isExpanded) "Collapse" else "Expand",
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          color = if (isExpanded) Color(0xFF1E3A8A) else Color(0xFF475569)
        )
        Icon(
          imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          tint = if (isExpanded) Color(0xFF1E3A8A) else Color(0xFF475569),
          modifier = Modifier.size(16.dp)
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserAccessRightsDialog(
  initialUser: RegisteredUser?,
  onDismiss: () -> Unit,
  onSave: (RegisteredUser) -> Unit
) {
  var officerName by remember { mutableStateOf(initialUser?.officerName ?: "") }
  var staffId by remember { mutableStateOf(initialUser?.staffId ?: "") }
  var email by remember { mutableStateOf(initialUser?.email ?: "") }
  var branchName by remember { mutableStateOf(initialUser?.branchName ?: "") }
  var allowedBranch by remember { mutableStateOf(initialUser?.allowedSupervisorBranch ?: "") }
  var canMonitorBranch by remember { mutableStateOf(initialUser?.canUseSupervisorFilter ?: true) }
  var isBlocked by remember { mutableStateOf(initialUser?.isBlocked ?: false) }
  var role by remember { mutableStateOf(initialUser?.role?.ifBlank { "Loan Officer" } ?: "Loan Officer") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (initialUser == null) "Add Officer & Branch Rights" else "Configure User & Branch Rights",
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = officerName,
          onValueChange = { officerName = it },
          label = { Text("Officer / RM Name *", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = staffId,
          onValueChange = { staffId = it },
          label = { Text("Staff / Employee ID *", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = email,
          onValueChange = { email = it },
          label = { Text("Google Email (Optional)", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = branchName,
          onValueChange = { branchName = it },
          label = { Text("Home Branch Name", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        // Role selector
        Text("Designation / Role:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF475569))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf("Loan Officer", "Relationship Manager").forEach { r ->
            FilterChip(
              selected = role == r,
              onClick = { role = r },
              label = { Text(r, fontSize = 10.5.sp) },
              modifier = Modifier.weight(1f)
            )
          }
        }

        // Allowed Branch Scope for Supervisor Filter
        OutlinedTextField(
          value = allowedBranch,
          onValueChange = { allowedBranch = it },
          label = { Text("Allowed Branch Scope (Single, Comma-Separated, or 'ALL')", fontSize = 10.5.sp) },
          placeholder = { Text("e.g. Blue Area, Saddar Branch, or ALL", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          OutlinedButton(
            onClick = { allowedBranch = branchName.ifBlank { "" } },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
          ) {
            Text("Set Own Branch", fontSize = 10.sp)
          }
          OutlinedButton(
            onClick = { allowedBranch = "ALL" },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
          ) {
            Text("Grant All Branches", fontSize = 10.sp)
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = canMonitorBranch, onCheckedChange = { canMonitorBranch = it })
          Spacer(modifier = Modifier.width(4.dp))
          Text("Allow Supervisor Branch Filter in Leads", fontSize = 11.5.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = isBlocked, onCheckedChange = { isBlocked = it })
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            "Block / Disable User Access to App",
            fontSize = 11.5.sp,
            color = Color(0xFFDC2626),
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val uid = initialUser?.uid?.ifBlank {
            "staff_${staffId.trim().ifBlank { System.currentTimeMillis().toString() }}"
          } ?: "staff_${staffId.trim().ifBlank { System.currentTimeMillis().toString() }}"
          onSave(
            RegisteredUser(
              uid = uid,
              email = email.trim(),
              officerName = officerName.trim(),
              staffId = staffId.trim(),
              branchName = branchName.trim(),
              allowedSupervisorBranch = allowedBranch.trim().ifBlank { branchName.trim() },
              canUseSupervisorFilter = canMonitorBranch,
              isBlocked = isBlocked,
              role = role,
              updatedAt = System.currentTimeMillis()
            )
          )
        },
        enabled = officerName.isNotBlank() || staffId.isNotBlank()
      ) {
        Text("Save Rights")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

/**
 * Bulk Import Products Dialog (CSV / Excel format) with Device Storage File Picker
 */
@Composable
fun BulkImportProductsDialog(
  onDismiss: () -> Unit,
  onImport: (String) -> Unit
) {
  val context = LocalContext.current
  var csvText by remember { mutableStateOf("") }
  var errorText by remember { mutableStateOf<String?>(null) }

  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    uri?.let {
      try {
        val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
        if (!text.isNullOrBlank()) {
          csvText = text
          errorText = null
        }
      } catch (e: Exception) {
        errorText = "Error reading CSV file: ${e.localizedMessage}"
      }
    }
  }

  val sampleTemplate = remember {
    """ID,Name,RateType,DefaultRate,MinAmount,MaxAmount,MinTenure,MaxTenure,MinAge,MaxAgeAtMaturity,FeeType,FeeValue
kb-personal-1,Personal Express Loan,reducing,21.5,50000,3000000,12,60,21,65,percentage,1.5
kb-auto-1,Auto Car Loan,reducing,18.5,200000,8000000,12,84,22,65,percentage,1.2
kb-home-1,Home Mortgage Loan,reducing,16.5,500000,25000000,12,120,25,65,percentage,1.0"""
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Bulk Import Products (CSV)", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Select a CSV file from device storage or paste CSV formatted with: ID, Name, RateType, DefaultRate, MinAmount, MaxAmount, MinTenure, MaxTenure, MinAge, MaxAgeAtMaturity, FeeType, FeeValue",
          fontSize = 11.sp,
          color = Color(0xFF64748B)
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Button(
            onClick = { filePickerLauncher.launch("*/*") },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.weight(1.1f)
          ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("📁 Pick CSV File", fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }

          OutlinedButton(
            onClick = { csvText = sampleTemplate },
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.weight(0.9f)
          ) {
            Text("Load Template", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
        }

        OutlinedTextField(
          value = csvText,
          onValueChange = {
            csvText = it
            errorText = null
          },
          label = { Text("CSV Content") },
          placeholder = { Text("Paste CSV or pick from storage above...", color = Color(0xFF94A3B8)) },
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
          minLines = 6
        )

        errorText?.let {
          Text(it, color = Color(0xFFDC2626), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (csvText.isBlank()) {
            errorText = "Please pick a CSV file or paste content"
          } else {
            onImport(csvText)
          }
        }
      ) {
        Text("Import Products")
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
fun AdminPinDialog(
  onDismiss: () -> Unit,
  onVerify: (String) -> Unit
) {
  var pin by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Admin Security Access", fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Enter Admin Password to manage SOC slabs and database settings.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
          value = pin,
          onValueChange = { pin = it },
          placeholder = { Text("Enter password...", color = Color(0xFF94A3B8)) },
          visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { passwordVisible = !passwordVisible },
              modifier = Modifier.size(36.dp)
            ) {
              Icon(
                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
              )
            }
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    },
    confirmButton = {
      Button(onClick = { onVerify(pin) }) {
        Text("Unlock")
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
fun ChangeAdminPasswordDialog(
  onDismiss: () -> Unit,
  onChange: (String, String) -> Unit
) {
  var oldPassword by remember { mutableStateOf("") }
  var newPassword by remember { mutableStateOf("") }
  var oldPasswordVisible by remember { mutableStateOf(false) }
  var newPasswordVisible by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Update Admin Password", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = oldPassword,
          onValueChange = { oldPassword = it },
          label = { Text("Current Password") },
          placeholder = { Text("Enter current password", color = Color(0xFF94A3B8)) },
          visualTransformation = if (oldPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { oldPasswordVisible = !oldPasswordVisible },
              modifier = Modifier.size(36.dp)
            ) {
              Icon(
                imageVector = if (oldPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (oldPasswordVisible) "Hide password" else "Show password",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
              )
            }
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
        OutlinedTextField(
          value = newPassword,
          onValueChange = { newPassword = it },
          label = { Text("New Password") },
          placeholder = { Text("Enter new password", color = Color(0xFF94A3B8)) },
          visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { newPasswordVisible = !newPasswordVisible },
              modifier = Modifier.size(36.dp)
            ) {
              Icon(
                imageVector = if (newPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (newPasswordVisible) "Hide password" else "Show password",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
              )
            }
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onChange(oldPassword, newPassword) },
        enabled = oldPassword.isNotBlank() && newPassword.length >= 4
      ) {
        Text("Save New Password")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

data class TenureRateInput(
  val id: String = java.util.UUID.randomUUID().toString(),
  val tenureMonths: String,
  val annualRate: String
)

@Composable
fun ProductEditDialog(
  product: LoanProduct?,
  onDismiss: () -> Unit,
  onSave: (LoanProduct) -> Unit
) {
  var category by remember { mutableStateOf(product?.category ?: "asset") }
  var name by remember { mutableStateOf(product?.name ?: "") }
  var rateType by remember { mutableStateOf(product?.rateType ?: (if (category == "liability") "flat" else "reducing")) }
  var liabilitySchemeType by remember(product, category) {
    mutableStateOf(
      if (product?.defaultAnnualRate == 0.0) "current"
      else if (product?.fixedTenures?.size == 1 && product?.fixedTenures?.contains(12) == true) "saving"
      else "term"
    )
  }
  var liabilityRateBasis by remember(product, category) {
    mutableStateOf(
      if (product != null && !product.liabilityPricingType.isNullOrBlank()) {
        product.liabilityPricingType
      } else if (product != null && product.category == "liability" && product.rateSlabs.size > 1 && product.tenureRates.isEmpty()) {
        "amount"
      } else {
        "tenure"
      }
    )
  }
  var minAmount by remember { mutableStateOf(product?.minAmount?.toString() ?: "50000") }
  var maxAmount by remember { mutableStateOf(product?.maxAmount?.toString() ?: "5000000") }
  var minTenure by remember { mutableStateOf(product?.minTenure?.toString() ?: "1") }
  var maxTenure by remember { mutableStateOf(product?.maxTenure?.toString() ?: "120") }
  var minAge by remember { mutableStateOf(product?.minAge?.toString() ?: "18") }
  var maxAge by remember { mutableStateOf(product?.maxAgeAtMaturity?.toString() ?: "65") }
  var collateralDetails by remember { mutableStateOf(product?.collateralDetails ?: "") }

  val rateSlabs = remember {
    mutableStateListOf<RateSlab>().apply {
      if (product != null && product.rateSlabs.isNotEmpty()) {
        addAll(product.rateSlabs)
      } else if (product != null) {
        add(RateSlab(product.minAmount, product.maxAmount, product.defaultAnnualRate))
      } else if (category == "liability") {
        addAll(
          listOf(
            RateSlab(0L, 499999L, 10.0),
            RateSlab(500000L, 9999999L, 10.25),
            RateSlab(10000000L, 24999999L, 10.50),
            RateSlab(25000000L, 500000000L, 10.50)
          )
        )
      } else {
        add(RateSlab(50000L, 5000000L, 20.0))
      }
    }
  }

  val tenureRateInputs = remember(product) {
    mutableStateListOf<TenureRateInput>().apply {
      if (product != null && product.tenureRates.isNotEmpty()) {
        product.tenureRates.toSortedMap().forEach { (tenure, rate) ->
          add(TenureRateInput(tenureMonths = tenure.toString(), annualRate = rate.toString()))
        }
      } else if (product != null && product.category == "liability") {
        if (product.defaultAnnualRate == 0.0) {
          add(TenureRateInput(tenureMonths = "12", annualRate = "0.0"))
        } else if (product.fixedTenures.size == 1) {
          add(TenureRateInput(tenureMonths = product.fixedTenures.first().toString(), annualRate = product.defaultAnnualRate.toString()))
        } else {
          listOf(6 to 11.75, 12 to 12.25, 24 to 12.50, 36 to 12.75, 60 to 13.25).forEach { (t, r) ->
            add(TenureRateInput(tenureMonths = t.toString(), annualRate = r.toString()))
          }
        }
      } else {
        listOf(6 to 11.75, 12 to 12.25, 24 to 12.50, 36 to 12.75, 60 to 13.25).forEach { (t, r) ->
          add(TenureRateInput(tenureMonths = t.toString(), annualRate = r.toString()))
        }
      }
    }
  }

  val feeSlabs = remember {
    mutableStateListOf<FeeSlab>().apply {
      if (product != null) {
        addAll(product.feeSlabs)
      } else {
        add(FeeSlab(50000L, 5000000L, "percentage", 1.5, 1500L, 25000L))
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        if (product == null || product.id.isBlank()) {
          if (category == "liability") "Add Liability / Deposit Product" else "Add Asset / Loan Facility"
        } else {
          if (category == "liability") "Edit Liability / Deposit Product" else "Edit Facility & SOC Slabs"
        },
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
      )
    },
    text = {
      Box(modifier = Modifier.sizeIn(maxHeight = 450.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // Category Selector (Asset Loan vs. Liability Deposit)
          item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = category == "asset",
                onClick = { category = "asset" },
                leadingIcon = { Text("🏦") },
                label = { Text("Asset / Loan Facility", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                modifier = Modifier.weight(1f)
              )
              FilterChip(
                selected = category == "liability",
                onClick = {
                  category = "liability"
                  rateType = "flat"
                },
                leadingIcon = { Text("💰") },
                label = { Text("Liability / Deposit", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                modifier = Modifier.weight(1f)
              )
            }
          }

          item {
            OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              label = { Text(if (category == "liability") "Deposit Product Name / اکاؤنٹ کا نام" else "Facility Name") },
              placeholder = { Text(if (category == "liability") "e.g. Khushhali Izafa Certificate" else "e.g. Commercial Loan", color = Color(0xFF94A3B8)) },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true
            )
          }

          if (category == "liability") {
            // Scheme Type Classification for Liability: Term Deposit vs Saving vs Current Account
            item {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = "Scheme Classification (اکاؤنٹ کی نوعیت):",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF0F766E)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  FilterChip(
                    selected = liabilitySchemeType == "term",
                    onClick = {
                      liabilitySchemeType = "term"
                      rateType = "flat"
                    },
                    label = { Text("Term (6-60M)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f)
                  )
                  FilterChip(
                    selected = liabilitySchemeType == "saving",
                    onClick = {
                      liabilitySchemeType = "saving"
                      rateType = "flat"
                    },
                    label = { Text("Saving (12M)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f)
                  )
                  FilterChip(
                    selected = liabilitySchemeType == "current",
                    onClick = {
                      liabilitySchemeType = "current"
                      rateType = "flat"
                    },
                    label = { Text("Current (0%)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f)
                  )
                }
              }
            }
          } else {
            // Asset Loan Rate Type
            item {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                  selected = rateType == "reducing",
                  onClick = { rateType = "reducing" },
                  label = { Text("Reducing Balance", fontSize = 11.sp) }
                )
                FilterChip(
                  selected = rateType == "flat",
                  onClick = { rateType = "flat" },
                  label = { Text("Flat Rate", fontSize = 11.sp) }
                )
              }
            }

            // Min & Max Amount (Asset Only)
            item {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = minAmount,
                  onValueChange = { minAmount = it },
                  label = { Text("Min Amount") },
                  placeholder = { Text("50000", color = Color(0xFF94A3B8)) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  singleLine = true
                )
                OutlinedTextField(
                  value = maxAmount,
                  onValueChange = { maxAmount = it },
                  label = { Text("Max Amount") },
                  placeholder = { Text("5000000", color = Color(0xFF94A3B8)) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  singleLine = true
                )
              }
            }

            // Min & Max Tenure (Asset Only)
            item {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = minTenure,
                  onValueChange = { minTenure = it },
                  label = { Text("Min Mo") },
                  placeholder = { Text("1", color = Color(0xFF94A3B8)) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  singleLine = true
                )
                OutlinedTextField(
                  value = maxTenure,
                  onValueChange = { maxTenure = it },
                  label = { Text("Max Mo (120)") },
                  placeholder = { Text("120", color = Color(0xFF94A3B8)) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  singleLine = true
                )
              }
            }

            // Min & Max Age (Asset Only)
            item {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = minAge,
                  onValueChange = { minAge = it },
                  label = { Text("Min Age") },
                  placeholder = { Text("21", color = Color(0xFF94A3B8)) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  singleLine = true
                )
                OutlinedTextField(
                  value = maxAge,
                  onValueChange = { maxAge = it },
                  label = { Text("Max Age at Maturity") },
                  placeholder = { Text("65", color = Color(0xFF94A3B8)) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f),
                  singleLine = true
                )
              }
            }
          }

          // Terms & Collateral / Description
          item {
            OutlinedTextField(
              value = collateralDetails,
              onValueChange = { collateralDetails = it },
              label = { Text(if (category == "liability") "Product Rules & Terms / شرائط و ضوابط" else "Security & Collateral Requirements") },
              placeholder = { Text(if (category == "liability") "Enter deposit rules, payout frequency, notes..." else "Enter required securities, collateral, guarantors...", color = Color(0xFF94A3B8)) },
              modifier = Modifier.fillMaxWidth(),
              minLines = 3,
              maxLines = 6
            )
          }

          // Section A: Rate Slabs (Tiered Slabs for Profit or Loan Interest)
          item {
            if (category == "liability") {
              if (liabilitySchemeType == "current") {
                // Current Account (0% non-remunerative)
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = Color(0xFFF0FDFA),
                  border = BorderStroke(1.dp, Color(0xFF99F6E4)),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🛡️ Current Account Rate: 0.0% p.a. (Non-Remunerative)", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF0F766E))
                    Text("لاگت و منافع سے پاک۔ کرنٹ ڈپازٹس پر کوئی منافع نہیں دیا جاتا اور نہ کوئی ودہولڈنگ ٹیکس کٹوتی ہوتی ہے۔", fontSize = 10.sp, color = Color(0xFF0D9488))
                  }
                }
              } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  // Toggle between Duration / Tenure Slabs vs Amount / Balance Slabs
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    FilterChip(
                      selected = liabilityRateBasis == "tenure",
                      onClick = { liabilityRateBasis = "tenure" },
                      label = { Text("⏳ By Duration (مدت کے مطابق)", fontSize = 10.5.sp, fontWeight = if (liabilityRateBasis == "tenure") FontWeight.Bold else FontWeight.Normal) },
                      modifier = Modifier.weight(1f),
                      colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFCCFBF1),
                        selectedLabelColor = Color(0xFF0F766E)
                      )
                    )
                    FilterChip(
                      selected = liabilityRateBasis == "amount",
                      onClick = {
                        liabilityRateBasis = "amount"
                        if (rateSlabs.isEmpty() || (rateSlabs.size == 1 && rateSlabs.first().maxAmount == 500000000L)) {
                          rateSlabs.clear()
                          rateSlabs.addAll(
                            listOf(
                              RateSlab(0L, 499999L, 10.0),
                              RateSlab(500000L, 9999999L, 10.25),
                              RateSlab(10000000L, 24999999L, 10.50),
                              RateSlab(25000000L, 500000000L, 10.50)
                            )
                          )
                        }
                      },
                      label = { Text("💵 By Amount Slabs (بیلنس سلیب)", fontSize = 10.5.sp, fontWeight = if (liabilityRateBasis == "amount") FontWeight.Bold else FontWeight.Normal) },
                      modifier = Modifier.weight(1f),
                      colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFCCFBF1),
                        selectedLabelColor = Color(0xFF0F766E)
                      )
                    )
                  }

                  if (liabilityRateBasis == "amount") {
                    // Amount / Balance Based Profit Rate Slabs for Liability (e.g. Behtreen Bachat Account)
                    Card(
                      modifier = Modifier.fillMaxWidth(),
                      colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)),
                      shape = RoundedCornerShape(10.dp),
                      border = BorderStroke(1.dp, Color(0xFF99F6E4))
                    ) {
                      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Column(modifier = Modifier.weight(1f)) {
                            Text(
                              text = "💵 Balance Profit Slabs / ڈپازٹ بیلنس سلیبس",
                              fontWeight = FontWeight.Bold,
                              fontSize = 11.5.sp,
                              color = Color(0xFF0F766E)
                            )
                            Text(
                              text = "ڈپازٹ کی رقم (کم از کم سے زیادہ سے زیادہ) اور اس پر لاگو منافع ریٹ درج کریں",
                              fontSize = 9.5.sp,
                              color = Color(0xFF0D9488)
                            )
                          }
                          FilledTonalButton(
                            onClick = {
                              val lastMax = rateSlabs.lastOrNull()?.maxAmount ?: 0L
                              val lastRate = rateSlabs.lastOrNull()?.annualRate ?: 10.50
                              rateSlabs.add(RateSlab(lastMax + 1, lastMax + 10000000L, lastRate))
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                              containerColor = Color(0xFFCCFBF1),
                              contentColor = Color(0xFF0F766E)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                          ) {
                            Text("+ Add Amount Slab", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                          }
                        }

                        if (rateSlabs.isEmpty()) {
                          Text(
                            text = "کوئی بیلنس سلیب موجود نہیں ہے۔ براہ کرم اوپر دیے گئے بٹن سے سلیب شامل کریں۔",
                            fontSize = 10.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(vertical = 4.dp)
                          )
                        } else {
                          rateSlabs.forEachIndexed { idx, slab ->
                            Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                              verticalAlignment = Alignment.CenterVertically
                            ) {
                              OutlinedTextField(
                                value = slab.minAmount.toString(),
                                onValueChange = { v ->
                                  rateSlabs[idx] = slab.copy(minAmount = v.filter { it.isDigit() }.toLongOrNull() ?: slab.minAmount)
                                },
                                label = { Text("Min (Rs)", fontSize = 8.5.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1.1f),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                              )
                              OutlinedTextField(
                                value = slab.maxAmount.toString(),
                                onValueChange = { v ->
                                  rateSlabs[idx] = slab.copy(maxAmount = v.filter { it.isDigit() }.toLongOrNull() ?: slab.maxAmount)
                                },
                                label = { Text("Max (Rs)", fontSize = 8.5.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1.1f),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                              )
                              OutlinedTextField(
                                value = slab.annualRate.toString(),
                                onValueChange = { v ->
                                  rateSlabs[idx] = slab.copy(annualRate = v.toDoubleOrNull() ?: slab.annualRate)
                                },
                                label = { Text("Rate (% p.a.)", fontSize = 8.5.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1.0f),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                              )
                              IconButton(
                                onClick = { rateSlabs.removeAt(idx) },
                                modifier = Modifier.size(28.dp)
                              ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                              }
                            }
                          }
                        }
                      }
                    }
                  } else {
                    // Tenure / Duration Based Profit Rate Slabs for Liability (Term / Saving Deposits)
                    Card(
                      modifier = Modifier.fillMaxWidth(),
                      colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)),
                      shape = RoundedCornerShape(10.dp),
                      border = BorderStroke(1.dp, Color(0xFF99F6E4))
                    ) {
                      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Column(modifier = Modifier.weight(1f)) {
                            Text(
                              text = "⏳ Duration Profit Slabs / ڈیوریشن کے پرافٹ ریٹس",
                              fontWeight = FontWeight.Bold,
                              fontSize = 11.5.sp,
                              color = Color(0xFF0F766E)
                            )
                            Text(
                              text = "ڈیوریشن (ماہ) اور اس کا سالانہ پرافٹ ریٹ درج کریں (مثلاً 6M: 11.75%, 12M: 12.25%)",
                              fontSize = 9.5.sp,
                              color = Color(0xFF0D9488)
                            )
                          }
                          FilledTonalButton(
                            onClick = {
                              val lastTenure = tenureRateInputs.mapNotNull { it.tenureMonths.filter { c -> c.isDigit() }.toIntOrNull() }.maxOrNull() ?: 12
                              val nextTenure = if (lastTenure in 6..23) lastTenure + 6 else lastTenure + 12
                              val lastRate = tenureRateInputs.lastOrNull()?.annualRate ?: "12.50"
                              tenureRateInputs.add(TenureRateInput(tenureMonths = nextTenure.toString(), annualRate = lastRate))
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                              containerColor = Color(0xFFCCFBF1),
                              contentColor = Color(0xFF0F766E)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                          ) {
                            Text("+ Add Duration Slab", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                          }
                        }

                        if (tenureRateInputs.isEmpty()) {
                          Text(
                            text = "کوئی ڈیوریشن سلیب موجود نہیں ہے۔ براہ کرم اوپر دیے گئے بٹن سے سلیب شامل کریں۔",
                            fontSize = 10.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(vertical = 4.dp)
                          )
                        } else {
                          tenureRateInputs.forEachIndexed { idx, item ->
                            Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                              verticalAlignment = Alignment.CenterVertically
                            ) {
                              OutlinedTextField(
                                value = item.tenureMonths,
                                onValueChange = { v ->
                                  tenureRateInputs[idx] = item.copy(tenureMonths = v)
                                },
                                label = { Text("Duration (Months)", fontSize = 8.5.sp) },
                                placeholder = { Text("6", fontSize = 9.sp, color = Color(0xFF94A3B8)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1.2f),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                              )

                              OutlinedTextField(
                                value = item.annualRate,
                                onValueChange = { v ->
                                  tenureRateInputs[idx] = item.copy(annualRate = v)
                                },
                                label = { Text("Profit Rate (% p.a.)", fontSize = 8.5.sp) },
                                placeholder = { Text("11.75", fontSize = 9.sp, color = Color(0xFF94A3B8)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1.2f),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                              )

                              IconButton(
                                onClick = { tenureRateInputs.removeAt(idx) },
                                modifier = Modifier.size(28.dp)
                              ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            } else {
              Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
              ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = "Multi-Tier Interest Rate Slabs",
                      fontWeight = FontWeight.Bold,
                      fontSize = 11.sp,
                      color = Color(0xFF1E3A8A)
                    )
                    TextButton(
                      onClick = {
                        val lastMax = rateSlabs.lastOrNull()?.maxAmount ?: 50000L
                        val lastRate = rateSlabs.lastOrNull()?.annualRate ?: 20.0
                        rateSlabs.add(RateSlab(lastMax + 1, lastMax + 200000L, lastRate))
                      }
                    ) {
                      Text("+ Add Rate Slab", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                  }

                  rateSlabs.forEachIndexed { idx, slab ->
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(4.dp),
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      OutlinedTextField(
                        value = slab.minAmount.toString(),
                        onValueChange = { v ->
                          rateSlabs[idx] = slab.copy(minAmount = v.toLongOrNull() ?: slab.minAmount)
                        },
                        label = { Text("Min (Rs)", fontSize = 8.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                      )
                      OutlinedTextField(
                        value = slab.maxAmount.toString(),
                        onValueChange = { v ->
                          rateSlabs[idx] = slab.copy(maxAmount = v.toLongOrNull() ?: slab.maxAmount)
                        },
                        label = { Text("Max (Rs)", fontSize = 8.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                      )
                      OutlinedTextField(
                        value = slab.annualRate.toString(),
                        onValueChange = { v ->
                          rateSlabs[idx] = slab.copy(annualRate = v.toDoubleOrNull() ?: slab.annualRate)
                        },
                        label = { Text("Rate %", fontSize = 8.sp) },
                        modifier = Modifier.weight(0.9f),
                        singleLine = true
                      )
                      IconButton(
                        onClick = { rateSlabs.removeAt(idx) },
                        modifier = Modifier.size(28.dp)
                      ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                      }
                    }
                  }
                }
              }
            }
          }

          // Section B: Tiered Fee Slabs (Only for Asset Loans, hidden for Liability deposits)
          if (category != "liability") {
            item {
              Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4))
              ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text("Tiered Processing Fee Slabs", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF166534))
                    TextButton(
                      onClick = {
                        val lastMax = feeSlabs.lastOrNull()?.maxAmount ?: 50000L
                        feeSlabs.add(FeeSlab(lastMax + 1, lastMax + 500000L, "percentage", 1.5, 2000L, 10000L))
                      }
                    ) {
                      Text("+ Add Fee Slab", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                  }

                  feeSlabs.forEachIndexed { idx, slab ->
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(6.dp),
                      verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        OutlinedTextField(
                          value = slab.minAmount.toString(),
                          onValueChange = { v ->
                            feeSlabs[idx] = slab.copy(minAmount = v.toLongOrNull() ?: slab.minAmount)
                          },
                          label = { Text("Min Amount", fontSize = 8.sp) },
                          modifier = Modifier.weight(1f),
                          singleLine = true
                        )
                        OutlinedTextField(
                          value = slab.maxAmount.toString(),
                          onValueChange = { v ->
                            feeSlabs[idx] = slab.copy(maxAmount = v.toLongOrNull() ?: slab.maxAmount)
                          },
                          label = { Text("Max Amount", fontSize = 8.sp) },
                          modifier = Modifier.weight(1f),
                          singleLine = true
                        )
                        IconButton(
                          onClick = { feeSlabs.removeAt(idx) },
                          modifier = Modifier.size(28.dp)
                        ) {
                          Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                        }
                      }

                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                      ) {
                        OutlinedTextField(
                          value = slab.feeValue.toString(),
                          onValueChange = { v ->
                            feeSlabs[idx] = slab.copy(feeValue = v.toDoubleOrNull() ?: slab.feeValue)
                          },
                          label = { Text("Fee %", fontSize = 8.sp) },
                          modifier = Modifier.weight(1f),
                          singleLine = true
                        )
                        OutlinedTextField(
                          value = slab.minFee.toString(),
                          onValueChange = { v ->
                            feeSlabs[idx] = slab.copy(minFee = v.toLongOrNull() ?: slab.minFee)
                          },
                          label = { Text("Min Floor (Rs)", fontSize = 8.sp) },
                          modifier = Modifier.weight(1f),
                          singleLine = true
                        )
                        OutlinedTextField(
                          value = slab.maxFee.toString(),
                          onValueChange = { v ->
                            feeSlabs[idx] = slab.copy(maxFee = v.toLongOrNull() ?: slab.maxFee)
                          },
                          label = { Text("Max Cap (Rs)", fontSize = 8.sp) },
                          modifier = Modifier.weight(1f),
                          singleLine = true
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
    },
    confirmButton = {
      Button(
        onClick = {
          val isLiab = category == "liability"
          val isCurrent = isLiab && liabilitySchemeType == "current"
          val isSaving = isLiab && liabilitySchemeType == "saving"
          val isAmountBased = isLiab && !isCurrent && liabilityRateBasis == "amount"

          val parsedTenureRates = mutableMapOf<Int, Double>()
          tenureRateInputs.forEach { input ->
            val t = input.tenureMonths.filter { it.isDigit() }.toIntOrNull()
            val r = input.annualRate.trim().toDoubleOrNull()
            if (t != null && t > 0 && r != null) {
              parsedTenureRates[t] = r
            }
          }

          val finalTenureRates: Map<Int, Double> = when {
            isCurrent -> mapOf(12 to 0.0)
            isAmountBased -> emptyMap()
            parsedTenureRates.isNotEmpty() -> parsedTenureRates
            isSaving -> mapOf(12 to 11.0)
            else -> mapOf(6 to 11.75, 12 to 12.25, 24 to 12.50, 36 to 12.75, 60 to 12.75)
          }

          val finalFixedTenures: List<Int> = when {
            isCurrent -> listOf(12)
            isAmountBased -> listOf(12)
            parsedTenureRates.isNotEmpty() -> parsedTenureRates.keys.sorted()
            isSaving -> listOf(12)
            else -> listOf(6, 12, 24, 36, 60)
          }

          val defRate = when {
            isCurrent -> 0.0
            isAmountBased -> rateSlabs.firstOrNull()?.annualRate ?: 10.0
            else -> finalTenureRates.values.firstOrNull() ?: 11.0
          }
          val minT = if (isLiab) (finalFixedTenures.minOrNull() ?: 6) else (minTenure.toIntOrNull() ?: 1)
          val maxT = if (isLiab) (finalFixedTenures.maxOrNull() ?: 60) else (maxTenure.toIntOrNull() ?: 120)

          val finalPricingType = when {
            isCurrent -> "current"
            isAmountBased -> "amount"
            isLiab -> "tenure"
            else -> ""
          }

          val p = LoanProduct(
            id = if (product?.id.isNullOrBlank()) {
              if (isLiab) "prod-liab-${System.currentTimeMillis()}" else "prod-kb-${System.currentTimeMillis()}"
            } else product!!.id,
            name = name,
            rateType = if (isLiab) "flat" else rateType,
            defaultAnnualRate = if (isCurrent) 0.0 else defRate,
            minAmount = if (isLiab) (if (isAmountBased && rateSlabs.isNotEmpty()) rateSlabs.first().minAmount else 1000L) else (minAmount.toLongOrNull() ?: 50000L),
            maxAmount = if (isLiab) (if (isAmountBased && rateSlabs.isNotEmpty()) rateSlabs.last().maxAmount else 500000000L) else (maxAmount.toLongOrNull() ?: 5000000L),
            minTenure = minT,
            maxTenure = maxT,
            minAge = if (isLiab) 18 else (minAge.toIntOrNull() ?: 21),
            maxAgeAtMaturity = if (isLiab) 90 else (maxAge.toIntOrNull() ?: 65),
            feeType = if (isLiab) "fixed" else "percentage",
            defaultFeeValue = 0.0,
            fedRate = if (isLiab) 0.0 else 16.0,
            collateralDetails = collateralDetails.trim(),
            rateSlabs = if (isCurrent) {
              listOf(RateSlab(0L, 500000000L, 0.0))
            } else if (isAmountBased) {
              rateSlabs.toList()
            } else if (isLiab) {
              listOf(RateSlab(0L, 500000000L, defRate))
            } else {
              rateSlabs.toList()
            },
            feeSlabs = if (isLiab) emptyList() else feeSlabs.toList(),
            category = category,
            fixedTenures = if (isLiab) finalFixedTenures else emptyList(),
            tenureRates = if (isLiab) finalTenureRates else emptyMap(),
            liabilityPricingType = finalPricingType
          )
          onSave(p)
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = if (category == "liability") Color(0xFF0D9488) else MaterialTheme.colorScheme.primary
        )
      ) {
        Text(if (category == "liability") "Save Liability Product" else "Save Facility & Slabs")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
