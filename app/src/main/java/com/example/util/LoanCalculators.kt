package com.example.util

import com.example.model.AmortizationRow
import com.example.model.LoanProduct
import com.example.model.PrepaymentSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

object LoanCalculators {

  data class EmiResult(val emi: Long, val totalProfit: Long, val totalPayable: Long)
  data class FeeResult(val baseFee: Long, val fedAmount: Long, val totalFee: Long)
  data class DepositProfitResult(
    val monthlyProfit: Long,       // Gross monthly profit
    val totalProfit: Long,         // Gross total profit
    val isFiler: Boolean,          // true = Filer, false = Non-Filer
    val taxRate: Double,           // 20.0 (filer) or 40.0 (non-filer)
    val monthlyTax: Long,          // Monthly WHT deduction
    val totalTax: Long,            // Total WHT deduction
    val netMonthlyProfit: Long,    // Net monthly profit after tax
    val netTotalProfit: Long,      // Net total profit after tax
    val maturityAmount: Long,      // Principal + Gross total profit
    val netMaturityAmount: Long,   // Principal + Net total profit
    val effectiveRate: Double
  )

  fun calculateDepositProfit(
    depositAmount: Long,
    annualRate: Double,
    tenureMonths: Int,
    isFiler: Boolean = true,
    filerTaxRate: Double = 20.0,
    nonFilerTaxRate: Double = 40.0
  ): DepositProfitResult {
    val rate = annualRate
    val t = tenureMonths.coerceAtLeast(1)
    val taxRate = if (isFiler) filerTaxRate else nonFilerTaxRate
    if (depositAmount <= 0L) {
      return DepositProfitResult(0L, 0L, isFiler, taxRate, 0L, 0L, 0L, 0L, 0L, 0L, rate)
    }

    val totalProfit = (depositAmount.toDouble() * (rate / 100.0) * (t.toDouble() / 12.0)).roundToLong()
    val monthlyProfit = (depositAmount.toDouble() * (rate / 100.0) / 12.0).roundToLong()
    val totalTax = (totalProfit.toDouble() * (taxRate / 100.0)).roundToLong()
    val monthlyTax = (monthlyProfit.toDouble() * (taxRate / 100.0)).roundToLong()
    val netTotalProfit = (totalProfit - totalTax).coerceAtLeast(0L)
    val netMonthlyProfit = (monthlyProfit - monthlyTax).coerceAtLeast(0L)
    val maturityAmount = depositAmount + totalProfit
    val netMaturityAmount = depositAmount + netTotalProfit

    return DepositProfitResult(
      monthlyProfit = monthlyProfit,
      totalProfit = totalProfit,
      isFiler = isFiler,
      taxRate = taxRate,
      monthlyTax = monthlyTax,
      totalTax = totalTax,
      netMonthlyProfit = netMonthlyProfit,
      netTotalProfit = netTotalProfit,
      maturityAmount = maturityAmount,
      netMaturityAmount = netMaturityAmount,
      effectiveRate = rate
    )
  }

  /**
   * Helper to format phone for WhatsApp web/API (converts 0342... to 92342...).
   * Does NOT alter the user's input in the phone textfield.
   */
  fun formatForWhatsApp(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return when {
      digits.startsWith("03") && digits.length == 11 -> "92" + digits.substring(1)
      digits.startsWith("3") && digits.length == 10 -> "92$digits"
      digits.startsWith("92") -> digits
      else -> digits
    }
  }

