/**
 * KB Loan Calculator & Lead CRM - Core Application, Firebase Sync, GIS Auth & Offline Engine
 * Works in coordination with ./calc-engine.js and ./admin-crm.js
 */

// 1. FIREBASE & GOOGLE IDENTITY SERVICES (GIS) BRIDGE STATE
const GOOGLE_WEB_CLIENT_ID = "184681541597-4n1vcijaaf2eh00i4t98us29d9nf0o14.apps.googleusercontent.com";
let fb = null;
let currentUser = null;
let currentUserProfile = null;
let firebaseInitialized = false;
let gisInitialized = false;
let unsubscribeProducts = null;
let unsubscribeLeads = null;
let unsubscribeGlobalConfig = null;
let unsubscribeUserDoc = null;
let unsubscribeUsersCol = null;
let toastTimer = null;
let cachedLeadsList = [];
let activeLoadingCount = 0;
let deferredInstallPrompt = null;
let lastKnownGeoLocation = null;
let onboardingShownThisSession = false;
let isVerifyingProfile = false;
let verifyProfileQueued = false;
let lastCloudSyncTime = 0;
let syncHeartbeatTimer = null;
let loadingDismissTimer = null;
let loadingFailSafeTimer = null;
let isSyncingNow = false;
let hasPulledSheetOnAuth = false;
let currentLeadsScopeKey = '';
let scopedLeadBuckets = new Map();
let scopedBucketReady = new Set();
let expectedScopedBuckets = new Set();
const backgroundLeadSyncQueues = new Map();

let globalConfig = {
  fedRate: 16.0,
  filerTaxRate: 20.0,
  nonFilerTaxRate: 40.0,
  currency: 'PKR',
  adminPin: 'Admin123',
  adminBroadcastMessage: '',
  adminBroadcastId: '',
  appUpdateUrl: '',
  appUpdateId: '',
  appUpdateMessage: '',
  googleSheetsWebhookUrl: '',
  registeredUsersMap: {},
  liveUsersCollectionMap: {},
  deletedUserKeys: []
};

// 2. BANK PRODUCTS CONFIGURATION (DEFAULT SEED & DYNAMIC FIRESTORE RECEPTACLE)
let BANK_PRODUCTS = [
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
    minAge: 21,
    maxAgeAtMaturity: 65,
    tenures: [6, 12, 18, 24, 30, 36],
    feePercent: 1.5,
    defaultFeeValue: 1.5,
    fedRate: 16.0,
    collateralDetails: "Pension book verification, verifiable pension credit in KBL account, CNIC copy & promissory note.",
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
    minAge: 21,
    maxAgeAtMaturity: 60,
    tenures: [6, 12, 18, 24, 30, 36],
    feePercent: 1.5,
    defaultFeeValue: 1.5,
    fedRate: 16.0,
    collateralDetails: "Confirmed permanent employee salary certificate, 6-month bank statement, CNIC & employer deduction undertaking.",
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
    minAge: 21,
    maxAgeAtMaturity: 65,
    tenures: [6, 12, 18, 24],
    feePercent: 1.5,
    defaultFeeValue: 1.5,
    fedRate: 16.0,
    collateralDetails: "Running business proof (minimum 1 year), business cashflow assessment, personal/third-party guarantee.",
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
    minAge: 21,
    maxAgeAtMaturity: 65,
    tenures: [6, 12, 18, 24, 30, 36],
    feePercent: 1.5,
    defaultFeeValue: 1.5,
    fedRate: 16.0,
    collateralDetails: "Business premises verification, valid CNIC, and promissory note per KBL SOC.",
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
    minAge: 20,
    maxAgeAtMaturity: 65,
    tenures: [6, 12, 18, 24],
    feePercent: 1.25,
    defaultFeeValue: 1.25,
    fedRate: 16.0,
    collateralDetails: "Personal guarantee and household cashflow evaluation.",
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: ""
  },
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
    minAge: 18,
    maxAgeAtMaturity: 99,
    tenures: [12],
    feePercent: 0,
    defaultFeeValue: 0,
    fedRate: 0,
    collateralDetails: "Valid CNIC, biometric verification, Filer/Non-Filer tax certificate; monthly profit payout.",
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: { 12: 17.5 },
    liabilityPricingType: "tenure"
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
    minAge: 18,
    maxAgeAtMaturity: 99,
    tenures: [36],
    feePercent: 0,
    defaultFeeValue: 0,
    fedRate: 0,
    collateralDetails: "Term deposit certificate with early encashment schedule per KBL SOC.",
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: { 36: 16.0 },
    liabilityPricingType: "tenure"
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
    minAge: 18,
    maxAgeAtMaturity: 99,
    tenures: [1, 3, 6, 12],
    feePercent: 0,
    defaultFeeValue: 0,
    fedRate: 0,
    collateralDetails: "Profit calculated on monthly average balance; WHT applied per Filer/Non-Filer status.",
    rateSlabs: [],
    feeSlabs: [],
    tenureRates: {},
    liabilityPricingType: "amount"
  }
];

// 3. RECURSIVE DE-NESTING PAYLOAD SANITIZER
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

// 4. RESILIENT INDEXEDDB + LOCALSTORAGE PERSISTENCE LAYER
class LocalDB {
  static _dbPromise = null;
  static _useFallback = false;
  static _memStore = new Map();

  static _lsGet(key, fallback = null) {
    try {
      const raw = window.localStorage.getItem('kbl_pwa_' + key);
      if (raw !== null) return JSON.parse(raw);
    } catch (e) {}
    return this._memStore.has(key) ? this._memStore.get(key) : fallback;
  }

  static _lsSet(key, value) {
    this._memStore.set(key, value);
    try {
      window.localStorage.setItem('kbl_pwa_' + key, JSON.stringify(value));
      return true;
    } catch (e) {
      return false;
    }
  }

  static init() {
    if (this._useFallback) return Promise.resolve(null);
    if (this._dbPromise) return this._dbPromise;

    this._dbPromise = new Promise((resolve) => {
      if (typeof indexedDB === 'undefined') {
        this._useFallback = true;
        resolve(null);
        return;
      }
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          this._useFallback = true;
          console.warn('IndexedDB init timed out — falling back to localStorage');
          resolve(null);
        }
      }, 2200);

      try {
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
        request.onsuccess = (e) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve(e.target.result);
        };
        request.onerror = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          this._useFallback = true;
          resolve(null);
        };
        request.onblocked = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          this._useFallback = true;
          resolve(null);
        };
      } catch (err) {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          this._useFallback = true;
          resolve(null);
        }
      }
    });

    return this._dbPromise;
  }

  static async getAllLeads() {
    const d = await this.init();
    if (!d) {
      const res = this._lsGet('leads_list', []);
      cachedLeadsList = Array.isArray(res) ? res : [];
      return cachedLeadsList;
    }
    return new Promise((resolve) => {
      try {
        const tx = d.transaction('leads', 'readonly');
        const store = tx.objectStore('leads');
        const req = store.getAll();
        req.onsuccess = () => {
          const res = req.result || [];
          cachedLeadsList = res;
          this._lsSet('leads_list', res);
          resolve(res);
        };
        req.onerror = () => {
          const fbRes = this._lsGet('leads_list', []);
          cachedLeadsList = Array.isArray(fbRes) ? fbRes : [];
          resolve(cachedLeadsList);
        };
      } catch (e) {
        const fbRes = this._lsGet('leads_list', []);
        cachedLeadsList = Array.isArray(fbRes) ? fbRes : [];
        resolve(cachedLeadsList);
      }
    });
  }

  static async saveLead(lead) {
    const idx = cachedLeadsList.findIndex(x => x.id === lead.id);
    if (idx >= 0) cachedLeadsList[idx] = lead;
    else cachedLeadsList.push(lead);
    this._lsSet('leads_list', cachedLeadsList);

    const d = await this.init();
    if (!d) return true;
    return new Promise((resolve) => {
      try {
        const tx = d.transaction('leads', 'readwrite');
        const store = tx.objectStore('leads');
        store.put(lead);
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(true);
      } catch (e) {
        resolve(true);
      }
    });
  }

  static async deleteLead(leadId) {
    cachedLeadsList = cachedLeadsList.filter(x => x.id !== leadId);
    this._lsSet('leads_list', cachedLeadsList);

    const d = await this.init();
    if (!d) return true;
    return new Promise((resolve) => {
      try {
        const tx = d.transaction('leads', 'readwrite');
        const store = tx.objectStore('leads');
        store.delete(leadId);
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(true);
      } catch (e) {
        resolve(true);
      }
    });
  }

  static async getSetting(key, fallback = '') {
    const d = await this.init();
    if (!d) return this._lsGet('setting_' + key, fallback);
    return new Promise((resolve) => {
      try {
        const tx = d.transaction('settings', 'readonly');
        const store = tx.objectStore('settings');
        const req = store.get(key);
        req.onsuccess = () => resolve(req.result ? req.result.value : this._lsGet('setting_' + key, fallback));
        req.onerror = () => resolve(this._lsGet('setting_' + key, fallback));
      } catch (e) {
        resolve(this._lsGet('setting_' + key, fallback));
      }
    });
  }

  static async setSetting(key, value) {
    this._lsSet('setting_' + key, value);
    const d = await this.init();
    if (!d) return true;
    return new Promise((resolve) => {
      try {
        const tx = d.transaction('settings', 'readwrite');
        const store = tx.objectStore('settings');
        store.put({ key, value });
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(true);
      } catch (e) {
        resolve(true);
      }
    });
  }
}

// 5. APPLICATION STATE & BOOTSTRAP
let currentProduct = BANK_PRODUCTS[0];
let currentEditingLeadId = null;
let currentFollowUpLeadId = null;

let officerProfile = {
  officerName: '',
  staffId: '',
  branchName: '',
  webhookUrl: '',
  profileUpdatedAt: 0
};

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredInstallPrompt = e;
  updatePwaBannerState();
});

window.addEventListener('appinstalled', () => {
  deferredInstallPrompt = null;
  updatePwaBannerState();
  showToast('📲 KB Loan Calculator installed as App!');
});

function updatePwaBannerState() {
  const banner = document.getElementById('pwaBanner');
  const txt = document.getElementById('pwaBannerText');
  const btn = document.getElementById('pwaInstallBtn');
  if (!banner) return;
  const isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  if (isStandalone) {
    banner.style.display = 'none';
    return;
  }
  banner.style.display = 'flex';
  if (deferredInstallPrompt) {
    if (txt) txt.textContent = '📲 Install KB Loan Calculator directly to your App Drawer';
    if (btn) btn.textContent = '📲 Install App';
  } else {
    if (txt) txt.textContent = '📲 Add KB Loan Calculator to Home Screen / App Drawer';
    if (btn) btn.textContent = '📲 Install App';
  }
}

async function triggerPwaInstall() {
  if (deferredInstallPrompt) {
    try {
      deferredInstallPrompt.prompt();
      const choice = await deferredInstallPrompt.userChoice;
      if (choice && choice.outcome === 'accepted') {
        deferredInstallPrompt = null;
        updatePwaBannerState();
        showToast('✅ Installing KB Loan Calculator...');
        return;
      }
    } catch (e) {
      console.warn('PWA install prompt notice:', e);
    }
  }
  openModal('installGuideModal');
}

window.addEventListener('DOMContentLoaded', async () => {
  window.__kblAppBooted = true;
  if (window.location.search && window.location.search.includes('reset=')) {
    try {
      const cleanUrl = new URL(window.location.href);
      cleanUrl.searchParams.delete('reset');
      window.history.replaceState({}, document.title, cleanUrl.pathname + cleanUrl.search + cleanUrl.hash);
      setTimeout(() => showToast('✅ Cache reset complete — syncing fresh data!'), 450);
    } catch (e) {}
  }
  try {
    const savedTheme = await LocalDB.getSetting('themeMode', 'light');
    applyThemeMode(savedTheme);
  } catch (e) {
    applyThemeMode('light');
  }
  updatePwaBannerState();

  try {
    const cachedProdsRaw = await LocalDB.getSetting('cachedProductsJson', '');
    if (cachedProdsRaw) {
      const parsedProds = JSON.parse(cachedProdsRaw);
      if (Array.isArray(parsedProds) && parsedProds.length > 0) {
        BANK_PRODUCTS = parsedProds;
      }
    }
  } catch (e) {}

  try {
    await loadSettings();
  } catch (e) {
    console.warn('Settings load notice:', e);
  }
  populateProducts();
  calculate();

  try {
    await renderLeads();
  } catch (e) {
    console.warn('Initial leads render notice:', e);
  }
  updateOnlineStatus();
  updateSettingsStats();
  prewarmGeoLocation();

  if (window.FirebaseBridge) {
    initFirebaseAuthAndFirestore();
  }
  initGoogleIdentityServices();
  startAutoSyncHeartbeat();

  window.addEventListener('online', () => {
    updateOnlineStatus();
    flushPendingLeadsToCloudAndSheets(true);
  });
  window.addEventListener('offline', updateOnlineStatus);

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').then((reg) => {
      try { reg.update(); } catch (e) {}
    }).catch(err => console.warn('SW registration notice:', err));
  }
});

window.addEventListener('firebase-bridge-ready', () => {
  initFirebaseAuthAndFirestore();
});
window.addEventListener('firebase-bridge-error', () => {
  showToast('⚠️ Cloud connection limited on this network — local mode active.');
});
window.addEventListener('gis-script-ready', () => {
  initGoogleIdentityServices();
});

function startAutoSyncHeartbeat() {
  if (syncHeartbeatTimer) clearInterval(syncHeartbeatTimer);
  syncHeartbeatTimer = setInterval(() => {
    if (navigator.onLine && fb && currentUser && !isSyncingNow) {
      flushPendingLeadsToCloudAndSheets(true);
    }
  }, 25000);
}

