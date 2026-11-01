/* CODES · Frontend integrado con Spring Boot */
const API_URL = '/api';
const REFRESCO_MS = 8000;
let configuracionPublica = {
  mapProvider: 'Esri World Imagery',
  mapTilesUrl: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  mapConfigured: true,
  geocodingConfigured: true,
  transcriptionConfigured: false,
  routingUrl: 'https://router.project-osrm.org',
};
let capaBaseMapa = null;
let errorConfiguracionMapa = '';
let errorTeselasMapa = '';
let fallbackTeselasActivado = false;
let captchaRequerido = false;
const configuracionPublicaPromise = fetch(`${API_URL}/public-config`)
  .then(resp => {
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.json();
  })
  .then(config => {
    const tilesAnteriores = configuracionPublica.mapTilesUrl;
    configuracionPublica = { ...configuracionPublica, ...config };
    errorConfiguracionMapa = '';
    if (mapa && configuracionPublica.mapTilesUrl !== tilesAnteriores) {
      configurarCapaBaseMapa();
    }
    actualizarEstadoMapa();
    const notaAsr = $('live-nota');
    if (notaAsr) notaAsr.textContent = configuracionPublica.transcriptionConfigured
      ? 'El audio se enviará al servidor para transcribirse con Groq al guardar.'
      : 'Falta configurar GROQ_API_KEY en el servidor para habilitar la transcripción automática.';
  })
  .catch(error => {
    errorConfiguracionMapa = 'No se pudo cargar la configuración; se mantienen los servicios públicos predeterminados.';
    actualizarEstadoMapa();
    console.error('No se pudo cargar la configuración pública del mapa.', error);
  });

let sesion = null;
// true cuando la pestaña se abrió como "vista previa de operador" (admin
// mirando la interfaz de una institución con la cuenta sintética
// preview_<institucion>): oculta/bloquea acciones que escriban datos,
// como defensa adicional a lo que ya rechaza el backend.
let vistaPreviaSoloLectura = false;
let mapa = null;
let marcador = null;
let marcadorDispositivo = null;
let precisionDispositivo = null;
let rutaIncidente = null;
let rutaIncidenteKey = null;
const rutasSolicitadas = new Set();
const rutasCalculadas = new Map();
let ubicacionActualOperador = null;
let solicitudRutaId = 0;
let llamadaSeleccionada = null;
let vistaActual = 'info';
let colaActual = 'pendientes';
let cacheColas = {
  pendientes: [],
  'en-curso': [],
  cerradas: []
};
let colasInicializadas = false;
const urgenciasNotificadas = new Set();
let adminTabActual = 'incidentes';
let feedEventos = [];
const captchaTokens = { login: '', register: '' };
const captchaWidgets = { login: null, register: null };
const captchaContenedores = { login: 'captcha-login', register: 'captcha-register' };
const captchaErrores = { login: 'login-error', register: 'registro-error' };
let captchaConfigurado = false;
let captchaSiteKey = '';

function mostrarErrorCaptcha(tipo, msg) {
  const el = document.getElementById(captchaErrores[tipo]);
  if (el) { el.textContent = msg; el.hidden = false; }
}

// Dibuja el widget de Turnstile. El de registro se dibuja recién al abrir su pestaña
// (dentro de un formulario oculto el widget puede no cargar bien).
function renderizarCaptcha(tipo, intentos = 0) {
  if (!captchaConfigurado || captchaWidgets[tipo] !== null) return;
  if (!window.turnstile) {
    if (intentos >= 40) {
      mostrarErrorCaptcha(tipo, 'No se pudo cargar la verificación de seguridad de Cloudflare. Revisa tu conexión a internet y recarga la página (Ctrl+F5).');
      return;
    }
    setTimeout(() => renderizarCaptcha(tipo, intentos + 1), 250);
    return;
  }
  const el = document.getElementById(captchaContenedores[tipo]);
  if (!el) return;
  el.innerHTML = '';
  captchaWidgets[tipo] = window.turnstile.render(el, {
    sitekey: captchaSiteKey,
    callback: token => { captchaTokens[tipo] = token; },
    'expired-callback': () => { captchaTokens[tipo] = ''; },
    'error-callback': () => {
      captchaTokens[tipo] = '';
      mostrarErrorCaptcha(tipo, 'La verificación de seguridad no pudo cargarse. Recarga la página; si sigue igual, revisa las claves de Turnstile.');
    }
  });
}

// Los tokens de Turnstile son de un solo uso: tras cada intento (bueno o malo)
// hay que pedir uno nuevo, si no el segundo intento falla con "captcha inválido".
function reiniciarCaptcha(tipo) {
  captchaTokens[tipo] = '';
  const id = captchaWidgets[tipo];
  if (window.turnstile && id !== null && id !== undefined) {
    try { window.turnstile.reset(id); } catch (_) { /* nada */ }
  }
}

function dibujarRutaGuardada(ruta, ajustarVista) {
  if (!mapa || !ruta || rutaIncidenteKey == null) return;
  const latLngs = ruta.coordinates.map(([lng, lat]) => [lat, lng]);
  rutaIncidente = L.polyline(latLngs, {
    color: '#ffd166',
    weight: 6,
    opacity: 0.9,
    lineCap: 'round',
    lineJoin: 'round'
  }).addTo(mapa);

  const distancia = ruta.distance >= 1000
    ? `${(ruta.distance / 1000).toFixed(1)} km`
    : `${Math.round(ruta.distance)} m`;
  const minutos = Math.max(1, Math.round(ruta.duration / 60));
  const rutaEstado = $('ruta-estado');
  if (rutaEstado) rutaEstado.textContent = `Ruta estimada · ${distancia} · ${minutos} min`;
  if (ajustarVista) {
    mapa.fitBounds(rutaIncidente.getBounds(), { padding: [40, 40], maxZoom: 16 });
  }
}

async function inicializarCaptcha() {
  try {
    const resp = await fetch(`${API_URL}/auth/captcha-site-key`);
    const config = resp.ok ? await resp.json() : {};
    captchaRequerido = config.enabled === true;
    if (!captchaRequerido) {
      captchaConfigurado = true;
      ['login', 'register'].forEach(tipo => {
        const error = $(captchaErrores[tipo]);
        if (error) error.hidden = true;
      });
      return;
    }
    if (!config.siteKey) {
      captchaConfigurado = false;
      const msg = 'Turnstile está habilitado pero no está configurado correctamente en el servidor.';
      ['login', 'register'].forEach(t => mostrarErrorCaptcha(t, msg));
      return;
    }
    captchaSiteKey = config.siteKey;
    captchaConfigurado = true;
    if (window.turnstile) {
      renderizarCaptcha('login');
    } else {
      const script = document.createElement('script');
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      script.defer = true;
      script.onload = () => renderizarCaptcha('login');
      script.onerror = () => mostrarErrorCaptcha('login', 'No se pudo cargar Turnstile.');
      document.head.appendChild(script);
    }
  } catch (error) {
    console.error('No se pudo cargar la verificación de seguridad.', error);
    ['login', 'register'].forEach(t => mostrarErrorCaptcha(t, 'No se pudo conectar con el servidor.'));
  }
}

const $ = (id) => document.getElementById(id);

function aplicarTema(tema) {
  const temaClaro = tema === 'claro';

  document.body.classList.toggle('tema-claro', temaClaro);

  const boton = $('btn-tema');
  if (!boton) return;

  boton.textContent = temaClaro ? 'Oscuro' : 'Claro';
  boton.setAttribute(
    'aria-label',
    temaClaro ? 'Cambiar a tema oscuro' : 'Cambiar a tema claro'
  );
}

