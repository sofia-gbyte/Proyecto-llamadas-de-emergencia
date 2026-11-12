# Auditoría CODES 1.0.2 — operación, clasificación y seguimiento de casos

## Alcance y fuentes

La ejecución comenzó con los dos hallazgos autorizados en `.github/modernize/codes-1.0.2-audit-fixes/plan.md`:

1. Limitar el cierre del launcher/watchdog a árboles de procesos iniciados por el propio launcher.
2. Excluir respaldos y logs locales del ZIP ligero.

La solicitud posterior amplió el trabajo a clasificación y seguimiento de casos, privacidad de logs y empaquetado local. El `README.md` y el comportamiento actual del código se usaron como fuente de verdad para resolver discrepancias tecnológicas. CODES usa Groq Whisper para transcripción; no usa `sherpa-onnx`.

El workspace no contiene metadatos Git, por lo que no se declara una rama ni un diff.

## Cambios realizados

### Launcher y ZIP ligero

- `tools/iniciar_codes_windows.ps1` ya no busca ni termina procesos según el puerto 8000. El aviso por puerto ocupado se conserva.
- El launcher y `tools/codes_watchdog.ps1` comprueban PID e identidad temporal del proceso antes de cerrar únicamente árboles que ellos iniciaron. No se termina un listener desconocido.
- `tools/limpiar_proyecto.ps1` excluye respaldos y logs locales del ZIP ligero, incluidos `backups`, `logs`, `app-run.log` y archivos `*.log`.
- Las pruebas de scripts usan un ZIP sintético y no borran datos del usuario.

### Clasificación y actualizaciones de casos

- `POST /api/llamadas/{id}/updates` permite agregar antecedentes a un caso abierto al operador asignado o a un supervisor autorizado. No crea una llamada nueva ni permite que operadores no asignados o cuentas de vista previa alteren el caso.
- Cada actualización conserva UUID, texto, autor y fecha. Los reintentos por UUID son idempotentes; también se evita guardar texto duplicado tras normalizar espacios y mayúsculas. El límite es 5.000 caracteres por aporte y 20.000 por caso.
- Se recalculan clasificación, prioridad, motivos e instituciones recomendadas con la transcripción original y el historial. No se modifican automáticamente la dirección ni las coordenadas, y las recomendaciones no despachan servicios externos.
- El historial se entrega en `CallResponse` y se muestra en español con escape HTML.
- La clasificación reconoce variantes plurales de personas atrapadas.

### Privacidad, mapa y empaquetado

- Los logs ya no guardan transcripciones completas ni nombres/rutas de audios. Los errores del proveedor de transcripción tampoco incluyen su cuerpo de respuesta.
- El mapa conserva Leaflet, Esri, OSRM, Geoapify y la geolocalización del navegador. Se corrigió únicamente la ruta de Leaflet para coincidir con los archivos reales del WebJar (`/webjars/leaflet/1.9.4/leaflet.js` y `.css`), sin rediseñar el mapa ni depender de una CDN para cargar la biblioteca.
- La prueba de navegador demostró que `initializeMap` buscaba el ID inexistente `map` en vez de `mapa`, y que el botón de reintento tenía un selector distinto al HTML. Se alinearon esos dos selectores; no se modificaron los proveedores cartográficos ni la lógica de geolocalización.
- `tools/package_codes_windows.ps1` crea una app-image mediante `jpackage`, con runtime Java incluido. Los datos se guardan bajo `%LOCALAPPDATA%\CODES\data` salvo que se configure `CODES_DATA_DIR`.
- El bootstrap local lee asignaciones permitidas de secretos sin ejecutar el archivo PowerShell. Los secretos locales permanecen en archivos de texto; deben protegerse y respaldarse de forma segura.

## Comportamiento y límites preservados

