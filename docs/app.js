/**
 * KB Loan Calculator & Lead CRM - Standalone PWA Engine
 * Complete offline-first calculator, IndexedDB persistence, Firebase Auth (Google Sign-In),
 * real-time Firestore synchronization (Named DB parity with Android), branch filtering for iPhone/Web,
 * Allow/Deny user access control, WhatsApp/SMS quotations, and Google Sheets in-place sync.
 */

// 1. FIREBASE BRIDGE STATE
let fb = null;
let currentUser = null;
let currentUserProfile = null;
let firebaseInitialized = false;
let unsubscribeProducts = null;
let unsubscribeLeads = null;
let unsubscribeGlobalConfig = null;
let unsubscribeUserDoc = null;

let globalConfig = {
  fedRate: 16.0,
  filerTaxRate: 20.0,
  nonFilerTaxRate: 40.0,
  currency: 'PKR',
  googleSheetsWebhookUrl: '',
  registeredUsersMap: {},
  deletedUserKeys: []
};

// 2. BANK PRODUCTS CONFIGURATION (DEFAULT SEED & DYNAMIC FIRESTORE RECEPTACLE)
let BANK_PRODUCTS = [
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
    fedRate: 16.0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
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
    fedRate: 16.0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
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
    fedRate: 16.0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
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
    fedRate: 16.0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
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
    fedRate: 16.0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
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
    fedRate: 0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
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
    fedRate: 0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
  },
  {
    id: "prod-liab-pls-savings",
    category: "liability",
    name: "PLS Regular Savings Account",
    rateType: "fixed",
    defaultAnnualRate: 15.0,
    minAmount: 1000,
    maxAmount: 100000000,
    minTenure: 1,
    maxTenure: 12,
    tenures: [1, 3, 6, 12],
    feePercent: 0,
    fedRate: 0,
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
  }
];

// 3. RECURSIVE DE-NESTING PAYLOAD SANITIZER (Standard JavaScript Regex)
function cleanRawActionPayload(raw) {
  if (!raw || typeof raw !== 'string') return '';
  let text = raw.trim();
  let prev = '';
  let iterations = 0;
  while (text !== prev && iterations < 12) {
    prev = text;
    iterations++;
    text = text.replace(/\s*-\s*By\s*:\s*[^\-\[\]]+(?:\([^\)]*\))?\s*$/gi, '').trim();
    text = text.replace(/\s*-\s*By\s*:.*$/gi, '').trim();
    text = text.replace(/^•?\s*\[[^\]]*\]\s*/g, '').trim();
    text = text.replace(/^(STATUS_CHANGE|STATUS|FOLLOW_UP|FOLLOWUP|CALL|PHONE|WHATSAPP|SMS|CREATED|PROPOSAL_UPDATE|PROPOSAL|NOTE_UPDATE|NOTE):\s*/gi, '').trim();
    text = text.replace(/^[🔄📅⏰📞💬✉️🎯📋📝✏️•\s]+/g, '').trim();
    text = text.replace(/^(Note Added|Status Changed|Follow-Up Date|Follow-Up Scheduled|Phone Call|Direct Phone Call|WhatsApp|WhatsApp Quotation|SMS|SMS Quotation|Lead Created|Loan Lead Created|Deposit Lead Created|Proposal Saved|Note \/ Comment Updated|Initial Customer Note):\s*/gi, '').trim();
    text = text.replace(/^(Current:|Note:|Notes:|Initial Note:)\s*/gi, '').trim();
    text = text.replace(/^(Lead status updated from |Lead status updated to |Scheduled follow-up for )/gi, '').trim();
  }
  return text.trim();
}

// 4. INDEXEDDB PERSISTENCE LAYER
class LocalDB {
  static init() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open('KBCalculatorDB', 2);
      request.onupgradeneeded = (e) => {
        const d = e.target.result;
        if (!d.objectStoreNames.contains('leads')) {
          d.createObjectStore('leads', { keyPath: 'id' });
        }
        if (!d.objectStoreNames.contains('settings')) {
          d.createObjectStore('settings', { keyPath: 'key' });
        }
      };
      request.onsuccess = (e) => resolve(e.target.result);
      request.onerror = (e) => reject(e);
    });
  }

  static async getAllLeads() {
    const d = await this.init();
    return new Promise((resolve) => {
      const tx = d.transaction('leads', 'readonly');
      const store = tx.objectStore('leads');
      const req = store.getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  }

  static async saveLead(lead) {
    const d = await this.init();
    return new Promise((resolve) => {
      const tx = d.transaction('leads', 'readwrite');
      const store = tx.objectStore('leads');
      store.put(lead);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
    });
  }

  static async deleteLead(leadId) {
    const d = await this.init();
    return new Promise((resolve) => {
      const tx = d.transaction('leads', 'readwrite');
      const store = tx.objectStore('leads');
      store.delete(leadId);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
    });
  }

  static async getSetting(key, fallback = '') {
    const d = await this.init();
    return new Promise((resolve) => {
      const tx = d.transaction('settings', 'readonly');
      const store = tx.objectStore('settings');
      const req = store.get(key);
      req.onsuccess = () => resolve(req.result ? req.result.value : fallback);
      req.onerror = () => resolve(fallback);
    });
  }

  static async setSetting(key, value) {
    const d = await this.init();
    return new Promise((resolve) => {
      const tx = d.transaction('settings', 'readwrite');
      const store = tx.objectStore('settings');
      store.put({ key, value });
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
    });
  }
}

// 5. APPLICATION STATE & CONTROLLERS
let currentCategory = 'asset';
let currentProduct = BANK_PRODUCTS[0];
let currentEditingLeadId = null;
let currentFollowUpLeadId = null;

// Officer profile defaults
let officerProfile = {
  officerName: 'Anjum Naveed',
  staffId: 'RM-8433',
  branchName: 'Main Branch',
  webhookUrl: ''
};

// Initialize App
window.addEventListener('DOMContentLoaded', async () => {
  await loadSettings();
  populateProducts();
  calculate();
  await renderLeads();
  updateOnlineStatus();

  if (window.FirebaseBridge) {
    initFirebaseAuthAndFirestore();
  }

  // Register online/offline listeners
  window.addEventListener('online', () => {
    updateOnlineStatus();
    manualSync();
  });
  window.addEventListener('offline', updateOnlineStatus);

  // Register Service Worker for offline PWA
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').then(() => {
      console.log('PWA Service Worker registered');
    }).catch(err => console.warn('SW registration notice:', err));
  }
});

// Wait for ES Module Firebase Bridge if it finishes slightly after DOMContentLoaded
window.addEventListener('firebase-bridge-ready', () => {
  initFirebaseAuthAndFirestore();
});

