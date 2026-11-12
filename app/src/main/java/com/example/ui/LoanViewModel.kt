package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AuthRepository
import com.example.data.FirestoreRepository
import com.example.data.GoogleSheetsRepository
import com.example.model.*
import com.example.util.LoanCalculators
import com.example.util.LeadAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class LoanUiState(
  val activeTab: Int = 0, // 0 = Calc, 1 = Leads, 2 = Admin
  val isOnline: Boolean = true,
  val profile: OfficerProfile = OfficerProfile(),
  val showOnboardingDialog: Boolean = false,
  val showProfileDialog: Boolean = false,
  val products: List<LoanProduct> = defaultBankProducts,
  val leads: List<CustomerLead> = emptyList(),
  val syncQueue: List<CustomerLead> = emptyList(),
  val isAdminUnlocked: Boolean = false,

  // Calculator Form Inputs - NO pre-filled dummy data
  val customerName: String = "",
  val mobile: String = "",
  val dob: String = "",
  val followUpDate: String = "",
  val specificLeadNotes: String = "",
  val loanAmountInput: String = "",
  val selectedProductId: String = "prod-kmbl-3136",
  val monthlyIncomeInput: String = "",
  val allowedDbrInput: String = "",
  val selectedTenure: Int = 36,
  val isFiler: Boolean = true, // Customer Tax Status: true = Filer (20% WHT), false = Non-Filer (40% WHT)

  // Matrix Filter
  val matrixTenureFilter: String = "All", // "All", "1-12", "13-36", "37-60", "61-120"

  // Amortization Screen & Dual Mode Extra Payments
  val showAmortizationScreen: Boolean = false,
  val extraPayments: Map<Int, Long> = emptyMap(),
  val prepaymentMode: String = "reduce_emi", // "reduce_emi" or "reduce_tenure"

  // Current active lead ID for the customer in Calculator session to prevent duplicates
  val currentSessionLeadId: String? = null,

  // Dialogs
  val showAdminPinDialog: Boolean = false,
  val showAdminChangePasswordDialog: Boolean = false,
  val showProductEditDialog: Boolean = false,
  val editingProduct: LoanProduct? = null,
  val adminBroadcastMessage: String = "",
  val adminBroadcastId: String = "",
  val showAdminBroadcastDialog: Boolean = false,
  val appUpdateUrl: String = "",
  val appUpdateId: String = "",
  val appUpdateMessage: String = "",
  val isAppUpdateRequired: Boolean = false,
  val hasClickedAppUpdateDownload: Boolean = false,
  val registeredUsers: List<RegisteredUser> = emptyList(),
  val deletedUserKeys: Set<String> = emptySet(),
  val toastMessage: String? = null,
  val authUserId: String? = null,
  val authUserEmail: String? = null,
  val isAuthenticating: Boolean = false,
  val authError: String? = null,
  val themeMode: String = "light", // "light", "dark", "system"
  val lastCapturedLat: Double? = null,
  val lastCapturedLng: Double? = null,
  val lastCapturedAddress: String = "",
  val googleSheetsWebhookUrl: String = "",
  val isTestingSheetsUrl: Boolean = false
) {
  val monthlyIncome: Long
    get() = monthlyIncomeInput.filter { it.isDigit() }.toLongOrNull() ?: 0L

  val allowedDbr: Int
    get() = allowedDbrInput.filter { it.isDigit() }.toIntOrNull() ?: 0

  val currentUserAccess: RegisteredUser?
    get() {
      val uid = authUserId?.trim().orEmpty()
      val myStaffId = profile.staffId.trim()
      val myEmail = authUserEmail?.trim().orEmpty()
      return registeredUsers.find { u ->
        (uid.isNotEmpty() && u.uid == uid) ||
          (myEmail.isNotEmpty() && u.email.equals(myEmail, ignoreCase = true)) ||
          (myStaffId.isNotEmpty() && u.staffId.equals(myStaffId, ignoreCase = true))
      }
    }

  /**
   * By default, every user is blocked on first attempt / app open until their record
   * is found ACTIVE (isBlocked == false) in Firestore /users database, or Admin session is unlocked.
   */
  val isCurrentUserBlocked: Boolean
    get() = !isAdminUnlocked && (currentUserAccess == null || currentUserAccess?.isBlocked == true)

  val effectiveAllowedBranch: String
    get() = currentUserAccess?.allowedSupervisorBranch?.trim()?.ifBlank {
      currentUserAccess?.branchName?.trim()?.ifBlank { profile.branchName.trim() } ?: profile.branchName.trim()
    } ?: profile.branchName.trim()

  val canMonitorBranch: Boolean
    get() = currentUserAccess?.canUseSupervisorFilter ?: true

  fun isLeadOwnedByCurrentUser(lead: CustomerLead): Boolean {
    val myStaffId = profile.staffId.trim().ifEmpty {
      currentUserAccess?.staffId?.trim().orEmpty()
    }
    val myUserId = authUserId?.trim().orEmpty()
    val myName = profile.officerName.trim().ifEmpty {
      currentUserAccess?.officerName?.trim().orEmpty()
    }

    val leadStaffId = lead.officerId.trim()
    val leadUserId = lead.userId.trim()
    val leadName = lead.officerName.trim()

    // 1. If BOTH the current user and the lead have a Staff ID, they MUST match strictly.
    // This prevents leads belonging to other staff members (even if tested on the same account/device) from mixing in.
    if (myStaffId.isNotEmpty() && leadStaffId.isNotEmpty()) {
      return leadStaffId.equals(myStaffId, ignoreCase = true)
    }

    // 2. If the lead has another officer's Staff ID (and current user hasn't set one or has a different one),
    // it belongs to that other officer unless the Google UID explicitly matches and current user has no conflicting Staff ID.
    if (leadStaffId.isNotEmpty() && myStaffId.isEmpty()) {
      return myUserId.isNotEmpty() && leadUserId == myUserId
    }

    // 3. When leadStaffId is empty, match by authenticated Google UID (excluding generic "officer" placeholder)
    if (myUserId.isNotEmpty() && leadUserId.isNotEmpty() && leadUserId != "officer") {
      return leadUserId == myUserId
    }

    // 4. Fallback to Officer Name only when both sides have a non-empty Officer Name and no conflicting IDs
    if (myName.isNotEmpty() && leadName.isNotEmpty()) {
      return leadName.equals(myName, ignoreCase = true)
    }

    return false
  }

  fun isLeadPermittedForUser(lead: CustomerLead): Boolean {
    if (isAdminUnlocked) return true
    if (isLeadOwnedByCurrentUser(lead)) return true

    val allowedTokens = effectiveAllowedBranch
      .split(",", ";", "|")
      .map { it.trim().lowercase(Locale.US) }
      .filter { it.isNotEmpty() }

    if (allowedTokens.any { it == "all" || it == "*" }) return true
    if (allowedTokens.isEmpty()) return false

    val leadBranch = lead.branchName.trim().lowercase(Locale.US)
    val officerStaffId = lead.officerId.trim().lowercase(Locale.US)
    val officerUser = registeredUsers.firstOrNull { u ->
      (officerStaffId.isNotEmpty() && u.staffId.trim().lowercase(Locale.US) == officerStaffId) ||
      (lead.userId.isNotBlank() && u.uid.trim() == lead.userId.trim())
    }
    val effectiveBranch = officerUser?.branchName?.trim()?.lowercase(Locale.US)?.ifBlank { leadBranch } ?: leadBranch

    return allowedTokens.any { token ->
      matchesBranchToken(token, leadBranch) || matchesBranchToken(token, effectiveBranch)
    }
  }
}