function inicializarTema() {
  let tema = 'oscuro';

  try {
    tema = localStorage.getItem('codes-tema') || tema;
  } catch (error) {
  }

  aplicarTema(tema);

  $('btn-tema')?.addEventListener('click', () => {
    const nuevoTema = document.body.classList.contains('tema-claro')
      ? 'oscuro'
      : 'claro';

    aplicarTema(nuevoTema);

    try {
      localStorage.setItem('codes-tema', nuevoTema);
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

function mostrarAlerta(texto, tipo = 'normal') {
  const el = $('alerta-urgente');
  const tx = $('alerta-texto');

  if (!el || !tx) return;

  tx.textContent = texto;
  el.hidden = false;

  el.style.background =
    tipo === 'error'
      ? '#7f1d1d'
      : tipo === 'ok'
        ? '#065f46'
        : '';

  clearTimeout(mostrarAlerta.timer);

  mostrarAlerta.timer = setTimeout(() => {
    el.hidden = true;
    el.style.background = '';
  }, 4500);
}

function registrarEvento(mensaje, clase = '') {
  feedEventos.unshift({
    hora: new Date(),
    mensaje,
    clase
  });

  feedEventos = feedEventos.slice(0, 80);

  renderizarFeed();
}

function notificarNuevasUrgencias(llamadas) {
  const urgentes = llamadas.filter(llamada =>
    llamada && llamada.priority === 'URGENTE' && llamada.id != null
  );

  if (!colasInicializadas) {
    urgentes.forEach(llamada => urgenciasNotificadas.add(llamada.id));
    return;
  }

  urgentes
    .filter(llamada => !urgenciasNotificadas.has(llamada.id))
    .forEach(llamada => {
      urgenciasNotificadas.add(llamada.id);
      const direccion = llamada.address || 'ubicación por determinar';
      const mensaje = `Nueva alerta urgente: caso #${llamada.id}, ${direccion}`;
      mostrarAlerta(mensaje, 'error');
      registrarEvento(mensaje, 'urgente');

      if (navigator.vibrate) navigator.vibrate([180, 100, 180]);
      if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
        new Notification('CODES: alerta urgente', { body: mensaje, tag: `codes-${llamada.id}` });
      }
    });
}

function renderizarFeed() {
  const c = $('feed-lista');

  if (!c) return;

  if (!feedEventos.length) {
    c.innerHTML =
      '<p class="estado-vacio" style="font-size:.7rem;padding:1rem;">Esperando actividad…</p>';

    if ($('feed-contador')) {
      $('feed-contador').textContent = '0 eventos';
    }

    return;
  }

  c.innerHTML = feedEventos
    .map(e => `
      <div class="feed-item">
        <span class="hora">
          ${e.hora.toLocaleTimeString('es-CL', { hour12: false })}
        </span>
        <span class="mensaje">
          ${escapeHtml(e.mensaje)}
        </span>
      </div>
    `)
    .join('');

  if ($('feed-contador')) {
    $('feed-contador').textContent = `${feedEventos.length} eventos`;
  }
}

async function apiFetch(ruta, opciones = {}) {
  const headers = {
    ...(opciones.headers || {})
  };

  const rutaConBarra = ruta.startsWith('/') ? ruta : `/${ruta}`;
  const rutaFinal =
    rutaConBarra === API_URL || rutaConBarra.startsWith(`${API_URL}/`)
      ? rutaConBarra
      : `${API_URL}${rutaConBarra}`;

  if (sesion?.token) {
    headers.Authorization = `Bearer ${sesion.token}`;
  }

  const resp = await fetch(rutaFinal, {
    ...opciones,
    headers
  });

  if (resp.status === 401) {
    const estabaLogueado = !!sesion;

    sesion = null;

    mostrarPantallaAuth(
      'Tu sesión expiró o fue revocada. Ingresa nuevamente.',
      true
    );

    if (estabaLogueado) {
      registrarEvento('Sesión cerrada por el servidor.');
    }

    throw new Error('No autenticado');
  }

  return resp;
}

async function leerRespuesta(resp) {
  const tipo = resp.headers.get('content-type') || '';

  if (tipo.includes('application/json')) {
    return resp.json();
  }

  return {
    error: await resp.text()
  };
}

function mostrarPantallaAuth(error = '') {
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


function etiquetaRol(rol) {
  return ({ administrator: 'Administrador', supervisor: 'Supervisor', operator: 'Operador' }[rol] || rol || 'Operador');
}

function actualizarUsuarioUI() {
  const rol = sesion?.role || 'operator';

  if ($('rol-tag')) {
    $('rol-tag').textContent = etiquetaRol(rol);
  }

  if ($('usuario-nombre')) {
    $('usuario-nombre').textContent =
      sesion?.username || 'usuario';
  }

  if ($('avatar-usuario')) {
    const nombreAvatar = sesion?.username || 'U';
    $('avatar-usuario').textContent =
      nombreAvatar.trim().charAt(0).toUpperCase() || 'U';
  }

  if ($('usuario-estado')) {
    $('usuario-estado').textContent =
      sesion ? '● conectado' : '';
  }

  if ($('indicador-estado')) {
    $('indicador-estado').className =
      `indicador ${sesion ? 'online' : ''}`;
  }

  if ($('estado-texto')) {
    $('estado-texto').textContent =
      sesion ? 'Operativo' : 'Sin sesión';
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

function mostrarAuthVista(vista) {
  const vistas = ['login', 'registro', 'recuperar', 'reset'];
  vistas.forEach(v => {
    const form = $(`form-${v}`);
    if (form) form.hidden = v !== vista;
  });
  document.querySelectorAll('.auth-tab').forEach(t => t.classList.toggle('activo', t.dataset.tab === vista));
  const tabs = $('auth-tabs');
  if (tabs) tabs.hidden = vista === 'recuperar' || vista === 'reset';
}

async function solicitarRecuperacion(e) {
  e.preventDefault();
  const error = $('recovery-error');
  const success = $('recovery-success');
  error.hidden = true; success.hidden = true;
  try {
    const resp = await fetch(`${API_URL}/auth/forgot-password`, {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ email: $('recovery-email').value.trim() })
    });
    const d = await leerRespuesta(resp);
    if (!resp.ok) throw new Error(d.error || 'No se pudo procesar la solicitud.');
    success.textContent = d.message;
    success.hidden = false;
  } catch (err) {
    error.textContent = err.message || 'No se pudo procesar la solicitud.';
    error.hidden = false;
  }
}

async function restablecerPassword(e) {
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
    const d = await leerRespuesta(resp);
    if (!resp.ok) throw new Error(d.error || 'No se pudo restablecer la contraseña.');
    success.textContent = d.message;
    success.hidden = false;
    history.replaceState({}, '', location.pathname);
    $('reset-password').value = ''; $('reset-password-confirm').value = '';
    setTimeout(() => mostrarAuthVista('login'), 1800);
  } catch (err) {
    error.textContent = err.message || 'No se pudo restablecer la contraseña.';
    error.hidden = false;
  }
}

async function iniciarSesion(e) {
  e.preventDefault();

  const error = $('login-error');

  if (error) {
    error.hidden = true;
  }

  const nombreUsuario =
    $('login-usuario').value.trim();

  const password =
    $('login-password').value;

  if (captchaRequerido && !captchaConfigurado) {
    error.textContent = 'Turnstile está habilitado pero no está configurado correctamente en el servidor.';
    error.hidden = false;
    return;
  }

  if (captchaRequerido && !captchaTokens.login) {
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
          username: nombreUsuario,
          password,
          captchaToken: captchaRequerido ? captchaTokens.login : ''
        })
      }
    );

    const d = await leerRespuesta(resp);

    if (!resp.ok) {
      error.textContent =
        d.error ||
        'No se pudo iniciar sesión.';

      error.hidden = false;
      reiniciarCaptcha('login');
      return;
    }

    sesion = d;

    $('login-password').value = '';

    ocultarPantallaAuth();
    actualizarUsuarioUI();

    registrarEvento(
      `Inició sesión como ${d.username}.`,
      'admin'
    );

    await entrarConsola();

  } catch (err) {
    error.textContent =
      'No se pudo conectar con el servidor. ¿Está ejecutándose Spring Boot?';

    error.hidden = false;
    reiniciarCaptcha('login');
  }
}

async function registrarCuenta(e) {
  e.preventDefault();

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
      $('reg-usuario').value.trim(),

    password,

    captchaToken: captchaRequerido ? captchaTokens.register : '',

    nombre:
      $('reg-nombre').value.trim(),

    apellido:
      $('reg-apellido').value.trim(),

    correo:
      $('reg-correo').value.trim(),

    institucion:
      $('reg-institucion').value
  };

  if (captchaRequerido && !captchaConfigurado) {
    error.textContent = 'Turnstile está habilitado pero no está configurado correctamente en el servidor.';
    error.hidden = false;
    return;
  }

  if (captchaRequerido && !captchaTokens.register) {
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

    const d = await leerRespuesta(resp);

    if (!resp.ok) {
      error.textContent =
        d.error ||
        (
          d.errores
            ? Object.values(d.errores).join(' · ')
            : 'No se pudo crear la cuenta.'
        );

      error.hidden = false;
      reiniciarCaptcha('register');
      return;
    }

    ok.textContent =
      (d.mensaje || d.message) ||
      'Cuenta creada. Un administrador debe activarla antes de iniciar sesión.';

    ok.hidden = false;

    e.target.reset();
    reiniciarCaptcha('register');

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
    reiniciarCaptcha('register');
  }
}


