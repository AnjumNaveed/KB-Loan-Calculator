package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.GoogleSheetsRepository
import com.example.model.CustomerLead
import com.example.model.LeadActivity
import com.example.model.LoanProduct
import kotlin.math.abs
import com.example.ui.LoanUiState
import com.example.ui.LoanViewModel
import com.example.ui.theme.*
import com.example.util.LoanCalculators
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Strips any legacy system-generated timestamps, brackets, bullets, or headers
 * to ensure only pure user-typed note text is displayed in the app UI.
 */
fun sanitizeRawNoteText(raw: String): String {
  if (raw.isBlank()) return ""
  val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
  val target = lines.lastOrNull() ?: raw
  return GoogleSheetsRepository.cleanRawActionPayload(target)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadsScreen(
  viewModel: LoanViewModel,
  uiState: LoanUiState,
  onCallCustomer: (String) -> Unit,
  onWhatsAppCustomer: (String, String) -> Unit,
  onSmsCustomer: (String, String) -> Unit,
  onExportCsv: (String, String) -> Unit
) {
  val context = LocalContext.current
  val isDark = when (uiState.themeMode) {
    "dark" -> true
    "light" -> false
    else -> androidx.compose.foundation.isSystemInDarkTheme()
  }
  var searchQuery by remember { mutableStateOf("") }
  var supervisorBranchQuery by remember { mutableStateOf("") }
  var statusFilter by remember { mutableStateOf("All") }
  var filterStatusMenuExpanded by remember { mutableStateOf(false) }

  // Set of lead IDs that are currently expanded. Starts empty so all existing and newly added leads are folded by default!
  var expandedLeadIds by rememberSaveable { mutableStateOf(setOf<String>()) }

  // Dialog for editing lead note
  var editingNoteLead by remember { mutableStateOf<CustomerLead?>(null) }
  var currentEditingNote by remember { mutableStateOf("") }

  // Dialog for editing loan proposal / plan
  var editingPlanLead by remember { mutableStateOf<CustomerLead?>(null) }

  // Dialog for viewing approach track record and audit history
  var viewingHistoryLead by remember { mutableStateOf<CustomerLead?>(null) }

  val todayIso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

  // Automatically sync pending offline leads and pull latest cloud leads when Leads screen opens or branch filter changes
  LaunchedEffect(supervisorBranchQuery) {
    viewModel.syncPendingLeadsToCloud(showToastOnComplete = false)
  }

  val cleanBranchQuery = remember(supervisorBranchQuery) {
    normalizeBranchText(supervisorBranchQuery)
  }
  val canUseBranchFilter = uiState.isAdminUnlocked || uiState.canMonitorBranch
  val allowedBranchesList = remember(uiState.effectiveAllowedBranch) {
    uiState.effectiveAllowedBranch
      .split(",", ";", "|")
      .map { normalizeBranchText(it) }
      .filter { it.isNotEmpty() }
  }
  val hasAllBranchesRight = remember(allowedBranchesList) {
    allowedBranchesList.any { it == "all" || it == "*" || it == "all branches" }
  }
  val isBranchFilterActive = canUseBranchFilter && cleanBranchQuery.isNotEmpty()

  // Check if the user typed a branch query that is outside their authorized branch rights
  val isUnauthorizedBranchQuery = remember(isBranchFilterActive, hasAllBranchesRight, allowedBranchesList, cleanBranchQuery) {
    if (!isBranchFilterActive || hasAllBranchesRight) {
      false
    } else if (allowedBranchesList.isEmpty()) {
      true
    } else {
      allowedBranchesList.none { allowed ->
        matchesSupervisorBranch(allowed, cleanBranchQuery) || matchesSupervisorBranch(cleanBranchQuery, allowed)
      }
    }
  }

  // Scope leads:
  // - When Supervisor Branch Filter is active (and allowed by Admin): aggregate users' leads matching the branch filter ONLY if within the user's authorized branch rights
  // - When Supervisor Branch Filter is empty/removed (or disabled by Admin): show ONLY the specific current user's own leads
  val scopedLeads = remember(
    uiState.leads,
    uiState.registeredUsers,
    cleanBranchQuery,
    canUseBranchFilter,
    allowedBranchesList,
    hasAllBranchesRight,
    isUnauthorizedBranchQuery,
    uiState.profile.staffId,
    uiState.profile.officerName,
    uiState.authUserId,
    uiState.currentUserAccess
  ) {
    if (isBranchFilterActive) {
      if (isUnauthorizedBranchQuery) {
        emptyList()
      } else {
        uiState.leads.filter { lead ->
          val effectiveLeadBranch = resolveEffectiveLeadBranch(lead, uiState)
          val matchesQuery = matchesSupervisorBranch(lead.branchName, cleanBranchQuery) ||
            matchesSupervisorBranch(effectiveLeadBranch, cleanBranchQuery)
          val matchesAllowedRight = hasAllBranchesRight ||
            allowedBranchesList.any { allowed ->
              (matchesSupervisorBranch(lead.branchName, allowed) || matchesSupervisorBranch(effectiveLeadBranch, allowed)) &&
                (matchesSupervisorBranch(allowed, cleanBranchQuery) || matchesSupervisorBranch(cleanBranchQuery, allowed))
            }
          matchesQuery && matchesAllowedRight
        }
      }
    } else {
      uiState.leads.filter { lead ->
        isCurrentUserLead(lead, uiState)
      }
    }
  }

  // Overdue / Due Today Follow-up detection (within current scope)
  val dueFollowUpLeads = remember(scopedLeads, todayIso) {
    scopedLeads.filter { lead ->
      val st = lead.status.trim()
      lead.followUpDate.isNotBlank() &&
        lead.followUpDate <= todayIso &&
        !st.equals("Lost", ignoreCase = true) &&
        !st.equals("Converted", ignoreCase = true)
    }
  }

  // Dynamic Pipeline Metric Math (auto-updates for Branch Filter across all branch users, or specific user when filter is removed)
  val totalLeadsCount = scopedLeads.size
  val activeLeads = remember(scopedLeads) {
    scopedLeads.filter {
      val st = it.status.trim()
      !st.equals("Lost", ignoreCase = true) && !st.equals("Converted", ignoreCase = true)
    }
  }
  val activePipelineValue = remember(activeLeads) { activeLeads.sumOf { it.loanAmount } }

  val convertedLeads = remember(scopedLeads) {
    scopedLeads.filter { it.status.trim().equals("Converted", ignoreCase = true) }
  }
  val convertedValue = remember(convertedLeads) { convertedLeads.sumOf { it.loanAmount } }

  val lostLeads = remember(scopedLeads) {
    scopedLeads.filter { it.status.trim().equals("Lost", ignoreCase = true) }
  }
  val lostValue = remember(lostLeads) { lostLeads.sumOf { it.loanAmount } }

  // Filtering within scopedLeads
  val filteredLeads = remember(scopedLeads, searchQuery, statusFilter) {
    val cleanSearch = searchQuery.trim()
    scopedLeads.filter { lead ->
      val matchesStatus = when (statusFilter) {
        "All" -> true
        "Follow-Up" -> lead.followUpDate.isNotBlank() && !lead.status.equals("Lost", ignoreCase = true) && !lead.status.equals("Converted", ignoreCase = true)
        else -> lead.status.equals(statusFilter, ignoreCase = true)
      }
      val matchesSearch = cleanSearch.isEmpty() ||
        lead.customerName.contains(cleanSearch, ignoreCase = true) ||
        lead.mobile.contains(cleanSearch) ||
        lead.productName.contains(cleanSearch, ignoreCase = true) ||
        lead.officerId.contains(cleanSearch, ignoreCase = true) ||
        lead.officerName.contains(cleanSearch, ignoreCase = true) ||
        lead.notes.contains(cleanSearch, ignoreCase = true)
      matchesStatus && matchesSearch
    }
  }

  // Helper function to open DatePickerDialog directly for a specific customer lead card (Restricted to Today or Future dates only)
  // Helper function to open DatePickerDialog followed by TimePickerDialog for scheduling exact follow-up alarm
  val openDatePickerForCustomer: (CustomerLead) -> Unit = { targetLead ->
    val cal = Calendar.getInstance()
    if (targetLead.followUpDate.isNotBlank()) {
      try {
        val dtFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsed = dtFormat.parse(targetLead.followUpDate.take(10))
        if (parsed != null) cal.time = parsed
      } catch (_: Exception) {}
    }
    val datePickerDialog = android.app.DatePickerDialog(
      context,
      { _, year, month, dayOfMonth ->
        val chosenCal = Calendar.getInstance().apply {
          set(Calendar.YEAR, year)
          set(Calendar.MONTH, month)
          set(Calendar.DAY_OF_MONTH, dayOfMonth)
          set(Calendar.SECOND, 0)
          set(Calendar.MILLISECOND, 0)
        }

        // Open TimePickerDialog immediately after date selection
        val initialHour = if (chosenCal.get(Calendar.HOUR_OF_DAY) < 9) 10 else chosenCal.get(Calendar.HOUR_OF_DAY)
        val initialMinute = 0
        val timePickerDialog = android.app.TimePickerDialog(
          context,
          { _, hourOfDay, minute ->
            chosenCal.set(Calendar.HOUR_OF_DAY, hourOfDay)
            chosenCal.set(Calendar.MINUTE, minute)

            val displayFormat = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US)
            val selectedDateTime = displayFormat.format(chosenCal.time)
            viewModel.scheduleLeadFollowUpWithAlarm(
              leadId = targetLead.id,
              dateTimeFormatted = selectedDateTime,
              triggerTimeMillis = chosenCal.timeInMillis
            )
          },
          initialHour,
          initialMinute,
          false // 12-hour AM/PM format
        )
        timePickerDialog.setTitle("⏰ Select Alarm & Follow-Up Time")
        timePickerDialog.show()
      },
      cal.get(Calendar.YEAR),
      cal.get(Calendar.MONTH),
      cal.get(Calendar.DAY_OF_MONTH)
    )
    val todayCal = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    datePickerDialog.datePicker.minDate = todayCal.timeInMillis
    datePickerDialog.setTitle("📅 Select Follow-Up Date")
    datePickerDialog.show()
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

    // 1. Follow-Up Reminders Visual Alarm Banner
    if (dueFollowUpLeads.isNotEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF59E0B)))
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Text("🔔", fontSize = 20.sp)
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Urgent Follow-Up Reminder",
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
                  color = Color(0xFF92400E)
                )
                Text(
                  text = "${dueFollowUpLeads.size} lead(s) scheduled for follow-up today or overdue!",
                  fontSize = 11.5.sp,
                  color = Color(0xFFB45309),
                  fontWeight = FontWeight.Medium
                )
              }
            }

            Button(
              onClick = {
                statusFilter = "Follow-Up"
                searchQuery = ""
              },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
              shape = RoundedCornerShape(8.dp),
              contentPadding = PaddingValues(vertical = 8.dp)
            ) {
              Text(
                "Review ${dueFollowUpLeads.size} Scheduled Follow-Up(s)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
              )
            }
          }
        }
      }
    }

    // 2. Dynamic Real-Time Dashboard KPI & Visual Analytics (Modern Executive Styling)
    item {
      val conversionRate = if (totalLeadsCount > 0) {
        ((convertedLeads.size.toDouble() / totalLeadsCount) * 100).toInt()
      } else 0
      val activeRate = if (totalLeadsCount > 0) {
        ((activeLeads.size.toDouble() / totalLeadsCount) * 100).toInt()
      } else 0
      val lostRate = if (totalLeadsCount > 0) {
        ((lostLeads.size.toDouble() / totalLeadsCount) * 100).toInt()
      } else 0

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          // --- Section Header: Title, Portfolio Subtitle, & Win Rate Badge ---
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              Box(
                modifier = Modifier
                  .size(38.dp)
                  .background(
                    brush = Brush.linearGradient(
                      colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                      )
                    ),
                    shape = RoundedCornerShape(11.dp)
                  ),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Insights,
                  contentDescription = null,
                  tint = Color.White,
                  modifier = Modifier.size(20.dp)
                )
              }

              Column {
                Text(
                  text = "Pipeline Dashboard & Metrics",
                  fontWeight = FontWeight.Bold,
                  fontSize = 14.sp,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = if (isBranchFilterActive) "Branch: $supervisorBranchQuery (Team Monitoring)"
                         else "${uiState.profile.officerName.ifBlank { "Personal Portfolio" }} • ${uiState.profile.staffId.ifBlank { "Sales Officer" }}",
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // High-impact Win Rate Pill
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = Color(0xFFDCFCE7),
              border = BorderStroke(1.dp, Color(0xFF86EFAC))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = "🎯",
                  fontSize = 10.sp
                )
                Text(
                  text = "$conversionRate% Win Rate",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF166534)
                )
              }
            }
          }

          // --- Visual Segmented Pipeline Health Bar ---
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "PORTFOLIO DISTRIBUTION",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                letterSpacing = 0.5.sp
              )
              Text(
                text = "$totalLeadsCount Leads Logged",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            // Multi-segment progress bar with clip shape
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF1F5F9))
            ) {
              if (totalLeadsCount > 0) {
                Row(modifier = Modifier.fillMaxSize()) {
                  if (convertedLeads.isNotEmpty()) {
                    Box(
                      modifier = Modifier
                        .weight(convertedLeads.size.toFloat())
                        .fillMaxHeight()
                        .background(Color(0xFF10B981))
                    )
                  }
                  if (activeLeads.isNotEmpty()) {
                    Box(
                      modifier = Modifier
                        .weight(activeLeads.size.toFloat())
                        .fillMaxHeight()
                        .background(Color(0xFF3B82F6))
                    )
                  }
                  if (lostLeads.isNotEmpty()) {
                    Box(
                      modifier = Modifier
                        .weight(lostLeads.size.toFloat())
                        .fillMaxHeight()
                        .background(Color(0xFFF43F5E))
                    )
                  }
                }
              }
            }

            // Segment Legend with colored micro-dots
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Converted
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Box(
                  modifier = Modifier
                    .size(7.dp)
                    .background(Color(0xFF10B981), CircleShape)
                )
                Text(
                  text = "Won: ${convertedLeads.size} ($conversionRate%)",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Medium,
                  color = Color(0xFF047857)
                )
              }

              // Active
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Box(
                  modifier = Modifier
                    .size(7.dp)
                    .background(Color(0xFF3B82F6), CircleShape)
                )
                Text(
                  text = "Active: ${activeLeads.size} ($activeRate%)",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Medium,
                  color = Color(0xFF1D4ED8)
                )
              }

              // Lost
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Box(
                  modifier = Modifier
                    .size(7.dp)
                    .background(Color(0xFFF43F5E), CircleShape)
                )
                Text(
                  text = "Lost: ${lostLeads.size} ($lostRate%)",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Medium,
                  color = Color(0xFFBE123C)
                )
              }
            }
          }

          Divider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            thickness = 0.8.dp
          )

          // --- 2x2 Modern High-Definition KPI Cards Grid ---
          // Row 1: Total Leads & Active Pipeline
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Tile 1: Total Leads Logged
            val isTotalSelected = statusFilter == "All"
            Card(
              modifier = Modifier
                .weight(1f)
                .clickable { statusFilter = "All" },
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(
                containerColor = if (isTotalSelected) Color(0xFFF1F5F9) else Color(0xFFF8FAFC)
              ),
              border = BorderStroke(
                width = if (isTotalSelected) 1.5.dp else 1.dp,
                color = if (isTotalSelected) Color(0xFF64748B) else Color(0xFFE2E8F0)
              ),
              elevation = CardDefaults.cardElevation(defaultElevation = if (isTotalSelected) 2.dp else 0.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Box(
                    modifier = Modifier
                      .size(26.dp)
                      .background(Color(0xFFE2E8F0), CircleShape),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.Groups,
                      contentDescription = null,
                      tint = Color(0xFF475569),
                      modifier = Modifier.size(15.dp)
                    )
                  }
                  if (isTotalSelected) {
                    Text(
                      text = "ALL",
                      fontSize = 8.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF475569)
                    )
                  }
                }
                Text(
                  text = "TOTAL LEADS",
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF64748B),
                  letterSpacing = 0.4.sp
                )
                Text(
                  text = "$totalLeadsCount",
                  fontSize = 19.sp,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFF0F172A)
                )
                Text(
                  text = "${activeLeads.size} active • ${convertedLeads.size} won",
                  fontSize = 10.sp,
                  color = Color(0xFF64748B),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            // Tile 2: Active Pipeline Value
            Card(
              modifier = Modifier
                .weight(1f)
                .clickable {
                  // Switch to active deals by resetting lost/converted filter if active
                  if (statusFilter == "Lost" || statusFilter == "Converted") {
                    statusFilter = "All"
                  }
                },
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
              border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
              elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Box(
                    modifier = Modifier
                      .size(26.dp)
                      .background(Color(0xFFDBEAFE), CircleShape),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.TrendingUp,
                      contentDescription = null,
                      tint = Color(0xFF1D4ED8),
                      modifier = Modifier.size(15.dp)
                    )
                  }
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFDBEAFE)
                  ) {
                    Text(
                      text = "${activeLeads.size} Deals",
                      fontSize = 8.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF1E40AF),
                      modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                  }
                }
                Text(
                  text = "ACTIVE PIPELINE",
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF1D4ED8),
                  letterSpacing = 0.4.sp
                )
                Text(
                  text = LoanCalculators.formatCurrency(activePipelineValue, uiState.profile.currency),
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF1E40AF),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "In-progress customer deals",
                  fontSize = 10.sp,
                  color = Color(0xFF3B82F6),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }

          // Row 2: Converted Won & Lost Deals
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Tile 3: Converted Deals Value
            val isConvertedSelected = statusFilter.equals("Converted", ignoreCase = true)
            Card(
              modifier = Modifier
                .weight(1f)
                .clickable { statusFilter = "Converted" },
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(
                containerColor = if (isConvertedSelected) Color(0xFFD1FAE5) else Color(0xFFECFDF5)
              ),
              border = BorderStroke(
                width = if (isConvertedSelected) 1.5.dp else 1.dp,
                color = if (isConvertedSelected) Color(0xFF059669) else Color(0xFFA7F3D0)
              ),
              elevation = CardDefaults.cardElevation(defaultElevation = if (isConvertedSelected) 2.dp else 0.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Box(
                    modifier = Modifier
                      .size(26.dp)
                      .background(Color(0xFFD1FAE5), CircleShape),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.CheckCircle,
                      contentDescription = null,
                      tint = Color(0xFF059669),
                      modifier = Modifier.size(15.dp)
                    )
                  }
                  if (isConvertedSelected) {
                    Text(
                      text = "SHOWING",
                      fontSize = 8.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF047857)
                    )
                  } else {
                    Surface(
                      shape = RoundedCornerShape(10.dp),
                      color = Color(0xFFD1FAE5)
                    ) {
                      Text(
                        text = "${convertedLeads.size} Won",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF047857),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                      )
                    }
                  }
                }
                Text(
                  text = "CONVERTED WON",
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF047857),
                  letterSpacing = 0.4.sp
                )
                Text(
                  text = LoanCalculators.formatCurrency(convertedValue, uiState.profile.currency),
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF065F46),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "${convertedLeads.size} closed loans ($conversionRate%)",
                  fontSize = 10.sp,
                  color = Color(0xFF059669),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            // Tile 4: Lost Deals Value
            val isLostSelected = statusFilter.equals("Lost", ignoreCase = true)
            Card(
              modifier = Modifier
                .weight(1f)
                .clickable { statusFilter = "Lost" },
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(
                containerColor = if (isLostSelected) Color(0xFFFFE4E6) else Color(0xFFFFF1F2)
              ),
              border = BorderStroke(
                width = if (isLostSelected) 1.5.dp else 1.dp,
                color = if (isLostSelected) Color(0xFFE11D48) else Color(0xFFFECDD3)
              ),
              elevation = CardDefaults.cardElevation(defaultElevation = if (isLostSelected) 2.dp else 0.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Box(
                    modifier = Modifier
                      .size(26.dp)
                      .background(Color(0xFFFFE4E6), CircleShape),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.Cancel,
                      contentDescription = null,
                      tint = Color(0xFFDC2626),
                      modifier = Modifier.size(15.dp)
                    )
                  }
                  if (isLostSelected) {
                    Text(
                      text = "SHOWING",
                      fontSize = 8.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFFB91C1C)
                    )
                  } else {
                    Surface(
                      shape = RoundedCornerShape(10.dp),
                      color = Color(0xFFFFE4E6)
                    ) {
                      Text(
                        text = "${lostLeads.size} Lost",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB91C1C),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                      )
                    }
                  }
                }
                Text(
                  text = "LOST / DEDUCTED",
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFFB91C1C),
                  letterSpacing = 0.4.sp
                )
                Text(
                  text = LoanCalculators.formatCurrency(lostValue, uiState.profile.currency),
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF9F1239),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "${lostLeads.size} dropped / lost lead(s)",
                  fontSize = 10.sp,
                  color = Color(0xFFDC2626),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }
      }
    }


    // 3. Search & Filter Section
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Search & Supervisor Monitoring",
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              color = MaterialTheme.colorScheme.primary
            )
            val hasPendingLeads = uiState.syncQueue.isNotEmpty()
            Button(
              onClick = { viewModel.syncNow() },
              shape = RoundedCornerShape(8.dp),
              colors = if (hasPendingLeads) {
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error,
                  contentColor = MaterialTheme.colorScheme.onError
                )
              } else {
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
              },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
              modifier = Modifier.height(30.dp)
            ) {
              Icon(
                Icons.Default.Sync,
                contentDescription = "Sync Live Leads",
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (hasPendingLeads) "Sync (${uiState.syncQueue.size})" else "Sync Live",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          // 1. Status Filter Dropdown
          Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
              onClick = { filterStatusMenuExpanded = true },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                val currentFilterCount = when (statusFilter) {
                  "All" -> allLeadsCount(scopedLeads)
                  "Follow-Up" -> scopedLeads.count { it.followUpDate.isNotBlank() && !it.status.equals("Lost", ignoreCase = true) && !it.status.equals("Converted", ignoreCase = true) }
                  else -> countForStatus(scopedLeads, statusFilter)
                }
                Text(
                  text = "Status: $statusFilter ($currentFilterCount)",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
              }
            }

            DropdownMenu(
              expanded = filterStatusMenuExpanded,
              onDismissRequest = { filterStatusMenuExpanded = false }
            ) {
              listOf("All", "Follow-Up", "New", "Contacted", "Converted", "Lost").forEach { st ->
                val count = when (st) {
                  "All" -> scopedLeads.size
                  "Follow-Up" -> scopedLeads.count { it.followUpDate.isNotBlank() && !it.status.equals("Lost", ignoreCase = true) && !it.status.equals("Converted", ignoreCase = true) }
                  else -> scopedLeads.count { it.status.equals(st, ignoreCase = true) }
                }
                DropdownMenuItem(
                  text = {
                    Text(
                      text = "$st ($count)",
                      fontWeight = if (statusFilter == st) FontWeight.Bold else FontWeight.Normal
                    )
                  },
                  onClick = {
                    statusFilter = st
                    filterStatusMenuExpanded = false
                  }
                )
              }
            }
          }

          // 2. Supervisor Branch Search Filter (Smart & compact sizing, governed by Admin Branch Rights)
          OutlinedTextField(
            value = supervisorBranchQuery,
            onValueChange = { if (canUseBranchFilter) supervisorBranchQuery = it },
            enabled = canUseBranchFilter,
            label = {
              Text(
                if (canUseBranchFilter) "Supervisor Branch Filter"
                else "Supervisor Branch Filter (Restricted by Admin)",
                fontSize = 11.sp
              )
            },
            placeholder = {
              Text(
                if (!canUseBranchFilter) "Branch monitoring disabled by Admin (Own leads only)"
                else if (hasAllBranchesRight) "Type any branch name to filter..."
                else "Authorized branch: ${uiState.effectiveAllowedBranch}",
                fontSize = 11.5.sp,
                color = Color(0xFF94A3B8)
              )
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            colors = kbInputFieldColors(isDark),
            leadingIcon = { Icon(Icons.Default.LocationCity, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(17.dp)) },
            trailingIcon = {
              if (supervisorBranchQuery.isNotBlank()) {
                IconButton(onClick = { supervisorBranchQuery = "" }, modifier = Modifier.size(30.dp)) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                }
              }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp),
            singleLine = true
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = when {
                !canUseBranchFilter -> "🔒 Branch filter disabled by Admin • Viewing your personal leads only"
                isUnauthorizedBranchQuery -> "🚫 Access Denied for \"$supervisorBranchQuery\" • Authorized only for: ${uiState.effectiveAllowedBranch.ifBlank { "None" }}"
                hasAllBranchesRight -> "🔓 Admin Branch Rights: All Branches Allowed"
                else -> "🏢 Authorized Branch Rights: ${uiState.effectiveAllowedBranch.ifBlank { "Own Leads Only" }}"
              },
              fontSize = 10.5.sp,
              color = when {
                !canUseBranchFilter -> Color(0xFFB45309)
                isUnauthorizedBranchQuery -> Color(0xFFDC2626)
                else -> Color(0xFF1D4ED8)
              },
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f)
            )
            if (canUseBranchFilter && allowedBranchesList.isNotEmpty() && !hasAllBranchesRight) {
              val primaryAllowedBranch = uiState.effectiveAllowedBranch.split(",", ";", "|").first().trim()
              TextButton(
                onClick = {
                  supervisorBranchQuery = if (supervisorBranchQuery.isBlank()) primaryAllowedBranch else ""
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.height(26.dp)
              ) {
                Text(
                  if (supervisorBranchQuery.isBlank()) "Filter My Branch" else "Show My Leads",
                  fontSize = 10.5.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }

          // 3. Customer / Phone Search (Smart & compact sizing)
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Customer Name / Phone / Notes", fontSize = 11.sp) },
            placeholder = { Text("Search customer, phone number, staff ID...", fontSize = 11.5.sp, color = Color(0xFF94A3B8)) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            colors = kbInputFieldColors(isDark),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(17.dp)) },
            trailingIcon = {
              if (searchQuery.isNotBlank()) {
                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(30.dp)) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                }
              }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp),
            singleLine = true
          )

          // 4. Export CSV Button with complete track record
          Button(
            onClick = {
              val csv = buildKbCsv(filteredLeads)
              val cleanBranchName = supervisorBranchQuery.trim().replace("[^a-zA-Z0-9_]".toRegex(), "_")
              val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
              val filename = if (cleanBranchName.isNotBlank()) "kb_leads_${cleanBranchName}_$ts.csv" else "kb_loan_leads_$ts.csv"
              onExportCsv(filename, csv)
            },
            modifier = Modifier
              .fillMaxWidth()
              .press3d(0.96f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export Leads to CSV", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }

    // 4. Vertical Card Layout for Leads (Folded by Default like Contacts)
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = "Customer Leads (${filteredLeads.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(4.dp)
          ) {
            Text(
              text = "Folded by Default",
              fontSize = 9.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
        }

        if (filteredLeads.isNotEmpty()) {
          val allExpanded = filteredLeads.all { expandedLeadIds.contains(it.id) }
          TextButton(
            onClick = {
              expandedLeadIds = if (allExpanded) {
                emptySet()
              } else {
                filteredLeads.map { it.id }.toSet()
              }
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Icon(
              imageVector = if (allExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (allExpanded) "Collapse All" else "Expand All",
              fontSize = 11.5.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary
            )
          }
        }
      }
    }

    if (filteredLeads.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(32.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("No customer records match your filter criteria.", color = Color(0xFF94A3B8), fontSize = 12.sp)
          }
        }
      }
    } else {
      items(filteredLeads, key = { it.id }) { lead ->
        val isExpanded = expandedLeadIds.contains(lead.id)
        VerticalLeadCard(
          lead = lead,
          currency = uiState.profile.currency,
          todayIso = todayIso,
          isExpanded = isExpanded,
          onToggleExpand = {
            expandedLeadIds = if (isExpanded) {
              expandedLeadIds - lead.id
            } else {
              expandedLeadIds + lead.id
            }
          },
          showEditPlanButton = !isBranchFilterActive,
          onStatusChange = { viewModel.updateLeadStatus(lead.id, it) },
          onEditNote = {
            editingNoteLead = lead
            currentEditingNote = sanitizeRawNoteText(lead.notes)
          },
          onEditPlan = {
            editingPlanLead = lead
          },
          onOpenFollowUpDatePicker = {
            openDatePickerForCustomer(lead)
          },
          onClearFollowUpDate = {
            viewModel.updateLeadFollowUpDate(lead.id, "")
          },
          onViewHistory = {
            viewingHistoryLead = lead
          },
          onCall = {
            viewModel.logCustomerApproach(lead.id, "call")
            onCallCustomer(lead.mobile)
          },
          onWhatsApp = {
            viewModel.logCustomerApproach(lead.id, "whatsapp")
            val text = "Dear ${lead.customerName}, following up regarding your ${lead.productName} proposal for ${LoanCalculators.formatCurrency(lead.loanAmount, uiState.profile.currency)} at ${LoanCalculators.formatCurrency(lead.monthlyEmi, uiState.profile.currency)}/mo. Best regards, ${uiState.profile.officerName} (${uiState.profile.staffId})"
            onWhatsAppCustomer(lead.mobile, text)
          },
          onSms = {
            viewModel.logCustomerApproach(lead.id, "sms")
            val msg = "Dear ${lead.customerName}, following up regarding your ${lead.productName} proposal for ${LoanCalculators.formatCurrency(lead.loanAmount, uiState.profile.currency)} (EMI: ${LoanCalculators.formatCurrency(lead.monthlyEmi, uiState.profile.currency)}/mo). KB Loan Team."
            onSmsCustomer(lead.mobile, msg)
          }
        )
      }
    }
    }
  }

  // Edit Note Modal Dialog
  editingNoteLead?.let { lead ->
    AlertDialog(
      onDismissRequest = { editingNoteLead = null },
      title = { Text("Edit Customer Note / Comment", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Customer: ${lead.customerName}", fontSize = 12.sp, color = Color(0xFF64748B))
          OutlinedTextField(
            value = currentEditingNote,
            onValueChange = { currentEditingNote = it },
            label = { Text("Note / Comment") },
            placeholder = { Text("Enter customer notes...", color = Color(0xFF94A3B8)) },
            colors = kbInputFieldColors(isDark),
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.updateLeadNote(lead.id, currentEditingNote)
            editingNoteLead = null
          }
        ) {
          Text("Save Note")
        }
      },
      dismissButton = {
        TextButton(onClick = { editingNoteLead = null }) {
          Text("Cancel")
        }
      }
    )
  }

  // Edit Loan Proposal / Plan Modal Dialog (Allows changing amount, tenure, product, recalculating, and sharing revised plan)
  editingPlanLead?.let { lead ->
    EditLeadPlanDialog(
      lead = lead,
      products = uiState.products,
      currency = uiState.profile.currency,
      fedRate = uiState.profile.fedRate,
      officerName = uiState.profile.officerName,
      staffId = uiState.profile.staffId,
      branchName = uiState.profile.branchName,
      onDismiss = { editingPlanLead = null },
      onSavePlan = { newProdId, newProdName, newCategory, newAmount, newTenure, newEmi, newProfit, newFee, taxRate, taxDeducted, netProfit ->
        viewModel.updateLeadPlan(
          leadId = lead.id,
          productId = newProdId,
          productName = newProdName,
          category = newCategory,
          loanAmount = newAmount,
          tenureMonths = newTenure,
          monthlyEmi = newEmi,
          totalProfit = newProfit,
          processingFee = newFee,
          taxRate = taxRate,
          taxDeducted = taxDeducted,
          netProfit = netProfit
        )
        editingPlanLead = null
      },
      onShareRevisedWhatsApp = { newProdId, newProdName, newCategory, newAmount, newTenure, newEmi, newProfit, newFee, taxRate, taxDeducted, netProfit, msg ->
        viewModel.updateLeadPlan(
          leadId = lead.id,
          productId = newProdId,
          productName = newProdName,
          category = newCategory,
          loanAmount = newAmount,
          tenureMonths = newTenure,
          monthlyEmi = newEmi,
          totalProfit = newProfit,
          processingFee = newFee,
          taxRate = taxRate,
          taxDeducted = taxDeducted,
          netProfit = netProfit
        )
        editingPlanLead = null
        val formattedPhone = LoanCalculators.formatForWhatsApp(lead.mobile)
        onWhatsAppCustomer(formattedPhone, msg)
      },
      onShareRevisedSms = { newProdId, newProdName, newCategory, newAmount, newTenure, newEmi, newProfit, newFee, taxRate, taxDeducted, netProfit, msg ->
        viewModel.updateLeadPlan(
          leadId = lead.id,
          productId = newProdId,
          productName = newProdName,
          category = newCategory,
          loanAmount = newAmount,
          tenureMonths = newTenure,
          monthlyEmi = newEmi,
          totalProfit = newProfit,
          processingFee = newFee,
          taxRate = taxRate,
          taxDeducted = taxDeducted,
          netProfit = netProfit
        )
        editingPlanLead = null
        onSmsCustomer(lead.mobile, msg)
      }
    )
  }

  // Lead Approach Track Record & Activity Audit History Modal Dialog
  viewingHistoryLead?.let { lead ->
    LeadActivityHistoryDialog(
      lead = lead,
      currency = uiState.profile.currency,
      onDismiss = { viewingHistoryLead = null }
    )
  }
}

