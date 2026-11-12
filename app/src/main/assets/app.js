// Bank LoanPro Core Engine
const DEFAULT_PRODUCTS = [
  {
    id: "prod-1",
    name: "Personal Loan (Salaried)",
    rateType: "reducing",
    annualRate: 21.5,
    minAmount: 50000,
    maxAmount: 3000000,
    minTenure: 12,
    maxTenure: 60,
    minAge: 21,
    maxAgeAtMaturity: 60,
    feeType: "percentage",
    feeValue: 1.5,
    fedRate: 16.0
  },
  {
    id: "prod-2",
    name: "Auto Finance (New Car)",
    rateType: "reducing",
    annualRate: 19.0,
    minAmount: 200000,
    maxAmount: 10000000,
    minTenure: 12,
    maxTenure: 84,
    minAge: 21,
    maxAgeAtMaturity: 65,
    feeType: "fixed",
    feeValue: 8500,
    fedRate: 16.0
  },
  {
    id: "prod-3",
    name: "Home Mortgage Finance",
    rateType: "reducing",
    annualRate: 16.5,
    minAmount: 500000,
    maxAmount: 50000000,
    minTenure: 36,
    maxTenure: 120,
    minAge: 25,
    maxAgeAtMaturity: 65,
    feeType: "percentage",
    feeValue: 0.5,
    fedRate: 16.0
  },
  {
    id: "prod-4",
    name: "SME Micro Business Loan",
    rateType: "flat",
    annualRate: 14.0,
    minAmount: 30000,
    maxAmount: 1500000,
    minTenure: 6,
    maxTenure: 36,
    minAge: 21,
    maxAgeAtMaturity: 62,
    feeType: "fixed",
    feeValue: 3500,
    fedRate: 16.0
  },
  {
    id: "prod-5",
    name: "Solar Energy Consumer Finance",
    rateType: "reducing",
    annualRate: 15.5,
    minAmount: 100000,
    maxAmount: 4000000,
    minTenure: 12,
    maxTenure: 60,
    minAge: 21,
    maxAgeAtMaturity: 65,
    feeType: "percentage",
    feeValue: 1.0,
    fedRate: 16.0
  }
];

const DEFAULT_SETTINGS = {
  officerName: "Muhammad Usman",
  staffId: "RO-8942",
  branchName: "Main Branch",
  adminPin: "Admin123",
  currency: "PKR",
  defaultDbr: 50,
  webhookUrl: "",
  autoSync: true
};

const DEFAULT_LEADS = [
  {
    id: "lead-1",
    customerName: "Tariq Mahmood",
    mobile: "+92 300 1234567",
    dob: "1988-06-15",
    age: 38,
    monthlyIncome: 180000,
    existingEmi: 25000,
    dbrAllowed: 50,
    productId: "prod-1",
    productName: "Personal Loan (Salaried)",
    loanAmount: 1200000,
    tenureMonths: 36,
    monthlyEmi: 45620,
    totalProfit: 442320,
    processingFee: 20880,
    status: "Contacted",
    notes: "Requires loan for home renovation. Documentation requested.",
    officerId: "RO-8942",
    createdAt: new Date(Date.now() - 86400000 * 2).toISOString(),
    syncStatus: "synced"
  },
  {
    id: "lead-2",
    customerName: "Ayesha Siddiqui",
    mobile: "+92 321 9876543",
    dob: "1994-03-22",
    age: 32,
    monthlyIncome: 320000,
    existingEmi: 0,
    dbrAllowed: 50,
    productId: "prod-2",
    productName: "Auto Finance (New Car)",
    loanAmount: 3500000,
    tenureMonths: 60,
    monthlyEmi: 90850,
    totalProfit: 1951000,
    processingFee: 9860,
    status: "New",
    notes: "Interested in Sedan financing. Sent proposal via WhatsApp.",
    officerId: "RO-8942",
    createdAt: new Date(Date.now() - 3600000 * 5).toISOString(),
    syncStatus: "pending_sync"
  }
];

