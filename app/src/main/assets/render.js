// Bank LoanPro DOM Rendering Components

function renderApp() {
  const root = document.getElementById("app-root");
  if (!root) return;

  const queueCount = appState.syncQueue.length;
  const p = getSelectedProduct();
  const age = calculateAge(appState.calc.dob);
  
  // DBR Engine Calculations
  const monthlyIncome = Number(appState.calc.monthlyIncome) || 0;
  const allowedDbrPercent = Number(appState.calc.allowedDbr) || 50;
  const existingObligations = Number(appState.calc.existingObligations) || 0;
  const maxTotalAllowedEmi = Math.round((monthlyIncome * allowedDbrPercent) / 100);
  const maxEligibleEmi = Math.max(0, maxTotalAllowedEmi - existingObligations);

  root.innerHTML = `
    <!-- Top Header -->
    <header class="bg-slate-900 text-white shadow-md sticky top-0 z-40">
      <div class="max-w-4xl mx-auto px-4 py-3 flex items-center justify-between">
        <div class="flex items-center space-x-3">
          <div class="w-10 h-10 rounded-xl bg-gradient-to-tr from-blue-700 to-amber-500 flex items-center justify-center font-bold text-lg text-white shadow">
            🏛️
          </div>
          <div>
            <h1 class="font-bold text-lg leading-tight flex items-center gap-2">
              LoanPro <span class="text-xs bg-blue-800 text-blue-200 px-2 py-0.5 rounded font-mono">BANK SUITE</span>
            </h1>
            <p class="text-xs text-slate-400">${appState.settings.branchName} • ${appState.settings.officerName}</p>
          </div>
        </div>

        <!-- Status & Sync Badge -->
        <div class="flex items-center space-x-2">
          <button onclick="syncWithBackend()" class="flex items-center space-x-1.5 px-2.5 py-1 rounded-full text-xs font-medium border ${
            appState.online ? 'bg-emerald-950/60 border-emerald-500/40 text-emerald-300' : 'bg-amber-950/60 border-amber-500/40 text-amber-300'
          }">
            <span class="w-2 h-2 rounded-full ${appState.online ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'}"></span>
            <span>${appState.online ? 'Online' : 'Offline'}</span>
            ${queueCount > 0 ? `<span class="bg-rose-500 text-white px-1.5 py-0.2 rounded-full text-[10px] font-bold">${queueCount}</span>` : ''}
          </button>

          <button onclick="toggleAdminView()" class="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300">
            🔒
          </button>
        </div>
      </div>

      <!-- Navigation Tabs -->
      <nav class="flex border-t border-slate-800 bg-slate-950/70 text-sm">
        <button onclick="switchTab('calc')" class="flex-1 py-2.5 text-center font-medium transition-colors ${
          appState.activeTab === 'calc' ? 'text-amber-400 border-b-2 border-amber-400 bg-slate-900/90' : 'text-slate-400 hover:text-white'
        }">
          🧮 Calculator
        </button>
        <button onclick="switchTab('leads')" class="flex-1 py-2.5 text-center font-medium transition-colors relative ${
          appState.activeTab === 'leads' ? 'text-amber-400 border-b-2 border-amber-400 bg-slate-900/90' : 'text-slate-400 hover:text-white'
        }">
          📋 Leads (${appState.leads.length})
          ${queueCount > 0 ? `<span class="absolute top-2 right-4 w-2 h-2 rounded-full bg-rose-500"></span>` : ''}
        </button>
        <button onclick="switchTab('admin')" class="flex-1 py-2.5 text-center font-medium transition-colors ${
          appState.activeTab === 'admin' ? 'text-amber-400 border-b-2 border-amber-400 bg-slate-900/90' : 'text-slate-400 hover:text-white'
        }">
          ⚙️ Admin
        </button>
      </nav>
    </header>

    <!-- Main Container -->
    <main class="max-w-4xl mx-auto p-4 pb-24">
      ${appState.activeTab === 'calc' ? renderCalcScreen(p, age, maxTotalAllowedEmi, maxEligibleEmi) : ''}
      ${appState.activeTab === 'leads' ? renderLeadsScreen() : ''}
      ${appState.activeTab === 'admin' ? renderAdminScreen() : ''}
    </main>

    <!-- Modals -->
    ${appState.scheduleModalOpen ? renderAmortizationModal() : ''}
    ${appState.adminAuthModalOpen ? renderAdminAuthModal() : ''}
    ${appState.productModalOpen ? renderProductModal() : ''}
  `;
}

function switchTab(tab) {
  if (tab === 'admin' && !appState.isAdminUnlocked) {
    appState.adminAuthModalOpen = true;
    renderApp();
    return;
  }
  appState.activeTab = tab;
  renderApp();
}