// 6. FIREBASE AUTH & ACCESS CONTROL GATE (ALLOW / DENY)
function initFirebaseAuthAndFirestore() {
  if (firebaseInitialized || !window.FirebaseBridge) return;
  fb = window.FirebaseBridge;
  firebaseInitialized = true;

  fb.getRedirectResult().catch((err) => {
    if (err && err.code) {
      handleAuthError(err);
    }
  });

  fb.onAuthStateChanged(async (user) => {
    currentUser = user;
    const headerWrap = document.getElementById('gisHeaderButtonWrap');
    const authBtn = document.getElementById('googleSignInBtn');
    const gisSettingsSlot = document.getElementById('gisSettingsSlot');
    const chip = document.getElementById('userProfileChip');
    const avatarImg = document.getElementById('userAvatarImg');
    const nameSpan = document.getElementById('userNameSpan');
    const settingsAuthBtn = document.getElementById('settingsAuthBtn');
    const settingsEmail = document.getElementById('settingsUserEmail');
    const settingsStatus = document.getElementById('settingsAccessStatus');
    const settingsRole = document.getElementById('settingsUserRole');

    if (user) {
      hasPulledSheetOnAuth = false;
      if (headerWrap) headerWrap.style.display = 'none';
      if (authBtn) authBtn.style.display = 'none';
      if (gisSettingsSlot) gisSettingsSlot.style.display = 'none';
      if (chip) chip.style.display = 'inline-flex';
      if (nameSpan) nameSpan.textContent = user.displayName || (user.email ? user.email.split('@')[0] : 'Officer');
      if (user.photoURL && avatarImg) avatarImg.src = user.photoURL;
      if (settingsEmail) settingsEmail.textContent = user.email || 'Google User';
      if (settingsAuthBtn) {
        settingsAuthBtn.style.display = 'inline-flex';
        settingsAuthBtn.textContent = '🚪 Sign Out';
      }
      closeModal('googleSignInModal');

      // Attach real-time listeners concurrently
      startFirestoreGlobalConfigListener(user);
      startFirestoreUserListener(user);
      startFirestoreUsersCollectionListener(user);
      startFirestoreProductsListener();
      startFirestoreLeadsListener();

      await verifyUserAccessAndSyncProfile(user, true);

      // Push any offline-created leads & perform 2-way Google Sheets sync after profile/branch rights are verified
      if (navigator.onLine) {
        flushPendingLeadsToCloudAndSheets(true).then(() => {
          if (!hasPulledSheetOnAuth && currentUserProfile && getEffectiveWebhookUrl()) {
            hasPulledSheetOnAuth = true;
            return pullLeadsFromGoogleSheet(true);
          }
        }).catch(e => console.warn('Initial post-auth sync notice:', e));
      }
    } else {
      if (headerWrap) headerWrap.style.display = 'inline-flex';
      if (gisSettingsSlot) gisSettingsSlot.style.display = 'flex';
      if (chip) chip.style.display = 'none';
      if (settingsEmail) settingsEmail.textContent = 'Not signed in';
      if (settingsAuthBtn) {
        settingsAuthBtn.style.display = 'inline-flex';
        settingsAuthBtn.textContent = '🔑 Sign In with Google';
      }
      if (settingsStatus) {
        settingsStatus.textContent = 'Local Guest Mode';
        settingsStatus.style.color = '#F59E0B';
      }
      if (settingsRole) settingsRole.textContent = 'Loan Officer';

      renderGisButtons();
      hideAccessDeniedScreen();

      if (unsubscribeProducts) { unsubscribeProducts(); unsubscribeProducts = null; }
      if (unsubscribeLeads) { unsubscribeLeads(); unsubscribeLeads = null; }
      currentLeadsScopeKey = '';
      if (unsubscribeGlobalConfig) { unsubscribeGlobalConfig(); unsubscribeGlobalConfig = null; }
      if (unsubscribeUserDoc) { unsubscribeUserDoc(); unsubscribeUserDoc = null; }
      if (unsubscribeUsersCol) { unsubscribeUsersCol(); unsubscribeUsersCol = null; }
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
      globalConfig.adminPin = d.adminPin || 'Admin123';
      globalConfig.adminBroadcastMessage = d.adminBroadcastMessage || '';
      globalConfig.adminBroadcastId = d.adminBroadcastId || '';
      globalConfig.appUpdateUrl = d.appUpdateUrl || '';
      globalConfig.appUpdateId = d.appUpdateId || '';
      globalConfig.appUpdateMessage = d.appUpdateMessage || '';
      globalConfig.googleSheetsWebhookUrl = d.googleSheetsWebhookUrl || '';
      globalConfig.registeredUsersMap = d.registeredUsersMap || {};
      globalConfig.deletedUserKeys = Array.isArray(d.deletedUserKeys) ? d.deletedUserKeys : [];

      let webhookJustUpdated = false;
      if (d.googleSheetsWebhookUrl && officerProfile.webhookUrl !== d.googleSheetsWebhookUrl) {
        officerProfile.webhookUrl = d.googleSheetsWebhookUrl;
        await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);
        webhookJustUpdated = true;
      }

      updateAppUpdateBanner();
      checkAndShowBroadcastModal();
      calculate();

      if (adminUnlocked) {
        populateAdminControls();
      }
      if (user) {
        await verifyUserAccessAndSyncProfile(user, false);
        if (webhookJustUpdated && currentUser && currentUserProfile && !hasPulledSheetOnAuth && navigator.onLine) {
          hasPulledSheetOnAuth = true;
          pullLeadsFromGoogleSheet(true).catch(e => console.warn('Auto sheet pull notice:', e));
        }
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
    await verifyUserAccessAndSyncProfile(user, false);
  }, (err) => {
    console.warn('User doc listener notice:', err);
  });
}

function startFirestoreUsersCollectionListener(user) {
  if (!fb) return;
  if (unsubscribeUsersCol) unsubscribeUsersCol();

  unsubscribeUsersCol = fb.onCollectionSnapshot('users', async (snapshot) => {
    const liveMap = {};
    snapshot.forEach(docSnap => {
      const u = docSnap.data();
      if (u && typeof u === 'object') {
        const uid = u.uid || docSnap.id;
        liveMap[uid] = { uid, ...u };
      }
    });
    globalConfig.liveUsersCollectionMap = liveMap;
    if (adminUnlocked) {
      renderAdminUsersList();
    }
    if (user) {
      await verifyUserAccessAndSyncProfile(user, false);
    } else {
      renderLeads();
    }
  }, (err) => {
    console.warn('Users collection listener notice:', err);
  });
}

async function verifyUserAccessAndSyncProfile(user, fetchFresh = false) {
  if (!fb || !user) return;
  if (isVerifyingProfile) {
    verifyProfileQueued = true;
    return;
  }
  isVerifyingProfile = true;

  try {
    const isPrimaryAdmin = Boolean(
      user.email && (user.email.toLowerCase().includes('vipanjum') || user.email.toLowerCase().includes('admin'))
    );
    const userEmailLower = (user.email || '').trim().toLowerCase();
    const localStaffLower = (officerProfile.staffId || '').trim().toLowerCase();

    let directUserDoc = null;
    if (fetchFresh || !(globalConfig.liveUsersCollectionMap && globalConfig.liveUsersCollectionMap[user.uid])) {
      try {
        const snap = await Promise.race([
          fb.getDoc('users', user.uid),
          new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 6500))
        ]);
        directUserDoc = snap.exists() ? snap.data() : null;
      } catch (e) {}
    } else {
      directUserDoc = globalConfig.liveUsersCollectionMap[user.uid] || null;
    }

    let regMap = globalConfig.registeredUsersMap || {};
    if (fetchFresh || Object.keys(regMap).length === 0) {
      try {
        const globalSnap = await Promise.race([
          fb.getDoc('config', 'global'),
          new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 6500))
        ]);
        if (globalSnap.exists()) {
          const gData = globalSnap.data();
          regMap = gData.registeredUsersMap || {};
          globalConfig.registeredUsersMap = regMap;
        }
      } catch (e) {
        console.warn('Global registry read notice:', e);
      }
    }

    const candidates = [];
    const considerRecord = (rec) => {
      if (!rec || typeof rec !== 'object') return;
      const rUid = (rec.uid || '').trim();
      const rEmail = (rec.email || '').trim().toLowerCase();
      const rStaff = (rec.staffId || '').trim().toLowerCase();
      const uidMatch = rUid && rUid === user.uid;
      const emailMatch = userEmailLower && rEmail && rEmail === userEmailLower;
      const staffMatch = localStaffLower && rStaff && rStaff === localStaffLower && (!rEmail || !userEmailLower || rEmail === userEmailLower);
      if (uidMatch || emailMatch || staffMatch) {
        candidates.push(rec);
      }
    };

    if (directUserDoc) candidates.push({ uid: user.uid, ...directUserDoc });
    Object.entries(regMap).forEach(([k, u]) => {
      if (u && typeof u === 'object') considerRecord({ uid: u.uid || k, ...u });
    });
    Object.entries(globalConfig.liveUsersCollectionMap || {}).forEach(([k, u]) => {
      if (u && typeof u === 'object') considerRecord({ uid: u.uid || k, ...u });
    });

    candidates.sort((a, b) => (Number(b.updatedAt) || 0) - (Number(a.updatedAt) || 0));

    let effectiveRecord = null;
    if (candidates.length > 0) {
      effectiveRecord = { uid: user.uid };
      // Merge values oldest -> newest so freshest timestamp wins for every non-empty field
      candidates.slice().reverse().forEach(rec => {
        Object.entries(rec).forEach(([k, v]) => {
          if (v !== undefined && v !== null && v !== '') {
            effectiveRecord[k] = v;
          }
        });
      });
      const freshest = candidates[0];
      if (freshest.isBlocked !== undefined) effectiveRecord.isBlocked = freshest.isBlocked;
      if (freshest.canUseSupervisorFilter !== undefined) effectiveRecord.canUseSupervisorFilter = freshest.canUseSupervisorFilter;
      if (freshest.role) effectiveRecord.role = freshest.role;
      if (freshest.allowedSupervisorBranch !== undefined) effectiveRecord.allowedSupervisorBranch = freshest.allowedSupervisorBranch;
      if (freshest.updatedAt) effectiveRecord.updatedAt = Number(freshest.updatedAt) || 0;
    }

    const localProfileTs = Number(officerProfile.profileUpdatedAt || await LocalDB.getSetting('profileUpdatedAt', 0)) || 0;

    if (effectiveRecord) {
      currentUserProfile = { uid: user.uid, ...effectiveRecord };

      if (effectiveRecord.isBlocked === true && !isPrimaryAdmin) {
        showAccessDeniedScreen(user);
        return;
      } else {
        hideAccessDeniedScreen();
      }

      const remoteTs = Number(effectiveRecord.updatedAt) || 0;
      const localNameBlank = !officerProfile.officerName || officerProfile.officerName.trim().toLowerCase() === 'loan officer';
      const localStaffBlank = !officerProfile.staffId || /^RO-[A-F0-9]{4}$/i.test(officerProfile.staffId.trim());
      const localBranchBlank = !officerProfile.branchName || !officerProfile.branchName.trim();
      const cloudWins = remoteTs >= localProfileTs || localNameBlank || localStaffBlank || localBranchBlank;

      if (cloudWins) {
        if (effectiveRecord.officerName && effectiveRecord.officerName.trim()) {
          officerProfile.officerName = effectiveRecord.officerName.trim();
          await LocalDB.setSetting('officerName', officerProfile.officerName);
        }
        if (effectiveRecord.staffId && effectiveRecord.staffId.trim()) {
          officerProfile.staffId = effectiveRecord.staffId.trim();
          await LocalDB.setSetting('staffId', officerProfile.staffId);
        }
        if (effectiveRecord.branchName && effectiveRecord.branchName.trim()) {
          officerProfile.branchName = effectiveRecord.branchName.trim();
          await LocalDB.setSetting('branchName', officerProfile.branchName);
        }
        if (remoteTs > localProfileTs) {
          officerProfile.profileUpdatedAt = remoteTs;
          await LocalDB.setSetting('profileUpdatedAt', remoteTs);
        }
      } else if (localProfileTs > remoteTs && (!localNameBlank || !localStaffBlank || !localBranchBlank)) {
        // Local Web offline edits are strictly newer than Cloud — push them upward!
        const pushRec = {
          ...effectiveRecord,
          uid: user.uid,
          email: user.email || effectiveRecord.email || '',
          officerName: officerProfile.officerName,
          staffId: officerProfile.staffId,
          branchName: officerProfile.branchName,
          updatedAt: localProfileTs
        };
        try {
          await fb.setDoc('users', user.uid, pushRec, true);
          await fb.setDoc('config', 'global', { registeredUsersMap: { [user.uid]: pushRec } }, true);
        } catch (e) {}
      }

      const roleEl = document.getElementById('settingsUserRole');
      if (roleEl) {
        const scope = effectiveRecord.allowedSupervisorBranch || effectiveRecord.branchName || 'Own Branch';
        roleEl.textContent = `${effectiveRecord.role || 'Loan Officer'} (${effectiveRecord.canUseSupervisorFilter === false ? 'Personal Only' : scope})`;
      }
    } else {
      const defaultRole = isPrimaryAdmin ? 'Admin' : 'Loan Officer';
      const defaultBlocked = !isPrimaryAdmin;
      const initialBranch = officerProfile.branchName || '';
      const nowTs = localProfileTs || Date.now();

      const newUser = {
        uid: user.uid,
        email: user.email || '',
        officerName: user.displayName || officerProfile.officerName || 'Loan Officer',
        staffId: officerProfile.staffId || ('RO-' + user.uid.substring(0, 4).toUpperCase()),
        branchName: initialBranch,
        allowedSupervisorBranch: isPrimaryAdmin ? 'ALL' : initialBranch,
        canUseSupervisorFilter: isPrimaryAdmin,
        isBlocked: defaultBlocked,
        role: defaultRole,
        updatedAt: nowTs
      };

      officerProfile.officerName = newUser.officerName;
      officerProfile.staffId = newUser.staffId;
      officerProfile.branchName = newUser.branchName;
      officerProfile.profileUpdatedAt = nowTs;
      await LocalDB.setSetting('officerName', officerProfile.officerName);
      await LocalDB.setSetting('staffId', officerProfile.staffId);
      await LocalDB.setSetting('branchName', officerProfile.branchName);
      await LocalDB.setSetting('profileUpdatedAt', nowTs);

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

    const displayName = toTitleCase(officerProfile.officerName || user.displayName || 'Officer');
    const displayStaff = officerProfile.staffId || 'RO';
    const displayBranch = officerProfile.branchName || 'Unassigned';

    document.getElementById('officerBadge').textContent = `👤 Officer: ${displayName} (${displayStaff})`;
    document.getElementById('branchBadge').textContent = `🏢 ${displayBranch}`;
    document.getElementById('settingsOfficerName').value = officerProfile.officerName;
    document.getElementById('settingsStaffId').value = officerProfile.staffId;
    document.getElementById('settingsBranch').value = officerProfile.branchName;

    const statusEl = document.getElementById('settingsAccessStatus');
    if (statusEl) {
      statusEl.textContent = 'Authorized (Active)';
      statusEl.style.color = '#16A34A';
    }

    openOnboardingModalIfNeeded(user);

    await sanitizeHijackedLeadOwnership();

    // Re-bind scoped Firestore leads listener if user's branch/role/staffId changed
    if (getCurrentLeadsScopeKey() !== currentLeadsScopeKey) {
      startFirestoreLeadsListener();
      if (!fetchRemote && navigator.onLine && currentUserProfile && getEffectiveWebhookUrl()) {
        hasPulledSheetOnAuth = true;
        pullLeadsFromGoogleSheet(true).catch(e => console.warn('Scope-change sheet pull notice:', e));
      }
    } else {
      await pruneOutOfScopeCachedLeads();
    }

    await renderLeads();
    updateSettingsStats();
  } catch (err) {
    console.warn('Error verifying user access in Firestore:', err);
  } finally {
    isVerifyingProfile = false;
    if (verifyProfileQueued) {
      verifyProfileQueued = false;
      setTimeout(() => verifyUserAccessAndSyncProfile(user, false), 150);
    }
  }
}

function openOnboardingModalIfNeeded(user) {
  if (!user || onboardingShownThisSession) return;
  const needsName = !officerProfile.officerName || officerProfile.officerName.trim().toLowerCase() === 'loan officer';
  const needsStaff = !officerProfile.staffId || /^RO-[A-F0-9]{4}$/i.test(officerProfile.staffId.trim());
  const needsBranch = !officerProfile.branchName || !officerProfile.branchName.trim();
  if (needsName || needsStaff || needsBranch) {
    onboardingShownThisSession = true;
    const obName = document.getElementById('obOfficerName');
    const obStaff = document.getElementById('obStaffId');
    const obBranch = document.getElementById('obBranchName');
    if (obName) obName.value = officerProfile.officerName && officerProfile.officerName !== 'Loan Officer' ? officerProfile.officerName : (user.displayName || '');
    if (obStaff) obStaff.value = officerProfile.staffId || '';
    if (obBranch) obBranch.value = officerProfile.branchName || '';
    openModal('onboardingModal');
  }
}

async function confirmOnboardingProfile() {
  const nameRaw = (document.getElementById('obOfficerName')?.value || '').trim();
  const staffRaw = (document.getElementById('obStaffId')?.value || '').trim();
  const branchRaw = (document.getElementById('obBranchName')?.value || '').trim();

  if (!nameRaw || !staffRaw || !branchRaw) {
    showToast('⚠️ Please enter Officer Name, Staff ID, and Branch Name.');
    return;
  }

  document.getElementById('settingsOfficerName').value = toTitleCase(nameRaw);
  document.getElementById('settingsStaffId').value = staffRaw.toUpperCase();
  document.getElementById('settingsBranch').value = toTitleCase(branchRaw);
  closeModal('onboardingModal');
  await saveSettings();
}