  fun getApplicableRate(product: LoanProduct, amount: Long, tenureMonths: Int = 0): Double {
    // 1. Current Account (Non-remunerative 0% profit)
    if (product.defaultAnnualRate <= 0.0 && (product.rateSlabs.isEmpty() || product.rateSlabs.all { it.annualRate <= 0.0 })) {
      return 0.0
    }

    // 2. Check for liability products: support both Amount-based and Tenure-based pricing
    if (product.category == "liability") {
      val isAmountTierBased = product.liabilityPricingType == "amount" || (product.tenureRates.isEmpty() && product.rateSlabs.size > 1)
      if (isAmountTierBased && product.rateSlabs.isNotEmpty()) {
        if (amount > 0L) {
          val slab = product.rateSlabs.find { amount in it.minAmount..it.maxAmount }
          if (slab != null) return slab.annualRate
          val nearest = product.rateSlabs.minByOrNull { s ->
            when {
              amount < s.minAmount -> s.minAmount - amount
              amount > s.maxAmount -> amount - s.maxAmount
              else -> 0L
            }
          }
          if (nearest != null) return nearest.annualRate
        }
        return product.rateSlabs.first().annualRate
      }

      // Duration / Tenure based pricing (Term Deposits / KIC / KAC)
      if (product.tenureRates.isNotEmpty()) {
        if (tenureMonths > 0) {
          if (product.tenureRates.containsKey(tenureMonths)) {
            return product.tenureRates[tenureMonths] ?: product.defaultAnnualRate
          }
          val nearest = product.tenureRates.minByOrNull { kotlin.math.abs(it.key - tenureMonths) }
          if (nearest != null) return nearest.value
        } else {
          return product.tenureRates.values.firstOrNull() ?: product.defaultAnnualRate
        }
      }
    }

    // 3. Amount-based rate slabs (Asset Loans or fallback)
    if (product.rateSlabs.isNotEmpty()) {
      if (amount > 0L) {
        val slab = product.rateSlabs.find { amount in it.minAmount..it.maxAmount }
        if (slab != null) return slab.annualRate
        val nearest = product.rateSlabs.minByOrNull { s ->
          when {
            amount < s.minAmount -> s.minAmount - amount
            amount > s.maxAmount -> amount - s.maxAmount
            else -> 0L
          }
        }
        if (nearest != null) return nearest.annualRate
      }
      return product.rateSlabs.first().annualRate
    }
    return product.defaultAnnualRate
  }

  fun calculateEmi(principal: Long, annualRate: Double, tenureMonths: Int, rateType: String): EmiResult {
    if (tenureMonths <= 0 || principal <= 0L) return EmiResult(0L, 0L, 0L)

    return if (rateType.equals("flat", ignoreCase = true)) {
      val totalProfit = (principal.toDouble() * (annualRate / 100.0) * (tenureMonths.toDouble() / 12.0)).roundToLong()
      val totalPayable = principal + totalProfit
      val emi = (totalPayable.toDouble() / tenureMonths.toDouble()).roundToLong()
      EmiResult(emi, totalProfit, totalPayable)
    } else {
      val monthlyRate = (annualRate / 100.0) / 12.0
      if (monthlyRate == 0.0) {
        val emi = principal / tenureMonths
        return EmiResult(emi, 0L, principal)
      }
      val factor = (1.0 + monthlyRate).pow(tenureMonths.toDouble())
      val emi = ((principal.toDouble() * monthlyRate * factor) / (factor - 1.0)).roundToLong()
      val totalPayable = emi * tenureMonths
      val totalProfit = totalPayable - principal
      EmiResult(emi, totalProfit, totalPayable)
    }
  }

  /**
   * Calculates processing fee strictly using Tiered Processing Fee Slabs.
   */
  fun calculateFee(product: LoanProduct, principal: Long, fedRate: Double = 16.0): FeeResult {
    if (principal <= 0L || product.feeSlabs.isEmpty()) {
      return FeeResult(0L, 0L, 0L)
    }

    var fee = 0L
    val slab = product.feeSlabs.find { principal in it.minAmount..it.maxAmount }
      ?: product.feeSlabs.minByOrNull { s ->
        when {
          principal < s.minAmount -> s.minAmount - principal
          principal > s.maxAmount -> principal - s.maxAmount
          else -> 0L
        }
      }

    if (slab != null) {
      val raw = if (slab.feeType.equals("fixed", ignoreCase = true)) {
        slab.feeValue.roundToLong()
      } else {
        ((principal.toDouble() * slab.feeValue) / 100.0).roundToLong()
      }
      val minApplied = max(slab.minFee, raw)
      fee = if (slab.maxFee > 0L) min(minApplied, slab.maxFee) else minApplied
    }

    val fed = ((fee.toDouble() * fedRate) / 100.0).roundToLong()
    return FeeResult(fee, fed, fee + fed)
  }

