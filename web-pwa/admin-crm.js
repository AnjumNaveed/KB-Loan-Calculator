/**
 * KBL Loan Calculator - Leads Analytics, Branch Supervisor Monitoring & 9-Card Admin Console
 * Complete parity with Android LeadsScreen.kt and AdminScreen.kt
 */

let adminUnlocked = false;
let expandedLeadIds = new Set();
let adminSelectedLeadIds = new Set();
let adminProdFilterCat = 'all';
let myBranchFilterActive = false;

const APPS_SCRIPT_TEMPLATE = `function cleanDigits(val) {
  if (!val) return "";
  var s = String(val).replace(/\\D/g, "");
  if (s.length > 10 && s.indexOf("92") === 0) s = s.substring(2);
  if (s.length > 10 && s.indexOf("0") === 0) s = s.substring(1);
  return s;
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.tryLock(30000);
  try {
    var data = JSON.parse(e.postData.contents);
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
        status: "success", version: "2.2-dual-key-upsert", message: "KB App Connected Successfully!"
      })).setMimeType(ContentService.MimeType.JSON);
    }

    if (data.action === "delete_leads" || data.action === "delete_lead") {
      var deleteIds = data.leadIds || (data.leadId ? [data.leadId] : []);
      var lastRow = sheet.getLastRow();
      if (lastRow > 1 && deleteIds.length > 0) {
        var vals = sheet.getRange(2, 1, lastRow - 1, 1).getValues();
        for (var r = vals.length - 1; r >= 0; r--) {
          if (deleteIds.indexOf(String(vals[r][0]).trim()) !== -1) sheet.deleteRow(r + 2);
        }
      }
      return ContentService.createTextOutput(JSON.stringify({ status: "success" })).setMimeType(ContentService.MimeType.JSON);
    }

    var leads = data.leads || [data];
    var lastRow = sheet.getLastRow();
    var idToRowMap = {};
    var phoneToRowMap = {};
    if (lastRow > 1) {
      var existingData = sheet.getRange(2, 1, lastRow - 1, 4).getValues();
      for (var r = 0; r < existingData.length; r++) {
        var eId = String(existingData[r][0]).trim();
        var ePhone = cleanDigits(existingData[r][3]);
        if (eId) idToRowMap[eId] = r + 2;
        if (ePhone) phoneToRowMap[ePhone] = r + 2;
      }
    }

    for (var i = 0; i < leads.length; i++) {
      var l = leads[i];
      var leadId = String(l.id || "").trim();
      var cleanPhone = cleanDigits(l.mobile);
      var rowData = [
        leadId, l.timestamp || new Date().toLocaleString(), l.customerName || "", l.mobile || "",
        l.category || "asset", l.productName || "", l.loanAmount || 0, l.tenureMonths || 0,
        l.monthlyEmi || 0, l.totalProfit || 0, l.processingFee || 0, l.status || "New",
        l.followUpDate || "", l.officerName || "", l.officerId || "", l.branchName || "",
        l.notes || "", l.approachCount || 1, l.locationAddress || "", l.touchpointLog || ""
      ];
      var targetRow = idToRowMap[leadId] || phoneToRowMap[cleanPhone] || null;
      if (targetRow) {
        sheet.getRange(targetRow, 1, 1, 20).setValues([rowData]);
      } else {
        sheet.appendRow(rowData);
      }
    }
    return ContentService.createTextOutput(JSON.stringify({ status: "success" })).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ status: "error", message: err.toString() })).setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}`;

// =========================================================================
// 1. BRANCH AUTHORIZATION HELPERS (Exact Android LeadsScreen.kt Parity)
// =========================================================================
function normalizeBranchText(branch) {
  return String(branch || '').trim().replace(/\s+/g, ' ').toLowerCase();
}

function canonicalBranchCode(branch) {
  return normalizeBranchText(branch).replace(/branch/g, '').replace(/\s+/g, '').trim();
}

function isPrimaryAdminUser() {
  if (!currentUser || !currentUser.email) return false;
  const em = currentUser.email.toLowerCase();
  return em.includes('vipanjum') || em.includes('admin');
}

function getEffectiveAllowedBranch() {
  if (isPrimaryAdminUser() || (currentUserProfile && currentUserProfile.role === 'Admin')) {
    return 'ALL';
  }
  const allowed = (currentUserProfile && currentUserProfile.allowedSupervisorBranch) || '';
  const home = (currentUserProfile && currentUserProfile.branchName) || officerProfile.branchName || '';
  return (allowed || home).trim();
}

function canCurrentUserUseSupervisorFilter() {
  if (isPrimaryAdminUser() || (currentUserProfile && currentUserProfile.role === 'Admin')) return true;
  if (!currentUserProfile) return true;
  return currentUserProfile.canUseSupervisorFilter !== false;
}

function isBranchAuthorizedForQuery(queryBranch, leadBranch) {
  const cleanQ = normalizeBranchText(queryBranch);
  const cleanLead = normalizeBranchText(leadBranch);
  if (!cleanQ || !cleanLead) return false;
  const compactQ = canonicalBranchCode(queryBranch);
  const compactLead = canonicalBranchCode(leadBranch);
  return cleanLead === cleanQ ||
    cleanLead.includes(cleanQ) ||
    (compactQ.length >= 3 && (compactLead === compactQ || compactLead.includes(compactQ)));
}

function isSupervisorQueryAllowed(queryBranch) {
  const cleanQ = normalizeBranchText(queryBranch);
  if (!cleanQ) return true;
  if (!canCurrentUserUseSupervisorFilter()) return false;
  const effAllowed = getEffectiveAllowedBranch();
  if (!effAllowed) return false;
  const tokens = effAllowed.split(/[,;|]/).map(s => s.trim()).filter(Boolean);
  if (tokens.some(t => t.toUpperCase() === 'ALL' || t === '*')) return true;
  const compactQ = canonicalBranchCode(queryBranch);
  return tokens.some(tok => {
    const cleanAllowed = normalizeBranchText(tok);
    const compactAllowed = canonicalBranchCode(tok);
    return cleanQ === cleanAllowed ||
      cleanAllowed.includes(cleanQ) ||
      cleanQ.includes(cleanAllowed) ||
      (compactQ.length >= 2 && (compactQ === compactAllowed || compactAllowed.includes(compactQ)));
  });
}

function isLeadOwnedByCurrentOfficer(lead) {
  const uidMatch = currentUser && lead.userId && lead.userId === currentUser.uid;
  const staffMatch = officerProfile.staffId && lead.officerId &&
    lead.officerId.trim().toLowerCase() === officerProfile.staffId.trim().toLowerCase();
  const nameMatch = officerProfile.officerName && lead.officerName &&
    lead.officerName.trim().toLowerCase() === officerProfile.officerName.trim().toLowerCase();
  return Boolean(uidMatch || staffMatch || nameMatch);
}

