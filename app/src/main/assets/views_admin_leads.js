// Bank LoanPro Views: Leads Dashboard, Admin & Amortization Modal

function openScheduleModal(tenure) {
  appState.calc.selectedTenure = tenure;
  appState.scheduleModalOpen = true;
  renderApp();
}

function closeScheduleModal() {
  appState.scheduleModalOpen = false;
  renderApp();
}

function renderAmortizationModal() {
  const p = getSelectedProduct();
  const rate = appState.calc.rateOverride || p.annualRate;
  const tenure = appState.calc.selectedTenure;
  const { emi, totalProfit, totalPayable } = calculateEmi(appState.calc.loanAmount, rate, tenure, p.rateType);
  const feeInfo = calculateProcessingFee(appState.calc.loanAmount, p);
  const schedule = generateAmortizationSchedule(appState.calc.loanAmount, rate, tenure, p.rateType);

  // Early Payment Simulation
  const prepay = appState.prepayment;
  const simResult = simulatePrepayment(
    appState.calc.loanAmount, rate, tenure, p.rateType,
    prepay.amount, prepay.atMonth, prepay.mode
  );

  return `
    <div class="fixed inset-0 bg-slate-900/60 backdrop-blur-xs flex items-center justify-center p-3 z-50 overflow-y-auto">
      <div class="modal-container bg-white w-full max-w-3xl rounded-2xl shadow-2xl border border-slate-200 overflow-hidden my-auto max-h-[90vh] flex flex-col">
        
        <!-- Modal Header -->
        <div class="bg-slate-900 text-white p-4 flex justify-between items-center no-print">
          <div>
            <h3 class="font-bold text-base flex items-center gap-2">
              📜 Loan Amortization Schedule & Simulator
            </h3>
            <p class="text-xs text-slate-300">
              ${p.name} • ${formatCurrency(appState.calc.loanAmount)} • ${tenure} Months @ ${rate}%
            </p>
          </div>
          <button onclick="closeScheduleModal()" class="w-8 h-8 rounded-full bg-slate-800 hover:bg-slate-700 text-slate-300 text-lg flex items-center justify-center">
            ✕
          </button>
        </div>

        <!-- Scrollable Modal Body -->
        <div class="p-4 overflow-y-auto space-y-4" id="amortization-print-container">
          
          <!-- Quotation Header for Printing -->
          <div class="border-b border-slate-200 pb-3">
            <div class="flex justify-between items-start">
              <div>
                <h2 class="text-lg font-black text-slate-900">BANK FINANCING QUOTATION</h2>
                <p class="text-xs text-slate-600">Customer: <b>${appState.calc.customerName || "Walk-in Customer"}</b> (${appState.calc.mobile || "N/A"})</p>
                <p class="text-xs text-slate-600">Date: ${new Date().toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' })}</p>
              </div>
              <div class="text-right">
                <span class="text-xs font-mono bg-blue-100 text-blue-900 px-2 py-0.5 rounded font-bold">${p.rateType.toUpperCase()} RATE</span>
                <p class="text-xs text-slate-500 mt-1">Branch: ${appState.settings.branchName}</p>
                <p class="text-xs text-slate-500">Officer: ${appState.settings.officerName} (${appState.settings.staffId})</p>
              </div>
            </div>

            <!-- Summary KPI Chips -->
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-2 mt-3 text-xs">
              <div class="p-2 bg-slate-50 rounded-lg border border-slate-200">
                <span class="text-slate-500 block">Monthly EMI:</span>
                <b class="text-sm text-blue-900 font-extrabold">${formatCurrency(emi)}</b>
              </div>
              <div class="p-2 bg-slate-50 rounded-lg border border-slate-200">
                <span class="text-slate-500 block">Total Profit:</span>
                <b class="text-sm text-slate-800 font-extrabold">${formatCurrency(totalProfit)}</b>
              </div>
              <div class="p-2 bg-slate-50 rounded-lg border border-slate-200">
                <span class="text-slate-500 block">Processing Fee+FED:</span>
                <b class="text-sm text-slate-800 font-extrabold">${formatCurrency(feeInfo.totalFee)}</b>
              </div>
              <div class="p-2 bg-slate-50 rounded-lg border border-slate-200">
                <span class="text-slate-500 block">Total Payable:</span>
                <b class="text-sm text-emerald-800 font-extrabold">${formatCurrency(totalPayable)}</b>
              </div>
            </div>
          </div>

          <!-- Early Payment / Pre-closure Simulator Box (No-print) -->
          <div class="no-print bg-amber-50/70 border border-amber-200 rounded-xl p-3.5 space-y-3">
            <div class="flex items-center justify-between">
              <h4 class="font-bold text-amber-950 text-xs flex items-center gap-1.5">
                💡 EARLY PAYMENT / PRE-CLOSURE SIMULATOR
              </h4>
              <span class="text-[11px] text-amber-800 font-medium">Test Lump-sum Savings</span>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2 text-xs">
              <div>
                <label class="block font-semibold text-slate-700 mb-1">Prepayment Amount</label>
                <input type="number" step="25000" value="${prepay.amount}"
                  oninput="appState.prepayment.amount = Number(this.value); renderApp();"
                  class="w-full px-2.5 py-1.5 bg-white border border-amber-300 rounded font-bold text-slate-800 outline-none" />
              </div>

              <div>
                <label class="block font-semibold text-slate-700 mb-1">Payment at Month #</label>
                <select onchange="appState.prepayment.atMonth = Number(this.value); renderApp();"
                  class="w-full px-2.5 py-1.5 bg-white border border-amber-300 rounded font-semibold text-slate-800 outline-none">
                  ${schedule.slice(0, -1).map(s => `
                    <option value="${s.month}" ${s.month === prepay.atMonth ? 'selected' : ''}>
                      Month ${s.month} (${s.date})
                    </option>
                  `).join('')}
                </select>
              </div>

              <div>
                <label class="block font-semibold text-slate-700 mb-1">Objective</label>
                <select onchange="appState.prepayment.mode = this.value; renderApp();"
                  class="w-full px-2.5 py-1.5 bg-white border border-amber-300 rounded font-semibold text-slate-800 outline-none">
                  <option value="reduce_emi" ${prepay.mode === 'reduce_emi' ? 'selected' : ''}>Reduce Monthly EMI</option>
                  <option value="reduce_tenure" ${prepay.mode === 'reduce_tenure' ? 'selected' : ''}>Reduce Loan Tenure</option>
                </select>
              </div>
            </div>

            ${simResult ? `
              <div class="bg-white p-3 rounded-lg border border-amber-300 grid grid-cols-2 sm:grid-cols-3 gap-2 text-xs">
                <div>
                  <span class="text-slate-500 block">Total Profit Saved:</span>
                  <span class="font-extrabold text-sm text-emerald-600">${formatCurrency(simResult.profitSaved)}</span>
                </div>
                <div>
                  <span class="text-slate-500 block">${prepay.mode === 'reduce_emi' ? 'Revised Monthly EMI:' : 'Months Saved:'}</span>
                  <span class="font-extrabold text-sm text-blue-900">
                    ${prepay.mode === 'reduce_emi' ? formatCurrency(simResult.newEmi) : `${simResult.monthsSaved} Months`}
                  </span>
                </div>
                <div class="col-span-2 sm:col-span-1">
                  <span class="text-slate-500 block">New End Tenure:</span>
                  <span class="font-bold text-slate-800">${simResult.newTenure} Months</span>
                </div>
              </div>
            ` : ''}
          </div>

          <!-- Schedule Table -->
          <div>
            <h4 class="font-bold text-slate-800 text-xs mb-2">COMPLETE MONTHLY AMORTIZATION SCHEDULE</h4>
            <div class="table-scroll border border-slate-200 rounded-lg max-h-72 overflow-y-auto">
              <table class="w-full text-left border-collapse text-[11px]">
                <thead class="bg-slate-100 text-slate-700 sticky top-0 font-bold border-b border-slate-200">
                  <tr>
                    <th class="p-2">Mo #</th>
                    <th class="p-2">Due Date</th>
                    <th class="p-2">Opening</th>
                    <th class="p-2">Principal</th>
                    <th class="p-2">Profit / Mark-up</th>
                    <th class="p-2">Total EMI</th>
                    <th class="p-2">Closing Balance</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-100">
                  ${schedule.map(row => `
                    <tr class="hover:bg-slate-50">
                      <td class="p-2 font-mono font-semibold">${row.month}</td>
                      <td class="p-2 text-slate-600">${row.date}</td>
                      <td class="p-2 text-slate-600">${formatCurrency(row.opening)}</td>
                      <td class="p-2 font-medium text-emerald-800">${formatCurrency(row.principal)}</td>
                      <td class="p-2 font-medium text-amber-800">${formatCurrency(row.profit)}</td>
                      <td class="p-2 font-bold text-blue-900">${formatCurrency(row.emi)}</td>
                      <td class="p-2 font-mono text-slate-700">${formatCurrency(row.closing)}</td>
                    </tr>
                  `).join('')}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- Modal Footer Actions (No-print) -->
        <div class="bg-slate-100 p-3 flex flex-wrap justify-between items-center gap-2 border-t border-slate-200 no-print">
          <div class="flex space-x-2">
            <button onclick="printAmortization()" class="px-3 py-2 bg-slate-800 hover:bg-slate-900 text-white rounded-lg text-xs font-bold flex items-center gap-1.5 shadow-xs">
              <span>🖨️</span> Print / PDF
            </button>
            <button onclick="shareViaWhatsApp()" class="px-3 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold flex items-center gap-1.5 shadow-xs">
              <span>💬</span> Share WhatsApp
            </button>
          </div>
          <button onclick="closeScheduleModal()" class="px-4 py-2 bg-slate-300 hover:bg-slate-400 text-slate-800 rounded-lg text-xs font-bold">
            Close
          </button>
        </div>

      </div>
    </div>
  `;
}

