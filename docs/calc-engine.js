/**
 * KBL Loan Calculator - Financial Calculation, Age Eligibility, Matrix & Amortization Engine
 * Exact parity with Android LoanCalculators.kt, CalculatorScreen.kt & AmortizationDialog.kt
 */

let calcCategoryFilter = 'all'; // 'all' | 'asset' | 'liability'
let matrixTenureFilter = 'All';
let selectedMatrixTenure = 12;
let isFilerCustomer = true;

let amortState = {
  principal: 0,
  rate: 0,
  tenure: 12,
  rateType: 'reducing',
  mode: 'reduce_emi', // 'reduce_emi' | 'reduce_tenure'
  extraPayments: {},
  rows: [],
  summary: null,
  selectedMonth: null
};

// 1. AGE & ELIGIBILITY ENGINE
function calculateDetailedAge(dobIso) {
  if (!dobIso) return { years: 0, months: 0, days: 0 };
  const dob = new Date(dobIso + 'T00:00:00');
  if (isNaN(dob.getTime())) return { years: 0, months: 0, days: 0 };
  const now = new Date();
  if (now < dob) return { years: 0, months: 0, days: 0 };

  let years = now.getFullYear() - dob.getFullYear();
  let months = now.getMonth() - dob.getMonth();
  let days = now.getDate() - dob.getDate();

  if (days < 0) {
    months--;
    const prevMonth = new Date(now.getFullYear(), now.getMonth(), 0);
    days += prevMonth.getDate();
  }
  if (months < 0) {
    years--;
    months += 12;
  }
  return {
    years: Math.max(0, years),
    months: Math.max(0, months),
    days: Math.max(0, days)
  };
}

function evaluateAgeEligibility(dobIso, product) {
  const detailed = calculateDetailedAge(dobIso);
  const pMinAge = Number(product.minAge) || 21;
  const pMaxAge = Number(product.maxAgeAtMaturity) || 65;
  const pMinTenure = Number(product.minTenure) || 1;
  const pMaxTenure = Number(product.maxTenure) || 36;

  if (!dobIso) {
    return {
      detailedAge: detailed,
      isEligible: true,
      isUnderMinAge: false,
      isOverMaturityAge: false,
      remainingMonthsToMaturity: pMaxTenure,
      maxOfferableTenureMonths: pMaxTenure
    };
  }

  const totalCustomerMonths = (detailed.years * 12) + detailed.months + (detailed.days > 0 ? 1 : 0);
  const maxMaturityMonths = pMaxAge * 12;

  const isUnderMin = detailed.years < pMinAge;
  const remainingToMaturity = Math.max(0, maxMaturityMonths - totalCustomerMonths);
  const isOverMaturity = remainingToMaturity < pMinTenure;
  const maxOfferable = (isUnderMin || isOverMaturity) ? 0 : Math.min(pMaxTenure, remainingToMaturity);
  const isEligible = !isUnderMin && !isOverMaturity && maxOfferable >= pMinTenure;

  return {
    detailedAge: detailed,
    isEligible,
    isUnderMinAge: isUnderMin,
    isOverMaturityAge: isOverMaturity,
    remainingMonthsToMaturity: remainingToMaturity,
    maxOfferableTenureMonths: maxOfferable
  };
}

function updateAgeEligibilityUI() {
  const pill = document.getElementById('ageEligibilityPill');
  if (!pill || !currentProduct) return;

  const dobVal = document.getElementById('customerDob').value;
  if (!dobVal) {
    pill.className = 'age-status-pill';
    pill.style.display = 'none';
    return;
  }

  const info = evaluateAgeEligibility(dobVal, currentProduct);
  const d = info.detailedAge;
  const pMinAge = Number(currentProduct.minAge) || 21;
  const pMaxAge = Number(currentProduct.maxAgeAtMaturity) || 65;
  const ageStr = `${d.years} Yrs, ${d.months} Mos, ${d.days} Days (${d.years} سال، ${d.months} ماہ، ${d.days} دن)`;

  pill.style.display = 'block';
  if (currentProduct.category === 'liability') {
    pill.className = 'age-status-pill info';
    pill.innerHTML = `🎂 <strong>Current Age:</strong> ${ageStr}`;
    return;
  }

  if (info.isUnderMinAge) {
    pill.className = 'age-status-pill ineligible';
    pill.innerHTML = `❌ <strong>Not Eligible (Under Age):</strong> ${ageStr}<br>Minimum required age for ${escapeHtml(currentProduct.name)} is <strong>${pMinAge} Years</strong>.`;
  } else if (info.isOverMaturityAge || !info.isEligible) {
    pill.className = 'age-status-pill ineligible';
    pill.innerHTML = `❌ <strong>Not Eligible (Maturity Age Exceeded):</strong> ${ageStr}<br>Max maturity age is <strong>${pMaxAge} Years</strong> (Remaining: ${info.remainingMonthsToMaturity}M &lt; Min ${currentProduct.minTenure}M).`;
  } else {
    pill.className = 'age-status-pill eligible';
    pill.innerHTML = `✅ <strong>Age Eligible:</strong> ${ageStr}<br>Max Allowed Tenure before ${pMaxAge} Yrs maturity: <strong>${info.maxOfferableTenureMonths} Months</strong>`;
  }
}

// 2. RATE, EMI, FEE & AUTO-MAX PRINCIPAL MATH
function getApplicableRate(product, amount, tenureMonths = 0) {
  const hasPositiveSlab = Array.isArray(product.rateSlabs) && product.rateSlabs.some(s => Number(s.annualRate) > 0);
  const hasPositiveTenure = product.tenureRates && Object.values(product.tenureRates).some(r => Number(r) > 0);
  if ((Number(product.defaultAnnualRate) || 0) <= 0 && !hasPositiveSlab && !hasPositiveTenure) {
    return 0.0;
  }

  const findAmountSlabRate = (slabs) => {
    if (!Array.isArray(slabs) || slabs.length === 0) return null;
    if (amount > 0) {
      const exact = slabs.find(s => amount >= Number(s.minAmount) && amount <= Number(s.maxAmount));
      if (exact) return Number(exact.annualRate);
      let nearest = slabs[0];
      let minDist = Infinity;
      slabs.forEach(s => {
        const minA = Number(s.minAmount);
        const maxA = Number(s.maxAmount);
        const dist = amount < minA ? (minA - amount) : (amount > maxA ? (amount - maxA) : 0);
        if (dist < minDist) { minDist = dist; nearest = s; }
      });
      if (nearest) return Number(nearest.annualRate);
    }
    return Number(slabs[0].annualRate);
  };

  if (product.category === 'liability') {
    const isAmountTierBased = product.liabilityPricingType === 'amount' ||
      ((!product.tenureRates || Object.keys(product.tenureRates).length === 0) && Array.isArray(product.rateSlabs) && product.rateSlabs.length > 1);
    if (isAmountTierBased && product.rateSlabs && product.rateSlabs.length > 0) {
      const slabRate = findAmountSlabRate(product.rateSlabs);
      if (slabRate !== null) return slabRate;
    }
    if (product.tenureRates && Object.keys(product.tenureRates).length > 0) {
      const keys = Object.keys(product.tenureRates).map(Number).filter(n => !isNaN(n));
      if (tenureMonths > 0 && keys.length > 0) {
        if (product.tenureRates[tenureMonths] !== undefined) return Number(product.tenureRates[tenureMonths]);
        let nearestK = keys[0];
        let minDiff = Math.abs(nearestK - tenureMonths);
        keys.forEach(k => {
          const diff = Math.abs(k - tenureMonths);
          if (diff < minDiff) { minDiff = diff; nearestK = k; }
        });
        return Number(product.tenureRates[nearestK]);
      } else if (keys.length > 0) {
        return Number(product.tenureRates[keys[0]]);
      }
    }
  }

  if (Array.isArray(product.rateSlabs) && product.rateSlabs.length > 0) {
    const slabRate = findAmountSlabRate(product.rateSlabs);
    if (slabRate !== null) return slabRate;
  }

  return Number(product.defaultAnnualRate) || 0;
}