function getAllRegisteredUsersList() {
  const map = new Map();
  const mergeSource = (srcObj) => {
    if (!srcObj || typeof srcObj !== 'object') return;
    Object.entries(srcObj).forEach(([k, u]) => {
      if (u && typeof u === 'object') {
        const uid = u.uid || k;
        const prev = map.get(uid);
        if (!prev || (Number(u.updatedAt) || 0) >= (Number(prev.updatedAt) || 0)) {
          map.set(uid, { uid, ...u });
        }
      }
    });
  };
  mergeSource(globalConfig.registeredUsersMap);
  mergeSource(globalConfig.liveUsersCollectionMap);
  return Array.from(map.values()).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

function resolveLeadEffectiveBranch(lead) {
  const directBranch = (lead.branchName || '').trim();
  const regUsers = getAllRegisteredUsersList();
  const matched = regUsers.find(u =>
    (lead.userId && u.uid === lead.userId) ||
    (lead.officerId && u.staffId && u.staffId.toLowerCase() === lead.officerId.toLowerCase())
  );
  if (matched && matched.branchName && matched.branchName.trim()) {
    return matched.branchName.trim();
  }
  return directBranch;
}

function toggleMyBranchFilter() {
  const inp = document.getElementById('supervisorBranchInput');
  if (!inp) return;
  const effAllowed = getEffectiveAllowedBranch();
  const firstAllowed = effAllowed.split(/[,;|]/).map(s => s.trim()).filter(Boolean)[0] || '';
  const targetBranch = (!effAllowed || effAllowed.toUpperCase() === 'ALL')
    ? (officerProfile.branchName || '')
    : (officerProfile.branchName && isSupervisorQueryAllowed(officerProfile.branchName) ? officerProfile.branchName : firstAllowed);

  if (inp.value.trim()) {
    inp.value = '';
    myBranchFilterActive = false;
  } else {
    if (!targetBranch) {
      showToast('No assigned branch synced from Cloud yet.');
      return;
    }
    inp.value = targetBranch;
    myBranchFilterActive = true;
  }
  renderLeads();
}

function setLeadsStatusFilter(status) {
  const sel = document.getElementById('statusFilterSelect');
  if (sel) sel.value = status || 'All';
  renderLeads();
}

// =========================================================================
// 2. LEADS ANALYTICS DASHBOARD & EXPANDABLE CRM CARDS
// =========================================================================
function getFilteredAuthorizedLeads(allLeads) {
  const search = (document.getElementById('searchLeadsInput')?.value || '').toLowerCase().trim();
  const statusFilter = document.getElementById('statusFilterSelect')?.value || 'All';
  const supInput = document.getElementById('supervisorBranchInput');
  const supQuery = supInput ? supInput.value.trim() : '';

  const canFilter = canCurrentUserUseSupervisorFilter();
  const effAllowed = getEffectiveAllowedBranch();
  const hasAllRights = effAllowed.split(/[,;|]/).some(t => t.trim().toUpperCase() === 'ALL' || t.trim() === '*');
  const unauthorizedQuery = supQuery && !isSupervisorQueryAllowed(supQuery);

  const statusEl = document.getElementById('supervisorAccessStatus');
  const toggleBtn = document.getElementById('toggleMyBranchBtn');
  if (toggleBtn) {
    toggleBtn.textContent = supQuery ? '👤 Show My Leads' : '🏢 Filter My Branch';
  }
  if (statusEl) {
    if (!canFilter) {
      statusEl.style.color = 'var(--danger)';
      statusEl.textContent = '🔒 Branch filter disabled by Admin • Viewing your personal leads only';
    } else if (unauthorizedQuery) {
      statusEl.style.color = 'var(--danger)';
      statusEl.textContent = `🚫 Access Denied for "${supQuery}" • Authorized only for: ${effAllowed || 'Own Leads'}`;
    } else if (hasAllRights) {
      statusEl.style.color = 'var(--success)';
      statusEl.textContent = supQuery
        ? `🔓 Admin Filter Active: Showing branch "${supQuery}"`
        : '🔓 Admin / Regional Rights: All Branches Allowed';
    } else {
      statusEl.style.color = 'var(--primary)';
      statusEl.textContent = `🛡️ Authorized Branch Scope: ${effAllowed || 'Own Branch'} (Viewing ${supQuery ? 'Branch Leads' : 'My Leads'})`;
    }
  }

  if (unauthorizedQuery) {
    return {
      leads: [],
      authorizedScopeLeads: [],
      scopeLabel: `Denied: "${supQuery}"`,
      statusFilter,
      search
    };
  }

  const scopeLeads = allLeads.filter(l => {
    const effBranch = resolveLeadEffectiveBranch(l);
    if (hasAllRights) {
      return supQuery ? isBranchAuthorizedForQuery(supQuery, effBranch) : true;
    }
    if (supQuery && canFilter) {
      return isBranchAuthorizedForQuery(supQuery, effBranch);
    }
    return isLeadOwnedByCurrentOfficer(l);
  });

  const filtered = scopeLeads.filter(l => {
    const effBranch = resolveLeadEffectiveBranch(l);
    if (statusFilter !== 'All' && (l.status || 'New') !== statusFilter) return false;
    if (search) {
      const hay = [
        l.customerName, l.mobile, l.productName, l.branchName,
        effBranch, l.officerName, l.officerId, l.notes, l.locationAddress
      ].join(' ').toLowerCase();
      if (!hay.includes(search)) return false;
    }
    return true;
  });

  filtered.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

  let baseScope = 'Personal Portfolio';
  if (supQuery) baseScope = `Branch Scope: ${supQuery}`;
  else if (hasAllRights) baseScope = 'All Branches (Admin Scope)';
  else if (officerProfile.branchName) baseScope = `My Leads • ${officerProfile.branchName}`;

  const parts = [baseScope];
  if (statusFilter !== 'All') parts.push(`Status: ${statusFilter}`);
  if (search) parts.push(`Search: "${search}"`);

  return {
    leads: filtered,
    authorizedScopeLeads: scopeLeads,
    scopeLabel: parts.join(' • ')
  };
}

function buildGpsMiniMapHtml(lead) {
  const lat = Number(lead.latitude);
  const lng = Number(lead.longitude);
  const hasCoords = lead.latitude !== null && lead.latitude !== undefined && lead.latitude !== '' &&
    lead.longitude !== null && lead.longitude !== undefined && lead.longitude !== '' &&
    !isNaN(lat) && !isNaN(lng) && !(lat === 0 && lng === 0);
  const cleanAddr = (lead.locationAddress || '').trim();
  const isGenericCoordAddr = /^GPS:\s*[-\d.]+\s*,\s*[-\d.]+$/i.test(cleanAddr);

  if (!hasCoords && !cleanAddr) return '';

  const timeNote = lead.locationCapturedAt ? ` • ${formatDate(lead.locationCapturedAt)}` : '';

  if (!hasCoords && cleanAddr) {
    const searchUrl = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(cleanAddr)}`;
    return `
      <div class="gps-addr-box">
        <div><strong>📍 Location Captured${timeNote}:</strong> ${escapeHtml(cleanAddr)}</div>
        <a href="${searchUrl}" target="_blank" rel="noopener noreferrer" style="display:inline-block;margin-top:4px;font-weight:800;color:var(--primary);text-decoration:none;">🗺️ Open in Google Maps ↗</a>
      </div>
    `;
  }

  const zoom = 15;
  const n = 1 << zoom;
  const clampedLat = Math.max(-85.0511, Math.min(85.0511, lat));
  const latRad = (clampedLat * Math.PI) / 180.0;
  const tileX = Math.floor(((lng + 180.0) / 360.0) * n);
  const tileY = Math.floor(((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0) * n);

  const tiles = [];
  for (let dy = -1; dy <= 0; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      const url = `https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/${zoom}/${tileY + dy}/${tileX + dx}`;
      tiles.push(`<img src="${url}" alt="" loading="lazy" onerror="this.style.opacity='0'">`);
    }
  }

  const mapsUrl = `https://www.google.com/maps/search/?api=1&query=${lat},${lng}`;
  const displayAddr = (!cleanAddr || isGenericCoordAddr) ? `GPS Coordinates: ${lat.toFixed(5)}, ${lng.toFixed(5)}` : cleanAddr;
  const topPillText = (!cleanAddr || isGenericCoordAddr) ? `${lat.toFixed(4)}, ${lng.toFixed(4)}` : cleanAddr;

  return `
    <div class="gps-addr-box">
      <strong>📍 Location Captured${timeNote}:</strong> ${escapeHtml(displayAddr)}
    </div>
    <a class="gps-map-card" href="${mapsUrl}" target="_blank" rel="noopener noreferrer" title="Tap to open location in Google Maps">
      <div class="gps-tile-grid">${tiles.join('')}</div>
      <div class="gps-map-top-badge">
        <span class="gps-badge-pill">📍 ${escapeHtml(topPillText)}</span>
        <span class="gps-badge-pill" style="max-width:none;">${lat.toFixed(4)}, ${lng.toFixed(4)}</span>
      </div>
      <div class="gps-pin-center">
        <div class="gps-pin-pulse"></div>
        <div class="gps-pin-marker">📍</div>
      </div>
      <div class="gps-map-footer">
        <span style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(displayAddr)}</span>
        <strong style="color:#FBBF24;white-space:nowrap;">Tap to open Google Maps ↗</strong>
      </div>
    </a>
  `;
}

async function renderLeads() {
  const allLeads = await LocalDB.getAllLeads();
  const countSpan = document.getElementById('leadsCount');
  const { leads: filtered, authorizedScopeLeads, scopeLabel } = getFilteredAuthorizedLeads(allLeads);

  if (countSpan) countSpan.textContent = authorizedScopeLeads.length;

  const kpiLeads = filtered;
  const total = kpiLeads.length;
  const convertedList = kpiLeads.filter(l => (l.status || 'New') === 'Converted');
  const lostList = kpiLeads.filter(l => (l.status || 'New') === 'Lost');
  const activeList = kpiLeads.filter(l => !['Converted', 'Lost'].includes(l.status || 'New'));

  const sumAmt = (arr) => arr.reduce((acc, item) => acc + (Number(item.loanAmount) || 0), 0);
  const convertedAmount = sumAmt(convertedList);
  const lostAmount = sumAmt(lostList);
  const activeAmount = sumAmt(activeList);

  const winRate = total > 0 ? Math.round((convertedList.length / total) * 100) : 0;
  const wonWidthPct = total > 0 ? (convertedList.length / total) * 100 : 0;
  const activeWidthPct = total > 0 ? (activeList.length / total) * 100 : 0;
  const lostWidthPct = total > 0 ? (lostList.length / total) * 100 : 0;
  const wonLabelPct = Math.round(wonWidthPct);
  const activeLabelPct = Math.round(activeWidthPct);
  const lostLabelPct = Math.round(lostWidthPct);

  const setKpi = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  const setWidth = (id, pct) => { const el = document.getElementById(id); if (el) el.style.width = `${pct.toFixed(1)}%`; };

  setKpi('kpiScopeSubtitle', scopeLabel || 'Personal Portfolio');
  setKpi('kpiWinRatePill', `🎯 ${winRate}% Win Rate`);
  setKpi('kpiPortfolioCountLabel', `${total} Lead${total === 1 ? '' : 's'} Logged`);

  setWidth('pbarWon', wonWidthPct);
  setWidth('pbarActive', activeWidthPct);
  setWidth('pbarLost', lostWidthPct);

  setKpi('legWonText', `Won: ${convertedList.length} (${wonLabelPct}%)`);
  setKpi('legActiveText', `Active: ${activeList.length} (${activeLabelPct}%)`);
  setKpi('legLostText', `Lost: ${lostList.length} (${lostLabelPct}%)`);

  setKpi('kpiTotalLeads', total);
  setKpi('kpiTotalSub', `${activeList.length} active • ${convertedList.length} won`);
  setKpi('kpiActiveCountTag', `${activeList.length} Deals`);
  setKpi('kpiActiveAmount', `PKR ${formatPKR(activeAmount)}`);
  setKpi('kpiWonCountTag', `${convertedList.length} Won`);
  setKpi('kpiConvertedAmount', `PKR ${formatPKR(convertedAmount)}`);
  setKpi('kpiConvertedSub', `${convertedList.length} closed deal${convertedList.length === 1 ? '' : 's'} (${winRate}%)`);
  setKpi('kpiLostCountTag', `${lostList.length} Lost`);
  setKpi('kpiLostAmount', `PKR ${formatPKR(lostAmount)}`);
  setKpi('kpiLostSub', `${lostList.length} dropped lead(s)`);

  const container = document.getElementById('leadsListContainer');
  if (!container) return;

  if (filtered.length === 0) {
    container.innerHTML = `
      <div class="card" style="text-align: center; color: var(--text-muted); padding: 28px;">
        <div style="font-size: 1.8rem; margin-bottom: 6px;">📂</div>
        <div style="font-weight: 800; margin-bottom: 4px;">No leads matched this filter</div>
        <div style="font-size: 0.78rem;">Try clearing the search/filter or check your authorized branch scope above.</div>
      </div>
    `;
    return;
  }

  const statuses = ['New', 'Contacted', 'Follow-Up', 'Converted', 'Lost'];

  container.innerHTML = filtered.map(lead => {
    const isExpanded = expandedLeadIds.has(lead.id);
    const cleanNote = cleanRawActionPayload(lead.notes || '');
    const badgeClass = 'badge-' + (lead.status || 'new').toLowerCase().replace(/[^a-z]/g, '');
    const effBranch = resolveLeadEffectiveBranch(lead) || 'Unassigned';
    const hasGps = (lead.latitude != null && lead.longitude != null && !(Number(lead.latitude) === 0 && Number(lead.longitude) === 0)) || Boolean((lead.locationAddress || '').trim());
    const mapCardHtml = buildGpsMiniMapHtml(lead);

    return `
      <div class="lead-card ${isExpanded ? 'expanded' : ''}" id="lead-card-${lead.id}">
        <button type="button" class="lead-summary-btn" onclick="toggleLeadCardExpand('${lead.id}')">
          <div style="min-width: 0; flex: 1;">
            <div style="display: flex; align-items: center; gap: 6px; flex-wrap: wrap;">
              <span class="lead-name">${escapeHtml(lead.customerName)}</span>
              <span class="badge ${badgeClass}">${escapeHtml(lead.status || 'New')}</span>
            </div>
            <div class="lead-phone">
              📞 ${escapeHtml(lead.mobile || 'N/A')} • 💼 ${escapeHtml(lead.productName)} • <strong>PKR ${formatPKR(lead.loanAmount)}</strong> (${lead.tenureMonths}M)
            </div>
            <div style="font-size: 0.72rem; color: var(--text-muted); margin-top: 3px;">
              👤 ${escapeHtml(lead.officerName || 'RO')} (${escapeHtml(lead.officerId || 'N/A')}) • 🏢 ${escapeHtml(effBranch)} • 📊 Approaches: <strong>${lead.approachCount || 1}</strong> ${hasGps ? '• 📍 GPS' : ''}
            </div>
          </div>
          <span class="admin-chevron">${isExpanded ? 'Hide ▴' : 'Details ▾'}</span>
        </button>

        <div class="lead-details-body">
          <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 6px 12px; font-size: 0.78rem; margin-bottom: 8px; background: var(--surface-subtle); padding: 9px 10px; border-radius: 8px;">
            <div><strong>Monthly EMI / Profit:</strong> PKR ${formatPKR(lead.monthlyEmi)}</div>
            <div><strong>Total Profit:</strong> PKR ${formatPKR(lead.totalProfit)}</div>
            <div><strong>Processing Fee:</strong> PKR ${formatPKR(lead.processingFee)}</div>
            <div><strong>Created:</strong> ${formatDate(lead.createdAt)}</div>
            ${lead.dob ? `<div><strong>DOB / Age:</strong> ${escapeHtml(lead.dob)} (${lead.age || 0} Yrs)</div>` : ''}
            ${lead.monthlyIncome ? `<div><strong>Income / DBR:</strong> PKR ${formatPKR(lead.monthlyIncome)} (${lead.dbrAllowed || 50}%)</div>` : ''}
            <div><strong>Follow-Up:</strong> ${lead.followUpDate ? escapeHtml(lead.followUpDate) : 'None'}</div>
            <div><strong>Cloud Sync:</strong> ${lead.syncStatus === 'synced' ? '✅ Synced' : '⏳ Pending'}</div>
          </div>

          ${mapCardHtml}
          ${cleanNote ? `<div class="lead-notes-box">📝 <strong>Note:</strong> ${escapeHtml(cleanNote)}</div>` : ''}

          <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin-bottom: 8px;">
            <label style="font-size: 0.76rem; font-weight: 700; color: var(--text-muted);">Update Pipeline Status:</label>
            <select class="form-select" style="height: 34px; font-size: 0.78rem; width: auto; min-width: 150px; font-weight: 700;" onchange="updateLeadStatus('${lead.id}', this.value)">
              ${statuses.map(s => `<option value="${s}" ${(lead.status || 'New') === s ? 'selected' : ''}>${s}</option>`).join('')}
            </select>
          </div>

          <div class="lead-actions">
            <button type="button" class="btn btn-call" onclick="initiateCall('${lead.id}', '${escapeHtml(lead.mobile)}')">📞 Call</button>
            <button type="button" class="btn btn-whatsapp" onclick="sendQuotationWhatsApp('${lead.id}')">💬 WhatsApp</button>
            <button type="button" class="btn btn-sms" onclick="sendQuotationSMS('${lead.id}')">✉️ SMS</button>
            <button type="button" class="btn btn-outline" onclick="openTrackRecord('${lead.id}')">📜 History (${(lead.activities || []).length})</button>
            <button type="button" class="btn btn-outline" onclick="openEditNoteModal('${lead.id}')">📝 Note</button>
            <button type="button" class="btn btn-outline" onclick="openFollowUpModal('${lead.id}')">📅 Follow-Up</button>
          </div>
        </div>
      </div>
    `;
  }).join('');
}

function toggleLeadCardExpand(leadId) {
  if (expandedLeadIds.has(leadId)) expandedLeadIds.delete(leadId);
  else expandedLeadIds.add(leadId);
  const el = document.getElementById(`lead-card-${leadId}`);
  if (el) {
    el.classList.toggle('expanded');
    const chev = el.querySelector('.admin-chevron');
    if (chev) chev.textContent = el.classList.contains('expanded') ? 'Hide ▴' : 'Details ▾';
  }
}

async function exportFilteredLeadsCsv() {
  await withLoading('Preparing CSV Export...', async () => {
    const allLeads = await LocalDB.getAllLeads();
    const { leads } = getFilteredAuthorizedLeads(allLeads);
    downloadLeadsAsCsv(leads, `kbl-leads-${new Date().toISOString().slice(0, 10)}.csv`);
  });
}

function downloadLeadsAsCsv(leads, filename) {
  if (!leads || leads.length === 0) {
    showToast('No leads available to export.');
    return;
  }
  const headers = [
    'Lead ID', 'Date Created', 'Customer Name', 'Mobile Number', 'Category',
    'Product Name', 'Loan / Deposit Amount', 'Tenure (M)', 'Monthly EMI / Profit',
    'Total Profit', 'Processing Fee', 'Status', 'Follow-up Date', 'Officer Name',
    'Staff ID', 'Branch Name', 'Current Notes', 'Approaches Count', 'Location Address', 'Audit Log'
  ];
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const rows = leads.map(l => {
    const effBranch = resolveLeadEffectiveBranch(l) || '';
    const activities = Array.isArray(l.activities)
      ? l.activities.slice().sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0))
      : [];
    const auditLog = activities.map(act => {
      const actTime = formatDate(act.timestamp);
      const cleanDetail = cleanRawActionPayload(act.details || '');
      return `• [${actTime}] ${act.title || ''}: ${cleanDetail} - By: ${act.officerName || 'Officer'}`;
    }).join('\n');
    const locAddress = l.locationAddress ||
      (l.latitude != null && l.longitude != null && !(Number(l.latitude) === 0 && Number(l.longitude) === 0)
        ? `GPS: ${l.latitude}, ${l.longitude}`
        : '');

    return [
      l.id,
      formatDate(l.createdAt || Date.now()),
      l.customerName || '',
      l.mobile || '',
      l.category || 'asset',
      l.productName || '',
      l.loanAmount || 0,
      l.tenureMonths || 0,
      l.monthlyEmi || 0,
      l.totalProfit || 0,
      l.processingFee || 0,
      l.status || 'New',
      l.followUpDate || '',
      l.officerName || '',
      l.officerId || '',
      effBranch,
      cleanRawActionPayload(l.notes || ''),
      l.approachCount || (activities.length || 1),
      locAddress,
      auditLog
    ].map(esc).join(',');
  });

  const csv = '\uFEFF' + [headers.map(esc).join(','), ...rows].join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  showToast(`📥 Exported ${leads.length} leads to CSV`);
}