function showAccessDeniedScreen(user) {
  document.getElementById('deniedUserEmail').textContent = user.email || 'User';
  document.getElementById('deniedUserUid').textContent = user.uid || '';
  document.getElementById('accessDeniedScreen').classList.add('active');
  const statusEl = document.getElementById('settingsAccessStatus');
  if (statusEl) {
    statusEl.textContent = 'Blocked / Inactive';
    statusEl.style.color = '#EF4444';
  }
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
  showToast('Google Sign-In notice: ' + (err.message || err.code));
}

function copyUnauthorizedHost() {
  const host = document.getElementById('unauthorizedHostText').textContent;
  copyToClipboard(host, `✅ Copied "${host}"! Add it in Firebase Console ➔ Authorized domains.`);
}

// 6B. GOOGLE IDENTITY SERVICES (GIS) SIGN-IN
function initGoogleIdentityServices() {
  if (gisInitialized) return;
  if (!window.google || !window.google.accounts || !window.google.accounts.id) return;

  try {
    window.google.accounts.id.initialize({
      client_id: GOOGLE_WEB_CLIENT_ID,
      callback: handleGoogleCredentialResponse,
      auto_select: false,
      cancel_on_tap_outside: true,
      context: 'signin'
    });
    gisInitialized = true;
    renderGisButtons();
  } catch (e) {
    console.warn('GIS initialization notice:', e);
  }
}

function renderGisButtons() {
  if (!gisInitialized || !window.google || !window.google.accounts || !window.google.accounts.id) return;

  const headerSlot = document.getElementById('gisHeaderSlot');
  const fallbackBtn = document.getElementById('googleSignInBtn');
  const settingsSlot = document.getElementById('gisSettingsSlot');
  const modalSlot = document.getElementById('gisModalSlot');

  try {
    if (headerSlot && !currentUser) {
      headerSlot.innerHTML = '';
      window.google.accounts.id.renderButton(headerSlot, {
        type: 'standard',
        theme: 'outline',
        size: 'medium',
        text: 'signin',
        shape: 'pill'
      });
      if (fallbackBtn) fallbackBtn.style.display = 'none';
    }
    if (settingsSlot && !currentUser) {
      settingsSlot.innerHTML = '';
      window.google.accounts.id.renderButton(settingsSlot, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        text: 'signin_with',
        shape: 'rectangular',
        width: 260
      });
    }
    if (modalSlot) {
      modalSlot.innerHTML = '';
      window.google.accounts.id.renderButton(modalSlot, {
        type: 'standard',
        theme: 'filled_blue',
        size: 'large',
        text: 'signin_with',
        shape: 'rectangular',
        width: 280
      });
    }
  } catch (e) {
    console.warn('GIS renderButton notice:', e);
    if (fallbackBtn && !currentUser) fallbackBtn.style.display = 'inline-flex';
  }
}

async function handleGoogleCredentialResponse(response) {
  if (!response || !response.credential) {
    showToast('Google Sign-In did not return a credential.');
    return;
  }
  if (!fb) {
    showToast('Firebase is still initializing. Please try again.');
    return;
  }
  try {
    await fb.signInWithGoogleIdToken(response.credential);
    closeModal('googleSignInModal');
    showToast('✅ Signed in successfully!');
  } catch (err) {
    handleAuthError(err);
  }
}

async function signInWithGoogle() {
  if (!fb) {
    showToast('Firebase Auth is still initializing. Please wait a second.');
    return;
  }

  initGoogleIdentityServices();
  const originEl = document.getElementById('gisOriginText');
  if (originEl) originEl.textContent = window.location.origin;

  if (gisInitialized && window.google && window.google.accounts && window.google.accounts.id) {
    renderGisButtons();
    openModal('googleSignInModal');
    try {
      window.google.accounts.id.prompt();
    } catch (e) {}
    return;
  }

  await signInWithFirebasePopupFallback();
}

async function signInWithFirebasePopupFallback() {
  if (!fb) return;
  closeModal('googleSignInModal');
  const isStandalonePWA = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  try {
    if (isStandalonePWA) {
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
    if (window.google && window.google.accounts && window.google.accounts.id) {
      window.google.accounts.id.disableAutoSelect();
    }
    await fb.signOut();
    hideAccessDeniedScreen();
    showToast('Signed out successfully.');
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

  unsubscribeProducts = fb.onCollectionSnapshot('products', async (snapshot) => {
    if (!snapshot.empty) {
      const fetched = [];
      snapshot.forEach(docSnap => {
        const d = docSnap.data();
        const pId = d.id || docSnap.id;
        const category = String(d.category || 'asset').trim().toLowerCase() === 'liability' ? 'liability' : 'asset';
        const rateType = String(d.rateType || 'reducing').trim().toLowerCase();
        const defaultAnnualRate = Number(d.defaultAnnualRate) || 0.0;
        const minAmount = Number(d.minAmount) || 50000;
        const maxAmount = Number(d.maxAmount) || 5000000;
        const minTenure = Number(d.minTenure) || 1;
        const maxTenure = Number(d.maxTenure) || 36;
        const minAge = Number(d.minAge) || 21;
        const maxAgeAtMaturity = Number(d.maxAgeAtMaturity) || 65;
        const rawFeeVal = (d.defaultFeeValue !== undefined && d.defaultFeeValue !== null)
          ? Number(d.defaultFeeValue)
          : ((d.feePercent !== undefined && d.feePercent !== null) ? Number(d.feePercent) : 1.5);
        const feePercent = isNaN(rawFeeVal) ? 1.5 : rawFeeVal;
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
          minAge,
          maxAgeAtMaturity,
          tenures: tenures.length > 0 ? tenures : [6, 12, 18, 24, 36],
          feeType: d.feeType || 'percentage',
          feePercent,
          defaultFeeValue: feePercent,
          fedRate,
          collateralDetails: d.collateralDetails || '',
          rateSlabs,
          feeSlabs,
          tenureRates,
          liabilityPricingType
        });
      });

      if (fetched.length > 0) {
        BANK_PRODUCTS = fetched;
        await LocalDB.setSetting('cachedProductsJson', JSON.stringify(fetched));
        const previousId = currentProduct ? currentProduct.id : null;
        populateProducts(previousId);
        calculate();
        if (adminUnlocked) renderAdminProductsList();
        const badge = document.getElementById('firestoreProductSyncBadge');
        if (badge) badge.textContent = `⚡ Synced (${fetched.length})`;
      }
    }
  }, (err) => {
    console.warn('Products sync stream notice:', err);
    const badge = document.getElementById('firestoreProductSyncBadge');
    if (badge) badge.textContent = '⚠️ Sync Retrying...';
  });
}

// 8. SCOPED REAL-TIME FIRESTORE LEADS SYNCHRONIZATION (Authorized Branch & Own Leads Only)
function isUserAllBranchesAdmin() {
  if (typeof canCurrentUserUseSupervisorFilter === 'function' && !canCurrentUserUseSupervisorFilter()) return false;
  if (typeof hasExplicitAllBranchesRight === 'function') return hasExplicitAllBranchesRight();
  const effAllowed = typeof getEffectiveAllowedBranch === 'function' ? getEffectiveAllowedBranch() : '';
  const tokens = effAllowed.split(/[,;|]/).map(s => s.trim()).filter(Boolean);
  return tokens.some(t => {
    const up = t.toUpperCase();
    return up === 'ALL' || up === 'ALL BRANCHES' || t === '*';
  });
}

function buildBranchQueryVariations(effAllowed) {
  if (!effAllowed) return [];
  const tokens = effAllowed.split(/[,;|]/).map(s => s.trim()).filter(Boolean);
  const set = new Set();
  const addVariants = (rawVal) => {
    const raw = String(rawVal || '').trim();
    if (!raw || ['ALL', 'ALL BRANCHES', '*'].includes(raw.toUpperCase())) return;
    set.add(raw);
    const titleRaw = typeof toTitleCase === 'function' ? toTitleCase(raw) : raw;
    set.add(titleRaw);
    set.add(raw.toLowerCase());
    set.add(raw.toUpperCase());
    const noBranch = raw.replace(/\s*\bbranch\b\s*/gi, ' ').replace(/\s+/g, ' ').trim();
    if (noBranch) {
      const titleShort = typeof toTitleCase === 'function' ? toTitleCase(noBranch) : noBranch;
      set.add(noBranch);
      set.add(titleShort);
      set.add(noBranch.toLowerCase());
      set.add(noBranch.toUpperCase());
      set.add(`${titleShort} Branch`);
      set.add(`${noBranch} Branch`);
      set.add(`${noBranch.toLowerCase()} branch`);
    }
  };

  tokens.forEach(tok => {
    addVariants(tok);
    if (typeof canonicalBranchCode === 'function' && typeof BRANCH_SHORT_CODES === 'object') {
      const canon = canonicalBranchCode(tok);
      if (canon) {
        Object.entries(BRANCH_SHORT_CODES).forEach(([alias, code]) => {
          if (code === canon) addVariants(alias);
        });
      }
    }
  });
  return Array.from(set).filter(Boolean);
}

function getCurrentLeadsScopeKey() {
  const uid = currentUser ? currentUser.uid : '';
  const staff = (officerProfile.staffId || '').trim().toUpperCase();
  const canFilter = typeof canCurrentUserUseSupervisorFilter === 'function' ? canCurrentUserUseSupervisorFilter() : true;
  const effAllowed = typeof getEffectiveAllowedBranch === 'function' ? getEffectiveAllowedBranch() : '';
  const isAll = isUserAllBranchesAdmin();
  return `${uid}|${staff}|${canFilter}|${effAllowed}|${isAll}`;
}

function isLeadInAuthorizedScope(lead) {
  if (!currentUser) return true;
  if (isUserAllBranchesAdmin()) return true;
  if (typeof isLeadOwnedByCurrentOfficer === 'function' && isLeadOwnedByCurrentOfficer(lead)) return true;
  const canFilter = typeof canCurrentUserUseSupervisorFilter === 'function' ? canCurrentUserUseSupervisorFilter() : true;
  if (!canFilter) return false;
  const effAllowed = typeof getEffectiveAllowedBranch === 'function' ? getEffectiveAllowedBranch() : '';
  if (!effAllowed) return false;
  const tokens = effAllowed.split(/[,;|]/).map(s => s.trim()).filter(Boolean);
  const leadBranch = typeof resolveLeadEffectiveBranch === 'function' ? resolveLeadEffectiveBranch(lead) : (lead.branchName || '');
  return tokens.some(tok => typeof isBranchAuthorizedForQuery === 'function' && isBranchAuthorizedForQuery(tok, leadBranch));
}

function mapRemoteDocToLead(docSnap, existing) {
  const d = docSnap.data();
  const leadId = d.id || docSnap.id;
  const remoteUpdatedMs = Number(d.updatedAtMs) || Number(d.createdAt) || 0;
  return {
    id: leadId,
    customerName: d.customerName || 'Customer',
    mobile: d.mobile || '',
    dob: d.dob || '',
    age: Number(d.age) || 0,
    monthlyIncome: Number(d.monthlyIncome) || 0,
    dbrAllowed: Number(d.dbrAllowed) || 50,
    category: d.category || 'asset',
    productId: d.productId || '',
    productName: d.productName || 'Bank Facility',
    loanAmount: Number(d.loanAmount) || 0,
    tenureMonths: Number(d.tenureMonths) || 12,
    monthlyEmi: Number(d.monthlyEmi) || 0,
    totalProfit: Number(d.totalProfit) || 0,
    processingFee: Number(d.processingFee) || 0,
    isFiler: d.isFiler !== false,
    taxRate: Number(d.taxRate) || 0,
    taxDeducted: Number(d.taxDeducted) || 0,
    netProfit: Number(d.netProfit) || 0,
    status: d.status || 'New',
    notes: cleanRawActionPayload(d.notes || ''),
    followUpDate: d.followUpDate || '',
    officerName: d.officerName || '',
    officerId: d.officerId || '',
    branchName: d.branchName || '',
    userId: d.userId || '',
    latitude: d.latitude ?? null,
    longitude: d.longitude ?? null,
    locationAddress: d.locationAddress || '',
    locationCapturedAt: Number(d.locationCapturedAt) || 0,
    createdAt: Number(d.createdAt) || Date.now(),
    updatedAtMs: remoteUpdatedMs || Date.now(),
    syncStatus: 'synced',
    sheetsSynced: d.sheetsSynced === true || (existing ? Boolean(existing.sheetsSynced) : false),
    approachCount: Number(d.approachCount) || (Array.isArray(d.activities) ? d.activities.length : 1),
    notesUpdateCount: Number(d.notesUpdateCount) || (Array.isArray(d.activities) ? d.activities.filter(a => a.actionType === 'note_update').length : 0),
    activities: Array.isArray(d.activities) ? d.activities : (existing ? existing.activities : [])
  };
}

async function pruneOutOfScopeCachedLeads() {
  if (!currentUser || isUserAllBranchesAdmin()) return;
  const localLeads = await LocalDB.getAllLeads();
  let removed = 0;
  for (const local of localLeads) {
    if (local.syncStatus === 'synced' && !isLeadInAuthorizedScope(local)) {
      await LocalDB.deleteLead(local.id);
      removed++;
    }
  }
  if (removed > 0) {
    await renderLeads();
    await updateSettingsStats();
  }
}

function buildScopedFirestorePlans() {
  if (!currentUser) return [];
  if (isUserAllBranchesAdmin() || !fb || typeof fb.onQuerySnapshot !== 'function') {
    return [{ id: 'all', constraints: [] }];
  }
  const plans = [
    { id: 'own_uid', constraints: [{ field: 'userId', op: '==', value: currentUser.uid }] }
  ];
  const staff = (officerProfile.staffId || '').trim();
  if (staff) {
    plans.push({ id: 'own_staff', constraints: [{ field: 'officerId', op: '==', value: staff }] });
  }
  const canFilter = typeof canCurrentUserUseSupervisorFilter === 'function' ? canCurrentUserUseSupervisorFilter() : true;
  if (canFilter) {
    const effAllowed = typeof getEffectiveAllowedBranch === 'function' ? getEffectiveAllowedBranch() : '';
    const variations = buildBranchQueryVariations(effAllowed);
    for (let i = 0; i < variations.length; i += 10) {
      const chunk = variations.slice(i, i + 10);
      if (chunk.length > 0) {
        plans.push({
          id: `branch_${Math.floor(i / 10)}`,
          constraints: [{ field: 'branchName', op: 'in', value: chunk }]
        });
      }
    }
  }
  return plans;
}

async function flushScopedBucketsToLocalDB() {
  try {
    const localLeads = await LocalDB.getAllLeads();
    const localMap = new Map(localLeads.map(l => [l.id, l]));
    const mergedMap = new Map();

    for (const bucketMap of scopedLeadBuckets.values()) {
      for (const [leadId, docSnap] of bucketMap.entries()) {
        const prev = mergedMap.get(leadId);
        const existing = localMap.get(leadId);
        const candidate = mapRemoteDocToLead(docSnap, existing);
        if (currentUser && candidate.userId === currentUser.uid && String(candidate.id || '').startsWith('sheet-row-') && typeof isLeadOwnedByCurrentOfficer === 'function' && !isLeadOwnedByCurrentOfficer(candidate)) {
          candidate.userId = '';
        }
        if (!isLeadInAuthorizedScope(candidate)) continue;
        if (!prev || candidate.updatedAtMs >= prev.updatedAtMs) {
          mergedMap.set(leadId, candidate);
        }
      }
    }

    for (const [leadId, remoteLead] of mergedMap.entries()) {
      const existing = localMap.get(leadId);
      const localUpdatedMs = existing ? (Number(existing.updatedAtMs) || Number(existing.createdAt) || 0) : 0;
      if (existing && existing.syncStatus === 'pending_sync' && localUpdatedMs > remoteLead.updatedAtMs) {
        continue;
      }
      await LocalDB.saveLead(remoteLead);
      localMap.set(leadId, remoteLead);
    }

    const allBucketsReady = expectedScopedBuckets.size > 0 && expectedScopedBuckets.size === scopedBucketReady.size;
    if (allBucketsReady) {
      for (const local of localLeads) {
        if (local.syncStatus === 'synced' && (!mergedMap.has(local.id) || !isLeadInAuthorizedScope(local))) {
          await LocalDB.deleteLead(local.id);
        }
      }
    }

    lastCloudSyncTime = Date.now();
    await renderLeads();
    await updateSettingsStats();
    if (adminUnlocked && typeof renderAdminLeadsDbList === 'function') {
      await renderAdminLeadsDbList();
    }
  } catch (e) {
    console.warn('Scoped leads flush error:', e);
  }
}

