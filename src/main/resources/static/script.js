/* CODES · Frontend integrado con Spring Boot */
const API_URL = '/api';
const REFRESCO_MS = 8000;

let sesion = null;
let mapa = null;
let marcador = null;
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

const $ = (id) => document.getElementById(id);

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
  const rol = sesion?.rol || 'operador';

  if ($('rol-tag')) {
    $('rol-tag').textContent = rol;
  }

  if ($('usuario-nombre')) {
    $('usuario-nombre').textContent =
      sesion?.nombreUsuario || 'usuario';
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

  try {
    const resp = await fetch(
      `${API_URL}/auth/login`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          nombreUsuario,
          password
        })
      }
    );

    const d = await leerRespuesta(resp);

    if (!resp.ok) {
      error.textContent =
        d.error ||
        'No se pudo iniciar sesión.';

      error.hidden = false;
      return;
    }

    sesion = d;

    $('login-password').value = '';

    ocultarPantallaAuth();
    actualizarUsuarioUI();

    registrarEvento(
      `Inició sesión como ${d.nombreUsuario}.`,
      'admin'
    );

    await entrarConsola();

  } catch (err) {
    error.textContent =
      'No se pudo conectar con el servidor. ¿Está ejecutándose Spring Boot?';

    error.hidden = false;
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
    nombreUsuario:
      $('reg-usuario').value.trim(),

    password,

    nombre:
      $('reg-nombre').value.trim(),

    apellido:
      $('reg-apellido').value.trim(),

    correo:
      $('reg-correo').value.trim(),

    institucion:
      $('reg-institucion').value
  };

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
      return;
    }

    ok.textContent =
      d.mensaje ||
      'Cuenta creada. Un administrador debe activarla antes de iniciar sesión.';

    ok.hidden = false;

    e.target.reset();

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
  }
}