private fun allLeadsCount(leads: List<CustomerLead>) = leads.size
private fun countForStatus(leads: List<CustomerLead>, st: String) = leads.count { it.status.equals(st, ignoreCase = true) }

private fun normalizeBranchText(branch: String): String {
  return branch.trim().replace("\\s+".toRegex(), " ").lowercase(Locale.US)
}

private fun resolveEffectiveLeadBranch(lead: CustomerLead, uiState: LoanUiState): String {
  val matchedOfficer = uiState.registeredUsers.find { u ->
    (lead.userId.isNotBlank() && u.uid == lead.userId) ||
      (lead.officerId.isNotBlank() && u.staffId.equals(lead.officerId.trim(), ignoreCase = true)) ||
      (lead.officerName.isNotBlank() && u.officerName.equals(lead.officerName.trim(), ignoreCase = true))
  }
  val officerBranch = matchedOfficer?.branchName?.trim()?.ifBlank {
    matchedOfficer.allowedSupervisorBranch
      .split(",", ";", "|")
      .firstOrNull { !it.trim().equals("all", ignoreCase = true) && it.trim() != "*" }
      ?.trim()
      .orEmpty()
  }.orEmpty()
  return officerBranch.ifBlank { lead.branchName.trim() }
}

private val BRANCH_SHORT_CODES = mapOf(
  "psr" to "peshawar",
  "peshawar" to "peshawar",
  "mdn" to "mardan",
  "mardan" to "mardan",
  "isb" to "islamabad",
  "islamabad" to "islamabad",
  "rwp" to "rawalpindi",
  "rawalpindi" to "rawalpindi",
  "lhr" to "lahore",
  "lahore" to "lahore",
  "khi" to "karachi",
  "karachi" to "karachi",
  "fsd" to "faisalabad",
  "faisalabad" to "faisalabad",
  "mul" to "multan",
  "multan" to "multan",
  "qta" to "quetta",
  "quetta" to "quetta",
  "swt" to "swat",
  "swat" to "swat",
  "atk" to "attock",
  "attock" to "attock",
  "abt" to "abbottabad",
  "abbottabad" to "abbottabad",
  "dik" to "deraismailkhan",
  "dera ismail khan" to "deraismailkhan",
  "grw" to "gujranwala",
  "gujranwala" to "gujranwala",
  "skt" to "sialkot",
  "sialkot" to "sialkot",
  "bwp" to "bahawalpur",
  "bahawalpur" to "bahawalpur",
  "hyd" to "hyderabad",
  "hyderabad" to "hyderabad",
  "suk" to "sukkur",
  "sukkur" to "sukkur",
  "gwd" to "gwadar",
  "gwadar" to "gwadar",
  "mir" to "mirpur",
  "mirpur" to "mirpur",
  "mzd" to "muzaffarabad",
  "muzaffarabad" to "muzaffarabad"
)