  data class DetailedAge(val years: Int, val months: Int, val days: Int) {
    fun format(): String = "$years Years, $months Months, $days Days"
    fun formatShort(): String = "$years Yrs, $months Mos, $days Days"
    fun formatComplete(): String = "$years Years, $months Months, $days Days ($years سال، $months ماہ، $days دن)"
    fun formatUrdu(): String = "$years سال، $months ماہ، $days دن"
  }

  /**
   * Automatically calculates the MAXIMUM possible loan amount for a given tenure
   * based on customer's Net Income and Allowed DBR%, guaranteeing that resulting
   * EMI matches the DBR capacity limit accurately without 1,000-rupee truncation.
   */
  fun calculateMaxLoanAmount(
    product: LoanProduct,
    netIncome: Long,
    dbrPercent: Int,
    tenureMonths: Int
  ): Long {
    val maxEmiAllowed = ((netIncome.toDouble() * dbrPercent.toDouble()) / 100.0).roundToLong()
    if (maxEmiAllowed <= 0L || tenureMonths <= 0) return 0L

    val candidateRates = (product.rateSlabs.map { it.annualRate } + product.defaultAnnualRate).distinct()
    var bestPrincipal = 0L

    for (candidateRate in candidateRates) {
      var p = computePrincipalFromEmi(maxEmiAllowed, candidateRate, tenureMonths, product.rateType)
      // Converge if slab rate changes at p
      repeat(4) {
        val actualRate = getApplicableRate(product, p)
        p = computePrincipalFromEmi(maxEmiAllowed, actualRate, tenureMonths, product.rateType)
      }
      // Fine-tune to exact rupee so EMI never exceeds maxEmiAllowed
      while (p > 0L) {
        val checkRate = getApplicableRate(product, p)
        val emi = calculateEmi(p, checkRate, tenureMonths, product.rateType).emi
        if (emi <= maxEmiAllowed) break
        p -= 1L
      }
      while (true) {
        val nextP = p + 1L
        val nextRate = getApplicableRate(product, nextP)
        val nextEmi = calculateEmi(nextP, nextRate, tenureMonths, product.rateType).emi
        if (nextEmi <= maxEmiAllowed) {
          p = nextP
        } else {
          break
        }
      }
      if (p > bestPrincipal) {
        val verifyRate = getApplicableRate(product, p)
        val verifyEmi = calculateEmi(p, verifyRate, tenureMonths, product.rateType).emi
        if (verifyEmi <= maxEmiAllowed) {
          bestPrincipal = p
        }
      }
    }

    return max(0L, bestPrincipal)
  }

  fun calculateMaxLoanAmount(
    netIncome: Long,
    dbrPercent: Int,
    tenureMonths: Int,
    annualRate: Double,
    rateType: String
  ): Long {
    val maxEmiAllowed = ((netIncome.toDouble() * dbrPercent.toDouble()) / 100.0).roundToLong()
    if (maxEmiAllowed <= 0L || tenureMonths <= 0) return 0L

    var principal = computePrincipalFromEmi(maxEmiAllowed, annualRate, tenureMonths, rateType)
    while (principal > 0L) {
      val emi = calculateEmi(principal, annualRate, tenureMonths, rateType).emi
      if (emi <= maxEmiAllowed) break
      principal -= 1L
    }
    while (true) {
      val nextP = principal + 1L
      val nextEmi = calculateEmi(nextP, annualRate, tenureMonths, rateType).emi
      if (nextEmi <= maxEmiAllowed) {
        principal = nextP
      } else {
        break
      }
    }
    return max(0L, principal)
  }