async function cambiarClave() {
  if (!sesion) return;
  const actual = window.prompt('Contraseña actual:');
  if (actual === null) return;
  const nueva = window.prompt('Nueva contraseña (mínimo 12 caracteres, mayúscula, minúscula, número y símbolo):');
  if (nueva === null) return;
  const confirmacion = window.prompt('Repite la nueva contraseña:');
  if (confirmacion === null) return;
  if (nueva !== confirmacion) { mostrarAlerta('Las contraseñas nuevas no coinciden.', 'error'); return; }
  try {
    const r = await apiFetch('/auth/change-password', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword: actual, newPassword: nueva })
    });
    const d = await leerRespuesta(r);
    if (!r.ok) throw new Error(d.error || 'No se pudo cambiar la contraseña');
    mostrarAlerta('Contraseña cambiada correctamente.', 'ok');
  } catch (e) { if (e.message !== 'No autenticado') mostrarAlerta(e.message, 'error'); }
}

async function cerrarSesion() {
  if (!sesion) return;

  const token = sesion.token;

  if (live.startedAt) {
    detenerLive(false);
  }

  try {
    await fetch(`${API_URL}/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    });
  } catch (e) {
    console.warn('No se pudo invalidar la sesión en el servidor.', e);
  }

  const usuario =
    sesion.username;

  sesion = null;

  if (vistaPreviaSoloLectura) {
    vistaPreviaSoloLectura = false;

    if ($('banner-vista-previa')) {
      $('banner-vista-previa').hidden = true;
    }

    if ($('btn-llamada-en-vivo')) {
      $('btn-llamada-en-vivo').hidden = false;
    }
  }

  cacheColas = {
    pendientes: [],
    'en-curso': [],
    cerradas: []
  };

  llamadaSeleccionada = null;
  rutasSolicitadas.clear();
  rutasCalculadas.clear();
  rutaIncidenteKey = null;
  ubicacionActualOperador = null;
  if (rutaIncidente && mapa) mapa.removeLayer(rutaIncidente);
  rutaIncidente = null;

  actualizarUsuarioUI();

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

  mostrarPantallaAuth();

  registrarEvento(
    `Cerró sesión ${usuario}.`
  );

  $('login-usuario').value = '';
  $('login-password').value = '';
  $('login-usuario').focus();
}

async function entrarConsola() {
  actualizarTodo();

  cambiarVista('info');

  initMapa();
  setTimeout(() => mapa?.invalidateSize(true), 60);

  await cargarCola();

  await actualizarMetricas();

  if (sesion?.role === 'administrator') {
    await cargarUsuarios();
  }
}

function actualizarReloj() {
  const ahora = new Date();

  if ($('reloj')) {
    $('reloj').textContent =
      ahora.toLocaleTimeString(
        'es-CL',
        { hour12: false }
      );
  }

  if ($('ultima-actualizacion')) {
    $('ultima-actualizacion').textContent =
      `· ${ahora.toLocaleTimeString(
        'es-CL',
        { hour12: false }
      )}`;
  }
}

setInterval(
  actualizarReloj,
  1000
);

actualizarReloj();


// ======================================================
// MAPA
// ======================================================

function initMapa() {
  if (
    mapa ||
    typeof L === 'undefined' ||
    !$('mapa')
  ) {
    return;
  }

  mapa = L
    .map('mapa', {
      zoomControl: true
    })
    .setView(
      [-33.4489, -70.6693],
      12
    );

  configurarCapaBaseMapa();
  $('btn-reintentar-ruta')?.addEventListener(
    'click',
    reintentarRutaSeleccionada
  );
  $('btn-mi-ubicacion')?.addEventListener(
    'click',
    mostrarUbicacionDispositivo
  );
}

const proveedoresTeselas = [
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
let indiceProveedorTeselas = -1;
let temporizadorTeselas = null;

function activarProveedorTeselas(index, mensaje = '') {
  if (!mapa || index < 0 || index >= proveedoresTeselasActivos.length) {
    errorTeselasMapa = 'No se pudieron cargar los mapas base. Comprueba la conexión a Internet o el acceso a los proveedores de mapas.';
    actualizarEstadoMapa();
    return;
  }
  if (temporizadorTeselas) clearTimeout(temporizadorTeselas);
  indiceProveedorTeselas = index;
  const provider = proveedoresTeselasActivos[index];
  const previousLayer = capaBaseMapa;
  const nextLayer = L.tileLayer(provider.url, {
    attribution: provider.attribution,
    maxZoom: provider.maxZoom,
    updateWhenIdle: true,
    keepBuffer: 2
  });
  let tileLoaded = false;
  nextLayer.on('tileload', () => {
    tileLoaded = true;
    errorTeselasMapa = '';
    fallbackTeselasActivado = index > 0;
    actualizarEstadoMapa();
    if (previousLayer && mapa.hasLayer(previousLayer)) mapa.removeLayer(previousLayer);
  });
  nextLayer.on('tileerror', () => {
    if (!tileLoaded && indiceProveedorTeselas === index) {
      if (temporizadorTeselas) clearTimeout(temporizadorTeselas);
      temporizadorTeselas = setTimeout(() => {
        if (!tileLoaded && indiceProveedorTeselas === index) activarProveedorTeselas(index + 1, provider.name);
      }, 1800);
    }
  });
  capaBaseMapa = nextLayer.addTo(mapa);
  errorTeselasMapa = mensaje ? `${mensaje} no responde; probando ${provider.name}…` : '';
  actualizarEstadoMapa();
  temporizadorTeselas = setTimeout(() => {
    if (!tileLoaded && indiceProveedorTeselas === index) activarProveedorTeselas(index + 1, provider.name);
  }, 7000);
}

function crearCapaBase(url) {
  // Preserve a configured tile URL as the first choice, then fall back to public providers.
  const configured = (url || '').trim();
  if (configured && !configured.includes('{z}') || configured && !configured.includes('{x}') || configured && !configured.includes('{y}')) {
    console.warn('Configured map tile URL is missing a required {z}/{x}/{y} placeholder; using public fallback providers.');
  }
  const customProvider = configured && ['{z}', '{x}', '{y}'].every(token => configured.includes(token))
    ? [{ name: 'Configured provider', url: configured, attribution: '&copy; Map data provider', maxZoom: 22 }]
    : [];
  const allProviders = [...customProvider, ...proveedoresTeselas];
  // Keep the shared fallback list aligned with the active map instance.
  if (customProvider.length) {
    proveedoresTeselasActivos = allProviders;
  } else {
    proveedoresTeselasActivos = proveedoresTeselas;
  }
  activarProveedorTeselas(0);
  return capaBaseMapa;
}
let proveedoresTeselasActivos = proveedoresTeselas;

function configurarCapaBaseMapa() {
  if (!mapa) return;
  fallbackTeselasActivado = false;
  indiceProveedorTeselas = -1;
  if (temporizadorTeselas) clearTimeout(temporizadorTeselas);
  if (capaBaseMapa) mapa.removeLayer(capaBaseMapa);
  capaBaseMapa = null;
  let tileUrl = (configuracionPublica.mapTilesUrl || '').trim();
  // El servidor antiguo devolvía OpenStreetMap como "configurado" por defecto: se ignora para usar Esri Imagery.
  if (tileUrl.includes('tile.openstreetmap.org')) tileUrl = '';
  const customProvider = tileUrl && ['{z}', '{x}', '{y}'].every(token => tileUrl.includes(token))
    ? [{ name: 'Configured provider', url: tileUrl, attribution: '&copy; Map data provider', maxZoom: 22 }]
    : [];
  proveedoresTeselasActivos = [...customProvider, ...proveedoresTeselas];
  activarProveedorTeselas(0);
}

function actualizarEstadoMapa() {
  const estado = $('mapa-provider-estado');
  if (!estado) return;
  const avisos = [];
  if (!configuracionPublica.mapConfigured) avisos.push('Teselas no configuradas.');
  if (!configuracionPublica.geocodingConfigured) avisos.push('Geocodificación no configurada.');
  if (!configuracionPublica.routingUrl) avisos.push('Rutas no configuradas.');
  if (errorConfiguracionMapa) avisos.push(errorConfiguracionMapa);
  if (errorTeselasMapa) avisos.push(errorTeselasMapa);
  estado.textContent = avisos.join(' ');
  estado.hidden = avisos.length === 0;
}

function mostrarUbicacionDispositivo() {
  const estado = $('ubicacion-estado');
  const boton = $('btn-mi-ubicacion');

  if (!navigator.geolocation) {
    if (estado) estado.textContent = 'Geolocalización no disponible en este navegador.';
    return;
  }

  initMapa();
  if (!mapa) return;

  if (estado) estado.textContent = 'Solicitando ubicación aproximada...';
  if (boton) boton.disabled = true;

  navigator.geolocation.getCurrentPosition(
    posicion => {
      const latLng = [
        posicion.coords.latitude,
        posicion.coords.longitude
      ];
      const accuracy = Math.max(posicion.coords.accuracy || 100, 40);

      if (marcadorDispositivo) mapa.removeLayer(marcadorDispositivo);
      if (precisionDispositivo) mapa.removeLayer(precisionDispositivo);

      precisionDispositivo = L.circle(latLng, {
        radius: accuracy,
        color: '#4da3ff',
        fillColor: '#4da3ff',
        fillOpacity: 0.14,
        weight: 1.5
      }).addTo(mapa);

      marcadorDispositivo = L.circleMarker(latLng, {
        radius: 8,
        color: '#ffffff',
        weight: 3,
        fillColor: '#1683ff',
        fillOpacity: 1
      })
        .addTo(mapa)
        .bindPopup(`Tu ubicación aproximada<br>Precisión: ${Math.round(accuracy)} m`);

      mapa.setView(latLng, Math.max(mapa.getZoom(), 15));
      if (estado) estado.textContent = `Ubicación aproximada · margen de ${Math.round(accuracy)} m`;
      if (boton) boton.disabled = false;
    },
    () => {
      if (estado) estado.textContent = 'No se pudo obtener la ubicación del dispositivo.';
      if (boton) boton.disabled = false;
    },
    {
      enableHighAccuracy: false,
      timeout: 8000,
      maximumAge: 300000
    }
  );
}

async function dibujarRutaAlIncidente(l) {
  const rutaEstado = $('ruta-estado');
  const botonReintentarRuta = $('btn-reintentar-ruta');
  const solicitud = ++solicitudRutaId;
  if (botonReintentarRuta) botonReintentarRuta.hidden = true;
  if (!l || l.latitude == null || l.longitude == null) {
    if (rutaEstado) rutaEstado.textContent = 'Ruta no disponible: el incidente no tiene coordenadas.';
    return;
  }
  const claveRuta = `${l.id}:${l.latitude}:${l.longitude}`;

  if (rutaIncidente && mapa) {
    mapa.removeLayer(rutaIncidente);
    rutaIncidente = null;
  }
  rutaIncidenteKey = claveRuta;

  const rutaGuardada = rutasCalculadas.get(claveRuta);
  if (rutaGuardada) {
    dibujarRutaGuardada(rutaGuardada, false);
    return;
  }
  if (rutasSolicitadas.has(claveRuta)) {
    if (rutaEstado) rutaEstado.textContent = 'El intento anterior de calcular la ruta falló.';
    if (botonReintentarRuta) botonReintentarRuta.hidden = false;
    return;
  }

  if (!ubicacionActualOperador) {
    if (rutaEstado) rutaEstado.textContent = 'Obteniendo tu ubicación para calcular la ruta…';
    await obtenerUbicacionParaRuta();
  }

  if (solicitud !== solicitudRutaId) return;

  const origen = ubicacionActualOperador;
  if (!origen) {
    if (rutaEstado) rutaEstado.textContent = 'No se pudo obtener tu ubicación. Puedes seleccionar “Ubicación actual”.';
    if (botonReintentarRuta) botonReintentarRuta.hidden = false;
    return;
  }

  if (rutaEstado) rutaEstado.textContent = 'Calculando ruta hacia el incidente…';

  try {
    await configuracionPublicaPromise;
    if (!configuracionPublica.routingUrl) {
      if (rutaEstado) rutaEstado.textContent = 'El servicio de rutas no está configurado.';
      return;
    }
    rutasSolicitadas.add(claveRuta);
    const baseRuta = configuracionPublica.routingUrl.replace(/\/+$/, '');
    const url = `${baseRuta}/route/v1/driving/${encodeURIComponent(origen.lng)},${encodeURIComponent(origen.lat)};${encodeURIComponent(l.longitude)},${encodeURIComponent(l.latitude)}?overview=full&geometries=geojson`;
    const resp = await fetch(url);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    if (data.code && data.code !== 'Ok') {
      const mensajesRuta = {
        NoRoute: 'El proveedor no encontró una ruta transitable entre tu ubicación y el incidente.',
        NoSegment: 'La ubicación está demasiado lejos de una vía transitable para calcular la ruta.',
        InvalidQuery: 'El proveedor rechazó las coordenadas enviadas para calcular la ruta.'
      };
      throw new Error(mensajesRuta[data.code] || `El proveedor de rutas respondió: ${data.code}.`);
    }

    const route = data.routes?.[0] ?? data;
    const coordinates = route.geometry?.coordinates ?? route.coordinates;
    if (!Array.isArray(coordinates) || coordinates.length < 2) {
      throw new Error('El proveedor no devolvió una ruta válida.');
    }
    if (!Number.isFinite(Number(route.distance)) || !Number.isFinite(Number(route.duration))) {
      throw new Error('El proveedor devolvió una ruta sin distancia o duración válidas.');
    }

    const ruta = {
      coordinates,
      distance: route.distance,
      duration: route.duration
    };
    rutasCalculadas.set(claveRuta, ruta);
    if (solicitud !== solicitudRutaId) return;
    dibujarRutaGuardada(ruta, true);
  } catch (error) {
    console.warn('No se pudo calcular la ruta al incidente:', error);
    if (solicitud !== solicitudRutaId) return;
    const mensaje = error instanceof TypeError
      ? 'No se pudo conectar con el proveedor de rutas. Comprueba la conexión e inténtalo de nuevo.'
      : error.message === 'HTTP 429'
        ? 'El proveedor de rutas está temporalmente limitado. Inténtalo de nuevo más tarde.'
        : error.message.startsWith('HTTP 5')
          ? 'El proveedor de rutas no está disponible temporalmente.'
          : error.message || 'No se pudo calcular la ruta.';
    if (rutaEstado) rutaEstado.textContent = mensaje;
    if (botonReintentarRuta) botonReintentarRuta.hidden = false;
  }
}

function reintentarRutaSeleccionada() {
  if (!llamadaSeleccionada) return;
  const claveRuta = `${llamadaSeleccionada.id}:${llamadaSeleccionada.latitude}:${llamadaSeleccionada.longitude}`;
  rutasSolicitadas.delete(claveRuta);
  dibujarRutaAlIncidente(llamadaSeleccionada);
}

function obtenerUbicacionParaRuta() {
  return new Promise(resolve => {
    if (!navigator.geolocation) { resolve(null); return; }
    navigator.geolocation.getCurrentPosition(
      pos => {
        ubicacionActualOperador = {
          lat: pos.coords.latitude,
          lng: pos.coords.longitude,
          accuracy: pos.coords.accuracy
        };
        mostrarMarcadorUbicacion(ubicacionActualOperador);
        resolve(ubicacionActualOperador);
      },
      () => resolve(null),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 120000 }
    );
  });
}

function mostrarMarcadorUbicacion(ubicacion) {
  if (!mapa || !ubicacion) return;
  const latLng = [ubicacion.lat, ubicacion.lng];
  const accuracy = Math.max(ubicacion.accuracy || 100, 40);
  if (marcadorDispositivo) mapa.removeLayer(marcadorDispositivo);
  if (precisionDispositivo) mapa.removeLayer(precisionDispositivo);
  precisionDispositivo = L.circle(latLng, { radius: accuracy, color: '#4da3ff', fillColor: '#4da3ff', fillOpacity: 0.14, weight: 1.5 }).addTo(mapa);
  marcadorDispositivo = L.circleMarker(latLng, { radius: 8, color: '#ffffff', weight: 3, fillColor: '#1683ff', fillOpacity: 1 }).addTo(mapa).bindPopup(`Tu ubicación aproximada<br>Precisión: ${Math.round(accuracy)} m`);
}

function actualizarMapa(l) {
  solicitudRutaId++;
  if (rutaIncidente && mapa) { mapa.removeLayer(rutaIncidente); rutaIncidente = null; }
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

  initMapa();

  if (!mapa) return;

  mapa.setView(
    [l.latitude, l.longitude],
    16
  );

  if (marcador) {
    mapa.removeLayer(marcador);
  }

  marcador =
    L.marker([
      l.latitude,
      l.longitude
    ])
      .addTo(mapa)
      .bindPopup(
        escapeHtml(
          l.address ||
          l.addressDetectada ||
          `Caso #${l.id}`
        )
      );

  dibujarRutaAlIncidente(l);

  setTimeout(
    () => mapa.invalidateSize(),
    50
  );
}

