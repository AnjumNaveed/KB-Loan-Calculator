/**
 * KB Loan Calculator & Lead CRM - Standalone PWA Engine
 * Complete offline-first calculator, IndexedDB persistence, approach track record,
 * and dual-key Google Sheets in-place synchronization.
 */

// 1. BANK PRODUCTS CONFIGURATION (ASSETS & LIABILITIES)
const BANK_PRODUCTS = [
  // ASSET LOAN PRODUCTS
  {
    id: "prod-kmbl-3136",
    category: "asset",
    name: "KPF Gen Pension NonTrans-3136",
    rateType: "reducing",
    defaultAnnualRate: 39.5,
    minAmount: 50000,
    maxAmount: 500000,
    minTenure: 3,
    maxTenure: 36,
    tenures: [6, 12, 18, 24, 30, 36],
    feePercent: 1.5,
    fedRate: 16.0
  },
  {
    id: "prod-kmbl-3138",
    category: "asset",
    name: "KPF Gen Salary NonTrans-3138",
    rateType: "reducing",
    defaultAnnualRate: 39.5,
    minAmount: 50000,
    maxAmount: 500000,
    minTenure: 3,
    maxTenure: 36,
    tenures: [6, 12, 18, 24, 30, 36],
    feePercent: 1.5,
    fedRate: 16.0
  },
  {
    id: "prod-kmbl-3146",
    category: "asset",
    name: "Karobari Sarmaya ENT Un-SEC-3146",
    rateType: "reducing",
    defaultAnnualRate: 38.0,
    minAmount: 50000,
    maxAmount: 1000000,
    minTenure: 3,
    maxTenure: 24,
    tenures: [6, 12, 18, 24],
    feePercent: 1.5,
    fedRate: 16.0
  },
  {
    id: "prod-kmbl-3172",
    category: "asset",
    name: "Karobari ENT Pen non-3172",
    rateType: "reducing",
    defaultAnnualRate: 39.5,
    minAmount: 50000,
    maxAmount: 500000,
    minTenure: 3,
    maxTenure: 36,
    tenures: [6, 12, 18, 24, 30, 36],
    feePercent: 1.5,
    fedRate: 16.0
  },
  {
    id: "prod-kmbl-flat-sample",
    category: "asset",
    name: "Sarmaya Micro Loan (Flat Rate)",
    rateType: "flat",
    defaultAnnualRate: 24.0,
    minAmount: 30000,
    maxAmount: 350000,
    minTenure: 6,
    maxTenure: 24,
    tenures: [6, 12, 18, 24],
    feePercent: 1.25,
    fedRate: 16.0
  },

  // LIABILITY DEPOSIT PRODUCTS
  {
    id: "prod-liab-tdr-1y",
    category: "liability",
    name: "Mahaana Amdan Term Deposit (1 Year)",
    rateType: "fixed",
    defaultAnnualRate: 17.5,
    minAmount: 25000,
    maxAmount: 50000000,
    minTenure: 12,
    maxTenure: 12,
    tenures: [12],
    feePercent: 0,
    fedRate: 0
  },
  {
    id: "prod-liab-tdr-3y",
    category: "liability",
    name: "Karobar Bachat Certificate (3 Years)",
    rateType: "fixed",
    defaultAnnualRate: 16.0,
    minAmount: 50000,
    maxAmount: 50000000,
    minTenure: 36,
    maxTenure: 36,
    tenures: [36],
    feePercent: 0,
    fedRate: 0
  },
  {
    id: "prod-liab-pls-saving",
    category: "liability",
    name: "Standard PLS Savings Account",
    rateType: "fixed",
    defaultAnnualRate: 13.5,
    minAmount: 1000,
    maxAmount: 100000000,
    minTenure: 12,
    maxTenure: 60,
    tenures: [12, 24, 36, 48, 60],
    feePercent: 0,
    fedRate: 0
  }
];

// 2. RECURSIVE DE-NESTING PAYLOAD SANITIZER (Exact replica of Android engine)
function cleanRawActionPayload(raw) {
  if (!raw || typeof raw !== 'string') return '';
  let text = raw.trim();
  let prev = '';
  let iterations = 0;
  while (text !== prev && iterations < 12) {
    prev = text;
    iterations++;
    // Strip trailing officer signatures repeatedly: " - By: Officer Name (ID)"
    text = text.replace(/(?i)\s*-\s*By\s*:\s*[^\-\[\]]+(?:\([^\)]*\))?\s*$/g, '').trim();
    text = text.replace(/(?i)\s*-\s*By\s*:\s*.*$/g, '').trim();
    // Strip leading bullet points and timestamp brackets: e.g. "[2026-11-09 04:01 PM] "
    text = text.replace(/^•?\s*\[[^\]]*\]\s*/g, '').trim();
    // Strip leading action tags and emoji headers
    text = text.replace(/^(STATUS_CHANGE|STATUS|FOLLOW_UP|FOLLOWUP|CALL|PHONE|WHATSAPP|SMS|CREATED|PROPOSAL_UPDATE|PROPOSAL|NOTE_UPDATE|NOTE):\s*/gi, '').trim();
    text = text.replace(/^[🔄📅⏰📞💬✉️🎯📋📝✏️•\s]+/g, '').trim();
    text = text.replace(/^(Note Added|Status Changed|Follow-Up Date|Follow-Up Scheduled|Phone Call|Direct Phone Call|WhatsApp|WhatsApp Quotation|SMS|SMS Quotation|Lead Created|Loan Lead Created|Deposit Lead Created|Proposal Saved|Note \/ Comment Updated|Initial Customer Note):\s*/gi, '').trim();
    text = text.replace(/^(Current:|Note:|Notes:|Initial Note:)\s*/gi, '').trim();
    text = text.replace(/^(Lead status updated from |Lead status updated to |Scheduled follow-up for )/gi, '').trim();
  }
  return text.trim();
}