  private fun computePrincipalFromEmi(emi: Long, annualRate: Double, tenureMonths: Int, rateType: String): Long {
    return if (rateType.equals("flat", ignoreCase = true)) {
      val totalPayable = emi.toDouble() * tenureMonths.toDouble()
      val factor = 1.0 + ((annualRate / 100.0) * (tenureMonths.toDouble() / 12.0))
      (totalPayable / factor).roundToLong()
    } else {
      val monthlyRate = (annualRate / 100.0) / 12.0
      if (monthlyRate == 0.0) {
        emi * tenureMonths
      } else {
        val factor = (1.0 + monthlyRate).pow(tenureMonths.toDouble())
        ((emi.toDouble() * (factor - 1.0)) / (monthlyRate * factor)).roundToLong()
      }
    }
  }

  /**
   * Generates Amortization Schedule supporting BOTH:
   * Option A: "reduce_emi" (keeps tenure same, lowers remaining installments)
   * Option B: "reduce_tenure" (keeps installment same, shortens loan duration)
   */
  fun generateInteractiveSchedule(
    principal: Long,
    annualRate: Double,
    tenureMonths: Int,
    rateType: String,
    extraPayments: Map<Int, Long> = emptyMap(),
    mode: String = "reduce_emi"
  ): Pair<List<AmortizationRow>, PrepaymentSummary> {
    val origEmiResult = calculateEmi(principal, annualRate, tenureMonths, rateType)
    val origTotalProfit = origEmiResult.totalProfit
    val list = mutableListOf<AmortizationRow>()

    var balance = principal
    val monthlyRate = (annualRate / 100.0) / 12.0
    val cal = Calendar.getInstance()
    val sdf = SimpleDateFormat("MMM yyyy", Locale.US)
    var revisedTotalProfit = 0L
    var totalExtraPaid = 0L

    var currentEmi = origEmiResult.emi

    for (m in 1..tenureMonths) {
      if (balance <= 0L) break
      cal.add(Calendar.MONTH, 1)

      val profitPart = (balance.toDouble() * monthlyRate).roundToLong()
      revisedTotalProfit += profitPart

      val standardPrincipal = if (currentEmi > profitPart) (currentEmi - profitPart) else 0L
      val actualPrincipal = min(balance, standardPrincipal)

      val extra = extraPayments[m] ?: 0L
      totalExtraPaid += extra

      val totalPrincipalThisMonth = min(balance, actualPrincipal + extra)
      val emiThisMonth = totalPrincipalThisMonth + profitPart
      val closing = max(0L, balance - totalPrincipalThisMonth)

      list.add(
        AmortizationRow(
          month = m,
          dueDate = sdf.format(cal.time),
          openingBalance = balance,
          principal = actualPrincipal,
          profit = profitPart,
          emi = emiThisMonth,
          extraPayment = extra,
          closingBalance = closing
        )
      )

      balance = closing

      // If mode is "reduce_emi" and an extra payment was made, recalculate future EMI for remaining tenure
      if (mode == "reduce_emi" && extra > 0L && balance > 0L && m < tenureMonths) {
        val remainingMonths = tenureMonths - m
        val recalculated = calculateEmi(balance, annualRate, remainingMonths, rateType)
        currentEmi = recalculated.emi
      }
    }

    val profitSaved = max(0L, origTotalProfit - revisedTotalProfit)
    val lastRowEmi = list.lastOrNull()?.emi ?: currentEmi

    val summary = PrepaymentSummary(
      mode = mode,
      originalTotalProfit = origTotalProfit,
      revisedTotalProfit = revisedTotalProfit,
      totalProfitSaved = profitSaved,
      originalMonthlyEmi = origEmiResult.emi,
      revisedMonthlyEmi = if (mode == "reduce_emi") currentEmi else origEmiResult.emi,
      originalTenure = tenureMonths,
      revisedTenure = list.size,
      monthsSaved = max(0, tenureMonths - list.size),
      totalExtraPaid = totalExtraPaid
    )

    return Pair(list, summary)
  }