function cambiarVista(vista) {
  vistaActual = 'info';

  $('mapa-container')
    ?.classList.add('visible');

  $('info-container')
    ?.classList.remove('hidden-view');

  initMapa();
  setTimeout(() => mapa?.invalidateSize(true), 60);
}


// ======================================================
// COLAS
// ======================================================

async function cargarCola() {
  if (!sesion) return;

  try {
    const rutas = {
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
      Object.entries(rutas)
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
      cacheColas[k] = v;
    }

    notificarNuevasUrgencias([
      ...cacheColas.pendientes,
      ...cacheColas['en-curso'],
      ...cacheColas.cerradas
    ]);
    colasInicializadas = true;

    actualizarContadores();

    renderizarLista();

    if (llamadaSeleccionada) {
      const nueva =
        Object.values(cacheColas)
          .flat()
          .find(
            x =>
              x.id ===
              llamadaSeleccionada.id
          );

      llamadaSeleccionada =
        nueva || null;

      renderizarDetalle(
        llamadaSeleccionada
      );

      if (!llamadaSeleccionada) actualizarMapa(null);
    }

    $('indicador-estado').className =
      'indicador online';

    $('estado-texto').textContent =
      'Operativo';

  } catch (e) {
    if (e.message !== 'No autenticado') {
      $('indicador-estado').className =
        'indicador';

        $('estado-texto').textContent =
          e.message === 'HTTP 403'
            ? 'Sin permisos'
            : e.message === 'HTTP 500'
              ? 'Error del servidor'
              : 'Sin conexión';
    }
  }
}