// 6. FIREBASE AUTH & ACCESS CONTROL GATE (ALLOW / DENY)
function initFirebaseAuthAndFirestore() {
  if (firebaseInitialized || !window.FirebaseBridge) return;
  fb = window.FirebaseBridge;
  firebaseInitialized = true;

  // Handle redirect return if user signed in via redirect (e.g. iOS standalone PWA or popup blocked)
  fb.getRedirectResult().catch((err) => {
    if (err && err.code) {
      handleAuthError(err);
    }
  });

  fb.onAuthStateChanged(async (user) => {
    currentUser = user;
    const authBtn = document.getElementById('googleSignInBtn');
    const chip = document.getElementById('userProfileChip');
    const avatarImg = document.getElementById('userAvatarImg');
    const nameSpan = document.getElementById('userNameSpan');
    const settingsAuthBtn = document.getElementById('settingsAuthBtn');
    const settingsEmail = document.getElementById('settingsUserEmail');
    const settingsStatus = document.getElementById('settingsAccessStatus');
    const settingsRole = document.getElementById('settingsUserRole');

    if (user) {
      authBtn.style.display = 'none';
      chip.style.display = 'inline-flex';
      nameSpan.textContent = user.displayName || (user.email ? user.email.split('@')[0] : 'Officer');
      if (user.photoURL) {
        avatarImg.src = user.photoURL;
      }
      settingsEmail.textContent = user.email || 'Google User';
      settingsAuthBtn.textContent = '🚪 Sign Out';

      // Start Global Config & Access Control Listeners
      startFirestoreGlobalConfigListener(user);
      startFirestoreUserListener(user);

      // Verify Access immediately
      await verifyUserAccessAndSyncProfile(user);

      // Start Real-Time Product & Lead Listeners
      startFirestoreProductsListener();
      startFirestoreLeadsListener();
    } else {
      authBtn.style.display = 'inline-flex';
      chip.style.display = 'none';
      settingsEmail.textContent = 'Not signed in';
      settingsAuthBtn.textContent = '🔑 Sign In with Google';
      settingsStatus.textContent = 'Local Guest Mode';
      settingsStatus.style.color = '#F59E0B';
      settingsRole.textContent = 'Loan Officer';

      hideAccessDeniedScreen();

      if (unsubscribeProducts) { unsubscribeProducts(); unsubscribeProducts = null; }
      if (unsubscribeLeads) { unsubscribeLeads(); unsubscribeLeads = null; }
      if (unsubscribeGlobalConfig) { unsubscribeGlobalConfig(); unsubscribeGlobalConfig = null; }
      if (unsubscribeUserDoc) { unsubscribeUserDoc(); unsubscribeUserDoc = null; }
    }
  });
}

function startFirestoreGlobalConfigListener(user) {
  if (!fb) return;
  if (unsubscribeGlobalConfig) unsubscribeGlobalConfig();

  unsubscribeGlobalConfig = fb.onDocSnapshot('config', 'global', async (docSnap) => {
    if (docSnap.exists()) {
      const d = docSnap.data();
      globalConfig.fedRate = Number(d.fedRate) || 16.0;
      globalConfig.filerTaxRate = Number(d.filerTaxRate) || 20.0;
      globalConfig.nonFilerTaxRate = Number(d.nonFilerTaxRate) || 40.0;
      globalConfig.currency = d.currency || 'PKR';
      globalConfig.registeredUsersMap = d.registeredUsersMap || {};
      globalConfig.deletedUserKeys = Array.isArray(d.deletedUserKeys) ? d.deletedUserKeys : [];

      if (d.googleSheetsWebhookUrl && !officerProfile.webhookUrl) {
        officerProfile.webhookUrl = d.googleSheetsWebhookUrl;
        await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);
        const webhookInput = document.getElementById('settingsWebhookUrl');
        if (webhookInput) webhookInput.value = officerProfile.webhookUrl;
      }

      const filerLabel = document.getElementById('filerToggleLabel');
      if (filerLabel) {
        filerLabel.textContent = `Active Tax Filer (${globalConfig.filerTaxRate}% Tax vs ${globalConfig.nonFilerTaxRate}% Non-Filer)`;
      }
      calculate();

      // Re-evaluate Allow/Deny whenever global registeredUsersMap updates from Android Admin panel
      if (user) {
        await verifyUserAccessAndSyncProfile(user);
      }
    }
  }, (err) => {
    console.warn('Global config listener notice:', err);
  });
}

function startFirestoreUserListener(user) {
  if (!fb) return;
  if (unsubscribeUserDoc) unsubscribeUserDoc();

  unsubscribeUserDoc = fb.onDocSnapshot('users', user.uid, async () => {
    await verifyUserAccessAndSyncProfile(user);
  }, (err) => {
    console.warn('User doc listener notice:', err);
  });
}

