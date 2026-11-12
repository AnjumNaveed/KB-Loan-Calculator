// Bank LoanPro Admin Panel & Products CRUD Engine

function renderAdminScreen() {
  return `
    <div class="space-y-4">
      <!-- Admin Header -->
      <div class="bg-white p-4 rounded-2xl border border-slate-200 shadow-xs flex justify-between items-center">
        <div>
          <h2 class="font-bold text-slate-800 text-sm tracking-wide">
            🔐 BANK PRODUCT CONFIGURATION & SETTINGS
          </h2>
          <p class="text-xs text-slate-500">Configure interest rates, fees, FED %, and officer identity.</p>
        </div>
        <button onclick="toggleAdminView()" class="px-3 py-1.5 bg-rose-50 text-rose-700 border border-rose-200 rounded-lg text-xs font-bold">
          Lock Admin
        </button>
      </div>

      <!-- Products Management Section -->
      <div class="bg-white p-4 rounded-2xl border border-slate-200 shadow-xs space-y-3">
        <div class="flex justify-between items-center">
          <h3 class="font-extrabold text-slate-900 text-xs tracking-wider uppercase">
            Bank Financing Products (${appState.products.length})
          </h3>
          <div class="flex space-x-2">
            <button onclick="resetDefaultProducts()" class="px-2.5 py-1 text-slate-600 bg-slate-100 hover:bg-slate-200 rounded text-xs font-semibold">
              ↺ Reset Defaults
            </button>
            <button onclick="openProductModal(null)" class="px-3 py-1 bg-blue-700 hover:bg-blue-800 text-white rounded text-xs font-bold shadow-xs">
              + Add Product
            </button>
          </div>
        </div>

        <div class="table-scroll border border-slate-200 rounded-xl overflow-hidden">
          <table class="w-full text-left border-collapse text-xs">
            <thead class="bg-slate-100 text-slate-700 font-bold border-b border-slate-200">
              <tr>
                <th class="p-2.5">Product Name</th>
                <th class="p-2.5">Pricing</th>
                <th class="p-2.5">Loan Limits</th>
                <th class="p-2.5">Tenure</th>
                <th class="p-2.5">Processing Fee</th>
                <th class="p-2.5 text-center">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              ${appState.products.map(p => `
                <tr class="hover:bg-slate-50">
                  <td class="p-2.5 font-bold text-slate-800">
                    ${p.name}
                    <span class="block text-[10px] text-slate-400 font-mono font-normal">Age: ${p.minAge} - ${p.maxAgeAtMaturity} yrs</span>
                  </td>
                  <td class="p-2.5 font-semibold text-blue-900">
                    ${p.annualRate}% <span class="text-[10px] uppercase text-slate-500 font-normal">(${p.rateType})</span>
                  </td>
                  <td class="p-2.5 text-slate-600 font-mono text-[11px]">
                    ${formatCurrency(p.minAmount)} - ${formatCurrency(p.maxAmount)}
                  </td>
                  <td class="p-2.5 text-slate-700">
                    ${p.minTenure} - ${p.maxTenure} Mo
                  </td>
                  <td class="p-2.5 text-slate-600">
                    ${p.feeType === 'fixed' ? formatCurrency(p.feeValue) : p.feeValue + '%'} + ${p.fedRate}% FED
                  </td>
                  <td class="p-2.5 text-center space-x-1">
                    <button onclick="openProductModal('${p.id}')" class="px-2 py-1 bg-slate-100 hover:bg-slate-200 rounded text-slate-700 font-bold text-[11px]">
                      ✏️ Edit
                    </button>
                    <button onclick="deleteProduct('${p.id}')" class="px-2 py-1 text-rose-600 hover:bg-rose-50 rounded font-bold text-[11px]">
                      🗑️
                    </button>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      </div>

      <!-- Officer & Branch Settings -->
      <div class="bg-white p-4 rounded-2xl border border-slate-200 shadow-xs space-y-3">
        <h3 class="font-extrabold text-slate-900 text-xs tracking-wider uppercase">
          Officer & Branch Settings
        </h3>
        
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
          <div>
            <label class="block font-semibold text-slate-600 mb-1">Relationship Officer Name</label>
            <input type="text" value="${appState.settings.officerName}"
              oninput="appState.settings.officerName = this.value; saveAllState();"
              class="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg outline-none font-semibold text-slate-800" />
          </div>

          <div>
            <label class="block font-semibold text-slate-600 mb-1">Staff / Employee ID</label>
            <input type="text" value="${appState.settings.staffId}"
              oninput="appState.settings.staffId = this.value; saveAllState();"
              class="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg outline-none font-semibold text-slate-800" />
          </div>

          <div>
            <label class="block font-semibold text-slate-600 mb-1">Branch Name</label>
            <input type="text" value="${appState.settings.branchName}"
              oninput="appState.settings.branchName = this.value; saveAllState();"
              class="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg outline-none font-semibold text-slate-800" />
          </div>

          <div>
            <label class="block font-semibold text-slate-600 mb-1">Currency Symbol / Code</label>
            <input type="text" value="${appState.settings.currency}"
              oninput="appState.settings.currency = this.value; saveAllState(); renderApp();"
              class="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg outline-none font-bold text-slate-800" />
          </div>

          <div class="col-span-1 md:col-span-2">
            <label class="block font-semibold text-slate-600 mb-1">Cloud Sync Webhook URL (Pabbly Connect / Zapier / Firestore)</label>
            <input type="url" value="${appState.settings.webhookUrl || ''}"
              oninput="appState.settings.webhookUrl = this.value; saveAllState();"
              placeholder="https://connect.pabbly.com/workflow/sendwebhookdata/..."
              class="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg outline-none text-slate-800" />
            <p class="text-[10px] text-slate-500 mt-1">Pending leads in queue will be auto-pushed to this webhook endpoint when online.</p>
          </div>
        </div>
      </div>
    </div>
  `;
}

function renderAdminAuthModal() {
  return `
    <div class="fixed inset-0 bg-slate-900/60 backdrop-blur-xs flex items-center justify-center p-3 z-50">
      <div class="bg-white w-full max-w-sm rounded-2xl shadow-2xl border border-slate-200 p-5 space-y-4">
        <div class="text-center">
          <div class="w-12 h-12 mx-auto rounded-full bg-blue-100 text-blue-900 flex items-center justify-center text-xl mb-2">
            🔐
          </div>
          <h3 class="font-black text-slate-900 text-base">Admin Authorization</h3>
          <p class="text-xs text-slate-500">Enter Admin PIN to manage products & rates (Default: <b>Admin123</b>)</p>
        </div>

        <div>
          <input type="password" id="admin-pin-input" placeholder="Enter PIN..."
            onkeydown="if(event.key === 'Enter') submitAdminPin();"
            class="w-full px-3 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-center text-lg tracking-widest font-black focus:ring-2 focus:ring-blue-600 outline-none" />
        </div>

        <div class="flex space-x-2">
          <button onclick="appState.adminAuthModalOpen = false; renderApp();" class="flex-1 py-2 bg-slate-200 text-slate-700 rounded-xl text-xs font-bold">
            Cancel
          </button>
          <button onclick="submitAdminPin()" class="flex-1 py-2 bg-blue-700 text-white rounded-xl text-xs font-bold shadow-xs">
            Unlock
          </button>
        </div>
      </div>
    </div>
  `;
}

function submitAdminPin() {
  const pinInput = document.getElementById("admin-pin-input");
  const entered = pinInput ? pinInput.value : "";
  if (entered === (appState.settings.adminPin || "Admin123")) {
    appState.isAdminUnlocked = true;
    appState.adminAuthModalOpen = false;
    appState.activeTab = "admin";
    showToast("Admin access granted", "success");
  } else {
    showToast("Incorrect Admin PIN! (Default: Admin123)", "error");
  }
  renderApp();
}

function openProductModal(productId = null) {
  if (productId) {
    appState.editingProduct = JSON.parse(JSON.stringify(appState.products.find(p => p.id === productId)));
  } else {
    appState.editingProduct = {
      id: "prod-" + Date.now(),
      name: "New Loan Facility",
      rateType: "reducing",
      annualRate: 18.0,
      minAmount: 100000,
      maxAmount: 2000000,
      minTenure: 12,
      maxTenure: 60,
      minAge: 21,
      maxAgeAtMaturity: 60,
      feeType: "percentage",
      feeValue: 1.5,
      fedRate: 16.0
    };
  }
  appState.productModalOpen = true;
  renderApp();
}

function renderProductModal() {
  const p = appState.editingProduct;
  if (!p) return "";

  return `
    <div class="fixed inset-0 bg-slate-900/60 backdrop-blur-xs flex items-center justify-center p-3 z-50 overflow-y-auto">
      <div class="bg-white w-full max-w-lg rounded-2xl shadow-2xl border border-slate-200 p-5 space-y-3 max-h-[90vh] overflow-y-auto">
        <h3 class="font-black text-slate-900 text-sm tracking-wide">
          ${p.id ? 'Edit Bank Loan Product' : 'Add New Product'}
        </h3>

        <div class="space-y-2 text-xs">
          <div>
            <label class="block font-semibold text-slate-600 mb-1">Product Name</label>
            <input type="text" value="${p.name}" oninput="appState.editingProduct.name = this.value"
              class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none font-bold" />
          </div>

          <div class="grid grid-cols-2 gap-2">
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Interest Rate Type</label>
              <select onchange="appState.editingProduct.rateType = this.value; renderApp();"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none font-semibold">
                <option value="reducing" ${p.rateType === 'reducing' ? 'selected' : ''}>Reducing Balance</option>
                <option value="flat" ${p.rateType === 'flat' ? 'selected' : ''}>Flat Rate %</option>
              </select>
            </div>
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Annual Interest Rate (%)</label>
              <input type="number" step="0.25" value="${p.annualRate}"
                oninput="appState.editingProduct.annualRate = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none font-bold" />
            </div>
          </div>

          <div class="grid grid-cols-2 gap-2">
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Min Loan Amount</label>
              <input type="number" step="10000" value="${p.minAmount}"
                oninput="appState.editingProduct.minAmount = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Max Loan Amount</label>
              <input type="number" step="50000" value="${p.maxAmount}"
                oninput="appState.editingProduct.maxAmount = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
          </div>

          <div class="grid grid-cols-2 gap-2">
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Min Tenure (Months)</label>
              <input type="number" value="${p.minTenure}"
                oninput="appState.editingProduct.minTenure = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Max Tenure (Months)</label>
              <input type="number" value="${p.maxTenure}"
                oninput="appState.editingProduct.maxTenure = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
          </div>

          <div class="grid grid-cols-2 gap-2">
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Min Age Limit</label>
              <input type="number" value="${p.minAge}"
                oninput="appState.editingProduct.minAge = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Max Age at Loan Maturity</label>
              <input type="number" value="${p.maxAgeAtMaturity}"
                oninput="appState.editingProduct.maxAgeAtMaturity = Number(this.value)"
                class="w-full px-3 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
          </div>

          <div class="grid grid-cols-3 gap-2">
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Fee Type</label>
              <select onchange="appState.editingProduct.feeType = this.value; renderApp();"
                class="w-full px-2 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none">
                <option value="percentage" ${p.feeType === 'percentage' ? 'selected' : ''}>% of Loan</option>
                <option value="fixed" ${p.feeType === 'fixed' ? 'selected' : ''}>Fixed PKR</option>
              </select>
            </div>
            <div>
              <label class="block font-semibold text-slate-600 mb-1">Fee Value</label>
              <input type="number" step="0.1" value="${p.feeValue}"
                oninput="appState.editingProduct.feeValue = Number(this.value)"
                class="w-full px-2 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none font-bold" />
            </div>
            <div>
              <label class="block font-semibold text-slate-600 mb-1">FED Rate (%)</label>
              <input type="number" step="0.5" value="${p.fedRate}"
                oninput="appState.editingProduct.fedRate = Number(this.value)"
                class="w-full px-2 py-1.5 bg-slate-50 border border-slate-300 rounded-lg outline-none" />
            </div>
          </div>
        </div>

        <div class="flex justify-end space-x-2 pt-2 border-t border-slate-200">
          <button onclick="appState.productModalOpen = false; renderApp();" class="px-3 py-1.5 bg-slate-200 text-slate-700 rounded-lg text-xs font-bold">
            Cancel
          </button>
          <button onclick="saveProductModal()" class="px-4 py-1.5 bg-blue-700 text-white rounded-lg text-xs font-bold shadow-xs">
            Save Product
          </button>
        </div>
      </div>
    </div>
  `;
}

function saveProductModal() {
  const p = appState.editingProduct;
  const idx = appState.products.findIndex(x => x.id === p.id);
  if (idx >= 0) {
    appState.products[idx] = p;
  } else {
    appState.products.push(p);
  }
  saveAllState();
  appState.productModalOpen = false;
  showToast("Product configuration saved", "success");
  renderApp();
}

function deleteProduct(id) {
  if (appState.products.length <= 1) {
    showToast("At least one product must remain", "warning");
    return;
  }
  if (confirm("Delete this product permanently?")) {
    appState.products = appState.products.filter(p => p.id !== id);
    if (appState.calc.productId === id) {
      appState.calc.productId = appState.products[0].id;
    }
    saveAllState();
    renderApp();
  }
}

function resetDefaultProducts() {
  if (confirm("Reset all products to bank standard defaults?")) {
    appState.products = JSON.parse(JSON.stringify(DEFAULT_PRODUCTS));
    saveAllState();
    showToast("Products reset to bank defaults", "success");
    renderApp();
  }
}
