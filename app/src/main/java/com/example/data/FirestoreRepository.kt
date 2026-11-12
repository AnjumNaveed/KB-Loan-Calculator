package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.example.model.CustomerLead
import com.example.model.FeeSlab
import com.example.model.GlobalConfig
import com.example.model.LeadActivity
import com.example.model.LoanProduct
import com.example.model.OfficerProfile
import com.example.model.RateSlab
import com.example.model.RegisteredUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

private const val TAG = "FirestoreRepo"

class FirestoreRepository(val db: FirebaseFirestore) {

  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  suspend fun ensureNetworkActive() {
    try {
      withTimeout(4_000L) {
        db.enableNetwork().await()
      }
    } catch (_: Exception) {}
  }

  /**
   * Cycles Firestore's gRPC network layer (disableNetwork -> enableNetwork) to immediately
   * clear any stuck exponential backoff timer or dead socket when internet is active.
   */
  suspend fun forceReconnectNetwork() {
    try {
      withTimeout(3_500L) {
        db.disableNetwork().await()
      }
    } catch (_: Exception) {}
    try {
      withTimeout(4_000L) {
        db.enableNetwork().await()
      }
    } catch (_: Exception) {}
  }

  private suspend fun getDocumentFreshFirst(
    docRef: com.google.firebase.firestore.DocumentReference
  ): com.google.firebase.firestore.DocumentSnapshot {
    return try {
      withTimeout(8_000L) {
        docRef.get(Source.SERVER).await()
      }
    } catch (_: Exception) {
      withTimeout(5_000L) {
        docRef.get(Source.DEFAULT).await()
      }
    }
  }

  private suspend fun getCollectionFreshFirst(
    colRef: com.google.firebase.firestore.CollectionReference
  ): com.google.firebase.firestore.QuerySnapshot {
    return try {
      withTimeout(8_000L) {
        colRef.get(Source.SERVER).await()
      }
    } catch (_: Exception) {
      withTimeout(5_000L) {
        colRef.get(Source.DEFAULT).await()
      }
    }
  }

