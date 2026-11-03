/* CODES · Frontend integrado con Spring Boot */
const API_URL = '/api';
const REFRESH_INTERVAL_MS = 8000;
let publicConfiguration = {
  mapProvider: 'Esri World Imagery',
  mapTilesUrl: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  mapConfigured: true,
  geocodingConfigured: true,
  transcriptionConfigured: false,
  routingUrl: 'https://router.project-osrm.org',
};
let baseMapLayer = null;
let mapConfigurationError = '';
let tileLoadError = '';
let tileFallbackEnabled = false;
let captchaRequired = false;
const publicConfigurationPromise = fetch(`${API_URL}/public-config`)
  .then(resp => {
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.json();
  })
  .then(config => {
    const previousTiles = publicConfiguration.mapTilesUrl;
    publicConfiguration = { ...publicConfiguration, ...config };
    mapConfigurationError = '';
    if (map && publicConfiguration.mapTilesUrl !== previousTiles) {
      configureBaseMapLayer();
    }
    updateMapStatus();
    const asrNote = $('live-nota');
    if (asrNote) asrNote.textContent = publicConfiguration.transcriptionConfigured
      ? 'El audio se enviará al servidor para transcribirse con Groq al guardar.'
      : 'Falta configurar GROQ_API_KEY en el servidor para habilitar la transcripción automática.';
  })
  .catch(error => {
    mapConfigurationError = 'No se pudo cargar la configuración; se mantienen los servicios públicos predeterminados.';
    updateMapStatus();
    console.error('No se pudo cargar la configuración pública del mapa.', error);
  });

let session = null;
// true cuando la pestaña se abrió como "vista previa de operador" (admin
// mirando la interfaz de una institución con la cuenta sintética
// preview_<institucion>): oculta/bloquea acciones que escriban datos,
// como defensa adicional a lo que ya rechaza el backend.
let readOnlyPreview = false;
let map = null;
let incidentMarker = null;
let deviceMarker = null;
let deviceAccuracy = null;
let incidentRoute = null;
let incidentRouteKey = null;
const requestedRoutes = new Set();
const calculatedRoutes = new Map();
let currentOperatorLocation = null;
let routeRequestId = 0;
let selectedCall = null;
let currentView = 'info';
let currentQueue = 'pendientes';
let queueCache = {
  pendientes: [],
  'en-curso': [],
  cerradas: []
};
let queuesInitialized = false;
const notifiedUrgencies = new Set();
let currentAdminTab = 'incidentes';
let eventFeed = [];
const captchaTokens = { login: '', register: '' };
const captchaWidgets = { login: null, register: null };
const captchaContainers = { login: 'captcha-login', register: 'captcha-register' };
const captchaErrors = { login: 'login-error', register: 'registro-error' };
const captchaInitialized = { login: false, register: false };
let captchaConfigured = false;

function showCaptchaError(type, msg) {
  const el = document.getElementById(captchaErrors[type]);
  if (el) { el.textContent = msg; el.hidden = false; }
}

function updateCaptchaStatus(type, message) {
  const status = $(`captcha-${type}-status`);
  if (status) status.textContent = message;
}

function renderCaptcha(type) {
  if (!captchaConfigured || captchaInitialized[type]) return;
  const widget = $(`captcha-${type}-widget`);
  if (!widget) return;
  captchaInitialized[type] = true;
  captchaWidgets[type] = widget;
  widget.addEventListener('verified', event => {
    captchaTokens[type] = event.detail?.payload || '';
    updateCaptchaStatus(
      type,
      captchaTokens[type] ? 'Verificación completada.' : 'No se recibió la solución ALTCHA.'
    );
  });
  widget.addEventListener('statechange', event => {
    const state = event.detail?.state;
    if (state !== 'verified') captchaTokens[type] = '';
    if (state === 'verifying') updateCaptchaStatus(type, 'Resolviendo ALTCHA Argon2id…');
    if (state === 'error') {
      showCaptchaError(type, 'No se pudo completar ALTCHA. Comprueba HTTPS y vuelve a intentarlo.');
    }
  });
  widget.addEventListener('expired', () => {
    captchaTokens[type] = '';
    updateCaptchaStatus(type, 'La verificación expiró. Inténtalo otra vez.');
  });
  widget.addEventListener('outofmemory', () => {
    captchaTokens[type] = '';
    showCaptchaError(type, 'El dispositivo no tiene memoria suficiente para completar la verificación.');
  });
}

function resetCaptcha(type) {
  captchaTokens[type] = '';
  const widget = captchaWidgets[type];
  if (typeof widget?.reset === 'function') widget.reset();
  updateCaptchaStatus(type, 'Verificación de seguridad ALTCHA Argon2id.');
}

function drawSavedRoute(route, adjustView) {
  if (!map || !route || incidentRouteKey == null) return;
  const latLngs = route.coordinates.map(([lng, lat]) => [lat, lng]);
  incidentRoute = L.polyline(latLngs, {
    color: '#ffd166',
    weight: 6,
    opacity: 0.9,
    lineCap: 'round',
    lineJoin: 'round'
  }).addTo(map);

  const distance = route.distance >= 1000
    ? `${(route.distance / 1000).toFixed(1)} km`
    : `${Math.round(route.distance)} m`;
  const minutes = Math.max(1, Math.round(route.duration / 60));
  const routeStatus = $('ruta-estado');
  if (routeStatus) routeStatus.textContent = `Ruta estimada · ${distance} · ${minutes} min`;
  if (adjustView) {
    map.fitBounds(incidentRoute.getBounds(), { padding: [40, 40], maxZoom: 16 });
  }
}

async function initializeCaptcha() {
  ['form-login', 'form-registro'].forEach(formId => {
    const form = $(formId);
    if (form) form.noValidate = true;
  });

  try {
    if (!window.altchaReadyPromise) throw new Error('No se configuró el widget ALTCHA.');
    await window.altchaReadyPromise;
    const resp = await fetch(`${API_URL}/auth/captcha-config`);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const config = await resp.json();
    captchaRequired = config.enabled === true;
    captchaConfigured = true;
    Object.keys(captchaContainers).forEach(type => {
      const container = $(captchaContainers[type]);
      if (container) container.hidden = !captchaRequired;
      if (captchaRequired) {
        renderCaptcha(type);
      } else {
        const widget = $(`captcha-${type}-widget`);
        if (widget) widget.remove();
        captchaWidgets[type] = null;
      }
    });
  } catch (error) {
    console.error('No se pudo cargar la verificación de seguridad.', error);
    captchaConfigured = false;
    ['login', 'register'].forEach(type => showCaptchaError(type, 'No se pudo conectar con la verificación de seguridad.'));
  }
}

const $ = (id) => document.getElementById(id);

function applyTheme(theme) {
  const isLightTheme = theme === 'claro';

  document.body.classList.toggle('tema-claro', isLightTheme);

  const themeButton = $('btn-tema');
  if (!themeButton) return;

  themeButton.textContent = isLightTheme ? 'Oscuro' : 'Claro';
  themeButton.setAttribute(
    'aria-label',
    isLightTheme ? 'Cambiar a tema oscuro' : 'Cambiar a tema claro'
  );
}

function initializeTheme() {
  let theme = 'oscuro';

  try {
    theme = localStorage.getItem('codes-tema') || theme;
  } catch (error) {
  }

  applyTheme(theme);

  $('btn-tema')?.addEventListener('click', () => {
    const newTheme = document.body.classList.contains('tema-claro')
      ? 'oscuro'
      : 'claro';

    applyTheme(newTheme);

    try {
      localStorage.setItem('codes-tema', newTheme);
    } catch (error) {
    }
  });
}

function escapeHtml(v) {
  return String(v ?? '').replace(
    /[&<>'"]/g,
    c => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[c])
  );
}

function showAlert(text, type = 'normal') {
  const el = $('alerta-urgente');
  const tx = $('alerta-text');

  if (!el || !tx) return;

  tx.textContent = text;
  el.hidden = false;

  el.style.background =
    type === 'error'
      ? '#7f1d1d'
      : type === 'ok'
        ? '#065f46'
        : '';

  clearTimeout(showAlert.timer);

  showAlert.timer = setTimeout(() => {
    el.hidden = true;
    el.style.background = '';
  }, 4500);
}

function recordEvent(message, styleClass = '') {
  eventFeed.unshift({
    timestamp: new Date(),
    message,
    styleClass
  });

  eventFeed = eventFeed.slice(0, 80);

  renderFeed();
}

function notifyNewUrgencies(calls) {
  const urgentCalls = calls.filter(call =>
    call && call.priority === 'URGENTE' && call.id != null
  );

  if (!queuesInitialized) {
    urgentCalls.forEach(call => notifiedUrgencies.add(call.id));
    return;
  }

  urgentCalls
    .filter(call => !notifiedUrgencies.has(call.id))
    .forEach(call => {
      notifiedUrgencies.add(call.id);
      const address = call.address || 'ubicación por determinar';
      const message = `Nueva alerta urgente: caso #${call.id}, ${address}`;
      showAlert(message, 'error');
      recordEvent(message, 'urgente');

      if (navigator.vibrate) navigator.vibrate([180, 100, 180]);
      if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
        new Notification('CODES: alerta urgente', { body: message, tag: `codes-${call.id}` });
      }
    });
}

function renderFeed() {
  const c = $('feed-lista');

  if (!c) return;

  if (!eventFeed.length) {
    c.innerHTML =
      '<p class="estado-vacio" style="font-size:.7rem;padding:1rem;">Esperando actividad…</p>';

    if ($('feed-contador')) {
      $('feed-contador').textContent = '0 eventos';
    }

    return;
  }

  c.innerHTML = eventFeed
    .map(e => `
      <div class="feed-item">
        <span class="hora">
          ${e.timestamp.toLocaleTimeString('es-CL', { hour12: false })}
        </span>
        <span class="mensaje">
          ${escapeHtml(e.message)}
        </span>
      </div>
    `)
    .join('');

  if ($('feed-contador')) {
    $('feed-contador').textContent = `${eventFeed.length} eventos`;
  }
}

async function apiFetch(route, options = {}) {
  const headers = {
    ...(options.headers || {})
  };

  const routeWithSlash = route.startsWith('/') ? route : `/${route}`;
  const finalRoute =
    routeWithSlash === API_URL || routeWithSlash.startsWith(`${API_URL}/`)
      ? routeWithSlash
      : `${API_URL}${routeWithSlash}`;

  if (session?.token) {
    headers.Authorization = `Bearer ${session.token}`;
  }

  const resp = await fetch(finalRoute, {
    ...options,
    headers
  });

  if (resp.status === 401) {
    const wasLoggedIn = !!session;

    session = null;

    showAuthScreen(
      'Tu sesión expiró o fue revocada. Ingresa nuevamente.',
      true
    );

    if (wasLoggedIn) {
      recordEvent('Sesión cerrada por el servidor.');
    }

    throw new Error('No autenticado');
  }

  return resp;
}

