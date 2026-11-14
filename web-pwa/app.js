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
let toastTimer = null;

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

// 5. APPLICATION STATE & BOOTSTRAP
let currentProduct = BANK_PRODUCTS[0];
let currentEditingLeadId = null;
let currentFollowUpLeadId = null;

let officerProfile = {
  officerName: 'Anjum Naveed',
  staffId: 'RM-8433',
  branchName: 'Main Branch',
  webhookUrl: ''
};

window.addEventListener('DOMContentLoaded', async () => {
  const savedTheme = await LocalDB.getSetting('themeMode', 'light');
  applyThemeMode(savedTheme);

  await loadSettings();
  populateProducts();
  calculate();
  await renderLeads();
  updateOnlineStatus();
  updateSettingsStats();

  if (window.FirebaseBridge) {
    initFirebaseAuthAndFirestore();
  }
  initGoogleIdentityServices();

  window.addEventListener('online', () => {
    updateOnlineStatus();
    manualSync(true);
  });
  window.addEventListener('offline', updateOnlineStatus);

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').then(() => {
      console.log('PWA Service Worker registered');
    }).catch(err => console.warn('SW registration notice:', err));
  }
});

window.addEventListener('firebase-bridge-ready', () => {
  initFirebaseAuthAndFirestore();
});
window.addEventListener('gis-script-ready', () => {
  initGoogleIdentityServices();
});

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

      startFirestoreGlobalConfigListener(user);
      startFirestoreUserListener(user);
      await verifyUserAccessAndSyncProfile(user);
      startFirestoreProductsListener();
      startFirestoreLeadsListener();
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
      globalConfig.adminPin = d.adminPin || 'Admin123';
      globalConfig.adminBroadcastMessage = d.adminBroadcastMessage || '';
      globalConfig.adminBroadcastId = d.adminBroadcastId || '';
      globalConfig.appUpdateUrl = d.appUpdateUrl || '';
      globalConfig.appUpdateId = d.appUpdateId || '';
      globalConfig.appUpdateMessage = d.appUpdateMessage || '';
      globalConfig.googleSheetsWebhookUrl = d.googleSheetsWebhookUrl || '';
      globalConfig.registeredUsersMap = d.registeredUsersMap || {};
      globalConfig.deletedUserKeys = Array.isArray(d.deletedUserKeys) ? d.deletedUserKeys : [];

      if (d.googleSheetsWebhookUrl && !officerProfile.webhookUrl) {
        officerProfile.webhookUrl = d.googleSheetsWebhookUrl;
        await LocalDB.setSetting('webhookUrl', officerProfile.webhookUrl);
        const webhookInput = document.getElementById('settingsWebhookUrl');
        if (webhookInput) webhookInput.value = officerProfile.webhookUrl;
      }

      updateAppUpdateBanner();
      checkAndShowBroadcastModal();
      calculate();

      if (adminUnlocked) {
        populateAdminControls();
      }
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

async function verifyUserAccessAndSyncProfile(user) {
  if (!fb) return;

  try {
    const isPrimaryAdmin = Boolean(
      user.email && (user.email.toLowerCase().includes('vipanjum') || user.email.toLowerCase().includes('admin'))
    );

    const snap = await fb.getDoc('users', user.uid);
    let userRecord = snap.exists() ? snap.data() : null;

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

      const roleEl = document.getElementById('settingsUserRole');
      if (roleEl) {
        const scope = effectiveRecord.allowedSupervisorBranch || effectiveRecord.branchName || 'Own Branch';
        roleEl.textContent = `${effectiveRecord.role || 'Loan Officer'} (${effectiveRecord.canUseSupervisorFilter === false ? 'Personal Only' : scope})`;
      }
    } else {
      const defaultRole = isPrimaryAdmin ? 'Admin' : 'Loan Officer';
      const defaultBlocked = !isPrimaryAdmin;

      const newUser = {
        uid: user.uid,
        email: user.email || '',
        officerName: user.displayName || officerProfile.officerName || 'Loan Officer',
        staffId: officerProfile.staffId || ('RO-' + user.uid.substring(0, 4).toUpperCase()),
        branchName: officerProfile.branchName || 'Main Branch',
        allowedSupervisorBranch: isPrimaryAdmin ? 'ALL' : (officerProfile.branchName || 'Main Branch'),
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

    document.getElementById('officerBadge').textContent = `👤 Officer: ${officerProfile.officerName} (${officerProfile.staffId})`;
    document.getElementById('branchBadge').textContent = `🏢 ${officerProfile.branchName}`;
    document.getElementById('settingsOfficerName').value = officerProfile.officerName;
    document.getElementById('settingsStaffId').value = officerProfile.staffId;
    document.getElementById('settingsBranch').value = officerProfile.branchName;

    const statusEl = document.getElementById('settingsAccessStatus');
    if (statusEl) {
      statusEl.textContent = 'Authorized (Active)';
      statusEl.style.color = '#16A34A';
    }

    renderLeads();
  } catch (err) {
    console.warn('Error verifying user access in Firestore:', err);
  }
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
        const minAge = Number(d.minAge) || 21;
        const maxAgeAtMaturity = Number(d.maxAgeAtMaturity) || 65;
        const feePercent = Number(d.defaultFeeValue) ?? Number(d.feePercent) ?? 1.5;
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
  });
}