// 3. INDEXEDDB PERSISTENCE LAYER
class LocalDB {
  static init() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open('KBCalculatorDB', 1);
      request.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains('leads')) {
          db.createObjectStore('leads', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings', { keyPath: 'key' });
        }
      };
      request.onsuccess = (e) => resolve(e.target.result);
      request.onerror = (e) => reject(e);
    });
  }

  static async getAllLeads() {
    const db = await this.init();
    return new Promise((resolve) => {
      const tx = db.transaction('leads', 'readonly');
      const store = tx.objectStore('leads');
      const req = store.getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  }

  static async saveLead(lead) {
    const db = await this.init();
    return new Promise((resolve) => {
      const tx = db.transaction('leads', 'readwrite');
      const store = tx.objectStore('leads');
      store.put(lead);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
    });
  }

  static async getSetting(key, fallback = '') {
    const db = await this.init();
    return new Promise((resolve) => {
      const tx = db.transaction('settings', 'readonly');
      const store = tx.objectStore('settings');
      const req = store.get(key);
      req.onsuccess = () => resolve(req.result ? req.result.value : fallback);
      req.onerror = () => resolve(fallback);
    });
  }

  static async setSetting(key, value) {
    const db = await this.init();
    return new Promise((resolve) => {
      const tx = db.transaction('settings', 'readwrite');
      const store = tx.objectStore('settings');
      store.put({ key, value });
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
    });
  }
}

// 4. APPLICATION STATE & CONTROLLERS
let currentCategory = 'asset';
let currentProduct = BANK_PRODUCTS[0];
let currentEditingLeadId = null;
let currentFollowUpLeadId = null;

// Officer profile defaults
let officerProfile = {
  officerName: 'Anjum Naveed',
  staffId: 'RM-8433',
  branchName: 'Peshawar Branch',
  webhookUrl: ''
};

// Initialize App
window.addEventListener('DOMContentLoaded', async () => {
  await loadSettings();
  populateProducts();
  calculate();
  await renderLeads();
  updateOnlineStatus();

  // Register online/offline listeners
  window.addEventListener('online', () => {
    updateOnlineStatus();
    manualSync();
  });
  window.addEventListener('offline', updateOnlineStatus);

  // Register Service Worker
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').then(() => {
      console.log('PWA Service Worker registered');
    }).catch(err => console.warn('SW registration failed', err));
  }
});

function updateOnlineStatus() {
  const dot = document.getElementById('statusDot');
  const text = document.getElementById('statusText');
  if (navigator.onLine) {
    dot.classList.remove('offline');
    text.textContent = 'Online';
  } else {
    dot.classList.add('offline');
    text.textContent = 'Offline';
  }
}

// Tab Switching
function switchTab(tabId, el) {
  document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  document.getElementById(tabId).classList.add('active');
  el.classList.add('active');
  if (tabId === 'leadsTab') renderLeads();
  if (tabId === 'settingsTab') updateSettingsStats();
}

function setCategory(cat) {
  currentCategory = cat;
  const assetBtn = document.getElementById('catAssetBtn');
  const liabBtn = document.getElementById('catLiabBtn');
  const incomeRow = document.getElementById('incomeRow');
  const filerRow = document.getElementById('filerToggleRow');
  const amortBtn = document.getElementById('amortBtn');
  const amountLabel = document.getElementById('amountLabel');

  if (cat === 'asset') {
    assetBtn.className = 'btn btn-primary';
    liabBtn.className = 'btn btn-outline';
    incomeRow.style.display = 'flex';
    filerRow.style.display = 'none';
    amortBtn.style.display = 'inline-flex';
    amountLabel.textContent = 'Loan Amount (PKR)';
    document.getElementById('loanAmount').value = 500000;
  } else {
    assetBtn.className = 'btn btn-outline';
    liabBtn.className = 'btn btn-primary';
    incomeRow.style.display = 'none';
    filerRow.style.display = 'flex';
    amortBtn.style.display = 'none';
    amountLabel.textContent = 'Deposit Amount (PKR)';
    document.getElementById('loanAmount').value = 500000;
  }
  populateProducts();
  calculate();
}