// =========================================================================
// 3. PASSWORD-PROTECTED ADMIN & SLABS CONSOLE (9 COLLAPSIBLE CARDS)
// =========================================================================
async function unlockAdminPanel() {
  const entered = document.getElementById('adminPinInput').value;
  const errEl = document.getElementById('adminPinError');
  errEl.style.display = 'none';

  await withLoading('Verifying Admin Password...', async () => {
    let firestorePin = globalConfig.adminPin || 'Admin123';
    if (fb) {
      try {
        const docSnap = await fb.getDoc('config', 'global');
        if (docSnap.exists() && docSnap.data().adminPin) {
          firestorePin = docSnap.data().adminPin;
          globalConfig.adminPin = firestorePin;
        }
      } catch (e) {
        console.warn('Admin PIN fetch warning:', e);
      }
    }

    if (entered === firestorePin) {
      adminUnlocked = true;
      document.getElementById('adminPinInput').value = '';
      document.getElementById('adminLockScreen').style.display = 'none';
      document.getElementById('adminUnlockedContent').style.display = 'block';
      document.getElementById('adminNavIcon').textContent = '🔓';
      populateAdminControls();
      showToast('🔓 Admin Console Unlocked');
    } else {
      errEl.style.display = 'block';
      errEl.textContent = '❌ غلط ایڈمن پاسورڈ! براہ کرم فائر اسٹور والا درست پاسورڈ درج کریں۔';
    }
  });
}