async function readResponse(resp) {
  const type = resp.headers.get('content-type') || '';

  if (type.includes('application/json')) {
    return resp.json();
  }

  return {
    error: await resp.text()
  };
}

function showAuthScreen(error = '') {
  if ($('pantalla-auth')) {
    $('pantalla-auth').hidden = false;
  }

  if (error && $('login-error')) {
    $('login-error').textContent = error;
    $('login-error').hidden = false;
  }
}

function ocultarPantallaAuth() {
  if ($('pantalla-auth')) {
    $('pantalla-auth').hidden = true;
  }

  if ($('login-error')) {
    $('login-error').hidden = true;
  }

  if ($('registro-error')) {
    $('registro-error').hidden = true;
  }

  if ($('registro-success')) {
    $('registro-success').hidden = true;
  }
}


function roleLabel(rol) {
  return ({ administrator: 'Administrador', supervisor: 'Supervisor', operator: 'Operador' }[rol] || rol || 'Operador');
}

function updateUserUi() {
  const rol = session?.role || 'operator';

  if ($('rol-tag')) {
    $('rol-tag').textContent = roleLabel(rol);
  }

  if ($('user-name')) {
    $('user-name').textContent =
      session?.username || 'user';
  }

  if ($('avatar-user')) {
    const avatarName = session?.username || 'U';
    $('avatar-user').textContent =
      avatarName.trim().charAt(0).toUpperCase() || 'U';
  }

  if ($('user-status')) {
    $('user-status').textContent =
      session ? '● conectado' : '';
  }

  if ($('indicador-status')) {
    $('indicador-status').className =
      `indicador ${session ? 'online' : ''}`;
  }

  if ($('status-text')) {
    $('status-text').textContent =
      session ? 'Operativo' : 'Sin sesión';
  }

  if ($('modo-badge')) {
    $('modo-badge').textContent = 'Servidor';
  }

  const esAdmin = rol === 'administrator';

  if ($('riel-admin')) {
    $('riel-admin').hidden = !esAdmin;
  }

  if ($('admin-selector')) {
    $('admin-selector').classList.toggle(
      'visible',
      esAdmin
    );
  }

  if ($('admin-tabs')) {
    $('admin-tabs').classList.toggle(
      'visible',
      esAdmin
    );
  }
}

function showAuthView(viewName) {
  const views = ['login', 'registro', 'recuperar', 'reset'];
  views.forEach(view => {
    const form = $(`form-${view}`);
    if (form) form.hidden = view !== viewName;
  });
  document.querySelectorAll('.auth-tab').forEach(tab => tab.classList.toggle('activo', tab.dataset.tab === viewName));
  const tabs = $('auth-tabs');
  if (tabs) tabs.hidden = viewName === 'recuperar' || viewName === 'reset';
}

async function requestPasswordRecovery(e) {
  e.preventDefault();
  const error = $('recovery-error');
  const success = $('recovery-success');
  error.hidden = true; success.hidden = true;
  try {
    const resp = await fetch(`${API_URL}/auth/forgot-password`, {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ email: $('recovery-email').value.trim() })
    });
    const d = await readResponse(resp);
    if (!resp.ok) throw new Error(d.error || 'No se pudo procesar la solicitud.');
    success.textContent = d.message;
    success.hidden = false;
  } catch (err) {
    error.textContent = err.message || 'No se pudo procesar la solicitud.';
    error.hidden = false;
  }
}

async function resetPassword(e) {
  e.preventDefault();
  const error = $('reset-error');
  const success = $('reset-success');
  error.hidden = true; success.hidden = true;
  const password = $('reset-password').value;
  const confirm = $('reset-password-confirm').value;
  if (password !== confirm) { error.textContent = 'Las contraseñas no coinciden.'; error.hidden = false; return; }
  const token = new URLSearchParams(location.search).get('resetToken');
  if (!token) { error.textContent = 'El enlace de recuperación no es válido.'; error.hidden = false; return; }
  try {
    const resp = await fetch(`${API_URL}/auth/reset-password`, {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ token, newPassword: password })
    });
    const d = await readResponse(resp);
    if (!resp.ok) throw new Error(d.error || 'No se pudo restablecer la contraseña.');
    success.textContent = d.message;
    success.hidden = false;
    history.replaceState({}, '', location.pathname);
    $('reset-password').value = ''; $('reset-password-confirm').value = '';
    setTimeout(() => showAuthView('login'), 1800);
  } catch (err) {
    error.textContent = err.message || 'No se pudo restablecer la contraseña.';
    error.hidden = false;
  }
}

function validateAuthFields(form) {
  const fields = form.querySelectorAll('input:not([type="hidden"]), select, textarea');
  for (const field of fields) {
    if (!field.checkValidity()) {
      field.reportValidity();
      return false;
    }
  }
  return true;
}

async function login(e) {
  e.preventDefault();
  if (!validateAuthFields(e.currentTarget)) return;

  const error = $('login-error');

  if (error) {
    error.hidden = true;
  }

  const username =
    $('login-username').value.trim();

  const password =
    $('login-password').value;

  if (!captchaConfigured) {
    error.textContent = 'No se pudo configurar la verificación ALTCHA. Recarga la página e inténtalo otra vez.';
    error.hidden = false;
    return;
  }

  if (captchaRequired && !captchaTokens.login) {
    error.textContent = 'Completa la verificación de seguridad.';
    error.hidden = false;
    return;
  }

  try {
    const resp = await fetch(
      `${API_URL}/auth/login`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          username: username,
          password,
          captchaToken: captchaRequired ? captchaTokens.login : ''
        })
      }
    );

    const d = await readResponse(resp);

    if (!resp.ok) {
      error.textContent =
        d.error ||
        'No se pudo iniciar sesión.';

      error.hidden = false;
      resetCaptcha('login');
      return;
    }

    session = d;

    $('login-password').value = '';

    ocultarPantallaAuth();
    updateUserUi();

    recordEvent(
      `Inició sesión como ${d.username}.`,
      'admin'
    );

    await enterConsole();

  } catch (err) {
    error.textContent =
      'No se pudo conectar con el servidor. ¿Está ejecutándose Spring Boot?';

    error.hidden = false;
    resetCaptcha('login');
  }
}

async function registerAccount(e) {
  e.preventDefault();
  if (!validateAuthFields(e.currentTarget)) return;

  const error = $('registro-error');
  const ok = $('registro-success');

  error.hidden = true;
  ok.hidden = true;

  const password =
    $('reg-password').value;

  const confirm =
    $('reg-password-confirm').value;

  if (password !== confirm) {
    error.textContent =
      'Las contraseñas no coinciden.';

    error.hidden = false;
    return;
  }

  const payload = {
    username:
      $('reg-username').value.trim(),

    password,

    captchaToken: captchaRequired ? captchaTokens.register : '',

    nombre:
      $('reg-first-name').value.trim(),

    apellido:
      $('reg-last-name').value.trim(),

    correo:
      $('reg-email').value.trim(),

    institucion:
      $('reg-institution').value
  };

  if (!captchaConfigured) {
    error.textContent = 'No se pudo configurar la verificación ALTCHA. Recarga la página e inténtalo otra vez.';
    error.hidden = false;
    return;
  }

  if (captchaRequired && !captchaTokens.register) {
    error.textContent = 'Completa la verificación de seguridad.';
    error.hidden = false;
    return;
  }

  try {
    const resp = await fetch(
      `${API_URL}/auth/register`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      }
    );

    const d = await readResponse(resp);

    if (!resp.ok) {
      error.textContent =
        d.error ||
        (
          d.errores
            ? Object.values(d.errores).join(' · ')
            : 'No se pudo crear la cuenta.'
        );

      error.hidden = false;
      resetCaptcha('register');
      return;
    }

    ok.textContent = 'Cuenta creada. Un administrador debe activarla antes de iniciar sesión.';

    ok.hidden = false;

    e.target.reset();
    resetCaptcha('register');

    setTimeout(
      () =>
        document
          .querySelector(
            '.auth-tab[data-tab="login"]'
          )
          ?.click(),
      1800
    );

  } catch (err) {
    error.textContent =
      'No se pudo conectar con el servidor.';

    error.hidden = false;
    resetCaptcha('register');
  }
}


async function changePassword() {
  if (!session) return;
  const currentValue = window.prompt('Contraseña actual:');
  if (currentValue === null) return;
  const newValue = window.prompt('Nueva contraseña (mínimo 12 caracteres, mayúscula, minúscula, número y símbolo):');
  if (newValue === null) return;
  const confirmation = window.prompt('Repite la nueva contraseña:');
  if (confirmation === null) return;
  if (newValue !== confirmation) { showAlert('Las contraseñas nuevas no coinciden.', 'error'); return; }
  try {
    const r = await apiFetch('/auth/change-password', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword: currentValue, newPassword: newValue })
    });
    const d = await readResponse(r);
    if (!r.ok) throw new Error(d.error || 'No se pudo cambiar la contraseña');
    showAlert('Contraseña cambiada correctamente.', 'ok');
  } catch (e) { if (e.message !== 'No autenticado') showAlert(e.message, 'error'); }
}

