package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AmortizationRow
import com.example.model.PrepaymentSummary
import com.example.ui.LoanUiState
import com.example.ui.LoanViewModel
import com.example.ui.theme.*
import com.example.util.LoanCalculators
import com.example.util.SchedulePdfGenerator
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmortizationScreen(
  viewModel: LoanViewModel,
  uiState: LoanUiState,
  onDismiss: () -> Unit,
  onShareWhatsApp: (String, String) -> Unit = { _, _ -> },
  onShareSms: (String, String) -> Unit = { _, _ -> },
  onExportPdf: (String) -> Unit
) {
  BackHandler { onDismiss() }

  val context = LocalContext.current
  val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isCompactHeight = configuration.screenHeightDp < 640
  val isCompactWidth = configuration.screenWidthDp < 360
  val product = viewModel.getSelectedProduct()
  val tenure = uiState.selectedTenure
  val principal = viewModel.getPrincipalForTenure(tenure)
  val rate = LoanCalculators.getApplicableRate(product, principal)

  // Interactive schedule generation factoring in row-level extra payments and dual recalculation mode
  val schedulePair = remember(principal, rate, tenure, product.rateType, uiState.extraPayments, uiState.prepaymentMode) {
    LoanCalculators.generateInteractiveSchedule(
      principal = principal,
      annualRate = rate,
      tenureMonths = tenure,
      rateType = product.rateType,
      extraPayments = uiState.extraPayments,
      mode = uiState.prepaymentMode
    )
  }

  val rows = schedulePair.first
  val summary = schedulePair.second
  var selectedRowForBreakdown by remember { mutableStateOf<AmortizationRow?>(null) }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Color(0xFF0F172A),
          titleContentColor = Color.White
        ),
        navigationIcon = {
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.press3d(0.90f)
          ) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to Calculator",
              tint = Color.White
            )
          }
        },
        title = {
          Column {
            Text(
              text = "📜 KB Amortization & Repayment",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.5.sp,
              maxLines = 1
            )
            Text(
              text = "${product.name} • ${LoanCalculators.formatCurrency(principal, uiState.profile.currency)} • ${tenure}M @ $rate%",
              color = Color(0xFF94A3B8),
              fontSize = 10.5.sp,
              maxLines = 1
            )
          }
        },
        actions = {
          if (uiState.extraPayments.isNotEmpty()) {
            IconButton(
              onClick = { viewModel.clearAllExtraPayments() },
              modifier = Modifier.press3d(0.88f)
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "Reset Extras", tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
            }
          }
        }
      )
    },
    bottomBar = {
            // Pinned Dialog Footer Actions (Always visible & elevated above Android navigation bar across ALL screen ratios)
            val feeResult = remember(product, principal, uiState.profile.fedRate) {
              LoanCalculators.calculateFee(product, principal, uiState.profile.fedRate)
            }

            var includeScheduleInMessage by remember { mutableStateOf(false) }

            val summaryOnlyText = remember(
              uiState.profile.officerName,
              uiState.profile.staffId,
              uiState.profile.branchName,
              uiState.customerName,
              uiState.mobile,
              product.name,
              product.rateType,
              principal,
              rate,
              tenure,
              feeResult.totalFee,
              uiState.profile.fedRate,
              rows,
              summary,
              uiState.profile.currency
            ) {
              buildAmortizationPdfText(
                officer = uiState.profile.officerName,
                staffId = uiState.profile.staffId,
                branch = uiState.profile.branchName,
                customer = uiState.customerName,
                mobile = uiState.mobile,
                product = product.name,
                rateType = product.rateType,
                principal = principal,
                rate = rate,
                tenure = tenure,
                processingFee = feeResult.totalFee,
                fedRate = uiState.profile.fedRate,
                rows = rows,
                summary = summary,
                currency = uiState.profile.currency,
                includeSchedule = false
              )
            }

            val scheduleWithTableText = remember(
              uiState.profile.officerName,
              uiState.profile.staffId,
              uiState.profile.branchName,
              uiState.customerName,
              uiState.mobile,
              product.name,
              product.rateType,
              principal,
              rate,
              tenure,
              feeResult.totalFee,
              uiState.profile.fedRate,
              rows,
              summary,
              uiState.profile.currency
            ) {
              buildAmortizationPdfText(
                officer = uiState.profile.officerName,
                staffId = uiState.profile.staffId,
                branch = uiState.profile.branchName,
                customer = uiState.customerName,
                mobile = uiState.mobile,
                product = product.name,
                rateType = product.rateType,
                principal = principal,
                rate = rate,
                tenure = tenure,
                processingFee = feeResult.totalFee,
                fedRate = uiState.profile.fedRate,
                rows = rows,
                summary = summary,
                currency = uiState.profile.currency,
                includeSchedule = true
              )
            }

            val messageToSend = if (includeScheduleInMessage) scheduleWithTableText else summaryOnlyText

            Surface(
              color = if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC),
              tonalElevation = 6.dp,
              shadowElevation = 8.dp,
              modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = if (isCompactHeight) 6.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp)
              ) {
                // Mobile target indicator from Calculator Screen
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                  ) {
                    Icon(
                      imageVector = if (uiState.mobile.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.Info,
                      contentDescription = null,
                      tint = if (uiState.mobile.isNotBlank()) (if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)) else (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)),
                      modifier = Modifier.size(13.dp)
                    )
                    Text(
                      text = if (uiState.mobile.isNotBlank()) {
                        "Target: ${uiState.mobile} (${uiState.customerName.ifBlank { "Customer" }})"
                      } else {
                        "Direct Share: No mobile entered (picker opens)"
                      },
                      fontSize = 10.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = if (uiState.mobile.isNotBlank()) (if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)) else (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)),
                      maxLines = 1
                    )
                  }
                  if (uiState.mobile.isNotBlank()) {
                    Text(
                      text = "Auto-Filled",
                      fontSize = 9.sp,
                      color = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D),
                      fontWeight = FontWeight.Bold
                    )
                  }
                }

                // Checkbox toggle: Include Repayment Schedule in WhatsApp / SMS message
                Surface(
                  color = if (includeScheduleInMessage) (if (isDark) Color(0xFF1E3A8A).copy(alpha = 0.35f) else Color(0xFFEFF6FF)) else (if (isDark) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFF1F5F9)),
                  shape = RoundedCornerShape(8.dp),
                  border = BorderStroke(
                    1.dp,
                    if (includeScheduleInMessage) Color(0xFF3B82F6) else (if (isDark) Color(0xFF475569) else Color(0xFFE2E8F0))
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .press3d(0.98f)
                    .clickable { includeScheduleInMessage = !includeScheduleInMessage }
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 3.dp else 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Checkbox(
                      checked = includeScheduleInMessage,
                      onCheckedChange = { includeScheduleInMessage = it },
                      colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF2563EB),
                        uncheckedColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                      ),
                      modifier = Modifier.size(20.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = "Include Full Schedule in WhatsApp / SMS",
                        fontSize = if (isCompactWidth) 9.5.sp else 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (includeScheduleInMessage) (if (isDark) Color(0xFF93C5FD) else Color(0xFF1E3A8A)) else (if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E293B)),
                        maxLines = 1
                      )
                      Text(
                        text = if (includeScheduleInMessage) {
                          "Full monthly repayment schedule will be sent"
                        } else {
                          "Summary only (Name, Mobile, Amount, EMI & Fee)"
                        },
                        fontSize = 8.5.sp,
                        color = if (includeScheduleInMessage) (if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)) else (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)),
                        maxLines = 1
                      )
                    }

                    Surface(
                      color = if (includeScheduleInMessage) (if (isDark) Color(0xFF1D4ED8) else Color(0xFFDBEAFE)) else (if (isDark) Color(0xFF475569) else Color(0xFFE2E8F0)),
                      shape = RoundedCornerShape(4.dp)
                    ) {
                      Text(
                        text = if (includeScheduleInMessage) "+ Schedule" else "Summary Only",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (includeScheduleInMessage) (if (isDark) Color(0xFFDBEAFE) else Color(0xFF1E40AF)) else (if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                      )
                    }
                  }
                }

                // 4 Action Buttons arranged in two perfectly equal 50/50 horizontal rows:
                // Row 1: WhatsApp (50%) & SMS (50%)
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Button(
                    onClick = {
                      viewModel.autoSaveLead(if (includeScheduleInMessage) "Amortization (WhatsApp + Sched)" else "Amortization (WhatsApp Summary)")
                      val formattedPhone = LoanCalculators.formatForWhatsApp(uiState.mobile)
                      onShareWhatsApp(formattedPhone, messageToSend)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                      .weight(1f)
                      .height(if (isCompactHeight) 38.dp else 42.dp)
                      .press3d(0.96f)
                      .shadow(3.dp, RoundedCornerShape(10.dp))
                  ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "WhatsApp",
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White,
                      maxLines = 1
                    )
                  }

                  Button(
                    onClick = {
                      viewModel.autoSaveLead(if (includeScheduleInMessage) "Amortization (SMS + Sched)" else "Amortization (SMS Summary)")
                      onShareSms(uiState.mobile, messageToSend)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                      .weight(1f)
                      .height(if (isCompactHeight) 38.dp else 42.dp)
                      .press3d(0.96f)
                      .shadow(3.dp, RoundedCornerShape(10.dp))
                  ) {
                    Icon(Icons.Default.Email, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "SMS",
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White,
                      maxLines = 1
                    )
                  }
                }

                // Row 2: Print/Share Schedule (PDF) (50%) & Close (50%)
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Button(
                    onClick = {
                      viewModel.autoSaveLead("Amortization (PDF)")
                      SchedulePdfGenerator.generateAndShareSchedulePdf(
                        context = context,
                        uiState = uiState,
                        rows = rows,
                        summary = summary,
                        actualPrincipal = principal
                      )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                      .weight(1f)
                      .height(if (isCompactHeight) 38.dp else 42.dp)
                      .press3d(0.96f)
                      .shadow(3.dp, RoundedCornerShape(10.dp))
                  ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "Print / Share Schedule",
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White,
                      maxLines = 1
                    )
                  }

                  Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                      .weight(1f)
                      .height(if (isCompactHeight) 38.dp else 42.dp)
                      .press3d(0.96f)
                      .shadow(3.dp, RoundedCornerShape(10.dp))
                  ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "Close",
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White,
                      maxLines = 1
                    )
                  }
                }
              }
            }
          }
        ) { innerPadding ->
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(innerPadding)
          ) {

            // Dual Extra Payment Recalculation Mode Selector (Option A vs Option B)
            Surface(
              color = Color(0xFF1E293B),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = if (isCompactHeight) 4.dp else 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = "RECALCULATION STRATEGY ON EXTRA PAYMENT:",
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFFCD34D)
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  val isModeEmi = uiState.prepaymentMode == "reduce_emi"
                  Button(
                    onClick = { viewModel.updatePrepaymentMode("reduce_emi") },
                    colors = ButtonDefaults.buttonColors(
                      containerColor = if (isModeEmi) Color(0xFF2563EB) else Color(0xFF334155),
                      contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                  ) {
                    Text(
                      text = "Option A: Reduce EMI",
                      fontSize = 10.sp,
                      fontWeight = if (isModeEmi) FontWeight.ExtraBold else FontWeight.Normal,
                      maxLines = 1
                    )
                  }

                  val isModeTenure = uiState.prepaymentMode == "reduce_tenure"
                  Button(
                    onClick = { viewModel.updatePrepaymentMode("reduce_tenure") },
                    colors = ButtonDefaults.buttonColors(
                      containerColor = if (isModeTenure) Color(0xFF059669) else Color(0xFF334155),
                      contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                  ) {
                    Text(
                      text = "Option B: Reduce Tenure",
                      fontSize = 10.sp,
                      fontWeight = if (isModeTenure) FontWeight.ExtraBold else FontWeight.Normal,
                      maxLines = 1
                    )
                  }
                }
              }
            }

            // Live Interest Savings Banner (Displays dynamically when extra payments are added)
            if (summary.totalExtraPaid > 0L) {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                shape = RoundedCornerShape(10.dp)
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column {
                    Text("TOTAL PROFIT SAVED:", fontSize = 9.sp, color = Color(0xFF065F46), fontWeight = FontWeight.Bold)
                    Text(
                      text = LoanCalculators.formatCurrency(summary.totalProfitSaved, uiState.profile.currency),
                      fontSize = 14.sp,
                      fontWeight = FontWeight.Black,
                      color = Color(0xFF047857)
                    )
                  }
                  Column(horizontalAlignment = Alignment.End) {
                    Text("Extra Paid: ${LoanCalculators.formatCurrency(summary.totalExtraPaid)}", fontSize = 9.5.sp, color = Color(0xFF047857))
                    Text(
                      text = if (uiState.prepaymentMode == "reduce_emi") {
                        "Revised EMI: ${LoanCalculators.formatCurrency(summary.revisedMonthlyEmi)}/mo"
                      } else {
                        "Payoff: Mo ${summary.revisedTenure} (Saved ${summary.monthsSaved}M)"
                      },
                      fontSize = 9.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF065F46)
                    )
                  }
                }
              }
            }

            // Summary KPI Strip
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                .padding(horizontal = 10.dp, vertical = 5.dp),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text("Original Profit", fontSize = 9.sp, color = if (isDark) Color(0xFF94A3B8) else Color.Gray)
                Text(
                  LoanCalculators.formatCurrency(summary.originalTotalProfit, uiState.profile.currency),
                  fontWeight = FontWeight.Bold,
                  fontSize = 10.sp,
                  color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                )
              }
              Column {
                Text("Revised Profit", fontSize = 9.sp, color = if (isDark) Color(0xFF94A3B8) else Color.Gray)
                Text(
                  LoanCalculators.formatCurrency(summary.revisedTotalProfit, uiState.profile.currency),
                  fontWeight = FontWeight.Bold,
                  fontSize = 10.sp,
                  color = Color(0xFF38BDF8)
                )
              }
              Column(horizontalAlignment = Alignment.End) {
                Text("Customer", fontSize = 9.sp, color = if (isDark) Color(0xFF94A3B8) else Color.Gray)
                Text(
                  uiState.customerName.ifBlank { "Walk-in" },
                  fontWeight = FontWeight.SemiBold,
                  fontSize = 10.sp,
                  color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                  maxLines = 1
                )
              }
            }

            if (!isCompactHeight) {
              Text(
                text = "💡 Enter extra principal in any month's 'Extra (Rs)' box to simulate Option A or Option B.",
                fontSize = 9.sp,
                color = Color(0xFF475569),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                maxLines = 1
              )
            }

            // Interactive Row Hint
            Surface(
              color = Color(0xFF0284C7).copy(alpha = 0.08f),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                  text = "💡 کسی بھی قسط پر کلک کر کے کل ادا شدہ پرافٹ اور بقایا رقم دیکھیں (Tap row for breakdown)",
                  fontSize = 9.5.sp,
                  color = Color(0xFF0369A1),
                  fontWeight = FontWeight.Medium
                )
              }
            }

            // Table Header
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text("Mo", fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.width(22.dp))
                Text("Opening", fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("Princ", fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.weight(0.9f))
                Text("Profit", fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.weight(0.9f))
                Text("EMI", fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("Extra (Rs)", fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color(0xFFB45309), modifier = Modifier.weight(1.25f))
                Text("Closing", fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.weight(1f))
              }
            }

            // Scrollable Schedule Rows with Row-Level Input (Takes remaining flexible space inside Scaffold)
            LazyColumn(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
            ) {
              items(rows, key = { it.month }) { row ->
                var extraInputText by remember(row.month, uiState.extraPayments[row.month]) {
                  mutableStateOf(uiState.extraPayments[row.month]?.let { if (it > 0) it.toString() else "" } ?: "")
                }

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { selectedRowForBreakdown = row }
                    .padding(vertical = 3.dp, horizontal = 2.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(row.month.toString(), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                  Text(formatCompact(row.openingBalance), fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
                  Text(formatCompact(row.principal), fontSize = 9.sp, color = Color(0xFF2E7D32), modifier = Modifier.weight(0.9f), maxLines = 1)
                  Text(formatCompact(row.profit), fontSize = 9.sp, color = Color(0xFFE65100), modifier = Modifier.weight(0.9f), maxLines = 1)
                  Text(formatCompact(row.emi), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1)

                  Box(
                    modifier = Modifier
                      .weight(1.25f)
                      .height(28.dp)
                      .padding(horizontal = 2.dp)
                      .background(Color(0xFFFEF3C7), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(6.dp))
                      .padding(horizontal = 5.dp, vertical = 3.dp),
                    contentAlignment = Alignment.CenterStart
                  ) {
                    if (extraInputText.isEmpty()) {
                      Text("0", fontSize = 9.5.sp, color = Color(0xFF94A3B8))
                    }
                    BasicTextField(
                      value = extraInputText,
                      onValueChange = { newVal ->
                        extraInputText = newVal
                        val num = newVal.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        viewModel.updateExtraPayment(row.month, num)
                      },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                      textStyle = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                      ),
                      cursorBrush = SolidColor(Color(0xFF0F172A)),
                      singleLine = true,
                      modifier = Modifier.fillMaxWidth()
                    )
                  }

                  Text(formatCompact(row.closingBalance), fontSize = 9.sp, color = if (isDark) Color(0xFF94A3B8) else Color.Gray, modifier = Modifier.weight(1f), maxLines = 1)
                }
                HorizontalDivider(color = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9))
              }
            }
          }
        }

  if (selectedRowForBreakdown != null) {
    CumulativeInstallmentBreakdownDialog(
      row = selectedRowForBreakdown!!,
      allRows = rows,
      principal = principal,
      tenure = tenure,
      isDark = isDark,
      onDismiss = { selectedRowForBreakdown = null }
    )
  }
}

