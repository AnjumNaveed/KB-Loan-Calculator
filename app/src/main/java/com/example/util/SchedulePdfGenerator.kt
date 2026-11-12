package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.model.AmortizationRow
import com.example.model.PrepaymentSummary
import com.example.ui.LoanUiState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SchedulePdfGenerator {

  private const val PAGE_WIDTH = 595 // Standard A4 width in PostScript points (72 dpi)
  private const val PAGE_HEIGHT = 842 // Standard A4 height in PostScript points (72 dpi)
  private const val MARGIN_LEFT = 36f
  private const val MARGIN_RIGHT = 559f
  private const val MARGIN_BOTTOM = 795f
  private const val ROW_HEIGHT = 18f

  /**
   * Generates a multi-page, formatted PDF repayment schedule and launches the Android Share Sheet.
   */
  fun generateAndShareSchedulePdf(
    context: Context,
    uiState: LoanUiState,
    rows: List<AmortizationRow>,
    summary: PrepaymentSummary,
    actualPrincipal: Long? = null
  ) {
    try {
      val pdfFile = createSchedulePdfFile(context, uiState, rows, summary, actualPrincipal)
      if (pdfFile == null || !pdfFile.exists()) {
        Toast.makeText(context, "Could not generate PDF schedule", Toast.LENGTH_SHORT).show()
        return
      }

      val contentUri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        pdfFile
      )

      val custName = uiState.customerName.trim().ifEmpty { "Customer" }
      val product = uiState.products.find { it.id == uiState.selectedProductId } ?: uiState.products.firstOrNull()
      val productName = product?.name ?: "Personal Loan"
      val principal = if (actualPrincipal != null && actualPrincipal > 0L) {
        actualPrincipal
      } else {
        rows.firstOrNull()?.openingBalance?.takeIf { it > 0L }
          ?: rows.sumOf { it.principal }.takeIf { it > 0L }
          ?: (uiState.loanAmountInput.filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0L })
          ?: (if (product != null && uiState.monthlyIncome > 0L && uiState.allowedDbr > 0) {
                LoanCalculators.calculateMaxLoanAmount(
                  product = product,
                  netIncome = uiState.monthlyIncome,
                  dbrPercent = uiState.allowedDbr,
                  tenureMonths = if (rows.isNotEmpty()) rows.size else uiState.selectedTenure
                )
              } else 0L)
      }
      val tenureMonths = if (rows.isNotEmpty()) rows.size else uiState.selectedTenure

      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_SUBJECT, "Repayment Schedule - $custName ($productName)")
        putExtra(
          Intent.EXTRA_TEXT,
          "Assalam-o-Alaikum,\n\nPlease find attached the official Loan Amortization & Repayment Schedule for $custName.\n\n" +
            "• Product: $productName\n" +
            "• Financing Amount: ${uiState.profile.currency} ${LoanCalculators.formatCurrency(principal, uiState.profile.currency)}\n" +
            "• Tenure: $tenureMonths Months\n" +
            "• Branch: ${uiState.profile.branchName.ifBlank { "Branch Office" }}\n\n" +
            "Generated via Khushhali Bank Loan Calculator."
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

      val chooser = Intent.createChooser(shareIntent, "Share Repayment Schedule (PDF)")
      context.startActivity(chooser)
    } catch (e: Exception) {
      Toast.makeText(context, "Error sharing PDF: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
    }
  }

  fun createSchedulePdfFile(
    context: Context,
    uiState: LoanUiState,
    rows: List<AmortizationRow>,
    summary: PrepaymentSummary,
    actualPrincipal: Long? = null
  ): File? {
    val outputDir = File(context.cacheDir, "schedule_pdfs")
    if (!outputDir.exists()) {
      outputDir.mkdirs()
    }

    // Cleanup older temp PDF files (older than 2 hours)
    val now = System.currentTimeMillis()
    outputDir.listFiles()?.forEach { f ->
      if (now - f.lastModified() > 2 * 3600 * 1000) {
        f.delete()
      }
    }

    val custName = uiState.customerName.trim().ifEmpty { "Valued Customer" }
    val custMobile = uiState.mobile.trim().ifEmpty { "Not Specified" }
    val product = uiState.products.find { it.id == uiState.selectedProductId } ?: uiState.products.firstOrNull()
    val productName = product?.name ?: "Personal Loan"
    val rateType = product?.rateType ?: "Fixed"
    val principal = if (actualPrincipal != null && actualPrincipal > 0L) {
      actualPrincipal
    } else {
      rows.firstOrNull()?.openingBalance?.takeIf { it > 0L }
        ?: rows.sumOf { it.principal }.takeIf { it > 0L }
        ?: (uiState.loanAmountInput.filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0L })
        ?: (if (product != null && uiState.monthlyIncome > 0L && uiState.allowedDbr > 0) {
              LoanCalculators.calculateMaxLoanAmount(
                product = product,
                netIncome = uiState.monthlyIncome,
                dbrPercent = uiState.allowedDbr,
                tenureMonths = if (rows.isNotEmpty()) rows.size else uiState.selectedTenure
              )
            } else 0L)
    }
    val tenureMonths = if (rows.isNotEmpty()) rows.size else uiState.selectedTenure
    val annualRate = if (product != null && principal > 0L) LoanCalculators.getApplicableRate(product, principal) else 0.0
    val currency = uiState.profile.currency.ifEmpty { "PKR" }
    val branchName = uiState.profile.branchName.trim().ifEmpty { "Main Branch" }
    val officerName = uiState.profile.officerName.trim().ifEmpty { "Loan Officer" }
    val staffId = uiState.profile.staffId.trim().ifEmpty { "N/A" }

    val sanitizedName = custName.replace(Regex("[^a-zA-Z0-9_]"), "_").ifEmpty { "Customer" }
    val pdfFile = File(outputDir, "Repayment_Schedule_${sanitizedName}_${now}.pdf")

    val doc = PdfDocument()

    // Paints
    val paintNavy = Paint().apply { color = Color.rgb(15, 23, 42) } // #0F172A
    val paintBlue = Paint().apply { color = Color.rgb(30, 58, 138) } // #1E3A8A
    val paintGold = Paint().apply { color = Color.rgb(252, 211, 77) } // #FCD34D
    val paintCardBg = Paint().apply { color = Color.rgb(248, 250, 252) } // #F8FAFC
    val paintBorder = Paint().apply {
      color = Color.rgb(203, 213, 225)
      style = Paint.Style.STROKE
      strokeWidth = 1f
    }
    val paintRowEven = Paint().apply { color = Color.WHITE }
    val paintRowOdd = Paint().apply { color = Color.rgb(248, 250, 252) }
    val paintRowExtra = Paint().apply { color = Color.rgb(236, 253, 245) } // Light mint
    val paintSummaryBg = Paint().apply { color = Color.rgb(254, 243, 199) } // Light amber
    val paintDivider = Paint().apply {
      color = Color.rgb(226, 232, 240)
      strokeWidth = 0.8f
    }

    val textTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 15f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textSubtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(252, 211, 77)
      textSize = 8.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textHeaderMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(203, 213, 225)
      textSize = 8f
    }
    val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(71, 85, 105)
      textSize = 7.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 8.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val textValBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 8.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textTh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 7.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textTd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      textSize = 7.5f
    }
    val textTdBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 7.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textFooter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(100, 116, 139)
      textSize = 7f
    }

    val hasPrepaymentSavings = summary.totalExtraPaid > 0L
    val page1Capacity = if (hasPrepaymentSavings) 24 else 26
    val remainingCapacity = 34
    val totalPages = if (rows.size <= page1Capacity) {
      1
    } else {
      1 + Math.ceil((rows.size - page1Capacity).toDouble() / remainingCapacity).toInt()
    }

    val dateFormat = SimpleDateFormat("dd-MMM-yyyy, hh:mm a", Locale.US)
    val generationTime = dateFormat.format(Date())

    var currentRowIndex = 0

    for (pageNumber in 1..totalPages) {
      val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
      val page = doc.startPage(pageInfo)
      val canvas = page.canvas

      var currentY = 36f

      if (pageNumber == 1) {
        // --- PAGE 1 FULL BANNER ---
        val headerRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 54f)
        canvas.drawRoundRect(headerRect, 8f, 8f, paintNavy)

        canvas.drawText("KHUSHHALI BANK LIMITED", MARGIN_LEFT + 14f, currentY + 18f, textTitle)
        canvas.drawText("LOAN AMORTIZATION & REPAYMENT SCHEDULE", MARGIN_LEFT + 14f, currentY + 32f, textSubtitle)

        val branchText = "Branch: $branchName • Officer: $officerName ($staffId)"
        canvas.drawText(branchText, MARGIN_LEFT + 14f, currentY + 45f, textHeaderMeta)

        val metaDateText = "Date: $generationTime"
        val dateWidth = textHeaderMeta.measureText(metaDateText)
        canvas.drawText(metaDateText, MARGIN_RIGHT - 14f - dateWidth, currentY + 45f, textHeaderMeta)

        currentY += 60f

        // --- CUSTOMER & LOAN SUMMARY CARD ---
        val cardHeight = 88f
        val cardRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + cardHeight)
        canvas.drawRoundRect(cardRect, 8f, 8f, paintCardBg)
        canvas.drawRoundRect(cardRect, 8f, 8f, paintBorder)

        val col1X = MARGIN_LEFT + 14f
        val col2X = MARGIN_LEFT + 270f
        var cardY = currentY + 16f

        // Row 1
        canvas.drawText("CUSTOMER NAME", col1X, cardY, textLabel)
        canvas.drawText(custName, col1X, cardY + 11f, textValBold)

        canvas.drawText("FINANCING PRODUCT", col2X, cardY, textLabel)
        canvas.drawText("$productName ($rateType)", col2X, cardY + 11f, textValBold)

        cardY += 27f

        // Row 2
        canvas.drawText("CUSTOMER CONTACT", col1X, cardY, textLabel)
        canvas.drawText(custMobile, col1X, cardY + 11f, textVal)

        canvas.drawText("PRINCIPAL / FINANCING AMOUNT", col2X, cardY, textLabel)
        val pFormatted = "$currency ${LoanCalculators.formatCurrency(principal, currency)}"
        canvas.drawText(pFormatted, col2X, cardY + 11f, textValBold)

        cardY += 27f

        // Row 3
        canvas.drawText("TENURE & INTEREST RATE", col1X, cardY, textLabel)
        val tenureRate = "$tenureMonths Months (${tenureMonths / 12} Yrs) @ $annualRate% p.a."
        canvas.drawText(tenureRate, col1X, cardY + 11f, textVal)

        canvas.drawText("ESTIMATED MONTHLY INSTALLMENT (EMI)", col2X, cardY, textLabel)
        val effectiveEmi = if (summary.originalMonthlyEmi > 0L) summary.originalMonthlyEmi else (rows.firstOrNull()?.emi ?: 0L)
        val emiFormatted = "$currency ${LoanCalculators.formatCurrency(effectiveEmi, currency)}"
        canvas.drawText(emiFormatted, col2X, cardY + 11f, textValBold)

        currentY += cardHeight + 8f

        // Prepayment Savings Banner if present
        if (hasPrepaymentSavings) {
          val prepayHeight = 28f
          val prepayRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + prepayHeight)
          val prepayBg = Paint().apply { color = Color.rgb(236, 253, 245) }
          val prepayBorder = Paint().apply {
            color = Color.rgb(16, 185, 129)
            style = Paint.Style.STROKE
            strokeWidth = 1f
          }
          canvas.drawRoundRect(prepayRect, 6f, 6f, prepayBg)
          canvas.drawRoundRect(prepayRect, 6f, 6f, prepayBorder)

          val modeDesc = if (summary.mode == "reduce_emi") "Option A: Reduced Monthly EMI" else "Option B: Reduced Loan Tenure"
          val prepayText = "✨ Extra Prepayments Applied: ${modeDesc} • Total Profit Saved: $currency ${LoanCalculators.formatCurrency(summary.totalProfitSaved, currency)}"
          val textPrepay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(4, 120, 87)
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
          }
          canvas.drawText(prepayText, MARGIN_LEFT + 12f, currentY + 17f, textPrepay)

          currentY += prepayHeight + 8f
        }

      } else {
        // --- PAGE 2+ MINI HEADER ---
        val headerMiniRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 28f)
        canvas.drawRoundRect(headerMiniRect, 6f, 6f, paintNavy)

        canvas.drawText(
          "Khushhali Bank Repayment Schedule - $custName",
          MARGIN_LEFT + 12f,
          currentY + 18f,
          textSubtitle
        )
        val pNum = "Page $pageNumber of $totalPages"
        val pNumWidth = textHeaderMeta.measureText(pNum)
        canvas.drawText(pNum, MARGIN_RIGHT - 12f - pNumWidth, currentY + 18f, textHeaderMeta)

        currentY += 34f
      }

      // --- TABLE HEADER ---
      val thHeight = 20f
      val thRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + thHeight)
      canvas.drawRoundRect(thRect, 4f, 4f, paintBlue)

      drawTableHeader(canvas, currentY, textTh)
      currentY += thHeight

      // --- TABLE ROWS ---
      val rowsThisPage = if (pageNumber == 1) page1Capacity else remainingCapacity
      val endIndex = Math.min(currentRowIndex + rowsThisPage, rows.size)

      for (i in currentRowIndex until endIndex) {
        val row = rows[i]
        val isEven = (row.month % 2 == 0)
        val rowBg = when {
          row.extraPayment > 0L -> paintRowExtra
          isEven -> paintRowEven
          else -> paintRowOdd
        }

        canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + ROW_HEIGHT, rowBg)
        canvas.drawLine(MARGIN_LEFT, currentY + ROW_HEIGHT, MARGIN_RIGHT, currentY + ROW_HEIGHT, paintDivider)

        drawTableRow(canvas, currentY, row, textTd, textTdBold)
        currentY += ROW_HEIGHT
      }

      currentRowIndex = endIndex

      // If last page, draw Total / Summary Row
      if (pageNumber == totalPages && currentRowIndex >= rows.size) {
        val sumHeight = 22f
        canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + sumHeight, paintSummaryBg)
        canvas.drawLine(MARGIN_LEFT, currentY + sumHeight, MARGIN_RIGHT, currentY + sumHeight, paintBorder)

        var totalPrincipalPaid = 0L
        var totalProfitPaid = 0L
        var totalEmiPaid = 0L
        rows.forEach { r ->
          totalPrincipalPaid += r.principal
          totalProfitPaid += r.profit
          totalEmiPaid += r.emi + r.extraPayment
        }

        val textSumLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.rgb(15, 23, 42)
          textSize = 8f
          typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textSumVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.rgb(15, 23, 42)
          textSize = 8f
          typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        canvas.drawText("TOTAL PAYABLE:", MARGIN_LEFT + 28f, currentY + 14f, textSumLabel)

        // Principal Total
        val pStr = LoanCalculators.formatCurrency(totalPrincipalPaid, "")
        val pW = textSumVal.measureText(pStr)
        canvas.drawText(pStr, 282f - pW, currentY + 14f, textSumVal)

        // Profit Total
        val prStr = LoanCalculators.formatCurrency(totalProfitPaid, "")
        val prW = textSumVal.measureText(prStr)
        canvas.drawText(prStr, 356f - prW, currentY + 14f, textSumVal)

        // Installments Total
        val emiStr = LoanCalculators.formatCurrency(totalEmiPaid, "")
        val emiW = textSumVal.measureText(emiStr)
        canvas.drawText(emiStr, 438f - emiW, currentY + 14f, textSumVal)

        val finalBal = "PKR 0"
        val fbW = textSumVal.measureText(finalBal)
        canvas.drawText(finalBal, MARGIN_RIGHT - 10f - fbW, currentY + 14f, textSumVal)

        currentY += sumHeight + 4f
      }

      // --- FOOTER ON EVERY PAGE ---
      canvas.drawLine(MARGIN_LEFT, MARGIN_BOTTOM, MARGIN_RIGHT, MARGIN_BOTTOM, paintBorder)

      val disclaimer = "Note: This schedule is an indicative repayment plan based on prevailing terms. Actual dates and amounts may vary subject to final bank approval."
      canvas.drawText(disclaimer, MARGIN_LEFT, MARGIN_BOTTOM + 12f, textFooter)

      val pageStr = "Page $pageNumber of $totalPages"
      val pageStrW = textFooter.measureText(pageStr)
      canvas.drawText(pageStr, MARGIN_RIGHT - pageStrW, MARGIN_BOTTOM + 24f, textFooter)

      val officerSign = "Officer: $officerName • Khushhali Bank Loan Calculator App"
      canvas.drawText(officerSign, MARGIN_LEFT, MARGIN_BOTTOM + 24f, textFooter)

      doc.finishPage(page)
    }

    FileOutputStream(pdfFile).use { fos ->
      doc.writeTo(fos)
    }
    doc.close()

    return pdfFile
  }

  // Column Boundaries on 523pt usable width (MARGIN_LEFT = 36f, MARGIN_RIGHT = 559f):
  // Col 1: # (36f .. 64f, w = 28f)
  // Col 2: Due Date (64f .. 128f, w = 64f)
  // Col 3: Opening Balance (128f .. 210f, w = 82f)
  // Col 4: Principal (210f .. 282f, w = 72f)
  // Col 5: Markup/Profit (282f .. 356f, w = 74f)
  // Col 6: EMI / Payment (356f .. 438f, w = 82f)
  // Col 7: Closing Balance (438f .. 559f, w = 121f)

  private fun drawTableHeader(canvas: Canvas, y: Float, paint: Paint) {
    val textY = y + 13.5f

    canvas.drawText("#", 45f, textY, paint)
    canvas.drawText("Due Date", 74f, textY, paint)

    val ob = "Opening Bal"
    canvas.drawText(ob, 206f - paint.measureText(ob), textY, paint)

    val pr = "Principal"
    canvas.drawText(pr, 278f - paint.measureText(pr), textY, paint)

    val pf = "Markup/Profit"
    canvas.drawText(pf, 352f - paint.measureText(pf), textY, paint)

    val emi = "EMI / Payment"
    canvas.drawText(emi, 434f - paint.measureText(emi), textY, paint)

    val cb = "Closing Bal"
    canvas.drawText(cb, MARGIN_RIGHT - 12f - paint.measureText(cb), textY, paint)
  }

  private fun drawTableRow(canvas: Canvas, y: Float, row: AmortizationRow, paint: Paint, paintBold: Paint) {
    val textY = y + 12.5f

    // # Month
    val mStr = "${row.month}"
    canvas.drawText(mStr, 44f, textY, paintBold)

    // Due Date
    canvas.drawText(row.dueDate, 68f, textY, paint)

    // Opening Balance
    val obStr = LoanCalculators.formatCurrency(row.openingBalance, "")
    canvas.drawText(obStr, 206f - paint.measureText(obStr), textY, paint)

    // Principal
    val prStr = LoanCalculators.formatCurrency(row.principal, "")
    canvas.drawText(prStr, 278f - paintBold.measureText(prStr), textY, paintBold)

    // Profit
    val pfStr = LoanCalculators.formatCurrency(row.profit, "")
    canvas.drawText(pfStr, 352f - paint.measureText(pfStr), textY, paint)

    // Installment / EMI (+ extra payment if any)
    val totalPaid = row.emi + row.extraPayment
    val emiStr = if (row.extraPayment > 0L) {
      "${LoanCalculators.formatCurrency(totalPaid, "")}*"
    } else {
      LoanCalculators.formatCurrency(totalPaid, "")
    }
    canvas.drawText(emiStr, 434f - paintBold.measureText(emiStr), textY, paintBold)

    // Closing Balance
    val cbStr = LoanCalculators.formatCurrency(row.closingBalance, "")
    canvas.drawText(cbStr, MARGIN_RIGHT - 12f - paint.measureText(cbStr), textY, paint)
  }
}