function lockAdminPanel() {
  adminUnlocked = false;
  document.getElementById('adminLockScreen').style.display = 'block';
  document.getElementById('adminUnlockedContent').style.display = 'none';
  document.getElementById('adminNavIcon').textContent = '🔒';
  showToast('🔒 Admin Session Locked');
}

function toggleAdminCard(cardId) {
  const card = document.getElementById(cardId);
  if (!card) return;
  card.classList.toggle('expanded');
  const chev = card.querySelector('.admin-chevron');
  if (chev) {
    chev.textContent = card.classList.contains('expanded') ? 'Collapse ▴' : 'Expand ▾';
  }
}

function populateAdminControls() {
  const setVal = (id, v) => { const el = document.getElementById(id); if (el) el.value = v ?? ''; };
  setVal('adminWebhookUrl', globalConfig.googleSheetsWebhookUrl || officerProfile.webhookUrl || '');
  setVal('adminFedRate', globalConfig.fedRate ?? 16.0);
  setVal('adminFilerTaxRate', globalConfig.filerTaxRate ?? 20.0);
  setVal('adminNonFilerTaxRate', globalConfig.nonFilerTaxRate ?? 40.0);
  setVal('adminCurrency', globalConfig.currency || 'PKR');
  setVal('adminBroadcastInput', globalConfig.adminBroadcastMessage || '');
  setVal('adminUpdateUrlInput', globalConfig.appUpdateUrl || '');
  setVal('adminUpdateMsgInput', globalConfig.appUpdateMessage || '');

  const sheetsSub = document.getElementById('adminSheetsSubtitle');
  if (sheetsSub) {
    sheetsSub.textContent = globalConfig.googleSheetsWebhookUrl
      ? '✅ Live sync active • Leads auto-log to Google Sheets'
      : '⚠️ Disconnected • Set Web App URL';
  }
  const taxSub = document.getElementById('adminTaxSubtitle');
  if (taxSub) {
    taxSub.textContent = `FED: ${globalConfig.fedRate}% • Filer: ${globalConfig.filerTaxRate}% • Non-Filer: ${globalConfig.nonFilerTaxRate}%`;
  }
  const updSub = document.getElementById('adminUpdateSubtitle');
  if (updSub) {
    updSub.textContent = globalConfig.appUpdateUrl ? '🚀 ACTIVE UPDATE LINK PUBLISHED' : 'No active forced update link';
  }

  const pre = document.getElementById('appsScriptCodePre');
  if (pre) pre.textContent = APPS_SCRIPT_TEMPLATE;

  renderAdminProductsList();
  renderAdminUsersList();
  renderAdminLeadsDbList();
}

// Card 1: Theme & Display Appearance
async function setAppTheme(mode) {
  await LocalDB.setSetting('themeMode', mode);
  applyThemeMode(mode);
  showToast(`🎨 Theme set to ${mode.toUpperCase()}`);
}

function applyThemeMode(mode) {
  let effective = mode || 'light';
  if (effective === 'system') {
    effective = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  document.documentElement.setAttribute('data-theme', effective);

  ['light', 'dark', 'system'].forEach(k => {
    const id = 'themeBtn' + k.charAt(0).toUpperCase() + k.slice(1);
    const btn = document.getElementById(id);
    if (btn) btn.className = 'chip-btn' + (mode === k ? ' active' : '');
  });
}

// Card 2: Google Sheets Integration
async function adminSaveSheetsUrl() {
  const url = document.getElementById('adminWebhookUrl').value.trim();
  await withLoading('Saving Google Sheets URL...', async () => {
    officerProfile.webhookUrl = url;
    globalConfig.googleSheetsWebhookUrl = url;
    await LocalDB.setSetting('webhookUrl', url);

    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          googleSheetsWebhookUrl: url,
          updatedAt: fb.serverTimestamp()
        }, true);
      } catch (e) {
        console.warn('Error saving webhook URL:', e);
      }
    }
    populateAdminControls();
    showToast('✅ Google Sheets Webhook URL synced globally!');
  });
}

function copyAppsScriptCode() {
  copyToClipboard(APPS_SCRIPT_TEMPLATE, '📋 Google Apps Script v2.2 code copied!');
}