function populateProducts() {
  const select = document.getElementById('productSelect');
  select.innerHTML = '';
  const filtered = BANK_PRODUCTS.filter(p => p.category === currentCategory);
  filtered.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = `${p.name} (${p.defaultAnnualRate}%)`;
    select.appendChild(opt);
  });
  currentProduct = filtered[0];
  populateTenures();
}

function populateTenures() {
  const tenureSelect = document.getElementById('tenureMonths');
  tenureSelect.innerHTML = '';
  currentProduct.tenures.forEach(m => {
    const opt = document.createElement('option');
    opt.value = m;
    opt.textContent = `${m} Months`;
    tenureSelect.appendChild(opt);
  });
}

function onProductChange() {
  const id = document.getElementById('productSelect').value;
  currentProduct = BANK_PRODUCTS.find(p => p.id === id) || BANK_PRODUCTS[0];
  populateTenures();
  calculate();
}

// 5. CALCULATOR ENGINE
let lastCalculation = null;

function calculate() {
  const amount = parseFloat(document.getElementById('loanAmount').value) || 0;
  const tenure = parseInt(document.getElementById('tenureMonths').value, 10) || 12;
  const monthlyIncome = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const allowedDbr = parseFloat(document.getElementById('allowedDbr').value) || 50;
  const isFiler = document.getElementById('isFilerToggle').checked;

  const rate = currentProduct.defaultAnnualRate;
  document.getElementById('resRate').textContent = `${rate.toFixed(1)}%`;

  if (currentProduct.category === 'asset') {
    // ASSET LOAN CALCULATION
    let emi = 0;
    let totalProfit = 0;
    let totalPayable = 0;

    if (currentProduct.rateType === 'flat') {
      totalProfit = Math.round(amount * (rate / 100.0) * (tenure / 12.0));
      totalPayable = amount + totalProfit;
      emi = Math.round(totalPayable / tenure);
    } else {
      // Reducing rate calculation
      const monthlyRate = (rate / 100.0) / 12.0;
      if (monthlyRate === 0) {
        emi = Math.round(amount / tenure);
        totalPayable = amount;
      } else {
        const factor = Math.pow(1.0 + monthlyRate, tenure);
        emi = Math.round((amount * monthlyRate * factor) / (factor - 1.0));
        totalPayable = emi * tenure;
        totalProfit = totalPayable - amount;
      }
    }

    // Processing Fee (Tiered: e.g. 1.5% + 16% FED)
    const baseFee = Math.round(amount * (currentProduct.feePercent / 100.0));
    const fedFee = Math.round(baseFee * (currentProduct.fedRate / 100.0));
    const totalFee = baseFee + fedFee;

    // DBR Calculation
    const dbrRatio = monthlyIncome > 0 ? ((emi / monthlyIncome) * 100).toFixed(1) : '0';

    // Update DOM
    document.getElementById('emiRow').style.display = 'flex';
    document.getElementById('dbrRow').style.display = 'flex';
    document.getElementById('feeRow').style.display = 'flex';
    document.getElementById('profitRow').style.display = 'flex';
    document.getElementById('rateLabel').textContent = 'Applied Profit Rate:';
    document.getElementById('profitLabel').textContent = 'Total Profit / Markup:';
    document.getElementById('totalPayableLabel').textContent = 'Total Repayable Amount:';

    document.getElementById('resEmi').textContent = formatPKR(emi);
    document.getElementById('resDbr').textContent = `${dbrRatio}% ${dbrRatio > allowedDbr ? '❌ Exceeds DBR!' : '✅ Within Limit'}`;
    document.getElementById('resFee').textContent = formatPKR(totalFee);
    document.getElementById('resProfit').textContent = formatPKR(totalProfit);
    document.getElementById('resTotalPayable').textContent = formatPKR(totalPayable);

    lastCalculation = {
      category: 'asset',
      amount,
      tenure,
      rate,
      emi,
      totalProfit,
      totalFee,
      totalPayable,
      dbrRatio: parseFloat(dbrRatio)
    };
  } else {
    // LIABILITY DEPOSIT CALCULATION
    const taxRate = isFiler ? 15.0 : 40.0;
    const grossTotalProfit = Math.round(amount * (rate / 100.0) * (tenure / 12.0));
    const grossMonthlyProfit = Math.round((amount * (rate / 100.0)) / 12.0);
    const totalTax = Math.round(grossTotalProfit * (taxRate / 100.0));
    const netTotalProfit = grossTotalProfit - totalTax;
    const netMonthlyProfit = Math.round(grossMonthlyProfit * (1.0 - (taxRate / 100.0)));
    const netMaturity = amount + netTotalProfit;

    document.getElementById('emiRow').style.display = 'flex';
    document.getElementById('dbrRow').style.display = 'none';
    document.getElementById('feeRow').style.display = 'flex';
    document.getElementById('profitRow').style.display = 'flex';
    document.getElementById('rateLabel').textContent = 'Annual Profit Rate:';
    document.getElementById('emiRow').children[0].textContent = 'Net Monthly Profit:';
    document.getElementById('feeRow').children[0].textContent = `WHT Tax Deduction (${taxRate}%):`;
    document.getElementById('profitLabel').textContent = 'Net Total Profit:';
    document.getElementById('totalPayableLabel').textContent = 'Net Maturity Amount:';

    document.getElementById('resEmi').textContent = formatPKR(netMonthlyProfit);
    document.getElementById('resFee').textContent = formatPKR(totalTax);
    document.getElementById('resProfit').textContent = formatPKR(netTotalProfit);
    document.getElementById('resTotalPayable').textContent = formatPKR(netMaturity);

    lastCalculation = {
      category: 'liability',
      amount,
      tenure,
      rate,
      netMonthlyProfit,
      totalTax,
      netTotalProfit,
      netMaturity,
      isFiler,
      taxRate
    };
  }
}