@Composable
fun CumulativeInstallmentBreakdownDialog(
  row: AmortizationRow,
  allRows: List<AmortizationRow>,
  principal: Long,
  tenure: Int,
  isDark: Boolean,
  onDismiss: () -> Unit
) {
  val month = row.month
  val rowsUpToM = allRows.filter { it.month <= month }
  val cumPrincipal = rowsUpToM.sumOf { it.principal }
  val cumProfit = rowsUpToM.sumOf { it.profit }
  val cumExtra = rowsUpToM.sumOf { it.extraPayment }
  val cumTotalPaid = rowsUpToM.sumOf { it.emi + it.extraPayment }

  val remainingPrincipal = row.closingBalance
  val totalScheduleProfit = allRows.sumOf { it.profit }
  val remainingProfit = (totalScheduleProfit - cumProfit).coerceAtLeast(0L)
  val remainingTotalLiability = remainingPrincipal + remainingProfit
  val remainingMonths = (tenure - month).coerceAtLeast(0)
  val pctRepaid = if (principal > 0L) ((cumPrincipal.toDouble() / principal.toDouble()) * 100).coerceIn(0.0, 100.0) else 0.0
  val pctTenure = if (tenure > 0) ((month.toDouble() / tenure.toDouble()) * 100).coerceIn(0.0, 100.0) else 0.0

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          shape = CircleShape,
          color = Color(0xFF16A34A).copy(alpha = 0.15f),
          modifier = Modifier.size(36.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("$month", color = Color(0xFF16A34A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
          }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text("Month $month Installment Breakdown", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          Text("قسط نمبر $month تک کی مجموعی اور بقایا تفصیل", fontSize = 11.sp, color = Color(0xFF64748B))
        }
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Progress Bar & Percentage
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC),
          border = BorderStroke(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Principal Repaid so far", fontSize = 11.sp, color = Color(0xFF64748B))
              Text("${String.format(Locale.US, "%.1f", pctRepaid)}% Cleared", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
            }
            LinearProgressIndicator(
              progress = { (pctRepaid / 100.0).toFloat() },
              modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
              color = Color(0xFF16A34A),
              trackColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Tenure Completed", fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text("$month of $tenure Months (${String.format(Locale.US, "%.0f", pctTenure)}%)", fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
          }
        }

        // Section 1: Paid So Far
        Card(
          shape = RoundedCornerShape(10.dp),
          colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF14532D).copy(alpha = 0.25f) else Color(0xFFF0FDF4)
          ),
          border = BorderStroke(1.dp, Color(0xFF86EFAC))
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
              "✅ Paid So Far (Month 1 to $month) • اب تک ادا شدہ رقم",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF166534)
            )
            HorizontalDivider(color = Color(0xFFBBF7D0))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Principal Repaid (پرنسپل):", fontSize = 11.sp, color = Color(0xFF334155))
              Text("PKR ${formatCompact(cumPrincipal)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Profit Paid (پرافٹ):", fontSize = 11.sp, color = Color(0xFF334155))
              Text("PKR ${formatCompact(cumProfit)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
            }
            if (cumExtra > 0L) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Extra Prepayments (اضافی ادائیگی):", fontSize = 11.sp, color = Color(0xFF334155))
                Text("PKR ${formatCompact(cumExtra)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
              }
            }
            HorizontalDivider(color = Color(0xFFBBF7D0))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Total Amount Paid (کل رقم):", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
              Text("PKR ${formatCompact(cumTotalPaid)}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF15803D))
            }
          }
        }

        // Section 2: Remaining Liability
        Card(
          shape = RoundedCornerShape(10.dp),
          colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF7F1D1D).copy(alpha = 0.25f) else Color(0xFFFEF2F2)
          ),
          border = BorderStroke(1.dp, Color(0xFFFECACA))
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
              "⚠️ Remaining Balance (Month ${month + 1} to $tenure) • کل بقایا واجب الادا",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF991B1B)
            )
            HorizontalDivider(color = Color(0xFFFED7AA))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Remaining Principal (بقایا پرنسپل):", fontSize = 11.sp, color = Color(0xFF334155))
              Text("PKR ${formatCompact(remainingPrincipal)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB91C1C))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Remaining Profit (بقایا پرافٹ):", fontSize = 11.sp, color = Color(0xFF334155))
              Text("PKR ${formatCompact(remainingProfit)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC2410C))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Remaining Months (بقایا اقساط):", fontSize = 11.sp, color = Color(0xFF334155))
              Text("$remainingMonths Months", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF475569))
            }
            HorizontalDivider(color = Color(0xFFFED7AA))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Total Remaining Liability (کل بقایا واجب الادا):", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
              Text("PKR ${formatCompact(remainingTotalLiability)}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFB91C1C))
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
      ) {
        Text("Close (بند کریں)", fontSize = 12.sp)
      }
    }
  )
}