function calculateEmi(principal, annualRate, tenureMonths, rateType) {
  if (tenureMonths <= 0 || principal <= 0) return { emi: 0, totalProfit: 0, totalPayable: 0 };
  if (String(rateType).toLowerCase() === 'flat') {
    const totalProfit = Math.round(principal * (annualRate / 100.0) * (tenureMonths / 12.0));
    const totalPayable = principal + totalProfit;
    const emi = Math.round(totalPayable / tenureMonths);
    return { emi, totalProfit, totalPayable };
  }
  const monthlyRate = (annualRate / 100.0) / 12.0;
  if (monthlyRate === 0) {
    const emi = Math.round(principal / tenureMonths);
    return { emi, totalProfit: 0, totalPayable: principal };
  }
  const factor = Math.pow(1.0 + monthlyRate, tenureMonths);
  const emi = Math.round((principal * monthlyRate * factor) / (factor - 1.0));
  const totalPayable = emi * tenureMonths;
  const totalProfit = totalPayable - principal;
  return { emi, totalProfit, totalPayable };
}

function calculateProcessingFee(product, principal, fedRate) {
  if (principal <= 0) return { baseFee: 0, fedAmount: 0, totalFee: 0 };
  let fee = 0;
  if (Array.isArray(product.feeSlabs) && product.feeSlabs.length > 0) {
    let slab = product.feeSlabs.find(s => principal >= Number(s.minAmount) && principal <= Number(s.maxAmount));
    if (!slab) {
      let minDist = Infinity;
      product.feeSlabs.forEach(s => {
        const minA = Number(s.minAmount);
        const maxA = Number(s.maxAmount);
        const dist = principal < minA ? (minA - principal) : (principal > maxA ? (principal - maxA) : 0);
        if (dist < minDist) { minDist = dist; slab = s; }
      });
    }
    if (slab) {
      const raw = String(slab.feeType).toLowerCase() === 'fixed'
        ? Math.round(Number(slab.feeValue) || 0)
        : Math.round((principal * (Number(slab.feeValue) || 0)) / 100.0);
      const minApplied = Math.max(Number(slab.minFee) || 0, raw);
      const maxFee = Number(slab.maxFee) || 0;
      fee = maxFee > 0 ? Math.min(minApplied, maxFee) : minApplied;
    }
  } else if ((Number(product.feePercent) || Number(product.defaultFeeValue) || 0) > 0) {
    const feeVal = Number(product.feePercent) || Number(product.defaultFeeValue) || 0;
    if (String(product.feeType).toLowerCase() === 'fixed') {
      fee = Math.round(feeVal);
    } else {
      fee = Math.round(principal * (feeVal / 100.0));
    }
  }
  const fedAmount = Math.round((fee * fedRate) / 100.0);
  return { baseFee: fee, fedAmount, totalFee: fee + fedAmount };
}

function computePrincipalFromEmi(emi, annualRate, tenureMonths, rateType) {
  if (String(rateType).toLowerCase() === 'flat') {
    const totalPayable = emi * tenureMonths;
    const factor = 1.0 + ((annualRate / 100.0) * (tenureMonths / 12.0));
    return Math.round(totalPayable / factor);
  }
  const monthlyRate = (annualRate / 100.0) / 12.0;
  if (monthlyRate === 0) return emi * tenureMonths;
  const factor = Math.pow(1.0 + monthlyRate, tenureMonths);
  return Math.round((emi * (factor - 1.0)) / (monthlyRate * factor));
}

function calculateMaxLoanAmount(product, netIncome, dbrPercent, tenureMonths) {
  const maxEmiAllowed = Math.round((netIncome * dbrPercent) / 100.0);
  if (maxEmiAllowed <= 0 || tenureMonths <= 0) return 0;

  const slabRates = Array.isArray(product.rateSlabs) ? product.rateSlabs.map(s => Number(s.annualRate)) : [];
  const candidateRates = Array.from(new Set([...slabRates, Number(product.defaultAnnualRate) || 0]));
  let bestPrincipal = 0;

  for (const candidateRate of candidateRates) {
    let p = computePrincipalFromEmi(maxEmiAllowed, candidateRate, tenureMonths, product.rateType);
    for (let i = 0; i < 4; i++) {
      const actualRate = getApplicableRate(product, p, tenureMonths);
      p = computePrincipalFromEmi(maxEmiAllowed, actualRate, tenureMonths, product.rateType);
    }
    // Binary/coarse refine so EMI <= maxEmiAllowed
    let guard = 0;
    while (p > 0 && guard < 2000) {
      const checkRate = getApplicableRate(product, p, tenureMonths);
      const emi = calculateEmi(p, checkRate, tenureMonths, product.rateType).emi;
      if (emi <= maxEmiAllowed) break;
      const step = Math.max(1, Math.floor((emi - maxEmiAllowed) * tenureMonths * 0.3));
      p = Math.max(0, p - step);
      guard++;
    }
    while (guard < 2500) {
      const nextP = p + 1;
      const nextRate = getApplicableRate(product, nextP, tenureMonths);
      const nextEmi = calculateEmi(nextP, nextRate, tenureMonths, product.rateType).emi;
      if (nextEmi <= maxEmiAllowed) {
        p = nextP;
        guard++;
      } else {
        break;
      }
    }
    if (p > bestPrincipal) {
      const verifyRate = getApplicableRate(product, p, tenureMonths);
      const verifyEmi = calculateEmi(p, verifyRate, tenureMonths, product.rateType).emi;
      if (verifyEmi <= maxEmiAllowed) {
        bestPrincipal = p;
      }
    }
  }

  // Clamp to product bounds if positive
  const pMax = Number(product.maxAmount) || 0;
  if (pMax > 0 && bestPrincipal > pMax) {
    bestPrincipal = pMax;
  }
  return Math.max(0, bestPrincipal);
}

function calculateDepositProfit(depositAmount, annualRate, tenureMonths, isFiler, filerTaxRate, nonFilerTaxRate) {
  const t = Math.max(1, tenureMonths);
  const taxRate = isFiler ? filerTaxRate : nonFilerTaxRate;
  if (depositAmount <= 0) {
    return {
      monthlyProfit: 0, totalProfit: 0, isFiler, taxRate,
      monthlyTax: 0, totalTax: 0, netMonthlyProfit: 0,
      netTotalProfit: 0, maturityAmount: 0, netMaturityAmount: 0, effectiveRate: annualRate
    };
  }
  const totalProfit = Math.round(depositAmount * (annualRate / 100.0) * (t / 12.0));
  const monthlyProfit = Math.round((depositAmount * (annualRate / 100.0)) / 12.0);
  const totalTax = Math.round(totalProfit * (taxRate / 100.0));
  const monthlyTax = Math.round(monthlyProfit * (taxRate / 100.0));
  const netTotalProfit = Math.max(0, totalProfit - totalTax);
  const netMonthlyProfit = Math.max(0, monthlyProfit - monthlyTax);
  return {
    monthlyProfit,
    totalProfit,
    isFiler,
    taxRate,
    monthlyTax,
    totalTax,
    netMonthlyProfit,
    netTotalProfit,
    maturityAmount: depositAmount + totalProfit,
    netMaturityAmount: depositAmount + netTotalProfit,
    effectiveRate: annualRate
  };
}