private fun canonicalBranchCode(branch: String): String {
  val clean = normalizeBranchText(branch).replace("branch", "").replace(" ", "").trim()
  return BRANCH_SHORT_CODES[clean] ?: clean
}

private fun matchesSupervisorBranch(leadBranch: String, cleanQuery: String): Boolean {
  if (cleanQuery.isEmpty()) return true
  val cleanLeadBranch = normalizeBranchText(leadBranch)
  if (cleanLeadBranch.isEmpty()) return false

  val canonQuery = canonicalBranchCode(cleanQuery)
  val canonLead = canonicalBranchCode(cleanLeadBranch)
  if (canonQuery.isNotEmpty() && canonLead.isNotEmpty() && canonQuery == canonLead) {
    return true
  }

  val compactQuery = cleanQuery.replace("branch", "").replace(" ", "")
  val compactLeadBranch = cleanLeadBranch.replace("branch", "").replace(" ", "")
  if (compactQuery.length <= 4 || compactLeadBranch.length <= 4) {
    return compactQuery == compactLeadBranch || canonQuery == canonLead
  }
  return cleanLeadBranch.contains(cleanQuery) ||
    cleanQuery.contains(cleanLeadBranch) ||
    (compactQuery.isNotEmpty() && compactLeadBranch.contains(compactQuery)) ||
    (compactLeadBranch.isNotEmpty() && compactQuery.contains(compactLeadBranch))
}

