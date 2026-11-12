package com.example.model

data class RateSlab(
  val minAmount: Long,
  val maxAmount: Long,
  val annualRate: Double
)

data class FeeSlab(
  val minAmount: Long,
  val maxAmount: Long,
  val feeType: String = "percentage", // "percentage" or "fixed"
  val feeValue: Double = 1.5,
  val minFee: Long = 0L,
  val maxFee: Long = 0L
) {
  val feePercentage: Double get() = feeValue
  val fixedMinFee: Long get() = minFee
  val fixedMaxFee: Long get() = maxFee
}

data class LoanProduct(
  val id: String,
  val name: String,
  val rateType: String, // "reducing" or "flat"
  val defaultAnnualRate: Double,
  val minAmount: Long,
  val maxAmount: Long,
  val minTenure: Int = 1,
  val maxTenure: Int = 120,
  val minAge: Int = 21,
  val maxAgeAtMaturity: Int = 65,
  val feeType: String = "percentage", // "percentage" or "fixed"
  val defaultFeeValue: Double = 1.5,
  val fedRate: Double = 16.0,
  val collateralDetails: String = "",
  val rateSlabs: List<RateSlab> = emptyList(),
  val feeSlabs: List<FeeSlab> = emptyList(),
  val category: String = "asset", // "asset" (Loan / Financing) or "liability" (Deposit / Savings)
  val fixedTenures: List<Int> = emptyList(), // For term deposits: listOf(6, 12, 24, 36, 60), for savings: listOf(12)
  val tenureRates: Map<Int, Double> = emptyMap(), // Map of tenure in months -> annual profit rate %
  val liabilityPricingType: String = "" // "tenure" (duration-based) or "amount" (balance slab-based)
)

data class LeadActivity(
  val id: String = java.util.UUID.randomUUID().toString(),
  val timestamp: Long = System.currentTimeMillis(),
  val actionType: String = "follow_up", // follow_up, note_update, call, whatsapp, sms, status_change
  val title: String = "",
  val details: String = "",
  val officerName: String = "",
  val officerId: String = ""
)

data class CustomerLead(
  val id: String,
  val customerName: String,
  val mobile: String = "",
  val dob: String = "",
  val age: Int = 0,
  val monthlyIncome: Long = 0L,
  val dbrAllowed: Int = 0,
  val productId: String = "",
  val productName: String = "",
  val loanAmount: Long = 0L,
  val tenureMonths: Int = 0,
  val monthlyEmi: Long = 0L,
  val totalProfit: Long = 0L,
  val processingFee: Long = 0L,
  val status: String = "New", // New, Contacted, Converted, Lost
  val notes: String = "",
  val followUpDate: String = "",
  val officerName: String = "",
  val officerId: String = "",
  val branchName: String = "",
  val userId: String = "",
  val createdAt: Long = System.currentTimeMillis(),
  val syncStatus: String = "synced",
  val approachCount: Int = 0,
  val notesUpdateCount: Int = 0,
  val activities: List<LeadActivity> = emptyList(),
  val category: String = "asset", // "asset" or "liability"
  val isFiler: Boolean = true, // Customer filer status (20% WHT) or non-filer (40% WHT)
  val taxRate: Double = 20.0,
  val taxDeducted: Long = 0L,
  val netProfit: Long = 0L,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val locationAddress: String = "",
  val locationCapturedAt: Long? = null
)

data class GlobalConfig(
  val fedRate: Double = 16.0,
  val filerTaxRate: Double = 20.0,
  val nonFilerTaxRate: Double = 40.0,
  val currency: String = "PKR",
  val adminPin: String = "Admin123",
  val adminBroadcastMessage: String = "",
  val adminBroadcastId: String = "",
  val appUpdateUrl: String = "",
  val appUpdateId: String = "",
  val appUpdateMessage: String = "",
  val googleSheetsWebhookUrl: String = "",
  val deletedUserKeys: Set<String> = emptySet(),
  val registryUsers: List<RegisteredUser> = emptyList()
)

data class AmortizationRow(
  val month: Int,
  val dueDate: String,
  val openingBalance: Long,
  val principal: Long,
  val profit: Long,
  val emi: Long,
  val extraPayment: Long = 0L,
  val closingBalance: Long
)

data class PrepaymentSummary(
  val mode: String = "reduce_emi", // "reduce_emi" or "reduce_tenure"
  val originalTotalProfit: Long,
  val revisedTotalProfit: Long,
  val totalProfitSaved: Long,
  val originalMonthlyEmi: Long,
  val revisedMonthlyEmi: Long,
  val originalTenure: Int,
  val revisedTenure: Int,
  val monthsSaved: Int,
  val totalExtraPaid: Long
)

data class OfficerProfile(
  val officerName: String = "",
  val staffId: String = "",
  val branchName: String = "",
  val isOnboarded: Boolean = false,
  val adminPin: String = "Admin123",
  val currency: String = "PKR",
  val defaultDbr: Int? = null,
  val fedRate: Double = 16.0,
  val filerTaxRate: Double = 20.0,
  val nonFilerTaxRate: Double = 40.0,
  val webhookUrl: String = ""
)

data class RegisteredUser(
  val uid: String = "",
  val email: String = "",
  val officerName: String = "",
  val staffId: String = "",
  val branchName: String = "",
  val allowedSupervisorBranch: String = "",
  val canUseSupervisorFilter: Boolean = true,
  val isBlocked: Boolean = true,
  val role: String = "Loan Officer", // "Loan Officer" or "Relationship Manager"
  val updatedAt: Long = System.currentTimeMillis()
)
