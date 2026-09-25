/* CODES · Frontend integrado con Spring Boot */
const API_URL = '/api';
const REFRESCO_MS = 8000;

let sesion = null;
let mapa = null;
let marcador = null;
let marcadorDispositivo = null;
let precisionDispositivo = null;
let rutaIncidente = null;
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

async function inicializarCaptcha() {
  try {
    const resp = await fetch(`${API_URL}/auth/captcha-site-key`);
    const config = resp.ok ? await resp.json() : {};
    if (!config.siteKey) {
      captchaConfigurado = false;
      const msg = 'La verificación de seguridad no está configurada en el servidor.';
      ['login', 'register'].forEach(t => mostrarErrorCaptcha(t, msg));
      return;
    }
    captchaSiteKey = config.siteKey;
    captchaConfigurado = true;
    renderizarCaptcha('login');
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

  boton.textContent = temaClaro ? '☾ Oscuro' : '☼ Claro';
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

  if (sesion?.token) {
    headers.Authorization = `Bearer ${sesion.token}`;
  }

  const resp = await fetch(`${API_URL}${ruta}`, {
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

function actualizarUsuarioUI() {
  const rol = sesion?.role || 'operador';

  if ($('rol-tag')) {
    $('rol-tag').textContent = rol;
  }

  if ($('usuario-nombre')) {
    $('usuario-nombre').textContent =
      sesion?.username || 'usuario';
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

  const esAdmin = rol === 'administrador';

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

  if (!captchaConfigurado) {
    error.textContent = 'La verificación de seguridad no está configurada. Revisa las claves de Cloudflare Turnstile del servidor.';
    error.hidden = false;
    return;
  }

  if (!captchaTokens.login) {
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
          captchaToken: captchaTokens.login
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

    captchaToken: captchaTokens.register,

    nombre:
      $('reg-nombre').value.trim(),

    apellido:
      $('reg-apellido').value.trim(),

    correo:
      $('reg-correo').value.trim(),

    institucion:
      $('reg-institucion').value
  };

  if (!captchaConfigurado) {
    error.textContent = 'La verificación de seguridad no está configurada. Revisa las claves de Cloudflare Turnstile del servidor.';
    error.hidden = false;
    return;
  }

  if (!captchaTokens.register) {
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

function cerrarSesion() {
  if (!sesion) return;

  if (live.startedAt) {
    detenerLive(false);
  }

  const usuario =
    sesion.username;

  sesion = null;

  cacheColas = {
    pendientes: [],
    'en-curso': [],
    cerradas: []
  };

  llamadaSeleccionada = null;

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

  await cargarCola();

  await actualizarMetricas();

  if (sesion?.role === 'administrador') {
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

  L
    .tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      {
        attribution:
          'Tiles © Esri · Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community',
        maxZoom: 19,
        subdomains: ['server', 'services']
      }
    )
    .addTo(mapa);

  $('btn-mi-ubicacion')?.addEventListener(
    'click',
    mostrarUbicacionDispositivo,
    { once: true }
  );
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
  const solicitud = ++solicitudRutaId;

  if (rutaIncidente && mapa) {
    mapa.removeLayer(rutaIncidente);
    rutaIncidente = null;
  }

  if (!l || l.latitude == null || l.longitude == null) {
    if (rutaEstado) rutaEstado.textContent = 'Ruta no disponible: el incidente no tiene coordenadas.';
    return;
  }

  if (!ubicacionActualOperador) {
    if (rutaEstado) rutaEstado.textContent = 'Obteniendo tu ubicación para calcular la ruta…';
    await obtenerUbicacionParaRuta();
  }

  if (solicitud !== solicitudRutaId) return;

  const origen = ubicacionActualOperador;
  if (!origen) {
    if (rutaEstado) rutaEstado.textContent = 'No se pudo obtener tu ubicación. Puedes usar “Usar mi ubicación”.';
    return;
  }

  if (rutaEstado) rutaEstado.textContent = 'Calculando ruta hacia el incidente…';

  try {
    const url = `https://router.project-osrm.org/route/v1/driving/${encodeURIComponent(origen.lng)},${encodeURIComponent(origen.lat)};${encodeURIComponent(l.longitude)},${encodeURIComponent(l.latitude)}?overview=full&geometries=geojson`;
    const resp = await fetch(url);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    if (solicitud !== solicitudRutaId) return;

    const route = data.routes?.[0];
    if (!route?.geometry?.coordinates?.length) throw new Error('Sin ruta');

    const latLngs = route.geometry.coordinates.map(([lng, lat]) => [lat, lng]);
    rutaIncidente = L.polyline(latLngs, {
      color: '#ffd166',
      weight: 6,
      opacity: 0.9,
      lineCap: 'round',
      lineJoin: 'round'
    }).addTo(mapa);

    const distancia = route.distance >= 1000
      ? `${(route.distance / 1000).toFixed(1)} km`
      : `${Math.round(route.distance)} m`;
    const minutos = Math.max(1, Math.round(route.duration / 60));

    if (rutaEstado) rutaEstado.textContent = `Ruta estimada · ${distancia} · ${minutos} min`;
    mapa.fitBounds(rutaIncidente.getBounds(), { padding: [40, 40], maxZoom: 16 });
  } catch (error) {
    console.warn('No se pudo calcular la ruta al incidente:', error);
    if (rutaEstado) rutaEstado.textContent = 'No se pudo calcular la ruta automáticamente.';
  }
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

    $('btn-abrir-mapa').disabled = true;

    return;
  }

  $('mapa-titulo').textContent =
    `Caso #${l.id} · ${l.priority || 'SIN PRIORIDAD'}`;

  $('mapa-direccion').textContent =
    l.address ||
    l.addressDetectada ||
    'Dirección por determinar';

  const btn =
    $('btn-abrir-mapa');

  btn.disabled = !(
    l.latitude != null &&
    l.longitude != null
  );

  btn.onclick = () => {
    if (
      l.latitude == null ||
      l.longitude == null
    ) {
      return;
    }

    window.open(
      `https://www.google.com/maps/search/?api=1&query=${
        encodeURIComponent(
          `${l.latitude},${l.longitude}`
        )
      }`,
      '_blank',
      'noopener'
    );
  };

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
  vistaActual = vista;

  document
    .querySelectorAll(
      '.detalle-menu-btn'
    )
    .forEach(b =>
      b.classList.toggle(
        'activo',
        b.dataset.vista === vista
      )
    );

  const mapaVisible =
    vista === 'mapa';

  $('mapa-container')
    ?.classList.toggle(
      'visible',
      mapaVisible
    );

  $('info-container')
    ?.classList.toggle(
      'hidden-view',
      mapaVisible
    );

  if (mapaVisible) {
    initMapa();

    setTimeout(() => {
      mapa?.invalidateSize();

      actualizarMapa(
        llamadaSeleccionada
      );
    }, 60);
  }
}


// ======================================================
// COLAS
// ======================================================

async function cargarCola() {
  if (!sesion) return;

  try {
    const rutas = {
      pendientes:
        '/api/llamadas/pending',

      'en-curso':
        '/api/llamadas/in-progress',

      cerradas:
        '/api/llamadas/closed?limit=100'
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
              throw new Error(
                'No se pudo cargar la cola'
              );
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

      actualizarMapa(
        llamadaSeleccionada
      );
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
        'Sin conexión';
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

  c.innerHTML = lista
    .map(l => {

      const prioridad =
        clasePrioridad(
          l.priority
        );

      const palabras =
        normalizarPalabrasDestacadas(
          l.highlightedWords
        );

      const transcripcion =
        limpiarTranscripcion(
          l.transcription
        );

      const resumen =
        l.operationalSummary ||
        generarResumenLocal(l);

      /*
       * IMPORTANTE:
       * La tarjeta muestra primero la transcripción real.
       * El resumen queda para el detalle del caso.
       */
      const textoPrincipal =
        transcripcion ||
        resumen ||
        'Sin información de la llamada.';

      const direccion =
        l.address ||
        l.addressDetectada ||
        'Dirección por determinar';

      const seleccionada =
        llamadaSeleccionada?.id ===
        l.id;

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
          ? '🔴 Urgente'
          : prioridad === 'ROJA'
            ? '🔴 Roja'
            : prioridad === 'MEDIA'
              ? '🟡 Media'
              : '🟢 Verde';

      return `
        <div
          class="llamada-item ${
            seleccionada
              ? 'seleccionada'
              : ''
          } ${
            prioridad === 'URGENTE'
              ? 'urgente'
              : ''
          }"
          data-id="${escapeHtml(l.id)}"
          role="button"
          tabindex="0"
        >

          <div class="llamada-header">

            <span class="llamada-id">
              Caso #${escapeHtml(l.id)}
            </span>

            <div class="llamada-badges">

              <span
                class="llamada-urgencia ${claseUrgencia}"
              >
                ${etiquetaPrioridad}
              </span>

            </div>

          </div>

          <div class="llamada-titulo">
            ${escapeHtml(
              obtenerTituloOperativo(l)
            )}
          </div>

          <div class="llamada-categoria">
            🎙️ Llamada en vivo
          </div>

          <div
            class="llamada-desc"
            title="${escapeHtml(
              textoPrincipal
            )}"
          >
            ${escapeHtml(
              truncar(
                textoPrincipal,
                160
              )
            )}
          </div>

          <div class="llamada-footer">

            <span class="ubicacion">
              📍 ${escapeHtml(
                direccion
              )}
            </span>

            <span>
              🕐 ${escapeHtml(
                formatearFecha(
                  l.createdAt
                )
              )}
            </span>

          </div>

          ${
            palabras.length
              ? `
                <div
                  class="llamada-palabras"
                  style="
                    display:flex;
                    gap:.25rem;
                    flex-wrap:wrap;
                    margin-top:.35rem;
                  "
                >
                  ${palabras
                    .slice(0, 4)
                    .map(
                      palabra => `
                        <span class="palabra">
                          ${escapeHtml(
                            palabra
                          )}
                        </span>
                      `
                    )
                    .join('')}
                </div>
              `
              : ''
          }

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
  const c =
    $('info-container');

  if (!c) return;

  if (!l) {
    c.innerHTML = `
      <p class="estado-vacio">
        Selecciona una llamada de la cola para ver el detalle.
      </p>
    `;

    return;
  }

  const prioridad =
    clasePrioridad(
      l.priority
    );

  const puedeAsignar =
    colaActual === 'pendientes';

  const puedeCerrar =
    colaActual === 'en-curso';

  const resumen =
    l.operationalSummary ||
    generarResumenLocal(l);

  const transcripcion =
    limpiarTranscripcion(
      l.transcription
    );

  const ubicacionEstado =
    l.latitude != null &&
    l.longitude != null
      ? '🟢 Ubicación disponible'
      : '🟡 Ubicación por determinar';

  const palabras =
    normalizarPalabrasDestacadas(
      l.highlightedWords
    );

  const direccion =
    l.address ||
    l.addressDetectada ||
    'Dirección por determinar';

  c.innerHTML = `
    <div class="detalle-contenido">

      <div class="detalle-cabecera">

        <span class="badge ${escapeHtml(
          prioridad
        )}">
          ${escapeHtml(
            prioridad
          )}
        </span>

        <span class="detalle-direccion">
          ${escapeHtml(
            direccion
          )}
        </span>

      </div>

      <div class="detalle-id">
        Caso #${escapeHtml(l.id)}
        · ${escapeHtml(
          formatearFecha(
            l.createdAt
          )
        )}
        ${
          l.assignedOperator
            ? ` · asignado a ${escapeHtml(
                l.assignedOperator
              )}`
            : ''
        }
      </div>

      <div class="detalle-grid">

        <div>
          <span class="detalle-label">
            Ubicación
          </span>

          <strong>
            ${escapeHtml(
              ubicacionEstado
            )}
          </strong>
        </div>

        <div>
          <span class="detalle-label">
            Estado
          </span>

          <strong>
            ${
              colaActual === 'pendientes'
                ? 'Sin asignar'
                : colaActual === 'en-curso'
                  ? 'En curso'
                  : 'Cerrada'
            }
          </strong>
        </div>

      </div>

      <section class="detalle-resumen">

        <div class="detalle-seccion-titulo">
          🧠 Resumen operativo
        </div>

        <p>
          ${escapeHtml(
            resumen
          )}
        </p>

      </section>

      <details class="detalle-transcripcion-bloque">

        <summary>
          🎙️ Ver transcripción completa
        </summary>

        <div class="detalle-transcripcion">
          ${escapeHtml(
            transcripcion ||
            '(sin transcripción)'
          )}
        </div>

      </details>

      <div class="detalle-meta">

        <strong>
          Motivos detectados:
        </strong>

        ${
          palabras.length
            ? palabras
                .map(
                  palabra =>
                    `<span class="palabra">${escapeHtml(
                      palabra
                    )}</span>`
                )
                .join(' ')
            : '<span class="palabra">Sin palabras clave detectadas</span>'
        }

      </div>

      ${
        l.closureComment
          ? `
            <div class="detalle-meta">
              Resolución:
              ${escapeHtml(
                l.closureComment
              )}
            </div>
          `
          : ''
      }

      <div class="detalle-acciones">

        ${
          puedeAsignar
            ? `
              <button
                class="btn-accion primario"
                id="accion-asignar"
              >
                ✓ Tomar caso
              </button>
            `
            : ''
        }

        ${
          puedeCerrar
            ? `
              <button
                class="btn-accion primario"
                id="accion-cerrar"
              >
                ✓ Cerrar caso
              </button>
            `
            : ''
        }

        ${
          l.latitude != null &&
          l.longitude != null
            ? `
              <button
                class="btn-accion secundario"
                id="accion-mapa"
              >
                🗺 Ver mapa
              </button>
            `
            : ''
        }

      </div>

    </div>
  `;

  $('accion-asignar')
    ?.addEventListener(
      'click',
      () =>
        asignarCaso(l.id)
    );

  $('accion-cerrar')
    ?.addEventListener(
      'click',
      () =>
        abrirModalCerrar(
          l.id
        )
    );

  $('accion-mapa')
    ?.addEventListener(
      'click',
      () =>
        cambiarVista(
          'mapa'
        )
    );
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

async function cargarUsuarios() {
  if (
    sesion?.role !==
    'administrador'
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
    `Total: ${usuarios.length} usuarios`;

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
        u => `
          <div class="usuario-card">

            <div class="info">

              <div>

                <div class="nombre">
                  ${escapeHtml(
                    u.fullName ||
                    u.username
                  )}
                </div>

                <div class="usuario">
                  @${escapeHtml(
                    u.username
                  )}
                  ${
                    u.email
                      ? ` · ${escapeHtml(
                          u.email
                        )}`
                      : ''
                  }
                </div>

              </div>

              <span class="rol-tag">
                ${escapeHtml(
                  u.role
                )}
              </span>

              ${
                u.institution
                  ? `
                    <span class="usuario-estado">
                      ${escapeHtml(
                        u.institution
                      )}
                    </span>
                  `
                  : ''
              }

              <span class="usuario-estado">
                ${
                  u.active
                    ? '● activo'
                    : '● pendiente'
                }
              </span>

            </div>

            <div class="acciones">

              ${
                u.active
                  ? `
                    <button
                      class="btn-accion peligro"
                      data-accion="desactivar"
                      data-id="${u.id}"
                      ${
                        u.username ===
                        sesion.username
                          ? 'disabled'
                          : ''
                      }
                    >
                      Desactivar
                    </button>
                  `
                  : `
                    <button
                      class="btn-accion primario"
                      data-accion="activar"
                      data-id="${u.id}"
                    >
                      Activar
                    </button>
                  `
              }

            </div>

          </div>
        `
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
            cambiarEstadoUsuario(
              Number(
                b.dataset.id
              ),
              b.dataset.accion
            )
        )
    );
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
    'administrador'
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
// LLAMADA EN VIVO / SHERPA-ONNX
// ======================================================

const ASR_WS_URL =
  'ws://localhost:6006';

let live = {
  ws: null,
  stream: null,
  audioContext: null,
  source: null,
  processor: null,
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

  $('live-asr').textContent =
    'ASR: desconectado';

  setLiveTranscript(
    'Presiona “Iniciar micrófono” para comenzar…'
  );

  $('live-iniciar').disabled =
    false;

  $('live-detener').disabled =
    true;

  $('live-nota').textContent =
    'Necesitas tener el servidor ASR en ws://localhost:6006.';
}

function cerrarLive() {
  if (live.startedAt) {
    mostrarAlerta(
      'Detén y guarda la llamada antes de cerrar esta ventana.',
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

function downsampleBuffer(
  buffer,
  inputRate,
  outputRate = 16000
) {
  if (
    inputRate ===
    outputRate
  ) {
    return buffer;
  }

  const ratio =
    inputRate /
    outputRate;

  const newLength =
    Math.round(
      buffer.length /
        ratio
    );

  const result =
    new Float32Array(
      newLength
    );

  let offsetResult = 0;
  let offsetBuffer = 0;

  while (
    offsetResult <
    result.length
  ) {
    const nextOffsetBuffer =
      Math.round(
        (offsetResult + 1) *
          ratio
      );

    let accum = 0;
    let count = 0;

    for (
      let i =
        offsetBuffer;
      i <
        nextOffsetBuffer &&
      i <
        buffer.length;
      i++
    ) {
      accum +=
        buffer[i];

      count++;
    }

    result[offsetResult] =
      count
        ? accum / count
        : 0;

    offsetResult++;

    offsetBuffer =
      nextOffsetBuffer;
  }

  return result;
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

    /*
     * Ubicación del operador:
     * solamente se utiliza como contexto
     * para ordenar candidatos geográficos.
     *
     * NO reemplaza la ubicación de la emergencia
     * y NO se guarda como ubicación del incidente.
     */
    live.ubicacionOperador =
      await obtenerUbicacionOperador();

    if (
      live.ubicacionOperador
    ) {

      $('live-nota').textContent =
        'Micrófono conectado · ubicación cercana disponible para mejorar la búsqueda en el mapa.';

    } else {

      $('live-nota').textContent =
        'Micrófono conectado · sin ubicación del equipo; la búsqueda del mapa seguirá funcionando.';
    }

    live.ws =
      new WebSocket(
        ASR_WS_URL
      );

    live.ws.binaryType =
      'arraybuffer';

    live.ws.onopen =
      async () => {

        try {

          live.audioContext =
            new (
              window.AudioContext ||
              window.webkitAudioContext
            )();

          await live.audioContext.resume();

          live.source =
            live.audioContext.createMediaStreamSource(
              live.stream
            );

          live.processor =
            live.audioContext.createScriptProcessor(
              4096,
              1,
              1
            );

          live.processor.onaudioprocess =
            event => {

              if (
                !live.ws ||
                live.ws.readyState !==
                  WebSocket.OPEN ||
                live.stopping
              ) {
                return;
              }

              const input =
                event.inputBuffer.getChannelData(
                  0
                );

              const samples =
                downsampleBuffer(
                  input,
                  live.audioContext.sampleRate,
                  16000
                );

              if (
                samples.length
              ) {
                live.ws.send(
                  samples.buffer
                );
              }
            };

          live.source.connect(
            live.processor
          );

          /*
           * Nodo silencioso para mantener
           * activo el procesamiento sin
           * reproducir la voz por los parlantes.
           */
          const silencioso =
            live.audioContext.createGain();

          silencioso.gain.value =
            0;

          live.processor.connect(
            silencioso
          );

          silencioso.connect(
            live.audioContext.destination
          );

          const mime =
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
                  mime
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

          setLiveStatus(
            'EN VIVO',
            'activo'
          );

          $('live-asr').textContent =
            'ASR: conectado · 16 kHz';

          setLiveTranscript(
            'Escuchando…'
          );

          registrarEvento(
            'Llamada en vivo iniciada.',
            'admin'
          );

        } catch (err) {

          detenerLive(
            false
          );

          throw err;
        }
      };

    live.ws.onmessage =
      event => {

        const bruto =
          String(
            event.data || ''
          ).trim();

        if (
          !bruto ||
          bruto === 'Done!'
        ) {
          return;
        }

        const texto =
          limpiarTranscripcion(
            bruto
          );

        if (!texto) {
          return;
        }

        /*
         * Sherpa puede enviar JSON
         * con información técnica.
         *
         * La interfaz solo muestra
         * el texto humano.
         */
        live.transcript =
          texto;

        setLiveTranscript(
          texto
        );
      };

    live.ws.onerror =
      () => {

        setLiveStatus(
          'ASR sin conexión',
          'error'
        );

        $('live-asr').textContent =
          'ASR: error';

        $('live-nota').textContent =
          'No se pudo conectar a ws://localhost:6006. Inicia start_asr_windows.ps1.';
      };

    live.ws.onclose =
      () => {

        if (
          !live.stopping &&
          live.startedAt
        ) {

          setLiveStatus(
            'ASR desconectado',
            'error'
          );

          $('live-asr').textContent =
            'ASR: desconectado';
        }
      };

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
      err?.name ===
      'NotAllowedError'
        ? 'El navegador bloqueó el micrófono.'
        : 'No se pudo iniciar la llamada en vivo.';

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

  clearInterval(
    live.timer
  );

  live.timer =
    null;

  if (
    live.processor
  ) {
    live.processor.disconnect();

    live.processor.onaudioprocess =
      null;
  }

  if (
    live.source
  ) {
    live.source.disconnect();
  }

  if (
    live.audioContext
  ) {

    try {
      await live.audioContext.close();
    } catch {}
  }

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

  if (
    live.ws &&
    live.ws.readyState ===
      WebSocket.OPEN
  ) {
    live.ws.close();
  }

  await grabacionPromise;

  const transcript =
    limpiarTranscripcion(
      live.transcript
    );

  live.startedAt =
    null;

  $('live-iniciar').disabled =
    false;

  $('live-detener').disabled =
    true;

  $('live-asr').textContent =
    'ASR: detenido';

  setLiveStatus(
    guardar
      ? 'Procesando caso…'
      : 'Detenido'
  );

  if (!guardar) {

    live = {
      ...live,

      ws: null,
      stream: null,
      audioContext: null,
      source: null,
      processor: null,
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

  if (!transcript) {

    setLiveStatus(
      'Sin texto',
      'error'
    );

    mostrarAlerta(
      'No se obtuvo transcripción. Revisa el servidor ASR y vuelve a intentar.',
      'error'
    );

    live.stopping =
      false;

    return;
  }

  try {

    const form =
      new FormData();

    const datos =
      new Blob(
        [
          JSON.stringify({
            transcription:
              transcript,

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

      const blob =
        new Blob(
          live.chunks,
          {
            type:
              live.chunks[0]
                ?.type ||
              'audio/webm'
          }
        );

      form.append(
        'audio',
        blob,
        `llamada-${Date.now()}.webm`
      );
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

    $('live-asr').textContent =
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

        live.stopping =
          false;

      },
      900
    );

  } catch (e) {

    setLiveStatus(
      'Error al guardar',
      'error'
    );

    $('live-asr').textContent =
      'ASR: transcripción disponible';

    mostrarAlerta(
      e.message ||
      'No se pudo guardar la llamada.',
      'error'
    );

    setLiveTranscript(
      transcript
    );

    live.stopping =
      false;
  }

  live = {
    ...live,

    ws: null,
    stream: null,
    audioContext: null,
    source: null,
    processor: null,
    mediaRecorder: null,
    chunks: [],
    transcript: '',
    stopping: false,

    ubicacionOperador: {
      lat: null,
      lng: null
    }
  };
}


// ======================================================
// EVENTOS UI
// ======================================================

document.addEventListener(
  'DOMContentLoaded',
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

              $('form-login').hidden =
                tab.dataset.tab !==
                'login';

              $('form-registro').hidden =
                tab.dataset.tab !==
                'registro';

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

    document
      .querySelectorAll(
        '.detalle-menu-btn'
      )
      .forEach(
        b =>
          b.addEventListener(
            'click',
            () =>
              cambiarVista(
                b.dataset.vista
              )
          )
      );

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

    window.addEventListener(
      'resize',
      () => {

        if (
          vistaActual ===
          'mapa'
        ) {
          setTimeout(
            () =>
              mapa?.invalidateSize(),
            100
          );
        }

      }
    );

    renderizarFeed();

    if ($('pantalla-auth')) {
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
        'administrador' &&
      adminTabActual ===
        'usuarios'
    ) {
      await cargarUsuarios();
    }

  },
  REFRESCO_MS
);