// Check Allow / Deny Whitelist (Matches Android FirestoreRepository.kt)
async function verifyUserAccessAndSyncProfile(user) {
  if (!fb) return;

  try {
    const isPrimaryAdmin = Boolean(
      user.email && (user.email.toLowerCase().includes('vipanjum') || user.email.toLowerCase().includes('admin'))
    );

    // 1. Check /users/{uid}
    const snap = await fb.getDoc('users', user.uid);
    let userRecord = snap.exists() ? snap.data() : null;

    // 2. Also check /config/global -> registeredUsersMap (Android admin toggles write here too!)
    let globalRecord = null;
    try {
      const globalSnap = await fb.getDoc('config', 'global');
      if (globalSnap.exists()) {
        const gData = globalSnap.data();
        const regMap = gData.registeredUsersMap || {};
        globalConfig.registeredUsersMap = regMap;
        globalRecord = regMap[user.uid] || Object.values(regMap).find(u =>
          u && user.email && u.email && u.email.toLowerCase() === user.email.toLowerCase()
        ) || null;
      }
    } catch (e) {
      console.warn('Global registry read notice:', e);
    }

    // Pick freshest record between /users/{uid} and /config/global.registeredUsersMap
    let effectiveRecord = userRecord;
    if (globalRecord && (!effectiveRecord || (Number(globalRecord.updatedAt) || 0) >= (Number(effectiveRecord.updatedAt) || 0))) {
      effectiveRecord = globalRecord;
    }

    if (effectiveRecord) {
      currentUserProfile = { uid: user.uid, ...effectiveRecord };

      if (effectiveRecord.isBlocked === true && !isPrimaryAdmin) {
        showAccessDeniedScreen(user);
        return;
      } else {
        hideAccessDeniedScreen();
      }

      if (effectiveRecord.officerName) officerProfile.officerName = effectiveRecord.officerName;
      if (effectiveRecord.staffId) officerProfile.staffId = effectiveRecord.staffId;
      if (effectiveRecord.branchName) officerProfile.branchName = effectiveRecord.branchName;
      if (effectiveRecord.role) {
        document.getElementById('settingsUserRole').textContent = effectiveRecord.role;
      }
    } else {
      // Brand new user: Admin is always allowed; new officers default to blocked (isBlocked: true)
      // until Admin enables them in Firestore / Android Admin Panel (exact Android parity)
      const defaultRole = isPrimaryAdmin ? 'Admin' : 'Loan Officer';
      const defaultBlocked = !isPrimaryAdmin;

      const newUser = {
        uid: user.uid,
        email: user.email || '',
        officerName: user.displayName || officerProfile.officerName || 'Loan Officer',
        staffId: officerProfile.staffId || ('RO-' + user.uid.substring(0, 4).toUpperCase()),
        branchName: officerProfile.branchName || 'Main Branch',
        allowedSupervisorBranch: officerProfile.branchName || 'Main Branch',
        canUseSupervisorFilter: isPrimaryAdmin,
        isBlocked: defaultBlocked,
        role: defaultRole,
        updatedAt: Date.now()
      };

      await fb.setDoc('users', user.uid, newUser, true);
      try {
        await fb.setDoc('config', 'global', {
          registeredUsersMap: { [user.uid]: newUser }
        }, true);
      } catch (e) {}

      currentUserProfile = newUser;

      if (defaultBlocked) {
        showAccessDeniedScreen(user);
        return;
      } else {
        hideAccessDeniedScreen();
      }
    }

    // Refresh UI badges & settings inputs
    document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
    document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName}`;
    document.getElementById('settingsOfficerName').value = officerProfile.officerName;
    document.getElementById('settingsStaffId').value = officerProfile.staffId;
    document.getElementById('settingsBranch').value = officerProfile.branchName;

    document.getElementById('settingsAccessStatus').textContent = 'Authorized (Active)';
    document.getElementById('settingsAccessStatus').style.color = '#16A34A';

    updateBranchFilterOptions();
  } catch (err) {
    console.warn('Error verifying user access in Firestore:', err);
  }
}

function showAccessDeniedScreen(user) {
  document.getElementById('deniedUserEmail').textContent = user.email || 'User';
  document.getElementById('deniedUserUid').textContent = user.uid || '';
  document.getElementById('accessDeniedScreen').classList.add('active');
  document.getElementById('settingsAccessStatus').textContent = 'Blocked / Inactive';
  document.getElementById('settingsAccessStatus').style.color = '#EF4444';
}

function hideAccessDeniedScreen() {
  document.getElementById('accessDeniedScreen').classList.remove('active');
}

async function checkUserAccessAgain() {
  if (currentUser) {
    await verifyUserAccessAndSyncProfile(currentUser);
  }
}

function handleAuthError(err) {
  if (!err || err.code === 'auth/popup-closed-by-user' || err.code === 'auth/cancelled-popup-request') {
    return;
  }
  if (err.code === 'auth/unauthorized-domain') {
    const host = window.location.hostname || 'github.io';
    const hostEl = document.getElementById('unauthorizedHostText');
    if (hostEl) hostEl.textContent = host;
    openModal('unauthorizedDomainModal');
    return;
  }
  alert('Google Sign-In notice: ' + (err.message || err.code));
}

function copyUnauthorizedHost() {
  const host = document.getElementById('unauthorizedHostText').textContent;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(host).then(() => {
      alert(`✅ Copied "${host}"! Now paste it in Firebase Console ➔ Authentication ➔ Settings ➔ Authorized domains.`);
    }).catch(() => {
      alert(`Domain to add: ${host}`);
    });
  } else {
    alert(`Domain to add: ${host}`);
  }
}

// Sign-In & Sign-Out Handlers
async function signInWithGoogle() {
  if (!fb) {
    alert('Firebase Auth is still initializing or the device is offline. Please wait a second and try again.');
    return;
  }
  const isStandalonePWA = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  try {
    if (isStandalonePWA) {
      // Standalone iOS / Android PWA works more reliably with redirect
      await fb.signInWithGoogleRedirect();
    } else {
      await fb.signInWithGooglePopup();
    }
  } catch (err) {
    if (err && (err.code === 'auth/popup-blocked' || err.code === 'auth/operation-not-supported-in-this-environment')) {
      try {
        await fb.signInWithGoogleRedirect();
      } catch (redirErr) {
        handleAuthError(redirErr);
      }
    } else {
      handleAuthError(err);
    }
  }
}

async function signOutUser() {
  if (!fb) return;
  try {
    await fb.signOut();
    hideAccessDeniedScreen();
    alert('Logged out successfully.');
  } catch (err) {
    console.warn('Sign out error:', err);
  }
}

function toggleAuthAction() {
  if (currentUser) {
    signOutUser();
  } else {
    signInWithGoogle();
  }
}

// 7. REAL-TIME FIRESTORE PRODUCTS SYNCHRONIZATION
function startFirestoreProductsListener() {
  if (!fb) return;
  if (unsubscribeProducts) unsubscribeProducts();

  unsubscribeProducts = fb.onCollectionSnapshot('products', (snapshot) => {
    if (!snapshot.empty) {
      const fetched = [];
      snapshot.forEach(docSnap => {
        const d = docSnap.data();
        const pId = d.id || docSnap.id;
        const category = d.category || 'asset';
        const rateType = d.rateType || 'reducing';
        const defaultAnnualRate = Number(d.defaultAnnualRate) || 0.0;
        const minAmount = Number(d.minAmount) || 50000;
        const maxAmount = Number(d.maxAmount) || 5000000;
        const minTenure = Number(d.minTenure) || 1;
        const maxTenure = Number(d.maxTenure) || 36;
        const feePercent = Number(d.defaultFeeValue) || 1.5;
        const fedRate = Number(d.fedRate) || globalConfig.fedRate || 16.0;
        const rateSlabs = Array.isArray(d.rateSlabs) ? d.rateSlabs : [];
        const feeSlabs = Array.isArray(d.feeSlabs) ? d.feeSlabs : [];
        const tenureRates = (d.tenureRates && typeof d.tenureRates === 'object') ? d.tenureRates : {};
        const liabilityPricingType = d.liabilityPricingType || '';

        let tenures = [];
        if (Array.isArray(d.fixedTenures) && d.fixedTenures.length > 0) {
          tenures = d.fixedTenures.map(n => Number(n)).sort((a, b) => a - b);
        } else if (Object.keys(tenureRates).length > 0) {
          tenures = Object.keys(tenureRates).map(k => Number(k)).filter(n => !isNaN(n) && n > 0).sort((a, b) => a - b);
        } else {
          for (let t = Math.max(6, minTenure); t <= maxTenure; t += 6) {
            if (t > 0) tenures.push(t);
          }
          if (minTenure > 0 && !tenures.includes(minTenure)) tenures.unshift(minTenure);
          if (maxTenure > 0 && !tenures.includes(maxTenure)) tenures.push(maxTenure);
        }

        fetched.push({
          id: pId,
          name: d.name || 'Bank Facility',
          category,
          rateType,
          defaultAnnualRate,
          minAmount,
          maxAmount,
          minTenure,
          maxTenure,
          tenures: tenures.length > 0 ? tenures : [6, 12, 18, 24, 36],
          feePercent,
          fedRate,
          rateSlabs,
          feeSlabs,
          tenureRates,
          liabilityPricingType
        });
      });

      if (fetched.length > 0) {
        BANK_PRODUCTS = fetched;
        const previousId = currentProduct ? currentProduct.id : null;
        populateProducts(previousId);
        calculate();
        renderProductsManagementList();
        const badge = document.getElementById('firestoreProductSyncBadge');
        if (badge) badge.textContent = `⚡ Firestore Synced (${fetched.length} Products)`;
      }
    }
  }, (err) => {
    console.warn('Products sync stream notice:', err);
  });
}

// 8. REAL-TIME FIRESTORE LEADS SYNCHRONIZATION (WITH BRANCH FILTERING)
function startFirestoreLeadsListener() {
  if (!fb) return;
  if (unsubscribeLeads) unsubscribeLeads();

  unsubscribeLeads = fb.onCollectionSnapshot('leads', async (snapshot) => {
    const localLeads = await LocalDB.getAllLeads();
    const localMap = new Map(localLeads.map(l => [l.id, l]));
    const remoteIds = new Set();

    snapshot.forEach(docSnap => {
      const d = docSnap.data();
      const leadId = d.id || docSnap.id;
      remoteIds.add(leadId);
      const existing = localMap.get(leadId);

      const remoteLead = {
        id: leadId,
        customerName: d.customerName || 'Customer',
        mobile: d.mobile || '',
        category: d.category || 'asset',
        productId: d.productId || '',
        productName: d.productName || 'Bank Facility',
        loanAmount: Number(d.loanAmount) || 0,
        tenureMonths: Number(d.tenureMonths) || 12,
        monthlyEmi: Number(d.monthlyEmi) || 0,
        totalProfit: Number(d.totalProfit) || 0,
        processingFee: Number(d.processingFee) || 0,
        status: d.status || 'New',
        notes: cleanRawActionPayload(d.notes || ''),
        followUpDate: d.followUpDate || '',
        officerName: d.officerName || '',
        officerId: d.officerId || '',
        branchName: d.branchName || 'Main Branch',
        userId: d.userId || '',
        createdAt: Number(d.createdAt) || Date.now(),
        syncStatus: 'synced',
        approachCount: Number(d.approachCount) || (Array.isArray(d.activities) ? d.activities.length : 1),
        activities: Array.isArray(d.activities) ? d.activities : (existing ? existing.activities : [])
      };

      LocalDB.saveLead(remoteLead);
      localMap.set(leadId, remoteLead);
    });

    // If a previously synced lead was deleted on Android/another client, remove it locally too
    for (const local of localLeads) {
      if (local.syncStatus === 'synced' && !remoteIds.has(local.id)) {
        await LocalDB.deleteLead(local.id);
      }
    }

    await renderLeads();
    updateBranchFilterOptions();
  }, (err) => {
    console.warn('Leads sync stream notice:', err);
  });
}

// Update Branch Filter Dropdown
async function updateBranchFilterOptions() {
  const branchSelect = document.getElementById('branchFilterSelect');
  if (!branchSelect) return;

  const currentSelection = branchSelect.value;
  const leads = await LocalDB.getAllLeads();
  const branches = new Set();

  if (officerProfile.branchName) branches.add(officerProfile.branchName);

  leads.forEach(l => {
    if (l.branchName && l.branchName.trim()) {
      branches.add(l.branchName.trim());
    }
  });

  const branchArray = Array.from(branches).sort();
  branchSelect.innerHTML = '<option value="All">All Branches (ساری برانچز)</option>';

  branchArray.forEach(b => {
    const opt = document.createElement('option');
    opt.value = b;
    opt.textContent = `🏢 ${b}`;
    branchSelect.appendChild(opt);
  });

  if (currentSelection && currentSelection !== 'All' && branchArray.includes(currentSelection)) {
    branchSelect.value = currentSelection;
  } else if (officerProfile.branchName && branchArray.includes(officerProfile.branchName)) {
    const isSupervisor = currentUserProfile && (currentUserProfile.role === 'Admin' || currentUserProfile.canUseSupervisorFilter);
    if (!isSupervisor) {
      branchSelect.value = officerProfile.branchName;
    }
  }
}

// 9. PRODUCT MANAGEMENT & EDIT MODAL
function renderProductsManagementList() {
  const container = document.getElementById('productsManageList');
  if (!container) return;

  container.innerHTML = BANK_PRODUCTS.map(p => `
    <div class="product-manage-item">
      <div class="product-manage-info">
        <h4>${escapeHtml(p.name)}</h4>
        <p>Category: <strong>${p.category === 'asset' ? '💼 Loan' : '💰 Deposit'}</strong> • Rate: <strong>${p.defaultAnnualRate}%</strong> • Tenures: ${p.tenures.join(', ')} M</p>
      </div>
      <button type="button" class="btn btn-outline" onclick="editProduct('${p.id}')" style="width: auto; height: 32px; font-size: 0.75rem; padding: 0 10px;">
        ✏️ Edit
      </button>
    </div>
  `).join('');
}

function openNewProductModal() {
  document.getElementById('editProductModalTitle').textContent = '➕ Add New Bank Facility';
  document.getElementById('prodEditId').value = 'prod-' + Date.now();
  document.getElementById('prodEditName').value = '';
  document.getElementById('prodEditCategory').value = 'asset';
  document.getElementById('prodEditRateType').value = 'reducing';
  document.getElementById('prodEditRate').value = '38.0';
  document.getElementById('prodEditFee').value = '1.5';
  document.getElementById('prodEditMinAmount').value = '50000';
  document.getElementById('prodEditMaxAmount').value = '1000000';
  document.getElementById('prodEditMinTenure').value = '3';
  document.getElementById('prodEditMaxTenure').value = '36';
  openModal('editProductModal');
}

function editProduct(productId) {
  const p = BANK_PRODUCTS.find(item => item.id === productId);
  if (!p) return;

  document.getElementById('editProductModalTitle').textContent = '⚙️ Edit Product: ' + p.name;
  document.getElementById('prodEditId').value = p.id;
  document.getElementById('prodEditName').value = p.name;
  document.getElementById('prodEditCategory').value = p.category;
  document.getElementById('prodEditRateType').value = p.rateType;
  document.getElementById('prodEditRate').value = p.defaultAnnualRate;
  document.getElementById('prodEditFee').value = p.feePercent || 1.5;
  document.getElementById('prodEditMinAmount').value = p.minAmount;
  document.getElementById('prodEditMaxAmount').value = p.maxAmount;
  document.getElementById('prodEditMinTenure').value = p.minTenure;
  document.getElementById('prodEditMaxTenure').value = p.maxTenure;
  openModal('editProductModal');
}

async function confirmSaveProduct() {
  const pId = document.getElementById('prodEditId').value.trim();
  const name = document.getElementById('prodEditName').value.trim();
  const category = document.getElementById('prodEditCategory').value;
  const rateType = document.getElementById('prodEditRateType').value;
  const rate = parseFloat(document.getElementById('prodEditRate').value) || 0.0;
  const fee = parseFloat(document.getElementById('prodEditFee').value) || 0.0;
  const minAmount = parseInt(document.getElementById('prodEditMinAmount').value, 10) || 50000;
  const maxAmount = parseInt(document.getElementById('prodEditMaxAmount').value, 10) || 1000000;
  const minTenure = parseInt(document.getElementById('prodEditMinTenure').value, 10) || 3;
  const maxTenure = parseInt(document.getElementById('prodEditMaxTenure').value, 10) || 36;

  if (!name) {
    alert('Please enter a product name.');
    return;
  }

  let prod = BANK_PRODUCTS.find(p => p.id === pId);
  if (prod) {
    prod.name = name;
    prod.category = category;
    prod.rateType = rateType;
    prod.defaultAnnualRate = rate;
    prod.feePercent = fee;
    prod.minAmount = minAmount;
    prod.maxAmount = maxAmount;
    prod.minTenure = minTenure;
    prod.maxTenure = maxTenure;
  } else {
    prod = {
      id: pId,
      name,
      category,
      rateType,
      defaultAnnualRate: rate,
      feePercent: fee,
      minAmount,
      maxAmount,
      minTenure,
      maxTenure,
      tenures: [6, 12, 18, 24, 36],
      fedRate: globalConfig.fedRate || 16.0,
      rateSlabs: [],
      feeSlabs: [],
      tenureRates: {},
      liabilityPricingType: ''
    };
    BANK_PRODUCTS.push(prod);
  }

  if (fb) {
    try {
      await fb.setDoc('products', pId, {
        id: pId,
        name,
        category,
        rateType,
        defaultAnnualRate: rate,
        defaultFeeValue: fee,
        fedRate: globalConfig.fedRate || 16.0,
        minAmount,
        maxAmount,
        minTenure,
        maxTenure,
        updatedAt: fb.serverTimestamp()
      }, true);
    } catch (e) {
      console.warn('Error saving product to Firestore:', e);
    }
  }

  closeModal('editProductModal');
  populateProducts(pId);
  calculate();
  renderProductsManagementList();
  alert('✅ Product saved and synced to Firestore!');
}

// 10. ONLINE / OFFLINE STATUS
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

function switchTab(tabId, el) {
  document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  document.getElementById(tabId).classList.add('active');
  el.classList.add('active');
  if (tabId === 'leadsTab') renderLeads();
  if (tabId === 'settingsTab') {
    updateSettingsStats();
    renderProductsManagementList();
  }
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

function populateProducts(preserveId = null) {
  const select = document.getElementById('productSelect');
  select.innerHTML = '';
  const filtered = BANK_PRODUCTS.filter(p => p.category === currentCategory);
  filtered.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = `${p.name} (${p.defaultAnnualRate}%)`;
    select.appendChild(opt);
  });
  const preserved = preserveId ? filtered.find(p => p.id === preserveId) : null;
  currentProduct = preserved || filtered[0] || BANK_PRODUCTS[0];
  if (currentProduct) select.value = currentProduct.id;
  populateTenures();
}

function populateTenures() {
  const tenureSelect = document.getElementById('tenureMonths');
  const prevTenure = parseInt(tenureSelect.value, 10);
  tenureSelect.innerHTML = '';
  if (!currentProduct || !currentProduct.tenures) return;
  currentProduct.tenures.forEach(m => {
    const opt = document.createElement('option');
    opt.value = m;
    opt.textContent = `${m} Months`;
    tenureSelect.appendChild(opt);
  });
  if (prevTenure && currentProduct.tenures.includes(prevTenure)) {
    tenureSelect.value = prevTenure;
  }
}

function onProductChange() {
  const id = document.getElementById('productSelect').value;
  currentProduct = BANK_PRODUCTS.find(p => p.id === id) || BANK_PRODUCTS[0];
  populateTenures();
  calculate();
}

// 11. CALCULATOR ENGINE (Exact Android LoanCalculators.kt Parity)
let lastCalculation = null;

function getApplicableRate(product, amount, tenureMonths) {
  const hasPositiveSlab = Array.isArray(product.rateSlabs) && product.rateSlabs.some(s => Number(s.annualRate) > 0);
  const hasPositiveTenure = product.tenureRates && Object.values(product.tenureRates).some(r => Number(r) > 0);
  if (product.defaultAnnualRate <= 0 && !hasPositiveSlab && !hasPositiveTenure) {
    return 0.0;
  }

  // Helper to match amount slab
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
        if (product.tenureRates[tenureMonths] !== undefined) {
          return Number(product.tenureRates[tenureMonths]);
        }
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
      const raw = (String(slab.feeType).toLowerCase() === 'fixed')
        ? Math.round(Number(slab.feeValue) || 0)
        : Math.round((principal * (Number(slab.feeValue) || 0)) / 100.0);
      const minApplied = Math.max(Number(slab.minFee) || 0, raw);
      const maxFee = Number(slab.maxFee) || 0;
      fee = maxFee > 0 ? Math.min(minApplied, maxFee) : minApplied;
    }
  } else if (product.feePercent > 0) {
    fee = Math.round(principal * (product.feePercent / 100.0));
  }

  const fedAmount = Math.round((fee * fedRate) / 100.0);
  return { baseFee: fee, fedAmount, totalFee: fee + fedAmount };
}

function calculate() {
  if (!currentProduct) return;
  const amount = parseFloat(document.getElementById('loanAmount').value) || 0;
  const tenure = parseInt(document.getElementById('tenureMonths').value, 10) || 12;
  const monthlyIncome = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const allowedDbr = parseFloat(document.getElementById('allowedDbr').value) || 50;
  const isFiler = document.getElementById('isFilerToggle').checked;

  const rate = getApplicableRate(currentProduct, amount, tenure);
  document.getElementById('resRate').textContent = `${rate.toFixed(2).replace(/\.00$/, '.0')}%`;

  if (currentProduct.category === 'asset') {
    let emi = 0;
    let totalProfit = 0;
    let totalPayable = 0;

    if (String(currentProduct.rateType).toLowerCase() === 'flat') {
      totalProfit = Math.round(amount * (rate / 100.0) * (tenure / 12.0));
      totalPayable = amount + totalProfit;
      emi = Math.round(totalPayable / tenure);
    } else {
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

    const effectiveFed = currentProduct.fedRate || globalConfig.fedRate || 16.0;
    const feeRes = calculateProcessingFee(currentProduct, amount, effectiveFed);
    const totalFee = feeRes.totalFee;
    const dbrRatio = monthlyIncome > 0 ? ((emi / monthlyIncome) * 100).toFixed(1) : '0';

    document.getElementById('emiRow').style.display = 'flex';
    document.getElementById('dbrRow').style.display = 'flex';
    document.getElementById('feeRow').style.display = 'flex';
    document.getElementById('profitRow').style.display = 'flex';
    document.getElementById('rateLabel').textContent = 'Applied Profit Rate:';
    document.getElementById('emiRow').children[0].textContent = 'Monthly Installment (EMI):';
    document.getElementById('feeRow').children[0].textContent = `Processing Fee (inc. ${effectiveFed}% FED):`;
    document.getElementById('profitLabel').textContent = 'Total Profit / Markup:';
    document.getElementById('totalPayableLabel').textContent = 'Total Repayable Amount:';

    document.getElementById('resEmi').textContent = formatPKR(emi);
    document.getElementById('resDbr').textContent = `${dbrRatio}% ${parseFloat(dbrRatio) > allowedDbr ? '❌ Exceeds DBR!' : '✅ Within Limit'}`;
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
    const taxRate = isFiler ? (globalConfig.filerTaxRate || 20.0) : (globalConfig.nonFilerTaxRate || 40.0);
    const grossTotalProfit = Math.round(amount * (rate / 100.0) * (tenure / 12.0));
    const grossMonthlyProfit = Math.round((amount * (rate / 100.0)) / 12.0);
    const totalTax = Math.round(grossTotalProfit * (taxRate / 100.0));
    const monthlyTax = Math.round(grossMonthlyProfit * (taxRate / 100.0));
    const netTotalProfit = Math.max(0, grossTotalProfit - totalTax);
    const netMonthlyProfit = Math.max(0, grossMonthlyProfit - monthlyTax);
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

// 12. AMORTIZATION SCHEDULE GENERATOR
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

// 13. SAVE LEAD TO LOCAL DB & FIRESTORE
async function saveLeadFromCalculator() {
  const custName = document.getElementById('customerName').value.trim();
  const mobile = document.getElementById('customerMobile').value.trim();
  const notes = document.getElementById('leadNotes').value.trim();

  if (!custName) {
    alert('Please enter customer name (گاہک کا نام درج کریں).');
    return;
  }
  if (!mobile) {
    alert('Please enter mobile number (موبائل نمبر درج کریں).');
    return;
  }

  const existingLeads = await LocalDB.getAllLeads();
  let lead = existingLeads.find(l => l.mobile === mobile || (l.customerName.toLowerCase() === custName.toLowerCase() && l.productName === currentProduct.name));

  const now = Date.now();
  const cleanNote = cleanRawActionPayload(notes);

  if (lead) {
    lead.customerName = custName;
    lead.loanAmount = lastCalculation.amount;
    lead.tenureMonths = lastCalculation.tenure;
    lead.category = currentProduct.category;
    lead.productName = currentProduct.name;
    lead.monthlyEmi = lastCalculation.emi || lastCalculation.netMonthlyProfit || 0;
    lead.totalProfit = lastCalculation.totalProfit || lastCalculation.netTotalProfit || 0;
    lead.processingFee = lastCalculation.totalFee || 0;
    lead.syncStatus = 'pending_sync';
    if (!lead.activities) lead.activities = [];

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
      details: `${currentProduct.name} (PKR ${formatPKR(lastCalculation.amount)})`,
      officerName: officerProfile.officerName,
      officerId: officerProfile.staffId
    });
  } else {
    const id = 'kb-lead-' + now + '-' + Math.random().toString(36).substring(2, 7);
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
      details: `${currentProduct.name} (PKR ${formatPKR(lastCalculation.amount)})`,
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
      userId: currentUser ? currentUser.uid : 'web-officer',
      createdAt: now,
      syncStatus: 'pending_sync',
      approachCount: 1,
      activities: initialActivities
    };
  }

  await LocalDB.saveLead(lead);

  if (fb) {
    try {
      await fb.setDoc('leads', lead.id, {
        ...lead,
        syncStatus: 'synced',
        updatedAtMs: Date.now(),
        updatedAt: fb.serverTimestamp()
      }, true);
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
    } catch (e) {
      console.warn('Firestore write warning:', e);
    }
  }

  alert(`✅ Lead for ${custName} saved successfully and synced!`);

  document.getElementById('customerName').value = '';
  document.getElementById('customerMobile').value = '';
  document.getElementById('leadNotes').value = '';

  await renderLeads();
  updateBranchFilterOptions();

  if (navigator.onLine && officerProfile.webhookUrl) {
    syncLeadToSheet(lead);
  }
}

// 14. RENDER CUSTOMER LEADS LIST (WITH BRANCH, STATUS, & DELETE ACTIONS)
async function renderLeads() {
  const container = document.getElementById('leadsListContainer');
  const countSpan = document.getElementById('leadsCount');
  const search = document.getElementById('searchLeadsInput').value.toLowerCase().trim();
  const statusFilter = document.getElementById('statusFilterSelect').value;
  const branchFilter = document.getElementById('branchFilterSelect') ? document.getElementById('branchFilterSelect').value : 'All';

  const leads = await LocalDB.getAllLeads();
  countSpan.textContent = leads.length;

  const filtered = leads.filter(l => {
    const matchesStatus = statusFilter === 'All' || l.status === statusFilter;
    const matchesBranch = branchFilter === 'All' || (l.branchName && l.branchName.trim() === branchFilter.trim());
    const matchesSearch = !search ||
      (l.customerName && l.customerName.toLowerCase().includes(search)) ||
      (l.mobile && l.mobile.includes(search)) ||
      (l.productName && l.productName.toLowerCase().includes(search)) ||
      (l.branchName && l.branchName.toLowerCase().includes(search));
    return matchesStatus && matchesBranch && matchesSearch;
  });

  filtered.sort((a, b) => b.createdAt - a.createdAt);

  if (filtered.length === 0) {
    container.innerHTML = `
      <div class="card" style="text-align: center; color: var(--text-muted); padding: 30px;">
        <p style="font-size: 1.1rem; margin-bottom: 6px;">📂 No customer leads found.</p>
        <p style="font-size: 0.8rem;">Saved leads for the selected branch will appear here with instant Firestore & Google Sheets sync.</p>
      </div>
    `;
    return;
  }

  const statuses = ['New', 'Contacted', 'Follow-Up', 'Converted', 'Lost'];

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
          <div style="display: flex; align-items: center; gap: 6px;">
            <select class="form-select" style="height: 28px; font-size: 0.72rem; padding: 0 6px; width: auto; font-weight: 700;" onchange="updateLeadStatus('${lead.id}', this.value)">
              ${statuses.map(s => `<option value="${s}" ${(lead.status || 'New') === s ? 'selected' : ''}>${s}</option>`).join('')}
            </select>
            <span class="badge ${badgeClass}">${escapeHtml(lead.status || 'New')}</span>
          </div>
        </div>

        <div style="font-size: 0.82rem; color: var(--primary); font-weight: 600; margin: 4px 0;">
          ${escapeHtml(lead.productName)} • PKR ${formatPKR(lead.loanAmount)} (${lead.tenureMonths}M)
        </div>

        ${cleanNote ? `<div class="lead-notes-box">📝 ${escapeHtml(cleanNote)}</div>` : ''}

        <div style="font-size: 0.75rem; color: var(--text-muted); margin-bottom: 8px;">
          📅 Follow-Up: <strong>${lead.followUpDate ? escapeHtml(lead.followUpDate) : 'None'}</strong>
          • Officer: <strong>${escapeHtml(lead.officerName || 'RO')}</strong>
          • Cloud: <strong>${lead.syncStatus === 'synced' ? '✅ Synced' : '⏳ Queued'}</strong>
        </div>

        <div class="lead-actions">
          <button type="button" class="btn btn-whatsapp" onclick="sendQuotationWhatsApp('${lead.id}')">💬 WhatsApp</button>
          <button type="button" class="btn btn-sms" onclick="sendQuotationSMS('${lead.id}')">✉️ SMS</button>
          <button type="button" class="btn btn-call" onclick="initiateCall('${lead.id}', '${lead.mobile}')">📞 Call</button>
          <button type="button" class="btn btn-outline" onclick="openTrackRecord('${lead.id}')">📜 History</button>
          <button type="button" class="btn btn-outline" onclick="openEditNoteModal('${lead.id}')">📝 Note</button>
          <button type="button" class="btn btn-outline" onclick="openFollowUpModal('${lead.id}')">📅 Follow-Up</button>
          <button type="button" class="btn btn-outline" style="color: var(--danger); border-color: #FCA5A5;" onclick="deleteLead('${lead.id}')">🗑 Delete</button>
        </div>
      </div>
    `;
  }).join('');
}