function renderLeadsScreen() {
  const leads = appState.leads;
  const totalAmount = leads.reduce((sum, l) => sum + (Number(l.loanAmount) || 0), 0);
  const newCount = leads.filter(l => l.status === "New").length;
  const convertedCount = leads.filter(l => l.status === "Converted").length;

  return `
    <div class="space-y-4">
      <!-- Pipeline Header Stats -->
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-2">
        <div class="bg-white p-3 rounded-2xl border border-slate-200 shadow-xs">
          <span class="text-xs text-slate-500 font-medium">Total Pipeline</span>
          <p class="text-base font-black text-blue-900">${formatCurrency(totalAmount)}</p>
        </div>
        <div class="bg-white p-3 rounded-2xl border border-slate-200 shadow-xs">
          <span class="text-xs text-slate-500 font-medium">Total Leads</span>
          <p class="text-base font-black text-slate-800">${leads.length}</p>
        </div>
        <div class="bg-white p-3 rounded-2xl border border-slate-200 shadow-xs">
          <span class="text-xs text-slate-500 font-medium">New Enquiries</span>
          <p class="text-base font-black text-amber-600">${newCount}</p>
        </div>
        <div class="bg-white p-3 rounded-2xl border border-slate-200 shadow-xs">
          <span class="text-xs text-slate-500 font-medium">Converted</span>
          <p class="text-base font-black text-emerald-600">${convertedCount}</p>
        </div>
      </div>

      <!-- Actions Bar -->
      <div class="bg-white p-3 rounded-2xl border border-slate-200 shadow-xs flex flex-wrap justify-between items-center gap-2">
        <div class="flex-1 min-w-[200px]">
          <input type="text" id="lead-search-input" placeholder="Search customer, phone or product..."
            oninput="filterLeads(this.value)"
            class="w-full px-3 py-1.5 text-xs bg-slate-50 border border-slate-300 rounded-lg outline-none focus:ring-2 focus:ring-blue-600" />
        </div>
        <div class="flex items-center space-x-2">
          <button onclick="exportLeadsToCsv()" class="px-3 py-1.5 bg-emerald-700 hover:bg-emerald-800 text-white rounded-lg text-xs font-bold flex items-center gap-1">
            <span>📥</span> Export CSV
          </button>
        </div>
      </div>

      <!-- Leads List -->
      <div class="space-y-3" id="leads-container">
        ${leads.map(lead => renderLeadCard(lead)).join('')}
        ${leads.length === 0 ? `
          <div class="bg-white rounded-2xl p-8 text-center text-slate-500 border border-slate-200">
            No leads recorded yet. Go to Calculator and click "Save Lead".
          </div>
        ` : ''}
      </div>
    </div>
  `;
}