function getPrincipalForTenure(tenure) {
  if (!currentProduct) return 0;
  const rawAmtStr = document.getElementById('loanAmount').value.trim();
  const manualAmt = parseFloat(rawAmtStr);

  if (currentProduct.category === 'liability') {
    return (!isNaN(manualAmt) && manualAmt > 0) ? Math.round(manualAmt) : (Number(currentProduct.minAmount) || 25000);
  }
  if (!isNaN(manualAmt) && manualAmt > 0) {
    return Math.round(manualAmt);
  }
  const income = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const dbr = parseFloat(document.getElementById('allowedDbr').value) || 0;
  if (income > 0 && dbr > 0) {
    return calculateMaxLoanAmount(currentProduct, income, dbr, tenure);
  }
  return 0;
}

// 3. PRODUCT FILTERING, SEARCH & DROPDOWN MANAGEMENT
function setCategory(cat) {
  calcCategoryFilter = cat;
  document.getElementById('catAllBtn').className = 'chip-btn' + (cat === 'all' ? ' active' : '');
  document.getElementById('catAssetBtn').className = 'chip-btn' + (cat === 'asset' ? ' active' : '');
  document.getElementById('catLiabBtn').className = 'chip-btn' + (cat === 'liability' ? ' active-teal' : '');
  populateProducts(currentProduct ? currentProduct.id : null);
  calculate();
}

function updateCategoryCounts() {
  const allCount = BANK_PRODUCTS.length;
  const assetCount = BANK_PRODUCTS.filter(p => p.category !== 'liability').length;
  const liabCount = BANK_PRODUCTS.filter(p => p.category === 'liability').length;
  const allBtn = document.getElementById('catAllBtn');
  const assetBtn = document.getElementById('catAssetBtn');
  const liabBtn = document.getElementById('catLiabBtn');
  if (allBtn) allBtn.textContent = `All (${allCount})`;
  if (assetBtn) assetBtn.textContent = `💼 Asset (${assetCount})`;
  if (liabBtn) liabBtn.textContent = `💰 Liability (${liabCount})`;
}

function getFilteredCalculatorProducts() {
  const q = (document.getElementById('productSearchInput')?.value || '').toLowerCase().trim();
  return BANK_PRODUCTS.filter(p => {
    const matchCat = calcCategoryFilter === 'all' ||
      (calcCategoryFilter === 'asset' && p.category !== 'liability') ||
      (calcCategoryFilter === 'liability' && p.category === 'liability');
    const matchSearch = !q ||
      (p.name && p.name.toLowerCase().includes(q)) ||
      (p.rateType && p.rateType.toLowerCase().includes(q)) ||
      (p.category && p.category.toLowerCase().includes(q));
    return matchCat && matchSearch;
  });
}

function populateProducts(preserveId = null) {
  updateCategoryCounts();
  const select = document.getElementById('productSelect');
  if (!select) return;
  select.innerHTML = '';

  let filtered = getFilteredCalculatorProducts();
  if (filtered.length === 0) {
    filtered = BANK_PRODUCTS;
  }

  filtered.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    const isLiab = p.category === 'liability';
    const rateTxt = getProductRateRangeLabel(p);
    opt.textContent = `${isLiab ? '💰' : '💼'} ${p.name} (${rateTxt} • ${String(p.rateType).toUpperCase()})`;
    select.appendChild(opt);
  });

  const preserved = preserveId ? filtered.find(p => p.id === preserveId) : null;
  currentProduct = preserved || filtered[0] || BANK_PRODUCTS[0];
  if (currentProduct) select.value = currentProduct.id;
  syncUiForSelectedProduct();
  populateTenures();
}

function getProductRateRangeLabel(p) {
  if (p.category === 'liability' && p.tenureRates && Object.keys(p.tenureRates).length > 0) {
    const vals = Object.values(p.tenureRates).map(Number);
    const minR = Math.min(...vals);
    const maxR = Math.max(...vals);
    return minR === maxR ? `${minR}%` : `${minR}%-${maxR}%`;
  }
  if (Array.isArray(p.rateSlabs) && p.rateSlabs.length > 0) {
    const vals = p.rateSlabs.map(s => Number(s.annualRate));
    const minR = Math.min(...vals);
    const maxR = Math.max(...vals);
    return minR === maxR ? `${minR}%` : `${minR}%-${maxR}%`;
  }
  return `${p.defaultAnnualRate}%`;
}

function syncUiForSelectedProduct() {
  if (!currentProduct) return;
  const isLiab = currentProduct.category === 'liability';
  const amountLabel = document.getElementById('amountLabel');
  const amountInput = document.getElementById('loanAmount');
  const dbrContent = document.getElementById('dbrCardContent');
  const depContent = document.getElementById('depositSummaryCardContent');
  const amortQuickRow = document.getElementById('amortQuickRow');
  const viewColBtn = document.getElementById('viewCollateralBtn');

  if (isLiab) {
    if (amountLabel) amountLabel.textContent = 'Desired Deposit Amount (PKR)';
    if (amountInput) amountInput.placeholder = `Min Deposit: ${formatPKR(currentProduct.minAmount || 25000)}`;
    if (dbrContent) dbrContent.style.display = 'none';
    if (depContent) depContent.style.display = 'block';
    if (amortQuickRow) amortQuickRow.style.display = 'none';
    if (viewColBtn) viewColBtn.textContent = `🛡️ View Deposit Rules & Terms (${currentProduct.name})`;
  } else {
    if (amountLabel) amountLabel.textContent = 'Desired Loan Amount (PKR)';
    if (amountInput) amountInput.placeholder = 'Blank = Auto-Max from DBR';
    if (dbrContent) dbrContent.style.display = 'block';
    if (depContent) depContent.style.display = 'none';
    if (amortQuickRow) amortQuickRow.style.display = 'flex';
    if (viewColBtn) viewColBtn.textContent = `🛡️ View Security & Collateral Requirements (${currentProduct.name})`;
  }
}

function populateTenures() {
  const tenureSelect = document.getElementById('tenureMonths');
  if (!tenureSelect || !currentProduct) return;
  const prevTenure = parseInt(tenureSelect.value, 10) || selectedMatrixTenure;
  tenureSelect.innerHTML = '';

  const pMin = Math.max(1, Number(currentProduct.minTenure) || 1);
  const pMax = Math.max(pMin, Number(currentProduct.maxTenure) || 36);
  let tenures = [];

  if (currentProduct.category === 'liability') {
    if (Array.isArray(currentProduct.tenures) && currentProduct.tenures.length > 0) {
      tenures = currentProduct.tenures;
    } else if (currentProduct.tenureRates && Object.keys(currentProduct.tenureRates).length > 0) {
      tenures = Object.keys(currentProduct.tenureRates).map(Number).sort((a, b) => a - b);
    } else {
      tenures = [6, 12, 24, 36, 60];
    }
  } else {
    for (let t = pMin; t <= pMax; t++) {
      tenures.push(t);
    }
  }

  tenures.forEach(m => {
    const opt = document.createElement('option');
    opt.value = m;
    opt.textContent = m % 12 === 0 ? `${m} Months (${m / 12} ${m === 12 ? 'Yr' : 'Yrs'})` : `${m} Months`;
    tenureSelect.appendChild(opt);
  });

  if (tenures.includes(prevTenure)) {
    tenureSelect.value = prevTenure;
    selectedMatrixTenure = prevTenure;
  } else if (tenures.includes(12)) {
    tenureSelect.value = 12;
    selectedMatrixTenure = 12;
  } else {
    tenureSelect.value = tenures[0];
    selectedMatrixTenure = tenures[0];
  }
}