@Composable
fun AmortizationDialog(
  viewModel: LoanViewModel,
  uiState: LoanUiState,
  onDismiss: () -> Unit,
  onShareWhatsApp: (String, String) -> Unit = { _, _ -> },
  onShareSms: (String, String) -> Unit = { _, _ -> },
  onExportPdf: (String) -> Unit
) {
  AmortizationScreen(
    viewModel = viewModel,
    uiState = uiState,
    onDismiss = onDismiss,
    onShareWhatsApp = onShareWhatsApp,
    onShareSms = onShareSms,
    onExportPdf = onExportPdf
  )
}

fun formatCompact(amt: Long): String {
  return String.format(Locale.US, "%,d", amt)
}

/**
 * Pads right-aligned text with standard spaces for monospace code blocks
 * so WhatsApp never collapses spaces or misaligns columns even after clicking "Read more".
 */
private fun padTabulated(text: String, targetLength: Int): String {
  return text.padStart(targetLength, ' ')
}

fun buildAmortizationPdfText(
  officer: String,
  staffId: String,
  branch: String,
  customer: String,
  mobile: String,
  product: String,
  rateType: String,
  principal: Long,
  rate: Double,
  tenure: Int,
  processingFee: Long,
  fedRate: Double,
  rows: List<AmortizationRow>,
  summary: PrepaymentSummary,
  currency: String,
  includeSchedule: Boolean = true
): String {
  val sb = StringBuilder()
  val durationLabel = when {
    tenure == 1 -> "1 Month"
    tenure % 12 == 0 -> "$tenure Months (${tenure / 12} ${if (tenure / 12 == 1) "Year" else "Years"})"
    else -> "$tenure Months (${String.format(Locale.US, "%.1f", tenure / 12.0)} Years)"
  }
  val effectiveMonthlyEmi = if (summary.totalExtraPaid > 0L && summary.mode == "reduce_emi") {
    summary.revisedMonthlyEmi
  } else {
    summary.originalMonthlyEmi
  }

  val headerTitle = if (includeSchedule) "🏛️ *KB LOAN AMORTIZATION SCHEDULE*" else "🏛️ *KB LOAN PROPOSAL & SUMMARY*"
  sb.appendLine(headerTitle)
  sb.appendLine("────────────────────")
  sb.appendLine("👤 *Customer Name:* ${customer.ifBlank { "Valued Client" }}")
  sb.appendLine("📱 *Mobile Number:* ${mobile.ifBlank { "N/A" }}")
  sb.appendLine("💰 *Loan Amount:* ${LoanCalculators.formatCurrency(principal, currency)}")
  sb.appendLine("📅 *Loan Duration:* $durationLabel")
  sb.appendLine("💵 *Monthly Installment:* ${LoanCalculators.formatCurrency(effectiveMonthlyEmi, currency)}")
  sb.appendLine("🧾 *Processing Fee:* ${LoanCalculators.formatCurrency(processingFee, currency)}")
  if (summary.totalExtraPaid > 0L) {
    sb.appendLine("⚡ *Extra Principal Paid:* ${LoanCalculators.formatCurrency(summary.totalExtraPaid, currency)}")
    sb.appendLine("✨ *Total Profit Saved:* ${LoanCalculators.formatCurrency(summary.totalProfitSaved, currency)}")
  }

  if (includeSchedule) {
    sb.appendLine("────────────────────")
    sb.appendLine("📅 INSTALLMENT SCHEDULE ($currency)")

    val moWidth = 3
    val emiWidth = maxOf(8, rows.maxOfOrNull { formatCompact(it.emi).length } ?: 8)
    val profWidth = maxOf(7, rows.maxOfOrNull { formatCompact(it.profit).length } ?: 7)
    val balWidth = maxOf(9, rows.maxOfOrNull { formatCompact(it.closingBalance).length } ?: 9)

    val hdrMo = "Mo".padEnd(moWidth, ' ')
    val hdrEmi = padTabulated("EMI", emiWidth)
    val hdrProf = padTabulated("Profit", profWidth)
    val hdrBal = padTabulated("Balance", balWidth)
    val dividerLine = "─".repeat(moWidth + emiWidth + profWidth + balWidth + 9)

    sb.appendLine("$hdrMo │ $hdrEmi │ $hdrProf │ $hdrBal")
    sb.appendLine(dividerLine)

    rows.forEach { r ->
      val moStr = r.month.toString().padStart(2, '0').padEnd(moWidth, ' ')
      val emiStr = padTabulated(formatCompact(r.emi), emiWidth)
      val profStr = padTabulated(formatCompact(r.profit), profWidth)
      val closeStr = padTabulated(formatCompact(r.closingBalance), balWidth)
      sb.appendLine("$moStr │ $emiStr │ $profStr │ $closeStr")
      if (r.extraPayment > 0L) {
        sb.appendLine("   ↳ Extra Paid: $currency ${formatCompact(r.extraPayment)}")
      }
    }
  }

  sb.appendLine("────────────────────")
  sb.appendLine("🏦 *Loan Officer:* ${officer.ifBlank { "Loan Officer" }}${if (staffId.isNotBlank()) " ($staffId)" else ""}")
  sb.append("📍 *Branch:* ${branch.ifBlank { "Main Branch" }}")

  return sb.toString().trimEnd()
}