private fun isCurrentUserLead(lead: CustomerLead, uiState: LoanUiState): Boolean {
  return uiState.isLeadOwnedByCurrentUser(lead)
}

/**
 * Edit Lead Plan Dialog:
 * Allows user to change product, loan amount, and tenure for an existing lead.
 * Automatically recalculates EMI, Profit, and Upfront Fee.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditLeadPlanDialog(
  lead: CustomerLead,
  products: List<LoanProduct>,
  currency: String,
  fedRate: Double,
  officerName: String = "",
  staffId: String = "",
  branchName: String = "",
  onDismiss: () -> Unit,
  onSavePlan: (productId: String, productName: String, category: String, loanAmount: Long, tenure: Int, emi: Long, profit: Long, fee: Long, taxRate: Double, taxDeducted: Long, netProfit: Long) -> Unit,
  onShareRevisedWhatsApp: (productId: String, productName: String, category: String, loanAmount: Long, tenure: Int, emi: Long, profit: Long, fee: Long, taxRate: Double, taxDeducted: Long, netProfit: Long, message: String) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
  onShareRevisedSms: (productId: String, productName: String, category: String, loanAmount: Long, tenure: Int, emi: Long, profit: Long, fee: Long, taxRate: Double, taxDeducted: Long, netProfit: Long, message: String) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
  var dialogCategoryFilter by remember {
    mutableStateOf(if (lead.category.equals("liability", ignoreCase = true)) "liability" else "asset")
  }
  var selectedProduct by remember {
    mutableStateOf(products.find { it.id == lead.productId } ?: products.first())
  }
  var productDropdownExpanded by remember { mutableStateOf(false) }

  var amountInput by remember { mutableStateOf(lead.loanAmount.toString()) }
  var tenureInput by remember { mutableStateOf(lead.tenureMonths.toString()) }

  val amount = amountInput.filter { it.isDigit() }.toLongOrNull() ?: lead.loanAmount
  val tenure = tenureInput.filter { it.isDigit() }.toIntOrNull() ?: lead.tenureMonths

  val isLiability = selectedProduct.category == "liability"
  val applicableRate = LoanCalculators.getApplicableRate(selectedProduct, amount, tenure)
  val emiResult = LoanCalculators.calculateEmi(amount, applicableRate, tenure, selectedProduct.rateType)
  val feeResult = LoanCalculators.calculateFee(selectedProduct, amount, fedRate)
  val depositRes = remember(amount, applicableRate, tenure, lead.isFiler) {
    LoanCalculators.calculateDepositProfit(
      depositAmount = amount,
      annualRate = applicableRate,
      tenureMonths = tenure,
      isFiler = lead.isFiler,
      filerTaxRate = if (lead.taxRate > 0.0 && lead.isFiler) lead.taxRate else 20.0,
      nonFilerTaxRate = if (lead.taxRate > 0.0 && !lead.isFiler) lead.taxRate else 40.0
    )
  }

  val revisedProposalMessage = remember(lead, selectedProduct, amount, tenure, emiResult, depositRes, isLiability, applicableRate, currency, officerName, staffId, branchName) {
    val durationLabel = when {
      tenure == 1 -> "1 Month"
      tenure % 12 == 0 -> "$tenure Months (${tenure / 12} ${if (tenure / 12 == 1) "Year" else "Years"})"
      else -> "$tenure Months (${String.format(Locale.US, "%.1f", tenure / 12.0)} Years)"
    }
    buildString {
      if (isLiability) {
        appendLine("🏛️ *KB REVISED DEPOSIT PROPOSAL*")
        appendLine("👤 Customer: ${lead.customerName.ifBlank { "Valued Client" }}")
        if (lead.mobile.isNotBlank()) {
          appendLine("📱 Mobile: ${lead.mobile}")
        }
        appendLine("📋 Deposit Scheme: ${selectedProduct.name}")
        appendLine("────────────────────")
        appendLine()
        appendLine("📅 Duration: *$durationLabel*")
        appendLine("💰 Deposit Amount: *${LoanCalculators.formatCurrency(amount, currency)}*")
        if (applicableRate > 0.0) {
          appendLine("📈 Profit Rate: *${applicableRate}% p.a.*")
          appendLine("💵 Net Monthly Return: *${LoanCalculators.formatCurrency(depositRes.netMonthlyProfit, currency)}*")
          appendLine("🏦 Net Total Payout: *${LoanCalculators.formatCurrency(depositRes.netMaturityAmount, currency)}*")
        } else {
          appendLine("🛡️ Current Account: Non-Remunerative (0% WHT / On-Demand)")
        }
        appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
        appendLine()
        appendLine("────────────────────")
        appendLine("🏦 Officer: ${officerName.ifBlank { lead.officerName.ifBlank { "Branch Officer" } }}${if (staffId.isNotBlank()) " ($staffId)" else if (lead.officerId.isNotBlank()) " (${lead.officerId})" else ""}")
        append("📍 Branch: ${branchName.ifBlank { lead.branchName.ifBlank { "Main Branch" } }}")
      } else {
        appendLine("🏛️ *KB REVISED LOAN PROPOSAL*")
        appendLine("👤 Customer: ${lead.customerName.ifBlank { "Valued Client" }}")
        if (lead.mobile.isNotBlank()) {
          appendLine("📱 Mobile: ${lead.mobile}")
        }
        appendLine("📋 Facility: ${selectedProduct.name}")
        appendLine("────────────────────")
        appendLine()
        appendLine("📅 *$durationLabel*")
        appendLine("💰 Loan Amount: *${LoanCalculators.formatCurrency(amount, currency)}*")
        appendLine("💳 Monthly EMI: *${LoanCalculators.formatCurrency(emiResult.emi, currency)}*")
        appendLine("🧾 Processing Fee: *${LoanCalculators.formatCurrency(feeResult.totalFee, currency)}*")
        appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
        appendLine()
        appendLine("────────────────────")
        appendLine("🏦 Officer: ${officerName.ifBlank { lead.officerName.ifBlank { "Loan Officer" } }}${if (staffId.isNotBlank()) " ($staffId)" else if (lead.officerId.isNotBlank()) " (${lead.officerId})" else ""}")
        append("📍 Branch: ${branchName.ifBlank { lead.branchName.ifBlank { "Main Branch" } }}")
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (isLiability) "Edit Deposit Proposal / Plan" else "Edit Loan Proposal / Plan",
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text("Customer: ${lead.customerName}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isLiability) Color(0xFF0F766E) else MaterialTheme.colorScheme.primary)

        // Facility Category Selector FilterChips (Asset Loans vs Liability Deposits)
        val dialogAssetCount = remember(products) { products.count { it.category != "liability" } }
        val dialogLiabCount = remember(products) { products.count { it.category == "liability" } }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          FilterChip(
            selected = dialogCategoryFilter == "all",
            onClick = { dialogCategoryFilter = "all" },
            label = { Text("All (${products.size})", fontSize = 10.sp) },
            modifier = Modifier.weight(1f)
          )
          FilterChip(
            selected = dialogCategoryFilter == "asset",
            onClick = {
              dialogCategoryFilter = "asset"
              if (selectedProduct.category == "liability") {
                val firstAsset = products.firstOrNull { it.category != "liability" }
                if (firstAsset != null) selectedProduct = firstAsset
              }
            },
            label = { Text("Asset ($dialogAssetCount)", fontSize = 10.sp) },
            modifier = Modifier.weight(1.1f)
          )
          FilterChip(
            selected = dialogCategoryFilter == "liability",
            onClick = {
              dialogCategoryFilter = "liability"
              if (selectedProduct.category != "liability") {
                val firstLiab = products.firstOrNull { it.category == "liability" }
                if (firstLiab != null) selectedProduct = firstLiab
              }
            },
            label = { Text("Liability ($dialogLiabCount)", fontSize = 10.sp) },
            modifier = Modifier.weight(1.1f)
          )
        }

        val filteredProductsForDialog = remember(products, dialogCategoryFilter) {
          when (dialogCategoryFilter) {
            "asset" -> products.filter { it.category != "liability" }
            "liability" -> products.filter { it.category == "liability" }
            else -> products
          }
        }

        // Product Selector Dropdown
        Box(modifier = Modifier.fillMaxWidth()) {
          OutlinedButton(
            onClick = { productDropdownExpanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(if (isLiability) "Product: ${selectedProduct.name}" else "Facility: ${selectedProduct.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
              Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
          }
          DropdownMenu(
            expanded = productDropdownExpanded,
            onDismissRequest = { productDropdownExpanded = false }
          ) {
            filteredProductsForDialog.forEach { p ->
              val pIsLiab = p.category == "liability"
              DropdownMenuItem(
                text = {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(p.name, fontWeight = if (p.id == selectedProduct.id) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Surface(
                      color = if (pIsLiab) Color(0xFFCCFBF1) else Color(0xFFDBEAFE),
                      shape = RoundedCornerShape(4.dp)
                    ) {
                      Text(
                        text = if (pIsLiab) "LIABILITY" else "ASSET",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pIsLiab) Color(0xFF0F766E) else Color(0xFF1E40AF),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                      )
                    }
                  }
                },
                onClick = {
                  selectedProduct = p
                  if (p.category == "liability" && dialogCategoryFilter == "asset") {
                    dialogCategoryFilter = "liability"
                  } else if (p.category != "liability" && dialogCategoryFilter == "liability") {
                    dialogCategoryFilter = "asset"
                  }
                  productDropdownExpanded = false
                }
              )
            }
          }
        }

        val isDark = androidx.compose.foundation.isSystemInDarkTheme()

        // Amount Input (Deposit vs Loan)
        OutlinedTextField(
          value = amountInput,
          onValueChange = { amountInput = it },
          label = { Text(if (isLiability) "Deposit Amount / جمع کروانے کی رقم (PKR)" else "Loan Amount (PKR)") },
          placeholder = { Text(if (isLiability) "e.g. 500000" else "e.g. 695000", color = Color(0xFF94A3B8)) },
          colors = kbInputFieldColors(isDark),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        // Tenure Months Input
        OutlinedTextField(
          value = tenureInput,
          onValueChange = { tenureInput = it },
          label = { Text(if (isLiability) "Deposit Tenure (Months)" else "Tenure (Months)") },
          placeholder = { Text(if (isLiability) "e.g. 12" else "e.g. 36", color = Color(0xFF94A3B8)) },
          colors = kbInputFieldColors(isDark),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        // Live Recalculated Summary Card
        if (isLiability) {
          // Liability Recalculated Terms (Deposit)
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)),
            border = BorderStroke(1.dp, Color(0xFF99F6E4)),
            shape = RoundedCornerShape(10.dp)
          ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Recalculated Deposit Terms (متوقع ڈپازٹ شرائط):", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F766E))
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Applicable Rate:", fontSize = 11.sp, color = Color(0xFF64748B))
                Text(
                  if (applicableRate <= 0.0) "0% (Current Account)" else "$applicableRate% p.a.",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF0F766E)
                )
              }
              if (applicableRate > 0.0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Monthly Expected Profit:", fontSize = 11.sp, color = Color(0xFF64748B))
                  Text(LoanCalculators.formatCurrency(depositRes.monthlyProfit, currency), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("WHT Tax (${depositRes.taxRate}%):", fontSize = 11.sp, color = Color(0xFF64748B))
                  Text("-${LoanCalculators.formatCurrency(depositRes.monthlyTax, currency)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFEF4444))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Net Monthly Profit:", fontSize = 11.sp, color = Color(0xFF64748B))
                  Text(LoanCalculators.formatCurrency(depositRes.netMonthlyProfit, currency), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Total Net Profit (${tenure}M):", fontSize = 11.sp, color = Color(0xFF64748B))
                  Text(LoanCalculators.formatCurrency(depositRes.netTotalProfit, currency), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                }
                HorizontalDivider(color = Color(0xFFCCFBF1))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Net Maturity Payout (اصل زر + خالص منافع):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F766E))
                  Text(LoanCalculators.formatCurrency(depositRes.netMaturityAmount, currency), fontSize = 11.5.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F766E))
                }
              } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Account Type:", fontSize = 11.sp, color = Color(0xFF64748B))
                  Text("Current Account (غیر منافع بخش)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Total Available Balance:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F766E))
                  Text(LoanCalculators.formatCurrency(amount, currency), fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F766E))
                }
              }
            }
          }
        } else {
          // Asset Loan Recalculated Terms
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(10.dp)
          ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Recalculated Loan Terms:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Applicable Rate:", fontSize = 11.sp, color = Color(0xFF64748B))
                Text("$applicableRate% (${selectedProduct.rateType})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Monthly EMI:", fontSize = 11.sp, color = Color(0xFF64748B))
                Text(LoanCalculators.formatCurrency(emiResult.emi, currency), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Profit:", fontSize = 11.sp, color = Color(0xFF64748B))
                Text(LoanCalculators.formatCurrency(emiResult.totalProfit, currency), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Upfront Fee (inc FED):", fontSize = 11.sp, color = Color(0xFF64748B))
                Text(LoanCalculators.formatCurrency(feeResult.totalFee, currency), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
              }
            }
          }
        }

        // Share Revised Plan Section (WhatsApp & SMS)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "Share Revised Plan with Customer:",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF334155)
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = {
                onShareRevisedWhatsApp(
                  selectedProduct.id,
                  selectedProduct.name,
                  selectedProduct.category,
                  amount,
                  tenure,
                  if (isLiability) depositRes.netMonthlyProfit else emiResult.emi,
                  if (isLiability) depositRes.totalProfit else emiResult.totalProfit,
                  if (isLiability) 0L else feeResult.totalFee,
                  if (isLiability) depositRes.taxRate else 0.0,
                  if (isLiability) depositRes.monthlyTax * tenure else 0L,
                  if (isLiability) depositRes.netTotalProfit else 0L,
                  revisedProposalMessage
                )
              },
              enabled = amount > 0L && tenure > 0,
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
              shape = RoundedCornerShape(8.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
              Spacer(modifier = Modifier.width(5.dp))
              Text("Share on WhatsApp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            }

            OutlinedButton(
              onClick = {
                onShareRevisedSms(
                  selectedProduct.id,
                  selectedProduct.name,
                  selectedProduct.category,
                  amount,
                  tenure,
                  if (isLiability) depositRes.netMonthlyProfit else emiResult.emi,
                  if (isLiability) depositRes.totalProfit else emiResult.totalProfit,
                  if (isLiability) 0L else feeResult.totalFee,
                  if (isLiability) depositRes.taxRate else 0.0,
                  if (isLiability) depositRes.monthlyTax * tenure else 0L,
                  if (isLiability) depositRes.netTotalProfit else 0L,
                  revisedProposalMessage
                )
              },
              enabled = amount > 0L && tenure > 0,
              shape = RoundedCornerShape(8.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF0284C7))
              Spacer(modifier = Modifier.width(5.dp))
              Text("Share via SMS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7), maxLines = 1)
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSavePlan(
            selectedProduct.id,
            selectedProduct.name,
            selectedProduct.category,
            amount,
            tenure,
            if (isLiability) depositRes.netMonthlyProfit else emiResult.emi,
            if (isLiability) depositRes.totalProfit else emiResult.totalProfit,
            if (isLiability) 0L else feeResult.totalFee,
            if (isLiability) depositRes.taxRate else 0.0,
            if (isLiability) depositRes.monthlyTax * tenure else 0L,
            if (isLiability) depositRes.netTotalProfit else 0L
          )
        },
        enabled = amount > 0L && tenure > 0,
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isLiability) Color(0xFF0D9488) else MaterialTheme.colorScheme.primary
        )
      ) {
        Text(if (isLiability) "Save Revised Deposit Plan" else "Save Revised Plan")
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
 * Lead Activity History & Track Record Dialog:
 * Shows complete immutable log of customer approaches, follow-up date assignments,
 * note/comment changes, and communications with timestamps and officer credentials.
 */