function onProductChange() {
  const id = document.getElementById('productSelect').value;
  currentProduct = BANK_PRODUCTS.find(p => p.id === id) || BANK_PRODUCTS[0];
  matrixTenureFilter = 'All';
  syncUiForSelectedProduct();
  populateTenures();
  calculate();
}

function onTenureSelectChange() {
  selectedMatrixTenure = parseInt(document.getElementById('tenureMonths').value, 10) || 12;
  calculate();
}

function clearDesiredAmount() {
  document.getElementById('loanAmount').value = '';
  calculate();
}

function setFilerStatus(val) {
  isFilerCustomer = val;
  document.getElementById('filerChipBtn').className = 'chip-btn' + (val ? ' active-teal' : '');
  document.getElementById('nonFilerChipBtn').className = 'chip-btn' + (!val ? ' active-danger' : '');
  calculate();
}

function onDobOrCalcChange() {
  updateAgeEligibilityUI();
  calculate();
}

function onCustomerProfileInput() {
  // Trigger subtle live state refresh if needed
}

function resetCalculatorForm() {
  document.getElementById('customerName').value = '';
  document.getElementById('customerMobile').value = '';
  document.getElementById('customerDob').value = '';
  document.getElementById('leadNotes').value = '';
  document.getElementById('loanAmount').value = '';
  document.getElementById('monthlyIncome').value = '';
  document.getElementById('allowedDbr').value = '50';
  updateAgeEligibilityUI();
  calculate();
}

// 4. MASTER CALCULATOR & ELIGIBILITY MATRIX UPDATER
let lastCalculation = null;

function calculate() {
  if (!currentProduct) return;
  updateAgeEligibilityUI();

  const rawAmtStr = document.getElementById('loanAmount').value.trim();
  const manualAmt = parseFloat(rawAmtStr);
  const isAutoMaxMode = currentProduct.category !== 'liability' && (isNaN(manualAmt) || manualAmt <= 0);

  const tenure = parseInt(document.getElementById('tenureMonths').value, 10) || selectedMatrixTenure || 12;
  selectedMatrixTenure = tenure;

  const monthlyIncome = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const allowedDbr = parseFloat(document.getElementById('allowedDbr').value) || 0;

  const amount = getPrincipalForTenure(tenure);
  const rate = getApplicableRate(currentProduct, amount, tenure);

  // Update Hint under Desired Loan Amount
  const hintEl = document.getElementById('desiredAmountHint');
  if (hintEl) {
    if (currentProduct.category === 'liability') {
      hintEl.style.color = 'var(--teal)';
      hintEl.textContent = rate <= 0
        ? `Deposit: PKR ${formatPKR(amount)} • 0% Profit`
        : `Deposit: PKR ${formatPKR(amount)} • ${rate}% p.a.`;
    } else if (isAutoMaxMode) {
      hintEl.style.color = 'var(--accent)';
      hintEl.textContent = '⚡ Auto-Max from DBR';
    } else {
      hintEl.style.color = 'var(--primary)';
      hintEl.textContent = `Fixed: PKR ${formatPKR(amount)} @ ${rate}%`;
    }
  }

  document.getElementById('resRate').textContent = `${Number(rate).toFixed(2).replace(/\.00$/, '.0')}%`;
  document.getElementById('resPrincipal').textContent = `PKR ${formatPKR(amount)}`;

  if (currentProduct.category !== 'liability') {
    document.getElementById('filerTaxSelectorCard').style.display = 'none';
    document.getElementById('currentAccountNotice').style.display = 'none';

    const maxAllowedEmi = (monthlyIncome > 0 && allowedDbr > 0) ? Math.round((monthlyIncome * allowedDbr) / 100.0) : 0;
    const capEl = document.getElementById('dbrCapResult');
    if (capEl) {
      capEl.textContent = maxAllowedEmi > 0
        ? `${globalConfig.currency || 'PKR'} ${formatPKR(maxAllowedEmi)} / mo`
        : 'Enter Income & DBR';
    }

    const emiRes = calculateEmi(amount, rate, tenure, currentProduct.rateType);
    const effectiveFed = Number(currentProduct.fedRate) || Number(globalConfig.fedRate) || 16.0;
    const feeRes = calculateProcessingFee(currentProduct, amount, effectiveFed);
    const dbrRatio = monthlyIncome > 0 ? ((emiRes.emi / monthlyIncome) * 100).toFixed(1) : '0.0';

    document.getElementById('resPrincipalLabel').textContent = isAutoMaxMode ? `Auto-Max Loan (${tenure}M):` : `Loan Principal (${tenure}M):`;
    document.getElementById('rateLabel').textContent = 'Applied Profit Rate:';
    document.getElementById('emiRowLabel').textContent = 'Monthly Installment (EMI):';
    document.getElementById('dbrRow').style.display = 'flex';
    document.getElementById('feeRowLabel').textContent = `Processing Fee (inc. ${effectiveFed}% FED):`;
    document.getElementById('totalPayableLabel').textContent = 'Total Repayable Amount:';

    document.getElementById('resEmi').textContent = `PKR ${formatPKR(emiRes.emi)}`;
    document.getElementById('resDbr').textContent = monthlyIncome > 0
      ? `${dbrRatio}% ${parseFloat(dbrRatio) > allowedDbr + 0.05 ? '❌ Exceeds DBR' : '✅ Within Limit'}`
      : 'Enter Income';
    document.getElementById('resFee').textContent = `PKR ${formatPKR(feeRes.totalFee)}`;
    document.getElementById('resTotalPayable').textContent = `PKR ${formatPKR(emiRes.totalPayable)}`;

    const badge = document.getElementById('matrixModeBadge');
    if (badge) badge.textContent = isAutoMaxMode ? 'AUTO-MAX DBR' : 'FIXED AMOUNT';

    lastCalculation = {
      category: 'asset',
      isAutoMaxMode,
      amount,
      tenure,
      rate,
      emi: emiRes.emi,
      totalProfit: emiRes.totalProfit,
      totalFee: feeRes.totalFee,
      totalPayable: emiRes.totalPayable,
      dbrRatio: parseFloat(dbrRatio),
      monthlyIncome,
      allowedDbr
    };
  } else {
    const filerRate = Number(globalConfig.filerTaxRate) || 20.0;
    const nonFilerRate = Number(globalConfig.nonFilerTaxRate) || 40.0;
    document.getElementById('filerRateChipTxt').textContent = filerRate;
    document.getElementById('nonFilerRateChipTxt').textContent = nonFilerRate;

    if (rate <= 0) {
      document.getElementById('filerTaxSelectorCard').style.display = 'none';
      document.getElementById('currentAccountNotice').style.display = 'block';
    } else {
      document.getElementById('filerTaxSelectorCard').style.display = 'block';
      document.getElementById('currentAccountNotice').style.display = 'none';
    }

    const depRes = calculateDepositProfit(amount, rate, tenure, isFilerCustomer, filerRate, nonFilerRate);

    document.getElementById('depGrossMo').textContent = `PKR ${formatPKR(depRes.monthlyProfit)} / mo`;
    document.getElementById('depTaxMo').textContent = `Tax (${depRes.taxRate}%): -PKR ${formatPKR(depRes.monthlyTax)}`;
    document.getElementById('depNetMo').textContent = `Net: PKR ${formatPKR(depRes.netMonthlyProfit)} / mo`;
    document.getElementById('depTermTitle').textContent = `Total Profit (${tenure}M):`;
    document.getElementById('depGrossTot').textContent = `PKR ${formatPKR(depRes.totalProfit)}`;
    document.getElementById('depTaxTot').textContent = `Tax: -PKR ${formatPKR(depRes.totalTax)}`;
    document.getElementById('depNetTot').textContent = `Net: PKR ${formatPKR(depRes.netTotalProfit)}`;
    document.getElementById('depNetMaturity').textContent = `PKR ${formatPKR(depRes.netMaturityAmount)}`;

    document.getElementById('resPrincipalLabel').textContent = `Deposit Amount (${tenure}M):`;
    document.getElementById('rateLabel').textContent = 'Annual Profit Rate:';
    document.getElementById('emiRowLabel').textContent = 'Net Monthly Profit:';
    document.getElementById('dbrRow').style.display = 'none';
    document.getElementById('feeRowLabel').textContent = `WHT Tax (${depRes.taxRate}%):`;
    document.getElementById('totalPayableLabel').textContent = 'Net Maturity Payout:';

    document.getElementById('resEmi').textContent = `PKR ${formatPKR(depRes.netMonthlyProfit)}`;
    document.getElementById('resFee').textContent = `PKR ${formatPKR(depRes.totalTax)}`;
    document.getElementById('resTotalPayable').textContent = `PKR ${formatPKR(depRes.netMaturityAmount)}`;

    const badge = document.getElementById('matrixModeBadge');
    if (badge) badge.textContent = 'DEPOSIT PLAN';

    lastCalculation = {
      category: 'liability',
      amount,
      tenure,
      rate,
      netMonthlyProfit: depRes.netMonthlyProfit,
      totalTax: depRes.totalTax,
      netTotalProfit: depRes.netTotalProfit,
      netMaturity: depRes.netMaturityAmount,
      isFiler: isFilerCustomer,
      taxRate: depRes.taxRate
    };
  }

  renderEligibilityMatrix();
}