// Card 3: Change Admin Password
async function adminUpdatePassword() {
  const oldPin = document.getElementById('oldAdminPinInput').value;
  const newPin = document.getElementById('newAdminPinInput').value.trim();
  const confirmPin = document.getElementById('confirmAdminPinInput').value.trim();

  if (!newPin || newPin.length < 4) {
    showToast('New password must be at least 4 characters.');
    return;
  }
  if (newPin !== confirmPin) {
    showToast('❌ New password and confirmation do not match.');
    return;
  }

  await withLoading('Updating Admin Password...', async () => {
    let currentPin = globalConfig.adminPin || 'Admin123';
    if (fb) {
      try {
        const docSnap = await fb.getDoc('config', 'global');
        if (docSnap.exists() && docSnap.data().adminPin) {
          currentPin = docSnap.data().adminPin;
        }
        if (oldPin !== currentPin) {
          showToast('❌ Current Admin Password does not match Firestore!');
          return;
        }
        await fb.setDoc('config', 'global', {
          adminPin: newPin,
          updatedAt: fb.serverTimestamp()
        }, true);
        globalConfig.adminPin = newPin;
        document.getElementById('oldAdminPinInput').value = '';
        document.getElementById('newAdminPinInput').value = '';
        document.getElementById('confirmAdminPinInput').value = '';
        showToast('🔑 Admin Password updated in Firestore!');
      } catch (e) {
        showToast('Error updating password: ' + e.message);
      }
    } else {
      showToast('Firebase offline — cannot update global password.');
    }
  });
}

// Card 4: Global FED & WHT Tax Rates
async function adminSaveTaxAndFedConfig() {
  const fedRate = parseFloat(document.getElementById('adminFedRate').value) || 16.0;
  const filerTaxRate = parseFloat(document.getElementById('adminFilerTaxRate').value) || 20.0;
  const nonFilerTaxRate = parseFloat(document.getElementById('adminNonFilerTaxRate').value) || 40.0;
  const currency = document.getElementById('adminCurrency').value.trim() || 'PKR';

  await withLoading('Syncing Tax & FED Rates...', async () => {
    globalConfig.fedRate = fedRate;
    globalConfig.filerTaxRate = filerTaxRate;
    globalConfig.nonFilerTaxRate = nonFilerTaxRate;
    globalConfig.currency = currency;

    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          fedRate, filerTaxRate, nonFilerTaxRate, currency,
          updatedAt: fb.serverTimestamp()
        }, true);
      } catch (e) {
        console.warn('Error saving global tax config:', e);
      }
    }
    calculate();
    populateAdminControls();
    showToast('✅ Global FED & WHT rates synced!');
  });
}

// Card 5: Admin Broadcast Notice
async function adminPublishBroadcast() {
  const msg = document.getElementById('adminBroadcastInput').value.trim();
  if (!msg) {
    showToast('Enter a broadcast notice message first.');
    return;
  }
  await withLoading('Publishing Broadcast Notice...', async () => {
    const broadcastId = 'brd-' + Date.now();
    globalConfig.adminBroadcastMessage = msg;
    globalConfig.adminBroadcastId = broadcastId;

    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          adminBroadcastMessage: msg,
          adminBroadcastId: broadcastId,
          updatedAt: fb.serverTimestamp()
        }, true);
        showToast('📢 Broadcast Notice published to all users!');
      } catch (e) {
        showToast('Error publishing broadcast: ' + e.message);
      }
    }
  });
}

async function adminClearBroadcast() {
  await withLoading('Clearing Broadcast Notice...', async () => {
    document.getElementById('adminBroadcastInput').value = '';
    globalConfig.adminBroadcastMessage = '';
    globalConfig.adminBroadcastId = '';
    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          adminBroadcastMessage: '',
          adminBroadcastId: '',
          updatedAt: fb.serverTimestamp()
        }, true);
        showToast('🗑️ Broadcast Notice cleared.');
      } catch (e) {}
    }
  });
}

async function checkAndShowBroadcastModal() {
  const msg = (globalConfig.adminBroadcastMessage || '').trim();
  const id = (globalConfig.adminBroadcastId || '').trim();
  if (!msg || !id) return;
  const uid = currentUser ? currentUser.uid : 'guest';
  const ackKey = `ack_broadcast_${uid}`;
  const ackedId = await LocalDB.getSetting(ackKey, '');
  if (ackedId !== id) {
    document.getElementById('broadcastModalMessage').textContent = msg;
    openModal('broadcastNoticeModal');
  }
}

async function acknowledgeAdminBroadcast() {
  const uid = currentUser ? currentUser.uid : 'guest';
  const ackKey = `ack_broadcast_${uid}`;
  await LocalDB.setSetting(ackKey, globalConfig.adminBroadcastId || '');
  closeModal('broadcastNoticeModal');
}

// Card 6: App Update & Force Upgrade
async function adminPublishAppUpdate() {
  const url = document.getElementById('adminUpdateUrlInput').value.trim();
  const msg = document.getElementById('adminUpdateMsgInput').value.trim() || 'New KBL App Update Available';
  const updateId = url ? ('upd-' + Date.now()) : '';

  await withLoading('Publishing App Update Notice...', async () => {
    globalConfig.appUpdateUrl = url;
    globalConfig.appUpdateMessage = msg;
    globalConfig.appUpdateId = updateId;

    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          appUpdateUrl: url,
          appUpdateMessage: msg,
          appUpdateId: updateId,
          updatedAt: fb.serverTimestamp()
        }, true);
        updateAppUpdateBanner();
        populateAdminControls();
        showToast('🚀 App Update notice published!');
      } catch (e) {
        showToast('Error publishing update: ' + e.message);
      }
    }
  });
}

async function adminClearAppUpdate() {
  await withLoading('Clearing App Update Link...', async () => {
    document.getElementById('adminUpdateUrlInput').value = '';
    document.getElementById('adminUpdateMsgInput').value = '';
    globalConfig.appUpdateUrl = '';
    globalConfig.appUpdateMessage = '';
    globalConfig.appUpdateId = '';
    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          appUpdateUrl: '', appUpdateMessage: '', appUpdateId: '',
          updatedAt: fb.serverTimestamp()
        }, true);
      } catch (e) {}
    }
    updateAppUpdateBanner();
    populateAdminControls();
    showToast('Cleared App Update link.');
  });
}

function updateAppUpdateBanner() {
  const banner = document.getElementById('appUpdateBanner');
  const txt = document.getElementById('appUpdateBannerText');
  const lnk = document.getElementById('appUpdateBannerLink');
  if (!banner) return;
  if (globalConfig.appUpdateUrl) {
    banner.classList.add('active');
    txt.textContent = `📲 ${globalConfig.appUpdateMessage || 'New App Version Available'}`;
    lnk.href = globalConfig.appUpdateUrl;
  } else {
    banner.classList.remove('active');
  }
}

// Card 7: Loan Facilities & Slabs
function setAdminProductFilter(cat) {
  adminProdFilterCat = cat;
  document.getElementById('adminProdCatAll').className = 'chip-btn' + (cat === 'all' ? ' active' : '');
  document.getElementById('adminProdCatAsset').className = 'chip-btn' + (cat === 'asset' ? ' active' : '');
  document.getElementById('adminProdCatLiab').className = 'chip-btn' + (cat === 'liability' ? ' active-teal' : '');
  renderAdminProductsList();
}