@Composable
fun LeadActivityHistoryDialog(
  lead: CustomerLead,
  currency: String,
  onDismiss: () -> Unit
) {
  val dateFormat = remember { SimpleDateFormat("dd-MMM-yyyy, hh:mm a", Locale.US) }
  val sortedActivities = remember(lead.activities) {
    val result = mutableListOf<LeadActivity>()
    val sorted = lead.activities.sortedByDescending { it.timestamp }
    for (act in sorted) {
      val isRedundantAlarm = act.details.contains("Alarm reminder set", ignoreCase = true) &&
        result.any { it.actionType == "follow_up" && abs(it.timestamp - act.timestamp) < 5000L }
      val isDuplicate = result.any {
        it.actionType == act.actionType &&
        it.details.trim().equals(act.details.trim(), ignoreCase = true) &&
        abs(it.timestamp - act.timestamp) < 10000L
      }
      if (!isRedundantAlarm && !isDuplicate) {
        result.add(act)
      }
    }
    result
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    title = null,
    text = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Card(
          modifier = Modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            // Header
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "📜 Customer Approach Track Record",
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp,
                  color = MaterialTheme.colorScheme.primary
                )
                Text(
                  text = "${lead.customerName} • ${lead.mobile.ifBlank { "No Mobile" }}",
                  fontSize = 11.5.sp,
                  color = Color(0xFF64748B),
                  fontWeight = FontWeight.Medium
                )
              }
              IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF64748B))
              }
            }

            // Stat Summary Cards Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Surface(
                color = Color(0xFFEFF6FF),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                modifier = Modifier.weight(1f)
              ) {
                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  Text("Total Approaches", fontSize = 9.5.sp, color = Color(0xFF1E3A8A), fontWeight = FontWeight.Bold)
                  Text("${lead.approachCount}x", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D4ED8))
                  Text("کتنی بار رابطہ ہوا", fontSize = 8.5.sp, color = Color(0xFF64748B))
                }
              }

              Surface(
                color = Color(0xFFFEF3C7),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                modifier = Modifier.weight(1f)
              ) {
                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  Text("Notes Updates", fontSize = 9.5.sp, color = Color(0xFF92400E), fontWeight = FontWeight.Bold)
                  Text("${lead.notesUpdateCount}x", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD97706))
                  Text("تبدیلیاں کمنٹس", fontSize = 8.5.sp, color = Color(0xFF64748B))
                }
              }

              Surface(
                color = Color(0xFFF0FDF4),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                modifier = Modifier.weight(1f)
              ) {
                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  Text("Scheduled Follow-Up", fontSize = 9.sp, color = Color(0xFF166534), fontWeight = FontWeight.Bold)
                  Text(
                    text = if (lead.followUpDate.isNotBlank()) lead.followUpDate else "None",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF15803D),
                    maxLines = 1
                  )
                  Text("فالو اپ شیڈیول", fontSize = 8.5.sp, color = Color(0xFF64748B))
                }
              }
            }

            // Lead Metadata Details
            Surface(
              color = Color(0xFFF8FAFC),
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
              ) {
                Text(
                  text = "🏢 Facility: ${lead.productName} • Amount: ${LoanCalculators.formatCurrency(lead.loanAmount, currency)} • Status: ${lead.status}",
                  fontSize = 10.5.sp,
                  color = Color(0xFF334155),
                  fontWeight = FontWeight.Medium
                )
                Text(
                  text = "👤 Assigned Officer: ${lead.officerName.ifBlank { "Officer" }} (${lead.officerId.ifBlank { "N/A" }}) • Branch: ${lead.branchName.ifBlank { "Main Branch" }}",
                  fontSize = 10.sp,
                  color = Color(0xFF64748B)
                )
              }
            }

            // Timeline Header
            Text(
              text = "Chronological Action Log (تاریخ وار مکمل ٹریک ریکارڈ):",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF1E293B)
            )

            // Timeline Items
            if (sortedActivities.isEmpty()) {
              Surface(
                color = Color(0xFFF8FAFC),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Text("📅 No activity records logged yet.", fontSize = 11.5.sp, color = Color(0xFF64748B))
                  Text("Every new follow-up date and note update will be permanently recorded here.", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
              }
            } else {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 280.dp)
                  .verticalScroll(rememberScrollState())
              ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  sortedActivities.forEach { act ->
                    val (bgCol, borderCol, icon) = when (act.actionType) {
                      "follow_up" -> Triple(Color(0xFFEFF6FF), Color(0xFFBFDBFE), Icons.Default.CalendarToday)
                      "note_update", "note" -> Triple(Color(0xFFFEF3C7), Color(0xFFFDE68A), Icons.Default.Edit)
                      "call" -> Triple(Color(0xFFF0FDF4), Color(0xFFBBF7D0), Icons.Default.Phone)
                      "whatsapp" -> Triple(Color(0xFFDCFCE7), Color(0xFF86EFAC), Icons.Default.Send)
                      "sms" -> Triple(Color(0xFFEDE9FE), Color(0xFFDDD6FE), Icons.Default.Email)
                      "status_change" -> Triple(Color(0xFFF1F5F9), Color(0xFFCBD5E1), Icons.Default.Refresh)
                      else -> Triple(Color(0xFFF8FAFC), Color(0xFFE2E8F0), Icons.Default.CheckCircle)
                    }

                    val cleanTitle = when (act.actionType) {
                      "status_change" -> "🔄 Status Changed"
                      "follow_up" -> "📅 Follow-Up Date"
                      "call" -> "📞 Phone Call"
                      "whatsapp" -> "💬 WhatsApp"
                      "sms" -> "✉️ SMS"
                      "created" -> "🎯 Lead Created"
                      "proposal_update" -> "📋 Proposal Saved"
                      "note_update", "note" -> "📝 Note Added"
                      else -> act.title.ifBlank { "Action Logged" }
                    }

                    // Recursively clean redundant title prefixes, timestamps, and officer tags
                    val rawDetail = act.details.trim()
                    val strippedDetail = GoogleSheetsRepository.cleanRawActionPayload(rawDetail)

                    val displayDetail = if (strippedDetail.isNotBlank() && !strippedDetail.equals(cleanTitle, ignoreCase = true)) {
                      strippedDetail
                    } else if (rawDetail.isNotBlank() && !rawDetail.equals(cleanTitle, ignoreCase = true)) {
                      rawDetail
                    } else {
                      ""
                    }

                    Surface(
                      color = bgCol,
                      shape = RoundedCornerShape(8.dp),
                      border = BorderStroke(1.dp, borderCol),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                      ) {
                        Surface(
                          color = Color.White,
                          shape = CircleShape,
                          border = BorderStroke(1.dp, borderCol),
                          modifier = Modifier.size(28.dp)
                        ) {
                          Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF1E3A8A))
                          }
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Text(
                              text = cleanTitle,
                              fontSize = 11.5.sp,
                              fontWeight = FontWeight.Bold,
                              color = Color(0xFF0F172A)
                            )
                            Text(
                              text = dateFormat.format(Date(act.timestamp)),
                              fontSize = 9.5.sp,
                              color = Color(0xFF64748B),
                              fontWeight = FontWeight.Medium
                            )
                          }
                          if (displayDetail.isNotBlank()) {
                            Text(
                              text = displayDetail,
                              fontSize = 11.sp,
                              color = Color(0xFF334155),
                              lineHeight = 15.sp
                            )
                          }
                          val officerName = act.officerName.ifBlank { lead.officerName.ifBlank { "Officer" } }
                          val officerId = if (act.officerId.isNotBlank()) " (${act.officerId})" else if (lead.officerId.isNotBlank()) " (${lead.officerId})" else ""
                          Text(
                            text = "👤 $officerName$officerId",
                            fontSize = 9.sp,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.SemiBold
                          )
                        }
                      }
                    }
                  }
                }
              }
            }

            // Tamper-proof audit notice
            Surface(
              color = Color(0xFFF1F5F9),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF475569))
                Text(
                  text = "🔒 Immutable System Record: Approach history and timestamps are permanently logged and cannot be edited or fabricated.",
                  fontSize = 9.sp,
                  color = Color(0xFF475569)
                )
              }
            }

            // Close button
            Button(
              onClick = onDismiss,
              modifier = Modifier
                .fillMaxWidth()
                .press3d(0.96f),
              shape = RoundedCornerShape(10.dp),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
              Text("Close Track Record", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {}
  )
}