function startFirestoreLeadsListener() {
  if (!fb || !currentUser) return;
  if (unsubscribeLeads) {
    try { unsubscribeLeads(); } catch (e) {}
    unsubscribeLeads = null;
  }

  currentLeadsScopeKey = getCurrentLeadsScopeKey();
  const plans = buildScopedFirestorePlans();
  scopedLeadBuckets = new Map();
  scopedBucketReady = new Set();
  expectedScopedBuckets = new Set(plans.map(p => p.id));

  const unsubs = plans.map(plan => {
    const onNext = async (snapshot) => {
      const bucket = new Map();
      snapshot.docs.forEach(docSnap => {
        const d = docSnap.data();
        bucket.set(d.id || docSnap.id, docSnap);
      });
      scopedLeadBuckets.set(plan.id, bucket);
      scopedBucketReady.add(plan.id);
      await flushScopedBucketsToLocalDB();
    };
    const onErr = (err) => {
      console.warn(`Scoped leads stream notice (${plan.id}):`, err);
      scopedBucketReady.add(plan.id);
    };

    if (plan.constraints.length === 0 || typeof fb.onQuerySnapshot !== 'function') {
      return fb.onCollectionSnapshot('leads', onNext, onErr);
    }
    return fb.onQuerySnapshot('leads', plan.constraints, onNext, onErr);
  });

  unsubscribeLeads = () => {
    unsubs.forEach(fn => {
      try { if (typeof fn === 'function') fn(); } catch (e) {}
    });
  };
}

// 9. ONLINE / OFFLINE & NAVIGATION
function updateOnlineStatus() {
  const dot = document.getElementById('statusDot');
  const text = document.getElementById('statusText');
  if (!dot || !text) return;
  if (navigator.onLine) {
    dot.classList.remove('offline');
    text.textContent = 'Online';
  } else {
    dot.classList.add('offline');
    text.textContent = 'Offline';
  }
}

function switchTab(tabId, navElement) {
  document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
  const profileBtn = document.getElementById('headerProfileBtn');
  if (profileBtn) profileBtn.classList.remove('active');

  const target = document.getElementById(tabId);
  if (target) target.classList.add('active');

  if (tabId === 'settingsTab') {
    if (profileBtn) profileBtn.classList.add('active');
    updateSettingsStats();
  } else {
    if (navElement) navElement.classList.add('active');
    else {
      const fallbackNav = document.querySelector(`.nav-item[data-tab="${tabId}"]`);
      if (fallbackNav) fallbackNav.classList.add('active');
    }
  }

  if (tabId === 'leadsTab') {
    renderLeads();
  } else if (tabId === 'adminTab' && adminUnlocked) {
    populateAdminControls();
  }
}

// 10. GEOLOCATION (2-STAGE GPS + OSM NOMINATIM REVERSE GEOCODING) & PROPOSAL DISPATCH
async function reverseGeocodeAddress(lat, lng, timeoutMs = 3500) {
  const coordPrefix = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const resp = await fetch(
      `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lng)}&zoom=18&addressdetails=1`,
      { signal: controller.signal, headers: { 'Accept-Language': 'en' } }
    );
    clearTimeout(timer);
    if (resp.ok) {
      const data = await resp.json();
      let area = '';
      if (data && data.address) {
        const a = data.address;
        const parts = [
          a.road || a.pedestrian || a.neighbourhood || a.suburb,
          a.city_district || a.town || a.village || a.city || a.county,
          a.state
        ].filter(Boolean);
        if (parts.length > 0) area = parts.join(', ');
      }
      if (!area && data && data.display_name) {
        area = String(data.display_name).split(',').slice(0, 3).map(s => s.trim()).join(', ');
      }
      if (area) return `${coordPrefix} | ${area}`;
    }
  } catch (e) {}
  return coordPrefix;
}

function prewarmGeoLocation() {
  if (!navigator.geolocation) return;
  try {
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        const lat = Number(pos.coords.latitude.toFixed(6));
        const lng = Number(pos.coords.longitude.toFixed(6));
        const addr = await reverseGeocodeAddress(lat, lng, 3000);
        lastKnownGeoLocation = {
          latitude: lat,
          longitude: lng,
          locationAddress: addr,
          locationCapturedAt: Date.now()
        };
      },
      () => {},
      { enableHighAccuracy: false, timeout: 6000, maximumAge: 300000 }
    );
  } catch (e) {}
}

function getPositionPromise(options) {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve(pos),
      () => resolve(null),
      options
    );
  });
}

async function captureBrowserLocation(timeoutMs = 5000) {
  if (!navigator.geolocation) return lastKnownGeoLocation;

  let pos = await getPositionPromise({ enableHighAccuracy: true, timeout: timeoutMs, maximumAge: 60000 });
  if (!pos) {
    pos = await getPositionPromise({ enableHighAccuracy: false, timeout: 4500, maximumAge: 300000 });
  }
  if (!pos) return lastKnownGeoLocation;

  const lat = Number(pos.coords.latitude.toFixed(6));
  const lng = Number(pos.coords.longitude.toFixed(6));
  const addr = await reverseGeocodeAddress(lat, lng, 3500);
  const resolved = {
    latitude: lat,
    longitude: lng,
    locationAddress: addr,
    locationCapturedAt: Date.now()
  };
  lastKnownGeoLocation = resolved;
  return resolved;
}

async function refreshLeadGps(leadId) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  await withLoading('Capturing GPS & Street Address...', async () => {
    const loc = await captureBrowserLocation(7000);
    if (!loc) {
      showToast('⚠️ GPS unavailable. Please allow Location permission in browser settings.');
      return;
    }
    lead.latitude = loc.latitude;
    lead.longitude = loc.longitude;
    lead.locationAddress = loc.locationAddress;
    lead.locationCapturedAt = loc.locationCapturedAt;
    lead.syncStatus = 'pending_sync';
    await LocalDB.saveLead(lead);

    if (fb) {
      try {
        await fb.setDoc('leads', lead.id, {
          latitude: loc.latitude,
          longitude: loc.longitude,
          locationAddress: loc.locationAddress,
          locationCapturedAt: loc.locationCapturedAt,
          syncStatus: 'synced',
          updatedAtMs: Date.now(),
          updatedAt: fb.serverTimestamp()
        }, true);
        lead.syncStatus = 'synced';
        await LocalDB.saveLead(lead);
      } catch (e) {
        console.warn('GPS Firestore sync warning:', e);
      }
    }

    await renderLeads();
    showToast(`📍 GPS updated: ${loc.locationAddress}`);
    if (navigator.onLine && (officerProfile.webhookUrl || globalConfig.googleSheetsWebhookUrl)) syncLeadToSheet(lead);
  });
}

function formatUrduTenureLabel(months) {
  const t = Number(months) || 0;
  if (t <= 0) return '0 ماہ';
  if (t === 1) return '1 ماہ';
  if (t >= 12 && t % 12 === 0) {
    const yrs = t / 12;
    return `${t} ماہ (${yrs} سال)`;
  }
  if (t > 12) {
    const yrs = (t / 12).toFixed(1).replace(/\.0$/, '');
    return `${t} ماہ (${yrs} سال)`;
  }
  return `${t} ماہ`;
}

function formatCompactAllPlansSms(plans, cust, isLiab, offName, offBranch) {
  let list = plans;
  if (plans.length > 20) {
    const picked = [];
    const seen = new Set();
    const addPlan = (p) => {
      if (p && !seen.has(p.tenure)) {
        seen.add(p.tenure);
        picked.push(p);
      }
    };
    addPlan(plans[0]);
    plans.forEach(p => { if (p.tenure % 12 === 0) addPlan(p); });
    addPlan(plans[plans.length - 1]);
    list = picked.sort((a, b) => a.tenure - b.tenure);
  }
  const lines = [
    `Dear ${cust}, KBL ${currentProduct.name} Eligible Plans:`,
    ``
  ];
  list.forEach(p => {
    if (isLiab) {
      lines.push(`${p.tenure}M (${p.rate}%): Dep PKR ${formatPKR(p.amount)}, Net Mo PKR ${formatPKR(p.netMonthlyProfit)}, Payout PKR ${formatPKR(p.netMaturity)}`);
    } else {
      lines.push(`${p.tenure}M: Loan PKR ${formatPKR(p.amount)} | EMI PKR ${formatPKR(p.emi)} | Fee PKR ${formatPKR(p.totalFee)}`);
    }
  });
  if (list.length < plans.length) {
    lines.push(``);
    lines.push(`(${plans.length} interval plans from ${plans[0].tenure}M to ${plans[plans.length - 1].tenure}M)`);
  }
  lines.push(``);
  lines.push(`Contact: ${offName} (${offBranch})`);
  return lines.join('\n');
}

function buildCalculatorProposalText(isSmsCompact = false) {
  if (!currentProduct || !lastCalculation) return '';
  const cust = toTitleCase(document.getElementById('customerName').value.trim() || 'Valued Customer');
  const isLiab = currentProduct.category === 'liability';
  const offName = toTitleCase(officerProfile.officerName || (currentUser && currentUser.displayName) || 'Officer');
  const offId = officerProfile.staffId || 'RO';
  const offBranch = officerProfile.branchName || (currentUserProfile && currentUserProfile.branchName) || 'KBL Branch';

  if (typeof isAllEligibleDispatchMode === 'function' && isAllEligibleDispatchMode() && typeof getEligiblePlansForDispatch === 'function') {
    const rawEligible = getEligiblePlansForDispatch();
    const plans = (typeof filterPlansBySixMonthIntervals === 'function')
      ? filterPlansBySixMonthIntervals(rawEligible)
      : rawEligible;

    if (plans && plans.length > 0) {
      if (isSmsCompact) {
        return formatCompactAllPlansSms(plans, cust, isLiab, offName, offBranch);
      }
      const header = [
        `السلام علیکم جناب *${cust}*،`,
        isLiab ? `*KBL Deposit & Investment Services*` : `*KBL Financing Services*`,
        ``,
        `📋 *${currentProduct.name}* کے تمام اہل پلانز:`
      ];
      if (!isLiab && plans[0].dbrVal !== undefined && lastCalculation.monthlyIncome > 0) {
        header.push(`(ماہانہ آمدنی: PKR ${formatPKR(lastCalculation.monthlyIncome)} • DBR حد: ${lastCalculation.allowedDbr || 50}%)`);
      }
      header.push(`────────────────────`);

      const planBlocks = plans.map(p => {
        const durLabel = formatUrduTenureLabel(p.tenure);
        if (isLiab) {
          return [
            ``,
            `📅 *${durLabel}*`,
            `💵 ڈپازٹ رقم: *PKR ${formatPKR(p.amount)}*`,
            `📈 شرح منافع: *${p.rate}%*`,
            `💳 ماہانہ خالص منافع: *PKR ${formatPKR(p.netMonthlyProfit)}*`,
            `💎 مدت پوری ہونے پر کل واپسی: *PKR ${formatPKR(p.netMaturity)}*`,
            `┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄`
          ].join('\n');
        }
        return [
          ``,
          `📅 *${durLabel}*`,
          `💰 قرض کی رقم: *PKR ${formatPKR(p.amount)}*`,
          `💳 ماہانہ قسط: *PKR ${formatPKR(p.emi)}*`,
          `🧾 پروسیسنگ فیس: *PKR ${formatPKR(p.totalFee)}*`,
          `┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄`
        ].join('\n');
      });

      return [
        ...header,
        ...planBlocks,
        ``,
        `────────────────────`,
        `مزید تفصیل یا منتخب پلان کے لیے رابطہ فرمائیں۔ شکریہ!`,
        `🏦 آفیسر: ${offName} (${offId})`,
        `📍 برانچ: ${offBranch}`
      ].join('\n');
    }
  }

  const emiOrProfit = isLiab ? (lastCalculation.netMonthlyProfit || 0) : (lastCalculation.emi || 0);
  const feeOrTax = isLiab ? (lastCalculation.totalTax || 0) : (lastCalculation.totalFee || 0);
  const totalOrMat = isLiab ? (lastCalculation.netMaturity || 0) : (lastCalculation.totalPayable || 0);

  if (isSmsCompact) {
    if (isLiab) {
      return `Dear ${cust}, KBL Quotation: ${currentProduct.name}, Deposit: PKR ${formatPKR(lastCalculation.amount)}, Tenure: ${lastCalculation.tenure}M, Monthly Net Profit: PKR ${formatPKR(emiOrProfit)}. Contact: ${offName} (${offBranch})`;
    }
    return `Dear ${cust}, KBL Quotation: ${currentProduct.name}, Loan: PKR ${formatPKR(lastCalculation.amount)}, Tenure: ${lastCalculation.tenure}M, Monthly EMI: PKR ${formatPKR(emiOrProfit)}, Fee: PKR ${formatPKR(feeOrTax)}. Contact: ${offName} (${offBranch})`;
  }

  if (isLiab) {
    return [
      `السلام علیکم جناب *${cust}*،`,
      `*KBL Deposit & Investment Services*`,
      ``,
      `آپ کی مطلوبہ ڈپازٹ کوٹیشن:`,
      `────────────────────`,
      `📋 پراڈکٹ: *${currentProduct.name}*`,
      `💵 رقم (Principal): *PKR ${formatPKR(lastCalculation.amount)}*`,
      `📅 مدت (Tenure): *${formatUrduTenureLabel(lastCalculation.tenure)}*`,
      `📈 شرح منافع: *${lastCalculation.rate}%*`,
      `💳 ماہانہ خالص منافع (Net Profit): *PKR ${formatPKR(emiOrProfit)}*`,
      `🧾 ودہولڈنگ ٹیکس (${lastCalculation.taxRate}%): *PKR ${formatPKR(feeOrTax)}*`,
      `💎 مدت پوری ہونے پر کل خالص رقم: *PKR ${formatPKR(totalOrMat)}*`,
      `────────────────────`,
      ``,
      `مزید معلومات کے لیے رابطہ فرمائیں۔ شکریہ!`,
      `🏦 آفیسر: ${offName} (${offId})`,
      `📍 برانچ: ${offBranch}`
    ].join('\n');
  }

  return [
    `السلام علیکم جناب *${cust}*،`,
    `*KBL Financing Services*`,
    ``,
    `آپ کی مطلوبہ لون کوٹیشن:`,
    `────────────────────`,
    `📋 پراڈکٹ: *${currentProduct.name}*`,
    `📅 مدت (Tenure): *${formatUrduTenureLabel(lastCalculation.tenure)}*`,
    `💰 قرض کی رقم: *PKR ${formatPKR(lastCalculation.amount)}*`,
    `💳 ماہانہ قسط (EMI): *PKR ${formatPKR(emiOrProfit)}*`,
    `🧾 پروسیسنگ فیس (مع FED): *PKR ${formatPKR(feeOrTax)}*`,
    `────────────────────`,
    ``,
    `مزید معلومات کے لیے رابطہ فرمائیں۔ شکریہ!`,
    `🏦 آفیسر: ${offName} (${offId})`,
    `📍 برانچ: ${offBranch}`
  ].join('\n');
}