// 6. AMORTIZATION SCHEDULE GENERATOR
function openAmortizationModal() {
  if (!lastCalculation || lastCalculation.category !== 'asset') {
    alert('Amortization schedule is available for Asset Loan products.');
    return;
  }
  const tbody = document.getElementById('amortTableBody');
  tbody.innerHTML = '';

  const { amount, tenure, rate, emi } = lastCalculation;
  let balance = amount;
  const monthlyRate = (rate / 100.0) / 12.0;

  for (let m = 1; m <= tenure; m++) {
    const profit = Math.round(balance * monthlyRate);
    const principal = Math.min(balance, emi - profit);
    balance = Math.max(0, balance - principal);

    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td style="text-align: center; font-weight: 700;">${m}</td>
      <td>${formatPKR(emi)}</td>
      <td>${formatPKR(principal)}</td>
      <td>${formatPKR(profit)}</td>
      <td style="font-weight: 700;">${formatPKR(balance)}</td>
    `;
    tbody.appendChild(tr);
    if (balance <= 0) break;
  }
  openModal('amortizationModal');
}

// 7. SAVE LEAD TO LOCAL DB & QUEUE
async function saveLeadFromCalculator() {
  const custName = document.getElementById('customerName').value.trim();
  const mobile = document.getElementById('customerMobile').value.trim();
  const notes = document.getElementById('leadNotes').value.trim();

  if (!custName) {
    alert('Please enter customer name.');
    return;
  }
  if (!mobile) {
    alert('Please enter mobile number.');
    return;
  }

  const existingLeads = await LocalDB.getAllLeads();
  let lead = existingLeads.find(l => l.mobile === mobile || (l.customerName.toLowerCase() === custName.toLowerCase() && l.productName === currentProduct.name));

  const now = Date.now();
  const cleanNote = cleanRawActionPayload(notes);

  if (lead) {
    // Update existing lead in-place
    lead.customerName = custName;
    lead.loanAmount = lastCalculation.amount;
    lead.tenureMonths = lastCalculation.tenure;
    lead.category = currentProduct.category;
    lead.productName = currentProduct.name;
    lead.syncStatus = 'pending_sync';
    if (cleanNote) {
      lead.notes = cleanNote;
      lead.activities.push({
        id: 'act-' + now,
        timestamp: now,
        actionType: 'note_update',
        title: '📝 Note Added',
        details: cleanNote,
        officerName: officerProfile.officerName,
        officerId: officerProfile.staffId
      });
    }
    lead.activities.push({
      id: 'prop-' + now,
      timestamp: now,
      actionType: 'proposal_update',
      title: '📋 Proposal Saved',
      details: `${currentProduct.name} (${formatPKR(lastCalculation.amount)})`,
      officerName: officerProfile.officerName,
      officerId: officerProfile.staffId
    });
  } else {
    // Create new lead
    const id = 'kb-web-' + now + '-' + Math.random().toString(36).substring(2, 6);
    const initialActivities = [];
    if (cleanNote) {
      initialActivities.push({
        id: 'act-' + now,
        timestamp: now,
        actionType: 'note_update',
        title: '📝 Note Added',
        details: cleanNote,
        officerName: officerProfile.officerName,
        officerId: officerProfile.staffId
      });
    }
    initialActivities.push({
      id: 'create-' + now,
      timestamp: now,
      actionType: 'created',
      title: currentProduct.category === 'liability' ? '🎯 Deposit Lead Created' : '🎯 Loan Lead Created',
      details: `${currentProduct.name} (${formatPKR(lastCalculation.amount)})`,
      officerName: officerProfile.officerName,
      officerId: officerProfile.staffId
    });

    lead = {
      id,
      customerName: custName,
      mobile,
      category: currentProduct.category,
      productId: currentProduct.id,
      productName: currentProduct.name,
      loanAmount: lastCalculation.amount,
      tenureMonths: lastCalculation.tenure,
      monthlyEmi: lastCalculation.emi || lastCalculation.netMonthlyProfit || 0,
      totalProfit: lastCalculation.totalProfit || lastCalculation.netTotalProfit || 0,
      processingFee: lastCalculation.totalFee || 0,
      status: 'New',
      followUpDate: '',
      notes: cleanNote,
      officerName: officerProfile.officerName,
      officerId: officerProfile.staffId,
      branchName: officerProfile.branchName,
      createdAt: now,
      syncStatus: 'pending_sync',
      approachCount: 1,
      activities: initialActivities
    };
  }

  await LocalDB.saveLead(lead);
  alert(`✅ Lead for ${custName} saved locally to phone!`);

  // Clear inputs
  document.getElementById('customerName').value = '';
  document.getElementById('customerMobile').value = '';
  document.getElementById('leadNotes').value = '';

  await renderLeads();

  // Try background sync if online
  if (navigator.onLine && officerProfile.webhookUrl) {
    syncLeadToSheet(lead);
  }
}

// 8. RENDER CUSTOMER LEADS LIST
async function renderLeads() {
  const container = document.getElementById('leadsListContainer');
  const countSpan = document.getElementById('leadsCount');
  const search = document.getElementById('searchLeadsInput').value.toLowerCase().trim();
  const filter = document.getElementById('statusFilterSelect').value;

  const leads = await LocalDB.getAllLeads();
  countSpan.textContent = leads.length;

  const filtered = leads.filter(l => {
    const matchesFilter = filter === 'All' || l.status === filter;
    const matchesSearch = !search ||
      l.customerName.toLowerCase().includes(search) ||
      l.mobile.includes(search) ||
      l.productName.toLowerCase().includes(search);
    return matchesFilter && matchesSearch;
  });

  filtered.sort((a, b) => b.createdAt - a.createdAt);

  if (filtered.length === 0) {
    container.innerHTML = `
      <div class="card" style="text-align: center; color: var(--text-muted); padding: 30px;">
        <p style="font-size: 1.1rem; margin-bottom: 6px;">📂 No customer leads found.</p>
        <p style="font-size: 0.8rem;">Saved leads will appear here with offline support and approach logs.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = filtered.map(lead => {
    const cleanNote = cleanRawActionPayload(lead.notes || '');
    const badgeClass = 'badge-' + (lead.status || 'new').toLowerCase().replace(/[^a-z]/g, '');

    return `
      <div class="lead-card">
        <div class="lead-header">
          <div>
            <div class="lead-name">${escapeHtml(lead.customerName)}</div>
            <div class="lead-phone">📞 ${escapeHtml(lead.mobile)} • 🏢 ${escapeHtml(lead.branchName || 'Main')}</div>
          </div>
          <span class="badge ${badgeClass}">${escapeHtml(lead.status || 'New')}</span>
        </div>

        <div style="font-size: 0.82rem; color: var(--primary); font-weight: 600; margin: 4px 0;">
          ${escapeHtml(lead.productName)} • PKR ${formatPKR(lead.loanAmount)} (${lead.tenureMonths}M)
        </div>

        ${cleanNote ? `<div class="lead-notes-box">📝 ${escapeHtml(cleanNote)}</div>` : ''}

        <div style="font-size: 0.75rem; color: var(--text-muted); margin-bottom: 6px;">
          📅 Follow-Up: <strong>${lead.followUpDate ? escapeHtml(lead.followUpDate) : 'None assigned'}</strong>
          • Synced: <strong>${lead.syncStatus === 'synced' ? '✅ Cloud' : '⏳ Local Queue'}</strong>
        </div>

        <div class="lead-actions">
          <button class="btn btn-primary" onclick="initiateCall('${lead.id}', '${lead.mobile}')">📞 Call</button>
          <button class="btn btn-success" onclick="initiateWhatsApp('${lead.id}', '${lead.mobile}', '${escapeHtml(lead.customerName)}', '${escapeHtml(lead.productName)}', ${lead.loanAmount}, ${lead.monthlyEmi})">💬 WhatsApp</button>
          <button class="btn btn-outline" onclick="openTrackRecord('${lead.id}')">📜 History</button>
          <button class="btn btn-outline" onclick="openEditNoteModal('${lead.id}')">📝 Note</button>
          <button class="btn btn-outline" onclick="openFollowUpModal('${lead.id}')">📅 Follow-Up</button>
        </div>
      </div>
    `;
  }).join('');
}

// 9. APPROACH TRACK RECORD TOUCHPOINTS
async function initiateCall(leadId, mobile) {
  await logApproach(leadId, 'call', '📞 Phone Call', `Phone call to ${mobile}`);
  window.location.href = `tel:${mobile}`;
}

async function initiateWhatsApp(leadId, mobile, custName, prodName, amount, emi) {
  const cleanPhone = formatForWhatsApp(mobile);
  const msg = encodeURIComponent(
    `Dear ${custName},\n\nThank you for reaching out to us regarding ${prodName}.\n\n` +
    `• Facility Amount: PKR ${formatPKR(amount)}\n` +
    `• Monthly Installment / Profit: PKR ${formatPKR(emi)}\n\n` +
    `Please let us know if you need any further assistance.\n\nRegards,\n${officerProfile.officerName}\n${officerProfile.branchName}`
  );
  await logApproach(leadId, 'whatsapp', '💬 WhatsApp', `Quotation sent via WhatsApp (${mobile})`);
  window.open(`https://wa.me/${cleanPhone}?text=${msg}`, '_blank');
}

async function logApproach(leadId, type, title, detail) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  const now = Date.now();
  lead.approachCount = (lead.approachCount || 1) + 1;
  lead.syncStatus = 'pending_sync';
  if (!lead.activities) lead.activities = [];

  lead.activities.push({
    id: 'act-' + now,
    timestamp: now,
    actionType: type,
    title,
    details: detail,
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);
  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

// 10. MODAL DIALOGS CONTROLLER
function openModal(id) {
  document.getElementById(id).classList.add('open');
}

function closeModal(id) {
  document.getElementById(id).classList.remove('open');
}

async function openTrackRecord(leadId) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  document.getElementById('trackRecordCustomerHeader').innerHTML = `
    <strong>${escapeHtml(lead.customerName)}</strong> (${escapeHtml(lead.mobile)})<br>
    Facility: ${escapeHtml(lead.productName)} • Status: <strong>${escapeHtml(lead.status)}</strong>
  `;

  const list = document.getElementById('trackRecordList');
  const activities = (lead.activities || []).slice().sort((a, b) => b.timestamp - a.timestamp);

  if (activities.length === 0) {
    list.innerHTML = '<div style="color: var(--text-muted); padding: 16px; text-align: center;">No activity recorded yet.</div>';
  } else {
    list.innerHTML = activities.map(act => {
      const cleanDetail = cleanRawActionPayload(act.details || '');
      const timeStr = formatDate(act.timestamp);
      return `
        <div class="track-card">
          <div class="track-header">
            <span>${escapeHtml(act.title)}</span>
            <span>${timeStr}</span>
          </div>
          <div class="track-detail">${escapeHtml(cleanDetail || act.details)}</div>
          <div class="track-officer">👤 ${escapeHtml(act.officerName || 'Officer')} (${escapeHtml(act.officerId || 'RO')})</div>
        </div>
      `;
    }).join('');
  }
  openModal('trackRecordModal');
}

async function openEditNoteModal(leadId) {
  currentEditingLeadId = leadId;
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  document.getElementById('editNoteCustName').textContent = `Customer: ${lead.customerName}`;
  document.getElementById('editNoteTextarea').value = cleanRawActionPayload(lead.notes || '');
  openModal('editNoteModal');
}

async function confirmSaveNote() {
  if (!currentEditingLeadId) return;
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === currentEditingLeadId);
  if (!lead) return;

  const noteText = document.getElementById('editNoteTextarea').value.trim();
  const cleanNote = cleanRawActionPayload(noteText);
  const now = Date.now();

  lead.notes = cleanNote;
  lead.syncStatus = 'pending_sync';
  if (!lead.activities) lead.activities = [];

  lead.activities.push({
    id: 'act-' + now,
    timestamp: now,
    actionType: 'note_update',
    title: '📝 Note Added',
    details: cleanNote || '(Note cleared)',
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);
  closeModal('editNoteModal');
  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

async function openFollowUpModal(leadId) {
  currentFollowUpLeadId = leadId;
  openModal('followUpModal');
}

async function confirmScheduleFollowUp() {
  if (!currentFollowUpLeadId) return;
  const val = document.getElementById('followUpDateTimeInput').value;
  if (!val) {
    alert('Please pick a date and time.');
    return;
  }
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === currentFollowUpLeadId);
  if (!lead) return;

  const formatted = val.replace('T', ' ');
  const now = Date.now();
  lead.followUpDate = formatted;
  lead.status = 'Follow-Up';
  lead.syncStatus = 'pending_sync';
  if (!lead.activities) lead.activities = [];

  lead.activities.push({
    id: 'act-' + now,
    timestamp: now,
    actionType: 'follow_up',
    title: '📅 Follow-Up Date',
    details: `Scheduled for ${formatted}`,
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);
  closeModal('followUpModal');
  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

async function confirmClearFollowUp() {
  if (!currentFollowUpLeadId) return;
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === currentFollowUpLeadId);
  if (!lead) return;

  lead.followUpDate = '';
  lead.syncStatus = 'pending_sync';
  await LocalDB.saveLead(lead);
  closeModal('followUpModal');
  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

// 11. GOOGLE SHEETS DUAL-KEY IN-PLACE UPSERT SYNC ENGINE
function leadToJson(lead) {
  const dtFormat = formatDate(lead.createdAt || Date.now());

  // Column 17: User notes only (No action tags, no officer names)
  const userNotes = (lead.activities || [])
    .filter(a => (a.actionType === 'note' || a.actionType === 'note_update') && a.details)
    .sort((a, b) => a.timestamp - b.timestamp);

  const noteLines = [];
  userNotes.forEach(act => {
    const clean = cleanRawActionPayload(act.details);
    if (clean) {
      const line = `[${formatDate(act.timestamp)}] ${clean}`;
      if (!noteLines.some(l => l.endsWith(`] ${clean}`))) {
        noteLines.push(line);
      }
    }
  });

  const commentsHistory = noteLines.length > 0 ? noteLines.join('\n') : (lead.notes ? `[${dtFormat}] ${cleanRawActionPayload(lead.notes)}` : '');

  // Column 20: Audit Log chronologically
  const sortedActs = (lead.activities || []).slice().sort((a, b) => a.timestamp - b.timestamp);
  const auditLines = [];
  sortedActs.forEach(act => {
    const cleanPayload = cleanRawActionPayload(act.details);
    const officerName = act.officerName || lead.officerName || officerProfile.officerName;
    const officerId = act.officerId || lead.officerId || officerProfile.staffId;
    const officerPart = officerName ? ` - By: ${officerName}${officerId ? ` (${officerId})` : ''}` : '';
    const desc = cleanPayload && cleanPayload !== act.title ? `${act.title}: ${cleanPayload}` : act.title;
    const line = `[${formatDate(act.timestamp)}] ${desc}${officerPart}`;
    if (!auditLines.includes(line)) auditLines.push(line);
  });

  return {
    id: lead.id,
    customerName: lead.customerName,
    mobile: lead.mobile,
    category: lead.category || 'asset',
    productId: lead.productId,
    productName: lead.productName,
    loanAmount: lead.loanAmount,
    tenureMonths: lead.tenureMonths,
    monthlyEmi: lead.monthlyEmi,
    totalProfit: lead.totalProfit,
    processingFee: lead.processingFee || 0,
    status: lead.status || 'New',
    followUpDate: lead.followUpDate || '',
    notes: commentsHistory,
    officerName: lead.officerName || officerProfile.officerName,
    officerId: lead.officerId || officerProfile.staffId,
    branchName: lead.branchName || officerProfile.branchName,
    createdAtMs: lead.createdAt || Date.now(),
    createdAtFormatted: dtFormat,
    activitiesAudit: auditLines.join('\n')
  };
}

async function syncLeadToSheet(lead) {
  if (!officerProfile.webhookUrl) return;
  const payload = {
    action: 'save_leads',
    leads: [leadToJson(lead)]
  };

  try {
    const resp = await fetch(officerProfile.webhookUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      mode: 'cors'
    });
    if (resp.ok) {
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
      renderLeads();
      updateSettingsStats();
    }
  } catch (err) {
    console.warn('Google Sheets background sync postponed (offline):', err.message);
  }
}

async function manualSync() {
  if (!officerProfile.webhookUrl) {
    alert('Please enter your Google Sheets Webhook URL in Settings first.');
    switchTab('settingsTab', document.querySelectorAll('.nav-item')[2]);
    return;
  }
  if (!navigator.onLine) {
    alert('Device is currently offline. Pending leads are safely cached and will auto-sync when connected.');
    return;
  }

  const badge = document.getElementById('onlineBadge');
  badge.style.opacity = '0.6';
  document.getElementById('statusText').textContent = 'Syncing...';

  const leads = await LocalDB.getAllLeads();
  const pending = leads.filter(l => l.syncStatus !== 'synced');

  try {
    // 1. PUSH PENDING LEADS TO GOOGLE SHEET
    if (pending.length > 0) {
      const payload = {
        action: 'save_leads',
        leads: pending.map(leadToJson)
      };
      await fetch(officerProfile.webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        mode: 'cors'
      });
      for (const l of pending) {
        l.syncStatus = 'synced';
        await LocalDB.saveLead(l);
      }
    }

    // 2. PULL AUTHORIZED LEADS FROM GOOGLE SHEET
    const getResp = await fetch(officerProfile.webhookUrl, { method: 'GET', mode: 'cors' });
    if (getResp.ok) {
      const data = await getResp.json();
      const remoteLeads = data.leads || [];
      for (const r of remoteLeads) {
        // Dual-key match: Match by ID or Mobile
        const local = leads.find(l => l.id === r.id || l.mobile === r.mobile);
        if (!local || local.syncStatus === 'synced') {
          // Parse remote lead cleanly
          const cleanNote = cleanRawActionPayload(r.notes || '');
          const newLead = {
            id: r.id || ('sheet-' + Date.now()),
            customerName: r.customerName || 'Customer',
            mobile: r.mobile || '',
            category: r.category || 'asset',
            productName: r.productName || '',
            loanAmount: parseFloat(r.loanAmount) || 0,
            tenureMonths: parseInt(r.tenureMonths, 10) || 12,
            monthlyEmi: parseFloat(r.monthlyEmi) || 0,
            totalProfit: parseFloat(r.totalProfit) || 0,
            status: r.status || 'New',
            followUpDate: r.followUpDate || '',
            notes: cleanNote,
            officerName: r.officerName || officerProfile.officerName,
            officerId: r.officerId || officerProfile.staffId,
            branchName: r.branchName || officerProfile.branchName,
            createdAt: r.createdAtMs || Date.now(),
            syncStatus: 'synced',
            activities: parseActivitiesFromSheet(r.activitiesAudit, r.notes)
          };
          await LocalDB.saveLead(newLead);
        }
      }
    }
    alert('✅ Google Sheets sync completed successfully!');
  } catch (err) {
    alert('Notice syncing with Google Sheets: ' + err.message);
  } finally {
    badge.style.opacity = '1';
    updateOnlineStatus();
    renderLeads();
    updateSettingsStats();
  }
}

function parseActivitiesFromSheet(auditText, rawNotes) {
  const list = [];
  if (auditText) {
    auditText.split('\n').forEach(line => {
      const clean = cleanRawActionPayload(line);
      if (clean) {
        list.push({
          id: 'sheet-act-' + Math.random().toString(36).substring(2),
          timestamp: Date.now(),
          actionType: line.includes('Note') ? 'note_update' : 'status_change',
          title: line.includes('Note') ? '📝 Note Added' : '🔄 Status Changed',
          details: clean,
          officerName: officerProfile.officerName,
          officerId: officerProfile.staffId
        });
      }
    });
  }
  return list;
}

// 12. SETTINGS & PROFILE CONTROLLER
async function loadSettings() {
  officerProfile.officerName = await LocalDB.getSetting('officerName', 'Anjum Naveed');
  officerProfile.staffId = await LocalDB.getSetting('staffId', 'RM-8433');
  officerProfile.branchName = await LocalDB.getSetting('branchName', 'Peshawar Branch');
  officerProfile.webhookUrl = await LocalDB.getSetting('webhookUrl', '');

  document.getElementById('settingsOfficerName').value = officerProfile.officerName;
  document.getElementById('settingsStaffId').value = officerProfile.staffId;
  document.getElementById('settingsBranch').value = officerProfile.branchName;
  document.getElementById('settingsWebhookUrl').value = officerProfile.webhookUrl;

  document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
  document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName}`;
}

async function saveSettings() {
  officerProfile.officerName = document.getElementById('settingsOfficerName').value.trim() || 'Officer';
  officerProfile.staffId = document.getElementById('settingsStaffId').value.trim() || 'RO';
  officerProfile.branchName = document.getElementById('settingsBranch').value.trim() || 'Main Branch';
  officerProfile.webhookUrl = document.getElementById('settingsWebhookUrl').value.trim();

  await LocalDB.setSetting('officerName', officerProfile.officerName);
  await LocalDB.setSetting('staffId', officerProfile.staffId);
  await LocalDB.setSetting('branchName', officerProfile.branchName);
  await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);

  document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
  document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName}`;
  alert('✅ Settings saved!');
}