function renderLeadCard(lead) {
  const statusColors = {
    New: "bg-blue-100 text-blue-800 border-blue-300",
    Contacted: "bg-amber-100 text-amber-800 border-amber-300",
    Converted: "bg-emerald-100 text-emerald-800 border-emerald-300",
    Lost: "bg-rose-100 text-rose-800 border-rose-300"
  };

  return `
    <div class="bg-white rounded-2xl p-4 border border-slate-200 shadow-xs space-y-3">
      <div class="flex justify-between items-start">
        <div>
          <h3 class="font-extrabold text-slate-900 text-sm flex items-center gap-2">
            ${lead.customerName}
            <span class="text-[10px] px-2 py-0.5 rounded-full font-bold border ${statusColors[lead.status] || 'bg-slate-100'}">
              ${lead.status}
            </span>
          </h3>
          <p class="text-xs text-slate-500 mt-0.5">
            📞 ${lead.mobile} • Age: ${lead.age} • Net Inc: ${formatCurrency(lead.monthlyIncome)}
          </p>
        </div>

        <div class="text-right">
          <p class="text-sm font-black text-blue-900">${formatCurrency(lead.loanAmount)}</p>
          <span class="text-[10px] text-slate-400 font-mono">${new Date(lead.createdAt).toLocaleDateString()}</span>
        </div>
      </div>

      <div class="bg-slate-50 p-2.5 rounded-xl border border-slate-200 grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        <div>
          <span class="text-slate-400 block text-[10px]">Product</span>
          <span class="font-semibold text-slate-800">${lead.productName}</span>
        </div>
        <div>
          <span class="text-slate-400 block text-[10px]">Tenure</span>
          <span class="font-semibold text-slate-800">${lead.tenureMonths} Months</span>
        </div>
        <div>
          <span class="text-slate-400 block text-[10px]">Monthly EMI</span>
          <span class="font-bold text-blue-900">${formatCurrency(lead.monthlyEmi)}</span>
        </div>
        <div>
          <span class="text-slate-400 block text-[10px]">Sync Status</span>
          <span class="font-semibold ${lead.syncStatus === 'synced' ? 'text-emerald-600' : 'text-amber-600'}">
            ${lead.syncStatus === 'synced' ? '☁️ Synced' : '⏳ Pending'}
          </span>
        </div>
      </div>

      ${lead.notes ? `
        <p class="text-xs text-slate-600 bg-amber-50/50 p-2 rounded-lg border border-amber-100">
          📝 <i>${lead.notes}</i>
        </p>
      ` : ''}

      <!-- Lead Actions -->
      <div class="flex flex-wrap justify-between items-center pt-1 border-t border-slate-100 gap-2">
        <div class="flex items-center space-x-1.5">
          <label class="text-[11px] text-slate-500 font-medium">Status:</label>
          <select onchange="updateLeadStatus('${lead.id}', this.value)" class="text-xs bg-slate-100 border border-slate-300 rounded px-2 py-1 font-semibold outline-none">
            <option value="New" ${lead.status === 'New' ? 'selected' : ''}>New</option>
            <option value="Contacted" ${lead.status === 'Contacted' ? 'selected' : ''}>Contacted</option>
            <option value="Converted" ${lead.status === 'Converted' ? 'selected' : ''}>Converted</option>
            <option value="Lost" ${lead.status === 'Lost' ? 'selected' : ''}>Lost</option>
          </select>
        </div>

        <div class="flex space-x-1.5">
          <a href="tel:${lead.mobile}" class="px-2.5 py-1 bg-slate-100 hover:bg-slate-200 text-slate-700 rounded text-xs font-bold">
            📞 Call
          </a>
          <button onclick="shareLeadWhatsApp('${lead.id}')" class="px-2.5 py-1 bg-emerald-600 hover:bg-emerald-700 text-white rounded text-xs font-bold">
            💬 WhatsApp
          </button>
          <button onclick="deleteLead('${lead.id}')" class="px-2 py-1 text-rose-600 hover:bg-rose-50 rounded text-xs font-bold">
            🗑️
          </button>
        </div>
      </div>
    </div>
  `;
}