- Groq Whisper sigue siendo el proveedor de transcripción; no se incorporó otro motor.
- Los servicios cartográficos y sus datos siguen siendo externos: Geoapify recibe direcciones al geocodificar, OSRM recibe coordenadas al solicitar rutas y Esri sirve el mapa base. El README advierte revisar términos y privacidad antes de usar datos reales.
- CODES continúa siendo una aplicación para demostración y uso local controlado. No queda certificada para Internet ni para despacho real.
- La app-image no es un instalador firmado. Se debe distribuir la carpeta completa, incluido `runtime`, y no solo `CODES.exe`.
- La conversión amplia de identificadores internos sigue incompleta; esta Fase 2 renombra componentes y variables seleccionados, pero no cambia claves ni rutas públicas en español.

## FASE 2 — CLASIFICACIÓN Y ROUTING

### Clasificador y contexto

- Se amplió el diccionario de clasificación y se añadieron categorías estructuradas para incendio estructural, vehicular y forestal; humo; explosión; fuga de gas; materiales peligrosos; accidente vehicular, colisión, atropello y volcamiento; persona atrapada y rescate; inconsciencia, dificultad respiratoria, convulsión, heridas, sangrado y emergencias médicas; derrumbe, inundación y riesgos eléctricos; accidentes laborales y emergencias en colegios, edificios y espacios públicos; robo, asalto, amenaza, violencia, desaparición y situaciones de riesgo.
- Se incorporaron expresiones naturales, raíces y variantes comunes, por ejemplo «se está quemando», «casa en llamas», «se volcó», «personas atrapadas», «no puede respirar» y «olor a gas». La normalización para comparar términos permite diferencias de tildes y una tolerancia acotada a errores menores; `originalText` conserva íntegra la transcripción recibida.
- El contexto distingue humo sin incendio confirmado de fuego confirmado, y considera lesiones, inconsciencia, atrapamiento, tipo de incidente y contexto del lugar para determinar categorías y recomendaciones. La clasificación sigue siendo heurística de demostración, no protocolo oficial.

### Gravedad y routing institucional

- El resultado incluye prioridad general, categorías detectadas y recomendaciones por institución con rol `PRIMARY`/`SECONDARY`, severidad y motivo. Se consideran Bomberos para fuego, humo, rescate, gas, derrumbe y riesgos afines; SAMU para lesiones y emergencias médicas; Carabineros para delitos, violencia, seguridad y tránsito.
- La gravedad es contextual por institución: un incendio confirmado prioriza Bomberos; lesiones o inconsciencia elevan SAMU; un choque sin heridos prioriza Carabineros sin recomendar automáticamente SAMU o Bomberos; rescate y riesgo específico pueden activar apoyos adicionales.
- Las recomendaciones son orientativas y no envían despachos ni notificaciones externas. La institución principal y las secundarias se incluyen en el caso para apoyar la decisión operativa.

### Interconectividad e idempotencia

- Una actualización de información vuelve a clasificar el texto original junto con los antecedentes nuevos, recalcula categorías, prioridades y recomendaciones, y persiste el resultado sobre el mismo caso.
- Los reintentos con el mismo UUID y el contenido repetido tras normalizar espacios/mayúsculas no agregan otra actualización. Las instituciones sugeridas se mantienen sin duplicados; la lógica no crea llamadas separadas ni genera ciclos de notificación, pues CODES no implementa notificaciones institucionales automáticas.

### Nombres internos, contratos y validación

- Se cambiaron a inglés identificadores seleccionados de Java/JavaScript y del DOM, incluidos `NumberWords`, coordenadas del operador, `stopLiveCall`, `publicConfigurationPromise` y los campos internos de login/registro (`login-username`, `reg-first-name`, `reg-last-name`, `reg-username`, `reg-email`, `reg-institution`). Se corrigieron las referencias correlacionadas.
- Las pruebas de navegador encontraron que el patrón de usuario no era válido con la expresión Unicode `v` de Chromium y que los selectores de login/registro no correspondían a los IDs HTML. Se corrigieron ambos problemas. También se evita que el control ALTCHA oculto bloquee formularios cuando la verificación está deshabilitada.
- ALTCHA conserva ahora su clave HMAC en los secretos locales cuando se genera automáticamente, para que desafíos nuevos sigan verificándose tras reiniciar CODES. No se desactivó ni se debilitó el CAPTCHA.
- Se conservan para compatibilidad las claves JSON y rutas públicas en español, además de nombres serializados y claves de configuración ya existentes. La interfaz visible continúa en español.
- La validación HTTP final conserva comillas simples/dobles, puntuación, `ñ`, vocales con tilde, emoji y números. Rechaza texto vacío, espacios, `null`, texto excesivo, coordenadas fuera de rango, JSON inválido y solicitudes incompletas con respuestas controladas.
- El renombrado integral de todos los artefactos JavaScript/Java sigue pendiente: se cambiaron y buscaron los identificadores relevantes de la aplicación activa, pero no se certifica una conversión exhaustiva del prototipo HTML heredado ni de todos los valores/alias internos en español. Los contratos públicos no se cambiaron silenciosamente.