@Composable
fun CustomerLeadGpsMapCard(
  lat: Double,
  lng: Double,
  customerName: String,
  productName: String,
  modifier: Modifier = Modifier,
  onOpenMaps: () -> Unit
) {
  val context = LocalContext.current
  var showHeatmap by remember { mutableStateOf(false) }
  val zoom = 15
  val n = 1 shl zoom
  val exactX = (lng + 180.0) / 360.0 * n
  val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
  val exactY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n

  val tileX = exactX.toInt()
  val tileY = exactY.toInt()

  // Pre-build 3x2 Esri ArcGIS World Street Map tile URLs (Public GIS CDN, zero API key, no restrictions)
  val tileUrls = remember(tileX, tileY, zoom) {
    listOf(
      listOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zoom/${tileY - 1}/${tileX - 1}",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zoom/${tileY - 1}/$tileX",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zoom/${tileY - 1}/${tileX + 1}"
      ),
      listOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zoom/$tileY/${tileX - 1}",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zoom/$tileY/$tileX",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zoom/$tileY/${tileX + 1}"
      )
    )
  }

  val infiniteTransition = rememberInfiniteTransition(label = "gps_radar")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.8f,
    targetValue = 1.6f,
    animationSpec = infiniteRepeatable(
      animation = tween(1800, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "pulse_scale"
  )
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.7f,
    targetValue = 0.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(1800, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "pulse_alpha"
  )

  Card(
    modifier = modifier
      .fillMaxWidth()
      .height(115.dp)
      .clip(RoundedCornerShape(12.dp))
      .clickable { onOpenMaps() },
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, Color(0xFF93C5FD)),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      // 1. Realistic Vector Map Canvas (base and instant fallback so card is never blank)
      Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Ground/Base map color (soft map paper beige-slate)
        drawRect(Color(0xFFF1F5F9))

        // Green park/nature zones
        drawRoundRect(
          color = Color(0xFFDCFCE7),
          topLeft = Offset(w * 0.05f, h * 0.08f),
          size = Size(w * 0.28f, h * 0.42f),
          cornerRadius = CornerRadius(16f, 16f)
        )
        drawRoundRect(
          color = Color(0xFFE2E8F0),
          topLeft = Offset(w * 0.65f, h * 0.52f),
          size = Size(w * 0.3f, h * 0.35f),
          cornerRadius = CornerRadius(12f, 12f)
        )

        // Waterway / Canal curve
        val waterPath = Path().apply {
          moveTo(0f, h * 0.72f)
          cubicTo(w * 0.3f, h * 0.62f, w * 0.5f, h * 0.85f, w, h * 0.75f)
          lineTo(w, h * 0.95f)
          cubicTo(w * 0.5f, h * 1.05f, w * 0.3f, h * 0.82f, 0f, h * 0.92f)
          close()
        }
        drawPath(waterPath, Color(0xFFBAE6FD))

        // Secondary street grid lines
        for (i in 1..6) {
          val yPos = h * (i / 7f)
          drawLine(
            color = Color(0xFFCBD5E1),
            start = Offset(0f, yPos),
            end = Offset(w, yPos),
            strokeWidth = 3f
          )
        }
        for (i in 1..7) {
          val xPos = w * (i / 8f)
          drawLine(
            color = Color(0xFFCBD5E1),
            start = Offset(xPos, 0f),
            end = Offset(xPos, h),
            strokeWidth = 3f
          )
        }

        // Primary Highway / Arterial Road through center
        drawLine(
          color = Color(0xFFFED7AA),
          start = Offset(0f, h * 0.46f),
          end = Offset(w, h * 0.46f),
          strokeWidth = 14f
        )
        drawLine(
          color = Color(0xFFFDE68A),
          start = Offset(w * 0.49f, 0f),
          end = Offset(w * 0.49f, h),
          strokeWidth = 12f
        )
      }

      // 2. OpenStreetMap Real Map Tile Mosaic (Rendered via Coil with User-Agent)
      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
      ) {
        tileUrls.forEach { rowTiles ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            horizontalArrangement = Arrangement.Center
          ) {
            rowTiles.forEach { url ->
              val imgReq = remember(url) {
                ImageRequest.Builder(context)
                  .data(url)
                  .crossfade(true)
                  .build()
              }
              AsyncImage(
                model = imgReq,
                contentDescription = "Map Tile",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .weight(1f)
                  .fillMaxHeight()
              )
            }
          }
        }
      }

      // 3. Translucent Top Gradient for contrast
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(34.dp)
          .background(
            Brush.verticalGradient(
              listOf(Color(0x990F172A), Color.Transparent)
            )
          )
      )

      // 4. Floating Header Info Badge (Customer & Scheme)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          color = Color(0xEEFFFFFF),
          shape = RoundedCornerShape(6.dp),
          border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
          shadowElevation = 2.dp
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              Icons.Default.Place,
              contentDescription = null,
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(11.dp)
            )
            Text(
              text = "${customerName.ifBlank { "Customer Lead" }} (${productName.ifBlank { "Scheme" }})",
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF0F172A),
              maxLines = 1
            )
          }
        }

        Surface(
          color = if (showHeatmap) Color(0xFFDC2626) else Color(0xDD0F172A),
          shape = RoundedCornerShape(6.dp),
          modifier = Modifier.clickable { showHeatmap = !showHeatmap }
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Text(
              text = if (showHeatmap) "🔥 HEATMAP: ON" else "📍 PIN: ON",
              fontSize = 8.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }
      }

      // 5. Central Location Marker / Heatmap Circle
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        if (showHeatmap) {
          // Heatmap radial gradient circle with intensity gradient (Red -> Amber -> Emerald)
          Box(
            modifier = Modifier
              .size(76.dp * pulseScale)
              .background(
                brush = Brush.radialGradient(
                  colors = listOf(
                    Color(0xE6EF4444),
                    Color(0xB3F59E0B),
                    Color(0x6610B981),
                    Color.Transparent
                  )
                ),
                shape = CircleShape
              )
          )
          Surface(
            color = Color(0xEE0F172A),
            shape = CircleShape,
            border = BorderStroke(1.dp, Color(0xFFF59E0B))
          ) {
            Text(
              text = "🔥",
              fontSize = 11.sp,
              modifier = Modifier.padding(3.dp)
            )
          }
        } else {
          // Pulsing radar ring
          Box(
            modifier = Modifier
              .size(38.dp * pulseScale)
              .background(
                color = Color(0xFFEF4444).copy(alpha = pulseAlpha),
                shape = CircleShape
              )
          )
          // Soft drop-shadow beneath pin point
          Box(
            modifier = Modifier
              .offset(y = 10.dp)
              .size(width = 12.dp, height = 4.dp)
              .background(Color(0x66000000), CircleShape)
          )
          // Red Marker Pin Icon
          Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = "Exact Pinned Location",
            tint = Color(0xFFDC2626),
            modifier = Modifier
              .size(28.dp)
              .offset(y = (-6).dp)
          )
          // White dot inside pin head
          Box(
            modifier = Modifier
              .offset(y = (-11).dp)
              .size(5.dp)
              .background(Color.White, CircleShape)
          )
        }
      }

      // 6. Bottom Banner: Coordinates & Tap to Open in Google Maps
      Surface(
        color = Color(0xEE0F172A),
        shape = RoundedCornerShape(bottomStart = 11.dp, bottomEnd = 11.dp),
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.BottomCenter)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "📍 ${String.format(Locale.US, "%.5f, %.5f", lat, lng)}",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF8FAFC)
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Icon(
              Icons.Default.OpenInNew,
              contentDescription = null,
              tint = Color(0xFF60A5FA),
              modifier = Modifier.size(11.dp)
            )
            Text(
              text = "Tap to open Google Maps",
              fontSize = 9.5.sp,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF93C5FD)
            )
          }
        }
      }
    }
  }
}

