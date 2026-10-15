# Operacion local de CODES

Guia de instalacion, respaldo, seguridad y preparacion para una futura instalacion en LAN. CODES sigue funcionando localmente y no requiere dominio, HTTPS publico ni una base de datos externa.

## Inicio en Windows

Para desarrollo y llamadas en vivo, el flujo basico es:

```text
CODES.bat -> 1. Iniciar CODES
```

Ese modo busca o instala Java 21 y Maven, actualiza el diccionario de calles cuando corresponde, inicia Spring Boot y trata de iniciar el ASR local.

La aplicacion queda disponible en `http://localhost:8000` y el ASR local en `ws://localhost:6006`.

## Mapa y servicios cartograficos

Leaflet y sus recursos se sirven desde el JAR. El mapa base predeterminado es **Esri World Imagery** (satélite); las rutas usan OSRM Demo y la geocodificación usa Nominatim con Photon como alternativa. No hace falta cuenta, tarjeta ni API key. Son servicios públicos que dependen de Internet y no ofrecen SLA ni disponibilidad garantizada.

- La atribución de Esri se muestra en el mapa: `© Esri — Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community`.
- Nominatim público limita el uso, requiere identificación de la aplicación y no permite autocomplete. CODES respeta un intervalo mínimo de 1,1 segundos entre solicitudes Nominatim y cachea resultados.
- Las direcciones de incidentes se envían a Nominatim y pueden enviarse a Photon; al calcular una ruta, las coordenadas del operador y del incidente se envían a OSRM. La interfaz indica este intercambio.
- No uses esos servicios públicos con datos reales sensibles sin aprobación y revisión de privacidad, políticas y normativa aplicable. Para operación crítica, contrata un proveedor con SLA o aloja servicios propios.
- Esri World Imagery se sirve desde [ArcGIS Online](https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer); consulta los [términos de Esri](https://www.esri.com/en-us/legal/terms/full-master-agreement).
- Los endpoints se pueden cambiar con `CODES_MAP_TILES_URL`, `CODES_ROUTING_URL`, `CODES_NOMINATIM_BASE_URL` y `CODES_PHOTON_BASE_URL`. Las URLs de teselas deben ser plantillas compatibles con Leaflet; los endpoints personalizados deben respetar el formato esperado por Leaflet o OSRM.

El diccionario `data/calles_chile.txt` solo ayuda a corregir nombres de calles; no contiene coordenadas y no sustituye un geocodificador ni las teselas del mapa. Ver [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) para atribucion y condiciones de los datos OSM.

## Respaldo y restauracion

CODES.bat permite crear un respaldo local. Tambien se puede ejecutar:

```powershell
.\tools\backup_codes.ps1 -IncluirLogs
.\tools\restore_codes.ps1 -Respaldo .\backups\codes_YYYYMMDD_HHMMSS
```

CODES debe estar detenido para respaldar o restaurar la base H2. El respaldo incluye base de datos, audios, cache, diccionario, logs y secretos. Es informacion sensible y debe protegerse.

La perdida de `CODES_ENCRYPT_KEY` impide recuperar audios cifrados.

## Seguridad y evidencia

La aplicacion implementa JWT, BCrypt, roles, aislamiento institucional, invalidacion de sesiones, CORS restringido, limites de intentos, auditoria y cifrado AES-256-GCM. Turnstile es opcional y viene desactivado; si se expone a Internet, debe habilitarse o sustituirse por protecciones anti-abuso equivalentes en el proxy/WAF.

Antes de una entrega o instalacion operativa se debe comprobar:

- Peticion sin JWT: responde `401` o `403`.
- Usuario desactivado: no puede usar su JWT anterior.
- Operador: solo ve casos propios o instituciones sugeridas.
- Operador: no cierra casos ajenos o no asignados.
- Supervisor: puede cerrar casos de su institucion.
- Administrador: puede supervisar todas las instituciones.
- Logout, cambio de clave y cierre forzado invalidan JWT anteriores.
- Audio con tipo o extension no permitidos: se rechaza.
- Eventos operativos: quedan en `logs\auditoria.log`.

El flujo principal que debe demostrarse es:

```text
login -> llamada -> ASR o transcripcion manual -> prioridad -> direccion
-> geocodificacion -> instituciones sugeridas -> sin asignar -> en curso -> cerrada
```

## Diagnostico

El estado publico minimo es:

```text
GET /api/health
```

El diagnostico detallado para administradores es:

```text
GET  /api/admin/status
POST /api/admin/selftest
```

El autochequeo revisa base de datos, disco, espacio libre, ASR y cache de geocodificacion. ASR y geocodificacion pueden quedar en modo degradado sin impedir la operacion manual.

## Limites actuales

CODES distribuye alertas simultaneamente dentro de sus colas locales segun las instituciones sugeridas. Todavia no envia alertas a sistemas externos de Carabineros, SAMU o Bomberos.

## Siguiente etapa: web real y dispositivos

La version actual no debe publicarse directamente en Internet. Para habilitar acceso desde telefonos o computadores de otras redes, seguir este orden:

1. Definir responsable operativo, jurisdiccion, retencion de audios/transcripciones, consentimiento, control de acceso y procedimiento ante incidentes. No usar datos reales hasta acordar estas reglas.
2. Preparar un servidor Linux o Windows con dominio, DNS, firewall y almacenamiento cifrado. Mantener la aplicacion y H2 en almacenamiento persistente; para varias instancias o alta disponibilidad, migrar a PostgreSQL y agregar migraciones versionadas.
3. Colocar Caddy o Nginx delante de Spring Boot, emitir HTTPS y reenviar solo el trafico web necesario. Mantener el puerto 8000 y el ASR fuera de Internet; permitir acceso a Spring Boot solo desde el proxy.
4. Probar login, captura de microfono y geolocalizacion desde los dispositivos reales. Los navegadores requieren HTTPS (salvo `localhost`) para microfono y geolocalizacion.
5. El ASR actual escucha localmente y su WebSocket no tiene autenticacion. No publicarlo ni reenviarlo a Internet todavia. Antes de dispositivos remotos, implementar autenticacion de corta duracion, limites de conexiones y WSS en el proxy/servicio; entonces configurar `CODES_ASR_WEBSOCKET_URL=wss://...`.
6. Para operacion critica, contratar un proveedor cartografico con SLA que cubra teselas, rutas y geocodificacion; configurar los endpoints con variables de entorno y guardar las claves en un gestor de secretos.
7. Configurar secretos fuera del repositorio, CORS con el origen HTTPS exacto, Turnstile o proteccion anti-abuso equivalente, SMTP propio si se requiere recuperacion de clave y monitorizacion/alertas.
8. Automatizar respaldos cifrados de BD, audios, claves y datos cartograficos, y ensayar restauracion. Definir retencion y borrado antes de guardar llamadas reales.
9. Hacer pruebas de seguridad, concurrencia, navegadores y perdida de red; ejecutar un piloto cerrado con operadores antes de integrar o comunicar alertas a servicios oficiales.

La solucion actual no integra despachos oficiales y el clasificador automatico no reemplaza el criterio de un operador. HTTPS/WSS, autenticacion del ASR, PostgreSQL, migraciones, backups programados, monitoreo e integraciones oficiales siguen siendo trabajo de produccion.
