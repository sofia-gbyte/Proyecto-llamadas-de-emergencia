(() => {
  const $ = id => document.getElementById(id);
  const state = {
    token: '',
    captchaRequired: true,
    captchaToken: '',
    mfaLoginRequired: false,
    mfaEnrollment: false,
    refreshTimer: null
  };

  const showView = view => {
    $('login-view').hidden = view !== 'login';
    $('totp-setup-view').hidden = view !== 'totp-setup';
    $('cases-view').hidden = view !== 'cases';
    $('detail-view').hidden = view !== 'detail';
  };

  const setConnection = (online, text) => {
    const indicator = $('connection-status');
    indicator.className = `connection ${online ? 'online' : 'offline'}`;
    indicator.textContent = text;
  };

  const api = async (path, options = {}) => {
    const headers = new Headers(options.headers || {});
    headers.set('Content-Type', 'application/json');
    if (state.token) headers.set('Authorization', `Bearer ${state.token}`);
    const response = await fetch(path, { ...options, headers, cache: 'no-store' });
    const data = response.status === 204 ? {} : await response.json().catch(() => ({}));
    if (response.status === 401) {
      state.token = '';
      state.mfaEnrollment = false;
      showView('login');
      showLoginError('La sesión venció o fue cerrada. Inicia sesión nuevamente.');
    }
    if (!response.ok) {
      const error = new Error(data.error || data.message || `Error HTTP ${response.status}`);
      error.details = data;
      error.status = response.status;
      throw error;
    }
    return data;
  };

  const setCaptchaState = event => {
    state.captchaToken = event.detail?.payload || '';
  };

  const showLoginError = message => {
    $('login-error').textContent = message;
    $('login-error').hidden = false;
  };

  const finishLogin = async data => {
    if (data.role !== 'operator' || !data.token) {
      throw new Error('La app móvil está disponible solo para cuentas de operador.');
    }
    state.token = data.token;
    state.mfaEnrollment = false;
    state.mfaLoginRequired = false;
    $('password').value = '';
    $('totp-code').value = '';
    $('totp-login-area').hidden = true;
    $('login-captcha')?.reset?.();
    state.captchaToken = '';
    await loadCases();
    startSync();
  };

  const login = async event => {
    event.preventDefault();
    $('login-error').hidden = true;
    if (state.captchaRequired && !state.captchaToken) {
      showLoginError('Completa la verificación de seguridad.');
      return;
    }
    const button = $('login-button');
    button.disabled = true;
    try {
      const data = await api('/api/auth/mobile/login', {
        method: 'POST',
        body: JSON.stringify({
          username: $('username').value.trim(),
          password: $('password').value,
          captchaToken: state.captchaRequired ? state.captchaToken : '',
          totpCode: state.mfaLoginRequired ? $('totp-code').value.trim() : null
        })
      });
      if (data.mfaSetupRequired && data.token) {
        state.token = data.token;
        state.mfaEnrollment = true;
        $('password').value = '';
        const setup = await api('/api/auth/mobile/totp/setup', { method: 'POST', body: '{}' });
        $('totp-setup-secret').textContent = setup.secret;
        $('totp-setup-code').value = '';
        $('totp-setup-error').hidden = true;
        showView('totp-setup');
        return;
      }
      await finishLogin(data);
    } catch (error) {
      if (error.details?.mfaRequired) {
        state.mfaLoginRequired = true;
        $('totp-login-area').hidden = false;
        $('totp-code').required = true;
        $('totp-code').focus();
        showLoginError('Ingresa el código actual de tu app autenticadora.');
      } else {
        showLoginError(error.message || 'No se pudo iniciar sesión.');
      }
      if (state.captchaRequired) $('login-captcha')?.reset?.();
      state.captchaToken = '';
    } finally {
      button.disabled = false;
    }
  };

  const verifyTotpSetup = async event => {
    event.preventDefault();
    $('totp-setup-error').hidden = true;
    const button = $('totp-setup-button');
    button.disabled = true;
    try {
      const data = await api('/api/auth/mobile/totp/verify-setup', {
        method: 'POST',
        body: JSON.stringify({ code: $('totp-setup-code').value.trim() })
      });
      await finishLogin(data);
    } catch (error) {
      if (error.status === 401) {
        state.token = '';
        state.mfaEnrollment = false;
        showView('login');
        showLoginError('La configuración venció. Inicia sesión y vuelve a intentarlo.');
      } else {
        $('totp-setup-error').textContent = error.message || 'No se pudo activar la verificación.';
        $('totp-setup-error').hidden = false;
      }
    } finally {
      button.disabled = false;
    }
  };

  const makeCaseCard = (call, assigned) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'case-card';
    button.addEventListener('click', () => openCase(call.id));

    const top = document.createElement('div');
    top.className = 'case-card-top';
    const id = document.createElement('span');
    id.className = 'case-id';
    id.textContent = `CASO #${call.id}`;
    const priority = document.createElement('span');
    priority.className = `priority priority-${call.priority || ''}`;
    priority.textContent = call.priority || 'SIN PRIORIDAD';
    top.append(id, priority);

    const summary = document.createElement('p');
    summary.className = 'case-summary';
    summary.textContent = call.operationalSummary || 'Sin resumen operativo';
    const address = document.createElement('p');
    address.className = 'case-address';
    address.textContent = call.address || 'Dirección pendiente';
    const action = document.createElement('p');
    action.className = 'case-address';
    action.textContent = assigned ? 'Asignado a ti · Ver caso' : 'Pendiente · Revisar y asignar';
    button.append(top, summary, address, action);
    return button;
  };

  const renderCases = queues => {
    const inProgressList = $('in-progress-list');
    const pendingList = $('pending-list');
    inProgressList.replaceChildren(...queues.inProgress.map(call => makeCaseCard(call, true)));
    pendingList.replaceChildren(...queues.pending.map(call => makeCaseCard(call, false)));
    $('in-progress-count').textContent = String(queues.inProgress.length);
    $('pending-count').textContent = String(queues.pending.length);
    $('in-progress-empty').hidden = queues.inProgress.length > 0;
    $('pending-empty').hidden = queues.pending.length > 0;
  };

  const loadCases = async () => {
    $('sync-message').textContent = 'Sincronizando con el servidor…';
    try {
      const queues = await api('/api/mobile/cases');
      renderCases(queues);
      setConnection(true, 'Conectado');
      $('sync-message').textContent = `Actualizado ${new Date().toLocaleTimeString()}`;
    } catch (error) {
      setConnection(false, 'Sin conexión');
      $('sync-message').textContent = `${error.message}. Se volverá a intentar automáticamente.`;
      throw error;
    }
  };

  const startSync = () => {
    if (state.refreshTimer) clearInterval(state.refreshTimer);
    state.refreshTimer = setInterval(() => {
      if (!$('cases-view').hidden) loadCases().catch(() => {});
    }, 10000);
    showView('cases');
  };

  const addDetailText = (parent, className, text) => {
    const element = document.createElement('p');
    element.className = className;
    element.textContent = text;
    parent.append(element);
    return element;
  };

  const openCase = async id => {
    try {
      const call = await api(`/api/mobile/cases/${encodeURIComponent(id)}`);
      const article = $('case-detail');
      article.replaceChildren();

      const meta = document.createElement('div');
      meta.className = 'detail-meta';
      const caseId = document.createElement('span');
      caseId.className = 'case-id';
      caseId.textContent = `CASO #${call.id}`;
      const priority = document.createElement('span');
      priority.className = `priority priority-${call.priority || ''}`;
      priority.textContent = call.priority || 'SIN PRIORIDAD';
      meta.append(caseId, priority);

      const addressTitle = addDetailText(article, 'detail-section-title', 'Dirección');
      const address = document.createElement('div');
      address.className = 'detail-address';
      address.append(document.createTextNode(call.address || 'Dirección pendiente de confirmar'));
      if (call.latitude != null && call.longitude != null || call.address) {
        const navigate = document.createElement('a');
        navigate.className = 'map-link';
        navigate.textContent = 'Abrir navegación desde mi ubicación →';
        navigate.href = call.latitude != null && call.longitude != null
          ? `google.navigation:q=${encodeURIComponent(`${call.latitude},${call.longitude}`)}`
          : `geo:0,0?q=${encodeURIComponent(call.address)}`;
        address.append(document.createElement('br'), navigate);
      }

      const summaryTitle = document.createElement('p');
      summaryTitle.className = 'detail-section-title';
      summaryTitle.textContent = 'Resumen operativo';
      const summary = document.createElement('p');
      summary.className = 'detail-summary';
      summary.textContent = call.operationalSummary || 'Sin resumen operativo';
      article.append(meta, addressTitle, address, summaryTitle, summary);

      if (call.assigned) {
        addDetailText(article, 'case-address', `Asignado a ${call.assignedOperator || 'operador'}`);
        const label = document.createElement('label');
        label.htmlFor = 'closure-comment';
        label.textContent = 'Comentario de cierre';
        const comment = document.createElement('textarea');
        comment.id = 'closure-comment';
        comment.maxLength = 2000;
        comment.placeholder = 'Describe brevemente el resultado del caso';
        const closeButton = document.createElement('button');
        closeButton.type = 'button';
        closeButton.className = 'secondary-button danger-button';
        closeButton.textContent = 'Cerrar caso';
        closeButton.addEventListener('click', () => closeCase(call.id, comment.value));
        article.append(label, comment, closeButton);
      } else {
        const assignButton = document.createElement('button');
        assignButton.type = 'button';
        assignButton.className = 'primary-button';
        assignButton.textContent = 'Tomar este caso';
        assignButton.addEventListener('click', () => assignCase(call.id));
        article.append(assignButton);
      }
      showView('detail');
    } catch (error) {
      $('sync-message').textContent = error.message;
      showView('cases');
    }
  };

  const assignCase = async id => {
    try {
      await api(`/api/mobile/cases/${encodeURIComponent(id)}/assign`, { method: 'POST', body: '{}' });
      await loadCases();
      await openCase(id);
    } catch (error) {
      alert(error.message);
      loadCases().catch(() => {});
    }
  };

  const closeCase = async (id, comment) => {
    if (!comment.trim()) {
      alert('Escribe un comentario antes de cerrar el caso.');
      return;
    }
    if (!confirm('¿Confirmas que quieres cerrar este caso?')) return;
    try {
      await api(`/api/mobile/cases/${encodeURIComponent(id)}/close`, {
        method: 'POST',
        body: JSON.stringify({ comment: comment.trim() })
      });
      showView('cases');
      await loadCases();
    } catch (error) {
      alert(`No se pudo cerrar el caso: ${error.message}`);
    }
  };

  const logout = async () => {
    let logoutError = '';
    if (state.token && !state.mfaEnrollment) {
      try {
        await api('/api/auth/mobile/logout', { method: 'POST', body: '{}' });
      } catch (error) {
        logoutError = `La sesión se cerró en este dispositivo, pero no se pudo invalidar en el servidor: ${error.message}`;
      }
    }
    state.token = '';
    state.mfaEnrollment = false;
    state.mfaLoginRequired = false;
    if (state.refreshTimer) clearInterval(state.refreshTimer);
    state.refreshTimer = null;
    $('password').value = '';
    $('totp-code').value = '';
    $('totp-code').required = false;
    $('totp-login-area').hidden = true;
    $('login-error').hidden = true;
    if (logoutError) showLoginError(logoutError);
    showView('login');
  };

  const initialize = async () => {
    $('login-form').addEventListener('submit', login);
    $('totp-setup-form').addEventListener('submit', verifyTotpSetup);
    $('cancel-totp-setup').addEventListener('click', () => {
      state.token = '';
      state.mfaEnrollment = false;
      showView('login');
      showLoginError('La configuración no terminó. Vuelve a iniciar sesión para continuar.');
    });
    $('login-captcha').addEventListener('verified', setCaptchaState);
    $('login-captcha').addEventListener('statechange', event => {
      if (event.detail?.state !== 'verified') state.captchaToken = '';
    });
    $('refresh-button').addEventListener('click', () => loadCases().catch(() => {}));
    $('back-button').addEventListener('click', () => {
      showView('cases');
      loadCases().catch(() => {});
    });
    $('logout-button').addEventListener('click', logout);

    try {
      const captchaResponse = await fetch('/api/auth/captcha-config', { cache: 'no-store' });
      if (!captchaResponse.ok) throw new Error('No se pudo consultar la configuración de inicio de sesión.');
      const captcha = await captchaResponse.json();
      state.captchaRequired = Boolean(captcha.enabled);
      $('captcha-area').hidden = !state.captchaRequired;
      if (state.captchaRequired) await window.altchaReadyPromise();
      if (state.token) {
        try {
          await loadCases();
          startSync();
          return;
        } catch {
          logout();
        }
      }
      setConnection(true, 'Servidor disponible');
      showView('login');
    } catch (error) {
      setConnection(false, 'No disponible');
      showLoginError(error.message || 'No se pudo preparar el inicio de sesión.');
      showView('login');
    }
  };

  initialize();
})();