/**
 * Vertical Card Layout for Leads:
 * Folded by default into a sleek contact-card layout with:
 * - Customer Name as header
 * - Subheading containing Customer's Mobile Number & Leading Officer
 * - Expandable on tap to reveal complete lead details, history, and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerticalLeadCard(
  lead: CustomerLead,
  currency: String,
  todayIso: String,
  isExpanded: Boolean,
  onToggleExpand: () -> Unit,
  showEditPlanButton: Boolean = true,
  onStatusChange: (String) -> Unit,
  onEditNote: () -> Unit,
  onEditPlan: () -> Unit,
  onOpenFollowUpDatePicker: () -> Unit,
  onClearFollowUpDate: () -> Unit,
  onViewHistory: () -> Unit = {},
  onCall: () -> Unit,
  onWhatsApp: () -> Unit,
  onSms: () -> Unit
) {
  var statusMenuExpanded by remember { mutableStateOf(false) }

  val isDueToday = lead.followUpDate.isNotBlank() && lead.followUpDate == todayIso
  val isOverdue = lead.followUpDate.isNotBlank() && lead.followUpDate < todayIso &&
    !lead.status.equals("Lost", ignoreCase = true) && !lead.status.equals("Converted", ignoreCase = true)

  val isLiability = lead.category.equals("liability", ignoreCase = true)

  val statusColor = when (lead.status.lowercase()) {
    "converted" -> Color(0xFF047857)
    "lost" -> Color(0xFFB91C1C)
    "contacted" -> Color(0xFFD97706)
    else -> Color(0xFF1D4ED8)
  }

  val detailedAge = remember(lead.dob) { LoanCalculators.calculateDetailedAge(lead.dob) }
  val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "expand_rotation")

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = if (isExpanded) 3.dp else 1.5.dp),
    border = if (isDueToday || isOverdue) {
      BorderStroke(1.5.dp, Color(0xFFF59E0B))
    } else {
      BorderStroke(1.dp, Color(0xFFE2E8F0))
    }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      // 1. Sleek Contact Header (Always visible - Click to Expand / Fold)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { onToggleExpand() }
          .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Customer Avatar with First Initial
        Box(
          modifier = Modifier
            .size(42.dp)
            .background(
              brush = if (isLiability) {
                Brush.linearGradient(listOf(Color(0xFF0D9488), Color(0xFF0F766E)))
              } else {
                Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))
              },
              shape = CircleShape
            ),
          contentAlignment = Alignment.Center
        ) {
          val initial = lead.customerName.trim().take(1).uppercase()
          Text(
            text = if (initial.isNotBlank()) initial else "👤",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp
          )
        }

        // Customer Name & Subheading (Mobile & Leading Officer)
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = lead.customerName.ifBlank { "Unnamed Customer" },
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f, fill = false),
              overflow = TextOverflow.Ellipsis,
              maxLines = 1
            )
            // Small badge showing Loan or Deposit
            Surface(
              color = if (isLiability) Color(0xFFCCFBF1) else Color(0xFFDBEAFE),
              shape = RoundedCornerShape(4.dp)
            ) {
              Text(
                text = if (isLiability) "Deposit" else "Loan",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isLiability) Color(0xFF0F766E) else Color(0xFF1E40AF),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
              )
            }
          }

          // Subheading 1: Customer's Mobile Number
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Phone,
              contentDescription = null,
              modifier = Modifier.size(11.dp),
              tint = Color(0xFF0284C7)
            )
            Text(
              text = lead.mobile.ifBlank { "No Mobile Number" },
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Medium,
              color = Color(0xFF334155),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          // Subheading 2: Which Officer is leading / handling
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Person,
              contentDescription = null,
              modifier = Modifier.size(11.dp),
              tint = Color(0xFF64748B)
            )
            Text(
              text = "Leading: ${lead.officerName.ifBlank { "Officer" }} (${lead.officerId.ifBlank { "RO-8942" }})",
              fontSize = 10.5.sp,
              color = Color(0xFF64748B),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        // Right side: Status badge & Fold / Expand chevron toggle
        Column(
          horizontalAlignment = Alignment.End,
          verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
          Surface(
            color = statusColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(6.dp)
          ) {
            Text(
              text = lead.status.uppercase(),
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold,
              color = statusColor,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
          ) {
            Text(
              text = if (isExpanded) "Fold" else "Expand",
              fontSize = 9.5.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary
            )
            Icon(
              imageVector = Icons.Default.ExpandMore,
              contentDescription = if (isExpanded) "Collapse" else "Expand",
              modifier = Modifier
                .size(18.dp)
                .rotate(rotationAngle),
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }
      }

      // Follow-up alert badge (shown even when folded so urgent dates are visible)
      if (isDueToday) {
        Surface(
          color = Color(0xFFFDE68A),
          shape = RoundedCornerShape(6.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "⏰ Follow-up due today (${lead.followUpDate})",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF92400E),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
          )
        }
      } else if (isOverdue) {
        Surface(
          color = Color(0xFFFEE2E2),
          shape = RoundedCornerShape(6.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "⚠️ Follow-up overdue (${lead.followUpDate})",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF991B1B),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
          )
        }
      }

      // 2. Expandable Body Details (Folded by default, shown when tapped)
      AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
      ) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = Color(0xFFE2E8F0)
          )

          // Synced Status & Full Category Tag
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            val isSynced = lead.syncStatus == "synced"
            Surface(
              color = if (isSynced) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = if (isSynced) "☁️ Synced to Cloud" else "⏳ Pending Sync",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSynced) Color(0xFF166534) else Color(0xFF92400E),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }

            Surface(
              color = if (isLiability) Color(0xFFCCFBF1) else Color(0xFFEFF6FF),
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = if (isLiability) "💰 Liability / Deposit Facility" else "💼 Asset / Financing Facility",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isLiability) Color(0xFF0F766E) else Color(0xFF1D4ED8),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          // Full Line-by-Line Specifications
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
              .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            LeadFieldRow("Phone Number:", lead.mobile.ifBlank { "N/A" })
            if (!isLiability) {
              LeadFieldRow(
                "Date of Birth & Age:",
                if (lead.dob.isNotBlank()) "${lead.dob} (${detailedAge.formatShort()})" else "N/A"
              )
            }
            LeadFieldRow(if (isLiability) "Deposit Product:" else "Loan Facility:", lead.productName)
            LeadFieldRow(
              if (isLiability) "Deposit Commitment:" else "Loan Amount:",
              LoanCalculators.formatCurrency(lead.loanAmount, currency)
            )
            LeadFieldRow(
              if (isLiability) "Deposit Tenure:" else "Loan Tenure:",
              "${lead.tenureMonths} Months (${String.format(Locale.US, "%.1f", lead.tenureMonths / 12.0)} Yrs)"
            )
            if (isLiability) {
              LeadFieldRow(
                "Tax Status (WHT):",
                if (lead.isFiler) "✅ Filer (${lead.taxRate}% WHT)" else "⚠️ Non-Filer (${lead.taxRate}% WHT)"
              )
              LeadFieldRow(
                "Monthly Expected Profit:",
                LoanCalculators.formatCurrency(lead.monthlyEmi, currency)
              )
              if (lead.taxDeducted > 0L) {
                LeadFieldRow(
                  "Tax Deducted (${lead.taxRate}%):",
                  "-${LoanCalculators.formatCurrency(lead.taxDeducted, currency)}"
                )
              }
              LeadFieldRow(
                "Net Profit (بعد از ٹیکس):",
                LoanCalculators.formatCurrency(if (lead.netProfit > 0L) lead.netProfit else lead.totalProfit, currency)
              )
              LeadFieldRow(
                "Net Maturity Payout:",
                LoanCalculators.formatCurrency(lead.loanAmount + (if (lead.netProfit > 0L) lead.netProfit else lead.totalProfit), currency)
              )
            } else {
              LeadFieldRow(
                "Monthly EMI:",
                LoanCalculators.formatCurrency(lead.monthlyEmi, currency)
              )
            }
            LeadFieldRow("Officer ID:", "${lead.officerId.ifBlank { "RO-8942" }} (${lead.officerName.ifBlank { "Officer" }})")
            LeadFieldRow("Branch Name:", lead.branchName.ifBlank { "Main Branch" })
            LeadFieldRow(
              "Scheduled Follow-Up:",
              if (lead.followUpDate.isNotBlank()) "📅 ${lead.followUpDate}" else "Not Assigned"
            )
          }

          // 🗺️ GPS Captured Location & Embedded Mini-Map Section (For Officer & Supervisor Tracking)
          val (resolvedLat, resolvedLng) = remember(lead.latitude, lead.longitude, lead.locationAddress) {
            if (lead.latitude != null && lead.longitude != null && (lead.latitude != 0.0 || lead.longitude != 0.0)) {
              lead.latitude to lead.longitude
            } else if (lead.locationAddress.contains(",")) {
              val firstPart = lead.locationAddress.split("|").first().trim()
              val sub = firstPart.split(",")
              if (sub.size == 2) {
                val la = sub[0].trim().toDoubleOrNull()
                val ln = sub[1].trim().toDoubleOrNull()
                if (la != null && ln != null && (la != 0.0 || ln != 0.0)) la to ln else null to null
              } else null to null
            } else null to null
          }

          if (resolvedLat != null && resolvedLng != null) {
            val lat = resolvedLat
            val lng = resolvedLng
            val displayArea = if (lead.locationAddress.contains("|")) {
              lead.locationAddress.split("|").getOrNull(1)?.trim().orEmpty()
            } else {
              lead.locationAddress.ifBlank { "${lead.branchName} Region" }
            }
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
              shape = RoundedCornerShape(12.dp),
              border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Icon(
                      Icons.Default.LocationOn,
                      contentDescription = "Captured Location",
                      tint = Color(0xFFE11D48),
                      modifier = Modifier.size(18.dp)
                    )
                    Text(
                      text = "📍 Captured Lead Location (GPS)",
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                  }
                  Surface(
                    color = Color(0xFFDCFCE7),
                    shape = RoundedCornerShape(4.dp)
                  ) {
                    Text(
                      text = "VERIFIED GPS",
                      fontSize = 8.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF15803D),
                      modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                  }
                }

                // Coordinates and Geocoded Address
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(
                    text = "Coordinates: ${String.format(Locale.US, "%.5f, %.5f", lat, lng)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  if (displayArea.isNotBlank()) {
                    Text(
                      text = "Area: $displayArea",
                      fontSize = 10.5.sp,
                      color = Color(0xFF64748B)
                    )
                  }
                  if (lead.locationCapturedAt != null && lead.locationCapturedAt > 0) {
                    val capTimeStr = SimpleDateFormat("dd-MMM-yyyy, hh:mm a", Locale.US).format(Date(lead.locationCapturedAt))
                    Text(
                      text = "Captured at: $capTimeStr",
                      fontSize = 9.5.sp,
                      color = Color(0xFF94A3B8)
                    )
                  }
                }

                // 🗺️ Visual Map Preview Card with Live Street Tiles, Canvas Base & Pulsing Pin
                val activityContext = LocalContext.current
                val launchMapsAction: () -> Unit = {
                  try {
                    val label = Uri.encode("${lead.customerName} (${lead.productName})")
                    val gmmIntentUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                      setPackage("com.google.android.apps.maps")
                    }
                    activityContext.startActivity(mapIntent)
                  } catch (e: Exception) {
                    try {
                      val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
                      )
                      activityContext.startActivity(browserIntent)
                    } catch (_: Exception) {}
                  }
                }

                CustomerLeadGpsMapCard(
                  lat = lat,
                  lng = lng,
                  customerName = lead.customerName,
                  productName = lead.productName,
                  onOpenMaps = launchMapsAction
                )
              }
            }
          }

          // Approach & Track Record Statistics Banner
          Surface(
            color = Color(0xFFEFF6FF),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
            modifier = Modifier.fillMaxWidth()
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
                Text(
                  text = "📊 Approach & Audit History",
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF1E3A8A)
                )
                Surface(
                  color = Color(0xFFDBEAFE),
                  shape = RoundedCornerShape(4.dp)
                ) {
                  Text(
                    text = "Permanent Log",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D4ED8),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Surface(
                  color = Color.White,
                  shape = RoundedCornerShape(8.dp),
                  border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                  modifier = Modifier.weight(1f)
                ) {
                  Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                  ) {
                    Text("🎯 Approaches", fontSize = 9.5.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
                    Text(
                      text = "${lead.approachCount} Times",
                      fontSize = 13.sp,
                      fontWeight = FontWeight.ExtraBold,
                      color = Color(0xFF1E3A8A)
                    )
                    Text("کتنی بار اپروچ کیا", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
                  }
                }

                Surface(
                  color = Color.White,
                  shape = RoundedCornerShape(8.dp),
                  border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                  modifier = Modifier.weight(1f)
                ) {
                  Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                  ) {
                    Text("📝 Note Updates", fontSize = 9.5.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
                    Text(
                      text = "${lead.notesUpdateCount} Times",
                      fontSize = 13.sp,
                      fontWeight = FontWeight.ExtraBold,
                      color = Color(0xFFD97706)
                    )
                    Text("نوٹس تبدیلیاں", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
                  }
                }
              }

              // Full-width button to open the full audit timeline
              OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier
                  .fillMaxWidth()
                  .press3d(0.96f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                  containerColor = Color.White,
                  contentColor = Color(0xFF1E3A8A)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                border = BorderStroke(1.dp, Color(0xFF93C5FD))
              ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF1D4ED8))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "📜 View Customer Track Record (${lead.activities.size} Logs)",
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }

          // Edit Plan / Proposal Button
          if (showEditPlanButton) {
            OutlinedButton(
              onClick = onEditPlan,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp),
              colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isLiability) Color(0xFF0F766E) else MaterialTheme.colorScheme.primary
              ),
              contentPadding = PaddingValues(vertical = 6.dp)
            ) {
              Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (isLiability) {
                  "✏️ Edit Deposit Amount / Plan (${LoanCalculators.formatCurrency(lead.loanAmount, currency)})"
                } else {
                  "✏️ Edit Loan Amount / Plan (${LoanCalculators.formatCurrency(lead.loanAmount, currency)})"
                },
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          // Customer Note with Edit Button (Pure user input text only)
          val cleanCurrentNote = sanitizeRawNoteText(lead.notes)
          Surface(
            color = Color(0xFFFEF3C7).copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Text("📝 Note / Comment:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
              if (cleanCurrentNote.isNotBlank()) {
                Text(
                  text = cleanCurrentNote,
                  fontSize = 11.sp,
                  color = Color(0xFF451A03)
                )
              }
              OutlinedButton(
                onClick = onEditNote,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
              ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF78350F), modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  if (cleanCurrentNote.isNotBlank()) "Edit Note / Comment" else "+ Add Note / Comment",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF78350F)
                )
              }
            }
          }

          // Dedicated Follow-Up Date Button
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Button(
              onClick = onOpenFollowUpDatePicker,
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
              shape = RoundedCornerShape(8.dp),
              contentPadding = PaddingValues(vertical = 6.dp)
            ) {
              Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (lead.followUpDate.isNotBlank()) "📅 Change Follow-Up (${lead.followUpDate})" else "📅 Assign Follow-Up Date",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
              )
            }

            if (lead.followUpDate.isNotBlank()) {
              TextButton(
                onClick = onClearFollowUpDate,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 2.dp)
              ) {
                Text("✕ Remove Follow-Up Date", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
              }
            }
          }

          // Action Buttons: Row 1 (Status & Call) and Row 2 (WhatsApp & SMS)
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            // Row 1: Status Dropdown & Call Customer Button
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Status Update Dropdown
              Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                  onClick = { statusMenuExpanded = true },
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(8.dp),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = "Status: ${lead.status}",
                      fontSize = 11.sp,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                  }
                }

                DropdownMenu(
                  expanded = statusMenuExpanded,
                  onDismissRequest = { statusMenuExpanded = false }
                ) {
                  listOf("New", "Contacted", "Converted", "Lost").forEach { st ->
                    DropdownMenuItem(
                      text = { Text(st, fontWeight = if (lead.status == st) FontWeight.Bold else FontWeight.Normal) },
                      onClick = {
                        onStatusChange(st)
                        statusMenuExpanded = false
                      }
                    )
                  }
                }
              }

              // Call Customer Button
              OutlinedButton(
                onClick = onCall,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
              ) {
                Icon(Icons.Default.Phone, contentDescription = "Call", tint = Color(0xFF0284C7), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Call Customer", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
              }
            }

            // Row 2: WhatsApp & SMS Buttons
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // WhatsApp Button
              Button(
                onClick = onWhatsApp,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
              ) {
                Icon(Icons.Default.Send, contentDescription = "WhatsApp", tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("WhatsApp", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
              }

              // SMS Button
              OutlinedButton(
                onClick = onSms,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
              ) {
                Icon(Icons.Default.Email, contentDescription = "SMS", tint = Color(0xFF0284C7), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("SMS Proposal", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
              }
            }
          }

          // Quick Fold / Collapse button at the bottom of the expanded card
          TextButton(
            onClick = onToggleExpand,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 2.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.ExpandLess,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = Color(0xFF64748B)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "Fold Lead Details (سمیٹیں)",
              fontSize = 11.sp,
              color = Color(0xFF64748B),
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }
    }
  }
}

@Composable
fun LeadFieldRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, fontSize = 11.sp, color = Color(0xFF64748B), modifier = Modifier.weight(1f))
    Text(
      text = value,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1.3f),
      textAlign = androidx.compose.ui.text.style.TextAlign.End
    )
  }
}

private fun buildKbCsv(leads: List<CustomerLead>): String {
  val sb = StringBuilder("\uFEFF") // UTF-8 Byte Order Mark for seamless Microsoft Excel and Google Sheets compatibility
  val dtFormat = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US)
  val createdFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

  // CSV Column Headers
  val headers = listOf(
    "Lead ID",
    "Category (Asset/Liability)",
    "Customer Name",
    "Mobile Number",
    "Date of Birth",
    "Age",
    "Monthly Income (PKR)",
    "DBR Allowed %",
    "Facility / Product Name",
    "Loan / Deposit Amount (PKR)",
    "Tenure Months",
    "Monthly EMI / Profit (PKR)",
    "Total Profit (PKR)",
    "Processing Fee (PKR)",
    "Status",
    "Scheduled Follow-Up Date",
    "Assigned Officer",
    "Officer Staff ID",
    "Branch Name",
    "Current Notes",
    "Total Approaches Count (کتنی بار رابطہ کیا)",
    "Approach Dates & Timeline (کس کس تاریخ پر رابطہ کیا)",
    "Total Notes Updates Count (نوٹس تبدیلیاں)",
    "Notes Updates History (کیا کیا نوٹس کب کب اپڈیٹ ہوئے)",
    "Full Audit Trail Log (مکمل ٹریک ریکارڈ)",
    "Created Date & Time"
  )
  sb.append(headers.joinToString(",") { "\"$it\"" }).append("\r\n")

  leads.forEach { l ->
    // 1. Approach Dates & Timeline
    val approachActivities = l.activities.filter {
      it.actionType in listOf("follow_up", "call", "whatsapp", "sms", "created")
    }.sortedBy { it.timestamp }

    val approachTimeline = if (approachActivities.isNotEmpty()) {
      approachActivities.mapIndexed { idx, act ->
        val dt = dtFormat.format(Date(act.timestamp))
        val off = act.officerName.ifBlank { l.officerName.ifBlank { "Officer" } }
        val offId = act.officerId.ifBlank { l.officerId }
        val offInfo = if (offId.isNotBlank()) "($off, $offId)" else "($off)"
        "${idx + 1}. [$dt] ${act.title}: ${act.details} $offInfo"
      }.joinToString("\n")
    } else if (l.followUpDate.isNotBlank()) {
      "1. [Follow-up Date: ${l.followUpDate}] (${l.officerName.ifBlank { "Officer" }})"
    } else {
      "No approach history recorded"
    }

    // 2. Notes Updates History (In-cell row breaks per update in chronological sequence)
    val noteActivities = l.activities.filter { it.actionType == "note_update" }.sortedBy { it.timestamp }
    val notesHistory = if (noteActivities.isNotEmpty()) {
      noteActivities.mapIndexed { idx, act ->
        val dt = dtFormat.format(Date(act.timestamp))
        val off = act.officerName.ifBlank { l.officerName.ifBlank { "Officer" } }
        val offId = act.officerId.ifBlank { l.officerId }
        val offInfo = if (offId.isNotBlank()) "($off, $offId)" else "($off)"
        "${idx + 1}. [$dt] ${act.details} $offInfo"
      }.joinToString("\n")
    } else if (l.notes.isNotBlank()) {
      "1. [Current Note] ${l.notes}"
    } else {
      "No notes updates recorded"
    }

    // 3. Full Audit Trail (In-cell row breaks per audit event)
    val fullAudit = if (l.activities.isNotEmpty()) {
      l.activities.sortedBy { it.timestamp }.mapIndexed { idx, act ->
        val dt = dtFormat.format(Date(act.timestamp))
        val off = act.officerName.ifBlank { l.officerName.ifBlank { "Officer" } }
        val offId = act.officerId.ifBlank { l.officerId }
        val offInfo = if (offId.isNotBlank()) "($off, $offId)" else "($off)"
        "${idx + 1}. [$dt] ${act.title} - ${act.details} $offInfo"
      }.joinToString("\n")
    } else {
      "No activity logs recorded"
    }

    val cleanId = l.id.replace("\"", "\"\"")
    val cleanCategory = (if (l.category.equals("liability", ignoreCase = true)) "Liability / Deposit" else "Asset / Loan").replace("\"", "\"\"")
    val cleanName = l.customerName.replace("\"", "\"\"")
    val cleanMobile = l.mobile.replace("\"", "\"\"")
    val cleanDob = if (l.category.equals("liability", ignoreCase = true)) "N/A" else l.dob.replace("\"", "\"\"")
    val cleanAge = if (l.category.equals("liability", ignoreCase = true)) "N/A" else "${l.age}"
    val cleanProd = l.productName.replace("\"", "\"\"")
    val cleanStatus = l.status.replace("\"", "\"\"")
    val cleanFollowUp = l.followUpDate.replace("\"", "\"\"")
    val cleanOfficer = l.officerName.replace("\"", "\"\"")
    val cleanOfficerId = l.officerId.replace("\"", "\"\"")
    val cleanBranch = l.branchName.replace("\"", "\"\"")
    val cleanNotes = l.notes.replace("\"", "\"\"")
    val cleanApproachTimeline = approachTimeline.replace("\"", "\"\"")
    val cleanNotesHistory = notesHistory.replace("\"", "\"\"")
    val cleanFullAudit = fullAudit.replace("\"", "\"\"")

    val row = listOf(
      "\"$cleanId\"",
      "\"$cleanCategory\"",
      "\"$cleanName\"",
      "\"$cleanMobile\"",
      "\"$cleanDob\"",
      "\"$cleanAge\"",
      "${l.monthlyIncome}",
      "${l.dbrAllowed}",
      "\"$cleanProd\"",
      "${l.loanAmount}",
      "${l.tenureMonths}",
      "${l.monthlyEmi}",
      "${l.totalProfit}",
      "${l.processingFee}",
      "\"$cleanStatus\"",
      "\"$cleanFollowUp\"",
      "\"$cleanOfficer\"",
      "\"$cleanOfficerId\"",
      "\"$cleanBranch\"",
      "\"$cleanNotes\"",
      "${l.approachCount}",
      "\"$cleanApproachTimeline\"",
      "${l.notesUpdateCount}",
      "\"$cleanNotesHistory\"",
      "\"$cleanFullAudit\"",
      "\"${createdFormat.format(Date(l.createdAt))}\""
    )

    sb.append(row.joinToString(",")).append("\r\n")
  }

  return sb.toString()
}