function updateLeadStatus(id, newStatus) {
  const l = appState.leads.find(x => x.id === id);
  if (l) {
    l.status = newStatus;
    saveAllState();
    renderApp();
  }
}

function deleteLead(id) {
  if (confirm("Delete this lead permanently?")) {
    appState.leads = appState.leads.filter(l => l.id !== id);
    appState.syncQueue = appState.syncQueue.filter(l => l.id !== id);
    saveAllState();
    renderApp();
  }
}

function shareLeadWhatsApp(id) {
  const l = appState.leads.find(x => x.id === id);
  if (!l) return;
  const text = `Dear ${l.customerName}, following up regarding your ${l.productName} calculation: Amount: ${formatCurrency(l.loanAmount)}, Tenure: ${l.tenureMonths} Mo, Monthly EMI: ${formatCurrency(l.monthlyEmi)}. Let us know when to proceed with documentation. Best regards, ${appState.settings.officerName}`;
  const phone = l.mobile.replace(/[^0-9]/g, "");
  const url = `https://wa.me/${phone}?text=${encodeURIComponent(text)}`;
  window.open(url, "_blank");
}

function filterLeads(query) {
  const term = query.toLowerCase();
  const filtered = appState.leads.filter(l => 
    l.customerName.toLowerCase().includes(term) ||
    l.mobile.includes(term) ||
    l.productName.toLowerCase().includes(term)
  );
  const container = document.getElementById("leads-container");
  if (container) {
    container.innerHTML = filtered.map(lead => renderLeadCard(lead)).join('') || 
      `<div class="p-6 text-center text-slate-400 text-xs">No matching leads found.</div>`;
  }
}