function enqueueBackgroundLeadSync(leadId, opts = {}) {
  if (!leadId) return Promise.resolve(null);
  const prevChain = backgroundLeadSyncQueues.get(leadId) || Promise.resolve();
  const nextChain = prevChain.then(async () => {
    try {
      const leads = await LocalDB.getAllLeads();
      let lead = leads.find(l => l.id === leadId);
      if (!lead) return null;

      if (opts.captureGps) {
        const isStale = !lead.locationCapturedAt || (Date.now() - lead.locationCapturedAt > 300000);
        if (lead.latitude === null || lead.latitude === undefined || !lead.locationAddress || isStale) {
          const freshLoc = await captureBrowserLocation(4500);
          if (freshLoc) {
            const latestList = await LocalDB.getAllLeads();
            lead = latestList.find(l => l.id === leadId) || lead;
            lead.latitude = freshLoc.latitude;
            lead.longitude = freshLoc.longitude;
            lead.locationAddress = freshLoc.locationAddress;
            lead.locationCapturedAt = freshLoc.locationCapturedAt;
            lead.updatedAtMs = Date.now();
            await LocalDB.saveLead(lead);
          }
        }
      }

      let firestoreSaved = false;
      if (fb && currentUser && navigator.onLine) {
        try {
          const syncTs = Date.now();
          await Promise.race([
            fb.setDoc('leads', lead.id, {
              ...lead,
              syncStatus: 'synced',
              updatedAtMs: Math.max(Number(lead.updatedAtMs) || 0, syncTs),
              updatedAt: fb.serverTimestamp()
            }, true),
            new Promise((_, rej) => setTimeout(() => rej(new Error('Cloud timeout')), 8000))
          ]);
          lead.syncStatus = 'synced';
          firestoreSaved = true;
          lastCloudSyncTime = Date.now();
          await LocalDB.saveLead(lead);
        } catch (e) {
          console.warn('Background Firestore lead sync notice:', e);
        }
      }

      let sheetSaved = false;
      const webhookUrl = getEffectiveWebhookUrl();
      if (navigator.onLine && webhookUrl) {
        sheetSaved = await syncLeadToSheet(lead, true);
      }

      await renderLeads();
      await updateSettingsStats();

      if (opts.notifySuccess) {
        const label = opts.successLabel || lead.customerName || 'Lead';
        if (firestoreSaved && (!webhookUrl || sheetSaved)) {
          showToast(`✅ ${label} synced to Cloud${sheetSaved ? ' + Google Sheet' : ''}!`);
        } else if (firestoreSaved && webhookUrl && !sheetSaved) {
          showToast(`✅ Saved to Cloud (Sheet sync retrying later).`);
        }
      }
      return lead;
    } catch (err) {
      console.warn('Background lead queue error:', err);
      return null;
    }
  });

  backgroundLeadSyncQueues.set(leadId, nextChain.catch(() => null));
  return nextChain;
}

async function upsertLeadToLocalAndQueueSync(options = {}) {
  const {
    rawCustName = '',
    mobile = '',
    dob = '',
    notes = '',
    monthlyIncome = 0,
    allowedDbr = 0,
    activity = null,
    clearForm = false,
    notifySave = false,
    overrideAmount = null,
    overrideTenure = null,
    overrideEmi = null,
    overrideProfit = null,
    overrideFee = null
  } = options;

  if (!currentProduct) return null;

  const custName = toTitleCase(rawCustName || (mobile ? 'Walk-In Customer' : 'Customer'));
  const ageInfo = calculateDetailedAge(dob);
  const existingLeads = await LocalDB.getAllLeads();
  const phoneKey = cleanPhoneDigits(mobile);

  let lead = existingLeads.find(l => {
    if (phoneKey && cleanPhoneDigits(l.mobile) === phoneKey) return true;
    if (!phoneKey && rawCustName && (l.customerName || '').trim().toLowerCase() === custName.toLowerCase() && l.productName === currentProduct.name) {
      return true;
    }
    return false;
  });

  const effOffName = toTitleCase(officerProfile.officerName || (currentUser && currentUser.displayName) || 'Loan Officer');
  const effOffId = officerProfile.staffId || 'RO';
  const effBranch = officerProfile.branchName || (currentUserProfile && currentUserProfile.branchName) || '';
  const now = Date.now();
  const cleanNote = cleanRawActionPayload(notes);
  const cachedLoc = lastKnownGeoLocation;

  const isLiab = currentProduct.category === 'liability';
  const calcObj = lastCalculation || {};
  const effAmount = (overrideAmount !== null && overrideAmount > 0) ? overrideAmount : (calcObj.amount || 0);
  const effTenure = (overrideTenure !== null && overrideTenure > 0) ? overrideTenure : (calcObj.tenure || selectedMatrixTenure || 12);
  const effEmi = (overrideEmi !== null && overrideEmi > 0) ? overrideEmi : (isLiab ? (calcObj.netMonthlyProfit || 0) : (calcObj.emi || 0));
  const effProfit = (overrideProfit !== null && overrideProfit > 0) ? overrideProfit : (isLiab ? (calcObj.netTotalProfit || 0) : (calcObj.totalProfit || 0));
  const effFee = (overrideFee !== null && overrideFee >= 0) ? overrideFee : (isLiab ? (calcObj.totalTax || 0) : (calcObj.totalFee || 0));

  if (effAmount <= 0) return null;

  const extraNoteText = (cleanNote && (!lead || cleanRawActionPayload(lead.notes || '') !== cleanNote));

  if (lead) {
    if (rawCustName) lead.customerName = custName;
    if (mobile) lead.mobile = mobile;
    lead.dob = dob || lead.dob || '';
    lead.age = ageInfo.years || lead.age || 0;
    lead.monthlyIncome = monthlyIncome || lead.monthlyIncome || 0;
    lead.dbrAllowed = allowedDbr || lead.dbrAllowed || 50;
    lead.category = currentProduct.category;
    lead.productId = currentProduct.id;
    lead.productName = currentProduct.name;
    lead.loanAmount = effAmount;
    lead.tenureMonths = effTenure;
    lead.monthlyEmi = effEmi;
    lead.totalProfit = effProfit;
    lead.processingFee = effFee;
    lead.isFiler = isLiab ? isFilerCustomer : true;
    lead.taxRate = isLiab ? (calcObj.taxRate || 0) : 0;
    lead.taxDeducted = isLiab ? (calcObj.totalTax || 0) : 0;
    lead.netProfit = isLiab ? (calcObj.netTotalProfit || 0) : 0;
    if (cachedLoc && (lead.latitude === null || lead.latitude === undefined)) {
      lead.latitude = cachedLoc.latitude;
      lead.longitude = cachedLoc.longitude;
      lead.locationAddress = cachedLoc.locationAddress;
      lead.locationCapturedAt = cachedLoc.locationCapturedAt;
    }
    lead.syncStatus = 'pending_sync';
    lead.sheetsSynced = false;
    lead.approachCount = (Number(lead.approachCount) || 1) + 1;
    if (!lead.activities) lead.activities = [];

    if (extraNoteText) {
      lead.notes = cleanNote;
      lead.notesUpdateCount = (lead.notesUpdateCount || 0) + 1;
      lead.activities.push({
        id: 'act-' + now,
        timestamp: now,
        actionType: 'note_update',
        title: '📝 Note Added',
        details: cleanNote,
        officerName: effOffName,
        officerId: effOffId
      });
    }

    if (activity) {
      lead.activities.push({
        id: 'act-' + now + '-' + Math.random().toString(36).substring(2, 5),
        timestamp: now + 1,
        actionType: activity.actionType || 'proposal_update',
        title: activity.title || '📋 Proposal Saved',
        details: activity.details || `${currentProduct.name} (PKR ${formatPKR(effAmount)}, ${effTenure}M)`,
        officerName: effOffName,
        officerId: effOffId
      });
    }
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
        officerName: effOffName,
        officerId: effOffId
      });
    }
    initialActivities.push({
      id: 'create-' + now,
      timestamp: now,
      actionType: 'created',
      title: isLiab ? '🎯 Deposit Lead Created' : '🎯 Loan Lead Created',
      details: `${currentProduct.name} (PKR ${formatPKR(effAmount)}, ${effTenure}M)`,
      officerName: effOffName,
      officerId: effOffId
    });
    if (activity && activity.actionType !== 'proposal_update') {
      initialActivities.push({
        id: 'act-' + (now + 1) + '-' + Math.random().toString(36).substring(2, 5),
        timestamp: now + 1,
        actionType: activity.actionType,
        title: activity.title,
        details: activity.details,
        officerName: effOffName,
        officerId: effOffId
      });
    }

    lead = {
      id,
      customerName: custName,
      mobile,
      dob: dob || '',
      age: ageInfo.years || 0,
      monthlyIncome,
      dbrAllowed: allowedDbr || 50,
      category: currentProduct.category,
      productId: currentProduct.id,
      productName: currentProduct.name,
      loanAmount: effAmount,
      tenureMonths: effTenure,
      monthlyEmi: effEmi,
      totalProfit: effProfit,
      processingFee: effFee,
      isFiler: isLiab ? isFilerCustomer : true,
      taxRate: isLiab ? (calcObj.taxRate || 0) : 0,
      taxDeducted: isLiab ? (calcObj.totalTax || 0) : 0,
      netProfit: isLiab ? (calcObj.netTotalProfit || 0) : 0,
      status: 'New',
      followUpDate: '',
      notes: cleanNote,
      officerName: effOffName,
      officerId: effOffId,
      branchName: effBranch,
      userId: currentUser ? currentUser.uid : 'web-officer',
      latitude: cachedLoc ? cachedLoc.latitude : null,
      longitude: cachedLoc ? cachedLoc.longitude : null,
      locationAddress: cachedLoc ? cachedLoc.locationAddress : '',
      locationCapturedAt: cachedLoc ? cachedLoc.locationCapturedAt : 0,
      createdAt: now,
      updatedAtMs: now,
      syncStatus: 'pending_sync',
      sheetsSynced: false,
      approachCount: 1,
      notesUpdateCount: cleanNote ? 1 : 0,
      activities: initialActivities
    };
  }

  lead.updatedAtMs = now;
  lead.sheetsSynced = false;
  await LocalDB.saveLead(lead);

  if (clearForm) {
    document.getElementById('customerName').value = '';
    document.getElementById('customerMobile').value = '';
    document.getElementById('customerDob').value = '';
    document.getElementById('leadNotes').value = '';
    updateAgeEligibilityUI();
  }

  await renderLeads();
  await updateSettingsStats();

  enqueueBackgroundLeadSync(lead.id, {
    captureGps: true,
    notifySuccess: notifySave,
    successLabel: custName
  });

  return lead;
}

function autoSaveLeadFromAction(actionType, actionTitle, actionDetail, opts = {}) {
  const rawCustName = (document.getElementById('customerName')?.value || '').trim();
  const mobile = (document.getElementById('customerMobile')?.value || '').trim();
  if (!rawCustName && !mobile) {
    showToast('ℹ️ Shared! Enter Customer Name or Mobile to auto-save as Lead.');
    return Promise.resolve(null);
  }
  const dob = document.getElementById('customerDob')?.value || '';
  const notes = (document.getElementById('leadNotes')?.value || '').trim();
  const monthlyIncome = parseFloat(document.getElementById('monthlyIncome')?.value) || 0;
  const allowedDbr = parseFloat(document.getElementById('allowedDbr')?.value) || 0;

  return upsertLeadToLocalAndQueueSync({
    rawCustName,
    mobile,
    dob,
    notes,
    monthlyIncome,
    allowedDbr,
    activity: {
      actionType,
      title: actionTitle,
      details: actionDetail
    },
    clearForm: false,
    notifySave: false,
    ...opts
  }).then(saved => {
    if (saved) {
      showToast(`💾 Lead auto-saved & syncing (${saved.customerName})...`);
    }
    return saved;
  }).catch(e => {
    console.warn('Background auto-save warning:', e);
    return null;
  });
}

function copyProposalText() {
  const txt = buildCalculatorProposalText(false);
  if (!txt) {
    showToast('Calculate a plan first.');
    return;
  }
  copyToClipboard(txt, '📋 Proposal quotation copied to clipboard!');
  const modeLabel = (typeof isAllEligibleDispatchMode === 'function' && isAllEligibleDispatchMode())
    ? 'All Eligible Plans'
    : `${lastCalculation.tenure}M`;
  autoSaveLeadFromAction('copy', '📋 Proposal Copied', `Copied ${currentProduct.name} (${modeLabel}, PKR ${formatPKR(lastCalculation.amount)})`);
}

function saveLeadFromCalculator(silent = false) {
  const rawCustName = document.getElementById('customerName').value.trim();
  const mobile = document.getElementById('customerMobile').value.trim();
  const dob = document.getElementById('customerDob').value;
  const notes = document.getElementById('leadNotes').value.trim();
  const monthlyIncome = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const allowedDbr = parseFloat(document.getElementById('allowedDbr').value) || 0;

  if (!rawCustName) {
    if (!silent) showToast('⚠️ Please enter customer name (گاہک کا نام درج کریں).');
    return Promise.resolve(null);
  }
  if (!mobile) {
    if (!silent) showToast('⚠️ Please enter mobile number (موبائل نمبر درج کریں).');
    return Promise.resolve(null);
  }
  if (!lastCalculation || lastCalculation.amount <= 0) {
    if (!silent) showToast('⚠️ Enter Income & DBR or Desired Amount first.');
    return Promise.resolve(null);
  }

  const custTitle = toTitleCase(rawCustName);
  if (!silent) {
    showToast(`💾 Saving ${custTitle}... Cloud & GPS syncing in background`);
  }

  return upsertLeadToLocalAndQueueSync({
    rawCustName,
    mobile,
    dob,
    notes,
    monthlyIncome,
    allowedDbr,
    activity: {
      actionType: 'proposal_update',
      title: '📋 Proposal Saved',
      details: `${currentProduct.name} (PKR ${formatPKR(lastCalculation.amount)}, ${lastCalculation.tenure}M)`
    },
    clearForm: !silent,
    notifySave: !silent
  });
}

// 11. POPUP-FREE WHATSAPP, SMS & CALL WITH INSTANT EXECUTION & BACKGROUND SYNC
function openExternalWhatsAppUrl(url) {
  let opened = null;
  try {
    opened = window.open(url, '_blank');
    if (opened) {
      try { opened.opener = null; } catch (err) {}
    }
  } catch (e) {
    opened = null;
  }
  if (!opened || opened.closed || typeof opened.closed === 'undefined') {
    window.location.href = url;
  }
}