  private fun parseProductDoc(doc: com.google.firebase.firestore.DocumentSnapshot): LoanProduct? {
    return try {
      val id = doc.getString("id") ?: doc.id
      val name = doc.getString("name") ?: "Loan Product"
      val rateType = doc.getString("rateType") ?: "reducing"
      val defaultRate = doc.getDouble("defaultAnnualRate")
        ?: (doc.getLong("defaultAnnualRate")?.toDouble() ?: 20.0)
      val minAmount = doc.getLong("minAmount") ?: 50000L
      val maxAmount = doc.getLong("maxAmount") ?: 5000000L
      val minTenure = (doc.getLong("minTenure") ?: 1L).toInt()
      val maxTenure = (doc.getLong("maxTenure") ?: 120L).toInt()
      val minAge = (doc.getLong("minAge") ?: 21L).toInt()
      val maxAge = (doc.getLong("maxAgeAtMaturity") ?: 65L).toInt()
      val feeType = doc.getString("feeType") ?: "percentage"
      val defaultFeeValue = doc.getDouble("defaultFeeValue")
        ?: (doc.getLong("defaultFeeValue")?.toDouble() ?: 1.5)
      val fedRate = doc.getDouble("fedRate")
        ?: (doc.getLong("fedRate")?.toDouble() ?: 16.0)
      val collateralDetails = doc.getString("collateralDetails") ?: ""
      val category = doc.getString("category") ?: "asset"

      val rawRateSlabs = doc.get("rateSlabs") as? List<*>
      val rateSlabs = rawRateSlabs?.mapNotNull { item ->
        (item as? Map<*, *>)?.let { map ->
          val min = (map["minAmount"] as? Number)?.toLong() ?: 0L
          val max = (map["maxAmount"] as? Number)?.toLong() ?: 0L
          val rate = (map["annualRate"] as? Number)?.toDouble() ?: defaultRate
          RateSlab(min, max, rate)
        }
      } ?: emptyList()

      val rawFeeSlabs = doc.get("feeSlabs") as? List<*>
      val feeSlabs = rawFeeSlabs?.mapNotNull { item ->
        (item as? Map<*, *>)?.let { map ->
          val min = (map["minAmount"] as? Number)?.toLong() ?: 0L
          val max = (map["maxAmount"] as? Number)?.toLong() ?: 0L
          val fType = (map["feeType"] as? String) ?: feeType
          val fVal = (map["feeValue"] as? Number)?.toDouble() ?: defaultFeeValue
          val minFee = (map["minFee"] as? Number)?.toLong() ?: 0L
          val maxFee = (map["maxFee"] as? Number)?.toLong() ?: 0L
          FeeSlab(min, max, fType, fVal, minFee, maxFee)
        }
      } ?: emptyList()

      val rawFixedTenures = doc.get("fixedTenures") as? List<*>
      val fixedTenures = rawFixedTenures?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

      val rawTenureRates = doc.get("tenureRates") as? Map<*, *>
      val tenureRates = rawTenureRates?.mapNotNull { (k, v) ->
        val t = k?.toString()?.toIntOrNull()
        val r = (v as? Number)?.toDouble()
        if (t != null && r != null) t to r else null
      }?.toMap() ?: emptyMap()

      LoanProduct(
        id = id,
        name = name,
        rateType = rateType,
        defaultAnnualRate = defaultRate,
        minAmount = minAmount,
        maxAmount = maxAmount,
        minTenure = minTenure,
        maxTenure = maxTenure,
        minAge = minAge,
        maxAgeAtMaturity = maxAge,
        feeType = feeType,
        defaultFeeValue = defaultFeeValue,
        fedRate = fedRate,
        collateralDetails = collateralDetails,
        rateSlabs = rateSlabs,
        feeSlabs = feeSlabs,
        category = category,
        fixedTenures = fixedTenures,
        tenureRates = tenureRates,
        liabilityPricingType = doc.getString("liabilityPricingType").orEmpty()
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing product doc ${doc.id}", e)
      null
    }
  }

  // 1. Observe Products collection in real-time
  fun observeProducts(): Flow<List<LoanProduct>> {
    return db.collection("products")
      .snapshots()
      .map { snapshot ->
        snapshot.documents.mapNotNull { doc -> parseProductDoc(doc) }
      }
      .retryWhen { cause, _ ->
        Log.w(TAG, "observeProducts stream error, retrying...", cause)
        delay(2_500L)
        true
      }
      .catch { e ->
        Log.w(TAG, "observeProducts caught error", e)
      }
  }

  /**
   * Automatically fetches global product configurations from Firestore `products` collection
   * and seeds any missing standard bank facilities so all devices share identical real-time slabs.
   */
  suspend fun autoFetchGlobalProducts(defaults: List<LoanProduct>): List<LoanProduct> {
    return try {
      ensureNetworkActive()
      seedDefaultProductsIfEmpty(defaults)
      val snapshot = getCollectionFreshFirst(db.collection("products"))
      val fetched = snapshot.documents.mapNotNull { parseProductDoc(it) }
      if (fetched.isNotEmpty()) fetched else defaults
    } catch (e: Exception) {
      Log.w(TAG, "Error in autoFetchGlobalProducts, falling back to defaults", e)
      defaults
    }
  }

  /**
   * Synchronizes application state (officer profile, pending leads, and global config)
   * with Firestore documents for real-time cross-device persistence.
   */
  suspend fun saveState(
    profile: OfficerProfile,
    leads: List<CustomerLead>,
    userId: String,
    userEmail: String
  ) {
    try {
      if (userId.isNotBlank() && profile.isOnboarded) {
        syncCurrentUserProfile(
          uid = userId,
          email = userEmail,
          officerName = profile.officerName,
          staffId = profile.staffId,
          branchName = profile.branchName
        )
      }
      val pendingLeads = leads.filter { it.syncStatus != "synced" }
      for (lead in pendingLeads) {
        saveLead(lead.copy(syncStatus = "synced"), userId.ifBlank { lead.userId.ifBlank { "officer" } })
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error syncing state to Firestore", e)
    }
  }

  // 2. Save or update product in Firestore
  suspend fun saveProduct(product: LoanProduct) {
    val rateSlabsData = product.rateSlabs.map { s ->
      mapOf(
        "minAmount" to s.minAmount,
        "maxAmount" to s.maxAmount,
        "annualRate" to s.annualRate
      )
    }

    val feeSlabsData = product.feeSlabs.map { f ->
      mapOf(
        "minAmount" to f.minAmount,
        "maxAmount" to f.maxAmount,
        "feeType" to f.feeType,
        "feeValue" to f.feeValue,
        "minFee" to f.minFee,
        "maxFee" to f.maxFee
      )
    }

    val data = hashMapOf<String, Any>(
      "id" to product.id,
      "name" to product.name,
      "rateType" to product.rateType,
      "defaultAnnualRate" to product.defaultAnnualRate,
      "minAmount" to product.minAmount,
      "maxAmount" to product.maxAmount,
      "minTenure" to product.minTenure,
      "maxTenure" to product.maxTenure,
      "minAge" to product.minAge,
      "maxAgeAtMaturity" to product.maxAgeAtMaturity,
      "feeType" to product.feeType,
      "defaultFeeValue" to product.defaultFeeValue,
      "fedRate" to product.fedRate,
      "collateralDetails" to product.collateralDetails,
      "category" to product.category,
      "fixedTenures" to product.fixedTenures,
      "tenureRates" to product.tenureRates.mapKeys { it.key.toString() },
      "liabilityPricingType" to product.liabilityPricingType,
      "rateSlabs" to rateSlabsData,
      "feeSlabs" to feeSlabsData,
      "updatedAt" to FieldValue.serverTimestamp()
    )

    ensureNetworkActive()
    withTimeout(8_000L) {
      db.collection("products").document(product.id).set(data).await()
    }
  }

  suspend fun deleteProduct(productId: String) {
    ensureNetworkActive()
    withTimeout(8_000L) {
      db.collection("products").document(productId).delete().await()
    }
  }

  suspend fun seedDefaultProductsIfEmpty(defaults: List<LoanProduct>) {
    try {
      val existing = db.collection("products").get().await()
      val existingDocs = existing.documents.associateBy { it.id }
      val existingIds = existingDocs.keys
      val obsoleteIds = setOf(
        "prod-kb-personal", "prod-kb-auto", "prod-kb-home", "prod-kb-sme",
        "prod-liab-mahana-amdan", "prod-liab-pls-savings", "prod-liab-senior-citizen"
      )
      for (obsId in obsoleteIds) {
        if (obsId in existingIds) {
          db.collection("products").document(obsId).delete().await()
        }
      }
      for (prod in defaults) {
        val existingDoc = existingDocs[prod.id]
        val needsUpdate = existingDoc == null ||
          (prod.category == "liability" && (existingDoc.get("tenureRates") == null || existingDoc.get("fixedTenures") == null))
        if (needsUpdate) {
          saveProduct(prod)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error seeding default products", e)
    }
  }

  // 3. Observe Leads collection & /config/leads_registry in real-time
  private fun parseLeadMap(map: Map<*, *>, fallbackId: String = ""): CustomerLead? {
    return try {
      val id = (map["id"] as? String)?.ifBlank { fallbackId } ?: fallbackId
      if (id.isBlank()) return null
      val rawActivities = (map["activities"] as? List<*>) ?: emptyList<Any>()
      val activityList = rawActivities.mapNotNull { actMap ->
        (actMap as? Map<*, *>)?.let { a ->
          LeadActivity(
            id = (a["id"] as? String) ?: java.util.UUID.randomUUID().toString(),
            timestamp = (a["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            actionType = (a["actionType"] as? String) ?: "follow_up",
            title = (a["title"] as? String) ?: "",
            details = (a["details"] as? String) ?: "",
            officerName = (a["officerName"] as? String) ?: "",
            officerId = (a["officerId"] as? String) ?: ""
          )
        }
      }
      val approachCount = (map["approachCount"] as? Number)?.toInt()
        ?: activityList.count { it.actionType in listOf("follow_up", "call", "whatsapp", "sms") }
      val notesUpdateCount = (map["notesUpdateCount"] as? Number)?.toInt()
        ?: activityList.count { it.actionType == "note_update" }

      CustomerLead(
        id = id,
        customerName = (map["customerName"] as? String) ?: "",
        mobile = (map["mobile"] as? String) ?: "",
        dob = (map["dob"] as? String) ?: "",
        age = (map["age"] as? Number)?.toInt() ?: 0,
        monthlyIncome = (map["monthlyIncome"] as? Number)?.toLong() ?: 0L,
        dbrAllowed = (map["dbrAllowed"] as? Number)?.toInt() ?: 50,
        productId = (map["productId"] as? String) ?: "",
        productName = (map["productName"] as? String) ?: "",
        loanAmount = (map["loanAmount"] as? Number)?.toLong() ?: 0L,
        tenureMonths = (map["tenureMonths"] as? Number)?.toInt() ?: 36,
        monthlyEmi = (map["monthlyEmi"] as? Number)?.toLong() ?: 0L,
        totalProfit = (map["totalProfit"] as? Number)?.toLong() ?: 0L,
        processingFee = (map["processingFee"] as? Number)?.toLong() ?: 0L,
        status = (map["status"] as? String) ?: "New",
        notes = (map["notes"] as? String) ?: "",
        followUpDate = (map["followUpDate"] as? String) ?: "",
        officerName = (map["officerName"] as? String) ?: "",
        officerId = (map["officerId"] as? String) ?: "",
        branchName = (map["branchName"] as? String) ?: "",
        userId = (map["userId"] as? String) ?: "",
        createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        syncStatus = "synced",
        approachCount = approachCount,
        notesUpdateCount = notesUpdateCount,
        activities = activityList,
        category = (map["category"] as? String) ?: "asset",
        isFiler = (map["isFiler"] as? Boolean) ?: true,
        taxRate = (map["taxRate"] as? Number)?.toDouble() ?: 20.0,
        taxDeducted = (map["taxDeducted"] as? Number)?.toLong() ?: 0L,
        netProfit = (map["netProfit"] as? Number)?.toLong() ?: 0L,
        latitude = (map["latitude"] as? Number)?.toDouble(),
        longitude = (map["longitude"] as? Number)?.toDouble(),
        locationAddress = (map["locationAddress"] as? String) ?: "",
        locationCapturedAt = (map["locationCapturedAt"] as? Number)?.toLong()
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing lead map $fallbackId", e)
      null
    }
  }

  private fun leadToMap(lead: CustomerLead, userId: String): HashMap<String, Any> {
    val map = hashMapOf<String, Any>(
      "id" to lead.id,
      "customerName" to lead.customerName,
      "mobile" to lead.mobile,
      "dob" to lead.dob,
      "age" to lead.age,
      "monthlyIncome" to lead.monthlyIncome,
      "dbrAllowed" to lead.dbrAllowed,
      "productId" to lead.productId,
      "productName" to lead.productName,
      "category" to lead.category,
      "isFiler" to lead.isFiler,
      "taxRate" to lead.taxRate,
      "taxDeducted" to lead.taxDeducted,
      "netProfit" to lead.netProfit,
      "loanAmount" to lead.loanAmount,
      "tenureMonths" to lead.tenureMonths,
      "monthlyEmi" to lead.monthlyEmi,
      "totalProfit" to lead.totalProfit,
      "processingFee" to lead.processingFee,
      "status" to lead.status,
      "notes" to lead.notes,
      "followUpDate" to lead.followUpDate,
      "officerName" to lead.officerName,
      "officerId" to lead.officerId,
      "branchName" to lead.branchName,
      "userId" to userId.ifBlank { lead.userId.ifBlank { "officer" } },
      "createdAt" to lead.createdAt,
      "updatedAtMs" to System.currentTimeMillis(),
      "approachCount" to lead.approachCount,
      "notesUpdateCount" to lead.notesUpdateCount,
      "activities" to lead.activities.map { act ->
        hashMapOf(
          "id" to act.id,
          "timestamp" to act.timestamp,
          "actionType" to act.actionType,
          "title" to act.title,
          "details" to act.details,
          "officerName" to act.officerName,
          "officerId" to act.officerId
        )
      }
    )
    if (lead.latitude != null) map["latitude"] = lead.latitude
    if (lead.longitude != null) map["longitude"] = lead.longitude
    if (lead.locationAddress.isNotBlank()) map["locationAddress"] = lead.locationAddress
    if (lead.locationCapturedAt != null) map["locationCapturedAt"] = lead.locationCapturedAt
    return map
  }

  fun observeLeads(): Flow<List<CustomerLead>> {
    return db.collection("leads")
      .snapshots()
      .map { snapshot ->
        snapshot.documents.mapNotNull { doc ->
          doc.data?.let { parseLeadMap(it, doc.id) }
        }
      }
      .retryWhen { cause, _ ->
        Log.w(TAG, "observeLeads stream error, retrying...", cause)
        delay(2_500L)
        true
      }
      .catch { e ->
        Log.w(TAG, "observeLeads caught error", e)
      }
  }

  /**
   * Observes `/config/leads_registry` in real-time so that all leads across all devices
   * synchronize immediately even if `/leads` collection rules are cached or restricted.
   */
  fun observeLeadsRegistry(): Flow<Pair<List<CustomerLead>, Set<String>>> {
    return db.collection("config").document("leads_registry")
      .snapshots()
      .map { doc ->
        if (doc.exists()) {
          val deletedIds = (doc.get("deletedLeadIds") as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?.toSet() ?: emptySet()
          val leadsList = mutableListOf<CustomerLead>()
          val rawMap = doc.get("leadsMap") as? Map<*, *>
          rawMap?.forEach { (k, v) ->
            val m = v as? Map<*, *>
            if (m != null) {
              parseLeadMap(m, k?.toString().orEmpty())?.let { lead ->
                if (lead.id !in deletedIds) {
                  leadsList.add(lead)
                }
              }
            }
          }
          Pair<List<CustomerLead>, Set<String>>(leadsList.toList(), deletedIds)
        } else {
          Pair<List<CustomerLead>, Set<String>>(emptyList(), emptySet())
        }
      }
      .retryWhen { cause, _ ->
        Log.w(TAG, "observeLeadsRegistry stream error, retrying...", cause)
        delay(2_500L)
        true
      }
      .catch { e ->
        Log.w(TAG, "observeLeadsRegistry caught error", e)
      }
  }

  suspend fun fetchAllLeadsFromFirestore(): Pair<List<CustomerLead>, Set<String>> {
    ensureNetworkActive()
    val map = LinkedHashMap<String, CustomerLead>()
    val deletedIds = mutableSetOf<String>()

    // Fetch directly from /leads collection (unlimited scalability per lead document)
    try {
      val snap = getCollectionFreshFirst(db.collection("leads"))
      for (doc in snap.documents) {
        val data = doc.data ?: continue
        parseLeadMap(data, doc.id)?.let { lead ->
          map[lead.id] = lead
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error fetching /leads collection", e)
    }

    return Pair(map.values.sortedByDescending { it.createdAt }, deletedIds)
  }

  // 4. Save or update lead in Firestore (Pure Direct Document Model: /leads/{id})
  suspend fun saveLead(
    lead: CustomerLead,
    userId: String,
    removeDuplicateIds: Collection<String> = emptyList()
  ) {
    ensureNetworkActive()
    val effectiveUserId = userId.ifBlank { lead.userId.ifBlank { "officer" } }
    val data = leadToMap(lead, effectiveUserId)
    data["updatedAt"] = FieldValue.serverTimestamp()

    // Fast, direct document write (same reliable model as products and users)
    withTimeout(10_000L) {
      db.collection("leads").document(lead.id).set(data, SetOptions.merge()).await()
    }

    // Clean up any duplicates if specified
    val cleanDups = removeDuplicateIds.filter { it.isNotBlank() && it != lead.id }
    for (dupId in cleanDups) {
      try {
        db.collection("leads").document(dupId).delete().await()
      } catch (_: Exception) {}
    }
  }

  suspend fun deleteLead(leadId: String) {
    deleteLeadsBatch(listOf(leadId))
  }

  suspend fun deleteLeadsBatch(leadIds: Collection<String>) {
    val cleanIds = leadIds.filter { it.isNotBlank() }.distinct()
    if (cleanIds.isEmpty()) return
    ensureNetworkActive()

    for (id in cleanIds) {
      try {
        withTimeout(6_000L) {
          db.collection("leads").document(id).delete().await()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Error deleting lead $id from /leads", e)
      }
    }
  }

  suspend fun clearAllLeads(leads: List<CustomerLead>) {
    deleteLeadsBatch(leads.map { it.id })
  }

  // 4B. Registered Users & Branch / App Access Control in Firestore
  private fun parseUserMap(map: Map<*, *>, fallbackUid: String = ""): RegisteredUser? {
    return try {
      val branch = (map["branchName"] as? String) ?: ""
      val uid = (map["uid"] as? String)?.ifBlank { fallbackUid } ?: fallbackUid
      val email = (map["email"] as? String) ?: ""
      val name = (map["officerName"] as? String) ?: ""
      val staffId = (map["staffId"] as? String) ?: ""
      if (uid.isBlank() && staffId.isBlank() && email.isBlank() && name.isBlank()) return null
      RegisteredUser(
        uid = uid,
        email = email,
        officerName = name,
        staffId = staffId,
        branchName = branch,
        allowedSupervisorBranch = (map["allowedSupervisorBranch"] as? String)?.ifBlank { branch } ?: branch,
        canUseSupervisorFilter = (map["canUseSupervisorFilter"] as? Boolean) ?: true,
        isBlocked = (map["isBlocked"] as? Boolean) ?: true,
        role = (map["role"] as? String)?.ifBlank { "Loan Officer" } ?: "Loan Officer",
        updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
      )
    } catch (e: Exception) {
      null
    }
  }

  private fun userToMap(user: RegisteredUser): Map<String, Any> {
    val docId = user.uid.ifBlank { "user-${user.staffId.ifBlank { System.currentTimeMillis().toString() }}" }
    val cleanBranch = user.branchName.trim()
    val cleanAllowedBranch = user.allowedSupervisorBranch.trim().ifBlank { cleanBranch }
    return hashMapOf(
      "uid" to docId,
      "email" to user.email.trim(),
      "officerName" to user.officerName.trim(),
      "staffId" to user.staffId.trim(),
      "branchName" to cleanBranch,
      "allowedSupervisorBranch" to cleanAllowedBranch,
      "canUseSupervisorFilter" to user.canUseSupervisorFilter,
      "isBlocked" to user.isBlocked,
      "role" to user.role.trim().ifBlank { "Loan Officer" },
      "updatedAt" to user.updatedAt
    )
  }

  fun observeUsers(): Flow<List<RegisteredUser>> {
    return db.collection("users")
      .snapshots()
      .map { snapshot ->
        snapshot.documents.mapNotNull { doc ->
          try {
            val branch = doc.getString("branchName") ?: ""
            RegisteredUser(
              uid = doc.getString("uid") ?: doc.id,
              email = doc.getString("email") ?: "",
              officerName = doc.getString("officerName") ?: "",
              staffId = doc.getString("staffId") ?: "",
              branchName = branch,
              allowedSupervisorBranch = doc.getString("allowedSupervisorBranch")?.ifBlank { branch } ?: branch,
              canUseSupervisorFilter = doc.getBoolean("canUseSupervisorFilter") ?: true,
              isBlocked = doc.getBoolean("isBlocked") ?: true,
              role = doc.getString("role") ?: "Loan Officer",
              updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
            )
          } catch (e: Exception) {
            Log.e(TAG, "Error parsing user doc ${doc.id}", e)
            null
          }
        }
      }
      .retryWhen { cause, _ ->
        Log.w(TAG, "observeUsers stream error, retrying...", cause)
        delay(2_500L)
        true
      }
      .catch { e ->
        Log.w(TAG, "observeUsers caught error", e)
      }
  }

  suspend fun fetchAllUsersFromFirestore(): List<RegisteredUser> {
    ensureNetworkActive()
    val resultMap = LinkedHashMap<String, RegisteredUser>()
    try {
      val globalDoc = getDocumentFreshFirst(db.collection("config").document("global"))
      if (globalDoc.exists()) {
        val rawRegistry = globalDoc.get("registeredUsersMap") as? Map<*, *>
        rawRegistry?.forEach { (k, v) ->
          val m = v as? Map<*, *>
          if (m != null) {
            parseUserMap(m, k?.toString().orEmpty())?.let { u ->
              resultMap[u.uid] = u
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error fetching users from config/global", e)
    }
    try {
      val snapshot = getCollectionFreshFirst(db.collection("users"))
      for (doc in snapshot.documents) {
        val branch = doc.getString("branchName") ?: ""
        val u = RegisteredUser(
          uid = doc.getString("uid") ?: doc.id,
          email = doc.getString("email") ?: "",
          officerName = doc.getString("officerName") ?: "",
          staffId = doc.getString("staffId") ?: "",
          branchName = branch,
          allowedSupervisorBranch = doc.getString("allowedSupervisorBranch")?.ifBlank { branch } ?: branch,
          canUseSupervisorFilter = doc.getBoolean("canUseSupervisorFilter") ?: true,
          isBlocked = doc.getBoolean("isBlocked") ?: true,
          role = doc.getString("role") ?: "Loan Officer",
          updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
        )
        val existing = resultMap[u.uid]
        if (existing == null || u.updatedAt >= existing.updatedAt) {
          resultMap[u.uid] = u
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error fetching users collection", e)
    }
    return resultMap.values.toList()
  }

  private suspend fun upsertUserInGlobalRegistry(user: RegisteredUser, clearDeletedKeys: Set<String> = emptySet()) {
    try {
      val globalRef = db.collection("config").document("global")
      val safeKey = user.uid.ifBlank { "staff_${user.staffId.trim().lowercase()}" }
      val usersMapDelta = HashMap<String, Any>()
      try {
        val doc = getDocumentFreshFirst(globalRef)
        val rawExisting = doc.get("registeredUsersMap") as? Map<*, *>
        rawExisting?.forEach { (k, v) ->
          val keyStr = k?.toString().orEmpty()
          if (keyStr.isNotBlank() && keyStr != safeKey && v != null) {
            val existingUser = (v as? Map<*, *>)?.let { parseUserMap(it, keyStr) }
            val isDuplicateStaff = user.staffId.isNotBlank() &&
              existingUser?.staffId?.equals(user.staffId.trim(), ignoreCase = true) == true
            val isDuplicateEmail = user.email.isNotBlank() &&
              existingUser?.email?.equals(user.email.trim(), ignoreCase = true) == true
            if (isDuplicateStaff || isDuplicateEmail) {
              usersMapDelta[keyStr] = FieldValue.delete()
            }
          }
        }
      } catch (_: Exception) {}

      usersMapDelta[safeKey] = userToMap(user.copy(uid = safeKey))

      val payload = HashMap<String, Any>(
        mapOf(
          "registeredUsersMap" to usersMapDelta,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      if (clearDeletedKeys.isNotEmpty()) {
        payload["deletedUserKeys"] = FieldValue.arrayRemove(*clearDeletedKeys.toTypedArray())
      }

      withTimeout(10_000L) {
        globalRef.set(payload, SetOptions.merge()).await()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error upserting user in global registry", e)
    }
  }

  suspend fun syncCurrentUserProfile(
    uid: String,
    email: String,
    officerName: String,
    staffId: String,
    branchName: String
  ) {
    val cleanUid = uid.trim()
    if (cleanUid.isBlank()) return
    val cleanEmail = email.trim()
    val cleanName = officerName.trim().ifBlank {
      if (cleanEmail.isNotBlank()) cleanEmail.substringBefore("@") else "New Officer"
    }
    val cleanStaff = staffId.trim()
    val cleanBranch = branchName.trim()

    var syncedUser: RegisteredUser? = null

    try {
      // First check if user already exists in /config/global or /users/{uid}
      var existingUser: RegisteredUser? = null
      try {
        val globalDoc = db.collection("config").document("global").get().await()
        val rawMap = globalDoc.get("registeredUsersMap") as? Map<*, *>
        rawMap?.forEach { (k, v) ->
          val m = v as? Map<*, *> ?: return@forEach
          val parsed = parseUserMap(m, k?.toString().orEmpty()) ?: return@forEach
          if (parsed.uid == cleanUid ||
            (cleanEmail.isNotBlank() && parsed.email.equals(cleanEmail, ignoreCase = true)) ||
            (cleanStaff.isNotBlank() && parsed.staffId.equals(cleanStaff, ignoreCase = true))
          ) {
            existingUser = parsed
          }
        }
      } catch (_: Exception) {}

      if (existingUser == null) {
        try {
          val docSnap = db.collection("users").document(cleanUid).get().await()
          if (docSnap.exists()) {
            val b = docSnap.getString("branchName") ?: ""
            existingUser = RegisteredUser(
              uid = cleanUid,
              email = docSnap.getString("email") ?: cleanEmail,
              officerName = docSnap.getString("officerName") ?: cleanName,
              staffId = docSnap.getString("staffId") ?: cleanStaff,
              branchName = b,
              allowedSupervisorBranch = docSnap.getString("allowedSupervisorBranch")?.ifBlank { b } ?: b,
              canUseSupervisorFilter = docSnap.getBoolean("canUseSupervisorFilter") ?: true,
              isBlocked = docSnap.getBoolean("isBlocked") ?: true,
              role = docSnap.getString("role") ?: "Loan Officer",
              updatedAt = System.currentTimeMillis()
            )
          }
        } catch (_: Exception) {}
      }

      val finalUser = if (existingUser != null) {
        val prev = existingUser!!
        val effBranch = cleanBranch.ifBlank { prev.branchName }
        val effAllowed = prev.allowedSupervisorBranch.ifBlank { effBranch }
        prev.copy(
          uid = cleanUid,
          email = cleanEmail.ifBlank { prev.email },
          officerName = officerName.trim().ifBlank { prev.officerName.ifBlank { cleanName } },
          staffId = cleanStaff.ifBlank { prev.staffId },
          branchName = effBranch,
          allowedSupervisorBranch = effAllowed,
          updatedAt = System.currentTimeMillis()
        )
      } else {
        // Brand-new user: BLOCKED by default until Admin enables them
        RegisteredUser(
          uid = cleanUid,
          email = cleanEmail,
          officerName = cleanName,
          staffId = cleanStaff,
          branchName = cleanBranch,
          allowedSupervisorBranch = cleanBranch,
          canUseSupervisorFilter = true,
          isBlocked = true,
          role = "Loan Officer",
          updatedAt = System.currentTimeMillis()
        )
      }
      syncedUser = finalUser

      // 1. Always write to /config/global (guaranteed cross-device sync)
      upsertUserInGlobalRegistry(finalUser)

      // 2. Also write to /users/{cleanUid}
      db.collection("users").document(cleanUid).set(userToMap(finalUser), SetOptions.merge()).await()
    } catch (e: Exception) {
      Log.w(TAG, "Error syncing current user profile to Firestore", e)
      syncedUser?.let {
        try {
          upsertUserInGlobalRegistry(it)
        } catch (_: Exception) {}
      }
    }
  }

  suspend fun saveUserAccessRights(user: RegisteredUser, clearDeletedKeys: Set<String> = emptySet()) {
    val docId = user.uid.ifBlank { "user-${user.staffId.ifBlank { System.currentTimeMillis().toString() }}" }
    val normalizedUser = user.copy(
      uid = docId,
      email = user.email.trim(),
      officerName = user.officerName.trim(),
      staffId = user.staffId.trim(),
      branchName = user.branchName.trim(),
      allowedSupervisorBranch = user.allowedSupervisorBranch.trim().ifBlank { user.branchName.trim() },
      role = user.role.trim().ifBlank { "Loan Officer" },
      updatedAt = System.currentTimeMillis()
    )
    // 1. Save to /config/global registry first for guaranteed instant sync across all devices
    upsertUserInGlobalRegistry(normalizedUser, clearDeletedKeys)
    // 2. Save to /users/{docId}
    try {
      db.collection("users").document(docId).set(userToMap(normalizedUser), SetOptions.merge()).await()
    } catch (e: Exception) {
      Log.w(TAG, "Error writing to /users/$docId, saved in global registry", e)
    }
  }

  suspend fun deleteUserRecord(
    uid: String,
    staffId: String = "",
    email: String = "",
    newDeletedKeys: Set<String> = emptySet()
  ) {
    // 1. Remove from /config/global registry using FieldValue.delete() and record deletedUserKeys
    try {
      val globalRef = db.collection("config").document("global")
      val deleteUsersDelta = HashMap<String, Any>()
      if (uid.isNotBlank()) {
        deleteUsersDelta[uid] = FieldValue.delete()
      }
      try {
        val doc = getDocumentFreshFirst(globalRef)
        val rawExisting = doc.get("registeredUsersMap") as? Map<*, *>
        rawExisting?.forEach { (k, v) ->
          val keyStr = k?.toString().orEmpty()
          val m = v as? Map<*, *>
          val parsed = if (m != null) parseUserMap(m, keyStr) else null
          val matchesUid = uid.isNotBlank() && (keyStr == uid || parsed?.uid == uid)
          val matchesStaff = staffId.isNotBlank() && parsed?.staffId?.equals(staffId.trim(), ignoreCase = true) == true
          val matchesEmail = email.isNotBlank() && parsed?.email?.equals(email.trim(), ignoreCase = true) == true
          if ((matchesUid || matchesEmail || matchesStaff) && keyStr.isNotBlank()) {
            deleteUsersDelta[keyStr] = FieldValue.delete()
          }
        }
      } catch (_: Exception) {}

      val payload = HashMap<String, Any>(
        mapOf(
          "registeredUsersMap" to deleteUsersDelta,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      if (newDeletedKeys.isNotEmpty()) {
        payload["deletedUserKeys"] = FieldValue.arrayUnion(*newDeletedKeys.toTypedArray())
      }

      withTimeout(10_000L) {
        globalRef.set(payload, SetOptions.merge()).await()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error deleting user from global registry", e)
    }

    // 2. Remove from /users collection
    try {
      if (uid.isNotBlank()) {
        withTimeout(6_000L) {
          db.collection("users").document(uid).delete().await()
        }
      }
      val snapshot = getCollectionFreshFirst(db.collection("users"))
      for (doc in snapshot.documents) {
        val docUid = doc.getString("uid").orEmpty()
        val docStaff = doc.getString("staffId").orEmpty()
        val docEmail = doc.getString("email").orEmpty()
        val matchesUid = uid.isNotBlank() && (doc.id == uid || docUid == uid)
        val matchesStaff = staffId.isNotBlank() && docStaff.equals(staffId.trim(), ignoreCase = true)
        val matchesEmail = email.isNotBlank() && docEmail.equals(email.trim(), ignoreCase = true)
        if (matchesUid || matchesStaff || matchesEmail) {
          withTimeout(6_000L) {
            db.collection("users").document(doc.id).delete().await()
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error cleaning up matching user documents", e)
    }
  }

  // 5. Global Configuration & Admin Security Password in Firestore
  suspend fun seedDefaultGlobalConfigIfEmpty() {
    try {
      val docRef = db.collection("config").document("global")
      val doc = getDocumentFreshFirst(docRef)
      if (!doc.exists()) {
        val data = hashMapOf<String, Any>(
          "fedRate" to 16.0,
          "filerTaxRate" to 20.0,
          "nonFilerTaxRate" to 40.0,
          "currency" to "PKR",
          "adminPin" to "Admin123",
          "updatedAt" to FieldValue.serverTimestamp()
        )
        withTimeout(8_000L) {
          docRef.set(data, SetOptions.merge()).await()
        }
      } else if (doc.getString("adminPin").isNullOrBlank()) {
        withTimeout(8_000L) {
          docRef.set(
            mapOf(
              "adminPin" to "Admin123",
              "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
          ).await()
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error seeding default global config", e)
    }
  }

  fun observeGlobalConfig(): Flow<GlobalConfig?> {
    return db.collection("config").document("global")
      .snapshots()
      .map { doc ->
        if (doc.exists()) {
          val fed = doc.getDouble("fedRate")
            ?: (doc.getLong("fedRate")?.toDouble() ?: 16.0)
          val filerTax = doc.getDouble("filerTaxRate")
            ?: (doc.getLong("filerTaxRate")?.toDouble() ?: 20.0)
          val nonFilerTax = doc.getDouble("nonFilerTaxRate")
            ?: (doc.getLong("nonFilerTaxRate")?.toDouble() ?: 40.0)
          val curr = doc.getString("currency") ?: "PKR"
          val adminPin = doc.getString("adminPin")?.takeIf { it.isNotBlank() } ?: "Admin123"
          val broadcastMsg = doc.getString("adminBroadcastMessage") ?: ""
          val broadcastId = doc.getString("adminBroadcastId") ?: ""
          val updateUrl = doc.getString("appUpdateUrl") ?: ""
          val updateId = doc.getString("appUpdateId") ?: ""
          val updateMsg = doc.getString("appUpdateMessage") ?: ""
          val sheetsUrl = doc.getString("googleSheetsWebhookUrl") ?: ""

          val deletedKeys = (doc.get("deletedUserKeys") as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?.toSet() ?: emptySet()

          val registryUsers = mutableListOf<RegisteredUser>()
          val rawRegistry = doc.get("registeredUsersMap") as? Map<*, *>
          rawRegistry?.forEach { (k, v) ->
            val m = v as? Map<*, *>
            if (m != null) {
              parseUserMap(m, k?.toString().orEmpty())?.let { registryUsers.add(it) }
            }
          }

          GlobalConfig(
            fedRate = fed,
            filerTaxRate = filerTax,
            nonFilerTaxRate = nonFilerTax,
            currency = curr,
            adminPin = adminPin,
            adminBroadcastMessage = broadcastMsg,
            adminBroadcastId = broadcastId,
            appUpdateUrl = updateUrl,
            appUpdateId = updateId,
            appUpdateMessage = updateMsg,
            googleSheetsWebhookUrl = sheetsUrl,
            deletedUserKeys = deletedKeys,
            registryUsers = registryUsers
          )
        } else {
          null
        }
      }
      .retryWhen { cause, _ ->
        Log.w(TAG, "observeGlobalConfig stream error, retrying...", cause)
        delay(2_500L)
        true
      }
      .catch { e ->
        Log.w(TAG, "observeGlobalConfig caught error", e)
      }
  }

  suspend fun publishAdminBroadcast(message: String, broadcastId: String) {
    val data = hashMapOf<String, Any>(
      "adminBroadcastMessage" to message,
      "adminBroadcastId" to broadcastId,
      "updatedAt" to FieldValue.serverTimestamp()
    )
    db.collection("config").document("global").set(data, SetOptions.merge()).await()
  }

  suspend fun publishAppUpdate(updateUrl: String, updateId: String, updateMessage: String) {
    val data = hashMapOf<String, Any>(
      "appUpdateUrl" to updateUrl,
      "appUpdateId" to updateId,
      "appUpdateMessage" to updateMessage,
      "updatedAt" to FieldValue.serverTimestamp()
    )
    db.collection("config").document("global").set(data, SetOptions.merge()).await()
  }

  suspend fun clearAppUpdate() {
    val data = hashMapOf<String, Any>(
      "appUpdateUrl" to "",
      "appUpdateId" to "",
      "appUpdateMessage" to "",
      "updatedAt" to FieldValue.serverTimestamp()
    )
    db.collection("config").document("global").set(data, SetOptions.merge()).await()
  }

  suspend fun publishGoogleSheetsWebhookUrl(url: String) {
    val data = hashMapOf<String, Any>(
      "googleSheetsWebhookUrl" to url.trim(),
      "updatedAt" to FieldValue.serverTimestamp()
    )
    db.collection("config").document("global").set(data, SetOptions.merge()).await()
  }

  suspend fun saveGlobalConfig(
    fedRate: Double,
    currency: String,
    filerTaxRate: Double = 20.0,
    nonFilerTaxRate: Double = 40.0
  ) {
    val data = hashMapOf<String, Any>(
      "fedRate" to fedRate,
      "currency" to currency,
      "filerTaxRate" to filerTaxRate,
      "nonFilerTaxRate" to nonFilerTaxRate,
      "updatedAt" to FieldValue.serverTimestamp()
    )
    db.collection("config").document("global").set(data, SetOptions.merge()).await()
  }

  suspend fun verifyAdminPinFromFirestore(enteredPin: String): Boolean {
    val doc = db.collection("config").document("global").get().await()
    val storedPin = if (doc.exists()) {
      doc.getString("adminPin")?.takeIf { it.isNotBlank() } ?: "Admin123"
    } else {
      "Admin123"
    }
    return enteredPin == storedPin
  }

  suspend fun updateAdminPinInFirestore(oldPin: String, newPin: String): Result<Unit> {
    return try {
      val docRef = db.collection("config").document("global")
      val doc = docRef.get().await()
      val storedPin = if (doc.exists()) {
        doc.getString("adminPin")?.takeIf { it.isNotBlank() } ?: "Admin123"
      } else {
        "Admin123"
      }
      if (oldPin != storedPin) {
        Result.failure(IllegalArgumentException("Current password does not match Firebase database record!"))
      } else {
        val data = hashMapOf<String, Any>(
          "adminPin" to newPin,
          "fedRate" to (doc.getDouble("fedRate") ?: 16.0),
          "currency" to (doc.getString("currency") ?: "PKR"),
          "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data, SetOptions.merge()).await()
        Result.success(Unit)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