async function logout() {
  if (!session) return;

  const token = session.token;

  if (live.startedAt) {
    stopLiveCall(false);
  }

  try {
    await fetch(`${API_URL}/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    });
  } catch (e) {
    console.warn('No se pudo invalidar la sesión en el servidor.', e);
  }

  const user =
    session.username;

  session = null;

  if (vistaPreviaSoloLectura) {
    vistaPreviaSoloLectura = false;

    if ($('banner-vista-previa')) {
      $('banner-vista-previa').hidden = true;
    }

    if ($('btn-llamada-en-vivo')) {
      $('btn-llamada-en-vivo').hidden = false;
    }
  }

  queueCache = {
    pendientes: [],
    'en-curso': [],
    cerradas: []
  };

  selectedCall = null;
  requestedRoutes.clear();
  calculatedRoutes.clear();
  incidentRouteKey = null;
  currentOperatorLocation = null;
  if (incidentRoute && map) map.removeLayer(incidentRoute);
  incidentRoute = null;

  updateUserUi();

  if ($('panel-cola')) {
    $('panel-cola').style.display = 'flex';
  }

  if ($('gestion-usuarios')) {
    $('gestion-usuarios').classList.remove(
      'visible'
    );
  }

  if ($('panel-detalle')) {
    $('panel-detalle').style.display = 'flex';
  }

  showAuthScreen();

  recordEvent(
    `Cerró sesión ${user}.`
  );

  $('login-username').value = '';
  $('login-password').value = '';
  $('login-username').focus();
}

async function enterConsole() {
  updateAll();

  changeView('info');

  initializeMap();
  setTimeout(() => map?.invalidateSize(true), 60);

  await loadQueue();

  await updateMetrics();

  if (session?.role === 'administrator') {
    await loadUsers();
  }
}

function updateClock() {
  const currentTime = new Date();

  if ($('reloj')) {
    $('reloj').textContent =
      currentTime.toLocaleTimeString(
        'es-CL',
        { hour12: false }
      );
  }

  if ($('ultima-actualizacion')) {
    $('ultima-actualizacion').textContent =
      `· ${currentTime.toLocaleTimeString(
        'es-CL',
        { hour12: false }
      )}`;
  }
}

setInterval(
  updateClock,
  1000
);

updateClock();


// ======================================================
// MAPA
// ======================================================

function initializeMap() {
  if (
    map ||
    typeof L === 'undefined' ||
    !$('mapa')
  ) {
    return;
  }

  map = L
    .map('mapa', {
      zoomControl: true
    })
    .setView(
      [-33.4489, -70.6693],
      12
    );

  configureBaseMapLayer();
  $('btn-reintentar-ruta')?.addEventListener(
    'click',
    retrySelectedRoute
  );
  $('btn-mi-ubicacion')?.addEventListener(
    'click',
    showDeviceLocation
  );
}

const tileProviders = [
  {
    name: 'Esri World Imagery',
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: 'Tiles &copy; Esri &mdash; Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community',
    maxZoom: 19
  },
  {
    name: 'OpenStreetMap',
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap contributors</a>',
    maxZoom: 19
  },
  {
    name: 'Carto',
    url: 'https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors &copy; <a href="https://carto.com/attributions" target="_blank" rel="noopener noreferrer">CARTO</a>',
    maxZoom: 20
  },
  {
    name: 'Esri World Street Map',
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',
    attribution: 'Tiles &copy; Esri',
    maxZoom: 19
  }
];
let tileProviderIndex = -1;
let tileTimer = null;

function activateTileProvider(index, message = '') {
  if (!map || index < 0 || index >= activeTileProviders.length) {
    tileLoadError = 'No se pudieron cargar los mapas base. Comprueba la conexión a Internet o el acceso a los proveedores de mapas.';
    updateMapStatus();
    return;
  }
  if (tileTimer) clearTimeout(tileTimer);
  tileProviderIndex = index;
  const provider = activeTileProviders[index];
  const previousLayer = baseMapLayer;
  const nextLayer = L.tileLayer(provider.url, {
    attribution: provider.attribution,
    maxZoom: provider.maxZoom,
    updateWhenIdle: true,
    keepBuffer: 2
  });
  let tileLoaded = false;
  nextLayer.on('tileload', () => {
    tileLoaded = true;
    tileLoadError = '';
    tileFallbackEnabled = index > 0;
    updateMapStatus();
    if (previousLayer && map.hasLayer(previousLayer)) map.removeLayer(previousLayer);
  });
  nextLayer.on('tileerror', () => {
    if (!tileLoaded && tileProviderIndex === index) {
      if (tileTimer) clearTimeout(tileTimer);
      tileTimer = setTimeout(() => {
        if (!tileLoaded && tileProviderIndex === index) activateTileProvider(index + 1, provider.name);
      }, 1800);
    }
  });
  baseMapLayer = nextLayer.addTo(map);
  tileLoadError = message ? `${message} no responde; probando ${provider.name}…` : '';
  updateMapStatus();
  tileTimer = setTimeout(() => {
    if (!tileLoaded && tileProviderIndex === index) activateTileProvider(index + 1, provider.name);
  }, 7000);
}

function createBaseLayer(url) {
  // Preserve a configured tile URL as the first choice, then fall back to public providers.
  const configured = (url || '').trim();
  if (configured && !configured.includes('{z}') || configured && !configured.includes('{x}') || configured && !configured.includes('{y}')) {
    console.warn('Configured map tile URL is missing a required {z}/{x}/{y} placeholder; using public fallback providers.');
  }
  const customProvider = configured && ['{z}', '{x}', '{y}'].every(token => configured.includes(token))
    ? [{ name: 'Configured provider', url: configured, attribution: '&copy; Map data provider', maxZoom: 22 }]
    : [];
  const allProviders = [...customProvider, ...tileProviders];
  // Keep the shared fallback list aligned with the active map instance.
  if (customProvider.length) {
    activeTileProviders = allProviders;
  } else {
    activeTileProviders = tileProviders;
  }
  activateTileProvider(0);
  return baseMapLayer;
}
let activeTileProviders = tileProviders;

function configureBaseMapLayer() {
  if (!map) return;
  tileFallbackEnabled = false;
  tileProviderIndex = -1;
  if (tileTimer) clearTimeout(tileTimer);
  if (baseMapLayer) map.removeLayer(baseMapLayer);
  baseMapLayer = null;
  let tileUrl = (publicConfiguration.mapTilesUrl || '').trim();
  // El servidor antiguo devolvía OpenStreetMap como "configurado" por defecto: se ignora para usar Esri Imagery.
  if (tileUrl.includes('tile.openstreetmap.org')) tileUrl = '';
  const customProvider = tileUrl && ['{z}', '{x}', '{y}'].every(token => tileUrl.includes(token))
    ? [{ name: 'Configured provider', url: tileUrl, attribution: '&copy; Map data provider', maxZoom: 22 }]
    : [];
  activeTileProviders = [...customProvider, ...tileProviders];
  activateTileProvider(0);
}

function updateMapStatus() {
  const status = $('mapa-provider-estado');
  if (!status) return;
  const avisos = [];
  if (!publicConfiguration.mapConfigured) avisos.push('Teselas no configuradas.');
  if (!publicConfiguration.geocodingConfigured) avisos.push('Geocodificación no configurada.');
  if (!publicConfiguration.routingUrl) avisos.push('Rutas no configuradas.');
  if (mapConfigurationError) avisos.push(mapConfigurationError);
  if (tileLoadError) avisos.push(tileLoadError);
  status.textContent = avisos.join(' ');
  status.hidden = avisos.length === 0;
}

function showDeviceLocation() {
  const status = $('ubicacion-estado');
  const locationButton = $('btn-mi-ubicacion');

  if (!navigator.geolocation) {
    if (status) status.textContent = 'Geolocalización no disponible en este navegador.';
    return;
  }

  initializeMap();
  if (!map) return;

  if (status) status.textContent = 'Solicitando ubicación aproximada...';
  if (locationButton) locationButton.disabled = true;

  navigator.geolocation.getCurrentPosition(
    posicion => {
      const latLng = [
        posicion.coords.latitude,
        posicion.coords.longitude
      ];
      const accuracy = Math.max(posicion.coords.accuracy || 100, 40);

      if (deviceMarker) map.removeLayer(deviceMarker);
      if (deviceAccuracy) map.removeLayer(deviceAccuracy);

      deviceAccuracy = L.circle(latLng, {
        radius: accuracy,
        color: '#4da3ff',
        fillColor: '#4da3ff',
        fillOpacity: 0.14,
        weight: 1.5
      }).addTo(map);

      deviceMarker = L.circleMarker(latLng, {
        radius: 8,
        color: '#ffffff',
        weight: 3,
        fillColor: '#1683ff',
        fillOpacity: 1
      })
        .addTo(map)
        .bindPopup(`Tu ubicación aproximada<br>Precisión: ${Math.round(accuracy)} m`);

      map.setView(latLng, Math.max(map.getZoom(), 15));
      if (status) status.textContent = `Ubicación aproximada · margen de ${Math.round(accuracy)} m`;
      if (locationButton) locationButton.disabled = false;
    },
    () => {
      if (status) status.textContent = 'No se pudo obtener la ubicación del dispositivo.';
      if (locationButton) locationButton.disabled = false;
    },
    {
      enableHighAccuracy: false,
      timeout: 8000,
      maximumAge: 300000
    }
  );
}

async function drawRouteToIncident(l) {
  const routeStatus = $('ruta-estado');
  const retryRouteButton = $('btn-reintentar-ruta');
  const requestId = ++routeRequestId;
  if (retryRouteButton) retryRouteButton.hidden = true;
  if (!l || l.latitude == null || l.longitude == null) {
    if (routeStatus) routeStatus.textContent = 'Ruta no disponible: el incidente no tiene coordenadas.';
    return;
  }
  const routeKey = `${l.id}:${l.latitude}:${l.longitude}`;

  if (incidentRoute && map) {
    map.removeLayer(incidentRoute);
    incidentRoute = null;
  }
  incidentRouteKey = routeKey;

  const savedRoute = calculatedRoutes.get(routeKey);
  if (savedRoute) {
    drawSavedRoute(savedRoute, false);
    return;
  }
  if (requestedRoutes.has(routeKey)) {
    if (routeStatus) routeStatus.textContent = 'El intento anterior de calcular la ruta falló.';
    if (retryRouteButton) retryRouteButton.hidden = false;
    return;
  }

  if (!currentOperatorLocation) {
    if (routeStatus) routeStatus.textContent = 'Obteniendo tu ubicación para calcular la ruta…';
    await getRouteOrigin();
  }

  if (requestId !== routeRequestId) return;

  const origin = currentOperatorLocation;
  if (!origin) {
    if (routeStatus) routeStatus.textContent = 'No se pudo obtener tu ubicación. Puedes seleccionar “Ubicación actual”.';
    if (retryRouteButton) retryRouteButton.hidden = false;
    return;
  }

  if (routeStatus) routeStatus.textContent = 'Calculando ruta hacia el incidente…';

  try {
    await publicConfigurationPromise;
    if (!publicConfiguration.routingUrl) {
      if (routeStatus) routeStatus.textContent = 'El servicio de rutas no está configurado.';
      return;
    }
    requestedRoutes.add(routeKey);
    const baseRoute = publicConfiguration.routingUrl.replace(/\/+$/, '');
    const url = `${baseRoute}/route/v1/driving/${encodeURIComponent(origin.lng)},${encodeURIComponent(origin.lat)};${encodeURIComponent(l.longitude)},${encodeURIComponent(l.latitude)}?overview=full&geometries=geojson`;
    const resp = await fetch(url);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    if (data.code && data.code !== 'Ok') {
      const routeMessages = {
        NoRoute: 'El proveedor no encontró una ruta transitable entre tu ubicación y el incidente.',
        NoSegment: 'La ubicación está demasiado lejos de una vía transitable para calcular la ruta.',
        InvalidQuery: 'El proveedor rechazó las coordenadas enviadas para calcular la ruta.'
      };
      throw new Error(routeMessages[data.code] || `El proveedor de rutas respondió: ${data.code}.`);
    }

    const providerRoute = data.routes?.[0] ?? data;
    const coordinates = providerRoute.geometry?.coordinates ?? providerRoute.coordinates;
    if (!Array.isArray(coordinates) || coordinates.length < 2) {
      throw new Error('El proveedor no devolvió una ruta válida.');
    }
    if (!Number.isFinite(Number(providerRoute.distance)) || !Number.isFinite(Number(providerRoute.duration))) {
      throw new Error('El proveedor devolvió una ruta sin distancia o duración válidas.');
    }

    const routeResult = {
      coordinates,
      distance: providerRoute.distance,
      duration: providerRoute.duration
    };
    calculatedRoutes.set(routeKey, routeResult);
    if (requestId !== routeRequestId) return;
    drawSavedRoute(routeResult, true);
  } catch (error) {
    console.warn('No se pudo calcular la ruta al incidente:', error);
    if (requestId !== routeRequestId) return;
    const message = error instanceof TypeError
      ? 'No se pudo conectar con el proveedor de rutas. Comprueba la conexión e inténtalo de nuevo.'
      : error.message === 'HTTP 429'
        ? 'El proveedor de rutas está temporalmente limitado. Inténtalo de nuevo más tarde.'
        : error.message.startsWith('HTTP 5')
          ? 'El proveedor de rutas no está disponible temporalmente.'
          : error.message || 'No se pudo calcular la ruta.';
    if (routeStatus) routeStatus.textContent = message;
    if (retryRouteButton) retryRouteButton.hidden = false;
  }
}

function retrySelectedRoute() {
  if (!selectedCall) return;
  const routeKey = `${selectedCall.id}:${selectedCall.latitude}:${selectedCall.longitude}`;
  requestedRoutes.delete(routeKey);
  drawRouteToIncident(selectedCall);
}

function getRouteOrigin() {
  return new Promise(resolve => {
    if (!navigator.geolocation) { resolve(null); return; }
    navigator.geolocation.getCurrentPosition(
      pos => {
        currentOperatorLocation = {
          lat: pos.coords.latitude,
          lng: pos.coords.longitude,
          accuracy: pos.coords.accuracy
        };
        showLocationMarker(currentOperatorLocation);
        resolve(currentOperatorLocation);
      },
      () => resolve(null),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 120000 }
    );
  });
}

function showLocationMarker(location) {
  if (!map || !location) return;
  const latLng = [location.lat, location.lng];
  const accuracy = Math.max(location.accuracy || 100, 40);
  if (deviceMarker) map.removeLayer(deviceMarker);
  if (deviceAccuracy) map.removeLayer(deviceAccuracy);
  deviceAccuracy = L.circle(latLng, { radius: accuracy, color: '#4da3ff', fillColor: '#4da3ff', fillOpacity: 0.14, weight: 1.5 }).addTo(map);
  deviceMarker = L.circleMarker(latLng, { radius: 8, color: '#ffffff', weight: 3, fillColor: '#1683ff', fillOpacity: 1 }).addTo(map).bindPopup(`Tu ubicación aproximada<br>Precisión: ${Math.round(accuracy)} m`);
}

function updateMap(l) {
  routeRequestId++;
  if (incidentRoute && map) { map.removeLayer(incidentRoute); incidentRoute = null; }
  if (!l) {
    $('mapa-titulo').textContent =
      'Sin incidente seleccionado';

    $('mapa-direccion').textContent =
      'Selecciona una llamada para mostrar su ubicación.';
    if ($('ruta-estado')) $('ruta-estado').textContent = 'Selecciona un caso para calcular una ruta.';
    if ($('btn-reintentar-ruta')) $('btn-reintentar-ruta').hidden = true;

    return;
  }

  $('mapa-titulo').textContent =
    `Caso #${l.id} · ${l.priority || 'SIN PRIORIDAD'}`;

  $('mapa-direccion').textContent =
    l.address ||
    l.addressDetectada ||
    'Dirección por determinar';

  if (
    l.latitude == null ||
    l.longitude == null
  ) {
    return;
  }

  initializeMap();

  if (!map) return;

  map.setView(
    [l.latitude, l.longitude],
    16
  );

  if (incidentMarker) {
    map.removeLayer(incidentMarker);
  }

  incidentMarker =
    L.marker([
      l.latitude,
      l.longitude
    ])
      .addTo(map)
      .bindPopup(
        escapeHtml(
          l.address ||
          l.addressDetectada ||
          `Caso #${l.id}`
        )
      );

  drawRouteToIncident(l);

  setTimeout(
    () => map.invalidateSize(),
    50
  );
}

function changeView(viewName) {
  currentView = 'info';

  $('mapa-container')
    ?.classList.add('visible');

  $('info-container')
    ?.classList.remove('hidden-view');

  initializeMap();
  setTimeout(() => map?.invalidateSize(true), 60);
}


// ======================================================
// COLAS
// ======================================================

async function loadQueue() {
  if (!session) return;

  try {
    const routes = {
      pendientes:
        '/llamadas/pending',

      'en-curso':
        '/llamadas/in-progress',

      cerradas:
        '/llamadas/closed?limit=100'
    };

    const [
      p,
      pr,
      c
    ] = await Promise.all(
      Object.entries(routes)
        .map(
          async ([k, r]) => {
            const resp =
              await apiFetch(r);

            if (!resp.ok) {
              throw new Error(`HTTP ${resp.status}`);
            }

            return [
              k,
              await resp.json()
            ];
          }
        )
    );

    for (
      const [k, v] of [p, pr, c]
    ) {
      queueCache[k] = v;
    }

    notifyNewUrgencies([
      ...cacheColas.pendientes,
      ...cacheColas['en-curso'],
      ...cacheColas.cerradas
    ]);
    queuesInitialized = true;

    updateCounters();

    renderList();

    if (selectedCall) {
      const newValue =
        Object.values(queueCache)
          .flat()
          .find(
            x =>
              x.id ===
              selectedCall.id
          );

      selectedCall =
        newValue || null;

      renderDetails(
        selectedCall
      );

      if (!selectedCall) updateMap(null);
    }

  } catch (e) {
    if (e.message !== 'No autenticado') console.error('No se pudieron cargar las llamadas.', e);
  }
}

function updateCounters() {
  $('cont-pendientes').textContent =
    queueCache.pendientes.length;

  $('cont-en-curso').textContent =
    queueCache['en-curso'].length;

  $('cont-cerradas').textContent =
    queueCache.cerradas.length;
}

function currentList() {
  return queueCache[currentQueue] || [];
}

function priorityClass(p) {
  return String(
    p || 'VERDE'
  ).toUpperCase();
}


// ======================================================
// PALABRAS DESTACADAS
// ======================================================

function normalizeHighlightedWords(value) {
  if (Array.isArray(value)) {
    return value
      .filter(Boolean)
      .map(String);
  }

  if (value == null) {
    return [];
  }

  if (typeof value === 'string') {
    try {
      const parsed =
        JSON.parse(value);

      return Array.isArray(parsed)
        ? parsed
            .filter(Boolean)
            .map(String)
        : [];

    } catch (_) {
      return [];
    }
  }

  return [];
}

function institutionLabel(value) {
  return {
    carabineros: 'Carabineros',
    samu: 'SAMU',
    bomberos: 'Bomberos'
  }[String(value).toLowerCase()] || String(value);
}


// ======================================================
// TRANSCRIPCIÓN
// ======================================================

function cleanTranscription(value) {
  if (value == null) {
    return '';
  }

  if (typeof value === 'object') {
    return String(
      value.text ||
      value.transcript ||
      ''
    ).trim();
  }

  const s =
    String(value).trim();

  if (!s) {
    return '';
  }

  try {
    const obj =
      JSON.parse(s);

    if (
      obj &&
      typeof obj === 'object' &&
      typeof obj.text === 'string'
    ) {
      return obj.text.trim();
    }

    if (
      obj &&
      typeof obj === 'object' &&
      typeof obj.transcript === 'string'
    ) {
      return obj.transcript.trim();
    }

  } catch (_) {
    // No era JSON; usamos el texto directamente.
  }

  return s;
}

function truncateText(value, limit) {
  const text =
    cleanTranscription(value);

  return text.length > limit
    ? text.slice(0, limit) + '…'
    : text;
}


// ======================================================
// TÍTULO OPERATIVO
// ======================================================

function getOperationalTitle(l) {
  const words =
    normalizeHighlightedWords(
      l.highlightedWords
    ).map(
      x =>
        x.toLowerCase()
    );

  if (
    words.some(
      x =>
        x.includes('incendi') ||
        x.includes('fuego')
    )
  ) {
    return 'Incendio reportado';
  }

  if (
    words.some(
      x =>
        x.includes('accidente') ||
        x.includes('choque') ||
        x.includes('atropell')
    )
  ) {
    return 'Accidente reportado';
  }

  if (
    words.some(
      x =>
        x.includes('robo') ||
        x.includes('asalto')
    )
  ) {
    return 'Robo / asalto reportado';
  }

  if (
    words.some(
      x =>
        x.includes('paro') ||
        x.includes('infarto') ||
        x.includes('herido') ||
        x.includes('emergencia')
    )
  ) {
    return 'Emergencia reportada';
  }

  return 'Llamada de emergencia';
}


// ======================================================
// RENDERIZADO DE LLAMADAS
// ======================================================

function renderList() {
  const callListElement =
    $('lista-llamadas');

  const calls =
    currentList();

  if (!callListElement) return;

  if (!calls.length) {
    callListElement.innerHTML = `
      <p class="estado-vacio">
        ${
          currentQueue === 'pendientes'
            ? 'No hay llamadas sin asignar.'
            : currentQueue === 'en-curso'
              ? 'No hay casos en curso.'
              : 'Aún no hay casos cerrados.'
        }
      </p>
    `;

    return;
  }

  const searchTerm = $('busqueda-incidentes')?.value
    .trim()
    .toLocaleLowerCase('es-CL') || '';
  const filteredCalls = searchTerm
    ? calls.filter(call => {
        const searchIn = [
          call.id,
          call.priority,
          getOperationalTitle(call),
          call.address,
          call.addressDetectada,
          'Llamada en vivo'
        ].filter(Boolean).join(' ').toLocaleLowerCase('es-CL');
        return searchIn.includes(searchTerm);
      })
    : calls;

  if (!filteredCalls.length) {
    callListElement.innerHTML = '<p class="estado-vacio">No hay incidentes que coincidan con la búsqueda.</p>';
    return;
  }

  callListElement.innerHTML = filteredCalls
    .map(l => {

      const priority =
        priorityClass(
          l.priority
        );

      const address =
        l.address ||
        l.addressDetectada ||
        'Ubicación no identificada';

      const isSelected =
        selectedCall?.id ===
        l.id;
      const caseStatus = getCaseStatus(l);
      const incidentType = getOperationalTitle(l)
        .replace(/\s+reportad[oa]$/i, '');

      const priorityClass =
        priority === 'URGENTE'
          ? 'alta'
          : priority === 'ROJA'
            ? 'alta'
            : priority === 'MEDIA'
              ? 'media'
              : 'baja';

      const priorityLabel =
        priority === 'URGENTE'
          ? 'Urgente'
          : priority === 'ROJA'
            ? 'Roja'
            : priority === 'MEDIA'
              ? 'Media'
                : 'Verde';

      return `
        <div
          class="llamada-item ${isSelected ? 'seleccionada' : ''} prioridad-${priority.toLowerCase()} estado-${caseStatus.className}"
          data-id="${escapeHtml(l.id)}"
          role="button"
          tabindex="0"
          aria-label="Caso #${escapeHtml(l.id)}, ${escapeHtml(priority)}, ${escapeHtml(incidentType)}, ${escapeHtml(address)}, ${escapeHtml(caseStatus.label)}"
        >

          <div class="llamada-header">
            <span class="llamada-urgencia ${priorityClass}">${escapeHtml(priorityLabel)}</span>
            <span class="llamada-id">#${escapeHtml(l.id)}</span>
          </div>

          <div class="llamada-titulo">${escapeHtml(capitalizeFirstLetter(incidentType))}</div>
          <div class="llamada-ubicacion">
            <span class="llamada-ubicacion-label">Ubicación</span>
            <span class="ubicacion" title="${escapeHtml(address)}">${escapeHtml(address)}</span>
          </div>
          <div class="llamada-meta">
            <span>Llamada en vivo</span>
            <time>${escapeHtml(formatDate(l.createdAt))}</time>
          </div>
          <span class="llamada-estado ${caseStatus.className}">${escapeHtml(caseStatus.label)}</span>

        </div>
      `;
    })
    .join('');

  /*
   * Eventos de selección.
   * Se mantienen sobre .llamada-item para integrarse
   * con el CSS original de CODES.
   */
  c
    .querySelectorAll(
      '.llamada-item'
    )
    .forEach(item => {

      const abrir = () => {
        selectCall(
          Number(
            item.dataset.id
          )
        );
      };

      item.addEventListener(
        'click',
        abrir
      );

      item.addEventListener(
        'keydown',
        e => {

          if (
            e.key === 'Enter' ||
            e.key === ' '
          ) {
            e.preventDefault();
            abrir();
          }

        }
      );
    });
}

function getCaseStatus(call) {
  if (call.closureDate || currentQueue === 'cerradas') {
    return { label: 'Cerrado', className: 'cerrado' };
  }
  if (call.assigned || currentQueue === 'en-curso') {
    return { label: 'En curso', className: 'en-curso' };
  }
  return { label: 'Sin asignar', className: 'sin-asignar' };
}

function formatDate(iso) {
  if (!iso) {
    return '';
  }

  const d =
    new Date(iso);

  if (isNaN(d)) {
    return String(iso);
  }

  return d.toLocaleTimeString(
    'es-CL',
    {
      hour12: false
    }
  );
}


// ======================================================
// SELECCIÓN Y DETALLE
// ======================================================

function selectCall(id) {
  selectedCall =
    Object.values(queueCache)
      .flat()
      .find(
        x =>
          x.id === id
      ) || null;

  renderList();

  renderDetails(
    selectedCall
  );

  updateMap(
    selectedCall
  );
}

function generateLocalSummary(l) {
  const words =
    normalizeHighlightedWords(
      l.highlightedWords
    ).map(
      x =>
        String(x).toLowerCase()
    );

  let type =
    'Emergencia reportada';

  if (
    words.some(
      x =>
        x.includes('incendi') ||
        x.includes('fuego')
    )
  ) {
    type =
      'Incendio reportado';

  } else if (
    words.some(
      x =>
        x.includes('accidente') ||
        x.includes('choque') ||
        x.includes('atropell')
    )
  ) {
    type =
      'Accidente reportado';

  } else if (
    words.some(
      x =>
        x.includes('robo') ||
        x.includes('asalto')
    )
  ) {
    type =
      'Robo o asalto reportado';
  }

  const address =
    l.address ||
    l.addressDetectada
      ? ` en ${
          l.address ||
          l.addressDetectada
        }`
      : '';

  return `${type}${address}. Revisa la transcripción completa para el detalle de la llamada.`;
}

function renderDetails(l) {
  const c = $('info-container');
  if (!c) return;
  if (!l) {
    c.innerHTML = '<p class="estado-vacio">Selecciona una llamada para consultar sus detalles.</p>';
    return;
  }

  const priority = priorityClass(l.priority);
  const priorityCssClass = ({
    URGENTE: 'urgente',
    ROJA: 'roja',
    MEDIA: 'media',
    VERDE: 'verde'
  })[priority] || 'verde';
  const canAssign = currentQueue === 'pendientes' && !readOnlyPreview;
  const canClose = currentQueue === 'en-curso' && !readOnlyPreview;
  const canAddInformation = currentQueue === 'en-curso' &&
    l.closureDate == null &&
    !readOnlyPreview &&
    (['supervisor', 'administrator'].includes(session?.role) ||
      (session?.role === 'operator' && l.assignedOperator === session.username));
  const summary = l.operationalSummary || generateLocalSummary(l);
  const transcription = cleanTranscription(l.transcription);
  const updates = Array.isArray(l.informationUpdates) ? l.informationUpdates : [];
  const hasConfirmedLocation = l.latitude != null &&
    l.longitude != null &&
    Number.isFinite(Number(l.latitude)) &&
    Number.isFinite(Number(l.longitude)) &&
    Math.abs(Number(l.latitude)) <= 90 &&
    Math.abs(Number(l.longitude)) <= 180;
  const words = normalizeHighlightedWords(l.highlightedWords);
  const reasons = normalizeHighlightedWords(l.classificationReasons);
  const institutions = normalizeHighlightedWords(l.suggestedInstitutions);
  const recommendations = Array.isArray(l.institutionRecommendations)
    ? l.institutionRecommendations
    : [];
  const address = l.address || l.addressDetectada || 'Ubicación no identificada';
  const caseStatus = getCaseStatus(l);
  const emergencyType = getOperationalTitle(l).replace(/\s+reportad[oa]$/i, '');

  c.innerHTML = `
    <article class="detalle-contenido">
      <header class="detalle-cabecera">
        <div class="detalle-caso-id">
          <strong>Caso #${escapeHtml(l.id)}</strong>
          <span>${escapeHtml(formatDate(l.createdAt))}</span>
          ${l.assignedOperator ? `<span>Asignado a ${escapeHtml(l.assignedOperator)}</span>` : ''}
        </div>
        <div class="detalle-cabecera-badges">
          <span class="detalle-prioridad ${priorityCssClass}">
            <span class="detalle-prioridad-punto" aria-hidden="true"></span>
            ${escapeHtml(capitalizeFirstLetter(priority.toLocaleLowerCase('es-CL')))}
          </span>
          <span class="detalle-tipo-emergencia">${escapeHtml(capitalizeFirstLetter(emergencyType))}</span>
        </div>
      </header>

      <section class="detalle-ubicacion" aria-label="Ubicación del incidente">
        <div>
          <span class="detalle-etiqueta">Ubicación</span>
          <h1>${escapeHtml(address)}</h1>
        </div>
        <span class="detalle-ubicacion-estado ${hasConfirmedLocation ? 'confirmada' : 'no-identificada'}">
          ${hasConfirmedLocation ? 'Confirmada' : 'No identificada'}
        </span>
      </section>

      <section class="detalle-estado-linea">
        <span class="detalle-etiqueta">Estado</span>
        <span class="detalle-estado ${caseStatus.className}">${escapeHtml(caseStatus.label)}</span>
      </section>

      <section class="detalle-resumen">
        <h2 class="detalle-seccion-titulo">Resumen operativo</h2>
        <p>${escapeHtml(capitalizeFirstLetter(summary))}</p>
      </section>

      <section class="detalle-clasificacion" aria-label="Clasificación automática">
        <div class="detalle-evidencia">
          <h2 class="detalle-seccion-titulo">Motivos detectados</h2>
          <div class="detalle-evidencia-valores">
            ${words.length
              ? words.map(p => `<span class="detalle-chip">${escapeHtml(capitalizeFirstLetter(p))}</span>`).join('')
              : '<span class="detalle-vacio">Sin motivos detectados</span>'}
          </div>
          ${reasons.length
            ? `<p class="detalle-justificacion">${escapeHtml(reasons.map(capitalizeFirstLetter).join(' · '))}</p>`
            : ''}
        </div>
        <div class="detalle-evidencia">
          <h2 class="detalle-seccion-titulo">Instituciones sugeridas <span class="detalle-sugerencia-label">Revisar antes de activar</span></h2>
          <div class="detalle-evidencia-valores">
            ${recommendations.length
              ? recommendations.map(r => {
                  const role = r.role === 'PRIMARY' ? 'Principal' : 'Secundaria';
                  const severity = ['URGENTE', 'ROJA', 'MEDIA', 'VERDE'].includes(r.severity)
                    ? r.severity
                    : 'VERDE';
                  return `<article class="detalle-recomendacion">
                    <div class="detalle-recomendacion-cabecera">
                      <strong>${escapeHtml(institutionLabel(r.institution))}</strong>
                      <span>${role}</span>
                      <span class="detalle-recomendacion-prioridad ${severity.toLowerCase()}">${escapeHtml(severity)}</span>
                    </div>
                    <p>${escapeHtml(r.reason || 'Recomendación automática; validar con el operador.')}</p>
                  </article>`;
                }).join('')
              : institutions.length
                ? institutions.map(i => `<span class="detalle-chip">${escapeHtml(capitalizeFirstLetter(institutionLabel(i)))}</span>`).join('')
              : '<span class="detalle-vacio">Sin institución sugerida</span>'}
          </div>
        </div>
      </section>

      <section class="detalle-relato">
        <div class="detalle-relato-cabecera">
          <h2 class="detalle-seccion-titulo">Relato de la llamada</h2>
          <span class="detalle-transcripcion-meta">Transcripción ASR · Llamada en vivo</span>
        </div>
        <p class="detalle-transcripcion">${escapeHtml(transcription || 'Transcripción no disponible.')}</p>
      </section>

      ${updates.length ? `
        <section class="detalle-actualizaciones" aria-label="Información complementaria">
          <h2 class="detalle-seccion-titulo">Información complementaria</h2>
          <ol class="detalle-actualizaciones-lista">
            ${updates.map(update => `
              <li>
                <p>${escapeHtml(update.information || '')}</p>
                <span>${escapeHtml(update.createdBy || 'Operador')} · ${escapeHtml(formatDate(update.createdAt))}</span>
              </li>
            `).join('')}
          </ol>
        </section>
      ` : ''}

      ${canAddInformation ? `
        <form class="detalle-actualizacion-form" id="form-informacion-complementaria">
          <label for="input-informacion-complementaria">Agregar información al caso</label>
          <textarea id="input-informacion-complementaria" maxlength="5000" required
            placeholder="Registra nuevos antecedentes recibidos. No se creará un caso nuevo."></textarea>
          <button class="btn-accion secundario" type="submit">Guardar información</button>
        </form>
      ` : ''}

      ${l.closureComment ? `
        <section class="detalle-resolucion">
          <span class="detalle-etiqueta">Resolución</span>
          <p>${escapeHtml(capitalizeFirstLetter(l.closureComment))}</p>
        </section>
      ` : ''}

      <div class="detalle-acciones">
        ${canAssign ? '<button class="btn-accion primario accion-principal" id="accion-asignar">Tomar caso</button>' : ''}
        ${canClose ? '<button class="btn-accion primario" id="accion-cerrar">Cerrar caso</button>' : ''}
      </div>
    </article>
  `;

  $('accion-asignar')?.addEventListener('click', () => assignCase(l.id));
  $('accion-cerrar')?.addEventListener('click', () => openCloseModal(l.id));
  $('form-informacion-complementaria')?.addEventListener('submit', event => {
    event.preventDefault();
    appendCaseInformation(l.id);
  });
}

function capitalizeFirstLetter(value) {
  const text = String(value ?? '').trim();
  return text ? text.charAt(0).toLocaleUpperCase('es-CL') + text.slice(1) : text;
}


// ======================================================
// GESTIÓN DE CASOS
// ======================================================

async function appendCaseInformation(id) {
  const input = $('input-informacion-complementaria');
  const information = String(input?.value || '').trim();
  if (!information) {
    showAlert('Escribe la información complementaria antes de guardarla.', 'error');
    return;
  }

  try {
    const updateId = generateUpdateId();
    const response = await apiFetch(`/api/llamadas/${id}/updates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ updateId, information })
    });
    const data = await readResponse(response);
    if (!response.ok) {
      throw new Error(data.error || data.message || 'No se pudo actualizar el caso.');
    }

    for (const queue of Object.values(queueCache)) {
      const index = queue.findIndex(call => call.id === id);
      if (index >= 0) queue[index] = data;
    }
    selectedCall = data;
    renderList();
    renderDetails(data);
    showAlert('Información agregada al caso existente.', 'ok');
  } catch (error) {
    if (error.message !== 'No autenticado') {
      showAlert(error.message, 'error');
    }
  }
}

function generateUpdateId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
    const random = Math.floor(Math.random() * 16);
    return (character === 'x' ? random : (random & 0x3) | 0x8).toString(16);
  });
}

async function assignCase(id) {
  try {
    const r =
      await apiFetch(
        `/api/llamadas/${id}/assign`,
        {
          method: 'POST'
        }
      );

    const d =
      await readResponse(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo asignar'
      );
    }

    showAlert(
      (d.mensaje || d.message) ||
      'Caso asignado.',
      'ok'
    );

    recordEvent(
      (d.mensaje || d.message) ||
      `Caso #${id} asignado.`
    );

    await loadQueue();

    currentQueue =
      'en-curso';

    updateTabs();

    renderList();

    selectCall(id);

  } catch (e) {
    if (
      e.message !==
      'No autenticado'
    ) {
      showAlert(
        e.message,
        'error'
      );
    }
  }
}

function openCloseModal(id) {
  $('modal-titulo').textContent =
    `Cerrar caso #${id}`;

  $('modal-cuerpo').innerHTML = `
    <div class="campo">

      <label for="input-comentario">
        Comentario de resolución
      </label>

      <textarea
        id="input-comentario"
        placeholder="Ej.: Se derivó patrulla al lugar, situación controlada."
      ></textarea>

    </div>

    <button
      class="btn-accion primario"
      id="btn-confirmar-cerrar"
    >
      Confirmar cierre
    </button>
  `;

  $('modal').hidden = false;

  $('input-comentario').focus();

  $('btn-confirmar-cerrar').onclick =
    async () => {

      const comment =
        $('input-comentario')
          .value
          .trim();

      if (!comment) {
        return;
      }

      try {
        const r =
          await apiFetch(
            `/api/llamadas/${id}/close`,
            {
              method: 'POST',
              headers: {
                'Content-Type':
                  'application/json'
              },
              body:
                JSON.stringify({
                  comment
                })
            }
          );

        const d =
          await readResponse(r);

        if (!r.ok) {
          throw new Error(
            d.error ||
            'No se pudo cerrar'
          );
        }

        $('modal').hidden =
          true;

        showAlert(
          (d.mensaje || d.message) ||
          'Caso cerrado.',
          'ok'
        );

        recordEvent(
          (d.mensaje || d.message) ||
          `Caso #${id} cerrado.`
        );

        currentQueue =
          'cerradas';

        await loadQueue();

        updateTabs();

        selectCall(id);

      } catch (e) {
        if (
          e.message !==
          'No autenticado'
        ) {
          showAlert(
            e.message,
            'error'
          );
        }
      }
    };
}


// ======================================================
// MÉTRICAS
// ======================================================

async function updateMetrics() {
  if (!session) {
    return;
  }

  try {
    const r =
      await apiFetch(
        '/api/metrics'
      );

    if (!r.ok) {
      return;
    }

    const d =
      await r.json();

    $('m-pendientes').textContent =
      d.pendientes;

    $('m-en-curso').textContent =
      d.enProgreso;

    $('m-urgentes').textContent =
      d.urgentesActivas;

    $('m-tiempo').textContent =
      d.tiempoPromedioRespuestaSeg ==
      null
        ? '–'
        : `${Math.round(
            d.tiempoPromedioRespuestaSeg
          )}s`;

  } catch (e) {
    // Sin acción.
  }
}

function updateTabs() {
  document
    .querySelectorAll(
      '.tab'
    )
    .forEach(
      b =>
        b.classList.toggle(
          'activo',
          b.dataset.tab ===
            currentQueue
        )
    );
}


// ======================================================
// USUARIOS / ADMINISTRADOR
// ======================================================

async function openPreviewSession(institution) {
  try {
    const r = await apiFetch(
      `/api/admin/preview-session/${institution}`,
      { method: 'POST' }
    );

    const d = await readResponse(r);

    if (!r.ok) {
      throw new Error(d.error || 'No se pudo abrir la vista previa.');
    }

    const params = new URLSearchParams({
      previewToken: d.token,
      previewUsername: d.username,
      previewInstitution: d.institution,
      previewExpira: String(d.expiresInMinutes)
    });

    const ventana = window.open(
      `${location.origin}${location.pathname}?${params.toString()}`,
      '_blank',
      'noopener'
    );

    if (!ventana) {
      showAlert(
        'El navegador bloqueó la pestaña nueva. Habilita las ventanas emergentes para este sitio e inténtalo de nuevo.',
        'error'
      );
      return;
    }

    showAlert(
      `Vista previa de ${institutionLabel(institution)} abierta en una pestaña nueva (expira en ${d.expiresInMinutes} min, solo lectura).`,
      'ok'
    );
  } catch (e) {
    if (e.message !== 'No autenticado') {
      showAlert(e.message, 'error');
    }
  }
}

function institutionLabel(institution) {
  return (
    { bomberos: 'Bomberos', carabineros: 'Carabineros', samu: 'SAMU' }[
      institution
    ] || institution
  );
}

async function loadUsers() {
  if (
    session?.role !==
    'administrator'
  ) {
    return;
  }

  try {
    const r =
      await apiFetch(
        '/api/users'
      );

    const d =
      await readResponse(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo cargar usuarios'
      );
    }

    renderUsers(d);

  } catch (e) {
    if (
      e.message !==
      'No autenticado'
    ) {
      $('lista-usuarios').innerHTML =
        `
          <p class="estado-vacio">
            No se pudieron cargar los usuarios.
          </p>
        `;
    }
  }
}

function renderUsers(usuarios) {
  $('usuarios-total').textContent =
    `${usuarios.length} ${usuarios.length === 1 ? 'usuario' : 'usuarios'}`;

  if (!usuarios.length) {
    $('lista-usuarios').innerHTML =
      `
        <p class="estado-vacio">
          No hay usuarios registrados.
        </p>
      `;

    return;
  }

  $('lista-usuarios').innerHTML =
    usuarios
      .map(
        u => {
          const status = u.active
            ? { label: 'Activo', className: 'activo' }
            : { label: 'Inactivo', className: 'inactivo' };
          const institution = u.institution
            ? institutionLabel(u.institution)
            : 'Administración general';
          const isCurrentUser = u.username === session.username;

          return `
            <article class="usuario-card">
              <div class="usuario-identidad">
                <span class="usuario-avatar" aria-hidden="true">${escapeHtml(
                  String(u.fullName || u.username).trim().charAt(0).toLocaleUpperCase('es-CL') || 'U'
                )}</span>
                <div class="usuario-identidad-texto">
                  <h3 class="nombre">${escapeHtml(u.fullName || u.username)}</h3>
                  <p class="usuario">@${escapeHtml(u.username)}</p>
                  ${u.email ? `<p class="usuario-email">${escapeHtml(u.email)}</p>` : ''}
                </div>
              </div>

              <div class="usuario-etiquetas" aria-label="Rol, institución y estado">
                <span class="usuario-etiqueta rol">${escapeHtml(roleLabel(u.role))}</span>
                <span class="usuario-etiqueta institucion">${escapeHtml(institution)}</span>
                <span class="usuario-etiqueta estado ${status.className}">
                  <span aria-hidden="true"></span>${status.label}
                </span>
                ${u.testAccount
                  ? '<span class="usuario-etiqueta prueba" title="Cuenta de solo lectura usada para vista previa">Cuenta de vista previa</span>'
                  : ''}
                ${isCurrentUser ? '<span class="usuario-etiqueta actual">Tu cuenta</span>' : ''}
              </div>

              <div class="acciones" aria-label="Acciones de usuario">
                ${u.active
                  ? `<button class="btn-accion peligro" data-accion="desactivar" data-id="${u.id}" ${isCurrentUser ? 'disabled title="No puedes desactivar tu propia cuenta"' : ''}>Desactivar cuenta</button>`
                  : `<button class="btn-accion primario" data-accion="activar" data-id="${u.id}">Activar cuenta</button>`}
                ${u.testAccount
                  ? ''
                  : `<button class="btn-accion secundario" data-accion="reset-clave" data-id="${u.id}" title="Genera una contraseña temporal para este usuario">Restablecer contraseña</button>`}
              </div>
            </article>
          `;
        }
      )
      .join('');

  $('lista-usuarios')
    .querySelectorAll(
      '[data-accion]'
    )
    .forEach(
      b =>
        b.addEventListener(
          'click',
          () =>
            b.dataset.accion === 'reset-clave'
              ? resetUserPassword(Number(b.dataset.id))
              : changeUserStatus(
              Number(
                b.dataset.id
              ),
              b.dataset.accion
            )
        )
    );
}

async function resetUserPassword(id) {
  if (!confirm('¿Generar una contraseña temporal para este usuario? La actual dejará de funcionar y sus sesiones abiertas se cerrarán.')) return;
  try {
    const r = await apiFetch(`/api/users/${id}/reset-password`, { method: 'PATCH' });
    const d = await readResponse(r);
    if (!r.ok) throw new Error(d.error || 'No se pudo restablecer la contraseña');
    // prompt() permite seleccionar y copiar la clave temporal; solo se muestra una vez.
    window.prompt(`${d.message}\n\nContraseña temporal (cópiala ahora):`, d.temporaryPassword);
    recordEvent('Se generó una contraseña temporal para un usuario.');
  } catch (e) {
    if (e.message !== 'No autenticado') showAlert(e.message, 'error');
  }
}

async function changeUserStatus(
  id,
  accion
) {
  try {
    const r =
      await apiFetch(
        `/api/users/${id}/${accion === 'desactivar' ? 'disable' : 'enable'}`,
        {
          method: 'PATCH'
        }
      );

    const d =
      await readResponse(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo actualizar'
      );
    }

    showAlert(
      `${d.username}: ${
        d.active
          ? 'activado'
          : 'desactivado'
      }.`,
      'ok'
    );

    recordEvent(
      `${d.username} fue ${
        d.active
          ? 'activado'
          : 'desactivado'
      }.`
    );

    await loadUsers();

  } catch (e) {
    if (
      e.message !==
      'No autenticado'
    ) {
      showAlert(
        e.message,
        'error'
      );
    }
  }
}