function sendQuotationWhatsApp(leadId = null) {
  let mobile = '';
  let msgText = '';
  const offName = officerProfile.officerName || (currentUser && currentUser.displayName) || 'Officer';
  const offBranch = officerProfile.branchName || (currentUserProfile && currentUserProfile.branchName) || 'KBL Branch';

  if (leadId) {
    const lead = cachedLeadsList.find(l => l.id === leadId);
    if (!lead) {
      showToast('Lead record loading, please try again.');
      return;
    }
    mobile = lead.mobile || '';
    const matchedProd = BANK_PRODUCTS.find(p => p.id === lead.productId || p.name === lead.productName) || currentProduct;
    const rate = getApplicableRate(matchedProd, lead.loanAmount, lead.tenureMonths);
    const totalPayable = (lead.loanAmount || 0) + (lead.totalProfit || 0);
    msgText = [
      `السلام علیکم جناب *${lead.customerName}*،`,
      `*KBL Financing & Deposit Services*`,
      ``,
      `آپ کی مطلوبہ کوٹیشن:`,
      `• پراڈکٹ: *${lead.productName}*`,
      `• رقم: *PKR ${formatPKR(lead.loanAmount)}*`,
      `• مدت: *${lead.tenureMonths} ماہ*`,
      `• ریٹ: *${rate}%*`,
      `• ماہانہ قسط / منافع: *PKR ${formatPKR(lead.monthlyEmi)}*`,
      `• فیس / ٹیکس: *PKR ${formatPKR(lead.processingFee)}*`,
      `• کل رقم: *PKR ${formatPKR(totalPayable)}*`,
      ``,
      `آفیسر: ${offName} (${offBranch})`
    ].join('\n');

    const cleanPhone = formatForWhatsApp(mobile);
    const url = cleanPhone
      ? `https://wa.me/${cleanPhone}?text=${encodeURIComponent(msgText)}`
      : `https://wa.me/?text=${encodeURIComponent(msgText)}`;

    openExternalWhatsAppUrl(url);
    logApproach(leadId, 'whatsapp', '💬 WhatsApp Quotation', `Quotation sent via WhatsApp to ${mobile}`).catch(e => console.warn(e));
    return;
  }

  mobile = document.getElementById('customerMobile').value.trim();
  msgText = buildCalculatorProposalText(false);
  if (!msgText) {
    showToast('Calculate a plan first.');
    return;
  }

  const cleanPhone = formatForWhatsApp(mobile);
  const url = cleanPhone
    ? `https://wa.me/${cleanPhone}?text=${encodeURIComponent(msgText)}`
    : `https://wa.me/?text=${encodeURIComponent(msgText)}`;

  openExternalWhatsAppUrl(url);

  const modeLabel = (typeof isAllEligibleDispatchMode === 'function' && isAllEligibleDispatchMode())
    ? 'All Eligible Plans'
    : `${lastCalculation.tenure}M`;
  autoSaveLeadFromAction('whatsapp', '💬 WhatsApp Quotation', `Shared ${currentProduct.name} (${modeLabel}) on WhatsApp (${mobile || 'customer'})`);
}

function sendQuotationSMS(leadId = null) {
  let mobile = '';
  let smsBody = '';
  const offName = officerProfile.officerName || (currentUser && currentUser.displayName) || 'Officer';
  const offBranch = officerProfile.branchName || (currentUserProfile && currentUserProfile.branchName) || 'KBL Branch';

  if (leadId) {
    const lead = cachedLeadsList.find(l => l.id === leadId);
    if (!lead) return;
    mobile = lead.mobile || '';
    smsBody = `Dear ${lead.customerName}, KBL Quotation: ${lead.productName}, Amount: PKR ${formatPKR(lead.loanAmount)}, Tenure: ${lead.tenureMonths}M, Monthly: PKR ${formatPKR(lead.monthlyEmi)}. Contact: ${offName} (${offBranch})`;

    const cleanPhone = mobile.replace(/[^\d+]/g, '');
    window.location.href = `sms:${cleanPhone}?body=${encodeURIComponent(smsBody)}`;
    logApproach(leadId, 'sms', '✉️ SMS Quotation', `SMS quotation sent to ${mobile}`).catch(e => console.warn(e));
    return;
  }

  mobile = document.getElementById('customerMobile').value.trim();
  smsBody = buildCalculatorProposalText(true).replace(/\*/g, '');
  if (!smsBody) {
    showToast('Calculate a plan first.');
    return;
  }

  const cleanPhone = mobile.replace(/[^\d+]/g, '');
  window.location.href = `sms:${cleanPhone}?body=${encodeURIComponent(smsBody)}`;

  const modeLabel = (typeof isAllEligibleDispatchMode === 'function' && isAllEligibleDispatchMode())
    ? 'All Eligible Plans'
    : `${lastCalculation.tenure}M`;
  autoSaveLeadFromAction('sms', '✉️ SMS Quotation', `Sent ${currentProduct.name} (${modeLabel}) via SMS (${mobile || 'customer'})`);
}

function initiateCall(leadId, mobile) {
  const cleanPhone = String(mobile || '').trim().replace(/[^\d+]/g, '');
  if (!cleanPhone) {
    showToast('⚠️ Valid mobile number not found for this lead.');
    return;
  }
  // Launch native phone dialer synchronously before any DB or network work
  window.location.href = `tel:${cleanPhone}`;
  logApproach(leadId, 'call', '📞 Phone Call', `Phone call initiated to ${mobile}`).catch(e => console.warn(e));
}

async function logApproach(leadId, type, title, detail) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  const now = Date.now();
  lead.approachCount = (lead.approachCount || 1) + 1;
  lead.updatedAtMs = now;
  lead.syncStatus = 'pending_sync';
  lead.sheetsSynced = false;
  if (!lead.activities) lead.activities = [];

  lead.activities.push({
    id: 'act-' + now + '-' + Math.random().toString(36).substring(2, 5),
    timestamp: now,
    actionType: type,
    title,
    details: detail,
    officerName: officerProfile.officerName,
    officerId: officerProfile.staffId
  });

  await LocalDB.saveLead(lead);
  await renderLeads();
  await updateSettingsStats();
  enqueueBackgroundLeadSync(lead.id, { captureGps: false });
}

// 12. LEAD STATUS, NOTE & FOLLOW-UP ACTIONS
async function updateLeadStatus(leadId, newStatus) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  const oldStatus = lead.status || 'New';
  if (oldStatus === newStatus) return;

  await withLoading(`Updating status to ${newStatus}...`, async () => {
    const now = Date.now();
    lead.status = newStatus;
    lead.updatedAtMs = now;
    lead.syncStatus = 'pending_sync';
    lead.sheetsSynced = false;
    if (!lead.activities) lead.activities = [];

    lead.activities.push({
      id: 'stat-' + now,
      timestamp: now,
      actionType: 'status_change',
      title: '🔄 Status Changed',
      details: `${oldStatus} ➔ ${newStatus}`,
      officerName: officerProfile.officerName || 'Officer',
      officerId: officerProfile.staffId || 'RO'
    });

    await LocalDB.saveLead(lead);

    let cloudOk = false;
    if (fb && currentUser && navigator.onLine) {
      try {
        await fb.setDoc('leads', lead.id, {
          status: newStatus,
          activities: lead.activities,
          syncStatus: 'synced',
          updatedAtMs: now,
          updatedAt: fb.serverTimestamp()
        }, true);
        lead.syncStatus = 'synced';
        cloudOk = true;
        await LocalDB.saveLead(lead);
      } catch (e) {
        console.warn('Firestore status sync notice:', e);
      }
    }

    await renderLeads();
    await updateSettingsStats();
    showToast(cloudOk ? `✅ Status updated to ${newStatus}` : `💾 Status updated locally (Pending Cloud Sync)`);
    if (navigator.onLine && getEffectiveWebhookUrl()) syncLeadToSheet(lead, true);
  });
}

async function openTrackRecord(leadId) {
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === leadId);
  if (!lead) return;

  const dispCust = toTitleCase(lead.customerName || 'Customer');
  const activities = (lead.activities || []).slice().sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  const approachesCount = Number(lead.approachCount) || Math.max(1, activities.length);
  const notesCount = Number(lead.notesUpdateCount) || activities.filter(a => a.actionType === 'note_update').length;

  document.getElementById('trackRecordCustomerHeader').innerHTML = `
    <div><strong>${escapeHtml(dispCust)}</strong> (${escapeHtml(lead.mobile)})</div>
    <div style="margin-bottom: 8px;">Facility: ${escapeHtml(lead.productName)} • Status: <strong>${escapeHtml(lead.status)}</strong></div>
    <div class="audit-stats-grid">
      <div class="audit-stat-pill">
        <span class="audit-stat-label">Approaches</span>
        <span class="audit-stat-val tabular-nums">${approachesCount} Time${approachesCount === 1 ? '' : 's'}</span>
      </div>
      <div class="audit-stat-pill">
        <span class="audit-stat-label">Note Updates</span>
        <span class="audit-stat-val tabular-nums">${notesCount} Time${notesCount === 1 ? '' : 's'}</span>
      </div>
    </div>
  `;

  const list = document.getElementById('trackRecordList');
  if (activities.length === 0) {
    list.innerHTML = '<div style="color: var(--text-muted); padding: 16px; text-align: center;">No activity recorded yet.</div>';
  } else {
    list.innerHTML = `<div class="timeline-list">${activities.map(renderActivityTimelineCardHtml).join('')}</div>`;
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

  await withLoading('Saving Lead Note...', async () => {
    const now = Date.now();
    lead.notes = cleanNote;
    lead.notesUpdateCount = (lead.notesUpdateCount || 0) + 1;
    lead.updatedAtMs = now;
    lead.syncStatus = 'pending_sync';
    lead.sheetsSynced = false;
    if (!lead.activities) lead.activities = [];

    lead.activities.push({
      id: 'act-' + now,
      timestamp: now,
      actionType: 'note_update',
      title: '📝 Note / Comment Updated',
      details: cleanNote || 'Cleared note',
      officerName: officerProfile.officerName || 'Officer',
      officerId: officerProfile.staffId || 'RO'
    });

    await LocalDB.saveLead(lead);

    let cloudOk = false;
    if (fb && currentUser && navigator.onLine) {
      try {
        await fb.setDoc('leads', lead.id, {
          ...lead,
          syncStatus: 'synced',
          updatedAtMs: now,
          updatedAt: fb.serverTimestamp()
        }, true);
        lead.syncStatus = 'synced';
        cloudOk = true;
        await LocalDB.saveLead(lead);
      } catch (e) {
        console.warn('Firestore note sync notice:', e);
      }
    }

    closeModal('editNoteModal');
    await renderLeads();
    await updateSettingsStats();
    showToast(cloudOk ? '✅ Note updated & synced!' : '💾 Note saved locally (Pending Cloud Sync)');
    if (navigator.onLine && getEffectiveWebhookUrl()) syncLeadToSheet(lead, true);
  });
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
    showToast('Please select date and time.');
    return;
  }
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === currentFollowUpLeadId);
  if (!lead) return;

  await withLoading('Scheduling Follow-Up...', async () => {
    const now = Date.now();
    lead.followUpDate = dt.replace('T', ' ');
    lead.status = 'Follow-Up';
    lead.updatedAtMs = now;
    lead.syncStatus = 'pending_sync';
    lead.sheetsSynced = false;
    if (!lead.activities) lead.activities = [];

    lead.activities.push({
      id: 'act-' + now,
      timestamp: now,
      actionType: 'follow_up',
      title: '📅 Follow-Up Scheduled',
      details: `Scheduled follow-up for ${lead.followUpDate}`,
      officerName: officerProfile.officerName || 'Officer',
      officerId: officerProfile.staffId || 'RO'
    });

    await LocalDB.saveLead(lead);

    let cloudOk = false;
    if (fb && currentUser && navigator.onLine) {
      try {
        await fb.setDoc('leads', lead.id, {
          ...lead,
          syncStatus: 'synced',
          updatedAtMs: now,
          updatedAt: fb.serverTimestamp()
        }, true);
        lead.syncStatus = 'synced';
        cloudOk = true;
        await LocalDB.saveLead(lead);
      } catch (e) {
        console.warn('Firestore follow-up sync notice:', e);
      }
    }

    closeModal('followUpModal');
    await renderLeads();
    await updateSettingsStats();
    showToast(cloudOk ? '📅 Follow-Up scheduled & synced!' : '📅 Scheduled locally (Pending Cloud Sync)');
    if (navigator.onLine && getEffectiveWebhookUrl()) syncLeadToSheet(lead, true);
  });
}

async function confirmClearFollowUp() {
  if (!currentFollowUpLeadId) return;
  const leads = await LocalDB.getAllLeads();
  const lead = leads.find(l => l.id === currentFollowUpLeadId);
  if (!lead) return;

  await withLoading('Clearing Follow-Up...', async () => {
    const now = Date.now();
    lead.followUpDate = '';
    lead.updatedAtMs = now;
    lead.syncStatus = 'pending_sync';
    lead.sheetsSynced = false;
    await LocalDB.saveLead(lead);

    if (fb && currentUser && navigator.onLine) {
      try {
        await fb.setDoc('leads', lead.id, {
          ...lead,
          syncStatus: 'synced',
          updatedAtMs: now,
          updatedAt: fb.serverTimestamp()
        }, true);
        lead.syncStatus = 'synced';
        await LocalDB.saveLead(lead);
      } catch (e) {}
    }

    closeModal('followUpModal');
    await renderLeads();
    await updateSettingsStats();
    showToast('Cleared follow-up date.');
    if (navigator.onLine && getEffectiveWebhookUrl()) syncLeadToSheet(lead, true);
  });
}

// 13. GOOGLE SHEETS 2-WAY SYNC (PULL doGet + DUAL-KEY UPSERT doPost)
function getEffectiveWebhookUrl() {
  return String(globalConfig.googleSheetsWebhookUrl || officerProfile.webhookUrl || '').trim();
}

function cleanPhoneDigits(val) {
  if (!val) return '';
  let s = String(val).replace(/\D/g, '');
  if (s.length > 10 && s.startsWith('92')) s = s.substring(2);
  if (s.length > 10 && s.startsWith('0')) s = s.substring(1);
  return s;
}