function renderAdminProductsList() {
  const countEl = document.getElementById('adminProdCount');
  if (countEl) countEl.textContent = BANK_PRODUCTS.length;
  const container = document.getElementById('adminProductsList');
  if (!container) return;

  const q = (document.getElementById('adminProdSearchInput')?.value || '').toLowerCase().trim();
  const list = BANK_PRODUCTS.filter(p => {
    const matchCat = adminProdFilterCat === 'all' || p.category === adminProdFilterCat;
    const matchQ = !q || p.name.toLowerCase().includes(q) || String(p.rateType).toLowerCase().includes(q);
    return matchCat && matchQ;
  });

  if (list.length === 0) {
    container.innerHTML = '<div style="padding:14px; text-align:center; color:var(--text-muted); font-size:0.82rem;">No matching products.</div>';
    return;
  }

  container.innerHTML = list.map(p => {
    const isLiab = p.category === 'liability';
    const rateTxt = getProductRateRangeLabel(p);
    return `
      <div class="manage-item">
        <div class="manage-info">
          <h4>${escapeHtml(p.name)} <span class="badge ${isLiab ? 'badge-contacted' : 'badge-new'}">${isLiab ? 'LIABILITY' : 'ASSET'}</span></h4>
          <p>Rate: <strong>${rateTxt}</strong> (${String(p.rateType).toUpperCase()}) • Amount: PKR ${formatPKR(p.minAmount)}-${formatPKR(p.maxAmount)} • Tenure: ${p.minTenure}-${p.maxTenure}M • Age: ${p.minAge || 21}-${p.maxAgeAtMaturity || 65}Y</p>
          <p>Slabs: Rate (${(p.rateSlabs || []).length}) • Fee (${(p.feeSlabs || []).length})</p>
        </div>
        <div style="display: flex; gap: 6px;">
          <button type="button" class="btn btn-outline btn-sm" style="width: auto;" onclick="editProduct('${p.id}')">✏️ Edit</button>
          <button type="button" class="btn btn-outline btn-sm" style="width: auto; color: var(--danger);" onclick="deleteAdminProduct('${p.id}')">🗑</button>
        </div>
      </div>
    `;
  }).join('');
}

function addRateSlabRow(minA = 0, maxA = 1000000, rate = 38.0) {
  const container = document.getElementById('prodRateSlabsContainer');
  const div = document.createElement('div');
  div.className = 'slab-row-editor rate-slab-row';
  div.innerHTML = `
    <input type="number" class="form-input slab-min" style="height:34px;font-size:0.78rem;" placeholder="Min" value="${minA}">
    <input type="number" class="form-input slab-max" style="height:34px;font-size:0.78rem;" placeholder="Max" value="${maxA}">
    <input type="number" step="0.01" class="form-input slab-rate" style="height:34px;font-size:0.78rem;" placeholder="Rate %" value="${rate}">
    <button type="button" class="btn btn-outline btn-sm" style="width:auto;min-height:32px;height:32px;padding:0 8px;color:var(--danger);" onclick="this.parentElement.remove()">✕</button>
  `;
  container.appendChild(div);
}

function addFeeSlabRow(minA = 0, maxA = 1000000, fType = 'percentage', fVal = 1.5, minF = 0, maxF = 0) {
  const container = document.getElementById('prodFeeSlabsContainer');
  const div = document.createElement('div');
  div.className = 'fee-slab-row';
  div.style.cssText = 'display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap:6px; padding:8px; border:1px solid var(--border); border-radius:8px; margin-bottom:6px; background:var(--card-bg);';
  div.innerHTML = `
    <input type="number" class="form-input fslab-min" style="height:32px;font-size:0.75rem;" placeholder="Min Amt" value="${minA}">
    <input type="number" class="form-input fslab-max" style="height:32px;font-size:0.75rem;" placeholder="Max Amt" value="${maxA}">
    <select class="form-select fslab-type" style="height:32px;font-size:0.75rem;">
      <option value="percentage" ${fType === 'percentage' ? 'selected' : ''}>% Percent</option>
      <option value="fixed" ${fType === 'fixed' ? 'selected' : ''}>Fixed PKR</option>
    </select>
    <input type="number" step="0.01" class="form-input fslab-val" style="height:32px;font-size:0.75rem;" placeholder="Value" value="${fVal}">
    <input type="number" class="form-input fslab-minf" style="height:32px;font-size:0.75rem;" placeholder="Min Fee" value="${minF}">
    <div style="display:flex; gap:4px;">
      <input type="number" class="form-input fslab-maxf" style="height:32px;font-size:0.75rem;" placeholder="Max Fee" value="${maxF}">
      <button type="button" class="btn btn-outline btn-sm" style="width:auto;min-height:32px;height:32px;padding:0 8px;color:var(--danger);" onclick="this.closest('.fee-slab-row').remove()">✕</button>
    </div>
  `;
  container.appendChild(div);
}

function openNewProductModal(cat = 'asset') {
  document.getElementById('editProductModalTitle').textContent = cat === 'liability' ? '➕ Add Liability Deposit Product' : '➕ Add Asset Loan Facility';
  document.getElementById('prodEditId').value = 'prod-' + Date.now();
  document.getElementById('prodEditName').value = '';
  document.getElementById('prodEditCategory').value = cat;
  document.getElementById('prodEditRateType').value = cat === 'liability' ? 'fixed' : 'reducing';
  document.getElementById('prodEditRate').value = cat === 'liability' ? '16.5' : '38.0';
  document.getElementById('prodEditFee').value = cat === 'liability' ? '0' : '1.5';
  document.getElementById('prodEditMinAmount').value = cat === 'liability' ? '25000' : '50000';
  document.getElementById('prodEditMaxAmount').value = cat === 'liability' ? '50000000' : '1000000';
  document.getElementById('prodEditMinTenure').value = cat === 'liability' ? '6' : '3';
  document.getElementById('prodEditMaxTenure').value = cat === 'liability' ? '60' : '36';
  document.getElementById('prodEditMinAge').value = '21';
  document.getElementById('prodEditMaxAge').value = '65';
  document.getElementById('prodEditCollateral').value = '';
  document.getElementById('prodRateSlabsContainer').innerHTML = '';
  document.getElementById('prodFeeSlabsContainer').innerHTML = '';
  openModal('editProductModal');
}

function editProduct(productId) {
  const p = BANK_PRODUCTS.find(item => item.id === productId);
  if (!p) return;
  document.getElementById('editProductModalTitle').textContent = '⚙️ Edit: ' + p.name;
  document.getElementById('prodEditId').value = p.id;
  document.getElementById('prodEditName').value = p.name;
  document.getElementById('prodEditCategory').value = p.category || 'asset';
  document.getElementById('prodEditRateType').value = p.rateType || 'reducing';
  document.getElementById('prodEditRate').value = p.defaultAnnualRate ?? 0;
  document.getElementById('prodEditFee').value = p.feePercent ?? p.defaultFeeValue ?? 1.5;
  document.getElementById('prodEditMinAmount').value = p.minAmount ?? 50000;
  document.getElementById('prodEditMaxAmount').value = p.maxAmount ?? 1000000;
  document.getElementById('prodEditMinTenure').value = p.minTenure ?? 3;
  document.getElementById('prodEditMaxTenure').value = p.maxTenure ?? 36;
  document.getElementById('prodEditMinAge').value = p.minAge ?? 21;
  document.getElementById('prodEditMaxAge').value = p.maxAgeAtMaturity ?? 65;
  document.getElementById('prodEditCollateral').value = p.collateralDetails || '';

  const rateCont = document.getElementById('prodRateSlabsContainer');
  rateCont.innerHTML = '';
  (p.rateSlabs || []).forEach(s => addRateSlabRow(s.minAmount, s.maxAmount, s.annualRate));

  const feeCont = document.getElementById('prodFeeSlabsContainer');
  feeCont.innerHTML = '';
  (p.feeSlabs || []).forEach(f => addFeeSlabRow(f.minAmount, f.maxAmount, f.feeType || 'percentage', f.feeValue ?? 1.5, f.minFee || 0, f.maxFee || 0));

  openModal('editProductModal');
}

