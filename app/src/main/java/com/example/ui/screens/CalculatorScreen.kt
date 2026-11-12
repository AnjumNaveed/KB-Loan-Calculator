package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.LoanUiState
import com.example.ui.LoanViewModel
import com.example.ui.theme.*
import com.example.util.LoanCalculators
import java.util.Calendar
import java.util.Locale

internal fun obtainCurrentLocation(
  context: android.content.Context,
  onObtained: (Double?, Double?, String) -> Unit
) {
  try {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
    val hasFine = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (hasFine || hasCoarse) {
      val gpsLoc = try { lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
      val netLoc = try { lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch (_: SecurityException) { null }
      val passLoc = try { lm?.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER) } catch (_: SecurityException) { null }
      val best = listOfNotNull(gpsLoc, netLoc, passLoc).maxByOrNull { it.time }
      if (best != null) {
        val lat = best.latitude
        val lng = best.longitude
        var addr = ""
        try {
          if (android.location.Geocoder.isPresent()) {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
              val a = list[0]
              val parts = listOfNotNull(a.subLocality ?: a.thoroughfare, a.locality ?: a.subAdminArea, a.adminArea)
              addr = parts.filter { it.isNotBlank() }.joinToString(", ")
            }
          }
        } catch (_: Exception) {}
        onObtained(lat, lng, addr)
        return
      } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        try {
          val provider = if (lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true) {
            android.location.LocationManager.GPS_PROVIDER
          } else {
            android.location.LocationManager.NETWORK_PROVIDER
          }
          lm?.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
            if (loc != null) {
              val lat = loc.latitude
              val lng = loc.longitude
              var addr = ""
              try {
                if (android.location.Geocoder.isPresent()) {
                  val geocoder = android.location.Geocoder(context, Locale.getDefault())
                  val list = geocoder.getFromLocation(lat, lng, 1)
                  if (!list.isNullOrEmpty()) {
                    val a = list[0]
                    val parts = listOfNotNull(a.subLocality ?: a.thoroughfare, a.locality ?: a.subAdminArea, a.adminArea)
                    addr = parts.filter { it.isNotBlank() }.joinToString(", ")
                  }
                }
              } catch (_: Exception) {}
              onObtained(lat, lng, addr)
            } else {
              onObtained(null, null, "")
            }
          }
          return
        } catch (_: Exception) {}
      }
    }
  } catch (_: Exception) {}
  onObtained(null, null, "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(
  viewModel: LoanViewModel,
  uiState: LoanUiState,
  onShareWhatsApp: (String, String) -> Unit,
  onShareSms: (String, String) -> Unit,
  onCopyProposal: (String) -> Unit,
  onExportPdf: (String) -> Unit = {}
) {
  val context = LocalContext.current
  val isDarkTheme = when (uiState.themeMode) {
    "dark" -> true
    "light" -> false
    else -> androidx.compose.foundation.isSystemInDarkTheme()
  }

  // Location permission launcher
  val locationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { perms ->
    val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
      perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (granted) {
      obtainCurrentLocation(context) { lat, lng, addr ->
        if (lat != null && lng != null) {
          viewModel.setCapturedLocation(lat, lng, addr)
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (hasFine || hasCoarse) {
      obtainCurrentLocation(context) { lat, lng, addr ->
        if (lat != null && lng != null) {
          viewModel.setCapturedLocation(lat, lng, addr)
        }
      }
    } else {
      locationPermissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION
        )
      )
    }
  }

  val executeSaveLead: (String) -> Unit = { source ->
    obtainCurrentLocation(context) { lat, lng, addr ->
      val effectiveLat = lat ?: uiState.lastCapturedLat
      val effectiveLng = lng ?: uiState.lastCapturedLng
      val effectiveAddr = if (addr.isNotBlank()) addr else uiState.lastCapturedAddress
      viewModel.autoSaveLead(source, effectiveLat, effectiveLng, effectiveAddr)
    }
  }

  val product = remember(uiState.products, uiState.selectedProductId) {
    uiState.products.find { it.id == uiState.selectedProductId } ?: uiState.products.firstOrNull() ?: viewModel.getSelectedProduct()
  }

  var productDropdownExpanded by remember { mutableStateOf(false) }
  var facilitySearchQuery by remember { mutableStateOf("") }
  var showCollateralInfoDialog by remember { mutableStateOf(false) }
  var calcCategoryFilter by remember { mutableStateOf("all") } // "all", "asset", "liability"

  val filteredProductsForCalc = remember(uiState.products, facilitySearchQuery, calcCategoryFilter) {
    val q = facilitySearchQuery.trim()
    uiState.products.filter { p ->
      val matchesCat = when (calcCategoryFilter) {
        "asset" -> p.category != "liability"
        "liability" -> p.category == "liability"
        else -> true
      }
      val minR = if (p.rateSlabs.isNotEmpty()) p.rateSlabs.minOf { it.annualRate }.toString() else ""
      val maxR = if (p.rateSlabs.isNotEmpty()) p.rateSlabs.maxOf { it.annualRate }.toString() else ""
      val matchesSearch = q.isEmpty() ||
        p.name.contains(q, ignoreCase = true) ||
        p.id.contains(q, ignoreCase = true) ||
        p.rateType.contains(q, ignoreCase = true) ||
        p.defaultAnnualRate.toString().contains(q) ||
        minR.contains(q) ||
        maxR.contains(q)
      matchesCat && matchesSearch
    }
  }

  val ageInfo = remember(uiState.dob, product) {
    LoanCalculators.evaluateAgeEligibility(uiState.dob, product)
  }
  val detailedAge = ageInfo.detailedAge
  val isAgeEligible = ageInfo.isEligible

  val maxTotalAllowedEmi = if (uiState.monthlyIncome > 0L && uiState.allowedDbr > 0) {
    (uiState.monthlyIncome * uiState.allowedDbr) / 100
  } else 0L

  val isAutoMaxMode = uiState.loanAmountInput.isBlank() ||
    (uiState.loanAmountInput.filter { it.isDigit() }.toLongOrNull() ?: 0L) <= 0L

  val currentPrincipal = viewModel.getPrincipalForTenure(uiState.selectedTenure)
  val currentRate = LoanCalculators.getApplicableRate(product, currentPrincipal, uiState.selectedTenure)
  val isLiabProduct = product.category == "liability"
  val isCurrentProduct = isLiabProduct && (currentRate <= 0.0 || product.defaultAnnualRate <= 0.0 || product.name.contains("Current", ignoreCase = true))
  val feeInfo = LoanCalculators.calculateFee(product, currentPrincipal, uiState.profile.fedRate)

  // Contact Picker launcher for Customer Mobile Number
  val contactPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val contactUri = result.data?.data ?: return@rememberLauncherForActivityResult
      try {
        context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
              .takeIf { it >= 0 } ?: cursor.getColumnIndex("data1")
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
              .takeIf { it >= 0 } ?: cursor.getColumnIndex("display_name")
            val rawNumber = if (numberIdx >= 0) cursor.getString(numberIdx).orEmpty() else ""
            val contactName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""

            val digitsOnly = rawNumber.filter { it.isDigit() }
            val cleanedNumber = when {
              digitsOnly.startsWith("0092") && digitsOnly.length >= 14 -> "0" + digitsOnly.substring(4)
              digitsOnly.startsWith("92") && digitsOnly.length == 12 -> "0" + digitsOnly.substring(2)
              digitsOnly.startsWith("3") && digitsOnly.length == 10 -> "0$digitsOnly"
              digitsOnly.isNotEmpty() -> digitsOnly
              else -> rawNumber.trim()
            }

            if (cleanedNumber.isNotBlank()) {
              viewModel.updateMobile(cleanedNumber)
              if (contactName.isNotBlank()) {
                viewModel.updateCustomerName(contactName)
              }
              viewModel.showToast("Contact selected: ${contactName.ifBlank { cleanedNumber }}")
            }
          }
        }
      } catch (e: Exception) {
        viewModel.showToast("Unable to read selected contact number")
      }
    }
  }

  val contactsPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { _ ->
    try {
      val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
      contactPickerLauncher.launch(intent)
    } catch (e: Exception) {
      viewModel.showToast("No Contacts app found on device")
    }
  }

  val launchContactPicker = {
    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    if (hasPerm) {
      try {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        contactPickerLauncher.launch(intent)
      } catch (e: Exception) {
        viewModel.showToast("No Contacts app found on device")
      }
    } else {
      contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }
  }

  // Function to open DatePickerDialog for Date of Birth
  val openDobDatePicker = {
    val cal = Calendar.getInstance()
    if (uiState.dob.isNotBlank()) {
      try {
        val parts = uiState.dob.trim().split("-", "/")
        if (parts.size == 3) {
          if (parts[0].length == 4) {
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
          } else if (parts[2].length == 4) {
            cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
          }
        }
      } catch (_: Exception) {}
    } else {
      cal.add(Calendar.YEAR, -30)
      cal.set(Calendar.MONTH, Calendar.MAY)
      cal.set(Calendar.DAY_OF_MONTH, 15)
    }
    DatePickerDialog(
      context,
      { _, year, month, dayOfMonth ->
        val formatted = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
        viewModel.updateDob(formatted)
      },
      cal.get(Calendar.YEAR),
      cal.get(Calendar.MONTH),
      cal.get(Calendar.DAY_OF_MONTH)
    ).show()
  }

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter
  ) {
    val isCompactScreen = maxWidth < 360.dp || maxHeight < 660.dp
    val horizontalPad = if (isCompactScreen) 10.dp else 14.dp
    val cardSpacing = if (isCompactScreen) 10.dp else 14.dp

    LazyColumn(
      modifier = Modifier
        .fillMaxHeight()
        .widthIn(max = 760.dp)
        .fillMaxWidth()
        .imePadding()
        .padding(horizontal = horizontalPad),
      verticalArrangement = Arrangement.spacedBy(cardSpacing),
      contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp)
    ) {

    // 1. Customer Profile Card: Vertically arranged inputs & Prominent RESET Button
    item {
      GlassCard(
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Top Header
          Text(
            text = "👤 CUSTOMER PROFILE",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
          )

          // Full-Width Reset Button (Easy to tap, vertically arranged with 3D press)
          OutlinedButton(
            onClick = { viewModel.resetAllInputs() },
            modifier = Modifier
              .fillMaxWidth()
              .press3d(0.96f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
            shape = RoundedCornerShape(10.dp)
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("↺ Reset / Clear All Inputs", fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }

          // Customer Full Name (Compact & smart sizing)
          OutlinedTextField(
            value = uiState.customerName,
            onValueChange = { viewModel.updateCustomerName(it) },
            label = { Text("Customer Full Name", fontSize = 11.sp, maxLines = 1) },
            placeholder = { Text("Enter customer name...", fontSize = 11.5.sp, color = Color(0xFF94A3B8), maxLines = 1) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            colors = kbInputFieldColors(isDarkTheme),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(17.dp)) },
            trailingIcon = {
              if (uiState.customerName.isNotBlank()) {
                IconButton(onClick = { viewModel.updateCustomerName("") }, modifier = Modifier.size(28.dp)) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear Name", tint = Color(0xFF64748B), modifier = Modifier.size(15.dp))
                }
              }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp),
            singleLine = true
          )

          // Customer Mobile Number with Contact Picker button on the right
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
              value = uiState.mobile,
              onValueChange = { viewModel.updateMobile(it) },
              label = { Text("Mobile Number", fontSize = 11.sp, maxLines = 1) },
              placeholder = { Text("03425944951", fontSize = 11.5.sp, color = Color(0xFF94A3B8), maxLines = 1) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
              colors = kbInputFieldColors(isDarkTheme),
              leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(17.dp)) },
              trailingIcon = {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(2.dp),
                  modifier = Modifier.padding(end = 4.dp)
                ) {
                  if (uiState.mobile.isNotBlank()) {
                    IconButton(
                      onClick = { viewModel.updateMobile("") },
                      modifier = Modifier.size(28.dp)
                    ) {
                      Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear Number",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(15.dp)
                      )
                    }
                  }
                  IconButton(
                    onClick = launchContactPicker,
                    modifier = Modifier
                      .size(32.dp)
                      .press3d(0.88f)
                  ) {
                    Icon(
                      Icons.Default.ContactPhone,
                      contentDescription = "Pick from Saved Contacts",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(18.dp)
                    )
                  }
                }
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
              singleLine = true
            )
            val digits = uiState.mobile.filter { it.isDigit() }
            if (digits.isNotBlank()) {
              Text(
                text = "Direct Ready: ${uiState.mobile} (wa.me/${LoanCalculators.formatForWhatsApp(uiState.mobile)})",
                fontSize = 10.sp,
                color = Color(0xFF059669),
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          // Date of Birth & Age Policy (Only required for Asset / Loan facilities, hidden for Liability Deposits)
          if (product.category != "liability") {
            OutlinedTextField(
              value = uiState.dob,
              onValueChange = { viewModel.updateDob(it) },
              label = { Text("Date of Birth", fontSize = 11.sp, maxLines = 1) },
              placeholder = { Text("YYYY-MM-DD", fontSize = 11.5.sp, color = Color(0xFF94A3B8), maxLines = 1) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
              colors = kbInputFieldColors(isDarkTheme),
              leadingIcon = {
                IconButton(
                  onClick = openDobDatePicker,
                  modifier = Modifier
                    .size(30.dp)
                    .press3d(0.88f)
                ) {
                  Icon(Icons.Default.CalendarToday, contentDescription = "Select DOB Calendar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                }
              },
              trailingIcon = {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(2.dp),
                  modifier = Modifier.padding(end = 4.dp)
                ) {
                  if (uiState.dob.isNotBlank()) {
                    IconButton(
                      onClick = { viewModel.updateDob("") },
                      modifier = Modifier.size(28.dp)
                    ) {
                      Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear DOB",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(15.dp)
                      )
                    }
                  }
                  IconButton(
                    onClick = openDobDatePicker,
                    modifier = Modifier
                      .size(32.dp)
                      .press3d(0.88f)
                  ) {
                    Icon(Icons.Default.EditCalendar, contentDescription = "Open Calendar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                  }
                }
              },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
              singleLine = true
            )

            // Customer Age & Eligibility Banner positioned directly below Date of Birth
            if (uiState.dob.isNotBlank() && (detailedAge.years > 0 || detailedAge.months > 0 || detailedAge.days > 0)) {
              Surface(
                color = if (isAgeEligible) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isAgeEligible) Color(0xFFA5D6A7) else Color(0xFFEF9A9A)),
                modifier = Modifier
                  .fillMaxWidth()
                  .press3d(0.98f)
                  .clickable { openDobDatePicker() }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                  ) {
                    Text(
                      text = "🎂 Customer Age: ${detailedAge.years} Years, ${detailedAge.months} Months, ${detailedAge.days} Days",
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                      color = if (isAgeEligible) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                    )
                    val statusText = when {
                      ageInfo.isUnderMinAge -> "❌ Not Eligible (Under Min Age ${product.minAge} Yrs for ${product.name})"
                      ageInfo.isOverMaturityAge -> "❌ Not Eligible (Exceeds Max Maturity Age ${product.maxAgeAtMaturity} Yrs for ${product.name})"
                      else -> "✅ Eligible for ${product.name} (Age Policy: ${product.minAge}–${product.maxAgeAtMaturity} Yrs)"
                    }
                    Text(
                      text = statusText,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.ExtraBold,
                      color = if (isAgeEligible) Color(0xFF15803D) else Color(0xFFB91C1C)
                    )
                    Text(
                      text = if (isAgeEligible) {
                        "⏳ Remaining to Maturity (${product.maxAgeAtMaturity} Yrs): ${ageInfo.remainingMonthsToMaturity} Months • Max Loan Offer: Up to ${ageInfo.maxOfferableTenureMonths} Months (Product Range: ${product.minTenure}–${product.maxTenure}M)"
                      } else if (ageInfo.isUnderMinAge) {
                        "⏳ Required Minimum Entry Age: ${product.minAge} Years (مطلوبہ کم از کم عمر ${product.minAge} سال)"
                      } else {
                        "⏳ Remaining to Maturity (${product.maxAgeAtMaturity} Yrs): ${ageInfo.remainingMonthsToMaturity} Months (Min Facility Tenure: ${product.minTenure} Months)"
                      },
                      fontSize = 10.5.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = if (isAgeEligible) Color(0xFF1E3A8A) else Color(0xFF991B1B)
                    )
                    Text(
                      text = "مکمل عمر: ${detailedAge.years} سال، ${detailedAge.months} ماہ، ${detailedAge.days} دن • ${
                        if (isAgeEligible) "اہل ہے (میچورٹی سے پہلے زیادہ سے زیادہ ${ageInfo.maxOfferableTenureMonths} ماہ تک قرض آفر کیا جا سکتا ہے)"
                        else "نااہل (پروڈکٹ کی عمر کی حد ${product.minAge} تا ${product.maxAgeAtMaturity} سال)"
                      }",
                      fontSize = 10.5.sp,
                      fontWeight = FontWeight.Medium,
                      color = if (isAgeEligible) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                  }
                  Icon(
                    Icons.Default.EditCalendar,
                    contentDescription = "Edit DOB",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }
          }

          // Specific Lead Notes Textarea (Dynamically expandable downwards as more data is entered)
          OutlinedTextField(
            value = uiState.specificLeadNotes,
            onValueChange = { viewModel.updateSpecificNotes(it) },
            label = { Text("Specific Lead Notes", fontSize = 11.sp, maxLines = 1) },
            placeholder = { Text("Enter customer details...", fontSize = 11.5.sp, color = Color(0xFF94A3B8), maxLines = 1) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            colors = kbInputFieldColors(isDarkTheme),
            leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null, modifier = Modifier.size(17.dp)) },
            trailingIcon = {
              if (uiState.specificLeadNotes.isNotBlank()) {
                IconButton(onClick = { viewModel.updateSpecificNotes("") }, modifier = Modifier.size(28.dp)) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear Notes", tint = Color(0xFF64748B), modifier = Modifier.size(15.dp))
                }
              }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            maxLines = Int.MAX_VALUE
          )
        }
      }
    }

    // 2. Loan Facility & Dynamic Pricing Card: Vertically arranged elements
    item {
      GlassCard(
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val isCurrentProdLiability = product.category == "liability"
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = if (isCurrentProdLiability) "💰 DEPOSIT PRODUCT & PROFIT RATES" else "💼 LOAN FACILITY & TIERED PRICING",
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              color = if (isCurrentProdLiability) Color(0xFF0F766E) else MaterialTheme.colorScheme.primary
            )
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = if (isCurrentProdLiability) Color(0xFFCCFBF1) else Color(0xFFDBEAFE)
            ) {
              Text(
                text = if (isCurrentProdLiability) "LIABILITY" else "ASSET",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCurrentProdLiability) Color(0xFF0F766E) else Color(0xFF1E40AF),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          // Category Toggle Chips for Product Selection
          val calcAssetCount = remember(uiState.products) { uiState.products.count { it.category != "liability" } }
          val calcLiabilityCount = remember(uiState.products) { uiState.products.count { it.category == "liability" } }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            FilterChip(
              selected = calcCategoryFilter == "all",
              onClick = {
                calcCategoryFilter = "all"
                if (product.category == "liability") {
                  val firstAsset = uiState.products.firstOrNull { it.category != "liability" }
                  if (firstAsset != null) viewModel.updateProduct(firstAsset.id)
                }
              },
              label = { Text("All (${uiState.products.size})", fontSize = 10.5.sp) },
              modifier = Modifier.weight(1f)
            )
            FilterChip(
              selected = calcCategoryFilter == "asset",
              onClick = {
                calcCategoryFilter = "asset"
                if (product.category == "liability") {
                  val firstAsset = uiState.products.firstOrNull { it.category != "liability" }
                  if (firstAsset != null) viewModel.updateProduct(firstAsset.id)
                }
              },
              label = { Text("Asset ($calcAssetCount)", fontSize = 10.5.sp) },
              modifier = Modifier.weight(1.1f)
            )
            FilterChip(
              selected = calcCategoryFilter == "liability",
              onClick = {
                calcCategoryFilter = "liability"
                if (product.category != "liability") {
                  val firstLiab = uiState.products.firstOrNull { it.category == "liability" }
                  if (firstLiab != null) viewModel.updateProduct(firstLiab.id)
                }
              },
              label = { Text("Liability ($calcLiabilityCount)", fontSize = 10.5.sp) },
              modifier = Modifier.weight(1.1f)
            )
          }

          // Search Box to quickly find & select a Facility/Product
          OutlinedTextField(
            value = facilitySearchQuery,
            onValueChange = { facilitySearchQuery = it },
            label = { Text(if (isCurrentProdLiability) "Search Deposit / Liability Product" else "Search Bank Loan Facility", fontSize = 11.sp) },
            placeholder = { Text(if (isCurrentProdLiability) "Type deposit name or rate (e.g. Mahana, PLS, TDR)..." else "Type product name or rate (e.g. Munafa, Gold, Salary)...", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            colors = kbInputFieldColors(isDarkTheme),
            leadingIcon = {
              Icon(
                Icons.Default.Search,
                contentDescription = "Search Product",
                tint = if (isCurrentProdLiability) Color(0xFF0F766E) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
              )
            },
            trailingIcon = {
              if (facilitySearchQuery.isNotBlank()) {
                IconButton(
                  onClick = { facilitySearchQuery = "" },
                  modifier = Modifier.size(28.dp)
                ) {
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

          // Instant matching product results when typing in Search Box
          if (facilitySearchQuery.isNotBlank()) {
            Card(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp),
              colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
              border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFBFDBFE))
              )
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = "Matching Products (${filteredProductsForCalc.size}) - Tap to Select:",
                  fontSize = 10.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF1E3A8A)
                )
                if (filteredProductsForCalc.isEmpty()) {
                  Text(
                    text = "No product matches \"$facilitySearchQuery\".",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(vertical = 4.dp)
                  )
                } else {
                  filteredProductsForCalc.forEach { p ->
                    val pIsLiab = p.category == "liability"
                    val pSlabRateText = if (p.rateSlabs.isNotEmpty()) {
                      val minR = p.rateSlabs.minOf { it.annualRate }
                      val maxR = p.rateSlabs.maxOf { it.annualRate }
                      if (minR == maxR) "$minR%" else "$minR% - $maxR%"
                    } else "${p.defaultAnnualRate}%"
                    val isSelected = p.id == product.id
                    Surface(
                      color = if (isSelected) (if (pIsLiab) Color(0xFFCCFBF1) else Color(0xFFDBEAFE)) else Color.White,
                      shape = RoundedCornerShape(8.dp),
                      modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                          viewModel.updateProduct(p.id)
                          if (p.category != "liability" && calcCategoryFilter == "liability") {
                            calcCategoryFilter = "asset"
                          } else if (p.category == "liability" && calcCategoryFilter == "asset") {
                            calcCategoryFilter = "liability"
                          }
                          facilitySearchQuery = ""
                          productDropdownExpanded = false
                        }
                    ) {
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                          ) {
                            Text(
                              text = p.name,
                              fontSize = 12.sp,
                              fontWeight = FontWeight.Bold,
                              color = if (isSelected) (if (pIsLiab) Color(0xFF0F766E) else Color(0xFF1E3A8A)) else Color(0xFF0F172A)
                            )
                            Surface(
                              shape = RoundedCornerShape(4.dp),
                              color = if (pIsLiab) Color(0xFF99F6E4) else Color(0xFFBFDBFE)
                            ) {
                              Text(
                                text = if (pIsLiab) "LIABILITY" else "ASSET",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pIsLiab) Color(0xFF0F766E) else Color(0xFF1E3A8A),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                              )
                            }
                          }
                          val rateLabel = if (pIsLiab) "Profit" else "Rate"
                          Text(
                            text = "$rateLabel: $pSlabRateText (${p.rateType.uppercase()}) • Tenure: ${p.minTenure}-${p.maxTenure}M • Age: ${p.minAge}-${p.maxAgeAtMaturity}Y",
                            fontSize = 10.sp,
                            color = Color(0xFF475569)
                          )
                        }
                        if (isSelected) {
                          Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = if (pIsLiab) Color(0xFF0D9488) else Color(0xFF1D4ED8),
                            modifier = Modifier.size(16.dp)
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          // Product Selector Dropdown (Full Width)
          val selectedSlabRateText = if (isCurrentProdLiability && product.tenureRates.isNotEmpty()) {
            val minR = product.tenureRates.values.minOrNull() ?: product.defaultAnnualRate
            val maxR = product.tenureRates.values.maxOrNull() ?: product.defaultAnnualRate
            if (minR == maxR) "$minR%" else "$minR%-$maxR%"
          } else if (product.rateSlabs.isNotEmpty()) {
            val minR = product.rateSlabs.minOf { it.annualRate }
            val maxR = product.rateSlabs.maxOf { it.annualRate }
            if (minR == maxR) "$minR%" else "$minR%-$maxR%"
          } else "${product.defaultAnnualRate}%"

          ExposedDropdownMenuBox(
            expanded = productDropdownExpanded,
            onExpandedChange = { productDropdownExpanded = it }
          ) {
            OutlinedTextField(
              value = "${product.name} ($selectedSlabRateText ${product.rateType.uppercase()})",
              onValueChange = {},
              readOnly = true,
              label = { Text(if (isCurrentProdLiability) "Selected Deposit Product (or Tap Dropdown)" else "Selected Bank Loan Facility (or Tap Dropdown)", fontSize = 11.sp) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
              colors = kbInputFieldColors(isDarkTheme),
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = productDropdownExpanded) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .heightIn(min = 48.dp),
              singleLine = true
            )
            ExposedDropdownMenu(
              expanded = productDropdownExpanded,
              onDismissRequest = { productDropdownExpanded = false }
            ) {
              val dropdownList = if (filteredProductsForCalc.isNotEmpty()) filteredProductsForCalc else uiState.products
              dropdownList.forEach { p ->
                val pIsLiab = p.category == "liability"
                val pSlabRateText = if (pIsLiab && p.tenureRates.isNotEmpty()) {
                  val minR = p.tenureRates.values.minOrNull() ?: p.defaultAnnualRate
                  val maxR = p.tenureRates.values.maxOrNull() ?: p.defaultAnnualRate
                  if (minR == maxR) "$minR%" else "$minR% - $maxR%"
                } else if (p.rateSlabs.isNotEmpty()) {
                  val minR = p.rateSlabs.minOf { it.annualRate }
                  val maxR = p.rateSlabs.maxOf { it.annualRate }
                  if (minR == maxR) "$minR%" else "$minR% - $maxR%"
                } else "${p.defaultAnnualRate}%"
                DropdownMenuItem(
                  text = {
                    Column {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                      ) {
                        Text(
                          text = p.name,
                          fontSize = 12.5.sp,
                          fontWeight = if (p.id == product.id) FontWeight.Bold else FontWeight.Normal
                        )
                        Surface(
                          shape = RoundedCornerShape(4.dp),
                          color = if (pIsLiab) Color(0xFFCCFBF1) else Color(0xFFDBEAFE)
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
                      val ratePrefix = if (pIsLiab) "Profit Rate" else "Slab Rate"
                      Text(
                        text = "$ratePrefix: $pSlabRateText • ${p.rateType.uppercase()} • Age: ${p.minAge}-${p.maxAgeAtMaturity} Yrs (${p.minTenure}-${p.maxTenure}M)",
                        fontSize = 10.5.sp,
                        color = Color.Gray
                      )
                    }
                  },
                  onClick = {
                    viewModel.updateProduct(p.id)
                    if (p.category != "liability" && calcCategoryFilter == "liability") {
                      calcCategoryFilter = "asset"
                    } else if (p.category == "liability" && calcCategoryFilter == "asset") {
                      calcCategoryFilter = "liability"
                    }
                    facilitySearchQuery = ""
                    productDropdownExpanded = false
                  }
                )
              }
            }
          }

          // Security / Collateral / Rules Info Button
          FilledTonalButton(
            onClick = { showCollateralInfoDialog = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = if (isCurrentProdLiability) Color(0xFFCCFBF1) else Color(0xFFDBEAFE),
              contentColor = if (isCurrentProdLiability) Color(0xFF0F766E) else Color(0xFF1E3A8A)
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(40.dp)
              .press3d(0.96f)
          ) {
            Icon(Icons.Default.Security, contentDescription = "Security Info", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (isCurrentProdLiability) "View Deposit Rules & Terms (${product.name})" else "View Security & Collateral Requirements (${product.name})",
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1
            )
          }

          // Desired Amount Input (Loan Amount vs Deposit Amount)
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
              value = uiState.loanAmountInput,
              onValueChange = { viewModel.updateLoanAmountInput(it) },
              label = { Text(if (isCurrentProdLiability) "Desired Deposit Amount / جمع کروانے کی رقم (PKR)" else "Desired Loan Amount (PKR)", fontSize = 11.sp) },
              placeholder = { Text(if (isCurrentProdLiability) "Enter initial deposit amount (e.g. 500,000)..." else "Leave BLANK to auto-calculate maximum possible loan", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
              colors = kbInputFieldColors(isDarkTheme),
              leadingIcon = { Icon(Icons.Default.MonetizationOn, contentDescription = null, modifier = Modifier.size(17.dp)) },
              trailingIcon = {
                if (uiState.loanAmountInput.isNotBlank()) {
                  IconButton(onClick = { viewModel.updateLoanAmountInput("") }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                  }
                }
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
              singleLine = true
            )

            if (isCurrentProdLiability) {
              val appliedDep = if (currentPrincipal > 0L) currentPrincipal else product.minAmount
              Text(
                text = if (isCurrentProduct) "Target Deposit: ${LoanCalculators.formatCurrency(appliedDep, uiState.profile.currency)} • Current Account (0% Profit)"
                else "Target Deposit: ${LoanCalculators.formatCurrency(appliedDep, uiState.profile.currency)} • Profit Rate: $currentRate% p.a. (${product.rateType.uppercase()})",
                fontSize = 10.5.sp,
                color = Color(0xFF0F766E),
                fontWeight = FontWeight.Bold
              )

              // Customer Filer Status Selector (Filer vs Non-Filer WHT)
              if (currentRate <= 0.0) {
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = Color(0xFFF0FDFA),
                  border = BorderStroke(1.dp, Color(0xFF99F6E4)),
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text("🛡️", fontSize = 16.sp)
                    Column {
                      Text(
                        text = "Current Account (0% Profit / لاگت و منافع سے پاک)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F766E)
                      )
                      Text(
                        text = "کرنٹ اکاؤنٹ پر کوئی منافع نہیں ہوتا اور کوئی ودہولڈنگ ٹیکس لاگو نہیں ہوتا۔",
                        fontSize = 10.sp,
                        color = Color(0xFF0D9488)
                      )
                    }
                  }
                }
              } else {
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = Color(0xFFF0FDFA),
                  border = BorderStroke(1.dp, Color(0xFF99F6E4)),
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                ) {
                  Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Text(
                      text = "👤 CUSTOMER TAX STATUS (منافع پر ودہولڈنگ ٹیکس)",
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF0F766E)
                    )

                    // Vertical Arrangement of Filer & Non-Filer Buttons
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                      FilterChip(
                        selected = uiState.isFiler,
                        onClick = { viewModel.updateIsFiler(true) },
                        leadingIcon = { Text("✅", fontSize = 12.sp) },
                        label = {
                          Text(
                            text = "Filer (فائلر کسٹمر) • ${uiState.profile.filerTaxRate}% Withholding Tax",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                          )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                          selectedContainerColor = Color(0xFF0D9488),
                          selectedLabelColor = Color.White
                        ),
                        modifier = Modifier
                          .fillMaxWidth()
                          .press3d(0.96f)
                      )
                      FilterChip(
                        selected = !uiState.isFiler,
                        onClick = { viewModel.updateIsFiler(false) },
                        leadingIcon = { Text("⚠️", fontSize = 12.sp) },
                        label = {
                          Text(
                            text = "Non-Filer (نان فائلر) • ${uiState.profile.nonFilerTaxRate}% Withholding Tax",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                          )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                          selectedContainerColor = Color(0xFFE11D48),
                          selectedLabelColor = Color.White
                        ),
                        modifier = Modifier
                          .fillMaxWidth()
                          .press3d(0.96f)
                      )
                    }

                    Text(
                      text = if (uiState.isFiler)
                        "✓ Filer: منافع سے ${uiState.profile.filerTaxRate}% ودہولڈنگ ٹیکس منہا ہوگا اور باقی خالص منافع کسٹمر کو ملے گا۔"
                      else
                        "⚠️ Non-Filer: منافع سے ${uiState.profile.nonFilerTaxRate}% ودہولڈنگ ٹیکس کٹے گا اور باقی خالص منافع کسٹمر کو ملے گا۔",
                      fontSize = 10.sp,
                      color = if (uiState.isFiler) Color(0xFF0F766E) else Color(0xFFB91C1C)
                    )
                  }
                }
              }
            } else if (isAutoMaxMode) {
              Text(
                text = "⚡ DYNAMIC MODE: Loan amount is blank. Matrix will compute MAXIMUM possible loan for each tenure based on DBR.",
                fontSize = 10.5.sp,
                color = Color(0xFFD97706),
                fontWeight = FontWeight.Bold
              )
            } else {
              Text(
                text = "Fixed Principal: ${LoanCalculators.formatCurrency(currentPrincipal, uiState.profile.currency)} • Rate Applied: $currentRate%",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }
    }

    // 3. Financial & DBR Capacity Card (For Asset) OR Deposit Return Summary (For Saving/Term Liability)
    if (!isCurrentProduct) {
      item {
        val isLiab = product.category == "liability"
        GlassCard(
          modifier = Modifier.fillMaxWidth()
        ) {
          if (isLiab) {
            val appliedDep = if (currentPrincipal > 0L) currentPrincipal else product.minAmount
            val depositRes = remember(appliedDep, currentRate, uiState.selectedTenure, uiState.isFiler, uiState.profile.filerTaxRate, uiState.profile.nonFilerTaxRate) {
              LoanCalculators.calculateDepositProfit(
                depositAmount = appliedDep,
                annualRate = currentRate,
                tenureMonths = uiState.selectedTenure,
                isFiler = uiState.isFiler,
                filerTaxRate = uiState.profile.filerTaxRate,
                nonFilerTaxRate = uiState.profile.nonFilerTaxRate
              )
            }
            DarkGlassCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 6.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth(),
                  verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Column {
                      Text("Monthly Expected Payout:", fontSize = 10.5.sp, color = Color(0xFF94A3B8))
                      Text(
                        text = "${LoanCalculators.formatCurrency(depositRes.monthlyProfit, uiState.profile.currency)} / mo",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                      )
                      Text(
                        text = "Tax (${depositRes.taxRate}%): -${LoanCalculators.formatCurrency(depositRes.monthlyTax, uiState.profile.currency)}",
                        fontSize = 10.sp,
                        color = Color(0xFFF87171)
                      )
                      Text(
                        text = "Net: ${LoanCalculators.formatCurrency(depositRes.netMonthlyProfit, uiState.profile.currency)} / mo",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF34D399)
                      )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                      Text("Total Profit (${uiState.selectedTenure}M Term):", fontSize = 10.5.sp, color = Color(0xFF94A3B8))
                      Text(
                        text = LoanCalculators.formatCurrency(depositRes.totalProfit, uiState.profile.currency),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                      )
                      Text(
                        text = "Tax: -${LoanCalculators.formatCurrency(depositRes.totalTax, uiState.profile.currency)}",
                        fontSize = 10.sp,
                        color = Color(0xFFF87171)
                      )
                      Text(
                        text = "Net: ${LoanCalculators.formatCurrency(depositRes.netTotalProfit, uiState.profile.currency)}",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFBBF24)
                      )
                    }
                  }

                  HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column {
                      Text("Net Maturity Payout (اصل زر + خالص منافع):", fontSize = 11.sp, color = Color(0xFFCBD5E1), fontWeight = FontWeight.Medium)
                      Text("Principal + Net Profit after ${depositRes.taxRate}% WHT", fontSize = 9.sp, color = Color(0xFF94A3B8))
                    }
                    Text(
                      text = LoanCalculators.formatCurrency(depositRes.netMaturityAmount, uiState.profile.currency),
                      fontSize = 17.sp,
                      fontWeight = FontWeight.Black,
                      color = Color.White
                    )
                  }
                }
              }
        } else {
          // Debt Burden Ratio (DBR) for Asset / Loan facilities
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "📊 DEBT BURDEN RATIO (DBR)",
              fontWeight = FontWeight.Bold,
              fontSize = 12.5.sp,
              color = MaterialTheme.colorScheme.primary
            )

            // Net Monthly Income and Allowed DBR placed horizontally with identical width & height and no * in label
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedTextField(
                value = uiState.monthlyIncomeInput,
                onValueChange = { viewModel.updateMonthlyIncomeInput(it) },
                label = { Text("Net Monthly Income (PKR)", fontSize = 10.sp, maxLines = 1) },
                placeholder = { Text("e.g. 150000", fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                colors = kbInputFieldColors(isDarkTheme),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp),
                singleLine = true
              )

              OutlinedTextField(
                value = uiState.allowedDbrInput,
                onValueChange = { viewModel.updateAllowedDbrInput(it) },
                label = { Text("Allowed DBR %", fontSize = 10.sp, maxLines = 1) },
                placeholder = { Text("e.g. 50", fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                colors = kbInputFieldColors(isDarkTheme),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp),
                singleLine = true
              )
            }

            // Capacity Result Banner in Dark Glass with Specular Glow
            DarkGlassCard(
              modifier = Modifier.fillMaxWidth(),
              elevation = 6.dp,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
              ) {
                Text("Max Allowable Monthly Installment (DBR Cap):", fontSize = 10.5.sp, color = Color(0xFFCBD5E1))
                Text(
                  text = if (maxTotalAllowedEmi > 0L) "${LoanCalculators.formatCurrency(maxTotalAllowedEmi, uiState.profile.currency)} / mo" else "Enter Income & DBR",
                  fontSize = 18.sp,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFFFBBF24)
                )
              }
            }
          }
        }
      }
    }
    }

    // 4. RELOCATED SECTION: Dispatch / Communication & Save Lead Toolbar (Positioned between DBR and Eligibility Matrix)
    item {
      GlassCard(
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "DISPATCH & COMMUNICATION (AUTO-SAVES LEAD)",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
          )

          val intervalTenures = remember(product.minTenure, product.maxTenure) {
            val pMin = product.minTenure.coerceAtLeast(1)
            val pMax = product.maxTenure.coerceAtLeast(pMin)
            val tenures = mutableSetOf<Int>()
            tenures.add(pMin)
            val step = if (pMax - pMin > 36) 6 else 3
            var curr = (pMin / step) * step
            while (curr <= pMax) {
              if (curr >= pMin) tenures.add(curr)
              curr += step
            }
            tenures.add(pMax)
            tenures.sorted()
          }

          val effMaxEmiForShare = (uiState.monthlyIncome * uiState.allowedDbr) / 100
          val hasIncomeAndDbrForShare = uiState.monthlyIncome > 0L && uiState.allowedDbr > 0

          val proposalText = remember(
            uiState.customerName,
            uiState.mobile,
            uiState.dob,
            uiState.monthlyIncome,
            uiState.allowedDbr,
            uiState.loanAmountInput,
            uiState.selectedTenure,
            uiState.profile,
            product,
            isAutoMaxMode,
            ageInfo
          ) {
            val sb = StringBuilder()
            val pMin = product.minTenure.coerceAtLeast(1)
            val pMax = product.maxTenure.coerceAtLeast(pMin)

            if (product.category == "liability") {
              val depAmt = if (currentPrincipal > 0L) currentPrincipal else product.minAmount
              if (isCurrentProduct) {
                sb.appendLine("🏛️ *KB CURRENT ACCOUNT PROPOSAL*")
                sb.appendLine("👤 Customer: ${uiState.customerName.ifBlank { "Valued Client" }}")
                if (uiState.mobile.isNotBlank()) {
                  sb.appendLine("📱 Mobile: ${uiState.mobile}")
                }
                sb.appendLine("💼 Product: ${product.name}")
                sb.appendLine("────────────────────")
                sb.appendLine("💵 Initial Deposit: *${LoanCalculators.formatCurrency(depAmt, uiState.profile.currency)}*")
                sb.appendLine("🛡️ Profit Rate: *0.0% p.a.* (Non-Remunerative / لاگت و منافع سے پاک)")
                sb.appendLine("⚖️ Withholding Tax: *0%* (Exempt / کوئی کٹوتی نہیں)")
                sb.appendLine("💳 Features: Cheque Book, ATM/Debit Card, Online Banking, Unlimited Transactions")
                sb.appendLine("────────────────────")
                sb.appendLine("Prepared by: ${uiState.profile.officerName.ifBlank { "Relationship Manager" }}${if (uiState.profile.staffId.isNotBlank()) " (${uiState.profile.staffId})" else ""}")
                sb.append("📍 Branch: ${uiState.profile.branchName.ifBlank { "Main Branch" }}")
                return@remember sb.toString()
              }

              sb.appendLine("🏛️ *KB DEPOSIT & INVESTMENT PROPOSAL*")
              sb.appendLine("👤 Customer: ${uiState.customerName.ifBlank { "Valued Client" }}")
              if (uiState.mobile.isNotBlank()) {
                sb.appendLine("📱 Mobile: ${uiState.mobile}")
              }
              sb.appendLine("💰 Deposit Product: ${product.name}")
              sb.appendLine("────────────────────")
              val tenuresToShare = intervalTenures.filter { it in pMin..pMax }

              var planIndex = 0
              tenuresToShare.forEach { t ->
                val rForT = LoanCalculators.getApplicableRate(product, depAmt)
                val dRes = LoanCalculators.calculateDepositProfit(depAmt, rForT, t)
                planIndex++

                val durationLabel = when {
                  t == 1 -> "1 Month"
                  t % 12 == 0 -> "$t Months (${t / 12} ${if (t / 12 == 1) "Year" else "Years"})"
                  else -> "$t Months (${String.format(Locale.US, "%.1f", t / 12.0)} Years)"
                }

                sb.appendLine()
                sb.appendLine("📅 *$durationLabel*")
                sb.appendLine("💵 Target Deposit: *${LoanCalculators.formatCurrency(depAmt, uiState.profile.currency)}*")
                sb.appendLine("📈 Profit Rate: *${rForT}% p.a.* (${product.rateType.uppercase()})")
                sb.appendLine("💵 Monthly Profit Payout: *${LoanCalculators.formatCurrency(dRes.monthlyProfit, uiState.profile.currency)}*")
                sb.appendLine("🎁 Total Term Profit: *${LoanCalculators.formatCurrency(dRes.totalProfit, uiState.profile.currency)}*")
                sb.appendLine("💎 Maturity Payout: *${LoanCalculators.formatCurrency(dRes.maturityAmount, uiState.profile.currency)}*")
                sb.appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
              }

              sb.appendLine()
              sb.appendLine("────────────────────")
              sb.appendLine("Prepared by: ${uiState.profile.officerName.ifBlank { "Relationship Manager" }}${if (uiState.profile.staffId.isNotBlank()) " (${uiState.profile.staffId})" else ""}")
              sb.append("📍 Branch: ${uiState.profile.branchName.ifBlank { "Main Branch" }}")
              return@remember sb.toString()
            }

            sb.appendLine("🏛️ *KB LOAN PROPOSAL PLANS*")
            sb.appendLine("👤 Customer: ${uiState.customerName.ifBlank { "Valued Client" }}")
            if (uiState.mobile.isNotBlank()) {
              sb.appendLine("📱 Mobile: ${uiState.mobile}")
            }
            sb.appendLine("📋 Facility: ${product.name}")
            sb.appendLine("────────────────────")

            val maxAllowedTenureForShare = if (uiState.dob.isNotBlank() && ageInfo.isEligible) {
              ageInfo.remainingMonthsToMaturity.coerceAtMost(pMax)
            } else {
              pMax
            }

            val matchedTenures = mutableListOf<Int>()
            intervalTenures.forEach { t ->
              if (t in pMin..maxAllowedTenureForShare) {
                val pForT = viewModel.getPrincipalForTenure(t)
                if (pForT > 0L) {
                  val rForT = LoanCalculators.getApplicableRate(product, pForT)
                  val emiForT = LoanCalculators.calculateEmi(pForT, rForT, t, product.rateType)
                  val dbrForT = if (uiState.monthlyIncome > 0L) (emiForT.emi.toDouble() / uiState.monthlyIncome.toDouble()) * 100.0 else 0.0
                  val isEligible = if (!hasIncomeAndDbrForShare || isAutoMaxMode) {
                    true
                  } else {
                    emiForT.emi <= effMaxEmiForShare || dbrForT <= uiState.allowedDbr.toDouble()
                  }
                  if (isEligible) {
                    matchedTenures.add(t)
                  }
                }
              }
            }

            // If a manual loan amount is entered without Income/DBR or if all interval plans up to maxAllowedTenureForShare are needed,
            // fall back to interval plans within product tenure & maturity limits.
            val tenuresToShare = if (matchedTenures.isNotEmpty()) {
              matchedTenures
            } else {
              intervalTenures.filter { it in pMin..maxAllowedTenureForShare && viewModel.getPrincipalForTenure(it) > 0L }
            }

            var planIndex = 0
            tenuresToShare.forEach { t ->
              val pForT = viewModel.getPrincipalForTenure(t)
              if (pForT > 0L) {
                val rForT = LoanCalculators.getApplicableRate(product, pForT)
                val emiForT = LoanCalculators.calculateEmi(pForT, rForT, t, product.rateType)
                val feeForT = LoanCalculators.calculateFee(product, pForT, uiState.profile.fedRate)
                planIndex++

                val durationLabel = when {
                  t == 1 -> "1 Month"
                  t % 12 == 0 -> "$t Months (${t / 12} ${if (t / 12 == 1) "Year" else "Years"})"
                  else -> "$t Months (${String.format(Locale.US, "%.1f", t / 12.0)} Years)"
                }

                sb.appendLine()
                sb.appendLine("📅 *$durationLabel*")
                sb.appendLine("💰 Loan Amount: *${LoanCalculators.formatCurrency(pForT, uiState.profile.currency)}*")
                sb.appendLine("💳 Monthly EMI: *${LoanCalculators.formatCurrency(emiForT.emi, uiState.profile.currency)}*")
                sb.appendLine("🧾 Processing Fee: *${LoanCalculators.formatCurrency(feeForT.totalFee, uiState.profile.currency)}*")
                sb.appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
              }
            }

            if (planIndex == 0) {
              sb.appendLine()
              sb.appendLine("Please enter Loan Amount or Monthly Income & DBR% to generate plans.")
            }

            sb.appendLine()
            sb.appendLine("────────────────────")
            sb.appendLine("🏦 Officer: ${uiState.profile.officerName.ifBlank { "Loan Officer" }}${if (uiState.profile.staffId.isNotBlank()) " (${uiState.profile.staffId})" else ""}")
            sb.append("📍 Branch: ${uiState.profile.branchName.ifBlank { "Main Branch" }}")
            sb.toString()
          }

          // Vertically arranged full-width communication and save buttons with 3D tactile press
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Officer GPS Location Status indicator
            val lat = uiState.lastCapturedLat
            val lng = uiState.lastCapturedLng
            if (lat != null && lng != null) {
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.dp, Color(0xFF93C5FD)),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Icon(
                    Icons.Default.LocationOn,
                    contentDescription = "GPS Attached",
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.size(16.dp)
                  )
                  Text(
                    text = "GPS Tag: ${String.format(Locale.US, "%.4f, %.4f", lat, lng)}${if (uiState.lastCapturedAddress.isNotBlank()) " (${uiState.lastCapturedAddress})" else ""} • Auto-attached",
                    fontSize = 11.sp,
                    color = Color(0xFF1E40AF),
                    fontWeight = FontWeight.Medium
                  )
                }
              }
            }

            Button(
              onClick = {
                executeSaveLead("WhatsApp")
                val formattedPhone = LoanCalculators.formatForWhatsApp(uiState.mobile)
                onShareWhatsApp(formattedPhone, proposalText)
              },
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .press3d(0.96f)
                .shadow(4.dp, RoundedCornerShape(12.dp)),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
              shape = RoundedCornerShape(12.dp)
            ) {
              Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(17.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Share Proposal on WhatsApp", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
              onClick = {
                executeSaveLead("SMS")
                onShareSms(uiState.mobile, proposalText)
              },
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .press3d(0.96f)
                .shadow(4.dp, RoundedCornerShape(12.dp)),
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
              shape = RoundedCornerShape(12.dp)
            ) {
              Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(17.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Send SMS Proposal", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
              onClick = {
                executeSaveLead("Clipboard Quote")
                onCopyProposal(proposalText)
              },
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .press3d(0.96f),
              shape = RoundedCornerShape(12.dp)
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Copy Proposal Quotation", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
              onClick = { executeSaveLead("Manual Save") },
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .press3d(0.96f)
                .shadow(4.dp, RoundedCornerShape(12.dp)),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              shape = RoundedCornerShape(12.dp)
            ) {
              Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Save Customer Lead to Pipeline", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }

    // 5. Interactive Eligibility Matrix (Customized to Product's Defined Tenure Range)
    if (!isCurrentProduct) {
      item {
        val productMinTenure = product.minTenure.coerceAtLeast(1)
        val productMaxTenure = product.maxTenure.coerceAtLeast(productMinTenure)

        GlassCard(
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val isLiab = product.category == "liability"
            val isAmountTiered = isLiab && (product.liabilityPricingType == "amount" || (product.tenureRates.isEmpty() && product.rateSlabs.size > 1))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              val tenuresText = if (product.fixedTenures.isNotEmpty()) product.fixedTenures.joinToString(", ") else "6, 12, 24, 36, 60"
              Text(
                text = if (isLiab) {
                  if (isAmountTiered) "💰 SAVING ACCOUNT RETURN MATRIX (BALANCE TIER RATES)"
                  else if (product.fixedTenures.size <= 1) "💰 SAVING ACCOUNT RETURN MATRIX (12 MONTHS ANNUALIZED)"
                  else "💰 TERM DEPOSIT RETURN MATRIX ($tenuresText MONTHS)"
                } else "⚡ ELIGIBILITY MATRIX (${productMinTenure} TO ${productMaxTenure} MONTHS)",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isLiab) Color(0xFF0F766E) else MaterialTheme.colorScheme.primary
              )
              Text(
                text = if (isLiab) {
                  if (isAmountTiered) "Expected profit return based on deposit balance tier. Tax: ${if (uiState.isFiler) "Filer ${uiState.profile.filerTaxRate}%" else "Non-Filer ${uiState.profile.nonFilerTaxRate}%"}."
                  else if (product.fixedTenures.size <= 1) "Expected daily profit return on average balance calculated for 12 months. Tax: ${if (uiState.isFiler) "Filer ${uiState.profile.filerTaxRate}%" else "Non-Filer ${uiState.profile.nonFilerTaxRate}%"}."
                  else "Fixed tenure profit returns for ${product.name} ($tenuresText Months). Tax: ${if (uiState.isFiler) "Filer ${uiState.profile.filerTaxRate}%" else "Non-Filer ${uiState.profile.nonFilerTaxRate}%"}. Tap row to select tenure."
                } else "Facility plans generated according to ${product.name} criteria (${productMinTenure}–${productMaxTenure} Months). Tap any row to customize Amortization Schedule.",
                fontSize = 11.sp,
                color = Color.Gray
              )
            }

          if (!isLiab) {
            // Dynamic Tenure Filter Chips based on selected product's min/max tenure limits (for Loans only)
            val standardTenureBrackets = listOf(
              "1-12" to (1..12),
              "13-36" to (13..36),
              "37-60" to (37..60),
              "61-120" to (61..120)
            )

            val applicableFilterChips = remember(productMinTenure, productMaxTenure) {
              val matching = standardTenureBrackets.filter { (_, range) ->
                productMaxTenure >= range.first && productMinTenure <= range.last
              }.map { it.first }
              listOf("All") + matching
            }

            val activeFilter = if (applicableFilterChips.contains(uiState.matrixTenureFilter)) {
              uiState.matrixTenureFilter
            } else {
              "All"
            }

            // Tenure Filter Chips (Horizontally scrollable with 3D press)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              applicableFilterChips.forEach { filter ->
                val isSelected = activeFilter == filter
                FilterChip(
                  selected = isSelected,
                  onClick = { viewModel.updateMatrixFilter(filter) },
                  label = { Text(filter, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                  modifier = Modifier.press3d(0.94f)
                )
              }
            }
          }

          // Check if customer entered Income and DBR (not required for Liability investments)
          val hasFinancialInputs = if (isLiab) true else (uiState.monthlyIncome > 0L && uiState.allowedDbr > 0)

          if (!hasFinancialInputs) {
            Card(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
              shape = RoundedCornerShape(12.dp),
              border = CardDefaults.outlinedCardBorder()
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Text("📊", fontSize = 26.sp)
                Text(
                  text = "Enter Monthly Income & DBR %",
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
                  color = Color(0xFF1E293B)
                )
                Text(
                  text = "Please enter Net Monthly Income and Allowed DBR above to automatically generate your ${productMinTenure}-to-${productMaxTenure} Month Eligibility Matrix & Loan Options.\n\nبراہ کرم خالص ماہانہ آمدنی اور DBR درج کریں تاکہ پروڈکٹ کے مطابق ${productMinTenure} سے ${productMaxTenure} مہینوں کے تمام پلانز ظاہر ہوں۔",
                  fontSize = 11.sp,
                  color = Color(0xFF64748B),
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
              }
            }
          } else {
            val filteredTenures = if (isLiab) {
              if (isAmountTiered) listOf(1, 3, 6, 12)
              else if (product.fixedTenures.isNotEmpty()) product.fixedTenures
              else if (product.minTenure == product.maxTenure) listOf(product.minTenure)
              else listOf(6, 12, 24, 36, 60)
            } else {
              val activeFilter = uiState.matrixTenureFilter
              (productMinTenure..productMaxTenure).filter { t ->
                when (activeFilter) {
                  "1-12" -> t in 1..12
                  "13-36" -> t in 13..36
                  "37-60" -> t in 37..60
                  "61-120" -> t in 61..120
                  else -> true
                }
              }
            }

            if (isLiab) {
              val appliedDep = if (currentPrincipal > 0L) currentPrincipal else product.minAmount
              // Liability Deposit Return Table Headers
              Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF042F2E),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text("Tenure", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(0.75f))
                  Text("Deposit\n(PKR)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.05f))
                  Text("Rate\n(% p.a.)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(0.75f))
                  Text("Net Mo\n(PKR)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34D399), modifier = Modifier.weight(1.05f))
                  Text("Net Profit\n(PKR)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24), modifier = Modifier.weight(1.15f))
                  Text("Net Payout\n(PKR)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.25f))
                }
              }

              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 420.dp)
                  .verticalScroll(rememberScrollState())
              ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  filteredTenures.forEach { t ->
                    val rForTenure = LoanCalculators.getApplicableRate(product, appliedDep, t)
                    val dRes = LoanCalculators.calculateDepositProfit(
                      depositAmount = appliedDep,
                      annualRate = rForTenure,
                      tenureMonths = t,
                      isFiler = uiState.isFiler,
                      filerTaxRate = uiState.profile.filerTaxRate,
                      nonFilerTaxRate = uiState.profile.nonFilerTaxRate
                    )
                    val isSelected = uiState.selectedTenure == t

                    val rowBgColor = if (isSelected) Color(0xFFCCFBF1) else Color(0xFFF0FDFA)
                    val rowTextColor = Color(0xFF0F766E)

                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .press3d(0.97f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color = rowBgColor)
                        .clickable {
                          viewModel.selectTenure(t)
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = if (isAmountTiered) "${t}M" else if (t == 12 && product.fixedTenures.size <= 1) "12M (1Y)" else "${t}M",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = rowTextColor,
                        maxLines = 1,
                        modifier = Modifier.weight(0.75f)
                      )

                      Text(
                        text = LoanCalculators.formatNumber(appliedDep),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = rowTextColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1.05f)
                      )

                      Text(
                        text = "$rForTenure%",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD97706),
                        maxLines = 1,
                        modifier = Modifier.weight(0.75f)
                      )

                      Column(modifier = Modifier.weight(1.05f)) {
                        Text(
                          text = LoanCalculators.formatNumber(dRes.netMonthlyProfit),
                          fontSize = 9.5.sp,
                          fontWeight = FontWeight.ExtraBold,
                          color = Color(0xFF047857),
                          maxLines = 1
                        )
                        Text(
                          text = "Gross: ${LoanCalculators.formatNumber(dRes.monthlyProfit)}",
                          fontSize = 7.5.sp,
                          color = Color(0xFF64748B),
                          maxLines = 1
                        )
                      }

                      Column(modifier = Modifier.weight(1.15f)) {
                        Text(
                          text = LoanCalculators.formatNumber(dRes.netTotalProfit),
                          fontSize = 9.5.sp,
                          color = Color(0xFFB45309),
                          fontWeight = FontWeight.Bold,
                          maxLines = 1
                        )
                        Text(
                          text = "-Tax: ${LoanCalculators.formatNumber(dRes.totalTax)}",
                          fontSize = 7.5.sp,
                          color = Color(0xFFEF4444),
                          maxLines = 1
                        )
                      }

                      Text(
                        text = LoanCalculators.formatNumber(dRes.netMaturityAmount),
                        fontSize = 9.5.sp,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        modifier = Modifier.weight(1.25f)
                      )
                    }
                  }
                }
              }
            } else {
              // Matrix Column Headers with PKR in Headings Only (Dark Executive Glass)
              Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text("Tenure", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(0.62f))
                  Text("Principal\n(PKR)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.25f))
                  Text("Monthly EMI\n(PKR)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.25f))
                  Text("Profit\n(PKR)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.15f))
                  Text("Fee+FED\n(PKR)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.05f))
                  Text("Status\n(DBR %)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.25f))
                }
              }

              val effMaxEmi = (uiState.monthlyIncome * uiState.allowedDbr) / 100

              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 420.dp)
                  .verticalScroll(rememberScrollState())
              ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  filteredTenures.forEach { t ->
                    val pForTenure = viewModel.getPrincipalForTenure(t)
                    val rForTenure = LoanCalculators.getApplicableRate(product, pForTenure)
                    val emiRes = LoanCalculators.calculateEmi(pForTenure, rForTenure, t, product.rateType)
                    val feeRes = LoanCalculators.calculateFee(product, pForTenure, uiState.profile.fedRate)

                    val dbrValue = (emiRes.emi.toDouble() / uiState.monthlyIncome.toDouble()) * 100.0
                    val dbrPercent = String.format(Locale.US, "%.1f", dbrValue)

                    val isDbrEligible = if (isAutoMaxMode) {
                      pForTenure > 0L && (emiRes.emi <= effMaxEmi || dbrValue <= uiState.allowedDbr.toDouble() + 0.05)
                    } else {
                      emiRes.emi <= effMaxEmi || dbrValue <= uiState.allowedDbr.toDouble() + 0.05
                    }
                    val hasValidDob = uiState.dob.isNotBlank() && (detailedAge.years > 0 || detailedAge.months > 0 || detailedAge.days > 0)
                    val isTenureAgeEligible = if (hasValidDob) {
                      ageInfo.isEligible && t <= ageInfo.remainingMonthsToMaturity
                    } else {
                      true
                    }

                    val isPlanEligible = isDbrEligible && isTenureAgeEligible
                    val isSelected = uiState.selectedTenure == t

                    val rowBgColor = when {
                      isPlanEligible && isSelected -> Color(0xFFC8E6C9)
                      isPlanEligible -> Color(0xFFE8F5E9)
                      !isPlanEligible && isSelected -> Color(0xFFFFCDD2)
                      else -> Color(0xFFFFEBEE)
                    }
                    val rowTextColor = if (isPlanEligible) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                    val rowAccentColor = if (isPlanEligible) Color(0xFF2E7D32) else Color(0xFFC62828)

                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .press3d(0.97f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color = rowBgColor)
                        .clickable {
                          viewModel.openAmortizationScreen(t)
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "${t}M",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = rowTextColor,
                        maxLines = 1,
                        modifier = Modifier.weight(0.62f)
                      )

                      Text(
                        text = LoanCalculators.formatNumber(pForTenure),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = rowTextColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1.25f)
                      )

                      Text(
                        text = LoanCalculators.formatNumber(emiRes.emi),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = rowTextColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1.25f)
                      )

                      Text(
                        text = LoanCalculators.formatNumber(emiRes.totalProfit),
                        fontSize = 9.5.sp,
                        color = rowAccentColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1.15f)
                      )

                      Text(
                        text = LoanCalculators.formatNumber(feeRes.totalFee),
                        fontSize = 9.5.sp,
                        color = rowAccentColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1.05f)
                      )

                      Text(
                        text = if (!isTenureAgeEligible) "Age Limit ($dbrPercent%)" else "DBR $dbrPercent%",
                        fontSize = 9.5.sp,
                        color = rowTextColor,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1.25f)
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
    }
    }
  }

  // Security & Collateral Info Dialog
  if (showCollateralInfoDialog) {
    AlertDialog(
      onDismissRequest = { showCollateralInfoDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF1E3A8A))
          Column {
            Text("Security & Collateral Guide", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(product.name, fontSize = 11.sp, color = Color(0xFF64748B))
          }
        }
      },
      text = {
        Column(
          modifier = Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("📌 Facility Criteria Summary:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
              Text("• Amount Range: ${LoanCalculators.formatCurrency(product.minAmount, uiState.profile.currency)} – ${LoanCalculators.formatCurrency(product.maxAmount, uiState.profile.currency)}", fontSize = 11.sp)
              Text("• Tenure Range: ${product.minTenure} to ${product.maxTenure} Months", fontSize = 11.sp)
              Text("• Age Policy: Min ${product.minAge} Years • Max ${product.maxAgeAtMaturity} Years at Maturity", fontSize = 11.sp)
            }
          }

          Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("🛡️ Required Securities & Collateral:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
              Text(
                text = product.collateralDetails.ifBlank {
                  "• Valid CNIC & Photographs\n• Proof of Income / Bank Statement\n• Facility-specific Security & Post-Dated Cheques\n(Admin can customize collateral details in Admin & Slabs -> Edit Facility)"
                },
                fontSize = 12.sp,
                color = Color(0xFF0F172A),
                lineHeight = 18.sp
              )
            }
          }
        }
      },
      confirmButton = {
        Button(onClick = { showCollateralInfoDialog = false }) {
          Text("Close Guide")
        }
      }
    )
  }
}