function cerrarSesion() {
  if (!sesion) return;

  if (live.startedAt) {
    detenerLive(false);
  }

  const usuario =
    sesion.nombreUsuario;

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

  if (sesion?.rol === 'administrador') {
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
}

function actualizarMapa(l) {
  if (!l) {
    $('mapa-titulo').textContent =
      'Sin incidente seleccionado';

    $('mapa-direccion').textContent =
      'Selecciona una llamada para mostrar su ubicación.';

    $('btn-abrir-mapa').disabled = true;

    return;
  }

  $('mapa-titulo').textContent =
    `Caso #${l.id} · ${l.prioridad || 'SIN PRIORIDAD'}`;

  $('mapa-direccion').textContent =
    l.direccion ||
    l.direccionDetectada ||
    'Dirección por determinar';

  const btn =
    $('btn-abrir-mapa');

  btn.disabled = !(
    l.latitud != null &&
    l.longitud != null
  );

  btn.onclick = () => {
    if (
      l.latitud == null ||
      l.longitud == null
    ) {
      return;
    }

    window.open(
      `https://www.google.com/maps/search/?api=1&query=${
        encodeURIComponent(
          `${l.latitud},${l.longitud}`
        )
      }`,
      '_blank',
      'noopener'
    );
  };

  if (
    l.latitud == null ||
    l.longitud == null
  ) {
    return;
  }

  initMapa();

  if (!mapa) return;

  mapa.setView(
    [l.latitud, l.longitud],
    16
  );

  if (marcador) {
    mapa.removeLayer(marcador);
  }

  marcador =
    L.marker([
      l.latitud,
      l.longitud
    ])
      .addTo(mapa)
      .bindPopup(
        escapeHtml(
          l.direccion ||
          l.direccionDetectada ||
          `Caso #${l.id}`
        )
      );

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
        '/llamadas/pendientes',

      'en-curso':
        '/llamadas/en-progreso',

      cerradas:
        '/llamadas/cerradas?limite=100'
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
      l.palabrasDestacadas
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
          l.prioridad
        );

      const palabras =
        normalizarPalabrasDestacadas(
          l.palabrasDestacadas
        );

      const transcripcion =
        limpiarTranscripcion(
          l.transcripcion
        );

      const resumen =
        l.resumenOperativo ||
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
        l.direccion ||
        l.direccionDetectada ||
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
                  l.fechaHora
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
      l.palabrasDestacadas
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
    l.direccion ||
    l.direccionDetectada
      ? ` en ${
          l.direccion ||
          l.direccionDetectada
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
      l.prioridad
    );

  const puedeAsignar =
    colaActual === 'pendientes';

  const puedeCerrar =
    colaActual === 'en-curso';

  const resumen =
    l.resumenOperativo ||
    generarResumenLocal(l);

  const transcripcion =
    limpiarTranscripcion(
      l.transcripcion
    );

  const ubicacionEstado =
    l.latitud != null &&
    l.longitud != null
      ? '🟢 Ubicación disponible'
      : '🟡 Ubicación por determinar';

  const palabras =
    normalizarPalabrasDestacadas(
      l.palabrasDestacadas
    );

  const direccion =
    l.direccion ||
    l.direccionDetectada ||
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
            l.fechaHora
          )
        )}
        ${
          l.operadorAsignado
            ? ` · asignado a ${escapeHtml(
                l.operadorAsignado
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
        l.comentarioCierre
          ? `
            <div class="detalle-meta">
              Resolución:
              ${escapeHtml(
                l.comentarioCierre
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
          l.latitud != null &&
          l.longitud != null
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
        `/llamadas/${id}/asignar`,
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
      d.mensaje ||
      'Caso asignado.',
      'ok'
    );

    registrarEvento(
      d.mensaje ||
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
            `/llamadas/${id}/cerrar`,
            {
              method: 'POST',
              headers: {
                'Content-Type':
                  'application/json'
              },
              body:
                JSON.stringify({
                  comentario
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
          d.mensaje ||
          'Caso cerrado.',
          'ok'
        );

        registrarEvento(
          d.mensaje ||
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
        '/metricas'
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
    sesion?.rol !==
    'administrador'
  ) {
    return;
  }

  try {
    const r =
      await apiFetch(
        '/usuarios'
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
                    u.nombreCompleto ||
                    u.nombreUsuario
                  )}
                </div>

                <div class="usuario">
                  @${escapeHtml(
                    u.nombreUsuario
                  )}
                  ${
                    u.correo
                      ? ` · ${escapeHtml(
                          u.correo
                        )}`
                      : ''
                  }
                </div>

              </div>

              <span class="rol-tag">
                ${escapeHtml(
                  u.rol
                )}
              </span>

              ${
                u.institucion
                  ? `
                    <span class="usuario-estado">
                      ${escapeHtml(
                        u.institucion
                      )}
                    </span>
                  `
                  : ''
              }

              <span class="usuario-estado">
                ${
                  u.activo
                    ? '● activo'
                    : '● pendiente'
                }
              </span>

            </div>

            <div class="acciones">

              ${
                u.activo
                  ? `
                    <button
                      class="btn-accion peligro"
                      data-accion="desactivar"
                      data-id="${u.id}"
                      ${
                        u.nombreUsuario ===
                        sesion.nombreUsuario
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
        `/usuarios/${id}/${accion}`,
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
      `${d.nombreUsuario}: ${
        d.activo
          ? 'activado'
          : 'desactivado'
      }.`,
      'ok'
    );

    registrarEvento(
      `${d.nombreUsuario} fue ${
        d.activo
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
    sesion.rol !==
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
            transcripcion:
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
        '/llamadas/en-vivo',
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
        d.prioridad ||
        'clasificando'
      }`;

    mostrarAlerta(
      `Llamada en vivo convertida en caso #${d.id} · ${d.prioridad}`,
      'ok'
    );

    registrarEvento(
      `Llamada en vivo guardada como caso #${d.id} (${d.prioridad}).`,
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
      sesion.rol ===
        'administrador' &&
      adminTabActual ===
        'usuarios'
    ) {
      await cargarUsuarios();
    }

  },
  REFRESCO_MS
);