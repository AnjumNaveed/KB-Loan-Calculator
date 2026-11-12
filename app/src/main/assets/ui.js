// Bank LoanPro UI Rendering and Interaction Engine

function formatCurrency(num) {
  const curr = (appState.settings && appState.settings.currency) || "PKR";
  return `${curr} ${Number(num || 0).toLocaleString("en-US")}`;
}

function getSelectedProduct() {
  return appState.products.find(p => p.id === appState.calc.productId) || appState.products[0];
}

function shareViaWhatsApp(customText = null) {
  const p = getSelectedProduct();
  const rate = appState.calc.rateOverride || p.annualRate;
  const tenure = appState.calc.selectedTenure;
  const { emi, totalProfit, totalPayable } = calculateEmi(appState.calc.loanAmount, rate, tenure, p.rateType);
  const feeInfo = calculateProcessingFee(appState.calc.loanAmount, p);
  
  const text = customText || `🏛️ *OFFICIAL BANK FINANCING PROPOSAL*
━━━━━━━━━━━━━━━━━━━━
👤 *Customer:* ${appState.calc.customerName || "Valued Client"}
📱 *Mobile:* ${appState.calc.mobile || "N/A"}
📋 *Facility:* ${p.name}
💰 *Loan Amount:* ${formatCurrency(appState.calc.loanAmount)}
📅 *Tenure:* ${tenure} Months (${(tenure/12).toFixed(1)} Yrs)
📊 *Profit Rate:* ${rate}% (${p.rateType === 'reducing' ? 'Reducing Balance' : 'Flat Rate'})
━━━━━━━━━━━━━━━━━━━━
💵 *Monthly Installment (EMI):* ${formatCurrency(emi)}
🧾 *Processing Fee + FED (16%):* ${formatCurrency(feeInfo.totalFee)}
📈 *Total Profit / Mark-up:* ${formatCurrency(totalProfit)}
💳 *Total Repayment:* ${formatCurrency(totalPayable)}
━━━━━━━━━━━━━━━━━━━━
🏦 *Relationship Officer:* ${appState.settings.officerName} (${appState.settings.staffId})
📍 *Branch:* ${appState.settings.branchName}
*Note:* Subject to credit approval & bank policies.`;

  const phone = (appState.calc.mobile || "").replace(/[^0-9]/g, "");
  const url = phone ? `https://wa.me/${phone}?text=${encodeURIComponent(text)}` : `https://wa.me/?text=${encodeURIComponent(text)}`;
  
  if (window.AndroidBridge && window.AndroidBridge.shareWhatsApp) {
    window.AndroidBridge.shareWhatsApp(phone, text);
  } else {
    window.open(url, "_blank");
  }
}

function shareViaSms(customText = null) {
  const p = getSelectedProduct();
  const rate = appState.calc.rateOverride || p.annualRate;
  const tenure = appState.calc.selectedTenure;
  const { emi, totalPayable } = calculateEmi(appState.calc.loanAmount, rate, tenure, p.rateType);
  const phone = (appState.calc.mobile || "").replace(/[^0-9]/g, "");
  
  const msg = customText || `Bank Loan Proposal for ${appState.calc.customerName || "Customer"}: Facility: ${p.name}, Amount: ${formatCurrency(appState.calc.loanAmount)}, Tenure: ${tenure}M, EMI: ${formatCurrency(emi)}, Total: ${formatCurrency(totalPayable)}. Contact: ${appState.settings.officerName} (${appState.settings.staffId})`;
  
  if (window.AndroidBridge && window.AndroidBridge.shareSms) {
    window.AndroidBridge.shareSms(phone, msg);
  } else {
    window.location.href = `sms:${phone}?body=${encodeURIComponent(msg)}`;
  }
}

function copyProposalText() {
  const p = getSelectedProduct();
  const rate = appState.calc.rateOverride || p.annualRate;
  const tenure = appState.calc.selectedTenure;
  const { emi, totalProfit, totalPayable } = calculateEmi(appState.calc.loanAmount, rate, tenure, p.rateType);
  const feeInfo = calculateProcessingFee(appState.calc.loanAmount, p);
  
  const text = `BANK LOAN QUOTATION
Customer: ${appState.calc.customerName || "Client"}
Mobile: ${appState.calc.mobile || "N/A"}
Product: ${p.name}
Amount: ${formatCurrency(appState.calc.loanAmount)}
Tenure: ${tenure} Months
Rate: ${rate}% (${p.rateType})
Monthly EMI: ${formatCurrency(emi)}
Processing Fee + FED: ${formatCurrency(feeInfo.totalFee)}
Total Profit: ${formatCurrency(totalProfit)}
Total Payable: ${formatCurrency(totalPayable)}
Officer: ${appState.settings.officerName} - ${appState.settings.branchName}`;

  navigator.clipboard.writeText(text).then(() => {
    showToast("Proposal copied to clipboard!", "success");
  }).catch(() => {
    showToast("Copied to clipboard", "success");
  });
}

