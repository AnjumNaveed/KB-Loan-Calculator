package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.CustomerLead
import com.example.model.LeadActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

private const val TAG = "GoogleSheetsRepo"

class GoogleSheetsRepository(private val context: Context) {

  companion object {
    /**
     * Recursively strips nested timestamps, action tags, emoji prefixes, and officer signatures
     * from serialized sheet entries to isolate the pure underlying content payload.
     */
    fun cleanRawActionPayload(raw: String): String {
      if (raw.isBlank()) return ""
      var text = raw.trim()
      var prev = ""
      var iterations = 0
      while (text != prev && iterations < 12) {
        prev = text
        iterations++
        // 1. Strip trailing officer signatures repeatedly: " - By: Officer Name (ID)"
        text = text.replace("(?i)\\s*-\\s*By\\s*:\\s*[^\\-\\[\\]]+(?:\\([^\\)]*\\))?\\s*$".toRegex(), "").trim()
        text = text.replace("(?i)\\s*-\\s*By\\s*:\\s*.*$".toRegex(), "").trim()
        // 2. Strip leading bullet points and timestamp brackets: e.g. "[2026-11-09 04:01 PM] "
        text = text.replace("^•?\\s*\\[[^\\]]*\\]\\s*".toRegex(), "").trim()
        // 3. Strip leading action tags and emoji headers: e.g. "📝 Note Added:", "🎯 Loan Lead Created:", "🔄 Status Changed:"
        text = text.replace("^(STATUS_CHANGE|STATUS|FOLLOW_UP|FOLLOWUP|CALL|PHONE|WHATSAPP|SMS|CREATED|PROPOSAL_UPDATE|PROPOSAL|NOTE_UPDATE|NOTE):\\s*".toRegex(RegexOption.IGNORE_CASE), "").trim()
        text = text.replace("^[🔄📅⏰📞💬✉️🎯📋📝✏️•\\s]+".toRegex(), "").trim()
        text = text.replace("^(Note Added|Status Changed|Follow-Up Date|Follow-Up Scheduled|Phone Call|Direct Phone Call|WhatsApp|WhatsApp Quotation|SMS|SMS Quotation|Lead Created|Loan Lead Created|Deposit Lead Created|Proposal Saved|Note / Comment Updated|Initial Customer Note):\\s*".toRegex(RegexOption.IGNORE_CASE), "").trim()
        text = text.replace("^(Current:|Note:|Notes:|Initial Note:)\\s*".toRegex(RegexOption.IGNORE_CASE), "").trim()
        text = text.replace("^(Lead status updated from |Lead status updated to |Scheduled follow-up for )".toRegex(RegexOption.IGNORE_CASE), "").trim()
      }
      return text.trim()
    }
  }

  private val prefs = context.getSharedPreferences("loan_calc_prefs", Context.MODE_PRIVATE)

  fun getWebhookUrl(): String {
    return prefs.getString("google_sheets_webhook_url", "")?.trim() ?: ""
  }

  fun setWebhookUrl(url: String) {
    prefs.edit().putString("google_sheets_webhook_url", url.trim()).apply()
  }

  /**
   * Sends a single customer lead record to the Google Sheets Apps Script Web App.
   * Upserts the row in-place matching by Lead ID OR Mobile Number.
   */
  suspend fun syncLeadToGoogleSheet(lead: CustomerLead, webhookUrlOverride: String? = null): Result<Boolean> {
    return syncLeadsBatchToGoogleSheet(listOf(lead), webhookUrlOverride)
  }