function toggleAdminView() {
  if (appState.isAdminUnlocked) {
    appState.isAdminUnlocked = false;
    showToast("Admin session locked", "info");
    if (appState.activeTab === 'admin') appState.activeTab = 'calc';
  } else {
    appState.adminAuthModalOpen = true;
  }
  renderApp();
}

function renderCalcScreen(product, customerAge, maxTotalAllowedEmi, maxEligibleEmi) {
  const rate = appState.calc.rateOverride || product.annualRate;
  const feeInfo = calculateProcessingFee(appState.calc.loanAmount, product);
  const isAgeValidStart = customerAge >= product.minAge;
  const isDbrBreached = Number(appState.calc.existingObligations) >= maxTotalAllowedEmi;

  return `
    <div class="space-y-4">
      <!-- Customer Information Card -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="font-bold text-slate-800 text-sm tracking-wide flex items-center gap-1.5">
            👤 CUSTOMER PROFILE
          </h2>
          <span class="text-xs ${isAgeValidStart ? 'text-emerald-700 bg-emerald-50' : 'text-rose-700 bg-rose-50'} px-2 py-0.5 rounded-full font-medium">
            Age: ${customerAge} yrs (${isAgeValidStart ? 'Eligible' : 'Under Min Age ' + product.minAge})
          </span>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
          <div>
            <label class="block text-xs font-semibold text-slate-600 mb-1">Customer Full Name *</label>
            <input type="text" id="calc-cust-name" value="${appState.calc.customerName}" 
              oninput="appState.calc.customerName = this.value" placeholder="e.g. Asad Ullah Khan"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 focus:bg-white outline-none" />
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-600 mb-1">Mobile Phone (WhatsApp) *</label>
            <input type="tel" id="calc-cust-phone" value="${appState.calc.mobile}"
              oninput="appState.calc.mobile = this.value" placeholder="+92 300 1234567"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 focus:bg-white outline-none" />
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-600 mb-1">Date of Birth</label>
            <input type="date" value="${appState.calc.dob}" 
              onchange="appState.calc.dob = this.value; renderApp();"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 focus:bg-white outline-none" />
          </div>
        </div>
      </div>

      <!-- Facility & Amount Selection Card -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4">
        <h2 class="font-bold text-slate-800 text-sm tracking-wide mb-3 flex items-center justify-between">
          <span>💼 LOAN FACILITY & PRICING</span>
          <span class="text-xs font-mono font-normal text-slate-500">Max Tenure: ${product.maxTenure} Mo</span>
        </h2>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
          <div>
            <label class="block text-xs font-semibold text-slate-600 mb-1">Loan Product</label>
            <select onchange="appState.calc.productId = this.value; appState.calc.rateOverride = null; renderApp();"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg font-medium text-slate-800 focus:ring-2 focus:ring-blue-600 outline-none">
              ${appState.products.map(p => `
                <option value="${p.id}" ${p.id === product.id ? 'selected' : ''}>
                  ${p.name} (${p.annualRate}% ${p.rateType === 'reducing' ? 'Reducing' : 'Flat'})
                </option>
              `).join('')}
            </select>
          </div>

          <div>
            <div class="flex justify-between items-center mb-1">
              <label class="text-xs font-semibold text-slate-600">Annual Interest Rate (%)</label>
              <span class="text-[11px] text-blue-700 bg-blue-50 px-1.5 py-0.2 rounded font-semibold uppercase">${product.rateType} balance</span>
            </div>
            <input type="number" step="0.25" value="${rate}"
              oninput="appState.calc.rateOverride = Number(this.value); renderApp();"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 outline-none font-semibold text-slate-800" />
          </div>
        </div>

        <!-- Desired Loan Amount with Slider & Quick Chips -->
        <div class="bg-slate-50 p-3.5 rounded-xl border border-slate-200">
          <div class="flex justify-between items-baseline mb-1">
            <label class="text-xs font-bold text-slate-700">Desired Loan Amount</label>
            <span class="text-base font-extrabold text-blue-900">${formatCurrency(appState.calc.loanAmount)}</span>
          </div>

          <input type="range" min="${product.minAmount}" max="${product.maxAmount}" step="25000" value="${appState.calc.loanAmount}"
            oninput="appState.calc.loanAmount = Number(this.value); renderApp();"
            class="w-full h-2 bg-slate-200 rounded-lg appearance-none cursor-pointer accent-blue-700 my-2" />

          <div class="flex justify-between text-[11px] text-slate-500 font-mono mb-2">
            <span>Min: ${formatCurrency(product.minAmount)}</span>
            <span>Max: ${formatCurrency(product.maxAmount)}</span>
          </div>

          <div class="flex flex-wrap gap-1.5">
            <span class="text-[11px] text-slate-400 self-center">Quick Adjust:</span>
            <button onclick="appState.calc.loanAmount = Math.max(${product.minAmount}, appState.calc.loanAmount - 100000); renderApp();" class="text-xs px-2 py-1 bg-white border border-slate-200 rounded shadow-xs font-medium text-slate-700 hover:bg-slate-100">-100k</button>
            <button onclick="appState.calc.loanAmount = Math.min(${product.maxAmount}, appState.calc.loanAmount + 100000); renderApp();" class="text-xs px-2 py-1 bg-white border border-slate-200 rounded shadow-xs font-medium text-slate-700 hover:bg-slate-100">+100k</button>
            <button onclick="appState.calc.loanAmount = Math.min(${product.maxAmount}, appState.calc.loanAmount + 500000); renderApp();" class="text-xs px-2 py-1 bg-white border border-slate-200 rounded shadow-xs font-medium text-slate-700 hover:bg-slate-100">+500k</button>
            <button onclick="appState.calc.loanAmount = Math.min(${product.maxAmount}, appState.calc.loanAmount + 1000000); renderApp();" class="text-xs px-2 py-1 bg-white border border-slate-200 rounded shadow-xs font-medium text-slate-700 hover:bg-slate-100">+1M</button>
          </div>
        </div>

        <!-- Processing Fee & FED info -->
        <div class="mt-3 flex justify-between items-center text-xs text-slate-600 bg-blue-50/70 p-2.5 rounded-lg border border-blue-100">
          <span>Fee: ${formatCurrency(feeInfo.baseFee)} (${product.feeType === 'fixed' ? 'Fixed' : product.feeValue + '%'}) + 16% FED (${formatCurrency(feeInfo.fedAmount)})</span>
          <span class="font-bold text-blue-900">Total Upfront: ${formatCurrency(feeInfo.totalFee)}</span>
        </div>
      </div>

      <!-- Financial & DBR Eligibility Engine Card -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4">
        <h2 class="font-bold text-slate-800 text-sm tracking-wide mb-3 flex items-center justify-between">
          <span>📊 DEBT BURDEN RATIO (DBR) ELIGIBILITY</span>
          <span class="text-xs font-semibold px-2 py-0.5 rounded ${isDbrBreached ? 'bg-rose-100 text-rose-800' : 'bg-emerald-100 text-emerald-800'}">
            ${isDbrBreached ? '⚠️ DBR EXCEEDED' : '✅ CAPACITY AVAILABLE'}
          </span>
        </h2>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-3 mb-3">
          <div>
            <label class="block text-xs font-semibold text-slate-600 mb-1">Monthly Net Income *</label>
            <input type="number" step="5000" value="${appState.calc.monthlyIncome}"
              oninput="appState.calc.monthlyIncome = Number(this.value); renderApp();"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 font-bold outline-none" />
          </div>

          <div>
            <div class="flex justify-between items-center mb-1">
              <label class="text-xs font-semibold text-slate-600">Allowed DBR (%)</label>
              <div class="flex space-x-1">
                ${[35, 40, 50, 60].map(p => `
                  <button onclick="appState.calc.allowedDbr = ${p}; renderApp();" class="text-[10px] px-1.5 py-0.5 rounded font-bold ${
                    appState.calc.allowedDbr === p ? 'bg-blue-700 text-white' : 'bg-slate-200 text-slate-700'
                  }">${p}%</button>
                `).join('')}
              </div>
            </div>
            <input type="number" value="${appState.calc.allowedDbr}"
              oninput="appState.calc.allowedDbr = Number(this.value); renderApp();"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 font-bold outline-none" />
          </div>

          <div>
            <label class="block text-xs font-semibold text-slate-600 mb-1">Existing Obligations / EMIs</label>
            <input type="number" step="1000" value="${appState.calc.existingObligations}"
              oninput="appState.calc.existingObligations = Number(this.value); renderApp();"
              class="w-full px-3 py-2 text-sm bg-slate-50 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-600 font-bold outline-none" />
          </div>
        </div>

        <!-- DBR Calculated Banner -->
        <div class="p-3 rounded-xl bg-gradient-to-r from-slate-900 to-blue-950 text-white flex flex-col md:flex-row justify-between items-center gap-2">
          <div>
            <p class="text-xs text-slate-300">Max Allowable Monthly Installment (Capacity):</p>
            <p class="text-lg font-black text-amber-400">
              ${formatCurrency(maxEligibleEmi)} / month
            </p>
          </div>
          <div class="text-xs text-right text-slate-300">
            Formula: (${formatCurrency(monthlyIncome)} × ${allowedDbrPercent}%) - ${formatCurrency(existingObligations)}
          </div>
        </div>
      </div>

      <!-- Interactive Eligibility Matrix (1 to 120 Months) -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4">
        <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2 mb-3">
          <div>
            <h2 class="font-bold text-slate-800 text-sm tracking-wide">
              ⚡ ELIGIBILITY MATRIX & TENURE COMPARISON
            </h2>
            <p class="text-xs text-slate-500">Tap any tenure to inspect the Amortization Schedule & simulate early payments.</p>
          </div>
        </div>

        <div class="table-scroll border border-slate-200 rounded-xl overflow-hidden">
          <table class="w-full text-left border-collapse text-xs">
            <thead>
              <tr class="bg-slate-100 text-slate-700 font-bold border-b border-slate-200">
                <th class="p-2.5">Tenure</th>
                <th class="p-2.5">Monthly EMI</th>
                <th class="p-2.5">Total Profit</th>
                <th class="p-2.5">Total Payable</th>
                <th class="p-2.5">DBR Status</th>
                <th class="p-2.5 text-center">Action</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              ${renderTenureRows(product, rate, customerAge, maxEligibleEmi)}
            </tbody>
          </table>
        </div>
      </div>

      <!-- Action Proposal Toolbar -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4">
        <h3 class="font-bold text-slate-800 text-xs tracking-wider uppercase mb-2">Lead Communication & Dispatch</h3>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-2">
          <button onclick="shareViaWhatsApp()" class="flex items-center justify-center gap-1.5 px-3 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white rounded-xl text-xs font-bold shadow-xs">
            <span>💬</span> WhatsApp
          </button>
          <button onclick="shareViaSms()" class="flex items-center justify-center gap-1.5 px-3 py-2.5 bg-sky-600 hover:bg-sky-700 text-white rounded-xl text-xs font-bold shadow-xs">
            <span>✉️</span> SMS
          </button>
          <button onclick="copyProposalText()" class="flex items-center justify-center gap-1.5 px-3 py-2.5 bg-slate-700 hover:bg-slate-800 text-white rounded-xl text-xs font-bold shadow-xs">
            <span>📋</span> Copy Text
          </button>
          <button onclick="saveCurrentLead()" class="flex items-center justify-center gap-1.5 px-3 py-2.5 bg-blue-700 hover:bg-blue-800 text-white rounded-xl text-xs font-bold shadow-xs">
            <span>💾</span> Save Lead
          </button>
        </div>
      </div>
    </div>
  `;
}