### Pruebas y estado

- `ClassifierTest` cubre las frases operativas solicitadas y comprueba categorías, prioridad y recomendaciones; también cubre contexto, humo aislado, negaciones, puntuación/Unicode, tolerancia a transcripción y actualización/idempotencia.
- Pruebas de DTO comprueban límites de longitud/coordenadas y la compatibilidad de claves JSON de solicitudes de llamada y registro. `CallServiceTest` comprueba las claves de métricas existentes y la actualización del mismo caso.
- `mvn clean verify` (revalidación del 2026-11-03): **65 pruebas**, 0 fallidas, 0 errores y 0 omitidas; incluye persistencia y reutilización de la clave ALTCHA al reiniciar el servicio, además de verificación sin una transacción externa.
- `tools/tests/test_http_validation.ps1`: **46 comprobaciones HTTP aprobadas** contra la app-image aislada. Incluye JSON inválido/null/incompleto, autenticación, permisos (401/403), registro Unicode, transcripciones límite, coordenadas, puntuación/comillas/emoji, clasificación, actualización del mismo caso y deduplicación.
- `tools/tests/test_audit_fixes.ps1`: **15 aprobadas**, 0 fallidas, 0 omitidas; incluye scripts, ZIP sintético y comprobación del watchdog frente a un PID ajeno.
- Verificación directa de la app-image: `/api/health`, `/api/heartbeat`, `/`, `script.js`, Leaflet JS y CSS respondieron HTTP 200.
- Navegador sobre la app-image: registro HTTP 201 y login HTTP 200; el formulario quedó válido y el mensaje de registro fue español. Leaflet creó el contenedor y las capas del mapa. La geolocalización se probó con coordenadas simuladas; el permiso real del dispositivo no pudo concederse. Tampoco se validaron solicitudes reales a Esri, Geoapify u OSRM.
- La prueba previa del servicio aceptaba el token, pero no cubría la ruta HTTP real: fuera de una transacción de prueba, la limpieza JPA de desafíos vencidos fallaba y el login respondía HTTP 403. Se corrigió haciendo transaccional esa operación derivada.
- Revalidación de CAPTCHA en la app-image corregida: el widget del navegador generó una solución y el login de un usuario deliberadamente inexistente respondió HTTP 401 («usuario o contraseña inválidos»), confirmando que el CAPTCHA pasó; la interfaz ya no mostró «Captcha inválido o no configurado». `/api/auth/captcha-config` respondió HTTP 200 con `enabled: true`.
- No hubo excepciones JavaScript en la sesión final del navegador. Se observaron solicitudes de heartbeat abortadas durante la navegación automatizada; la verificación directa de `/api/heartbeat` respondió HTTP 200.
- La app-image final se generó en `dist\phase2-final-distribution\CODES` (aprox. 169,1 MB), con `CODES.exe` y `runtime\lib\modules`. Se debe distribuir la carpeta completa; el `.exe` no es autónomo.
- La API mantuvo el identificador de proveedor `groq-whisper`, pero `transcriptionConfigured` fue `false` por no disponer de una clave Groq. No se envió audio a Groq; la transcripción externa real sigue sin verificarse.

### Estado por prioridad