function changeAdminTab(tab) {
  if (tab === 'usuarios' && session?.role !== 'administrator') {
    currentAdminTab = 'incidentes';
    return;
  }

  currentAdminTab =
    tab;

  document
    .querySelectorAll(
      '.admin-tab'
    )
    .forEach(
      b =>
        b.classList.toggle(
          'activo',
          b.dataset.adminTab ===
            tab
        )
    );

  const isUsersView =
    tab === 'usuarios';

  $('panel-cola').style.display =
    isUsersView
      ? 'none'
      : 'flex';

  $('panel-detalle').style.display =
    'flex';

  $('gestion-usuarios')
    .classList.toggle(
      'visible',
      isUsersView
    );

  $('detalle-menu').style.display =
    isUsersView
      ? 'none'
      : 'flex';

  $('info-container')
    .classList.toggle(
      'hidden-view',
      isUsersView
    );

  $('mapa-container')
    .classList.toggle(
      'visible',
      false
    );

  if (isUsersView) {
    loadUsers();
  }
}

function updateAll() {
  if (!session) {
    return;
  }

  updateUserUi();

  if (
    session.role !==
    'administrator'
  ) {
    changeAdminTab(
      'incidentes'
    );
  }

  renderList();

  updateCounters();

  renderDetails(
    selectedCall
  );
}