// 8. REAL-TIME FIRESTORE LEADS SYNCHRONIZATION
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
        branchName: d.branchName || 'Main Branch',
        userId: d.userId || '',
        latitude: d.latitude ?? null,
        longitude: d.longitude ?? null,
        locationAddress: d.locationAddress || '',
        locationCapturedAt: Number(d.locationCapturedAt) || 0,
        createdAt: Number(d.createdAt) || Date.now(),
        syncStatus: 'synced',
        approachCount: Number(d.approachCount) || (Array.isArray(d.activities) ? d.activities.length : 1),
        activities: Array.isArray(d.activities) ? d.activities : (existing ? existing.activities : [])
      };

      LocalDB.saveLead(remoteLead);
      localMap.set(leadId, remoteLead);
    });

    for (const local of localLeads) {
      if (local.syncStatus === 'synced' && !remoteIds.has(local.id)) {
        await LocalDB.deleteLead(local.id);
      }
    }

    await renderLeads();
    updateSettingsStats();
    if (adminUnlocked) {
      await renderAdminLeadsDbList();
    }
  }, (err) => {
    console.warn('Leads sync stream notice:', err);
  });
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

  const target = document.getElementById(tabId);
  if (target) target.classList.add('active');
  if (navElement) navElement.classList.add('active');
  else {
    const fallbackNav = document.querySelector(`.nav-item[data-tab="${tabId}"]`);
    if (fallbackNav) fallbackNav.classList.add('active');
  }

  if (tabId === 'leadsTab') {
    renderLeads();
  } else if (tabId === 'settingsTab') {
    updateSettingsStats();
  } else if (tabId === 'adminTab' && adminUnlocked) {
    populateAdminControls();
  }
}

// 10. GEOLOCATION & PROPOSAL DISPATCH (NON-WRAPPING ACTIONS)
function captureBrowserLocation(timeoutMs = 3500) {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const lat = Number(pos.coords.latitude.toFixed(6));
        const lng = Number(pos.coords.longitude.toFixed(6));
        resolve({
          latitude: lat,
          longitude: lng,
          locationAddress: `GPS: ${lat}, ${lng}`,
          locationCapturedAt: Date.now()
        });
      },
      () => resolve(null),
      { enableHighAccuracy: true, timeout: timeoutMs, maximumAge: 60000 }
    );
  });
}

function buildCalculatorProposalText() {
  if (!currentProduct || !lastCalculation) return '';
  const cust = document.getElementById('customerName').value.trim() || 'Valued Customer';
  const isLiab = currentProduct.category === 'liability';
  const emiOrProfit = isLiab ? (lastCalculation.netMonthlyProfit || 0) : (lastCalculation.emi || 0);
  const feeOrTax = isLiab ? (lastCalculation.totalTax || 0) : (lastCalculation.totalFee || 0);
  const totalOrMat = isLiab ? (lastCalculation.netMaturity || 0) : (lastCalculation.totalPayable || 0);

  return [
    `السلام علیکم جناب *${cust}*،`,
    `*KBL Financing & Deposit Services*`,
    ``,
    `آپ کی مطلوبہ ${isLiab ? 'ڈپازٹ' : 'لون'} کوٹیشن:`,
    `• پراڈکٹ: *${currentProduct.name}*`,
    `• رقم (Principal): *PKR ${formatPKR(lastCalculation.amount)}*`,
    `• مدت (Tenure): *${lastCalculation.tenure} ماہ*`,
    `• شرح منافع / مارک اپ: *${lastCalculation.rate}%*`,
    isLiab
      ? `• ماہانہ خالص منافع (Net Profit): *PKR ${formatPKR(emiOrProfit)}*`
      : `• ماہانہ قسط (EMI): *PKR ${formatPKR(emiOrProfit)}*`,
    isLiab
      ? `• ودہولڈنگ ٹیکس (${lastCalculation.taxRate}%): *PKR ${formatPKR(feeOrTax)}*`
      : `• پروسیسنگ فیس (مع FED): *PKR ${formatPKR(feeOrTax)}*`,
    isLiab
      ? `• مدت پوری ہونے پر کل خالص رقم: *PKR ${formatPKR(totalOrMat)}*`
      : `• کل قابل ادائیگی رقم: *PKR ${formatPKR(totalOrMat)}*`,
    ``,
    `مزید معلومات کے لیے رابطہ فرمائیں۔ شکریہ!`,
    `آفیسر: ${officerProfile.officerName} (${officerProfile.staffId})`,
    `برانچ: ${officerProfile.branchName}`
  ].join('\n');
}