async function confirmSaveProduct() {
  const pId = document.getElementById('prodEditId').value.trim();
  const name = document.getElementById('prodEditName').value.trim();
  const category = document.getElementById('prodEditCategory').value;
  const rateType = document.getElementById('prodEditRateType').value;
  const rate = parseFloat(document.getElementById('prodEditRate').value) || 0.0;
  const fee = parseFloat(document.getElementById('prodEditFee').value) || 0.0;
  const minAmount = parseInt(document.getElementById('prodEditMinAmount').value, 10) || 0;
  const maxAmount = parseInt(document.getElementById('prodEditMaxAmount').value, 10) || 1000000;
  const minTenure = parseInt(document.getElementById('prodEditMinTenure').value, 10) || 1;
  const maxTenure = parseInt(document.getElementById('prodEditMaxTenure').value, 10) || 36;
  const minAge = parseInt(document.getElementById('prodEditMinAge').value, 10) || 21;
  const maxAgeAtMaturity = parseInt(document.getElementById('prodEditMaxAge').value, 10) || 65;
  const collateralDetails = document.getElementById('prodEditCollateral').value.trim();

  if (!name) {
    showToast('Please enter product name.');
    return;
  }

  const rateSlabs = Array.from(document.querySelectorAll('#prodRateSlabsContainer .rate-slab-row')).map(row => ({
    minAmount: Number(row.querySelector('.slab-min').value) || 0,
    maxAmount: Number(row.querySelector('.slab-max').value) || 0,
    annualRate: Number(row.querySelector('.slab-rate').value) || 0
  }));

  const feeSlabs = Array.from(document.querySelectorAll('#prodFeeSlabsContainer .fee-slab-row')).map(row => ({
    minAmount: Number(row.querySelector('.fslab-min').value) || 0,
    maxAmount: Number(row.querySelector('.fslab-max').value) || 0,
    feeType: row.querySelector('.fslab-type').value || 'percentage',
    feeValue: Number(row.querySelector('.fslab-val').value) || 0,
    minFee: Number(row.querySelector('.fslab-minf').value) || 0,
    maxFee: Number(row.querySelector('.fslab-maxf').value) || 0
  }));

  const existing = BANK_PRODUCTS.find(p => p.id === pId);
  const updated = {
    ...(existing || {}),
    id: pId,
    name,
    category,
    rateType,
    defaultAnnualRate: rate,
    feePercent: fee,
    defaultFeeValue: fee,
    fedRate: globalConfig.fedRate || 16.0,
    minAmount,
    maxAmount,
    minTenure,
    maxTenure,
    minAge,
    maxAgeAtMaturity,
    collateralDetails,
    rateSlabs,
    feeSlabs,
    tenures: existing && Array.isArray(existing.tenures) ? existing.tenures : [6, 12, 18, 24, 36]
  };

  const idx = BANK_PRODUCTS.findIndex(p => p.id === pId);
  if (idx >= 0) BANK_PRODUCTS[idx] = updated;
  else BANK_PRODUCTS.push(updated);

  await withLoading('Saving Product & Slabs...', async () => {
    if (fb) {
      try {
        await fb.setDoc('products', pId, {
          id: pId,
          name,
          category,
          rateType,
          defaultAnnualRate: rate,
          defaultFeeValue: fee,
          feeType: 'percentage',
          fedRate: globalConfig.fedRate || 16.0,
          minAmount,
          maxAmount,
          minTenure,
          maxTenure,
          minAge,
          maxAgeAtMaturity,
          collateralDetails,
          rateSlabs,
          feeSlabs,
          updatedAt: fb.serverTimestamp()
        }, true);
      } catch (e) {
        console.warn('Product save error:', e);
      }
    }

    closeModal('editProductModal');
    populateProducts(pId);
    calculate();
    renderAdminProductsList();
    showToast('✅ Product & Slabs saved to Firestore!');
  });
}

async function deleteAdminProduct(productId) {
  if (!confirm('Delete this product from Firestore globally?')) return;
  await withLoading('Deleting Product...', async () => {
    BANK_PRODUCTS = BANK_PRODUCTS.filter(p => p.id !== productId);
    if (fb) {
      try {
        await fb.deleteDoc('products', productId);
      } catch (e) {}
    }
    populateProducts();
    calculate();
    renderAdminProductsList();
    showToast('🗑️ Product deleted.');
  });
}