  private fun parseDobDate(dobRaw: String): java.util.Date? {
    val clean = dobRaw.trim()
    if (clean.isEmpty()) return null
    val formats = listOf("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "yyyy/MM/dd", "d-M-yyyy", "yyyy-M-d")
    for (pattern in formats) {
      try {
        val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val parsed = sdf.parse(clean)
        if (parsed != null) return parsed
      } catch (_: Exception) {}
    }
    return null
  }

  fun calculateDetailedAge(dobIso: String): DetailedAge {
    if (dobIso.isBlank()) return DetailedAge(0, 0, 0)
    return try {
      val dobDate = parseDobDate(dobIso) ?: return DetailedAge(0, 0, 0)
      val dobCal = Calendar.getInstance().apply { time = dobDate }
      val nowCal = Calendar.getInstance()
      if (nowCal.before(dobCal)) return DetailedAge(0, 0, 0)

      var years = nowCal.get(Calendar.YEAR) - dobCal.get(Calendar.YEAR)
      var months = nowCal.get(Calendar.MONTH) - dobCal.get(Calendar.MONTH)
      var days = nowCal.get(Calendar.DAY_OF_MONTH) - dobCal.get(Calendar.DAY_OF_MONTH)

      if (days < 0) {
        months--
        val prevMonth = Calendar.getInstance().apply {
          time = nowCal.time
          add(Calendar.MONTH, -1)
        }
        days += prevMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
      }

      if (months < 0) {
        years--
        months += 12
      }
      DetailedAge(max(0, years), max(0, months), max(0, days))
    } catch (e: Exception) {
      DetailedAge(0, 0, 0)
    }
  }

  fun calculateAge(dobIso: String): Int {
    return calculateDetailedAge(dobIso).years
  }

  data class AgeEligibilityInfo(
    val detailedAge: DetailedAge,
    val isEligible: Boolean,
    val isUnderMinAge: Boolean,
    val isOverMaturityAge: Boolean,
    val remainingMonthsToMaturity: Int,
    val maxOfferableTenureMonths: Int
  )

  fun evaluateAgeEligibility(dobIso: String, product: LoanProduct): AgeEligibilityInfo {
    val detailed = calculateDetailedAge(dobIso)
    if (dobIso.isBlank()) {
      return AgeEligibilityInfo(
        detailedAge = detailed,
        isEligible = true,
        isUnderMinAge = false,
        isOverMaturityAge = false,
        remainingMonthsToMaturity = product.maxTenure,
        maxOfferableTenureMonths = product.maxTenure
      )
    }

    val totalCustomerMonths = (detailed.years * 12) + detailed.months + (if (detailed.days > 0) 1 else 0)
    val minRequiredMonths = product.minAge * 12
    val maxMaturityMonths = product.maxAgeAtMaturity * 12

    val isUnderMin = detailed.years < product.minAge
    val remainingToMaturity = max(0, maxMaturityMonths - totalCustomerMonths)
    val isOverMaturity = remainingToMaturity < product.minTenure
    val maxOfferable = if (isUnderMin || isOverMaturity) 0 else min(product.maxTenure, remainingToMaturity)
    val isEligible = !isUnderMin && !isOverMaturity && maxOfferable >= product.minTenure

    return AgeEligibilityInfo(
      detailedAge = detailed,
      isEligible = isEligible,
      isUnderMinAge = isUnderMin,
      isOverMaturityAge = isOverMaturity,
      remainingMonthsToMaturity = remainingToMaturity,
      maxOfferableTenureMonths = maxOfferable
    )
  }

  fun formatCurrency(amount: Long, currency: String = "PKR"): String {
    return String.format(Locale.US, "%s %,d", currency, amount)
  }

  fun formatNumber(amount: Long): String {
    return String.format(Locale.US, "%,d", amount)
  }
}