function renderMatrixFilterChips(pMin, pMax) {
  const container = document.getElementById('matrixFilterChips');
  if (!container) return;
  if (currentProduct.category === 'liability') {
    container.innerHTML = '';
    return;
  }
  const ranges = [
    { key: '1-12', min: 1, max: 12 },
    { key: '13-36', min: 13, max: 36 },
    { key: '37-60', min: 37, max: 60 },
    { key: '61-120', min: 61, max: 120 }
  ].filter(r => pMin <= r.max && pMax >= r.min).map(r => r.key);

  const chips = ['All', ...ranges];
  if (!chips.includes(matrixTenureFilter)) matrixTenureFilter = 'All';

  container.innerHTML = chips.map(c => `
    <button type="button" class="chip-btn ${matrixTenureFilter === c ? 'active' : ''}" onclick="setMatrixTenureFilter('${c}')">${c}</button>
  `).join('');
}

function setMatrixTenureFilter(filterKey) {
  matrixTenureFilter = filterKey;
  renderEligibilityMatrix();
}

function renderEligibilityMatrix() {
  if (!currentProduct) return;
  const isLiab = currentProduct.category === 'liability';
  const pMin = Math.max(1, Number(currentProduct.minTenure) || 1);
  const pMax = Math.max(pMin, Number(currentProduct.maxTenure) || 36);

  renderMatrixFilterChips(pMin, pMax);

  const income = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const dbr = parseFloat(document.getElementById('allowedDbr').value) || 0;
  const rawAmt = document.getElementById('loanAmount').value.trim();
  const hasManualAmt = !isNaN(parseFloat(rawAmt)) && parseFloat(rawAmt) > 0;
  const isAutoMaxMode = !isLiab && !hasManualAmt;

  const emptyNotice = document.getElementById('matrixEmptyNotice');
  const tableBox = document.getElementById('matrixTableContainer');
  const thead = document.getElementById('matrixTableHead');
  const tbody = document.getElementById('matrixTableBody');

  if (!isLiab && isAutoMaxMode && (income <= 0 || dbr <= 0)) {
    emptyNotice.style.display = 'block';
    tableBox.style.display = 'none';
    return;
  }
  emptyNotice.style.display = 'none';
  tableBox.style.display = 'block';

  const dobVal = document.getElementById('customerDob').value;
  const ageInfo = evaluateAgeEligibility(dobVal, currentProduct);

  if (isLiab) {
    const appliedDep = hasManualAmt ? Math.round(parseFloat(rawAmt)) : (Number(currentProduct.minAmount) || 25000);
    const isAmountTiered = currentProduct.liabilityPricingType === 'amount' ||
      ((!currentProduct.tenureRates || Object.keys(currentProduct.tenureRates).length === 0) && Array.isArray(currentProduct.rateSlabs) && currentProduct.rateSlabs.length > 1);
    let tenures = isAmountTiered
      ? [1, 3, 6, 12]
      : (Array.isArray(currentProduct.tenures) && currentProduct.tenures.length > 0 ? currentProduct.tenures : [6, 12, 24, 36, 60]);

    thead.innerHTML = `
      <tr>
        <th>Tenure</th>
        <th>Deposit</th>
        <th>Rate %</th>
        <th>Net Mo</th>
        <th>Net Profit</th>
        <th>Net Payout</th>
      </tr>
    `;

    const filerRate = Number(globalConfig.filerTaxRate) || 20.0;
    const nonFilerRate = Number(globalConfig.nonFilerTaxRate) || 40.0;

    tbody.innerHTML = tenures.map(t => {
      const r = getApplicableRate(currentProduct, appliedDep, t);
      const dRes = calculateDepositProfit(appliedDep, r, t, isFilerCustomer, filerRate, nonFilerRate);
      const isSelected = selectedMatrixTenure === t;
      return `
        <tr class="matrix-row-liab ${isSelected ? 'selected' : ''}" onclick="selectDepositMatrixTenure(${t})">
          <td><strong>${t}M</strong></td>
          <td>${formatPKR(appliedDep)}</td>
          <td><strong>${r}%</strong></td>
          <td style="color:#047857;"><strong>${formatPKR(dRes.netMonthlyProfit)}</strong></td>
          <td>${formatPKR(dRes.netTotalProfit)}</td>
          <td><strong>${formatPKR(dRes.netMaturityAmount)}</strong></td>
        </tr>
      `;
    }).join('');
    return;
  }

  // ASSET MATRIX
  const effMaxEmi = (income > 0 && dbr > 0) ? (income * dbr) / 100.0 : 0;
  const maxAllowedByAge = dobVal
    ? (ageInfo.isEligible ? Math.min(pMax, ageInfo.remainingMonthsToMaturity) : 0)
    : pMax;

  let tenures = [];
  for (let t = pMin; t <= pMax; t++) {
    if (matrixTenureFilter === '1-12' && (t < 1 || t > 12)) continue;
    if (matrixTenureFilter === '13-36' && (t < 13 || t > 36)) continue;
    if (matrixTenureFilter === '37-60' && (t < 37 || t > 60)) continue;
    if (matrixTenureFilter === '61-120' && (t < 61 || t > 120)) continue;
    tenures.push(t);
  }

  thead.innerHTML = `
    <tr>
      <th>Mo</th>
      <th>Principal</th>
      <th>EMI</th>
      <th>Profit</th>
      <th>Fee+FED</th>
      <th>DBR % / Fit</th>
    </tr>
  `;

  const effectiveFed = Number(currentProduct.fedRate) || Number(globalConfig.fedRate) || 16.0;

  tbody.innerHTML = tenures.map(t => {
    const pForT = getPrincipalForTenure(t);
    const rForT = getApplicableRate(currentProduct, pForT, t);
    const emiRes = calculateEmi(pForT, rForT, t, currentProduct.rateType);
    const feeRes = calculateProcessingFee(currentProduct, pForT, effectiveFed);

    const dbrVal = income > 0 ? ((emiRes.emi / income) * 100.0) : 0;
    const dbrPct = income > 0 ? dbrVal.toFixed(1) + '%' : 'N/A';

    const isDbrFit = income > 0
      ? (pForT > 0 && (emiRes.emi <= effMaxEmi || dbrVal <= dbr + 0.05))
      : (pForT > 0);
    const isAgeFit = dobVal ? (ageInfo.isEligible && t <= maxAllowedByAge) : true;
    const isFit = isDbrFit && isAgeFit;
    const isSelected = selectedMatrixTenure === t;

    const statusText = !isAgeFit
      ? '❌ Age Limit'
      : (income > 0 ? `${dbrPct} ${isDbrFit ? '✅' : '❌'}` : '📋 Tap View');

    return `
      <tr class="${isFit ? 'matrix-row-eligible' : 'matrix-row-ineligible'} ${isSelected ? 'selected' : ''}" onclick="openAmortizationForTenure(${t})">
        <td><strong>${t}M</strong></td>
        <td><strong>${formatPKR(pForT)}</strong></td>
        <td><strong>${formatPKR(emiRes.emi)}</strong></td>
        <td>${formatPKR(emiRes.totalProfit)}</td>
        <td>${formatPKR(feeRes.totalFee)}</td>
        <td><strong>${statusText}</strong></td>
      </tr>
    `;
  }).join('');
}