function actualizarContadores() {
  $('cont-pendientes').textContent =
    cacheColas.pendientes.length;

  $('cont-en-curso').textContent =
    cacheColas['en-curso'].length;

  $('cont-cerradas').textContent =
    cacheColas.cerradas.length;
}

function listaActual() {
  return cacheColas[colaActual] || [];
}

function clasePrioridad(p) {
  return String(
    p || 'VERDE'
  ).toUpperCase();
}


// ======================================================
// PALABRAS DESTACADAS
// ======================================================

function normalizarPalabrasDestacadas(valor) {
  if (Array.isArray(valor)) {
    return valor
      .filter(Boolean)
      .map(String);
  }

  if (valor == null) {
    return [];
  }

  if (typeof valor === 'string') {
    try {
      const parsed =
        JSON.parse(valor);

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

function etiquetaInstitucion(valor) {
  return {
    carabineros: 'Carabineros',
    samu: 'SAMU',
    bomberos: 'Bomberos'
  }[String(valor).toLowerCase()] || String(valor);
}


// ======================================================
// TRANSCRIPCIÓN
// ======================================================

function limpiarTranscripcion(valor) {
  if (valor == null) {
    return '';
  }

  if (typeof valor === 'object') {
    return String(
      valor.text ||
      valor.transcript ||
      ''
    ).trim();
  }

  const s =
    String(valor).trim();

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

function truncar(valor, limite) {
  const texto =
    limpiarTranscripcion(valor);

  return texto.length > limite
    ? texto.slice(0, limite) + '…'
    : texto;
}


// ======================================================
// TÍTULO OPERATIVO
// ======================================================

function obtenerTituloOperativo(l) {
  const palabras =
    normalizarPalabrasDestacadas(
      l.highlightedWords
    ).map(
      x =>
        x.toLowerCase()
    );

  if (
    palabras.some(
      x =>
        x.includes('incendi') ||
        x.includes('fuego')
    )
  ) {
    return 'Incendio reportado';
  }

  if (
    palabras.some(
      x =>
        x.includes('accidente') ||
        x.includes('choque') ||
        x.includes('atropell')
    )
  ) {
    return 'Accidente reportado';
  }

  if (
    palabras.some(
      x =>
        x.includes('robo') ||
        x.includes('asalto')
    )
  ) {
    return 'Robo / asalto reportado';
  }

  if (
    palabras.some(
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

function renderizarLista() {
  const c =
    $('lista-llamadas');

  const lista =
    listaActual();

  if (!c) return;

  if (!lista.length) {
    c.innerHTML = `
      <p class="estado-vacio">
        ${
          colaActual === 'pendientes'
            ? 'No hay llamadas sin asignar.'
            : colaActual === 'en-curso'
              ? 'No hay casos en curso.'
              : 'Aún no hay casos cerrados.'
        }
      </p>
    `;

    return;
  }

  const terminoBusqueda = $('busqueda-incidentes')?.value
    .trim()
    .toLocaleLowerCase('es-CL') || '';
  const listaFiltrada = terminoBusqueda
    ? lista.filter(llamada => {
        const buscarEn = [
          llamada.id,
          llamada.priority,
          obtenerTituloOperativo(llamada),
          llamada.address,
          llamada.addressDetectada,
          'Llamada en vivo'
        ].filter(Boolean).join(' ').toLocaleLowerCase('es-CL');
        return buscarEn.includes(terminoBusqueda);
      })
    : lista;

  if (!listaFiltrada.length) {
    c.innerHTML = '<p class="estado-vacio">No hay incidentes que coincidan con la búsqueda.</p>';
    return;
  }

  c.innerHTML = listaFiltrada
    .map(l => {

      const prioridad =
        clasePrioridad(
          l.priority
        );

      const direccion =
        l.address ||
        l.addressDetectada ||
        'Ubicación no identificada';

      const seleccionada =
        llamadaSeleccionada?.id ===
        l.id;
      const estadoCaso = obtenerEstadoCaso(l);
      const tipoIncidente = obtenerTituloOperativo(l)
        .replace(/\s+reportad[oa]$/i, '');

      const claseUrgencia =
        prioridad === 'URGENTE'
          ? 'alta'
          : prioridad === 'ROJA'
            ? 'alta'
            : prioridad === 'MEDIA'
              ? 'media'
              : 'baja';

      const etiquetaPrioridad =
        prioridad === 'URGENTE'
          ? 'Urgente'
          : prioridad === 'ROJA'
            ? 'Roja'
            : prioridad === 'MEDIA'
              ? 'Media'
                : 'Verde';

      return `
        <div
          class="llamada-item ${seleccionada ? 'seleccionada' : ''} prioridad-${prioridad.toLowerCase()} estado-${estadoCaso.clase}"
          data-id="${escapeHtml(l.id)}"
          role="button"
          tabindex="0"
          aria-label="Caso #${escapeHtml(l.id)}, ${escapeHtml(prioridad)}, ${escapeHtml(tipoIncidente)}, ${escapeHtml(direccion)}, ${escapeHtml(estadoCaso.etiqueta)}"
        >

          <div class="llamada-header">
            <span class="llamada-urgencia ${claseUrgencia}">${escapeHtml(etiquetaPrioridad)}</span>
            <span class="llamada-id">#${escapeHtml(l.id)}</span>
          </div>

          <div class="llamada-titulo">${escapeHtml(capitalizarPrimeraLetra(tipoIncidente))}</div>
          <div class="llamada-ubicacion">
            <span class="llamada-ubicacion-label">Ubicación</span>
            <span class="ubicacion" title="${escapeHtml(direccion)}">${escapeHtml(direccion)}</span>
          </div>
          <div class="llamada-meta">
            <span>Llamada en vivo</span>
            <time>${escapeHtml(formatearFecha(l.createdAt))}</time>
          </div>
          <span class="llamada-estado ${estadoCaso.clase}">${escapeHtml(estadoCaso.etiqueta)}</span>

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
        seleccionarLlamada(
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

function obtenerEstadoCaso(llamada) {
  if (llamada.closureDate || colaActual === 'cerradas') {
    return { etiqueta: 'Cerrado', clase: 'cerrado' };
  }
  if (llamada.assigned || colaActual === 'en-curso') {
    return { etiqueta: 'En curso', clase: 'en-curso' };
  }
  return { etiqueta: 'Sin asignar', clase: 'sin-asignar' };
}

function formatearFecha(iso) {
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

function seleccionarLlamada(id) {
  llamadaSeleccionada =
    Object.values(cacheColas)
      .flat()
      .find(
        x =>
          x.id === id
      ) || null;

  renderizarLista();

  renderizarDetalle(
    llamadaSeleccionada
  );

  actualizarMapa(
    llamadaSeleccionada
  );
}

function generarResumenLocal(l) {
  const palabras =
    normalizarPalabrasDestacadas(
      l.highlightedWords
    ).map(
      x =>
        String(x).toLowerCase()
    );

  let tipo =
    'Emergencia reportada';

  if (
    palabras.some(
      x =>
        x.includes('incendi') ||
        x.includes('fuego')
    )
  ) {
    tipo =
      'Incendio reportado';

  } else if (
    palabras.some(
      x =>
        x.includes('accidente') ||
        x.includes('choque') ||
        x.includes('atropell')
    )
  ) {
    tipo =
      'Accidente reportado';

  } else if (
    palabras.some(
      x =>
        x.includes('robo') ||
        x.includes('asalto')
    )
  ) {
    tipo =
      'Robo o asalto reportado';
  }

  const direccion =
    l.address ||
    l.addressDetectada
      ? ` en ${
          l.address ||
          l.addressDetectada
        }`
      : '';

  return `${tipo}${direccion}. Revisa la transcripción completa para el detalle de la llamada.`;
}

function renderizarDetalle(l) {
  const c = $('info-container');
  if (!c) return;
  if (!l) {
    c.innerHTML = '<p class="estado-vacio">Selecciona una llamada para consultar sus detalles.</p>';
    return;
  }

  const prioridad = clasePrioridad(l.priority);
  const prioridadCss = ({
    URGENTE: 'urgente',
    ROJA: 'roja',
    MEDIA: 'media',
    VERDE: 'verde'
  })[prioridad] || 'verde';
  const puedeAsignar = colaActual === 'pendientes' && !vistaPreviaSoloLectura;
  const puedeCerrar = colaActual === 'en-curso' && !vistaPreviaSoloLectura;
  const resumen = l.operationalSummary || generarResumenLocal(l);
  const transcripcion = limpiarTranscripcion(l.transcription);
  const tieneUbicacionConfirmada = l.latitude != null &&
    l.longitude != null &&
    Number.isFinite(Number(l.latitude)) &&
    Number.isFinite(Number(l.longitude)) &&
    Math.abs(Number(l.latitude)) <= 90 &&
    Math.abs(Number(l.longitude)) <= 180;
  const palabras = normalizarPalabrasDestacadas(l.highlightedWords);
  const razones = normalizarPalabrasDestacadas(l.classificationReasons);
  const instituciones = normalizarPalabrasDestacadas(l.suggestedInstitutions);
  const direccion = l.address || l.addressDetectada || 'Ubicación no identificada';
  const estadoCaso = obtenerEstadoCaso(l);
  const tipoEmergencia = obtenerTituloOperativo(l).replace(/\s+reportad[oa]$/i, '');

  c.innerHTML = `
    <article class="detalle-contenido">
      <header class="detalle-cabecera">
        <div class="detalle-caso-id">
          <strong>Caso #${escapeHtml(l.id)}</strong>
          <span>${escapeHtml(formatearFecha(l.createdAt))}</span>
          ${l.assignedOperator ? `<span>Asignado a ${escapeHtml(l.assignedOperator)}</span>` : ''}
        </div>
        <div class="detalle-cabecera-badges">
          <span class="detalle-prioridad ${prioridadCss}">
            <span class="detalle-prioridad-punto" aria-hidden="true"></span>
            ${escapeHtml(capitalizarPrimeraLetra(prioridad.toLocaleLowerCase('es-CL')))}
          </span>
          <span class="detalle-tipo-emergencia">${escapeHtml(capitalizarPrimeraLetra(tipoEmergencia))}</span>
        </div>
      </header>

      <section class="detalle-ubicacion" aria-label="Ubicación del incidente">
        <div>
          <span class="detalle-etiqueta">Ubicación</span>
          <h1>${escapeHtml(direccion)}</h1>
        </div>
        <span class="detalle-ubicacion-estado ${tieneUbicacionConfirmada ? 'confirmada' : 'no-identificada'}">
          ${tieneUbicacionConfirmada ? 'Confirmada' : 'No identificada'}
        </span>
      </section>

      <section class="detalle-estado-linea">
        <span class="detalle-etiqueta">Estado</span>
        <span class="detalle-estado ${estadoCaso.clase}">${escapeHtml(estadoCaso.etiqueta)}</span>
      </section>

      <section class="detalle-resumen">
        <h2 class="detalle-seccion-titulo">Resumen operativo</h2>
        <p>${escapeHtml(capitalizarPrimeraLetra(resumen))}</p>
      </section>

      <section class="detalle-clasificacion" aria-label="Clasificación automática">
        <div class="detalle-evidencia">
          <h2 class="detalle-seccion-titulo">Motivos detectados</h2>
          <div class="detalle-evidencia-valores">
            ${palabras.length
              ? palabras.map(p => `<span class="detalle-chip">${escapeHtml(capitalizarPrimeraLetra(p))}</span>`).join('')
              : '<span class="detalle-vacio">Sin motivos detectados</span>'}
          </div>
          ${razones.length
            ? `<p class="detalle-justificacion">${escapeHtml(razones.map(capitalizarPrimeraLetra).join(' · '))}</p>`
            : ''}
        </div>
        <div class="detalle-evidencia">
          <h2 class="detalle-seccion-titulo">Respuesta sugerida <span class="detalle-sugerencia-label">Sugerencia del sistema</span></h2>
          <div class="detalle-evidencia-valores">
            ${instituciones.length
              ? instituciones.map(i => `<span class="detalle-chip">${escapeHtml(capitalizarPrimeraLetra(etiquetaInstitucion(i)))}</span>`).join('')
              : '<span class="detalle-vacio">Sin institución sugerida</span>'}
          </div>
        </div>
      </section>

      <section class="detalle-relato">
        <div class="detalle-relato-cabecera">
          <h2 class="detalle-seccion-titulo">Relato de la llamada</h2>
          <span class="detalle-transcripcion-meta">Transcripción ASR · Llamada en vivo</span>
        </div>
        <p class="detalle-transcripcion">${escapeHtml(transcripcion || 'Transcripción no disponible.')}</p>
      </section>

      ${l.closureComment ? `
        <section class="detalle-resolucion">
          <span class="detalle-etiqueta">Resolución</span>
          <p>${escapeHtml(capitalizarPrimeraLetra(l.closureComment))}</p>
        </section>
      ` : ''}

      <div class="detalle-acciones">
        ${puedeAsignar ? '<button class="btn-accion primario accion-principal" id="accion-asignar">Tomar caso</button>' : ''}
        ${puedeCerrar ? '<button class="btn-accion primario" id="accion-cerrar">Cerrar caso</button>' : ''}
      </div>
    </article>
  `;

  $('accion-asignar')?.addEventListener('click', () => asignarCaso(l.id));
  $('accion-cerrar')?.addEventListener('click', () => abrirModalCerrar(l.id));
}

function capitalizarPrimeraLetra(valor) {
  const texto = String(valor ?? '').trim();
  return texto ? texto.charAt(0).toLocaleUpperCase('es-CL') + texto.slice(1) : texto;
}


// ======================================================
// GESTIÓN DE CASOS
// ======================================================

async function asignarCaso(id) {
  try {
    const r =
      await apiFetch(
        `/api/llamadas/${id}/assign`,
        {
          method: 'POST'
        }
      );

    const d =
      await leerRespuesta(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo asignar'
      );
    }

    mostrarAlerta(
      (d.mensaje || d.message) ||
      'Caso asignado.',
      'ok'
    );

    registrarEvento(
      (d.mensaje || d.message) ||
      `Caso #${id} asignado.`
    );

    await cargarCola();

    colaActual =
      'en-curso';

    actualizarTabs();

    renderizarLista();

    seleccionarLlamada(id);

  } catch (e) {
    if (
      e.message !==
      'No autenticado'
    ) {
      mostrarAlerta(
        e.message,
        'error'
      );
    }
  }
}

function abrirModalCerrar(id) {
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

      const comentario =
        $('input-comentario')
          .value
          .trim();

      if (!comentario) {
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
                  comment: comentario
                })
            }
          );

        const d =
          await leerRespuesta(r);

        if (!r.ok) {
          throw new Error(
            d.error ||
            'No se pudo cerrar'
          );
        }

        $('modal').hidden =
          true;

        mostrarAlerta(
          (d.mensaje || d.message) ||
          'Caso cerrado.',
          'ok'
        );

        registrarEvento(
          (d.mensaje || d.message) ||
          `Caso #${id} cerrado.`
        );

        colaActual =
          'cerradas';

        await cargarCola();

        actualizarTabs();

        seleccionarLlamada(id);

      } catch (e) {
        if (
          e.message !==
          'No autenticado'
        ) {
          mostrarAlerta(
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

async function actualizarMetricas() {
  if (!sesion) {
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

function actualizarTabs() {
  document
    .querySelectorAll(
      '.tab'
    )
    .forEach(
      b =>
        b.classList.toggle(
          'activo',
          b.dataset.tab ===
            colaActual
        )
    );
}


// ======================================================
// USUARIOS / ADMINISTRADOR
// ======================================================

async function abrirVistaPrevia(institucion) {
  try {
    const r = await apiFetch(
      `/api/admin/preview-session/${institucion}`,
      { method: 'POST' }
    );

    const d = await leerRespuesta(r);

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
      mostrarAlerta(
        'El navegador bloqueó la pestaña nueva. Habilita las ventanas emergentes para este sitio e inténtalo de nuevo.',
        'error'
      );
      return;
    }

    mostrarAlerta(
      `Vista previa de ${etiquetaInstitucion(institucion)} abierta en una pestaña nueva (expira en ${d.expiresInMinutes} min, solo lectura).`,
      'ok'
    );
  } catch (e) {
    if (e.message !== 'No autenticado') {
      mostrarAlerta(e.message, 'error');
    }
  }
}

function etiquetaInstitucion(institucion) {
  return (
    { bomberos: 'Bomberos', carabineros: 'Carabineros', samu: 'SAMU' }[
      institucion
    ] || institucion
  );
}

async function cargarUsuarios() {
  if (
    sesion?.role !==
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
      await leerRespuesta(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo cargar usuarios'
      );
    }

    renderizarUsuarios(d);

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

function renderizarUsuarios(usuarios) {
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
          const estado = u.active
            ? { texto: 'Activo', clase: 'activo' }
            : { texto: 'Inactivo', clase: 'inactivo' };
          const institucion = u.institution
            ? etiquetaInstitucion(u.institution)
            : 'Administración general';
          const esUsuarioActual = u.username === sesion.username;

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
                <span class="usuario-etiqueta rol">${escapeHtml(etiquetaRol(u.role))}</span>
                <span class="usuario-etiqueta institucion">${escapeHtml(institucion)}</span>
                <span class="usuario-etiqueta estado ${estado.clase}">
                  <span aria-hidden="true"></span>${estado.texto}
                </span>
                ${u.testAccount
                  ? '<span class="usuario-etiqueta prueba" title="Cuenta de solo lectura usada para vista previa">Cuenta de vista previa</span>'
                  : ''}
                ${esUsuarioActual ? '<span class="usuario-etiqueta actual">Tu cuenta</span>' : ''}
              </div>

              <div class="acciones" aria-label="Acciones de usuario">
                ${u.active
                  ? `<button class="btn-accion peligro" data-accion="desactivar" data-id="${u.id}" ${esUsuarioActual ? 'disabled title="No puedes desactivar tu propia cuenta"' : ''}>Desactivar cuenta</button>`
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
              ? restablecerClaveUsuario(Number(b.dataset.id))
              : cambiarEstadoUsuario(
              Number(
                b.dataset.id
              ),
              b.dataset.accion
            )
        )
    );
}

async function restablecerClaveUsuario(id) {
  if (!confirm('¿Generar una contraseña temporal para este usuario? La actual dejará de funcionar y sus sesiones abiertas se cerrarán.')) return;
  try {
    const r = await apiFetch(`/api/users/${id}/reset-password`, { method: 'PATCH' });
    const d = await leerRespuesta(r);
    if (!r.ok) throw new Error(d.error || 'No se pudo restablecer la contraseña');
    // prompt() permite seleccionar y copiar la clave temporal; solo se muestra una vez.
    window.prompt(`${d.message}\n\nContraseña temporal (cópiala ahora):`, d.temporaryPassword);
    registrarEvento('Se generó una contraseña temporal para un usuario.');
  } catch (e) {
    if (e.message !== 'No autenticado') mostrarAlerta(e.message, 'error');
  }
}

async function cambiarEstadoUsuario(
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
      await leerRespuesta(r);

    if (!r.ok) {
      throw new Error(
        d.error ||
        'No se pudo actualizar'
      );
    }

    mostrarAlerta(
      `${d.username}: ${
        d.active
          ? 'activado'
          : 'desactivado'
      }.`,
      'ok'
    );

    registrarEvento(
      `${d.username} fue ${
        d.active
          ? 'activado'
          : 'desactivado'
      }.`
    );

    await cargarUsuarios();

  } catch (e) {
    if (
      e.message !==
      'No autenticado'
    ) {
      mostrarAlerta(
        e.message,
        'error'
      );
    }
  }
}

function cambiarAdminTab(tab) {
  if (tab === 'usuarios' && sesion?.role !== 'administrator') {
    adminTabActual = 'incidentes';
    return;
  }

  adminTabActual =
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

  const esUsuarios =
    tab === 'usuarios';

  $('panel-cola').style.display =
    esUsuarios
      ? 'none'
      : 'flex';

  $('panel-detalle').style.display =
    'flex';

  $('gestion-usuarios')
    .classList.toggle(
      'visible',
      esUsuarios
    );

  $('detalle-menu').style.display =
    esUsuarios
      ? 'none'
      : 'flex';

  $('info-container')
    .classList.toggle(
      'hidden-view',
      esUsuarios
    );

  $('mapa-container')
    .classList.toggle(
      'visible',
      false
    );

  if (esUsuarios) {
    cargarUsuarios();
  }
}

function actualizarTodo() {
  if (!sesion) {
    return;
  }

  actualizarUsuarioUI();

  if (
    sesion.role !==
    'administrator'
  ) {
    cambiarAdminTab(
      'incidentes'
    );
  }

  renderizarLista();

  actualizarContadores();

  renderizarDetalle(
    llamadaSeleccionada
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
  ubicacionOperador: {
    lat: null,
    lng: null
  }
};

function setLiveStatus(
  texto,
  estado = ''
) {
  const el =
    $('live-status');

  if (!el) return;

  el.textContent =
    texto;

  el.className =
    `live-status ${estado}`;

  // El estado de reposo ("Listo") no aporta nada: solo se muestra cuando hay algo que informar.
  el.hidden = texto === 'Listo';
}

function setLiveTranscript(
  texto
) {
  const el =
    $('live-transcripcion');

  if (!el) return;

  el.textContent =
    texto ||
    'Escuchando…';

  el.scrollTop =
    el.scrollHeight;
}

function actualizarLiveTimer() {
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

function abrirLive() {
  if (!sesion) {
    return mostrarAlerta(
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
    configuracionPublica.transcriptionConfigured
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
    configuracionPublica.transcriptionConfigured
      ? 'La transcripción se procesa en el servidor con Groq Whisper al guardar la grabación.'
      : 'Falta configurar GROQ_API_KEY en el servidor para crear llamadas desde audio.';
}

function cerrarLive() {
  if (live.startedAt || live.stopping) {
    mostrarAlerta(
      'Espera a que termine la grabación o el guardado antes de cerrar esta ventana.',
      'error'
    );

    return;
  }

  $('modal-live').hidden =
    true;
}

function obtenerUbicacionOperador() {
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

async function iniciarLive() {
  if (
    live.startedAt ||
    !sesion
  ) {
    return;
  }

  live.stopping =
    false;

  live.transcript =
    '';

  live.chunks =
    [];

  live.ubicacionOperador = {
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

    const mimeGrabacion =
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
            mimeGrabacion
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
        actualizarLiveTimer,
        250
      );

    /*
     * La ubicación del operador es contexto opcional para el mapa; la
     * grabación ya está activa y no espera el permiso de geolocalización.
     */
    live.ubicacionOperador =
      await obtenerUbicacionOperador();

    $('live-nota').textContent =
      live.ubicacionOperador
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

    registrarEvento(
      'Grabación de llamada iniciada.',
      'admin'
    );

  } catch (err) {

    detenerLive(
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

    const mensaje =
      err?.name === 'NotAllowedError'
        ? 'El navegador bloqueó el micrófono.'
        : err instanceof Error
          ? err.message
          : 'No se pudo iniciar la grabación.';

    $('live-nota').textContent =
      mensaje;

    mostrarAlerta(
      mensaje,
      'error'
    );
  }
}

async function detenerLive(
  guardar = true
) {
  if (
    live.stopping
  ) {
    return;
  }

  live.stopping =
    true;

  let guardarFallido =
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

  let grabacionPromise =
    Promise.resolve();

  if (
    live.mediaRecorder &&
    live.mediaRecorder.state !==
      'inactive'
  ) {

    grabacionPromise =
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

  await grabacionPromise;

  const transcript =
    limpiarTranscripcion(
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

      ubicacionOperador: {
        lat: null,
        lng: null
      }
    };

    return;
  }

  try {

    const form =
      new FormData();

    const datos =
      new Blob(
        [
          JSON.stringify({
            transcription: '',

            latitudOperador:
              live
                .ubicacionOperador
                ?.lat ??
              null,

            longitudOperador:
              live
                .ubicacionOperador
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
      datos,
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

    const audioAdjunto = form.get('audio');
    if (!(audioAdjunto instanceof Blob) || audioAdjunto.size === 0) {
      throw new Error('No se pudo preparar el audio. Inicia el micrófono y vuelve a grabar.');
    }
    if (audioAdjunto.size > 25 * 1024 * 1024) {
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
      await leerRespuesta(r);

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

    mostrarAlerta(
      `Llamada en vivo convertida en caso #${d.id} · ${d.priority}`,
      'ok'
    );

    registrarEvento(
      `Llamada en vivo guardada como caso #${d.id} (${d.priority}).`,
      'admin'
    );

    llamadaSeleccionada =
      d;

    colaActual =
      'pendientes';

    await cargarCola();

    actualizarTabs();

    seleccionarLlamada(
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

    guardarFallido =
      true;

    setLiveStatus(
      'Error al guardar',
      'error'
    );

    $('live-provider').textContent =
      'Audio conservado para reintentar';

    mostrarAlerta(
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
    chunks: guardarFallido ? live.chunks : [],
    transcript: guardarFallido ? live.transcript : '',
    stopping: false,

    ubicacionOperador: guardarFallido
      ? live.ubicacionOperador
      : { lat: null, lng: null }
  };
}


// ======================================================
// EVENTOS UI
// ======================================================

// script.js se carga dinámicamente (tras Leaflet): DOMContentLoaded puede haber pasado ya.
const __alCargarDom = (fn) => document.readyState === 'loading'
  ? document.addEventListener('DOMContentLoaded', fn)
  : fn();
__alCargarDom(
  () => {

    inicializarTema();
    inicializarCaptcha();

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

              mostrarAuthVista(tab.dataset.tab);

              $('login-error').hidden =
                true;

              $('registro-error').hidden =
                true;

              $('registro-success').hidden =
                true;

              if (tab.dataset.tab === 'registro') {
                renderizarCaptcha('register');
              }
            }
          )
      );

    $('form-login')
      ?.addEventListener(
        'submit',
        iniciarSesion
      );

    $('form-registro')
      ?.addEventListener(
        'submit',
        registrarCuenta
      );

    $('btn-olvido-password')?.addEventListener('click', () => mostrarAuthVista('recuperar'));
    $('btn-volver-login')?.addEventListener('click', () => mostrarAuthVista('login'));
    $('form-recuperar')?.addEventListener('submit', solicitarRecuperacion);
    $('form-reset')?.addEventListener('submit', restablecerPassword);
    $('btn-reset-login')?.addEventListener('click', () => { history.replaceState({}, '', location.pathname); mostrarAuthVista('login'); });
    if (new URLSearchParams(location.search).has('resetToken')) mostrarAuthVista('reset');


    $('btn-salir')
      ?.addEventListener(
        'click',
        cerrarSesion
      );

    $('btn-llamada-en-vivo')
      ?.addEventListener(
        'click',
        abrirLive
      );

    $('live-iniciar')
      ?.addEventListener(
        'click',
        iniciarLive
      );

    $('live-detener')
      ?.addEventListener(
        'click',
        () =>
          detenerLive(true)
      );

    $('live-cerrar')
      ?.addEventListener(
        'click',
        cerrarLive
      );

    $('modal-live')
      ?.addEventListener(
        'click',
        e => {

          if (
            e.target ===
            $('modal-live')
          ) {
            cerrarLive();
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

              colaActual =
                b.dataset.tab;

              actualizarTabs();

              renderizarLista();

              llamadaSeleccionada =
                null;

              renderizarDetalle(
                null
              );
            }
          )
      );

    $('busqueda-incidentes')?.addEventListener('input', renderizarLista);

    document
      .querySelectorAll(
        '.admin-tab'
      )
      .forEach(
        b =>
          b.addEventListener(
            'click',
            () =>
              cambiarAdminTab(
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
              abrirVistaPrevia(
                b.dataset.previewInstitucion
              )
          )
      );

    window.addEventListener(
      'resize',
      () => {
        setTimeout(() => mapa?.invalidateSize(true), 100);
      }
    );

    renderizarFeed();

    const parametrosUrl = new URLSearchParams(location.search);
    const previewToken = parametrosUrl.get('previewToken');

    if (previewToken) {
      // Pestaña abierta desde "Vista previa de operador": entra
      // directamente con el token de solo lectura, sin pasar por el
      // formulario de login.
      vistaPreviaSoloLectura = true;

      sesion = {
        token: previewToken,
        username: parametrosUrl.get('previewUsername') || 'preview',
        role: 'operator',
        expiresInMinutes:
          Number(parametrosUrl.get('previewExpira')) || 20
      };

      const institucionPreview =
        parametrosUrl.get('previewInstitution') || '';

      if ($('banner-vista-previa')) {
        $('banner-vista-previa').hidden = false;
      }

      if ($('banner-vista-previa-institucion')) {
        $('banner-vista-previa-institucion').textContent =
          etiquetaInstitucion(institucionPreview);
      }

      if ($('btn-llamada-en-vivo')) {
        $('btn-llamada-en-vivo').hidden = true;
      }

      // Saca el token de la URL para que no quede en el historial ni se
      // comparta por accidente al copiar el link de la pestaña.
      history.replaceState({}, '', location.pathname);

      ocultarPantallaAuth();
      entrarConsola();
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

    if (!sesion) {
      return;
    }

    await cargarCola();

    await actualizarMetricas();

    if (
      sesion.role ===
        'administrator' &&
      adminTabActual ===
        'usuarios'
    ) {
      await cargarUsuarios();
    }

  },
  REFRESCO_MS
);
document.getElementById('btn-cambiar-clave')?.addEventListener('click', cambiarClave);

/* ===== MENÚ DE USUARIO (desplegable en la esquina superior derecha) ===== */
(function inicializarMenuUsuario() {
  const contenedor = document.getElementById('menu-usuario');
  const boton = document.getElementById('btn-menu-usuario');
  const panel = document.getElementById('dropdown-usuario');
  if (!contenedor || !boton || !panel) return;

  function cerrarMenu() {
    panel.hidden = true;
    boton.setAttribute('aria-expanded', 'false');
  }

  function abrirMenu() {
    panel.hidden = false;
    boton.setAttribute('aria-expanded', 'true');
  }

  boton.addEventListener('click', (evento) => {
    evento.stopPropagation();
    if (panel.hidden) {
      abrirMenu();
    } else {
      cerrarMenu();
    }
  });

  document.addEventListener('click', (evento) => {
    if (!panel.hidden && !contenedor.contains(evento.target)) {
      cerrarMenu();
    }
  });

  document.addEventListener('keydown', (evento) => {
    if (evento.key === 'Escape') cerrarMenu();
  });

  // Cerrar el menú al usar cualquier acción dentro de él.
  panel.querySelectorAll('button').forEach((btn) => {
    btn.addEventListener('click', () => cerrarMenu());
  });
})();

/* ===== LATIDO DE PÁGINA (ver PageWatchdogService) =====
   Mientras esta pestaña siga abierta (haya o no sesión iniciada), avisamos
   al servidor cada pocos segundos. Si deja de recibir avisos, asume que
   cerraron la página y se apaga solo, liberando el puerto
   8000. Se manda con keepalive para que el último aviso salga incluso
   si el navegador ya está cerrando la pestaña. */
(function iniciarLatidoDePagina() {
  const INTERVALO_MS = 5000;

  function latir() {
    fetch(`${API_URL}/heartbeat`, { method: 'POST', keepalive: true }).catch(() => {});
  }

  latir();
  setInterval(latir, INTERVALO_MS);
  // Al volver a la pestaña (o al despertar el PC) avisa de inmediato.
  document.addEventListener('visibilitychange', () => { if (!document.hidden) latir(); });
})();