function saveCurrentLead() {
  if (!appState.calc.customerName.trim()) {
    showToast("Please enter customer name to save lead", "warning");
    document.getElementById("calc-cust-name")?.focus();
    return;
  }
  if (!appState.calc.mobile.trim()) {
    showToast("Please enter customer mobile number", "warning");
    document.getElementById("calc-cust-phone")?.focus();
    return;
  }

  const p = getSelectedProduct();
  const rate = appState.calc.rateOverride || p.annualRate;
  const tenure = appState.calc.selectedTenure;
  const { emi, totalProfit } = calculateEmi(appState.calc.loanAmount, rate, tenure, p.rateType);
  const feeInfo = calculateProcessingFee(appState.calc.loanAmount, p);

  const newLead = {
    id: "lead-" + Date.now(),
    customerName: appState.calc.customerName.trim(),
    mobile: appState.calc.mobile.trim(),
    dob: appState.calc.dob,
    age: calculateAge(appState.calc.dob),
    monthlyIncome: Number(appState.calc.monthlyIncome),
    existingEmi: Number(appState.calc.existingObligations),
    dbrAllowed: Number(appState.calc.allowedDbr),
    productId: p.id,
    productName: p.name,
    loanAmount: Number(appState.calc.loanAmount),
    tenureMonths: tenure,
    monthlyEmi: emi,
    totalProfit: totalProfit,
    processingFee: feeInfo.totalFee,
    status: "New",
    notes: appState.calc.notes || "Lead generated via LoanPro Mobile Calculator",
    officerId: appState.settings.staffId,
    createdAt: new Date().toISOString(),
    syncStatus: appState.online ? "synced" : "pending_sync"
  };

  appState.leads.unshift(newLead);
  if (!appState.online) {
    appState.syncQueue.push(newLead);
  }
  saveAllState();
  showToast("Lead successfully recorded in database!", "success");
  renderApp();
}

function exportLeadsToCsv() {
  if (!appState.leads.length) {
    showToast("No leads available to export", "warning");
    return;
  }
  const headers = [
    "Lead ID", "Date", "Customer Name", "Mobile", "Age", "Monthly Income",
    "Existing EMI", "Allowed DBR %", "Product Name", "Loan Amount",
    "Tenure (Mo)", "Monthly EMI", "Total Profit", "Processing Fee",
    "Status", "Officer ID", "Sync State", "Notes"
  ];

  const rows = appState.leads.map(l => [
    `"${l.id}"`,
    `"${new Date(l.createdAt).toLocaleDateString()}"`,
    `"${l.customerName.replace(/"/g, '""')}"`,
    `"${l.mobile}"`,
    l.age,
    l.monthlyIncome,
    l.existingEmi,
    l.dbrAllowed,
    `"${l.productName}"`,
    l.loanAmount,
    l.tenureMonths,
    l.monthlyEmi,
    l.totalProfit,
    l.processingFee,
    `"${l.status}"`,
    `"${l.officerId}"`,
    `"${l.syncStatus}"`,
    `"${(l.notes || '').replace(/"/g, '""')}"`
  ]);

  const csvContent = [headers.join(","), ...rows.map(r => r.join(","))].join("\r\n");
  
  if (window.AndroidBridge && window.AndroidBridge.exportCsv) {
    window.AndroidBridge.exportCsv("bank_leads_export.csv", csvContent);
  } else {
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.setAttribute("href", url);
    link.setAttribute("download", `bank_leads_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    showToast("CSV file exported successfully!", "success");
  }
}

function printAmortization() {
  if (window.AndroidBridge && window.AndroidBridge.printAmortization) {
    const printEl = document.getElementById("amortization-print-container");
    window.AndroidBridge.printAmortization(printEl ? printEl.innerHTML : "");
  } else {
    window.print();
  }
}