function selectDepositMatrixTenure(t) {
  selectedMatrixTenure = t;
  const sel = document.getElementById('tenureMonths');
  if (sel) sel.value = t;
  calculate();
}

function openAmortizationForTenure(t) {
  selectedMatrixTenure = t;
  const sel = document.getElementById('tenureMonths');
  if (sel) sel.value = t;
  calculate();
  openAmortizationModal(t);
}

// 5. SECURITY & COLLATERAL MODAL
function openCollateralModal() {
  if (!currentProduct) return;
  const p = currentProduct;
  document.getElementById('collateralModalTitle').textContent = `🛡️ ${p.name} — Security & Rules`;

  let html = '';
  const details = p.collateralDetails || (p.category === 'liability'
    ? 'Valid CNIC, biometric verification, and minimum initial deposit per bank SOC.'
    : 'Promissory note, personal/third-party guarantee or salary deduction undertaking per KBL SOC.');

  html += `
    <div style="background: var(--surface-subtle); border: 1px solid var(--border); border-radius: 10px; padding: 12px; margin-bottom: 12px;">
      <strong style="color: var(--primary); display: block; margin-bottom: 4px;">Security / Terms Details:</strong>
      <div>${escapeHtml(details)}</div>
    </div>
    <div class="result-row"><span>Category / Pricing:</span><strong>${p.category.toUpperCase()} (${String(p.rateType).toUpperCase()})</strong></div>
    <div class="result-row"><span>Amount Range:</span><strong>PKR ${formatPKR(p.minAmount)} – PKR ${formatPKR(p.maxAmount)}</strong></div>
    <div class="result-row"><span>Tenure Range:</span><strong>${p.minTenure} – ${p.maxTenure} Months</strong></div>
    <div class="result-row"><span>Age Requirement:</span><strong>${p.minAge || 21} – ${p.maxAgeAtMaturity || 65} Years (at maturity)</strong></div>
  `;

  if (Array.isArray(p.rateSlabs) && p.rateSlabs.length > 0) {
    html += `<h4 style="margin: 12px 0 6px; color: var(--primary);">📈 Tiered Rate Slabs:</h4>`;
    html += p.rateSlabs.map(s => `<div class="result-row"><span>PKR ${formatPKR(s.minAmount)} – ${formatPKR(s.maxAmount)}</span><strong>${s.annualRate}%</strong></div>`).join('');
  }
  if (Array.isArray(p.feeSlabs) && p.feeSlabs.length > 0) {
    html += `<h4 style="margin: 12px 0 6px; color: var(--primary);">🧾 Processing Fee Slabs:</h4>`;
    html += p.feeSlabs.map(f => `<div class="result-row"><span>PKR ${formatPKR(f.minAmount)} – ${formatPKR(f.maxAmount)}</span><strong>${f.feeType === 'fixed' ? 'PKR ' + formatPKR(f.feeValue) : f.feeValue + '%'}</strong></div>`).join('');
  }

  document.getElementById('collateralModalBody').innerHTML = html;
  openModal('collateralModal');
}

// 6. INTERACTIVE AMORTIZATION SCHEDULE, EXTRA PAYMENTS, SETTLEMENT & PRINT PDF
function generateInteractiveSchedule(principal, annualRate, tenureMonths, rateType, extraPayments = {}, mode = 'reduce_emi') {
  const orig = calculateEmi(principal, annualRate, tenureMonths, rateType);
  const rows = [];
  let balance = principal;
  const monthlyRate = (annualRate / 100.0) / 12.0;
  let revisedTotalProfit = 0;
  let totalExtraPaid = 0;
  let currentEmi = orig.emi;

  const baseDate = new Date();
  const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  for (let m = 1; m <= tenureMonths; m++) {
    if (balance <= 0) break;
    const dueDt = new Date(baseDate.getFullYear(), baseDate.getMonth() + m, 1);
    const dueStr = `${monthNames[dueDt.getMonth()]} ${dueDt.getFullYear()}`;

    const profitPart = Math.round(balance * monthlyRate);
    revisedTotalProfit += profitPart;

    const standardPrincipal = currentEmi > profitPart ? (currentEmi - profitPart) : 0;
    const actualPrincipal = Math.min(balance, standardPrincipal);
    const extra = Math.max(0, Math.round(Number(extraPayments[m]) || 0));
    totalExtraPaid += extra;

    const totalPrincipalThisMonth = Math.min(balance, actualPrincipal + extra);
    const emiThisMonth = totalPrincipalThisMonth + profitPart;
    const closing = Math.max(0, balance - totalPrincipalThisMonth);

    rows.push({
      month: m,
      dueDate: dueStr,
      openingBalance: balance,
      principal: actualPrincipal,
      profit: profitPart,
      emi: emiThisMonth,
      extraPayment: extra,
      closingBalance: closing
    });

    balance = closing;

    if (mode === 'reduce_emi' && extra > 0 && balance > 0 && m < tenureMonths) {
      const remainingMonths = tenureMonths - m;
      currentEmi = calculateEmi(balance, annualRate, remainingMonths, rateType).emi;
    }
  }

  const summary = {
    mode,
    originalTotalProfit: orig.totalProfit,
    revisedTotalProfit,
    totalProfitSaved: Math.max(0, orig.totalProfit - revisedTotalProfit),
    originalMonthlyEmi: orig.emi,
    revisedMonthlyEmi: mode === 'reduce_emi' ? currentEmi : orig.emi,
    originalTenure: tenureMonths,
    revisedTenure: rows.length,
    monthsSaved: Math.max(0, tenureMonths - rows.length),
    totalExtraPaid
  };

  return { rows, summary };
}

function openAmortizationModal(customTenure = null) {
  if (!currentProduct || currentProduct.category === 'liability') {
    showToast('Amortization schedule is for Asset Loan products.');
    return;
  }
  const tenure = customTenure || selectedMatrixTenure || (lastCalculation ? lastCalculation.tenure : 12);
  const principal = getPrincipalForTenure(tenure);
  if (principal <= 0) {
    showToast('Enter Monthly Income & DBR or Desired Loan Amount first.');
    return;
  }
  const rate = getApplicableRate(currentProduct, principal, tenure);

  amortState.principal = principal;
  amortState.rate = rate;
  amortState.tenure = tenure;
  amortState.rateType = currentProduct.rateType || 'reducing';
  amortState.extraPayments = {};

  document.getElementById('amortHeaderSubtitle').textContent = `${currentProduct.name} • PKR ${formatPKR(principal)} • ${tenure}M @ ${rate}%`;
  renderAmortizationSchedule();
  openModal('amortizationModal');
}