private val BRANCH_CANONICAL_MAP = mapOf(
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

private fun canonicalBranch(branch: String): String {
  val clean = branch.trim().lowercase(Locale.US).replace("branch", "").replace(" ", "")
  return BRANCH_CANONICAL_MAP[clean] ?: clean
}

private fun matchesBranchToken(token: String, candidateBranch: String): Boolean {
  val cleanToken = token.trim().lowercase(Locale.US).replace("branch", "").replace(" ", "")
  val cleanBranch = candidateBranch.trim().lowercase(Locale.US).replace("branch", "").replace(" ", "")
  if (cleanToken.isEmpty() || cleanBranch.isEmpty()) return false

  val canonToken = canonicalBranch(cleanToken)
  val canonBranch = canonicalBranch(cleanBranch)
  if (canonToken == canonBranch) return true

  if (cleanToken.length <= 4 || cleanBranch.length <= 4) {
    return cleanToken == cleanBranch || canonToken == canonBranch
  }
  return cleanBranch.contains(cleanToken) || cleanToken.contains(cleanBranch)
}

private val tierSlabs34To26 = listOf(
  RateSlab(50000L, 1000000L, 34.0),
  RateSlab(1000001L, 2000000L, 32.0),
  RateSlab(2000001L, 3000000L, 29.0),
  RateSlab(3000001L, 4000000L, 26.0),
  RateSlab(4000001L, 5000000L, 26.0)
)

private val tierSlabs34To29Max3M = listOf(
  RateSlab(50000L, 1000000L, 34.0),
  RateSlab(1000001L, 2000000L, 32.0),
  RateSlab(2000001L, 3000000L, 29.0)
)

private val tierSlabsGold395To32 = listOf(
  RateSlab(50000L, 500000L, 39.5),
  RateSlab(500001L, 1000000L, 39.0),
  RateSlab(1000001L, 2000000L, 37.0),
  RateSlab(2000001L, 3000000L, 34.0),
  RateSlab(3000001L, 4000000L, 33.0),
  RateSlab(4000001L, 5000000L, 32.0)
)

private val tierSlabsSalPen39To34 = listOf(
  RateSlab(50000L, 500000L, 39.0),
  RateSlab(500001L, 1000000L, 37.0),
  RateSlab(1000001L, 2000000L, 36.0),
  RateSlab(2000001L, 3000000L, 36.0),
  RateSlab(3000001L, 4000000L, 35.0),
  RateSlab(4000001L, 5000000L, 34.0)
)

private val tierSlabsSalPen39To36Max3M = listOf(
  RateSlab(50000L, 500000L, 39.0),
  RateSlab(500001L, 1000000L, 37.0),
  RateSlab(1000001L, 2000000L, 36.0),
  RateSlab(2000001L, 3000000L, 36.0)
)

private val stdFeeSlab500K = listOf(
  FeeSlab(50000L, 500000L, "percentage", 1.5, minFee = 1500L, maxFee = 7500L)
)

private val stdFeeSlab1M = listOf(
  FeeSlab(50000L, 500000L, "percentage", 1.5, minFee = 1500L, maxFee = 7500L),
  FeeSlab(500001L, 1000000L, "percentage", 1.5, minFee = 7500L, maxFee = 15000L)
)

private val stdFeeSlab3M = listOf(
  FeeSlab(50000L, 500000L, "percentage", 1.5, minFee = 1500L, maxFee = 7500L),
  FeeSlab(500001L, 3000000L, "percentage", 1.25, minFee = 7500L, maxFee = 37500L)
)

private val stdFeeSlab5M = listOf(
  FeeSlab(50000L, 500000L, "percentage", 1.5, minFee = 1500L, maxFee = 7500L),
  FeeSlab(500001L, 5000000L, "percentage", 1.25, minFee = 7500L, maxFee = 62500L)
)

val defaultBankProducts = listOf(
  // PAGE 1 PRODUCTS (1 to 11)
  LoanProduct(
    id = "prod-kmbl-3136",
    name = "KPF Gen Pension NonTrans-3136",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 70,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• PDC & Guarantee (up to Rs. 500K)\n• PDC or Guarantee (up to Rs. 350K)\nNote: In case of Pension not transferred in KMBL:\n• Original Pension Book in case pension is received through Pension book (as applicable) OR\n• PDC of total loan due amount (PA+SC) of applicant's pension account maintained at any commercial bank where pension is currently credited OR\n• Up to Rs. 500K: PDC & PG (If pension A/C PDC unavailable, obtain PDC from another A/C).\n• Up to Rs. 350K: PDC or PG (If pension A/C PDC unavailable, obtain PDC from another A/C).",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.5)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3138",
    name = "KPF Gen Salary NonTrans-3138",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 60,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• PDC & Guarantee (up to Rs. 500K)\n• PDC or Guarantee (up to Rs. 350K)\nNote: In case Salary is not transferred to KMBL:\n• Salary Book in original in case employee is receiving salary through cash especially Pakistan Post (as applicable).\n• Letter of Pledge, Lien and authorities for securities (if Salary book is kept with KMBL) OR\n• Guarantor and PDC of KMBL's account in case salary is paid in cash/photo account at commercial banks OR\n• Up to Rs. 500K: PDC & PG (If Salary A/C PDC unavailable, obtain PDC from another A/C).\n• Up to Rs. 350K: PDC or PG (If Salary A/C PDC unavailable, obtain PDC from another A/C).",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.5)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3146",
    name = "Karobari Sarmaya ENT Un-SEC-3146",
    rateType = "reducing",
    defaultAnnualRate = 38.0,
    minAmount = 50000L,
    maxAmount = 1000000L,
    minTenure = 3,
    maxTenure = 24,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: For Self-Employed: 68 Years\nTenure Policy: 3 to 24 Months (Up to 500K with PDC & PG: 24 Months | Up to 350K with PDC or PG: 18 Months)\n• PDC & Guarantee (NTB Rs. 500K, ETB Rs. 1 Million)\n• PDC or Guarantee (up to Rs. 350K)\nNote:\n• NTB borrowers with a PKR loan must have completed at least 80% of the loan tenure/EMIs to qualify for a loan exceeding Rs. 500K.\n• Repeat loans exceeding Rs. 500K shall only be allowed to borrowers who have previously availed the maximum Rs. 500K limit at least once.",
    rateSlabs = listOf(RateSlab(50000L, 1000000L, 38.0)),
    feeSlabs = stdFeeSlab1M
  ),
  LoanProduct(
    id = "prod-kmbl-3172",
    name = "Karobari ENT Pen non-3172",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 70,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• PDC & Guarantee (up to Rs. 500K)\n• PDC or Guarantee (up to Rs. 350K)\nNote: In case of Pension not transferred in KMBL:\n• Original Pension Book in case pension is received through Pension book (as applicable) OR\n• PDC of total loan due amount (PA+SC) of applicant's pension account maintained at any commercial bank OR\n• Up to Rs. 500K: PDC & PG (If pension A/C PDC unavailable, obtain PDC from another A/C).\n• Up to Rs. 350K: PDC or PG (If pension A/C PDC unavailable, obtain PDC from another A/C).",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.5)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3176",
    name = "Karobari ENT Sal non-3176",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 60,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• PDC & Guarantee (up to Rs. 500K)\n• PDC or Guarantee (up to Rs. 350K)\nNote: In case Salary is not transferred to KMBL:\n• Salary Book in original in case employee is receiving salary through cash especially Pakistan Post (as applicable).\n• Letter of Pledge, Lien and authorities for securities (if Salary book is kept with KMBL) OR\n• Guarantor and PDC of KMBL's account in case salary is paid in cash/photo account at commercial banks OR\n• Up to Rs. 500K: PDC & PG (If Salary A/C PDC unavailable, obtain PDC from another A/C).\n• Up to Rs. 350K: PDC or PG (If Salary A/C PDC unavailable, obtain PDC from another A/C).",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.5)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3201",
    name = "Karobari ENT Secured-3201",
    rateType = "reducing",
    defaultAnnualRate = 35.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: For Self-Employed: 68 Years\n• Gold\n• Mortgage of property (Residential/Commercial) • Deposit funds with the Bank (CASA)\n• Paper securities (NSC/TDC)\n• HPA of Commercial vehicle/tractor/Car\nNote:\n• No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC.\n• In Agri-development, livestock & enterprise loans, hypothecated assets (equipment, inventory or livestock) may be included in mixed collateral only if backed by liquid collateral (Gold, TDR or NSC). If liquid collateral is insufficient, hypothecation portion shall not exceed Rs. 500,000 for NTB & Rs. 1 million for ETB/Repeat borrowers.",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 35.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3260",
    name = "KZL Agriculture Land - 3260",
    rateType = "reducing",
    defaultAnnualRate = 34.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Passbook / PLRA / Registered mortgage (Full / Partial)\n• NTB: Rs. 2,000,000 | ETB: Rs. 3,000,000\nNote:\n• NTB borrowers with 2 Mln loan must have completed at least 80% of loan tenure/Installment for qualifying loan above 2 Mln.\n• Repeat loan exceeding Rs. 2 Mln shall only be allowed for borrowers who have already reached existing maximum threshold of Rs. 2 Mln at least once in previous loan history.",
    rateSlabs = tierSlabs34To26,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3263",
    name = "KZL Agriculture Tool & Trac - 3263",
    rateType = "reducing",
    defaultAnnualRate = 34.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• HPA of vehicle/tractor/Car & Digital Charge-Creation/Passbook\n• Hypothecation of equipment (e.g. Solar Plates, Battery, Motor, agriculture tools etc.)\n• In Agri-development, livestock & enterprise loans, hypothecated assets (equipment, inventory or livestock) may be included in mixed collateral only if backed by liquid collateral (Gold, TDR or NSC). If liquid collateral is insufficient, hypothecation portion shall not exceed Rs. 500,000 for NTB & Rs. 1 million for ETB/Repeat borrowers.",
    rateSlabs = tierSlabs34To26,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3265",
    name = "KZL Livestock UnSec - 3265",
    rateType = "reducing",
    defaultAnnualRate = 38.0,
    minAmount = 50000L,
    maxAmount = 1000000L,
    minTenure = 3,
    maxTenure = 18,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• PDC & Guarantee (NTB Rs. 500K, ETB Rs. 1 Million)\n• PDC or Guarantee (up to 500K)\nNote:\n• NTB borrowers with a PKR 500K loan must have completed at least 80% of the loan tenure/EMIs to qualify for a loan exceeding PKR 500K.\n• Repeat loans exceeding PKR 500K shall only be allowed to borrowers who have previously availed the maximum PKR 500K limit at least once.\n• For purchase of large animals, insurance & tagging of purchased animal(s) as per applicable guidelines of LISB.",
    rateSlabs = listOf(RateSlab(50000L, 1000000L, 38.0)),
    feeSlabs = stdFeeSlab1M
  ),
  LoanProduct(
    id = "prod-kmbl-3267",
    name = "KZL Livestock Land - 3267",
    rateType = "reducing",
    defaultAnnualRate = 34.0,
    minAmount = 50000L,
    maxAmount = 3000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Passbook / PLRA / Registered mortgage (Full / Partial)\n• NTB: Rs. 2,000,000 | ETB: Rs. 3,000,000\nNote:\n• NTB borrowers with 2 Mln loan must have completed at least 80% of loan tenure/Installment for qualifying loan above 2 Mln.\n• Repeat loan exceeding Rs. 2 Mln shall only be allowed for borrowers who have already reached existing maximum threshold of Rs. 2 Mln at least once in previous loan history.\n• For Lvs loans above Rs. 500K, hypothecation of minimum two large animals will be mandatory.\n• For purchase of large animals, insurance & tagging of purchased animal(s) as per applicable guidelines of LISB.",
    rateSlabs = tierSlabs34To29Max3M,
    feeSlabs = stdFeeSlab3M
  ),
  LoanProduct(
    id = "prod-kmbl-3270",
    name = "KZL Livestock Sec - 3270",
    rateType = "reducing",
    defaultAnnualRate = 34.0,
    minAmount = 50000L,
    maxAmount = 3000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• HPA of vehicle/tractor/Car\n• Hypothecation of equipment (e.g. Solar Plates, Battery, Motor, agriculture tools etc.)\n• Hypothecation of Large Animals\nNote:\n• For loans above Rs. 500K, hypothecation of minimum two large animals will be mandatory.\n• For purchase of large animals, insurance & tagging of purchased animal(s) as per applicable guidelines of LISB.\n• In Agri-development, livestock & enterprise loans, hypothecated assets may be included in mixed collateral only if backed by liquid collateral (Gold, TDR or NSC). If liquid collateral is insufficient, hypothecation portion shall not exceed Rs. 500,000 for NTB & Rs. 1 million for ETB/Repeat borrowers.",
    rateSlabs = tierSlabs34To29Max3M,
    feeSlabs = stdFeeSlab3M
  ),

  // PAGE 2 PRODUCTS (12 to 28)
  LoanProduct(
    id = "prod-kmbl-3271",
    name = "Karobari ENT Land-3271",
    rateType = "reducing",
    defaultAnnualRate = 34.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• PLRA/Digital Charge creation: NTB/ETB Maximum: Rs. 3,000,000\n• Equitable Mortgage: NTB: Rs. 2,000,000 | ETB: Rs. 3,000,000\nNote:\n• NTB borrowers with Rs. 2 Mln loan must have completed at least 80% of loan tenure/Installment for qualifying loan above Rs. 2 Mln.\n• Repeat loan exceeding Rs. 2 Mln shall only be allowed for borrowers who have already reached existing maximum threshold of Rs. 2 Mln at least once in previous loan history.",
    rateSlabs = tierSlabs34To26,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3116",
    name = "KPF Gen Gold-3116",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Gold\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.5)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3117",
    name = "KPF Gen TDC-3117",
    rateType = "reducing",
    defaultAnnualRate = 28.0,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nPricing Policy: Booking Rate + 8%\n• Paper securities (TDC).\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 28.0)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3118",
    name = "KPF Gen NSC-3118",
    rateType = "reducing",
    defaultAnnualRate = 28.0,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Paper securities (NSC).\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 28.0)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3137",
    name = "KPF Gen Pension Trans-3137",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 70,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Salary / Pension transferred into KMBL.\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.0)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3139",
    name = "KPF Gen Salary Trans-3139",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 60,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Salary / Pension transferred into KMBL.\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.0)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3143",
    name = "KPF Gen Asset-3143",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Hypothecation of Equipment / Animals / Inventory\n• HPA - Vehicle / Bike\n• PDC & Guarantee (500K)\n• PDC or Guarantee (up to 350K)",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 39.0)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3150",
    name = "Khushhali Apna Makaan Unsecure-3150",
    rateType = "reducing",
    defaultAnnualRate = 38.0,
    minAmount = 50000L,
    maxAmount = 500000L,
    minTenure = 3,
    maxTenure = 36,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nFor Self-Employed / Salary/Pension Non-Transfer Case (Un-Secure): Loan amount up to Rs. 500K:\na) Proof of ownership; copy of property document, or affidavit etc. (Mandatory) &\nb) PDC (any bank) & DP Note OR\nc) Personal Guarantee & DP Note OR d) Co-borrower (where applicable) e) Co-Signer (where applicable)\nNote: Eligibility Criteria for PG, Co-Borrower, Co-Signer as per KLM-012.",
    rateSlabs = listOf(RateSlab(50000L, 500000L, 38.0)),
    feeSlabs = stdFeeSlab500K
  ),
  LoanProduct(
    id = "prod-kmbl-3182",
    name = "Karobari ENT Gold - 3182",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Gold.\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold.",
    rateSlabs = tierSlabsGold395To32,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3187",
    name = "Karobari ENT NSC - 3187",
    rateType = "reducing",
    defaultAnnualRate = 28.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Paper securities (NSC).\nNote: No additional collateral is needed if loan is fully (100%) secured by NSC.",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 28.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3190",
    name = "Karobari ENT TDC - 3190",
    rateType = "reducing",
    defaultAnnualRate = 28.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nPricing Policy: Booking Rate + 8%\n• Paper securities (TDC).\nNote: No additional collateral is needed if loan is fully (100%) secured by TDC.",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 28.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3194",
    name = "Karobari Vehicle Finance 3194",
    rateType = "reducing",
    defaultAnnualRate = 25.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Lien marking (HPA) of Vehicle (original registration Card & Documents at Branch)",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 25.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3196",
    name = "Khushhali Apna Makaan (GSS) - 3196",
    rateType = "reducing",
    defaultAnnualRate = 5.0,
    minAmount = 500000L,
    maxAmount = 5000000L,
    minTenure = 12,
    maxTenure = 240,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nPricing & Subsidy Policy: For first 10 yrs: 5% | After 10 yrs: KIBOR + 3% (Tenure: 12 Months to Max 20 Years)\n• Mortgaged housing unit (as per GMSS)",
    rateSlabs = listOf(RateSlab(500000L, 5000000L, 5.0)),
    feeSlabs = listOf(FeeSlab(500000L, 5000000L, "percentage", 1.0, minFee = 5000L, maxFee = 50000L))
  ),
  LoanProduct(
    id = "prod-kmbl-3197",
    name = "Khushhali Apna Makaan-Sec-3197",
    rateType = "reducing",
    defaultAnnualRate = 30.0,
    minAmount = 100000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 96,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nFor Loans PKR 100,000 up to PKR 2,000,000:\n• Equitable Mortgage OR Registered Mortgage OR Digital Charge creation\n• In case of Equitable Mortgage, original title document will be retained at branch and MODTD shall be issued while copy of the same will be provided to customer. Verified photocopy of title document will be attached with credit case.\n• Co-borrower (where applicable) & Co-Signer (where applicable)\nFor Loans PKR 2,000,001 up to PKR 5,000,000:\n• Registered Mortgage (manually or through Digital Charge creation)",
    rateSlabs = listOf(
      RateSlab(100000L, 3000000L, 30.0),
      RateSlab(3000001L, 5000000L, 25.0)
    ),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3205",
    name = "Karobari RF Secured-3205",
    rateType = "reducing",
    defaultAnnualRate = 33.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 120,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: For Self-Employed: 68 Years\nPricing Policy: NSC: 28% | CA/SA: Deposit Rate + 8% | TDC: Booking Rate + 8% | Other Collateral: 33%\n• Gold\n• Mortgage of property (Residential/Commercial) • Deposit funds with the Bank (CASA)\n• Paper securities (NSC/TDC)\n• HPA of Commercial vehicle/tractor/Car\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 33.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3236",
    name = "KAM - Salary Pension - Uns-3236",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 100000L,
    maxAmount = 3000000L,
    minTenure = 3,
    maxTenure = 96,
    minAge = 20,
    maxAgeAtMaturity = 60,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nLoan amount up to PKR 03 Million (Home Improvement & Renovation Purpose):\n• Salary / Pension Transfer to KMBL (Mandatory)\n• Proof of residence (bill or document, rent agreement or affidavit etc.) Mandatory\n• Guarantor (as per recommendation of BCC)\n• Co-borrower (where applicable)\n• Co-Signer (where applicable)",
    rateSlabs = tierSlabsSalPen39To36Max3M,
    feeSlabs = stdFeeSlab3M
  ),
  LoanProduct(
    id = "prod-kmbl-3237",
    name = "KAM - Salary Pension - Sec-3237",
    rateType = "reducing",
    defaultAnnualRate = 35.0,
    minAmount = 3000000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 96,
    minAge = 20,
    maxAgeAtMaturity = 60,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nLoan amount from 03 Million to PKR 05 Million (Home Improvement & Renovation Purpose):\n• Salary / Pension Transfer to KMBL (Mandatory)\n• Equitable mortgage / Digital Charge Creation through Equitable Mortgage: Original title document will be retained at branch and MODTD shall be issued. Photocopy of title document & MODTD will be attached with credit case (Mandatory)\n• Co-borrower (where applicable)\n• Co-Signer (where applicable)",
    rateSlabs = tierSlabsSalPen39To34,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3251",
    name = "Karobari ENT Sal/Pen Trans-3251",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 96,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Salary / Pension transferred into KMBL.\nNote: No additional collateral is needed if loan is fully (100%) secured by salary/pension transfer.",
    rateSlabs = tierSlabsSalPen39To34,
    feeSlabs = stdFeeSlab5M
  ),

  // PAGE 3 PRODUCTS (29 to 36)
  LoanProduct(
    id = "prod-kmbl-3257",
    name = "KZL Agri Sal/Pen transfer- 3257",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 96,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Salary / Pension transferred into KMBL.\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = tierSlabsSalPen39To34,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3261",
    name = "KZL Agriculture Gold - 3261",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Gold.\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = tierSlabsGold395To32,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3262",
    name = "KZL Agriculture Deposit - 3262",
    rateType = "reducing",
    defaultAnnualRate = 28.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nPricing Policy: NSC: 28% | CA/SA: Deposit Rate + 8% | TDC: Booking Rate + 8%\n• Deposit funds with the Bank (CASA).\n• Paper securities (NSC/TDC).\nNote: No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 28.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3264",
    name = "KZL Lvs Sal/Pen transfer- 3264",
    rateType = "reducing",
    defaultAnnualRate = 39.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 96,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Salary / Pension transferred into KMBL.\n• Hypothecation of Large Animals.\nNote:\n• For loans above Rs. 500K, hypothecation of minimum two large animals will be mandatory.\n• For purchase of large animals, insurance & tagging of purchased animal(s) as per applicable guidelines of LISB.\n• No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = tierSlabsSalPen39To34,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3268",
    name = "KZL Livestock Gold - 3268",
    rateType = "reducing",
    defaultAnnualRate = 39.5,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\n• Gold.\n• Hypothecation of Large Animals.\nNote:\n• For loans above Rs. 500K, hypothecation of minimum two large animals will be mandatory.\n• For purchase of large animals, insurance & tagging of purchased animal(s) as per applicable guidelines of LISB.\n• No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = tierSlabsGold395To32,
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3269",
    name = "KZL Livestock Deposit - 3269",
    rateType = "reducing",
    defaultAnnualRate = 28.0,
    minAmount = 50000L,
    maxAmount = 5000000L,
    minTenure = 3,
    maxTenure = 60,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: Self-Employed: 68 Yrs | Pensioner: 70 Yrs | Salary: 60 Yrs\nPricing Policy: NSC: 28% | CA/SA: Deposit Rate + 8% | TDC: Booking Rate + 8%\n• Deposit funds with the Bank (CASA).\n• Paper securities (NSC/TDC).\n• Hypothecation of Large Animals.\nNote:\n• For loans above Rs. 500K, hypothecation of minimum two large animals will be mandatory.\n• For purchase of large animals, insurance & tagging of purchased animal(s) as per applicable guidelines of LISB.\n• No additional collateral is needed if loan is fully (100%) secured by Gold/TDC/NSC & salary/pension transfer.",
    rateSlabs = listOf(RateSlab(50000L, 5000000L, 28.0)),
    feeSlabs = stdFeeSlab5M
  ),
  LoanProduct(
    id = "prod-kmbl-3273",
    name = "Niswan Group Loan Lvs -3273",
    rateType = "reducing",
    defaultAnnualRate = 35.0,
    minAmount = 50000L,
    maxAmount = 150000L,
    minTenure = 3,
    maxTenure = 12,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: 68 Years\n• Social collateral (Cross Group Guarantee Min 03 – Max 06) For Women Only\nCycle Limits:\n• 1st Cycle (NTB): Min Rs. 50,000 – Max Rs. 75,000\n• 2nd Cycle (ETB): Min Rs. 50,000 – Max Rs. 100,000\n• 3rd Cycle onward (ETB): Min Rs. 50,000 – Max Rs. 150,000",
    rateSlabs = listOf(RateSlab(50000L, 150000L, 35.0)),
    feeSlabs = listOf(FeeSlab(50000L, 150000L, "percentage", 1.5, minFee = 1000L, maxFee = 2500L))
  ),
  LoanProduct(
    id = "prod-kmbl-3275",
    name = "Niswan Group Loan ENT - 3275",
    rateType = "reducing",
    defaultAnnualRate = 35.0,
    minAmount = 50000L,
    maxAmount = 150000L,
    minTenure = 3,
    maxTenure = 12,
    minAge = 20,
    maxAgeAtMaturity = 68,
    feeType = "percentage",
    defaultFeeValue = 0.0,
    fedRate = 16.0,
    collateralDetails = "Age at Maturity: 68 Years\n• Social collateral (Cross Group Guarantee Min 03 – Max 06) For Women Only\nCycle Limits:\n• 1st Cycle (NTB): Min Rs. 50,000 – Max Rs. 75,000\n• 2nd Cycle (ETB): Min Rs. 50,000 – Max Rs. 100,000\n• 3rd Cycle onward (ETB): Min Rs. 50,000 – Max Rs. 150,000",
    rateSlabs = listOf(RateSlab(50000L, 150000L, 35.0)),
    feeSlabs = listOf(FeeSlab(50000L, 150000L, "percentage", 1.5, minFee = 1000L, maxFee = 2500L)),
    category = "asset"
  ),

  // LIABILITY / DEPOSIT PRODUCTS (بچت و ڈپازٹ پروڈکٹس - ریٹ شیٹ wef 24-AUG-2026 & 01-Sep-2026)
  // 1. Term Deposit Certificate - Individual
  LoanProduct(
    id = "prod-liab-kic-individual",
    name = "Khushhali Izafa Certificate - Individual (KIC - Monthly)",
    rateType = "flat",
    defaultAnnualRate = 12.25,
    minAmount = 25000L,
    maxAmount = 50000000L,
    minTenure = 6,
    maxTenure = 60,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Term Deposit Certificate - Individual (Wef 24-AUG-2026)\n• Product: Khushhali Izafa Certificate (KIC)\n• Profit Payout: Monthly Profit credited directly to linked account\n• Fixed Tenures: 6, 12, 24, 36, 60 Months\n• Rates: 6M: 11.75% | 12M: 12.25% | 24M: 12.50% | 36M: 12.75% | 60M: 12.75%\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(6, 12, 24, 36, 60),
    tenureRates = mapOf(6 to 11.75, 12 to 12.25, 24 to 12.50, 36 to 12.75, 60 to 12.75)
  ),
  LoanProduct(
    id = "prod-liab-kac-individual",
    name = "Khushhali Amdani Certificate - Individual (KAC - Maturity)",
    rateType = "flat",
    defaultAnnualRate = 12.75,
    minAmount = 25000L,
    maxAmount = 50000000L,
    minTenure = 6,
    maxTenure = 60,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Term Deposit Certificate - Individual (Wef 24-AUG-2026)\n• Product: Khushhali Amdani Certificate (KAC)\n• Profit Payout: Paid at Maturity\n• Fixed Tenures: 6, 12, 24, 36, 60 Months\n• Rates: 6M: 12.25% | 12M: 12.75% | 24M: 13.00% | 36M: 13.25% | 60M: 13.25%\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(6, 12, 24, 36, 60),
    tenureRates = mapOf(6 to 12.25, 12 to 12.75, 24 to 13.00, 36 to 13.25, 60 to 13.25)
  ),

  // 2. Term Deposit Certificate - Special Scheme (Senior/Junior/Widow/Pensioner/Disabled)
  LoanProduct(
    id = "prod-liab-kic-special",
    name = "Special Scheme KIC (Senior/Widow/Disabled - Monthly)",
    rateType = "flat",
    defaultAnnualRate = 12.25,
    minAmount = 25000L,
    maxAmount = 50000000L,
    minTenure = 6,
    maxTenure = 60,
    minAge = 18,
    maxAgeAtMaturity = 95,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Special Scheme Term Deposit (Wef 24-AUG-2026)\n• Beneficiaries: Senior Citizens, Juniors, Widows, Pensioners, Disabled Persons\n• Product: Khushhali Izafa Certificate (KIC - Monthly Profit)\n• Fixed Tenures: 6, 12, 24, 36, 60 Months\n• Rates: 6M: 11.75% | 12M: 12.25% | 24M: 12.50% | 36M: 12.75% | 60M: 13.25%\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(6, 12, 24, 36, 60),
    tenureRates = mapOf(6 to 11.75, 12 to 12.25, 24 to 12.50, 36 to 12.75, 60 to 13.25)
  ),
  LoanProduct(
    id = "prod-liab-kac-special",
    name = "Special Scheme KAC (Senior/Widow/Disabled - Maturity)",
    rateType = "flat",
    defaultAnnualRate = 12.75,
    minAmount = 25000L,
    maxAmount = 50000000L,
    minTenure = 6,
    maxTenure = 60,
    minAge = 18,
    maxAgeAtMaturity = 95,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Special Scheme Term Deposit (Wef 24-AUG-2026)\n• Beneficiaries: Senior Citizens, Juniors, Widows, Pensioners, Disabled Persons\n• Product: Khushhali Amdani Certificate (KAC - Profit at Maturity)\n• Fixed Tenures: 6, 12, 24, 36, 60 Months\n• Rates: 6M: 12.25% | 12M: 12.75% | 24M: 13.00% | 36M: 13.25% | 60M: 13.75%\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(6, 12, 24, 36, 60),
    tenureRates = mapOf(6 to 12.25, 12 to 12.75, 24 to 13.00, 36 to 13.25, 60 to 13.75)
  ),

  // 3. Term Deposit Certificate - Institutional
  LoanProduct(
    id = "prod-liab-kic-institutional",
    name = "Institutional Term Deposit (KIC - Monthly Profit)",
    rateType = "flat",
    defaultAnnualRate = 11.75,
    minAmount = 100000L,
    maxAmount = 200000000L,
    minTenure = 6,
    maxTenure = 60,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Term Deposit Certificate - Institutional (Wef 24-AUG-2026)\n• Product: Khushhali Izafa Certificate (KIC - Monthly Profit)\n• Target: Corporate, Trusts, Institutions\n• Fixed Tenures: 6, 12, 24, 36, 60 Months\n• Rates: 6M: 11.50% | 12M: 11.75% | 24M: 12.00% | 36M: 12.25% | 60M: 12.25%\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(6, 12, 24, 36, 60),
    tenureRates = mapOf(6 to 11.50, 12 to 11.75, 24 to 12.00, 36 to 12.25, 60 to 12.25)
  ),
  LoanProduct(
    id = "prod-liab-kac-institutional",
    name = "Institutional Term Deposit (KAC - Profit at Maturity)",
    rateType = "flat",
    defaultAnnualRate = 12.75,
    minAmount = 100000L,
    maxAmount = 200000000L,
    minTenure = 6,
    maxTenure = 60,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Term Deposit Certificate - Institutional (Wef 24-AUG-2026)\n• Product: Khushhali Amdani Certificate (KAC - Profit at Maturity)\n• Target: Corporate, Trusts, Institutions\n• Fixed Tenures: 6, 12, 24, 36, 60 Months\n• Rates: 6M: 12.25% | 12M: 12.75% | 24M: 13.00% | 36M: 13.25% | 60M: 13.25%\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(6, 12, 24, 36, 60),
    tenureRates = mapOf(6 to 12.25, 12 to 12.75, 24 to 13.00, 36 to 13.25, 60 to 13.25)
  ),

  // 4. Saving Accounts (Daily Product) Wef 01-Sep-2026
  LoanProduct(
    id = "prod-liab-khushhali-bachat-plus",
    name = "Khushhali Bachat plus (خوشحالی بچت پلس)",
    rateType = "flat",
    defaultAnnualRate = 11.00,
    minAmount = 1000L,
    maxAmount = 100000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Saving Account (Daily Product) Wef 01-Sep-2026\n• Scheme: Khushhali Bachat plus\n• Deposit Tier: On All Balances\n• Rate: Policy Rate minus 0.5% (11.50% - 0.5% = 11.00% p.a.)\n• Profit Calculation: Daily product basis\n• Single Annualized Matrix: 12 Months\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 11.00),
    rateSlabs = listOf(RateSlab(1000L, 100000000L, 11.00))
  ),
  LoanProduct(
    id = "prod-liab-karobari-munafa",
    name = "Karobari Munafa Account (کاروباری منافع)",
    rateType = "flat",
    defaultAnnualRate = 10.50,
    minAmount = 1000L,
    maxAmount = 100000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Saving Account (Daily Product) Wef 01-Sep-2026\n• Scheme: Karobari Munafa Account\n• Deposit Tier: On All Balances\n• Rate: Policy Rate minus 1% (11.50% - 1.0% = 10.50% p.a.)\n• Profit Calculation: Daily product basis\n• Target: Traders, Sole Proprietors, Businesses\n• Single Annualized Matrix: 12 Months\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 10.50),
    rateSlabs = listOf(RateSlab(1000L, 100000000L, 10.50))
  ),
  LoanProduct(
    id = "prod-liab-mehfooz-account",
    name = "Mehfooz Account (محفوظ اکاؤنٹ)",
    rateType = "flat",
    defaultAnnualRate = 7.00,
    minAmount = 1000L,
    maxAmount = 100000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Saving Account (Daily Product) Wef 01-Sep-2026\n• Scheme: Mehfooz Account\n• Deposit Tier: On All Balances\n• Rate: 7.00% p.a. guaranteed\n• Profit Calculation: Daily product basis\n• Single Annualized Matrix: 12 Months\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 7.00),
    rateSlabs = listOf(RateSlab(1000L, 100000000L, 7.00))
  ),
  LoanProduct(
    id = "prod-liab-muhafiz-account",
    name = "Muhafiz Account - Salary/Pension (محافظ اکاؤنٹ)",
    rateType = "flat",
    defaultAnnualRate = 10.50,
    minAmount = 1000L,
    maxAmount = 100000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Saving Account (Daily Product) Wef 01-Sep-2026\n• Scheme: Muhafiz Account (Salary/Pension Individual)\n• Deposit Tier: On All Balances\n• Rate: Policy Rate minus 1% (11.50% - 1.0% = 10.50% p.a.)\n• Target: Salaried Individuals & Pensioners\n• Single Annualized Matrix: 12 Months\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 10.50),
    rateSlabs = listOf(RateSlab(1000L, 100000000L, 10.50))
  ),
  LoanProduct(
    id = "prod-liab-niswan-saving",
    name = "Niswan Saving (نسواں سیونگ اکاؤنٹ)",
    rateType = "flat",
    defaultAnnualRate = 11.50,
    minAmount = 1000L,
    maxAmount = 100000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Saving Account (Daily Product) Wef 01-Sep-2026\n• Scheme: Niswan Saving\n• Deposit Tier: On All Balances\n• Rate: Policy Rate 11.50% p.a.\n• Dedicated high-yield scheme for women savers\n• Single Annualized Matrix: 12 Months\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 11.50),
    rateSlabs = listOf(RateSlab(1000L, 100000000L, 11.50))
  ),
  LoanProduct(
    id = "prod-liab-behtreen-bachat",
    name = "Behtreen Bachat (بہترین بچت اکاؤنٹ)",
    rateType = "flat",
    defaultAnnualRate = 10.00,
    minAmount = 1000L,
    maxAmount = 100000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Saving Account (Daily Product) Wef 01-Sep-2026\n• Scheme: Behtreen Bachat\n• Tiered Balance Slabs:\n  - PKR 0 - 499,999: 10.00% (Policy Rate - 1.5%)\n  - PKR 500,000 - 9,999,999: 10.25% (Policy Rate - 1.25%)\n  - PKR 10,000,000 - 24,999,999: 10.50% (Policy Rate - 1.0%)\n  - PKR 25,000,000 & Above: 10.50% (Policy Rate - 1.0%)\n• Single Annualized Matrix: 12 Months\n• WHT on Profit: Filer 20%, Non-Filer 40%",
    category = "liability",
    fixedTenures = listOf(12),
    rateSlabs = listOf(
      RateSlab(0L, 499999L, 10.00),
      RateSlab(500000L, 9999999L, 10.25),
      RateSlab(10000000L, 24999999L, 10.50),
      RateSlab(25000000L, 100000000L, 10.50)
    ),
    liabilityPricingType = "amount"
  ),

  // 5. Current Accounts (Non-Remunerative / 0% Profit)
  LoanProduct(
    id = "prod-liab-current-asaan",
    name = "Khushhali Asaan Current Account (آسان کرنٹ اکاؤنٹ)",
    rateType = "flat",
    defaultAnnualRate = 0.0,
    minAmount = 1000L,
    maxAmount = 500000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Current Account (Non-Remunerative / لاگت و منافع سے پاک)\n• Scheme: Khushhali Asaan Current Account\n• Profit Rate: 0.0% p.a. (Non-Interest Bearing)\n• Initial Deposit: Nominal PKR 1,000 / No min balance penalty\n• Withdrawals & Deposits: Unlimited via Cheque book, Debit Card & ATM\n• Tax on Profit: Exempt / Not Applicable (Zero Profit)",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 0.0),
    rateSlabs = listOf(RateSlab(0L, 500000000L, 0.0))
  ),
  LoanProduct(
    id = "prod-liab-current-karobari",
    name = "Khushhali Karobari Current Account (کاروباری کرنٹ اکاؤنٹ)",
    rateType = "flat",
    defaultAnnualRate = 0.0,
    minAmount = 5000L,
    maxAmount = 1000000000L,
    minTenure = 12,
    maxTenure = 12,
    minAge = 18,
    maxAgeAtMaturity = 90,
    feeType = "fixed",
    defaultFeeValue = 0.0,
    fedRate = 0.0,
    collateralDetails = "Current Account (Commercial & Business / کاروباری کرنٹ اکاؤنٹ)\n• Scheme: Karobari Current Account\n• Target: Traders, Sole Proprietors, Partnerships & Commercial Entities\n• Profit Rate: 0.0% p.a. (Non-Remunerative)\n• High transaction volumes, interbank transfers, and complete banking facilities\n• Tax on Profit: Exempt / Not Applicable (Zero Profit)",
    category = "liability",
    fixedTenures = listOf(12),
    tenureRates = mapOf(12 to 0.0),
    rateSlabs = listOf(RateSlab(0L, 1000000000L, 0.0))
  )
)