| Prioridad | Estado | Evidencia o limitación |
|---|---|---|
| 1. Clasificador | COMPLETADO | Categorías y variaciones cubiertas por `ClassifierTest`. |
| 2. Contexto | COMPLETADO | Casos contextuales y negaciones cubiertos por pruebas. |
| 3. Gravedad por institución | COMPLETADO | Recomendaciones con severidad/rol por institución probadas. |
| 4. Routing | COMPLETADO | HTTP valida instituciones recomendadas; no se efectúa despacho externo. |
| 5. Interconectividad/idempotencia | COMPLETADO | Actualización del mismo ID, UUID repetido y contenido duplicado probados. |
| 6. Identificadores internos en inglés | PENDIENTE | Renombrado y barrido focalizado realizados; falta certificar todos los artefactos heredados/alias internos. |
| 7. Validación HTTP | COMPLETADO | 46 comprobaciones en la app-image final. |
| 8. Pruebas | COMPLETADO | 65 pruebas Maven; scripts 15/15 y matriz HTTP 46/46 de la ejecución registrada anteriormente. |
| 9. Mapa/geolocalización | PENDIENTE | Leaflet y el flujo con coordenadas simuladas funcionan; faltan permiso real y solicitudes externas a Esri/Geoapify/OSRM. |
| 10. Groq Whisper | PENDIENTE | Se conserva el proveedor y pasan pruebas unitarias; falta clave autorizada para probar audio real. |
| 11. App-image | COMPLETADO | Ejecutable con runtime incluido; distribuir toda la carpeta, no solo el `.exe`. |

**Estado de Fase 2: PENDIENTE.** Quedan pendientes el barrido integral de identificadores, la prueba de geolocalización con permiso real y una transcripción real con Groq. No se declaran como completadas por pruebas simuladas o no ejecutadas.

## Riesgos pendientes

- Definir HTTPS/reverse proxy, segmentación de red, política de CORS y administración de secretos antes de cualquier acceso desde una LAN o Internet.
- Los secretos locales persisten en texto plano; restringir el acceso al directorio de datos y cifrar sus respaldos.
- Definir retención y restauración probada de llamadas, base de datos y audios, junto con monitoreo externo y acuerdos para proveedores.
- Formalizar migraciones de base de datos y realizar pruebas de integración y seguridad en el entorno de despliegue.

## Verificación de esta entrega

- `tools/tests/test_audit_fixes.ps1`: 15 aprobadas, 0 fallidas, 0 omitidas.
- `tools/tests/test_http_validation.ps1`: 46 aprobadas, 0 fallidas.
- `tools/package_codes_windows.ps1`: `clean verify` aprobado; 65 pruebas y app-image actualizada en `dist\CODES`.
- La app-image se inició desde su carpeta empaquetada con datos temporales; login, registro, mapa Leaflet y flujo de ubicación simulada se probaron en navegador.
- En la revalidación CAPTCHA, un login de prueba con usuario inexistente respondió HTTP 401 tras completar el widget; no se probó la contraseña de una cuenta real.
- Revalidación del launcher (2026-11-03): `mvn clean verify` aprobó **68 pruebas**; la app-image abre el navegador al recibir `ApplicationReadyEvent`, y usa el manejador de URL de Windows como fallback si `Desktop.browse()` no está disponible. La nueva app-image en `dist\CODES` arrancó y `/api/health` respondió HTTP 200; el log confirmó el inicio del manejador del navegador.
- Estado de conexión de la interfaz (2026-11-03): el indicador se alimenta ahora de la respuesta HTTP del latido público, no del resultado de las solicitudes de colas autenticadas. `mvn clean verify` aprobó **68 pruebas**, 0 fallidas, errores u omitidas; la app-image arrancó, entregó HTTP 200 y el navegador mostró «Operativo» al completarse el latido.
- Groq Whisper real no se probó: falta una clave Groq; el test confirmó que no se envió audio externo.
- Toolchain: OpenJDK 21.0.12.1 y Maven 3.9.9.

Estos resultados verifican las pruebas y el empaquetado en este entorno; no constituyen certificación de seguridad ni validación de operación crítica.