function parseSheetRowToLead(item, idx = 0) {
  const rawId = String(item.id || '').trim();
  const cleanPhone = cleanPhoneDigits(item.mobile || '');
  const id = rawId || ('sheet-row-' + idx + '-' + (cleanPhone || Date.now()));
  const rawNotes = String(item.notes || '').trim();
  const noteLines = rawNotes ? rawNotes.split('\n').map(s => s.trim()).filter(Boolean) : [];
  const lastNote = noteLines.length > 0 ? cleanRawActionPayload(noteLines[noteLines.length - 1]) : '';

  let parsedLat = (item.latitude !== undefined && item.latitude !== null && Number(item.latitude) !== 0) ? Number(item.latitude) : null;
  let parsedLng = (item.longitude !== undefined && item.longitude !== null && Number(item.longitude) !== 0) ? Number(item.longitude) : null;
  const rawLoc = String(item.locationAddress || '').trim();
  let cleanAddr = rawLoc;
  if (rawLoc.includes(',')) {
    const parts = rawLoc.split('|');
    const sub = parts[0].trim().split(',');
    if (sub.length === 2) {
      const latC = parseFloat(sub[0].trim());
      const lngC = parseFloat(sub[1].trim());
      if (!isNaN(latC) && !isNaN(lngC) && (latC !== 0 || lngC !== 0)) {
        if (parsedLat === null) parsedLat = latC;
        if (parsedLng === null) parsedLng = lngC;
        cleanAddr = parts.length > 1 ? parts.slice(1).join('|').trim() : '';
      }
    }
  }

  let createdMs = Number(item.createdAtMs) || Number(item.createdAt) || 0;
  if (!createdMs && item.createdAtFormatted) {
    const parsedDt = Date.parse(String(item.createdAtFormatted));
    if (!isNaN(parsedDt)) createdMs = parsedDt;
  }
  if (!createdMs) createdMs = Date.now() - (idx * 1000);

  const activities = [];
  const rawAudit = String(item.activitiesAudit || item.touchpointLog || '').trim();
  if (rawAudit) {
    const lines = rawAudit.split('\n').map(l => l.replace(/^•\s*/, '').trim()).filter(Boolean);
    lines.forEach((line, lIdx) => {
      const u = line.toUpperCase();
      let actionType = 'note_update';
      if (u.includes('NOTE_UPDATE') || u.includes('NOTE ADDED') || u.includes('📝')) actionType = 'note_update';
      else if (u.includes('FOLLOW_UP') || u.includes('FOLLOW-UP') || u.includes('📅')) actionType = 'follow_up';
      else if (u.includes('CALL') || u.includes('PHONE') || u.includes('📞')) actionType = 'call';
      else if (u.includes('WHATSAPP') || u.includes('💬')) actionType = 'whatsapp';
      else if (u.includes('SMS') || u.includes('✉️')) actionType = 'sms';
      else if (u.includes('STATUS') || u.includes('🔄')) actionType = 'status_change';
      else if (u.includes('PROPOSAL') || u.includes('📋')) actionType = 'proposal_update';
      else if (u.includes('CREATED') || u.includes('🎯')) actionType = 'created';

      const titleMap = {
        status_change: '🔄 Status Changed',
        follow_up: '📅 Follow-Up Date',
        call: '📞 Phone Call',
        whatsapp: '💬 WhatsApp',
        sms: '✉️ SMS',
        created: String(item.category || '').toLowerCase() === 'liability' ? '🎯 Deposit Lead Created' : '🎯 Loan Lead Created',
        proposal_update: '📋 Proposal Saved',
        note_update: '📝 Note Added'
      };

      const timeMatch = line.match(/\[(.*?)\]/);
      let actTs = createdMs + (lIdx * 1000);
      if (timeMatch && timeMatch[1]) {
        const dt = Date.parse(timeMatch[1].trim());
        if (!isNaN(dt)) actTs = dt;
      }

      const offMatch = line.match(/-\s*By\s*:\s*(.*?)$/i);
      let actOff = String(item.officerName || '').trim();
      let actId = String(item.officerId || '').trim();
      if (offMatch && offMatch[1]) {
        const rawOff = offMatch[1].split(/\s*-\s*By:/i)[0].trim();
        if (rawOff.includes('(') && rawOff.endsWith(')')) {
          actOff = rawOff.substring(0, rawOff.indexOf('(')).trim() || actOff;
          actId = rawOff.substring(rawOff.indexOf('(') + 1, rawOff.length - 1).trim() || actId;
        } else if (rawOff) {
          actOff = rawOff;
        }
      }

      const cleanDet = cleanRawActionPayload(line) || titleMap[actionType];
      activities.push({
        id: 'sh-act-' + actTs + '-' + lIdx,
        timestamp: actTs,
        actionType,
        title: titleMap[actionType],
        details: cleanDet,
        officerName: actOff,
        officerId: actId
      });
    });
  }

  return {
    id,
    customerName: toTitleCase(String(item.customerName || 'Customer').trim() || 'Customer'),
    mobile: String(item.mobile || '').trim(),
    dob: String(item.dob || ''),
    age: Number(item.age) || 0,
    monthlyIncome: Number(item.monthlyIncome) || 0,
    dbrAllowed: Number(item.dbrAllowed) || 50,
    category: String(item.category || 'asset').trim().toLowerCase() === 'liability' ? 'liability' : 'asset',
    productId: String(item.productId || ''),
    productName: String(item.productName || 'Bank Facility'),
    loanAmount: Number(item.loanAmount) || 0,
    tenureMonths: Number(item.tenureMonths) || 12,
    monthlyEmi: Number(item.monthlyEmi) || 0,
    totalProfit: Number(item.totalProfit) || 0,
    processingFee: Number(item.processingFee) || 0,
    isFiler: String(item.isFiler || '').toLowerCase() !== 'non-filer' && item.isFiler !== false,
    taxRate: Number(item.taxRate) || 0,
    taxDeducted: Number(item.taxDeducted) || 0,
    netProfit: Number(item.netProfit) || 0,
    status: String(item.status || 'New').trim() || 'New',
    followUpDate: String(item.followUpDate || '').trim(),
    notes: lastNote,
    officerName: toTitleCase(String(item.officerName || '').trim()),
    officerId: String(item.officerId || '').trim(),
    branchName: toTitleCase(String(item.branchName || '').trim()),
    userId: String(item.userId || '').trim(),
    latitude: parsedLat,
    longitude: parsedLng,
    locationAddress: cleanAddr,
    locationCapturedAt: Number(item.locationCapturedAt) || 0,
    createdAt: createdMs,
    updatedAtMs: createdMs,
    syncStatus: currentUser && fb ? 'synced' : 'pending_sync',
    sheetsSynced: true,
    approachCount: Number(item.approachCount) || Math.max(1, activities.length),
    notesUpdateCount: activities.filter(a => a.actionType === 'note_update').length,
    activities
  };
}

function isSheetLeadAuthorizedForCurrentUser(lead) {
  if (!currentUser && !officerProfile.staffId && !officerProfile.officerName) return true;
  if (isUserAllBranchesAdmin()) return true;
  if (typeof isLeadOwnedByCurrentOfficer === 'function' && isLeadOwnedByCurrentOfficer(lead)) return true;

  const canFilter = typeof canCurrentUserUseSupervisorFilter === 'function' ? canCurrentUserUseSupervisorFilter() : true;
  if (!canFilter) return false;

  const effAllowed = typeof getEffectiveAllowedBranch === 'function' ? getEffectiveAllowedBranch() : '';
  if (!effAllowed) return false;
  const tokens = effAllowed.split(/[,;|]/).map(s => s.trim()).filter(Boolean);
  if (tokens.some(t => t.toUpperCase() === 'ALL' || t === '*')) return true;

  const leadBranch = typeof resolveLeadEffectiveBranch === 'function' ? resolveLeadEffectiveBranch(lead) : (lead.branchName || '');
  return tokens.some(tok => typeof isBranchAuthorizedForQuery === 'function' && isBranchAuthorizedForQuery(tok, leadBranch));
}

async function sanitizeHijackedLeadOwnership() {
  if (!currentUser) return;
  try {
    const localLeads = await LocalDB.getAllLeads();
    for (const lead of localLeads) {
      if (lead.userId === currentUser.uid) {
        const isSheet = String(lead.id || '').startsWith('sheet-row-');
        const ownsStrictly = isLeadOwnedByCurrentOfficer(lead);
        if (!ownsStrictly && (isSheet || lead.officerName || lead.officerId)) {
          lead.userId = '';
          await LocalDB.saveLead(lead);
          if (isSheet && fb && navigator.onLine) {
            fb.setDoc('leads', lead.id, { userId: '' }, true).catch(() => {});
          }
        }
      }
    }
  } catch (e) {
    console.warn('Ownership sanitization notice:', e);
  }
}

async function pullLeadsFromGoogleSheet(silent = false) {
  const url = getEffectiveWebhookUrl();
  if (!url) {
    if (!silent) showToast('⚠️ Configure Google Sheets Web App URL in Admin first.');
    return { pulledCount: 0, importedCount: 0, pushedToCloudCount: 0 };
  }
  if (!navigator.onLine) {
    if (!silent) showToast('Device is offline.');
    return { pulledCount: 0, importedCount: 0, pushedToCloudCount: 0 };
  }

  const runPull = async () => {
    const controller = new AbortController();
    const tId = setTimeout(() => controller.abort(), 12000);
    let resp;
    try {
      const fetchUrl = url + (url.includes('?') ? '&' : '?') + '_ts=' + Date.now();
      resp = await fetch(fetchUrl, { method: 'GET', redirect: 'follow', signal: controller.signal });
    } finally {
      clearTimeout(tId);
    }

    const rawText = await resp.text();
    const trimmed = rawText.trim();
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status} from Apps Script`);
    }
    if (trimmed.startsWith('<!DOCTYPE') || trimmed.startsWith('<html')) {
      throw new Error('Apps Script returned HTML login page. Ensure deployment Access = "Anyone" and v2.3 doGet() is deployed.');
    }

    let parsed;
    try {
      parsed = JSON.parse(trimmed);
    } catch (e) {
      throw new Error('Apps Script did not return valid JSON. Redeploy v2.3 with doGet().');
    }

    const rawRows = Array.isArray(parsed) ? parsed : (Array.isArray(parsed.leads) ? parsed.leads : []);
    const existingLeads = await LocalDB.getAllLeads();
    const byId = new Map(existingLeads.map(l => [l.id, l]));
    const byPhone = new Map();
    existingLeads.forEach(l => {
      const p = cleanPhoneDigits(l.mobile);
      if (p) byPhone.set(p, l);
    });

    let importedCount = 0;
    let pushedToCloudCount = 0;

    for (let i = 0; i < rawRows.length; i++) {
      const row = rawRows[i];
      if (!row || (!row.customerName && !row.mobile && !row.id)) continue;
      const candidate = parseSheetRowToLead(row, i);
      if (!isSheetLeadAuthorizedForCurrentUser(candidate)) continue;

      const phoneKey = cleanPhoneDigits(candidate.mobile);
      const isCandidateSheetRow = String(candidate.id || '').startsWith('sheet-row-');
      const phoneCandidate = phoneKey ? byPhone.get(phoneKey) : null;
      const match = byId.get(candidate.id) || (
        phoneCandidate && (isCandidateSheetRow || String(phoneCandidate.id || '').startsWith('sheet-row-'))
          ? phoneCandidate
          : null
      );

      if (!match) {
        // Only attach currentUser.uid when the Sheet row genuinely belongs to the current officer
        if (!candidate.userId && currentUser && typeof isLeadOwnedByCurrentOfficer === 'function' && isLeadOwnedByCurrentOfficer(candidate)) {
          candidate.userId = currentUser.uid;
        }
        await LocalDB.saveLead(candidate);
        byId.set(candidate.id, candidate);
        if (phoneKey) byPhone.set(phoneKey, candidate);
        importedCount++;

        if (fb && currentUser) {
          try {
            await fb.setDoc('leads', candidate.id, {
              ...candidate,
              syncStatus: 'synced',
              updatedAtMs: Date.now(),
              updatedAt: fb.serverTimestamp()
            }, true);
            pushedToCloudCount++;
          } catch (e) {
            console.warn('Could not mirror Sheet lead to Firestore:', e);
          }
        }
      } else {
        // Sync Google Sheet Column P branchName & missing metadata without clobbering newer Firestore/local state
        let changed = false;
        if (currentUser && match.userId === currentUser.uid && String(match.id || '').startsWith('sheet-row-') && !isLeadOwnedByCurrentOfficer(match)) {
          match.userId = '';
          changed = true;
        }
        const candBranch = String(candidate.branchName || '').trim();
        const isValidCandBranch = Boolean(candBranch) && !['unassigned', 'n/a', 'na', 'unknown'].includes(candBranch.toLowerCase());
        const normBranch = (b) => (typeof normalizeBranchText === 'function' ? normalizeBranchText(b) : String(b || '').trim().toLowerCase());
        if (isValidCandBranch && normBranch(match.branchName) !== normBranch(candBranch)) {
          match.branchName = candBranch;
          changed = true;
        }
        if (!match.notes && candidate.notes) { match.notes = candidate.notes; changed = true; }
        if (!match.locationAddress && candidate.locationAddress) { match.locationAddress = candidate.locationAddress; changed = true; }
        if ((match.latitude === null || match.latitude === undefined) && candidate.latitude !== null) {
          match.latitude = candidate.latitude;
          match.longitude = candidate.longitude;
          changed = true;
        }
        if ((!match.activities || match.activities.length === 0) && candidate.activities.length > 0) {
          match.activities = candidate.activities;
          changed = true;
        }
        if (match.sheetsSynced !== true) {
          match.sheetsSynced = true;
          changed = true;
        }
        if (changed) {
          await LocalDB.saveLead(match);
        }
      }
    }

    await pruneOutOfScopeCachedLeads();
    lastCloudSyncTime = Date.now();
    await renderLeads();
    await updateSettingsStats();
    if (adminUnlocked && typeof renderAdminLeadsDbList === 'function') {
      await renderAdminLeadsDbList();
    }

    const result = { pulledCount: rawRows.length, importedCount, pushedToCloudCount };
    if (!silent) {
      showToast(`✅ Sheet Sync: ${rawRows.length} row(s) checked, ${importedCount} imported${pushedToCloudCount ? ` & ${pushedToCloudCount} synced to Cloud` : ''}!`);
    }
    return result;
  };

  try {
    if (silent) return await runPull();
    return await withLoading('Pulling Leads from Google Sheet...', runPull);
  } catch (err) {
    console.warn('Pull from Google Sheet warning:', err);
    if (!silent) showToast('❌ Sheet Pull Error: ' + err.message);
    return { pulledCount: 0, importedCount: 0, pushedToCloudCount: 0, error: err.message };
  }
}

async function syncLeadToSheet(lead, silent = true) {
  const url = getEffectiveWebhookUrl();
  if (!url) return false;

  const dtFormat = formatDate(lead.createdAt);
  const activities = (lead.activities || []).slice().sort((a, b) => a.timestamp - b.timestamp);

  const noteLines = activities.filter(a => a.actionType === 'note_update' && a.details).map(act => {
    const actTime = formatDate(act.timestamp);
    const clean = cleanRawActionPayload(act.details);
    return `[${actTime}] ${clean}`;
  });

  const commentsHistory = noteLines.length > 0
    ? noteLines.join('\n')
    : (lead.notes ? `[${dtFormat}] ${cleanRawActionPayload(lead.notes)}` : '');

  const touchpointLines = activities.map(act => {
    const actTime = formatDate(act.timestamp);
    const cleanPayload = cleanRawActionPayload(act.details);
    return `[${actTime}] ${act.title}: ${cleanPayload} - By: ${act.officerName || 'Officer'}${act.officerId ? ` (${act.officerId})` : ''}`;
  });
  const touchpointLog = touchpointLines.join('\n');

  const hasCoords = lead.latitude !== null && lead.latitude !== undefined && lead.longitude !== null && lead.longitude !== undefined && (Number(lead.latitude) !== 0 || Number(lead.longitude) !== 0);
  const locFormatted = hasCoords
    ? `${Number(lead.latitude).toFixed(5)}, ${Number(lead.longitude).toFixed(5)}${lead.locationAddress ? ` | ${lead.locationAddress}` : ''}`
    : (lead.locationAddress || '');

  const payload = {
    action: 'saveLead',
    id: lead.id,
    customerName: toTitleCase(lead.customerName),
    mobile: lead.mobile,
    dob: lead.dob || '',
    age: lead.age || 0,
    monthlyIncome: lead.monthlyIncome || 0,
    dbrAllowed: lead.dbrAllowed || 50,
    productId: lead.productId || '',
    productName: lead.productName,
    category: lead.category || 'asset',
    isFiler: lead.isFiler !== false ? 'Filer' : 'Non-Filer',
    taxRate: lead.taxRate || 0,
    taxDeducted: lead.taxDeducted || 0,
    netProfit: lead.netProfit || 0,
    loanAmount: lead.loanAmount,
    tenureMonths: lead.tenureMonths,
    monthlyEmi: lead.monthlyEmi,
    totalProfit: lead.totalProfit,
    processingFee: lead.processingFee,
    status: lead.status || 'New',
    followUpDate: lead.followUpDate || '',
    notes: commentsHistory,
    officerName: toTitleCase(lead.officerName || officerProfile.officerName),
    officerId: lead.officerId || officerProfile.staffId,
    branchName: toTitleCase(lead.branchName || officerProfile.branchName),
    userId: lead.userId || (currentUser ? currentUser.uid : ''),
    latitude: hasCoords ? Number(lead.latitude) : 0,
    longitude: hasCoords ? Number(lead.longitude) : 0,
    locationAddress: locFormatted,
    activitiesAudit: touchpointLog,
    touchpointLog: touchpointLog,
    approachCount: lead.approachCount || (activities.length || 1),
    notesUpdateCount: lead.notesUpdateCount || 0,
    createdAtMs: lead.createdAt || Date.now(),
    createdAtFormatted: dtFormat,
    timestamp: dtFormat
  };

  let pushed = false;
  const controller = new AbortController();
  const tId = setTimeout(() => controller.abort(), 11000);
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=utf-8' },
      body: JSON.stringify(payload),
      redirect: 'follow',
      signal: controller.signal
    });
    clearTimeout(tId);
    if (resp.ok) {
      const txt = await resp.text().catch(() => '');
      try {
        const json = JSON.parse(txt);
        pushed = json.status === 'success';
      } catch (e) {
        pushed = !txt.trim().startsWith('<');
      }
    }
  } catch (corsErr) {
    clearTimeout(tId);
    // Cross-origin redirect preflight/CORS fallback via no-cors text/plain POST
    try {
      await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify(payload),
        mode: 'no-cors'
      });
      pushed = true;
    } catch (netErr) {
      console.warn('Google Sheets POST failed:', netErr);
      if (!silent) showToast('❌ Sheets push failed: ' + netErr.message);
      pushed = false;
    }
  }

  lead.sheetsSynced = pushed;
  await LocalDB.saveLead(lead);
  await updateSettingsStats();
  renderLeads();
  return pushed;
}

async function flushPendingLeadsToCloudAndSheets(silent = true) {
  if (!navigator.onLine || isSyncingNow) return { cloudPushed: 0, sheetPushed: 0 };
  isSyncingNow = true;
  let cloudPushed = 0;
  let sheetPushed = 0;
  try {
    const leads = await LocalDB.getAllLeads();
    const webhook = getEffectiveWebhookUrl();

    for (const l of leads) {
      if (l.syncStatus !== 'synced' && fb && currentUser) {
        try {
          await fb.setDoc('leads', l.id, {
            ...l,
            syncStatus: 'synced',
            updatedAtMs: l.updatedAtMs || Date.now(),
            updatedAt: fb.serverTimestamp()
          }, true);
          l.syncStatus = 'synced';
          await LocalDB.saveLead(l);
          cloudPushed++;
        } catch (e) {
          console.warn('Pending lead Firestore flush error:', e);
        }
      }
      if (webhook && (l.sheetsSynced !== true || l.syncStatus !== 'synced')) {
        const ok = await syncLeadToSheet(l, true);
        if (ok) sheetPushed++;
      }
    }
  } finally {
    isSyncingNow = false;
  }
  return { cloudPushed, sheetPushed };
}

async function manualSync(silent = false) {
  if (!navigator.onLine) {
    if (!silent) showToast('Device is currently offline.');
    return;
  }

  const runSync = async () => {
    // 1. Push any pending local leads upward to Firestore & Sheets
    const pushRes = await flushPendingLeadsToCloudAndSheets(true);

    // 2. Pull fresh Scoped Cloud Leads, Profile, and Config from Firestore
    if (fb && currentUser) {
      try {
        await verifyUserAccessAndSyncProfile(currentUser, true);
        const plans = buildScopedFirestorePlans();
        const snaps = await Promise.race([
          Promise.all(plans.map(plan => {
            if (plan.constraints.length === 0 || typeof fb.queryCollection !== 'function') {
              return fb.getDocs('leads');
            }
            return fb.queryCollection('leads', plan.constraints);
          })),
          new Promise((_, rej) => setTimeout(() => rej(new Error('Firestore timeout')), 9000))
        ]);

        const localLeads = await LocalDB.getAllLeads();
        const localMap = new Map(localLeads.map(l => [l.id, l]));
        const remoteMap = new Map();

        snaps.forEach(snap => {
          snap.docs.forEach(docSnap => {
            const d = docSnap.data();
            const leadId = d.id || docSnap.id;
            const candidate = mapRemoteDocToLead(docSnap, localMap.get(leadId));
            if (!isLeadInAuthorizedScope(candidate)) return;
            const prev = remoteMap.get(leadId);
            if (!prev || candidate.updatedAtMs >= prev.updatedAtMs) {
              remoteMap.set(leadId, candidate);
            }
          });
        });

        for (const [leadId, remoteLead] of remoteMap.entries()) {
          const existing = localMap.get(leadId);
          if (existing && existing.syncStatus === 'pending_sync' && (Number(existing.updatedAtMs) || 0) > (Number(remoteLead.updatedAtMs) || 0)) {
            continue;
          }
          await LocalDB.saveLead(remoteLead);
        }

        for (const local of localLeads) {
          if (local.syncStatus === 'synced' && (!remoteMap.has(local.id) || !isLeadInAuthorizedScope(local))) {
            await LocalDB.deleteLead(local.id);
          }
        }
      } catch (e) {
        console.warn('Firestore manual sync notice:', e);
        if (!silent) showToast('⚠️ Firestore sync warning: ' + e.message);
      }
    }

    // 3. Pull any leads from Google Sheets & import Sheet-only rows upward to Cloud
    let pullRes = { pulledCount: 0, importedCount: 0, error: null };
    if (getEffectiveWebhookUrl()) {
      pullRes = await pullLeadsFromGoogleSheet(true);
    }

    lastCloudSyncTime = Date.now();
    await renderLeads();
    await updateSettingsStats();
    if (!silent) {
      const sheetNote = pullRes.error
        ? ` (Sheet Pull: ${pullRes.error})`
        : (pullRes.importedCount > 0 ? ` • Imported ${pullRes.importedCount} from Sheet` : '');
      const pushedNote = (pushRes.cloudPushed > 0 || pushRes.sheetPushed > 0)
        ? ` • Pushed ${Math.max(pushRes.cloudPushed, pushRes.sheetPushed)}`
        : '';
      showToast(`✅ Synced with Firestore & Sheets${pushedNote}${sheetNote}!`);
    }
  };

  if (silent) {
    await runSync();
  } else {
    await withLoading('Syncing Firestore & Google Sheets...', runSync);
  }
}

// 14. SETTINGS & PROFILE MANAGEMENT
async function loadSettings() {
  officerProfile.officerName = await LocalDB.getSetting('officerName', '');
  officerProfile.staffId = await LocalDB.getSetting('staffId', '');
  officerProfile.branchName = await LocalDB.getSetting('branchName', '');
  officerProfile.webhookUrl = await LocalDB.getSetting('webhookUrl', '');
  officerProfile.profileUpdatedAt = Number(await LocalDB.getSetting('profileUpdatedAt', 0)) || 0;

  if (!officerProfile.webhookUrl && fb) {
    try {
      const configDoc = await fb.getDoc('config', 'global');
      if (configDoc.exists() && configDoc.data().googleSheetsWebhookUrl) {
        officerProfile.webhookUrl = configDoc.data().googleSheetsWebhookUrl;
        globalConfig.googleSheetsWebhookUrl = officerProfile.webhookUrl;
        await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);
      }
    } catch (e) {}
  }

  document.getElementById('settingsOfficerName').value = officerProfile.officerName;
  document.getElementById('settingsStaffId').value = officerProfile.staffId;
  document.getElementById('settingsBranch').value = officerProfile.branchName;

  document.getElementById('officerBadge').textContent = officerProfile.officerName
    ? `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId || 'RO'})`
    : '👤 Officer: Sign In Required';
  document.getElementById('branchBadge').textContent = officerProfile.branchName
    ? `🏢 ${officerProfile.branchName}`
    : '🏢 Cloud Synced';
}

async function saveSettings() {
  await withLoading('Saving Profile to Cloud...', async () => {
    const oldBranch = officerProfile.branchName || '';
    const nowTs = Date.now();
    officerProfile.officerName = toTitleCase(document.getElementById('settingsOfficerName').value.trim() || 'Loan Officer');
    officerProfile.staffId = document.getElementById('settingsStaffId').value.trim().toUpperCase() || 'RO-001';
    const editedBranch = document.getElementById('settingsBranch').value.trim();
    officerProfile.branchName = editedBranch ? toTitleCase(editedBranch) : ((currentUserProfile && currentUserProfile.branchName) || officerProfile.branchName || '');
    officerProfile.profileUpdatedAt = nowTs;

    await LocalDB.setSetting('officerName', officerProfile.officerName);
    await LocalDB.setSetting('staffId', officerProfile.staffId);
    await LocalDB.setSetting('branchName', officerProfile.branchName);
    await LocalDB.setSetting('profileUpdatedAt', nowTs);

    let cloudSynced = false;
    let cloudErr = '';
    if (fb && currentUser && navigator.onLine) {
      try {
        const existingAllowed = (currentUserProfile && currentUserProfile.allowedSupervisorBranch) || '';
        let nextAllowed = existingAllowed;
        if (!existingAllowed || (oldBranch && existingAllowed.toLowerCase() === oldBranch.toLowerCase())) {
          nextAllowed = officerProfile.branchName;
        }
        const userData = {
          uid: currentUser.uid,
          email: currentUser.email || '',
          officerName: officerProfile.officerName,
          staffId: officerProfile.staffId,
          branchName: officerProfile.branchName,
          allowedSupervisorBranch: nextAllowed,
          role: (currentUserProfile && currentUserProfile.role) || 'Loan Officer',
          isBlocked: currentUserProfile ? Boolean(currentUserProfile.isBlocked) : false,
          canUseSupervisorFilter: currentUserProfile ? currentUserProfile.canUseSupervisorFilter !== false : true,
          updatedAt: nowTs
        };
        currentUserProfile = { ...userData };
        await fb.setDoc('users', currentUser.uid, userData, true);
        await fb.setDoc('config', 'global', {
          registeredUsersMap: { [currentUser.uid]: userData },
          updatedAt: fb.serverTimestamp()
        }, true);
        cloudSynced = true;
        lastCloudSyncTime = Date.now();
      } catch (e) {
        cloudErr = e && e.message ? e.message : 'Cloud error';
        console.warn('Firestore profile sync error:', e);
      }
    } else {
      cloudErr = !currentUser ? 'Not signed in' : 'Offline';
    }

    document.getElementById('settingsOfficerName').value = officerProfile.officerName;
    document.getElementById('settingsStaffId').value = officerProfile.staffId;
    document.getElementById('settingsBranch').value = officerProfile.branchName;
    document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
    document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName || 'Unassigned'}`;
    await renderLeads();
    await updateSettingsStats();
    showToast(cloudSynced ? '✅ Profile & Branch synced to Cloud (Web & Android)!' : `💾 Saved locally (${cloudErr}). Sign in online to sync.`);
  });
}