async function testConnection() {
  const url = document.getElementById('settingsWebhookUrl').value.trim();
  if (!url) {
    alert('Please enter Webhook URL first.');
    return;
  }
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'ping' }),
      mode: 'cors'
    });
    if (resp.ok) {
      alert('✅ Connected successfully to Google Sheets Web App!');
    } else {
      alert('HTTP Response ' + resp.status + ' from endpoint.');
    }
  } catch (err) {
    alert('Connection test failed: ' + err.message);
  }
}

async function updateSettingsStats() {
  const leads = await LocalDB.getAllLeads();
  document.getElementById('statTotalLeads').textContent = leads.length;
  document.getElementById('statPendingSync').textContent = leads.filter(l => l.syncStatus !== 'synced').length;
}

async function exportLeadsJson() {
  const leads = await LocalDB.getAllLeads();
  const blob = new Blob([JSON.stringify(leads, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `kb-leads-backup-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
}

function showInstallHelp() {
  openModal('installGuideModal');
}

// Helpers
function formatPKR(val) {
  if (isNaN(val) || val === null || val === undefined) return '0';
  return Math.round(val).toLocaleString('en-PK');
}

function formatDate(ts) {
  const d = new Date(ts);
  const pad = (n) => String(n).padStart(2, '0');
  const yyyy = d.getFullYear();
  const mm = pad(d.getMonth() + 1);
  const dd = pad(d.getDate());
  let hh = d.getHours();
  const min = pad(d.getMinutes());
  const ampm = hh >= 12 ? 'PM' : 'AM';
  hh = hh % 12 || 12;
  return `${yyyy}-${mm}-${dd} ${pad(hh)}:${min} ${ampm}`;
}

function formatForWhatsApp(phone) {
  const digits = phone.replace(/\D/g, '');
  if (digits.startsWith('03') && digits.length === 11) return '92' + digits.substring(1);
  if (digits.startsWith('3') && digits.length === 10) return '92' + digits;
  return digits;
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