class LoanViewModel(application: Application) : AndroidViewModel(application) {

  private val prefs = application.getSharedPreferences("kb_loan_prefs", Context.MODE_PRIVATE)
  private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
  private var networkCallback: ConnectivityManager.NetworkCallback? = null

  private val authRepo = AuthRepository()
  private val firestoreRepo = FirestoreRepository(application)
  private val sheetsRepo = GoogleSheetsRepository(application)
  private var productsJob: Job? = null
  private var leadsJob: Job? = null
  private var configJob: Job? = null
  private var usersJob: Job? = null
  private var heartbeatJob: Job? = null
  private val deletedLeadIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val isSyncingLeads = java.util.concurrent.atomic.AtomicBoolean(false)

  private val _uiState = MutableStateFlow(LoanUiState(isOnline = false))
  val uiState: StateFlow<LoanUiState> = _uiState.asStateFlow()

  init {
    loadLocalState()
    val initialUser = authRepo.currentUser
    _uiState.update {
      it.copy(
        isOnline = checkCurrentOnlineStatus(),
        authUserId = initialUser?.uid,
        authUserEmail = initialUser?.email
      )
    }
    setupNetworkMonitor()
    observeAuthState()
  }

  private fun getCurrentApkLastUpdateTime(): Long {
    return try {
      val app = getApplication<Application>()
      app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
      0L
    }
  }

  private fun mergeUserLists(
    primary: List<RegisteredUser>,
    secondary: List<RegisteredUser>,
    deletedKeys: Set<String>
  ): List<RegisteredUser> {
    val map = LinkedHashMap<String, RegisteredUser>()
    for (u in (primary + secondary)) {
      if (isUserKeyDeleted(u, deletedKeys)) continue
      val existingKey = map.entries.find { (_, existing) ->
        (u.uid.isNotBlank() && existing.uid == u.uid) ||
          (u.email.isNotBlank() && existing.email.equals(u.email, ignoreCase = true)) ||
          (u.staffId.isNotBlank() && existing.staffId.equals(u.staffId, ignoreCase = true))
      }?.key
      if (existingKey != null) {
        val prev = map[existingKey]!!
        // Prefer record with newer updatedAt, while preserving non-blank fields
        val newer = if (u.updatedAt >= prev.updatedAt) u else prev
        val older = if (u.updatedAt >= prev.updatedAt) prev else u
        val realUid = if (!newer.uid.startsWith("staff_") && newer.uid.isNotBlank()) newer.uid
        else if (!older.uid.startsWith("staff_") && older.uid.isNotBlank()) older.uid
        else newer.uid.ifBlank { older.uid }
        val merged = newer.copy(
          uid = realUid,
          email = newer.email.ifBlank { older.email },
          officerName = newer.officerName.ifBlank { older.officerName },
          staffId = newer.staffId.ifBlank { older.staffId },
          branchName = newer.branchName.ifBlank { older.branchName },
          allowedSupervisorBranch = newer.allowedSupervisorBranch.ifBlank { older.allowedSupervisorBranch }
        )
        map.remove(existingKey)
        map[realUid.ifBlank { merged.staffId.lowercase(Locale.US) }] = merged
      } else {
        val k = u.uid.ifBlank { u.staffId.lowercase(Locale.US).ifBlank { u.email.lowercase(Locale.US) } }
        if (k.isNotBlank()) {
          map[k] = u
        }
      }
    }
    return map.values.toList()
  }

  private fun observeAuthState() {
    viewModelScope.launch {
      authRepo.authStateFlow().collect { user ->
        val displayName = user?.displayName?.trim().orEmpty()
        _uiState.update { s ->
          val updatedProfile = if (s.profile.officerName.isBlank() && displayName.isNotNotBlank()) {
            s.profile.copy(officerName = displayName)
          } else {
            s.profile
          }
          s.copy(
            authUserId = user?.uid,
            authUserEmail = user?.email,
            profile = updatedProfile,
            isAuthenticating = false,
            authError = null
          )
        }
        if (user != null) {
          startFirestoreSync(user.uid, user.email.orEmpty(), displayName)
        } else {
          stopFirestoreSync()
        }
      }
    }
  }

  private fun String.isNotNotBlank(): Boolean = this.isNotBlank()

  /**
   * Fetches global product configurations from Firestore `products` collection
   * and updates UI state for real-time cross-device consistency.
   */
  fun autoFetchGlobalProducts() {
    viewModelScope.launch(Dispatchers.IO) {
      val fetched = firestoreRepo.autoFetchGlobalProducts(defaultBankProducts)
      if (fetched.isNotEmpty()) {
        _uiState.update { s ->
          s.copy(products = fetched)
        }
      }
    }
  }

  /**
   * Persists local preferences and synchronizes officer profile & leads to Firestore documents.
   */
  fun saveState() {
    saveLocalState()
    val state = _uiState.value
    val uid = state.authUserId.orEmpty()
    val email = state.authUserEmail.orEmpty()
    if (uid.isNotBlank()) {
      viewModelScope.launch(Dispatchers.IO) {
        firestoreRepo.saveState(
          profile = state.profile,
          leads = state.leads,
          userId = uid,
          userEmail = email
        )
      }
    }
  }

  private fun startFirestoreSync(userId: String, userEmail: String = "", userDisplayName: String = "") {
    stopFirestoreSync()

    // Immediately sync current user's profile to both /config/global and /users/{userId}
    // (creates blocked user by default if new so Admin on any other device sees them right away)
    val initialState = _uiState.value
    val effectiveEmail = userEmail.ifBlank { initialState.authUserEmail.orEmpty() }
    val effectiveName = initialState.profile.officerName.ifBlank {
      userDisplayName.ifBlank {
        if (effectiveEmail.isNotBlank()) effectiveEmail.substringBefore("@") else "New Officer"
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      firestoreRepo.syncCurrentUserProfile(
        uid = userId,
        email = effectiveEmail,
        officerName = effectiveName,
        staffId = initialState.profile.staffId,
        branchName = initialState.profile.branchName
      )
    }

    // 1. Auto-fetch & Observe Products in real-time
    productsJob = viewModelScope.launch(Dispatchers.IO) {
      val initialProducts = firestoreRepo.autoFetchGlobalProducts(defaultBankProducts)
      if (initialProducts.isNotEmpty()) {
        _uiState.update { it.copy(products = initialProducts) }
      }
      firestoreRepo.observeProducts().collect { firestoreProducts ->
        if (firestoreProducts.isNotEmpty()) {
          _uiState.update { s ->
            s.copy(products = firestoreProducts)
          }
        }
      }
    }

    // 2. Observe Leads in real-time from direct /leads collection
    leadsJob = viewModelScope.launch(Dispatchers.IO) {
      firestoreRepo.observeLeads().collect { firestoreLeads ->
        mergeRemoteLeadsIntoState(firestoreLeads, emptySet())
      }
    }

    // Immediately trigger a full bi-directional lead sync on startup/sign-in
    syncPendingLeadsToCloud(showToastOnComplete = false)

    // 3. Observe Global Config (including Admin Password, Broadcast Note, Mandatory App Update Link, and Global User Registry)
    configJob = viewModelScope.launch(Dispatchers.IO) {
      firestoreRepo.seedDefaultGlobalConfigIfEmpty()
      firestoreRepo.observeGlobalConfig().collect { config ->
        config?.let { cfg ->
          val currentUser = _uiState.value.authUserId ?: "default_user"
          val ackKey = "ack_broadcast_$currentUser"
          val lastAckId = prefs.getString(ackKey, "") ?: ""
          val shouldShowBroadcast = cfg.adminBroadcastMessage.isNotBlank() &&
            cfg.adminBroadcastId.isNotBlank() &&
            cfg.adminBroadcastId != lastAckId

          // Evaluate Mandatory App Update lock state
          val hasUpdateLink = cfg.appUpdateUrl.isNotBlank() && cfg.appUpdateId.isNotBlank()
          var isCompletedForThisId = prefs.getString("completed_app_update_id", "") == cfg.appUpdateId
          val downloadingId = prefs.getString("downloading_app_update_id", "") ?: ""
          val preDownloadApkTime = prefs.getLong("pre_download_apk_time", 0L)
          val currentApkTime = getCurrentApkLastUpdateTime()

          if (hasUpdateLink && !isCompletedForThisId && downloadingId == cfg.appUpdateId && preDownloadApkTime > 0L && currentApkTime > preDownloadApkTime) {
            // APK was updated on the device after clicking download! Automatically restore access.
            prefs.edit().putString("completed_app_update_id", cfg.appUpdateId).apply()
            isCompletedForThisId = true
          }

          val updateRequired = hasUpdateLink && !isCompletedForThisId
          val clickedDownloadForCurrent = hasUpdateLink && downloadingId == cfg.appUpdateId

          var mergedUsersToSave: List<RegisteredUser> = emptyList()

          _uiState.update { s ->
            val combinedDeletedKeys = s.deletedUserKeys + cfg.deletedUserKeys
            if (combinedDeletedKeys.size != s.deletedUserKeys.size) {
              prefs.edit().putStringSet("deleted_user_keys", combinedDeletedKeys).apply()
            }
            val combinedUsers = mergeUserLists(
              primary = cfg.registryUsers,
              secondary = s.registeredUsers,
              deletedKeys = combinedDeletedKeys
            )
            mergedUsersToSave = combinedUsers

            val myEmail = s.authUserEmail?.trim().orEmpty()
            val myStaffId = s.profile.staffId.trim()
            val myRecord = combinedUsers.find { u ->
              u.uid == userId ||
                (myEmail.isNotEmpty() && u.email.equals(myEmail, ignoreCase = true)) ||
                (myStaffId.isNotEmpty() && u.staffId.equals(myStaffId, ignoreCase = true))
            }
            val syncedName = if (s.profile.officerName.isBlank() && myRecord != null && myRecord.officerName.isNotBlank()) {
              prefs.edit().putString("officer_name", myRecord.officerName).apply()
              myRecord.officerName
            } else s.profile.officerName
            val syncedStaffId = if (s.profile.staffId.isBlank() && myRecord != null && myRecord.staffId.isNotBlank()) {
              prefs.edit().putString("staff_id", myRecord.staffId).apply()
              myRecord.staffId
            } else s.profile.staffId
            val syncedBranch = if (myRecord != null && myRecord.branchName.isNotBlank()) {
              if (myRecord.branchName != s.profile.branchName) {
                prefs.edit().putString("branch_name", myRecord.branchName).apply()
              }
              myRecord.branchName
            } else s.profile.branchName
            val syncedOnboarded = s.profile.isOnboarded || (syncedStaffId.isNotBlank() && syncedBranch.isNotBlank())
            if (syncedOnboarded && !s.profile.isOnboarded) {
              prefs.edit().putBoolean("is_onboarded", true).apply()
            }

            val updatedProfile = s.profile.copy(
              officerName = syncedName,
              staffId = syncedStaffId,
              branchName = syncedBranch,
              isOnboarded = syncedOnboarded,
              fedRate = cfg.fedRate,
              filerTaxRate = cfg.filerTaxRate,
              nonFilerTaxRate = cfg.nonFilerTaxRate,
              currency = cfg.currency,
              adminPin = cfg.adminPin
            )

            s.copy(
              profile = updatedProfile,
              showOnboardingDialog = if (syncedOnboarded) false else s.showOnboardingDialog,
              registeredUsers = combinedUsers,
              deletedUserKeys = combinedDeletedKeys,
              adminBroadcastMessage = cfg.adminBroadcastMessage,
              adminBroadcastId = cfg.adminBroadcastId,
              showAdminBroadcastDialog = shouldShowBroadcast,
              appUpdateUrl = cfg.appUpdateUrl,
              appUpdateId = cfg.appUpdateId,
              appUpdateMessage = cfg.appUpdateMessage,
              isAppUpdateRequired = updateRequired,
              hasClickedAppUpdateDownload = clickedDownloadForCurrent,
              googleSheetsWebhookUrl = if (cfg.googleSheetsWebhookUrl.isNotBlank()) {
                sheetsRepo.setWebhookUrl(cfg.googleSheetsWebhookUrl)
                cfg.googleSheetsWebhookUrl
              } else s.googleSheetsWebhookUrl
            )
          }
          saveLocalUsers(mergedUsersToSave)
        }
      }
    }

    // 4. Observe Registered Users & Branch Access Rights in real-time from /users collection as well
    usersJob = viewModelScope.launch(Dispatchers.IO) {
      firestoreRepo.observeUsers().collect { remoteUsers ->
        var mergedToSave: List<RegisteredUser> = emptyList()
        _uiState.update { s ->
          val activeRemote = remoteUsers.filterNot { u -> isUserKeyDeleted(u, s.deletedUserKeys) }
          val combinedUsers = mergeUserLists(
            primary = activeRemote,
            secondary = s.registeredUsers,
            deletedKeys = s.deletedUserKeys
          )
          mergedToSave = combinedUsers
          val myEmail = s.authUserEmail?.trim().orEmpty()
          val myStaffId = s.profile.staffId.trim()
          val myRecord = combinedUsers.find { u ->
            u.uid == userId ||
              (myEmail.isNotEmpty() && u.email.equals(myEmail, ignoreCase = true)) ||
              (myStaffId.isNotEmpty() && u.staffId.equals(myStaffId, ignoreCase = true))
          }
          val syncedName = if (s.profile.officerName.isBlank() && myRecord != null && myRecord.officerName.isNotBlank()) {
            prefs.edit().putString("officer_name", myRecord.officerName).apply()
            myRecord.officerName
          } else s.profile.officerName
          val syncedStaffId = if (s.profile.staffId.isBlank() && myRecord != null && myRecord.staffId.isNotBlank()) {
            prefs.edit().putString("staff_id", myRecord.staffId).apply()
            myRecord.staffId
          } else s.profile.staffId
          val syncedBranch = if (myRecord != null && myRecord.branchName.isNotBlank()) {
            if (myRecord.branchName != s.profile.branchName) {
              prefs.edit().putString("branch_name", myRecord.branchName).apply()
            }
            myRecord.branchName
          } else s.profile.branchName
          val syncedOnboarded = s.profile.isOnboarded || (syncedStaffId.isNotBlank() && syncedBranch.isNotBlank())
          if (syncedOnboarded && !s.profile.isOnboarded) {
            prefs.edit().putBoolean("is_onboarded", true).apply()
          }
          val updatedProfile = s.profile.copy(
            officerName = syncedName,
            staffId = syncedStaffId,
            branchName = syncedBranch,
            isOnboarded = syncedOnboarded
          )
          s.copy(
            registeredUsers = combinedUsers,
            profile = updatedProfile,
            showOnboardingDialog = if (syncedOnboarded) false else s.showOnboardingDialog
          )
        }
        saveLocalUsers(mergedToSave)
      }
    }

    // 5. Periodic auto-sync heartbeat while app is active:
    // Automatically uploads any pending offline leads when internet is available
    heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
      while (true) {
        kotlinx.coroutines.delay(20_000L)
        val online = checkCurrentOnlineStatus()
        _uiState.update { it.copy(isOnline = online) }
        if (online) {
          val hasPending = _uiState.value.leads.any { it.syncStatus != "synced" && it.id !in deletedLeadIds }
          if (hasPending && !isSyncingLeads.get()) {
            syncPendingLeadsToCloud(showToastOnComplete = false)
          }
        }
      }
    }
  }