async function updateLeadStatus(leadId, newStatus) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  const oldStatus = lead.status || 'New';
  if (oldStatus === newStatus) return;

  const now = Date.now();
  lead.status = newStatus;
  lead.syncStatus = 'pending_sync';
  if (!lead.activities) lead.activities = [];

  lead.activities.push({
    id: 'stat-' + now,
    timestamp: now,
    actionType: 'status_change',
    title: '🔄 Status Changed',
    details: `${oldStatus} ➔ ${newStatus}`,
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);

  if (fb) {
    try {
      await fb.setDoc('leads', lead.id, {
        status: newStatus,
        activities: lead.activities,
        syncStatus: 'synced',
        updatedAtMs: Date.now(),
        updatedAt: fb.serverTimestamp()
      }, true);
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
    } catch (e) {
      console.warn('Firestore status sync notice:', e);
    }
  }

  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

async function deleteLead(leadId) {
  if (!confirm('Are you sure you want to delete this lead?')) return;

  await LocalDB.deleteLead(leadId);
  if (fb) {
    try {
      await fb.deleteDoc('leads', leadId);
    } catch (e) {
      console.warn('Firestore delete notice:', e);
    }
  }
  await renderLeads();
  updateSettingsStats();
}

// 15. WHATSAPP & SMS QUOTATIONS WITH LOGGING
async function sendQuotationWhatsApp(leadId = null) {
  let custName = '';
  let mobile = '';
  let prodName = '';
  let amount = 0;
  let tenure = 12;
  let emi = 0;
  let rate = 39.5;
  let fee = 0;
  let totalPayable = 0;

  if (leadId) {
    const leads = await LocalDB.getAllLeads();
    const lead = leads.find(l => l.id === leadId);
    if (!lead) return;
    custName = lead.customerName;
    mobile = lead.mobile;
    prodName = lead.productName;
    amount = lead.loanAmount;
    tenure = lead.tenureMonths;
    emi = lead.monthlyEmi;
    const matchedProd = BANK_PRODUCTS.find(p => p.id === lead.productId || p.name === lead.productName) || currentProduct;
    rate = getApplicableRate(matchedProd, amount, tenure);
    fee = lead.processingFee;
    totalPayable = amount + lead.totalProfit;
  } else {
    custName = document.getElementById('customerName').value.trim() || 'Valued Customer';
    mobile = document.getElementById('customerMobile').value.trim();
    if (!mobile) {
      alert('Please enter customer mobile number (موبائل نمبر درج کریں).');
      return;
    }
    prodName = currentProduct.name;
    amount = lastCalculation.amount;
    tenure = lastCalculation.tenure;
    emi = lastCalculation.emi || lastCalculation.netMonthlyProfit || 0;
    rate = lastCalculation.rate;
    fee = lastCalculation.totalFee || 0;
    totalPayable = lastCalculation.totalPayable || lastCalculation.netMaturity || 0;
  }

  const cleanPhone = formatForWhatsApp(mobile);
  const msgText =
`السلام علیکم جناب *${custName}*،
*KB Bank Financing & Deposit Services*

آپ کی مطلوبہ فنانسنگ / ڈپازٹ کوٹیشن:
• پراڈکٹ: *${prodName}*
• رقم: *PKR ${formatPKR(amount)}*
• مدت: *${tenure} ماہ*
• منافع / مارک اپ ریٹ: *${rate}%*
• ماہانہ قسط (EMI / Monthly Profit): *PKR ${formatPKR(emi)}*
• پروسیسنگ فیس: *PKR ${formatPKR(fee)}*
• کل رقم: *PKR ${formatPKR(totalPayable)}*

مزید معلومات کے لیے ہم سے رابطہ فرمائیں۔
شکریہ!
آفیسر: ${officerProfile.officerName}
برانچ: ${officerProfile.branchName}`;

  if (leadId) {
    await logApproach(leadId, 'whatsapp', '💬 WhatsApp Quotation Sent', `Quotation sent via WhatsApp to ${mobile}`);
  }

  window.open(`https://wa.me/${cleanPhone}?text=${encodeURIComponent(msgText)}`, '_blank');
}

async function sendQuotationSMS(leadId = null) {
  let custName = '';
  let mobile = '';
  let prodName = '';
  let amount = 0;
  let tenure = 12;
  let emi = 0;

  if (leadId) {
    const leads = await LocalDB.getAllLeads();
    const lead = leads.find(l => l.id === leadId);
    if (!lead) return;
    custName = lead.customerName;
    mobile = lead.mobile;
    prodName = lead.productName;
    amount = lead.loanAmount;
    tenure = lead.tenureMonths;
    emi = lead.monthlyEmi;
  } else {
    custName = document.getElementById('customerName').value.trim() || 'Customer';
    mobile = document.getElementById('customerMobile').value.trim();
    if (!mobile) {
      alert('Please enter customer mobile number (موبائل نمبر درج کریں).');
      return;
    }
    prodName = currentProduct.name;
    amount = lastCalculation.amount;
    tenure = lastCalculation.tenure;
    emi = lastCalculation.emi || lastCalculation.netMonthlyProfit || 0;
  }

  const cleanPhone = mobile.replace(/\D/g, '');
  const smsBody = `Dear ${custName}, KB Bank Quotation: ${prodName}, Amount: PKR ${formatPKR(amount)}, Tenure: ${tenure}M, Monthly: PKR ${formatPKR(emi)}. Contact: ${officerProfile.officerName} (${officerProfile.branchName})`;

  if (leadId) {
    await logApproach(leadId, 'sms', '✉️ SMS Quotation Sent', `SMS sent to ${mobile}`);
  }

  window.location.href = `sms:${cleanPhone}?body=${encodeURIComponent(smsBody)}`;
}

async function initiateCall(leadId, mobile) {
  await logApproach(leadId, 'call', '📞 Phone Call', `Phone call to ${mobile}`);
  window.location.href = `tel:${mobile.replace(/\D/g, '')}`;
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

  if (fb) {
    try {
      await fb.setDoc('leads', lead.id, {
        ...lead,
        syncStatus: 'synced',
        updatedAtMs: Date.now(),
        updatedAt: fb.serverTimestamp()
      }, true);
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
    } catch (e) {
      console.warn('Firestore activity sync notice:', e);
    }
  }

  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

// 16. MODAL DIALOGS CONTROLLER
function openModal(id) {
  const el = document.getElementById(id);
  if (el) el.classList.add('open');
}

function closeModal(id) {
  const el = document.getElementById(id);
  if (el) el.classList.remove('open');
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
    title: '📝 Note / Comment Updated',
    details: cleanNote || 'Cleared note',
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);

  if (fb) {
    try {
      await fb.setDoc('leads', lead.id, {
        ...lead,
        syncStatus: 'synced',
        updatedAtMs: Date.now(),
        updatedAt: fb.serverTimestamp()
      }, true);
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
    } catch (e) {
      console.warn('Firestore note sync notice:', e);
    }
  }

  closeModal('editNoteModal');
  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

async function openFollowUpModal(leadId) {
  currentFollowUpLeadId = leadId;
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  document.getElementById('followUpDateTimeInput').value = lead.followUpDate ? lead.followUpDate.slice(0, 16) : '';
  openModal('followUpModal');
}

async function confirmScheduleFollowUp() {
  if (!currentFollowUpLeadId) return;
  const dt = document.getElementById('followUpDateTimeInput').value;
  if (!dt) {
    alert('Please select date and time.');
    return;
  }
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === currentFollowUpLeadId);
  if (!lead) return;

  const now = Date.now();
  lead.followUpDate = dt.replace('T', ' ');
  lead.status = 'Follow-Up';
  lead.syncStatus = 'pending_sync';
  if (!lead.activities) lead.activities = [];

  lead.activities.push({
    id: 'act-' + now,
    timestamp: now,
    actionType: 'follow_up',
    title: '📅 Follow-Up Scheduled',
    details: `Scheduled follow-up for ${lead.followUpDate}`,
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);

  if (fb) {
    try {
      await fb.setDoc('leads', lead.id, {
        ...lead,
        syncStatus: 'synced',
        updatedAtMs: Date.now(),
        updatedAt: fb.serverTimestamp()
      }, true);
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
    } catch (e) {
      console.warn('Firestore follow-up sync notice:', e);
    }
  }

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

  if (fb) {
    try {
      await fb.setDoc('leads', lead.id, {
        ...lead,
        syncStatus: 'synced',
        updatedAtMs: Date.now(),
        updatedAt: fb.serverTimestamp()
      }, true);
      lead.syncStatus = 'synced';
      await LocalDB.saveLead(lead);
    } catch (e) {
      console.warn('Firestore clear follow-up notice:', e);
    }
  }

  closeModal('followUpModal');
  renderLeads();
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

// 17. GOOGLE SHEETS IN-PLACE SYNC (DUAL-KEY UPSERT ENGINE)
async function syncLeadToSheet(lead) {
  if (!officerProfile.webhookUrl) return;

  const dtFormat = formatDate(lead.createdAt);
  const activities = (lead.activities || []).slice().sort((a, b) => a.timestamp - b.timestamp);

  const noteLines = activities.filter(a => a.actionType === 'note_update' && a.details).map(act => {
    const actTime = formatDate(act.timestamp);
    const clean = cleanRawActionPayload(act.details);
    return `[${actTime}] ${clean} - By: ${act.officerName || 'Officer'}`;
  });

  const commentsHistory = noteLines.length > 0
    ? noteLines.join('\n')
    : (lead.notes ? `[${dtFormat}] ${cleanRawActionPayload(lead.notes)}` : '');

  const touchpointLines = activities.map(act => {
    const actTime = formatDate(act.timestamp);
    const cleanPayload = cleanRawActionPayload(act.details);
    return `• [${actTime}] ${act.title}: ${cleanPayload} - By: ${act.officerName || 'Officer'}`;
  });
  const touchpointLog = touchpointLines.join('\n');

  const payload = {
    action: 'saveLead',
    id: lead.id,
    customerName: lead.customerName,
    mobile: lead.mobile,
    productName: lead.productName,
    category: lead.category,
    loanAmount: lead.loanAmount,
    tenureMonths: lead.tenureMonths,
    monthlyEmi: lead.monthlyEmi,
    totalProfit: lead.totalProfit,
    processingFee: lead.processingFee,
    status: lead.status || 'New',
    followUpDate: lead.followUpDate || '',
    notes: commentsHistory,
    officerName: lead.officerName || officerProfile.officerName,
    officerId: lead.officerId || officerProfile.staffId,
    branchName: lead.branchName || officerProfile.branchName,
    touchpointLog: touchpointLog,
    approachCount: lead.approachCount || (activities.length || 1),
    timestamp: dtFormat
  };

  try {
    await fetch(officerProfile.webhookUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      mode: 'no-cors'
    });
    lead.syncStatus = 'synced';
    await LocalDB.saveLead(lead);
    renderLeads();
  } catch (err) {
    console.warn('Google Sheets sync warning:', err);
  }
}

async function manualSync() {
  if (!navigator.onLine) {
    alert('Device is currently offline.');
    return;
  }

  // 1. Trigger Firestore fetch if authenticated
  if (fb && currentUser) {
    try {
      const snap = await fb.getDocs('leads');
      snap.forEach(docSnap => {
        const d = docSnap.data();
        LocalDB.saveLead({ id: d.id || docSnap.id, ...d, syncStatus: 'synced' });
      });
    } catch (e) {
      console.warn('Firestore manual sync notice:', e);
    }
  }

  // 2. Trigger Google Sheets Push & Pull
  if (!officerProfile.webhookUrl) {
    await renderLeads();
    updateBranchFilterOptions();
    updateSettingsStats();
    alert('✅ Synchronized with Firestore database!');
    return;
  }

  const leads = await LocalDB.getAllLeads();
  const pending = leads.filter(l => l.syncStatus !== 'synced');
  for (const l of pending) {
    await syncLeadToSheet(l);
  }

  try {
    const res = await fetch(`${officerProfile.webhookUrl}?action=getLeads`, { method: 'GET' });
    if (res.ok) {
      const sheetData = await res.json();
      if (Array.isArray(sheetData)) {
        for (const r of sheetData) {
          const cleanNote = cleanRawActionPayload(r.notes || '');
          const sheetLead = {
            id: r.id || ('kb-lead-' + Math.random().toString(36).substring(2, 8)),
            customerName: r.customerName || '',
            mobile: r.mobile || '',
            category: r.category || 'asset',
            productName: r.productName || '',
            loanAmount: Number(r.loanAmount) || 0,
            tenureMonths: Number(r.tenureMonths) || 12,
            monthlyEmi: Number(r.monthlyEmi) || 0,
            totalProfit: Number(r.totalProfit) || 0,
            processingFee: Number(r.processingFee) || 0,
            status: r.status || 'New',
            notes: cleanNote,
            followUpDate: r.followUpDate || '',
            officerName: r.officerName || '',
            officerId: r.officerId || '',
            branchName: r.branchName || 'Main Branch',
            createdAt: r.timestamp ? new Date(r.timestamp).getTime() : Date.now(),
            syncStatus: 'synced',
            approachCount: Number(r.approachCount) || 1,
            activities: []
          };
          await LocalDB.saveLead(sheetLead);
        }
      }
    }
  } catch (err) {
    console.warn('Google Sheet pull notice:', err);
  }

  await renderLeads();
  updateBranchFilterOptions();
  updateSettingsStats();
  alert('✅ Sync completed across Firestore and Google Sheets!');
}

// 18. SETTINGS & PROFILE MANAGEMENT
async function loadSettings() {
  officerProfile.officerName = await LocalDB.getSetting('officerName', 'Anjum Naveed');
  officerProfile.staffId = await LocalDB.getSetting('staffId', 'RM-8433');
  officerProfile.branchName = await LocalDB.getSetting('branchName', 'Main Branch');
  officerProfile.webhookUrl = await LocalDB.getSetting('webhookUrl', '');

  if (!officerProfile.webhookUrl && fb) {
    try {
      const configDoc = await fb.getDoc('config', 'global');
      if (configDoc.exists() && configDoc.data().googleSheetsWebhookUrl) {
        officerProfile.webhookUrl = configDoc.data().googleSheetsWebhookUrl;
        await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);
      }
    } catch (e) {}
  }

  document.getElementById('settingsOfficerName').value = officerProfile.officerName;
  document.getElementById('settingsStaffId').value = officerProfile.staffId;
  document.getElementById('settingsBranch').value = officerProfile.branchName;
  document.getElementById('settingsWebhookUrl').value = officerProfile.webhookUrl;

  document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
  document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName}`;
}

async function saveSettings() {
  officerProfile.officerName = document.getElementById('settingsOfficerName').value.trim() || 'Loan Officer';
  officerProfile.staffId = document.getElementById('settingsStaffId').value.trim() || 'RO-001';
  officerProfile.branchName = document.getElementById('settingsBranch').value.trim() || 'Main Branch';
  officerProfile.webhookUrl = document.getElementById('settingsWebhookUrl').value.trim();

  await LocalDB.setSetting('officerName', officerProfile.officerName);
  await LocalDB.setSetting('staffId', officerProfile.staffId);
  await LocalDB.setSetting('branchName', officerProfile.branchName);
  await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);

  if (fb && currentUser) {
    try {
      const userData = {
        uid: currentUser.uid,
        email: currentUser.email || '',
        officerName: officerProfile.officerName,
        staffId: officerProfile.staffId,
        branchName: officerProfile.branchName,
        updatedAt: Date.now()
      };
      await fb.setDoc('users', currentUser.uid, userData, true);
      await fb.setDoc('config', 'global', {
        registeredUsersMap: { [currentUser.uid]: userData }
      }, true);
    } catch (e) {
      console.warn('Firestore profile sync error:', e);
    }
  }

  document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
  document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName}`;
  updateBranchFilterOptions();
  alert('✅ Profile saved and synced!');
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
    alert('Connection test note: ' + err.message);
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

// 19. UTILITY HELPERS
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