// ======================================================
// LLAMADA EN VIVO / TRANSCRIPCIÓN EN NUBE
// ======================================================

let live = {
  stream: null,
  mediaRecorder: null,
  chunks: [],
  transcript: '',
  startedAt: null,
  timer: null,
  stopping: false,
  operatorLocation: {
    lat: null,
    lng: null
  }
};

function setLiveStatus(
  text,
  status = ''
) {
  const el =
    $('live-status');

  if (!el) return;

  el.textContent =
    text;

  el.className =
    `live-status ${status}`;

  // El estado de reposo ("Listo") no aporta nada: solo se muestra cuando hay algo que informar.
  el.hidden = text === 'Listo';
}

function setLiveTranscript(
  text
) {
  const el =
    $('live-transcripcion');

  if (!el) return;

  el.textContent =
    text ||
    'Escuchando…';

  el.scrollTop =
    el.scrollHeight;
}

function updateLiveTimer() {
  if (!live.startedAt) {
    return;
  }

  const segundos =
    Math.floor(
      (Date.now() -
        live.startedAt) /
        1000
    );

  const mm =
    String(
      Math.floor(
        segundos / 60
      )
    ).padStart(2, '0');

  const ss =
    String(
      segundos % 60
    ).padStart(2, '0');

  $('live-tiempo').textContent =
    `${mm}:${ss}`;
}