function copyProposalText() {
  const txt = buildCalculatorProposalText();
  if (!txt) {
    showToast('Calculate a plan first.');
    return;
  }
  copyToClipboard(txt, '📋 Proposal quotation copied to clipboard!');
}

async function saveLeadFromCalculator(silent = false) {
  const custName = document.getElementById('customerName').value.trim();
  const mobile = document.getElementById('customerMobile').value.trim();
  const dob = document.getElementById('customerDob').value;
  const notes = document.getElementById('leadNotes').value.trim();
  const monthlyIncome = parseFloat(document.getElementById('monthlyIncome').value) || 0;
  const allowedDbr = parseFloat(document.getElementById('allowedDbr').value) || 50;

  if (!custName) {
    if (!silent) showToast('⚠️ Please enter customer name (گاہک کا نام درج کریں).');
    return null;
  }
  if (!mobile) {
    if (!silent) showToast('⚠️ Please enter mobile number (موبائل نمبر درج کریں).');
    return null;
  }
  if (!lastCalculation || lastCalculation.amount <= 0) {
    if (!silent) showToast('⚠️ Enter Income & DBR or Desired Amount first.');
    return null;
  }

  const ageInfo = calculateDetailedAge(dob);
  const existingLeads = await LocalDB.getAllLeads();
  let lead = existingLeads.find(l => l.mobile === mobile || (l.customerName.toLowerCase() === custName.toLowerCase() && l.productName === currentProduct.name));

  const now = Date.now();
  const cleanNote = cleanRawActionPayload(notes);
  const loc = await captureBrowserLocation(2500);

  const isLiab = currentProduct.category === 'liability';
  const effEmi = isLiab ? (lastCalculation.netMonthlyProfit || 0) : (lastCalculation.emi || 0);
  const effProfit = isLiab ? (lastCalculation.netTotalProfit || 0) : (lastCalculation.totalProfit || 0);
  const effFee = isLiab ? (lastCalculation.totalTax || 0) : (lastCalculation.totalFee || 0);

  if (lead) {
    lead.customerName = custName;
    lead.dob = dob || lead.dob || '';
    lead.age = ageInfo.years || lead.age || 0;
    lead.monthlyIncome = monthlyIncome || lead.monthlyIncome || 0;
    lead.dbrAllowed = allowedDbr || lead.dbrAllowed || 50;
    lead.category = currentProduct.category;
    lead.productId = currentProduct.id;
    lead.productName = currentProduct.name;
    lead.loanAmount = lastCalculation.amount;
    lead.tenureMonths = lastCalculation.tenure;
    lead.monthlyEmi = effEmi;
    lead.totalProfit = effProfit;
    lead.processingFee = effFee;
    lead.isFiler = isLiab ? isFilerCustomer : true;
    lead.taxRate = isLiab ? (lastCalculation.taxRate || 0) : 0;
    lead.taxDeducted = isLiab ? (lastCalculation.totalTax || 0) : 0;
    lead.netProfit = isLiab ? (lastCalculation.netTotalProfit || 0) : 0;
    if (loc) {
      lead.latitude = loc.latitude;
      lead.longitude = loc.longitude;
      lead.locationAddress = loc.locationAddress;
      lead.locationCapturedAt = loc.locationCapturedAt;
    }
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
      details: `${currentProduct.name} (PKR ${formatPKR(lastCalculation.amount)}, ${lastCalculation.tenure}M)`,
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
      title: isLiab ? '🎯 Deposit Lead Created' : '🎯 Loan Lead Created',
      details: `${currentProduct.name} (PKR ${formatPKR(lastCalculation.amount)}, ${lastCalculation.tenure}M)`,
      officerName: officerProfile.officerName,
      officerId: officerProfile.staffId
    });

    lead = {
      id,
      customerName: custName,
      mobile,
      dob: dob || '',
      age: ageInfo.years || 0,
      monthlyIncome,
      dbrAllowed,
      category: currentProduct.category,
      productId: currentProduct.id,
      productName: currentProduct.name,
      loanAmount: lastCalculation.amount,
      tenureMonths: lastCalculation.tenure,
      monthlyEmi: effEmi,
      totalProfit: effProfit,
      processingFee: effFee,
      isFiler: isLiab ? isFilerCustomer : true,
      taxRate: isLiab ? (lastCalculation.taxRate || 0) : 0,
      taxDeducted: isLiab ? (lastCalculation.totalTax || 0) : 0,
      netProfit: isLiab ? (lastCalculation.netTotalProfit || 0) : 0,
      status: 'New',
      followUpDate: '',
      notes: cleanNote,
      officerName: officerProfile.officerName,
      officerId: officerProfile.staffId,
      branchName: officerProfile.branchName,
      userId: currentUser ? currentUser.uid : 'web-officer',
      latitude: loc ? loc.latitude : null,
      longitude: loc ? loc.longitude : null,
      locationAddress: loc ? loc.locationAddress : '',
      locationCapturedAt: loc ? loc.locationCapturedAt : 0,
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

  if (!silent) {
    showToast(`✅ Lead for ${custName} saved & synced!`);
    document.getElementById('customerName').value = '';
    document.getElementById('customerMobile').value = '';
    document.getElementById('customerDob').value = '';
    document.getElementById('leadNotes').value = '';
    updateAgeEligibilityUI();
  }

  await renderLeads();
  updateSettingsStats();

  if (navigator.onLine && officerProfile.webhookUrl) {
    syncLeadToSheet(lead);
  }
  return lead;
}