function exportProductsJson() {
  const blob = new Blob([JSON.stringify(BANK_PRODUCTS, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `kbl-products-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
}

async function confirmBulkImportProducts() {
  const raw = document.getElementById('bulkImportTextarea').value.trim();
  if (!raw) return;
  await withLoading('Importing Products...', async () => {
    try {
      const arr = JSON.parse(raw);
      if (!Array.isArray(arr)) throw new Error('Expected JSON array.');
      for (const p of arr) {
        const pId = p.id || ('prod-' + Date.now() + '-' + Math.random().toString(36).slice(2, 5));
        if (fb) {
          await fb.setDoc('products', pId, { ...p, id: pId, updatedAt: fb.serverTimestamp() }, true);
        }
      }
      closeModal('bulkImportModal');
      showToast(`✅ Imported ${arr.length} products to Firestore!`);
    } catch (e) {
      showToast('❌ Invalid JSON: ' + e.message);
    }
  });
}

// Card 8: User Access & Branch Rights
async function refreshAdminUsersFromFirestore() {
  if (!fb) return;
  await withLoading('Refreshing User Registry...', async () => {
    try {
      const gDoc = await fb.getDoc('config', 'global');
      const regMap = gDoc.exists() ? { ...(gDoc.data().registeredUsersMap || {}) } : {};
      const usersSnap = await fb.getDocs('users');
      const liveMap = {};
      usersSnap.forEach(d => {
        const u = d.data();
        const uid = u.uid || d.id;
        liveMap[uid] = { uid, ...u };
        const prev = regMap[uid];
        if (!prev || (Number(u.updatedAt) || 0) >= (Number(prev.updatedAt) || 0)) {
          regMap[uid] = { uid, ...u };
        }
      });
      globalConfig.registeredUsersMap = regMap;
      globalConfig.liveUsersCollectionMap = liveMap;
      renderAdminUsersList();
      showToast('🔄 Refreshed user registry');
    } catch (e) {
      showToast('Refresh notice: ' + e.message);
    }
  });
}

function renderAdminUsersList() {
  const users = getAllRegisteredUsersList();
  const countEl = document.getElementById('adminUsersCount');
  if (countEl) countEl.textContent = users.length;

  const container = document.getElementById('adminUsersList');
  if (!container) return;

  const q = (document.getElementById('adminUserSearchInput')?.value || '').toLowerCase().trim();
  const filtered = users.filter(u => {
    if (!q) return true;
    const hay = [u.officerName, u.email, u.staffId, u.branchName, u.allowedSupervisorBranch, u.role].join(' ').toLowerCase();
    return hay.includes(q);
  });

  if (filtered.length === 0) {
    container.innerHTML = '<div style="padding:14px; text-align:center; color:var(--text-muted); font-size:0.82rem;">No matching registered users.</div>';
    return;
  }

  container.innerHTML = filtered.map(u => {
    const isBlocked = u.isBlocked === true;
    const scope = (u.allowedSupervisorBranch || '').toUpperCase() === 'ALL'
      ? 'All Branches'
      : (u.allowedSupervisorBranch || u.branchName || 'Own Branch');
    return `
      <div class="manage-item">
        <div class="manage-info">
          <h4>
            ${escapeHtml(u.officerName || u.email || u.uid)}
            <span class="badge ${isBlocked ? 'badge-lost' : 'badge-contacted'}">${isBlocked ? 'BLOCKED' : 'ALLOWED'}</span>
          </h4>
          <p>${escapeHtml(u.role || 'Loan Officer')} • Staff: ${escapeHtml(u.staffId || 'N/A')} • Home: ${escapeHtml(u.branchName || 'N/A')}</p>
          <p>Supervisor Scope: <strong>${escapeHtml(scope)}</strong> • Filter: ${u.canUseSupervisorFilter !== false ? '✅ Enabled' : '🔒 Disabled'} • ${escapeHtml(u.email || '')}</p>
        </div>
        <div style="display: flex; gap: 6px; flex-wrap: wrap;">
          <button type="button" class="btn ${isBlocked ? 'btn-success' : 'btn-danger'} btn-sm" style="width: auto;" onclick="toggleUserBlockedStatus('${u.uid}')">${isBlocked ? 'Allow User' : 'Block User'}</button>
          <button type="button" class="btn btn-outline btn-sm" style="width: auto;" onclick="openEditUserAccessModal('${u.uid}')">✏️ Rights</button>
          <button type="button" class="btn btn-outline btn-sm" style="width: auto; color: var(--danger);" onclick="deleteAdminUser('${u.uid}')">🗑</button>
        </div>
      </div>
    `;
  }).join('');
}

async function toggleUserBlockedStatus(uid) {
  const users = getAllRegisteredUsersList();
  const user = users.find(u => u.uid === uid);
  if (!user) return;
  const updated = { ...user, isBlocked: !(user.isBlocked === true), updatedAt: Date.now() };
  await withLoading('Updating User Access...', async () => {
    await saveUserAccessRecordToFirestore(updated);
    showToast(updated.isBlocked ? `🚫 Blocked ${updated.officerName}` : `✅ Allowed ${updated.officerName}`);
  });
}

function openEditUserAccessModal(uid = null) {
  const users = getAllRegisteredUsersList();
  const u = uid ? users.find(x => x.uid === uid) : null;
  document.getElementById('editUserModalTitle').textContent = u ? `👥 Edit Rights: ${u.officerName || u.email}` : '➕ Add User Access Record';
  document.getElementById('editUserUid').value = u ? u.uid : ('user-' + Date.now());
  document.getElementById('editUserName').value = u ? (u.officerName || '') : '';
  document.getElementById('editUserStaffId').value = u ? (u.staffId || '') : '';
  document.getElementById('editUserEmail').value = u ? (u.email || '') : '';
  document.getElementById('editUserRole').value = u ? (u.role || 'Loan Officer') : 'Loan Officer';
  document.getElementById('editUserHomeBranch').value = u ? (u.branchName || '') : '';
  document.getElementById('editUserAllowedBranch').value = u ? (u.allowedSupervisorBranch || u.branchName || '') : '';
  document.getElementById('editUserCanSupervisor').checked = u ? (u.canUseSupervisorFilter !== false) : true;
  document.getElementById('editUserIsBlocked').checked = u ? (u.isBlocked === true) : false;
  openModal('editUserAccessModal');
}

async function confirmSaveUserAccess() {
  const uid = document.getElementById('editUserUid').value.trim();
  const home = document.getElementById('editUserHomeBranch').value.trim();
  const allowed = document.getElementById('editUserAllowedBranch').value.trim() || home;
  const userObj = {
    uid,
    officerName: document.getElementById('editUserName').value.trim(),
    staffId: document.getElementById('editUserStaffId').value.trim(),
    email: document.getElementById('editUserEmail').value.trim(),
    role: document.getElementById('editUserRole').value,
    branchName: home,
    allowedSupervisorBranch: allowed,
    canUseSupervisorFilter: document.getElementById('editUserCanSupervisor').checked,
    isBlocked: document.getElementById('editUserIsBlocked').checked,
    updatedAt: Date.now()
  };

  await withLoading('Syncing User Rights...', async () => {
    await saveUserAccessRecordToFirestore(userObj);
    closeModal('editUserAccessModal');
    showToast('✅ User rights synced to Firestore!');
  });
}

async function saveUserAccessRecordToFirestore(userObj) {
  globalConfig.registeredUsersMap[userObj.uid] = userObj;
  if (globalConfig.liveUsersCollectionMap) {
    globalConfig.liveUsersCollectionMap[userObj.uid] = userObj;
  }
  renderAdminUsersList();
  if (fb) {
    try {
      await fb.setDoc('config', 'global', {
        registeredUsersMap: { [userObj.uid]: userObj },
        updatedAt: fb.serverTimestamp()
      }, true);
      await fb.setDoc('users', userObj.uid, userObj, true);
    } catch (e) {
      console.warn('Error syncing user access:', e);
    }
  }
}

async function deleteAdminUser(uid) {
  if (!confirm('Permanently delete this user record from Firestore?')) return;
  await withLoading('Removing User Record...', async () => {
    delete globalConfig.registeredUsersMap[uid];
    if (globalConfig.liveUsersCollectionMap) {
      delete globalConfig.liveUsersCollectionMap[uid];
    }
    renderAdminUsersList();
    if (fb) {
      try {
        await fb.setDoc('config', 'global', {
          registeredUsersMap: { [uid]: fb.deleteField() },
          updatedAt: fb.serverTimestamp()
        }, true);
        await fb.deleteDoc('users', uid);
        showToast('🗑️ User removed from Firestore');
      } catch (e) {
        showToast('Error deleting user: ' + e.message);
      }
    }
  });
}

// Card 9: Lead Database Management
async function getAdminFilteredLeads() {
  const all = await LocalDB.getAllLeads();
  const countEl = document.getElementById('adminLeadsDbCount');
  if (countEl) countEl.textContent = all.length;

  const branchSel = document.getElementById('adminLeadBranchSelect');
  if (branchSel) {
    const prev = branchSel.value;
    const branches = Array.from(new Set(all.map(l => resolveLeadEffectiveBranch(l)).filter(Boolean))).sort();
    branchSel.innerHTML = '<option value="All">All Branches</option>' +
      branches.map(b => `<option value="${escapeHtml(b)}">${escapeHtml(b)}</option>`).join('');
    if (branches.includes(prev)) branchSel.value = prev;
  }

  const q = (document.getElementById('adminLeadSearchInput')?.value || '').toLowerCase().trim();
  const bFilter = branchSel ? branchSel.value : 'All';

  return all.filter(l => {
    const effB = resolveLeadEffectiveBranch(l);
    if (bFilter !== 'All' && effB !== bFilter) return false;
    if (q) {
      const hay = [l.customerName, l.mobile, l.officerName, l.officerId, effB, l.productName].join(' ').toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}

async function renderAdminLeadsDbList() {
  const list = await getAdminFilteredLeads();
  const container = document.getElementById('adminLeadsManageList');
  const lbl = document.getElementById('adminSelectedLeadsLabel');
  if (lbl) lbl.textContent = `Select All Filtered (${list.length}) • Selected: ${adminSelectedLeadIds.size}`;
  if (!container) return;

  if (list.length === 0) {
    container.innerHTML = '<div style="padding:14px; text-align:center; color:var(--text-muted); font-size:0.82rem;">No leads match filter.</div>';
    return;
  }

  container.innerHTML = list.map(l => {
    const checked = adminSelectedLeadIds.has(l.id) ? 'checked' : '';
    return `
      <div class="manage-item">
        <label style="display:flex; align-items:flex-start; gap:10px; cursor:pointer; flex:1;">
          <input type="checkbox" ${checked} onchange="toggleAdminLeadCheck('${l.id}', this.checked)" style="margin-top:4px;">
          <div class="manage-info">
            <h4>${escapeHtml(l.customerName)} (${escapeHtml(l.mobile)})</h4>
            <p>${escapeHtml(l.productName)} • PKR ${formatPKR(l.loanAmount)} • ${escapeHtml(l.officerName || 'RO')} (${escapeHtml(resolveLeadEffectiveBranch(l) || 'Unassigned')})</p>
          </div>
        </label>
        <span class="badge badge-new">${escapeHtml(l.status || 'New')}</span>
      </div>
    `;
  }).join('');
}

function toggleAdminLeadCheck(id, isChecked) {
  if (isChecked) adminSelectedLeadIds.add(id);
  else adminSelectedLeadIds.delete(id);
  renderAdminLeadsDbList();
}

async function toggleAdminSelectAllLeads(isChecked) {
  const list = await getAdminFilteredLeads();
  if (isChecked) list.forEach(l => adminSelectedLeadIds.add(l.id));
  else list.forEach(l => adminSelectedLeadIds.delete(l.id));
  renderAdminLeadsDbList();
}

async function exportAdminFilteredLeadsCsv() {
  await withLoading('Preparing Admin CSV...', async () => {
    const list = await getAdminFilteredLeads();
    downloadLeadsAsCsv(list, `kbl-admin-leads-${new Date().toISOString().slice(0, 10)}.csv`);
  });
}

async function adminDeleteSelectedLeads() {
  if (adminSelectedLeadIds.size === 0) {
    showToast('Select at least one lead first.');
    return;
  }
  const ids = Array.from(adminSelectedLeadIds);
  if (!confirm(`Permanently delete ${ids.length} selected lead(s) from Firestore & local cache?`)) return;
  await withLoading(`Deleting ${ids.length} Lead(s)...`, async () => {
    for (const id of ids) {
      await LocalDB.deleteLead(id);
      if (fb) {
        try { await fb.deleteDoc('leads', id); } catch (e) {}
      }
    }
    adminSelectedLeadIds.clear();
    await renderLeads();
    await renderAdminLeadsDbList();
    updateSettingsStats();
    showToast(`🗑️ Deleted ${ids.length} lead(s)`);
  });
}

async function adminDeleteAllLeads() {
  const all = await LocalDB.getAllLeads();
  if (all.length === 0) return;
  if (!confirm(`⚠️ Permanently delete ALL ${all.length} leads from Firestore? This cannot be undone.`)) return;
  await withLoading('Clearing All Leads...', async () => {
    for (const l of all) {
      await LocalDB.deleteLead(l.id);
      if (fb) {
        try { await fb.deleteDoc('leads', l.id); } catch (e) {}
      }
    }
    adminSelectedLeadIds.clear();
    await renderLeads();
    await renderAdminLeadsDbList();
    updateSettingsStats();
    showToast('🗑️ All leads cleared.');
  });
}