function openLive() {
  if (!session) {
    return showAlert(
      'Debes iniciar sesión primero.',
      'error'
    );
  }

  $('modal-live').hidden =
    false;

  setLiveStatus(
    'Listo'
  );

  $('live-provider').textContent =
    publicConfiguration.transcriptionConfigured
      ? 'Groq Whisper configurado'
      : 'Falta configurar Groq';

  setLiveTranscript(
    'Presiona “Iniciar micrófono” para comenzar…'
  );

  $('live-iniciar').disabled =
    false;

  $('live-detener').disabled =
    true;

  $('live-nota').textContent =
    publicConfiguration.transcriptionConfigured
      ? 'La transcripción se procesa en el servidor con Groq Whisper al guardar la grabación.'
      : 'Falta configurar GROQ_API_KEY en el servidor para crear llamadas desde audio.';
}

function closeLiveCall() {
  if (live.startedAt || live.stopping) {
    showAlert(
      'Espera a que termine la grabación o el guardado antes de cerrar esta ventana.',
      'error'
    );

    return;
  }

  $('modal-live').hidden =
    true;
}

function getOperatorLocation() {
  return new Promise(
    resolve => {

      if (
        !navigator.geolocation
      ) {
        resolve(null);
        return;
      }

      navigator.geolocation.getCurrentPosition(
        pos =>
          resolve({
            lat:
              pos.coords.latitude,

            lng:
              pos.coords.longitude,

            accuracy:
              pos.coords.accuracy
          }),

        () =>
          resolve(null),

        {
          enableHighAccuracy:
            false,

          timeout:
            3500,

          maximumAge:
            120000
        }
      );
    }
  );
}