// 11. WHATSAPP & SMS QUOTATIONS WITH AUTO-SAVE & APPROACH LOGGING
async function sendQuotationWhatsApp(leadId = null) {
  let mobile = '';
  let msgText = '';

  if (leadId) {
    const leads = await LocalDB.getAllLeads();
    const lead = leads.find(l => l.id === leadId);
    if (!lead) return;
    mobile = lead.mobile;
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
      `آفیسر: ${officerProfile.officerName} (${officerProfile.branchName})`
    ].join('\n');

    await logApproach(leadId, 'whatsapp', '💬 WhatsApp Quotation', `Quotation sent via WhatsApp to ${mobile}`);
  } else {
    mobile = document.getElementById('customerMobile').value.trim();
    const custName = document.getElementById('customerName').value.trim();
    msgText = buildCalculatorProposalText();
    if (!msgText) {
      showToast('Calculate a plan first.');
      return;
    }
    if (custName && mobile) {
      const saved = await saveLeadFromCalculator(true);
      if (saved) {
        await logApproach(saved.id, 'whatsapp', '💬 WhatsApp Quotation', `Proposal shared on WhatsApp (${mobile})`);
      }
    }
  }

  const cleanPhone = formatForWhatsApp(mobile);
  const url = cleanPhone
    ? `https://wa.me/${cleanPhone}?text=${encodeURIComponent(msgText)}`
    : `https://wa.me/?text=${encodeURIComponent(msgText)}`;
  window.open(url, '_blank');
}