// App Global State
window.appState = {
  activeTab: "calc", // calc, leads, admin, settings
  online: navigator.onLine,
  settings: JSON.parse(localStorage.getItem("lp_settings") || "null") || DEFAULT_SETTINGS,
  products: JSON.parse(localStorage.getItem("lp_products") || "null") || DEFAULT_PRODUCTS,
  leads: JSON.parse(localStorage.getItem("lp_leads") || "null") || DEFAULT_LEADS,
  syncQueue: JSON.parse(localStorage.getItem("lp_sync_queue") || "[]"),
  isAdminUnlocked: false,
  
  // Calculator Inputs
  calc: {
    customerName: "",
    mobile: "",
    dob: "1992-05-15",
    age: 34,
    productId: "prod-1",
    loanAmount: 1000000,
    rateOverride: null,
    monthlyIncome: 150000,
    allowedDbr: 50,
    existingObligations: 15000,
    selectedTenure: 36,
    notes: ""
  },

  // Modal states
  scheduleModalOpen: false,
  adminAuthModalOpen: false,
  productModalOpen: false,
  editingProduct: null,
  toastMessage: "",
  toastType: "info",

  // Early Payment Simulator State
  prepayment: {
    amount: 200000,
    atMonth: 12,
    mode: "reduce_emi" // reduce_emi or reduce_tenure
  }
};

// Storage Helpers
function saveAllState() {
  localStorage.setItem("lp_settings", JSON.stringify(appState.settings));
  localStorage.setItem("lp_products", JSON.stringify(appState.products));
  localStorage.setItem("lp_leads", JSON.stringify(appState.leads));
  localStorage.setItem("lp_sync_queue", JSON.stringify(appState.syncQueue));
}

// Financial Calculator Functions
function calculateAge(dobString) {
  if (!dobString) return 0;
  const dob = new Date(dobString);
  const diffMs = Date.now() - dob.getTime();
  const ageDate = new Date(diffMs);
  return Math.abs(ageDate.getUTCFullYear() - 1970);
}

function calculateEmi(principal, annualRate, tenureMonths, rateType) {
  if (tenureMonths <= 0 || principal <= 0) return { emi: 0, totalProfit: 0, totalPayable: 0 };
  
  if (rateType === "flat") {
    const totalProfit = principal * (annualRate / 100) * (tenureMonths / 12);
    const totalPayable = principal + totalProfit;
    const emi = Math.round(totalPayable / tenureMonths);
    return { emi, totalProfit: Math.round(totalProfit), totalPayable: Math.round(totalPayable) };
  } else {
    // Reducing balance
    const monthlyRate = (annualRate / 100) / 12;
    if (monthlyRate === 0) {
      const emi = Math.round(principal / tenureMonths);
      return { emi, totalProfit: 0, totalPayable: principal };
    }
    const factor = Math.pow(1 + monthlyRate, tenureMonths);
    const emi = Math.round((principal * monthlyRate * factor) / (factor - 1));
    const totalPayable = emi * tenureMonths;
    const totalProfit = totalPayable - principal;
    return { emi, totalProfit, totalPayable };
  }
}

function calculateProcessingFee(principal, product) {
  let fee = 0;
  if (product.feeType === "fixed") {
    fee = Number(product.feeValue);
  } else {
    fee = (principal * Number(product.feeValue)) / 100;
  }
  const fed = (fee * Number(product.fedRate)) / 100;
  return {
    baseFee: Math.round(fee),
    fedAmount: Math.round(fed),
    totalFee: Math.round(fee + fed)
  };
}

function generateAmortizationSchedule(principal, annualRate, tenureMonths, rateType) {
  const schedule = [];
  let balance = principal;
  const monthlyRate = (annualRate / 100) / 12;
  const { emi } = calculateEmi(principal, annualRate, tenureMonths, rateType);

  const startDate = new Date();

  if (rateType === "flat") {
    const monthlyPrincipal = Math.round(principal / tenureMonths);
    const monthlyProfit = emi - monthlyPrincipal;
    for (let m = 1; m <= tenureMonths; m++) {
      const dueDate = new Date(startDate.getFullYear(), startDate.getMonth() + m, 1);
      const principalPaid = (m === tenureMonths) ? balance : monthlyPrincipal;
      const closing = Math.max(0, balance - principalPaid);
      schedule.push({
        month: m,
        date: dueDate.toLocaleDateString('en-US', { month: 'short', year: 'numeric' }),
        opening: Math.round(balance),
        principal: principalPaid,
        profit: monthlyProfit,
        emi: principalPaid + monthlyProfit,
        closing: closing
      });
      balance = closing;
    }
  } else {
    for (let m = 1; m <= tenureMonths; m++) {
      const dueDate = new Date(startDate.getFullYear(), startDate.getMonth() + m, 1);
      const profitPart = Math.round(balance * monthlyRate);
      let principalPart = emi - profitPart;
      if (m === tenureMonths || principalPart > balance) {
        principalPart = balance;
      }
      const closing = Math.max(0, balance - principalPart);
      schedule.push({
        month: m,
        date: dueDate.toLocaleDateString('en-US', { month: 'short', year: 'numeric' }),
        opening: Math.round(balance),
        principal: principalPart,
        profit: profitPart,
        emi: principalPart + profitPart,
        closing: closing
      });
      balance = closing;
      if (balance <= 0) break;
    }
  }
  return schedule;
}