async function startLiveCall() {
  if (
    live.startedAt ||
    !session
  ) {
    return;
  }

  live.stopping =
    false;

  live.transcript =
    '';

  live.chunks =
    [];

  live.operatorLocation = {
    lat: null,
    lng: null
  };

  setLiveStatus(
    'Conectando…'
  );

  $('live-iniciar').disabled =
    true;

  $('live-detener').disabled =
    false;

  try {
    if (!window.MediaRecorder || !navigator.mediaDevices?.getUserMedia) {
      throw new Error('Este navegador no permite grabar audio.');
    }

    live.stream =
      await navigator.mediaDevices.getUserMedia(
        {
          audio: {
            channelCount: 1,
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
          }
        }
      );

    const recordingMimeType =
      MediaRecorder.isTypeSupported(
        'audio/webm;codecs=opus'
      )
        ? 'audio/webm;codecs=opus'
        : 'audio/webm';

    live.mediaRecorder =
      new MediaRecorder(
        live.stream,
        {
          mimeType:
            recordingMimeType
        }
      );

    live.mediaRecorder.ondataavailable =
      e => {

        if (
          e.data.size
        ) {
          live.chunks.push(
            e.data
          );
        }
      };

    live.mediaRecorder.start(
      1000
    );

    live.startedAt =
      Date.now();

    live.timer =
      setInterval(
        updateLiveTimer,
        250
      );

    /*
     * La ubicación del operador es contexto opcional para el mapa; la
     * grabación ya está activa y no espera el permiso de geolocalización.
     */
    live.operatorLocation =
      await getOperatorLocation();

    $('live-nota').textContent =
      live.operatorLocation
        ? 'Micrófono conectado · ubicación del equipo disponible como contexto del mapa.'
        : 'Micrófono conectado · sin ubicación del equipo; la búsqueda del mapa seguirá funcionando.';

    setLiveStatus(
      'GRABANDO',
      'activo'
    );

    $('live-provider').textContent =
      'Audio se transcribirá con Groq al guardar';

    setLiveTranscript(
      'Grabando audio… la transcripción aparecerá al detener y guardar.'
    );

    recordEvent(
      'Grabación de llamada iniciada.',
      'admin'
    );

  } catch (err) {

    stopLiveCall(
      false
    );

    setLiveStatus(
      'No disponible',
      'error'
    );

    $('live-iniciar').disabled =
      false;

    $('live-detener').disabled =
      true;

    const message =
      err?.name === 'NotAllowedError'
        ? 'El navegador bloqueó el micrófono.'
        : err instanceof Error
          ? err.message
          : 'No se pudo iniciar la grabación.';

    $('live-nota').textContent =
      message;

    showAlert(
      message,
      'error'
    );
  }
}