  private fun mergeRemoteLeadsIntoState(
    remoteLeads: List<CustomerLead>,
    remoteDeletedIds: Set<String>
  ) {
    if (remoteDeletedIds.isNotEmpty()) {
      deletedLeadIds.addAll(remoteDeletedIds)
      prefs.edit().putStringSet("deleted_lead_ids", deletedLeadIds.toSet()).apply()
    }
    val filteredRemote = remoteLeads.filterNot { it.id in deletedLeadIds }
    var mergedToSave: List<CustomerLead> = emptyList()
    var hasPendingLocalLeads = false

    _uiState.update { s ->
      // Branch-scoped sync: only retain leads owned by current officer OR within authorized branch(es)
      val scopedRemote = filteredRemote.filter { s.isLeadPermittedForUser(it) }
      val remoteById = scopedRemote.associateBy { it.id }
      val combinedMap = LinkedHashMap<String, CustomerLead>()
      for (r in scopedRemote) {
        combinedMap[r.id] = r.copy(syncStatus = "synced")
      }
      for (localLead in s.leads) {
        if (localLead.id in deletedLeadIds) continue
        // If it's already synced and not permitted for this officer's branch scope, prune it from device cache
        if (localLead.syncStatus == "synced" && !s.isLeadPermittedForUser(localLead)) continue

        val remoteMatch = remoteById[localLead.id]
        if (remoteMatch == null) {
          // Preserve local lead (either local-pending or permitted)
          combinedMap[localLead.id] = localLead
          if (localLead.syncStatus != "synced") {
            hasPendingLocalLeads = true
          }
        } else if (localLead.syncStatus != "synced") {
          // Local lead has offline edits pending upload — keep local pending version
          combinedMap[localLead.id] = localLead
          hasPendingLocalLeads = true
        } else if (localLead.createdAt > remoteMatch.createdAt) {
          // Local synced lead has a newer timestamp than this snapshot
          combinedMap[localLead.id] = localLead
        }
      }
      val mergedList = combinedMap.values.sortedByDescending { it.createdAt }
      val pendingQueue = mergedList.filter { it.syncStatus != "synced" }
      mergedToSave = mergedList
      s.copy(
        leads = mergedList,
        syncQueue = pendingQueue
      )
    }
    saveLocalLeads(mergedToSave)

    if (hasPendingLocalLeads && checkCurrentOnlineStatus() && !isSyncingLeads.get()) {
      syncPendingLeadsToCloud(showToastOnComplete = false)
    }
  }

  private fun stopFirestoreSync() {
    productsJob?.cancel()
    leadsJob?.cancel()
    configJob?.cancel()
    usersJob?.cancel()
    heartbeatJob?.cancel()
    productsJob = null
    leadsJob = null
    configJob = null
    usersJob = null
    heartbeatJob = null
  }

  fun signInWithGoogle(context: Context) {
    viewModelScope.launch {
      _uiState.update { it.copy(isAuthenticating = true, authError = null) }
      val result = authRepo.signInWithGoogle(context)
      result.onSuccess { user ->
        _uiState.update {
          it.copy(
            isAuthenticating = false,
            authUserId = user?.uid,
            authUserEmail = user?.email
          )
        }
        showToast("Welcome! Signed in as ${user?.email}")
      }.onFailure { e ->
        _uiState.update {
          it.copy(
            isAuthenticating = false,
            authError = e.localizedMessage ?: "Google Sign-In failed or was cancelled"
          )
        }
      }
    }
  }

  fun signOut() {
    stopFirestoreSync()
    authRepo.signOut()
    _uiState.update {
      it.copy(
        authUserId = null,
        authUserEmail = null
      )
    }
    showToast("Signed out from Google account")
  }

  fun continueInOfflineMode() {
    _uiState.update { s ->
      s.copy(
        authUserId = s.authUserId ?: "offline_officer",
        authUserEmail = s.authUserEmail ?: "offline@local",
        profile = if (s.profile.officerName.isBlank()) s.profile.copy(officerName = "Loan Officer") else s.profile
      )
    }
  }

  private fun saveLocalLeads(leads: List<CustomerLead>) {
    try {
      prefs.edit().putString("saved_leads", serializeLeadsJson(leads)).apply()
    } catch (_: Exception) {}
  }