function setPrepaymentMode(mode) {
  amortState.mode = mode;
  document.getElementById('modeReduceEmiBtn').className = 'chip-btn' + (mode === 'reduce_emi' ? ' active' : '');
  document.getElementById('modeReduceTenureBtn').className = 'chip-btn' + (mode === 'reduce_tenure' ? ' active' : '');
  renderAmortizationSchedule();
}

function updateAmortExtraPayment(month, valStr) {
  const num = Math.max(0, Math.round(parseFloat(valStr) || 0));
  if (num > 0) {
    amortState.extraPayments[month] = num;
  } else {
    delete amortState.extraPayments[month];
  }
  renderAmortizationSchedule(month);
}

function clearAllExtraPayments() {
  amortState.extraPayments = {};
  renderAmortizationSchedule();
}

function renderAmortizationSchedule(focusedMonth = null) {
  const { rows, summary } = generateInteractiveSchedule(
    amortState.principal,
    amortState.rate,
    amortState.tenure,
    amortState.rateType,
    amortState.extraPayments,
    amortState.mode
  );
  amortState.rows = rows;
  amortState.summary = summary;

  const hasExtras = Object.keys(amortState.extraPayments).length > 0;
  document.getElementById('resetExtrasBtn').style.display = hasExtras ? 'inline-flex' : 'none';

  const effEmi = (summary.totalExtraPaid > 0 && summary.mode === 'reduce_emi')
    ? summary.revisedMonthlyEmi
    : summary.originalMonthlyEmi;

  document.getElementById('amortSumEmi').textContent = `PKR ${formatPKR(effEmi)}`;
  document.getElementById('amortSumTenure').textContent = summary.monthsSaved > 0
    ? `${summary.revisedTenure}M (Saved ${summary.monthsSaved}M)`
    : `${summary.revisedTenure}M`;
  document.getElementById('amortSumProfit').textContent = `PKR ${formatPKR(summary.revisedTotalProfit)}`;
  document.getElementById('amortSumSaved').textContent = `PKR ${formatPKR(summary.totalProfitSaved)}`;

  const tbody = document.getElementById('amortTableBody');
  tbody.innerHTML = rows.map(r => {
    const extraVal = amortState.extraPayments[r.month] || '';
    return `
      <tr onclick="onAmortRowClick(event, ${r.month})">
        <td style="text-align: center; font-weight: 800; color: var(--primary);">${r.month}</td>
        <td>${escapeHtml(r.dueDate)}</td>
        <td>${formatPKR(r.principal)}</td>
        <td>${formatPKR(r.profit)}</td>
        <td><strong>${formatPKR(r.emi)}</strong></td>
        <td onclick="event.stopPropagation()">
          <input type="number" class="extra-pay-input" data-extra-mo="${r.month}" value="${extraVal}" placeholder="0" onchange="updateAmortExtraPayment(${r.month}, this.value)">
        </td>
        <td><strong>${formatPKR(r.closingBalance)}</strong></td>
      </tr>
    `;
  }).join('');

  if (focusedMonth !== null) {
    const inp = tbody.querySelector(`input[data-extra-mo="${focusedMonth}"]`);
    if (inp) inp.focus();
  }
}

function onAmortRowClick(event, month) {
  if (event.target && event.target.tagName === 'INPUT') return;
  openSettlementBreakdownModal(month);
}

function openSettlementBreakdownModal(month) {
  const row = amortState.rows.find(r => r.month === month);
  if (!row) return;
  amortState.selectedMonth = month;

  const rowsUpToM = amortState.rows.filter(r => r.month <= month);
  const cumPrincipal = rowsUpToM.reduce((s, r) => s + r.principal, 0);
  const cumProfit = rowsUpToM.reduce((s, r) => s + r.profit, 0);
  const cumExtra = rowsUpToM.reduce((s, r) => s + r.extraPayment, 0);
  const cumTotalPaid = rowsUpToM.reduce((s, r) => s + r.emi + (r.extraPayment > 0 ? 0 : 0), 0);

  const remPrincipal = row.closingBalance;
  const totalSchedProfit = amortState.rows.reduce((s, r) => s + r.profit, 0);
  const remProfit = Math.max(0, totalSchedProfit - cumProfit);
  const remTotal = remPrincipal + remProfit;
  const remMonths = Math.max(0, amortState.rows.length - month);
  const pctRepaid = amortState.principal > 0 ? Math.min(100, ((cumPrincipal + cumExtra) / amortState.principal) * 100) : 0;

  document.getElementById('settlementTitle').textContent = `📊 Month ${month} Settlement Summary`;
  document.getElementById('settlementSubtitle').textContent = `قسط نمبر ${month} تک جمع شدہ اور بقایا تفصیل (${row.dueDate})`;
  document.getElementById('settlePctText').textContent = `${pctRepaid.toFixed(1)}% Cleared`;
  document.getElementById('settleProgressBar').style.width = `${pctRepaid.toFixed(1)}%`;
  document.getElementById('settleTenureProgress').textContent = `${month} of ${amortState.rows.length} Months Completed`;

  document.getElementById('settlePaidPrincipal').textContent = `PKR ${formatPKR(cumPrincipal)}`;
  document.getElementById('settlePaidProfit').textContent = `PKR ${formatPKR(cumProfit)}`;
  document.getElementById('settlePaidExtraRow').style.display = cumExtra > 0 ? 'flex' : 'none';
  document.getElementById('settlePaidExtra').textContent = `PKR ${formatPKR(cumExtra)}`;
  document.getElementById('settleTotalPaid').textContent = `PKR ${formatPKR(cumTotalPaid)}`;

  document.getElementById('settleRemPrincipal').textContent = `PKR ${formatPKR(remPrincipal)}`;
  document.getElementById('settleRemProfit').textContent = `PKR ${formatPKR(remProfit)}`;
  document.getElementById('settleRemMonths').textContent = `${remMonths} Months`;
  document.getElementById('settleTotalRem').textContent = `PKR ${formatPKR(remTotal)}`;

  openModal('settlementModal');
}

function buildSettlementText() {
  const m = amortState.selectedMonth || 1;
  const cust = document.getElementById('customerName').value.trim() || 'Valued Client';
  return [
    `🏛️ *KBL SETTLEMENT & INSTALLMENT BREAKDOWN (Month ${m})*`,
    `👤 Customer: ${cust}`,
    `💼 Product: ${currentProduct.name}`,
    `────────────────────`,
    `✅ *Paid So Far (Month 1-${m}):*`,
    `• Principal Paid: ${document.getElementById('settlePaidPrincipal').textContent}`,
    `• Profit Paid: ${document.getElementById('settlePaidProfit').textContent}`,
    `• Total Paid: ${document.getElementById('settleTotalPaid').textContent}`,
    `────────────────────`,
    `⚠️ *Remaining Settlement Balance:*`,
    `• Remaining Principal: ${document.getElementById('settleRemPrincipal').textContent}`,
    `• Remaining Profit: ${document.getElementById('settleRemProfit').textContent}`,
    `• Remaining Tenure: ${document.getElementById('settleRemMonths').textContent}`,
    `• Total Remaining Liability: *${document.getElementById('settleTotalRem').textContent}*`,
    `────────────────────`,
    `🏦 Officer: ${officerProfile.officerName || 'Officer'} (${officerProfile.branchName || 'KBL Branch'})`
  ].join('\n');
}