async function stopLiveCall(
  guardar = true
) {
  if (
    live.stopping
  ) {
    return;
  }

  live.stopping =
    true;

  let saveFailed =
    false;

  $('live-iniciar').disabled =
    guardar;

  $('live-detener').disabled =
    true;

  clearInterval(
    live.timer
  );

  live.timer =
    null;

  let recordingPromise =
    Promise.resolve();

  if (
    live.mediaRecorder &&
    live.mediaRecorder.state !==
      'inactive'
  ) {

    recordingPromise =
      new Promise(
        resolve => {

          const recorder =
            live.mediaRecorder;

          const anterior =
            recorder.onstop;

          recorder.onstop =
            () => {

              try {
                anterior?.();
              } finally {
                resolve();
              }

            };

          recorder.stop();
        }
      );
  }

  if (
    live.stream
  ) {
    live.stream
      .getTracks()
      .forEach(
        t => t.stop()
      );
  }

  await recordingPromise;

  const transcript =
    cleanTranscription(
      live.transcript
    );

  live.startedAt =
    null;

  $('live-provider').textContent =
    guardar
      ? 'Enviando audio a Groq Whisper…'
      : 'Grabación detenida';

  setLiveStatus(
    guardar
      ? 'Procesando caso…'
      : 'Detenido'
  );

  if (!guardar) {

    live = {
      ...live,

      stream: null,
      mediaRecorder: null,
      chunks: [],
      transcript: '',
      stopping: false,

      operatorLocation: {
        lat: null,
        lng: null
      }
    };

    return;
  }

  try {

    const form =
      new FormData();

    const requestData =
      new Blob(
        [
          JSON.stringify({
            transcription: '',

            latitudOperador:
              live
                .operatorLocation
                ?.lat ??
              null,

            longitudOperador:
              live
                .operatorLocation
                ?.lng ??
              null
          })
        ],
        {
          type:
            'application/json'
        }
      );

    form.append(
      'datos',
      requestData,
      'datos.json'
    );

    if (
      live.chunks.length
    ) {

      const audioType =
        (live.chunks.find(c => c && c.type)?.type || 'audio/webm')
          .split(';', 1)[0]
          .toLowerCase();

      const extension =
        audioType === 'audio/ogg' ? 'ogg' :
        audioType === 'audio/wav' || audioType === 'audio/x-wav' ? 'wav' :
        audioType === 'audio/mpeg' ? 'mp3' :
        audioType === 'audio/mp4' ? 'm4a' :
        audioType === 'audio/aac' ? 'aac' :
        'webm';

      const blob =
        new Blob(
          live.chunks,
          { type: audioType }
        );

      if (blob.size > 0) {
        form.append(
          'audio',
          blob,
          `llamada-${Date.now()}.${extension}`
        );
      }
    }

    const attachedAudio = form.get('audio');
    if (!(attachedAudio instanceof Blob) || attachedAudio.size === 0) {
      throw new Error('No se pudo preparar el audio. Inicia el micrófono y vuelve a grabar.');
    }
    if (attachedAudio.size > 25 * 1024 * 1024) {
      throw new Error('La grabación supera el límite de 25 MB de Groq. Detén antes y vuelve a intentarlo.');
    }

    const r =
      await apiFetch(
        '/api/llamadas/live',
        {
          method: 'POST',
          body: form
        }
      );

    const d =
      await readResponse(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo guardar la llamada'
      );
    }

    setLiveStatus(
      'Caso creado',
      'activo'
    );

    $('live-provider').textContent =
      `Caso #${d.id} · ${
        d.priority ||
        'clasificando'
      }`;

    showAlert(
      `Llamada en vivo convertida en caso #${d.id} · ${d.priority}`,
      'ok'
    );

    recordEvent(
      `Llamada en vivo guardada como caso #${d.id} (${d.priority}).`,
      'admin'
    );

    selectedCall =
      d;

    currentQueue =
      'pendientes';

    await loadQueue();

    updateTabs();

    selectCall(
      d.id
    );

    setTimeout(
      () => {

        $('modal-live').hidden =
          true;

        $('live-iniciar').disabled =
          false;

        $('live-detener').textContent =
          'Detener y guardar';

        live.stopping =
          false;

      },
      900
    );

  } catch (e) {

    saveFailed =
      true;

    setLiveStatus(
      'Error al guardar',
      'error'
    );

    $('live-provider').textContent =
      'Audio conservado para reintentar';

    showAlert(
      e.message ||
      'No se pudo guardar la llamada.',
      'error'
    );

    setLiveTranscript(
      transcript || 'Grabación lista. Puedes reintentar el envío.'
    );

    $('live-detener').textContent =
      'Reintentar guardado';

    $('live-detener').disabled =
      false;
    $('live-iniciar').disabled =
      false;

    live.stopping =
      false;
  }

  live = {
    ...live,

    stream: null,
    mediaRecorder: null,
    chunks: saveFailed ? live.chunks : [],
    transcript: saveFailed ? live.transcript : '',
    stopping: false,

    operatorLocation: saveFailed
      ? live.operatorLocation
      : { lat: null, lng: null }
  };
}


// ======================================================
// EVENTOS UI
// ======================================================

// script.js se carga dinámicamente (tras Leaflet): DOMContentLoaded puede haber pasado ya.
const onDomReady = (fn) => document.readyState === 'loading'
  ? document.addEventListener('DOMContentLoaded', fn)
  : fn();
onDomReady(
  () => {

    initializeTheme();
    initializeCaptcha();

    document
      .querySelectorAll(
        '.auth-tab'
      )
      .forEach(
        tab =>
          tab.addEventListener(
            'click',
            () => {

              document
                .querySelectorAll(
                  '.auth-tab'
                )
                .forEach(
                  t =>
                    t.classList.toggle(
                      'activo',
                      t === tab
                    )
                );

              showAuthView(tab.dataset.tab);

              $('login-error').hidden =
                true;

              $('registro-error').hidden =
                true;

              $('registro-success').hidden =
                true;

              if (tab.dataset.tab === 'registro') {
                renderCaptcha('register');
              }
            }
          )
      );

    $('form-login')
      ?.addEventListener(
        'submit',
        login
      );

    $('form-registro')
      ?.addEventListener(
        'submit',
        registerAccount
      );

    $('btn-olvido-password')?.addEventListener('click', () => showAuthView('recuperar'));
    $('btn-volver-login')?.addEventListener('click', () => showAuthView('login'));
    $('form-recuperar')?.addEventListener('submit', requestPasswordRecovery);
    $('form-reset')?.addEventListener('submit', resetPassword);
    $('btn-reset-login')?.addEventListener('click', () => { history.replaceState({}, '', location.pathname); showAuthView('login'); });
    if (new URLSearchParams(location.search).has('resetToken')) showAuthView('reset');


    $('btn-salir')
      ?.addEventListener(
        'click',
        logout
      );

    $('btn-llamada-en-vivo')
      ?.addEventListener(
        'click',
        openLive
      );

    $('live-iniciar')
      ?.addEventListener(
        'click',
        startLiveCall
      );

    $('live-detener')
      ?.addEventListener(
        'click',
        () =>
          stopLiveCall(true)
      );

    $('live-cerrar')
      ?.addEventListener(
        'click',
        closeLiveCall
      );

    $('modal-live')
      ?.addEventListener(
        'click',
        e => {

          if (
            e.target ===
            $('modal-live')
          ) {
            closeLiveCall();
          }

        }
      );

    $('modal-cerrar')
      ?.addEventListener(
        'click',
        () =>
          $('modal').hidden =
            true
      );

    $('modal')
      ?.addEventListener(
        'click',
        e => {

          if (
            e.target ===
            $('modal')
          ) {
            $('modal').hidden =
              true;
          }

        }
      );

    document
      .querySelectorAll(
        '.tab'
      )
      .forEach(
        b =>
          b.addEventListener(
            'click',
            () => {

              currentQueue =
                b.dataset.tab;

              updateTabs();

              renderList();

              selectedCall =
                null;

              renderDetails(
                null
              );
            }
          )
      );

    $('busqueda-incidentes')?.addEventListener('input', renderList);

    document
      .querySelectorAll(
        '.admin-tab'
      )
      .forEach(
        b =>
          b.addEventListener(
            'click',
            () =>
              changeAdminTab(
                b.dataset.adminTab
              )
          )
      );

    document
      .querySelectorAll(
        '[data-preview-institucion]'
      )
      .forEach(
        b =>
          b.addEventListener(
            'click',
            () =>
              openPreviewSession(
                b.dataset.previewInstitucion
              )
          )
      );

    window.addEventListener(
      'resize',
      () => {
        setTimeout(() => map?.invalidateSize(true), 100);
      }
    );

    renderFeed();

    const urlParams = new URLSearchParams(location.search);
    const previewToken = urlParams.get('previewToken');

    if (previewToken) {
      // Pestaña abierta desde "Vista previa de operador": entra
      // directamente con el token de solo lectura, sin pasar por el
      // formulario de login.
      readOnlyPreview = true;

      session = {
        token: previewToken,
        username: urlParams.get('previewUsername') || 'preview',
        role: 'operator',
        expiresInMinutes:
          Number(urlParams.get('previewExpira')) || 20
      };

      const previewInstitution =
        urlParams.get('previewInstitution') || '';

      if ($('banner-vista-previa')) {
        $('banner-vista-previa').hidden = false;
      }

      if ($('banner-vista-previa-institucion')) {
        $('banner-vista-previa-institucion').textContent =
          institutionLabel(previewInstitution);
      }

      if ($('btn-llamada-en-vivo')) {
        $('btn-llamada-en-vivo').hidden = true;
      }

      // Saca el token de la URL para que no quede en el historial ni se
      // comparta por accidente al copiar el link de la pestaña.
      history.replaceState({}, '', location.pathname);

      hideAuthScreen();
      enterConsole();
    } else if ($('pantalla-auth')) {
      $('pantalla-auth').hidden =
        false;
    }
  }
);


// ======================================================
// ACTUALIZACIÓN AUTOMÁTICA
// ======================================================

setInterval(
  async () => {

    if (!session) {
      return;
    }

    await loadQueue();

    await updateMetrics();

    if (
      session.role ===
        'administrator' &&
      currentAdminTab ===
        'usuarios'
    ) {
      await loadUsers();
    }

  },
  REFRESH_INTERVAL_MS
);
document.getElementById('btn-cambiar-clave')?.addEventListener('click', changePassword);

/* ===== MENÚ DE USUARIO (desplegable en la esquina superior derecha) ===== */
(function initializeUserMenu() {
  const container = document.getElementById('menu-usuario');
  const menuButton = document.getElementById('btn-menu-usuario');
  const panel = document.getElementById('dropdown-usuario');
  if (!container || !menuButton || !panel) return;

  function closeMenu() {
    panel.hidden = true;
    menuButton.setAttribute('aria-expanded', 'false');
  }

  function openMenu() {
    panel.hidden = false;
    menuButton.setAttribute('aria-expanded', 'true');
  }

  menuButton.addEventListener('click', (event) => {
    event.stopPropagation();
    if (panel.hidden) {
      openMenu();
    } else {
      closeMenu();
    }
  });

  document.addEventListener('click', (event) => {
    if (!panel.hidden && !container.contains(event.target)) {
      closeMenu();
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeMenu();
  });

  // Cerrar el menú al usar cualquier acción dentro de él.
  panel.querySelectorAll('button').forEach((btn) => {
    btn.addEventListener('click', () => closeMenu());
  });
})();

/* ===== LATIDO DE PÁGINA (ver PageWatchdogService) =====
   Mientras esta pestaña siga abierta (haya o no sesión iniciada), avisamos
   al servidor cada pocos segundos. Si deja de recibir avisos, asume que
   cerraron la página y se apaga solo, liberando el puerto
   8000. Se manda con keepalive para que el último aviso salga incluso
   si el navegador ya está cerrando la pestaña. */
(function startPageHeartbeat() {
  const INTERVAL_MS = 5000;
  let heartbeatInProgress = false;

  function updateConnectionStatus(connected) {
    const indicator = $('indicador-estado');
    const statusText = $('estado-texto');
    if (!indicator || !statusText) return;

    indicator.className = `indicador ${connected ? 'online' : ''}`;
    statusText.textContent = connected ? 'Operativo' : 'Sin conexión';
  }

  async function sendHeartbeat() {
    if (heartbeatInProgress) return;
    heartbeatInProgress = true;
    try {
      const response = await fetch(`${API_URL}/heartbeat`, {
        method: 'POST',
        keepalive: true
      });
      updateConnectionStatus(response.ok);
    } catch {
      updateConnectionStatus(false);
    } finally {
      heartbeatInProgress = false;
    }
  }

  updateConnectionStatus(false);
  sendHeartbeat();
  setInterval(sendHeartbeat, INTERVAL_MS);
  // Al volver a la pestaña (o al despertar el PC) avisa de inmediato.
  document.addEventListener('visibilitychange', () => { if (!document.hidden) sendHeartbeat(); });
})();