async function sendQuotationSMS(leadId = null) {
  let mobile = '';
  let smsBody = '';

  if (leadId) {
    const leads = await LocalDB.getAllLeads();
    const lead = leads.find(l => l.id === leadId);
    if (!lead) return;
    mobile = lead.mobile;
    smsBody = `Dear ${lead.customerName}, KBL Quotation: ${lead.productName}, Amount: PKR ${formatPKR(lead.loanAmount)}, Tenure: ${lead.tenureMonths}M, Monthly: PKR ${formatPKR(lead.monthlyEmi)}. Contact: ${officerProfile.officerName} (${officerProfile.branchName})`;
    await logApproach(leadId, 'sms', '✉️ SMS Quotation', `SMS quotation sent to ${mobile}`);
  } else {
    const custName = document.getElementById('customerName').value.trim() || 'Customer';
    mobile = document.getElementById('customerMobile').value.trim();
    if (!lastCalculation || lastCalculation.amount <= 0) {
      showToast('Calculate a plan first.');
      return;
    }
    if (document.getElementById('customerName').value.trim() && mobile) {
      const saved = await saveLeadFromCalculator(true);
      if (saved) {
        await logApproach(saved.id, 'sms', '✉️ SMS Quotation', `SMS quotation sent (${mobile})`);
      }
    }
    const emiVal = lastCalculation.emi || lastCalculation.netMonthlyProfit || 0;
    smsBody = `Dear ${custName}, KBL Quotation: ${currentProduct.name}, Amount: PKR ${formatPKR(lastCalculation.amount)}, Tenure: ${lastCalculation.tenure}M, Monthly: PKR ${formatPKR(emiVal)}. Contact: ${officerProfile.officerName} (${officerProfile.branchName})`;
  }

  const cleanPhone = mobile.replace(/\D/g, '');
  window.location.href = `sms:${cleanPhone}?body=${encodeURIComponent(smsBody)}`;
}

async function initiateCall(leadId, mobile) {
  await logApproach(leadId, 'call', '📞 Phone Call', `Phone call initiated to ${mobile}`);
  window.location.href = `tel:${String(mobile || '').replace(/\D/g, '')}`;
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

// 12. LEAD STATUS, NOTE, FOLLOW-UP & DELETE ACTIONS
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
  showToast(`✅ Status updated to ${newStatus}`);
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
  showToast('🗑️ Lead deleted.');
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
  lead.notesUpdateCount = (lead.notesUpdateCount || 0) + 1;
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
  showToast('✅ Note updated & synced!');
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
    showToast('Please select date and time.');
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
  showToast('📅 Follow-Up scheduled!');
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
    } catch (e) {}
  }

  closeModal('followUpModal');
  renderLeads();
  showToast('Cleared follow-up date.');
  if (navigator.onLine && officerProfile.webhookUrl) syncLeadToSheet(lead);
}

// 13. GOOGLE SHEETS IN-PLACE SYNC (DUAL-KEY UPSERT ENGINE)
async function syncLeadToSheet(lead) {
  const url = officerProfile.webhookUrl || globalConfig.googleSheetsWebhookUrl;
  if (!url) return;

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
    locationAddress: lead.locationAddress || '',
    touchpointLog: touchpointLog,
    approachCount: lead.approachCount || (activities.length || 1),
    timestamp: dtFormat
  };

  try {
    await fetch(url, {
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

async function manualSync(silent = false) {
  if (!navigator.onLine) {
    if (!silent) showToast('Device is currently offline.');
    return;
  }

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

  const webhook = officerProfile.webhookUrl || globalConfig.googleSheetsWebhookUrl;
  if (webhook) {
    const leads = await LocalDB.getAllLeads();
    const pending = leads.filter(l => l.syncStatus !== 'synced');
    for (const l of pending) {
      await syncLeadToSheet(l);
    }
  }

  await renderLeads();
  updateSettingsStats();
  if (!silent) showToast('✅ Synced with Firestore & Google Sheets!');
}

// 14. SETTINGS & PROFILE MANAGEMENT
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
  renderLeads();
  showToast('✅ Profile saved and synced!');
}

async function testConnection(customUrl = null) {
  const url = (customUrl !== null ? customUrl : document.getElementById('settingsWebhookUrl').value).trim();
  if (!url) {
    showToast('Please enter Webhook URL first.');
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
      showToast('✅ Connected successfully to Google Sheets Web App!');
    } else {
      showToast('HTTP Response ' + resp.status + ' from endpoint.');
    }
  } catch (err) {
    showToast('Connection note: ' + err.message);
  }
}

async function updateSettingsStats() {
  const leads = await LocalDB.getAllLeads();
  const totEl = document.getElementById('statTotalLeads');
  const pendEl = document.getElementById('statPendingSync');
  if (totEl) totEl.textContent = leads.length;
  if (pendEl) pendEl.textContent = leads.filter(l => l.syncStatus !== 'synced').length;
}

async function exportLeadsJson() {
  const leads = await LocalDB.getAllLeads();
  const blob = new Blob([JSON.stringify(leads, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `kb-leads-backup-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  showToast('📥 Leads JSON backup downloaded');
}

function showInstallHelp() {
  openModal('installGuideModal');
}

// 15. MODALS, TOASTS & FORMATTING HELPERS
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

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