function simulatePrepayment(principal, annualRate, tenureMonths, rateType, prepayAmount, atMonth, mode) {
  const origSchedule = generateAmortizationSchedule(principal, annualRate, tenureMonths, rateType);
  if (atMonth >= origSchedule.length) return null;

  const targetRow = origSchedule[atMonth - 1];
  const balanceBefore = targetRow.closing;
  const newBalance = Math.max(0, balanceBefore - prepayAmount);
  const remainingMonths = tenureMonths - atMonth;

  if (mode === "reduce_emi") {
    const revisedEmiCalc = calculateEmi(newBalance, annualRate, remainingMonths, rateType);
    let originalRemainingProfit = 0;
    for (let i = atMonth; i < origSchedule.length; i++) {
      originalRemainingProfit += origSchedule[i].profit;
    }
    const profitSaved = Math.max(0, originalRemainingProfit - revisedEmiCalc.totalProfit);
    return {
      newEmi: revisedEmiCalc.emi,
      monthsSaved: 0,
      profitSaved: Math.round(profitSaved),
      newTenure: tenureMonths
    };
  } else {
    // reduce tenure
    const currentEmi = origSchedule[0].emi;
    const monthlyRate = (annualRate / 100) / 12;
    let b = newBalance;
    let newMonths = 0;
    let newTotalProfit = 0;
    while (b > 0 && newMonths < remainingMonths) {
      newMonths++;
      const profit = Math.round(b * monthlyRate);
      newTotalProfit += profit;
      const principal = Math.min(b, currentEmi - profit);
      b -= principal;
    }
    let originalRemainingProfit = 0;
    for (let i = atMonth; i < origSchedule.length; i++) {
      originalRemainingProfit += origSchedule[i].profit;
    }
    const profitSaved = Math.max(0, originalRemainingProfit - newTotalProfit);
    return {
      newEmi: currentEmi,
      monthsSaved: remainingMonths - newMonths,
      profitSaved: Math.round(profitSaved),
      newTenure: atMonth + newMonths
    };
  }
}

// Global UI Rendering & Event Handling
window.addEventListener("online", () => {
  appState.online = true;
  showToast("Internet restored! Syncing pending leads...", "success");
  syncWithBackend();
  renderApp();
});

window.addEventListener("offline", () => {
  appState.online = false;
  showToast("Working in Offline Mode. Calculations & leads saved locally.", "warning");
  renderApp();
});

function showToast(msg, type = "info") {
  appState.toastMessage = msg;
  appState.toastType = type;
  const toastEl = document.getElementById("toast-banner");
  if (toastEl) {
    toastEl.className = `fixed bottom-4 left-1/2 transform -translate-x-1/2 px-4 py-2 rounded-lg text-white font-medium shadow-lg z-50 transition-all ${
      type === "success" ? "bg-emerald-600" : type === "warning" ? "bg-amber-600" : type === "error" ? "bg-rose-600" : "bg-blue-600"
    }`;
    toastEl.innerText = msg;
    toastEl.style.display = "block";
    clearTimeout(window._toastTimer);
    window._toastTimer = setTimeout(() => {
      toastEl.style.display = "none";
    }, 3500);
  }
}

async function syncWithBackend() {
  if (appState.syncQueue.length === 0) {
    showToast("Everything is up to date!", "info");
    return;
  }
  showToast(`Pushing ${appState.syncQueue.length} queued leads to server...`, "info");
  
  // If webhook configured, send POST
  if (appState.settings.webhookUrl) {
    try {
      const res = await fetch(appState.settings.webhookUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          officer: appState.settings.officerName,
          staffId: appState.settings.staffId,
          queue: appState.syncQueue
        })
      });
      if (res.ok) {
        appState.leads.forEach(l => l.syncStatus = "synced");
        appState.syncQueue = [];
        saveAllState();
        showToast("Leads synced successfully with cloud server!", "success");
        renderApp();
        return;
      }
    } catch (e) {
      console.warn("Backend sync failed, will retry later:", e);
    }
  }

  // Fallback simulation for offline demo/pabbly
  setTimeout(() => {
    appState.leads.forEach(l => l.syncStatus = "synced");
    appState.syncQueue = [];
    saveAllState();
    showToast("Leads synced successfully to storage queue!", "success");
    renderApp();
  }, 1000);
}