function shareSettlementWhatsApp() {
  const mob = document.getElementById('customerMobile').value.trim();
  const phone = formatForWhatsApp(mob);
  const txt = buildSettlementText();
  const url = phone ? `https://wa.me/${phone}?text=${encodeURIComponent(txt)}` : `https://wa.me/?text=${encodeURIComponent(txt)}`;
  openExternalWhatsAppUrl(url);
}

function shareSettlementSMS() {
  const mob = document.getElementById('customerMobile').value.replace(/\D/g, '');
  const txt = buildSettlementText().replace(/\*/g, '');
  window.location.href = `sms:${mob}?body=${encodeURIComponent(txt)}`;
}

function copySettlementSummary() {
  copyToClipboard(buildSettlementText(), '📋 Settlement summary copied!');
}

function buildAmortizationShareText(includeRows = false) {
  const cust = document.getElementById('customerName').value.trim() || 'Valued Client';
  const mob = document.getElementById('customerMobile').value.trim() || 'N/A';
  const feeRes = calculateProcessingFee(currentProduct, amortState.principal, Number(globalConfig.fedRate) || 16.0);
  const s = amortState.summary || {};
  const effEmi = (s.totalExtraPaid > 0 && s.mode === 'reduce_emi') ? s.revisedMonthlyEmi : s.originalMonthlyEmi;

  const lines = [
    includeRows ? `🏛️ *KBL LOAN AMORTIZATION SCHEDULE*` : `🏛️ *KBL LOAN PROPOSAL & SUMMARY*`,
    `────────────────────`,
    `👤 Customer: ${cust}`,
    `📱 Mobile: ${mob}`,
    `💼 Product: ${currentProduct.name}`,
    `💰 Loan Amount: PKR ${formatPKR(amortState.principal)}`,
    `📅 Duration: ${amortState.tenure} Months @ ${amortState.rate}%`,
    `💵 Monthly EMI: PKR ${formatPKR(effEmi)}`,
    `🧾 Processing Fee: PKR ${formatPKR(feeRes.totalFee)}`
  ];

  if (s.totalExtraPaid > 0) {
    lines.push(`⚡ Extra Prepayments: PKR ${formatPKR(s.totalExtraPaid)}`);
    lines.push(`✨ Profit Saved: PKR ${formatPKR(s.totalProfitSaved)}`);
  }

  if (includeRows) {
    lines.push(`────────────────────`);
    lines.push(`Mo | Due | EMI | Profit | Balance`);
    amortState.rows.forEach(r => {
      lines.push(`${String(r.month).padStart(2, '0')} | ${r.dueDate} | ${formatPKR(r.emi)} | ${formatPKR(r.profit)} | ${formatPKR(r.closingBalance)}${r.extraPayment > 0 ? ' (Extra: ' + formatPKR(r.extraPayment) + ')' : ''}`);
    });
  }

  lines.push(`────────────────────`);
  lines.push(`🏦 Officer: ${officerProfile.officerName || 'Officer'} (${officerProfile.staffId || 'RO'}) • ${officerProfile.branchName || 'KBL Branch'}`);
  return lines.join('\n');
}

function shareAmortizationWhatsApp(copyOnly = false) {
  const txt = buildAmortizationShareText(copyOnly);
  if (copyOnly) {
    copyToClipboard(txt, '📋 Full Amortization text copied!');
    return;
  }
  const mob = document.getElementById('customerMobile').value.trim();
  const phone = formatForWhatsApp(mob);
  const url = phone ? `https://wa.me/${phone}?text=${encodeURIComponent(txt)}` : `https://wa.me/?text=${encodeURIComponent(txt)}`;
  openExternalWhatsAppUrl(url);
}

function shareAmortizationSMS() {
  const mob = document.getElementById('customerMobile').value.replace(/\D/g, '');
  const txt = buildAmortizationShareText(false).replace(/\*/g, '');
  window.location.href = `sms:${mob}?body=${encodeURIComponent(txt)}`;
}

// Print-Ready PDF Generator using @media print sheet
function printAmortizationPdf() {
  const sheet = document.getElementById('printOnlySheet');
  if (!sheet || !amortState.rows.length) {
    showToast('Open an amortization schedule first.');
    return;
  }
  const cust = document.getElementById('customerName').value.trim() || 'Valued Customer';
  const mob = document.getElementById('customerMobile').value.trim() || 'N/A';
  const dob = document.getElementById('customerDob').value || 'N/A';
  const feeRes = calculateProcessingFee(currentProduct, amortState.principal, Number(globalConfig.fedRate) || 16.0);
  const s = amortState.summary;

  sheet.innerHTML = `
    <div class="print-sheet-header">
      <div>
        <h2 style="color: #1E3A8A; margin-bottom: 2px;">KBL BANK — REPAYMENT &amp; AMORTIZATION SCHEDULE</h2>
        <div style="color: #475569; font-size: 10px;">Generated on ${new Date().toLocaleString()}</div>
      </div>
      <div style="text-align: right; font-size: 10px;">
        <strong>Officer:</strong> ${escapeHtml(officerProfile.officerName || 'Officer')} (${escapeHtml(officerProfile.staffId || 'RO')})<br>
        <strong>Branch:</strong> ${escapeHtml(officerProfile.branchName || 'KBL Branch')}
      </div>
    </div>

    <div class="print-sheet-grid">
      <div><strong>Customer Name:</strong> ${escapeHtml(cust)}</div>
      <div><strong>Mobile Number:</strong> ${escapeHtml(mob)}</div>
      <div><strong>Facility / Product:</strong> ${escapeHtml(currentProduct.name)}</div>
      <div><strong>Date of Birth:</strong> ${escapeHtml(dob)}</div>
      <div><strong>Loan Principal:</strong> PKR ${formatPKR(amortState.principal)}</div>
      <div><strong>Markup Rate / Mode:</strong> ${amortState.rate}% (${String(amortState.rateType).toUpperCase()})</div>
      <div><strong>Monthly EMI:</strong> PKR ${formatPKR(s.mode === 'reduce_emi' && s.totalExtraPaid > 0 ? s.revisedMonthlyEmi : s.originalMonthlyEmi)}</div>
      <div><strong>Processing Fee + FED:</strong> PKR ${formatPKR(feeRes.totalFee)}</div>
      <div><strong>Tenure:</strong> ${s.revisedTenure} Months ${s.monthsSaved > 0 ? '(Saved ' + s.monthsSaved + 'M)' : ''}</div>
      <div><strong>Total Profit ${s.totalProfitSaved > 0 ? '(Saved PKR ' + formatPKR(s.totalProfitSaved) + ')' : ''}:</strong> PKR ${formatPKR(s.revisedTotalProfit)}</div>
    </div>

    <table class="print-sheet-table">
      <thead>
        <tr>
          <th>Mo</th>
          <th>Due Month</th>
          <th>Opening</th>
          <th>Principal</th>
          <th>Profit</th>
          <th>Extra Paid</th>
          <th>Total EMI</th>
          <th>Closing Bal</th>
        </tr>
      </thead>
      <tbody>
        ${amortState.rows.map(r => `
          <tr>
            <td>${r.month}</td>
            <td style="text-align: left;">${escapeHtml(r.dueDate)}</td>
            <td>${formatPKR(r.openingBalance)}</td>
            <td>${formatPKR(r.principal)}</td>
            <td>${formatPKR(r.profit)}</td>
            <td>${r.extraPayment > 0 ? formatPKR(r.extraPayment) : '-'}</td>
            <td><strong>${formatPKR(r.emi)}</strong></td>
            <td><strong>${formatPKR(r.closingBalance)}</strong></td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;

  window.print();
}