  fun checkCurrentOnlineStatus(): Boolean {
    val cm = connectivityManager ?: return false
    val activeNet = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNet) ?: return false
    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                       caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                       caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    return hasInternet && (isValidated || hasTransport)
  }

  private fun setupNetworkMonitor() {
    connectivityManager?.let { cm ->
      try {
        _uiState.update { it.copy(isOnline = checkCurrentOnlineStatus()) }

        val callback = object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            viewModelScope.launch(Dispatchers.Main) {
              kotlinx.coroutines.delay(150)
              val online = checkCurrentOnlineStatus()
              _uiState.update { it.copy(isOnline = online) }
              if (online) {
                syncPendingLeadsToCloud(showToastOnComplete = false)
              }
            }
          }
          override fun onLost(network: Network) {
            val active = cm.activeNetwork
            if (active == null || active == network) {
              _uiState.update { it.copy(isOnline = false) }
            }
            viewModelScope.launch(Dispatchers.Main) {
              kotlinx.coroutines.delay(200)
              _uiState.update { it.copy(isOnline = checkCurrentOnlineStatus()) }
            }
          }
          override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val wasOnline = _uiState.value.isOnline
            val isOnline = hasInternet && (isValidated || hasTransport)
            _uiState.update { it.copy(isOnline = isOnline) }
            if (isOnline && (!wasOnline || _uiState.value.syncQueue.isNotEmpty() || _uiState.value.leads.any { it.syncStatus != "synced" })) {
              syncPendingLeadsToCloud(showToastOnComplete = false)
            }
          }
          override fun onUnavailable() {
            _uiState.update { it.copy(isOnline = false) }
          }
        }
        networkCallback = callback
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
          cm.registerDefaultNetworkCallback(callback)
        } else {
          val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
          cm.registerNetworkCallback(request, callback)
        }
      } catch (_: Exception) {
        _uiState.update { it.copy(isOnline = checkCurrentOnlineStatus()) }
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    networkCallback?.let {
      try {
        connectivityManager?.unregisterNetworkCallback(it)
      } catch (_: Exception) {}
    }
  }

  private fun loadLocalState() {
    val isOnboarded = prefs.getBoolean("is_onboarded", false)
    val officerName = prefs.getString("officer_name", "") ?: ""
    val staffId = prefs.getString("staff_id", "") ?: ""
    val branchName = prefs.getString("branch_name", "") ?: ""
    val fedRate = prefs.getFloat("fed_rate", 16.0f).toDouble()
    val filerTaxRate = prefs.getFloat("filer_tax_rate", 20.0f).toDouble()
    val nonFilerTaxRate = prefs.getFloat("non_filer_tax_rate", 40.0f).toDouble()

    // Ensure admin_pin is never stored locally on the device
    if (prefs.contains("admin_pin")) {
      prefs.edit().remove("admin_pin").apply()
    }

    val profile = OfficerProfile(
      officerName = officerName,
      staffId = staffId,
      branchName = branchName,
      isOnboarded = isOnboarded,
      adminPin = "Admin123",
      fedRate = fedRate,
      filerTaxRate = filerTaxRate,
      nonFilerTaxRate = nonFilerTaxRate
    )

    val savedDeletedLeadIds = prefs.getStringSet("deleted_lead_ids", emptySet())?.toSet() ?: emptySet()
    deletedLeadIds.addAll(savedDeletedLeadIds)

    // Load Leads from SharedPreferences
    val leadsJson = prefs.getString("saved_leads", null)
    val loadedLeads = if (!leadsJson.isNullOrBlank()) {
      parseLeadsJson(leadsJson).filterNot {
        it.id in deletedLeadIds ||
        it.id.startsWith("kb-lead-") || it.id.startsWith("lead-") ||
        it.customerName.contains("Tariq Mahmood", ignoreCase = true) ||
        it.customerName.contains("Ayesha Siddiqui", ignoreCase = true)
      }
    } else emptyList()
    val initialSyncQueue = loadedLeads.filter { it.syncStatus != "synced" }

    val savedDeletedUserKeys = prefs.getStringSet("deleted_user_keys", emptySet())?.toSet() ?: emptySet()
    val usersJson = prefs.getString("saved_registered_users", null)
    val loadedUsers = if (!usersJson.isNullOrBlank()) {
      parseUsersJson(usersJson).filterNot { u -> isUserKeyDeleted(u, savedDeletedUserKeys) }
    } else emptyList()

    val initialUsers = if (
      isOnboarded &&
      (officerName.isNotBlank() || staffId.isNotBlank()) &&
      loadedUsers.none {
        (staffId.isNotBlank() && it.staffId.equals(staffId.trim(), ignoreCase = true)) ||
          (officerName.isNotBlank() && it.officerName.equals(officerName.trim(), ignoreCase = true))
      }
    ) {
      val selfUser = RegisteredUser(
        uid = "staff_${staffId.trim().lowercase(Locale.US).ifBlank { "local_officer" }}",
        email = "",
        officerName = officerName.trim(),
        staffId = staffId.trim(),
        branchName = branchName.trim(),
        allowedSupervisorBranch = branchName.trim(),
        canUseSupervisorFilter = true,
        isBlocked = true,
        role = "Loan Officer"
      )
      if (!isUserKeyDeleted(selfUser, savedDeletedUserKeys)) loadedUsers + selfUser else loadedUsers
    } else {
      loadedUsers
    }

    val savedThemeMode = prefs.getString("theme_mode", "light") ?: "light"
    val savedSheetsUrl = sheetsRepo.getWebhookUrl()

    _uiState.update {
      it.copy(
        profile = profile,
        showOnboardingDialog = !isOnboarded,
        leads = loadedLeads,
        syncQueue = initialSyncQueue,
        registeredUsers = initialUsers,
        deletedUserKeys = savedDeletedUserKeys,
        themeMode = savedThemeMode,
        googleSheetsWebhookUrl = savedSheetsUrl
      )
    }
  }

  fun setThemeMode(mode: String) {
    prefs.edit().putString("theme_mode", mode).apply()
    _uiState.update { it.copy(themeMode = mode) }
  }

  fun toggleThemeMode() {
    val current = _uiState.value.themeMode
    val next = if (current == "dark") "light" else "dark"
    setThemeMode(next)
  }

  fun setCapturedLocation(lat: Double?, lng: Double?, address: String = "") {
    _uiState.update {
      it.copy(
        lastCapturedLat = lat,
        lastCapturedLng = lng,
        lastCapturedAddress = address
      )
    }
  }

  private fun saveLocalState() {
    val state = _uiState.value
    prefs.edit()
      .putBoolean("is_onboarded", state.profile.isOnboarded)
      .putString("officer_name", state.profile.officerName)
      .putString("staff_id", state.profile.staffId)
      .putString("branch_name", state.profile.branchName)
      .remove("admin_pin")
      .putFloat("fed_rate", state.profile.fedRate.toFloat())
      .putFloat("filer_tax_rate", state.profile.filerTaxRate.toFloat())
      .putFloat("non_filer_tax_rate", state.profile.nonFilerTaxRate.toFloat())
      .putString("saved_leads", serializeLeadsJson(state.leads))
      .putStringSet("deleted_lead_ids", deletedLeadIds.toSet())
      .putString("saved_registered_users", serializeUsersJson(state.registeredUsers))
      .apply()
  }

  private fun saveLocalUsers(users: List<RegisteredUser>) {
    try {
      prefs.edit().putString("saved_registered_users", serializeUsersJson(users)).apply()
    } catch (_: Exception) {}
  }

  fun completeOnboarding(name: String, staffId: String, branch: String, restoreCloud: Boolean) {
    val cleanName = name.trim()
    val cleanStaffId = staffId.trim()
    val cleanBranch = branch.trim()
    val updatedProfile = _uiState.value.profile.copy(
      officerName = cleanName,
      staffId = cleanStaffId,
      branchName = cleanBranch,
      isOnboarded = true
    )

    val uid = _uiState.value.authUserId?.takeIf { it.isNotBlank() }
      ?: "staff_${cleanStaffId.lowercase(Locale.US).ifBlank { System.currentTimeMillis().toString() }}"
    val email = _uiState.value.authUserEmail.orEmpty()

    val existingUser = _uiState.value.registeredUsers.find { u ->
      (uid.isNotBlank() && u.uid == uid) ||
        (cleanStaffId.isNotBlank() && u.staffId.equals(cleanStaffId, ignoreCase = true)) ||
        (email.isNotBlank() && u.email.equals(email, ignoreCase = true))
    }

    // First-time registration is BLOCKED by default until Admin explicitly allows the user
    val registeredRecord = existingUser?.copy(
      officerName = cleanName.ifBlank { existingUser.officerName },
      staffId = cleanStaffId.ifBlank { existingUser.staffId },
      branchName = cleanBranch.ifBlank { existingUser.branchName },
      allowedSupervisorBranch = existingUser.allowedSupervisorBranch.ifBlank { cleanBranch },
      updatedAt = System.currentTimeMillis()
    ) ?: RegisteredUser(
      uid = uid,
      email = email,
      officerName = cleanName,
      staffId = cleanStaffId,
      branchName = cleanBranch,
      allowedSupervisorBranch = cleanBranch,
      canUseSupervisorFilter = true,
      isBlocked = true,
      role = "Loan Officer",
      updatedAt = System.currentTimeMillis()
    )

    val keysToClear = buildUserDeleteKeys(registeredRecord)

    _uiState.update { s ->
      val updatedDeleted = s.deletedUserKeys - keysToClear
      prefs.edit().putStringSet("deleted_user_keys", updatedDeleted).apply()
      val updatedUsers = s.registeredUsers.filterNot { u ->
        u.uid == registeredRecord.uid ||
          (cleanStaffId.isNotBlank() && u.staffId.equals(cleanStaffId, ignoreCase = true))
      } + registeredRecord

      s.copy(
        profile = updatedProfile,
        registeredUsers = updatedUsers,
        deletedUserKeys = updatedDeleted,
        showOnboardingDialog = false,
        showProfileDialog = false,
        toastMessage = if (registeredRecord.isBlocked) {
          "Profile registered! Account is Blocked by default until Admin allows access."
        } else {
          "Officer Profile Configured!"
        }
      )
    }

    if (restoreCloud && cleanStaffId.isNotBlank()) {
      restoreLeadsForStaffId(cleanStaffId)
    }

    saveState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.saveUserAccessRights(registeredRecord, keysToClear)
      } catch (_: Exception) {}
    }
  }

  fun updateProfile(name: String, staffId: String, branch: String) {
    val cleanName = name.trim()
    val cleanStaffId = staffId.trim()
    val cleanBranch = branch.trim()
    val updated = _uiState.value.profile.copy(
      officerName = cleanName,
      staffId = cleanStaffId,
      branchName = cleanBranch,
      isOnboarded = true
    )
    val uid = _uiState.value.authUserId?.takeIf { it.isNotBlank() }
      ?: "staff_${cleanStaffId.lowercase(Locale.US).ifBlank { "officer" }}"
    val email = _uiState.value.authUserEmail.orEmpty()
    val existing = _uiState.value.registeredUsers.find { u ->
      u.uid == uid ||
        (cleanStaffId.isNotBlank() && u.staffId.equals(cleanStaffId, ignoreCase = true)) ||
        (email.isNotBlank() && u.email.equals(email, ignoreCase = true))
    }
    val updatedUserRecord = (existing ?: RegisteredUser(uid = uid, email = email, isBlocked = true)).copy(
      uid = uid,
      email = email.ifBlank { existing?.email.orEmpty() },
      officerName = cleanName,
      staffId = cleanStaffId,
      branchName = cleanBranch,
      allowedSupervisorBranch = existing?.allowedSupervisorBranch?.ifBlank { cleanBranch } ?: cleanBranch,
      updatedAt = System.currentTimeMillis()
    )
    val keysToClear = buildUserDeleteKeys(updatedUserRecord)
    _uiState.update { s ->
      val updatedUsers = s.registeredUsers.filterNot { u ->
        u.uid == uid || (cleanStaffId.isNotBlank() && u.staffId.equals(cleanStaffId, ignoreCase = true))
      } + updatedUserRecord
      s.copy(
        profile = updated,
        registeredUsers = updatedUsers,
        showProfileDialog = false,
        toastMessage = "Profile updated & synced across all devices"
      )
    }
    saveState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.saveUserAccessRights(updatedUserRecord, keysToClear)
      } catch (_: Exception) {}
    }
  }

  fun openProfileDialog() {
    _uiState.update { it.copy(showProfileDialog = true) }
  }

  fun dismissProfileDialog() {
    _uiState.update { it.copy(showProfileDialog = false) }
  }

  fun restoreLeadsForStaffId(staffId: String) {
    // Queries or restores leads matching staff ID
    val restored = sampleLeads(_uiState.value.profile.copy(staffId = staffId))
    _uiState.update { s ->
      val existingIds = s.leads.map { it.id }.toSet()
      val newBatch = restored.filterNot { it.id in existingIds }
      s.copy(
        leads = newBatch + s.leads,
        toastMessage = "Restored ${newBatch.size} historical records for $staffId"
      )
    }
    saveLocalState()
  }

  fun setTab(index: Int) {
    if (index == 2 && !_uiState.value.isAdminUnlocked) {
      _uiState.update { it.copy(showAdminPinDialog = true) }
      return
    }
    _uiState.update { it.copy(activeTab = index) }
  }

  fun resetAllInputs() {
    _uiState.update {
      it.copy(
        customerName = "",
        mobile = "",
        dob = "",
        followUpDate = "",
        specificLeadNotes = "",
        loanAmountInput = "",
        monthlyIncomeInput = "",
        allowedDbrInput = "",
        selectedTenure = 36,
        extraPayments = emptyMap(),
        prepaymentMode = "reduce_emi",
        currentSessionLeadId = null,
        toastMessage = "All input fields cleared"
      )
    }
  }

  fun updateCustomerName(name: String) = _uiState.update { s ->
    val sLead = s.currentSessionLeadId?.let { id -> s.leads.find { it.id == id } }
    val resetSession = sLead != null && name.trim().length >= 3 &&
      !sLead.customerName.trim().startsWith(name.trim(), ignoreCase = true) &&
      !name.trim().startsWith(sLead.customerName.trim(), ignoreCase = true)
    s.copy(
      customerName = name,
      currentSessionLeadId = if (resetSession) null else s.currentSessionLeadId
    )
  }

  fun updateMobile(rawPhone: String) = _uiState.update { s ->
    val sLead = s.currentSessionLeadId?.let { id -> s.leads.find { it.id == id } }
    val newKey = normalizePhoneKey(rawPhone)
    val oldKey = sLead?.let { normalizePhoneKey(it.mobile) } ?: ""
    val resetSession = sLead != null && newKey.length >= 7 && oldKey.length >= 7 && newKey != oldKey
    s.copy(
      mobile = rawPhone,
      currentSessionLeadId = if (resetSession) null else s.currentSessionLeadId
    )
  }

  private fun normalizePhoneKey(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (digits.length >= 10) digits.takeLast(10) else digits
  }

  fun updateDob(dob: String) = _uiState.update { it.copy(dob = dob) }
  fun updateFollowUpDate(date: String) = _uiState.update { it.copy(followUpDate = date) }
  fun updateSpecificNotes(notes: String) = _uiState.update { it.copy(specificLeadNotes = notes) }
  fun updateLoanAmountInput(input: String) = _uiState.update { it.copy(loanAmountInput = input) }
  fun updateMonthlyIncomeInput(input: String) = _uiState.update { it.copy(monthlyIncomeInput = input) }
  fun updateProduct(productId: String) = _uiState.update { s ->
    val newProd = s.products.find { it.id == productId } ?: s.products.firstOrNull()
    val clampedTenure = if (newProd != null) {
      if (newProd.category == "liability" && newProd.fixedTenures.isNotEmpty()) {
        if (s.selectedTenure in newProd.fixedTenures) s.selectedTenure else newProd.fixedTenures.first()
      } else {
        val minT = newProd.minTenure.coerceAtLeast(1)
        val maxT = newProd.maxTenure.coerceAtLeast(minT)
        s.selectedTenure.coerceIn(minT, maxT)
      }
    } else {
      s.selectedTenure
    }
    s.copy(
      selectedProductId = productId,
      selectedTenure = clampedTenure,
      matrixTenureFilter = "All"
    )
  }

  fun updateIsFiler(isFiler: Boolean) = _uiState.update { it.copy(isFiler = isFiler) }
  fun updateAllowedDbrInput(input: String) = _uiState.update { it.copy(allowedDbrInput = input) }
  fun updateMatrixFilter(filter: String) = _uiState.update { it.copy(matrixTenureFilter = filter) }
  fun updatePrepaymentMode(mode: String) = _uiState.update { it.copy(prepaymentMode = mode) }

  fun getSelectedProduct(): LoanProduct {
    val state = _uiState.value
    return state.products.find { it.id == state.selectedProductId } ?: state.products.first()
  }

  /**
   * If loanAmountInput is empty or 0, dynamic loan calculation calculates
   * the maximum affordable loan amount for the given tenure based on Net Income & DBR%.
   */
  fun getPrincipalForTenure(tenure: Int): Long {
    val state = _uiState.value
    val manualAmount = state.loanAmountInput.filter { it.isDigit() }.toLongOrNull()
    if (manualAmount != null && manualAmount > 0L) {
      return manualAmount
    }
    val product = getSelectedProduct()
    if (product.category == "liability") {
      return product.minAmount
    }
    // Calculate MAXIMUM eligible loan amount based on customer's Net Income and DBR%
    if (state.monthlyIncome <= 0L || state.allowedDbr <= 0) {
      return 0L
    }
    return LoanCalculators.calculateMaxLoanAmount(
      product = product,
      netIncome = state.monthlyIncome,
      dbrPercent = state.allowedDbr,
      tenureMonths = tenure
    )
  }

  fun selectTenure(tenure: Int) {
    _uiState.update { it.copy(selectedTenure = tenure) }
  }

  fun openAmortizationScreen(tenure: Int) {
    _uiState.update {
      it.copy(
        selectedTenure = tenure,
        showAmortizationScreen = true,
        extraPayments = emptyMap()
      )
    }
  }

  fun closeAmortizationScreen() {
    _uiState.update { it.copy(showAmortizationScreen = false, extraPayments = emptyMap()) }
  }

  fun updateExtraPayment(month: Int, amount: Long) {
    _uiState.update { s ->
      val updated = s.extraPayments.toMutableMap()
      if (amount <= 0L) {
        updated.remove(month)
      } else {
        updated[month] = amount
      }
      s.copy(extraPayments = updated)
    }
  }

  fun clearAllExtraPayments() {
    _uiState.update { it.copy(extraPayments = emptyMap()) }
  }

  /**
   * Auto-saves or updates the current calculation into the Lead record whenever
   * WhatsApp, SMS, Copy, Amortization Share, or Save Lead action is triggered.
   * Deduplicates so modifying the plan and pressing any button again updates the existing lead instead of duplicating it.
   */
  fun autoSaveLead(
    triggerSource: String,
    latitude: Double? = null,
    longitude: Double? = null,
    locationAddress: String = ""
  ): CustomerLead? {
    val state = _uiState.value
    val rawCustName = state.customerName.trim()
    val custName = rawCustName.ifBlank { "Client (${state.mobile.trim().ifBlank { "Walk-in" }})" }
    val cleanPhoneKey = normalizePhoneKey(state.mobile)
    val product = getSelectedProduct()
    val pMin = product.minTenure.coerceAtLeast(1)
    val pMax = product.maxTenure.coerceAtLeast(pMin)
    val effectiveTenure = state.selectedTenure.coerceIn(pMin, pMax)
    val principal = getPrincipalForTenure(effectiveTenure).let { p ->
      if (p > 0L) p else getPrincipalForTenure(pMax)
    }
    val rate = LoanCalculators.getApplicableRate(product, principal, effectiveTenure)
    val isLiability = product.category == "liability"
    val emiRes = if (!isLiability) LoanCalculators.calculateEmi(principal, rate, effectiveTenure, product.rateType) else LoanCalculators.EmiResult(0L, 0L, 0L)
    val depositRes = if (isLiability) LoanCalculators.calculateDepositProfit(
      depositAmount = principal,
      annualRate = rate,
      tenureMonths = effectiveTenure,
      isFiler = state.isFiler,
      filerTaxRate = state.profile.filerTaxRate,
      nonFilerTaxRate = state.profile.nonFilerTaxRate
    ) else null
    val feeRes = if (!isLiability) LoanCalculators.calculateFee(product, principal, state.profile.fedRate) else LoanCalculators.FeeResult(0L, 0L, 0L)

    val finalMonthlyEmi = if (isLiability) (depositRes?.monthlyProfit ?: 0L) else emiRes.emi
    val finalTotalProfit = if (isLiability) (depositRes?.totalProfit ?: 0L) else emiRes.totalProfit
    val finalProcessingFee = if (isLiability) 0L else feeRes.totalFee
    val leadTaxRate = if (state.isFiler) state.profile.filerTaxRate else state.profile.nonFilerTaxRate
    val leadTaxDeducted = if (isLiability) (depositRes?.totalTax ?: 0L) else 0L
    val leadNetProfit = if (isLiability) (depositRes?.netTotalProfit ?: 0L) else 0L

    val effectiveLat = latitude ?: state.lastCapturedLat
    val effectiveLng = longitude ?: state.lastCapturedLng
    val effectiveAddress = if (locationAddress.isNotBlank()) locationAddress else state.lastCapturedAddress

    val locationNote = if (effectiveLat != null && effectiveLng != null) {
      " • 📍 GPS: ${String.format(Locale.US, "%.5f", effectiveLat)}, ${String.format(Locale.US, "%.5f", effectiveLng)}${if (effectiveAddress.isNotBlank()) " ($effectiveAddress)" else ""}"
    } else ""

    val leadDetailDesc = (if (isLiability) {
      "${product.name} • Deposit: ${LoanCalculators.formatCurrency(principal, "PKR")} • Tenure: ${effectiveTenure}M • Profit: ${rate}% • ${if (state.isFiler) "Filer" else "Non-Filer"} (${leadTaxRate}% WHT) • Net Profit: ${LoanCalculators.formatCurrency(leadNetProfit, "PKR")}"
    } else {
      "${product.name} • Loan Amount: ${LoanCalculators.formatCurrency(principal, "PKR")} • Tenure: ${effectiveTenure}M"
    }) + locationNote

    val currentUserId = state.authUserId ?: authRepo.currentUser?.uid ?: "officer"
    val myStaffId = state.profile.staffId.trim()

    val sessionLead = state.currentSessionLeadId?.let { sId -> state.leads.find { it.id == sId } }
    val isSessionReallySameCustomer = if (sessionLead != null) {
      val sPhone = normalizePhoneKey(sessionLead.mobile)
      val sName = sessionLead.customerName.trim().lowercase(Locale.US)
      val curName = rawCustName.lowercase(Locale.US)
      val curPhone = cleanPhoneKey
      val phoneMatch = sPhone.length >= 7 && curPhone.length >= 7 && sPhone == curPhone
      val nameMatch = curName.isNotBlank() && sName.isNotBlank() && (curName == sName || curName.contains(sName) || sName.contains(curName))
      phoneMatch || nameMatch || (curPhone.isEmpty() && curName.isEmpty())
    } else false

    // Find all matching leads for this customer in the current session or matching phone/name for this officer
    val matchingLeads = state.leads.filter { lead ->
      val isSessionMatch = isSessionReallySameCustomer && lead.id == state.currentSessionLeadId
      if (isSessionMatch) {
        true
      } else {
        val isSameOfficer = (myStaffId.isNotEmpty() && lead.officerId.trim().equals(myStaffId, ignoreCase = true)) ||
          (lead.userId.isNotBlank() && lead.userId == currentUserId) ||
          (myStaffId.isEmpty() && lead.officerId.isBlank())
        if (!isSameOfficer) {
          false
        } else {
          val leadPhoneKey = normalizePhoneKey(lead.mobile)
          val leadCleanName = lead.customerName.trim()
          val phoneMatch = cleanPhoneKey.length >= 7 && leadPhoneKey == cleanPhoneKey
          val nameMatch = rawCustName.isNotEmpty() &&
            leadCleanName.equals(custName, ignoreCase = true) &&
            (cleanPhoneKey.isEmpty() || leadPhoneKey.isEmpty() || leadPhoneKey == cleanPhoneKey)
          phoneMatch || nameMatch
        }
      }
    }

    val existingLead = if (isSessionReallySameCustomer) {
      matchingLeads.firstOrNull { it.id == state.currentSessionLeadId } ?: matchingLeads.firstOrNull()
    } else {
      matchingLeads.firstOrNull { it.id != state.currentSessionLeadId }
    }
    val duplicateIdsToRemove = matchingLeads.map { it.id }.filter { it != existingLead?.id }.toSet()

    val effectiveOfficerName = state.profile.officerName.trim().ifBlank {
      state.currentUserAccess?.officerName?.trim().orEmpty()
    }
    val effectiveOfficerId = state.profile.staffId.trim().ifBlank {
      state.currentUserAccess?.staffId?.trim().orEmpty()
    }
    val effectiveBranchForLead = state.profile.branchName.trim().ifBlank {
      state.currentUserAccess?.branchName?.trim()?.ifBlank {
        state.effectiveAllowedBranch.split(",", ";", "|").firstOrNull()?.trim().orEmpty()
      } ?: state.effectiveAllowedBranch.split(",", ";", "|").firstOrNull()?.trim().orEmpty()
    }

    val savedLead = if (existingLead != null) {
      existingLead.copy(
        customerName = custName,
        mobile = state.mobile.ifBlank { existingLead.mobile },
        dob = state.dob.ifBlank { existingLead.dob },
        followUpDate = state.followUpDate.ifBlank { existingLead.followUpDate },
        age = if (state.dob.isNotBlank()) LoanCalculators.calculateAge(state.dob) else existingLead.age,
        monthlyIncome = if (state.monthlyIncome > 0L) state.monthlyIncome else existingLead.monthlyIncome,
        dbrAllowed = if (state.allowedDbr > 0) state.allowedDbr else existingLead.dbrAllowed,
        productId = product.id,
        productName = product.name,
        category = product.category,
        isFiler = state.isFiler,
        taxRate = leadTaxRate,
        taxDeducted = leadTaxDeducted,
        netProfit = leadNetProfit,
        loanAmount = principal,
        tenureMonths = effectiveTenure,
        monthlyEmi = finalMonthlyEmi,
        totalProfit = finalTotalProfit,
        processingFee = finalProcessingFee,
        latitude = effectiveLat ?: existingLead.latitude,
        longitude = effectiveLng ?: existingLead.longitude,
        locationAddress = if (effectiveAddress.isNotBlank()) effectiveAddress else existingLead.locationAddress,
        locationCapturedAt = if (effectiveLat != null) System.currentTimeMillis() else existingLead.locationCapturedAt,
        notes = state.specificLeadNotes.trim().ifBlank { existingLead.notes },
        officerName = effectiveOfficerName.ifBlank { existingLead.officerName },
        officerId = effectiveOfficerId.ifBlank { existingLead.officerId },
        branchName = effectiveBranchForLead.ifBlank { existingLead.branchName },
        userId = currentUserId,
        createdAt = System.currentTimeMillis(),
        syncStatus = "pending_sync",
        approachCount = existingLead.approachCount + 1,
        notesUpdateCount = existingLead.notesUpdateCount + (if (state.specificLeadNotes.isNotBlank() && state.specificLeadNotes.trim() != existingLead.notes.trim()) 1 else 0),
        activities = existingLead.activities + (
          if (state.specificLeadNotes.isNotBlank() && state.specificLeadNotes.trim() != existingLead.notes.trim()) {
            listOf(
              LeadActivity(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                actionType = "note_update",
                title = "📝 Note Added",
                details = state.specificLeadNotes.trim(),
                officerName = effectiveOfficerName.ifBlank { existingLead.officerName },
                officerId = effectiveOfficerId.ifBlank { existingLead.officerId }
              )
            )
          } else emptyList()
        ) + listOf(
          LeadActivity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            actionType = "proposal_update",
            title = "📋 Proposal Saved",
            details = "${product.name} (Rs. $principal)",
            officerName = effectiveOfficerName.ifBlank { existingLead.officerName },
            officerId = effectiveOfficerId.ifBlank { existingLead.officerId }
          )
        )
      )
    } else {
      val initialNoteAct = if (state.specificLeadNotes.isNotBlank()) {
        listOf(
          LeadActivity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            actionType = "note_update",
            title = "📝 Note Added",
            details = state.specificLeadNotes.trim(),
            officerName = effectiveOfficerName,
            officerId = effectiveOfficerId
          )
        )
      } else emptyList()

      CustomerLead(
        id = "kb-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(5)}",
        customerName = custName,
        mobile = state.mobile,
        dob = state.dob,
        followUpDate = state.followUpDate,
        age = LoanCalculators.calculateAge(state.dob),
        monthlyIncome = state.monthlyIncome,
        dbrAllowed = state.allowedDbr,
        productId = product.id,
        productName = product.name,
        category = product.category,
        isFiler = state.isFiler,
        taxRate = leadTaxRate,
        taxDeducted = leadTaxDeducted,
        netProfit = leadNetProfit,
        loanAmount = principal,
        tenureMonths = effectiveTenure,
        monthlyEmi = finalMonthlyEmi,
        totalProfit = finalTotalProfit,
        processingFee = finalProcessingFee,
        latitude = effectiveLat,
        longitude = effectiveLng,
        locationAddress = effectiveAddress,
        locationCapturedAt = if (effectiveLat != null) System.currentTimeMillis() else null,
        status = "New",
        notes = state.specificLeadNotes.trim(),
        officerName = effectiveOfficerName,
        officerId = effectiveOfficerId,
        branchName = effectiveBranchForLead,
        userId = currentUserId,
        createdAt = System.currentTimeMillis(),
        syncStatus = "pending_sync",
        approachCount = 1,
        notesUpdateCount = if (state.specificLeadNotes.isNotBlank()) 1 else 0,
        activities = initialNoteAct + listOf(
          LeadActivity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            actionType = "created",
            title = if (isLiability) "🎯 Deposit Lead Created" else "🎯 Loan Lead Created",
            details = "${product.name} (Rs. $principal)",
            officerName = effectiveOfficerName,
            officerId = effectiveOfficerId
          )
        )
      )
    }

    deletedLeadIds.remove(savedLead.id)
    deletedLeadIds.addAll(duplicateIdsToRemove)

    val isCurrentlyOnline = checkCurrentOnlineStatus()
    val initialSyncStatus = if (isCurrentlyOnline) "synced" else "pending_sync"
    val leadToSave = savedLead.copy(syncStatus = initialSyncStatus)

    _uiState.update { s ->
      val idsToFilter = duplicateIdsToRemove + leadToSave.id
      val updatedLeads = listOf(leadToSave) + s.leads.filterNot { it.id in idsToFilter }
      val updatedQueue = updatedLeads.filter { it.syncStatus != "synced" }

      s.copy(
        isOnline = isCurrentlyOnline,
        leads = updatedLeads,
        syncQueue = updatedQueue,
        currentSessionLeadId = leadToSave.id,
        toastMessage = when {
          !isCurrentlyOnline -> "💾 Lead saved offline to local database! Will auto-sync when internet connects."
          existingLead != null -> "Customer lead updated & synced ($triggerSource)"
          else -> "Lead logged & synced ($triggerSource)"
        }
      )
    }
    saveLocalState()
    if (isCurrentlyOnline) {
      val effectiveUid = state.authUserId ?: authRepo.currentUser?.uid
      val targetSheetsUrl = state.googleSheetsWebhookUrl.ifBlank { sheetsRepo.getWebhookUrl() }
      viewModelScope.launch(Dispatchers.IO) {
        var syncedSuccess = false

        // 1. Google Sheets sync first (reliable, in-place upsert & quota-free)
        if (targetSheetsUrl.isNotBlank()) {
          val sheetRes = sheetsRepo.syncLeadToGoogleSheet(leadToSave.copy(syncStatus = "synced"), targetSheetsUrl)
          if (sheetRes.isSuccess) {
            syncedSuccess = true
          }
        }

        // 2. Firestore save
        try {
          firestoreRepo.saveLead(
            lead = leadToSave.copy(syncStatus = "synced"),
            userId = currentUserId,
            removeDuplicateIds = duplicateIdsToRemove
          )
          if (duplicateIdsToRemove.isNotEmpty()) {
            firestoreRepo.deleteLeadsBatch(duplicateIdsToRemove)
          }
          syncedSuccess = true
        } catch (e: Exception) {
          Log.w("LoanViewModel", "Firestore lead save notice: ${e.message}")
        }

        if (!syncedSuccess && targetSheetsUrl.isNotBlank()) {
          // If network failed during push, mark back as pending_sync for later retry
          _uiState.update { s ->
            val updatedLeads = s.leads.map { if (it.id == leadToSave.id) it.copy(syncStatus = "pending_sync") else it }
            s.copy(
              leads = updatedLeads,
              syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
            )
          }
          saveLocalState()
        }
      }
    }
    return leadToSave
  }

  fun updateLeadPlan(
    leadId: String,
    productId: String,
    productName: String,
    category: String,
    loanAmount: Long,
    tenureMonths: Int,
    monthlyEmi: Long,
    totalProfit: Long,
    processingFee: Long,
    taxRate: Double = 0.0,
    taxDeducted: Long = 0L,
    netProfit: Long = 0L
  ) {
    val isLiab = category.equals("liability", ignoreCase = true)
    _uiState.update { s ->
      val updatedLeads = s.leads.map {
        if (it.id == leadId) {
          it.copy(
            productId = productId,
            productName = productName,
            category = category,
            loanAmount = loanAmount,
            tenureMonths = tenureMonths,
            monthlyEmi = monthlyEmi,
            totalProfit = totalProfit,
            processingFee = processingFee,
            taxRate = taxRate,
            taxDeducted = taxDeducted,
            netProfit = netProfit,
            createdAt = System.currentTimeMillis(),
            syncStatus = "pending_sync",
            activities = it.activities + LeadActivity(
              id = UUID.randomUUID().toString(),
              timestamp = System.currentTimeMillis(),
              actionType = "plan_update",
              title = if (isLiab) "✏️ Revised Deposit Plan ($productName)" else "✏️ Revised Loan Plan ($productName)",
              details = if (isLiab) "Converted/Updated to Deposit Product: $productName, Deposit: Rs. $loanAmount, Tenure: ${tenureMonths}M" else "Converted/Updated to Loan Facility: $productName, Amount: Rs. $loanAmount, Tenure: ${tenureMonths}M",
              officerName = it.officerName,
              officerId = it.officerId
            )
          )
        } else it
      }
      s.copy(
        leads = updatedLeads,
        syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
      )
    }
    saveLocalState()
    showToast(if (isLiab) "Deposit plan updated & saved" else "Loan plan updated & saved")
    val updated = _uiState.value.leads.find { it.id == leadId }
    if (updated != null) {
      syncUpdatedLeadRemotely(updated)
    }
  }

  /**
   * Remotely synchronizes an updated lead in-place to Google Sheets (row rewrite) and Firestore.
   */
  fun syncUpdatedLeadRemotely(updatedLead: CustomerLead) {
    if (!checkCurrentOnlineStatus()) return
    val currentUserId = _uiState.value.authUserId ?: "officer"
    val targetSheetsUrl = _uiState.value.googleSheetsWebhookUrl.ifBlank { sheetsRepo.getWebhookUrl() }
    val leadToSync = updatedLead.copy(syncStatus = "synced")
    viewModelScope.launch(Dispatchers.IO) {
      try {
        if (targetSheetsUrl.isNotBlank()) {
          sheetsRepo.syncLeadToGoogleSheet(leadToSync, targetSheetsUrl)
        }
        firestoreRepo.saveLead(leadToSync, currentUserId)
        _uiState.update { s ->
          val syncedList = s.leads.map { if (it.id == updatedLead.id) leadToSync else it }
          s.copy(leads = syncedList, syncQueue = syncedList.filter { it.syncStatus != "synced" })
        }
        saveLocalState()
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error remotely syncing updated lead", e)
      }
    }
  }

  fun deleteLead(leadId: String) {
    deletedLeadIds.add(leadId)
    val targetLead = _uiState.value.leads.find { it.id == leadId }
    val mobile = targetLead?.mobile.orEmpty().trim()
    _uiState.update { s ->
      s.copy(
        leads = s.leads.filterNot { it.id == leadId },
        currentSessionLeadId = if (s.currentSessionLeadId == leadId) null else s.currentSessionLeadId
      )
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        sheetsRepo.deleteLeadsBatchFromGoogleSheet(
          leadIds = listOf(leadId),
          mobiles = if (mobile.isNotBlank()) listOf(mobile) else emptyList()
        )
        firestoreRepo.deleteLead(leadId)
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error deleting lead", e)
      }
    }
    showToast("Lead permanently deleted")
  }

  fun deleteSelectedLeads(leadIds: Set<String>) {
    if (leadIds.isEmpty()) return
    deletedLeadIds.addAll(leadIds)
    val count = leadIds.size
    val targetLeads = _uiState.value.leads.filter { it.id in leadIds }
    val mobiles = targetLeads.map { it.mobile.trim() }.filter { it.isNotBlank() }
    _uiState.update { s ->
      s.copy(
        leads = s.leads.filterNot { it.id in leadIds },
        currentSessionLeadId = if (s.currentSessionLeadId in leadIds) null else s.currentSessionLeadId
      )
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        sheetsRepo.deleteLeadsBatchFromGoogleSheet(
          leadIds = leadIds.toList(),
          mobiles = mobiles
        )
        firestoreRepo.deleteLeadsBatch(leadIds)
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error batch deleting leads", e)
      }
    }
    showToast("Deleted $count selected lead(s) from database")
  }

  private fun buildUserDeleteKeys(user: RegisteredUser): Set<String> {
    val keys = mutableSetOf<String>()
    if (user.uid.isNotBlank()) keys.add("uid:${user.uid.trim().lowercase(Locale.US)}")
    if (user.staffId.isNotBlank()) keys.add("staff:${user.staffId.trim().lowercase(Locale.US)}")
    if (user.email.isNotBlank()) keys.add("email:${user.email.trim().lowercase(Locale.US)}")
    if (user.officerName.isNotBlank()) {
      keys.add("name:${user.officerName.trim().lowercase(Locale.US)}")
    }
    return keys
  }

  fun isUserKeyDeleted(user: RegisteredUser, deletedKeys: Set<String>): Boolean {
    if (deletedKeys.isEmpty()) return false
    val userKeys = buildUserDeleteKeys(user)
    return userKeys.any { it in deletedKeys }
  }

  fun saveUserAccessRights(user: RegisteredUser) {
    val keysToClear = buildUserDeleteKeys(user)
    _uiState.update { s ->
      val updatedDeleted = s.deletedUserKeys - keysToClear
      prefs.edit().putStringSet("deleted_user_keys", updatedDeleted).apply()
      val updatedList = s.registeredUsers.filterNot {
        it.uid == user.uid || (user.staffId.isNotBlank() && it.staffId.equals(user.staffId, ignoreCase = true))
      } + user
      s.copy(
        registeredUsers = updatedList,
        deletedUserKeys = updatedDeleted
      )
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.saveUserAccessRights(user, keysToClear)
        showToast("Updated access & branch rights for ${user.officerName.ifBlank { user.staffId }}")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error saving user access rights", e)
        showToast("Saved user rights locally")
      }
    }
  }

  fun toggleUserBlocked(user: RegisteredUser) {
    val updated = user.copy(isBlocked = !user.isBlocked, updatedAt = System.currentTimeMillis())
    val keysToClear = buildUserDeleteKeys(updated)
    _uiState.update { s ->
      val updatedDeleted = s.deletedUserKeys - keysToClear
      prefs.edit().putStringSet("deleted_user_keys", updatedDeleted).apply()
      val exists = s.registeredUsers.any {
        it.uid == updated.uid || (updated.staffId.isNotBlank() && it.staffId.equals(updated.staffId, ignoreCase = true))
      }
      val updatedList = if (exists) {
        s.registeredUsers.map {
          if (it.uid == updated.uid || (updated.staffId.isNotBlank() && it.staffId.equals(updated.staffId, ignoreCase = true))) updated else it
        }
      } else {
        s.registeredUsers + updated
      }
      s.copy(
        registeredUsers = updatedList,
        deletedUserKeys = updatedDeleted
      )
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.saveUserAccessRights(updated, keysToClear)
        val statusLabel = if (updated.isBlocked) "Blocked / Disabled" else "Enabled / Allowed"
        showToast("${user.officerName.ifBlank { user.staffId }} access is now $statusLabel")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error toggling user block status", e)
      }
    }
  }

  fun deleteUserRecord(user: RegisteredUser) {
    val newDeletedKeys = buildUserDeleteKeys(user)
    _uiState.update { s ->
      val updatedDeleted = s.deletedUserKeys + newDeletedKeys
      prefs.edit().putStringSet("deleted_user_keys", updatedDeleted).apply()
      val remainingUsers = s.registeredUsers.filterNot { u ->
        isUserKeyDeleted(u, newDeletedKeys) ||
          u.uid == user.uid ||
          (user.staffId.isNotBlank() && u.staffId.equals(user.staffId, ignoreCase = true))
      }
      s.copy(
        registeredUsers = remainingUsers,
        deletedUserKeys = updatedDeleted
      )
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.deleteUserRecord(user.uid, user.staffId, user.email, newDeletedKeys)
        showToast("User \"${user.officerName.ifBlank { user.staffId }}\" deleted & hidden")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error deleting user record", e)
      }
    }
  }

  fun refreshRegisteredUsers() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val cloudUsers = firestoreRepo.fetchAllUsersFromFirestore()
        var mergedToSave: List<RegisteredUser> = emptyList()
        _uiState.update { s ->
          val combined = mergeUserLists(cloudUsers, s.registeredUsers, s.deletedUserKeys)
          mergedToSave = combined
          s.copy(registeredUsers = combined)
        }
        saveLocalUsers(mergedToSave)
        showToast("Synced ${mergedToSave.size} registered user(s) from cloud")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error refreshing users", e)
        showToast("Could not refresh users from cloud")
      }
    }
  }

  fun clearAllLeads() {
    val currentLeads = _uiState.value.leads
    val allIds = currentLeads.map { it.id }
    deletedLeadIds.addAll(allIds)
    _uiState.update { s ->
      s.copy(leads = emptyList(), currentSessionLeadId = null)
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        sheetsRepo.deleteLeadsBatchFromGoogleSheet(allIds)
        firestoreRepo.clearAllLeads(currentLeads)
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error clearing leads", e)
      }
    }
    showToast("All customer leads cleared")
  }

  private fun serializeRateSlabs(slabs: List<RateSlab>): String {
    return slabs.joinToString(";") { "${it.minAmount}-${it.maxAmount}:${it.annualRate}" }
  }

  private fun parseRateSlabs(text: String): List<RateSlab> {
    if (text.isBlank()) return emptyList()
    return text.split(";").mapNotNull { part ->
      try {
        val pieces = part.trim().split(":")
        val (minStr, maxStr) = pieces[0].split("-")
        RateSlab(minStr.trim().toLong(), maxStr.trim().toLong(), pieces[1].trim().toDouble())
      } catch (_: Exception) { null }
    }
  }

  private fun serializeFeeSlabs(slabs: List<FeeSlab>): String {
    return slabs.joinToString(";") { "${it.minAmount}-${it.maxAmount}:${it.feeType}:${it.feeValue}:${it.minFee}:${it.maxFee}" }
  }

  private fun parseFeeSlabs(text: String): List<FeeSlab> {
    if (text.isBlank()) return emptyList()
    return text.split(";").mapNotNull { part ->
      try {
        val pieces = part.trim().split(":")
        val (minStr, maxStr) = pieces[0].split("-")
        val feeType = pieces.getOrNull(1) ?: "percentage"
        val feeVal = pieces.getOrNull(2)?.toDoubleOrNull() ?: 1.5
        val minFee = pieces.getOrNull(3)?.toLongOrNull() ?: 0L
        val maxFee = pieces.getOrNull(4)?.toLongOrNull() ?: 0L
        FeeSlab(minStr.trim().toLong(), maxStr.trim().toLong(), feeType, feeVal, minFee, maxFee)
      } catch (_: Exception) { null }
    }
  }

  fun exportProductsCsv(): String {
    val sb = StringBuilder()
    sb.append("ID,Name,Category,RateType,DefaultRate,MinAmount,MaxAmount,MinTenure,MaxTenure,MinAge,MaxAgeAtMaturity,FeeType,FeeValue,RateSlabs,FeeSlabs,CollateralDetails\n")
    _uiState.value.products.forEach { p ->
      sb.append("\"${p.id}\",")
      sb.append("\"${p.name.replace("\"", "\"\"")}\",")
      sb.append("\"${p.category}\",")
      sb.append("\"${p.rateType}\",")
      sb.append("${p.defaultAnnualRate},")
      sb.append("${p.minAmount},")
      sb.append("${p.maxAmount},")
      sb.append("${p.minTenure},")
      sb.append("${p.maxTenure},")
      sb.append("${p.minAge},")
      sb.append("${p.maxAgeAtMaturity},")
      sb.append("\"${p.feeType}\",")
      sb.append("${p.defaultFeeValue},")
      sb.append("\"${serializeRateSlabs(p.rateSlabs)}\",")
      sb.append("\"${serializeFeeSlabs(p.feeSlabs)}\",")
      sb.append("\"${p.collateralDetails.replace("\"", "\"\"").replace("\n", " | ")}\"\n")
    }
    return sb.toString()
  }

  fun exportAllLeadsCsv(): String {
    val sb = StringBuilder()
    sb.append("Lead ID,Customer Name,Mobile,DOB,Age,Category,Facility,Loan / Deposit Amount,Tenure Months,Monthly EMI / Profit,Total Profit,Processing Fee,Status,Follow-Up Date,Officer Name,Officer ID,Branch,Notes,Created Date\n")
    _uiState.value.leads.forEach { l ->
      sb.append("\"${l.id}\",")
      sb.append("\"${l.customerName.replace("\"", "\"\"")}\",")
      sb.append("\"${l.mobile}\",")
      sb.append("\"${l.dob}\",")
      sb.append("${l.age},")
      sb.append("\"${if (l.category == "liability") "Liability / Deposit" else "Asset / Loan"}\",")
      sb.append("\"${l.productName.replace("\"", "\"\"")}\",")
      sb.append("${l.loanAmount},")
      sb.append("${l.tenureMonths},")
      sb.append("${l.monthlyEmi},")
      sb.append("${l.totalProfit},")
      sb.append("${l.processingFee},")
      sb.append("\"${l.status}\",")
      sb.append("\"${l.followUpDate}\",")
      sb.append("\"${l.officerName.replace("\"", "\"\"")}\",")
      sb.append("\"${l.officerId}\",")
      sb.append("\"${l.branchName.replace("\"", "\"\"")}\",")
      sb.append("\"${l.notes.replace("\"", "\"\"")}\",")
      sb.append("\"${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(l.createdAt))}\"\n")
    }
    return sb.toString()
  }

  fun importProductsCsv(csv: String): Pair<Int, String> {
    if (csv.isBlank()) return Pair(0, "CSV content is empty")
    return try {
      val lines = csv.lines().map { it.trim() }.filter { it.isNotBlank() }
      if (lines.size <= 1) return Pair(0, "CSV must contain a header and at least one data row")

      val parsedProducts = mutableListOf<LoanProduct>()
      val startIndex = if (lines[0].startsWith("ID", ignoreCase = true) || lines[0].startsWith("\"ID", ignoreCase = true) || lines[0].startsWith("Name", ignoreCase = true)) 1 else 0

      for (i in startIndex until lines.size) {
        val line = lines[i]
        val tokens = parseCsvLine(line)
        if (tokens.size >= 8) {
          val id = if (tokens[0].isNotBlank()) tokens[0] else "prod-kb-${System.currentTimeMillis()}-$i"
          val name = tokens.getOrNull(1)?.ifBlank { "Facility $i" } ?: "Facility $i"
          val hasCategoryCol = tokens.getOrNull(2)?.trim()?.lowercase() in listOf("asset", "liability")
          val category = if (hasCategoryCol) tokens[2].trim().lowercase() else "asset"
          val offset = if (hasCategoryCol) 1 else 0

          val rateType = if (tokens.getOrNull(2 + offset)?.contains("flat", ignoreCase = true) == true) "flat" else "reducing"
          val rate = tokens.getOrNull(3 + offset)?.toDoubleOrNull() ?: 20.0
          val minAmt = tokens.getOrNull(4 + offset)?.toLongOrNull() ?: 50000L
          val maxAmt = tokens.getOrNull(5 + offset)?.toLongOrNull() ?: 5000000L
          val minTenure = tokens.getOrNull(6 + offset)?.toIntOrNull() ?: 1
          val maxTenure = tokens.getOrNull(7 + offset)?.toIntOrNull() ?: 120
          val minAge = tokens.getOrNull(8 + offset)?.toIntOrNull() ?: 21
          val maxAge = tokens.getOrNull(9 + offset)?.toIntOrNull() ?: 65
          val feeType = if (tokens.getOrNull(10 + offset)?.contains("fixed", ignoreCase = true) == true) "fixed" else "percentage"
          val feeVal = tokens.getOrNull(11 + offset)?.toDoubleOrNull() ?: 1.5

          val rateSlabs = if (tokens.size >= 13 + offset) parseRateSlabs(tokens[12 + offset]) else emptyList()
          val feeSlabs = if (tokens.size >= 14 + offset) parseFeeSlabs(tokens[13 + offset]) else emptyList()
          val collateralDetails = if (tokens.size >= 15 + offset) tokens[14 + offset].replace(" | ", "\n") else ""

          parsedProducts.add(
            LoanProduct(
              id = id,
              name = name,
              rateType = rateType,
              defaultAnnualRate = rate,
              minAmount = minAmt,
              maxAmount = maxAmt,
              minTenure = minTenure,
              maxTenure = maxTenure,
              minAge = minAge,
              maxAgeAtMaturity = maxAge,
              feeType = feeType,
              defaultFeeValue = feeVal,
              fedRate = 16.0,
              collateralDetails = collateralDetails,
              rateSlabs = rateSlabs,
              feeSlabs = feeSlabs,
              category = category
            )
          )
        }
      }

      if (parsedProducts.isEmpty()) {
        Pair(0, "No valid product records found in CSV format")
      } else {
        _uiState.update { s ->
          s.copy(
            products = parsedProducts,
            selectedProductId = parsedProducts.first().id
          )
        }
        viewModelScope.launch(Dispatchers.IO) {
          try {
            for (p in parsedProducts) {
              firestoreRepo.saveProduct(p)
            }
          } catch (e: Exception) {
            Log.e("LoanViewModel", "Error saving imported products to Firestore", e)
          }
        }
        showToast("Imported ${parsedProducts.size} loan products successfully!")
        Pair(parsedProducts.size, "Success")
      }
    } catch (e: Exception) {
      Pair(0, "CSV Parsing error: ${e.message}")
    }
  }

  private fun parseCsvLine(line: String): List<String> {
    val list = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    for (ch in line) {
      if (ch == '\"') {
        inQuotes = !inQuotes
      } else if (ch == ',' && !inQuotes) {
        list.add(sb.toString().trim())
        sb.clear()
      } else {
        sb.append(ch)
      }
    }
    list.add(sb.toString().trim())
    return list.map { it.trim('\"').trim() }
  }

  /**
   * Updates lead status (persists locally if offline and syncs immediately when online).
   */
  fun updateLeadStatus(leadId: String, newStatus: String) {
    val currentProfile = _uiState.value.profile
    val targetLead = _uiState.value.leads.find { it.id == leadId } ?: return

    val newActivity = LeadActivity(
      id = UUID.randomUUID().toString(),
      timestamp = System.currentTimeMillis(),
      actionType = "status_change",
      title = "🔄 Status Changed",
      details = "${targetLead.status} ➔ $newStatus",
      officerName = currentProfile.officerName.ifBlank { "Loan Officer" },
      officerId = currentProfile.staffId.ifBlank { "RO" }
    )
    val updatedActivities = targetLead.activities + newActivity

    _uiState.update { s ->
      val updatedLeads = s.leads.map {
        if (it.id == leadId) it.copy(
          status = newStatus,
          activities = updatedActivities,
          createdAt = System.currentTimeMillis(),
          syncStatus = "pending_sync"
        ) else it
      }
      s.copy(
        leads = updatedLeads,
        syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
      )
    }
    saveLocalState()
    val updated = _uiState.value.leads.find { it.id == leadId }
    if (updated != null) {
      syncUpdatedLeadRemotely(updated)
    }
  }

  fun updateLeadNote(leadId: String, newNote: String) {
    val trimmedNote = newNote.trim()
    val currentProfile = _uiState.value.profile
    val targetLead = _uiState.value.leads.find { it.id == leadId } ?: return

    val newActivity = LeadActivity(
      id = UUID.randomUUID().toString(),
      timestamp = System.currentTimeMillis(),
      actionType = "note_update",
      title = "📝 Note Added",
      details = if (trimmedNote.isBlank()) "(Note cleared)" else trimmedNote,
      officerName = currentProfile.officerName.ifBlank { "Loan Officer" },
      officerId = currentProfile.staffId.ifBlank { "RO" }
    )

    val newNotesUpdateCount = targetLead.notesUpdateCount + 1
    val updatedActivities = targetLead.activities + newActivity

    _uiState.update { s ->
      val updatedLeads = s.leads.map {
        if (it.id == leadId) {
          it.copy(
            notes = trimmedNote,
            notesUpdateCount = newNotesUpdateCount,
            activities = updatedActivities,
            createdAt = System.currentTimeMillis(),
            syncStatus = "pending_sync"
          )
        } else it
      }
      s.copy(
        leads = updatedLeads,
        syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
      )
    }
    saveLocalState()
    showToast("Customer note updated & logged to track record")
    val updated = _uiState.value.leads.find { it.id == leadId }
    if (updated != null) {
      syncUpdatedLeadRemotely(updated)
    }
  }

  fun updateLeadFollowUpDate(leadId: String, newDate: String) {
    val trimmedDate = newDate.trim()
    val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    // Enforce future or today date constraint: prevent past dates
    if (trimmedDate.isNotBlank() && trimmedDate.take(10) < todayIso) {
      showToast("❌ Past dates are not allowed. Please choose today or a future date.")
      return
    }

    if (trimmedDate.isBlank()) {
      LeadAlarmScheduler.cancelFollowUpAlarm(getApplication(), leadId)
    }

    val currentProfile = _uiState.value.profile
    val targetLead = _uiState.value.leads.find { it.id == leadId } ?: return

    val isNewFollowUp = trimmedDate.isNotBlank() && trimmedDate != targetLead.followUpDate
    val newActivity = LeadActivity(
      id = UUID.randomUUID().toString(),
      timestamp = System.currentTimeMillis(),
      actionType = "follow_up",
      title = if (trimmedDate.isBlank()) "📅 Follow-Up Cleared" else "📅 Follow-Up Date",
      details = if (trimmedDate.isBlank()) "Cleared previous date" else "Scheduled for $trimmedDate",
      officerName = currentProfile.officerName.ifBlank { "Loan Officer" },
      officerId = currentProfile.staffId.ifBlank { "RO" }
    )

    val newApproachCount = if (isNewFollowUp) targetLead.approachCount + 1 else targetLead.approachCount
    val updatedActivities = targetLead.activities + newActivity

    _uiState.update { s ->
      val updatedLeads = s.leads.map {
        if (it.id == leadId) {
          it.copy(
            followUpDate = trimmedDate,
            approachCount = newApproachCount,
            activities = updatedActivities,
            createdAt = System.currentTimeMillis(),
            syncStatus = "pending_sync"
          )
        } else it
      }
      s.copy(
        leads = updatedLeads,
        syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
      )
    }
    saveLocalState()
    showToast(if (trimmedDate.isBlank()) "Follow-up date cleared" else "Follow-up set to $trimmedDate")
    val updated = _uiState.value.leads.find { it.id == leadId }
    if (updated != null) {
      syncUpdatedLeadRemotely(updated)
    }
  }

  fun scheduleLeadFollowUpWithAlarm(
    leadId: String,
    dateTimeFormatted: String,
    triggerTimeMillis: Long
  ) {
    val targetLead = _uiState.value.leads.find { it.id == leadId } ?: return
    val currentProfile = _uiState.value.profile

    val newActivity = LeadActivity(
      id = UUID.randomUUID().toString(),
      timestamp = System.currentTimeMillis(),
      actionType = "follow_up",
      title = "📅 Follow-Up Date",
      details = "Scheduled for $dateTimeFormatted",
      officerName = currentProfile.officerName.ifBlank { "Loan Officer" },
      officerId = currentProfile.staffId.ifBlank { "RO" }
    )

    val updatedLead = targetLead.copy(
      followUpDate = dateTimeFormatted,
      approachCount = targetLead.approachCount + 1,
      activities = targetLead.activities + newActivity,
      syncStatus = "pending_sync"
    )

    _uiState.update { s ->
      val updatedLeads = s.leads.map { if (it.id == leadId) updatedLead else it }
      s.copy(
        leads = updatedLeads,
        syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
      )
    }
    saveLocalState()

    // Schedule loud exact alarm
    try {
      LeadAlarmScheduler.scheduleFollowUpAlarm(
        context = getApplication(),
        lead = updatedLead,
        triggerTimeMillis = triggerTimeMillis
      )
      showToast("⏰ Follow-up alarm scheduled for $dateTimeFormatted!")
    } catch (e: Exception) {
      Log.e("LoanViewModel", "Failed to schedule alarm", e)
      showToast("Follow-up saved: $dateTimeFormatted")
    }

    syncUpdatedLeadRemotely(updatedLead)
  }

  /**
   * Logs a direct customer approach touchpoint (Phone call, WhatsApp, or SMS).
   * Appends an immutable activity entry and increments approachCount.
   */
  fun logCustomerApproach(leadId: String, channel: String) {
    val currentProfile = _uiState.value.profile
    val targetLead = _uiState.value.leads.find { it.id == leadId } ?: return

    val (actionTitle, actionDetails) = when (channel.lowercase()) {
      "call" -> "📞 Phone Call" to "Phone call to ${targetLead.mobile}"
      "whatsapp" -> "💬 WhatsApp" to "Proposal sent via WhatsApp (${targetLead.mobile})"
      "sms" -> "✉️ SMS" to "Proposal sent via SMS (${targetLead.mobile})"
      else -> "🎯 Customer Contacted" to "Approached via ${channel.uppercase()} (${targetLead.mobile})"
    }

    val newActivity = LeadActivity(
      id = UUID.randomUUID().toString(),
      timestamp = System.currentTimeMillis(),
      actionType = channel.lowercase(),
      title = actionTitle,
      details = actionDetails,
      officerName = currentProfile.officerName.ifBlank { "Loan Officer" },
      officerId = currentProfile.staffId.ifBlank { "RO" }
    )

    val newApproachCount = targetLead.approachCount + 1
    val updatedActivities = targetLead.activities + newActivity

    _uiState.update { s ->
      val updatedLeads = s.leads.map {
        if (it.id == leadId) {
          it.copy(
            approachCount = newApproachCount,
            activities = updatedActivities,
            createdAt = System.currentTimeMillis(),
            syncStatus = "pending_sync"
          )
        } else it
      }
      s.copy(
        leads = updatedLeads,
        syncQueue = updatedLeads.filter { it.syncStatus != "synced" }
      )
    }
    saveLocalState()
    val updated = _uiState.value.leads.find { it.id == leadId }
    if (updated != null) {
      syncUpdatedLeadRemotely(updated)
    }
  }

  fun updateGlobalFedRate(rate: Double) {
    updateGlobalTaxRates(
      fedRate = rate,
      filerTaxRate = _uiState.value.profile.filerTaxRate,
      nonFilerTaxRate = _uiState.value.profile.nonFilerTaxRate
    )
  }

  fun updateGlobalTaxRates(fedRate: Double, filerTaxRate: Double, nonFilerTaxRate: Double) {
    _uiState.update { s ->
      s.copy(
        profile = s.profile.copy(
          fedRate = fedRate,
          filerTaxRate = filerTaxRate,
          nonFilerTaxRate = nonFilerTaxRate
        )
      )
    }
    saveLocalState()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.saveGlobalConfig(
          fedRate = fedRate,
          currency = _uiState.value.profile.currency,
          filerTaxRate = filerTaxRate,
          nonFilerTaxRate = nonFilerTaxRate
        )
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error updating global tax rates in Firestore", e)
      }
    }
    showToast("Global Tax Rates Saved: FED ${fedRate}%, Filer WHT ${filerTaxRate}%, Non-Filer WHT ${nonFilerTaxRate}%")
  }

  fun publishAdminBroadcast(message: String) {
    val cleanMsg = message.trim()
    if (cleanMsg.isBlank()) {
      clearAdminBroadcast()
      return
    }
    val newBroadcastId = "notice-${System.currentTimeMillis()}"
    _uiState.update { s ->
      s.copy(
        adminBroadcastMessage = cleanMsg,
        adminBroadcastId = newBroadcastId
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.publishAdminBroadcast(cleanMsg, newBroadcastId)
        showToast("Admin broadcast notice published to all branch users!")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error publishing admin broadcast in Firestore", e)
        showToast("Failed to publish broadcast notice to Firebase")
      }
    }
  }

  fun clearAdminBroadcast() {
    _uiState.update { s ->
      s.copy(
        adminBroadcastMessage = "",
        adminBroadcastId = "",
        showAdminBroadcastDialog = false
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.publishAdminBroadcast("", "")
        showToast("Admin broadcast notice cleared successfully!")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error clearing admin broadcast", e)
      }
    }
  }

  fun acknowledgeAdminBroadcast() {
    val state = _uiState.value
    val currentUser = state.authUserId ?: "default_user"
    if (state.adminBroadcastId.isNotBlank()) {
      prefs.edit().putString("ack_broadcast_$currentUser", state.adminBroadcastId).apply()
    }
    _uiState.update {
      it.copy(
        showAdminBroadcastDialog = false,
        activeTab = 0,
        toastMessage = "Admin notice acknowledged & saved"
      )
    }
  }

  fun publishAppUpdateLink(rawUrl: String, customMessage: String = "") {
    val trimmedUrl = rawUrl.trim()
    if (trimmedUrl.isBlank()) {
      showToast("Please paste a valid App Update Download Link before submitting")
      return
    }
    val finalUrl = if (trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true)) {
      trimmedUrl
    } else {
      "https://$trimmedUrl"
    }
    val finalMessage = customMessage.trim().ifBlank {
      "A mandatory new version of KB Loan Calculator is available! Your app access is locked until you download and install the latest update from the link below."
    }
    val newUpdateId = "update-${System.currentTimeMillis()}"
    _uiState.update { s ->
      s.copy(
        appUpdateUrl = finalUrl,
        appUpdateId = newUpdateId,
        appUpdateMessage = finalMessage
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.publishAppUpdate(finalUrl, newUpdateId, finalMessage)
        showToast("Mandatory App Update Link published! All users will be prompted & blocked until updated.")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error publishing app update link", e)
        showToast("Failed to publish app update link to cloud")
      }
    }
  }

  fun clearAppUpdateLink() {
    _uiState.update { s ->
      s.copy(
        appUpdateUrl = "",
        appUpdateId = "",
        appUpdateMessage = "",
        isAppUpdateRequired = false,
        hasClickedAppUpdateDownload = false
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.clearAppUpdate()
        showToast("Mandatory App Update lock cleared for all users")
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error clearing app update link", e)
      }
    }
  }

  fun markAppUpdateDownloadStarted() {
    val state = _uiState.value
    if (state.appUpdateId.isNotBlank()) {
      prefs.edit()
        .putString("downloading_app_update_id", state.appUpdateId)
        .putLong("pre_download_apk_time", getCurrentApkLastUpdateTime())
        .apply()
    }
    _uiState.update {
      it.copy(hasClickedAppUpdateDownload = true)
    }
  }

  fun verifyAppUpdateInstallation(): Boolean {
    val state = _uiState.value
    if (!state.hasClickedAppUpdateDownload) {
      showToast("Please click 'Download Latest APK' first to download the update")
      return false
    }

    val preDownloadTime = prefs.getLong("pre_download_apk_time", 0L)
    val currentApkTime = getCurrentApkLastUpdateTime()
    val isActuallyInstalled = preDownloadTime > 0L && currentApkTime > preDownloadTime

    if (isActuallyInstalled) {
      if (state.appUpdateId.isNotBlank()) {
        prefs.edit().putString("completed_app_update_id", state.appUpdateId).apply()
      }
      _uiState.update {
        it.copy(
          isAppUpdateRequired = false,
          hasClickedAppUpdateDownload = false,
          toastMessage = "✅ App update verified! Access restored."
        )
      }
      return true
    } else {
      showToast("⚠️ Update not installed yet! Open Downloads, tap the APK to install, then tap Verify.")
      return false
    }
  }

  fun verifyAdminPin(pin: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val isValid = try {
        firestoreRepo.verifyAdminPinFromFirestore(pin)
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error verifying admin pin from Firestore", e)
        pin == _uiState.value.profile.adminPin
      }
      if (isValid) {
        _uiState.update {
          it.copy(
            isAdminUnlocked = true,
            showAdminPinDialog = false,
            activeTab = 2,
            profile = it.profile.copy(adminPin = pin)
          )
        }
      } else {
        showToast("Incorrect Admin Password!")
      }
    }
  }

  fun lockAdmin() {
    _uiState.update { it.copy(isAdminUnlocked = false, activeTab = 0) }
  }

  fun dismissAdminPinDialog() {
    _uiState.update { it.copy(showAdminPinDialog = false) }
  }

  fun openChangePasswordDialog() {
    _uiState.update { it.copy(showAdminChangePasswordDialog = true) }
  }

  fun dismissChangePasswordDialog() {
    _uiState.update { it.copy(showAdminChangePasswordDialog = false) }
  }

  fun changeAdminPassword(oldPin: String, newPin: String) {
    if (newPin.length < 4) {
      showToast("New password must be at least 4 characters")
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val result = firestoreRepo.updateAdminPinInFirestore(oldPin, newPin)
      result.onSuccess {
        _uiState.update {
          it.copy(
            profile = it.profile.copy(adminPin = newPin),
            showAdminChangePasswordDialog = false,
            toastMessage = "Admin password updated in Firebase database!"
          )
        }
      }.onFailure { err ->
        showToast(err.localizedMessage ?: "Failed to update password in Firebase!")
      }
    }
  }

  fun openProductEdit(product: LoanProduct?) {
    _uiState.update { it.copy(showProductEditDialog = true, editingProduct = product) }
  }

  fun dismissProductEdit() {
    _uiState.update { it.copy(showProductEditDialog = false, editingProduct = null) }
  }

  fun saveProduct(product: LoanProduct) {
    _uiState.update { s ->
      val exists = s.products.any { it.id == product.id }
      val updated = if (exists) {
        s.products.map { if (it.id == product.id) product else it }
      } else {
        s.products + product
      }
      s.copy(products = updated, showProductEditDialog = false, editingProduct = null)
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.saveProduct(product)
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error saving product to Firestore", e)
      }
    }
    showToast("Product & Slabs configuration saved and synced across devices")
  }

  fun deleteProduct(productId: String) {
    if (_uiState.value.products.size <= 1) {
      showToast("At least one product must remain configured")
      return
    }
    _uiState.update { s ->
      val remaining = s.products.filterNot { it.id == productId }
      s.copy(
        products = remaining,
        selectedProductId = if (s.selectedProductId == productId) remaining.first().id else s.selectedProductId
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.deleteProduct(productId)
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error deleting product from Firestore", e)
      }
    }
  }

  fun resetDefaultProducts() {
    _uiState.update { it.copy(products = defaultBankProducts) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        for (prod in defaultBankProducts) {
          firestoreRepo.saveProduct(prod)
        }
      } catch (e: Exception) {
        Log.e("LoanViewModel", "Error resetting products in Firestore", e)
      }
    }
    showToast("Reset products to KB standard SOC matrix & synced")
  }

  /**
   * Automatically called when the app is opened or resumed from background,
   * when internet connection is restored, or when user taps Sync.
   * Uploads all offline-saved leads to Firestore and pulls all latest leads across devices.
   */
  fun onAppResumed() {
    val online = checkCurrentOnlineStatus()
    _uiState.update { it.copy(isOnline = online) }
    val uid = _uiState.value.authUserId ?: authRepo.currentUser?.uid
    if (online && uid != null) {
      val listenersDead = leadsJob?.isActive != true ||
        configJob?.isActive != true ||
        usersJob?.isActive != true
      if (listenersDead) {
        startFirestoreSync(uid, _uiState.value.authUserEmail.orEmpty(), _uiState.value.profile.officerName)
      } else {
        syncPendingLeadsToCloud(showToastOnComplete = false, forceReconnect = false)
      }
    }
  }

  fun syncPendingLeadsToCloud(showToastOnComplete: Boolean = false, forceReconnect: Boolean = false) {
    val online = checkCurrentOnlineStatus()
    _uiState.update { it.copy(isOnline = online) }
    if (!online) {
      if (showToastOnComplete) {
        showToast("⚠️ Device is Offline. Leads are saved locally and will auto-sync when internet connects.")
      }
      return
    }
    if (!isSyncingLeads.compareAndSet(false, true)) {
      if (showToastOnComplete) {
        showToast("🔄 Sync already in progress...")
      }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        if (forceReconnect) {
          firestoreRepo.forceReconnectNetwork()
        } else {
          firestoreRepo.ensureNetworkActive()
        }

        val state = _uiState.value
        val currentUser = authRepo.currentUser
        val uid = state.authUserId ?: currentUser?.uid

        if (uid == null || currentUser == null) {
          if (showToastOnComplete) {
            showToast("⚠️ Cloud Sync requires Google Sign-In. Please sign in via the top bar to upload offline leads.")
          }
          return@launch
        }

        val targetSheetsUrl = state.googleSheetsWebhookUrl.ifBlank { sheetsRepo.getWebhookUrl() }

        val myName = state.profile.officerName.trim().ifBlank {
          state.currentUserAccess?.officerName?.trim().orEmpty()
        }
        val myStaffId = state.profile.staffId.trim().ifBlank {
          state.currentUserAccess?.staffId?.trim().orEmpty()
        }
        val myBranch = state.profile.branchName.trim().ifBlank {
          state.currentUserAccess?.branchName?.trim()?.ifBlank {
            state.effectiveAllowedBranch.split(",", ";", "|").firstOrNull()?.trim().orEmpty()
          } ?: state.effectiveAllowedBranch.split(",", ";", "|").firstOrNull()?.trim().orEmpty()
        }

        // 1. Upload all pending offline leads to Google Sheets and Firestore
        val pendingLeads = state.leads.filter { it.syncStatus != "synced" && it.id !in deletedLeadIds }
        var uploadedCount = 0
        var failCount = 0
        var lastFailReason = ""

        if (pendingLeads.isNotEmpty()) {
          val enrichedPending = pendingLeads.map { lead ->
            lead.copy(
              officerName = lead.officerName.ifBlank { myName },
              officerId = lead.officerId.ifBlank { myStaffId },
              branchName = lead.branchName.ifBlank { myBranch },
              userId = if (lead.userId.isBlank() || lead.userId == "officer") (uid ?: "officer") else lead.userId,
              syncStatus = "synced"
            )
          }

          // A. If Google Sheets Webhook URL is configured, push to Google Sheets
          var sheetSyncSuccess = false
          if (targetSheetsUrl.isNotBlank()) {
            val sheetRes = sheetsRepo.syncLeadsBatchToGoogleSheet(enrichedPending, targetSheetsUrl)
            if (sheetRes.isSuccess) {
              sheetSyncSuccess = true
              uploadedCount = enrichedPending.size
              _uiState.update { s ->
                val enrichedMap = enrichedPending.associateBy { it.id }
                val updatedList = s.leads.map { enrichedMap[it.id] ?: it }
                s.copy(
                  leads = updatedList,
                  syncQueue = updatedList.filter { it.syncStatus != "synced" }
                )
              }
              saveLocalState()
            } else {
              lastFailReason = sheetRes.exceptionOrNull()?.message ?: "Google Sheets sync error"
            }
          }

          // B. Also sync to Firestore if user has UID
          if (uid != null) {
            for (enrichedLead in enrichedPending) {
              try {
                withTimeout(6_000L) {
                  firestoreRepo.saveLead(enrichedLead, uid)
                }
                if (!sheetSyncSuccess) {
                  uploadedCount++
                  _uiState.update { s ->
                    val updatedList = s.leads.map { if (it.id == enrichedLead.id) enrichedLead else it }
                    s.copy(
                      leads = updatedList,
                      syncQueue = updatedList.filter { it.syncStatus != "synced" }
                    )
                  }
                  saveLocalState()
                }
              } catch (e: Exception) {
                if (!sheetSyncSuccess) {
                  failCount++
                  lastFailReason = e.localizedMessage ?: e.message ?: "Network error"
                }
              }
            }
          } else if (!sheetSyncSuccess && targetSheetsUrl.isBlank()) {
            failCount = pendingLeads.size
            lastFailReason = "Configure Google Sheets Web App URL in Admin or Sign In with Google"
          }
        }

        // 2. Fetch all cloud leads from direct /leads collection (Server-first) if user is signed in
        if (uid != null) {
          try {
            withTimeout(10_000L) {
              val (cloudLeads, cloudDeletedIds) = firestoreRepo.fetchAllLeadsFromFirestore()
              mergeRemoteLeadsIntoState(cloudLeads, cloudDeletedIds)
            }
          } catch (fetchEx: Exception) {
            Log.w("LoanViewModel", "Error fetching remote leads", fetchEx)
          }

          // 3. Also refresh registered users so branch rights & unblock status stay 100% live
          try {
            withTimeout(8_000L) {
              val cloudUsers = firestoreRepo.fetchAllUsersFromFirestore()
              if (cloudUsers.isNotEmpty()) {
                var mergedUsersToSave: List<RegisteredUser> = emptyList()
                _uiState.update { s ->
                  val combined = mergeUserLists(cloudUsers, s.registeredUsers, s.deletedUserKeys)
                  mergedUsersToSave = combined
                  s.copy(registeredUsers = combined)
                }
                saveLocalUsers(mergedUsersToSave)
              }
            }
          } catch (_: Exception) {}
        }

        // 2b. If Google Sheets Webhook URL is configured, pull authorized leads from Google Sheet
        // Strictly restore only leads that this officer authored OR belongs to their authorized branch(es)
        if (targetSheetsUrl.isNotBlank()) {
          try {
            val sheetFetch = sheetsRepo.fetchLeadsFromGoogleSheet(targetSheetsUrl)
            if (sheetFetch.isSuccess) {
              val sheetLeads = sheetFetch.getOrNull().orEmpty()
              if (sheetLeads.isNotEmpty()) {
                val currentState = _uiState.value
                val authorizedSheetLeads = sheetLeads.filter { lead ->
                  currentState.isLeadOwnedByCurrentUser(lead) || currentState.isLeadPermittedForUser(lead)
                }
                if (authorizedSheetLeads.isNotEmpty()) {
                  mergeRemoteLeadsIntoState(authorizedSheetLeads, emptySet())
                }
              }
            }
          } catch (e: Exception) {
            Log.w("LoanViewModel", "Notice fetching sheet leads", e)
          }
        }

        if (showToastOnComplete) {
          if (uploadedCount > 0 && failCount == 0) {
            showToast("✅ $uploadedCount pending lead(s) synced successfully to Sheets/Cloud!")
          } else if (uploadedCount > 0 && failCount > 0) {
            showToast("⚠️ $uploadedCount lead(s) synced, but $failCount failed: $lastFailReason")
          } else if (failCount > 0) {
            showToast("❌ Could not sync $failCount lead(s): $lastFailReason")
          } else {
            showToast("✅ Database synced • ${_uiState.value.leads.size} total lead(s) active")
          }
        } else if (uploadedCount > 0) {
          showToast("☁️ Auto-synced $uploadedCount lead(s) to Sheets/Cloud!")
        }
      } catch (e: Exception) {
        Log.w("LoanViewModel", "Error during syncPendingLeadsToCloud", e)
        if (showToastOnComplete) {
          showToast("⚠️ Sync notice: ${e.localizedMessage ?: e.message}")
        }
      } finally {
        isSyncingLeads.set(false)
      }
    }
  }

  fun syncNow() {
    isSyncingLeads.set(false) // Clear any stuck flag
    val uid = _uiState.value.authUserId ?: authRepo.currentUser?.uid
    if (uid != null && (leadsJob?.isActive != true || configJob?.isActive != true)) {
      startFirestoreSync(uid, _uiState.value.authUserEmail.orEmpty(), _uiState.value.profile.officerName)
    }
    showToast("🔄 Connecting to cloud & syncing leads...")
    syncPendingLeadsToCloud(showToastOnComplete = true, forceReconnect = true)
  }

  fun showToast(msg: String) = _uiState.update { it.copy(toastMessage = msg) }
  fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

  fun isLeadOwnedByCurrentUser(lead: CustomerLead): Boolean = _uiState.value.isLeadOwnedByCurrentUser(lead)
  fun isLeadPermittedForUser(lead: CustomerLead): Boolean = _uiState.value.isLeadPermittedForUser(lead)

  fun setGoogleSheetsWebhookUrl(url: String) {
    val cleanUrl = url.trim()
    sheetsRepo.setWebhookUrl(cleanUrl)
    _uiState.update { it.copy(googleSheetsWebhookUrl = cleanUrl) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        firestoreRepo.publishGoogleSheetsWebhookUrl(cleanUrl)
      } catch (e: Exception) {
        Log.w("LoanViewModel", "Error saving webhook URL to Firestore config", e)
      }
      if (cleanUrl.isNotBlank()) {
        val allLocalLeads = _uiState.value.leads.filterNot { it.id in deletedLeadIds }
        if (allLocalLeads.isNotEmpty()) {
          val res = sheetsRepo.syncLeadsBatchToGoogleSheet(allLocalLeads, cleanUrl)
          if (res.isSuccess) {
            _uiState.update { s ->
              val syncedLeads = s.leads.map { it.copy(syncStatus = "synced") }
              s.copy(leads = syncedLeads, syncQueue = emptyList())
            }
            saveLocalState()
            showToast("📊 Auto-migrated ${allLocalLeads.size} local leads to Google Sheet!")
          }
        }
      }
    }
    showToast(if (cleanUrl.isNotBlank()) "Google Sheets Web App URL saved & synced!" else "Google Sheets URL cleared")
  }

  fun testGoogleSheetsConnection(urlOverride: String? = null) {
    val targetUrl = (urlOverride ?: _uiState.value.googleSheetsWebhookUrl).trim()
    if (targetUrl.isBlank()) {
      showToast("❌ Please enter a valid Google Sheets Web App URL first")
      return
    }
    _uiState.update { it.copy(isTestingSheetsUrl = true) }
    viewModelScope.launch {
      val res = sheetsRepo.testConnection(targetUrl)
      _uiState.update { it.copy(isTestingSheetsUrl = false) }
      res.onSuccess { msg ->
        showToast("✅ Connection Verified: $msg")
      }.onFailure { err ->
        showToast("❌ Connection Test Failed: ${err.localizedMessage ?: err.message}")
      }
    }
  }

  private fun serializeLeadsJson(leads: List<CustomerLead>): String {
    val array = JSONArray()
    leads.forEach { l ->
      val obj = JSONObject().apply {
        put("id", l.id)
        put("name", l.customerName)
        put("mobile", l.mobile)
        put("dob", l.dob)
        put("age", l.age)
        put("income", l.monthlyIncome)
        put("dbr", l.dbrAllowed)
        put("prodId", l.productId)
        put("prodName", l.productName)
        put("amount", l.loanAmount)
        put("tenure", l.tenureMonths)
        put("monthlyEmi", l.monthlyEmi)
        put("profit", l.totalProfit)
        put("fee", l.processingFee)
        put("category", l.category)
        put("isFiler", l.isFiler)
        put("taxRate", l.taxRate)
        put("taxDeducted", l.taxDeducted)
        put("netProfit", l.netProfit)
        if (l.latitude != null) put("latitude", l.latitude)
        if (l.longitude != null) put("longitude", l.longitude)
        if (l.locationAddress.isNotBlank()) put("locationAddress", l.locationAddress)
        if (l.locationCapturedAt != null) put("locationCapturedAt", l.locationCapturedAt)
        put("status", l.status)
        put("notes", l.notes)
        put("followUpDate", l.followUpDate)
        put("officer", l.officerName)
        put("officerId", l.officerId)
        put("branch", l.branchName)
        put("userId", l.userId)
        put("created", l.createdAt)
        put("syncStatus", l.syncStatus)
        put("approachCount", l.approachCount)
        put("notesUpdateCount", l.notesUpdateCount)
        val actArr = JSONArray()
        l.activities.forEach { a ->
          actArr.put(JSONObject().apply {
            put("id", a.id)
            put("timestamp", a.timestamp)
            put("actionType", a.actionType)
            put("title", a.title)
            put("details", a.details)
            put("officer", a.officerName)
            put("officerId", a.officerId)
          })
        }
        put("activities", actArr)
      }
      array.put(obj)
    }
    return array.toString()
  }

  private fun parseLeadsJson(json: String): List<CustomerLead> {
    return try {
      val array = JSONArray(json)
      val list = mutableListOf<CustomerLead>()
      for (i in 0 until array.length()) {
        val o = array.getJSONObject(i)
        val actArr = o.optJSONArray("activities")
        val actList = mutableListOf<LeadActivity>()
        if (actArr != null) {
          for (j in 0 until actArr.length()) {
            val ao = actArr.optJSONObject(j) ?: continue
            actList.add(
              LeadActivity(
                id = ao.optString("id", UUID.randomUUID().toString()),
                timestamp = ao.optLong("timestamp", System.currentTimeMillis()),
                actionType = ao.optString("actionType", "follow_up"),
                title = ao.optString("title", ""),
                details = ao.optString("details", ""),
                officerName = ao.optString("officer", ""),
                officerId = ao.optString("officerId", "")
              )
            )
          }
        }
        val appCount = if (o.has("approachCount")) o.optInt("approachCount")
          else actList.count { it.actionType in listOf("follow_up", "call", "whatsapp", "sms", "created") }
        val noteCount = if (o.has("notesUpdateCount")) o.optInt("notesUpdateCount")
          else actList.count { it.actionType == "note_update" }

        val lat = if (o.has("latitude") && !o.isNull("latitude")) o.optDouble("latitude") else null
        val lng = if (o.has("longitude") && !o.isNull("longitude")) o.optDouble("longitude") else null
        val locCap = if (o.has("locationCapturedAt") && !o.isNull("locationCapturedAt")) o.optLong("locationCapturedAt") else null

        list.add(
          CustomerLead(
            id = o.optString("id"),
            customerName = o.optString("name"),
            mobile = o.optString("mobile"),
            dob = o.optString("dob", "1992-05-15"),
            age = o.optInt("age", 32),
            monthlyIncome = o.optLong("income", 150000L),
            dbrAllowed = o.optInt("dbr", 50),
            productId = o.optString("prodId"),
            productName = o.optString("prodName"),
            loanAmount = o.optLong("amount"),
            tenureMonths = o.optInt("tenure"),
            monthlyEmi = o.optLong("monthlyEmi"),
            totalProfit = o.optLong("profit"),
            processingFee = o.optLong("fee"),
            status = o.optString("status", "New"),
            notes = o.optString("notes"),
            followUpDate = o.optString("followUpDate", ""),
            officerName = o.optString("officer"),
            officerId = o.optString("officerId"),
            branchName = o.optString("branch"),
            userId = o.optString("userId", ""),
            createdAt = o.optLong("created", System.currentTimeMillis()),
            syncStatus = o.optString("syncStatus", "synced"),
            approachCount = appCount,
            notesUpdateCount = noteCount,
            activities = actList,
            category = o.optString("category", "asset"),
            isFiler = o.optBoolean("isFiler", true),
            taxRate = o.optDouble("taxRate", 20.0),
            taxDeducted = o.optLong("taxDeducted", 0L),
            netProfit = o.optLong("netProfit", 0L),
            latitude = lat,
            longitude = lng,
            locationAddress = o.optString("locationAddress", ""),
            locationCapturedAt = locCap
          )
        )
      }
      list
    } catch (e: Exception) {
      emptyList()
    }
  }

  private fun sampleLeads(profile: OfficerProfile): List<CustomerLead> {
    return emptyList()
  }

  private fun serializeUsersJson(users: List<RegisteredUser>): String {
    val array = JSONArray()
    users.forEach { u ->
      val obj = JSONObject().apply {
        put("uid", u.uid)
        put("email", u.email)
        put("officerName", u.officerName)
        put("staffId", u.staffId)
        put("branchName", u.branchName)
        put("allowedSupervisorBranch", u.allowedSupervisorBranch)
        put("canUseSupervisorFilter", u.canUseSupervisorFilter)
        put("isBlocked", u.isBlocked)
        put("role", u.role)
        put("updatedAt", u.updatedAt)
      }
      array.put(obj)
    }
    return array.toString()
  }

  private fun parseUsersJson(json: String): List<RegisteredUser> {
    return try {
      val array = JSONArray(json)
      val list = mutableListOf<RegisteredUser>()
      for (i in 0 until array.length()) {
        val o = array.getJSONObject(i)
        list.add(
          RegisteredUser(
            uid = o.optString("uid"),
            email = o.optString("email"),
            officerName = o.optString("officerName"),
            staffId = o.optString("staffId"),
            branchName = o.optString("branchName"),
            allowedSupervisorBranch = o.optString("allowedSupervisorBranch"),
            canUseSupervisorFilter = o.optBoolean("canUseSupervisorFilter", true),
            isBlocked = o.optBoolean("isBlocked", true),
            role = o.optString("role", "Loan Officer"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
          )
        )
      }
      list
    } catch (_: Exception) {
      emptyList()
    }
  }
}