async function testConnection(customUrl = null) {
  const url = String(customUrl !== null ? customUrl : getEffectiveWebhookUrl()).trim();
  if (!url) {
    showToast('Please enter Webhook URL in Admin Panel first.');
    return;
  }
  if (!url.startsWith('https://script.google.com/')) {
    showToast('⚠️ Enter a valid https://script.google.com/... Web App URL.');
    return;
  }
  await withLoading('Testing Google Sheets Webhook (2-Way)...', async () => {
    try {
      const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify({ action: 'ping' }),
        redirect: 'follow'
      });
      const txt = await resp.text().catch(() => '');
      let parsed = null;
      try { parsed = JSON.parse(txt); } catch (e) {}
      if (parsed && parsed.status === 'success') {
        showToast(`✅ Connected! (${parsed.version || 'v2.3'}) — 2-Way Pull & Push ready.`);
      } else if (txt.trim().startsWith('<')) {
        showToast('⚠️ Deployment Access must be "Anyone" (Apps Script returned HTML login page).');
      } else {
        showToast('✅ Webhook reachable! Ping delivered to Google Apps Script.');
      }
    } catch (err) {
      try {
        await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain;charset=utf-8' },
          body: JSON.stringify({ action: 'ping' }),
          mode: 'no-cors'
        });
        showToast('✅ Webhook ping delivered! (Browser CORS hid response; test Pull Sheet too.)');
      } catch (err2) {
        showToast('❌ Webhook unreachable: ' + err2.message);
      }
    }
  });
}

async function updateSettingsStats() {
  const leads = await LocalDB.getAllLeads();
  const myLeads = leads.filter(isLeadOwnedByCurrentOfficer);
  const totEl = document.getElementById('statTotalLeads');
  const pendEl = document.getElementById('statPendingSync');
  const lastEl = document.getElementById('statLastSyncTime');
  if (totEl) totEl.textContent = myLeads.length;
  if (pendEl) pendEl.textContent = myLeads.filter(l => l.syncStatus !== 'synced' || (getEffectiveWebhookUrl() && l.sheetsSynced !== true)).length;
  if (lastEl) {
    lastEl.textContent = lastCloudSyncTime
      ? formatDate(lastCloudSyncTime)
      : (currentUser ? 'Connected' : 'Local Mode');
  }
}

async function exportLeadsJson() {
  await withLoading('Preparing Personal Backup...', async () => {
    const leads = await LocalDB.getAllLeads();
    const myLeads = leads.filter(isLeadOwnedByCurrentOfficer);
    if (myLeads.length === 0) {
      showToast('No personal leads found in your offline cache.');
      return;
    }
    const blob = new Blob([JSON.stringify(myLeads, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `kbl-my-leads-${officerProfile.staffId || 'backup'}-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast(`📥 Backed up ${myLeads.length} personal lead(s)`);
  });
}

function showInstallHelp() {
  openModal('installGuideModal');
}

// 15. MODALS, FAIL-SAFE LOADING OVERLAY, TOASTS & FORMATTING HELPERS
function showLoading(message = 'Processing...') {
  activeLoadingCount++;
  const ov = document.getElementById('globalLoadingOverlay');
  const txt = document.getElementById('globalLoadingText');
  const dismissBtn = document.getElementById('loadingDismissBtn');
  if (txt) txt.textContent = message;
  if (dismissBtn) dismissBtn.style.display = 'none';
  if (ov) ov.classList.add('active');

  if (loadingDismissTimer) clearTimeout(loadingDismissTimer);
  loadingDismissTimer = setTimeout(() => {
    const btn = document.getElementById('loadingDismissBtn');
    if (btn && activeLoadingCount > 0) btn.style.display = 'inline-block';
  }, 5000);

  if (loadingFailSafeTimer) clearTimeout(loadingFailSafeTimer);
  loadingFailSafeTimer = setTimeout(() => {
    if (activeLoadingCount > 0) {
      forceHideLoading();
      showToast('⏱️ Network took too long — continued in background.');
    }
  }, 18000);
}

function forceHideLoading() {
  activeLoadingCount = 0;
  if (loadingDismissTimer) { clearTimeout(loadingDismissTimer); loadingDismissTimer = null; }
  if (loadingFailSafeTimer) { clearTimeout(loadingFailSafeTimer); loadingFailSafeTimer = null; }
  const ov = document.getElementById('globalLoadingOverlay');
  const dismissBtn = document.getElementById('loadingDismissBtn');
  if (dismissBtn) dismissBtn.style.display = 'none';
  if (ov) ov.classList.remove('active');
}

function hideLoading() {
  activeLoadingCount = Math.max(0, activeLoadingCount - 1);
  if (activeLoadingCount === 0) {
    forceHideLoading();
  }
}

async function withLoading(message, asyncFn) {
  showLoading(message);
  try {
    return await Promise.race([
      Promise.resolve().then(asyncFn),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Operation timed out on slow connection')), 16000))
    ]);
  } catch (err) {
    console.warn('withLoading error:', err);
    if (err && err.message && err.message.includes('timed out')) {
      showToast('⏱️ Request timed out on slow network.');
    }
    throw err;
  } finally {
    hideLoading();
  }
}

function openModal(id) {
  const el = document.getElementById(id);
  if (el) el.classList.add('open');
}

function closeModal(id) {
  const el = document.getElementById(id);
  if (el) el.classList.remove('open');
}

function showToast(msg) {
  const el = document.getElementById('toastBanner');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.classList.remove('show');
  }, 3200);
}

function copyToClipboard(text, toastMsg = 'Copied!') {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      showToast(toastMsg);
    }).catch(() => {
      fallbackCopy(text, toastMsg);
    });
  } else {
    fallbackCopy(text, toastMsg);
  }
}

function fallbackCopy(text, toastMsg) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand('copy');
    showToast(toastMsg);
  } catch (e) {}
  document.body.removeChild(ta);
}

function formatPKR(val) {
  if (isNaN(val) || val === null || val === undefined) return '0';
  return Math.round(val).toLocaleString('en-PK');
}

function formatDate(ts) {
  if (!ts) return 'N/A';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return 'N/A';
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
  const digits = String(phone || '').replace(/\D/g, '');
  if (digits.startsWith('03') && digits.length === 11) return '92' + digits.substring(1);
  if (digits.startsWith('3') && digits.length === 10) return '92' + digits;
  return digits;
}

function toTitleCase(str) {
  if (!str) return '';
  return String(str)
    .trim()
    .replace(/\s+/g, ' ')
    .replace(/(^|\s)\S/g, (ch) => ch.toUpperCase());
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