function renderTenureRows(product, rate, customerAge, maxEligibleEmi) {
  // Common standard banking tenures: 6, 12, 18, 24, 36, 48, 60, 72, 84, 96, 120
  const tenures = [6, 12, 18, 24, 36, 48, 60, 72, 84, 96, 120].filter(
    t => t >= product.minTenure && t <= product.maxTenure
  );

  return tenures.map(t => {
    const { emi, totalProfit, totalPayable } = calculateEmi(appState.calc.loanAmount, rate, t, product.rateType);
    const isEmiEligible = emi <= maxEligibleEmi;
    const ageAtMaturity = customerAge + Math.floor(t / 12);
    const isAgeEligible = ageAtMaturity <= product.maxAgeAtMaturity;
    const isSelected = appState.calc.selectedTenure === t;

    // DBR ratio calculation
    const totalObligation = Number(appState.calc.existingObligations) + emi;
    const income = Number(appState.calc.monthlyIncome) || 1;
    const dbrPercent = ((totalObligation / income) * 100).toFixed(1);

    return `
      <tr class="hover:bg-blue-50/50 cursor-pointer transition-colors ${isSelected ? 'bg-amber-50/80 font-medium' : ''}"
        onclick="appState.calc.selectedTenure = ${t}; openScheduleModal(${t});">
        <td class="p-2.5 font-bold text-slate-800">
          ${t} Mo <span class="text-[11px] text-slate-500 font-normal">(${(t/12).toFixed(1)} yr)</span>
        </td>
        <td class="p-2.5 font-bold text-blue-900">${formatCurrency(emi)}</td>
        <td class="p-2.5 text-slate-600">${formatCurrency(totalProfit)}</td>
        <td class="p-2.5 text-slate-600">${formatCurrency(totalPayable)}</td>
        <td class="p-2.5">
          ${!isAgeEligible ? `
            <span class="inline-block px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-100 text-amber-900">
              Age ${ageAtMaturity} > Max ${product.maxAgeAtMaturity}
            </span>
          ` : isEmiEligible ? `
            <span class="inline-block px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-800">
              ELIGIBLE (${dbrPercent}% DBR)
            </span>
          ` : `
            <span class="inline-block px-2 py-0.5 rounded-full text-[10px] font-bold bg-rose-100 text-rose-800">
              EXCEEDS DBR (${dbrPercent}%)
            </span>
          `}
        </td>
        <td class="p-2.5 text-center">
          <button class="px-2 py-1 text-[11px] bg-blue-100 text-blue-800 hover:bg-blue-200 rounded font-semibold">
            Schedule ➔
          </button>
        </td>
      </tr>
    `;
  }).join('');
}