  /**
   * Sends a batch of customer leads to Google Sheets.
   * Uses in-place upsert by Lead ID in Column 1 or Mobile Number in Column 4.
   */
  suspend fun syncLeadsBatchToGoogleSheet(
    leads: List<CustomerLead>,
    webhookUrlOverride: String? = null
  ): Result<Boolean> = withContext(Dispatchers.IO) {
    if (leads.isEmpty()) return@withContext Result.success(true)

    val targetUrl = (webhookUrlOverride ?: getWebhookUrl()).trim()
    if (targetUrl.isBlank()) {
      return@withContext Result.failure(
        IllegalStateException("Google Sheets Web App URL not configured. Please set the Web App URL in Admin & Slabs.")
      )
    }

    var connection: HttpURLConnection? = null
    try {
      val url = URL(targetUrl)
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 20_000
        doInput = true
        doOutput = true
        instanceFollowRedirects = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
      }

      val payload = JSONObject().apply {
        put("action", "sync_leads")
        put("timestamp", System.currentTimeMillis())
        val leadsArray = JSONArray()
        leads.forEach { lead ->
          leadsArray.put(leadToJson(lead))
        }
        put("leads", leadsArray)
      }

      OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(payload.toString())
        writer.flush()
      }

      val responseCode = connection.responseCode
      if (responseCode in 200..399) {
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        Log.i(TAG, "Google Sheets sync response ($responseCode): $responseBody")
        Result.success(true)
      } else {
        val errorBody = connection.errorStream?.let {
          BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: "HTTP $responseCode"
        Log.w(TAG, "Google Sheets sync failed ($responseCode): $errorBody")
        Result.failure(Exception("Google Sheets returned HTTP $responseCode: $errorBody"))
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error syncing leads to Google Sheets", e)
      Result.failure(e)
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * Deletes specific leads from Google Sheet matching by Lead ID OR Mobile Number.
   */
  suspend fun deleteLeadsBatchFromGoogleSheet(
    leadIds: List<String>,
    mobiles: List<String> = emptyList(),
    webhookUrlOverride: String? = null
  ): Result<Boolean> = withContext(Dispatchers.IO) {
    if (leadIds.isEmpty() && mobiles.isEmpty()) return@withContext Result.success(true)
    val targetUrl = (webhookUrlOverride ?: getWebhookUrl()).trim()
    if (targetUrl.isBlank()) return@withContext Result.success(false)

    var connection: HttpURLConnection? = null
    try {
      val url = URL(targetUrl)
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 12_000
        readTimeout = 15_000
        doInput = true
        doOutput = true
        instanceFollowRedirects = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
      }

      val payload = JSONObject().apply {
        put("action", "delete_leads")
        put("timestamp", System.currentTimeMillis())
        val idsArray = JSONArray()
        leadIds.forEach { idsArray.put(it) }
        put("leadIds", idsArray)

        val phonesArray = JSONArray()
        mobiles.forEach { if (it.isNotBlank()) phonesArray.put(it) }
        put("mobiles", phonesArray)
      }

      OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(payload.toString())
        writer.flush()
      }

      val responseCode = connection.responseCode
      if (responseCode in 200..399) {
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        Log.i(TAG, "Google Sheets delete response ($responseCode): $responseBody")
        Result.success(true)
      } else {
        Result.failure(Exception("Google Sheets delete returned HTTP $responseCode"))
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting leads from Google Sheets", e)
      Result.failure(e)
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * Fetches all leads from Google Sheet for bi-directional device restore.
   */
  suspend fun fetchLeadsFromGoogleSheet(webhookUrlOverride: String? = null): Result<List<CustomerLead>> = withContext(Dispatchers.IO) {
    val targetUrl = (webhookUrlOverride ?: getWebhookUrl()).trim()
    if (targetUrl.isBlank()) return@withContext Result.success(emptyList())

    var connection: HttpURLConnection? = null
    try {
      val url = URL(targetUrl)
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 20_000
        doInput = true
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json")
      }

      val responseCode = connection.responseCode
      if (responseCode in 200..399) {
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        val jsonObj = JSONObject(responseBody)
        val leadsArray = jsonObj.optJSONArray("leads") ?: JSONArray()
        val resultList = mutableListOf<CustomerLead>()
        for (i in 0 until leadsArray.length()) {
          val item = leadsArray.getJSONObject(i)
          val id = item.optString("id").ifBlank { "sheet-$i" }
          val rawNotes = item.optString("notes")
          val cleanCurrentNote = if (rawNotes.isNotBlank()) {
            val lines = rawNotes.lines().map { it.trim() }.filter { it.isNotBlank() }
            val lastLine = lines.lastOrNull() ?: rawNotes
            cleanRawActionPayload(lastLine)
          } else {
            ""
          }

          var parsedLat: Double? = item.optDouble("latitude").takeIf { !it.isNaN() && it != 0.0 }
          var parsedLng: Double? = item.optDouble("longitude").takeIf { !it.isNaN() && it != 0.0 }
          val rawLocAddr = item.optString("locationAddress").trim()
          var cleanAddr = rawLocAddr

          if (rawLocAddr.contains(",")) {
            val parts = rawLocAddr.split("|")
            val coordPart = parts[0].trim()
            val sub = coordPart.split(",")
            if (sub.size == 2) {
              val latCandidate = sub[0].trim().toDoubleOrNull()
              val lngCandidate = sub[1].trim().toDoubleOrNull()
              if (latCandidate != null && lngCandidate != null && (latCandidate != 0.0 || lngCandidate != 0.0)) {
                if (parsedLat == null) parsedLat = latCandidate
                if (parsedLng == null) parsedLng = lngCandidate
                cleanAddr = if (parts.size > 1) parts[1].trim() else ""
              }
            }
          }

          val activitiesAudit = item.optString("activitiesAudit")
          val activitiesList = mutableListOf<LeadActivity>()
          if (activitiesAudit.isNotBlank()) {
            val lines = activitiesAudit.split("\n")
            lines.forEachIndexed { idx, line ->
              val cleanLine = line.trim().removePrefix("•").trim()
              if (cleanLine.isNotBlank()) {
                val upper = cleanLine.uppercase(Locale.US)
                val actionType = when {
                  upper.contains("NOTE_UPDATE") || upper.contains("NOTE ADDED") || upper.contains("📝 NOTE") || upper.contains("COMMENT") -> "note_update"
                  upper.contains("FOLLOW_UP") || upper.contains("FOLLOWUP") || upper.contains("FOLLOW-UP") || upper.contains("📅 FOLLOW") -> "follow_up"
                  upper.contains("CALL") || upper.contains("PHONE") || upper.contains("📞") -> "call"
                  upper.contains("WHATSAPP") || upper.contains("💬") -> "whatsapp"
                  upper.contains("SMS") || upper.contains("✉️") -> "sms"
                  upper.contains("STATUS") || upper.contains("🔄") -> "status_change"
                  upper.contains("PROPOSAL") || upper.contains("📋") -> "proposal_update"
                  upper.contains("CREATED") || upper.contains("🎯") -> "created"
                  else -> "note_update"
                }

                val parsedTitle = when (actionType) {
                  "status_change" -> "🔄 Status Changed"
                  "follow_up" -> "📅 Follow-Up Date"
                  "call" -> "📞 Phone Call"
                  "whatsapp" -> "💬 WhatsApp"
                  "sms" -> "✉️ SMS"
                  "created" -> if (item.optString("category").equals("liability", ignoreCase = true)) "🎯 Deposit Lead Created" else "🎯 Loan Lead Created"
                  "proposal_update" -> "📋 Proposal Saved"
                  else -> "📝 Note Added"
                }

                // 1. Extract historical timestamp from leading bracket [yyyy-MM-dd hh:mm a]
                val timeMatch = "\\[(.*?)\\]".toRegex().find(cleanLine)
                val timeStr = timeMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val parsedTimestamp = if (timeStr.isNotBlank()) {
                  val formats = listOf(
                    SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US),
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
                    SimpleDateFormat("dd-MMM-yyyy, hh:mm a", Locale.US),
                    SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US),
                    SimpleDateFormat("yyyy-MM-dd", Locale.US)
                  )
                  var parsedDate: Date? = null
                  for (f in formats) {
                    try {
                      parsedDate = f.parse(timeStr)
                      if (parsedDate != null) break
                    } catch (_: Exception) {}
                  }
                  parsedDate?.time ?: (System.currentTimeMillis() - ((lines.size - idx) * 60_000L))
                } else {
                  System.currentTimeMillis() - ((lines.size - idx) * 60_000L)
                }

                // 2. Extract officer credentials from trailing - By: ...
                val officerMatch = "(?i)-\\s*By\\s*:\\s*(.*?)$".toRegex().find(cleanLine)
                val rawOfficerPart = officerMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val firstOfficerPart = rawOfficerPart.split(" - By:", ignoreCase = true).firstOrNull()?.trim().orEmpty()
                val officerNameExtracted: String
                val officerIdExtracted: String
                if (firstOfficerPart.contains("(") && firstOfficerPart.endsWith(")")) {
                  officerNameExtracted = firstOfficerPart.substringBefore("(").trim()
                  officerIdExtracted = firstOfficerPart.substringAfter("(").substringBefore(")").trim()
                } else {
                  officerNameExtracted = firstOfficerPart
                  officerIdExtracted = ""
                }
                val finalOfficerName = officerNameExtracted.ifBlank { item.optString("officerName") }
                val finalOfficerId = officerIdExtracted.ifBlank { item.optString("officerId") }

                // 3. Extract pure payload without any nested prefixes or suffixes
                val cleanPayload = cleanRawActionPayload(cleanLine)
                val finalDetails = cleanPayload.ifBlank {
                  when (actionType) {
                    "status_change" -> item.optString("status", "New")
                    "follow_up" -> item.optString("followUpDate")
                    "call" -> "Phone call to ${item.optString("mobile")}"
                    "created" -> "${item.optString("productName")} (Rs. ${item.optLong("loanAmount")})"
                    else -> "Note recorded"
                  }
                }

                // 4. Avoid duplicates in memory
                val isDuplicate = activitiesList.any { existing ->
                  existing.actionType == actionType &&
                    existing.details.equals(finalDetails, ignoreCase = true) &&
                    abs(existing.timestamp - parsedTimestamp) < 15_000L
                }
                if (!isDuplicate) {
                  activitiesList.add(
                    LeadActivity(
                      id = UUID.randomUUID().toString(),
                      timestamp = parsedTimestamp,
                      actionType = actionType,
                      title = parsedTitle,
                      details = finalDetails,
                      officerName = finalOfficerName,
                      officerId = finalOfficerId
                    )
                  )
                }
              }
            }
          }

          // If activitiesList has no note_updates, extract individual note entries from Column 17
          if (activitiesList.none { it.actionType == "note_update" } && rawNotes.isNotBlank()) {
            rawNotes.split("\n").forEach { noteLine ->
              val cleanNote = cleanRawActionPayload(noteLine)
              if (cleanNote.isNotBlank()) {
                val timeMatch = "\\[(.*?)\\]".toRegex().find(noteLine)
                val timeStr = timeMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val noteTs = if (timeStr.isNotBlank()) {
                  try { SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US).parse(timeStr)?.time ?: System.currentTimeMillis() }
                  catch (_: Exception) { System.currentTimeMillis() }
                } else System.currentTimeMillis()

                val isDuplicate = activitiesList.any { existing ->
                  existing.actionType == "note_update" &&
                    existing.details.equals(cleanNote, ignoreCase = true) &&
                    abs(existing.timestamp - noteTs) < 15_000L
                }
                if (!isDuplicate) {
                  activitiesList.add(
                    LeadActivity(
                      id = UUID.randomUUID().toString(),
                      timestamp = noteTs,
                      actionType = "note_update",
                      title = "📝 Note Added",
                      details = cleanNote,
                      officerName = item.optString("officerName"),
                      officerId = item.optString("officerId")
                    )
                  )
                }
              }
            }
          }

          resultList.add(
            CustomerLead(
              id = id,
              customerName = item.optString("customerName", "Client"),
              mobile = item.optString("mobile"),
              category = item.optString("category", "asset"),
              productName = item.optString("productName"),
              loanAmount = item.optLong("loanAmount", 0L),
              tenureMonths = item.optInt("tenureMonths", 0),
              monthlyEmi = item.optLong("monthlyEmi", 0L),
              totalProfit = item.optLong("totalProfit", 0L),
              processingFee = item.optLong("processingFee", 0L),
              status = item.optString("status", "New"),
              followUpDate = item.optString("followUpDate"),
              officerName = item.optString("officerName"),
              officerId = item.optString("officerId"),
              branchName = item.optString("branchName"),
              notes = cleanCurrentNote,
              approachCount = item.optInt("approachCount", 1),
              locationAddress = cleanAddr,
              latitude = parsedLat,
              longitude = parsedLng,
              syncStatus = "synced",
              activities = activitiesList
            )
          )
        }
        Result.success(resultList)
      } else {
        Result.failure(Exception("HTTP $responseCode fetching leads"))
      }
    } catch (e: Exception) {
      Log.w(TAG, "Notice fetching leads from Google Sheet: ${e.message}")
      Result.failure(e)
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * Tests the Google Sheets Webhook URL with a test ping and verifies the deployed script version.
   */
  suspend fun testConnection(urlStr: String): Result<String> = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
      val cleanUrl = urlStr.trim()
      if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) {
        return@withContext Result.failure(IllegalArgumentException("Please enter a valid HTTP/HTTPS Web App URL."))
      }
      val url = URL(cleanUrl)
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 12_000
        doInput = true
        doOutput = true
        instanceFollowRedirects = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
      }

      val testPayload = JSONObject().apply {
        put("action", "ping")
        put("message", "KB Loan Calculator Connection Test")
        put("timestamp", System.currentTimeMillis())
      }

      OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(testPayload.toString())
        writer.flush()
      }

      val code = connection.responseCode
      if (code in 200..399) {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val resp = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        val parsed = try { JSONObject(resp) } catch (_: Exception) { null }
        val ver = parsed?.optString("version", "") ?: ""

        if (ver.contains("2.2") || ver.contains("dual-key")) {
          Result.success("✅ Connected! (Engine: $ver - Dual-Key In-Place Upsert Active)")
        } else if (ver.isNotBlank()) {
          Result.success("⚠️ Connected (Old Version $ver). Please update Apps Script: Deploy > Manage deployments > Edit > New version > Deploy.")
        } else {
          Result.success("Connected! Response: $resp")
        }
      } else {
        Result.failure(Exception("HTTP $code from Google Sheets endpoint"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    } finally {
      connection?.disconnect()
    }
  }

  private fun leadToJson(lead: CustomerLead): JSONObject {
    val dtFormat = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US)
    val dateCreatedStr = try {
      dtFormat.format(Date(lead.createdAt))
    } catch (_: Exception) {
      ""
    }

    // Build multi-line chronological user-authored remarks only for Column 17
    // System status changes and proposal events are routed exclusively to Column 20 (Audit Log)
    // Strictly formatted as: "[Date Time] PureUserNote" with zero action tags and zero officer signatures!
    val userNoteActivities = lead.activities
      .filter { it.actionType in listOf("note", "note_update") && it.details.isNotBlank() && it.details != "(Note cleared)" }
      .sortedBy { it.timestamp }

    val noteLines = mutableListOf<String>()
    for (act in userNoteActivities) {
      val cleanNote = cleanRawActionPayload(act.details)
      if (cleanNote.isNotBlank()) {
        val timeStr = try { dtFormat.format(Date(act.timestamp)) } catch (_: Exception) { dateCreatedStr }
        val line = "[$timeStr] $cleanNote"
        // Prevent duplicate identical consecutive note lines
        if (noteLines.none { it.endsWith("] $cleanNote") }) {
          noteLines.add(line)
        }
      }
    }

    val commentsHistory = if (noteLines.isNotEmpty()) {
      noteLines.joinToString("\n")
    } else if (lead.notes.isNotBlank()) {
      val clean = cleanRawActionPayload(lead.notes)
      if (clean.isNotBlank()) "[$dateCreatedStr] $clean" else ""
    } else {
      ""
    }

    // Format full audit trail chronologically (oldest to latest) as concise single-line actions for Column 20
    val sortedActivities = lead.activities.sortedBy { it.timestamp }
    val auditLines = mutableListOf<String>()
    for (act in sortedActivities) {
      val timeStr = try { dtFormat.format(Date(act.timestamp)) } catch (_: Exception) { dateCreatedStr }
      val cleanPayload = cleanRawActionPayload(act.details)
      val effectiveOfficer = act.officerName.ifBlank { lead.officerName.ifBlank { "Officer" } }
      val effectiveOfficerId = act.officerId.ifBlank { lead.officerId }
      val officerPart = if (effectiveOfficer.isNotBlank() || effectiveOfficerId.isNotBlank()) {
        " - By: $effectiveOfficer${if (effectiveOfficerId.isNotBlank()) " ($effectiveOfficerId)" else ""}"
      } else ""

      val desc = if (cleanPayload.isNotBlank() && !cleanPayload.equals(act.title, ignoreCase = true)) {
        "${act.title}: $cleanPayload"
      } else {
        act.title
      }
      val line = "[$timeStr] $desc$officerPart"
      // Avoid duplicate identical lines in the audit trail
      if (auditLines.none { it == line }) {
        auditLines.add(line)
      }
    }
    val activitiesSummary = auditLines.joinToString("\n")

    val locFormatted = if (lead.latitude != null && lead.longitude != null && (lead.latitude != 0.0 || lead.longitude != 0.0)) {
      val coordStr = String.format(Locale.US, "%.5f, %.5f", lead.latitude, lead.longitude)
      if (lead.locationAddress.isNotBlank()) "$coordStr | ${lead.locationAddress}" else coordStr
    } else {
      lead.locationAddress
    }

    return JSONObject().apply {
      put("id", lead.id)
      put("customerName", lead.customerName)
      put("mobile", lead.mobile)
      put("dob", lead.dob)
      put("age", lead.age)
      put("monthlyIncome", lead.monthlyIncome)
      put("dbrAllowed", lead.dbrAllowed)
      put("productId", lead.productId)
      put("productName", lead.productName)
      put("category", lead.category)
      put("isFiler", if (lead.isFiler) "Filer" else "Non-Filer")
      put("taxRate", lead.taxRate)
      put("taxDeducted", lead.taxDeducted)
      put("netProfit", lead.netProfit)
      put("loanAmount", lead.loanAmount)
      put("tenureMonths", lead.tenureMonths)
      put("monthlyEmi", lead.monthlyEmi)
      put("totalProfit", lead.totalProfit)
      put("processingFee", lead.processingFee)
      put("status", lead.status)
      put("followUpDate", lead.followUpDate)
      put("notes", commentsHistory)
      put("officerName", lead.officerName)
      put("officerId", lead.officerId)
      put("branchName", lead.branchName)
      put("userId", lead.userId)
      put("createdAtMs", lead.createdAt)
      put("createdAtFormatted", dateCreatedStr)
      put("approachCount", lead.approachCount)
      put("notesUpdateCount", lead.notesUpdateCount)
      put("locationAddress", locFormatted)
      put("latitude", lead.latitude ?: 0.0)
      put("longitude", lead.longitude ?: 0.0)
      put("activitiesAudit", activitiesSummary)
    }
  }
}
